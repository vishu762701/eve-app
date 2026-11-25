package org.telegram.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.EveInspectionMode;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.eve.auth.EveAuthCoordinator;
import org.telegram.ui.eve.auth.EveAuthValidator;
import org.telegram.ui.eve.auth.EveUserProfile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Behavioral JVM regression suite for Mission T0 (Official Telegram Login restoration).
 * Exercises real routing, SharedPreferences login state, UserConfig activation state,
 * inspection-mode isolation, network gate state, and preserved EVE Firebase/backend classes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
public class OfficialTelegramLoginTest {

    private Context appContext;

    @Before
    public void setUp() {
        appContext = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = appContext;
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig.getInstance(a).clearConfig();
            String suffix = a == 0 ? "" : "_" + a;
            appContext.getSharedPreferences("logininfo2" + suffix, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit();
        }
        appContext.getSharedPreferences("logininfo2", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @Test
    public void freshUnauthenticatedLaunch_opensOriginalTelegramIntroActivity() {
        assertFalse(UserConfig.getInstance(0).isClientActivated());
        BaseFragment startup = LaunchActivity.createClientNotActivatedFragment(0);
        assertNotNull(startup);
        assertEquals(IntroActivity.class, startup.getClass());
    }

    @Test
    public void savedIncompleteLoginState_restoresOriginalTelegramLoginActivity() {
        SharedPreferences prefs = appContext.getSharedPreferences("logininfo2", Context.MODE_PRIVATE);
        prefs.edit().putInt("currentViewNum", 2).commit();

        Bundle restored = LoginActivity.loadCurrentState(false, 0);
        assertEquals(2, restored.getInt("currentViewNum", 0));

        BaseFragment startup = LaunchActivity.createClientNotActivatedFragment(0);
        assertNotNull(startup);
        assertEquals(LoginActivity.class, startup.getClass());
    }

    @Test
    public void logout_returnsToOriginalTelegramIntroActivityWithOnLogoutFlag() throws Exception {
        IntroActivity logoutIntro = LaunchActivity.createLogoutIntroFragment();
        assertNotNull(logoutIntro);
        assertEquals(IntroActivity.class, logoutIntro.getClass());

        Field isOnLogoutField = IntroActivity.class.getDeclaredField("isOnLogout");
        isOnLogoutField.setAccessible(true);
        assertTrue(isOnLogoutField.getBoolean(logoutIntro));
    }

    @Test
    public void addAccount_opensOriginalLoginActivityForSelectedAccountSlot() throws Exception {
        int slot = 2;
        LoginActivity addAccountFragment = MainTabsActivity.createAddAccountLoginFragment(slot);
        assertNotNull(addAccountFragment);
        assertEquals(LoginActivity.class, addAccountFragment.getClass());
        assertEquals(slot, addAccountFragment.getCurrentAccount());

        Field newAccountField = LoginActivity.class.getDeclaredField("newAccount");
        newAccountField.setAccessible(true);
        assertTrue(newAccountField.getBoolean(addAccountFragment));
    }

    @Test
    public void debugMode_doesNotBypassTelegramAuthentication() {
        assertTrue(EveInspectionMode.isEnabledForBuild(true));
        assertFalse(EveInspectionMode.isEnabledForBuild(false));
        assertFalse(EveInspectionMode.isActive(0));

        BaseFragment startup = LaunchActivity.createClientNotActivatedFragment(0);
        assertEquals(IntroActivity.class, startup.getClass());
    }

    @Test
    public void syntheticLocalAccount_neverCreatedOrTreatedAsAuthenticated() {
        assertNull(EveInspectionMode.ensureLocalInspectionUser(0));
        assertFalse(UserConfig.getInstance(0).isClientActivated());
        assertEquals(0L, UserConfig.getInstance(0).getClientUserId());
        assertNull(UserConfig.getInstance(0).getCurrentUser());
        assertEquals(0, UserConfig.getActivatedAccountsCount());
    }

    @Test
    public void mtprotoRequests_notRejectedByInspectionModeNetworkGate() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            assertFalse(ConnectionsManager.isInspectionNetworkGateActive(a));
            assertFalse(EveInspectionMode.isActive(a));
        }
    }

