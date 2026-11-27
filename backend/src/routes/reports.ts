// ============================================================================
// Question Reports & Admin Audit Log Routes
// ============================================================================

import { Hono } from "hono";
import { parseAndValidateQuestions } from "../ai/generator";
import { getAdminPermissions } from "../auth";
import { requireAdmin, requirePermission } from "../middleware/authMiddleware";
import { checkRateLimit } from "../middleware/rateLimiter";
import { AuthUser, Env, GeneratedTestRow, QuestionReportRow, AdminAuditLogRow, QuestionRow } from "../types";
import { prepareAdminAuditLog, recordAdminAction, runBatch } from "../util/auditLog";
import { parseGeneratedQuestionId } from "./attempts";

export const reportRoutes = new Hono<{ Bindings: Env; Variables: { user: AuthUser } }>();
export const adminReportRoutes = new Hono<{ Bindings: Env; Variables: { user: AuthUser } }>();
export const adminAuditLogRoutes = new Hono<{ Bindings: Env; Variables: { user: AuthUser } }>();

export const CONTENT_ISSUES = [
  "Wrong Question",
  "No Solution",
  "Wrong Translation",
  "Out of Syllabus",
];

export const TECHNICAL_ISSUES = [
  "Question and Options not visible",
  "Blinking Screen Issue",
  "Formatting Issues",
  "Scroll Not Working",
  "Dark Mode Issue",
  "Question not visible but Options visible",
];

export const VALID_REASONS = new Set([
  ...CONTENT_ISSUES,
  ...TECHNICAL_ISSUES,
  "Other",
]);

export function isContentIssue(reason: string): boolean {
  return CONTENT_ISSUES.includes(reason) || reason === "Other" || reason.startsWith("Wrong");
}

// ----------------------------------------------------------------------------
// 1. Submit Question Report (Student)
// POST /api/reports
// ----------------------------------------------------------------------------
reportRoutes.post("/", async (c) => {
  const user = c.get("user");
  if (!user || !user.uid) {
    return c.json({ success: false, error: "Unauthorized" }, 401);
  }

  // Rate limit: 15 reports / minute per user
  const { limited, retryAfter } = await checkRateLimit(c.env.DB, `uid:${user.uid}:reports`, 15, 60);
  if (limited) {
    c.header("Retry-After", String(retryAfter));
    return c.json({ success: false, error: "Too many reports submitted. Please wait before retrying." }, 429);
  }

  const body = await c.req.json().catch(() => ({}));
  const questionId = String(body.questionId || "").trim();
  const examId = String(body.examId || "").trim();
  const examName = String(body.examName || "").trim();
  const rawQuestionText = String(body.questionText || "").trim();
  const reason = String(body.reason || "").trim();
  const rawComment = String(body.comment || "").trim();

  if (!questionId) {
    return c.json({ success: false, error: "questionId is required" }, 400);
  }
  if (!reason) {
    return c.json({ success: false, error: "reason is required" }, 400);
  }
  if (!VALID_REASONS.has(reason) && !reason.startsWith("Wrong")) {
    return c.json({ success: false, error: "Invalid report reason" }, 400);
  }
  if (rawComment.length > 500) {
    return c.json({ success: false, error: "Comment cannot exceed 500 characters" }, 400);
  }

  const comment = rawComment.slice(0, 500);
  const questionText = rawQuestionText.slice(0, 1000);
  const reportType = isContentIssue(reason) ? "content" : "technical";
  const studentId = user.uid;
  const studentEmail = user.email || "Student";
  const db = c.env.DB;

  // De-duplicate: same user + same question + same reason still pending -> return success without duplicate
  const existing = await db
    .prepare("SELECT id FROM question_reports WHERE student_id = ? AND question_id = ? AND reason = ? AND status = 'pending'")
    .bind(studentId, questionId, reason)
    .first<{ id: string }>();

  if (existing) {
    return c.json({ success: true, message: "Report already submitted", data: { id: existing.id } });
  }

  const id = crypto.randomUUID();
  const now = Date.now();

  await db
    .prepare(
      `INSERT INTO question_reports (
        id, question_id, exam_id, exam_name, question_text, reason, comment,
        student_id, student_email, timestamp, status, report_type
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)`
    )
    .bind(
      id,
      questionId,
      examId,
      examName,
      questionText,
      reason,
      comment,
      studentId,
      studentEmail,
      now,
      reportType
    )
    .run();

  return c.json({ success: true, data: { id } }, 201);
});

