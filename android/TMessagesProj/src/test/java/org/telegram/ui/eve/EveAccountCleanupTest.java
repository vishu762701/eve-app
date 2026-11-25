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
import org.telegram.messenger.R;
import org.telegram.ui.LoginActivity;
import org.telegram.ui.LogoutActivity;
import org.telegram.ui.UserInfoActivity;

/**
 * Behavioral regression tests for Mission 3 (Account screen cleanup).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
public class EveAccountCleanupTest {

    private Context appContext;

    @Before
    public void setUp() {
        appContext = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = appContext;
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
    }

    @Test
    public void removedAccountOptions_areDisabledWhileCoreOptionsRemainEnabled() {
        assertFalse(UserInfoActivity.isEveAccountOptionEnabled(UserInfoActivity.ACCOUNT_OPTION_CHANNEL));
        assertFalse(UserInfoActivity.isEveAccountOptionEnabled(UserInfoActivity.ACCOUNT_OPTION_CHAT_AUTOMATION));
        assertFalse(UserInfoActivity.isEveAccountOptionEnabled(UserInfoActivity.ACCOUNT_OPTION_ADD_ACCOUNT));

        assertTrue(UserInfoActivity.isEveAccountOptionEnabled(UserInfoActivity.ACCOUNT_OPTION_PHONE));
        assertTrue(UserInfoActivity.isEveAccountOptionEnabled(UserInfoActivity.ACCOUNT_OPTION_USERNAME));
        assertTrue(UserInfoActivity.isEveAccountOptionEnabled(UserInfoActivity.ACCOUNT_OPTION_BIRTHDAY));
        assertTrue(UserInfoActivity.isEveAccountOptionEnabled(UserInfoActivity.ACCOUNT_OPTION_LOGOUT));
    }

    @Test
    public void preservedAccountStringsAndClasses_remainIntact() throws Exception {
        assertEquals("Account", appContext.getString(R.string.EditAccountInfo2));
        assertEquals("Your Info", appContext.getString(R.string.EditAccountInfoHeader));
        assertEquals("Bio", appContext.getString(R.string.EditProfileBioHint2));
        assertEquals("Log out", appContext.getString(R.string.LogOut));

        // Original Telegram classes must not be deleted even though Account entries are hidden.
        assertNotNull(Class.forName("org.telegram.ui.ChooseChannelFragment"));
        assertNotNull(LoginActivity.class);
        assertEquals("LogoutActivity", LogoutActivity.class.getSimpleName());
        assertNotNull(UserInfoActivity.AdminedChannelsFetcher.class);
    }
}
