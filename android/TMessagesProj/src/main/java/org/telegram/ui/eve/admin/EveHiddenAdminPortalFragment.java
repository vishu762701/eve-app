/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.auth.EveAuthCoordinator;
import org.telegram.ui.eve.auth.EveAuthManager;
import org.telegram.ui.eve.auth.EveSignInFragment;
import org.telegram.ui.eve.auth.EveTelegramSessionManager;
import org.telegram.ui.eve.auth.EveUserProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Secret Admin & Developer Portal unlocked from the Settings footer version tap/long-press
 * after verifying an authorized Admin or Owner Gmail account.
 */
public class EveHiddenAdminPortalFragment extends BaseFragment {

    public static final String ACCESS_DENIED_MESSAGE = "Access denied — Admin or Owner account required";

    private static final int ID_ADMIN_CONTROL_CENTER = 1;
    private static final int ID_EVE_CLOUD_ACCOUNT = 2;
    private static final int ID_TELEGRAM_CHAT_SYNC = 3;

    private UniversalRecyclerView listView;

    @NonNull
    public static List<String> getHiddenPortalItemTitles() {
        return Arrays.asList(
                "EVE Admin Control Center",
                "EVE Cloud Account",
                "Telegram Chat & Sync");
    }

    /**
     * Verifies Admin or Owner Gmail authentication before opening {@link EveHiddenAdminPortalFragment}.
     * Regular non-admin Gmail accounts are rejected with {@link #ACCESS_DENIED_MESSAGE}.
     */
    public static void triggerAdminPortalFlow(@Nullable BaseFragment hostFragment) {
        if (hostFragment == null) {
            return;
        }
        Activity activity = hostFragment.getParentActivity();
        if (activity == null) {
            return;
        }
        EveAuthManager auth = EveAuthManager.getInstance();
        if (auth.isSignedInAccount() && EveAdminConfig.isAdminAvailable()) {
            hostFragment.presentFragment(new EveHiddenAdminPortalFragment());
            return;
        }

        Theme.ResourcesProvider rp = hostFragment.getResourceProvider();
        String currentEmail = auth.getCachedProfile() != null ? auth.getCachedProfile().email : "";
        String msg = auth.isSignedInAccount() && !TextUtils.isEmpty(currentEmail)
                ? ("Signed in as " + currentEmail + ", which does not have Admin or Owner permissions.\n\nSign in with an authorized Admin or Owner Gmail account to continue.")
                : "Sign in with an authorized Admin or Owner Gmail account to open the EVE Admin Portal.";

        AlertDialog dialog = new AlertDialog.Builder(activity, rp)
                .setTitle("Admin / Owner Sign-In")
                .setMessage(msg)
                .setPositiveButton("Sign in with Google", (d, w) -> launchAdminGoogleSignIn(hostFragment, activity))
                .setNegativeButton(org.telegram.messenger.LocaleController.getString(R.string.Cancel), null)
                .create();
        hostFragment.showDialog(dialog);
    }

