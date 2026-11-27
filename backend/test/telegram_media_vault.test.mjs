import test from "node:test";
import assert from "node:assert/strict";
import { Hono } from "hono";
import { HARDCODED_ADMIN_EMAILS } from "../src/auth.ts";
import { bannerRoutes } from "../src/routes/banners.ts";
import { examRoutes } from "../src/routes/exams.ts";
import {
  MAX_TELEGRAM_MEDIA_BYTES,
  TelegramMediaVault,
  TelegramMediaVaultError,
  parseTelegramStorageRef,
} from "../src/telegramMediaVault.ts";
import { isolatedD1 } from "./support/sqliteD1.mjs";

function makeEnv(extra = {}) {
  return {
    FIREBASE_PROJECT_ID: "eve-fb0e3",
    TELEGRAM_BOT_TOKEN: "123456:test-bot-token",
    TELEGRAM_MEDIA_CHANNEL_ID: "-1009876543210",
    ...extra,
  };
}

test("TelegramMediaVault.uploadFile sends sendDocument FormData and returns tg:<msgId>:<fileId>", async (t) => {
  const calls = [];
  t.mock.method(globalThis, "fetch", async (url, init) => {
    calls.push({ url: String(url), init });
    return Response.json({
      ok: true,
      result: {
        message_id: 812,
        document: { file_id: "BQACAgUAAxkBAAIDMm" },
      },
    });
  });

  const vault = new TelegramMediaVault(makeEnv());
  assert.equal(vault.isConfigured(), true);

  const bytes = new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x37]);
  const ref = await vault.uploadFile("syllabi/notes.pdf", bytes, "application/pdf");
  assert.equal(ref, "tg:812:BQACAgUAAxkBAAIDMm");
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, "https://api.telegram.org/bot123456:test-bot-token/sendDocument");
  assert.equal(calls[0].init.method, "POST");
  assert.ok(calls[0].init.body instanceof FormData);
  assert.equal(calls[0].init.body.get("chat_id"), "-1009876543210");
  assert.equal(calls[0].init.body.get("caption"), "syllabi/notes.pdf");
  assert.equal(calls[0].init.body.get("disable_notification"), "true");

  // Supports video/animation/photo reclassification from Telegram response
  t.mock.method(globalThis, "fetch", async () =>
    Response.json({
      ok: true,
      result: {
        message_id: 901,
        photo: [{ file_id: "small" }, { file_id: "largest_photo_id" }],
      },
    })
  );
  const photoRef = await vault.uploadFile("banners/pic.jpg", new Uint8Array([1, 2, 3]), "image/jpeg");
  assert.equal(photoRef, "tg:901:largest_photo_id");
});

test("TelegramMediaVault.getObjectBytes resolves getFile and downloads bytes with accurate MIME type", async (t) => {
  const expected = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x11, 0x22]);
  const seenUrls = [];
  t.mock.method(globalThis, "fetch", async (url) => {
    const u = String(url);
    seenUrls.push(u);
    if (u.includes("/getFile?")) {
      return Response.json({
        ok: true,
        result: { file_path: "documents/file_42.jpg" },
      });
    }
    if (u.includes("/file/bot123456:test-bot-token/documents/file_42.jpg")) {
      return new Response(expected, {
        status: 200,
        headers: { "content-type": "application/octet-stream" },
      });
    }
    return new Response("Not found", { status: 404 });
  });

  const vault = new TelegramMediaVault(makeEnv());
  const asset = await vault.getObjectBytes("tg:812:BQACAgUAAxkBAAIDMm");
  assert.ok(asset);
  assert.deepEqual(asset.body, expected);
  assert.equal(asset.contentType, "image/jpeg");
  assert.equal(seenUrls.length, 2);

  // Supports tg:<fileId> without messageId and returns null when file missing
  t.mock.method(globalThis, "fetch", async () =>
    Response.json({ ok: false, description: "Bad Request: invalid file_id" }, { status: 400 })
  );
  assert.equal(await vault.getObjectBytes("tg:missing_id"), null);
  assert.equal(await vault.getObjectBytes("not-a-tg-ref"), null);
});

test("TelegramMediaVault.deleteFile calls deleteMessage and treats 'message to delete not found' idempotently", async (t) => {
  const deletedBodies = [];
  t.mock.method(globalThis, "fetch", async (url, init) => {
    assert.equal(String(url), "https://api.telegram.org/bot123456:test-bot-token/deleteMessage");
    deletedBodies.push(JSON.parse(init.body));
    if (deletedBodies.length === 1) {
      return Response.json({ ok: true, result: true });
    }
    return Response.json({ ok: false, description: "Bad Request: message to delete not found" }, { status: 400 });
  });

  const vault = new TelegramMediaVault(makeEnv());
  await vault.deleteFiles(["tg:812:fileA", "tg:813:fileB", "tg:812:fileA"]);
  assert.equal(deletedBodies.length, 2);
  assert.deepEqual(deletedBodies[0], { chat_id: "-1009876543210", message_id: 812 });
  assert.deepEqual(deletedBodies[1], { chat_id: "-1009876543210", message_id: 813 });
});

