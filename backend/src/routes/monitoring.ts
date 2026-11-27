import { Hono } from 'hono';
import { getAdminPermissions } from '../auth';
import { requireAdmin, requirePermission } from '../middleware/authMiddleware';
import { getGeminiApiKeys } from '../ai/generator';
import { runGeneration, GenerationBusyError } from '../services/generation';
import { AuthUser, Env, ExamRow } from '../types';
import { prepareAdminAuditLog, recordAdminAction, runBatch } from '../util/auditLog';

export const monitoringRoutes = new Hono<{ Bindings: Env; Variables: { user: AuthUser } }>();
monitoringRoutes.use('*', requirePermission("view_analytics_logs"));

monitoringRoutes.get('/overview', async (c) => {
  const totals = await c.env.DB.prepare(`SELECT
    (SELECT COUNT(*) FROM users) AS users,
    (SELECT COUNT(*) FROM exams) AS exams,
    (SELECT COUNT(*) FROM generated_tests WHERE status IN ('live', 'published')) AS publishedTests,
    (SELECT COUNT(*) FROM questions) + (SELECT COALESCE(SUM(question_count), 0) FROM generated_tests) AS questions,
    (SELECT COUNT(*) FROM attempts) AS attempts`).first();
  const { results: activity } = await c.env.DB.prepare('SELECT id, exam_name, timestamp, counted FROM attempts ORDER BY timestamp DESC LIMIT 20').all();
  return c.json({ success: true, data: { totals, activity } });
});

monitoringRoutes.get('/health', async (c) => {
  const hasR2 = Boolean(c.env.EMOJI_BUCKET);
  const hasTelegramVault = Boolean(
    c.env.TELEGRAM_BOT_TOKEN?.trim() && c.env.TELEGRAM_MEDIA_CHANNEL_ID?.trim()
  );
  const checks: Record<string, { status: string; detail: string }> = {
    backend: { status: 'verified', detail: 'Authenticated API request reached the Worker' },
    database: { status: 'unconfigured', detail: 'D1 binding is missing' },
    firebase: { status: 'unconfigured', detail: 'Firebase project is missing' },
    firestore: { status: 'unavailable', detail: 'No authorized Firestore probe is configured in the Worker' },
    messaging: { status: 'unavailable', detail: 'FCM delivery is not tested by this read-only check' },
    media: {
      status: hasTelegramVault || hasR2 ? 'configured' : 'unavailable',
      detail: hasTelegramVault
        ? 'Telegram Media Vault (TELEGRAM_BOT_TOKEN and TELEGRAM_MEDIA_CHANNEL_ID) is configured; storage access is not tested'
        : hasR2
        ? 'Cloudflare R2 EMOJI_BUCKET is configured; Telegram Media Vault is unconfigured'
        : 'Telegram Media Vault is unavailable: set TELEGRAM_BOT_TOKEN and TELEGRAM_MEDIA_CHANNEL_ID on the Worker',
    },
    r2: {
      status: hasR2 ? 'configured' : 'unconfigured',
      detail: hasR2
        ? 'Cloudflare R2 EMOJI_BUCKET binding configured for emoji/sticker assets'
        : 'R2 EMOJI_BUCKET not bound in wrangler.toml; Telegram Media Vault active',
    },
    ai: { status: getGeminiApiKeys(c.env).length ? 'configured' : 'unconfigured', detail: 'Configuration check only; use the existing AI connection test to verify provider access' },
  };
  if (c.env.DB) {
    try {
      const checksToRun = [
        c.env.DB.prepare('SELECT 1 AS ok'),
        c.env.DB.prepare('SELECT id FROM generation_jobs LIMIT 1'),
        c.env.DB.prepare('SELECT id FROM operation_events LIMIT 1'),
        c.env.DB.prepare('SELECT questions_json, negative_marking_value, session_instance_id FROM attempt_sessions LIMIT 1'),
      ];
      if (typeof c.env.DB.batch === 'function') {
        await c.env.DB.batch(checksToRun);
      } else {
        await Promise.all(checksToRun.map((s) => s.first()));
      }
      checks.database = { status: 'verified', detail: 'D1 query and monitoring schema are available' };
    } catch { checks.database = { status: 'degraded', detail: 'D1 query or required migration failed' }; }
  }
  if (c.env.FIREBASE_PROJECT_ID) {
    try {
      const response = await fetch('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com', { signal: AbortSignal.timeout(5000) });
      const body = await response.json() as { keys?: unknown[] };
      checks.firebase = response.ok && Array.isArray(body.keys) && body.keys.length > 0
        ? { status: 'verified', detail: 'Firebase Auth public signing keys are reachable; Firestore and FCM are not tested by this check' }
        : { status: 'degraded', detail: 'Firebase Auth signing key check failed' };
    } catch { checks.firebase = { status: 'unavailable', detail: 'Firebase Auth signing key check timed out or failed' }; }
  }
  return c.json({ success: true, data: checks });
});

