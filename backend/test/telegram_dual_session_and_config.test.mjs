import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { Hono } from "hono";
import { isolatedD1 } from "./support/sqliteD1.mjs";
import { authRoutes } from "../src/routes/auth.ts";
import { adminRoutes } from "../src/routes/admin.ts";
import { appContentRoutes } from "../src/routes/appContent.ts";
import { premiumRoutes } from "../src/routes/premium.ts";
import { readAppConfig } from "../src/util/appConfig.ts";

function buildApp(d1, user) {
  const app = new Hono();
  app.use("*", async (c, next) => {
    c.env = { DB: d1, FIREBASE_PROJECT_ID: "test-project" };
    if (user) c.set("user", user);
    await next();
  });
  app.get("/api/app-config", async (c) => {
    const config = await readAppConfig(c.env.DB);
    return c.json({ success: true, data: config });
  });
  app.route("/api/users", authRoutes);
  app.route("/api/admin", adminRoutes);
  app.route("/api/app-content", appContentRoutes);
  app.route("/api/premium", premiumRoutes);
  return app;
}

test("POST /api/users/sync persists and returns unified Telegram identity (telegramUserId, phone, username, displayName, bio) when migration is applied", async () => {
  const { d1, sqlite } = isolatedD1();
  const app = buildApp(d1, {
    uid: "stu-tg-1",
    email: "student.tg@example.com",
    displayName: "Aarav",
    isAdmin: false,
    emailVerified: true,
  });

  const syncRes = await app.request("http://localhost/api/users/sync", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      displayName: "Aarav Sharma",
      telegramUserId: "9996628429",
      username: "@aarav_test",
      phone: "917627018989",
      bio: "UPSC CSE 2027 aspirant",
    }),
  });
  assert.equal(syncRes.status, 200);
  const syncJson = await syncRes.json();
  assert.equal(syncJson.success, true);
  assert.ok(syncJson.data.eveId.startsWith("EV-"));
  assert.equal(syncJson.data.displayName, "Aarav Sharma");
  assert.equal(syncJson.data.telegramUserId, "9996628429");
  assert.equal(syncJson.data.telegramUsername, "aarav_test");
  assert.equal(syncJson.data.username, "aarav_test");
  assert.equal(syncJson.data.phone, "+917627018989");
  assert.equal(syncJson.data.bio, "UPSC CSE 2027 aspirant");

  // Verify lookup indexes created by migration 0038 exist
  const indexRows = sqlite.prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'users'").all();
  const indexNames = new Set(indexRows.map((r) => r.name));
  assert.equal(indexNames.has("idx_users_phone"), true);
  assert.equal(indexNames.has("idx_users_telegram_user_id"), true);

  // Subsequent sync without telegram fields preserves linked Telegram identity
  const secondRes = await app.request("http://localhost/api/users/sync", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ displayName: "Aarav Kumar" }),
  });
  assert.equal(secondRes.status, 200);
  const secondJson = await secondRes.json();
  assert.equal(secondJson.data.telegramUserId, "9996628429");
  assert.equal(secondJson.data.telegramUsername, "aarav_test");
  assert.equal(secondJson.data.phone, "+917627018989");
  assert.equal(secondJson.data.bio, "UPSC CSE 2027 aspirant");

  // Invalid telegramUserId or phone is rejected with 400
  const badRes = await app.request("http://localhost/api/users/sync", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ displayName: "Aarav", telegramUserId: "bad id with spaces!" }),
  });
  assert.equal(badRes.status, 400);

  const badPhoneRes = await app.request("http://localhost/api/users/sync", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ displayName: "Aarav", phone: "123" }),
  });
  assert.equal(badPhoneRes.status, 400);
});

test("POST /api/users/sync succeeds defensively on legacy users table before migration 0036", async () => {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(`CREATE TABLE users (
    id TEXT PRIMARY KEY,
    eve_id TEXT,
    email TEXT,
    display_name TEXT,
    created_at INTEGER,
    last_active INTEGER
  )`);
  const d1 = {
    prepare(sql) {
      const make = (values = []) => ({
        bind(...args) { return make(args); },
        async run() {
          const r = sqlite.prepare(sql).run(...values);
          return { meta: { changes: Number(r.changes) } };
        },
        async first() { return sqlite.prepare(sql).get(...values) || null; },
        async all() { return { results: sqlite.prepare(sql).all(...values) }; },
      });
      return make();
    },
  };
  const app = buildApp(d1, {
    uid: "legacy-u1",
    email: "legacy@example.com",
    displayName: "Legacy Student",
    isAdmin: false,
  });
  const res = await app.request("http://localhost/api/users/sync", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      displayName: "Legacy Student",
      telegramUserId: "12345678",
      telegramUsername: "@legacy_tg",
    }),
  });
  assert.equal(res.status, 200);
  const json = await res.json();
  assert.ok(json.data.eveId.startsWith("EV-"));
  assert.equal(json.data.telegramUserId, undefined);
  sqlite.close();
});