async function fetchPendingReports(db: any, typeParam?: string) {
  let query = "SELECT * FROM question_reports WHERE status = 'pending'";
  const params: any[] = [];

  if (typeParam === "content" || typeParam === "technical") {
    query += " AND report_type = ?";
    params.push(typeParam);
  }
  query += " ORDER BY timestamp DESC";

  const stmt = params.length > 0 ? db.prepare(query).bind(...params) : db.prepare(query);
  const { results } = await stmt.all();

  return (results || []).map((r: any) => ({
    id: r.id,
    questionId: r.question_id,
    examId: r.exam_id,
    examName: r.exam_name,
    questionText: r.question_text,
    reason: r.reason,
    comment: r.comment || "",
    studentId: r.student_id,
    studentEmail: r.student_email,
    timestamp: r.timestamp,
    status: r.status,
    reportType: r.report_type,
  }));
}

// Also allow GET /api/reports for convenience (admin only)
reportRoutes.get("/", requirePermission("moderate_feedback"), async (c) => {
  const typeParam = c.req.query("type")?.trim().toLowerCase();
  const list = await fetchPendingReports(c.env.DB, typeParam);
  return c.json({ success: true, data: list });
});

// ----------------------------------------------------------------------------
// 2. Admin Question Reports
adminReportRoutes.use("*", requirePermission("moderate_feedback"));

adminReportRoutes.get("/", async (c) => {
  const typeParam = c.req.query("type")?.trim().toLowerCase();
  const list = await fetchPendingReports(c.env.DB, typeParam);
  return c.json({ success: true, data: list });
});

function validReportId(value: unknown): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= 128 &&
    value === value.trim();
}

adminReportRoutes.put("/:id/dismiss", async (c) => {
  const id = c.req.param("id");
  if (!validReportId(id)) return c.json({ success: false, error: "Invalid report ID" }, 400);
  const db = c.env.DB;
  const pending = await db.prepare("SELECT id FROM question_reports WHERE id = ? AND status = 'pending'")
    .bind(id).first<{ id: string }>();
  if (!pending) return c.json({ success: false, error: "Pending report not found" }, 404);

  const update = db.prepare("UPDATE question_reports SET status = 'dismissed' WHERE id = ? AND status = 'pending'").bind(id);
  // D1 executes the ordered batch on one SQLite connection. changes() reflects
  // the immediately preceding UPDATE, so a racing dismissal cannot forge an audit.
  const { stmt: audit } = prepareAdminAuditLog(db, c.get("user"), {
    actionType: "REPORT_DISMISSED",
    description: `Dismissed question report '${id}'`,
    targetType: "question_report",
    targetId: id,
    condition: { sql: "changes() = 1", bindings: [] },
  });
  const results = await runBatch(db, [update, audit]);
  if (results[0]?.meta?.changes !== 1 || results[1]?.meta?.changes !== 1) {
    return c.json({ success: false, error: "Report dismissal not confirmed; reload before retrying" }, 409);
  }
  return c.json({ success: true });
});

