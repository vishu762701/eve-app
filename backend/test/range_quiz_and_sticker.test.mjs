import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readdirSync, readFileSync } from "node:fs";
import { Hono } from "hono";
import { bannerRoutes } from "../src/routes/banners.ts";
import { pollRoutes } from "../src/routes/polls.ts";
import { broadcastRoutes } from "../src/routes/broadcasts.ts";
import { isolatedD1 } from "./support/sqliteD1.mjs";

function wrapSqliteAsD1(sqlite) {
  return {
    prepare(sql) {
      const make = (values = []) => ({
        bind(...args) { return make(args); },
        execute() { const r = sqlite.prepare(sql).run(...values); return { meta: { changes: r.changes } }; },
        async run() { return this.execute(); },
        async first() { return sqlite.prepare(sql).get(...values) || null; },
        async all() { return { results: sqlite.prepare(sql).all(...values) }; },
      });
      return make();
    },
    async batch(statements) {
      sqlite.exec("BEGIN");
      try {
        const result = statements.map((s) => s.execute());
        sqlite.exec("COMMIT");
        return result;
      } catch (e) {
        sqlite.exec("ROLLBACK");
        throw e;
      }
    },
  };
}

test("GET /api/banners/:id/media supports Accept-Ranges, HTTP 206 Partial Content, and 416", async () => {
  const { sqlite, d1 } = isolatedD1();
  const originalFetch = globalThis.fetch;
  const videoBytes = new Uint8Array(64);
  for (let i = 0; i < videoBytes.length; i++) videoBytes[i] = i;

  try {
    sqlite.prepare(
      "INSERT INTO home_banners (id, image_url, storage_path, order_index, uploaded_at, uploaded_by, active, media_type) VALUES (?,?,?,?,?,?,?,?)"
    ).run("vid-1", "/api/banners/vid-1/media", "banners/vid-1.mp4", 0, 1000, "admin@test", 1, "video");

    const r2Bucket = {
      async get(key) {
        if (key !== "banners/vid-1.mp4") return null;
        return {
          httpMetadata: { contentType: "video/mp4" },
          async arrayBuffer() { return videoBytes.buffer.slice(0); },
        };
      },
    };

    const app = new Hono();
    app.use("*", async (c, next) => {
      c.env = { DB: d1, EMOJI_BUCKET: r2Bucket };
      c.set("user", { uid: "student-1", email: "student@test", isAdmin: false });
      await next();
    });
    app.route("/api/banners", bannerRoutes);

    const fullRes = await app.request("/api/banners/vid-1/media");
    assert.equal(fullRes.status, 200);
    assert.equal(fullRes.headers.get("accept-ranges"), "bytes");
    assert.equal(fullRes.headers.get("content-length"), "64");

    const partRes = await app.request("/api/banners/vid-1/media", {
      headers: { Range: "bytes=10-19" },
    });
    assert.equal(partRes.status, 206);
    assert.equal(partRes.headers.get("accept-ranges"), "bytes");
    assert.equal(partRes.headers.get("content-range"), "bytes 10-19/64");
    assert.equal(partRes.headers.get("content-length"), "10");
    assert.deepEqual(Array.from(new Uint8Array(await partRes.arrayBuffer())), [10, 11, 12, 13, 14, 15, 16, 17, 18, 19]);

    const openEndRes = await app.request("/api/banners/vid-1/media", {
      headers: { Range: "bytes=60-" },
    });
    assert.equal(openEndRes.status, 206);
    assert.equal(openEndRes.headers.get("content-range"), "bytes 60-63/64");
    assert.deepEqual(Array.from(new Uint8Array(await openEndRes.arrayBuffer())), [60, 61, 62, 63]);

    const badRes = await app.request("/api/banners/vid-1/media", {
      headers: { Range: "bytes=100-120" },
    });
    assert.equal(badRes.status, 416);
    assert.equal(badRes.headers.get("content-range"), "bytes */64");
  } finally {
    globalThis.fetch = originalFetch;
    sqlite.close();
  }
});

