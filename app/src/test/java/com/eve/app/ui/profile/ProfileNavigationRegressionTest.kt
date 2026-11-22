package com.eve.app.ui.profile

import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.eve.app.EveApplication
import com.eve.app.R
import com.eve.app.ui.home.HomeStartupBoundaryFixture
import com.eve.app.ui.home.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Mission 00B / 00A Automated Profile Navigation Regression Test.
 * Validates:
 * 1. Drawer avatar click in MainActivity resolves Intent pointing to ProfileActivity.
 * 2. Authenticated ProfileActivity completes onCreate, onStart, and onResume without uncaught exceptions.
 * 3. View hierarchy (profileScrollView, avatarContainer, avatarGooey) initializes safely.
 * 4. Repeated navigation and lifecycle teardown cycles cleanly without crashing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = EveApplication::class,
    shadows = [
        ProfileEditorAuthFixture.ShadowAuth::class,
        HomeStartupBoundaryFixture.ApplicationBoundary::class,
        HomeStartupBoundaryFixture.ApiBoundary::class,
        HomeStartupBoundaryFixture.PushBoundary::class,
        HomeStartupBoundaryFixture.SchedulerBoundary::class
    ],
    instrumentedPackages = ["com.eve.app.data.remote", "com.eve.app.util", "com.eve.app.EveApplication"]
)
class ProfileNavigationRegressionTest {

    @Before
    fun setUp() {
        ProfileEditorAuthFixture.reset()
        ProfileEditorAuthFixture.setOwner("profile-test-uid")
        org.telegram.messenger.AndroidUtilities.checkDisplaySize(
            ApplicationProvider.getApplicationContext(),
            ApplicationProvider.getApplicationContext<EveApplication>().resources.configuration
        )
        org.telegram.ui.ActionBar.Theme.syncFromEvePalette(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        ProfileEditorAuthFixture.reset()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun testDrawerAvatarClickNavigatesToProfileActivity() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().setTheme(R.style.Theme_Eve)
        controller.setup().visible()
        idle()

        try {
            val mainActivity = controller.get()
            val drawerAvatar = mainActivity.findViewById<View>(R.id.ivDrawerAvatar)
            assertNotNull("Drawer avatar view must be present in MainActivity", drawerAvatar)

            // Click the drawer avatar to navigate
            drawerAvatar.performClick()
            idle()

            // In MainActivity.navigateFromDrawer: if drawer is not open, it navigates immediately via EveNavigationHelper;
            // if open, it sets pendingDrawerDestination and closes drawer.
            val shadowActivity = shadowOf(mainActivity)
            val nextIntent = shadowActivity.nextStartedActivity
            if (nextIntent != null) {
                assertEquals(
                    "Target activity must be ProfileActivity",
                    ProfileActivity::class.java.name,
                    nextIntent.component?.className
                )
            } else {
                // Pending navigation on drawer close
                val pendingField = MainActivity::class.java.getDeclaredField("pendingDrawerDestination").apply {
                    isAccessible = true
                }
                val pendingIntent = pendingField.get(mainActivity) as? Intent
                assertNotNull("Pending drawer destination must be set", pendingIntent)
                assertEquals(
                    "Target component must be ProfileActivity",
                    ProfileActivity::class.java.name,
                    pendingIntent?.component?.className
                )
            }
        } finally {
            controller.pause().stop().destroy()
            idle()
        }
    }

    @Test
    fun testProfileActivityInitializesAndRendersSafely() {
        val controller = Robolectric.buildActivity(ProfileActivity::class.java)
        controller.get().setTheme(R.style.Theme_Eve)

        try {
            controller.create().start().resume().visible()
            idle()

            val activity = controller.get()
            assertNotNull("ProfileActivity instance must be created", activity)

            val scrollView = activity.findViewById<View>(R.id.profileScrollView)
            assertNotNull("profileScrollView must be initialized in hierarchy", scrollView)

            val avatarContainer = activity.findViewById<View>(R.id.avatarContainer)
            assertNotNull("avatarContainer must be initialized in hierarchy", avatarContainer)

            val avatarGooey = activity.findViewById<View>(R.id.avatarGooey)
            assertNotNull("avatarGooey must be present in hierarchy", avatarGooey)

            assertTrue("ProfileActivity must not be finishing when owner is authenticated", !activity.isFinishing)
        } finally {
            controller.pause().stop().destroy()
            idle()
        }
    }

    @Test
    fun testRepeatedProfileNavigationLifecycleNoLeakOrCrash() {
        // Exercise 3 consecutive creation and teardown cycles
        for (i in 1..3) {
            val controller = Robolectric.buildActivity(ProfileActivity::class.java)
            controller.get().setTheme(R.style.Theme_Eve)
            controller.create().start().resume().visible()
            idle()

            val activity = controller.get()
            assertTrue("Cycle $i: ProfileActivity must complete startup", !activity.isFinishing)

            controller.pause().stop().destroy()
            idle()
            assertTrue("Cycle $i: ProfileActivity must destroy cleanly", activity.isDestroyed)
        }
    }
}
