import test from "node:test";
import assert from "node:assert/strict";
import { Hono } from "hono";
import { isolatedD1 } from "./support/sqliteD1.mjs";
import {
  buildPrompt,
  filterDuplicateQuestions,
  normalizeQuestionText,
} from "../src/ai/generator.ts";
import { generateQuestionsWithRouter } from "../src/services/aiProviderRouter.ts";
import { authMiddleware } from "../src/middleware/authMiddleware.ts";
import { generatedTestRoutes } from "../src/routes/generatedTests.ts";
import {
  telegramMiniAppApi,
  telegramMiniAppPage,
  verifyTelegramInitData,
} from "../src/routes/telegramMiniApp.ts";
import { telegramWebhookRoutes } from "../src/routes/telegramWebhook.ts";
import { readAppConfig } from "../src/util/appConfig.ts";
import {
  matchStudentEdgeCache,
  purgeEdgeCache,
  storeStudentEdgeCache,
  STUDENT_EDGE_CACHE_CONTROL,
} from "../src/util/edgeCache.ts";

function createMockCacheStorage() {
  const store = new Map();
  return {
    store,
    async match(req) {
      const key = typeof req === "string" ? req : req.url;
      const hit = store.get(key);
      return hit ? hit.clone() : undefined;
    },
    async put(req, res) {
      const key = typeof req === "string" ? req : req.url;
      store.set(key, res.clone());
    },
    async delete(req) {
      const key = typeof req === "string" ? req : req.url;
      return store.delete(key);
    },
  };
}

function createSampleMcqJson(prefix, count) {
  const arr = [];
  for (let i = 1; i <= count; i++) {
    arr.push({
      questionText: `${prefix} Question ${i}?`,
      optionA: `Opt A ${i}`,
      optionB: `Opt B ${i}`,
      optionC: `Opt C ${i}`,
      optionD: `Opt D ${i}`,
      correctAnswer: "A",
      explanation: `Explanation for ${prefix} Q${i}.`,
    });
  }
  return JSON.stringify(arr);
}

