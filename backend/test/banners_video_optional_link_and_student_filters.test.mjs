import test from "node:test";
import assert from "node:assert/strict";
import { Hono } from "hono";
import { isolatedD1 } from "./support/sqliteD1.mjs";
import { bannerRoutes } from "../src/routes/banners.ts";
import { adminRoutes } from "../src/routes/admin.ts";

function createMockR2Bucket() {
  const objects = new Map();
  return {
    objects,
    async put(key, body, opts = {}) {
      const buf = body instanceof ArrayBuffer ? new Uint8Array(body) : new Uint8Array(body);
      objects.set(key, { bytes: buf, httpMetadata: opts.httpMetadata || {} });
    },
    async get(key) {
      const entry = objects.get(key);
      if (!entry) return null;
      return {
        httpMetadata: entry.httpMetadata,
        async arrayBuffer() {
          return entry.bytes.buffer.slice(entry.bytes.byteOffset, entry.bytes.byteOffset + entry.bytes.byteLength);
        },
      };
    },
    async delete(keys) {
      const list = Array.isArray(keys) ? keys : [keys];
      for (const k of list) objects.delete(k);
    },
  };
}

function buildApp(d1, user, r2Bucket = null) {
  const app = new Hono();
  app.use("*", async (c, next) => {
    c.env = {
      DB: d1,
      ...(r2Bucket ? { EMOJI_BUCKET: r2Bucket } : {}),
    };
    if (user) {
      c.set("user", user);
    }
    await next();
  });
  app.route("/api/banners", bannerRoutes);
  app.route("/api/admin", adminRoutes);
  return app;
}

test("Banners support optional empty links, image & MP4 video upload to R2, manage listing, status toggle, and pre-0034 compatibility", async () => {
  const { sqlite, d1 } = isolatedD1();
  sqlite.prepare("INSERT OR IGNORE INTO admins (email, created_at) VALUES (?, ?)").run("pronlike9@gmail.com", 1000);
  const r2 = createMockR2Bucket();
  const ownerApp = buildApp(d1, { uid: "owner-1", email: "pronlike9@gmail.com", isAdmin: true }, r2);
  const publicApp = buildApp(d1, null, r2);

  // 1. Image banner with EMPTY optional linkUrl & linkLabel succeeds
  const pngBytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4]);
  const imgRes = await ownerApp.request("http://localhost/api/banners", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ base64: Buffer.from(pngBytes).toString("base64"), mimeType: "image/png", linkUrl: "", linkLabel: "" }),
  });
  assert.equal(imgRes.status, 201);
  const imgBody = await imgRes.json();
  assert.equal(imgBody.data.mediaType, "image");
  assert.equal(imgBody.data.linkUrl, "");
  assert.equal(imgBody.data.linkLabel, "");

  // 2. MP4 video banner upload (ftyp box at offset 4) with caption & URL
  const mp4Bytes = new Uint8Array([0, 0, 0, 20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d, 0, 0, 2, 0]);
  const vidRes = await ownerApp.request("http://localhost/api/banners", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      base64: Buffer.from(mp4Bytes).toString("base64"),
      mimeType: "video/mp4",
      linkUrl: "https://eve.example/promo",
      linkLabel: "Watch Intro",
    }),
  });
  assert.equal(vidRes.status, 201);
  const vidBody = await vidRes.json();
  assert.equal(vidBody.data.mediaType, "video");
  assert.equal(vidBody.data.linkUrl, "https://eve.example/promo");

  // 3. Public GET /api/banners/:id/media streams video bytes from R2
  const mediaRes = await publicApp.request(`http://localhost${vidBody.data.imageUrl}`);
  assert.equal(mediaRes.status, 200);
  assert.equal(mediaRes.headers.get("content-type"), "video/mp4");

  // 4. Hide video banner via PUT /api/banners/:id/status -> excluded from public GET /api/banners, visible in /manage
  const hideRes = await ownerApp.request(`http://localhost/api/banners/${vidBody.data.id}/status`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ active: false }),
  });
  assert.equal(hideRes.status, 200);
  const pubList = await (await publicApp.request("http://localhost/api/banners")).json();
  assert.equal(pubList.data.length, 1);
  const mgrList = await (await ownerApp.request("http://localhost/api/banners/manage")).json();
  assert.equal(mgrList.data.length, 2);

  // 5. Delete video banner cleans R2 object
  const delRes = await ownerApp.request(`http://localhost/api/banners/${vidBody.data.id}`, { method: "DELETE" });
  assert.equal(delRes.status, 200);
  assert.equal(r2.objects.size, 1);

  // 6. Pre-0034 table compatibility: without media_type column, POST & GET still succeed
  sqlite.exec("ALTER TABLE home_banners DROP COLUMN media_type");
  const legacyPost = await ownerApp.request("http://localhost/api/banners", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ base64: Buffer.from(mp4Bytes).toString("base64"), mimeType: "video/mp4" }),
  });
  assert.equal(legacyPost.status, 201);
  const legacyPosted = await legacyPost.json();
  assert.equal(legacyPosted.data.mediaType, "video");
  const legacyList = await (await publicApp.request("http://localhost/api/banners")).json();
  assert.equal(legacyList.data.find((b) => b.id === legacyPosted.data.id)?.mediaType, "video");
});

