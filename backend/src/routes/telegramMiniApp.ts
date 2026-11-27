// ============================================================================
// Telegram Mini App Full Admin Panel
// GET /admin-miniapp (Telegram-Native 3-Card Hub UI, Cache-Control max-age=300)
// /api/telegram/miniapp/* (HMAC-SHA256 Verified Owner APIs, D1 Read Replication)
// ============================================================================

import { Hono } from "hono";
import { parseAndValidateQuestions } from "../ai/generator";
import { ALL_ADMIN_RIGHTS, AdminRight, HARDCODED_ADMIN_EMAILS, isOwnerEmail } from "../auth";
import { renderMiniAppHtml } from "../miniapp/template";
import { deletionInfo, deleteUnused } from "../services/deletion";
import {
  assembleTestFromBank,
  GenerationBusyError,
  InsufficientBankQuestionsError,
  runGeneration,
} from "../services/generation";
import { cleanupMedia } from "../services/mediaCleanup";
import { isTelegramOwner } from "../services/telegramAdminAlerts";
import { AssetStorage } from "../storage";
import { AuthUser, Env, ExamRow, GeneratedTestRow, HomeBannerRow, PremiumOrderRow, QuestionReportRow, QuestionRow } from "../types";
import {
  normalizeAppConfigObject,
  readAppConfig,
  sanitizeTelegramAtUsername,
  VALID_AI_PROVIDERS,
} from "../util/appConfig";
import { prepareAdminAuditLog, runBatch } from "../util/auditLog";
import {
  buildAvatarProxyUrl,
  detectAvatarImage,
  extractStorageAvatarKey,
  getTableColumns,
  parseAvatarUploadPayload,
} from "../util/avatarMedia";
import { batchReadRows, getReadDb, parseAppConfigRow } from "../util/d1Session";
import { purgeEdgeCache } from "../util/edgeCache";
import { getUtcYearMonth } from "../util/usageTracker";
import { parseGeneratedQuestionId } from "./attempts";
import { parseNegativeMarking, safeSyllabusFilename, validSyllabusPdf } from "./exams";
import { activateUserPremium, getPremiumConfig } from "./premium";

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

const EMAIL_REGEX =
  /^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$/;

const NATURAL_TEST_COMPARE = (a: { testNumber?: string; generatedAt?: number }, b: { testNumber?: string; generatedAt?: number }) => {
  const ma = String(a.testNumber || "").match(/(\d+)/g);
  const mb = String(b.testNumber || "").match(/(\d+)/g);
  const na = ma ? Number(ma[ma.length - 1]) : Number.MAX_SAFE_INTEGER;
  const nb = mb ? Number(mb[mb.length - 1]) : Number.MAX_SAFE_INTEGER;
  if (na !== nb) return na - nb;
  return Number(a.generatedAt || 0) - Number(b.generatedAt || 0);
};