// Bounded pages. No user IDs, answer payloads, tokens or provider raw messages.
monitoringRoutes.get('/operations', async (c) => {
  const offset = Number(c.req.query('offset') || 0);
  if (!Number.isInteger(offset) || offset < 0 || offset > 10000) return c.json({ success: false, error: 'Invalid page offset' }, 400);
  const db = c.env.DB;
  const [jobs, failures, submissions] = await Promise.all([
    db.prepare('SELECT * FROM generation_jobs ORDER BY started_at DESC, id DESC LIMIT 25 OFFSET ?').bind(offset).all(),
    db.prepare('SELECT * FROM operation_events ORDER BY timestamp DESC, id DESC LIMIT 25 OFFSET ?').bind(offset).all(),
    db.prepare('SELECT id, exam_name, timestamp, counted FROM attempts ORDER BY timestamp DESC, id DESC LIMIT 25 OFFSET ?').bind(offset).all(),
  ]);
  return c.json({ success: true, data: { jobs: jobs.results || [], failures: failures.results || [], submissions: submissions.results || [], nextOffset: [jobs, failures, submissions].some((r) => r.results?.length === 25) && offset < 10000 ? offset + 25 : null } });
});

monitoringRoutes.post('/jobs/:id/retry', requirePermission("manage_generated_tests"), async (c) => {
  const user = c.get('user');
  const job = await c.env.DB.prepare('SELECT * FROM generation_jobs WHERE id = ?').bind(c.req.param('id')).first<any>();
  if (!job) return c.json({ success: false, error: 'Generation job not found' }, 404);
  if (job.status !== 'failed' || !job.retryable) return c.json({ success: false, error: 'This job is not retryable' }, 409);
  const exam = await c.env.DB.prepare('SELECT * FROM exams WHERE id = ?').bind(job.exam_id).first<ExamRow>();
  if (!exam) return c.json({ success: false, error: 'Exam no longer exists' }, 404);
  try {
    // A retry uses current syllabus/settings and the failed job's requested count.
    const data = await runGeneration(c.env, exam, job.requested_count, exam.test_number || 'Test 1', exam.generation_prompt || exam.custom_prompt_notes || '', 'retry', `retry:${job.id}`);
    await recordAdminAction(c.env.DB, user, {
      actionType: "JOB_RETRIED",
      description: `Retried generation job '${job.id}' for exam '${exam.exam_name}'`,
      targetType: "generation_job",
      targetId: job.id,
      metadata: { examId: exam.id, examName: exam.exam_name },
    });
    return c.json({ success: true, data });
  } catch (err) {
    return c.json({ success: false, error: err instanceof GenerationBusyError ? err.message : 'Generation retry failed; refresh operations for the safe category' }, err instanceof GenerationBusyError ? 409 : 502);
  }
});

