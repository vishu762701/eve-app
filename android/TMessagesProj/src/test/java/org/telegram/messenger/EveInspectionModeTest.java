package org.telegram.messenger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Automated JVM verification suite for Mission T0 + Mission 11/12 invariants:
 *  1. DEBUG-only UI Inspection Mode helper remains strictly disabled in release builds.
 *  2. LaunchActivity routes unactivated sessions to official Telegram IntroActivity / LoginActivity.
 *  3. Mission 10 unauthorized EVE Mock-Test Dashboard package remains removed.
 *  4. ConnectionsManager does not block unauthenticated MTProto login requests.
 *  5. Official Telegram Login files are restored while M01-M05 removals remain preserved.
 */
public class EveInspectionModeTest {

    private File getModuleDir() {
        File dir = new File(System.getProperty("user.dir"));
        if (!new File(dir, "src/main/java").exists()) {
            dir = new File("/home/spark/telegram-baseline/TMessagesProj");
        }
        return dir;
    }

    @Test
    public void inspectionMode_enabledInDebugAndStrictlyDisabledInRelease() {
        assertTrue(EveInspectionMode.isEnabledForBuild(true));
        assertFalse(EveInspectionMode.isEnabledForBuild(false));
    }

    @Test
    public void launchActivity_routesToOfficialTelegramIntroAndLoginWhenNotActivated() throws Exception {
        File moduleDir = getModuleDir();
        String launch = new String(Files.readAllBytes(
                new File(moduleDir, "src/main/java/org/telegram/ui/LaunchActivity.java").toPath()), StandardCharsets.UTF_8);

        assertFalse(launch.contains("EveInspectionMode.isEnabledForCurrentBuild()"));
        assertFalse(launch.contains("EveAuthFragment"));
        assertFalse(launch.contains("EveDashboardFragment"));
        assertTrue(launch.contains("new LoginActivity()"));
        assertTrue(launch.contains("new IntroActivity()"));
    }

    @Test
    public void unauthorizedEveMockTestDashboardIsCompletelyRemoved() {
        File moduleDir = getModuleDir();
        File eveAppDir = new File(moduleDir, "src/main/java/org/telegram/ui/eve/app");
        assertFalse("org.telegram.ui.eve.app must be removed", eveAppDir.exists());
    }

    @Test
    public void connectionsManager_allowsOfficialTelegramLoginRequests() throws Exception {
        File moduleDir = getModuleDir();
        String cm = new String(Files.readAllBytes(
                new File(moduleDir, "src/main/java/org/telegram/tgnet/ConnectionsManager.java").toPath()), StandardCharsets.UTF_8);

        assertFalse(cm.contains("LOCAL_UI_INSPECTION_MODE"));
        assertFalse(cm.contains("EveInspectionMode.isActive"));
        assertTrue(cm.contains("LoginActivity.errorString"));
    }

    @Test
    public void priorSixRemovalMissionsRemainRemovedAndOfficialLoginRestored() throws Exception {
        File moduleDir = getModuleDir();
        // M01 Contacts, M02 Calls, M04 Stories remain removed; T0 official Telegram Login restored
        assertFalse(new File(moduleDir, "src/main/java/org/telegram/ui/ContactsActivity.java").exists());
        assertFalse(new File(moduleDir, "src/main/java/org/telegram/messenger/voip/VoIPService.java").exists());
        assertFalse(new File(moduleDir, "src/main/java/org/telegram/ui/Stories/StoriesController.java").exists());
        assertTrue(new File(moduleDir, "src/main/java/org/telegram/ui/LoginActivity.java").exists());
        assertTrue(new File(moduleDir, "src/main/java/org/telegram/ui/IntroActivity.java").exists());
        assertTrue(new File(moduleDir, "src/main/java/org/telegram/messenger/SmsReceiver.java").exists());
        assertTrue(new File(moduleDir, "src/main/java/org/telegram/messenger/CallReceiver.java").exists());
        assertTrue(new File(moduleDir, "src/main/java/org/telegram/messenger/AuthTokensHelper.java").exists());
        assertTrue(new File(moduleDir, "src/main/java/org/telegram/messenger/FirebasePhoneNumberVerificationHelper.java").exists());
    }
}