test("PUT /api/admin/config and GET /api/app-config store and sanitize official Telegram usernames (while /api/app-content/app_config stays isolated)", async () => {
  const { sqlite, d1 } = isolatedD1();
  sqlite.prepare("INSERT OR IGNORE INTO admins (email, created_at) VALUES (?, ?)").run("pronlike9@gmail.com", 1000);
  const app = buildApp(d1, {
    uid: "owner-1",
    email: "pronlike9@gmail.com",
    displayName: "Owner",
    isAdmin: true,
    emailVerified: true,
  });

  const putRes = await app.request("http://localhost/api/admin/config", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      officialTelegramChannelUsername: "https://t.me/eve_official_updates",
      officialTelegramSupportUsername: "eve_support_desk",
    }),
  });
  assert.equal(putRes.status, 200);
  const putJson = await putRes.json();
  assert.equal(putJson.data.official_telegram_channel_username, "@eve_official_updates");
  assert.equal(putJson.data.official_telegram_support_username, "@eve_support_desk");

  const appContentRes = await app.request("http://localhost/api/app-content/app_config");
  assert.equal(appContentRes.status, 404);

  const appConfigRes = await app.request("http://localhost/api/app-config");
  assert.equal(appConfigRes.status, 200);
  const appConfigJson = await appConfigRes.json();
  assert.equal(appConfigJson.data.officialTelegramChannelUsername, "@eve_official_updates");
  assert.equal(appConfigJson.data.officialTelegramSupportUsername, "@eve_support_desk");

  // Blank clears it cleanly
  const clearRes = await app.request("http://localhost/api/admin/config", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      officialTelegramChannelUsername: "",
      officialTelegramSupportUsername: "",
    }),
  });
  assert.equal(clearRes.status, 200);
  const clearedCfg = await (await app.request("http://localhost/api/app-config")).json();
  assert.equal(clearedCfg.data.officialTelegramChannelUsername, "");
  assert.equal(clearedCfg.data.officialTelegramSupportUsername, "");

  // Invalid username returns 400
  const invalidRes = await app.request("http://localhost/api/admin/config", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ officialTelegramChannelUsername: "bad username!" }),
  });
  assert.equal(invalidRes.status, 400);
});

test("GET /api/premium/status auto-unlocks EVE Premium for Owner and D1 Admins without fake payment orders", async () => {
  const { sqlite, d1 } = isolatedD1();
  sqlite.prepare("INSERT OR IGNORE INTO admins (email, created_at) VALUES (?, ?)").run("pronlike9@gmail.com", 1000);
  sqlite.prepare("INSERT OR IGNORE INTO admins (email, created_at) VALUES (?, ?)").run("subadmin@eve.app", 1000);

  const ownerApp = buildApp(d1, {
    uid: "owner-uid-1",
    email: "pronlike9@gmail.com",
    displayName: "Owner",
    isAdmin: true,
    emailVerified: true,
  });
  const ownerRes = await ownerApp.request("http://localhost/api/premium/status");
  assert.equal(ownerRes.status, 200);
  const ownerJson = await ownerRes.json();
  assert.equal(ownerJson.data.isPremium, true);
  assert.equal(ownerJson.data.isLifetime, true);
  assert.equal(ownerJson.data.status, "ACTIVE");
  assert.equal(ownerJson.data.planName, "EVE Owner Access");
  assert.equal(ownerJson.data.source, "OWNER");

  const adminApp = buildApp(d1, {
    uid: "admin-uid-2",
    email: "subadmin@eve.app",
    displayName: "Sub Admin",
    isAdmin: true,
    emailVerified: true,
  });
  const adminRes = await adminApp.request("http://localhost/api/premium/status");
  assert.equal(adminRes.status, 200);
  const adminJson = await adminRes.json();
  assert.equal(adminJson.data.isPremium, true);
  assert.equal(adminJson.data.isLifetime, true);
  assert.equal(adminJson.data.planName, "EVE Admin Access");

  const studentApp = buildApp(d1, {
    uid: "student-uid-3",
    email: "student@example.com",
    displayName: "Student",
    isAdmin: false,
    emailVerified: true,
  });
  const studentRes = await studentApp.request("http://localhost/api/premium/status");
  assert.equal(studentRes.status, 200);
  const studentJson = await studentRes.json();
  assert.equal(studentJson.data.isPremium, false);
  assert.equal(studentJson.data.status, "NONE");

  assert.equal(sqlite.prepare("SELECT COUNT(*) AS n FROM premium_orders").get().n, 0);
  assert.equal(sqlite.prepare("SELECT COUNT(*) AS n FROM premium_entitlements").get().n, 0);
});
