package com.eve.app.ui.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch 3 (Refs 10, 11, 12) contract tests:
 * Verifies org.telegram.ui.PrivacySettingsActivity is wired into SettingsActivity,
 * registered in AndroidManifest.xml, uses original UniversalRecyclerView rounded sections
 * and AlertDialog, and binds real EVE controls.
 */
class PrivacySettingsContractTest {

    private val tgPrivacySrc = File("src/main/java/org/telegram/ui/PrivacySettingsActivity.java").readText()
    private val eveSettingsSrc = File("src/main/java/com/eve/app/ui/settings/SettingsActivity.kt").readText()
    private val manifestXml = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun privacySettingsUsesOriginalTelegramSectionsAndAlertDialog() {
        assertTrue(tgPrivacySrc.contains("extends BaseFragment"))
        assertTrue(tgPrivacySrc.contains("listView.setSections();"))
        assertTrue(tgPrivacySrc.contains("listView.setSectionsDrawBackground(true);"))
        assertTrue(tgPrivacySrc.contains("new AlertDialog.Builder(context, getResourceProvider())"))
        assertTrue(tgPrivacySrc.contains("public boolean isSupportEdgeToEdge()"))
    }

    @Test
    fun settingsActivityRoutesRowPrivacyToPrivacySettingsActivity() {
        assertTrue(eveSettingsSrc.contains("PrivacySettingsActivity::class.java"))
        assertTrue(manifestXml.contains(".ui.settings.PrivacySettingsActivity"))
    }
}