async function signTelegramInitData(paramsObj, botToken) {
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(paramsObj)) {
    params.set(k, typeof v === "string" ? v : JSON.stringify(v));
  }
  const entries = Array.from(params.entries()).sort(([a], [b]) => a.localeCompare(b));
  const checkStr = entries.map(([k, v]) => `${k}=${v}`).join("\n");
  const enc = new TextEncoder();
  const webAppKey = await crypto.subtle.importKey(
    "raw",
    enc.encode("WebAppData"),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const secretKey = await crypto.subtle.sign("HMAC", webAppKey, enc.encode(botToken));
  const hmacKey = await crypto.subtle.importKey(
    "raw",
    secretKey,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const sig = await crypto.subtle.sign("HMAC", hmacKey, enc.encode(checkStr));
  const hash = Array.from(new Uint8Array(sig))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  params.set("hash", hash);
  return params.toString();
}

test("edgeCache caches student GETs, isolates admins, and purges on mutation", async () => {
  const mockCache = createMockCacheStorage();
  const app = new Hono();
  app.use("*", async (c, next) => {
    const isAdmin = c.req.header("X-Test-Admin") === "1";
    c.set("user", {
      uid: isAdmin ? "admin-1" : "stu-1",
      email: isAdmin ? "admin@test.com" : "student@test.com",
      emailVerified: true,
      isAdmin,
    });
    await next();
  });
  app.get("/api/exams", async (c) => {
    const hit = await matchStudentEdgeCache(c, mockCache);
    if (hit) return hit;
    return storeStudentEdgeCache(c, c.json({ success: true, data: [{ id: "ex1" }] }), mockCache);
  });
  app.post("/api/exams/mutate", async (c) => {
    await purgeEdgeCache(c, ["/api/exams"], mockCache);
    return c.json({ success: true });
  });

  // 1st student call -> MISS, stored
  const r1 = await app.request("/api/exams");
  assert.equal(r1.status, 200);
  assert.equal(r1.headers.get("X-Eve-Edge-Cache"), "MISS");
  assert.equal(r1.headers.get("Cache-Control"), STUDENT_EDGE_CACHE_CONTROL);

  // 2nd student call -> HIT
  const r2 = await app.request("/api/exams");
  assert.equal(r2.status, 200);
  assert.equal(r2.headers.get("X-Eve-Edge-Cache"), "HIT");

  // Admin call -> isolated, never receives or stores student cache
  const rAdmin = await app.request("/api/exams", { headers: { "X-Test-Admin": "1" } });
  assert.equal(rAdmin.status, 200);
  assert.equal(rAdmin.headers.get("X-Eve-Edge-Cache"), null);
  assert.equal(rAdmin.headers.get("Cache-Control"), "no-store");

  // Purge clears stored key
  await app.request("/api/exams/mutate", { method: "POST" });
  assert.equal(mockCache.store.size, 0);
});

test("aiProviderRouter routes explicitly and fails over automatically in auto mode", async () => {
  const calls = [];
  const mockFetch = async (url, init) => {
    const body = JSON.parse(String(init?.body || "{}"));
    calls.push({ url: String(url), model: body.model });
    if (String(url).includes("openrouter.ai")) {
      return new Response("rate limit", { status: 429 });
    }
    if (String(url).includes("api.groq.com")) {
      return new Response("upstream error", { status: 503 });
    }
    return new Response(
      JSON.stringify({
        choices: [{ message: { content: createSampleMcqJson("AutoRecovered", 2) } }],
      }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  };

  const env = {
    OPENROUTER_API_KEY: "or-key-123456",
    GROQ_API_KEY: "groq-key-123456",
    OPENAI_API_KEY: "openai-key-123456",
  };

  const autoRes = await generateQuestionsWithRouter(env, "SSC CGL", "Polity", 2, "", mockFetch, {
    aiProvider: "auto",
  });
  assert.equal(autoRes.provider, "openai");
  assert.equal(autoRes.questions.length, 2);
  assert.ok(calls.some((c) => c.url.includes("openrouter.ai")));
  assert.ok(calls.some((c) => c.url.includes("api.groq.com")));

  // Explicit Groq routing
  const groqOnlyFetch = async (url, init) => {
    const body = JSON.parse(String(init?.body || "{}"));
    assert.ok(String(url).includes("api.groq.com"));
    assert.equal(body.model, "llama-3.3-70b-versatile");
    return new Response(
      JSON.stringify({
        choices: [{ message: { content: createSampleMcqJson("GroqDirect", 1) } }],
      }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  };
  const groqRes = await generateQuestionsWithRouter(env, "UPSC", "History", 1, "", groqOnlyFetch, {
    aiProvider: "groq",
    aiModel: "llama-3.3-70b-versatile",
  });
  assert.equal(groqRes.provider, "groq");
  assert.equal(groqRes.model, "llama-3.3-70b-versatile");
});

test("Paper-Setter prompt rubric and filterDuplicateQuestions deduplicate normalized & hashed stems", async () => {
  const prompt = buildPrompt("UPSC Prelims", "Indian Polity Articles", 5, "Focus on Fundamental Rights", {
    language: "bilingual",
    difficulty: "hard",
  });
  assert.ok(prompt.includes("Senior Paper-Setter"));
  assert.ok(prompt.includes("Bilingual"));
  assert.ok(prompt.includes("HARD"));
  assert.ok(prompt.includes("questionTextHi"));

  const existing = new Set([normalizeQuestionText("What is the capital of India?")]);
  const { unique, skippedCount } = await filterDuplicateQuestions(
    [
      {
        questionText: "1. What is the capital of India?!",
        optionA: "Delhi",
        optionB: "Mumbai",
        optionC: "Kolkata",
        optionD: "Chennai",
        correctAnswer: "A",
        explanation: "New Delhi.",
      },
      {
        questionText: "Which Article guarantees Right to Equality?",
        optionA: "Article 14",
        optionB: "Article 19",
        optionC: "Article 21",
        optionD: "Article 32",
        correctAnswer: "A",
        explanation: "Article 14.",
      },
    ],
    existing
  );
  assert.equal(skippedCount, 1);
  assert.equal(unique.length, 1);
  assert.ok(unique[0].questionText.includes("Right to Equality"));
});

test("POST /api/generated-tests/assemble-from-bank enforces permissions, handles insufficient bank, and picks non-repeating questions", async () => {
  await isolatedD1(async (db) => {
    await db
      .prepare("INSERT INTO exams (id, exam_name, category, question_count, test_number, created_at) VALUES (?, ?, ?, ?, ?, ?)")
      .bind("ex-bank", "SSC CHSL", "SSC", 3, "Test 2", Date.now())
      .run();
    await db
      .prepare("INSERT INTO admins (email, rights_json, created_at) VALUES (?, ?, ?)")
      .bind("owner@test.com", JSON.stringify(["manage_generated_tests"]), Date.now())
      .run();

    // Insert 6 verified questions
    for (let i = 1; i <= 6; i++) {
      await db
        .prepare(
          `INSERT INTO questions (id, exam_id, question_text, option_a, option_b, option_c, option_d, correct_answer, explanation)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
        )
        .bind(`q-${i}`, "ex-bank", `Bank Q${i}?`, `A${i}`, `B${i}`, `C${i}`, `D${i}`, "A", `Exp ${i}`)
        .run();
    }
    // Insert Test 1 using Q1, Q2, Q3
    await db
      .prepare(
        `INSERT INTO generated_tests (id, exam_id, exam_name, test_number, title, generated_at, status, question_count, questions_json)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .bind(
        "gt-1",
        "ex-bank",
        "SSC CHSL",
        "Test 1",
        "SSC CHSL - Test 1",
        Date.now(),
        "live",
        3,
        JSON.stringify([1, 2, 3].map((i) => ({
          questionText: `Bank Q${i}?`,
          optionA: `A${i}`,
          optionB: `B${i}`,
          optionC: `C${i}`,
          optionD: `D${i}`,
          correctAnswer: "A",
          explanation: `Exp ${i}`,
        })))
      )
      .run();

    const makeApp = (isAdmin, email) => {
      const app = new Hono();
      app.use("*", async (c, next) => {
        c.env = { DB: db };
        c.set("user", { uid: "u1", email, emailVerified: true, isAdmin });
        await next();
      });
      app.route("/api/generated-tests", generatedTestRoutes);
      return app;
    };

    // Non-admin -> 403
    const studentRes = await makeApp(false, "stu@test.com").request("/api/generated-tests/assemble-from-bank", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ examId: "ex-bank", questionCount: 3 }),
    });
    assert.equal(studentRes.status, 403);

    const adminApp = makeApp(true, "owner@test.com");
    // Requesting 10 when bank has 6 -> 400 INSUFFICIENT_BANK_QUESTIONS
    const shortRes = await adminApp.request("/api/generated-tests/assemble-from-bank", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ examId: "ex-bank", questionCount: 10 }),
    });
    assert.equal(shortRes.status, 400);

    // Requesting 3 picks ONLY fresh Q4, Q5, Q6 (0 repeats!)
    const okRes = await adminApp.request("/api/generated-tests/assemble-from-bank", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ examId: "ex-bank", questionCount: 3, status: "draft" }),
    });
    assert.equal(okRes.status, 200);
    const body = await okRes.json();
    assert.equal(body.data.freshUsedCount, 3);
    assert.equal(body.data.status, "draft");

    const saved = await db
      .prepare("SELECT questions_json FROM generated_tests WHERE id = ?")
      .bind(body.data.testId)
      .first();
    const qs = JSON.parse(saved.questions_json).map((q) => q.questionText).sort();
    assert.deepEqual(qs, ["Bank Q4?", "Bank Q5?", "Bank Q6?"]);
  });
});

