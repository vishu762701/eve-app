package org.telegram.ui.eve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.RectF;
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
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.R;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.eve.admin.EveAdminConfig;
import org.telegram.ui.eve.admin.EveAdminModels;
import org.telegram.ui.eve.auth.EveAuthCoordinator;
import org.telegram.ui.eve.auth.EveBackendClient;
import org.telegram.ui.eve.auth.EveTelegramChatOnboardingFragment;
import org.telegram.ui.eve.auth.EveTelegramSessionManager;
import org.telegram.ui.eve.auth.EveUserProfile;
import org.telegram.ui.eve.exams.EveExamDialogsMapper;
import org.telegram.ui.eve.exams.EveExamModels;
import org.telegram.ui.eve.exams.EveExamRepository;
import org.telegram.ui.eve.premium.EvePremiumRepository;

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
        // Production DC must be default across Debug and Release builds.
        assertFalse(EveTelegramSessionManager.shouldUseTestDc());

        long eveSyntheticId = 123456789L;
        assertFalse(EveTelegramSessionManager.isRealMtprotoUserId(false, 9996628429L, eveSyntheticId));
        assertFalse(EveTelegramSessionManager.isRealMtprotoUserId(true, 0L, eveSyntheticId));
        assertFalse(EveTelegramSessionManager.isRealMtprotoUserId(true, eveSyntheticId, eveSyntheticId));
        assertTrue(EveTelegramSessionManager.isRealMtprotoUserId(true, 9996628429L, eveSyntheticId));
        assertTrue(EveTelegramSessionManager.isRealMtprotoUserId(true, 555001L, 0L));
    }

    @Test
    public void ownerAndAdminEvePremiumAutoUnlock_worksWithoutSpoofingTelegramPremium() {
        EveUserProfile owner = new EveUserProfile(
                "own-1", "EV-OWN01", "Owner", "pronlike9@gmail.com", "", "google.com",
                true, EveUserProfile.STATUS_ACTIVE, true, "owner", "General", "", 1L, 1L, 1L, true);
        EveUserProfile student = new EveUserProfile(
                "stu-1", "EV-STU01", "Student", "student@example.com", "", "google.com",
                true, EveUserProfile.STATUS_ACTIVE, false, "stu", "General", "", 1L, 1L, 1L, true);

        assertTrue(EveAdminConfig.isOwner(owner));
        assertFalse(EveAdminConfig.isOwner(student));

        EvePremiumRepository.EntitlementStatus unlocked = EvePremiumRepository.resolveEffectiveEntitlement(
                new EvePremiumRepository.EntitlementStatus(false, "NONE", "", 0L, null, false, ""),
                true);
        assertTrue(unlocked.isPremium);
        assertTrue(unlocked.isLifetime);
        assertEquals("ACTIVE", unlocked.status);
        assertEquals("EVE Owner Access", unlocked.planName);

        EvePremiumRepository.EntitlementStatus locked = EvePremiumRepository.resolveEffectiveEntitlement(
                new EvePremiumRepository.EntitlementStatus(false, "NONE", "", 0L, null, false, ""),
                false);
        assertFalse(locked.isPremium);
        assertEquals("NONE", locked.status);
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

    @Test
    public void dialogCellAvatarCoordinatesAndBindings_workForChatsAndExams() {
        ImageReceiver receiver = new ImageReceiver(null);
        assertEquals(0f, receiver.getImageWidth(), 0.01f);
        assertEquals(0f, receiver.getImageHeight(), 0.01f);

        RectF avatarRect = new RectF(16f, 12f, 120f, 116f);
        DialogCell.syncAvatarImageCoords(receiver, avatarRect);
        assertEquals(16f, receiver.getImageX(), 0.01f);
        assertEquals(12f, receiver.getImageY(), 0.01f);
        assertEquals(104f, receiver.getImageWidth(), 0.01f);
        assertEquals(104f, receiver.getImageHeight(), 0.01f);
        assertFalse(receiver.isAspectFit());

        assertEquals(26, DialogCell.resolveCustomDialogAvatarRoundRadiusDp(false));
        assertEquals(28, DialogCell.resolveCustomDialogAvatarRoundRadiusDp(true));

        EveExamModels.ExamSummary exam = new EveExamModels.ExamSummary(
                "ex-1", "UPSC CSE", 120, "UPSC", 100, "Test 1", "", "", "live",
                1700000000000L, 1, "Mock 1", 1700000000, false,
                "/api/exams/ex-1/avatar?v=4", "\uD83D\uDCDA", 3);
        DialogCell.CustomDialog examDialog = EveExamDialogsMapper.toCustomDialog(exam);
        assertEquals("/api/exams/ex-1/avatar?v=4", examDialog.avatarUrl);
        assertEquals("\uD83D\uDCDA", examDialog.avatarEmoji);
        assertEquals(3, examDialog.avatarColor);
        assertTrue(EveBackendClient.resolveMediaUrl(examDialog.avatarUrl).startsWith("https://"));

        DialogCell.CustomDialog channelDialog = EveExamDialogsMapper.toAnnouncementChannelDialog(
                new EveExamRepository.AnnouncementChannelSummary(
                        "EVE Updates", "/api/announcement-channel/avatar?v=2", "Notice", 1700000000, 1700000000000L, 1));
        assertEquals("/api/announcement-channel/avatar?v=2", channelDialog.avatarUrl);
        assertTrue(EveBackendClient.resolveMediaUrl(channelDialog.avatarUrl).startsWith("https://"));

        assertFalse(EveTelegramChatOnboardingFragment.LOGIN_CAPTION.contains("999662"));
        assertFalse(EveTelegramChatOnboardingFragment.LOGIN_CAPTION.contains("22222"));
    }
}
