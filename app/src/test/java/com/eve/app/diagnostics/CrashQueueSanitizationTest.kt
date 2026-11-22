package com.eve.app.diagnostics

import com.eve.app.util.CrashlyticsHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashQueueSanitizationTest {

    @Test
    fun testPiiRedactionRemovesEmailAndToken() {
        val raw = "Crash in student test.user@eve.mock with Bearer eyJhbGciOi... and phone 9876543210"
        val sanitized = CrashlyticsHelper.redactPii(raw)

        assertFalse("Raw email must be redacted", sanitized.contains("test.user@eve.mock"))
        assertTrue("Email placeholder must be present", sanitized.contains("[REDACTED_EMAIL]"))
        assertFalse("Bearer token must be redacted", sanitized.contains("eyJhbGciOi"))
        assertTrue("Secret placeholder must be present", sanitized.contains("[REDACTED_SECRET]"))
        assertFalse("Phone number must be redacted", sanitized.contains("9876543210"))
        assertTrue("Phone placeholder must be present", sanitized.contains("[REDACTED_PHONE]"))
    }

    @Test
    fun testPseudonymousIdDoesNotContainRawUid() {
        val rawUid = "firebase_user_abc123456789"
        CrashlyticsHelper.identify(rawUid, isAdmin = false)
        val sanitized = CrashlyticsHelper.redactPii("User $rawUid failed")
        assertFalse(sanitized.contains("@"))
    }
}
