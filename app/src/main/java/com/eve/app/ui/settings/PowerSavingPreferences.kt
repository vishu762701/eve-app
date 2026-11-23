package com.eve.app.ui.settings

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Persists EVE Power Saving preferences (aligned with Telegram LiteMode / Power Saving settings)
 * and evaluates whether motion/animations should be reduced based on user threshold and battery level.
 */
object PowerSavingPreferences {

    private const val PREFS_NAME = "eve_power_saving_prefs"
    private const val KEY_THRESHOLD_INDEX = "threshold_index"
    private const val KEY_ANIMATED_EMOJIS = "animated_emojis"
    private const val KEY_SMOOTH_TRANSITIONS = "smooth_transitions"
    private const val KEY_PARTICLES = "particles"
    private const val KEY_AUTOPLAY_ANIMATIONS = "autoplay_animations"

    // 0 = Off, 1 = Below 10%, 2 = Below 25%, 3 = Below 50%, 4 = Always On
    val THRESHOLD_LABELS = arrayOf("Off", "10%", "25%", "50%", "On")
    val THRESHOLD_PERCENTS = intArrayOf(0, 10, 25, 50, 100)

    fun getThresholdIndex(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_THRESHOLD_INDEX, 0)
            .coerceIn(0, THRESHOLD_PERCENTS.lastIndex)

    fun setThresholdIndex(context: Context, index: Int) {
        val clamped = index.coerceIn(0, THRESHOLD_PERCENTS.lastIndex)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THRESHOLD_INDEX, clamped)
            .apply()
    }

    fun formatThresholdStatus(index: Int): String = when (index.coerceIn(0, THRESHOLD_PERCENTS.lastIndex)) {
        0 -> "Off"
        4 -> "Always On"
        else -> "When below ${THRESHOLD_PERCENTS[index]}%"
    }

    fun isAnimatedEmojisEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ANIMATED_EMOJIS, true)

    fun setAnimatedEmojisEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ANIMATED_EMOJIS, enabled).apply()
    }

    fun isSmoothTransitionsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SMOOTH_TRANSITIONS, true)

    fun setSmoothTransitionsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SMOOTH_TRANSITIONS, enabled).apply()
    }

    fun isParticlesEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PARTICLES, true)

    fun setParticlesEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PARTICLES, enabled).apply()
    }

    fun isAutoplayAnimationsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOPLAY_ANIMATIONS, true)

    fun setAutoplayAnimationsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOPLAY_ANIMATIONS, enabled).apply()
    }

    /** Pure evaluation helper for unit testing and runtime gating. */
    fun shouldReduceMotion(thresholdIndex: Int, batteryPercent: Int?, smoothTransitionsEnabled: Boolean): Boolean {
        if (!smoothTransitionsEnabled) return true
        val clamped = thresholdIndex.coerceIn(0, THRESHOLD_PERCENTS.lastIndex)
        val threshold = THRESHOLD_PERCENTS[clamped]
        if (threshold <= 0) return false
        if (threshold >= 100) return true
        return batteryPercent != null && batteryPercent in 0..threshold
    }

    fun isPowerSavingActive(context: Context): Boolean {
        val thresholdIndex = getThresholdIndex(context)
        val smoothTransitions = isSmoothTransitionsEnabled(context)
        if (!smoothTransitions) return true
        val threshold = THRESHOLD_PERCENTS[thresholdIndex]
        if (threshold <= 0) return false
        if (threshold >= 100) return true
        val batteryPct = readBatteryPercent(context)
        return shouldReduceMotion(thresholdIndex, batteryPct, smoothTransitions)
    }

    private fun readBatteryPercent(context: Context): Int? {
        return try {
            val intent = context.applicationContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else null
        } catch (_: Exception) {
            null
        }
    }
}