test("GET /api/admin/users supports summary stats and online/premium/banned quick filters", async () => {
  const { sqlite, d1 } = isolatedD1();
  sqlite.prepare("INSERT OR IGNORE INTO admins (email, created_at) VALUES (?, ?)").run("pronlike9@gmail.com", 1000);

  const now = Date.now();
  const insertUser = sqlite.prepare(
    "INSERT INTO users (id, email, display_name, created_at, last_active, disabled) VALUES (?, ?, ?, ?, ?, ?)"
  );
  insertUser.run("u-online-prem", "onprem@example.com", "Aarav", now - 100000, now - 30_000, 0);
  insertUser.run("u-offline", "off@example.com", "Neha", now - 500000, now - 3600_000, 0);
  insertUser.run("u-banned", "ban@example.com", "Vikram", now - 500000, now - 7200_000, 1);

  sqlite
    .prepare(
      `INSERT INTO premium_entitlements (user_id, plan_id, plan_name, activated_at, expires_at, is_lifetime, status, created_at, updated_at)
       VALUES (?, 'default', 'EVE Premium', ?, ?, 0, 'ACTIVE', ?, ?)`
    )
    .run("u-online-prem", now - 10000, now + 86400_000, now - 10000, now - 10000);

  const ownerApp = buildApp(d1, { uid: "owner-1", email: "pronlike9@gmail.com", isAdmin: true });

  const allRes = await (await ownerApp.request("http://localhost/api/admin/users")).json();
  assert.equal(allRes.summary.total, 3);
  assert.equal(allRes.summary.online, 1);
  assert.equal(allRes.summary.premium, 1);
  assert.equal(allRes.summary.banned, 1);

  const onlineRes = await (await ownerApp.request("http://localhost/api/admin/users?filter=online")).json();
  assert.equal(onlineRes.data.length, 1);
  assert.equal(onlineRes.data[0].id, "u-online-prem");
  assert.equal(onlineRes.data[0].isOnline, true);
  assert.equal(onlineRes.data[0].isPremium, true);

  const premRes = await (await ownerApp.request("http://localhost/api/admin/users?filter=premium")).json();
  assert.equal(premRes.data.length, 1);
  assert.equal(premRes.data[0].id, "u-online-prem");

  const bannedRes = await (await ownerApp.request("http://localhost/api/admin/users?filter=banned")).json();
  assert.equal(bannedRes.data.length, 1);
  assert.equal(bannedRes.data[0].id, "u-banned");
});
