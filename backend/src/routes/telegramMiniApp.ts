// ============================================================================
// Telegram Mini App Full Admin Panel
// GET /admin-miniapp (Mobile-First Telegram WebApp UI)
// /api/telegram/miniapp/* (HMAC-SHA256 Verified Owner APIs)
// ============================================================================

import { Hono } from "hono";
import { parseAndValidateQuestions } from "../ai/generator";
import { deleteUnused } from "../services/deletion";
import {
  assembleTestFromBank,
  GenerationBusyError,
  InsufficientBankQuestionsError,
  runGeneration,
} from "../services/generation";
import { isTelegramOwner } from "../services/telegramAdminAlerts";
import { AuthUser, Env, ExamRow, GeneratedTestRow, PremiumOrderRow } from "../types";
import { normalizeAppConfigObject, readAppConfig, VALID_AI_PROVIDERS } from "../util/appConfig";
import { prepareAdminAuditLog, runBatch } from "../util/auditLog";
import { batchReadRows, getReadDb, parseAppConfigRow } from "../util/d1Session";
import { purgeEdgeCache } from "../util/edgeCache";
import { activateUserPremium } from "./premium";

export const telegramMiniAppPage = new Hono<{ Bindings: Env; Variables: { user: AuthUser } }>();
export const telegramMiniAppApi = new Hono<{ Bindings: Env; Variables: { user: AuthUser } }>();

export interface TelegramInitDataVerificationResult {
  valid: boolean;
  reason?: string;
  telegramUserId?: number;
  username?: string;
  displayName?: string;
  authDate?: number;
}

/**
 * Verifies Telegram WebApp `initData` strictly using HMAC-SHA256("WebAppData", BOT_TOKEN),
 * enforcing `auth_date` freshness <= 24 hours and owner ID membership.
 */