// PUT /api/admin/reports/:id/fix-question - Atomically update reported question + dismiss pending reports + audit
adminReportRoutes.put("/:id/fix-question", async (c) => {
  const id = c.req.param("id");
  if (!validReportId(id)) {
    return c.json({ success: false, error: "Invalid report ID" }, 400);
  }
  const body = await c.req.json().catch(() => null);
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    return c.json({ success: false, error: "Provide question fields to fix" }, 400);
  }

  let validated: ReturnType<typeof parseAndValidateQuestions>[0];
  try {
    validated = parseAndValidateQuestions(JSON.stringify([body]))[0];
  } catch (err: any) {
    return c.json({ success: false, error: err?.message || "Invalid question text, options, or answer" }, 400);
  }

  const db = c.env.DB;
  const user = c.get("user");
  const report = await db
    .prepare("SELECT * FROM question_reports WHERE id = ?")
    .bind(id)
    .first<QuestionReportRow>();
  if (!report) {
    return c.json({ success: false, error: "Report not found" }, 404);
  }

  const qId = String(report.question_id || "").trim();
  const topic = typeof body.topic === "string" ? body.topic.trim() : undefined;
  const qTextHi = typeof body.questionTextHi === "string" ? body.questionTextHi.trim() : undefined;
  const optAHi = typeof body.optionAHi === "string" ? body.optionAHi.trim() : undefined;
  const optBHi = typeof body.optionBHi === "string" ? body.optionBHi.trim() : undefined;
  const optCHi = typeof body.optionCHi === "string" ? body.optionCHi.trim() : undefined;
  const optDHi = typeof body.optionDHi === "string" ? body.optionDHi.trim() : undefined;
  const expHi = typeof body.explanationHi === "string" ? body.explanationHi.trim() : undefined;

  const bankRow = await db.prepare("SELECT * FROM questions WHERE id = ?").bind(qId).first<QuestionRow>();
  if (bankRow) {
    const updateQuestion = db
      .prepare(
        `UPDATE questions SET
          question_text = ?, option_a = ?, option_b = ?, option_c = ?, option_d = ?,
          correct_answer = ?, explanation = ?, topic = ?,
          question_text_hi = ?, option_a_hi = ?, option_b_hi = ?, option_c_hi = ?, option_d_hi = ?, explanation_hi = ?
         WHERE id = ?`
      )
      .bind(
        validated.questionText,
        validated.optionA,
        validated.optionB,
        validated.optionC,
        validated.optionD,
        validated.correctAnswer,
        validated.explanation,
        topic !== undefined ? topic : (bankRow.topic || ""),
        qTextHi !== undefined ? qTextHi : (bankRow.question_text_hi || ""),
        optAHi !== undefined ? optAHi : (bankRow.option_a_hi || ""),
        optBHi !== undefined ? optBHi : (bankRow.option_b_hi || ""),
        optCHi !== undefined ? optCHi : (bankRow.option_c_hi || ""),
        optDHi !== undefined ? optDHi : (bankRow.option_d_hi || ""),
        expHi !== undefined ? expHi : (bankRow.explanation_hi || ""),
        qId
      );
    const resolveReports = db
      .prepare("UPDATE question_reports SET status = 'dismissed' WHERE question_id = ? AND status = 'pending'")
      .bind(qId);
    const { stmt: audit } = prepareAdminAuditLog(db, user, {
      actionType: "FLAGGED_QUESTION_FIXED",
      description: `Fixed flagged question '${qId}' via report '${id}'`,
      targetType: "question",
      targetId: qId,
      metadata: { reportId: id, sourceType: "bank" },
    });
    const results = await runBatch(db, [updateQuestion, resolveReports, audit]);
    if (results[0]?.meta?.changes !== 1 || results[2]?.meta?.changes !== 1) {
      return c.json({ success: false, error: "Question fix was not confirmed; retry" }, 409);
    }
    return c.json({
      success: true,
      data: {
        reportId: id,
        questionId: qId,
        resolvedReportsCount: Number(results[1]?.meta?.changes || 0),
        sourceType: "bank",
      },
    });
  }

  const parsedGen = parseGeneratedQuestionId(qId);
  if (parsedGen) {
    const test = await db
      .prepare("SELECT * FROM generated_tests WHERE id = ?")
      .bind(parsedGen.testId)
      .first<GeneratedTestRow>();
    if (test && test.questions_json) {
      let parsedJson: any = null;
      try { parsedJson = JSON.parse(test.questions_json); } catch {}
      const list = Array.isArray(parsedJson)
        ? parsedJson
        : Array.isArray(parsedJson?.questions)
        ? parsedJson.questions
        : null;
      if (list && parsedGen.index >= 0 && parsedGen.index < list.length) {
        const existingItem = list[parsedGen.index] && typeof list[parsedGen.index] === "object"
          ? list[parsedGen.index]
          : {};
        list[parsedGen.index] = {
          ...existingItem,
          id: qId,
          questionText: validated.questionText,
          optionA: validated.optionA,
          optionB: validated.optionB,
          optionC: validated.optionC,
          optionD: validated.optionD,
          correctAnswer: validated.correctAnswer,
          explanation: validated.explanation,
          ...(topic !== undefined ? { topic } : {}),
          ...(qTextHi !== undefined ? { questionTextHi: qTextHi } : {}),
          ...(optAHi !== undefined ? { optionAHi: optAHi } : {}),
          ...(optBHi !== undefined ? { optionBHi: optBHi } : {}),
          ...(optCHi !== undefined ? { optionCHi: optCHi } : {}),
          ...(optDHi !== undefined ? { optionDHi: optDHi } : {}),
          ...(expHi !== undefined ? { explanationHi: expHi } : {}),
        };
        const updatedJson = Array.isArray(parsedJson)
          ? JSON.stringify(list)
          : JSON.stringify({ ...parsedJson, questions: list });
        const updateTest = db
          .prepare("UPDATE generated_tests SET questions_json = ? WHERE id = ?")
          .bind(updatedJson, test.id);
        const resolveReports = db
          .prepare("UPDATE question_reports SET status = 'dismissed' WHERE question_id = ? AND status = 'pending'")
          .bind(qId);
        const { stmt: audit } = prepareAdminAuditLog(db, user, {
          actionType: "FLAGGED_QUESTION_FIXED",
          description: `Fixed flagged test question '${qId}' via report '${id}'`,
          targetType: "generated_test",
          targetId: test.id,
          metadata: { reportId: id, questionId: qId, sourceType: "generated_test" },
        });
        const results = await runBatch(db, [updateTest, resolveReports, audit]);
        if (results[0]?.meta?.changes !== 1 || results[2]?.meta?.changes !== 1) {
          return c.json({ success: false, error: "Generated test question fix was not confirmed; retry" }, 409);
        }
        return c.json({
          success: true,
          data: {
            reportId: id,
            questionId: qId,
            resolvedReportsCount: Number(results[1]?.meta?.changes || 0),
            sourceType: "generated_test",
          },
        });
      }
    }
  }

  return c.json({ success: false, error: "Referenced question not found" }, 404);
});

