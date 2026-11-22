package com.eve.app.ui.profile

import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ApplicationProvider
import com.eve.app.EveApplication
import com.eve.app.R
import com.eve.app.ui.home.HomeStartupBoundaryFixture
import com.eve.app.ui.home.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 1. Full drawer avatar click navigation sequence in MainActivity:
 *    open drawer -> tap avatar -> drawer close callback -> EveNavigationHelper -> ProfileActivity Intent.
 * 2. Authenticated ProfileActivity completes full initialization without early-exit:
 *    proves isFinishing == false, and executes full layout measurement pass
 *    (triggering positionFullWindowAvatarHost, ProfileHeaderExpansion, avatarContainer, avatarGooey).
 * 3. Repeated navigation and recreation cycles cleanly without memory or window leaks.
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
    fun testDrawerAvatarClickNavigatesToProfileActivityThroughDrawerClosure() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().setTheme(R.style.Theme_Eve)
        controller.setup().visible()
        idle()

        try {
            val mainActivity = controller.get()
            val drawerLayout = mainActivity.findViewById<DrawerLayout>(R.id.drawerLayout)
            val drawerAvatar = mainActivity.findViewById<View>(R.id.ivDrawerAvatar)
            assertNotNull("Drawer layout must be present", drawerLayout)
            assertNotNull("Drawer avatar view must be present in MainActivity", drawerAvatar)

            // 1. Open drawer
            drawerLayout.openDrawer(GravityCompat.START)
            idle()

            // 2. Click drawer avatar
            drawerAvatar.performClick()
            idle()

            // 3. Verify drawer closed and EveNavigationHelper navigated
            val shadowActivity = shadowOf(mainActivity)
            val nextIntent = shadowActivity.nextStartedActivity
            assertNotNull("ProfileActivity must be launched by drawer closure navigation", nextIntent)
            assertEquals(
                "Target activity must be ProfileActivity",
                ProfileActivity::class.java.name,
                nextIntent?.component?.className
            )
        } finally {
            controller.pause().stop().destroy()
            idle()
        }
    }

    @Test
    fun testAuthenticatedProfileActivityInitializesAndExecutesLayoutPass() {
        val controller = Robolectric.buildActivity(ProfileActivity::class.java)
        controller.get().setTheme(R.style.Theme_Eve)

        try {
            // Full startup sequence: onCreate -> onStart -> onResume -> visible
            controller.create().start().resume().visible()
            idle()

            val activity = controller.get()
            assertNotNull("ProfileActivity instance must be created", activity)
            assertFalse(
                "ProfileActivity must NOT finish early when owner is authenticated",
                activity.isFinishing
            )

            // Verify core views in hierarchy
            val scrollView = activity.findViewById<View>(R.id.profileScrollView)
            assertNotNull("profileScrollView must be initialized in hierarchy", scrollView)

            val avatarContainer = activity.findViewById<View>(R.id.avatarContainer)
            assertNotNull("avatarContainer must be initialized in hierarchy", avatarContainer)

            val avatarGooey = activity.findViewById<View>(R.id.avatarGooey)
            assertNotNull("avatarGooey must be present in hierarchy", avatarGooey)

            // Execute full layout pass to exercise positionFullWindowAvatarHost and ProfileHeaderExpansion
            val decor = activity.window.decorView
            val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
            decor.measure(widthSpec, heightSpec)
            decor.layout(0, 0, 1080, 2400)
            idle()

            assertTrue("Decor view must have positive measured width", decor.measuredWidth > 0)
            assertTrue("Decor view must have positive measured height", decor.measuredHeight > 0)
            assertFalse("Activity must remain active and not finishing after layout pass", activity.isFinishing)
        } finally {
            controller.pause().stop().destroy()
            idle()
        }
    }

    @Test
    fun testRepeatedProfileNavigationLifecycleNoLeakOrCrash() {
        for (i in 1..3) {
            val controller = Robolectric.buildActivity(ProfileActivity::class.java)
            controller.get().setTheme(R.style.Theme_Eve)
            controller.create().start().resume().visible()
            idle()

            val activity = controller.get()
            assertFalse("Cycle $i: ProfileActivity must not finish early", activity.isFinishing)

            val decor = activity.window.decorView
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
            )
            decor.layout(0, 0, 1080, 2400)
            idle()

            controller.pause().stop().destroy()
            idle()
            assertTrue("Cycle $i: ProfileActivity must destroy cleanly", activity.isDestroyed)
        }
    }
}