test("telegramWebhook verifies secret & owner ID, runs commands, uploads banner & approves UPI", async () => {
  await isolatedD1(async (db) => {
    const now = Date.now();
    await db
      .prepare("INSERT INTO exams (id, exam_name, category, question_count, test_number, created_at) VALUES (?, ?, ?, ?, ?, ?)")
      .bind("ex-tg", "Railway NTPC", "Railways", 2, "Test 1", now)
      .run();
    for (let i = 1; i <= 3; i++) {
      await db
        .prepare(
          "INSERT INTO questions (id, exam_id, question_text, option_a, option_b, option_c, option_d, correct_answer, explanation) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )
        .bind(`tg-q${i}`, "ex-tg", `NTPC Q${i}?`, "A1", "B1", "C1", "D1", "B", "Exp")
        .run();
    }
    await db
      .prepare("INSERT INTO users (id, email, display_name, eve_id, disabled) VALUES (?, ?, ?, ?, 0)")
      .bind("stu-tg", "rahul@eve.app", "Rahul", "EVE999")
      .run();
    await db
      .prepare(
        `INSERT INTO premium_orders (id, user_id, user_email, plan_id, plan_name, amount, currency, duration_days, is_lifetime, payment_method, provider_order_id, status, created_at, expires_at, paid_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?, 0)`
      )
      .bind("ORD_TG_1", "stu-tg", "rahul@eve.app", "default", "Premium Pro", 99, "INR", 30, 0, "upi", "ORD_TG_1", now, now + 600_000)
      .run();

    const tgCalls = [];
    const origFetch = globalThis.fetch;
    globalThis.fetch = async (url, init) => {
      const sUrl = String(url);
      if (sUrl.includes("api.telegram.org/bot")) {
        const body = init?.body ? JSON.parse(String(init.body)) : {};
        tgCalls.push({ url: sUrl, body });
        if (sUrl.endsWith("/getFile")) {
          return new Response(JSON.stringify({ ok: true, result: { file_path: "photos/b.jpg" } }), { status: 200 });
        }
        if (sUrl.endsWith("/sendDocument") || sUrl.endsWith("/sendPhoto") || sUrl.endsWith("/sendVideo")) {
          return new Response(
            JSON.stringify({ ok: true, result: { message_id: 55, photo: [{ file_id: "f123" }] } }),
            { status: 200 }
          );
        }
        return new Response(JSON.stringify({ ok: true, result: { message_id: 77 } }), { status: 200 });
      }
      if (sUrl.includes("api.telegram.org/file/bot")) {
        // Minimal valid JPEG bytes
        return new Response(new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10]), { status: 200 });
      }
      return origFetch(url, init);
    };

    try {
      const r2Objects = new Map();
      const mockR2 = {
        async put(k, b, o) { r2Objects.set(k, { b, o }); },
        async get(k) { return r2Objects.get(k) || null; },
        async delete(keys) { (Array.isArray(keys) ? keys : [keys]).forEach((k) => r2Objects.delete(k)); },
      };

      const app = new Hono();
      app.use("*", async (c, next) => {
        c.env = {
          DB: db,
          EMOJI_BUCKET: mockR2,
          TELEGRAM_BOT_TOKEN: "12345:TEST_TOKEN",
          TELEGRAM_OWNER_ID: "9001, 9002",
          TELEGRAM_WEBHOOK_SECRET: "secret-abc-999",
        };
        await next();
      });
      app.route("/api/telegram/webhook", telegramWebhookRoutes);

      // 1. Bad webhook secret -> 401
      const badSecret = await app.request("/api/telegram/webhook", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Telegram-Bot-Api-Secret-Token": "wrong" },
        body: JSON.stringify({ message: { from: { id: 9001 }, chat: { id: 9001 }, text: "/status" } }),
      });
      assert.equal(badSecret.status, 401);

      // 2. Non-owner silently ignored
      const nonOwner = await app.request("/api/telegram/webhook", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Telegram-Bot-Api-Secret-Token": "secret-abc-999" },
        body: JSON.stringify({ message: { from: { id: 1111 }, chat: { id: 1111 }, text: "/maintenance on" } }),
      });
      assert.equal(nonOwner.status, 200);
      assert.equal(tgCalls.length, 0);
      assert.equal((await readAppConfig(db)).maintenanceMode, false);

      // 3. Owner /maintenance on + /model groq llama-3.3-70b-versatile
      await app.request("/api/telegram/webhook", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Telegram-Bot-Api-Secret-Token": "secret-abc-999" },
        body: JSON.stringify({ message: { from: { id: 9001, username: "vishu" }, chat: { id: 9001 }, text: "/maintenance on Bot test" } }),
      });
      await app.request("/api/telegram/webhook", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Telegram-Bot-Api-Secret-Token": "secret-abc-999" },
        body: JSON.stringify({ message: { from: { id: 9001 }, chat: { id: 9001 }, text: "/model groq llama-3.3-70b-versatile" } }),
      });
      const cfg = await readAppConfig(db);
      assert.equal(cfg.maintenanceMode, true);
      assert.equal(cfg.maintenanceMessage, "Bot test");
      assert.equal(cfg.aiProvider, "groq");
      assert.equal(cfg.aiModel, "llama-3.3-70b-versatile");

      // 4. Owner /assemble Railway NTPC -> draft with Publish button -> callback publishes live
      await app.request("/api/telegram/webhook", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Telegram-Bot-Api-Secret-Token": "secret-abc-999" },
        body: JSON.stringify({ message: { from: { id: 9001 }, chat: { id: 9001 }, text: "/assemble Railway NTPC" } }),
      });
      const draftTest = await db.prepare("SELECT id, status FROM generated_tests WHERE exam_id = 'ex-tg'").first();
      assert.equal(draftTest.status, "draft");

      await app.request("/api/telegram/webhook", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Telegram-Bot-Api-Secret-Token": "secret-abc-999" },
        body: JSON.stringify({
          callback_query: {
            id: "cq-pub",
            from: { id: 9001 },
            message: { chat: { id: 9001 }, message_id: 77 },
            data: `cb:publish:${draftTest.id}`,
          },
        }),
      });
      const liveTest = await db.prepare("SELECT status FROM generated_tests WHERE id = ?").bind(draftTest.id).first();
      assert.equal(liveTest.status, "live");

      // 5. Direct /banner photo upload
      await app.request("/api/telegram/webhook", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Telegram-Bot-Api-Secret-Token": "secret-abc-999" },
        body: JSON.stringify({
          message: {
            from: { id: 9001 },
            chat: { id: 9001 },
            caption: "/banner https://eve-mock-test.app/offer",
            photo: [{ file_id: "small" }, { file_id: "large_file_id" }],
          },
        }),
      });
      const bannerRow = await db.prepare("SELECT id, link_url, active FROM home_banners").first();
      assert.equal(bannerRow.active, 1);
      assert.equal(bannerRow.link_url, "https://eve-mock-test.app/offer");

      // 6. UPI Order Approval via cb:approve_order:ORD_TG_1 + /ban EVE999
      await app.request("/api/telegram/webhook", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Telegram-Bot-Api-Secret-Token": "secret-abc-999" },
        body: JSON.stringify({
          callback_query: {
            id: "cq-upi",
            from: { id: 9002 },
            message: { chat: { id: 9002 }, message_id: 88 },
            data: "cb:approve_order:ORD_TG_1",
          },
        }),
      });
      const ordAfter = await db.prepare("SELECT status FROM premium_orders WHERE id = 'ORD_TG_1'").first();
      const premAfter = await db.prepare("SELECT status FROM premium_entitlements WHERE user_id = 'stu-tg'").first();
      assert.equal(ordAfter.status, "SUCCESS");
      assert.equal(premAfter.status, "ACTIVE");
    } finally {
      globalThis.fetch = origFetch;
    }
  });
});

