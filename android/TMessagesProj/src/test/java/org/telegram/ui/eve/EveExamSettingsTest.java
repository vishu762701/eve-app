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
import org.telegram.messenger.R;
import org.telegram.ui.ThemeActivity;

/**
 * Behavioral & resource regression tests for Mission 4 (Chat Settings -> Exam Settings).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
public class EveExamSettingsTest {

    private Context appContext;

    @Before
    public void setUp() {
        appContext = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = appContext;
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
    }

    @Test
    public void navigationTitleAndEntry_areRenamedToExamSettings() {
        assertEquals("Exam Settings", appContext.getString(R.string.ChatSettings));
        assertEquals("Exam Settings", appContext.getString(R.string.SettingsChat));
    }

    @Test
    public void removedExamSettingSections_areDisabledWhilePreservedSectionsRemainEnabled() {
        assertFalse(ThemeActivity.isEveExamSettingSectionEnabled(ThemeActivity.EVE_SECTION_APP_ICON));
        assertFalse(ThemeActivity.isEveExamSettingSectionEnabled(ThemeActivity.EVE_SECTION_CHAT_LIST_SWIPE));

        assertTrue(ThemeActivity.isEveExamSettingSectionEnabled(ThemeActivity.EVE_SECTION_MESSAGE_CORNERS));
        assertTrue(ThemeActivity.isEveExamSettingSectionEnabled(ThemeActivity.EVE_SECTION_CHAT_LIST_VIEW));
        assertTrue(ThemeActivity.isEveExamSettingSectionEnabled(ThemeActivity.EVE_SECTION_MESSAGE_TEXT_SIZE));
        assertTrue(ThemeActivity.isEveExamSettingSectionEnabled(ThemeActivity.EVE_SECTION_COMPACT_MODE));
        assertTrue(ThemeActivity.isEveExamSettingSectionEnabled(ThemeActivity.EVE_SECTION_WALLPAPER));
        assertTrue(ThemeActivity.isEveExamSettingSectionEnabled(ThemeActivity.EVE_SECTION_NAME_COLOR));
        assertTrue(ThemeActivity.isEveExamSettingSectionEnabled(ThemeActivity.EVE_SECTION_COLOR_THEME));
        assertTrue(ThemeActivity.isEveExamSettingSectionEnabled(ThemeActivity.EVE_SECTION_AUTO_NIGHT_MODE));
    }
}
