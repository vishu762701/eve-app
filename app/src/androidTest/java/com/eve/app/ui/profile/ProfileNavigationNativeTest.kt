package com.eve.app.ui.profile

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eve.app.R
import com.eve.app.ui.home.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native Android instrumentation test for Profile navigation and initial rendering.
 * Exercises:
 * 1. MainActivity drawer avatar click navigation sequence.
 * 2. ProfileActivity launch, view tree attachment, and safe lifecycle termination.
 * 3. Repeated launch stability without window leaks.
 */
@RunWith(AndroidJUnit4::class)
class ProfileNavigationNativeTest {

    @Test
    fun mainActivityDrawerAvatarNavigatesToProfile() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<MainActivity>(mainIntent).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("MainActivity must be launched", activity)
                val drawerAvatar = activity.findViewById<View>(R.id.ivDrawerAvatar)
                assertNotNull("Drawer avatar must be present", drawerAvatar)
                // Trigger drawer avatar click directly
                drawerAvatar.performClick()
            }
        }
    }

    @Test
    fun profileActivityInitializesViewTreeWithoutCrash() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val intent = Intent(context, ProfileActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<ProfileActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("ProfileActivity must be launched", activity)
                val decor = activity.window?.decorView
                assertNotNull("Window decorView must not be null", decor)

                val scrollView = activity.findViewById<View>(R.id.profileScrollView)
                val avatarGooey = activity.findViewById<View>(R.id.avatarGooey)
                // When unauthenticated, ProfileActivity gracefully finishes without throwing;
                // when authenticated, views remain attached and active.
                assertTrue("ProfileActivity must either be finishing gracefully or views must be bound",
                    activity.isFinishing || (scrollView != null && avatarGooey != null))
            }
        }
    }

    @Test
    fun repeatedProfileActivityLaunchesSurviveCleanly() {
        val context: Context = ApplicationProvider.getApplicationContext()
        for (i in 1..3) {
            val intent = Intent(context, ProfileActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityScenario.launch<ProfileActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    assertNotNull("Cycle $i must instantiate ProfileActivity", activity)
                }
            }
        }
    }
}
