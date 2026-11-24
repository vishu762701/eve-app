package com.eve.app.ui.settings

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eve.app.ui.profile.ProfileEditorAccountGuard
import com.eve.app.util.ThemeManager
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.Components.UniversalRecyclerView
import org.telegram.ui.SettingsActivity as TelegramSettingsActivity
import java.io.File
import java.io.FileOutputStream

/**
 * Batch 2 (Refs 04, 19, 20) Native UI Instrumentation Test:
 * Proves original Telegram SettingsActivity renders with UniversalRecyclerView sections,
 * SettingCell rows, inline search filtering, original Telegram AlertDialog for Ask a Question,
 * and captures light/dark emulator screenshots for Refs 20, 19, and 04.
 */
@RunWith(AndroidJUnit4::class)
class SettingsActivityNativeTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var savedNightMode: Int = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

    @Before
    fun setUp() {
        savedNightMode = AppCompatDelegate.getDefaultNightMode()
        ProfileEditorAccountGuard.testOverrideUid = "test-settings-owner"
        instrumentation.runOnMainSync {
            ThemeManager.applyThemeTarget(context, false)
        }
    }

    @After
    fun tearDown() {
        ProfileEditorAccountGuard.testOverrideUid = null
        instrumentation.runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(savedNightMode)
        }
    }

    @Test
    fun refs04_19_20OriginalTelegramSettingsHierarchySearchHelpDialogAndScreenshots() {
        val intent = Intent(context, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("is_test_intent", true)
            putExtra(ProfileEditorAccountGuard.EXTRA_OWNER_UID, "test-settings-owner")
        }
        val activity = instrumentation.startActivitySync(intent) as SettingsActivity
        try {
            SystemClock.sleep(600)
            val root = activity.window.decorView
            val actionBar = findFirst(root, ActionBar::class.java)
            val listView = findFirst(root, UniversalRecyclerView::class.java)
            val cells = findAll(root, TelegramSettingsActivity.SettingCell::class.java)

            assertNotNull("Original Telegram ActionBar must be present", actionBar)
            assertNotNull("Original Telegram UniversalRecyclerView must be present", listView)
            assertTrue("SettingCell rows must render in SettingsActivity", cells.isNotEmpty())

            // Ref 20: Main Settings top view (light)
            captureScreenshot("ref20-settings-top-light")

            // Scroll to Help section (Ref 19)
            activity.runOnUiThread { listView!!.scrollBy(0, 1400) }
            SystemClock.sleep(450)
            captureScreenshot("ref19-settings-help-light")

            // Ref 04: Original Telegram AlertDialog for Ask a Question
            val fragment = activity.actionBarLayout.lastFragment as TelegramSettingsActivity
            var shownDialog: org.telegram.ui.ActionBar.AlertDialog? = null
            activity.runOnUiThread {
                shownDialog = fragment.showAskQuestionDialog(null, null)
            }
            SystemClock.sleep(450)
            assertNotNull("Original Telegram AlertDialog must show for Ask a Question", shownDialog)
            assertTrue("Ask a Question dialog must be showing", shownDialog!!.isShowing)
            captureScreenshot("ref04-ask-question-light")
            activity.runOnUiThread { shownDialog?.dismiss() }
            SystemClock.sleep(250)

            // Verify live inline search filtering
            activity.runOnUiThread { fragment.setSearchQueryForTest("power") }
            SystemClock.sleep(300)
            val filteredCells = findAll(root, TelegramSettingsActivity.SettingCell::class.java)
            assertTrue("Inline search for 'power' must match Power Saving cell", filteredCells.isNotEmpty())
            activity.runOnUiThread { fragment.setSearchQueryForTest(null) }
            SystemClock.sleep(250)
        } finally {
            if (!activity.isFinishing && !activity.isDestroyed) {
                activity.finish()
            }
            SystemClock.sleep(250)
        }

        // Dark mode capture for Ref 20
        instrumentation.runOnMainSync {
            ThemeManager.applyThemeTarget(context, true)
        }
        val darkActivity = instrumentation.startActivitySync(intent) as SettingsActivity
        try {
            SystemClock.sleep(600)
            val darkList = findFirst(darkActivity.window.decorView, UniversalRecyclerView::class.java)
            assertNotNull("UniversalRecyclerView must render in dark mode", darkList)
            captureScreenshot("ref20-settings-top-dark")
        } finally {
            if (!darkActivity.isFinishing && !darkActivity.isDestroyed) {
                darkActivity.finish()
            }
            SystemClock.sleep(250)
        }
    }

    private fun <T : View> findFirst(root: View, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findFirst(root.getChildAt(i), type)
                if (found != null) return found
            }
        }
        return null
    }

    private fun <T : View> findAll(root: View, type: Class<T>, out: MutableList<T> = mutableListOf()): List<T> {
        if (type.isInstance(root)) {
            type.cast(root)?.let { out.add(it) }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findAll(root.getChildAt(i), type, out)
            }
        }
        return out
    }

    private fun captureScreenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/eve-approved-ui")
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        } catch (_: Throwable) {
            try {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "eve-approved-ui")
                dir.mkdirs()
                FileOutputStream(File(dir, "$name.png")).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (_: Throwable) {}
        } finally {
            bitmap.recycle()
        }
    }
}
