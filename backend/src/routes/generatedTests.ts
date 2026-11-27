import { deletionInfo, deleteUnused } from '../services/deletion';
// ============================================================================
// AI Generated Tests Management Routes
// ============================================================================

import { runGeneration, GenerationBusyError } from "../services/generation";
import { parseAndValidateQuestions } from "../ai/generator";
import { hideAnswers } from "../util/clientProtocol";
import { getAdminPermissions } from "../auth";
import { Hono } from "hono";
import { GeminiProviderError } from "../ai/generator";
import { requireAdmin, requirePermission } from "../middleware/authMiddleware";
import { AuthUser, Env, ExamRow, GeneratedTestRow } from "../types";
import { prepareAdminAuditLog } from "../util/auditLog";
import { AssetStorage } from "../storage";
import {
  detectAvatarImage,
  extractStorageAvatarKey,
  getTableColumns,
  parseAvatarUploadPayload,
  serveStoredAvatarResponse,
} from "../util/avatarMedia";

export const generatedTestRoutes = new Hono<{ Bindings: Env; Variables: { user: AuthUser } }>();

// GET /api/generated-tests - List generated tests
generatedTestRoutes.get("/", async (c) => {
  const user = c.get("user");
  const examId = c.req.query("examId");
  const db = c.env.DB;
  // Admin membership alone must not disclose drafts or answer keys.
  const canManage = user.isAdmin && (await getAdminPermissions(db, user.email))?.rights.includes("manage_generated_tests") === true;

  let query = "SELECT * FROM generated_tests";
  const params: any[] = [];
  const conditions: string[] = [];

  if (examId) {
    conditions.push("exam_id = ?");
    params.push(examId.trim());
  }

  // Non-admins only see 'live' or 'published' tests
  if (!canManage) {
    conditions.push("status IN ('live', 'published')");
  }

  if (conditions.length > 0) {
    query += " WHERE " + conditions.join(" AND ");
  }

  query += " ORDER BY generated_at ASC";

  const { results } = await db.prepare(query).bind(...params).all<GeneratedTestRow>();
  const serverNow = Date.now();

  const list = (results || []).map((r) => {
    let questionCount = r.question_count;
    if (!questionCount && r.questions_json) {
      try {
        questionCount = JSON.parse(r.questions_json).length;
      } catch (_e) {}
    }

    let validationError = "";
    if (canManage) {
      try { if (parseAndValidateQuestions(r.questions_json).length !== questionCount) throw new Error(); }
      catch { validationError = "Invalid questions, duplicate options, answer keys or question count"; }
    }
    return {
      validationError,
      id: r.id,
      examId: r.exam_id,
      examName: r.exam_name,
      testNumber: r.test_number,
      title: r.title,
      generatedAt: r.generated_at,
      status: r.status,
      questionCount: questionCount || 0,
      availableFrom: r.available_from || 0,
      avatarUrl: String(r.avatar_url || "").trim(),
      avatarEmoji: String(r.avatar_emoji || "").trim(),
      avatarColor: typeof r.avatar_color === "number" ? r.avatar_color : null,
      questions: [],
    };
  });

  return c.json({ success: true, serverNow, data: list });
});

// GET /api/generated-tests/:id - Single generated test details
generatedTestRoutes.get("/:id", async (c) => {
  const id = c.req.param("id") || "";
  const user = c.get("user");
  const db = c.env.DB;
  const serverNow = Date.now();
  const canManage = user.isAdmin && (await getAdminPermissions(db, user.email))?.rights.includes("manage_generated_tests") === true;

  const row = await db.prepare("SELECT * FROM generated_tests WHERE id = ?").bind(id).first<GeneratedTestRow>();
  if (!row) {
    return c.json({ success: false, error: "Test not found" }, 404);
  }

  if (!canManage && row.status !== "live" && row.status !== "published") {
    return c.json({ success: false, error: "Test is not available" }, 403);
  }

  if (!canManage && (row.available_from || 0) > serverNow) return c.json({ success: false, error: "Test not open yet" }, 403);
  let questionCount = row.question_count;
  let questions: any[] = [];
  if (row.questions_json) {
    try {
      questions = JSON.parse(row.questions_json);
      if (!questionCount) {
        questionCount = questions.length;
      }
    } catch (_e) {}
  }

  let validationError = "";
  try { if (parseAndValidateQuestions(row.questions_json).length !== questionCount) throw new Error(); }
  catch { validationError = "Invalid questions or answer keys"; }
  if (!Array.isArray(questions)) questions = [];
  if (!canManage && validationError) return c.json({ success: false, error: "This test needs administrator repair before it can be attempted" }, 409);

  return c.json({
    success: true,
    serverNow,
    data: {
      validationError,
      id: row.id,
      examId: row.exam_id,
      examName: row.exam_name,
      testNumber: row.test_number,
      title: row.title,
      generatedAt: row.generated_at,
      status: row.status,
      questionCount: questionCount || 0,
      availableFrom: row.available_from || 0,
      avatarUrl: String(row.avatar_url || "").trim(),
      avatarEmoji: String(row.avatar_emoji || "").trim(),
      avatarColor: typeof row.avatar_color === "number" ? row.avatar_color : null,
      questions: (!canManage || hideAnswers(c)) ? questions.map((q) => ({ ...q, correctAnswer: "", correct_answer: "", answer: "", explanation: "", explanationHi: "", explanation_hi: "" })) : questions,
    },
  });
});