test("Telegram Mini App HMAC-SHA256 verification and API routes enforce owner access", async () => {
  await isolatedD1(async (db) => {
    const botToken = "99999:MINIAPP_SECRET_TOKEN";
    const env = { DB: db, TELEGRAM_BOT_TOKEN: botToken, TELEGRAM_OWNER_ID: "7777" };
    const nowSec = Math.floor(Date.now() / 1000);

    const ownerInitData = await signTelegramInitData(
      { auth_date: String(nowSec), query_id: "AAHdF6IQ", user: { id: 7777, username: "vishu" } },
      botToken
    );
    const nonOwnerInitData = await signTelegramInitData(
      { auth_date: String(nowSec), user: { id: 8888, username: "intruder" } },
      botToken
    );
    const expiredInitData = await signTelegramInitData(
      { auth_date: String(nowSec - 90_000), user: { id: 7777, username: "vishu" } },
      botToken
    );

    assert.equal((await verifyTelegramInitData(ownerInitData, env, nowSec)).valid, true);
    assert.equal((await verifyTelegramInitData(ownerInitData + "tamper", env, nowSec)).valid, false);
    assert.equal((await verifyTelegramInitData(expiredInitData, env, nowSec)).valid, false);
    assert.equal((await verifyTelegramInitData(nonOwnerInitData, env, nowSec)).reason, "FORBIDDEN_NON_OWNER");

    const app = new Hono();
    app.use("*", async (c, next) => {
      c.env = env;
      await next();
    });
    app.use("/api/*", authMiddleware);
    app.route("/admin-miniapp", telegramMiniAppPage);
    app.route("/api/telegram/miniapp", telegramMiniAppApi);

    // HTML page serves without Firebase auth
    const htmlRes = await app.request("/admin-miniapp");
    assert.equal(htmlRes.status, 200);
    const html = await htmlRes.text();
    assert.ok(html.includes("telegram-web-app.js"));
    assert.ok(html.includes("--tg-theme-bg-color"));

    // Non-owner -> 403
    const forbiddenRes = await app.request("/api/telegram/miniapp/overview", {
      headers: { "X-Telegram-Init-Data": nonOwnerInitData },
    });
    assert.equal(forbiddenRes.status, 403);

    // Valid owner -> 200 with KPIs & config
    const okRes = await app.request("/api/telegram/miniapp/overview", {
      headers: { "X-Telegram-Init-Data": ownerInitData },
    });
    assert.equal(okRes.status, 200);
    const okBody = await okRes.json();
    assert.equal(okBody.success, true);
    assert.equal(okBody.data.config.aiProvider, "auto");
  });
});


