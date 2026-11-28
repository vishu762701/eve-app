package org.telegram.ui.eve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.Components.Premium.GLIcon.GLIconTextureView;
import org.telegram.ui.Components.Premium.GLIcon.Icon3D;
import org.telegram.ui.Components.Premium.StarParticlesView;
import org.telegram.ui.PremiumPreviewFragment;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.premium.EvePremiumRepository;

/**
 * Behavioral & visual-asset regression tests for Settings Cleanup, EVE Help/Support,
 * and EVE Premium + Worker-backed UPI/QR billing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
public class EveSettingsAndPremiumTest {

    private Context appContext;

    @Before
    public void setUp() {
        appContext = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = appContext;
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
    }

    @Test
    public void telegramFeatures_isRemovedAndHelpStringsAreEveBranded() {
        assertFalse(SettingsActivity.isEveSettingsItemEnabled(SettingsActivity.EVE_SETTINGS_ITEM_TELEGRAM_FEATURES));
        assertTrue(SettingsActivity.isEveSettingsItemEnabled(SettingsActivity.EVE_SETTINGS_ITEM_PREMIUM));
        assertFalse(org.telegram.ui.eve.admin.EveAdminConfig.isSectionVisible(org.telegram.ui.eve.admin.EveAdminConfig.SECTION_CHAT_FOLDERS));
        assertFalse(org.telegram.ui.eve.admin.EveAdminConfig.isSectionVisible(org.telegram.ui.eve.admin.EveAdminConfig.SECTION_DEVICES));
        assertFalse(org.telegram.ui.ThemeActivity.isEveExamSettingSectionEnabled(org.telegram.ui.ThemeActivity.EVE_SECTION_MESSAGING_EXTRAS));
        assertEquals("EVE Help & Support", appContext.getString(R.string.EveHelpSupport));
        assertEquals("EVE FAQ", appContext.getString(R.string.EveFaq));
        assertEquals("Privacy Policy", appContext.getString(R.string.PrivacyPolicy));
        assertFalse(appContext.getString(R.string.EvePrivacyPolicyBody).contains("Telegram API"));
        assertTrue(appContext.getString(R.string.EvePrivacyPolicyBody).contains("never sends"));
        assertNotNull(org.telegram.ui.eve.notifications.EveNotificationSettingsFragment.class);
        assertTrue(org.telegram.ui.eve.notifications.EveNotificationHelper.isCategoryEnabled(
                appContext, org.telegram.ui.eve.notifications.EveNotificationHelper.PREF_EXAM_REMINDERS));
        assertFalse(org.telegram.ui.eve.notifications.EveNotificationHelper.toggleCategory(
                appContext, org.telegram.ui.eve.notifications.EveNotificationHelper.PREF_EXAM_REMINDERS));
        assertTrue(org.telegram.ui.eve.notifications.EveNotificationHelper.toggleCategory(
                appContext, org.telegram.ui.eve.notifications.EveNotificationHelper.PREF_EXAM_REMINDERS));
    }

    @Test
    public void evePremiumBrandingAndStarAnimationAssets_arePreservedWithoutTelegramMarketing() {
        assertTrue(PremiumPreviewFragment.isEvePremiumTransformEnabled());
        assertEquals("EVE Premium", appContext.getString(R.string.EvePremium));

        String subtitle = appContext.getString(R.string.EvePremiumSubtitle);
        String footer = appContext.getString(R.string.EvePremiumFooterInfo);
        assertTrue(subtitle.contains("EVE"));
        assertFalse(subtitle.toLowerCase().contains("telegram"));
        assertTrue(footer.contains("EVE Admin Control Center"));
        assertFalse(footer.toLowerCase().contains("telegram"));

        // Preserved Premium star visual icon and 3D/particle star animation classes remain intact.
        assertNotNull(ContextCompat.getDrawable(appContext, R.drawable.settings_premium));
        assertEquals("GLIconTextureView", GLIconTextureView.class.getSimpleName());
        assertEquals("StarParticlesView", StarParticlesView.class.getSimpleName());
        assertNotNull(Icon3D.class);

        Bitmap qr = EvePremiumRepository.generateQrBitmap("upi://pay?pa=eve@upi&am=199&cu=INR", 128);
        assertNotNull(qr);
        assertEquals(128, qr.getWidth());
    }

    @Test
    public void hiddenAdminItems_areRemovedFromRegularSettingsAndGatedBehindFooterPortal() {
        assertFalse(SettingsActivity.isEveSettingsItemEnabled(28));
        assertFalse(SettingsActivity.isEveSettingsItemEnabled(29));
        assertFalse(SettingsActivity.isEveSettingsItemEnabled(SettingsActivity.EVE_SETTINGS_ITEM_TELEGRAM_CHAT));
        assertTrue(SettingsActivity.isEveSettingsItemEnabled(SettingsActivity.EVE_SETTINGS_ITEM_PREMIUM));

        int taps = 0;
        long now = 1000L;
        for (int i = 0; i < 7; i++) {
            taps = SettingsActivity.advanceFooterTapCount(taps, now, now + 200L);
            now += 200L;
        }
        assertEquals(7, taps);
        assertEquals(1, SettingsActivity.advanceFooterTapCount(taps, now, now + 3000L));

        int longPresses = SettingsActivity.advanceFooterLongPressCount(1, 1000L, 2500L);
        assertEquals(2, longPresses);
        assertEquals(1, SettingsActivity.advanceFooterLongPressCount(1, 1000L, 6000L));

        assertFalse(SettingsActivity.shouldOpenAdminPortalOnFooterTap(false, 1));
        assertFalse(SettingsActivity.shouldOpenAdminPortalOnFooterTap(false, 7));
        assertTrue(SettingsActivity.shouldOpenAdminPortalOnFooterTap(true, 1));

        org.telegram.ui.eve.auth.EveUserProfile adminProfile = new org.telegram.ui.eve.auth.EveUserProfile(
                "adm-1", "EV-ADM01", "Admin", "admin@example.com", "", "google.com",
                true, org.telegram.ui.eve.auth.EveUserProfile.STATUS_ACTIVE, true, "admin_tg",
                "General", "", 1L, 1L, 1L, true);
        org.telegram.ui.eve.auth.EveUserProfile studentProfile = new org.telegram.ui.eve.auth.EveUserProfile(
                "stu-1", "EV-STU01", "Student", "student@example.com", "", "google.com",
                true, org.telegram.ui.eve.auth.EveUserProfile.STATUS_ACTIVE, false, "stu_tg",
                "General", "", 1L, 1L, 1L, true);
        assertTrue(org.telegram.ui.eve.admin.EveAdminConfig.isAdminPortalUnlockedForAccount(
                adminProfile, true, "ADMIN@example.com"));
        assertFalse(org.telegram.ui.eve.admin.EveAdminConfig.isAdminPortalUnlockedForAccount(
                adminProfile, false, "admin@example.com"));
        assertFalse(org.telegram.ui.eve.admin.EveAdminConfig.isAdminPortalUnlockedForAccount(
                adminProfile, true, "other@example.com"));
        assertFalse(org.telegram.ui.eve.admin.EveAdminConfig.isAdminPortalUnlockedForAccount(
                studentProfile, true, "student@example.com"));

        String withHandle = SettingsActivity.formatTelegramHeaderSubtitle("+917627018989", "@Any9ue");
        assertTrue(withHandle.contains("76270"));
        assertTrue(withHandle.endsWith("@Any9ue"));
        assertFalse(withHandle.contains("@gmail.com"));
        assertFalse(withHandle.contains("EV-"));
        String phoneOnly = SettingsActivity.formatTelegramHeaderSubtitle("+917627018989", "");
        assertFalse(phoneOnly.contains("@"));
        assertFalse(phoneOnly.contains("\u2022"));

        assertEquals(
                java.util.Arrays.asList("EVE Admin Control Center", "EVE Cloud Account", "Telegram Chat & Sync"),
                org.telegram.ui.eve.admin.EveHiddenAdminPortalFragment.getHiddenPortalItemTitles());
        assertEquals(
                "Access denied — Admin or Owner account required",
                org.telegram.ui.eve.admin.EveHiddenAdminPortalFragment.ACCESS_DENIED_MESSAGE);
        assertFalse(new org.telegram.ui.eve.admin.EveHiddenAdminPortalFragment().onFragmentCreate());
    }
}
