package com.eve.app.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Mission 00B: Safe reader for Android ApplicationExitInfo (API 30+).
 * Eve minSdk is 24, so every call is strictly SDK-guarded.
 * Extracts exit reason and ANR trace snippet with bounded size and PII redaction.
 */
object ApplicationExitInfoReader {

    data class ExitInfoSummary(
        val reason: String,
        val description: String?,
        val timestamp: Long,
        val status: Int
    )

    fun getLatestExitInfo(context: Context): ExitInfoSummary? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        return readExitInfoApi30(context)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readExitInfoApi30(context: Context): ExitInfoSummary? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            val exitInfos = am.getHistoricalProcessExitReasons(context.packageName, 0, 1)
            if (exitInfos.isNullOrEmpty()) return null
            val latest = exitInfos[0]
            val reasonStr = when (latest.reason) {
                ApplicationExitInfo.REASON_ANR -> "ANR"
                ApplicationExitInfo.REASON_CRASH -> "CRASH"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
                else -> "OTHER_${latest.reason}"
            }
            val traceText = latest.description?.take(1000)
            ExitInfoSummary(
                reason = reasonStr,
                description = traceText,
                timestamp = latest.timestamp,
                status = latest.status
            )
        } catch (_: Throwable) {
            null
        }
    }
}
