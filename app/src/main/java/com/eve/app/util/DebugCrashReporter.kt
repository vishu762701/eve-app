package com.eve.app.util

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.eve.app.BuildConfig
import com.eve.app.designsystem.TelegramNativeAlertDialog
import com.eve.app.diagnostics.CrashQueueWorker
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Mission 00B: Uncaught exception reporter and diagnostic bridge.
 * Saves crash stack traces privately and transfers structured, sanitized records to the CrashQueueWorker.
 * In debug builds, displays a copyable dialog on subsequent app launch without losing the trace on dismissal.
 */
object DebugCrashReporter {

    private const val CRASH_FILE_NAME = "debug_last_crash_trace.txt"
    private var hasCheckedCrash = false

    fun init(application: Application) {
        // 1. If a crash trace exists from a previous launch, ensure it has been queued
        try {
            val pending = getCrashTrace(application)
            if (!pending.isNullOrBlank()) {
                val lines = pending.lines()
                val exceptionLine = lines.firstOrNull { it.contains("Exception") || it.contains("Error") } ?: "UncaughtException"
                val topFrame = lines.firstOrNull { it.trim().startsWith("at ") } ?: exceptionLine
                CrashQueueWorker.persistCrash(
                    context = application,
                    exceptionClass = exceptionLine.trim(),
                    topFrame = topFrame.trim(),
                    message = pending.take(1000),
                    stackFrames = lines.filter { it.trim().startsWith("at ") }
                )
            }
        } catch (_: Throwable) {}

        // 2. Install global uncaught exception handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                pw.flush()
                val stackTrace = sw.toString()
                val crashPayload = "Time: ${System.currentTimeMillis()}\nThread: ${thread.name}\n\n$stackTrace"

                Log.e("CRITICAL_STARTUP", "Unhandled exception captured (${throwable.javaClass.simpleName})")

                if (BuildConfig.DEBUG) {
                    saveCrashPayload(application, crashPayload)
                }

                // Mission 00B: Persist to bounded diagnostic queue atomically
                CrashQueueWorker.persistFromThrowable(application, throwable)
            } catch (t: Throwable) {
                t.printStackTrace()
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        if (!BuildConfig.DEBUG) return

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (!hasCheckedCrash) {
                    checkAndShowDialog(activity)
                }
            }

            override fun onActivityResumed(activity: Activity) {
                if (!hasCheckedCrash) {
                    checkAndShowDialog(activity)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun saveCrashPayload(context: Context, crashPayload: String) {
        try {
            val crashFile = File(context.filesDir, CRASH_FILE_NAME)
            crashFile.writeText(crashPayload)
        } catch (_: Throwable) {}
    }

    fun hasCrash(context: Context): Boolean {
        val crashFile = File(context.filesDir, CRASH_FILE_NAME)
        return crashFile.exists() && crashFile.length() > 0
    }

    fun getCrashTrace(context: Context): String? {
        val crashFile = File(context.filesDir, CRASH_FILE_NAME)
        if (!crashFile.exists()) return null
        return try {
            crashFile.readText()
        } catch (_: Throwable) {
            null
        }
    }

    fun clearCrash(context: Context) {
        try {
            File(context.filesDir, CRASH_FILE_NAME).delete()
        } catch (_: Throwable) {}
    }

    fun showCrashDialog(activity: Activity, onDismiss: () -> Unit = {}) {
        if (!BuildConfig.DEBUG || activity.isFinishing || activity.isDestroyed) {
            onDismiss()
            return
        }

        val crashContent = getCrashTrace(activity)
        if (crashContent.isNullOrBlank()) {
            onDismiss()
            return
        }

        hasCheckedCrash = true

        val textView = TextView(activity).apply {
            text = crashContent
            setPadding(40, 20, 40, 20)
            setTextIsSelectable(true)
            com.eve.app.designsystem.DesignType.apply(this, com.eve.app.designsystem.DesignType.Role.SECONDARY)
        }
        val scrollView = ScrollView(activity).apply {
            addView(textView)
        }

        val dialog = TelegramNativeAlertDialog.Builder(activity)
            .setTitle("Crash Detected on Previous Launch")
            .setView(scrollView)
            .setPositiveButton("Copy Trace", null)
            .setNegativeButton("Dismiss") { d, _ ->
                clearCrash(activity)
                d.dismiss()
            }
            .setOnDismissListener {
                clearCrash(activity)
                onDismiss()
            }
            .create()

        dialog.setOnShowListener {
            dialog.getActionButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("Crash Trace", crashContent)
                clipboard?.setPrimaryClip(clip)
                AppBulletin.show(activity, "Crash trace copied to clipboard")
                clearCrash(activity)
            }
        }

        dialog.show()
    }

    private fun checkAndShowDialog(activity: Activity) {
        if (hasCrash(activity)) {
            showCrashDialog(activity)
        }
    }
}