// GET /api/generated-tests/:id/avatar - Public asset reader for mock test avatars
generatedTestRoutes.get("/:id/avatar", async (c) => {
  const id = c.req.param("id") || "";
  const db = c.env.DB;
  const cols = await getTableColumns(db, "generated_tests");
  const row = await db.prepare("SELECT * FROM generated_tests WHERE id = ?").bind(id).first<GeneratedTestRow>();
  if (!row) {
    return c.json({ success: false, error: "Test not found" }, 404);
  }
  const storedUrl = cols.has("avatar_url") ? String(row.avatar_url || "").trim() : "";
  return serveStoredAvatarResponse(c, storedUrl, "test-avatars/");
});

// POST /api/generated-tests/:id/avatar - Upload photo/GIF/WebP or emoji avatar for mock test (Admin)
generatedTestRoutes.post("/:id/avatar", requirePermission("manage_generated_tests"), async (c) => {
  const user = c.get("user");
  const id = c.req.param("id") || "";
  const db = c.env.DB;
  const existing = await db.prepare("SELECT * FROM generated_tests WHERE id = ?").bind(id).first<GeneratedTestRow>();
  if (!existing) {
    return c.json({ success: false, error: "Test not found" }, 404);
  }

  const cols = await getTableColumns(db, "generated_tests");
  const hasAvatarUrl = cols.has("avatar_url");
  const hasAvatarEmoji = cols.has("avatar_emoji");
  const hasAvatarColor = cols.has("avatar_color");
  if (!hasAvatarUrl && !hasAvatarEmoji && !hasAvatarColor) {
    return c.json({ success: false, error: "Generated test avatar columns require migration 0035 first" }, 503);
  }

  const oldKey = extractStorageAvatarKey(String(existing.avatar_url || ""), "test-avatars/");
  const parsed = await parseAvatarUploadPayload(c);
  if (parsed.error) {
    return c.json({ success: false, error: parsed.error }, 400);
  }
  const { imageBytes, declaredMime, parsedBody } = parsed;
  const storage = new AssetStorage(c.env);

  if (imageBytes !== null) {
    if (!hasAvatarUrl) {
      return c.json({ success: false, error: "Generated test avatar_url column requires migration 0035 first" }, 503);
    }
    const detected = detectAvatarImage(imageBytes, declaredMime);
    if (!detected) {
      return c.json({ success: false, error: "Supported avatar formats: JPEG, PNG, WebP, GIF (max 5MB)" }, 400);
    }
    const storagePath = `test-avatars/${id}/${crypto.randomUUID()}.${detected.ext}`;
    const storedLocation = await storage.uploadFile(storagePath, imageBytes, detected.mime);
    const avatarUrl = storedLocation.startsWith("r2://")
      ? `/api/generated-tests/${encodeURIComponent(id)}/avatar?k=${encodeURIComponent(storagePath)}&v=${Date.now()}`
      : storedLocation;

    const sets: string[] = ["avatar_url = ?"];
    const binds: Array<string | number | null> = [avatarUrl];
    if (hasAvatarEmoji) sets.push("avatar_emoji = ''");
    if (hasAvatarColor) sets.push("avatar_color = NULL");
    binds.push(id);

    const updateStmt = db.prepare(`UPDATE generated_tests SET ${sets.join(", ")} WHERE id = ?`).bind(...binds);
    const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
      actionType: "GENERATED_TEST_AVATAR_UPDATED",
      description: `Uploaded ${detected.ext.toUpperCase()} avatar for test '${existing.title}'`,
      targetType: "generated_test",
      targetId: id,
      metadata: { mode: "image", mime: detected.mime },
    });
    try {
      const results = await db.batch([updateStmt, auditStmt]);
      if ((results[0]?.meta?.changes ?? 0) !== 1) throw new Error("Test update not confirmed");
    } catch {
      try { await storage.deleteFiles([storagePath]); } catch {}
      return c.json({ success: false, error: "Could not save test avatar; please retry" }, 409);
    }
    if (oldKey && oldKey !== storagePath) {
      try { await storage.deleteFiles([oldKey]); } catch {}
    }
    return c.json({ success: true, data: { avatarUrl, avatarEmoji: "", avatarColor: null } });
  }

  const emojiRaw = typeof parsedBody?.avatarEmoji === "string" ? parsedBody.avatarEmoji.trim() : "";
  if (emojiRaw.length > 32) {
    return c.json({ success: false, error: "avatarEmoji must be 32 characters or fewer" }, 400);
  }
  let avatarColor: number | null = null;
  if (parsedBody?.avatarColor !== undefined && parsedBody.avatarColor !== null && parsedBody.avatarColor !== "") {
    const num = Number(parsedBody.avatarColor);
    if (!Number.isInteger(num) || num < 0 || num > 31) {
      return c.json({ success: false, error: "avatarColor must be an integer between 0 and 31" }, 400);
    }
    avatarColor = num;
  }
  if (!emojiRaw && avatarColor === null) {
    return c.json({ success: false, error: "Provide an image file or emoji/color avatar (use DELETE to reset)" }, 400);
  }

  const sets: string[] = [];
  const binds: Array<string | number | null> = [];
  if (hasAvatarUrl) sets.push("avatar_url = ''");
  if (hasAvatarEmoji) {
    sets.push("avatar_emoji = ?");
    binds.push(emojiRaw);
  }
  if (hasAvatarColor) {
    sets.push("avatar_color = ?");
    binds.push(avatarColor);
  }
  binds.push(id);

  const updateStmt = db.prepare(`UPDATE generated_tests SET ${sets.join(", ")} WHERE id = ?`).bind(...binds);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "GENERATED_TEST_AVATAR_UPDATED",
    description: `Set emoji/color avatar for test '${existing.title}'`,
    targetType: "generated_test",
    targetId: id,
    metadata: { mode: "emoji", avatarEmoji: emojiRaw, avatarColor },
  });
  await db.batch([updateStmt, auditStmt]);
  if (oldKey) {
    try { await storage.deleteFiles([oldKey]); } catch {}
  }
  return c.json({ success: true, data: { avatarUrl: "", avatarEmoji: emojiRaw, avatarColor } });
});