export async function verifyTelegramInitData(
  initDataRaw: string,
  env: Pick<Env, "TELEGRAM_BOT_TOKEN" | "TELEGRAM_OWNER_ID">,
  nowSeconds: number = Math.floor(Date.now() / 1000)
): Promise<TelegramInitDataVerificationResult> {
  const botToken = String(env.TELEGRAM_BOT_TOKEN || "").trim();
  if (!botToken) {
    return { valid: false, reason: "Bot token not configured" };
  }
  const raw = String(initDataRaw || "").trim();
  if (!raw) {
    return { valid: false, reason: "Missing X-Telegram-Init-Data header" };
  }

  const params = new URLSearchParams(raw);
  const hash = String(params.get("hash") || "").trim().toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(hash)) {
    return { valid: false, reason: "Malformed initData signature hash" };
  }

  const authDate = Number(params.get("auth_date") || "0");
  if (!Number.isSafeInteger(authDate) || authDate <= 0) {
    return { valid: false, reason: "Missing auth_date" };
  }
  if (nowSeconds - authDate > 86400 || authDate - nowSeconds > 300) {
    return { valid: false, reason: "initData expired (>24h)" };
  }

  const entries: Array<[string, string]> = [];
  params.forEach((value, key) => {
    if (key !== "hash") {
      entries.push([key, value]);
    }
  });
  entries.sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
  const dataCheckString = entries.map(([k, v]) => `${k}=${v}`).join("\n");

  const enc = new TextEncoder();
  const webAppKey = await crypto.subtle.importKey(
    "raw",
    enc.encode("WebAppData"),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const secretKeyBytes = await crypto.subtle.sign("HMAC", webAppKey, enc.encode(botToken));
  const dataKey = await crypto.subtle.importKey(
    "raw",
    secretKeyBytes,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signatureBytes = await crypto.subtle.sign("HMAC", dataKey, enc.encode(dataCheckString));
  const expectedHex = Array.from(new Uint8Array(signatureBytes))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");

  let diff = 0;
  for (let i = 0; i < 64; i++) {
    diff |= expectedHex.charCodeAt(i) ^ hash.charCodeAt(i);
  }
  if (diff !== 0) {
    return { valid: false, reason: "Invalid initData HMAC signature" };
  }

  let parsedUser: any = null;
  try {
    parsedUser = JSON.parse(String(params.get("user") || "null"));
  } catch {}

  const tgId = Number(parsedUser?.id);
  if (!Number.isSafeInteger(tgId) || tgId <= 0) {
    return { valid: false, reason: "Missing Telegram user in initData" };
  }
  if (!isTelegramOwner(env, tgId)) {
    return { valid: false, reason: "FORBIDDEN_NON_OWNER", telegramUserId: tgId };
  }

  const username = String(parsedUser?.username || "").trim();
  const displayName = [parsedUser?.first_name, parsedUser?.last_name].filter(Boolean).join(" ").trim() || "Owner";
  return {
    valid: true,
    telegramUserId: tgId,
    username,
    displayName,
    authDate,
  };
}

// Middleware securing all /api/telegram/miniapp/* routes
telegramMiniAppApi.use("*", async (c, next) => {
  const initData = c.req.header("X-Telegram-Init-Data") || c.req.header("x-telegram-init-data") || "";
  const verified = await verifyTelegramInitData(initData, c.env);
  if (!verified.valid) {
    const status = verified.reason === "FORBIDDEN_NON_OWNER" ? 403 : 401;
    return c.json(
      {
        success: false,
        error: status === 403 ? "Forbidden: Owner access only" : verified.reason || "Unauthorized",
      },
      status
    );
  }
  c.set("user", {
    uid: `tg:${verified.telegramUserId}`,
    email: verified.username ? `tg:@${verified.username}` : `tg:${verified.telegramUserId}@telegram.local`,
    displayName: verified.displayName || "Telegram Owner",
    emailVerified: true,
    isAdmin: true,
  });
  await next();
});

// GET /api/telegram/miniapp/overview
telegramMiniAppApi.get("/overview", async (c) => {
  const readDb = getReadDb(c.env.DB);
  const now = Date.now();
  const onlineCutoff = now - 5 * 60 * 1000;

  const [
    examsRows,
    liveTestsRows,
    draftTestsRows,
    usersRows,
    onlineRows,
    premRows,
    ordersRows,
    flagsRows,
    crashesRows,
    cfgRows,
  ] = await batchReadRows<any>(readDb, [
    readDb.prepare("SELECT COUNT(*) AS c FROM exams"),
    readDb.prepare("SELECT COUNT(*) AS c FROM generated_tests WHERE status IN ('live', 'published')"),
    readDb.prepare("SELECT COUNT(*) AS c FROM generated_tests WHERE status IN ('draft', 'paused')"),
    readDb.prepare("SELECT COUNT(*) AS c FROM users"),
    readDb.prepare("SELECT COUNT(*) AS c FROM users WHERE last_active >= ?").bind(onlineCutoff),
    readDb
      .prepare(
        "SELECT COUNT(*) AS c FROM premium_entitlements WHERE status = 'ACTIVE' AND (is_lifetime = 1 OR expires_at IS NULL OR expires_at > ?)"
      )
      .bind(now),
    readDb.prepare("SELECT COUNT(*) AS c FROM premium_orders WHERE status IN ('CREATED', 'PENDING')"),
    readDb.prepare("SELECT COUNT(*) AS c FROM question_reports WHERE status = 'pending'"),
    readDb.prepare("SELECT COUNT(*) AS c FROM crash_signatures WHERE status = 'open'"),
    readDb.prepare("SELECT body FROM app_content WHERE id = 'app_config' LIMIT 1"),
  ]);

  const countVal = (rows: any[]) => Number(rows?.[0]?.c ?? 0);
  const config = parseAppConfigRow(cfgRows?.[0]);

  return c.json({
    success: true,
    data: {
      kpis: {
        totalExams: countVal(examsRows),
        liveTests: countVal(liveTestsRows),
        draftTests: countVal(draftTestsRows),
        totalStudents: countVal(usersRows),
        onlineStudents: countVal(onlineRows),
        activePremium: countVal(premRows),
        pendingOrders: countVal(ordersRows),
        pendingFlags: countVal(flagsRows),
        openCrashes: countVal(crashesRows),
      },
      config,
    },
  });
});

// POST /api/telegram/miniapp/config - Update Maintenance & AI Router settings
telegramMiniAppApi.post("/config", async (c) => {
  const user = c.get("user");
  const db = c.env.DB;
  const body = await c.req.json().catch(() => ({}));
  const prev = await readAppConfig(db).catch(() => normalizeAppConfigObject({}));

  const patch: Record<string, any> = {};
  if (typeof body.maintenanceMode === "boolean") {
    patch.maintenance_mode = body.maintenanceMode;
  }
  if (typeof body.maintenanceMessage === "string") {
    patch.maintenance_message = body.maintenanceMessage.trim().slice(0, 1000);
  }
  if (typeof body.aiProvider === "string") {
    const prov = body.aiProvider.trim().toLowerCase();
    if (!(VALID_AI_PROVIDERS as readonly string[]).includes(prov)) {
      return c.json({ success: false, error: "Invalid AI provider" }, 400);
    }
    patch.ai_provider = prov;
    patch.aiProvider = prov;
  }
  if (typeof body.aiModel === "string") {
    patch.ai_model = body.aiModel.trim().slice(0, 160);
    patch.aiModel = patch.ai_model;
  }

  const nextNorm = normalizeAppConfigObject({ ...prev, ...patch });
  const bodyObj = {
    ...prev,
    ...patch,
    minimum_supported_version_code: nextNorm.minimumSupportedVersionCode,
    maintenance_mode: nextNorm.maintenanceMode,
    maintenance_message: nextNorm.maintenanceMessage,
    default_publish_mode: nextNorm.defaultPublishMode,
    ai_provider: nextNorm.aiProvider,
    aiProvider: nextNorm.aiProvider,
    ai_model: nextNorm.aiModel,
    aiModel: nextNorm.aiModel,
  };

  const saveStmt = db
    .prepare(
      "INSERT INTO app_content (id, title, body, updated_at, updated_by) VALUES ('app_config', 'App Configuration', ?, ?, ?) ON CONFLICT(id) DO UPDATE SET body = json_patch(app_content.body, ?), updated_at = excluded.updated_at, updated_by = excluded.updated_by"
    )
    .bind(JSON.stringify(bodyObj), Date.now(), user.email, JSON.stringify(patch));
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "CONFIG_UPDATED",
    description: `Updated App/AI config from Telegram Mini App (maint=${nextNorm.maintenanceMode}, ai=${nextNorm.aiProvider})`,
    targetType: "app_content",
    targetId: "app_config",
    metadata: patch,
  });
  await runBatch(db, [saveStmt, auditStmt]);
  await purgeEdgeCache(c, ["/api/app-config"]);
  return c.json({ success: true, data: nextNorm });
});

// GET /api/telegram/miniapp/exams
telegramMiniAppApi.get("/exams", async (c) => {
  const readDb = getReadDb(c.env.DB);
  const [examsList, testsList] = await batchReadRows<any>(readDb, [
    readDb.prepare("SELECT * FROM exams ORDER BY created_at DESC"),
    readDb.prepare(
      "SELECT id, exam_id, title, test_number, status, question_count, generated_at FROM generated_tests ORDER BY generated_at DESC LIMIT 120"
    ),
  ]);

  const testsByExam = new Map<string, any[]>();
  for (const t of (testsList || []) as GeneratedTestRow[]) {
    if (!testsByExam.has(t.exam_id)) testsByExam.set(t.exam_id, []);
    testsByExam.get(t.exam_id)!.push({
      id: t.id,
      title: t.title,
      testNumber: t.test_number,
      status: t.status,
      questionCount: t.question_count,
      generatedAt: t.generated_at,
    });
  }

  return c.json({
    success: true,
    data: ((examsList || []) as ExamRow[]).map((e) => ({
      id: e.id,
      examName: e.exam_name,
      category: e.category || "",
      questionCount: e.question_count || 20,
      testNumber: e.test_number || "Test 1",
      aiLanguage: e.ai_language || "english",
      aiDifficulty: e.ai_difficulty || "mixed",
      aiPrompt: e.ai_prompt || e.generation_prompt || e.custom_prompt_notes || "",
      tests: testsByExam.get(e.id) || [],
    })),
  });
});

