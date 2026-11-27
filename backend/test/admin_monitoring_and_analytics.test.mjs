import test from "node:test";
import assert from "node:assert/strict";
import { Hono } from "hono";
import { isolatedD1 } from "./support/sqliteD1.mjs";
import { adminRoutes } from "../src/routes/admin.ts";
import { monitoringRoutes } from "../src/routes/monitoring.ts";
import { crashIngestRoutes } from "../src/routes/crashes.ts";

function buildApp(d1, user) {
  const app = new Hono();
  app.use("*", async (c, next) => {
    c.env = { DB: d1 };
    c.set("user", user);
    await next();
  });
  app.route("/api/admin/system", monitoringRoutes);
  app.route("/api/admin", adminRoutes);
  app.route("/api/crashes", crashIngestRoutes);
  return app;
}

function seedAdmins(sqlite) {
  sqlite.prepare("INSERT OR IGNORE INTO admins (email, created_at) VALUES (?, ?)").run("pronlike9@gmail.com", 1000);
  sqlite.prepare("INSERT OR IGNORE INTO admins (email, created_at) VALUES (?, ?)").run("mod@eve.app", 1000);
  sqlite
    .prepare("INSERT OR IGNORE INTO admin_rights (email, rights, custom_title, updated_at) VALUES (?, ?, ?, ?)")
    .run("mod@eve.app", JSON.stringify(["view_analytics_logs", "manage_users"]), "Moderator", 1000);
}

test("GET /api/admin/analytics/exams returns accurate highestScore across attempts", async () => {
  const { sqlite, d1 } = isolatedD1();
  seedAdmins(sqlite);

  const now = Date.now();
  sqlite
    .prepare(
      `INSERT INTO exams (id, exam_name, category, time_limit_minutes, question_count, test_number, negative_marking_text, publish_mode)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
    )
    .run("ex-ssc", "SSC CGL Tier 1", "SSC", 60, 100, "Mock 1", "0.25", "live");

  const insertAttempt = sqlite.prepare(
    `INSERT INTO attempts (id, user_id, exam_id, exam_name, category, score, total, correct, wrong, unattempted, timestamp, counted)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)`
  );
  insertAttempt.run("att-1", "u1", "ex-ssc", "SSC CGL Tier 1", "SSC", 54.5, 100, 60, 22, 18, now - 10000);
  insertAttempt.run("att-2", "u2", "ex-ssc__t1", "SSC CGL Tier 1", "SSC", 91.25, 100, 92, 3, 5, now - 5000);

  const app = buildApp(d1, { uid: "owner_1", email: "pronlike9@gmail.com", isAdmin: true });
  const res = await app.request("http://localhost/api/admin/analytics/exams");
  assert.equal(res.status, 200);
  const json = await res.json();
  assert.equal(json.success, true);
  assert.equal(json.data.length, 1);
  assert.equal(json.data[0].attemptCount, 2);
  assert.equal(json.data[0].averageScore, 72.9);
  assert.equal(json.data[0].highestScore, 91.3);

  const cutoffRes = await app.request("http://localhost/api/admin/analytics/exams?days=7");
  assert.equal(cutoffRes.status, 200);
  const cutoffJson = await cutoffRes.json();
  assert.equal(cutoffJson.data[0].highestScore, 91.3);
});

test("GET /api/admin/users/:id returns student profile fields and 404 when absent", async () => {
  const { sqlite, d1 } = isolatedD1();
  seedAdmins(sqlite);

  sqlite
    .prepare(
      `INSERT INTO users (id, eve_id, email, display_name, username, phone, bio, category, created_at, last_active, disabled)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)`
    )
    .run("u-student-1", "EV-9001", "student@eve.app", "Aarav Verma", "aarav_v", "+919999988888", "SSC Aspirant", "SSC", 1000, 2000);

  const app = buildApp(d1, { uid: "mod_1", email: "mod@eve.app", isAdmin: true });
  const res = await app.request("http://localhost/api/admin/users/u-student-1");
  assert.equal(res.status, 200);
  const json = await res.json();
  assert.equal(json.success, true);
  assert.equal(json.data.id, "u-student-1");
  assert.equal(json.data.username, "aarav_v");
  assert.equal(json.data.phone, "+919999988888");
  assert.equal(json.data.bio, "SSC Aspirant");

  const missingRes = await app.request("http://localhost/api/admin/users/nonexistent");
  assert.equal(missingRes.status, 404);
});

test("Crash center: plain-English enrichment, resolve, reopen on recurrence, and Owner-only delete", async () => {
  const { sqlite, d1 } = isolatedD1();
  seedAdmins(sqlite);

  const studentApp = buildApp(d1, { uid: "student_1", email: "s@eve.app", isAdmin: false });
  const modApp = buildApp(d1, { uid: "mod_1", email: "mod@eve.app", isAdmin: true });
  const ownerApp = buildApp(d1, { uid: "owner_1", email: "pronlike9@gmail.com", isAdmin: true });

  // 1. Ingest crash
  const ingestRes = await studentApp.request("http://localhost/api/crashes", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      exception_class: "java.lang.NullPointerException",
      top_frame: "org.telegram.ui.eve.exams.EveTestPlayerFragment.submit(EveTestPlayerFragment.java:210)",
      message: "Attempt to invoke virtual method on null object reference",
      stack_frames: ["org.telegram.ui.eve.exams.EveTestPlayerFragment.submit(EveTestPlayerFragment.java:210)"],
      app_version_name: "13.2.2",
      app_version_code: 73039,
      android_sdk: 35,
      device_model: "Pixel 8",
    }),
  });
  assert.equal(ingestRes.status, 200);
  const ingestJson = await ingestRes.json();
  const sig = ingestJson.signature;
  assert.ok(sig);

  // 2. Fetch open crashes (default) with plain-English enrichment
  const listRes = await modApp.request("http://localhost/api/admin/system/crashes");
  assert.equal(listRes.status, 200);
  const listJson = await listRes.json();
  assert.equal(listJson.data.length, 1);
  assert.ok(listJson.data[0].plainEnglishSummary.toLowerCase().includes("null"));
  assert.ok(listJson.data[0].whereItHappened.includes("Test Player screen"));

  // 3. Resolve crash -> disappears from default open list, appears in resolved
  const resolveRes = await modApp.request(`http://localhost/api/admin/system/crashes/${sig}/resolve`, {
    method: "POST",
  });
  assert.equal(resolveRes.status, 200);
  const openAfter = await (await modApp.request("http://localhost/api/admin/system/crashes")).json();
  assert.equal(openAfter.data.length, 0);
  const resolvedList = await (await modApp.request("http://localhost/api/admin/system/crashes?status=resolved")).json();
  assert.equal(resolvedList.data.length, 1);

  // 4. Recurrence reopens crash automatically
  const recurRes = await studentApp.request("http://localhost/api/crashes", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      exception_class: "java.lang.NullPointerException",
      top_frame: "org.telegram.ui.eve.exams.EveTestPlayerFragment.submit(EveTestPlayerFragment.java:210)",
      message: "Recurred",
    }),
  });
  assert.equal(recurRes.status, 200);
  const reopened = await (await modApp.request("http://localhost/api/admin/system/crashes")).json();
  assert.equal(reopened.data.length, 1);
  assert.equal(reopened.data[0].occurrence_count, 2);

  // 5. Delete is Owner-only
  const nonOwnerDel = await modApp.request(`http://localhost/api/admin/system/crashes/${sig}`, { method: "DELETE" });
  assert.equal(nonOwnerDel.status, 403);
  const ownerDel = await ownerApp.request(`http://localhost/api/admin/system/crashes/${sig}`, { method: "DELETE" });
  assert.equal(ownerDel.status, 200);
  const afterDel = await (await modApp.request("http://localhost/api/admin/system/crashes?status=all")).json();
  assert.equal(afterDel.data.length, 0);
});