const AUDIT_CATEGORY_PREDICATES: Record<string, string> = {
  exams:
    "(action_type LIKE 'EXAM_%' OR action_type LIKE 'TEST_%' OR action_type LIKE 'GENERATED_TEST_%' OR action_type LIKE 'QUESTION_%' OR action_type LIKE 'SYLLABUS_%' OR action_type LIKE 'GENERATE_%' OR action_type = 'ASSEMBLE_FROM_BANK' OR action_type = 'JOB_RETRIED')",
  students:
    "(action_type LIKE 'USER_%' OR action_type LIKE 'PREMIUM_%' OR action_type LIKE 'PAYMENT_%')",
  content:
    "(action_type LIKE 'POLL_%' OR action_type LIKE 'BROADCAST_%' OR action_type LIKE 'BANNER_%' OR action_type LIKE 'REPORT_%' OR action_type LIKE 'FLAGGED_%' OR action_type LIKE 'ANNOUNCEMENT_CHANNEL_%' OR action_type LIKE 'FEEDBACK_%' OR action_type LIKE 'APP_CONTENT_%')",
};

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
  if (!botToken) return { valid: false, reason: "Bot token not configured" };
  const raw = String(initDataRaw || "").trim();
  if (!raw) return { valid: false, reason: "Missing X-Telegram-Init-Data header" };

  const params = new URLSearchParams(raw);
  const hash = String(params.get("hash") || "").trim().toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(hash)) return { valid: false, reason: "Malformed initData signature hash" };

  const authDate = Number(params.get("auth_date") || "0");
  if (!Number.isSafeInteger(authDate) || authDate <= 0) return { valid: false, reason: "Missing auth_date" };
  if (nowSeconds - authDate > 86400 || authDate - nowSeconds > 300) {
    return { valid: false, reason: "initData expired (>24h)" };
  }

  const entries: Array<[string, string]> = [];
  params.forEach((value, key) => {
    if (key !== "hash") entries.push([key, value]);
  });
  entries.sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
  const dataCheckString = entries.map(([k, v]) => `${k}=${v}`).join("\n");

  const enc = new TextEncoder();
  const webAppKey = await crypto.subtle.importKey("raw", enc.encode("WebAppData"), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const secretKeyBytes = await crypto.subtle.sign("HMAC", webAppKey, enc.encode(botToken));
  const dataKey = await crypto.subtle.importKey("raw", secretKeyBytes, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const signatureBytes = await crypto.subtle.sign("HMAC", dataKey, enc.encode(dataCheckString));
  const expectedHex = Array.from(new Uint8Array(signatureBytes))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");

  let diff = 0;
  for (let i = 0; i < 64; i++) diff |= expectedHex.charCodeAt(i) ^ hash.charCodeAt(i);
  if (diff !== 0) return { valid: false, reason: "Invalid initData HMAC signature" };

  let parsedUser: any = null;
  try {
    parsedUser = JSON.parse(String(params.get("user") || "null"));
  } catch {}

  const tgId = Number(parsedUser?.id);
  if (!Number.isSafeInteger(tgId) || tgId <= 0) return { valid: false, reason: "Missing Telegram user in initData" };
  if (!isTelegramOwner(env, tgId)) {
    return { valid: false, reason: "FORBIDDEN_NON_OWNER", telegramUserId: tgId };
  }

  const username = String(parsedUser?.username || "").trim();
  const displayName = [parsedUser?.first_name, parsedUser?.last_name].filter(Boolean).join(" ").trim() || "Owner";
  return { valid: true, telegramUserId: tgId, username, displayName, authDate };
}

// Middleware securing all /api/telegram/miniapp/* routes
telegramMiniAppApi.use("*", async (c, next) => {
  const initData = c.req.header("X-Telegram-Init-Data") || c.req.header("x-telegram-init-data") || "";
  const verified = await verifyTelegramInitData(initData, c.env);
  if (!verified.valid) {
    const status = verified.reason === "FORBIDDEN_NON_OWNER" ? 403 : 401;
    return c.json(
      { success: false, error: status === 403 ? "Forbidden: Owner access only" : verified.reason || "Unauthorized" },
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

// ============================================================================
// 1. OVERVIEW / HOME HUB (Single db.batch round-trip on D1 Read Session)
// ============================================================================

telegramMiniAppApi.get("/overview", async (c) => {
  const readDb = getReadDb(c.env.DB);
  const now = Date.now();
  const onlineCutoff = now - 5 * 60 * 1000;

  const [
    examsRows,
    testsRows,
    usersRows,
    premRows,
    ordersRows,
    flagsRows,
    bannersRows,
    pollsRows,
    attemptsRows,
    crashesRows,
    adminsRows,
    cfgRows,
  ] = await batchReadRows<any>(readDb, [
    readDb.prepare(
      "SELECT COUNT(*) AS c, COALESCE(SUM(CASE WHEN COALESCE(publish_mode,'inherit') != 'paused' THEN 1 ELSE 0 END), 0) AS live FROM exams"
    ),
    readDb.prepare(
      "SELECT COALESCE(SUM(CASE WHEN status IN ('live','published') THEN 1 ELSE 0 END), 0) AS live, COALESCE(SUM(CASE WHEN status IN ('draft','paused') THEN 1 ELSE 0 END), 0) AS draft FROM generated_tests"
    ),
    readDb
      .prepare(
        "SELECT COUNT(*) AS c, COALESCE(SUM(CASE WHEN last_active >= ? THEN 1 ELSE 0 END), 0) AS online FROM users"
      )
      .bind(onlineCutoff),
    readDb
      .prepare(
        "SELECT COUNT(*) AS c FROM premium_entitlements WHERE status = 'ACTIVE' AND (is_lifetime = 1 OR expires_at IS NULL OR expires_at > ?)"
      )
      .bind(now),
    readDb.prepare(
      "SELECT COUNT(*) AS c, COALESCE(SUM(CASE WHEN status IN ('CREATED','PENDING') THEN 1 ELSE 0 END), 0) AS pending, COALESCE(SUM(CASE WHEN status IN ('PAID','SUCCESS') THEN amount ELSE 0 END), 0) AS revenue FROM premium_orders"
    ),
    readDb.prepare("SELECT COUNT(*) AS c FROM question_reports WHERE status = 'pending'"),
    readDb.prepare("SELECT COUNT(*) AS c FROM home_banners"),
    readDb.prepare("SELECT COUNT(*) AS c FROM polls"),
    readDb.prepare("SELECT COUNT(*) AS c, COALESCE(ROUND(AVG(score), 1), 0) AS avg_score FROM attempts WHERE counted = 1"),
    readDb.prepare("SELECT COUNT(*) AS c FROM crash_signatures WHERE status = 'open'"),
    readDb.prepare("SELECT COUNT(*) AS c FROM admins"),
    readDb.prepare("SELECT body FROM app_content WHERE id = 'app_config' LIMIT 1"),
  ]);

  const num = (rows: any[], field: string, fallbackField: string = "c") =>
    Number(rows?.[0]?.[field] ?? rows?.[0]?.[fallbackField] ?? 0);
  const config = parseAppConfigRow(cfgRows?.[0]);

  return c.json({
    success: true,
    data: {
      kpis: {
        totalExams: num(examsRows, "c"),
        liveExamsCount: num(examsRows, "live"),
        liveTests: num(testsRows, "live"),
        draftTests: num(testsRows, "draft"),
        totalStudents: num(usersRows, "c"),
        onlineStudents: num(usersRows, "online"),
        activePremium: num(premRows, "c"),
        totalOrders: num(ordersRows, "c"),
        pendingOrders: num(ordersRows, "pending"),
        revenueInr: num(ordersRows, "revenue"),
        pendingFlags: num(flagsRows, "c"),
        bannersCount: num(bannersRows, "c"),
        pollsCount: num(pollsRows, "c"),
        totalAttempts: num(attemptsRows, "c"),
        avgScore: num(attemptsRows, "avg_score"),
        openCrashes: num(crashesRows, "c"),
        adminsCount: Math.max(HARDCODED_ADMIN_EMAILS.size, num(adminsRows, "c")),
      },
      config,
    },
  });
});

// ============================================================================
// 2. MAINTENANCE & AI ROUTER CONFIG
// ============================================================================

telegramMiniAppApi.post("/config", async (c) => {
  const user = c.get("user");
  const db = c.env.DB;
  const body = await c.req.json().catch(() => ({}));
  const prev = await readAppConfig(db).catch(() => normalizeAppConfigObject({}));

  const patch: Record<string, any> = {};
  if (typeof body.maintenanceMode === "boolean") patch.maintenance_mode = body.maintenanceMode;
  if (typeof body.maintenanceMessage === "string") patch.maintenance_message = body.maintenanceMessage.trim().slice(0, 1000);
  if (typeof body.minimumSupportedVersionCode === "number" && Number.isInteger(body.minimumSupportedVersionCode) && body.minimumSupportedVersionCode >= 1) {
    patch.minimum_supported_version_code = body.minimumSupportedVersionCode;
  }
  if (typeof body.defaultPublishMode === "string") {
    const mode = body.defaultPublishMode.trim().toLowerCase();
    if (!["live", "paused", "draft"].includes(mode)) return c.json({ success: false, error: "Invalid publish mode" }, 400);
    patch.default_publish_mode = mode;
  }
  if (typeof body.aiProvider === "string") {
    const prov = body.aiProvider.trim().toLowerCase();
    if (!(VALID_AI_PROVIDERS as readonly string[]).includes(prov)) return c.json({ success: false, error: "Invalid AI provider" }, 400);
    patch.ai_provider = prov;
    patch.aiProvider = prov;
  }
  if (typeof body.aiModel === "string") {
    patch.ai_model = body.aiModel.trim().slice(0, 160);
    patch.aiModel = patch.ai_model;
  }
  if (typeof body.officialTelegramChannelUsername === "string") {
    const parsed = sanitizeTelegramAtUsername(body.officialTelegramChannelUsername);
    if (!parsed.valid) return c.json({ success: false, error: parsed.error || "Invalid Telegram channel username" }, 400);
    patch.official_telegram_channel_username = parsed.value;
    patch.officialTelegramChannelUsername = parsed.value;
  }
  if (typeof body.officialTelegramSupportUsername === "string") {
    const parsed = sanitizeTelegramAtUsername(body.officialTelegramSupportUsername);
    if (!parsed.valid) return c.json({ success: false, error: parsed.error || "Invalid Telegram support username" }, 400);
    patch.official_telegram_support_username = parsed.value;
    patch.officialTelegramSupportUsername = parsed.value;
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
    official_telegram_channel_username: nextNorm.officialTelegramChannelUsername,
    officialTelegramChannelUsername: nextNorm.officialTelegramChannelUsername,
    official_telegram_support_username: nextNorm.officialTelegramSupportUsername,
    officialTelegramSupportUsername: nextNorm.officialTelegramSupportUsername,
  };

  const saveStmt = db
    .prepare(
      "INSERT INTO app_content (id, title, body, updated_at, updated_by) VALUES ('app_config', 'App Configuration', ?, ?, ?) ON CONFLICT(id) DO UPDATE SET body = json_patch(app_content.body, ?), updated_at = excluded.updated_at, updated_by = excluded.updated_by"
    )
    .bind(JSON.stringify(bodyObj), Date.now(), user.email, JSON.stringify(patch));
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "CONFIG_UPDATED",
    description: `Updated App/AI config from Mini App (maint=${nextNorm.maintenanceMode}, ai=${nextNorm.aiProvider})`,
    targetType: "app_content",
    targetId: "app_config",
    metadata: patch,
  });
  await runBatch(db, [saveStmt, auditStmt]);
  await purgeEdgeCache(c, ["/api/app-config"]);
  return c.json({ success: true, data: nextNorm });
});

// ============================================================================
// 3. EXAMS, SUB-EXAMS, AVATARS, SYLLABUS, AI STUDIO, MOCK TESTS & Q-BANK
// ============================================================================

telegramMiniAppApi.get("/exams", async (c) => {
  const readDb = getReadDb(c.env.DB);
  const [examsList, testsList, qCountRows] = await batchReadRows<any>(readDb, [
    readDb.prepare("SELECT * FROM exams ORDER BY created_at DESC"),
    readDb.prepare(
      "SELECT id, exam_id, title, test_number, status, question_count, generated_at FROM generated_tests ORDER BY generated_at ASC LIMIT 300"
    ),
    readDb.prepare("SELECT exam_id, COUNT(*) AS qcount FROM questions GROUP BY exam_id"),
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
  const qCountByExam = new Map<string, number>();
  for (const row of qCountRows || []) {
    qCountByExam.set(String(row.exam_id), Number(row.qcount || 0));
  }

  return c.json({
    success: true,
    data: ((examsList || []) as ExamRow[]).map((e) => {
      const tests = (testsByExam.get(e.id) || []).slice().sort(NATURAL_TEST_COMPARE);
      return {
        id: e.id,
        examName: e.exam_name,
        category: e.category || "General",
        timeLimitMinutes: e.time_limit_minutes || 30,
        questionCount: e.question_count || 20,
        negativeMarkingText: e.negative_marking_text || "0",
        publishMode: e.publish_mode || "inherit",
        parentExamId: e.parent_exam_id || "",
        avatarUrl: String(e.avatar_url || e.image_url || "").trim(),
        avatarEmoji: String(e.avatar_emoji || "").trim(),
        syllabusUrl: e.syllabus_url || "",
        syllabusFileName: e.syllabus_file_name || "",
        testNumber: e.test_number || "Test 1",
        aiLanguage: e.ai_language || "english",
        aiDifficulty: e.ai_difficulty || "mixed",
        aiPrompt: e.ai_prompt || e.generation_prompt || e.custom_prompt_notes || "",
        questionsCount: qCountByExam.get(e.id) || 0,
        tests,
      };
    }),
  });
});

telegramMiniAppApi.post("/exams", async (c) => {
  const user = c.get("user");
  const db = c.env.DB;
  const body = await c.req.json().catch(() => ({}));
  const examName = String(body.examName || "").trim();
  const category = String(body.category || "General").trim();
  const minutes = Number(body.timeLimitMinutes ?? 30);
  const questionCount = Number(body.questionCount ?? 20);
  const parentExamId = String(body.parentExamId || "").trim();
  const publishMode = ["inherit", "live", "paused"].includes(String(body.publishMode || "inherit").toLowerCase())
    ? String(body.publishMode || "inherit").toLowerCase()
    : "inherit";
  const negParsed = parseNegativeMarking(String(body.negativeMarkingText ?? "0"));

  if (!examName) return c.json({ success: false, error: "Exam name cannot be empty" }, 400);
  if (!Number.isFinite(minutes) || minutes < 1 || minutes > 600) {
    return c.json({ success: false, error: "Duration must be 1–600 minutes" }, 400);
  }
  if (!Number.isInteger(questionCount) || questionCount < 1 || questionCount > 200) {
    return c.json({ success: false, error: "Questions must be 1–200" }, 400);
  }
  if (!negParsed) return c.json({ success: false, error: "Negative marking must be 0, 0.25, 1/3, etc." }, 400);

  if (parentExamId) {
    const parent = await db.prepare("SELECT id, parent_exam_id FROM exams WHERE id = ?").bind(parentExamId).first<ExamRow>();
    if (!parent) return c.json({ success: false, error: "Parent exam not found" }, 404);
    if (parent.parent_exam_id && parent.parent_exam_id.trim()) {
      return c.json({ success: false, error: "Only 1 level of sub-exams is allowed" }, 400);
    }
  }
  const dup = await db
    .prepare("SELECT 1 FROM exams WHERE LOWER(exam_name) = LOWER(?) AND COALESCE(parent_exam_id, '') = ?")
    .bind(examName, parentExamId)
    .first();
  if (dup) return c.json({ success: false, error: "Exam with this name already exists here" }, 409);

  const id = crypto.randomUUID();
  const cols = await getTableColumns(db, "exams");
  const insertCols = [
    "id",
    "exam_name",
    "time_limit_minutes",
    "category",
    "test_number",
    "question_count",
    "negative_marking_text",
    "negative_marking_value",
    "parent_exam_id",
    "publish_mode",
  ];
  const insertVals: Array<string | number> = [
    id,
    examName,
    minutes,
    category,
    "Test 1",
    questionCount,
    negParsed.text,
    negParsed.value,
    parentExamId,
    publishMode,
  ];
  if (cols.has("ai_language")) {
    insertCols.push("ai_language");
    insertVals.push("english");
  }
  if (cols.has("ai_difficulty")) {
    insertCols.push("ai_difficulty");
    insertVals.push("mixed");
  }

  const insStmt = db
    .prepare(`INSERT INTO exams (${insertCols.join(", ")}) VALUES (${insertCols.map(() => "?").join(", ")})`)
    .bind(...insertVals);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "EXAM_CREATED",
    description: `Created ${parentExamId ? "sub-exam" : "exam"} '${examName}' via Mini App`,
    targetType: "exam",
    targetId: id,
    metadata: { examName, category, parentExamId },
  });
  await db.batch([insStmt, auditStmt]);
  await purgeEdgeCache(c, ["/api/exams"]);
  return c.json({ success: true, data: { id } }, 201);
});

telegramMiniAppApi.put("/exams/:id", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;
  const existing = await db.prepare("SELECT * FROM exams WHERE id = ?").bind(id).first<ExamRow>();
  if (!existing) return c.json({ success: false, error: "Exam not found" }, 404);

  const body = await c.req.json().catch(() => ({}));
  const examName = body.examName !== undefined ? String(body.examName).trim() : existing.exam_name;
  if (!examName) return c.json({ success: false, error: "Exam name cannot be empty" }, 400);
  const category = body.category !== undefined ? String(body.category).trim() : existing.category;
  const minutes = body.timeLimitMinutes !== undefined ? Number(body.timeLimitMinutes) : existing.time_limit_minutes;
  const questionCount = body.questionCount !== undefined ? Number(body.questionCount) : existing.question_count;
  const publishMode =
    body.publishMode !== undefined && ["inherit", "live", "paused", "archived"].includes(String(body.publishMode).toLowerCase())
      ? String(body.publishMode).toLowerCase()
      : existing.publish_mode || "inherit";
  const negParsed =
    body.negativeMarkingText !== undefined
      ? parseNegativeMarking(String(body.negativeMarkingText))
      : { text: existing.negative_marking_text || "0", value: existing.negative_marking_value ?? 0 };
  if (!negParsed) return c.json({ success: false, error: "Invalid negative marking format" }, 400);

  const renamed = examName.toLowerCase() !== existing.exam_name.toLowerCase();
  if (renamed) {
    const dup = await db
      .prepare("SELECT 1 FROM exams WHERE LOWER(exam_name) = LOWER(?) AND COALESCE(parent_exam_id, '') = ? AND id != ?")
      .bind(examName, existing.parent_exam_id || "", id)
      .first();
    if (dup) return c.json({ success: false, error: "Sibling exam with this name already exists" }, 409);
  }

  const updateStmt = db
    .prepare(
      "UPDATE exams SET exam_name = ?, category = ?, time_limit_minutes = ?, question_count = ?, negative_marking_text = ?, negative_marking_value = ?, publish_mode = ? WHERE id = ?"
    )
    .bind(examName, category, minutes, questionCount, negParsed.text, negParsed.value, publishMode, id);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "EXAM_EDITED",
    description: `Updated settings for '${examName}' via Mini App`,
    targetType: "exam",
    targetId: id,
  });

  const stmts: D1PreparedStatement[] = [updateStmt, auditStmt];
  if (renamed) {
    stmts.push(
      db.prepare("UPDATE generated_tests SET exam_name = ? WHERE exam_id = ?").bind(examName, id),
      db.prepare("UPDATE attempts SET exam_name = ? WHERE exam_id = ?").bind(examName, id),
      db.prepare("UPDATE leaderboard SET exam_name = ? WHERE exam_id = ?").bind(examName, id)
    );
  }
  await db.batch(stmts);
  await purgeEdgeCache(c, ["/api/exams", "/api/generated-tests"]);
  return c.json({ success: true });
});