// DELETE /api/admin/reports/:id/question - Delete bad question + dismiss pending reports for it
adminReportRoutes.delete("/:id/question", async (c) => {
  const id = c.req.param("id");
  if (!validReportId(id)) {
    return c.json({ success: false, error: "Invalid report ID" }, 400);
  }
  const db = c.env.DB;
  const user = c.get("user");
  const report = await db
    .prepare("SELECT * FROM question_reports WHERE id = ?")
    .bind(id)
    .first<QuestionReportRow>();
  if (!report) {
    return c.json({ success: false, error: "Report not found" }, 404);
  }

  const qId = String(report.question_id || "").trim();
  const bankRow = await db.prepare("SELECT id FROM questions WHERE id = ?").bind(qId).first<{ id: string }>();
  if (bankRow) {
    const deleteQ = db.prepare("DELETE FROM questions WHERE id = ?").bind(qId);
    const resolveReports = db
      .prepare("UPDATE question_reports SET status = 'dismissed' WHERE question_id = ? AND status = 'pending'")
      .bind(qId);
    const { stmt: audit } = prepareAdminAuditLog(db, user, {
      actionType: "FLAGGED_QUESTION_DELETED",
      description: `Deleted flagged question '${qId}' via report '${id}'`,
      targetType: "question",
      targetId: qId,
      metadata: { reportId: id, sourceType: "bank" },
    });
    const results = await runBatch(db, [deleteQ, resolveReports, audit]);
    if (results[0]?.meta?.changes !== 1 || results[2]?.meta?.changes !== 1) {
      return c.json({ success: false, error: "Question deletion was not confirmed; retry" }, 409);
    }
    return c.json({ success: true, data: { questionId: qId, resolvedReportsCount: Number(results[1]?.meta?.changes || 0) } });
  }

  const parsedGen = parseGeneratedQuestionId(qId);
  if (parsedGen) {
    const test = await db
      .prepare("SELECT * FROM generated_tests WHERE id = ?")
      .bind(parsedGen.testId)
      .first<GeneratedTestRow>();
    if (test && test.questions_json) {
      let parsedJson: any = null;
      try { parsedJson = JSON.parse(test.questions_json); } catch {}
      const list = Array.isArray(parsedJson)
        ? parsedJson
        : Array.isArray(parsedJson?.questions)
        ? parsedJson.questions
        : null;
      if (list && parsedGen.index >= 0 && parsedGen.index < list.length) {
        if (list.length <= 1) {
          return c.json({
            success: false,
            error: "Cannot remove the only remaining question in a generated test; edit it or delete the test.",
          }, 409);
        }
        const nextList = list.filter((_: any, idx: number) => idx !== parsedGen.index);
        const updatedJson = Array.isArray(parsedJson)
          ? JSON.stringify(nextList)
          : JSON.stringify({ ...parsedJson, questions: nextList });
        const updateTest = db
          .prepare("UPDATE generated_tests SET questions_json = ?, question_count = ? WHERE id = ?")
          .bind(updatedJson, nextList.length, test.id);
        const resolveReports = db
          .prepare("UPDATE question_reports SET status = 'dismissed' WHERE question_id = ? AND status = 'pending'")
          .bind(qId);
        const { stmt: audit } = prepareAdminAuditLog(db, user, {
          actionType: "FLAGGED_QUESTION_DELETED",
          description: `Removed flagged test question '${qId}' from '${test.id}'`,
          targetType: "generated_test",
          targetId: test.id,
          metadata: { reportId: id, questionId: qId, sourceType: "generated_test" },
        });
        const results = await runBatch(db, [updateTest, resolveReports, audit]);
        if (results[0]?.meta?.changes !== 1 || results[2]?.meta?.changes !== 1) {
          return c.json({ success: false, error: "Generated test question removal was not confirmed; retry" }, 409);
        }
        return c.json({ success: true, data: { questionId: qId, resolvedReportsCount: Number(results[1]?.meta?.changes || 0) } });
      }
    }
  }

  return c.json({ success: false, error: "Referenced question not found" }, 404);
});