import { batchReadRows, getReadDb } from "../src/util/d1Session.ts";

test("D1 Read Replication session (`getReadDb`) delegates to withSession('first-unconstrained') and falls back cleanly on mock D1", async () => {
  const mockSessionCalls = [];
  const sessionDb = { prepare: () => ({}) };
  const d1WithSession = {
    withSession(constraint) {
      mockSessionCalls.push(constraint);
      return sessionDb;
    },
    prepare: () => ({}),
  };
  assert.equal(getReadDb(d1WithSession), sessionDb);
  assert.deepEqual(mockSessionCalls, ["first-unconstrained"]);

  const legacyD1 = { prepare: () => ({}) };
  assert.equal(getReadDb(legacyD1), legacyD1);

  const throwingD1 = {
    withSession() {
      throw new Error("no replica");
    },
    prepare: () => ({}),
  };
  assert.equal(getReadDb(throwingD1), throwingD1);
});

test("Mini App routes use D1 read session + single db.batch([...]) round-trip and HTML includes 0ms localStorage SWR cache", async () => {
  const botToken = "88888:MINIAPP_SWR_TOKEN";
  const nowSec = Math.floor(Date.now() / 1000);
  const ownerInitData = await signTelegramInitData(
    { auth_date: String(nowSec), user: { id: 7777, username: "vishu" } },
    botToken
  );

  let batchCalls = 0;
  let lastBatchCount = 0;
  const sessionModes = [];

  const sessionReadDb = {
    prepare(sql) {
      return {
        _sql: sql,
        bind() { return this; },
        async all() { return { results: [] }; },
      };
    },
    async batch(stmts) {
      batchCalls++;
      lastBatchCount = stmts.length;
      return stmts.map((s) => {
        if (String(s._sql).includes("app_content")) {
          return { success: true, results: [{ body: JSON.stringify({ aiProvider: "groq", aiModel: "llama-3.3-70b-versatile" }) }] };
        }
        return { success: true, results: [{ c: 42 }] };
      });
    },
  };

  const mockD1 = {
    withSession(constraint) {
      sessionModes.push(constraint);
      return sessionReadDb;
    },
    prepare: sessionReadDb.prepare,
    batch: sessionReadDb.batch,
  };

  const app = new Hono();
  app.use("*", async (c, next) => {
    c.env = { DB: mockD1, TELEGRAM_BOT_TOKEN: botToken, TELEGRAM_OWNER_ID: "7777" };
    await next();
  });
  app.route("/admin-miniapp", telegramMiniAppPage);
  app.route("/api/telegram/miniapp", telegramMiniAppApi);

  const overviewRes = await app.request("/api/telegram/miniapp/overview", {
    headers: { "X-Telegram-Init-Data": ownerInitData },
  });
  assert.equal(overviewRes.status, 200);
  const body = await overviewRes.json();
  assert.equal(body.data.kpis.totalExams, 42);
  assert.equal(body.data.config.aiProvider, "groq");
  assert.deepEqual(sessionModes, ["first-unconstrained"]);
  assert.equal(batchCalls, 1);
  assert.equal(lastBatchCount, 12);

  // Verify HTML UI contains Cache-Control max-age=300, BackButton, HapticFeedback, 3-Card Hub & localStorage SWR v2
  const pageRes = await app.request("/admin-miniapp");
  assert.equal(pageRes.status, 200);
  assert.equal(pageRes.headers.get("Cache-Control"), "public, max-age=300");
  const html = await pageRes.text();
  assert.ok(html.includes("eve_miniapp_swr_v2:"));
  assert.ok(html.includes("localStorage.getItem"));
  assert.ok(html.includes("swrInvalidate"));
  assert.ok(html.includes("Cached · Syncing"));
  assert.ok(html.includes("BackButton"));
  assert.ok(html.includes("HapticFeedback"));
  assert.ok(html.includes("Exams &amp; Content"));
  assert.ok(html.includes("Students &amp; Revenue"));
  assert.ok(html.includes("System &amp; Access"));
});

