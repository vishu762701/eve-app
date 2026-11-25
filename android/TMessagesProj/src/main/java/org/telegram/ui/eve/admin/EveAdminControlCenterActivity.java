package org.telegram.ui.eve.admin;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.EveCrashDiagnostics;
import org.telegram.messenger.EveInspectionMode;
import org.telegram.messenger.EveLoadingTracker;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.NativeLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.camera.CameraView;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.DataSettingsActivity;
import org.telegram.ui.DebugSettingsActivity;
import org.telegram.ui.FiltersSetupActivity;
import org.telegram.ui.LanguageSelectActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.LiteModeSettingsActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.NotificationsSettingsActivity;
import org.telegram.ui.PrivacySettingsActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.QrShare.QrShareActivity;
import org.telegram.ui.SessionsActivity;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.ThemeActivity;
import org.telegram.ui.UserInfoActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DEBUG-only EVE Owner Admin Control Center.
 *
 * Provides functional local inspection controls across all retained Telegram-derived
 * modules without reviving any of the 13 removed feature ecosystems and without
 * granting any production backend privileges.
 *
 * Strictly disabled in release builds (EveAdminConfig.isAdminAvailable() == false).
 */
public class EveAdminControlCenterActivity extends BaseFragment {

    private static final int ID_TOGGLE_ACCOUNT = 101;
    private static final int ID_TOGGLE_CHAT_SETTINGS = 102;
    private static final int ID_TOGGLE_PRIVACY = 103;
    private static final int ID_TOGGLE_NOTIFICATIONS = 104;
    private static final int ID_TOGGLE_DATA_STORAGE = 105;
    private static final int ID_TOGGLE_CHAT_FOLDERS = 106;
    private static final int ID_TOGGLE_DEVICES = 107;
    private static final int ID_TOGGLE_POWER_SAVING = 108;
    private static final int ID_TOGGLE_LANGUAGE = 109;
    private static final int ID_RESET_MODULES = 110;
    private static final int ID_VIEW_REMOVED_MISSIONS = 111;

    private static final int ID_NAV_CHATS = 201;
    private static final int ID_NAV_PROFILE = 202;
    private static final int ID_NAV_SETTINGS = 203;
    private static final int ID_NAV_ACCOUNT = 204;
    private static final int ID_NAV_CHAT_SETTINGS = 205;
    private static final int ID_NAV_PRIVACY = 206;
    private static final int ID_NAV_NOTIFICATIONS = 207;
    private static final int ID_NAV_DATA = 208;
    private static final int ID_NAV_FOLDERS = 209;
    private static final int ID_NAV_DEVICES = 210;
    private static final int ID_NAV_POWER = 211;
    private static final int ID_NAV_LANGUAGE = 212;
    private static final int ID_NAV_DEBUG_SETTINGS = 213;
    private static final int ID_NAV_DIAGNOSTICS = 214;
    private static final int ID_NAV_RESET = 215;

    private static final int ID_THEME_LIGHT = 301;
    private static final int ID_THEME_DARK = 302;
    private static final int ID_THEME_SYSTEM = 303;
    private static final int ID_THEME_PRESET = 304;
    private static final int ID_THEME_RESET = 305;

    private static final int ID_ANIM_PROBE_RLOTTIE = 401;
    private static final int ID_ANIM_TOGGLE_EMOJI = 402;
    private static final int ID_ANIM_TOGGLE_STICKERS = 403;
    private static final int ID_ANIM_DIAGNOSTICS = 404;

    private static final int ID_QR_GENERATE = 501;
    private static final int ID_QR_SCAN = 502;
    private static final int ID_CAMERA_DIAGNOSTICS = 503;

    private static final int ID_DIAG_CRASH = 601;
    private static final int ID_DIAG_LOADING = 602;
    private static final int ID_DIAG_EVENTS = 603;
    private static final int ID_DIAG_EXPORT = 604;
    private static final int ID_DIAG_CLEAR = 605;

    private static final int ID_INSP_RESET_PREVIEW = 701;
    private static final int ID_INSP_CACHE_INSPECT = 702;
    private static final int ID_INSP_CACHE_CLEAR = 703;