    @Test
    public void eveFirebaseBackendAndCredentialInjection_remainIntact() {
        assertEquals(BuildConfig.TELEGRAM_APP_ID, BuildVars.APP_ID);
        assertEquals(BuildConfig.HAS_CUSTOM_TELEGRAM_API_CREDENTIALS, BuildVars.HAS_CUSTOM_TELEGRAM_API_CREDENTIALS);
        assertTrue(BuildVars.APP_ID != 4);

        assertEquals(EveAuthCoordinator.NavDestination.EVE_LOGIN,
                EveAuthCoordinator.resolveStartupDestination(false));
        assertEquals(EveAuthCoordinator.NavDestination.EVE_HOME,
                EveAuthCoordinator.resolvePostAuthDestination(true, EveAuthCoordinator.SyncState.CLOUD_SYNCHRONIZED));
        assertTrue(EveAuthValidator.validateEmail("student@eve.app").valid);

        EveUserProfile profile = new EveUserProfile(
                "uid_t0", "EV-11AA22", "Student", "student@eve.app", "", "password",
                true, EveUserProfile.STATUS_ACTIVE, false, "", "General", "",
                1L, 1L, 1L, true);
        assertEquals("EV-11AA22", profile.eveId);
    }

    @Test
    public void defaultCountryFallback_resolvesIndia91AndMatchesIsoEvenWhenLocalized() throws Exception {
        ArrayList<CountrySelectActivity.Country> countries = new ArrayList<>();
        HashMap<String, String> languageMap = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(appContext.getAssets().open("countries.txt")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] args = line.split(";");
                CountrySelectActivity.Country country = new CountrySelectActivity.Country();
                country.code = args[0];
                country.shortname = args[1];
                country.name = args[2];
                countries.add(country);
                languageMap.put(args[1], args[2]);
            }
        }

        assertEquals("IN", LoginActivity.resolveInitialCountryIso(null, languageMap));
        assertEquals("IN", LoginActivity.resolveInitialCountryIso("", languageMap));
        assertEquals("IN", LoginActivity.resolveInitialCountryIso("zz", languageMap));
        assertEquals("IN", LoginActivity.resolveInitialCountryIso("in", languageMap));
        assertEquals("GB", LoginActivity.resolveInitialCountryIso("gb", languageMap));

        CountrySelectActivity.Country india = LoginActivity.findCountryByIso(countries, languageMap, "IN");
        assertNotNull(india);
        assertEquals("91", india.code);
        assertEquals("IN", india.shortname);

        // Localized country name must still resolve by ISO shortname/defaultName.
        india.defaultName = india.name;
        india.name = "\u092d\u093e\u0930\u0924";
        CountrySelectActivity.Country localizedIndia = LoginActivity.findCountryByIso(countries, languageMap, "IN");
        assertNotNull(localizedIndia);
        assertEquals("91", localizedIndia.code);
    }

    @Test
    public void telegramApiCredentialGuard_rejectsUnconfiguredCredentialsAndAcceptsValidOnes() {
        int originalAppId = BuildVars.APP_ID;
        String originalAppHash = BuildVars.APP_HASH;
        try {
            BuildVars.APP_ID = 0;
            BuildVars.APP_HASH = "";
            assertFalse(LoginActivity.hasConfiguredTelegramApiCredentials());

            BuildVars.APP_ID = 123456;
            BuildVars.APP_HASH = "";
            assertFalse(LoginActivity.hasConfiguredTelegramApiCredentials());

            BuildVars.APP_ID = 123456;
            BuildVars.APP_HASH = "configured_test_hash_value";
            assertTrue(LoginActivity.hasConfiguredTelegramApiCredentials());
        } finally {
            BuildVars.APP_ID = originalAppId;
            BuildVars.APP_HASH = originalAppHash;
        }
    }
}
