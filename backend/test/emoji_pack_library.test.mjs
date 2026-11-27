import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync, readdirSync } from "node:fs";
import { Hono } from "hono";
import { emojiRoutes, emojiAdminRoutes } from "../src/routes/emoji.ts";

function isolatedD1() {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec("PRAGMA foreign_keys = ON");
  const migrationsDir = new URL("../migrations/", import.meta.url);
  const files = readdirSync(migrationsDir).filter((x) => x.endsWith(".sql")).sort();
  for (const name of files) {
    sqlite.exec(readFileSync(new URL("../migrations/" + name, import.meta.url), "utf8"));
  }
  return {
    sqlite,
    d1: {
      prepare(query) {
        function makeStmt(boundValues = []) {
          return {
            bind(...values) {
              return makeStmt(values);
            },
            async run() {
              const info = sqlite.prepare(query).run(...boundValues);
              return { meta: { changes: info.changes } };
            },
            runSync() {
              const info = sqlite.prepare(query).run(...boundValues);
              return { meta: { changes: info.changes } };
            },
            async first() {
              return sqlite.prepare(query).get(...boundValues) || null;
            },
            async all() {
              return { results: sqlite.prepare(query).all(...boundValues) };
            },
          };
        }
        return makeStmt();
      },
      async batch(statements) {
        sqlite.exec("BEGIN");
        try {
          const results = [];
          for (const statement of statements) {
            results.push(statement.runSync());
          }
          sqlite.exec("COMMIT");
          return results;
        } catch (error) {
          sqlite.exec("ROLLBACK");
          throw error;
        }
      },
    },
  };
}

function createMemoryR2() {
  const objects = new Map();
  return {
    objects,
    async put(key, value, options = {}) {
      const bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
      objects.set(key, { bytes, contentType: options.httpMetadata?.contentType || "application/octet-stream" });
    },
    async get(key) {
      const entry = objects.get(key);
      if (!entry) return null;
      return {
        httpMetadata: { contentType: entry.contentType },
        async arrayBuffer() {
          return entry.bytes.buffer.slice(entry.bytes.byteOffset, entry.bytes.byteOffset + entry.bytes.byteLength);
        },
      };
    },
    async delete(keys) {
      for (const k of Array.isArray(keys) ? keys : [keys]) {
        objects.delete(k);
      }
    },
  };
}

function createApp(d1, user, r2Bucket = null) {
  const app = new Hono();
  app.use("*", async (c, next) => {
    c.env = {
      DB: d1,
      ...(r2Bucket ? { EMOJI_BUCKET: r2Bucket } : {}),
      FIREBASE_PROJECT_ID: "eve-fb0e3",
      TELEGRAM_BOT_TOKEN: "test-bot-secret",
      TELEGRAM_MEDIA_CHANNEL_ID: "-1001234567890",
    };
    c.set("user", user);
    await next();
  });
  app.route("/api/admin/emoji", emojiAdminRoutes);
  app.route("/api/emoji", emojiRoutes);
  return app;
}

const adminUser = { uid: "admin-1", email: "owner@test.com", displayName: "Owner", isAdmin: true };
const studentUser = { uid: "student-1", email: "student@test.com", displayName: "Student", isAdmin: false };

function seedOwner(sqlite) {
  const now = Date.now();
  sqlite.prepare("INSERT OR IGNORE INTO admins (email, created_at) VALUES (?, ?)").run(adminUser.email, now);
  sqlite
    .prepare("INSERT OR IGNORE INTO admin_rights (email, rights, custom_title, updated_at) VALUES (?, ?, ?, ?)")
    .run(adminUser.email, JSON.stringify(["manage_content", "manage_premium"]), "Owner", now);
  sqlite.prepare("INSERT OR IGNORE INTO users (id, email, display_name, created_at) VALUES (?, ?, ?, ?)").run(
    studentUser.uid, studentUser.email, "Student", now
  );
}