telegramMiniAppApi.delete("/exams/:id", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;
  const existing = await db.prepare("SELECT * FROM exams WHERE id = ?").bind(id).first<ExamRow>();
  if (!existing) return c.json({ success: false, error: "Exam not found" }, 404);

  const info = await deletionInfo(db, id, "exam");
  if (!info.canDelete) {
    return c.json({ success: false, error: info.message || "Exam has dependent attempts/tests and cannot be removed" }, 409);
  }
  const ok = await deleteUnused(db, id, "exam", user);
  if (!ok) return c.json({ success: false, error: "Exam could not be deleted" }, 409);

  await purgeEdgeCache(c, ["/api/exams", "/api/generated-tests"]);
  const oldAvatar = extractStorageAvatarKey(String(existing.avatar_url || existing.image_url || ""), "exam-avatars/");
  if (oldAvatar) {
    try { await new AssetStorage(c.env).deleteFiles([oldAvatar]); } catch {}
  }
  return c.json({ success: true });
});

telegramMiniAppApi.post("/exams/:id/avatar", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;
  const existing = await db.prepare("SELECT * FROM exams WHERE id = ?").bind(id).first<ExamRow>();
  if (!existing) return c.json({ success: false, error: "Exam not found" }, 404);

  const parsedPayload = await parseAvatarUploadPayload(c);
  if (parsedPayload.error || !parsedPayload.imageBytes) {
    return c.json({ success: false, error: parsedPayload.error || "Image file or base64 required" }, 400);
  }
  const detected = detectAvatarImage(parsedPayload.imageBytes, parsedPayload.declaredMime);
  if (!detected) return c.json({ success: false, error: "Supported formats: JPG, PNG, WebP, GIF (max 5MB)" }, 400);

  const storage = new AssetStorage(c.env);
  const storagePath = `exam-avatars/${id}/${crypto.randomUUID()}.${detected.ext}`;
  const storedLoc = await storage.uploadFile(storagePath, parsedPayload.imageBytes, detected.mime);
  const avatarUrl = buildAvatarProxyUrl(`/api/exams/${encodeURIComponent(id)}/avatar`, storagePath, storedLoc);

  const cols = await getTableColumns(db, "exams");
  const sets = ["image_url = ?"];
  const binds: any[] = [avatarUrl];
  if (cols.has("avatar_url")) {
    sets.push("avatar_url = ?");
    binds.push(avatarUrl);
  }
  if (cols.has("avatar_emoji")) sets.push("avatar_emoji = ''");
  if (cols.has("avatar_color")) sets.push("avatar_color = NULL");
  binds.push(id);

  const updateStmt = db.prepare(`UPDATE exams SET ${sets.join(", ")} WHERE id = ?`).bind(...binds);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "EXAM_AVATAR_UPDATED",
    description: `Uploaded avatar photo for '${existing.exam_name}' via Mini App`,
    targetType: "exam",
    targetId: id,
  });
  await db.batch([updateStmt, auditStmt]);

  const oldKey = extractStorageAvatarKey(String(existing.avatar_url || existing.image_url || ""), "exam-avatars/");
  if (oldKey && oldKey !== storagePath) {
    try { await storage.deleteFiles([oldKey]); } catch {}
  }
  await purgeEdgeCache(c, ["/api/exams"]);
  return c.json({ success: true, data: { avatarUrl } });
});

telegramMiniAppApi.post("/exams/:id/syllabus", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;
  const existing = await db.prepare("SELECT id, exam_name, syllabus_url FROM exams WHERE id = ?").bind(id).first<ExamRow>();
  if (!existing) return c.json({ success: false, error: "Exam not found" }, 404);

  const body = await c.req.json().catch(() => ({}));
  if (typeof body.base64 !== "string" || !body.base64) {
    return c.json({ success: false, error: "Base64 PDF payload is required" }, 400);
  }
  let binary: string;
  try {
    binary = atob(body.base64);
  } catch {
    return c.json({ success: false, error: "Invalid base64 encoding" }, 400);
  }
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  if (!validSyllabusPdf(bytes)) {
    return c.json({ success: false, error: "Invalid PDF document or exceeds 20MB limit" }, 400);
  }

  const fileName = safeSyllabusFilename(body.fileName || `${existing.exam_name}.pdf`);
  const storage = new AssetStorage(c.env);
  const storagePath = `syllabi/${crypto.randomUUID()}.pdf`;
  const storedLoc = await storage.uploadFile(storagePath, bytes, "application/pdf");
  const refForUrl = storedLoc.startsWith("tg:") ? storedLoc : storagePath;
  const downloadUrl = `/api/exams/${encodeURIComponent(id)}/syllabus?k=${encodeURIComponent(refForUrl)}&v=${Date.now()}`;

  const updateStmt = db.prepare("UPDATE exams SET syllabus_url = ?, syllabus_file_name = ? WHERE id = ?").bind(downloadUrl, fileName, id);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "SYLLABUS_UPDATED",
    description: `Uploaded syllabus '${fileName}' for '${existing.exam_name}' via Mini App`,
    targetType: "exam",
    targetId: id,
  });
  await db.batch([updateStmt, auditStmt]);
  await purgeEdgeCache(c, ["/api/exams"]);
  return c.json({ success: true, data: { syllabusUrl: downloadUrl, syllabusFileName: fileName } });
});

telegramMiniAppApi.delete("/exams/:id/syllabus", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;
  const exam = await db.prepare("SELECT syllabus_url FROM exams WHERE id = ?").bind(id).first<ExamRow>();
  if (!exam) return c.json({ success: false, error: "Exam not found" }, 404);

  const updateStmt = db.prepare("UPDATE exams SET syllabus_url = '', syllabus_file_name = '' WHERE id = ?").bind(id);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "SYLLABUS_DELETED",
    description: `Removed syllabus PDF for exam '${id}' via Mini App`,
    targetType: "exam",
    targetId: id,
  });
  await db.batch([updateStmt, auditStmt]);
  await purgeEdgeCache(c, ["/api/exams"]);
  const raw = String(exam.syllabus_url || "");
  if (raw) {
    try {
      const parsed = new URL(raw, "https://eve.local");
      const ref = parsed.searchParams.get("k") || raw;
      if (ref) await cleanupMedia(c.env, ref, c.res.headers.get("X-Request-ID"));
    } catch {}
  }
  return c.json({ success: true });
});

