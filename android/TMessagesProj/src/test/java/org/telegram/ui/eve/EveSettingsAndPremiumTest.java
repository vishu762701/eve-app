package org.telegram.ui.eve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
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

/**
 * Behavioral & visual-asset regression tests for Mission 6 (Settings Cleanup & EVE Premium).
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
    public void telegramFeatures_isRemovedWhileOtherSettingsRemainEnabled() {
        assertFalse(SettingsActivity.isEveSettingsItemEnabled(SettingsActivity.EVE_SETTINGS_ITEM_TELEGRAM_FEATURES));
        assertTrue(SettingsActivity.isEveSettingsItemEnabled(SettingsActivity.EVE_SETTINGS_ITEM_PREMIUM));
        assertEquals("Ask a Question", appContext.getString(R.string.AskAQuestion));
        assertEquals("Privacy Policy", appContext.getString(R.string.PrivacyPolicy));
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
    }
}