// DELETE /api/generated-tests/:id/avatar - Clear mock test avatar (Admin)
generatedTestRoutes.delete("/:id/avatar", requirePermission("manage_generated_tests"), async (c) => {
  const user = c.get("user");
  const id = c.req.param("id") || "";
  const db = c.env.DB;
  const existing = await db.prepare("SELECT * FROM generated_tests WHERE id = ?").bind(id).first<GeneratedTestRow>();
  if (!existing) {
    return c.json({ success: false, error: "Test not found" }, 404);
  }
  const cols = await getTableColumns(db, "generated_tests");
  const oldKey = extractStorageAvatarKey(String(existing.avatar_url || ""), "test-avatars/");
  const sets: string[] = [];
  if (cols.has("avatar_url")) sets.push("avatar_url = ''");
  if (cols.has("avatar_emoji")) sets.push("avatar_emoji = ''");
  if (cols.has("avatar_color")) sets.push("avatar_color = NULL");
  if (sets.length > 0) {
    const updateStmt = db.prepare(`UPDATE generated_tests SET ${sets.join(", ")} WHERE id = ?`).bind(id);
    const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
      actionType: "GENERATED_TEST_AVATAR_DELETED",
      description: `Cleared avatar for test '${existing.title}'`,
      targetType: "generated_test",
      targetId: id,
    });
    await db.batch([updateStmt, auditStmt]);
  }
  if (oldKey) {
    try { await new AssetStorage(c.env).deleteFiles([oldKey]); } catch {}
  }
  return c.json({ success: true, data: { avatarUrl: "", avatarEmoji: "", avatarColor: null } });
});