// PUT /api/telegram/miniapp/exams/:id/prompt
telegramMiniAppApi.put("/exams/:id/prompt", async (c) => {
  const user = c.get("user");
  const examId = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  const db = c.env.DB;
  const exam = await db.prepare("SELECT * FROM exams WHERE id = ?").bind(examId).first<ExamRow>();
  if (!exam) return c.json({ success: false, error: "Exam not found" }, 404);

  const aiPrompt = String(body.aiPrompt ?? exam.ai_prompt ?? "").trim().slice(0, 3000);
  const aiLanguage = String(body.aiLanguage || exam.ai_language || "english").trim().toLowerCase();
  const aiDifficulty = String(body.aiDifficulty || exam.ai_difficulty || "mixed").trim().toLowerCase();
  if (!["english", "hindi", "bilingual"].includes(aiLanguage)) {
    return c.json({ success: false, error: "Invalid aiLanguage" }, 400);
  }
  if (!["easy", "medium", "hard", "mixed"].includes(aiDifficulty)) {
    return c.json({ success: false, error: "Invalid aiDifficulty" }, 400);
  }

  const updateStmt = db
    .prepare("UPDATE exams SET ai_prompt = ?, ai_language = ?, ai_difficulty = ? WHERE id = ?")
    .bind(aiPrompt, aiLanguage, aiDifficulty, examId);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "EXAM_PROMPT_UPDATED",
    description: `Updated AI prompt/language/difficulty for '${exam.exam_name}' via Mini App`,
    targetType: "exam",
    targetId: examId,
  });
  await db.batch([updateStmt, auditStmt]);
  await purgeEdgeCache(c, ["/api/exams"]);
  return c.json({ success: true });
});

// POST /api/telegram/miniapp/exams/:id/generate
telegramMiniAppApi.post("/exams/:id/generate", async (c) => {
  const user = c.get("user");
  const examId = c.req.param("id");
  const db = c.env.DB;
  const exam = await db.prepare("SELECT * FROM exams WHERE id = ?").bind(examId).first<ExamRow>();
  if (!exam) return c.json({ success: false, error: "Exam not found" }, 404);

  try {
    const prompt = (exam.ai_prompt || exam.generation_prompt || exam.custom_prompt_notes || "").trim();
    const data = await runGeneration(
      c.env,
      exam,
      exam.question_count || 20,
      exam.test_number || "Test 1",
      prompt,
      "telegram_miniapp",
      undefined,
      user
    );
    await purgeEdgeCache(c, ["/api/generated-tests", "/api/exams"]);
    return c.json({ success: true, data });
  } catch (err: any) {
    if (err instanceof GenerationBusyError) {
      return c.json({ success: false, error: err.message }, 409);
    }
    return c.json({ success: false, error: err?.userFacingMessage || err?.message || "Generation failed" }, 500);
  }
});

// POST /api/telegram/miniapp/exams/:id/assemble
telegramMiniAppApi.post("/exams/:id/assemble", async (c) => {
  const user = c.get("user");
  const examId = c.req.param("id");
  const db = c.env.DB;
  const exam = await db.prepare("SELECT * FROM exams WHERE id = ?").bind(examId).first<ExamRow>();
  if (!exam) return c.json({ success: false, error: "Exam not found" }, 404);

  try {
    const data = await assembleTestFromBank(db, exam, exam.question_count || 20, {
      auditActor: user,
      source: "telegram_miniapp",
    });
    await purgeEdgeCache(c, ["/api/generated-tests", "/api/exams"]);
    return c.json({ success: true, data });
  } catch (err: any) {
    if (err instanceof InsufficientBankQuestionsError) {
      return c.json({ success: false, error: err.message }, 400);
    }
    return c.json({ success: false, error: err?.message || "Assembly failed" }, 500);
  }
});

// PUT /api/telegram/miniapp/tests/:id/status
telegramMiniAppApi.put("/tests/:id/status", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  const status = String(body.status || "").trim().toLowerCase();
  if (!["draft", "paused", "live"].includes(status)) {
    return c.json({ success: false, error: "Invalid status" }, 400);
  }
  const db = c.env.DB;
  const test = await db.prepare("SELECT * FROM generated_tests WHERE id = ?").bind(id).first<GeneratedTestRow>();
  if (!test) return c.json({ success: false, error: "Test not found" }, 404);
  if (status === "live") {
    try {
      const parsed = parseAndValidateQuestions(test.questions_json);
      if (parsed.length !== test.question_count) throw new Error();
    } catch {
      return c.json({ success: false, error: "Test failed validation before publishing" }, 400);
    }
  }
  const updateStmt = db.prepare("UPDATE generated_tests SET status = ? WHERE id = ?").bind(status, id);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "GENERATED_TEST_STATUS_UPDATED",
    description: `Set test '${id}' to '${status}' via Mini App`,
    targetType: "generated_test",
    targetId: id,
  });
  await db.batch([updateStmt, auditStmt]);
  await purgeEdgeCache(c, ["/api/generated-tests", "/api/exams"]);
  return c.json({ success: true });
});

// DELETE /api/telegram/miniapp/tests/:id
telegramMiniAppApi.delete("/tests/:id", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const ok = await deleteUnused(c.env.DB, id, "test", user);
  if (!ok) return c.json({ success: false, error: "Test cannot be deleted (attempted or missing)" }, 409);
  await purgeEdgeCache(c, ["/api/generated-tests", "/api/exams"]);
  return c.json({ success: true });
});

