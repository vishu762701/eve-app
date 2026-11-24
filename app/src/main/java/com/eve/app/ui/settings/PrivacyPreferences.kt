package com.eve.app.ui.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent user-scoped privacy preferences for EVE Privacy and Security settings.
 */
object PrivacyPreferences {

    const val VISIBILITY_EVERYBODY = "Everybody"
    const val VISIBILITY_ONLY_ME = "Only Me"

    private const val PREFS_NAME = "eve_privacy_prefs"
    private const val KEY_LEADERBOARD = "leaderboard_visibility"
    private const val KEY_PROFILE = "profile_visibility"
    private const val KEY_ACTIVITY = "activity_visibility"
    private const val KEY_SCREENSHOT_PROTECTION = "screenshot_protection"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun scopedKey(uid: String?, key: String): String =
        if (uid.isNullOrBlank()) key else "${uid}_$key"

    fun getLeaderboardVisibility(context: Context, uid: String?): String =
        prefs(context).getString(scopedKey(uid, KEY_LEADERBOARD), VISIBILITY_EVERYBODY) ?: VISIBILITY_EVERYBODY

    fun setLeaderboardVisibility(context: Context, uid: String?, value: String) {
        prefs(context).edit().putString(scopedKey(uid, KEY_LEADERBOARD), value).apply()
    }

    fun getProfileVisibility(context: Context, uid: String?): String =
        prefs(context).getString(scopedKey(uid, KEY_PROFILE), VISIBILITY_EVERYBODY) ?: VISIBILITY_EVERYBODY

    fun setProfileVisibility(context: Context, uid: String?, value: String) {
        prefs(context).edit().putString(scopedKey(uid, KEY_PROFILE), value).apply()
    }

    fun getActivityVisibility(context: Context, uid: String?): String =
        prefs(context).getString(scopedKey(uid, KEY_ACTIVITY), VISIBILITY_EVERYBODY) ?: VISIBILITY_EVERYBODY

    fun setActivityVisibility(context: Context, uid: String?, value: String) {
        prefs(context).edit().putString(scopedKey(uid, KEY_ACTIVITY), value).apply()
    }

    fun isScreenshotProtectionEnabled(context: Context, uid: String?): Boolean =
        prefs(context).getBoolean(scopedKey(uid, KEY_SCREENSHOT_PROTECTION), true)

    fun setScreenshotProtectionEnabled(context: Context, uid: String?, enabled: Boolean) {
        prefs(context).edit().putBoolean(scopedKey(uid, KEY_SCREENSHOT_PROTECTION), enabled).apply()
    }
}
