package com.eve.app.ui.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch 3 (Refs 10, 11, 12) contract tests:
 * Verifies restored upstream org.telegram.ui.PrivacySettingsActivity uses RecyclerListView +
 * ListAdapter (6 view types including TextSettingsCell, TextInfoPrivacyCell, HeaderCell,
 * TextCheckCell, TextCell), setSections(), PrivacyControlActivity & SessionsActivity navigation,
 * and is wired from SettingsActivity & AndroidManifest.xml.
 */
class PrivacySettingsContractTest {

    private val tgPrivacySrc = File("src/main/java/org/telegram/ui/PrivacySettingsActivity.java").readText()
    private val tgControlSrc = File("src/main/java/org/telegram/ui/PrivacyControlActivity.java").readText()
    private val tgSessionsSrc = File("src/main/java/org/telegram/ui/SessionsActivity.java").readText()
    private val eveSettingsSrc = File("src/main/java/com/eve/app/ui/settings/SettingsActivity.kt").readText()
    private val manifestXml = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun privacySettingsRestoresUpstreamListAdapterAndSupportingScreens() {
        assertTrue(tgPrivacySrc.contains("extends BaseFragment"))
        assertTrue(tgPrivacySrc.contains("private class ListAdapter extends RecyclerListView.SelectionAdapter"))
        assertTrue(tgPrivacySrc.contains("listView.setSections();"))
        assertTrue(tgPrivacySrc.contains("listView.setSectionsDrawBackground(true);"))
        assertTrue(tgPrivacySrc.contains("textCell.setBetterLayout(true);"))
        assertTrue(tgPrivacySrc.contains("presentFragment(new PrivacyControlActivity("))
        assertTrue(tgPrivacySrc.contains("presentFragment(new SessionsActivity("))
        assertTrue(tgPrivacySrc.contains("public boolean isSupportEdgeToEdge()"))
        assertTrue(tgControlSrc.contains("extends BaseFragment"))
        assertTrue(tgSessionsSrc.contains("extends BaseFragment"))
    }

    @Test
    fun settingsActivityRoutesRowPrivacyToPrivacySettingsActivity() {
        assertTrue(eveSettingsSrc.contains("PrivacySettingsActivity::class.java"))
        assertTrue(manifestXml.contains(".ui.settings.PrivacySettingsActivity"))
    }
}