// PUT /api/generated-tests/:id/schedule - Schedule test (Admin)
generatedTestRoutes.put("/:id/schedule", requirePermission("manage_generated_tests"), async (c) => {
  const user = c.get("user");
  const id = c.req.param("id") || "";
  const body = await c.req.json().catch(() => null);
  // Null, strings and unsafe numbers must never silently become a midnight schedule.
  const availableFrom = body && !Array.isArray(body) ? body.availableFrom : undefined;
  if (typeof availableFrom !== "number" || !Number.isSafeInteger(availableFrom) || availableFrom < 0) {
    return c.json({ success: false, error: "availableFrom must be a non-negative safe integer" }, 400);
  }

  const db = c.env.DB;
  if (!await db.prepare("SELECT id FROM generated_tests WHERE id = ?").bind(id).first()) {
    return c.json({ success: false, error: "Test not found" }, 404);
  }
  const updateStmt = db.prepare("UPDATE generated_tests SET available_from = ? WHERE id = ?").bind(availableFrom, id);
  // A deletion between the read and transaction must neither create a phantom audit nor report success.
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "GENERATED_TEST_SCHEDULE_UPDATED",
    description: `Updated schedule for test '${id}' (availableFrom=${availableFrom})`,
    targetType: "generated_test",
    targetId: id,
    metadata: { availableFrom },
    condition: { sql: "EXISTS (SELECT 1 FROM generated_tests WHERE id = ?)", bindings: [id] },
  });
  const results = await db.batch([updateStmt, auditStmt]);
  if (results[0]?.meta?.changes !== 1 || results[1]?.meta?.changes !== 1) {
    return c.json({ success: false, error: "Schedule update was not confirmed; reload before retrying" }, 409);
  }
  return c.json({ success: true, data: { id, availableFrom } });
});

// PUT /api/generated-tests/:id/status - Update test status (Admin)
generatedTestRoutes.put("/:id/status", requirePermission("manage_generated_tests"), async (c) => {
  const user = c.get("user");
  const id = c.req.param("id") || "";
  const body = await c.req.json().catch(() => null);
  if (!body || typeof body !== "object" || Array.isArray(body) || typeof body.status !== "string") {
    return c.json({ success: false, error: "status is required" }, 400);
  }
  const status = body.status.trim().toLowerCase();
  const db = c.env.DB;

  if (!["draft", "paused", "live", "published", "unpublished"].includes(status)) {
    return c.json({ success: false, error: "Invalid test status" }, 400);
  }
  const test = await db.prepare("SELECT * FROM generated_tests WHERE id = ?").bind(id).first<GeneratedTestRow>();
  if (!test) return c.json({ success: false, error: "Test not found" }, 404);
  if (["live", "published"].includes(status)) {
    try {
      const validated = parseAndValidateQuestions(test.questions_json);
      if (validated.length !== test.question_count) throw new Error("Question count mismatch");
    } catch { return c.json({ success: false, error: "Test has invalid questions or answer keys; repair it before publishing" }, 400); }
  }

  const updateStmt = db.prepare("UPDATE generated_tests SET status = ? WHERE id = ?").bind(status, id);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "GENERATED_TEST_STATUS_UPDATED",
    description: `Updated test status for '${id}' to '${status}'`,
    targetType: "generated_test",
    targetId: id,
    metadata: { status },
    condition: { sql: "EXISTS (SELECT 1 FROM generated_tests WHERE id = ?)", bindings: [id] },
  });
  const results = await db.batch([updateStmt, auditStmt]);
  if (results[0]?.meta?.changes !== 1 || results[1]?.meta?.changes !== 1) {
    return c.json({ success: false, error: "Status update was not confirmed; reload before retrying" }, 409);
  }
  // Keep the existing data-less status response compatible with ApiResponse<Unit>.
  return c.json({ success: true });
});

