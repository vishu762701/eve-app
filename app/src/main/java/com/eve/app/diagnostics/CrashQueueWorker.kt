package com.eve.app.diagnostics

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eve.app.BuildConfig
import com.eve.app.util.CrashlyticsHelper
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Mission 00B: Background WorkManager worker for uploading queued diagnostic records to the Worker.
 * Bounded queue: max 10 files, atomic disk persistence, backoff retry, clears local trace only
 * after successful server response.
 */
class CrashQueueWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val queueDir = File(context.filesDir, QUEUE_DIR_NAME)
        if (!queueDir.exists()) return@withContext Result.success()

        val files = queueDir.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.lastModified() }
            ?: return@withContext Result.success()
        if (files.isEmpty()) return@withContext Result.success()

        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser ?: return@withContext Result.success()
        val tokenResult = try {
            user.getIdToken(false).await()
        } catch (_: Exception) {
            return@withContext Result.retry()
        }
        val idToken = tokenResult.token ?: return@withContext Result.retry()

        for (file in files.take(5)) {
            val content = try {
                file.readText()
            } catch (_: Exception) {
                file.delete()
                continue
            }

            val success = uploadCrashReport(content, idToken)
            if (success) {
                file.delete()
            } else {
                return@withContext Result.retry()
            }
        }

        Result.success()
    }

    private fun uploadCrashReport(jsonPayload: String, idToken: String): Boolean {
        return try {
            val baseUrl = com.eve.app.util.Constants.WORKER_BASE_URL.trimEnd('/')
            val url = URL("$baseUrl/api/crashes")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Authorization", "Bearer $idToken")
            }

            conn.outputStream.use { os ->
                os.write(jsonPayload.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val QUEUE_DIR_NAME = "crash_queue"
        const val MAX_QUEUE_FILES = 10

        fun enqueue(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = OneTimeWorkRequestBuilder<CrashQueueWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()

                WorkManager.getInstance(context).enqueue(request)
            } catch (_: Throwable) {
                // Non-Android / unit test environment fallback
            }
        }

        fun persistCrash(
            context: Context,
            exceptionClass: String,
            topFrame: String,
            message: String = "",
            stackFrames: List<String> = emptyList(),
            anrOrExitReason: String? = null,
            isSynthetic: Boolean = false
        ): File? {
            return try {
                val queueDir = File(context.filesDir, QUEUE_DIR_NAME).apply { mkdirs() }

                // Bounded queue: delete oldest records so total files do not exceed MAX_QUEUE_FILES
                val existing = queueDir.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.lastModified() } ?: emptyList()
                if (existing.size >= MAX_QUEUE_FILES) {
                    val toDelete = existing.take(existing.size - MAX_QUEUE_FILES + 1)
                    toDelete.forEach { it.delete() }
                }

                val json = JSONObject().apply {
                    put("exception_class", exceptionClass.take(200))
                    put("top_frame", topFrame.take(500))
                    put("message", CrashlyticsHelper.redactPii(message).take(1000))
                    val framesArray = JSONArray()
                    stackFrames.take(15).forEach { frame ->
                        framesArray.put(CrashlyticsHelper.redactPii(frame).take(300))
                    }
                    put("stack_frames", framesArray)
                    put("app_version_name", BuildConfig.VERSION_NAME)
                    put("app_version_code", BuildConfig.VERSION_CODE)
                    put("android_sdk", Build.VERSION.SDK_INT)
                    put("device_model", Build.MODEL ?: "unknown")
                    put("anr_or_exit_reason", anrOrExitReason ?: "")
                    put("install_id_hash", getOrCreateInstallHash(context))
                    put("is_synthetic", isSynthetic)
                }

                val targetFile = File(queueDir, "${System.currentTimeMillis()}_${UUID.randomUUID()}.json")
                val tempFile = File(queueDir, "${targetFile.name}.tmp")
                tempFile.writeText(json.toString(), Charsets.UTF_8)
                if (tempFile.renameTo(targetFile)) {
                    enqueue(context)
                    targetFile
                } else {
                    tempFile.delete()
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }

        fun persistFromThrowable(
            context: Context,
            throwable: Throwable,
            isSynthetic: Boolean = false
        ): File? {
            val exceptionClass = throwable.javaClass.name
            val frames = throwable.stackTrace.map { it.toString() }
            val topFrame = frames.firstOrNull() ?: "unknown"
            return persistCrash(
                context = context,
                exceptionClass = exceptionClass,
                topFrame = topFrame,
                message = throwable.message ?: "",
                stackFrames = frames,
                anrOrExitReason = null,
                isSynthetic = isSynthetic
            )
        }

        fun persistFromExitInfo(
            context: Context,
            summary: ApplicationExitInfoReader.ExitInfoSummary
        ): File? {
            val topFrame = summary.description?.lines()?.firstOrNull() ?: summary.reason
            val frames = summary.description?.lines() ?: emptyList()
            return persistCrash(
                context = context,
                exceptionClass = "AndroidExitInfo:${summary.reason}",
                topFrame = topFrame,
                message = summary.description ?: "Exit status ${summary.status}",
                stackFrames = frames,
                anrOrExitReason = summary.reason,
                isSynthetic = false
            )
        }

        private fun getOrCreateInstallHash(context: Context): String {
            return try {
                val prefs = context.getSharedPreferences("eve_diagnostics", Context.MODE_PRIVATE)
                val id = prefs.getString("install_id", null) ?: UUID.randomUUID().toString().also {
                    prefs.edit().putString("install_id", it).apply()
                }
                val md = MessageDigest.getInstance("SHA-256")
                md.digest(id.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                    .take(32)
            } catch (_: Throwable) {
                "unknown_install"
            }
        }
    }
}