telegramMiniAppApi.put("/exams/:id/prompt", async (c) => {
  const user = c.get("user");
  const examId = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  const db = c.env.DB;
  const exam = await db.prepare("SELECT * FROM exams WHERE id = ?").bind(examId).first<ExamRow>();
  if (!exam) return c.json({ success: false, error: "Exam not found" }, 404);

  const aiPrompt = String(body.aiPrompt ?? exam.ai_prompt ?? "").trim().slice(0, 4000);
  const aiLanguage = String(body.aiLanguage || exam.ai_language || "english").trim().toLowerCase();
  const aiDifficulty = String(body.aiDifficulty || exam.ai_difficulty || "mixed").trim().toLowerCase();
  if (!["english", "hindi", "bilingual"].includes(aiLanguage)) return c.json({ success: false, error: "Invalid aiLanguage" }, 400);
  if (!["easy", "medium", "hard", "mixed"].includes(aiDifficulty)) return c.json({ success: false, error: "Invalid aiDifficulty" }, 400);

  const updateStmt = db
    .prepare("UPDATE exams SET ai_prompt = ?, generation_prompt = ?, ai_language = ?, ai_difficulty = ? WHERE id = ?")
    .bind(aiPrompt, aiPrompt, aiLanguage, aiDifficulty, examId);
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

telegramMiniAppApi.post("/exams/:id/generate", async (c) => {
  const user = c.get("user");
  const examId = c.req.param("id");
  const db = c.env.DB;
  const exam = await db.prepare("SELECT * FROM exams WHERE id = ?").bind(examId).first<ExamRow>();
  if (!exam) return c.json({ success: false, error: "Exam not found" }, 404);
  try {
    const prompt = (exam.ai_prompt || exam.generation_prompt || exam.custom_prompt_notes || "").trim();
    const data = await runGeneration(c.env, exam, exam.question_count || 20, exam.test_number || "Test 1", prompt, "telegram_miniapp", undefined, user);
    await purgeEdgeCache(c, ["/api/generated-tests", "/api/exams"]);
    return c.json({ success: true, data });
  } catch (err: any) {
    if (err instanceof GenerationBusyError) return c.json({ success: false, error: err.message }, 409);
    return c.json({ success: false, error: err?.userFacingMessage || err?.message || "Generation failed" }, 500);
  }
});

telegramMiniAppApi.post("/exams/:id/assemble", async (c) => {
  const user = c.get("user");
  const examId = c.req.param("id");
  const db = c.env.DB;
  const exam = await db.prepare("SELECT * FROM exams WHERE id = ?").bind(examId).first<ExamRow>();
  if (!exam) return c.json({ success: false, error: "Exam not found" }, 404);
  try {
    const data = await assembleTestFromBank(db, exam, exam.question_count || 20, { auditActor: user, source: "telegram_miniapp" });
    await purgeEdgeCache(c, ["/api/generated-tests", "/api/exams"]);
    return c.json({ success: true, data });
  } catch (err: any) {
    if (err instanceof InsufficientBankQuestionsError) return c.json({ success: false, error: err.message }, 400);
    return c.json({ success: false, error: err?.message || "Assembly failed" }, 500);
  }
});

telegramMiniAppApi.put("/tests/:id/status", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  const status = String(body.status || "").trim().toLowerCase();
  if (!["draft", "paused", "live"].includes(status)) return c.json({ success: false, error: "Invalid status" }, 400);
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

telegramMiniAppApi.delete("/tests/:id", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const ok = await deleteUnused(c.env.DB, id, "test", user);
  if (!ok) return c.json({ success: false, error: "Test cannot be deleted (attempted or missing)" }, 409);
  await purgeEdgeCache(c, ["/api/generated-tests", "/api/exams"]);
  return c.json({ success: true });
});

// Question Bank CRUD + Bulk Import
telegramMiniAppApi.get("/exams/:id/questions", async (c) => {
  const readDb = getReadDb(c.env.DB);
  const examId = c.req.param("id");
  const { results } = await readDb.prepare("SELECT * FROM questions WHERE exam_id = ?").bind(examId).all<QuestionRow>();
  return c.json({
    success: true,
    data: (results || []).map((r) => ({
      id: r.id,
      examId: r.exam_id,
      questionText: r.question_text,
      optionA: r.option_a,
      optionB: r.option_b,
      optionC: r.option_c,
      optionD: r.option_d,
      correctAnswer: r.correct_answer,
      explanation: r.explanation || "",
      topic: r.topic || "",
    })),
  });
});

telegramMiniAppApi.post("/exams/:id/questions", async (c) => {
  const user = c.get("user");
  const examId = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  let valid: ReturnType<typeof parseAndValidateQuestions>[0];
  try {
    valid = parseAndValidateQuestions(JSON.stringify([body]))[0];
  } catch {
    return c.json({ success: false, error: "Invalid question text, distinct options, or answer key" }, 400);
  }
  const db = c.env.DB;
  const qId = crypto.randomUUID();
  const ins = db
    .prepare(
      `INSERT INTO questions (id, exam_id, question_text, option_a, option_b, option_c, option_d, correct_answer, explanation, topic)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    )
    .bind(qId, examId, valid.questionText, valid.optionA, valid.optionB, valid.optionC, valid.optionD, valid.correctAnswer, valid.explanation, String(body.topic || ""));
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "QUESTION_CREATED",
    description: `Added question to exam '${examId}' via Mini App`,
    targetType: "question",
    targetId: qId,
  });
  await db.batch([ins, auditStmt]);
  return c.json({ success: true, data: { id: qId } }, 201);
});

telegramMiniAppApi.post("/exams/:id/questions/import", async (c) => {
  const user = c.get("user");
  const examId = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  const rawList: any[] = Array.isArray(body.questions) ? body.questions : Array.isArray(body) ? body : [];
  if (rawList.length === 0 || rawList.length > 300) {
    return c.json({ success: false, error: "Provide 1 to 300 valid questions" }, 400);
  }
  let validList: ReturnType<typeof parseAndValidateQuestions>;
  try {
    validList = parseAndValidateQuestions(JSON.stringify(rawList));
  } catch {
    return c.json({ success: false, error: "JSON contains invalid questions or duplicate options" }, 400);
  }
  const records = validList.map((v, i) => ({
    id: crypto.randomUUID(),
    examId,
    questionText: v.questionText,
    optionA: v.optionA,
    optionB: v.optionB,
    optionC: v.optionC,
    optionD: v.optionD,
    correctAnswer: v.correctAnswer,
    explanation: v.explanation,
    topic: String(rawList[i]?.topic || ""),
  }));
  const db = c.env.DB;
  const insertStmt = db
    .prepare(
      `INSERT INTO questions (
        id, exam_id, question_text, option_a, option_b, option_c, option_d, correct_answer, explanation, topic
      ) SELECT json_extract(value, '$.id'), json_extract(value, '$.examId'),
        json_extract(value, '$.questionText'), json_extract(value, '$.optionA'), json_extract(value, '$.optionB'),
        json_extract(value, '$.optionC'), json_extract(value, '$.optionD'), json_extract(value, '$.correctAnswer'),
        json_extract(value, '$.explanation'), json_extract(value, '$.topic') FROM json_each(?)`
    )
    .bind(JSON.stringify(records));
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "QUESTION_BATCH_CREATED",
    description: `Bulk imported ${records.length} questions into '${examId}' via Mini App`,
    targetType: "question",
    targetId: examId,
    metadata: { count: records.length },
  });
  await db.batch([insertStmt, auditStmt]);
  return c.json({ success: true, count: records.length }, 201);
});

telegramMiniAppApi.put("/questions/:id", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  let valid: ReturnType<typeof parseAndValidateQuestions>[0];
  try {
    valid = parseAndValidateQuestions(JSON.stringify([body]))[0];
  } catch {
    return c.json({ success: false, error: "Invalid question text, options, or answer key" }, 400);
  }
  const db = c.env.DB;
  const upd = db
    .prepare(
      "UPDATE questions SET question_text = ?, option_a = ?, option_b = ?, option_c = ?, option_d = ?, correct_answer = ?, explanation = ?, topic = ? WHERE id = ?"
    )
    .bind(valid.questionText, valid.optionA, valid.optionB, valid.optionC, valid.optionD, valid.correctAnswer, valid.explanation, String(body.topic || ""), id);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "QUESTION_EDITED",
    description: `Edited question '${id}' via Mini App`,
    targetType: "question",
    targetId: id,
  });
  await db.batch([upd, auditStmt]);
  return c.json({ success: true });
});

telegramMiniAppApi.delete("/questions/:id", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;
  const del = db.prepare("DELETE FROM questions WHERE id = ?").bind(id);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "QUESTION_DELETED",
    description: `Deleted question '${id}' via Mini App`,
    targetType: "question",
    targetId: id,
  });
  await db.batch([del, auditStmt]);
  return c.json({ success: true });
});

// ============================================================================
// 4. CONTENT & MODERATION HUB (Banners, Broadcasts, Polls, Flags, Feedback)
// ============================================================================

telegramMiniAppApi.get("/content", async (c) => {
  const readDb = getReadDb(c.env.DB);
  const [bannersRaw, broadcastsRaw, pollsRaw, votesRaw, flagsRaw, feedbackRaw, cfgRaw] = await batchReadRows<any>(readDb, [
    readDb.prepare("SELECT * FROM home_banners ORDER BY order_index ASC"),
    readDb.prepare("SELECT * FROM notifications ORDER BY sent_at DESC LIMIT 50"),
    readDb.prepare("SELECT * FROM polls ORDER BY created_at DESC LIMIT 40"),
    readDb.prepare("SELECT poll_id, option_index, COUNT(*) AS vote_count FROM poll_votes GROUP BY poll_id, option_index"),
    readDb.prepare("SELECT * FROM question_reports WHERE status = 'pending' ORDER BY timestamp DESC LIMIT 50"),
    readDb.prepare(
      "SELECT fm.*, fr.reply_text AS saved_reply_text FROM feedback_messages fm LEFT JOIN feedback_admin_replies fr ON fr.message_id = fm.id ORDER BY fm.timestamp DESC LIMIT 50"
    ),
    readDb.prepare("SELECT body FROM app_content WHERE id = 'app_config' LIMIT 1"),
  ]);

  const votesByPoll = new Map<string, Record<string, number>>();
  for (const v of votesRaw || []) {
    let map = votesByPoll.get(v.poll_id);
    if (!map) {
      map = {};
      votesByPoll.set(v.poll_id, map);
    }
    map[String(v.option_index)] = Number(v.vote_count || 0);
  }

  return c.json({
    success: true,
    data: {
      banners: (bannersRaw || []).map((b: HomeBannerRow) => ({
        id: b.id,
        imageUrl: b.image_url,
        mediaType: b.media_type === "video" || /\.(mp4|webm)/i.test(String(b.storage_path || "")) ? "video" : "image",
        active: Boolean(b.active),
        order: b.order_index,
        linkUrl: b.link_url || "",
        linkLabel: b.link_label || "",
      })),
      broadcasts: (broadcastsRaw || []).map((n: any) => ({
        id: n.id,
        title: n.title,
        message: n.message,
        sentAt: n.sent_at,
      })),
      polls: (pollsRaw || []).map((p: any) => {
        let options: string[] = [];
        try { options = JSON.parse(p.options_json); } catch {}
        return {
          id: p.id,
          question: p.question,
          options,
          active: Boolean(p.active),
          voteCounts: votesByPoll.get(p.id) || {},
        };
      }),
      flags: (flagsRaw || []).map((f: QuestionReportRow) => ({
        id: f.id,
        questionId: f.question_id,
        examName: f.exam_name,
        questionText: f.question_text,
        reason: f.reason,
        comment: f.comment || "",
        studentEmail: f.student_email,
        timestamp: f.timestamp,
      })),
      feedback: (feedbackRaw || []).map((fb: any) => ({
        id: fb.id,
        message: fb.message,
        userName: fb.user_name,
        userEmail: fb.user_email,
        timestamp: fb.timestamp,
        read: Boolean(fb.read),
        replyText: fb.saved_reply_text || "",
      })),
      config: parseAppConfigRow(cfgRaw?.[0]),
    },
  });
});

telegramMiniAppApi.post("/banners", async (c) => {
  const user = c.get("user");
  const db = c.env.DB;
  const body = await c.req.json().catch(() => ({}));
  if (typeof body.base64 !== "string" || !body.base64.trim()) {
    return c.json({ success: false, error: "Banner file base64 is required" }, 400);
  }
  let bytes: Uint8Array;
  try {
    const clean = body.base64.includes(",") ? body.base64.split(",")[1] : body.base64;
    const bin = atob(clean);
    bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  } catch {
    return c.json({ success: false, error: "Invalid base64 payload" }, 400);
  }
  const mime = String(body.mimeType || "").toLowerCase();
  const isVideo = mime.startsWith("video/") || (bytes.length >= 8 && bytes[4] === 0x66 && bytes[5] === 0x74);
  const maxBytes = isVideo ? 20 * 1024 * 1024 : 5 * 1024 * 1024;
  if (bytes.byteLength > maxBytes) return c.json({ success: false, error: "File exceeds max size limit" }, 400);

  const ext = isVideo ? (mime.includes("webm") ? "webm" : "mp4") : mime.includes("png") ? "png" : mime.includes("gif") ? "gif" : "jpg";
  const finalMime = isVideo ? (mime || "video/mp4") : (mime || "image/jpeg");
  const id = crypto.randomUUID();
  const storagePath = `banners/${id}.${ext}`;
  const storedLoc = await new AssetStorage(c.env).uploadFile(storagePath, bytes, finalMime);
  const persistedPath = storedLoc.startsWith("tg:") ? storedLoc : storagePath;
  const imageUrl = `/api/banners/${encodeURIComponent(id)}/media?v=${Date.now()}`;
  const linkUrl = typeof body.linkUrl === "string" ? body.linkUrl.trim() : "";
  const linkLabel = typeof body.linkLabel === "string" ? body.linkLabel.trim().slice(0, 64) : "";

  const maxRow = await db.prepare("SELECT MAX(order_index) AS m FROM home_banners").first<{ m: number | null }>();
  const nextOrder = (maxRow?.m ?? -1) + 1;
  const ins = db
    .prepare(
      `INSERT INTO home_banners (id, image_url, storage_path, order_index, uploaded_at, uploaded_by, active, link_url, link_label, media_type)
       VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)`
    )
    .bind(id, imageUrl, persistedPath, nextOrder, Date.now(), user.email, linkUrl, linkLabel, isVideo ? "video" : "image");
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "BANNER_ADDED",
    description: `Uploaded ${isVideo ? "video" : "image"} banner '${id}' via Mini App`,
    targetType: "banner",
    targetId: id,
  });
  await db.batch([ins, auditStmt]);
  await purgeEdgeCache(c, ["/api/banners"]);
  return c.json({ success: true, data: { id } }, 201);
});

telegramMiniAppApi.put("/banners/:id/status", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  const active = body.active ? 1 : 0;
  const db = c.env.DB;
  const upd = db.prepare("UPDATE home_banners SET active = ? WHERE id = ?").bind(active, id);
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: "BANNER_STATUS_UPDATED",
    description: `${active ? "Activated" : "Hidden"} banner '${id}' via Mini App`,
    targetType: "banner",
    targetId: id,
  });
  await db.batch([upd, stmt]);
  await purgeEdgeCache(c, ["/api/banners"]);
  return c.json({ success: true });
});

telegramMiniAppApi.put("/banners/:id/link", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  const linkUrl = String(body.linkUrl || "").trim();
  const linkLabel = String(body.linkLabel || "").trim().slice(0, 64);
  if (linkUrl && !/^https?:\/\//i.test(linkUrl)) return c.json({ success: false, error: "Link must start with http:// or https://" }, 400);
  const db = c.env.DB;
  const upd = db.prepare("UPDATE home_banners SET link_url = ?, link_label = ? WHERE id = ?").bind(linkUrl, linkLabel, id);
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: "BANNER_LINK_UPDATED",
    description: `Updated banner link for '${id}' via Mini App`,
    targetType: "banner",
    targetId: id,
  });
  await db.batch([upd, stmt]);
  await purgeEdgeCache(c, ["/api/banners"]);
  return c.json({ success: true });
});

telegramMiniAppApi.put("/banners/:id/reorder", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  const moveUp = Boolean(body.moveUp);
  const db = c.env.DB;
  const { results: banners } = await db.prepare("SELECT id, order_index FROM home_banners ORDER BY order_index ASC").all<{ id: string; order_index: number }>();
  const idx = (banners || []).findIndex((b) => b.id === id);
  const targetIdx = moveUp ? idx - 1 : idx + 1;
  if (idx < 0 || targetIdx < 0 || targetIdx >= (banners || []).length) return c.json({ success: true });
  const a = banners![idx];
  const b = banners![targetIdx];
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: "BANNER_REORDERED",
    description: `Reordered banner '${id}' via Mini App`,
    targetType: "banner",
    targetId: id,
  });
  await db.batch([
    db.prepare("UPDATE home_banners SET order_index = ? WHERE id = ?").bind(b.order_index, a.id),
    db.prepare("UPDATE home_banners SET order_index = ? WHERE id = ?").bind(a.order_index, b.id),
    stmt,
  ]);
  await purgeEdgeCache(c, ["/api/banners"]);
  return c.json({ success: true });
});

telegramMiniAppApi.delete("/banners/:id", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;
  const row = await db.prepare("SELECT storage_path FROM home_banners WHERE id = ?").bind(id).first<HomeBannerRow>();
  if (!row) return c.json({ success: false, error: "Banner not found" }, 404);
  const del = db.prepare("DELETE FROM home_banners WHERE id = ?").bind(id);
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: "BANNER_DELETED",
    description: `Deleted banner '${id}' via Mini App`,
    targetType: "banner",
    targetId: id,
  });
  await db.batch([del, stmt]);
  await purgeEdgeCache(c, ["/api/banners"]);
  if (row.storage_path) await cleanupMedia(c.env, row.storage_path, c.res.headers.get("X-Request-ID")).catch(() => {});
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
    .prepare("INSERT INTO notifications (id, title, message, sent_at, sent_by, type, target_category) VALUES (?, ?, ?, ?, ?, 'general', 'All Users')")
    .bind(id, title, message, Date.now(), user.email);
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: "BROADCAST_SENT",
    description: `Sent broadcast '${title}' via Mini App`,
    targetType: "broadcast",
    targetId: id,
  });
  await db.batch([ins, stmt]);
  return c.json({ success: true, data: { id } }, 201);
});

telegramMiniAppApi.delete("/broadcasts/:id", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;
  const del = db.prepare("DELETE FROM notifications WHERE id = ?").bind(id);
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: "BROADCAST_DELETED",
    description: `Deleted broadcast '${id}' via Mini App`,
    targetType: "broadcast",
    targetId: id,
  });
  await db.batch([del, stmt]);
  return c.json({ success: true });
});

telegramMiniAppApi.put("/announcement-channel", async (c) => {
  const user = c.get("user");
  const db = c.env.DB;
  const body = await c.req.json().catch(() => ({}));
  const name = String(body.announcementChannelName || "").trim().slice(0, 80);
  if (!name) return c.json({ success: false, error: "Channel name cannot be empty" }, 400);
  const tgParsed = sanitizeTelegramAtUsername(body.officialTelegramChannelUsername || "");
  if (!tgParsed.valid) return c.json({ success: false, error: tgParsed.error || "Invalid @username" }, 400);

  const prev = await readAppConfig(db).catch(() => normalizeAppConfigObject({}));
  const patch = {
    announcement_channel_name: name,
    announcementChannelName: name,
    official_telegram_channel_username: tgParsed.value,
    officialTelegramChannelUsername: tgParsed.value,
  };
  const bodyObj = { ...prev, ...patch };
  const saveStmt = db
    .prepare(
      "INSERT INTO app_content (id, title, body, updated_at, updated_by) VALUES ('app_config', 'App Configuration', ?, ?, ?) ON CONFLICT(id) DO UPDATE SET body = json_patch(app_content.body, ?), updated_at = excluded.updated_at, updated_by = excluded.updated_by"
    )
    .bind(JSON.stringify(bodyObj), Date.now(), user.email, JSON.stringify(patch));
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "ANNOUNCEMENT_CHANNEL_UPDATED",
    description: `Updated Announcements Channel info (${name}) via Mini App`,
    targetType: "app_content",
    targetId: "app_config",
  });
  await runBatch(db, [saveStmt, auditStmt]);
  await purgeEdgeCache(c, ["/api/app-config"]);
  return c.json({ success: true });
});

telegramMiniAppApi.post("/polls", async (c) => {
  const user = c.get("user");
  const body = await c.req.json().catch(() => ({}));
  const question = String(body.question || "").trim();
  const options: string[] = Array.isArray(body.options) ? body.options.map((x: any) => String(x).trim()).filter(Boolean) : [];
  if (!question || options.length < 2 || options.length > 6) {
    return c.json({ success: false, error: "Provide question and 2-6 non-empty options" }, 400);
  }
  const id = crypto.randomUUID();
  const db = c.env.DB;
  const ins = db
    .prepare("INSERT INTO polls (id, question, options_json, created_at, ends_at, active, created_by) VALUES (?, ?, ?, ?, 0, 1, ?)")
    .bind(id, question, JSON.stringify(options), Date.now(), user.email);
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: "POLL_CREATED",
    description: `Created poll '${question}' via Mini App`,
    targetType: "poll",
    targetId: id,
  });
  await db.batch([ins, stmt]);
  return c.json({ success: true, data: { id } }, 201);
});

telegramMiniAppApi.put("/polls/:id/status", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  const active = body.active ? 1 : 0;
  const db = c.env.DB;
  const upd = db.prepare("UPDATE polls SET active = ? WHERE id = ?").bind(active, id);
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: "POLL_STATUS_UPDATED",
    description: `${active ? "Activated" : "Closed"} poll '${id}' via Mini App`,
    targetType: "poll",
    targetId: id,
  });
  await db.batch([upd, stmt]);
  return c.json({ success: true });
});

telegramMiniAppApi.delete("/polls/:id", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: "POLL_DELETED",
    description: `Deleted poll '${id}' via Mini App`,
    targetType: "poll",
    targetId: id,
  });
  await db.batch([
    db.prepare("DELETE FROM poll_votes WHERE poll_id = ?").bind(id),
    db.prepare("DELETE FROM polls WHERE id = ?").bind(id),
    stmt,
  ]);
  return c.json({ success: true });
});

// Flagged Question Fixer Endpoints
telegramMiniAppApi.get("/reports/:id/detail", async (c) => {
  const readDb = getReadDb(c.env.DB);
  const id = c.req.param("id");
  const report = await readDb.prepare("SELECT * FROM question_reports WHERE id = ?").bind(id).first<QuestionReportRow>();
  if (!report) return c.json({ success: false, error: "Report not found" }, 404);

  const qId = String(report.question_id || "").trim();
  const bankRow = await readDb.prepare("SELECT * FROM questions WHERE id = ?").bind(qId).first<QuestionRow>();
  if (bankRow) {
    return c.json({
      success: true,
      data: {
        sourceType: "bank",
        report: {
          id: report.id,
          examName: report.exam_name,
          reason: report.reason,
          comment: report.comment || "",
          studentEmail: report.student_email,
          questionText: report.question_text,
        },
        question: {
          questionText: bankRow.question_text,
          optionA: bankRow.option_a,
          optionB: bankRow.option_b,
          optionC: bankRow.option_c,
          optionD: bankRow.option_d,
          correctAnswer: bankRow.correct_answer,
          explanation: bankRow.explanation || "",
        },
      },
    });
  }

  const parsedGen = parseGeneratedQuestionId(qId);
  if (parsedGen) {
    const test = await readDb.prepare("SELECT * FROM generated_tests WHERE id = ?").bind(parsedGen.testId).first<GeneratedTestRow>();
    if (test && test.questions_json) {
      try {
        const parsed = JSON.parse(test.questions_json);
        const arr = Array.isArray(parsed) ? parsed : Array.isArray(parsed?.questions) ? parsed.questions : [];
        const q = arr[parsedGen.index];
        if (q) {
          return c.json({
            success: true,
            data: {
              sourceType: "generated_test",
              report: {
                id: report.id,
                examName: report.exam_name,
                reason: report.reason,
                comment: report.comment || "",
                studentEmail: report.student_email,
                questionText: report.question_text,
              },
              question: {
                questionText: String(q.questionText || q.question_text || ""),
                optionA: String(q.optionA || q.option_a || ""),
                optionB: String(q.optionB || q.option_b || ""),
                optionC: String(q.optionC || q.option_c || ""),
                optionD: String(q.optionD || q.option_d || ""),
                correctAnswer: String(q.correctAnswer || q.correct_answer || "A").toUpperCase(),
                explanation: String(q.explanation || ""),
              },
            },
          });
        }
      } catch {}
    }
  }

  return c.json({
    success: true,
    data: {
      sourceType: "report_only",
      report: {
        id: report.id,
        examName: report.exam_name,
        reason: report.reason,
        comment: report.comment || "",
        studentEmail: report.student_email,
        questionText: report.question_text,
      },
      question: null,
    },
  });
});

telegramMiniAppApi.put("/reports/:id/fix-question", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  let validated: ReturnType<typeof parseAndValidateQuestions>[0];
  try {
    validated = parseAndValidateQuestions(JSON.stringify([body]))[0];
  } catch (err: any) {
    return c.json({ success: false, error: err?.message || "Invalid question fields or answer" }, 400);
  }
  const db = c.env.DB;
  const report = await db.prepare("SELECT * FROM question_reports WHERE id = ?").bind(id).first<QuestionReportRow>();
  if (!report) return c.json({ success: false, error: "Report not found" }, 404);
  const qId = String(report.question_id || "").trim();

  const bankRow = await db.prepare("SELECT id FROM questions WHERE id = ?").bind(qId).first<{ id: string }>();
  if (bankRow) {
    const upd = db
      .prepare(
        "UPDATE questions SET question_text = ?, option_a = ?, option_b = ?, option_c = ?, option_d = ?, correct_answer = ?, explanation = ? WHERE id = ?"
      )
      .bind(validated.questionText, validated.optionA, validated.optionB, validated.optionC, validated.optionD, validated.correctAnswer, validated.explanation, qId);
    const dismiss = db.prepare("UPDATE question_reports SET status = 'dismissed' WHERE question_id = ? AND status = 'pending'").bind(qId);
    const { stmt: audit } = prepareAdminAuditLog(db, user, {
      actionType: "FLAGGED_QUESTION_FIXED",
      description: `Fixed flagged question '${qId}' via Mini App`,
      targetType: "question",
      targetId: qId,
    });
    await runBatch(db, [upd, dismiss, audit]);
    return c.json({ success: true });
  }

  const parsedGen = parseGeneratedQuestionId(qId);
  if (parsedGen) {
    const test = await db.prepare("SELECT * FROM generated_tests WHERE id = ?").bind(parsedGen.testId).first<GeneratedTestRow>();
    if (test && test.questions_json) {
      let parsed: any = null;
      try { parsed = JSON.parse(test.questions_json); } catch {}
      const list = Array.isArray(parsed) ? parsed : Array.isArray(parsed?.questions) ? parsed.questions : null;
      if (list && parsedGen.index >= 0 && parsedGen.index < list.length) {
        list[parsedGen.index] = { ...list[parsedGen.index], ...validated, id: qId };
        const updatedJson = Array.isArray(parsed) ? JSON.stringify(list) : JSON.stringify({ ...parsed, questions: list });
        const upd = db.prepare("UPDATE generated_tests SET questions_json = ? WHERE id = ?").bind(updatedJson, test.id);
        const dismiss = db.prepare("UPDATE question_reports SET status = 'dismissed' WHERE question_id = ? AND status = 'pending'").bind(qId);
        const { stmt: audit } = prepareAdminAuditLog(db, user, {
          actionType: "FLAGGED_QUESTION_FIXED",
          description: `Fixed flagged test question '${qId}' via Mini App`,
          targetType: "generated_test",
          targetId: test.id,
        });
        await runBatch(db, [upd, dismiss, audit]);
        await purgeEdgeCache(c, ["/api/generated-tests"]);
        return c.json({ success: true });
      }
    }
  }
  return c.json({ success: false, error: "Referenced question not found" }, 404);
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

telegramMiniAppApi.delete("/reports/:id/question", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;
  const report = await db.prepare("SELECT * FROM question_reports WHERE id = ?").bind(id).first<QuestionReportRow>();
  if (!report) return c.json({ success: false, error: "Report not found" }, 404);
  const qId = String(report.question_id || "").trim();

  const bankRow = await db.prepare("SELECT id FROM questions WHERE id = ?").bind(qId).first();
  if (bankRow) {
    const del = db.prepare("DELETE FROM questions WHERE id = ?").bind(qId);
    const dismiss = db.prepare("UPDATE question_reports SET status = 'dismissed' WHERE question_id = ? AND status = 'pending'").bind(qId);
    const { stmt } = prepareAdminAuditLog(db, user, {
      actionType: "FLAGGED_QUESTION_DELETED",
      description: `Deleted flagged question '${qId}' via Mini App`,
      targetType: "question",
      targetId: qId,
    });
    await runBatch(db, [del, dismiss, stmt]);
    return c.json({ success: true });
  }

  const parsedGen = parseGeneratedQuestionId(qId);
  if (parsedGen) {
    const test = await db.prepare("SELECT * FROM generated_tests WHERE id = ?").bind(parsedGen.testId).first<GeneratedTestRow>();
    if (test && test.questions_json) {
      let parsed: any = null;
      try { parsed = JSON.parse(test.questions_json); } catch {}
      const list = Array.isArray(parsed) ? parsed : Array.isArray(parsed?.questions) ? parsed.questions : null;
      if (list && parsedGen.index >= 0 && parsedGen.index < list.length) {
        if (list.length <= 1) return c.json({ success: false, error: "Cannot delete the last remaining question in a test" }, 409);
        const nextList = list.filter((_: any, idx: number) => idx !== parsedGen.index);
        const updatedJson = Array.isArray(parsed) ? JSON.stringify(nextList) : JSON.stringify({ ...parsed, questions: nextList });
        const upd = db.prepare("UPDATE generated_tests SET questions_json = ?, question_count = ? WHERE id = ?").bind(updatedJson, nextList.length, test.id);
        const dismiss = db.prepare("UPDATE question_reports SET status = 'dismissed' WHERE question_id = ? AND status = 'pending'").bind(qId);
        const { stmt } = prepareAdminAuditLog(db, user, {
          actionType: "FLAGGED_QUESTION_DELETED",
          description: `Removed bad test question '${qId}' via Mini App`,
          targetType: "generated_test",
          targetId: test.id,
        });
        await runBatch(db, [upd, dismiss, stmt]);
        await purgeEdgeCache(c, ["/api/generated-tests"]);
        return c.json({ success: true });
      }
    }
  }
  return c.json({ success: false, error: "Referenced question not found" }, 404);
});

telegramMiniAppApi.post("/feedback/:id/reply", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  const reply = String(body.reply || "").trim().slice(0, 1000);
  if (!reply) return c.json({ success: false, error: "Reply cannot be empty" }, 400);
  const db = c.env.DB;
  const now = Date.now();
  const { stmt: audit } = prepareAdminAuditLog(db, user, {
    actionType: "FEEDBACK_REPLIED",
    description: `Replied to student feedback '${id}' via Mini App`,
    targetType: "feedback_message",
    targetId: id,
  });
  await db.batch([
    db
      .prepare(
        "INSERT INTO feedback_admin_replies (message_id, reply_text, replied_at) VALUES (?, ?, ?) ON CONFLICT(message_id) DO UPDATE SET reply_text = excluded.reply_text, replied_at = excluded.replied_at"
      )
      .bind(id, reply, now),
    db.prepare("UPDATE feedback_messages SET read = 1 WHERE id = ?").bind(id),
    audit,
  ]);
  return c.json({ success: true });
});

telegramMiniAppApi.post("/feedback/:id/read", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: "FEEDBACK_MESSAGE_READ",
    description: `Marked feedback '${id}' read via Mini App`,
    targetType: "feedback_message",
    targetId: id,
  });
  await db.batch([db.prepare("UPDATE feedback_messages SET read = 1 WHERE id = ?").bind(id), stmt]);
  return c.json({ success: true });
});

telegramMiniAppApi.delete("/feedback/:id", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: "FEEDBACK_MESSAGE_DELETED",
    description: `Deleted feedback '${id}' via Mini App`,
    targetType: "feedback_message",
    targetId: id,
  });
  await db.batch([
    db.prepare("DELETE FROM feedback_admin_replies WHERE message_id = ?").bind(id),
    db.prepare("DELETE FROM feedback_messages WHERE id = ?").bind(id),
    stmt,
  ]);
  return c.json({ success: true });
});

// ============================================================================
// 5. STUDENTS, PROFILE, ORDERS, PREMIUM PLANS & SUBSCRIBERS
// ============================================================================

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
         FROM users u ORDER BY u.last_active DESC LIMIT 200`
      )
      .bind(now),
    readDb.prepare("SELECT * FROM premium_orders WHERE status IN ('CREATED', 'PENDING') ORDER BY created_at DESC LIMIT 30"),
  ]);

  if (!users.length) {
    try {
      const legacy = await readDb
        .prepare("SELECT id, email, display_name, last_active, COALESCE(disabled, 0) AS disabled FROM users ORDER BY last_active DESC LIMIT 200")
        .all<any>();
      users = legacy?.results || [];
    } catch {}
  }

  let onlineCount = 0;
  let premCount = 0;
  let bannedCount = 0;
  users.forEach((u) => {
    if (Number(u.last_active || 0) >= onlineCutoff) onlineCount++;
    if (u.is_premium) premCount++;
    if (u.disabled) bannedCount++;
  });

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
      summary: { total: users.length, online: onlineCount, premium: premCount, banned: bannedCount },
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

telegramMiniAppApi.get("/students/:id", async (c) => {
  const readDb = getReadDb(c.env.DB);
  const id = c.req.param("id");
  const now = Date.now();
  const [userRows, entRows, attemptRows] = await batchReadRows<any>(readDb, [
    readDb.prepare("SELECT * FROM users WHERE id = ? LIMIT 1").bind(id),
    readDb.prepare("SELECT * FROM premium_entitlements WHERE user_id = ? LIMIT 1").bind(id),
    readDb.prepare("SELECT id, exam_id, exam_name, score, total, correct, timestamp FROM attempts WHERE user_id = ? ORDER BY timestamp DESC LIMIT 50").bind(id),
  ]);

  const u = userRows?.[0];
  if (!u) return c.json({ success: false, error: "Student not found" }, 404);

  const ent = entRows?.[0];
  const isEntActive = ent && ent.status === "ACTIVE" && (ent.is_lifetime === 1 || !ent.expires_at || ent.expires_at > now);

  const attempts = attemptRows || [];
  let sumScore = 0;
  let bestScore = 0;
  let totalCorrect = 0;
  let totalQ = 0;
  for (const a of attempts) {
    const sc = Number(a.score || 0);
    sumScore += sc;
    if (sc > bestScore) bestScore = sc;
    totalCorrect += Number(a.correct || 0);
    totalQ += Number(a.total || 0);
  }
  const avgScore = attempts.length > 0 ? Math.round((sumScore / attempts.length) * 10) / 10 : 0;
  const accuracy = totalQ > 0 ? Math.round((totalCorrect * 100) / totalQ) : 0;

  return c.json({
    success: true,
    data: {
      student: {
        id: u.id,
        eveId: u.eve_id || "",
        email: u.email || "",
        displayName: u.display_name || "Student",
        username: u.username || "",
        phone: u.phone || "",
        bio: u.bio || "",
        photoUrl: u.photo_url || u.avatar_url || "",
        disabled: Boolean(u.disabled),
      },
      entitlement: ent
        ? {
            planName: ent.plan_name,
            status: ent.status,
            isLifetime: Boolean(ent.is_lifetime),
            expiresAt: ent.expires_at,
            isActive: Boolean(isEntActive),
          }
        : null,
      stats: { totalTests: attempts.length, avgScore, bestScore, accuracy },
      attempts: attempts.map((a: any) => ({
        id: a.id,
        examId: a.exam_id,
        examName: a.exam_name,
        score: a.score,
        total: a.total,
        timestamp: a.timestamp,
      })),
    },
  });
});

telegramMiniAppApi.post("/users/:id/status", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  const disabled = Boolean(body.disabled);
  const db = c.env.DB;
  const upd = db.prepare("UPDATE users SET disabled = ? WHERE id = ?").bind(disabled ? 1 : 0, id);
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: disabled ? "USER_BANNED" : "USER_UNBANNED",
    description: `${disabled ? "Banned" : "Unbanned"} user '${id}' via Mini App`,
    targetType: "user",
    targetId: id,
  });
  await db.batch([upd, stmt]);
  return c.json({ success: true });
});