// GET /api/telegram/miniapp/students?filter=all|online|premium|banned&q=...
telegramMiniAppApi.get("/students", async (c) => {
  const readDb = getReadDb(c.env.DB);
  const filter = String(c.req.query("filter") || "all").toLowerCase();
  const q = String(c.req.query("q") || "").trim().toLowerCase();
  const now = Date.now();
  const onlineCutoff = now - 5 * 60 * 1000;

  let [users, orders] = await batchReadRows<any>(readDb, [
    readDb
      .prepare(
        `SELECT u.id, u.eve_id, u.email, u.display_name, u.last_active, COALESCE(u.disabled, 0) AS disabled,
                CASE WHEN EXISTS (
                  SELECT 1 FROM premium_entitlements pe
                  WHERE pe.user_id = u.id AND pe.status = 'ACTIVE'
                    AND (pe.is_lifetime = 1 OR pe.expires_at IS NULL OR pe.expires_at > ?)
                ) THEN 1 ELSE 0 END AS is_premium
         FROM users u
         ORDER BY u.last_active DESC LIMIT 150`
      )
      .bind(now),
    readDb.prepare("SELECT * FROM premium_orders WHERE status IN ('CREATED', 'PENDING') ORDER BY created_at DESC LIMIT 30"),
  ]);

  if (!users.length) {
    try {
      const legacy = await readDb
        .prepare("SELECT id, email, display_name, last_active, COALESCE(disabled, 0) AS disabled FROM users ORDER BY last_active DESC LIMIT 150")
        .all<any>();
      users = legacy?.results || [];
    } catch {}
  }

  const filtered = users.filter((u) => {
    const isOnline = Number(u.last_active || 0) >= onlineCutoff;
    const isPrem = Boolean(u.is_premium);
    const isBanned = Boolean(u.disabled);
    if (filter === "online" && !isOnline) return false;
    if (filter === "premium" && !isPrem) return false;
    if (filter === "banned" && !isBanned) return false;
    if (q) {
      const hay = `${u.email || ""} ${u.display_name || ""} ${u.eve_id || ""} ${u.id || ""}`.toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  });

  return c.json({
    success: true,
    data: {
      students: filtered.map((u) => ({
        id: u.id,
        eveId: u.eve_id || "",
        email: u.email || "",
        displayName: u.display_name || "Student",
        isOnline: Number(u.last_active || 0) >= onlineCutoff,
        isPremium: Boolean(u.is_premium),
        disabled: Boolean(u.disabled),
        lastActive: u.last_active || 0,
      })),
      pendingOrders: ((orders || []) as PremiumOrderRow[]).map((o) => ({
        orderId: o.id,
        userId: o.user_id,
        userEmail: o.user_email,
        planName: o.plan_name,
        amount: o.amount,
        paymentMethod: o.payment_method,
        createdAt: o.created_at,
      })),
    },
  });
});

// POST /api/telegram/miniapp/users/:id/status
telegramMiniAppApi.post("/users/:id/status", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  const disabled = Boolean(body.disabled);
  const db = c.env.DB;
  const updateStmt = db.prepare("UPDATE users SET disabled = ? WHERE id = ?").bind(disabled ? 1 : 0, id);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: disabled ? "USER_BANNED" : "USER_UNBANNED",
    description: `${disabled ? "Banned" : "Unbanned"} user '${id}' via Mini App`,
    targetType: "user",
    targetId: id,
  });
  await db.batch([updateStmt, auditStmt]);
  return c.json({ success: true });
});

// POST /api/telegram/miniapp/users/:id/premium
telegramMiniAppApi.post("/users/:id/premium", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  const days = Math.max(1, Math.min(3650, parseInt(String(body.days || "30"), 10) || 30));
  await activateUserPremium(
    c.env.DB,
    id,
    "miniapp_grant",
    `${days}-Day Premium`,
    `MINIAPP_${Date.now()}`,
    user.uid,
    days,
    false,
    "TELEGRAM_MINIAPP_GRANT",
    {
      user,
      options: {
        actionType: "PREMIUM_GRANTED",
        description: `Granted ${days}d Premium to '${id}' via Mini App`,
        targetType: "user",
        targetId: id,
      },
    }
  );
  return c.json({ success: true });
});

// POST /api/telegram/miniapp/orders/:id/approve & reject
telegramMiniAppApi.post("/orders/:id/approve", async (c) => {
  const user = c.get("user");
  const orderId = c.req.param("id");
  const db = c.env.DB;
  const order = await db.prepare("SELECT * FROM premium_orders WHERE id = ?").bind(orderId).first<PremiumOrderRow>();
  if (!order) return c.json({ success: false, error: "Order not found" }, 404);
  if (order.status !== "SUCCESS" && order.status !== "PAID") {
    const now = Date.now();
    await db
      .prepare("UPDATE premium_orders SET status = 'SUCCESS', provider_payment_id = ?, paid_at = ? WHERE id = ?")
      .bind(`MINIAPP_${now}`, now, orderId)
      .run();
    await activateUserPremium(
      db,
      order.user_id,
      order.plan_id,
      order.plan_name,
      order.id,
      `MINIAPP_${now}`,
      order.duration_days,
      Boolean(order.is_lifetime),
      "TELEGRAM_MINIAPP_APPROVAL",
      {
        user,
        options: {
          actionType: "PREMIUM_ORDER_APPROVED",
          description: `Approved UPI order '${orderId}' via Mini App`,
          targetType: "premium_order",
          targetId: orderId,
        },
      }
    );
  }
  return c.json({ success: true });
});

telegramMiniAppApi.post("/orders/:id/reject", async (c) => {
  const user = c.get("user");
  const orderId = c.req.param("id");
  const db = c.env.DB;
  const updateStmt = db.prepare("UPDATE premium_orders SET status = 'FAILED' WHERE id = ?").bind(orderId);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "PREMIUM_ORDER_REJECTED",
    description: `Rejected UPI order '${orderId}' via Mini App`,
    targetType: "premium_order",
    targetId: orderId,
  });
  await db.batch([updateStmt, auditStmt]);
  return c.json({ success: true });
});

// GET /api/telegram/miniapp/moderation
telegramMiniAppApi.get("/moderation", async (c) => {
  const readDb = getReadDb(c.env.DB);
  const [flags, auditLog, crashes] = await batchReadRows<any>(readDb, [
    readDb.prepare(
      "SELECT id, exam_name, reason, question_text, student_email, timestamp FROM question_reports WHERE status = 'pending' ORDER BY timestamp DESC LIMIT 20"
    ),
    readDb.prepare(
      "SELECT id, action_type, description, admin_email, timestamp FROM admin_audit_log ORDER BY timestamp DESC LIMIT 20"
    ),
    readDb.prepare(
      "SELECT signature, exception_class, top_frame, occurrence_count, status, last_seen FROM crash_signatures WHERE status = 'open' ORDER BY last_seen DESC LIMIT 15"
    ),
  ]);
  return c.json({
    success: true,
    data: {
      flags: flags || [],
      auditLog: auditLog || [],
      crashes: crashes || [],
    },
  });
});

