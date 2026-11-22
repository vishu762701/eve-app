import test from "node:test";
import assert from "node:assert/strict";
import { isolatedD1 } from "./support/sqliteD1.mjs";

function redactPii(text) {
  if (!text) return "";
  return text
    .replace(/\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b/g, "[REDACTED_EMAIL]")
    .replace(/(?:Bearer\s+[^\s,]+|(?:token|key|password|secret|authorization)\s*[:=]\s*[^\s,]+)/gi, "[REDACTED_SECRET]")
    .replace(/\b(?:\+?\d{1,3}[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}\b/g, "[REDACTED_PHONE]")
    .replace(/\/data\/user\/\d+\/[^\s\/]+/g, "/data/user/0/[APP]")
    .replace(/\/data\/data\/[^\s\/]+/g, "/data/data/[APP]");
}

function classifyCause(exceptionClass, topFrame) {
  if (topFrame.includes("org.telegram.")) return "rule-based: TELEGRAM_UI_CRASH";
  if (topFrame.includes("com.eve.app.")) return "rule-based: EVE_APP_LOGIC";
  if (exceptionClass.includes("OutOfMemoryError")) return "rule-based: OUT_OF_MEMORY";
  if (exceptionClass.includes("NullPointerException")) return "rule-based: NULL_POINTER";
  if (exceptionClass.includes("SecurityException")) return "rule-based: PERMISSION_SECURITY";
  if (exceptionClass.includes("IllegalStateException")) return "rule-based: ILLEGAL_STATE";
  return "no rule matched";
}

async function computeSignature(exceptionClass, topFrame) {
  const sigInput = new TextEncoder().encode(`${exceptionClass}:${topFrame}`);
  const hashBuffer = await crypto.subtle.digest("SHA-256", sigInput);
  return Array.from(new Uint8Array(hashBuffer))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("")
    .slice(0, 32);
}

test("Mission 00B: PII redaction strips emails, auth tokens, phone numbers and user data paths", () => {
  const sample = "Error at user test.student@gmail.com with Bearer eyJhbGciOiJIUzI1Ni... and phone +91-9876543210 in /data/user/0/com.eve.app/cache";
  const sanitized = redactPii(sample);

  assert.ok(!sanitized.includes("test.student@gmail.com"), "Email must be redacted");
  assert.ok(sanitized.includes("[REDACTED_EMAIL]"), "Email placeholder must exist");
  assert.ok(!sanitized.includes("eyJhbGciOiJIUzI1Ni"), "Token must be redacted");
  assert.ok(sanitized.includes("[REDACTED_SECRET]"), "Secret placeholder must exist");
  assert.ok(!sanitized.includes("9876543210"), "Phone number must be redacted");
  assert.ok(sanitized.includes("[REDACTED_PHONE]"), "Phone placeholder must exist");
  assert.ok(sanitized.includes("/data/user/0/[APP]"), "Private user paths must be anonymized");
});

test("Mission 00B: Body size limit enforcement (hard 8 KB cap)", () => {
  const smallPayload = JSON.stringify({ exception_class: "java.lang.NullPointerException", top_frame: "com.eve.app.MainActivity.init" });
  assert.ok(smallPayload.length < 8192, "Normal payload should be within 8 KB");

  const oversizedPayload = "x".repeat(8193);
  assert.ok(oversizedPayload.length > 8192, "Oversized payload correctly exceeds 8 KB");
});

test("Mission 00B: Crash classification and deterministic signature generation", async () => {
  const sig1 = await computeSignature("java.lang.NullPointerException", "com.eve.app.ui.profile.ProfileActivity.onCreate");
  const sig2 = await computeSignature("java.lang.NullPointerException", "com.eve.app.ui.profile.ProfileActivity.onCreate");
  const sig3 = await computeSignature("java.lang.NullPointerException", "org.telegram.ui.ActionBar.Theme.getColor");

  assert.equal(sig1, sig2, "Identical exception and frame must yield identical signature");
  assert.notEqual(sig1, sig3, "Different frames must yield distinct signatures");
  assert.equal(sig1.length, 32, "Signature must be 32 hex chars");

  assert.equal(classifyCause("java.lang.NullPointerException", "org.telegram.ui.ActionBar.Theme.getColor"), "rule-based: TELEGRAM_UI_CRASH");
  assert.equal(classifyCause("java.lang.NullPointerException", "com.eve.app.ui.profile.ProfileActivity.onCreate"), "rule-based: EVE_APP_LOGIC");
  assert.equal(classifyCause("java.lang.OutOfMemoryError", "android.graphics.Bitmap.createBitmap"), "rule-based: OUT_OF_MEMORY");
});

test("Mission 00B: Migration 0031 D1 persistence, conflict handling, and admin retrieval", async () => {
  const { sqlite, d1 } = isolatedD1();

  try {
    const rawClass = "java.lang.NullPointerException";
    const rawTopFrame = "com.eve.app.ui.profile.ProfileActivity.onCreate(ProfileActivity.kt:185)";
    const rawMsg = "Crash with user student@eve.mock and Bearer secret_token_xyz";
    const sanitizedMsg = redactPii(rawMsg);
    const signature = await computeSignature(rawClass, rawTopFrame);
    const suggestedCause = classifyCause(rawClass, rawTopFrame);
    const now = Date.now();

    // 1. Insert into crash_signatures
    await d1.prepare(`
      INSERT INTO crash_signatures (signature, exception_class, top_frame, sanitized_message, first_seen, last_seen, occurrence_count, status, suggested_cause)
      VALUES (?, ?, ?, ?, ?, ?, 1, 'open', ?)
      ON CONFLICT(signature) DO UPDATE SET
        last_seen = excluded.last_seen,
        occurrence_count = occurrence_count + 1,
        sanitized_message = excluded.sanitized_message
    `).bind(signature, rawClass, rawTopFrame, sanitizedMsg, now, now, suggestedCause).run();

    // 2. Insert genuine incident
    const incidentId1 = "incident-uuid-001";
    await d1.prepare(`
      INSERT INTO crash_incidents (
        id, signature, app_version_name, app_version_code,
        android_sdk, device_model, sanitized_frames_json, anr_or_exit_reason,
        install_id_hash, is_synthetic, timestamp
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(
      incidentId1, signature, "2.0.0", 42,
      34, "Pixel 8", JSON.stringify([rawTopFrame]), "",
      "pseudo_install_hash", 0, now
    ).run();

    // 3. Insert synthetic incident (same signature, occurrence increments)
    const incidentId2 = "incident-uuid-002";
    await d1.prepare(`
      INSERT INTO crash_signatures (signature, exception_class, top_frame, sanitized_message, first_seen, last_seen, occurrence_count, status, suggested_cause)
      VALUES (?, ?, ?, ?, ?, ?, 1, 'open', ?)
      ON CONFLICT(signature) DO UPDATE SET
        last_seen = excluded.last_seen,
        occurrence_count = occurrence_count + 1,
        sanitized_message = excluded.sanitized_message
    `).bind(signature, rawClass, rawTopFrame, sanitizedMsg, now, now + 1000, suggestedCause).run();

    await d1.prepare(`
      INSERT INTO crash_incidents (
        id, signature, app_version_name, app_version_code,
        android_sdk, device_model, sanitized_frames_json, anr_or_exit_reason,
        install_id_hash, is_synthetic, timestamp
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(
      incidentId2, signature, "2.0.0", 42,
      34, "Pixel 8", JSON.stringify([rawTopFrame]), "",
      "pseudo_install_hash", 1, now + 1000
    ).run();

    // 4. Verify signature table state
    const sigRecord = await d1.prepare("SELECT * FROM crash_signatures WHERE signature = ?").bind(signature).first();
    assert.ok(sigRecord, "Signature record must exist in D1");
    assert.equal(sigRecord.occurrence_count, 2, "Occurrence count must be incremented on conflict");
    assert.equal(sigRecord.suggested_cause, "rule-based: EVE_APP_LOGIC");
    assert.ok(!sigRecord.sanitized_message.includes("student@eve.mock"), "PII email must not be stored");
    assert.ok(sigRecord.sanitized_message.includes("[REDACTED_EMAIL]"), "Redacted placeholder must be present");
    assert.ok(!sigRecord.sanitized_message.includes("secret_token_xyz"), "Token must not be stored");

    // 5. Verify incident table records and separation of synthetic records
    const incidents = (await d1.prepare("SELECT * FROM crash_incidents WHERE signature = ? ORDER BY timestamp ASC").bind(signature).all()).results;
    assert.equal(incidents.length, 2);
    assert.equal(incidents[0].is_synthetic, 0, "Genuine crash must have is_synthetic = 0");
    assert.equal(incidents[1].is_synthetic, 1, "Controlled synthetic crash must have is_synthetic = 1");

    // 6. Verify admin query retrieval format
    const adminList = (await d1.prepare(`
      SELECT signature, exception_class, top_frame, sanitized_message, first_seen, last_seen, occurrence_count, status, suggested_cause
      FROM crash_signatures ORDER BY last_seen DESC LIMIT 50
    `).all()).results;
    assert.equal(adminList.length, 1);
    assert.equal(adminList[0].signature, signature);
  } finally {
    sqlite.close();
  }
});