telegramMiniAppApi.post("/users/:id/premium", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  const isLifetime = Boolean(body.isLifetime);
  const days = isLifetime ? 0 : Math.max(1, Math.min(3650, parseInt(String(body.days || "30"), 10) || 30));
  await activateUserPremium(
    c.env.DB,
    id,
    "miniapp_grant",
    isLifetime ? "Lifetime Admin Access" : `${days}-Day Premium`,
    `MINIAPP_${Date.now()}`,
    user.uid,
    days,
    isLifetime,
    "TELEGRAM_MINIAPP_GRANT",
    {
      user,
      options: {
        actionType: "PREMIUM_GRANTED",
        description: `Granted ${isLifetime ? "Lifetime" : `${days}d`} Premium to '${id}' via Mini App`,
        targetType: "user",
        targetId: id,
      },
    }
  );
  return c.json({ success: true });
});

telegramMiniAppApi.post("/users/:id/premium/revoke", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;
  const upd = db.prepare("UPDATE premium_entitlements SET status = 'REVOKED', updated_at = ? WHERE user_id = ?").bind(Date.now(), id);
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: "PREMIUM_REVOKED",
    description: `Revoked Premium from '${id}' via Mini App`,
    targetType: "user",
    targetId: id,
  });
  await runBatch(db, [upd, stmt]);
  return c.json({ success: true });
});

