package com.eve.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSavingPreferencesTest {

    @Test
    fun shouldReduceMotionRespectsThresholdAndBatteryPercent() {
        // Off (index 0) never reduces motion when smooth transitions enabled
        assertFalse(PowerSavingPreferences.shouldReduceMotion(0, 5, true))
        // Always On (index 4) always reduces motion
        assertTrue(PowerSavingPreferences.shouldReduceMotion(4, 100, true))
        // Disabling smooth transitions always reduces motion
        assertTrue(PowerSavingPreferences.shouldReduceMotion(0, 95, false))
        // Below 10% (index 1)
        assertTrue(PowerSavingPreferences.shouldReduceMotion(1, 10, true))
        assertTrue(PowerSavingPreferences.shouldReduceMotion(1, 4, true))
        assertFalse(PowerSavingPreferences.shouldReduceMotion(1, 11, true))
        assertFalse(PowerSavingPreferences.shouldReduceMotion(1, null, true))
        // Below 25% (index 2)
        assertTrue(PowerSavingPreferences.shouldReduceMotion(2, 25, true))
        assertFalse(PowerSavingPreferences.shouldReduceMotion(2, 50, true))
        // Below 50% (index 3)
        assertTrue(PowerSavingPreferences.shouldReduceMotion(3, 49, true))
        assertFalse(PowerSavingPreferences.shouldReduceMotion(3, 51, true))
    }

    @Test
    fun formatThresholdStatusProducesReadableLabels() {
        assertEquals("Off", PowerSavingPreferences.formatThresholdStatus(0))
        assertEquals("When below 10%", PowerSavingPreferences.formatThresholdStatus(1))
        assertEquals("When below 25%", PowerSavingPreferences.formatThresholdStatus(2))
        assertEquals("When below 50%", PowerSavingPreferences.formatThresholdStatus(3))
        assertEquals("Always On", PowerSavingPreferences.formatThresholdStatus(4))
    }
}