adminReportRoutes.post("/dismiss-batch", async (c) => {
  const body = await c.req.json().catch(() => null);
  const rawIds: unknown = body && typeof body === "object" && !Array.isArray(body) ? body.ids : null;
  if (!Array.isArray(rawIds) || rawIds.length < 1 || rawIds.length > 1000 ||
      !rawIds.every(validReportId) || new Set(rawIds).size !== rawIds.length) {
    return c.json({ success: false, error: "Provide 1 to 1000 unique valid report IDs" }, 400);
  }
  const ids: string[] = rawIds;
  const db = c.env.DB;
  // Cloudflare D1 permits only 100 bind parameters per statement. A variable
  // IN (?, ?, ...) duplicated in the WHERE clause exceeds that limit after 49
  // report IDs. Its supported SQLite JSON extension keeps this to <= 3 binds.
  const jsonIds = JSON.stringify(ids);
  const found = await db.prepare(
    "SELECT COUNT(*) AS count FROM question_reports WHERE status = 'pending' AND id IN (SELECT value FROM json_each(?))"
  ).bind(jsonIds).first<{ count: number }>();
  if (found?.count !== ids.length) {
    return c.json({ success: false, error: "Some pending reports are unavailable; reload before retrying" }, 409);
  }

  // One conditional UPDATE, rather than N independent writes, prevents partial
  // dismissal if even one report changes between preflight and the D1 transaction.
  const update = db.prepare(
    `UPDATE question_reports SET status = 'dismissed'
     WHERE status = 'pending' AND id IN (SELECT value FROM json_each(?))
       AND (SELECT COUNT(*) FROM question_reports
            WHERE status = 'pending' AND id IN (SELECT value FROM json_each(?))) = ?`
  ).bind(jsonIds, jsonIds, ids.length);
  const { stmt: audit } = prepareAdminAuditLog(db, c.get("user"), {
    actionType: "REPORT_BATCH_DISMISSED",
    description: `Batch dismissed ${ids.length} question reports`,
    targetType: "question_report",
    targetId: "batch",
    metadata: { count: ids.length },
    condition: { sql: "changes() = ?", bindings: [ids.length] },
  });
  const results = await runBatch(db, [update, audit]);
  if (results[0]?.meta?.changes !== ids.length || results[1]?.meta?.changes !== 1) {
    return c.json({ success: false, error: "Batch dismissal not confirmed; reload before retrying" }, 409);
  }
  return c.json({ success: true });
});