telegramMiniAppApi.get("/orders", async (c) => {
  const readDb = getReadDb(c.env.DB);
  const filter = String(c.req.query("status") || "pending").toLowerCase();
  let whereSql = "";
  if (filter === "pending") whereSql = "WHERE status IN ('CREATED', 'PENDING')";
  else if (filter === "paid") whereSql = "WHERE status IN ('PAID', 'SUCCESS')";
  else if (filter === "rejected") whereSql = "WHERE status IN ('FAILED', 'REJECTED')";

  const { results } = await readDb
    .prepare(`SELECT * FROM premium_orders ${whereSql} ORDER BY created_at DESC LIMIT 100`)
    .all<PremiumOrderRow>();
  return c.json({
    success: true,
    data: (results || []).map((o) => ({
      orderId: o.id,
      userId: o.user_id,
      userEmail: o.user_email,
      planName: o.plan_name,
      amount: o.amount,
      providerPaymentId: o.provider_payment_id || "",
      status: o.status,
      createdAt: o.created_at,
    })),
  });
});

telegramMiniAppApi.post("/orders/:id/approve", async (c) => {
  const user = c.get("user");
  const orderId = c.req.param("id");
  const db = c.env.DB;
  const order = await db.prepare("SELECT * FROM premium_orders WHERE id = ?").bind(orderId).first<PremiumOrderRow>();
  if (!order) return c.json({ success: false, error: "Order not found" }, 404);
  if (order.status !== "SUCCESS" && order.status !== "PAID") {
    const now = Date.now();
    await db.prepare("UPDATE premium_orders SET status = 'SUCCESS', provider_payment_id = ?, paid_at = ? WHERE id = ?").bind(`MINIAPP_${now}`, now, orderId).run();
    await activateUserPremium(db, order.user_id, order.plan_id, order.plan_name, order.id, `MINIAPP_${now}`, order.duration_days, Boolean(order.is_lifetime), "TELEGRAM_MINIAPP_APPROVAL", {
      user,
      options: {
        actionType: "PREMIUM_ORDER_APPROVED",
        description: `Approved UPI order '${orderId}' via Mini App`,
        targetType: "premium_order",
        targetId: orderId,
      },
    });
  }
  return c.json({ success: true });
});

