package com.eve.app.ui.profile

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eve.app.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native Android instrumentation test for Profile navigation and initial rendering.
 * Runs on device / emulator in CI full verification profile.
 */
@RunWith(AndroidJUnit4::class)
class ProfileNavigationNativeTest {

    @Test
    fun profileActivityLaunchesWithoutCrash() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val intent = Intent(context, ProfileActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<ProfileActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("ProfileActivity must be launched", activity)
                val root = activity.findViewById<View>(R.id.profileScrollView)
                // If user is null in unauthenticated test fixture, activity finishes safely without crashing
                assertTrue("Activity must not crash during creation", activity.isFinishing || root != null)
            }
        }
    }

    @Test
    fun repeatedProfileActivityLaunchesSurviveCleanly() {
        val context: Context = ApplicationProvider.getApplicationContext()
        for (i in 1..2) {
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
