import test from "node:test";
import assert from "node:assert/strict";
import { Hono } from "hono";
import { isolatedD1 } from "./support/sqliteD1.mjs";
import { examRoutes } from "../src/routes/exams.ts";
import { generatedTestRoutes } from "../src/routes/generatedTests.ts";
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
  app.route("/api/exams", examRoutes);
  app.route("/api/generated-tests", generatedTestRoutes);
  app.route("/api/admin", adminRoutes);
  return app;
}

function seedAdminsAndExam(sqlite) {
  sqlite.prepare("INSERT OR IGNORE INTO admins (email, created_at) VALUES (?, ?)").run("pronlike9@gmail.com", 1000);
  sqlite.prepare("INSERT OR IGNORE INTO admins (email, created_at) VALUES (?, ?)").run("noperm@eve.app", 1000);
  sqlite
    .prepare("INSERT OR IGNORE INTO admin_rights (email, rights, custom_title, updated_at) VALUES (?, ?, ?, ?)")
    .run("noperm@eve.app", JSON.stringify(["view_analytics_logs"]), "Viewer", 1000);

  sqlite
    .prepare(
      `INSERT INTO exams (id, exam_name, category, time_limit_minutes, question_count, test_number, negative_marking_text, publish_mode)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
    )
    .run("ex-1", "UPSC Prelims", "UPSC", 120, 100, "Test 1", "1/3", "live");
}

test("GET /api/generated-tests returns generated tests in natural ascending creation order", async () => {
  const { sqlite, d1 } = isolatedD1();
  seedAdminsAndExam(sqlite);

  const qJson = JSON.stringify([
    {
      id: "q-1",
      questionText: "Capital of India?",
      optionA: "Delhi",
      optionB: "Mumbai",
      optionC: "Kolkata",
      optionD: "Chennai",
      correctAnswer: "A",
      explanation: "New Delhi",
    },
  ]);
  const insert = sqlite.prepare(
    `INSERT INTO generated_tests (id, exam_id, exam_name, test_number, title, generated_at, status, question_count, questions_json, available_from)
     VALUES (?, ?, ?, ?, ?, ?, 'live', 1, ?, 0)`
  );
  // Insert out of order
  insert.run("gt-3", "ex-1", "UPSC Prelims", "Test 3", "Test 3", 3000, qJson);
  insert.run("gt-1", "ex-1", "UPSC Prelims", "Test 1", "Test 1", 1000, qJson);
  insert.run("gt-2", "ex-1", "UPSC Prelims", "Test 2", "Test 2", 2000, qJson);
  insert.run("gt-4", "ex-1", "UPSC Prelims", "Test 4", "Test 4", 4000, qJson);

  const studentApp = buildApp(d1, { uid: "stu-1", email: "student@example.com", isAdmin: false });
  const res = await studentApp.request("http://localhost/api/generated-tests?examId=ex-1");
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.success, true);
  assert.deepEqual(
    body.data.map((t) => t.testNumber),
    ["Test 1", "Test 2", "Test 3", "Test 4"]
  );
});

test("Exam avatar supports R2 image upload/replace, public fetch, emoji/color set, DELETE reset, and manage_exams guard", async () => {
  const { sqlite, d1 } = isolatedD1();
  seedAdminsAndExam(sqlite);
  const r2 = createMockR2Bucket();

  const ownerApp = buildApp(d1, { uid: "owner-1", email: "pronlike9@gmail.com", isAdmin: true }, r2);
  const noPermApp = buildApp(d1, { uid: "mod-1", email: "noperm@eve.app", isAdmin: true }, r2);

  // Forbidden without manage_exams right
  const denied = await noPermApp.request("http://localhost/api/exams/ex-1/avatar", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ avatarEmoji: "📘", avatarColor: 2 }),
  });
  assert.equal(denied.status, 403);

  // Set emoji & color avatar
  const emojiRes = await ownerApp.request("http://localhost/api/exams/ex-1/avatar", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ avatarEmoji: "📘", avatarColor: 3 }),
  });
  assert.equal(emojiRes.status, 200);
  const emojiJson = await emojiRes.json();
  assert.equal(emojiJson.data.avatarEmoji, "📘");
  assert.equal(emojiJson.data.avatarColor, 3);
  assert.equal(emojiJson.data.avatarUrl, "");

  // GET /api/exams exposes avatar fields
  const listAfterEmoji = await (await ownerApp.request("http://localhost/api/exams")).json();
  assert.equal(listAfterEmoji.data[0].avatarEmoji, "📘");
  assert.equal(listAfterEmoji.data[0].avatarColor, 3);

  // Upload PNG image to R2
  const pngBytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 1, 1, 2, 3, 4]);
  const pngBase64 = Buffer.from(pngBytes).toString("base64");
  const imgRes = await ownerApp.request("http://localhost/api/exams/ex-1/avatar", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ base64: pngBase64, mimeType: "image/png" }),
  });
  assert.equal(imgRes.status, 200);
  const imgJson = await imgRes.json();
  assert.ok(imgJson.data.avatarUrl.startsWith("/api/exams/ex-1/avatar?k=exam-avatars"));
  assert.equal(imgJson.data.avatarEmoji, "");
  assert.equal(r2.objects.size, 1);
  const firstKey = [...r2.objects.keys()][0];

  // Public GET /api/exams/:id/avatar fetches R2 bytes cleanly
  const publicApp = buildApp(d1, null, r2);
  const mediaRes = await publicApp.request(`http://localhost${imgJson.data.avatarUrl}`);
  assert.equal(mediaRes.status, 200);
  assert.equal(mediaRes.headers.get("content-type"), "image/png");
  const servedBytes = new Uint8Array(await mediaRes.arrayBuffer());
  assert.deepEqual(servedBytes, pngBytes);

  // Replace with GIF cleans up old PNG from R2
  const gifBytes = new Uint8Array([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 0, 1, 0, 0, 0, 0, 0x3b]);
  const gifRes = await ownerApp.request("http://localhost/api/exams/ex-1/avatar", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ base64: Buffer.from(gifBytes).toString("base64"), mimeType: "image/gif" }),
  });
  assert.equal(gifRes.status, 200);
  assert.equal(r2.objects.size, 1);
  assert.equal(r2.objects.has(firstKey), false);

  // Reject non-image invalid format
  const badRes = await ownerApp.request("http://localhost/api/exams/ex-1/avatar", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ base64: Buffer.from("not-an-image-payload").toString("base64"), mimeType: "text/plain" }),
  });
  assert.equal(badRes.status, 400);

  // DELETE /api/exams/:id/avatar clears avatar and deletes R2 object
  const delRes = await ownerApp.request("http://localhost/api/exams/ex-1/avatar", { method: "DELETE" });
  assert.equal(delRes.status, 200);
  assert.equal(r2.objects.size, 0);
  const listAfterDel = await (await ownerApp.request("http://localhost/api/exams")).json();
  assert.equal(listAfterDel.data[0].avatarUrl, "");
  assert.equal(listAfterDel.data[0].avatarEmoji, "");
  assert.equal(listAfterDel.data[0].avatarColor, null);
});

test("Exam routes degrade gracefully when pre-0033 D1 exams table lacks avatar columns", async () => {
  const { sqlite, d1 } = isolatedD1();
  seedAdminsAndExam(sqlite);
  sqlite.exec("ALTER TABLE exams DROP COLUMN avatar_url");
  sqlite.exec("ALTER TABLE exams DROP COLUMN avatar_emoji");
  sqlite.exec("ALTER TABLE exams DROP COLUMN avatar_color");

  const ownerApp = buildApp(d1, { uid: "owner-1", email: "pronlike9@gmail.com", isAdmin: true });
  const examsRes = await ownerApp.request("http://localhost/api/exams");
  assert.equal(examsRes.status, 200);
  const examsJson = await examsRes.json();
  assert.equal(examsJson.data[0].avatarEmoji, "");
  assert.equal(examsJson.data[0].avatarColor, null);

  const dashRes = await ownerApp.request("http://localhost/api/admin/dashboard");
  assert.equal(dashRes.status, 200);
});
