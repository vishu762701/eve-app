package org.telegram.ui.eve.admin;

import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;

/**
 * Telegram-native Content, Home, Notifications & Moderation Hub for EVE Admin Control Center.
 * Every row navigates to a full-screen Telegram-native management screen.
 */
public class EveAdminContentAndCommsFragment extends BaseFragment {

    private static final int ID_BANNERS = 101;
    private static final int ID_POLLS = 103;
    private static final int ID_APP_CONTENT_ABOUT = 104;
    private static final int ID_APP_CONTENT_PRIVACY = 105;
    private static final int ID_APP_CONTENT_TERMS = 106;

    private static final int ID_SEND_BROADCAST = 201;
    private static final int ID_BROADCAST_HISTORY = 202;
    private static final int ID_FLAGGED_REPORTS = 203;
    private static final int ID_FEEDBACK_MESSAGES = 204;
    private static final int ID_FEEDBACK_POSTS = 205;

    private final EveAdminModels.AdminProfile profile;
    private UniversalRecyclerView listView;

    public EveAdminContentAndCommsFragment(EveAdminModels.AdminProfile profile) {
        this.profile = profile;
    }

    @Override
    public boolean onFragmentCreate() {
        return EveAdminConfig.isAdminAvailable() && super.onFragmentCreate();
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    protected boolean useFadeDrawableForActionBar() {
        return false;
    }

    @Nullable
    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Content & Moderation",
                null,
                true,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        }
                    }
                },
                null
        );
        listView = chrome.listView;
        fragmentView = chrome.rootView;
        return chrome.rootView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_CONTENT)) {
            items.add(UItem.asHeader("Home & Pages"));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_BANNERS,
                    IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                    R.drawable.msg_photos,
                    "Home Screen Banners",
                    "View, edit links, reorder or remove banners",
                    null));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_APP_CONTENT_ABOUT,
                    IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                    R.drawable.msg_info,
                    "Edit About Page",
                    "Update app About page copy",
                    null));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_APP_CONTENT_PRIVACY,
                    IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                    R.drawable.settings_privacy,
                    "Edit Privacy Policy Content",
                    "Update privacy policy served from backend",
                    null));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_APP_CONTENT_TERMS,
                    IconBackgroundColors.BLUE_DEEP.top, IconBackgroundColors.BLUE_DEEP.bottom,
                    R.drawable.settings_policy,
                    "Edit Terms of Service",
                    "Update student terms of service copy",
                    null));
            items.add(UItem.asShadow(null));
        }

        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MODERATE_FEEDBACK)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_SEND_BROADCASTS)) {
            items.add(UItem.asHeader("Broadcasts, Polls & Moderation"));
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_SEND_BROADCASTS)) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_SEND_BROADCAST,
                        IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                        R.drawable.msg_send,
                        "Send Broadcast Notification",
                        "Compose & deliver via EVE FCM & Announcements",
                        null));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_BROADCAST_HISTORY,
                        IconBackgroundColors.BLUE_ALT.top, IconBackgroundColors.BLUE_ALT.bottom,
                        R.drawable.msg_log,
                        "Broadcast History",
                        "Inspect or delete sent notifications",
                        null));
            }
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MODERATE_FEEDBACK)) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_POLLS,
                        IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                        R.drawable.msg_list,
                        "Manage Student Polls",
                        "Create Telegram-style polls & inspect live votes",
                        null));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_FLAGGED_REPORTS,
                        IconBackgroundColors.ORANGE_DEEP.top, IconBackgroundColors.ORANGE_DEEP.bottom,
                        R.drawable.msg_report,
                        "Flagged Questions Inbox",
                        "Filter, dismiss, or delete student reports",
                        null));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_FEEDBACK_MESSAGES,
                        IconBackgroundColors.BLUE_LIGHT.top, IconBackgroundColors.BLUE_LIGHT.bottom,
                        R.drawable.settings_ask,
                        "Student Feedback Messages",
                        "Read, reply, mark read, or delete",
                        null));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_FEEDBACK_POSTS,
                        IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                        R.drawable.settings_faq,
                        "Feedback Posts",
                        "Create or remove feedback topics",
                        null));
            }
            items.add(UItem.asShadow(null));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_BANNERS:
                presentFragment(new EveAdminBannersFragment());
                break;
            case ID_POLLS:
                presentFragment(new EveAdminPollsFragment());
                break;
            case ID_APP_CONTENT_ABOUT:
                presentFragment(new EveAdminContentEditorFragment(
                        EveAdminContentEditorFragment.MODE_ABOUT, "About EVE"));
                break;
            case ID_APP_CONTENT_PRIVACY:
                presentFragment(new EveAdminContentEditorFragment(
                        EveAdminContentEditorFragment.MODE_PRIVACY, "Privacy Policy"));
                break;
            case ID_APP_CONTENT_TERMS:
                presentFragment(new EveAdminContentEditorFragment(
                        EveAdminContentEditorFragment.MODE_TERMS, "Terms of Service"));
                break;
            case ID_SEND_BROADCAST:
                presentFragment(new EveAdminBroadcastsFragment(true));
                break;
            case ID_BROADCAST_HISTORY:
                presentFragment(new EveAdminBroadcastsFragment(false));
                break;
            case ID_FLAGGED_REPORTS:
                presentFragment(new EveAdminFlaggedReportsFragment());
                break;
            case ID_FEEDBACK_MESSAGES:
            case ID_FEEDBACK_POSTS:
                presentFragment(new EveAdminFeedbackFragment());
                break;
        }
    }
}