// ----------------------------------------------------------------------------
// Crash Center, App Errors & Security Monitor Endpoints
// Protected by router-wide requirePermission("view_analytics_logs")
// ----------------------------------------------------------------------------
function describeTopFrameLocation(topFrame: string): string {
  const frame = (topFrame || "").trim();
  if (!frame) return "Unknown app screen";
  const withoutArgs = frame.split("(")[0].trim();
  const segments = withoutArgs.split(".");
  const className = segments.length >= 2 ? segments[segments.length - 2] : withoutArgs;
  const methodName = segments.length >= 2 ? segments[segments.length - 1] : "";
  const lineMatch = frame.match(/:(\d+)\)/);
  const lineSuffix = lineMatch ? ` (line ${lineMatch[1]})` : "";

  const screenMap: Record<string, string> = {
    EveTestPlayerFragment: "Test Player screen",
    EveTestResultFragment: "Test Result & Review screen",
    EveExamTestsFragment: "Exam Tests list",
    EveLeaderboardFragment: "Leaderboard screen",
    EveResultHistoryFragment: "Result History screen",
    EveAnnouncementsFragment: "Announcements & Polls feed",
    EveAdminControlCenterActivity: "Admin Control Center",
    EveAdminExamsFragment: "Admin Exams screen",
    EveAdminStudentsFragment: "Admin Students directory",
    EveAdminStudentDetailFragment: "Admin Student Profile screen",
    EveAdminFeedbackFragment: "Admin Feedback screen",
    EveAdminRolesFragment: "Admin Roles screen",
    EveAdminAuditLogFragment: "Admin Audit Log screen",
    EveBackendClient: "Cloud API sync",
    DialogsActivity: "Exams Home screen",
    ProfileActivity: "Profile screen",
    SettingsActivity: "Settings screen",
    Theme: "App Theme & Colors",
  };

  const friendlyModule = screenMap[className] || className.replace(/^Eve/, "EVE ");
  return methodName ? `${friendlyModule} in ${methodName}()${lineSuffix}` : `${friendlyModule}${lineSuffix}`;
}

function buildPlainEnglishCrashInfo(
  exceptionClass: string,
  topFrame: string,
  sanitizedMessage: string,
  suggestedCause: string
): { plainEnglishSummary: string; whereItHappened: string } {
  const ex = (exceptionClass || "").trim();
  const msg = (sanitizedMessage || "").trim();
  const whereItHappened = describeTopFrameLocation(topFrame);

  let causeText: string;
  if (ex.includes("NullPointerException")) {
    causeText = "The app tried to read a missing or uninitialized value";
  } else if (ex.includes("OutOfMemoryError")) {
    causeText = "The device ran out of memory while loading images, animations, or exam data";
  } else if (ex.includes("IllegalStateException")) {
    causeText = "A screen or background task ran in an unexpected lifecycle state";
  } else if (ex.includes("IndexOutOfBoundsException")) {
    causeText = "A list, question, or option index went out of range";
  } else if (ex.includes("ClassCastException")) {
    causeText = "An incompatible view or data model type was encountered";
  } else if (ex.includes("BadTokenException")) {
    causeText = "A popup dialog tried to open after its screen was already closed";
  } else if (ex.includes("SQLite")) {
    causeText = "Local device storage read or write failed";
  } else if (ex.includes("JSONException")) {
    causeText = "A server response could not be parsed cleanly";
  } else if (ex.includes("Timeout") || ex.includes("ConnectException") || ex.includes("UnknownHost")) {
    causeText = "Network connection timed out or could not reach the EVE server";
  } else if (ex.includes("SecurityException")) {
    causeText = "Android denied a required permission or security check";
  } else if (ex) {
    const shortEx = ex.includes(".") ? ex.substring(ex.lastIndexOf(".") + 1) : ex;
    causeText = `Unexpected ${shortEx} failure`;
  } else {
    causeText = suggestedCause && suggestedCause !== "no rule matched"
      ? suggestedCause
      : "Unexpected application error";
  }

  const cleanSnippet = msg && msg.length <= 120 ? `: ${msg}` : "";
  return {
    plainEnglishSummary: `${causeText}${cleanSnippet}`,
    whereItHappened,
  };
}

