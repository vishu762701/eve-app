package com.eve.app

import kotlinx.coroutines.launch
import android.app.Application

class EveApplication : Application() {

    companion object {
        lateinit var instance: EveApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize TelegramThemeEngine as single authoritative theme engine before any Activity is created
        org.telegram.messenger.ApplicationLoader.applicationContext = this
        org.telegram.messenger.AndroidUtilities.checkDisplaySize(this, resources.configuration)
        com.eve.app.theme.TelegramThemeEngine.init(this)
        org.telegram.ui.ActionBar.Theme.syncFromEvePalette(this)
        com.eve.app.util.DebugCrashReporter.init(this)

        // Mission 00B: Check ApplicationExitInfo on API 30+ for ANR or native crashes from previous process
        try {
            val exitInfo = com.eve.app.diagnostics.ApplicationExitInfoReader.getLatestExitInfo(this)
            if (exitInfo != null && (exitInfo.reason == "CRASH" || exitInfo.reason == "CRASH_NATIVE" || exitInfo.reason == "ANR")) {
                val prefs = getSharedPreferences("eve_diagnostics", android.content.Context.MODE_PRIVATE)
                val lastReportedTimestamp = prefs.getLong("last_exit_info_timestamp", 0L)
                if (exitInfo.timestamp > lastReportedTimestamp) {
                    com.eve.app.diagnostics.CrashQueueWorker.persistFromExitInfo(this, exitInfo)
                    prefs.edit().putLong("last_exit_info_timestamp", exitInfo.timestamp).apply()
                }
            }
        } catch (_: Throwable) {}

        com.eve.app.util.ThemeSwitchAnimator.ensureLifecycleRegistered(this)
        com.eve.app.util.SystemBarHelper.init(this)
        com.eve.app.util.ProfilePresenceClient.register()
        try {
            val typoRepo = com.eve.app.typography.TypographyRepository.getInstance(this)
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                typoRepo.fetchPublishedConfig()
            }
        } catch (_: Throwable) {}
        try {
            com.google.firebase.auth.FirebaseAuth.getInstance().addAuthStateListener { auth ->
                com.eve.app.util.ProfilePresenceClient.onAuthChanged(auth.currentUser != null)
                if (auth.currentUser != null) kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    com.eve.app.worker.SubmitWorker.enqueueAllPending(this@EveApplication)
                    com.eve.app.diagnostics.CrashQueueWorker.enqueue(this@EveApplication)
                }
            }
        } catch (_: Throwable) {}
    }
}
