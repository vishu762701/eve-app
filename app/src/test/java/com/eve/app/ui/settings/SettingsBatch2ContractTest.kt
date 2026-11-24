package com.eve.app.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch 2 (Refs 04, 19, 20) contract tests:
 * Verifies org.telegram.ui.SettingsActivity preserves upstream rounded sections,
 * avatar camera badge, subtitle Language row, native ActionBar inline search,
 * honest EVE rows without duplicate Telegram-only shims, and original Telegram AlertDialog
 * for Ask a Question (Ref 04).
 */
class SettingsBatch2ContractTest {

    private val tgSettingsSrc = File("src/main/java/org/telegram/ui/SettingsActivity.java").readText()
    private val eveSettingsSrc = File("src/main/java/com/eve/app/ui/settings/SettingsActivity.kt").readText()

    @Test
    fun settingsActivityUsesOriginalSectionsCameraBadgeAndEdgeToEdge() {
        assertTrue("SettingsActivity must call listView.setSections()", tgSettingsSrc.contains("listView.setSections();"))
        assertTrue("SettingsActivity must call listView.setSectionsDrawBackground(true)", tgSettingsSrc.contains("listView.setSectionsDrawBackground(true);"))
        assertTrue("SettingsActivity must render avatar camera badge", tgSettingsSrc.contains("cameraButton = new FrameLayout(context)"))
        assertTrue("SettingsActivity must support edge-to-edge", tgSettingsSrc.contains("public boolean isSupportEdgeToEdge()"))
    }

    @Test
    fun languageRowUsesSubtitleAndFakeDuplicateRowsAreOmitted() {
        assertTrue(
            "Language row must pass LocaleController.getCurrentLanguageName() as subtitle (Ref 19)",
            tgSettingsSrc.contains("R.string.SettingsLanguage), LocaleController.getCurrentLanguageName())")
        )
        assertFalse(
            "Duplicate Telegram-only ROW_FOLDERS must not appear in fillItems",
            tgSettingsSrc.contains("SettingCell.Factory.of(ROW_FOLDERS,")
        )
        assertFalse(
            "Duplicate Telegram-only ROW_DEVICES must not appear in fillItems",
            tgSettingsSrc.contains("SettingCell.Factory.of(ROW_DEVICES,")
        )
    }

    @Test
    fun askAQuestionUsesOriginalTelegramAlertDialogWithClickableFaqLink() {
        assertTrue("SettingsActivity must use org.telegram.ui.ActionBar.AlertDialog", tgSettingsSrc.contains("import org.telegram.ui.ActionBar.AlertDialog;"))
        assertTrue("showAskQuestionDialog must enable clickable FAQ links", tgSettingsSrc.contains("builder.setMessageTextViewClickable(true);"))
        assertTrue("showAskQuestionDialog must show via BaseFragment.showDialog", tgSettingsSrc.contains("showDialog(dialog);"))
        assertTrue("EVE SettingsActivity host must delegate Ask a Question to Telegram fragment", eveSettingsSrc.contains("fragment.showAskQuestionDialog("))
    }
}
