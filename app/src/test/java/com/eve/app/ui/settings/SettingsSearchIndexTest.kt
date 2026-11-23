package com.eve.app.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchIndexTest {

    @Test
    fun filterMatchesTitlesSubtitlesAndKeywords() {
        assertTrue(SettingsSearchIndex.filter("").size == SettingsSearchIndex.allEntries.size)
        assertTrue(SettingsSearchIndex.filter("power").any { it.title == "Power Saving" })
        assertTrue(SettingsSearchIndex.filter("passcode").any { it.title == "Privacy & Security" })
        assertTrue(SettingsSearchIndex.filter("hindi").any { it.title == "Language" })
        assertTrue(SettingsSearchIndex.filter("nonexistent-xyz").isEmpty())
    }

    @Test
    fun settingsActivityRoutesPowerSavingAndImplementsSearchAndAskDialog() {
        val src = File("src/main/java/com/eve/app/ui/settings/SettingsActivity.kt").readText()
        assertTrue(
            "SettingsActivity must route ROW_POWER to PowerSavingActivity",
            src.contains("PowerSavingActivity::class.java")
        )
        assertTrue(
            "SettingsActivity must implement live search in onSearchClicked()",
            src.contains("SettingsSearchIndex.filter(")
        )
        assertTrue(
            "SettingsActivity must show Ask a Question dialog before opening support",
            src.contains("showAskQuestionDialog()")
        )
    }
}
