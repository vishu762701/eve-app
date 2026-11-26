package org.telegram.ui.eve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.LanguageSelectActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * Behavioral regression tests for EVE dual-language support (English and हिन्दी only)
 * and prevention of cloud langpack overrides over EVE branding/titles.
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
    public void localeController_allowsOnlyEnglishAndHindi_andDisablesCloudOverrides() {
        assertFalse(BuildVars.USE_CLOUD_STRINGS);
        assertFalse(LocaleController.isEnglishOnlyMode());
        assertTrue(LocaleController.isEveRestrictedLanguageMode());
        assertEquals("English", LocaleController.getCurrentLanguageName());

        LocaleController.LocaleInfo english = makeLocale("en", "English");
        LocaleController.LocaleInfo hindi = makeLocale("hi", "हिन्दी");
        LocaleController.LocaleInfo arabic = makeLocale("ar", "Arabic");
        LocaleController.LocaleInfo belarusian = makeLocale("be", "Belarusian");
        LocaleController.LocaleInfo chineseSimp = makeLocale("zh_CN", "Chinese (Simplified)");
        LocaleController.LocaleInfo croatian = makeLocale("hr", "Croatian");

        assertTrue(LocaleController.isAllowedApplicationLocale(english));
        assertTrue(LocaleController.isAllowedApplicationLocale(hindi));
        assertFalse(LocaleController.isAllowedApplicationLocale(arabic));
        assertFalse(LocaleController.isAllowedApplicationLocale(belarusian));
        assertFalse(LocaleController.isAllowedApplicationLocale(chineseSimp));
        assertFalse(LocaleController.isAllowedApplicationLocale(croatian));

        ArrayList<LocaleController.LocaleInfo> filtered = LanguageSelectActivity.filterSelectableLanguages(
                Arrays.asList(arabic, belarusian, hindi, english, chineseSimp, croatian));
        assertEquals(2, filtered.size());
        assertEquals("en", filtered.get(0).shortName);
        assertEquals("English", filtered.get(0).nameEnglish);
        assertEquals("hi", filtered.get(1).shortName);
        assertEquals("हिन्दी", filtered.get(1).name);
    }

    @Test
    public void hindiTranslations_existForEveNavigationAndExamControls() {
        assertEquals("Language", appContext.getString(R.string.SettingsLanguage));
        assertEquals("Show Translate Button", appContext.getString(R.string.ShowTranslateButton));
        assertEquals("Do Not Translate", appContext.getString(R.string.DoNotTranslate));

        Configuration hiConfig = new Configuration(appContext.getResources().getConfiguration());
        hiConfig.setLocale(new Locale("hi"));
        Context hiContext = appContext.createConfigurationContext(hiConfig);

        assertEquals("परीक्षा", hiContext.getString(R.string.MainTabsExams));
        assertEquals("परीक्षा सेटिंग्स", hiContext.getString(R.string.ChatSettings));
        assertEquals("सबमिट करें", hiContext.getString(R.string.EveSubmitTest));
        assertEquals("लीडरबोर्ड", hiContext.getString(R.string.EveLeaderboard));
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
