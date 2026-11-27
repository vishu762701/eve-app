import test from "node:test";
import assert from "node:assert/strict";
import { Hono } from "hono";
import { isolatedD1 } from "./support/sqliteD1.mjs";
import { authMiddleware } from "../src/middleware/authMiddleware.ts";
import { adminRoutes } from "../src/routes/admin.ts";
import { examRoutes } from "../src/routes/exams.ts";
import { generatedTestRoutes } from "../src/routes/generatedTests.ts";
import { questionRoutes } from "../src/routes/questions.ts";
import { adminReportRoutes } from "../src/routes/reports.ts";
import { pollRoutes } from "../src/routes/polls.ts";
import { readAppConfig } from "../src/util/appConfig.ts";
import { serveStoredAvatarResponse } from "../src/util/avatarMedia.ts";
import { buildPrompt, parseAndValidateQuestions } from "../src/ai/generator.ts";

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

function buildTestWorker(d1, { r2 = null, user = null } = {}) {
  const app = new Hono();
  app.use("*", async (c, next) => {
    c.env = {
      DB: d1,
      FIREBASE_PROJECT_ID: "test-project",
      ...(r2 ? { EMOJI_BUCKET: r2 } : {}),
    };
    if (user) {
      c.set("user", user);
    }
    await next();
  });
  app.get("/api/app-config", async (c) => {
    const config = await readAppConfig(c.env.DB);
    return c.json({ success: true, data: config });
  });
  app.get("/api/announcement-channel/avatar", async (c) => {
    const config = await readAppConfig(c.env.DB);
    return serveStoredAvatarResponse(c, config.announcementChannelAvatarUrl, "channel-avatars/");
  });
  // Optional authMiddleware simulator for maintenance/public checks when token bypass isn't mocked
  app.route("/api/exams", examRoutes);
  app.route("/api/generated-tests", generatedTestRoutes);
  app.route("/api/questions", questionRoutes);
  app.route("/api/admin/reports", adminReportRoutes);
  app.route("/api/admin", adminRoutes);
  app.route("/api/polls", pollRoutes);
  return app;
}