    private static void launchAdminGoogleSignIn(@NonNull BaseFragment hostFragment, @NonNull Activity activity) {
        EveAuthManager.getInstance().signInWithGoogle(activity, new EveAuthManager.AuthCallback() {
            @Override
            public void onSuccess(@NonNull EveUserProfile synced, @NonNull EveAuthCoordinator.SyncOutcome syncOutcome) {
                if (EveAdminConfig.isServerAdminProfile(synced) || EveAdminConfig.isAdminAvailable()) {
                    BulletinFactory.of(hostFragment)
                            .createSimpleBulletin(R.raw.contact_check, "Admin verified: " + synced.email).show();
                    hostFragment.presentFragment(new EveHiddenAdminPortalFragment());
                } else {
                    BulletinFactory.of(hostFragment)
                            .createErrorBulletin(ACCESS_DENIED_MESSAGE).show();
                }
            }

            @Override
            public void onError(@NonNull String message, boolean accountDisabled) {
                BulletinFactory.of(hostFragment)
                        .createErrorBulletin(!TextUtils.isEmpty(message) ? message : ACCESS_DENIED_MESSAGE).show();
            }
        });
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
                "Admin Portal",
                "Verified Admin & Owner Access",
                true,
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
                null);
        listView = chrome.listView;
        fragmentView = chrome.rootView;
        return chrome.rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!EveAdminConfig.isAdminAvailable()) {
            finishFragment();
            return;
        }
        updateList();
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);
        items.add(UItem.asHeader("Admin & System Controls"));

        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_ADMIN_CONTROL_CENTER,
                0xFF3D8BEB, 0xFF2A6FD9,
                R.drawable.settings_devices,
                "EVE Admin Control Center",
                getString(R.string.EveAdminControlCenterSubtitle)));

        EveAuthManager auth = EveAuthManager.getInstance();
        EveUserProfile profile = auth.getCachedProfile();
        String cloudSubtitle = (auth.isSignedInAccount() && profile != null && !profile.email.isEmpty())
                ? (profile.email + (!profile.eveId.isEmpty() ? " • " + profile.eveId : ""))
                : "Switch or refresh Admin Gmail account";
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_EVE_CLOUD_ACCOUNT,
                IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                R.drawable.settings_account,
                "EVE Cloud Account",
                cloudSubtitle));

        String tgSubtitle = EveTelegramSessionManager.getInstance().getConnectionStatusSubtitle(currentAccount);
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_TELEGRAM_CHAT_SYNC,
                IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                R.drawable.settings_chat,
                "Telegram Chat & Sync",
                tgSubtitle));

        items.add(UItem.asShadow("Hidden owner portal. Switch Admin Gmail or manage MTProto session safely."));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_ADMIN_CONTROL_CENTER:
                if (EveAdminConfig.isAdminAvailable()) {
                    presentFragment(new EveAdminControlCenterActivity());
                } else {
                    BulletinFactory.of(this).createErrorBulletin(ACCESS_DENIED_MESSAGE).show();
                }
                break;
            case ID_EVE_CLOUD_ACCOUNT:
                openAdminCloudAccountDialog();
                break;
            case ID_TELEGRAM_CHAT_SYNC:
                openTelegramChatSyncDialog();
                break;
        }
    }

    private void openAdminCloudAccountDialog() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        EveAuthManager auth = EveAuthManager.getInstance();
        EveUserProfile profile = auth.getCachedProfile();
        StringBuilder details = new StringBuilder();
        if (profile != null && !profile.email.isEmpty()) {
            details.append("Email: ").append(profile.email).append("\n");
        }
        if (profile != null && !profile.eveId.isEmpty()) {
            details.append("EVE ID: ").append(profile.eveId).append("\n");
        }
        details.append("Role: ").append(EveAdminConfig.isServerAdminProfile(profile) ? "EVE Administrator" : "Student");

        showDialog(new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle("EVE Cloud Account")
                .setMessage(details.toString())
                .setPositiveButton("Switch Gmail", (d, w) -> auth.signInWithGoogle(activity, new EveAuthManager.AuthCallback() {
                    @Override
                    public void onSuccess(@NonNull EveUserProfile synced, @NonNull EveAuthCoordinator.SyncOutcome syncOutcome) {
                        if (!EveAdminConfig.isServerAdminProfile(synced)) {
                            BulletinFactory.of(EveHiddenAdminPortalFragment.this)
                                    .createErrorBulletin(ACCESS_DENIED_MESSAGE).show();
                            finishFragment();
                            return;
                        }
                        BulletinFactory.of(EveHiddenAdminPortalFragment.this)
                                .createSimpleBulletin(R.raw.info, "Admin session updated: " + synced.email).show();
                        updateList();
                    }

                    @Override
                    public void onError(@NonNull String message, boolean accountDisabled) {
                        BulletinFactory.of(EveHiddenAdminPortalFragment.this).createErrorBulletin(message).show();
                    }
                }))
                .setNeutralButton(getString(R.string.LogOut), (d, w) -> auth.signOut(activity, () -> {
                    presentFragment(new EveSignInFragment(), true);
                }))
                .setNegativeButton(getString(R.string.Cancel), null)
                .create());
    }

    private void openTelegramChatSyncDialog() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        EveTelegramSessionManager tgManager = EveTelegramSessionManager.getInstance();
        if (!tgManager.isRealMtprotoSession(currentAccount)) {
            tgManager.launchTelegramLogin(this);
            return;
        }
        String statusText = tgManager.getConnectionStatusSubtitle(currentAccount);
        showDialog(new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle("Telegram Chat & Sync")
                .setMessage(statusText + "\n\nTelegram Chats, groups, channels, and polls are active alongside your EVE Exams account.")
                .setPositiveButton("Open Chats", (d, w) -> {
                    if (parentLayout != null && parentLayout.getFragmentStack() != null) {
                        for (int i = parentLayout.getFragmentStack().size() - 1; i >= 0; i--) {
                            BaseFragment f = parentLayout.getFragmentStack().get(i);
                            if (f instanceof MainTabsActivity) {
                                ((MainTabsActivity) f).onTelegramSessionStateChanged(true);
                                if (f != this) {
                                    finishFragment();
                                }
                                return;
                            }
                        }
                    }
                    Bundle args = new Bundle();
                    args.putBoolean("eve_exams_mode", false);
                    presentFragment(new DialogsActivity(args));
                })
                .setNeutralButton("Disconnect", (d, w) -> tgManager.confirmAndDisconnect(this, () -> {
                    updateList();
                    BulletinFactory.of(this)
                            .createSimpleBulletin(R.raw.info, "Telegram Chat disconnected. EVE Exams remain active.")
                            .show();
                }))
                .setNegativeButton(getString(R.string.Cancel), null)
                .create());
    }
}
