package org.telegram.messenger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Automated JVM verification suite for Mission 11:
 *  1. DEBUG-only UI Inspection Mode enabled in debug builds and strictly disabled in release builds.
 *  2. LaunchActivity opens original Telegram MainTabsActivity directly without login or EVE Dashboard.
 *  3. Mission 10 unauthorized EVE Mock-Test Dashboard package is completely removed.
 *  4. ConnectionsManager blocks unauthenticated MTProto requests during inspection mode.
 *  5. Missions 01-06 feature removals and legacy Telegram phone auth removals remain preserved.
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
    public void launchActivity_routesDirectlyToOriginalTelegramMainTabsInDebugMode() throws Exception {
        File moduleDir = getModuleDir();
        String launch = new String(Files.readAllBytes(
                new File(moduleDir, "src/main/java/org/telegram/ui/LaunchActivity.java").toPath()), StandardCharsets.UTF_8);

        assertTrue(launch.contains("if (org.telegram.messenger.EveInspectionMode.isEnabledForCurrentBuild())"));
        assertTrue(launch.contains("return new MainTabsActivity();"));
        assertFalse(launch.contains("EveDashboardFragment"));
        assertFalse(launch.contains("LoginActivity"));
        assertFalse(launch.contains("new IntroActivity"));
    }

    @Test
    public void unauthorizedEveMockTestDashboardIsCompletelyRemoved() {
        File moduleDir = getModuleDir();
        File eveAppDir = new File(moduleDir, "src/main/java/org/telegram/ui/eve/app");
        assertFalse("org.telegram.ui.eve.app must be removed", eveAppDir.exists());
    }

    @Test
    public void connectionsManager_blocksUnauthenticatedRequestsInInspectionMode() throws Exception {
        File moduleDir = getModuleDir();
        String cm = new String(Files.readAllBytes(
                new File(moduleDir, "src/main/java/org/telegram/tgnet/ConnectionsManager.java").toPath()), StandardCharsets.UTF_8);

        assertTrue(cm.contains("if (org.telegram.messenger.EveInspectionMode.isActive(currentAccount))"));
        assertTrue(cm.contains("LOCAL_UI_INSPECTION_MODE"));
    }

    @Test
    public void priorSixRemovalMissionsAndLegacyPhoneAuthRemainRemoved() throws Exception {
        File moduleDir = getModuleDir();
        // M01 Contacts, M02 Calls, M03 Bots/Business, M04 Stories, M05 Wallet/Stars, M08 Phone Auth
        assertFalse(new File(moduleDir, "src/main/java/org/telegram/ui/ContactsActivity.java").exists());
        assertFalse(new File(moduleDir, "src/main/java/org/telegram/ui/LoginActivity.java").exists());
        assertFalse(new File(moduleDir, "src/main/java/org/telegram/ui/IntroActivity.java").exists());
        assertFalse(new File(moduleDir, "src/main/java/org/telegram/messenger/SmsReceiver.java").exists());
        assertFalse(new File(moduleDir, "src/main/java/org/telegram/messenger/CallReceiver.java").exists());
        assertFalse(new File(moduleDir, "src/main/java/org/telegram/messenger/voip/VoIPService.java").exists());
        assertFalse(new File(moduleDir, "src/main/java/org/telegram/ui/Stories/StoriesController.java").exists());
    }
}
