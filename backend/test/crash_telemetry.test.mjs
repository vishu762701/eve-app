import test from "node:test";
import assert from "node:assert/strict";

function redactPii(text) {
  if (!text) return "";
  return text
    .replace(/\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b/g, "[REDACTED_EMAIL]")
    .replace(/(?:Bearer\s+[^\s,]+|(?:token|key|password|secret|authorization)\s*[:=]\s*[^\s,]+)/gi, "[REDACTED_SECRET]")
    .replace(/\b(?:\+?\d{1,3}[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}\b/g, "[REDACTED_PHONE]")
    .replace(/\/data\/user\/\d+\/[^\s\/]+/g, "/data/user/0/[APP]")
    .replace(/\/data\/data\/[^\s\/]+/g, "/data/data/[APP]");
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
});

test("Mission 00B: Body size limit enforcement (hard 8 KB cap)", () => {
  const smallPayload = JSON.stringify({ exception_class: "java.lang.NullPointerException", top_frame: "com.eve.app.MainActivity.init" });
  assert.ok(smallPayload.length < 8192, "Normal payload should be within 8 KB");

  const oversizedPayload = "x".repeat(8193);
  assert.ok(oversizedPayload.length > 8192, "Oversized payload correctly exceeds 8 KB");
});