function seedAdminsAndCatalog(sqlite) {
  const owner = "pronlike9@gmail.com";
  const broadcastMod = "comms@eve.app";
  const viewer = "viewer@eve.app";
  sqlite.prepare("INSERT OR IGNORE INTO admins (email, created_at) VALUES (?, ?)").run(owner, 1000);
  sqlite.prepare("INSERT OR IGNORE INTO admins (email, created_at) VALUES (?, ?)").run(broadcastMod, 1000);
  sqlite.prepare("INSERT OR IGNORE INTO admin_rights (email, rights, custom_title, updated_at) VALUES (?, ?, ?, ?)")
    .run(broadcastMod, JSON.stringify(["send_broadcasts"]), "Comms", 1000);
  sqlite.prepare("INSERT OR IGNORE INTO admins (email, created_at) VALUES (?, ?)").run(viewer, 1000);
  sqlite.prepare("INSERT OR IGNORE INTO admin_rights (email, rights, custom_title, updated_at) VALUES (?, ?, ?, ?)")
    .run(viewer, JSON.stringify(["view_analytics_logs"]), "Viewer", 1000);

  sqlite
    .prepare(
      `INSERT INTO exams (id, exam_name, category, time_limit_minutes, question_count, test_number, negative_marking_text, publish_mode)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
    )
    .run("ex-1", "SSC CGL Tier 1", "SSC", 60, 25, "Test 1", "1/3", "live");

  const questionsJson = JSON.stringify([
    {
      id: "gt-1_0",
      questionText: "Old generated question?",
      optionA: "Wrong One",
      optionB: "Old Two",
      optionC: "Three",
      optionD: "Four",
      correctAnswer: "A",
      explanation: "Needs correction",
    },
    {
      id: "gt-1_1",
      questionText: "Second generated question?",
      optionA: "Alpha",
      optionB: "Beta",
      optionC: "Gamma",
      optionD: "Delta",
      correctAnswer: "C",
      explanation: "Valid",
    },
  ]);
  sqlite
    .prepare(
      `INSERT INTO generated_tests (id, exam_id, exam_name, test_number, title, generated_at, status, question_count, questions_json, available_from)
       VALUES (?, ?, ?, ?, ?, ?, 'live', 2, ?, 0)`
    )
    .run("gt-1", "ex-1", "SSC CGL Tier 1", "Test 1", "Mock Test 1", 1000, questionsJson);
}

test("App config returns maintenance & channel metadata publicly and admin maintenance guard blocks students with 503", async () => {
  const { sqlite, d1 } = isolatedD1();
  seedAdminsAndCatalog(sqlite);

  const ownerApp = buildTestWorker(d1, { user: { uid: "own-1", email: "pronlike9@gmail.com", isAdmin: true, emailVerified: true } });
  const initialRes = await ownerApp.request("http://localhost/api/app-config");
  assert.equal(initialRes.status, 200);
  const initialJson = await initialRes.json();
  assert.equal(initialJson.data.maintenanceMode, false);
  assert.equal(initialJson.data.announcementChannelName, "Announcements");
  assert.equal(initialJson.data.announcementChannelAvatarUrl, "");

  // Turn Maintenance ON + rename channel
  const putRes = await ownerApp.request("http://localhost/api/admin/config", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      maintenanceMode: true,
      maintenanceMessage: "Server database upgrade in progress",
      announcementChannelName: "EVE Official Updates",
    }),
  });
  assert.equal(putRes.status, 200);
  const putJson = await putRes.json();
  assert.equal(putJson.data.maintenance_mode, true);
  assert.equal(putJson.data.maintenance_message, "Server database upgrade in progress");
  assert.equal(putJson.data.announcement_channel_name, "EVE Official Updates");

  // Public /api/app-config reflects updated fields without auth
  const publicRes = await buildTestWorker(d1).request("http://localhost/api/app-config");
  assert.equal(publicRes.status, 200);
  const publicJson = await publicRes.json();
  assert.equal(publicJson.data.maintenanceMode, true);
  assert.equal(publicJson.data.maintenanceMessage, "Server database upgrade in progress");
  assert.equal(publicJson.data.announcementChannelName, "EVE Official Updates");

  // Verify authMiddleware blocks unprivileged students on guarded routes with 503 MAINTENANCE_MODE
  // while leaving /api/app-config and public avatar GETs open.
  const guardedApp = new Hono();
  const originalFetch = globalThis.fetch;
  // Stub token verification path indirectly via middleware check using invalid JWT -> 401 still happens before auth!
  // Instead test maintenance helper contract via authMiddleware with student vs admin on guarded paths:
  const { isStudentMaintenanceGuardedPath, enforceStudentMaintenanceMode } = await import("../src/middleware/authMiddleware.ts");
  assert.equal(isStudentMaintenanceGuardedPath("/api/exams"), true);
  assert.equal(isStudentMaintenanceGuardedPath("/api/generated-tests/gt-1"), true);
  assert.equal(isStudentMaintenanceGuardedPath("/api/attempts/start"), true);
  assert.equal(isStudentMaintenanceGuardedPath("/api/leaderboard"), true);
  assert.equal(isStudentMaintenanceGuardedPath("/api/polls"), true);
  assert.equal(isStudentMaintenanceGuardedPath("/api/users/sync"), false);
  assert.equal(isStudentMaintenanceGuardedPath("/api/app-config"), false);
  assert.equal(isStudentMaintenanceGuardedPath("/api/admin/config"), false);

  const studentGuardedApp = new Hono();
  studentGuardedApp.use("*", async (c, next) => {
    c.env = { DB: d1, FIREBASE_PROJECT_ID: "test-project" };
    const u = { uid: "stu-1", email: "stu@test.com", displayName: "Stu", isAdmin: false };
    c.set("user", u);
    const blocked = await enforceStudentMaintenanceMode(c, u, c.req.path);
    if (blocked) return blocked;
    return next();
  });
  studentGuardedApp.route("/api/exams", examRoutes);
  studentGuardedApp.route("/api/polls", pollRoutes);
  const blockedRes = await studentGuardedApp.request("http://localhost/api/exams");
  assert.equal(blockedRes.status, 503);
  const blockedJson = await blockedRes.json();
  assert.equal(blockedJson.code, "MAINTENANCE_MODE");
  assert.equal(blockedJson.maintenanceMessage, "Server database upgrade in progress");
  assert.equal((await studentGuardedApp.request("http://localhost/api/polls")).status, 503);

  const adminGuardedApp = new Hono();
  adminGuardedApp.use("*", async (c, next) => {
    c.env = { DB: d1, FIREBASE_PROJECT_ID: "test-project" };
    const u = { uid: "own-1", email: "pronlike9@gmail.com", displayName: "Owner", isAdmin: true };
    c.set("user", u);
    const blocked = await enforceStudentMaintenanceMode(c, u, c.req.path);
    if (blocked) return blocked;
    return next();
  });
  adminGuardedApp.route("/api/exams", examRoutes);
  assert.equal((await adminGuardedApp.request("http://localhost/api/exams")).status, 200);
  void guardedApp;
  void originalFetch;
});

test("Announcement channel avatar upload, public read, rename, and delete work with permission checks", async () => {
  const { sqlite, d1 } = isolatedD1();
  seedAdminsAndCatalog(sqlite);
  const r2 = createMockR2Bucket();

  const viewerApp = buildTestWorker(d1, { r2, user: { uid: "v-1", email: "viewer@eve.app", isAdmin: true } });
  const denied = await viewerApp.request("http://localhost/api/admin/announcement-channel", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ announcementChannelName: "Hijacked" }),
  });
  assert.equal(denied.status, 403);

  const commsApp = buildTestWorker(d1, { r2, user: { uid: "c-1", email: "comms@eve.app", isAdmin: true } });
  const renameRes = await commsApp.request("http://localhost/api/admin/announcement-channel", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ announcementChannelName: "EVE Desk" }),
  });
  assert.equal(renameRes.status, 200);
  assert.equal((await renameRes.json()).data.announcementChannelName, "EVE Desk");

  const pngBytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 1, 9, 8, 7, 6]);
  const uploadRes = await commsApp.request("http://localhost/api/admin/announcement-channel/avatar", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ base64: Buffer.from(pngBytes).toString("base64"), mimeType: "image/png" }),
  });
  assert.equal(uploadRes.status, 200);
  const uploadJson = await uploadRes.json();
  assert.ok(uploadJson.data.announcementChannelAvatarUrl.startsWith("/api/announcement-channel/avatar?k=channel-avatars"));
  assert.equal(r2.objects.size, 1);

  const publicRes = await buildTestWorker(d1, { r2 }).request(`http://localhost${uploadJson.data.announcementChannelAvatarUrl}`);
  assert.equal(publicRes.status, 200);
  assert.deepEqual(new Uint8Array(await publicRes.arrayBuffer()), pngBytes);

  const delRes = await commsApp.request("http://localhost/api/admin/announcement-channel/avatar", { method: "DELETE" });
  assert.equal(delRes.status, 200);
  assert.equal((await delRes.json()).data.announcementChannelAvatarUrl, "");
  assert.equal(r2.objects.size, 0);
});