import { MINIAPP_SCRIPT } from "../src/miniapp/script.ts";

test("Mini App modular JS compiles cleanly with zero syntax errors and all parity API routes work end-to-end", async () => {
  // Verify browser JS string has zero syntax or template-escaping bugs
  assert.doesNotThrow(() => new Function(MINIAPP_SCRIPT));

  await isolatedD1(async (db) => {
    const botToken = "77777:FULL_PARITY_MINIAPP_TOKEN";
    const nowSec = Math.floor(Date.now() / 1000);
    const ownerInitData = await signTelegramInitData(
      { auth_date: String(nowSec), user: { id: 7777, username: "vishu" } },
      botToken
    );

    const r2Objects = new Map();
    const mockR2 = {
      async put(k, b, o) { r2Objects.set(k, { b, o }); },
      async get(k) { return r2Objects.get(k) || null; },
      async delete(keys) { (Array.isArray(keys) ? keys : [keys]).forEach((k) => r2Objects.delete(k)); },
    };

    const app = new Hono();
    app.use("*", async (c, next) => {
      c.env = { DB: db, EMOJI_BUCKET: mockR2, TELEGRAM_BOT_TOKEN: botToken, TELEGRAM_OWNER_ID: "7777" };
      await next();
    });
    app.route("/api/telegram/miniapp", telegramMiniAppApi);

    const h = { "Content-Type": "application/json", "X-Telegram-Init-Data": ownerInitData };

    // 1. Create Exam & Sub-Exam + Bulk JSON Question Import
    const createEx = await app.request("/api/telegram/miniapp/exams", {
      method: "POST",
      headers: h,
      body: JSON.stringify({ examName: "UPSC CSE", category: "UPSC", questionCount: 10, negativeMarkingText: "1/3" }),
    });
    assert.equal(createEx.status, 201);
    const parentId = (await createEx.json()).data.id;

    const createSub = await app.request("/api/telegram/miniapp/exams", {
      method: "POST",
      headers: h,
      body: JSON.stringify({ examName: "UPSC Polity", category: "UPSC", parentExamId: parentId }),
    });
    assert.equal(createSub.status, 201);

    const bulkRes = await app.request(`/api/telegram/miniapp/exams/${parentId}/questions/import`, {
      method: "POST",
      headers: h,
      body: JSON.stringify({
        questions: [
          { questionText: "Art 14?", optionA: "Equality", optionB: "Speech", optionC: "Life", optionD: "Property", correctAnswer: "A", explanation: "Right to Equality" },
          { questionText: "Art 21?", optionA: "Equality", optionB: "Speech", optionC: "Life & Liberty", optionD: "Taxes", correctAnswer: "C", explanation: "Personal Liberty" },
        ],
      }),
    });
    assert.equal(bulkRes.status, 201);
    assert.equal((await bulkRes.json()).count, 2);

    const qListRes = await app.request(`/api/telegram/miniapp/exams/${parentId}/questions`, { headers: h });
    const qList = (await qListRes.json()).data;
    assert.equal(qList.length, 2);

    // 2. Content Hub: Poll + Banner + Broadcast + Flagged Question Fixer
    const pollRes = await app.request("/api/telegram/miniapp/polls", {
      method: "POST",
      headers: h,
      body: JSON.stringify({ question: "Next test?", options: ["GS-1", "CSAT"] }),
    });
    assert.equal(pollRes.status, 201);

    const bannerRes = await app.request("/api/telegram/miniapp/banners", {
      method: "POST",
      headers: h,
      body: JSON.stringify({
        base64: Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0, 16, 74, 70, 73, 70, 0, 1]).toString("base64"),
        mimeType: "image/jpeg",
        linkUrl: "https://eve-app.example.com",
      }),
    });
    assert.equal(bannerRes.status, 201);

    await db
      .prepare("INSERT INTO question_reports (id, question_id, exam_id, exam_name, question_text, reason, student_id, student_email, timestamp, status, report_type) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', 'content')")
      .bind("rep-1", qList[0].id, parentId, "UPSC CSE", "Art 14?", "Typo", "stu-1", "s@test.com", Date.now())
      .run();

    const detailRes = await app.request("/api/telegram/miniapp/reports/rep-1/detail", { headers: h });
    assert.equal(detailRes.status, 200);
    assert.equal((await detailRes.json()).data.question.optionA, "Equality");

    const fixRes = await app.request("/api/telegram/miniapp/reports/rep-1/fix-question", {
      method: "PUT",
      headers: h,
      body: JSON.stringify({
        questionText: "Article 14 deals with?",
        optionA: "Equality before Law",
        optionB: "Freedom of Speech",
        optionC: "Right to Life",
        optionD: "Right to Property",
        correctAnswer: "A",
        explanation: "Article 14 guarantees equality.",
      }),
    });
    assert.equal(fixRes.status, 200);
    const fixedRow = await db.prepare("SELECT status FROM question_reports WHERE id = 'rep-1'").first();
    assert.equal(fixedRow.status, "dismissed");

    // 3. Students Profile, Premium Config, Admin Roles & Audit Log
    await db.prepare("INSERT INTO users (id, email, display_name, eve_id, disabled) VALUES (?, ?, ?, ?, 0)").bind("stu-p", "pro@eve.app", "Priya", "EVE777").run();
    const stuDetail = await app.request("/api/telegram/miniapp/students/stu-p", { headers: h });
    assert.equal(stuDetail.status, 200);
    assert.equal((await stuDetail.json()).data.student.eveId, "EVE777");

    const roleAdd = await app.request("/api/telegram/miniapp/roles", {
      method: "POST",
      headers: h,
      body: JSON.stringify({ email: "mod@eve.app", customTitle: "Moderator" }),
    });
    assert.equal(roleAdd.status, 201);

    const roleEdit = await app.request("/api/telegram/miniapp/roles/mod%40eve.app/rights", {
      method: "PUT",
      headers: h,
      body: JSON.stringify({ rights: ["moderate_feedback"], customTitle: "Senior Mod" }),
    });
    assert.equal(roleEdit.status, 200);

    const auditRes = await app.request("/api/telegram/miniapp/audit-log?category=exams", { headers: h });
    assert.equal(auditRes.status, 200);
    assert.ok((await auditRes.json()).data.length >= 3);

    const ahRes = await app.request("/api/telegram/miniapp/analytics-health", { headers: h });
    assert.equal(ahRes.status, 200);
    const ah = (await ahRes.json()).data;
    assert.ok(ah.infraHealth.backend.status === "verified");
  });
});