adminReportRoutes.delete("/:id", async (c) => {
  const id = c.req.param("id");
  if (!validReportId(id)) return c.json({ success: false, error: "Invalid report ID" }, 400);
  const db = c.env.DB;
  const row = await db.prepare("SELECT id FROM question_reports WHERE id = ?").bind(id).first<{ id: string }>();
  if (!row) return c.json({ success: false, error: "Report not found" }, 404);

  const removal = db.prepare("DELETE FROM question_reports WHERE id = ?").bind(id);
  const { stmt: audit } = prepareAdminAuditLog(db, c.get("user"), {
    actionType: "REPORT_DELETED",
    description: `Deleted question report '${id}'`,
    targetType: "question_report",
    targetId: id,
    condition: { sql: "changes() = 1", bindings: [] },
  });
  const results = await runBatch(db, [removal, audit]);
  if (results[0]?.meta?.changes !== 1 || results[1]?.meta?.changes !== 1) {
    return c.json({ success: false, error: "Report deletion not confirmed; reload before retrying" }, 409);
  }
  return c.json({ success: true });
});

// ----------------------------------------------------------------------------
// 3. Admin Audit Log Routes
// POST /api/admin/audit-log
// GET /api/admin/audit-log?range=today|7d|all&before=123456&admin=email&type=TYPE&q=search&limit=50
// GET /api/admin/audit-log/types
// ----------------------------------------------------------------------------
adminAuditLogRoutes.use("*", requirePermission("view_analytics_logs"));

const FORBIDDEN_METADATA_KEY_REGEX = /(token|secret|password|auth|answer|credential|phone|username|api_?key|secret_?key)/i;

function safeFormatMetadata(raw: any): Record<string, any> | null {
  if (!raw) return null;
  let obj = raw;
  if (typeof raw === "string") {
    try {
      obj = JSON.parse(raw);
    } catch {
      return null;
    }
  }
  if (typeof obj !== "object" || obj === null) return null;
  const safe: Record<string, any> = {};
  for (const [k, v] of Object.entries(obj)) {
    if (FORBIDDEN_METADATA_KEY_REGEX.test(k)) continue;
    if (typeof v === "string") {
      safe[k] = v.length > 200 ? v.substring(0, 200) + "..." : v;
    } else if (typeof v === "number" || typeof v === "boolean" || v === null) {
      safe[k] = v;
    } else if (Array.isArray(v)) {
      safe[k] = v.slice(0, 20).filter((item) => typeof item === "string" || typeof item === "number" || typeof item === "boolean");
    } else if (typeof v === "object") {
      safe[k] = safeFormatMetadata(v);
    }
  }
  return safe;
}

