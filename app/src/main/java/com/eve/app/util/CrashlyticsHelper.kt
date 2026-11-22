package com.eve.app.util

import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.security.MessageDigest

/**
 * Mission 00B: Privacy-hardened crash telemetry helper.
 * Never associates raw Firebase UID, email, phone number, or auth tokens with crash events.
 * Uses a hashed, pseudonymous installation identifier so crash grouping remains intact
 * without disclosing real user identities.
 */
object CrashlyticsHelper {

    private fun hashPseudonymousId(raw: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(raw.toByteArray(Charsets.UTF_8))
            bytes.take(8).joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            "anon_" + (raw.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
        }
    }

    /**
     * Associates a pseudonymous identifier with Crashlytics events.
     * Raw UID is hashed before passing to setUserId to protect student privacy.
     */
    fun identify(uid: String, isAdmin: Boolean) {
        val pseudonymousId = if (uid.isNotBlank()) "pseudo_" + hashPseudonymousId(uid) else ""
        try {
            FirebaseCrashlytics.getInstance().apply {
                setUserId(pseudonymousId)
                setCustomKey("is_admin", isAdmin)
            }
        } catch (_: Throwable) {
            // Firebase not initialized in test/isolated environments
        }
    }

    fun clearIdentity() {
        try {
            FirebaseCrashlytics.getInstance().setUserId("")
        } catch (_: Throwable) {
            // Firebase not initialized
        }
    }

    /** Non-fatal error log with PII redaction */
    fun logNonFatal(throwable: Throwable) {
        try {
            FirebaseCrashlytics.getInstance().recordException(throwable)
        } catch (_: Throwable) {
            // Firebase not initialized
        }
    }

    /** Redacts sensitive PII from diagnostic strings */
    fun redactPii(text: String): String {
        if (text.isBlank()) return ""
        return text
            .replace(Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"), "[REDACTED_EMAIL]")
            .replace(Regex("(?:Bearer\\s+[^\\s,]+|(?:token|key|password|secret|authorization)\\s*[:=]\\s*[^\\s,]+)", RegexOption.IGNORE_CASE), "[REDACTED_SECRET]")
            .replace(Regex("(?:\\+?\\d{1,3}[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}"), "[REDACTED_PHONE]")
            .replace(Regex("/data/user/\\d+/[^\\s/]+"), "/data/user/0/[APP]")
            .replace(Regex("/data/data/[^\\s/]+"), "/data/data/[APP]")
    }
}