test("GET /api/admin/system/security-events classifies real security and backend error events", async () => {
  const { sqlite, d1 } = isolatedD1();
  seedAdmins(sqlite);

  const now = Date.now();
  sqlite
    .prepare(
      `INSERT INTO operation_events (id, operation, timestamp, category, status, retryable, correlation_id)
       VALUES (?, ?, ?, ?, ?, ?, ?)`
    )
    .run("ev-1", "admin_access_denied", now, "AUTHORIZATION", 403, 0, "path=/api/admin/users|not_admin");
  sqlite
    .prepare(
      `INSERT INTO operation_events (id, operation, timestamp, category, status, retryable, correlation_id)
       VALUES (?, ?, ?, ?, ?, ?, ?)`
    )
    .run("ev-2", "generation", now - 1000, "BACKEND_FAILURE", 502, 1, "req-xyz");

  const app = buildApp(d1, { uid: "mod_1", email: "mod@eve.app", isAdmin: true });
  const res = await app.request("http://localhost/api/admin/system/security-events");
  assert.equal(res.status, 200);
  const json = await res.json();
  assert.equal(json.success, true);
  assert.equal(json.data.securityCount, 1);
  assert.equal(json.data.errorCount, 1);
  assert.equal(json.data.events.length, 2);
  assert.equal(json.data.events[0].kind, "security");
  assert.equal(json.data.events[0].severity, "high");
  assert.equal(json.data.events[1].kind, "app_error");
});
