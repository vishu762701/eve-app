package com.eve.app.ui.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent user-scoped privacy & security preferences for EVE PrivacySettingsActivity
 * and PrivacyControlActivity.
 */
object PrivacyPreferences {

    const val VISIBILITY_EVERYBODY = "Everybody"
    const val VISIBILITY_ONLY_ME = "Only Me"

    const val RULE_PHONE = 1
    const val RULE_ACTIVITY = 2
    const val RULE_PROFILE_PHOTO = 3
    const val RULE_BIRTHDAY = 4
    const val RULE_BIO = 5
    const val RULE_LEADERBOARD = 6

    private const val PREFS_NAME = "eve_privacy_prefs"
    private const val KEY_PHONE = "phone_visibility"
    private const val KEY_ACTIVITY = "activity_visibility"
    private const val KEY_PROFILE = "profile_visibility"
    private const val KEY_BIRTHDAY = "birthday_visibility"
    private const val KEY_BIO = "bio_visibility"
    private const val KEY_LEADERBOARD = "leaderboard_visibility"
    private const val KEY_AUTO_SYNC = "auto_sync_progress"
    private const val KEY_SUGGEST_RECENT = "suggest_recent_exams"
    private const val KEY_SCREENSHOT_PROTECTION = "screenshot_protection"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun scopedKey(uid: String?, key: String): String =
        if (uid.isNullOrBlank()) key else "${uid}_$key"

    private fun keyForRule(ruleType: Int): String = when (ruleType) {
        RULE_PHONE -> KEY_PHONE
        RULE_ACTIVITY -> KEY_ACTIVITY
        RULE_PROFILE_PHOTO -> KEY_PROFILE
        RULE_BIRTHDAY -> KEY_BIRTHDAY
        RULE_BIO -> KEY_BIO
        RULE_LEADERBOARD -> KEY_LEADERBOARD
        else -> KEY_LEADERBOARD
    }

    private fun defaultForRule(ruleType: Int): String = when (ruleType) {
        RULE_PHONE -> VISIBILITY_ONLY_ME
        else -> VISIBILITY_EVERYBODY
    }

    fun getRuleVisibility(context: Context, uid: String?, ruleType: Int): String =
        prefs(context).getString(scopedKey(uid, keyForRule(ruleType)), defaultForRule(ruleType))
            ?: defaultForRule(ruleType)

    fun setRuleVisibility(context: Context, uid: String?, ruleType: Int, value: String) {
        prefs(context).edit().putString(scopedKey(uid, keyForRule(ruleType)), value).apply()
    }

    fun getLeaderboardVisibility(context: Context, uid: String?): String =
        getRuleVisibility(context, uid, RULE_LEADERBOARD)

    fun setLeaderboardVisibility(context: Context, uid: String?, value: String) =
        setRuleVisibility(context, uid, RULE_LEADERBOARD, value)

    fun getProfileVisibility(context: Context, uid: String?): String =
        getRuleVisibility(context, uid, RULE_PROFILE_PHOTO)

    fun setProfileVisibility(context: Context, uid: String?, value: String) =
        setRuleVisibility(context, uid, RULE_PROFILE_PHOTO, value)

    fun getActivityVisibility(context: Context, uid: String?): String =
        getRuleVisibility(context, uid, RULE_ACTIVITY)

    fun setActivityVisibility(context: Context, uid: String?, value: String) =
        setRuleVisibility(context, uid, RULE_ACTIVITY, value)

    fun isAutoSyncProgressEnabled(context: Context, uid: String?): Boolean =
        prefs(context).getBoolean(scopedKey(uid, KEY_AUTO_SYNC), true)

    fun setAutoSyncProgressEnabled(context: Context, uid: String?, enabled: Boolean) {
        prefs(context).edit().putBoolean(scopedKey(uid, KEY_AUTO_SYNC), enabled).apply()
    }

    fun isSuggestRecentExamsEnabled(context: Context, uid: String?): Boolean =
        prefs(context).getBoolean(scopedKey(uid, KEY_SUGGEST_RECENT), true)

    fun setSuggestRecentExamsEnabled(context: Context, uid: String?, enabled: Boolean) {
        prefs(context).edit().putBoolean(scopedKey(uid, KEY_SUGGEST_RECENT), enabled).apply()
    }

    fun isScreenshotProtectionEnabled(context: Context, uid: String?): Boolean =
        prefs(context).getBoolean(scopedKey(uid, KEY_SCREENSHOT_PROTECTION), true)

    fun setScreenshotProtectionEnabled(context: Context, uid: String?, enabled: Boolean) {
        prefs(context).edit().putBoolean(scopedKey(uid, KEY_SCREENSHOT_PROTECTION), enabled).apply()
    }
}
