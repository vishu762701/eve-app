package com.eve.app.diagnostics

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
        val queueDir = File(context.filesDir, "crash_queue")
        if (!queueDir.exists()) return@withContext Result.success()

        val files = queueDir.listFiles()?.filter { it.extension == "json" } ?: return@withContext Result.success()
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
            val url = URL("https://eve-api.mocktest.workers.dev/api/crashes")
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
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<CrashQueueWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