adminAuditLogRoutes.get("/types", async (c) => {
  const db = c.env.DB;
  const { results } = await db
    .prepare("SELECT DISTINCT action_type FROM admin_audit_log ORDER BY action_type ASC")
    .all<{ action_type: string }>();
  const types = (results || []).map((r) => r.action_type).filter(Boolean);
  return c.json({ success: true, data: types });
});

adminAuditLogRoutes.post("/", async (c) => {
  const user = c.get("user");
  const body = await c.req.json().catch(() => ({}));
  const actionType = String(body.actionType || "").trim();
  const description = String(body.description || "").trim().substring(0, 500);

  if (!actionType || !/^[A-Z0-9_]{3,64}$/.test(actionType)) {
    return c.json({ success: false, error: "Valid actionType is required" }, 400);
  }

  const db = c.env.DB;
  const { id, stmt } = prepareAdminAuditLog(db, user, {
    actionType,
    description,
    source: "client",
  });
  await stmt.run();

  return c.json({ success: true, data: { id } }, 201);
});

adminAuditLogRoutes.get("/", async (c) => {
  const range = c.req.query("range")?.trim().toLowerCase() || "all";
  const beforeRaw = c.req.query("before")?.trim();
  const adminFilter = c.req.query("admin")?.trim().toLowerCase();
  const typeFilter = c.req.query("type")?.trim().toUpperCase();
  const searchQuery = c.req.query("q")?.trim();
  const limitParam = parseInt(c.req.query("limit") || "50", 10);
  const limit = Math.max(1, Math.min(isNaN(limitParam) ? 50 : limitParam, 100));

  const now = Date.now();
  let cutoff = 0;
  if (range === "today") {
    const istOffset = 5.5 * 3600 * 1000;
    const istNow = new Date(now + istOffset);
    istNow.setUTCHours(0, 0, 0, 0);
    cutoff = istNow.getTime() - istOffset;
  } else if (range === "7d") {
    cutoff = now - 7 * 86400 * 1000;
  }

  const conditions: string[] = [];
  const bindings: any[] = [];

  if (cutoff > 0) {
    conditions.push("timestamp >= ?");
    bindings.push(cutoff);
  }

  if (beforeRaw) {
    const beforeTs = parseInt(beforeRaw, 10);
    if (!isNaN(beforeTs) && beforeTs > 0) {
      conditions.push("timestamp < ?");
      bindings.push(beforeTs);
    }
  }

  if (adminFilter && adminFilter !== "all") {
    conditions.push("LOWER(admin_email) = ?");
    bindings.push(adminFilter);
  }

  if (typeFilter && typeFilter !== "ALL") {
    conditions.push("action_type = ?");
    bindings.push(typeFilter);
  }

  if (searchQuery) {
    const escaped = `%${searchQuery.replace(/[%_\\]/g, "\\$&")}%`;
    conditions.push("(description LIKE ? ESCAPE '\\' OR target_id LIKE ? ESCAPE '\\' OR admin_email LIKE ? ESCAPE '\\')");
    bindings.push(escaped, escaped, escaped);
  }

  const whereClause = conditions.length > 0 ? "WHERE " + conditions.join(" AND ") : "";
  const query = `SELECT * FROM admin_audit_log ${whereClause} ORDER BY timestamp DESC, id DESC LIMIT ?`;
  bindings.push(limit + 1);

  const db = c.env.DB;
  const { results } = await db.prepare(query).bind(...bindings).all<AdminAuditLogRow>();
  const rows = results || [];
  const hasMore = rows.length > limit;
  const listRows = hasMore ? rows.slice(0, limit) : rows;

  const list = listRows.map((r) => {
    return {
      id: r.id,
      actionType: r.action_type,
      description: r.description,
      adminEmail: r.admin_email,
      timestamp: r.timestamp,
      actorUid: r.actor_uid || null,
      targetType: r.target_type || null,
      targetId: r.target_id || null,
      metadata: safeFormatMetadata(r.metadata),
      source: r.source || "server",
    };
  });

  const nextCursor = hasMore && list.length > 0 ? list[list.length - 1].timestamp : null;

  return c.json({
    success: true,
    data: list,
    nextCursor,
    hasMore,
  });
});