test("Quiz creation, secrecy before vote, reveal on vote, and pre-migration PRAGMA fallback", async () => {
  const { sqlite, d1 } = isolatedD1();
  const adminEmail = "quiz-admin@example.test";
  sqlite.prepare("INSERT INTO admins (email, created_at) VALUES (?, ?)").run(adminEmail, 1);
  sqlite.prepare("INSERT INTO admin_rights (email, rights, updated_at) VALUES (?, ?, ?)")
    .run(adminEmail, JSON.stringify(["moderate_feedback"]), 1);

  const makeApp = (user) => {
    const app = new Hono();
    app.use("*", async (c, next) => {
      c.env = { DB: d1 };
      c.set("user", user);
      await next();
    });
    app.route("/api/polls", pollRoutes);
    return app;
  };

  try {
    const adminApp = makeApp({ uid: "admin-1", email: adminEmail, isAdmin: true });
    const studentApp = makeApp({ uid: "student-1", email: "s1@test", isAdmin: false });

    const createRes = await adminApp.request("/api/polls", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        question: "Article 21 relates to?",
        options: ["Equality", "Right to Life", "Property"],
        correctOptionIndex: 1,
        explanation: "Article 21 guarantees protection of life and personal liberty.",
      }),
    });
    assert.equal(createRes.status, 201);

    // Before voting: student sees quiz but NOT correctOptionIndex or explanation
    const beforeStudent = await (await studentApp.request("/api/polls/active")).json();
    assert.equal(beforeStudent.data.isQuiz, true);
    assert.equal(beforeStudent.data.correctOptionIndex, null);
    assert.equal(beforeStudent.data.isCorrect, null);
    assert.equal(beforeStudent.data.explanation, "");
    assert.equal(beforeStudent.data.userVote, null);

    // Admin sees answer & explanation immediately
    const beforeAdmin = await (await adminApp.request("/api/polls/active")).json();
    assert.equal(beforeAdmin.data.correctOptionIndex, 1);
    assert.match(beforeAdmin.data.explanation, /Article 21/);

    // Student votes option 1 (correct) -> revealed immediately
    const voteRes = await studentApp.request(`/api/polls/${beforeStudent.data.id}/vote`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ optionIndex: 1 }),
    });
    assert.equal(voteRes.status, 200);
    const voteBody = await voteRes.json();
    assert.equal(voteBody.data.isQuiz, true);
    assert.equal(voteBody.data.correctOptionIndex, 1);
    assert.equal(voteBody.data.isCorrect, true);
    assert.match(voteBody.data.explanation, /Article 21/);

    // Active endpoint now shows student's vote, isCorrect, correctOptionIndex & explanation
    const afterStudent = await (await studentApp.request("/api/polls/active")).json();
    assert.equal(afterStudent.data.userVote, 1);
    assert.equal(afterStudent.data.isCorrect, true);
    assert.equal(afterStudent.data.correctOptionIndex, 1);
    assert.match(afterStudent.data.explanation, /Article 21/);
  } finally {
    sqlite.close();
  }

  // Legacy pre-0037 schema without 0037 columns still succeeds via PRAGMA fallback
  const legacyDb = new DatabaseSync(":memory:");
  legacyDb.exec("PRAGMA foreign_keys=ON");
  const migrationsDir = new URL("../migrations/", import.meta.url);
  for (const file of readdirSync(migrationsDir).filter((f) => f.endsWith(".sql") && f < "0037").sort()) {
    legacyDb.exec(readFileSync(new URL(file, migrationsDir), "utf8"));
  }
  legacyDb.prepare("INSERT INTO admins (email, created_at) VALUES (?, ?)").run(adminEmail, 1);
  legacyDb.prepare("INSERT INTO admin_rights (email, rights, custom_title, updated_at) VALUES (?, ?, ?, ?)")
    .run(adminEmail, JSON.stringify(["moderate_feedback"]), "", 1);
  const legacyApp = new Hono();
  legacyApp.use("*", async (c, next) => {
    c.env = { DB: wrapSqliteAsD1(legacyDb) };
    c.set("user", { uid: "admin-1", email: adminEmail, isAdmin: true });
    await next();
  });
  legacyApp.route("/api/polls", pollRoutes);
  const legacyRes = await legacyApp.request("/api/polls", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question: "Legacy Q?", options: ["One", "Two"], correctOptionIndex: 0 }),
  });
  assert.equal(legacyRes.status, 201);
  legacyDb.close();
});

test("Broadcast animated sticker fields persist, validate, and serialize cleanly", async () => {
  const { sqlite, d1 } = isolatedD1();
  const adminEmail = "bcast-admin@example.test";
  sqlite.prepare("INSERT INTO admins (email, created_at) VALUES (?, ?)").run(adminEmail, 1);
  sqlite.prepare("INSERT INTO admin_rights (email, rights, updated_at) VALUES (?, ?, ?)")
    .run(adminEmail, JSON.stringify(["send_broadcasts"]), 1);

  const app = new Hono();
  app.use("*", async (c, next) => {
    c.env = { DB: d1 };
    c.set("user", { uid: "admin-1", email: adminEmail, isAdmin: true });
    await next();
  });
  app.route("/api/broadcasts", broadcastRoutes);

  try {
    const badRes = await app.request("/api/broadcasts", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        title: "Notice",
        message: "Body",
        stickerDocId: "not-a-number",
      }),
    });
    assert.equal(badRes.status, 400);

    const okRes = await app.request("/api/broadcasts", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        title: "Results Out",
        message: "Check your ranks [tg_emoji:5368324170671202286:🔥]",
        stickerDocId: "5368324170671202286",
        stickerAccessHash: "9876543210",
        stickerDcId: 2,
        stickerEmoji: "🎉",
      }),
    });
    assert.equal(okRes.status, 201);

    const listRes = await app.request("/api/broadcasts");
    assert.equal(listRes.status, 200);
    const listBody = await listRes.json();
    assert.equal(listBody.data.length, 1);
    assert.equal(listBody.data[0].stickerDocId, "5368324170671202286");
    assert.equal(listBody.data[0].stickerAccessHash, "9876543210");
    assert.equal(listBody.data[0].stickerDcId, 2);
    assert.equal(listBody.data[0].stickerEmoji, "🎉");
  } finally {
    sqlite.close();
  }
});
