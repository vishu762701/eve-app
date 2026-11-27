// ============================================================================
// Crash Ingestion Route for Student Clients (Mission 00B)
// POST /api/crashes
// ============================================================================

import { Hono } from "hono";
import { checkRateLimit } from "../middleware/rateLimiter";
import { alertNewCrashSignature } from "../services/telegramAdminAlerts";
import { AuthUser, Env } from "../types";

export const crashIngestRoutes = new Hono<{ Bindings: Env; Variables: { user: AuthUser } }>();

function redactPii(text: string): string {
  if (!text) return "";
  return text
    .replace(/\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b/g, "[REDACTED_EMAIL]")
    .replace(/(?:Bearer\s+[^\s,]+|(?:token|key|password|secret|authorization)\s*[:=]\s*[^\s,]+)/gi, "[REDACTED_SECRET]")
    .replace(/\b(?:\+?\d{1,3}[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}\b/g, "[REDACTED_PHONE]")
    .replace(/\/data\/user\/\d+\/[^\s\/]+/g, "/data/user/0/[APP]")
    .replace(/\/data\/data\/[^\s\/]+/g, "/data/data/[APP]");
}

function classifyCause(exceptionClass: string, topFrame: string): string {
  if (topFrame.includes("org.telegram.")) return "rule-based: TELEGRAM_UI_CRASH";
  if (topFrame.includes("com.eve.app.")) return "rule-based: EVE_APP_LOGIC";
  if (exceptionClass.includes("OutOfMemoryError")) return "rule-based: OUT_OF_MEMORY";
  if (exceptionClass.includes("NullPointerException")) return "rule-based: NULL_POINTER";
  if (exceptionClass.includes("SecurityException")) return "rule-based: PERMISSION_SECURITY";
  if (exceptionClass.includes("IllegalStateException")) return "rule-based: ILLEGAL_STATE";
  return "no rule matched";
}

crashIngestRoutes.post("/", async (c) => {
  const user = c.get("user");
  if (!user || !user.uid) {
    return c.json({ success: false, error: "Unauthorized" }, 401);
  }

  // 1. Hard 8 KB body limit
  const rawBody = await c.req.text().catch(() => "");
  if (rawBody.length > 8192) {
    return c.json({ success: false, error: "Payload exceeds 8 KB maximum limit" }, 413);
  }

  // 2. JSON Parse
  let body: any;
  try {
    body = JSON.parse(rawBody);
  } catch {
    return c.json({ success: false, error: "Invalid JSON payload" }, 400);
  }

  // 3. Rate limit: 10 reports / minute per user
  const { limited, retryAfter } = await checkRateLimit(c.env.DB, `uid:${user.uid}:crashes`, 10, 60);
  if (limited) {
    c.header("Retry-After", String(retryAfter));
    return c.json({ success: false, error: "Too many crash reports submitted. Please wait." }, 429);
  }

  // 4. Validate & Sanitize Fields
  const rawClass = String(body.exception_class || "").trim().slice(0, 200);
  const rawTopFrame = String(body.top_frame || "").trim().slice(0, 500);
  const rawMessage = redactPii(String(body.message || "").trim().slice(0, 1000));
  const rawFrames = Array.isArray(body.stack_frames)
    ? JSON.stringify(body.stack_frames.slice(0, 15).map((f: any) => redactPii(String(f).trim().slice(0, 300))))
    : redactPii(String(body.stack_frames || "").trim().slice(0, 2000));
  const appVersionName = String(body.app_version_name || "").trim().slice(0, 50);
  const appVersionCode = Number.isInteger(body.app_version_code) ? body.app_version_code : 0;
  const androidSdk = Number.isInteger(body.android_sdk) ? body.android_sdk : 0;
  const deviceModel = String(body.device_model || "").trim().slice(0, 100);
  const anrOrExitReason = String(body.anr_or_exit_reason || "").trim().slice(0, 200);
  const installIdHash = String(body.install_id_hash || "").trim().slice(0, 64);
  const isSynthetic = body.is_synthetic === true || body.is_synthetic === 1 ? 1 : 0;

  if (!rawClass || !rawTopFrame) {
    return c.json({ success: false, error: "Missing required fields: exception_class and top_frame" }, 400);
  }

  // 5. Compute deterministic SHA-256 signature
  const sigInput = new TextEncoder().encode(`${rawClass}:${rawTopFrame}`);
  const hashBuffer = await crypto.subtle.digest("SHA-256", sigInput);
  const signature = Array.from(new Uint8Array(hashBuffer))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("")
    .slice(0, 32);

  const now = Date.now();
  const suggestedCause = classifyCause(rawClass, rawTopFrame);

  const prevSig = await c.env.DB.prepare("SELECT status FROM crash_signatures WHERE signature = ?")
    .bind(signature)
    .first<{ status?: string }>()
    .catch(() => null);
  const isNewOrReopened = !prevSig || String(prevSig.status || "").toLowerCase() !== "open";

  // 6. Persist signature and incident in D1 (reopen if a previously resolved crash recurs)
  await c.env.DB.prepare(`
    INSERT INTO crash_signatures (signature, exception_class, top_frame, sanitized_message, first_seen, last_seen, occurrence_count, status, suggested_cause)
    VALUES (?, ?, ?, ?, ?, ?, 1, 'open', ?)
    ON CONFLICT(signature) DO UPDATE SET
      last_seen = excluded.last_seen,
      occurrence_count = occurrence_count + 1,
      sanitized_message = excluded.sanitized_message,
      status = 'open',
      suggested_cause = excluded.suggested_cause
  `).bind(signature, rawClass, rawTopFrame, rawMessage, now, now, suggestedCause).run();

  const incidentId = crypto.randomUUID();
  await c.env.DB.prepare(`
    INSERT INTO crash_incidents (id, signature, app_version_name, app_version_code, android_sdk, device_model, anr_or_exit_reason, sanitized_frames_json, install_id_hash, timestamp, is_synthetic)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).bind(incidentId, signature, appVersionName, appVersionCode, androidSdk, deviceModel, anrOrExitReason, rawFrames, installIdHash, now, isSynthetic).run();

  if (isNewOrReopened) {
    alertNewCrashSignature(c, {
      signature,
      exceptionClass: rawClass,
      topFrame: rawTopFrame,
      appVersionName,
      deviceModel,
      reopened: Boolean(prevSig),
    });
  }

  return c.json({ success: true, signature, incidentId, suggestedCause });
});
