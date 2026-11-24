package com.eve.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Batch 1A (Ref 06 — 06_language_settings.jpg) contract verification:
 * - Honest EVE-supported locales only (System default, English, Hindi) matching locales_config.xml & values-hi
 * - Original Telegram LanguageSelectActivity preserved with restored edge-to-edge, gray canvas, 400ms animator,
 *   post-selection search collapse/refresh, and case-insensitive search filtering
 */
class LanguageSelectContractTest {

    private fun repoFile(relative: String): File {
        val direct = File(relative)
        if (direct.exists()) return direct
        val fromApp = File("..", relative)
        if (fromApp.exists()) return fromApp
        throw IllegalStateException("Could not locate $relative from ${File(".").absolutePath}")
    }

    @Test
    fun localesConfigAndHindiResourcesMatchHonestEveLanguageList() {
        val localesConfig = repoFile("app/src/main/res/xml/locales_config.xml").readText()
        assertTrue(localesConfig.contains("android:name=\"en\""))
        assertTrue(localesConfig.contains("android:name=\"hi\""))
        assertTrue(repoFile("app/src/main/res/values-hi/strings.xml").exists())

        val hostSource = repoFile("app/src/main/java/com/eve/app/ui/settings/LanguageSelectActivity.kt").readText()
        assertTrue("Host must extend TelegramHostActivity", hostSource.contains("TelegramHostActivity<TelegramLanguageSelectActivity>"))
        assertTrue("System default option must be present", hostSource.contains("make(\"\", \"System default\""))
        assertTrue("English option must be present", hostSource.contains("make(\"en\", \"English\""))
        assertTrue("Hindi option must be present", hostSource.contains("make(\"hi\", \"हिन्दी\""))
        val makeCount = Regex("\\bmake\\(").findAll(hostSource).count()
        // 1 function definition + 3 real EVE language entries = 4
        assertEquals("Only System default, English, and Hindi may be listed (no fake languages)", 4, makeCount)
    }

    @Test
    fun telegramLanguageSelectActivityPreservesRestoredUpstreamBehaviors() {
        val javaSource = repoFile("app/src/main/java/org/telegram/ui/LanguageSelectActivity.java").readText()
        assertTrue("Must set windowBackgroundGray behind rounded card", javaSource.contains("Theme.key_windowBackgroundGray"))
        assertTrue("Must preserve 400ms animator timing", javaSource.contains("setMoveDuration(400)"))
        assertTrue("Must close search field after selection", javaSource.contains("actionBar.closeSearchField()"))
        assertTrue("Must refresh search adapter on selection", javaSource.contains("searchListViewAdapter.notifyDataSetChanged()"))
        assertTrue("Must restore isSupportEdgeToEdge()", javaSource.contains("public boolean isSupportEdgeToEdge()"))
        assertTrue("Must use trimmed lowercase q in processSearch", javaSource.contains("startsWith(q)"))
        assertFalse("Must not use raw case-sensitive query in processSearch", javaSource.contains("startsWith(query)"))
    }
}
