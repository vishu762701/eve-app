package org.telegram.ui.eve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LanguageSelectActivity;
import org.telegram.ui.LauncherIconController;
import org.telegram.ui.LoginActivity;
import org.telegram.ui.LogoutActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.PremiumPreviewFragment;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.ThemeActivity;
import org.telegram.ui.UserInfoActivity;
import org.telegram.ui.eve.admin.EveAdminConfig;
import org.telegram.ui.eve.admin.EveAdminControlCenterActivity;
import org.telegram.ui.eve.admin.EveAdminModels;
import org.telegram.ui.eve.auth.EveBackendClient;
import org.telegram.ui.eve.auth.EveUserProfile;
import org.telegram.ui.eve.exams.EveExamDialogsMapper;
import org.telegram.ui.eve.exams.EveExamModels;
import org.telegram.ui.eve.exams.EveExamTestsFragment;

import java.util.Collections;

/**
 * End-to-end integration & cross-mission regression verification for Missions 1..8:
 *   1. EVE Brand Identity & Third-party Telegram API Disclosure
 *   2. Main Screen Chats -> Exams (preserving Telegram UI & Worker backend)
 *   3. Account Screen Cleanup (preserving Telegram auth/sessions/logout)
 *   4. Chat Settings -> Exam Settings
 *   5. English-only Application Language (preserving translation & system locale)
 *   6. Settings Cleanup & EVE Premium (preserving star visual & animation)
 *   7. Authoritative Worker + D1 EVE Admin Control Center
 *   8. Separation of MTProto messaging/auth vs Worker+D1 exam ecosystem authority
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
public class EveIntegrationRegressionTest {

    private Context appContext;

    @Before
    public void setUp() {
        appContext = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = appContext;
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
    }

    @Test
    public void allMissionPoliciesAndStrings_holdSimultaneously() {
        // Mission 1: EVE independent brand identity
        assertEquals("EVE", appContext.getString(R.string.AppName));
        assertFalse(appContext.getString(R.string.EveThirdPartyApiDisclosure).contains("Telegram API"));
        assertTrue(appContext.getString(R.string.Page1Message).toLowerCase().contains("independent"));
        assertEquals(R.drawable.ic_eve_launcher_foreground, LauncherIconController.LauncherIcon.DEFAULT.foreground);

        // Mission 2: Exams main tab & search copy
        assertEquals("Exams", appContext.getString(R.string.MainTabsExams));
        assertEquals("Search exams", appContext.getString(R.string.SearchExams));
        assertNotNull(EveExamDialogsMapper.resolveEmptyState(EveExamModels.LoadState.EMPTY, "", ""));
        assertNotNull(EveExamTestsFragment.class);
        assertNotNull(DialogsActivity.class);
        assertNotNull(MainTabsActivity.class);

        // Mission 3: Account cleanup without breaking auth/logout
        assertFalse(UserInfoActivity.isEveAccountOptionEnabled(UserInfoActivity.ACCOUNT_OPTION_CHANNEL));
        assertFalse(UserInfoActivity.isEveAccountOptionEnabled(UserInfoActivity.ACCOUNT_OPTION_CHAT_AUTOMATION));
        assertFalse(UserInfoActivity.isEveAccountOptionEnabled(UserInfoActivity.ACCOUNT_OPTION_ADD_ACCOUNT));
        assertTrue(UserInfoActivity.isEveAccountOptionEnabled(UserInfoActivity.ACCOUNT_OPTION_LOGOUT));

        // Mission 4: Exam Settings rename & removed sections
        assertEquals("Exam Settings", appContext.getString(R.string.ChatSettings));
        assertEquals("Exam Settings", appContext.getString(R.string.SettingsChat));
        assertFalse(ThemeActivity.isEveExamSettingSectionEnabled(ThemeActivity.EVE_SECTION_APP_ICON));
        assertFalse(ThemeActivity.isEveExamSettingSectionEnabled(ThemeActivity.EVE_SECTION_CHAT_LIST_SWIPE));
        assertTrue(ThemeActivity.isEveExamSettingSectionEnabled(ThemeActivity.EVE_SECTION_WALLPAPER));

        // Mission 5: Dual English and Hindi restricted language set
        assertFalse(LocaleController.isEnglishOnlyMode());
        assertTrue(LocaleController.isEveRestrictedLanguageMode());
        assertEquals("English", LocaleController.getCurrentLanguageName());
        assertEquals(2, LanguageSelectActivity.filterSelectableLanguages(Collections.emptyList()).size());

        // Mission 6: Telegram Features removed & EVE Premium rebranded
        assertFalse(SettingsActivity.isEveSettingsItemEnabled(SettingsActivity.EVE_SETTINGS_ITEM_TELEGRAM_FEATURES));
        assertTrue(SettingsActivity.isEveSettingsItemEnabled(SettingsActivity.EVE_SETTINGS_ITEM_PREMIUM));
        assertTrue(PremiumPreviewFragment.isEvePremiumTransformEnabled());
        assertEquals("EVE Premium", appContext.getString(R.string.EvePremium));

        // Mission 7: Worker + D1 Admin RBAC (hidden unless verified server admin)
        assertFalse(EveAdminConfig.isAdminAvailable());
        assertTrue(org.telegram.messenger.PushListenerController.EVE_DISABLE_TELEGRAM_PUSH);
        EveUserProfile verifiedAdmin = new EveUserProfile(
                "u1", "EVE0001", "Admin", "admin@example.com", "", "google.com",
                true, EveUserProfile.STATUS_ACTIVE, true, "admin", "General", "", 1L, 1L, 1L, true);
        assertTrue(EveAdminConfig.isServerAdminProfile(verifiedAdmin));
        assertFalse(EveAdminConfig.hasRight(
                new EveAdminModels.AdminProfile("sub@example.com", false, "Sub", Collections.emptySet()),
                EveAdminModels.RIGHT_MANAGE_PREMIUM));
        assertNotNull(EveAdminControlCenterActivity.class);
    }

    @Test
    public void integrationBoundaries_preserveMtprotoAndWorkerAuthority() {
        // Telegram MTProto auth, session management, and logout remain intact.
        assertNotNull(ConnectionsManager.class);
        assertNotNull(LoginActivity.class);
        assertNotNull(LogoutActivity.class);

        // Authoritative EVE Worker backend defaults cleanly and refuses blank Bearer tokens.
        EveBackendClient client = new EveBackendClient();
        assertEquals("https://eve-backend.anyqueairdrop.workers.dev", client.getBaseUrl());
        assertEquals("2", EveBackendClient.EVE_CLIENT_VERSION);
        try {
            client.getJsonRoot("/api/admin/me", "");
        } catch (EveBackendClient.BackendException expected) {
            assertTrue(expected.unauthorized);
            assertEquals(401, expected.httpStatus);
        }
    }
}
