package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.IntroActivity;
import org.telegram.ui.LaunchActivity;

/**
 * Behavioral JVM verification suite for Mission T0 + Mission 11/12 invariants:
 *  1. Debug-only diagnostics flag remains gated by build type.
 *  2. LaunchActivity routes unactivated sessions to IntroActivity / LoginActivity (no debug bypass).
 *  3. Synthetic inspection user is never registered or authenticated.
 *  4. ConnectionsManager does not enforce an artificial inspection network gate.
 *  5. Mission 10 unauthorized dashboard package remains absent while official login classes load.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
public class EveInspectionModeTest {

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        UserConfig.getInstance(0).clearConfig();
    }

    @Test
    public void inspectionMode_buildFlagGatedAndSessionBypassDisabled() {
        assertTrue(EveInspectionMode.isEnabledForBuild(true));
        assertFalse(EveInspectionMode.isEnabledForBuild(false));
        assertFalse(EveInspectionMode.isActive(0));
        assertNull(EveInspectionMode.ensureLocalInspectionUser(0));
    }

    @Test
    public void launchActivity_routesUnactivatedDebugSessionToIntroActivity() {
        BaseFragment fragment = LaunchActivity.createClientNotActivatedFragment(0);
        assertEquals(IntroActivity.class, fragment.getClass());
        assertFalse(UserConfig.getInstance(0).isClientActivated());
    }

    @Test(expected = ClassNotFoundException.class)
    public void unauthorizedEveMockTestDashboardIsCompletelyRemoved() throws ClassNotFoundException {
        Class.forName("org.telegram.ui.eve.app.EveDashboardFragment");
    }

    @Test
    public void connectionsManager_allowsUnauthenticatedMtprotoRequests() {
        assertFalse(ConnectionsManager.isInspectionNetworkGateActive(0));
    }

    @Test
    public void officialLoginClassesLoadWhileRemovedModulesStayAbsent() throws ClassNotFoundException {
        Class.forName("org.telegram.ui.LoginActivity");
        Class.forName("org.telegram.ui.IntroActivity");
        Class.forName("org.telegram.messenger.SmsReceiver");
        Class.forName("org.telegram.messenger.CallReceiver");
        Class.forName("org.telegram.messenger.AuthTokensHelper");
        Class.forName("org.telegram.messenger.FirebasePhoneNumberVerificationHelper");
    }
}