const AUDIT_CATEGORY_PREDICATES: Record<string, string> = {
  exams:
    "(action_type LIKE 'EXAM_%' OR action_type LIKE 'TEST_%' OR action_type LIKE 'GENERATED_TEST_%' OR action_type LIKE 'QUESTION_%' OR action_type LIKE 'SYLLABUS_%' OR action_type = 'JOB_RETRIED')",
  students:
    "(action_type LIKE 'USER_%' OR action_type LIKE 'PREMIUM_%' OR action_type LIKE 'PAYMENT_%')",
  content:
    "(action_type LIKE 'POLL_%' OR action_type LIKE 'BROADCAST_%' OR action_type LIKE 'BANNER_%' OR action_type LIKE 'REPORT_%' OR action_type LIKE 'FLAGGED_%' OR action_type LIKE 'ANNOUNCEMENT_CHANNEL_%' OR action_type LIKE 'FEEDBACK_%' OR action_type LIKE 'APP_CONTENT_%' OR action_type = 'FLOATING_LINK_UPDATED')",
};

// DELETE /api/admin/audit-log - Owner-only audit log clear (all, by category, or by types)
adminAuditLogRoutes.delete("/", async (c) => {
  const user = c.get("user");
  const db = c.env.DB;
  const perms = await getAdminPermissions(db, user?.email);
  if (!perms?.isOwner) {
    return c.json({ success: false, error: "Only the Owner can clear audit logs" }, 403);
  }

  const category = (c.req.query("category") || "").trim().toLowerCase();
  const typesRaw = (c.req.query("types") || "").trim();

  if (typesRaw) {
    const types = typesRaw
      .split(",")
      .map((t) => t.trim().toUpperCase())
      .filter(Boolean);
    if (types.length === 0 || types.length > 50 || types.some((t) => !/^[A-Z0-9_]{3,64}$/.test(t))) {
      return c.json({ success: false, error: "Invalid types parameter" }, 400);
    }
    const placeholders = types.map(() => "?").join(",");
    const res = await db
      .prepare(`DELETE FROM admin_audit_log WHERE action_type IN (${placeholders})`)
      .bind(...types)
      .run();
    return c.json({ success: true, deletedCount: Number(res?.meta?.changes || 0) });
  }

  if (category && category !== "all") {
    let whereSql: string;
    if (category === "system") {
      whereSql = `NOT (${AUDIT_CATEGORY_PREDICATES.exams} OR ${AUDIT_CATEGORY_PREDICATES.students} OR ${AUDIT_CATEGORY_PREDICATES.content})`;
    } else if (AUDIT_CATEGORY_PREDICATES[category]) {
      whereSql = AUDIT_CATEGORY_PREDICATES[category];
    } else {
      return c.json({ success: false, error: "Invalid audit log category" }, 400);
    }
    const res = await db.prepare(`DELETE FROM admin_audit_log WHERE ${whereSql}`).bind().run();
    return c.json({ success: true, deletedCount: Number(res?.meta?.changes || 0) });
  }

  const res = await db.prepare("DELETE FROM admin_audit_log").bind().run();
  return c.json({ success: true, deletedCount: Number(res?.meta?.changes || 0) });
});
