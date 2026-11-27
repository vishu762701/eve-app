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

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.R;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.eve.admin.EveAdminModels;
import org.telegram.ui.eve.auth.EveAuthCoordinator;
import org.telegram.ui.eve.auth.EveBackendClient;
import org.telegram.ui.eve.auth.EveTelegramChatOnboardingFragment;
import org.telegram.ui.eve.auth.EveTelegramSessionManager;
import org.telegram.ui.eve.auth.EveUserProfile;

import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
public class EveTelegramDualSessionTest {

    private Context appContext;

    @Before
    public void setUp() {
        appContext = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = appContext;
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
    }

    @Test
    public void testDcAndRealMtprotoSessionGuards_behaveDeterministically() {
        assertEquals(BuildVars.DEBUG_VERSION, EveTelegramSessionManager.shouldUseTestDc());

        long eveSyntheticId = 123456789L;
        assertFalse(EveTelegramSessionManager.isRealMtprotoUserId(false, 9996628429L, eveSyntheticId));
        assertFalse(EveTelegramSessionManager.isRealMtprotoUserId(true, 0L, eveSyntheticId));
        assertFalse(EveTelegramSessionManager.isRealMtprotoUserId(true, eveSyntheticId, eveSyntheticId));
        assertTrue(EveTelegramSessionManager.isRealMtprotoUserId(true, 9996628429L, eveSyntheticId));
        assertTrue(EveTelegramSessionManager.isRealMtprotoUserId(true, 555001L, 0L));
    }

    @Test
    public void usernameSanitizer_normalizesLinksAndRejectsInvalidHandles() {
        assertEquals("eve_official", EveTelegramSessionManager.sanitizeUsernameForResolve("@eve_official"));
        assertEquals("eve_official", EveTelegramSessionManager.sanitizeUsernameForResolve("https://t.me/eve_official?start=1"));
        assertEquals("eve_support", EveTelegramSessionManager.sanitizeUsernameForResolve("t.me/eve_support/"));
        assertEquals("", EveTelegramSessionManager.sanitizeUsernameForResolve("bad handle!"));
        assertEquals("", EveTelegramSessionManager.sanitizeUsernameForResolve("   "));
        assertEquals("", EveTelegramSessionManager.sanitizeUsernameForResolve(null));
    }

    @Test
    public void cloudSyncIdentityBridge_includesTelegramIdentityWhenConnected() throws Exception {
        AtomicReference<String> capturedSyncBody = new AtomicReference<>("");
        EveBackendClient.HttpTransport transport = (method, url, bearerToken, jsonBody) -> {
            if (url.endsWith("/api/users/sync")) {
                capturedSyncBody.set(jsonBody != null ? jsonBody : "");
                return new EveBackendClient.HttpResponse(200,
                        "{\"success\":true,\"data\":{\"eveId\":\"EV-TEST99\",\"telegramUserId\":\"9996628429\",\"telegramUsername\":\"aarav_test\"}}");
            }
            if (url.endsWith("/api/users/profile")) {
                return new EveBackendClient.HttpResponse(200,
                        "{\"success\":true,\"data\":{\"id\":\"u1\",\"eveId\":\"EV-TEST99\",\"displayName\":\"Aarav\"}}");
            }
            if (url.endsWith("/api/auth/me")) {
                return new EveBackendClient.HttpResponse(200,
                        "{\"success\":true,\"data\":{\"uid\":\"u1\",\"email\":\"a@b.com\",\"displayName\":\"Aarav\",\"isAdmin\":false}}");
            }
            return new EveBackendClient.HttpResponse(404, "{\"success\":false}");
        };
        EveBackendClient client = new EveBackendClient("https://example.workers.dev", transport);
        EveUserProfile base = new EveUserProfile(
                "u1", "", "Aarav", "a@b.com", "", "google.com",
                true, EveUserProfile.STATUS_ACTIVE, false, "", "General", "", 1L, 1L, 1L, false);

        EveAuthCoordinator.SyncOutcome outcome = EveAuthCoordinator.executeCloudSync(
                client, base, "firebase-jwt", "9996628429", "@aarav_test");
        assertEquals(EveAuthCoordinator.SyncState.CLOUD_SYNCHRONIZED, outcome.state);
        JSONObject sent = new JSONObject(capturedSyncBody.get());
        assertEquals("Aarav", sent.getString("displayName"));
        assertEquals("9996628429", sent.getString("telegramUserId"));
        assertEquals("aarav_test", sent.getString("telegramUsername"));

        // Without Telegram session, sync remains clean and omits telegram fields
        EveAuthCoordinator.SyncOutcome plainOutcome = EveAuthCoordinator.executeCloudSync(
                client, base, "firebase-jwt");
        assertEquals(EveAuthCoordinator.SyncState.CLOUD_SYNCHRONIZED, plainOutcome.state);
        JSONObject plainSent = new JSONObject(capturedSyncBody.get());
        assertFalse(plainSent.has("telegramUserId"));
        assertFalse(plainSent.has("telegramUsername"));
    }

    @Test
    public void appConfigAndTabs_parseOfficialTelegramHandlesAndExposeFourTabs() throws Exception {
        JSONObject json = new JSONObject();
        json.put("maintenance_mode", false);
        json.put("officialTelegramChannelUsername", "@eve_channel");
        json.put("official_telegram_support_username", "@eve_support");

        EveBackendClient.AppConfigResult clientCfg = EveBackendClient.AppConfigResult.fromJson(json);
        assertEquals("@eve_channel", clientCfg.officialTelegramChannelUsername);
        assertEquals("@eve_support", clientCfg.officialTelegramSupportUsername);

        EveAdminModels.AppConfigModel adminCfg = EveAdminModels.AppConfigModel.fromJson(json);
        assertEquals("@eve_channel", adminCfg.officialTelegramChannelUsername);
        assertEquals("@eve_support", adminCfg.officialTelegramSupportUsername);

        assertEquals(4, MainTabsActivity.TABS_COUNT);
        assertNotNull(EveTelegramChatOnboardingFragment.class);
        assertEquals("Exams", appContext.getString(R.string.MainTabsExams));
        assertEquals("Chats", appContext.getString(R.string.MainTabsChats));
        assertEquals("Telegram Chat & Sync", appContext.getString(R.string.EveTelegramChatSync));
    }
}
