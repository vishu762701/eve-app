package org.telegram.ui.eve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.drawable.Drawable;
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
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.LauncherIconController;

/**
 * Behavioral & resource regression tests for Mission 1 (EVE Brand Identity).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
public class EveBrandIdentityTest {

    private Context appContext;

    @Before
    public void setUp() {
        appContext = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = appContext;
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
    }

    @Test
    public void appDisplayStrings_useEveIdentityAndDisclosure() {
        assertEquals("EVE", appContext.getString(R.string.AppName));
        assertEquals("EVE", appContext.getString(R.string.AppNameBeta));
        assertEquals("EVE", appContext.getString(R.string.Page1Title));
        assertEquals("Get Started", appContext.getString(R.string.StartMessaging));

        String page1Message = appContext.getString(R.string.Page1Message);
        assertTrue(page1Message.contains("EVE"));
        assertTrue(page1Message.contains("Telegram API"));
        assertTrue(page1Message.toLowerCase().contains("not an official telegram"));

        String disclosure = appContext.getString(R.string.EveThirdPartyApiDisclosure);
        assertTrue(disclosure.contains("Telegram API"));
        assertTrue(disclosure.toLowerCase().contains("not an official telegram"));

        String version = LocaleController.formatString(R.string.TelegramVersion, "v1.0 (1)");
        assertTrue(version.startsWith("EVE for Android"));
        assertFalse(version.contains("Telegram for Android"));
    }

    @Test
    public void launcherAndSplashDrawables_useEveAssets() {
        assertEquals(R.drawable.ic_eve_launcher_background, LauncherIconController.LauncherIcon.DEFAULT.background);
        assertEquals(R.drawable.ic_eve_launcher_foreground, LauncherIconController.LauncherIcon.DEFAULT.foreground);

        Drawable adaptiveBg = ContextCompat.getDrawable(appContext, R.drawable.ic_eve_launcher_background);
        Drawable adaptiveFg = ContextCompat.getDrawable(appContext, R.drawable.ic_eve_launcher_foreground);
        Drawable adaptiveMono = ContextCompat.getDrawable(appContext, R.drawable.ic_eve_launcher_monochrome);
        Drawable splash = ContextCompat.getDrawable(appContext, R.drawable.eve_splash_320);
        Drawable legacyEve = ContextCompat.getDrawable(appContext, R.drawable.ic_eve_launcher);

        assertNotNull(adaptiveBg);
        assertNotNull(adaptiveFg);
        assertNotNull(adaptiveMono);
        assertNotNull(splash);
        assertNotNull(legacyEve);
    }
}