telegramMiniAppApi.post("/orders/:id/reject", async (c) => {
  const user = c.get("user");
  const orderId = c.req.param("id");
  const db = c.env.DB;
  const upd = db.prepare("UPDATE premium_orders SET status = 'FAILED' WHERE id = ?").bind(orderId);
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: "PREMIUM_ORDER_REJECTED",
    description: `Rejected UPI order '${orderId}' via Mini App`,
    targetType: "premium_order",
    targetId: orderId,
  });
  await db.batch([upd, stmt]);
  return c.json({ success: true });
});

telegramMiniAppApi.get("/premium/config", async (c) => {
  const cfg = await getPremiumConfig(getReadDb(c.env.DB));
  return c.json({
    success: true,
    data: {
      isEnabled: Boolean(cfg.is_enabled),
      planName: cfg.plan_name,
      priceInr: cfg.price_inr,
      durationDays: cfg.duration_days,
      isLifetime: Boolean(cfg.is_lifetime),
      qrEnabled: Boolean(cfg.qr_enabled),
      upiEnabled: Boolean(cfg.upi_enabled),
      merchantVpa: cfg.merchant_vpa,
      merchantName: cfg.merchant_name,
    },
  });
});

telegramMiniAppApi.put("/premium/config", async (c) => {
  const user = c.get("user");
  const db = c.env.DB;
  const body = await c.req.json().catch(() => ({}));
  const prev = await getPremiumConfig(db);
  const planName = String(body.planName || prev.plan_name).trim().slice(0, 100);
  const priceInr = Number(body.priceInr ?? prev.price_inr);
  const durationDays = Number(body.durationDays ?? prev.duration_days);
  const merchantVpa = String(body.merchantVpa ?? prev.merchant_vpa).trim();
  const merchantName = String(body.merchantName ?? prev.merchant_name).trim();
  if (!planName || !merchantVpa || !merchantName || !Number.isInteger(priceInr) || priceInr < 1 || !Number.isInteger(durationDays) || durationDays < 1) {
    return c.json({ success: false, error: "Invalid plan or UPI configuration fields" }, 400);
  }
  const now = Date.now();
  const stmt = db
    .prepare(
      `INSERT INTO premium_config (id, is_enabled, plan_name, price_inr, currency, duration_days, is_lifetime, description, benefits_json, qr_enabled, upi_enabled, session_expiry_minutes, merchant_vpa, merchant_name, webhook_secret, updated_at, updated_by)
       VALUES ('default', ?, ?, ?, 'INR', ?, ?, ?, ?, ?, ?, ?, ?, ?, '', ?, ?)
       ON CONFLICT(id) DO UPDATE SET
         is_enabled = excluded.is_enabled, plan_name = excluded.plan_name, price_inr = excluded.price_inr,
         duration_days = excluded.duration_days, is_lifetime = excluded.is_lifetime, qr_enabled = excluded.qr_enabled,
         upi_enabled = excluded.upi_enabled, merchant_vpa = excluded.merchant_vpa, merchant_name = excluded.merchant_name,
         updated_at = excluded.updated_at, updated_by = excluded.updated_by`
    )
    .bind(
      body.isEnabled !== undefined ? (body.isEnabled ? 1 : 0) : prev.is_enabled,
      planName,
      priceInr,
      durationDays,
      body.isLifetime !== undefined ? (body.isLifetime ? 1 : 0) : prev.is_lifetime,
      prev.description,
      prev.benefits_json,
      body.qrEnabled !== undefined ? (body.qrEnabled ? 1 : 0) : prev.qr_enabled,
      body.upiEnabled !== undefined ? (body.upiEnabled ? 1 : 0) : prev.upi_enabled,
      prev.session_expiry_minutes || 10,
      merchantVpa,
      merchantName,
      now,
      user.email
    );
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "PREMIUM_CONFIG_UPDATED",
    description: `Updated Premium plan '${planName}' (₹${priceInr}) via Mini App`,
    targetType: "premium_config",
    targetId: "default",
  });
  await runBatch(db, [stmt, auditStmt]);
  return c.json({ success: true });
});

telegramMiniAppApi.get("/premium/subscribers", async (c) => {
  const readDb = getReadDb(c.env.DB);
  const now = Date.now();
  const { results } = await readDb
    .prepare(
      "SELECT pe.*, u.email AS user_email, u.display_name FROM premium_entitlements pe LEFT JOIN users u ON pe.user_id = u.id ORDER BY pe.updated_at DESC LIMIT 200"
    )
    .all<any>();
  return c.json({
    success: true,
    data: (results || []).map((r) => {
      const expired = r.is_lifetime === 0 && r.expires_at !== null && r.expires_at <= now;
      return {
        userId: r.user_id,
        email: r.user_email || "",
        displayName: r.display_name || "Student",
        planName: r.plan_name,
        isLifetime: Boolean(r.is_lifetime),
        expiresAt: r.expires_at,
        status: expired ? "EXPIRED" : r.status,
      };
    }),
  });
});

// ============================================================================
// 6. ANALYTICS, CRASHES, SECURITY, AI USAGE, HEALTH, LEADERBOARD, ROLES, AUDIT
// ============================================================================

telegramMiniAppApi.get("/analytics-health", async (c) => {
  const readDb = getReadDb(c.env.DB);
  const ym = getUtcYearMonth();
  const monthPrefixStart = Date.UTC(Number(ym.slice(0, 4)), Number(ym.slice(5, 7)) - 1, 1);

  const [examRows, crashRows, eventRows, usageRows, monthAttRows, monthExamRows, lbRows] = await batchReadRows<any>(readDb, [
    readDb.prepare(
      `SELECT e.id AS exam_id, e.exam_name, COUNT(a.id) AS attempt_count, COALESCE(AVG(a.score), 0.0) AS average_score, COALESCE(MAX(a.score), 0.0) AS highest_score
       FROM exams e LEFT JOIN attempts a ON (e.id = a.exam_id OR substr(a.exam_id, 1, length(e.id) + 2) = e.id || '__') AND a.counted = 1
       GROUP BY e.id, e.exam_name ORDER BY attempt_count DESC`
    ),
    readDb.prepare("SELECT * FROM crash_signatures ORDER BY last_seen DESC LIMIT 60"),
    readDb.prepare("SELECT * FROM operation_events ORDER BY timestamp DESC LIMIT 60"),
    readDb.prepare("SELECT * FROM api_usage_monthly WHERE year_month = ?").bind(ym),
    readDb.prepare("SELECT COUNT(*) AS c FROM attempts WHERE timestamp >= ?").bind(monthPrefixStart),
    readDb.prepare("SELECT COUNT(*) AS c FROM generated_tests WHERE generated_at >= ?").bind(monthPrefixStart),
    readDb.prepare("SELECT user_id, display_name, score, accuracy FROM overall_leaderboard ORDER BY score DESC, accuracy DESC LIMIT 50"),
  ]);

  const uRow = usageRows?.[0] || {};
  const hasVault = Boolean(c.env.TELEGRAM_BOT_TOKEN?.trim() && c.env.TELEGRAM_MEDIA_CHANNEL_ID?.trim());
  const hasAi = Boolean(c.env.OPENROUTER_API_KEY || c.env.GROQ_API_KEY || c.env.OPENAI_API_KEY || c.env.GEMINI_API_KEY || c.env.GEMINI_API_KEYS);

  return c.json({
    success: true,
    data: {
      examAnalytics: (examRows || []).map((r: any) => ({
        examId: r.exam_id,
        examName: r.exam_name,
        attemptCount: Number(r.attempt_count || 0),
        averageScore: Math.round(Number(r.average_score || 0) * 10) / 10,
        highestScore: Math.round(Number(r.highest_score || 0) * 10) / 10,
      })),
      crashes: (crashRows || []).map((r: any) => ({
        signature: r.signature,
        exceptionClass: r.exception_class,
        topFrame: r.top_frame,
        occurrenceCount: Number(r.occurrence_count || 1),
        status: r.status || "open",
        lastSeen: Number(r.last_seen || 0),
        plainEnglishSummary: r.suggested_cause || r.sanitized_message || "App runtime crash",
        whereItHappened: String(r.top_frame || "").split("(")[0],
      })),
      securityEvents: (eventRows || []).map((r: any) => {
        const cat = String(r.category || "BACKEND_FAILURE").toUpperCase();
        const isSec = cat === "AUTHENTICATION" || cat === "AUTHORIZATION" || cat === "RATE_LIMIT";
        return {
          id: r.id,
          kind: isSec ? "security" : "app_error",
          severity: cat === "AUTHORIZATION" || Number(r.status) >= 500 ? "high" : "medium",
          plainEnglishTitle: `${r.operation || "API"} (${cat})`,
          plainEnglishDetail: `HTTP ${r.status || 500} · ${r.correlation_id || ""}`,
          timestamp: Number(r.timestamp || 0),
        };
      }),
      usage: {
        monthDisplayName: ym,
        geminiCalls: Number(uRow.gemini_calls || 0),
        geminiSuccessfulCalls: Number(uRow.gemini_successful_calls || 0),
        geminiFailedCalls: Number(uRow.gemini_failed_calls || 0),
        documentsCreated: Number(monthExamRows?.[0]?.c || 0),
        testSubmissions: Number(monthAttRows?.[0]?.c || 0),
      },
      infraHealth: {
        backend: { label: "BACKEND", status: "verified", detail: "Cloudflare Worker + Smart Placement active" },
        database: { label: "DATABASE", status: "verified", detail: "D1 SQL + Read Replication session ready" },
        firebase: { label: "FIREBASE", status: c.env.FIREBASE_PROJECT_ID ? "configured" : "unavailable", detail: c.env.FIREBASE_PROJECT_ID || "Missing FIREBASE_PROJECT_ID" },
        media: { label: "MEDIA (Telegram Vault)", status: hasVault || c.env.EMOJI_BUCKET ? "configured" : "unavailable", detail: hasVault ? "Telegram Bot Media Vault active" : "R2 Bucket / Vault fallback" },
        ai: { label: "AI FACTORY", status: hasAi ? "configured" : "unconfigured", detail: hasAi ? "Multi-Provider AI credentials configured" : "No AI keys found" },
      },
      leaderboard: (lbRows || []).map((r: any) => ({
        userId: r.user_id,
        displayName: r.display_name || "Student",
        score: r.score,
        accuracy: r.accuracy || 0,
      })),
    },
  });
});