    private UniversalRecyclerView listView;

    @Override
    public boolean onFragmentCreate() {
        if (!EveAdminConfig.isAdminAvailable()) {
            return false;
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("EVE Admin Control Center");
        actionBar.setSubtitle("DEBUG Local Inspection Only");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = frameLayout;
        return frameLayout;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        Context ctx = getContext();

        // A. MODULE CONTROL
        items.add(UItem.asHeader("A. Module Control (Settings Visibility)"));
        items.add(UItem.asCheck(ID_TOGGLE_ACCOUNT, "Show Account").setChecked(EveAdminConfig.isSectionVisible(EveAdminConfig.SECTION_ACCOUNT)));
        items.add(UItem.asCheck(ID_TOGGLE_CHAT_SETTINGS, "Show Chat Settings").setChecked(EveAdminConfig.isSectionVisible(EveAdminConfig.SECTION_CHAT_SETTINGS)));
        items.add(UItem.asCheck(ID_TOGGLE_PRIVACY, "Show Privacy & Security").setChecked(EveAdminConfig.isSectionVisible(EveAdminConfig.SECTION_PRIVACY)));
        items.add(UItem.asCheck(ID_TOGGLE_NOTIFICATIONS, "Show Notifications").setChecked(EveAdminConfig.isSectionVisible(EveAdminConfig.SECTION_NOTIFICATIONS)));
        items.add(UItem.asCheck(ID_TOGGLE_DATA_STORAGE, "Show Data and Storage").setChecked(EveAdminConfig.isSectionVisible(EveAdminConfig.SECTION_DATA_STORAGE)));
        items.add(UItem.asCheck(ID_TOGGLE_CHAT_FOLDERS, "Show Chat Folders").setChecked(EveAdminConfig.isSectionVisible(EveAdminConfig.SECTION_CHAT_FOLDERS)));
        items.add(UItem.asCheck(ID_TOGGLE_DEVICES, "Show Devices").setChecked(EveAdminConfig.isSectionVisible(EveAdminConfig.SECTION_DEVICES)));
        items.add(UItem.asCheck(ID_TOGGLE_POWER_SAVING, "Show Power Saving").setChecked(EveAdminConfig.isSectionVisible(EveAdminConfig.SECTION_POWER_SAVING)));
        items.add(UItem.asCheck(ID_TOGGLE_LANGUAGE, "Show Language").setChecked(EveAdminConfig.isSectionVisible(EveAdminConfig.SECTION_LANGUAGE)));
        items.add(UItem.asButton(ID_RESET_MODULES, "Restore All Retained Settings Sections"));
        items.add(UItem.asButton(ID_VIEW_REMOVED_MISSIONS, "Removed Ecosystems Status (Read-Only)", "6 Missions"));
        items.add(UItem.asShadow("Toggles hide/show retained Settings rows live. Removed features from Missions 01-06 cannot be revived."));

        // B. NAVIGATION CONTROL
        items.add(UItem.asHeader("B. Navigation Control"));
        items.add(UItem.asButton(ID_NAV_CHATS, "Open Chats Tab"));
        items.add(UItem.asButton(ID_NAV_PROFILE, "Open Profile Screen (Local Inspector)"));
        items.add(UItem.asButton(ID_NAV_SETTINGS, "Open Settings Screen"));
        items.add(UItem.asButton(ID_NAV_ACCOUNT, "Open Account (UserInfoActivity)"));
        items.add(UItem.asButton(ID_NAV_CHAT_SETTINGS, "Open Chat Settings (ThemeActivity)"));
        items.add(UItem.asButton(ID_NAV_PRIVACY, "Open Privacy & Security"));
        items.add(UItem.asButton(ID_NAV_NOTIFICATIONS, "Open Notifications"));
        items.add(UItem.asButton(ID_NAV_DATA, "Open Data & Storage"));
        items.add(UItem.asButton(ID_NAV_FOLDERS, "Open Chat Folders"));
        items.add(UItem.asButton(ID_NAV_DEVICES, "Open Devices"));
        items.add(UItem.asButton(ID_NAV_POWER, "Open Power Saving"));
        items.add(UItem.asButton(ID_NAV_LANGUAGE, "Open Language"));
        items.add(UItem.asButton(ID_NAV_DEBUG_SETTINGS, "Open Telegram Debug Settings"));
        items.add(UItem.asButton(ID_NAV_DIAGNOSTICS, "Navigation Diagnostics"));
        items.add(UItem.asButton(ID_NAV_RESET, "Reset Navigation to Chats"));
        items.add(UItem.asShadow(null));

        // C. THEME CONTROL
        String currentThemeName = Theme.getActiveTheme() != null ? Theme.getActiveTheme().getKey() : "Unknown";
        String autoNightMode = Theme.selectedAutoNightType == Theme.AUTO_NIGHT_TYPE_SYSTEM ? "System"
                : (Theme.isCurrentThemeDark() ? "Dark" : "Light");
        items.add(UItem.asHeader("C. Theme Control (" + currentThemeName + " • " + autoNightMode + ")"));
        items.add(UItem.asButton(ID_THEME_LIGHT, "Switch to Light Mode (Animated Reveal)"));
        items.add(UItem.asButton(ID_THEME_DARK, "Switch to Dark Mode (Animated Reveal)"));
        items.add(UItem.asButton(ID_THEME_SYSTEM, "Enable System Day/Night Mode"));
        items.add(UItem.asButton(ID_THEME_PRESET, "Select Built-In Theme Preset", currentThemeName));
        items.add(UItem.asButton(ID_THEME_RESET, "Reset Theme to Default (Blue)"));
        items.add(UItem.asShadow(null));

        // D. ANIMATION CONTROL
        items.add(UItem.asHeader("D. Animation Control"));
        items.add(UItem.asButton(ID_ANIM_PROBE_RLOTTIE, "Run Live RLottie Native Probe", NativeLoader.loaded() ? "Native OK" : "Not Loaded"));
        items.add(UItem.asCheck(ID_ANIM_TOGGLE_EMOJI, "Animated Emoji Enabled").setChecked(LiteMode.isEnabled(LiteMode.FLAG_ANIMATED_EMOJI_CHAT)));
        items.add(UItem.asCheck(ID_ANIM_TOGGLE_STICKERS, "Animated Stickers Enabled").setChecked(LiteMode.isEnabled(LiteMode.FLAG_ANIMATED_STICKERS_CHAT)));
        items.add(UItem.asButton(ID_ANIM_DIAGNOSTICS, "Animation & Motion Diagnostics"));
        items.add(UItem.asShadow(null));

        // E. CAMERA AND QR CONTROL
        boolean hasCamera = ctx != null && ctx.getPackageManager() != null
                && ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        boolean hasCamPerm = ctx != null && (Build.VERSION.SDK_INT < 23
                || ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
        items.add(UItem.asHeader("E. Camera & QR Control"));
        items.add(UItem.asButton(ID_QR_GENERATE, "Open Local Inspection QR Generator", "Offline QR"));
        items.add(UItem.asButton(ID_QR_SCAN, "Open QR Code Scanner", hasCamPerm ? "Perm Granted" : "Needs Perm"));
        items.add(UItem.asButton(ID_CAMERA_DIAGNOSTICS, "Camera Hardware & Permission Diagnostics", hasCamera ? "Camera OK" : "No Camera"));
        items.add(UItem.asShadow(null));

        // F. APPLICATION DIAGNOSTICS
        int nonTerminal = EveLoadingTracker.countNonTerminal();
        items.add(UItem.asHeader("F. Application Diagnostics"));
        items.add(UItem.asButton(ID_DIAG_CRASH, "Crash Reports & ApplicationExitInfo"));
        items.add(UItem.asButton(ID_DIAG_LOADING, "Loading States Viewer", nonTerminal == 0 ? "All Terminal" : nonTerminal + " Active"));
        items.add(UItem.asButton(ID_DIAG_EVENTS, "Recent Nav / Resource / Network Events", String.valueOf(EveCrashDiagnostics.getRecentEvents().size())));
        items.add(UItem.asButton(ID_DIAG_EXPORT, "Export Redacted Diagnostic Report"));
        items.add(UItem.asButton(ID_DIAG_CLEAR, "Clear Stored Diagnostic Reports"));
        items.add(UItem.asShadow("Native libtmessages.49 loaded: " + NativeLoader.loaded() + " • Account activated: " + UserConfig.getInstance(currentAccount).isClientActivated()));

        // G. INSPECTION CONTROLS
        EveAdminConfig.CacheStats stats = EveAdminConfig.inspectCache(ctx);
        String cacheSummary = String.format(Locale.US, "%d files (%.1f MB)", stats.fileCount, stats.totalBytes / (1024f * 1024f));
        items.add(UItem.asHeader("G. Inspection Controls"));
        items.add(UItem.asButton(ID_INSP_RESET_PREVIEW, "Reset Local Inspection Preview Data", "EVE Local Inspector"));
        items.add(UItem.asButton(ID_INSP_CACHE_INSPECT, "Inspect App Cache", cacheSummary));
        items.add(UItem.asButton(ID_INSP_CACHE_CLEAR, "Clear Safe Cache Files"));
        items.add(UItem.asShadow("DEBUG-only local inspection admin. Grants zero production backend privileges and is excluded from release builds."));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (!EveAdminConfig.isAdminAvailable()) {
            return;
        }
        switch (item.id) {
            case ID_TOGGLE_ACCOUNT:
                EveAdminConfig.toggleSectionVisible(EveAdminConfig.SECTION_ACCOUNT);
                refreshAndNotifySettings();
                break;
            case ID_TOGGLE_CHAT_SETTINGS:
                EveAdminConfig.toggleSectionVisible(EveAdminConfig.SECTION_CHAT_SETTINGS);
                refreshAndNotifySettings();
                break;
            case ID_TOGGLE_PRIVACY:
                EveAdminConfig.toggleSectionVisible(EveAdminConfig.SECTION_PRIVACY);
                refreshAndNotifySettings();
                break;
            case ID_TOGGLE_NOTIFICATIONS:
                EveAdminConfig.toggleSectionVisible(EveAdminConfig.SECTION_NOTIFICATIONS);
                refreshAndNotifySettings();
                break;
            case ID_TOGGLE_DATA_STORAGE:
                EveAdminConfig.toggleSectionVisible(EveAdminConfig.SECTION_DATA_STORAGE);
                refreshAndNotifySettings();
                break;
            case ID_TOGGLE_CHAT_FOLDERS:
                EveAdminConfig.toggleSectionVisible(EveAdminConfig.SECTION_CHAT_FOLDERS);
                refreshAndNotifySettings();
                break;
            case ID_TOGGLE_DEVICES:
                EveAdminConfig.toggleSectionVisible(EveAdminConfig.SECTION_DEVICES);
                refreshAndNotifySettings();
                break;
            case ID_TOGGLE_POWER_SAVING:
                EveAdminConfig.toggleSectionVisible(EveAdminConfig.SECTION_POWER_SAVING);
                refreshAndNotifySettings();
                break;
            case ID_TOGGLE_LANGUAGE:
                EveAdminConfig.toggleSectionVisible(EveAdminConfig.SECTION_LANGUAGE);
                refreshAndNotifySettings();
                break;
            case ID_RESET_MODULES:
                EveAdminConfig.resetAllSectionVisibility();
                refreshAndNotifySettings();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "All retained Settings sections restored.").show();
                break;
            case ID_VIEW_REMOVED_MISSIONS:
                showInfoDialog("Removed Feature Ecosystems (Intact)",
                        "Mission 01: Contacts & Contact Sync — REMOVED\n"
                                + "Mission 02: Voice Calls, Video Calls & Group Calls — REMOVED\n"
                                + "Mission 03: Bots, Mini Apps & Telegram Business — REMOVED\n"
                                + "Mission 04: Stories & Live Location Sharing — REMOVED\n"
                                + "Mission 05: TON Wallet, Stars & Gifts — REMOVED\n"
                                + "Mission 06: Android Auto, Wear OS & Home Widgets — REMOVED\n\n"
                                + "None of these can be enabled from Admin Control Center.");
                break;

            case ID_NAV_CHATS:
                switchMainTab(0);
                break;
            case ID_NAV_SETTINGS:
                switchMainTab(1);
                break;
            case ID_NAV_PROFILE: {
                long clientUserId = UserConfig.getInstance(currentAccount).getClientUserId();
                if (clientUserId == 0) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "No authenticated Telegram account.").show();
                    break;
                }
                Bundle args = new Bundle();
                args.putLong("user_id", clientUserId);
                args.putBoolean("my_profile", true);
                presentFragment(new ProfileActivity(args));
                break;
            }
            case ID_NAV_ACCOUNT:
                presentFragment(new UserInfoActivity());
                break;
            case ID_NAV_CHAT_SETTINGS:
                presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC));
                break;
            case ID_NAV_PRIVACY:
                presentFragment(new PrivacySettingsActivity());
                break;
            case ID_NAV_NOTIFICATIONS:
                presentFragment(new NotificationsSettingsActivity());
                break;
            case ID_NAV_DATA:
                presentFragment(new DataSettingsActivity());
                break;
            case ID_NAV_FOLDERS:
                presentFragment(new FiltersSetupActivity());
                break;
            case ID_NAV_DEVICES:
                presentFragment(new SessionsActivity(0));
                break;
            case ID_NAV_POWER:
                presentFragment(new LiteModeSettingsActivity());
                break;
            case ID_NAV_LANGUAGE:
                presentFragment(new LanguageSelectActivity());
                break;
            case ID_NAV_DEBUG_SETTINGS:
                presentFragment(new DebugSettingsActivity());
                break;
            case ID_NAV_DIAGNOSTICS:
                showNavigationDiagnostics();
                break;
            case ID_NAV_RESET:
                switchMainTab(0);
                break;

            case ID_THEME_LIGHT:
                applyThemeMode("Blue", false, view);
                break;
            case ID_THEME_DARK:
                applyThemeMode("Dark Blue", true, view);
                break;
            case ID_THEME_SYSTEM:
                Theme.selectedAutoNightType = Theme.AUTO_NIGHT_TYPE_SYSTEM;
                Theme.saveAutoNightThemeConfig();
                Theme.checkAutoNightThemeConditions(true);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "System Day/Night theme enabled.").show();
                listView.adapter.update(true);
                break;
            case ID_THEME_PRESET:
                showThemePresetPicker(view);
                break;
            case ID_THEME_RESET:
                applyThemeMode("Blue", false, view);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Theme reset to default Blue.").show();
                break;

            case ID_ANIM_PROBE_RLOTTIE:
                runRLottieProbe();
                break;
            case ID_ANIM_TOGGLE_EMOJI:
                LiteMode.toggleFlag(LiteMode.FLAGS_ANIMATED_EMOJI);
                listView.adapter.update(true);
                break;
            case ID_ANIM_TOGGLE_STICKERS:
                LiteMode.toggleFlag(LiteMode.FLAGS_ANIMATED_STICKERS);
                listView.adapter.update(true);
                break;
            case ID_ANIM_DIAGNOSTICS:
                showAnimationDiagnostics();
                break;

            case ID_QR_GENERATE:
                presentFragment(QrShareActivity.self(currentAccount));
                break;
            case ID_QR_SCAN:
                QrShareActivity.openCameraScanActivity(this);
                break;
            case ID_CAMERA_DIAGNOSTICS:
                showCameraDiagnostics();
                break;

            case ID_DIAG_CRASH:
                showInfoDialog("Crash Reports & Exit Reasons",
                        "=== Process Exit Reasons ===\n"
                                + EveCrashDiagnostics.getLastExitSummary(getContext())
                                + "\n\n=== Last Uncaught Java Crash ===\n"
                                + EveCrashDiagnostics.getLastCrashReport(getContext()));
                break;
            case ID_DIAG_LOADING:
                showLoadingStatesDialog();
                break;
            case ID_DIAG_EVENTS:
                showEventsDialog();
                break;
            case ID_DIAG_EXPORT:
                exportDiagnosticReport();
                break;
            case ID_DIAG_CLEAR:
                EveCrashDiagnostics.clearReports(getContext());
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Stored diagnostic reports cleared.").show();
                listView.adapter.update(true);
                break;

            case ID_INSP_RESET_PREVIEW:
                EveInspectionMode.resetLocalPreviewState(currentAccount);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Local inspection preview data reset.").show();
                listView.adapter.update(true);
                break;
            case ID_INSP_CACHE_INSPECT: {
                EveAdminConfig.CacheStats st = EveAdminConfig.inspectCache(getContext());
                showInfoDialog("App Cache Inspection",
                        String.format(Locale.US, "Cached files: %d\nTotal size: %.2f MB\n\nIncludes internal and external cache directories only.",
                                st.fileCount, st.totalBytes / (1024f * 1024f)));
                break;
            }
            case ID_INSP_CACHE_CLEAR: {
                int removed = EveAdminConfig.clearSafeCache(getContext());
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Cleared " + removed + " cached files.").show();
                listView.adapter.update(true);
                break;
            }
        }
    }

    private void refreshAndNotifySettings() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void switchMainTab(int tabPosition) {
        if (getParentLayout() != null) {
            List<BaseFragment> stack = getParentLayout().getFragmentStack();
            for (int i = stack.size() - 1; i >= 0; i--) {
                BaseFragment f = stack.get(i);
                if (f instanceof MainTabsActivity) {
                    finishFragment();
                    ((MainTabsActivity) f).selectTab(tabPosition, true);
                    return;
                }
            }
        }
        finishFragment();
    }

    private void applyThemeMode(String themeKey, boolean isDark, View anchor) {
        Theme.ThemeInfo target = Theme.getTheme(themeKey);
        if (target == null) {
            target = isDark ? Theme.getTheme("Night") : Theme.getTheme("Blue");
        }
        if (target == null) {
            return;
        }
        Theme.selectedAutoNightType = Theme.AUTO_NIGHT_TYPE_NONE;
        Theme.saveAutoNightThemeConfig();
        Theme.cancelAutoNightThemeCallbacks();
        SharedPreferences.Editor editor = ApplicationLoader.applicationContext
                .getSharedPreferences("themeconfig", Activity.MODE_PRIVATE).edit();
        editor.putString(isDark ? "lastDarkTheme" : "lastDayTheme", target.getKey());
        editor.apply();
        Theme.switchThemeWithReveal(target, false, -1, anchor);
        if (listView != null && listView.adapter != null) {
            listView.postDelayed(() -> {
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            }, 250);
        }
    }

    private void showThemePresetPicker(View anchor) {
        if (getParentActivity() == null) return;
        final String[] presets = new String[] {"Blue", "Classic", "Day", "Dark Blue", "Night"};
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Select Built-In Theme")
                .setItems(presets, (dialog, which) -> {
                    String key = presets[which];
                    boolean dark = "Dark Blue".equals(key) || "Night".equals(key);
                    applyThemeMode(key, dark, anchor);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runRLottieProbe() {
        try {
            RLottieDrawable probe = new RLottieDrawable(R.raw.sun_outline, AndroidUtilities.dp(28), AndroidUtilities.dp(28), true, null);
            int frames = probe.getFramesCount();
            boolean loaded = NativeLoader.loaded();
            probe.recycle(false);
            showInfoDialog("RLottie Native Probe",
                    "Native lib loaded: " + loaded + "\n"
                            + "Test resource: R.raw.sun_outline\n"
                            + "Decoded frames: " + frames + "\n"
                            + "Status: " + (frames > 0 ? "PASS (rendering ready)" : "FAIL (0 frames decoded)"));
        } catch (Throwable t) {
            EveCrashDiagnostics.recordEvent("ANIMATION", "RLottie probe error: " + t.getMessage());
            showInfoDialog("RLottie Probe Error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void showAnimationDiagnostics() {
        float scale = 1f;
        try {
            if (getContext() != null) {
                scale = Settings.Global.getFloat(getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            }
        } catch (Throwable ignored) {
        }
        showInfoDialog("Animation & Motion Diagnostics",
                "Native libtmessages loaded: " + NativeLoader.loaded() + "\n"
                        + "SharedConfig.animationsEnabled(): " + SharedConfig.animationsEnabled() + "\n"
                        + "System ANIMATOR_DURATION_SCALE: " + scale + "\n"
                        + "Device performance class: " + SharedConfig.getDevicePerformanceClass() + "\n"
                        + "Animated Emoji (Chat): " + LiteMode.isEnabled(LiteMode.FLAG_ANIMATED_EMOJI_CHAT) + "\n"
                        + "Animated Stickers (Chat): " + LiteMode.isEnabled(LiteMode.FLAG_ANIMATED_STICKERS_CHAT) + "\n"
                        + "LiteMode power saver active: " + LiteMode.isPowerSaverApplied());
    }

    private void showNavigationDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("Account Index: ").append(currentAccount).append("\n");
        sb.append("UserConfig.isClientActivated(): ").append(UserConfig.getInstance(currentAccount).isClientActivated()).append("\n");
        sb.append("EveInspectionMode.isActive(): ").append(EveInspectionMode.isActive(currentAccount)).append("\n");
        if (getParentLayout() != null) {
            List<BaseFragment> stack = getParentLayout().getFragmentStack();
            sb.append("Fragment Stack Depth: ").append(stack.size()).append("\n");
            for (int i = 0; i < stack.size(); i++) {
                sb.append("  [").append(i).append("] ").append(stack.get(i).getClass().getSimpleName()).append("\n");
            }
        }
        showInfoDialog("Navigation Diagnostics", sb.toString().trim());
    }

    private void showCameraDiagnostics() {
        Context ctx = getContext();
        PackageManager pm = ctx != null ? ctx.getPackageManager() : null;
        boolean hasAny = pm != null && pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        boolean hasFront = pm != null && pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT);
        boolean hasFlash = pm != null && pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
        boolean hasPerm = ctx != null && (Build.VERSION.SDK_INT < 23
                || ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
        showInfoDialog("Camera & QR Diagnostics",
                "FEATURE_CAMERA_ANY: " + hasAny + "\n"
                        + "FEATURE_CAMERA_FRONT: " + hasFront + "\n"
                        + "FEATURE_CAMERA_FLASH: " + hasFlash + "\n"
                        + "CAMERA Permission Granted: " + hasPerm + "\n"
                        + "CameraView.isCameraAllowed(): " + CameraView.isCameraAllowed() + "\n"
                        + "Local Inspection QR Payload:\n" + EveInspectionMode.getLocalInspectionQrUrl());
    }

    private void showLoadingStatesDialog() {
        List<EveLoadingTracker.Entry> entries = EveLoadingTracker.snapshot();
        StringBuilder sb = new StringBuilder();
        if (entries.isEmpty()) {
            sb.append("No tracked loading states recorded yet.");
        } else {
            for (EveLoadingTracker.Entry e : entries) {
                sb.append("• ").append(e.module).append(": ").append(e.outcome)
                        .append("\n   ").append(e.detail).append("\n");
            }
        }
        showInfoDialog("Loading States Viewer", sb.toString().trim());
    }

    private void showEventsDialog() {
        List<String> events = EveCrashDiagnostics.getRecentEvents();
        StringBuilder sb = new StringBuilder();
        if (events.isEmpty()) {
            sb.append("No navigation, resource, or network error events recorded.");
        } else {
            for (String ev : events) {
                sb.append(ev).append("\n");
            }
        }
        showInfoDialog("Recent Diagnostic Events", sb.toString().trim());
    }

    private void exportDiagnosticReport() {
        if (getParentActivity() == null) return;
        String report = EveCrashDiagnostics.buildSanitizedReport(getContext());
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "EVE Debug Diagnostic Report (Redacted)");
        intent.putExtra(Intent.EXTRA_TEXT, report);
        try {
            getParentActivity().startActivity(Intent.createChooser(intent, "Export Redacted Diagnostic Report"));
        } catch (Throwable t) {
            showInfoDialog("Diagnostic Report", report);
        }
    }

    private void showInfoDialog(String title, String message) {
        if (getParentActivity() == null) return;
        new AlertDialog.Builder(getParentActivity())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Close", null)
                .show();
    }
}
