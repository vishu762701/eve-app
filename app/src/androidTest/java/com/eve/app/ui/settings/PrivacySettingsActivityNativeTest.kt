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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.Cells.HeaderCell
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Components.UniversalRecyclerView
import java.io.File
import java.io.FileOutputStream

/**
 * Batch 3 (Refs 10, 11, 12) Native UI Instrumentation Test:
 * Launches original Telegram PrivacySettingsActivity in EVE, verifies hierarchy,
 * visibility preference persistence, light/dark rendering, and captures emulator screenshots
 * for Refs 12 (Security), 11 (Privacy), and 10 (Data & Protection).
 */
@RunWith(AndroidJUnit4::class)
class PrivacySettingsActivityNativeTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var savedNightMode: Int = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

    @Before
    fun setUp() {
        savedNightMode = AppCompatDelegate.getDefaultNightMode()
        ProfileEditorAccountGuard.testOverrideUid = "test-privacy-owner"
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
    fun refs10_11_12OriginalTelegramPrivacySettingsHierarchyPersistenceAndScreenshots() {
        val intent = Intent(context, PrivacySettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val activity = instrumentation.startActivitySync(intent) as PrivacySettingsActivity
        try {
            SystemClock.sleep(550)
            val root = activity.window.decorView
            val actionBar = findFirst(root, ActionBar::class.java)
            val listView = findFirst(root, UniversalRecyclerView::class.java)
            val headers = findAll(root, HeaderCell::class.java)
            val cells = findAll(root, TextCell::class.java)

            assertNotNull("Original Telegram ActionBar must be present", actionBar)
            assertNotNull("Original Telegram UniversalRecyclerView must be present", listView)
            assertTrue("HeaderCell sections must render", headers.isNotEmpty())
            assertTrue("TextCell rows must render", cells.isNotEmpty())

            // Ref 12: Security & Privacy top section
            captureScreenshot("ref12-privacy-security-light")

            // Verify preference persistence
            activity.onVisibilitySelected(
                org.telegram.ui.PrivacySettingsActivity.ROW_LEADERBOARD_VISIBILITY,
                PrivacyPreferences.VISIBILITY_ONLY_ME
            )
            assertEquals(PrivacyPreferences.VISIBILITY_ONLY_ME, activity.getLeaderboardVisibility())
            activity.onVisibilitySelected(
                org.telegram.ui.PrivacySettingsActivity.ROW_LEADERBOARD_VISIBILITY,
                PrivacyPreferences.VISIBILITY_EVERYBODY
            )

            // Ref 11: Privacy section
            activity.runOnUiThread { listView!!.scrollBy(0, 700) }
            SystemClock.sleep(400)
            captureScreenshot("ref11-privacy-mid-light")

            // Ref 10: Lower Data & Protection section
            activity.runOnUiThread { listView!!.scrollBy(0, 900) }
            SystemClock.sleep(400)
            captureScreenshot("ref10-privacy-lower-light")
        } finally {
            if (!activity.isFinishing && !activity.isDestroyed) {
                activity.finish()
            }
            SystemClock.sleep(250)
        }

        // Dark mode capture
        instrumentation.runOnMainSync {
            ThemeManager.applyThemeTarget(context, true)
        }
        val darkActivity = instrumentation.startActivitySync(intent) as PrivacySettingsActivity
        try {
            SystemClock.sleep(550)
            val darkList = findFirst(darkActivity.window.decorView, UniversalRecyclerView::class.java)
            assertNotNull("UniversalRecyclerView must render in dark theme", darkList)
            captureScreenshot("ref12-privacy-security-dark")
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