function enrichCrashSignatureRow(row: any) {
  if (!row) return row;
  const info = buildPlainEnglishCrashInfo(
    String(row.exception_class || ""),
    String(row.top_frame || ""),
    String(row.sanitized_message || ""),
    String(row.suggested_cause || "")
  );
  return {
    ...row,
    plainEnglishSummary: info.plainEnglishSummary,
    whereItHappened: info.whereItHappened,
  };
}

monitoringRoutes.get('/crashes', async (c) => {
  if (!c.env.DB) {
    return c.json({ success: false, error: "Database unavailable" }, 503);
  }
  const statusParam = (c.req.query("status") || "open").trim().toLowerCase();
  let whereSql = "WHERE COALESCE(status, 'open') = 'open'";
  const bindings: any[] = [];
  if (statusParam === "resolved") {
    whereSql = "WHERE status = 'resolved'";
  } else if (statusParam === "all") {
    whereSql = "";
  }
  const { results } = await c.env.DB.prepare(`
    SELECT signature, exception_class, top_frame, sanitized_message, first_seen, last_seen, occurrence_count, status, suggested_cause
    FROM crash_signatures
    ${whereSql}
    ORDER BY last_seen DESC LIMIT 100
  `).bind(...bindings).all();
  return c.json({ success: true, data: (results || []).map(enrichCrashSignatureRow) });
});

monitoringRoutes.get('/crashes/:signature', async (c) => {
  if (!c.env.DB) {
    return c.json({ success: false, error: "Database unavailable" }, 503);
  }
  const signature = c.req.param("signature");
  const sigRow = await c.env.DB.prepare(`
    SELECT signature, exception_class, top_frame, sanitized_message, first_seen, last_seen, occurrence_count, status, suggested_cause
    FROM crash_signatures
    WHERE signature = ?
  `).bind(signature).first();

  if (!sigRow) {
    return c.json({ success: false, error: "Crash signature not found" }, 404);
  }

  const { results: incidents } = await c.env.DB.prepare(`
    SELECT id, app_version_name, app_version_code, android_sdk, device_model, anr_or_exit_reason, sanitized_frames_json, timestamp, is_synthetic
    FROM crash_incidents
    WHERE signature = ?
    ORDER BY timestamp DESC LIMIT 20
  `).bind(signature).all();

  return c.json({ success: true, data: { signature: enrichCrashSignatureRow(sigRow), incidents: incidents || [] } });
});

monitoringRoutes.post('/crashes/:signature/resolve', async (c) => {
  if (!c.env.DB) {
    return c.json({ success: false, error: "Database unavailable" }, 503);
  }
  const db = c.env.DB;
  const user = c.get("user");
  const signature = (c.req.param("signature") || "").trim();
  if (!signature) {
    return c.json({ success: false, error: "Crash signature is required" }, 400);
  }

  const existing = await db
    .prepare("SELECT signature, exception_class, status FROM crash_signatures WHERE signature = ?")
    .bind(signature)
    .first<{ signature: string; exception_class: string; status: string }>();
  if (!existing) {
    return c.json({ success: false, error: "Crash signature not found" }, 404);
  }

  const updateStmt = db
    .prepare("UPDATE crash_signatures SET status = 'resolved' WHERE signature = ?")
    .bind(signature);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "CRASH_RESOLVED",
    description: `Marked crash signature '${existing.exception_class}' (${signature}) as resolved`,
    targetType: "crash_signature",
    targetId: signature,
  });
  await runBatch(db, [updateStmt, auditStmt]);

  return c.json({ success: true, data: { signature, status: "resolved" } });
});