test("Generated test avatar upload, list metadata, public GET, delete, and pre-0035 fallback work", async () => {
  const { sqlite, d1 } = isolatedD1();
  seedAdminsAndCatalog(sqlite);
  const r2 = createMockR2Bucket();
  const ownerApp = buildTestWorker(d1, { r2, user: { uid: "own-1", email: "pronlike9@gmail.com", isAdmin: true } });

  const jpgBytes = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 16, 0x4a, 0x46, 0x49, 0x46, 0, 1, 2, 3]);
  const upRes = await ownerApp.request("http://localhost/api/generated-tests/gt-1/avatar", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ base64: Buffer.from(jpgBytes).toString("base64"), mimeType: "image/jpeg" }),
  });
  assert.equal(upRes.status, 200);
  const upJson = await upRes.json();
  assert.ok(upJson.data.avatarUrl.startsWith("/api/generated-tests/gt-1/avatar?k=test-avatars"));
  assert.equal(r2.objects.size, 1);

  const listJson = await (await ownerApp.request("http://localhost/api/generated-tests?examId=ex-1")).json();
  assert.equal(listJson.data[0].avatarUrl, upJson.data.avatarUrl);

  const publicImg = await buildTestWorker(d1, { r2 }).request(`http://localhost${upJson.data.avatarUrl}`);
  assert.equal(publicImg.status, 200);
  assert.deepEqual(new Uint8Array(await publicImg.arrayBuffer()), jpgBytes);

  const delRes = await ownerApp.request("http://localhost/api/generated-tests/gt-1/avatar", { method: "DELETE" });
  assert.equal(delRes.status, 200);
  assert.equal(r2.objects.size, 0);

  // Pre-0035 graceful fallback when generated_tests avatar columns are missing
  sqlite.exec("ALTER TABLE generated_tests DROP COLUMN avatar_url");
  sqlite.exec("ALTER TABLE generated_tests DROP COLUMN avatar_emoji");
  sqlite.exec("ALTER TABLE generated_tests DROP COLUMN avatar_color");
  const fallbackList = await ownerApp.request("http://localhost/api/generated-tests?examId=ex-1");
  assert.equal(fallbackList.status, 200);
  const fallbackJson = await fallbackList.json();
  assert.equal(fallbackJson.data[0].avatarUrl, "");
});