test("Mini App script compiles cleanly and reloads views post-mutation without getting stuck on Loading...", async () => {
  assert.doesNotThrow(() => new Function(MINIAPP_SCRIPT));

    function makeEl(id = "") {
      const listeners = {};
      return {
        id,
        innerHTML: "",
        textContent: "",
        value: "",
        dataset: {},
        className: "",
        files: [],
        style: {},
        classList: {
          _set: new Set(),
          add(c) { this._set.add(c); },
          remove(c) { this._set.delete(c); },
          toggle(c) { if (this._set.has(c)) this._set.delete(c); else this._set.add(c); },
          contains(c) { return this._set.has(c); },
        },
        addEventListener(ev, fn) { (listeners[ev] ||= []).push(fn); },
        closest() { return null; },
      };
    }

    const elements = new Map([
      ["viewRoot", makeEl("viewRoot")],
      ["topTitle", makeEl("topTitle")],
      ["topSub", makeEl("topSub")],
      ["syncPill", makeEl("syncPill")],
      ["syncText", makeEl("syncText")],
      ["sheetBackdrop", makeEl("sheetBackdrop")],
      ["sheetTitle", makeEl("sheetTitle")],
      ["sheetBody", makeEl("sheetBody")],
      ["toast", makeEl("toast")],
    ]);
    const docListeners = {};
    const store = new Map();
    const localStorageMock = {
      get length() { return store.size; },
      key(i) { return Array.from(store.keys())[i] ?? null; },
      getItem(k) { return store.has(k) ? store.get(k) : null; },
      setItem(k, v) { store.set(k, String(v)); },
      removeItem(k) { store.delete(k); },
    };

    let fetchCount = 0;
    let examsVersion = 1;
    let failNextOverview = false;
    let slowResolve = null;
    const windowMock = {
      Telegram: {
        WebApp: {
          initData: "query_id=test",
          BackButton: { show() {}, hide() {}, onClick() {} },
          HapticFeedback: { impactOccurred() {}, notificationOccurred() {} },
          ready() {},
          expand() {},
        },
      },
      scrollTo() {},
      addEventListener() {},
      fetch: async (url) => {
        fetchCount++;
        const u = String(url);
        if (u.endsWith("/overview")) {
          if (failNextOverview) {
            failNextOverview = false;
            throw new Error("Simulated network drop");
          }
          return {
            ok: true,
            status: 200,
            json: async () => ({
              success: true,
              data: { kpis: { totalExams: 1, liveExamsCount: 1 }, config: { aiProvider: "auto" } },
            }),
          };
        }
        if (u.endsWith("/exams")) {
          if (examsVersion === 3) {
            await new Promise((r) => { slowResolve = r; });
          }
          return {
            ok: true,
            status: 200,
            json: async () => ({
              success: true,
              data: [
                {
                  id: "ex-1",
                  examName: examsVersion === 1 ? "UPSC Initial" : "UPSC Mutated v2",
                  category: "UPSC",
                  isLive: true,
                  questionsCount: 5,
                  tests: [{ id: "t-1", title: "Mock 1", testNumber: "Test 1", status: examsVersion === 1 ? "draft" : "live", questionCount: 10 }],
                },
              ],
            }),
          };
        }
        if (u.endsWith("/tests/t-1/status")) {
          examsVersion = 2;
          return { ok: true, status: 200, json: async () => ({ success: true, data: { updated: true } }) };
        }
        return { ok: true, status: 200, json: async () => ({ success: true, data: {} }) };
      },
    };
    const documentMock = {
      getElementById(id) {
        if (!elements.has(id)) elements.set(id, makeEl(id));
        return elements.get(id);
      },
      querySelectorAll() { return []; },
      addEventListener(ev, fn) { (docListeners[ev] ||= []).push(fn); },
    };

    const run = new Function(
      "window",
      "document",
      "localStorage",
      "fetch",
      MINIAPP_SCRIPT,
    );
    run(windowMock, documentMock, localStorageMock, windowMock.fetch);

    // Wait for initial cold hub render even when cacheKey ('overview') !== view ('hub')
    await new Promise((r) => setTimeout(r, 30));
    assert.ok(fetchCount >= 1);
    assert.ok(!elements.get("viewRoot").innerHTML.includes("Loading..."));
    assert.equal(elements.get("syncText").textContent, "Live");
    assert.ok(elements.get("syncPill").className.includes("ok"));

    // Navigate to examDetail (route view 'examDetail' !== cacheKey 'exams')
    windowMock.eveMiniAppNavigate("examDetail", { id: "ex-1", examName: "UPSC" });
    await new Promise((r) => setTimeout(r, 30));
    assert.ok(elements.get("viewRoot").innerHTML.includes("UPSC Initial"));
    assert.ok(!elements.get("viewRoot").innerHTML.includes("Loading..."));
    assert.equal(elements.get("syncText").textContent, "Live");

    // Simulate post-mutation reload via click [data-act="toggle-test-live"]
    const toggleNode = makeEl();
    toggleNode.dataset = { act: "toggle-test-live", id: "t-1", next: "live" };
    docListeners.click.forEach((fn) => fn({ target: { closest: () => toggleNode } }));

    // Immediately after reload starts, old content stays visible instead of wiping to Loading...
    await new Promise((r) => setTimeout(r, 5));
    assert.ok(!elements.get("viewRoot").innerHTML.includes("Loading..."));
    await new Promise((r) => setTimeout(r, 30));
    assert.ok(elements.get("viewRoot").innerHTML.includes("Mock 1"));
    assert.ok(elements.get("viewRoot").innerHTML.includes("UPSC Mutated v2"));
    assert.ok(!elements.get("viewRoot").innerHTML.includes("Loading..."));
    assert.equal(elements.get("syncText").textContent, "Live");
    assert.ok(elements.get("syncPill").className.includes("ok"));

    // Cold error shows Retry button instead of hanging on Loading...
    localStorageMock.removeItem("eve_miniapp_swr_v2:overview");
    elements.get("viewRoot").innerHTML = "";
    failNextOverview = true;
    windowMock.eveMiniAppNavigate("hub");
    await new Promise((r) => setTimeout(r, 30));
    assert.ok(elements.get("viewRoot").innerHTML.includes("Simulated network drop"));
    assert.ok(elements.get("viewRoot").innerHTML.includes("reload-view"));

    // Out-of-order response from slow exams request cannot clobber subsequent hub navigation
    examsVersion = 3;
    windowMock.eveMiniAppNavigate("examDetail", { id: "ex-1", examName: "UPSC" });
    await new Promise((r) => setTimeout(r, 10));
    windowMock.eveMiniAppNavigate("hub");
    await new Promise((r) => setTimeout(r, 20));
    assert.ok(elements.get("viewRoot").innerHTML.includes("Exams &amp; Sub-Exams"));
  if (slowResolve) slowResolve();
  await new Promise((r) => setTimeout(r, 20));
  assert.ok(elements.get("viewRoot").innerHTML.includes("Exams &amp; Sub-Exams"));
});