monitoringRoutes.delete('/crashes/:signature', async (c) => {
  if (!c.env.DB) {
    return c.json({ success: false, error: "Database unavailable" }, 503);
  }
  const db = c.env.DB;
  const user = c.get("user");
  const perms = await getAdminPermissions(db, user?.email);
  if (!perms?.isOwner) {
    return c.json({ success: false, error: "Only the Owner can permanently delete crash signatures" }, 403);
  }

  const signature = (c.req.param("signature") || "").trim();
  if (!signature) {
    return c.json({ success: false, error: "Crash signature is required" }, 400);
  }
  const existing = await db
    .prepare("SELECT signature, exception_class FROM crash_signatures WHERE signature = ?")
    .bind(signature)
    .first<{ signature: string; exception_class: string }>();
  if (!existing) {
    return c.json({ success: false, error: "Crash signature not found" }, 404);
  }

  const delIncidents = db.prepare("DELETE FROM crash_incidents WHERE signature = ?").bind(signature);
  const delSig = db.prepare("DELETE FROM crash_signatures WHERE signature = ?").bind(signature);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "CRASH_DELETED",
    description: `Deleted crash signature '${existing.exception_class}' (${signature})`,
    targetType: "crash_signature",
    targetId: signature,
  });
  await runBatch(db, [delIncidents, delSig, auditStmt]);

  return c.json({ success: true, data: { signature, deleted: true } });
});

monitoringRoutes.get('/security-events', async (c) => {
  if (!c.env.DB) {
    return c.json({ success: false, error: "Database unavailable" }, 503);
  }
  const limitRaw = parseInt(c.req.query("limit") || "60", 10);
  const limit = Math.max(1, Math.min(isNaN(limitRaw) ? 60 : limitRaw, 100));

  const { results } = await c.env.DB.prepare(`
    SELECT id, operation, timestamp, category, status, retryable, correlation_id
    FROM operation_events
    ORDER BY timestamp DESC, id DESC
    LIMIT ?
  `).bind(limit).all<any>();

  let securityCount = 0;
  let errorCount = 0;

  const events = (results || []).map((row) => {
    const category = String(row.category || "BACKEND_FAILURE").toUpperCase();
    const operation = String(row.operation || "operation").toLowerCase();
    const status = Number(row.status || 500);
    const correlation = String(row.correlation_id || "");

    const isSecurity = category === "AUTHENTICATION" || category === "AUTHORIZATION" || category === "RATE_LIMIT";
    const kind = isSecurity ? "security" : "app_error";
    if (isSecurity) {
      securityCount++;
    } else {
      errorCount++;
    }

    let severity = "low";
    if (category === "AUTHORIZATION" || status >= 500) {
      severity = "high";
    } else if (category === "RATE_LIMIT" || category === "AUTHENTICATION") {
      severity = "medium";
    }

    let plainEnglishTitle = "Backend Operation Error";
    let plainEnglishDetail = `${operation} returned HTTP ${status}`;
    if (category === "AUTHORIZATION") {
      plainEnglishTitle = "Unauthorized Admin Access Blocked";
      plainEnglishDetail = `A non-admin or unpermitted request was blocked with HTTP ${status} (${operation}).`;
    } else if (category === "AUTHENTICATION") {
      plainEnglishTitle = "Rejected Authentication Attempt";
      plainEnglishDetail = `An invalid, missing, or expired credential was rejected with HTTP ${status} (${operation}).`;
    } else if (category === "RATE_LIMIT") {
      plainEnglishTitle = "Rate Limit Protection Triggered";
      plainEnglishDetail = `Excessive request burst was throttled with HTTP 429 (${operation}).`;
    } else if (operation === "submission") {
      plainEnglishTitle = status >= 500 ? "Test Submission Server Error" : "Test Submission Rejected";
      plainEnglishDetail = `A student test submission ended with HTTP ${status} (${category.toLowerCase()}).`;
    } else if (operation === "generation") {
      plainEnglishTitle = status >= 500 ? "AI Test Generation Failed" : "AI Generation Request Rejected";
      plainEnglishDetail = `An AI test generation job failed with HTTP ${status} (${category.toLowerCase()}).`;
    }

    if (correlation && correlation.includes("path=")) {
      plainEnglishDetail += ` • ${correlation}`;
    }

    return {
      id: String(row.id || ""),
      kind,
      severity,
      operation,
      category,
      status,
      retryable: Boolean(row.retryable),
      correlationId: correlation,
      plainEnglishTitle,
      plainEnglishDetail,
      timestamp: Number(row.timestamp || 0),
    };
  });

  return c.json({
    success: true,
    data: {
      events,
      securityCount,
      errorCount,
    },
  });
});