test("Exam AI Prompt Studio fields persist cleanly, inject into buildPrompt, and degrade safely pre-0035", async () => {
  const { sqlite, d1 } = isolatedD1();
  seedAdminsAndCatalog(sqlite);
  const ownerApp = buildTestWorker(d1, { user: { uid: "own-1", email: "pronlike9@gmail.com", isAdmin: true } });

  const updateRes = await ownerApp.request("http://localhost/api/exams/ex-1", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      aiPrompt: "Focus 40% on Indian Polity Articles and PYQ trends.",
      aiLanguage: "bilingual",
      aiDifficulty: "hard",
    }),
  });
  assert.equal(updateRes.status, 200);

  const examJson = await (await ownerApp.request("http://localhost/api/exams/ex-1")).json();
  assert.equal(examJson.data.aiPrompt, "Focus 40% on Indian Polity Articles and PYQ trends.");
  assert.equal(examJson.data.aiLanguage, "bilingual");
  assert.equal(examJson.data.aiDifficulty, "hard");

  const prompt = buildPrompt("SSC CGL", "Polity & Economy", 25, examJson.data.aiPrompt, {
    language: examJson.data.aiLanguage,
    difficulty: examJson.data.aiDifficulty,
  });
  assert.ok(prompt.includes("Bilingual"));
  assert.ok(prompt.includes("questionTextHi"));
  assert.ok(prompt.includes("HARD"));
  assert.ok(prompt.includes("Indian Polity Articles"));

  const parsedBilingual = parseAndValidateQuestions(
    JSON.stringify([
      {
        questionText: "What is Article 21?",
        optionA: "Right to Life",
        optionB: "Right to Property",
        optionC: "Right to Vote",
        optionD: "Right to Strike",
        correctAnswer: "A",
        explanation: "Article 21 protects life and personal liberty.",
        questionTextHi: "अनुच्छेद 21 क्या है?",
        optionAHi: "जीवन का अधिकार",
        optionBHi: "संपत्ति का अधिकार",
        optionCHi: "मतदान का अधिकार",
        optionDHi: "हड़ताल का अधिकार",
        explanationHi: "अनुच्छेद 21 जीवन और व्यक्तिगत स्वतंत्रता की रक्षा करता है।",
      },
    ])
  );
  assert.equal(parsedBilingual[0].questionTextHi, "अनुच्छेद 21 क्या है?");

  // Pre-0035 schema fallback on exams
  sqlite.exec("ALTER TABLE exams DROP COLUMN ai_prompt");
  sqlite.exec("ALTER TABLE exams DROP COLUMN ai_language");
  sqlite.exec("ALTER TABLE exams DROP COLUMN ai_difficulty");
  const legacyPut = await ownerApp.request("http://localhost/api/exams/ex-1", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      aiPrompt: "Legacy prompt fallback",
      aiLanguage: "hindi",
      aiDifficulty: "easy",
    }),
  });
  assert.equal(legacyPut.status, 200);
  const legacyGet = await (await ownerApp.request("http://localhost/api/exams/ex-1")).json();
  assert.equal(legacyGet.data.aiPrompt, "Legacy prompt fallback");
});