telegramMiniAppApi.post("/crashes/:signature/resolve", async (c) => {
  const user = c.get("user");
  const signature = c.req.param("signature");
  const db = c.env.DB;
  const upd = db.prepare("UPDATE crash_signatures SET status = 'resolved' WHERE signature = ?").bind(signature);
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: "CRASH_RESOLVED",
    description: `Resolved crash signature '${signature}' via Mini App`,
    targetType: "crash_signature",
    targetId: signature,
  });
  await runBatch(db, [upd, stmt]);
  return c.json({ success: true });
});

telegramMiniAppApi.delete("/crashes/:signature", async (c) => {
  const user = c.get("user");
  const signature = c.req.param("signature");
  const db = c.env.DB;
  const { stmt } = prepareAdminAuditLog(db, user, {
    actionType: "CRASH_DELETED",
    description: `Deleted crash signature '${signature}' via Mini App`,
    targetType: "crash_signature",
    targetId: signature,
  });
  await runBatch(db, [
    db.prepare("DELETE FROM crash_incidents WHERE signature = ?").bind(signature),
    db.prepare("DELETE FROM crash_signatures WHERE signature = ?").bind(signature),
    stmt,
  ]);
  return c.json({ success: true });
});

telegramMiniAppApi.get("/roles", async (c) => {
  const readDb = getReadDb(c.env.DB);
  const [adminRows, rightsRows] = await batchReadRows<any>(readDb, [
    readDb.prepare("SELECT email, created_at FROM admins ORDER BY email ASC"),
    readDb.prepare("SELECT email, rights, custom_title FROM admin_rights"),
  ]);
  const rMap = new Map<string, { rights: string[]; customTitle: string }>();
  for (const r of rightsRows || []) {
    let arr: string[] = [];
    try { arr = JSON.parse(r.rights); } catch {}
    rMap.set(String(r.email).toLowerCase(), { rights: Array.isArray(arr) ? arr : [], customTitle: r.custom_title || "Admin" });
  }
  return c.json({
    success: true,
    data: {
      allRights: [...ALL_ADMIN_RIGHTS],
      admins: (adminRows || []).map((a: any) => {
        const lower = String(a.email).toLowerCase();
        const owner = isOwnerEmail(lower);
        const info = rMap.get(lower);
        return {
          email: a.email,
          isOwner: owner,
          customTitle: owner ? "Owner" : info?.customTitle || "Admin",
          rights: owner ? [...ALL_ADMIN_RIGHTS] : info?.rights || [],
        };
      }),
    },
  });
});

telegramMiniAppApi.post("/roles", async (c) => {
  const user = c.get("user");
  const db = c.env.DB;
  const body = await c.req.json().catch(() => ({}));
  const email = String(body.email || "").trim().toLowerCase();
  if (!email || !EMAIL_REGEX.test(email)) return c.json({ success: false, error: "Valid email required" }, 400);
  const customTitle = String(body.customTitle || "Admin").trim().slice(0, 64);
  const now = Date.now();
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "ADMIN_ADDED",
    description: `Added admin '${email}' via Mini App`,
    targetType: "admin",
    targetId: email,
  });
  await runBatch(db, [
    db.prepare("INSERT INTO admins (email, created_at) VALUES (?, ?) ON CONFLICT(email) DO NOTHING").bind(email, now),
    db.prepare("INSERT INTO admin_rights (email, rights, custom_title, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT(email) DO NOTHING").bind(email, JSON.stringify([]), customTitle, now),
    auditStmt,
  ]);
  return c.json({ success: true }, 201);
});

telegramMiniAppApi.put("/roles/:email/rights", async (c) => {
  const user = c.get("user");
  const email = decodeURIComponent(c.req.param("email") || "").trim().toLowerCase();
  if (isOwnerEmail(email)) return c.json({ success: false, error: "Owner rights cannot be modified" }, 403);
  const body = await c.req.json().catch(() => ({}));
  const rights: AdminRight[] = Array.isArray(body.rights)
    ? body.rights.filter((r: any) => ALL_ADMIN_RIGHTS.includes(r))
    : [];
  const customTitle = body.customTitle !== undefined ? String(body.customTitle).trim().slice(0, 64) : null;
  const db = c.env.DB;
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "ADMIN_RIGHTS_CHANGED",
    description: `Updated rights for '${email}' (${rights.length} permissions) via Mini App`,
    targetType: "admin",
    targetId: email,
  });
  await runBatch(db, [
    db
      .prepare(
        "INSERT INTO admin_rights (email, rights, custom_title, updated_at) VALUES (?1, ?2, ?3, ?4) ON CONFLICT(email) DO UPDATE SET rights = ?2, custom_title = COALESCE(?3, custom_title), updated_at = ?4"
      )
      .bind(email, JSON.stringify(rights), customTitle, Date.now()),
    auditStmt,
  ]);
  return c.json({ success: true });
});

telegramMiniAppApi.delete("/roles/:email", async (c) => {
  const user = c.get("user");
  const email = decodeURIComponent(c.req.param("email") || "").trim().toLowerCase();
  if (HARDCODED_ADMIN_EMAILS.has(email)) return c.json({ success: false, error: "Built-in Owner cannot be removed" }, 409);
  const db = c.env.DB;
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "ADMIN_REMOVED",
    description: `Removed admin '${email}' via Mini App`,
    targetType: "admin",
    targetId: email,
  });
  await runBatch(db, [
    db.prepare("DELETE FROM admins WHERE LOWER(TRIM(email)) = ?").bind(email),
    db.prepare("DELETE FROM admin_rights WHERE LOWER(TRIM(email)) = ?").bind(email),
    auditStmt,
  ]);
  return c.json({ success: true });
});

telegramMiniAppApi.get("/audit-log", async (c) => {
  const readDb = getReadDb(c.env.DB);
  const category = String(c.req.query("category") || "all").toLowerCase();
  const q = String(c.req.query("q") || "").trim();
  const conditions: string[] = [];
  const binds: any[] = [];

  if (category && category !== "all") {
    if (category === "system") {
      conditions.push(`NOT (${AUDIT_CATEGORY_PREDICATES.exams} OR ${AUDIT_CATEGORY_PREDICATES.students} OR ${AUDIT_CATEGORY_PREDICATES.content})`);
    } else if (AUDIT_CATEGORY_PREDICATES[category]) {
      conditions.push(AUDIT_CATEGORY_PREDICATES[category]);
    }
  }
  if (q) {
    const escaped = `%${q.replace(/[%_\\]/g, "\\$&")}%`;
    conditions.push("(description LIKE ? ESCAPE '\\' OR admin_email LIKE ? ESCAPE '\\' OR action_type LIKE ? ESCAPE '\\')");
    binds.push(escaped, escaped, escaped);
  }
  const whereSql = conditions.length ? `WHERE ${conditions.join(" AND ")}` : "";
  const { results } = await readDb
    .prepare(`SELECT id, action_type, description, admin_email, timestamp FROM admin_audit_log ${whereSql} ORDER BY timestamp DESC LIMIT 100`)
    .bind(...binds)
    .all<any>();

  return c.json({
    success: true,
    data: (results || []).map((r) => ({
      id: r.id,
      actionType: r.action_type,
      description: r.description,
      adminEmail: r.admin_email,
      timestamp: r.timestamp,
    })),
  });
});

telegramMiniAppApi.delete("/audit-log", async (c) => {
  const db = c.env.DB;
  const category = String(c.req.query("category") || "all").toLowerCase();
  let whereSql = "";
  if (category && category !== "all") {
    if (category === "system") {
      whereSql = `WHERE NOT (${AUDIT_CATEGORY_PREDICATES.exams} OR ${AUDIT_CATEGORY_PREDICATES.students} OR ${AUDIT_CATEGORY_PREDICATES.content})`;
    } else if (AUDIT_CATEGORY_PREDICATES[category]) {
      whereSql = `WHERE ${AUDIT_CATEGORY_PREDICATES[category]}`;
    }
  }
  const res = await db.prepare(`DELETE FROM admin_audit_log ${whereSql}`).run();
  return c.json({ success: true, deletedCount: Number(res?.meta?.changes || 0) });
});

// Backwards-compatible alias for `/moderation`
telegramMiniAppApi.get("/moderation", async (c) => {
  const readDb = getReadDb(c.env.DB);
  const [flags, auditLog, crashes] = await batchReadRows<any>(readDb, [
    readDb.prepare("SELECT id, exam_name, reason, question_text, student_email, timestamp FROM question_reports WHERE status = 'pending' ORDER BY timestamp DESC LIMIT 20"),
    readDb.prepare("SELECT id, action_type, description, admin_email, timestamp FROM admin_audit_log ORDER BY timestamp DESC LIMIT 20"),
    readDb.prepare("SELECT signature, exception_class, top_frame, occurrence_count, status, last_seen FROM crash_signatures WHERE status = 'open' ORDER BY last_seen DESC LIMIT 15"),
  ]);
  return c.json({ success: true, data: { flags: flags || [], auditLog: auditLog || [], crashes: crashes || [] } });
});

// ============================================================================
// 7. HTML ENTRYPOINT (Zero external build, Cache-Control: public, max-age=300)
// ============================================================================

telegramMiniAppPage.get("/", (c) => {
  c.header("Content-Type", "text/html; charset=utf-8");
  c.header("Cache-Control", "public, max-age=300");
  return c.html(renderMiniAppHtml());
});