telegramMiniAppApi.post("/reports/:id/dismiss", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;
  const upd = db.prepare("UPDATE question_reports SET status = 'dismissed' WHERE id = ?").bind(id);
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: "QUESTION_REPORT_DISMISSED",
    description: `Dismissed flag '${id}' via Mini App`,
    targetType: "question_report",
    targetId: id,
  });
  await db.batch([upd, stmt]);
  return c.json({ success: true });
});

telegramMiniAppApi.post("/broadcasts", async (c) => {
  const user = c.get("user");
  const body = await c.req.json().catch(() => ({}));
  const title = String(body.title || "").trim().slice(0, 120);
  const message = String(body.message || "").trim().slice(0, 4000);
  if (!title || !message) return c.json({ success: false, error: "Title & message required" }, 400);
  const id = crypto.randomUUID();
  const db = c.env.DB;
  const ins = db
    .prepare(
      "INSERT INTO notifications (id, title, message, sent_at, sent_by, type, target_category) VALUES (?, ?, ?, ?, ?, 'general', 'All Users')"
    )
    .bind(id, title, message, Date.now(), user.email);
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: "BROADCAST_SENT",
    description: `Sent broadcast '${title}' via Mini App`,
    targetType: "notification",
    targetId: id,
  });
  await db.batch([ins, stmt]);
  return c.json({ success: true, data: { id } });
});