// DELETE /api/generated-tests/:id - Delete generated test (Admin)
generatedTestRoutes.get("/:id/deletion-info", requirePermission("manage_generated_tests"), async (c) => {
  const id = c.req.param("id") || "";
  if (!await c.env.DB.prepare("SELECT id FROM generated_tests WHERE id = ?").bind(id).first()) return c.json({ success: false, error: "Record not found" }, 404);
  return c.json({ success: true, data: await deletionInfo(c.env.DB, id, 'test') });
});

generatedTestRoutes.delete("/:id", requirePermission("manage_generated_tests"), async (c) => {
  const user = c.get("user");
  const id = c.req.param("id") || "";
  const db = c.env.DB;
  if (!await db.prepare("SELECT id FROM generated_tests WHERE id = ?").bind(id).first()) return c.json({ success: false, error: "Record not found" }, 404);
  const dependencies = await deletionInfo(db, id, 'test');
  if (!dependencies.canDelete) return c.json({ success: false, error: dependencies.message, code: dependencies.code, dependencies, requestId: c.res.headers.get('X-Request-ID') }, 409);

  if (!await deleteUnused(db, id, 'test', user)) {
    const current = await deletionInfo(db, id, 'test');
    return c.json({ success: false, error: current.message || 'Record changed. Refresh before retrying.', code: current.code || 'DELETE_RECORD_CHANGED', dependencies: current, requestId: c.res.headers.get('X-Request-ID') }, 409);
  }

  return c.json({ success: true });
});

// POST /api/generated-tests/generate-now - On-demand AI test generation (Admin)
generatedTestRoutes.post("/generate-now", requirePermission("manage_generated_tests"), async (c) => {
  const user = c.get("user");
  const body = await c.req.json().catch(() => ({}));
  const examId = String(body.examId || "").trim();
  const db = c.env.DB;

  if (!examId) {
    return c.json({ success: false, error: "examId is required" }, 400);
  }

  const exam = await db.prepare("SELECT * FROM exams WHERE id = ?").bind(examId).first<ExamRow>();
  if (!exam) {
    return c.json({ success: false, error: "Exam not found" }, 404);
  }

  const examName = exam.exam_name || "Mock Test";
  const targetCount = Number(body.questionCount ?? exam.question_count ?? 20);
  if (!Number.isInteger(targetCount) || targetCount < 1 || targetCount > 200) return c.json({ success: false, error: "questionCount must be an integer between 1 and 200" }, 400);
  const testNumber = String(body.testNumber || exam.test_number || "Test 1").trim();
  const customPrompt = String(
    body.aiPrompt || body.customPromptNotes || exam.ai_prompt || exam.generation_prompt || exam.custom_prompt_notes || ""
  ).trim();

  let effectiveExam = exam;
  if (body.aiLanguage !== undefined && body.aiLanguage !== null && String(body.aiLanguage).trim() !== "") {
    const lang = String(body.aiLanguage).trim().toLowerCase();
    if (lang !== "english" && lang !== "hindi" && lang !== "bilingual") {
      return c.json({ success: false, error: "aiLanguage must be 'english', 'hindi', or 'bilingual'" }, 400);
    }
    effectiveExam = { ...effectiveExam, ai_language: lang };
  }
  if (body.aiDifficulty !== undefined && body.aiDifficulty !== null && String(body.aiDifficulty).trim() !== "") {
    const diff = String(body.aiDifficulty).trim().toLowerCase();
    if (diff !== "easy" && diff !== "medium" && diff !== "hard" && diff !== "mixed") {
      return c.json({ success: false, error: "aiDifficulty must be 'easy', 'medium', 'hard', or 'mixed'" }, 400);
    }
    effectiveExam = { ...effectiveExam, ai_difficulty: diff };
  }

  try {
    // Generation and its admin audit commit together before acknowledgment.
    const data = await runGeneration(c.env, effectiveExam, targetCount, testNumber, customPrompt,
      "manual", body.requestId ? String(body.requestId).slice(0, 100) : undefined, user);
    return c.json({ success: true, data });
  } catch (err: any) {
    if (err instanceof GenerationBusyError) return c.json({ success: false, error: err.message, code: "GENERATION_BUSY" }, 409);
    const provider = err instanceof GeminiProviderError;
    return c.json({ success: false, error: provider ? err.userFacingMessage : "Generation could not be completed. Check the system monitor and retry.", code: provider ? err.code : "GENERATION_FAILED" }, provider ? 502 : 500);
  }
});