test("TelegramMediaVault enforces 20 MB upload cap and reports missing credentials clearly", async () => {
  const missingVault = new TelegramMediaVault(makeEnv({ TELEGRAM_BOT_TOKEN: "", TELEGRAM_MEDIA_CHANNEL_ID: "" }));
  assert.equal(missingVault.isConfigured(), false);
  await assert.rejects(
    () => missingVault.uploadFile("a.jpg", new Uint8Array([1, 2, 3]), "image/jpeg"),
    /TELEGRAM_BOT_TOKEN and TELEGRAM_MEDIA_CHANNEL_ID/
  );

  const vault = new TelegramMediaVault(makeEnv());
  const oversized = new Uint8Array(MAX_TELEGRAM_MEDIA_BYTES + 1);
  await assert.rejects(
    () => vault.uploadFile("huge.mp4", oversized, "video/mp4"),
    (err) => err instanceof TelegramMediaVaultError && err.status === 400 && /20 MB/i.test(err.message)
  );
  assert.deepEqual(parseTelegramStorageRef("tg:55:FILE_XYZ"), { messageId: "55", fileId: "FILE_XYZ" });
});

test("Public media proxies (/api/exams/:id/syllabus & /api/banners/:id/media) cache responses via Cloudflare Edge Cache", async (t) => {
  const { sqlite, d1 } = isolatedD1();
  t.after(() => sqlite.close());
  sqlite.exec(`
    INSERT INTO exams (id, exam_name, syllabus_url, syllabus_file_name)
    VALUES ('ex-1', 'UPSC', '/api/exams/ex-1/syllabus?k=tg%3A701%3APDF_1', 'upsc_syllabus.pdf');
    INSERT INTO home_banners (id, image_url, storage_path, uploaded_at, media_type)
    VALUES ('ban-1', '/api/banners/ban-1/media?v=1', 'tg:702:BANNER_1', 1700000000000, 'image');
  `);

  const edgeStore = new Map();
  const originalCaches = globalThis.caches;
  globalThis.caches = {
    default: {
      async match(req) {
        const hit = edgeStore.get(req.url);
        return hit ? hit.clone() : undefined;
      },
      async put(req, res) {
        edgeStore.set(req.url, res.clone());
      },
    },
  };
  t.after(() => {
    globalThis.caches = originalCaches;
  });

  let telegramFetchCalls = 0;
  const pdfBytes = new TextEncoder().encode("%PDF-1.7\ntrailer\n%%EOF");
  const imgBytes = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x01, 0x02]);
  t.mock.method(globalThis, "fetch", async (url) => {
    telegramFetchCalls++;
    const u = String(url);
    if (u.includes("file_id=PDF_1")) return Response.json({ ok: true, result: { file_path: "docs/upsc.pdf" } });
    if (u.includes("docs/upsc.pdf")) return new Response(pdfBytes);
    if (u.includes("file_id=BANNER_1")) return Response.json({ ok: true, result: { file_path: "docs/banner.jpg" } });
    if (u.includes("docs/banner.jpg")) return new Response(imgBytes);
    return new Response("not found", { status: 404 });
  });

  const app = new Hono();
  app.use("*", async (c, next) => {
    c.set("user", { uid: "admin-1", email: [...HARDCODED_ADMIN_EMAILS][0], displayName: "Owner", isAdmin: true });
    await next();
  });
  app.route("/api/exams", examRoutes);
  app.route("/api/banners", bannerRoutes);

  const env = makeEnv({ DB: d1 });

  // First syllabus GET fetches from Telegram (getFile + file) and caches in edgeStore
  const s1 = await app.request("http://localhost/api/exams/ex-1/syllabus?k=tg%3A701%3APDF_1", undefined, env);
  assert.equal(s1.status, 200);
  assert.equal(s1.headers.get("Content-Type"), "application/pdf");
  assert.equal(s1.headers.get("Cache-Control"), "public, max-age=86400");
  assert.equal(telegramFetchCalls, 2);

  // Second syllabus GET is served from Cloudflare Edge Cache with 0 Telegram API calls
  const s2 = await app.request("http://localhost/api/exams/ex-1/syllabus?k=tg%3A701%3APDF_1", undefined, env);
  assert.equal(s2.status, 200);
  assert.equal(telegramFetchCalls, 2);

  // Banner media GET fetches once then hits cache cleanly
  const b1 = await app.request("http://localhost/api/banners/ban-1/media?v=1", undefined, env);
  assert.equal(b1.status, 200);
  assert.equal(b1.headers.get("Content-Type"), "image/jpeg");
  assert.equal(telegramFetchCalls, 4);
  const b2 = await app.request("http://localhost/api/banners/ban-1/media?v=1", undefined, env);
  assert.equal(b2.status, 200);
  assert.equal(telegramFetchCalls, 4);
});
