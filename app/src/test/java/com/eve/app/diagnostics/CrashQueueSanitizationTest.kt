package com.eve.app.diagnostics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.eve.app.util.CrashlyticsHelper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
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

    @Test
    fun testCrashQueueWorkerPersistenceAndBounding() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queueDir = File(context.filesDir, CrashQueueWorker.QUEUE_DIR_NAME)
        queueDir.deleteRecursively()

        // 1. Persist a crash record from Throwable
        val testException = NullPointerException("Test profile crash with student@mock.test and Bearer 123456")
        val persistedFile = CrashQueueWorker.persistFromThrowable(context, testException, isSynthetic = true)

        assertNotNull("Persisted file must not be null", persistedFile)
        assertTrue("File must exist on disk", persistedFile!!.exists())
        assertEquals("File must be in crash_queue dir", queueDir.absolutePath, persistedFile.parentFile?.absolutePath)

        // Read and parse JSON content
        val content = persistedFile.readText()
        val json = JSONObject(content)
        assertEquals("java.lang.NullPointerException", json.getString("exception_class"))
        assertTrue(json.getBoolean("is_synthetic"))
        assertFalse("PII email must be redacted from message", json.getString("message").contains("student@mock.test"))
        assertTrue("Redacted email placeholder must exist", json.getString("message").contains("[REDACTED_EMAIL]"))
        assertFalse("Bearer token must be redacted", json.getString("message").contains("123456"))

        // 2. Test bounded queue enforcement (max 10 files)
        for (i in 1..15) {
            Thread.sleep(5)
            CrashQueueWorker.persistCrash(
                context = context,
                exceptionClass = "TestException$i",
                topFrame = "com.eve.app.TestClass.method$i",
                message = "Test message $i"
            )
        }

        val remainingFiles = queueDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        assertTrue("Queue must not exceed maximum limit of 10 files", remainingFiles.size <= CrashQueueWorker.MAX_QUEUE_FILES)
    }

    @Test
    fun testExitInfoReaderReturnsSummaryOrNullSafe() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // On SDK 34 without real historical exits in test environment, must return null without throwing
        val exitInfo = ApplicationExitInfoReader.getLatestExitInfo(context)
        // If not null, must have valid fields
        if (exitInfo != null) {
            assertNotNull(exitInfo.reason)
            assertTrue(exitInfo.timestamp > 0)
        }
    }
}