// GET /admin-miniapp - Crisp Mobile-First Telegram WebApp UI
telegramMiniAppPage.get("/", (c) => {
  c.header("Content-Type", "text/html; charset=utf-8");
  c.header("Cache-Control", "no-store");
  return c.html(`<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" />
  <title>Eve Admin Control Center</title>
  <script src="https://telegram.org/js/telegram-web-app.js"></script>
  <style>
    :root {
      --bg: var(--tg-theme-bg-color, #0f172a);
      --card: var(--tg-theme-secondary-bg-color, #1e293b);
      --text: var(--tg-theme-text-color, #f8fafc);
      --hint: var(--tg-theme-hint-color, #94a3b8);
      --btn: var(--tg-theme-button-color, #3b82f6);
      --btn-text: var(--tg-theme-button-text-color, #ffffff);
      --danger: #ef4444;
      --ok: #10b981;
      --border: rgba(148, 163, 184, 0.18);
    }
    * { box-sizing: border-box; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
    body { margin: 0; background: var(--bg); color: var(--text); padding: 12px 12px 76px; }
    h1 { font-size: 18px; margin: 0 0 10px; display: flex; align-items: center; justify-content: space-between; }
    .tabs { display: flex; gap: 6px; position: fixed; bottom: 0; left: 0; right: 0; background: var(--card); padding: 8px; border-top: 1px solid var(--border); z-index: 20; }
    .tab-btn { flex: 1; padding: 8px 4px; border: none; border-radius: 10px; background: transparent; color: var(--hint); font-weight: 600; font-size: 12px; cursor: pointer; }
    .tab-btn.active { background: var(--btn); color: var(--btn-text); }
    .panel { display: none; }
    .panel.active { display: block; }
    .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin-bottom: 12px; }
    .kpi { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 10px; }
    .kpi .v { font-size: 20px; font-weight: 700; }
    .kpi .l { font-size: 11px; color: var(--hint); margin-top: 2px; }
    .card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 12px; margin-bottom: 10px; }
    .row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    label { display: block; font-size: 12px; color: var(--hint); margin: 8px 0 4px; }
    input, select, textarea { width: 100%; padding: 9px 10px; border-radius: 10px; border: 1px solid var(--border); background: var(--bg); color: var(--text); font-size: 14px; }
    button.btn { border: none; border-radius: 10px; padding: 9px 12px; font-weight: 600; font-size: 13px; cursor: pointer; background: var(--btn); color: var(--btn-text); }
    button.btn.sec { background: transparent; border: 1px solid var(--border); color: var(--text); }
    button.btn.ok { background: var(--ok); color: #fff; }
    button.btn.danger { background: var(--danger); color: #fff; }
    .badge { display: inline-block; padding: 2px 7px; border-radius: 99px; font-size: 11px; font-weight: 600; background: rgba(59,130,246,.16); color: var(--btn); }
    .badge.ok { background: rgba(16,185,129,.18); color: var(--ok); }
    .badge.err { background: rgba(239,68,68,.18); color: var(--danger); }
    #toast { position: fixed; top: 10px; left: 50%; transform: translateX(-50%); background: #111827; color: #fff; padding: 8px 14px; border-radius: 99px; font-size: 12px; display: none; z-index: 50; }
  </style>
</head>
<body>
  <div id="toast"></div>
  <h1><span>⚡ Eve Admin Center</span><span id="ownerBadge" class="badge">Mini App</span></h1>

  <div id="tab-overview" class="panel active">
    <div id="kpiGrid" class="grid"></div>
    <div class="card">
      <div class="row" style="justify-content:space-between">
        <div><strong>Maintenance Mode</strong><div id="maintStatus" style="font-size:12px;color:var(--hint)">Loading...</div></div>
        <button id="maintToggleBtn" class="btn danger" onclick="toggleMaint()">Toggle</button>
      </div>
      <label>Maintenance Message</label>
      <input id="maintMsgInput" placeholder="Scheduled maintenance..." />
      <label>AI Provider</label>
      <select id="aiProviderSelect">
        <option value="auto">auto (openrouter -> groq -> openai -> gemini)</option>
        <option value="openrouter">openrouter</option>
        <option value="groq">groq</option>
        <option value="openai">openai</option>
        <option value="gemini">gemini</option>
      </select>
      <label>AI Model Override (optional)</label>
      <input id="aiModelInput" placeholder="e.g. llama-3.3-70b-versatile or google/gemini-2.5-pro" />
      <div class="row" style="margin-top:10px">
        <button class="btn" style="flex:1" onclick="saveConfig()">Save Config & AI Settings</button>
        <button class="btn sec" onclick="loadOverview()">Refresh</button>
      </div>
    </div>
  </div>

  <div id="tab-exams" class="panel">
    <div class="row" style="margin-bottom:8px">
      <button class="btn sec" style="flex:1" onclick="loadExams()">Reload Exams & Tests</button>
    </div>
    <div id="examsList"></div>
  </div>

  <div id="tab-students" class="panel">
    <div class="card">
      <strong>Pending UPI Orders</strong>
      <div id="ordersList" style="margin-top:8px"></div>
    </div>
    <div class="card">
      <div class="row">
        <select id="studentFilter" style="flex:1" onchange="loadStudents()">
          <option value="all">All Students</option>
          <option value="online">Online Now</option>
          <option value="premium">Premium</option>
          <option value="banned">Banned</option>
        </select>
        <input id="studentSearch" style="flex:2" placeholder="Search email / EVE ID..." oninput="loadStudents()" />
      </div>
      <div id="studentsList" style="margin-top:10px"></div>
    </div>
  </div>

  <div id="tab-mod" class="panel">
    <div class="card">
      <strong>Send Student Broadcast</strong>
      <label>Title</label><input id="bcTitle" placeholder="Important Update" />
      <label>Message</label><textarea id="bcMsg" rows="2" placeholder="New mock test live!"></textarea>
      <button class="btn" style="margin-top:8px;width:100%" onclick="sendBroadcast()">Send Broadcast Now</button>
    </div>
    <div class="card"><strong>Flagged Questions</strong><div id="flagsList" style="margin-top:8px"></div></div>
    <div class="card"><strong>Open Crash Signatures</strong><div id="crashesList" style="margin-top:8px"></div></div>
    <div class="card"><strong>Recent Audit Log</strong><div id="auditList" style="margin-top:8px"></div></div>
  </div>

  <div class="tabs">
    <button class="tab-btn active" onclick="switchTab('overview', this)">Overview</button>
    <button class="tab-btn" onclick="switchTab('exams', this)">Exams</button>
    <button class="tab-btn" onclick="switchTab('students', this)">Students/UPI</button>
    <button class="tab-btn" onclick="switchTab('mod', this)">Mod/Health</button>
  </div>

<script>
const tg = window.Telegram && window.Telegram.WebApp;
if (tg) { tg.ready(); tg.expand(); }
const initData = (tg && tg.initData) || "";
const SWR_PREFIX = 'eve_miniapp_swr_v1:';
const swrRenderedJson = {};
let currentMaint = false;

function swrGet(key) {
  try {
    const raw = localStorage.getItem(SWR_PREFIX + key);
    return raw ? JSON.parse(raw) : null;
  } catch (e) { return null; }
}
function swrSet(key, data) {
  try {
    const raw = JSON.stringify(data);
    localStorage.setItem(SWR_PREFIX + key, raw);
    return raw;
  } catch (e) { return ''; }
}
function swrInvalidate(prefixes) {
  try {
    const list = Array.isArray(prefixes) ? prefixes : [prefixes];
    const toRemove = [];
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k && k.indexOf(SWR_PREFIX) === 0) {
        const shortKey = k.slice(SWR_PREFIX.length);
        if (list.some(p => shortKey.indexOf(p) === 0)) toRemove.push(k);
      }
    }
    toRemove.forEach(k => localStorage.removeItem(k));
  } catch (e) {}
}
function setSyncBadge(text, ok) {
  const b = document.getElementById('ownerBadge');
  if (!b) return;
  b.textContent = text;
  b.className = 'badge' + (ok ? ' ok' : '');
}
function toast(m) {
  const el = document.getElementById('toast');
  el.textContent = m; el.style.display = 'block';
  setTimeout(() => { el.style.display = 'none'; }, 2400);
}
function esc(s) {
  return String(s ?? '').replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
}
async function api(path, opts = {}) {
  const res = await fetch('/api/telegram/miniapp' + path, {
    ...opts,
    headers: { 'Content-Type': 'application/json', 'X-Telegram-Init-Data': initData, ...(opts.headers || {}) }
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.success === false) throw new Error(data.error || ('HTTP ' + res.status));
  return data;
}
function switchTab(name, btn) {
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
  document.getElementById('tab-' + name).classList.add('active');
  btn.classList.add('active');
  if (name === 'overview') loadOverview();
  if (name === 'exams') loadExams();
  if (name === 'students') loadStudents();
  if (name === 'mod') loadModeration();
}
function renderOverview(data) {
  if (!data) return;
  const k = data.kpis || {}, c = data.config || {};
  currentMaint = !!c.maintenanceMode;
  document.getElementById('maintStatus').textContent = currentMaint ? 'ACTIVE (Students blocked)' : 'OFF (App normal)';
  document.getElementById('maintToggleBtn').textContent = currentMaint ? 'Turn OFF' : 'Turn ON';
  document.getElementById('maintToggleBtn').className = 'btn ' + (currentMaint ? 'ok' : 'danger');
  document.getElementById('maintMsgInput').value = c.maintenanceMessage || '';
  document.getElementById('aiProviderSelect').value = c.aiProvider || 'auto';
  document.getElementById('aiModelInput').value = c.aiModel || '';
  const cards = [
    ['Online Now', k.onlineStudents], ['Total Students', k.totalStudents],
    ['Live / Draft Tests', k.liveTests + ' / ' + k.draftTests], ['Premium Active', k.activePremium],
    ['Pending UPI', k.pendingOrders], ['Flags / Crashes', k.pendingFlags + ' / ' + k.openCrashes]
  ];
  document.getElementById('kpiGrid').innerHTML = cards.map(([l, v]) =>
    '<div class="kpi"><div class="v">' + esc(v) + '</div><div class="l">' + esc(l) + '</div></div>'
  ).join('');
}
async function loadOverview() {
  const cached = swrGet('overview');
  if (cached) {
    renderOverview(cached);
    swrRenderedJson['overview'] = JSON.stringify(cached);
    setSyncBadge('Cached · Syncing', false);
  } else {
    setSyncBadge('Syncing...', false);
  }
  try {
    const { data } = await api('/overview');
    const freshJson = JSON.stringify(data);
    if (swrRenderedJson['overview'] !== freshJson) {
      renderOverview(data);
      swrRenderedJson['overview'] = freshJson;
    }
    swrSet('overview', data);
    setSyncBadge('Live', true);
  } catch (e) { setSyncBadge(cached ? 'Offline Cache' : 'Error', false); toast(e.message); }
}
async function toggleMaint() {
  try {
    await api('/config', { method: 'POST', body: JSON.stringify({ maintenanceMode: !currentMaint }) });
    swrInvalidate('overview');
    toast('Maintenance toggled'); loadOverview();
  } catch (e) { toast(e.message); }
}
async function saveConfig() {
  try {
    await api('/config', {
      method: 'POST',
      body: JSON.stringify({
        maintenanceMessage: document.getElementById('maintMsgInput').value,
        aiProvider: document.getElementById('aiProviderSelect').value,
        aiModel: document.getElementById('aiModelInput').value
      })
    });
    swrInvalidate('overview');
    toast('Config saved'); loadOverview();
  } catch (e) { toast(e.message); }
}
function renderExams(data) {
  document.getElementById('examsList').innerHTML = (data || []).map(ex => {
    const testsHtml = (ex.tests || []).slice(0, 6).map(t =>
      '<div class="row" style="justify-content:space-between;padding:6px 0;border-top:1px solid var(--border)">' +
        '<div><strong>' + esc(t.title) + '</strong> <span class="badge ' + (t.status==='live'?'ok':'') + '">' + esc(t.status) + '</span></div>' +
        '<div class="row">' +
          (t.status !== 'live' ? '<button class="btn ok" onclick="setTestStatus(\\'' + esc(t.id) + '\\',\\'live\\')">Publish</button>' : '<button class="btn sec" onclick="setTestStatus(\\'' + esc(t.id) + '\\',\\'paused\\')">Pause</button>') +
          '<button class="btn danger" onclick="delTest(\\'' + esc(t.id) + '\\')">Del</button>' +
        '</div>' +
      '</div>'
    ).join('');
    return '<div class="card">' +
      '<div class="row" style="justify-content:space-between"><strong>' + esc(ex.examName) + '</strong><span class="badge">' + esc(ex.questionCount) + ' Qs</span></div>' +
      '<div class="row" style="margin-top:8px">' +
        '<select id="lang-' + esc(ex.id) + '" style="flex:1"><option value="english"' + (ex.aiLanguage==='english'?' selected':'') + '>English</option><option value="hindi"' + (ex.aiLanguage==='hindi'?' selected':'') + '>Hindi</option><option value="bilingual"' + (ex.aiLanguage==='bilingual'?' selected':'') + '>Bilingual</option></select>' +
        '<select id="diff-' + esc(ex.id) + '" style="flex:1"><option value="mixed"' + (ex.aiDifficulty==='mixed'?' selected':'') + '>Mixed</option><option value="easy"' + (ex.aiDifficulty==='easy'?' selected':'') + '>Easy</option><option value="medium"' + (ex.aiDifficulty==='medium'?' selected':'') + '>Medium</option><option value="hard"' + (ex.aiDifficulty==='hard'?' selected':'') + '>Hard</option></select>' +
      '</div>' +
      '<textarea id="pr-' + esc(ex.id) + '" rows="2" style="margin-top:6px" placeholder="Custom AI prompt">' + esc(ex.aiPrompt) + '</textarea>' +
      '<div class="row" style="margin-top:8px">' +
        '<button class="btn sec" onclick="saveExamPrompt(\\'' + esc(ex.id) + '\\')">Save Prompt</button>' +
        '<button class="btn" onclick="genExam(\\'' + esc(ex.id) + '\\')">⚡ AI Generate</button>' +
        '<button class="btn ok" onclick="asmExam(\\'' + esc(ex.id) + '\\')">🏦 Bank ₹0</button>' +
      '</div>' + testsHtml +
    '</div>';
  }).join('') || '<div class="card">No exams found.</div>';
}
async function loadExams() {
  const cached = swrGet('exams');
  if (cached) {
    renderExams(cached);
    swrRenderedJson['exams'] = JSON.stringify(cached);
    setSyncBadge('Cached · Syncing', false);
  } else {
    setSyncBadge('Syncing...', false);
  }
  try {
    const { data } = await api('/exams');
    const freshJson = JSON.stringify(data);
    if (swrRenderedJson['exams'] !== freshJson) {
      renderExams(data);
      swrRenderedJson['exams'] = freshJson;
    }
    swrSet('exams', data);
    setSyncBadge('Live', true);
  } catch (e) { setSyncBadge(cached ? 'Offline Cache' : 'Error', false); toast(e.message); }
}
async function saveExamPrompt(id) {
  try {
    await api('/exams/' + id + '/prompt', {
      method: 'PUT',
      body: JSON.stringify({
        aiPrompt: document.getElementById('pr-' + id).value,
        aiLanguage: document.getElementById('lang-' + id).value,
        aiDifficulty: document.getElementById('diff-' + id).value
      })
    });
    swrInvalidate('exams');
    toast('Prompt updated');
  } catch (e) { toast(e.message); }
}
async function genExam(id) { try { toast('Generating...'); await api('/exams/' + id + '/generate', { method: 'POST' }); swrInvalidate(['exams', 'overview']); toast('AI Test generated!'); loadExams(); } catch (e) { toast(e.message); } }
async function asmExam(id) { try { await api('/exams/' + id + '/assemble', { method: 'POST' }); swrInvalidate(['exams', 'overview']); toast('Assembled from bank!'); loadExams(); } catch (e) { toast(e.message); } }
async function setTestStatus(id, st) { try { await api('/tests/' + id + '/status', { method: 'PUT', body: JSON.stringify({ status: st }) }); swrInvalidate(['exams', 'overview']); toast('Updated ' + st); loadExams(); } catch (e) { toast(e.message); } }
async function delTest(id) { try { await api('/tests/' + id, { method: 'DELETE' }); swrInvalidate(['exams', 'overview']); toast('Deleted test'); loadExams(); } catch (e) { toast(e.message); } }
function renderStudents(data) {
  document.getElementById('ordersList').innerHTML = (data.pendingOrders || []).map(o =>
    '<div class="row" style="justify-content:space-between;padding:6px 0;border-top:1px solid var(--border)">' +
      '<div><div><strong>' + esc(o.userEmail || o.userId) + '</strong></div><div style="font-size:11px;color:var(--hint)">₹' + esc(o.amount) + ' · ' + esc(o.orderId) + '</div></div>' +
      '<div class="row"><button class="btn ok" onclick="approveOrder(\\'' + esc(o.orderId) + '\\')">✅</button><button class="btn danger" onclick="rejectOrder(\\'' + esc(o.orderId) + '\\')">❌</button></div>' +
    '</div>'
  ).join('') || '<div style="font-size:12px;color:var(--hint)">No pending orders</div>';
  document.getElementById('studentsList').innerHTML = (data.students || []).map(u =>
    '<div class="row" style="justify-content:space-between;padding:7px 0;border-top:1px solid var(--border)">' +
      '<div><div><strong>' + esc(u.displayName) + '</strong> ' + (u.isOnline?'🟢':'') + (u.isPremium?' 💎':'') + (u.disabled?' 🚫':'') + '</div><div style="font-size:11px;color:var(--hint)">' + esc(u.email) + '</div></div>' +
      '<div class="row"><button class="btn sec" onclick="grantPrem(\\'' + esc(u.id) + '\\')">+30d</button><button class="btn ' + (u.disabled?'ok':'danger') + '" onclick="toggleBan(\\'' + esc(u.id) + '\\',' + (!u.disabled) + ')">' + (u.disabled?'Unban':'Ban') + '</button></div>' +
    '</div>'
  ).join('') || '<div style="font-size:12px;color:var(--hint)">No students matched</div>';
}
async function loadStudents() {
  const f = document.getElementById('studentFilter').value;
  const q = document.getElementById('studentSearch').value;
  const cacheKey = 'students:' + f + ':' + q;
  const cached = swrGet(cacheKey);
  if (cached) {
    renderStudents(cached);
    swrRenderedJson[cacheKey] = JSON.stringify(cached);
    setSyncBadge('Cached · Syncing', false);
  } else {
    setSyncBadge('Syncing...', false);
  }
  try {
    const { data } = await api('/students?filter=' + encodeURIComponent(f) + '&q=' + encodeURIComponent(q));
    const freshJson = JSON.stringify(data);
    if (swrRenderedJson[cacheKey] !== freshJson) {
      renderStudents(data);
      swrRenderedJson[cacheKey] = freshJson;
    }
    swrSet(cacheKey, data);
    setSyncBadge('Live', true);
  } catch (e) { setSyncBadge(cached ? 'Offline Cache' : 'Error', false); toast(e.message); }
}
async function approveOrder(id) { try { await api('/orders/' + id + '/approve', { method: 'POST' }); swrInvalidate(['students:', 'overview']); toast('Order approved'); loadStudents(); } catch (e) { toast(e.message); } }
async function rejectOrder(id) { try { await api('/orders/' + id + '/reject', { method: 'POST' }); swrInvalidate(['students:', 'overview']); toast('Order rejected'); loadStudents(); } catch (e) { toast(e.message); } }
async function grantPrem(id) { try { await api('/users/' + id + '/premium', { method: 'POST', body: JSON.stringify({ days: 30 }) }); swrInvalidate(['students:', 'overview']); toast('+30d Premium granted'); loadStudents(); } catch (e) { toast(e.message); } }
async function toggleBan(id, disabled) { try { await api('/users/' + id + '/status', { method: 'POST', body: JSON.stringify({ disabled }) }); swrInvalidate(['students:', 'overview']); toast(disabled ? 'Banned' : 'Unbanned'); loadStudents(); } catch (e) { toast(e.message); } }
function renderModeration(data) {
  document.getElementById('flagsList').innerHTML = (data.flags || []).map(f =>
    '<div class="row" style="justify-content:space-between;padding:6px 0;border-top:1px solid var(--border)">' +
      '<div style="flex:1"><strong>' + esc(f.exam_name) + '</strong> (' + esc(f.reason) + ')<div style="font-size:11px;color:var(--hint)">' + esc(String(f.question_text||'').slice(0,90)) + '</div></div>' +
      '<button class="btn sec" onclick="dismissFlag(\\'' + esc(f.id) + '\\')">Dismiss</button>' +
    '</div>'
  ).join('') || '<div style="font-size:12px;color:var(--hint)">No flagged questions</div>';
  document.getElementById('crashesList').innerHTML = (data.crashes || []).map(cr =>
    '<div style="padding:6px 0;border-top:1px solid var(--border)"><strong>' + esc(cr.exception_class) + '</strong> ×' + esc(cr.occurrence_count) + '<div style="font-size:11px;color:var(--hint)">' + esc(cr.top_frame) + '</div></div>'
  ).join('') || '<div style="font-size:12px;color:var(--hint)">Zero open crash signatures</div>';
  document.getElementById('auditList').innerHTML = (data.auditLog || []).map(a =>
    '<div style="padding:5px 0;border-top:1px solid var(--border);font-size:12px"><span class="badge">' + esc(a.action_type) + '</span> ' + esc(a.description) + '</div>'
  ).join('') || '<div style="font-size:12px;color:var(--hint)">No logs</div>';
}
async function loadModeration() {
  const cached = swrGet('moderation');
  if (cached) {
    renderModeration(cached);
    swrRenderedJson['moderation'] = JSON.stringify(cached);
    setSyncBadge('Cached · Syncing', false);
  } else {
    setSyncBadge('Syncing...', false);
  }
  try {
    const { data } = await api('/moderation');
    const freshJson = JSON.stringify(data);
    if (swrRenderedJson['moderation'] !== freshJson) {
      renderModeration(data);
      swrRenderedJson['moderation'] = freshJson;
    }
    swrSet('moderation', data);
    setSyncBadge('Live', true);
  } catch (e) { setSyncBadge(cached ? 'Offline Cache' : 'Error', false); toast(e.message); }
}
async function dismissFlag(id) { try { await api('/reports/' + id + '/dismiss', { method: 'POST' }); swrInvalidate(['moderation', 'overview']); toast('Dismissed'); loadModeration(); } catch (e) { toast(e.message); } }
async function sendBroadcast() {
  try {
    await api('/broadcasts', { method: 'POST', body: JSON.stringify({ title: document.getElementById('bcTitle').value, message: document.getElementById('bcMsg').value }) });
    document.getElementById('bcTitle').value = ''; document.getElementById('bcMsg').value = '';
    swrInvalidate('moderation');
    toast('Broadcast sent'); loadModeration();
  } catch (e) { toast(e.message); }
}
loadOverview();
</script>
</body>
</html>`);
});
