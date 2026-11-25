package org.telegram.ui.eve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import org.telegram.ui.LanguageSelectActivity;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Behavioral regression tests for Mission 5 (English-only language enforcement).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
public class EveEnglishOnlyLanguageTest {

    private Context appContext;

    @Before
    public void setUp() {
        appContext = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = appContext;
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
    }

    @Test
    public void localeController_locksApplicationLanguageToEnglish() {
        assertTrue(LocaleController.isEnglishOnlyMode());
        assertEquals("English", LocaleController.getCurrentLanguageName());

        LocaleController.LocaleInfo english = makeLocale("en", "English");
        LocaleController.LocaleInfo arabic = makeLocale("ar", "Arabic");
        LocaleController.LocaleInfo belarusian = makeLocale("be", "Belarusian");
        LocaleController.LocaleInfo catalan = makeLocale("ca", "Catalan");
        LocaleController.LocaleInfo chineseSimp = makeLocale("zh_CN", "Chinese (Simplified)");
        LocaleController.LocaleInfo chineseTrad = makeLocale("zh_TW", "Chinese (Traditional)");
        LocaleController.LocaleInfo croatian = makeLocale("hr", "Croatian");

        assertTrue(LocaleController.isAllowedApplicationLocale(english));
        assertFalse(LocaleController.isAllowedApplicationLocale(arabic));
        assertFalse(LocaleController.isAllowedApplicationLocale(belarusian));
        assertFalse(LocaleController.isAllowedApplicationLocale(catalan));
        assertFalse(LocaleController.isAllowedApplicationLocale(chineseSimp));
        assertFalse(LocaleController.isAllowedApplicationLocale(chineseTrad));
        assertFalse(LocaleController.isAllowedApplicationLocale(croatian));

        ArrayList<LocaleController.LocaleInfo> filtered = LanguageSelectActivity.filterSelectableLanguages(
                Arrays.asList(arabic, belarusian, catalan, english, chineseSimp, chineseTrad, croatian));
        assertEquals(1, filtered.size());
        assertEquals("en", filtered.get(0).shortName);
        assertEquals("English", filtered.get(0).nameEnglish);
    }

    @Test
    public void translationStringsAndSettingsLanguageTitle_remainIntact() {
        assertEquals("Language", appContext.getString(R.string.SettingsLanguage));
        assertEquals("Show Translate Button", appContext.getString(R.string.ShowTranslateButton));
        assertEquals("Do Not Translate", appContext.getString(R.string.DoNotTranslate));
    }

    private static LocaleController.LocaleInfo makeLocale(String code, String nameEnglish) {
        LocaleController.LocaleInfo info = new LocaleController.LocaleInfo();
        info.shortName = code;
        info.pluralLangCode = code;
        info.name = nameEnglish;
        info.nameEnglish = nameEnglish;
        info.builtIn = true;
        return info;
    }
}