test("Complete pack lifecycle: create, upload Lottie + WebM, list by type, manifest, R2 serve, Premium lock", async () => {
  const { sqlite, d1 } = isolatedD1();
  seedOwner(sqlite);
  const r2 = createMemoryR2();
  const adminApp = createApp(d1, adminUser, r2);
  const studentApp = createApp(d1, studentUser, r2);

  // Student cannot call admin endpoints
  const forbidden = await studentApp.request("/api/admin/emoji/packs", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name: "unauth", title: "Unauth" }),
  });
  assert.equal(forbidden.status, 403);

  // Admin creates a sticker pack (initially free + published)
  const createRes = await adminApp.request("/api/admin/emoji/packs", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      name: "study_stickers",
      title: "Study Stickers",
      packType: "sticker",
      category: "Study",
      featured: true,
      accessTier: "free",
      rightsStatus: "approved",
      creatorAttribution: "EVE Design Studio",
    }),
  });
  assert.equal(createRes.status, 201);
  const createdPack = (await createRes.json()).data;
  assert.equal(createdPack.packType, "sticker");

  // Upload a valid Lottie JSON item and a valid WebM video sticker into R2
  const lottieRes = await adminApp.request("/api/admin/emoji/upload", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ packId: createdPack.id, altEmoji: "📚", v: "5.7", layers: [] }),
  });
  assert.equal(lottieRes.status, 201);
  const lottieItem = (await lottieRes.json()).data;

  const webmBytes = new Uint8Array([0x1a, 0x45, 0xdf, 0xa3, 0x01, 0x02, 0x03, 0x04]);
  const webmRes = await adminApp.request(`/api/admin/emoji/upload?packId=${createdPack.id}&altEmoji=🎯`, {
    method: "POST",
    headers: { "Content-Type": "video/webm" },
    body: webmBytes,
  });
  assert.equal(webmRes.status, 201);
  const webmItem = (await webmRes.json()).data;
  assert.equal(webmItem.kind, "video");
  assert.equal(webmItem.fileFormat, "webm");
  assert.equal(r2.objects.size, 2);

  // Student lists sticker packs & fetches manifest
  const listRes = await studentApp.request("/api/emoji/packs?type=sticker&q=Study");
  assert.equal(listRes.status, 200);
  const listBody = await listRes.json();
  assert.equal(listBody.data.length, 1);
  assert.equal(listBody.data[0].itemCount, 2);
  assert.equal(listBody.data[0].isLockedForUser, false);
  assert.equal(listBody.data[0].previews.length, 2);

  const manifestRes = await studentApp.request(`/api/emoji/packs/${createdPack.id}/manifest`);
  assert.equal(manifestRes.status, 200);
  const manifest = (await manifestRes.json()).data;
  assert.equal(manifest.itemCount, 2);
  assert.ok(manifest.manifestSha256);

  // Serve WebM bytes from R2 via Worker asset endpoint with SHA-256 header
  const assetRes = await studentApp.request(`/api/emoji/assets/${webmItem.id}`);
  assert.equal(assetRes.status, 200);
  assert.equal(assetRes.headers.get("Content-Type"), "video/webm");
  assert.equal(assetRes.headers.get("X-Asset-Sha256"), webmItem.sha256);

  // Upgrade pack to EVE Premium -> student manifest and asset downloads are gated 403 until unlocked
  const lockRes = await adminApp.request(`/api/admin/emoji/packs/${createdPack.id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ accessTier: "premium" }),
  });
  assert.equal(lockRes.status, 200);

  const lockedManifest = await studentApp.request(`/api/emoji/packs/${createdPack.id}/manifest`);
  assert.equal(lockedManifest.status, 403);
  const lockedAsset = await studentApp.request(`/api/emoji/assets/${lottieItem.id}`);
  assert.equal(lockedAsset.status, 403);

  // Grant EVE Premium entitlement -> access restored
  const now = Date.now();
  sqlite
    .prepare(
      "INSERT INTO premium_entitlements (user_id, plan_id, plan_name, activated_at, is_lifetime, status, created_at, updated_at) VALUES (?, 'pro', 'EVE Premium', ?, 1, 'ACTIVE', ?, ?)"
    )
    .run(studentUser.uid, now, now, now);
  const unlockedManifest = await studentApp.request(`/api/emoji/packs/${createdPack.id}/manifest`);
  assert.equal(unlockedManifest.status, 200);

  sqlite.close();
});

test("Telegram pack full import supports TGS, WebM, WebP and blocks mirroring when redistribution rights are missing", async (t) => {
  const { sqlite, d1 } = isolatedD1();
  seedOwner(sqlite);
  const r2 = createMemoryR2();
  const adminApp = createApp(d1, adminUser, r2);
  const lottieJson = JSON.stringify({ v: "5.7", layers: [] });

  t.mock.method(globalThis, "fetch", async (url) => {
    if (url.includes("/getStickerSet")) {
      return Response.json({
        ok: true,
        result: {
          title: "EVE Mascot Pack",
          sticker_type: "regular",
          stickers: [
            { file_id: "f_tgs", is_animated: true, is_video: false, emoji: "🔥" },
            { file_id: "f_webm", is_animated: false, is_video: true, emoji: "⚡" },
            { file_id: "f_webp", is_animated: false, is_video: false, emoji: "📘" },
          ],
        },
      });
    }
    if (url.includes("file_id=f_tgs")) return Response.json({ ok: true, result: { file_path: "stickers/a.tgs" } });
    if (url.includes("file_id=f_webm")) return Response.json({ ok: true, result: { file_path: "stickers/b.webm" } });
    if (url.includes("file_id=f_webp")) return Response.json({ ok: true, result: { file_path: "stickers/c.webp" } });
    if (url.includes("stickers/a.tgs")) {
      return new Response(new Blob([lottieJson]).stream().pipeThrough(new CompressionStream("gzip")));
    }
    if (url.includes("stickers/b.webm")) {
      return new Response(new Uint8Array([0x1a, 0x45, 0xdf, 0xa3, 0x09, 0x0a]));
    }
    if (url.includes("stickers/c.webp")) {
      return new Response(new Uint8Array([0x52, 0x49, 0x46, 0x46, 1, 2, 3, 4, 0x57, 0x45, 0x42, 0x50]));
    }
    return new Response("{}", { status: 404 });
  });

  // 1. Rights-restricted pack must NOT copy assets to R2
  const blockedRes = await adminApp.request("/api/admin/emoji/import/telegram", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      packName: "restricted_pack",
      rightsBasis: "no_redistribution_rights",
      importAll: true,
    }),
  });
  assert.equal(blockedRes.status, 200);
  const blockedBody = await blockedRes.json();
  assert.equal(blockedBody.data.redistributionBlocked, true);
  assert.equal(blockedBody.data.rightsStatus, "limited_external");
  assert.equal(r2.objects.size, 0);

  // 2. Rights-approved pack imports all 3 items (tgs + webm + webp) into R2 and D1
  const okRes = await adminApp.request("/api/admin/emoji/import/telegram", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      packName: "eve_mascot_pack",
      rightsBasis: "creator_permitted",
      licenseEvidence: "Signed creator grant #2026-11",
      importAll: true,
    }),
  });
  assert.equal(okRes.status, 200);
  const okBody = await okRes.json();
  assert.equal(okBody.data.packType, "sticker");
  assert.equal(okBody.data.totalStickers, 3);
  assert.equal(okBody.data.importedCount, 3);
  assert.equal(okBody.data.failedCount, 0);
  assert.equal(okBody.data.importStatus, "complete");
  assert.equal(r2.objects.size, 3);
  assert.equal(JSON.stringify(okBody).includes("test-bot-secret"), false);

  sqlite.close();
});