test("Flagged question lookup and atomic fix/delete work for both bank questions and generated test items", async () => {
  const { sqlite, d1 } = isolatedD1();
  seedAdminsAndCatalog(sqlite);

  sqlite
    .prepare(
      `INSERT INTO questions (id, exam_id, question_text, option_a, option_b, option_c, option_d, correct_answer, explanation, topic)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    )
    .run("q-bank-1", "ex-1", "2 + 3 = ?", "4", "5", "6", "7", "A", "Typo answer", "Quant");

  sqlite
    .prepare(
      `INSERT INTO question_reports (id, question_id, exam_id, exam_name, question_text, reason, comment, student_id, student_email, timestamp, status, report_type)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', 'content')`
    )
    .run("rep-bank-1", "q-bank-1", "ex-1", "SSC CGL Tier 1", "2 + 3 = ?", "Wrong Question", "5 is B", "s1", "s1@test.com", 1000);
  sqlite
    .prepare(
      `INSERT INTO question_reports (id, question_id, exam_id, exam_name, question_text, reason, comment, student_id, student_email, timestamp, status, report_type)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', 'content')`
    )
    .run("rep-bank-2", "q-bank-1", "ex-1", "SSC CGL Tier 1", "2 + 3 = ?", "Other", "Duplicate report", "s2", "s2@test.com", 1001);
  sqlite
    .prepare(
      `INSERT INTO question_reports (id, question_id, exam_id, exam_name, question_text, reason, comment, student_id, student_email, timestamp, status, report_type)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', 'content')`
    )
    .run("rep-gen-1", "gt-1_0", "ex-1__gt-1", "SSC CGL Tier 1", "Old generated question?", "Wrong Question", "Option B right", "s1", "s1@test.com", 1002);

  const ownerApp = buildTestWorker(d1, { user: { uid: "own-1", email: "pronlike9@gmail.com", isAdmin: true } });
  const viewerApp = buildTestWorker(d1, { user: { uid: "v-1", email: "viewer@eve.app", isAdmin: true } });

  // Viewer cannot inspect raw answer keys
  assert.equal((await viewerApp.request("http://localhost/api/questions/q-bank-1")).status, 403);

  // Admin/Moderator fetches both bank and generated-test question seamlessly
  const bankQ = await (await ownerApp.request("http://localhost/api/questions/q-bank-1")).json();
  assert.equal(bankQ.data.sourceType, "bank");
  assert.equal(bankQ.data.correctAnswer, "A");

  const genQ = await (await ownerApp.request("http://localhost/api/questions/gt-1_0")).json();
  assert.equal(genQ.data.sourceType, "generated_test");
  assert.equal(genQ.data.optionB, "Old Two");

  // Fix bank question + resolve BOTH pending reports for q-bank-1 atomically
  const fixBankRes = await ownerApp.request("http://localhost/api/admin/reports/rep-bank-1/fix-question", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      questionText: "2 + 3 equals what?",
      optionA: "4",
      optionB: "5",
      optionC: "6",
      optionD: "7",
      correctAnswer: "B",
      explanation: "2 + 3 = 5.",
    }),
  });
  assert.equal(fixBankRes.status, 200);
  const fixBankJson = await fixBankRes.json();
  assert.equal(fixBankJson.data.resolvedReportsCount, 2);

  const updatedQ = sqlite.prepare("SELECT * FROM questions WHERE id = 'q-bank-1'").get();
  assert.equal(updatedQ.correct_answer, "B");
  assert.equal(sqlite.prepare("SELECT status FROM question_reports WHERE id = 'rep-bank-2'").get().status, "dismissed");

  // Fix generated test question gt-1_0 atomically inside questions_json
  const fixGenRes = await ownerApp.request("http://localhost/api/admin/reports/rep-gen-1/fix-question", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      questionText: "Corrected generated question?",
      optionA: "New One",
      optionB: "Correct Two",
      optionC: "Three",
      optionD: "Four",
      correctAnswer: "B",
      explanation: "Fixed explanation.",
    }),
  });
  assert.equal(fixGenRes.status, 200);
  const updatedTest = JSON.parse(sqlite.prepare("SELECT questions_json FROM generated_tests WHERE id = 'gt-1'").get().questions_json);
  assert.equal(updatedTest[0].questionText, "Corrected generated question?");
  assert.equal(updatedTest[0].correctAnswer, "B");
  assert.equal(sqlite.prepare("SELECT status FROM question_reports WHERE id = 'rep-gen-1'").get().status, "dismissed");

  // Delete bad bank question via report route
  const delBankRes = await ownerApp.request("http://localhost/api/admin/reports/rep-bank-1/question", { method: "DELETE" });
  assert.equal(delBankRes.status, 200);
  assert.equal(sqlite.prepare("SELECT * FROM questions WHERE id = 'q-bank-1'").get(), undefined);
});

test("GET /api/polls returns voteCounts and caller userVote without N+1 requests", async () => {
  const { sqlite, d1 } = isolatedD1();
  seedAdminsAndCatalog(sqlite);

  sqlite
    .prepare("INSERT INTO polls (id, question, options_json, created_at, ends_at, active, created_by) VALUES (?, ?, ?, ?, 0, 1, ?)")
    .run("p-1", "Preferred time?", JSON.stringify(["Morning", "Evening"]), 5000, "pronlike9@gmail.com");
  sqlite
    .prepare("INSERT INTO poll_votes (poll_id, user_id, option_index, voted_at) VALUES (?, ?, ?, ?)")
    .run("p-1", "stu-1", 1, 6000);

  const studentApp = buildTestWorker(d1, { user: { uid: "stu-1", email: "stu1@test.com", isAdmin: false } });
  const res = await studentApp.request("http://localhost/api/polls");
  assert.equal(res.status, 200);
  const json = await res.json();
  assert.equal(json.data[0].userVote, 1);
  assert.equal(json.data[0].voteCounts["1"], 1);
});
