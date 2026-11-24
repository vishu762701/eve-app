/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.eve.app.ui.settings.PrivacyPreferences;
import com.eve.app.ui.settings.TelegramPrivacyDelegate;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/**
 * Batch 3 (Refs 10, 11, 12): Original Telegram PrivacySettingsActivity ported from
 * DrKLO/Telegram@21f7d109da6ab3506c9e9577f98c6a38771ab335 with UniversalRecyclerView rounded
 * sections and honest EVE security/privacy controls via TelegramPrivacyDelegate.
 */
public class PrivacySettingsActivity extends BaseFragment {

    public static final int ROW_PASSCODE = 1;
    public static final int ROW_BIOMETRIC = 2;
    public static final int ROW_LOGIN_EMAIL = 3;
    public static final int ROW_ACTIVE_SESSION = 4;

    public static final int ROW_LEADERBOARD_VISIBILITY = 10;
    public static final int ROW_PROFILE_VISIBILITY = 11;
    public static final int ROW_ACTIVITY_VISIBILITY = 12;

    public static final int ROW_CLEAR_SEARCH_HISTORY = 20;
    public static final int ROW_SCREENSHOT_PROTECTION = 21;
    public static final int ROW_PRIVACY_POLICY = 22;
    public static final int ROW_DELETE_ACCOUNT = 23;

    private TelegramPrivacyDelegate eveDelegate;
    private UniversalRecyclerView listView;

    public void setEveDelegate(TelegramPrivacyDelegate delegate) {
        this.eveDelegate = delegate;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Privacy and Security");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, this::onLongClick);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, AndroidUtilities.dp(24));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        ViewCompat.setOnApplyWindowInsetsListener(contentView, (v, insets) -> {
            if (listView != null) {
                int bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime()).bottom;
                listView.setPadding(0, 0, 0, AndroidUtilities.dp(24) + bottomInset);
            }
            return insets;
        });

        listView.adapter.update(false);
        return fragmentView = contentView;
    }

    public void refreshRows() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        eveDelegate = null;
        listView = null;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        String passcodeStatus = eveDelegate != null ? eveDelegate.getPasscodeStatus() : "Off";
        String biometricStatus = eveDelegate != null ? eveDelegate.getBiometricStatus() : "Off";
        String loginEmail = eveDelegate != null ? eveDelegate.getLoginEmail() : "Not set";
        String sessionLabel = eveDelegate != null ? eveDelegate.getActiveSessionLabel() : "1 device";
        String leaderboardVis = eveDelegate != null ? eveDelegate.getLeaderboardVisibility() : PrivacyPreferences.VISIBILITY_EVERYBODY;
        String profileVis = eveDelegate != null ? eveDelegate.getProfileVisibility() : PrivacyPreferences.VISIBILITY_EVERYBODY;
        String activityVis = eveDelegate != null ? eveDelegate.getActivityVisibility() : PrivacyPreferences.VISIBILITY_EVERYBODY;
        boolean screenshotProtection = eveDelegate == null || eveDelegate.isScreenshotProtectionEnabled();

        // Ref 12: Security card
        items.add(UItem.asHeader("Security"));
        items.add(UItem.asButton(ROW_PASSCODE, com.eve.app.R.drawable.ic_lock_header, "Passcode Lock", passcodeStatus));
        items.add(UItem.asButton(ROW_BIOMETRIC, com.eve.app.R.drawable.ic_key, "Biometric Unlock", biometricStatus));
        items.add(UItem.asButton(ROW_LOGIN_EMAIL, com.eve.app.R.drawable.ic_email, "Login Email", loginEmail));
        items.add(UItem.asButton(ROW_ACTIVE_SESSION, R.drawable.settings_devices, "Active Session", sessionLabel));
        items.add(UItem.asShadow("Protect your EVE exam account with a local passcode lock and review your signed-in account email."));

        // Ref 11: Privacy card
        items.add(UItem.asHeader("Privacy"));
        items.add(UItem.asButton(ROW_LEADERBOARD_VISIBILITY, "Leaderboard Visibility", leaderboardVis));
        items.add(UItem.asButton(ROW_PROFILE_VISIBILITY, "Profile Photo & Bio", profileVis));
        items.add(UItem.asButton(ROW_ACTIVITY_VISIBILITY, "Exam Activity & Streak", activityVis));
        items.add(UItem.asShadow("Control what other EVE learners can see on public leaderboards and shared profile links."));

        // Ref 10: Data & Account Protection card
        items.add(UItem.asHeader("Data & Protection"));
        items.add(UItem.asButton(ROW_CLEAR_SEARCH_HISTORY, "Clear Search History"));
        items.add(UItem.asCheck(ROW_SCREENSHOT_PROTECTION, "Exam Screenshot Protection").setChecked(screenshotProtection));
        items.add(UItem.asButton(ROW_PRIVACY_POLICY, LocaleController.getString(R.string.PrivacyPolicy)));
        items.add(UItem.asButton(ROW_DELETE_ACCOUNT, "Delete My Account").red());
        items.add(UItem.asShadow("Clearing search history removes local exam search suggestions. Deleting your account permanently erases your EVE profile and test history."));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item == null || eveDelegate == null) return;
        switch (item.id) {
            case ROW_LEADERBOARD_VISIBILITY:
                showVisibilityChoiceDialog(item.id, "Leaderboard Visibility");
                break;
            case ROW_PROFILE_VISIBILITY:
                showVisibilityChoiceDialog(item.id, "Profile Photo & Bio");
                break;
            case ROW_ACTIVITY_VISIBILITY:
                showVisibilityChoiceDialog(item.id, "Exam Activity & Streak");
                break;
            case ROW_CLEAR_SEARCH_HISTORY:
                showClearSearchConfirmDialog();
                break;
            default:
                eveDelegate.onRowClicked(item.id);
                refreshRows();
                break;
        }
    }

    private boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    public AlertDialog showVisibilityChoiceDialog(int rowId, String title) {
        Context context = getParentActivity();
        if (context == null) return null;
        final CharSequence[] choices = new CharSequence[] {
            PrivacyPreferences.VISIBILITY_EVERYBODY,
            PrivacyPreferences.VISIBILITY_ONLY_ME
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(title);
        builder.setItems(choices, (dialog, which) -> {
            if (eveDelegate != null && which >= 0 && which < choices.length) {
                eveDelegate.onVisibilitySelected(rowId, choices[which].toString());
                refreshRows();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        return dialog;
    }

    public AlertDialog showClearSearchConfirmDialog() {
        Context context = getParentActivity();
        if (context == null) return null;
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle("Clear Search History");
        builder.setMessage("Remove all recent exam search suggestions stored on this device?");
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton("Clear", (dialog, which) -> {
            if (eveDelegate != null) {
                eveDelegate.onClearSearchHistoryConfirmed();
            }
        });
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        return dialog;
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{HeaderCell.class, TextCell.class, TextCheckCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGray));
        return descriptions;
    }
}
