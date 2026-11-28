package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.auth.EveAuthCoordinator;
import org.telegram.ui.eve.auth.EveAuthManager;
import org.telegram.ui.eve.auth.EveUserProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * EVE Admin Control Center organized cleanly with a compact Owner Status card,
 * direct Maintenance quick toggle, and strictly alphabetical (A -> Z) Admin Modules.
 */
public class EveAdminControlCenterActivity extends BaseFragment {

    private static final int ID_ADMIN_REFRESH = 1001;
    private static final int ID_ADMIN_SIGN_IN = 1002;
    private static final int ID_OWNER_STATUS_CARD = 1003;

    private static final int ID_MOD_ADMINS = 1801;
    private static final int ID_MOD_INSIGHTS_SYSTEM = 1405;
    private static final int ID_MOD_ANNOUNCEMENTS_QUIZZES = 1102;
    private static final int ID_MOD_APP_CONFIG = 1404;
    private static final int ID_MOD_AUDIT = 1802;
    private static final int ID_MOD_EXAMS = 1101;
    private static final int ID_MOD_BANNERS = 1103;
    private static final int ID_MOD_CONTENT_COMMS = 1601;
    private static final int ID_MOD_PREMIUM_HUB = 1501;
    private static final int ID_MOD_STUDENTS = 1301;

    private static final int ID_MAINTENANCE_SWITCH = 1400;

    @NonNull
    public static List<String> getOrderedModuleTitles() {
        return Arrays.asList(
                "Admin Roles",
                "Analytics & Health",
                "Announcements & Quizzes",
                "App & AI Settings",
                "Audit Log",
                "Exams",
                "Home Banners",
                "Moderation & Legal",
                "Premium & Payments",
                "Students"
        );
    }

    private UniversalRecyclerView listView;
    private final EveAdminRepository repo = EveAdminRepository.getInstance();

    private boolean destroyed;
    private String loadError = "";
    private int lastHttpStatus;
    private int activeRequestSeq;
    private EveAdminRepository.ResultCallback<EveAdminModels.DashboardSnapshot> activeCallback;
    private EveAdminModels.DashboardSnapshot snapshot;
    private EveAdminModels.AppConfigModel appConfig;
    private final EveAdminUiChrome.AutoSyncController autoSync = new EveAdminUiChrome.AutoSyncController(this::refreshDashboard);

    @Override
    public boolean onFragmentCreate() {
        if (!EveAdminConfig.isAdminAvailable()) {
            return false;
        }
        return super.onFragmentCreate();
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
        destroyed = false;
        setHasOwnBackground(true);

        snapshot = repo.getCachedSnapshot();
        appConfig = repo.getCachedAppConfig();
        String initialSubtitle = (snapshot != null && snapshot.adminProfile != null)
                ? snapshot.adminProfile.customTitle + " • " + snapshot.adminProfile.email
                : "Admin Dashboard";

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                LocaleController.getString(R.string.EveAdminControlCenter),
                initialSubtitle,
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
                null
        );
        listView = chrome.listView;

        fragmentView = chrome.rootView;
        refreshDashboard();
        loadLiveAppConfig();
        return chrome.rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        appConfig = repo.getCachedAppConfig();
        EveAdminModels.DashboardSnapshot latestCached = repo.getCachedSnapshot();
        if (latestCached != null && latestCached != snapshot && snapshot == null) {
            snapshot = latestCached;
            updateHeaderSubtitle();
            updateList();
        }
        loadLiveAppConfig();
        autoSync.onResume();
    }

    private void loadLiveAppConfig() {
        if (destroyed) return;
        EveAdminRepository.ResultCallback<EveAdminModels.AppConfigModel> cb =
                new EveAdminRepository.ResultCallback<EveAdminModels.AppConfigModel>() {
                    @Override
                    public void onSuccess(EveAdminModels.AppConfigModel cfg) {
                        if (destroyed || cfg == null) return;
                        appConfig = cfg;
                        if (listView != null && listView.adapter != null) {
                            listView.adapter.update(true);
                        }
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchAppConfig(token)));
    }

    @Override
    public void onPause() {
        autoSync.onPause();
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        autoSync.destroy();
        activeRequestSeq++;
        if (activeCallback != null) {
            repo.cancelDashboardCallback(activeCallback);
            activeCallback = null;
        }
        super.onFragmentDestroy();
    }

    private void updateHeaderSubtitle() {
        if (simpleActionBar != null && snapshot != null && snapshot.adminProfile != null) {
            simpleActionBar.setSubtitle(snapshot.adminProfile.customTitle + " • " + snapshot.adminProfile.email);
        }
    }

    private void refreshDashboard() {
        if (destroyed) {
            return;
        }
        final int requestSeq = ++activeRequestSeq;
        if (activeCallback != null) {
            repo.cancelDashboardCallback(activeCallback);
        }
        loadError = "";
        EveAdminRepository.ResultCallback<EveAdminModels.DashboardSnapshot> callback =
                new EveAdminRepository.ResultCallback<EveAdminModels.DashboardSnapshot>() {
                    @Override
                    public void onSuccess(EveAdminModels.DashboardSnapshot data) {
                        if (destroyed || requestSeq != activeRequestSeq) {
                            return;
                        }
                        loadError = "";
                        lastHttpStatus = 200;
                        snapshot = data;
                        autoSync.markSynced();
                        updateHeaderSubtitle();
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed || requestSeq != activeRequestSeq) {
                            return;
                        }
                        loadError = message != null ? message : "Unable to load admin dashboard.";
                        lastHttpStatus = httpStatus;
                        autoSync.markSynced();
                        updateList();
                    }
                };
        activeCallback = callback;
        repo.loadDashboardAsync(callback);
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        EveUserProfile cached = EveAuthManager.getInstance().getCachedProfile();
        String accountLabel = cached != null && !cached.email.isEmpty() ? cached.email : "Not signed in";
        boolean needsAuthAction = cached == null || lastHttpStatus == 401 || lastHttpStatus == 403;
        boolean hasPartialWarnings = snapshot != null && !snapshot.partialWarnings.isEmpty();

        if (needsAuthAction || !loadError.isEmpty() || hasPartialWarnings) {
            if (needsAuthAction) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_ADMIN_SIGN_IN,
                        IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                        R.drawable.settings_account,
                        cached == null || lastHttpStatus == 401 ? "Sign In Admin Account" : "Switch Admin Account",
                        accountLabel,
                        null));
            }
            if (!loadError.isEmpty()) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_ADMIN_REFRESH,
                        IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                        R.drawable.msg_retry,
                        "Retry Dashboard",
                        loadError,
                        lastHttpStatus > 0 ? "HTTP " + lastHttpStatus : "Offline"));
            } else if (hasPartialWarnings) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_ADMIN_REFRESH,
                        IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                        R.drawable.msg_warning,
                        "Partial Sync Warning",
                        snapshot.partialWarnings.get(0),
                        "Retry"));
            }
            items.add(UItem.asShadow(null));
        }

        EveAdminModels.AdminProfile profile = snapshot != null ? snapshot.adminProfile : null;
        boolean hasSnapshot = snapshot != null && profile != null;

        // Card 1: Top Compact Status Card (Owner Avatar + Name/Email + Role badge + summary)
        String ownerDisplayName = cached != null && !TextUtils.isEmpty(cached.displayName)
                ? cached.displayName
                : (profile != null && !TextUtils.isEmpty(profile.customTitle) ? profile.customTitle : "EVE Administrator");
        String ownerEmail = (profile != null && !TextUtils.isEmpty(profile.email)) ? profile.email : accountLabel;
        String ownerPhoto = cached != null ? cached.photoUrl : "";
        String roleBadge = (profile != null && profile.isOwner) ? "Owner" : "Admin";
        String compactSummary = hasSnapshot
                ? (ownerEmail + " \u2022 " + snapshot.totalExams + " Exams \u2022 " + snapshot.totalStudents + " Students")
                : ownerEmail;
        items.add(EveAdminUiChrome.AvatarRowCell.Factory.of(
                ID_OWNER_STATUS_CARD,
                profile,
                new EveAdminUiChrome.AvatarRowCell.Payload(
                        !TextUtils.isEmpty(ownerEmail) ? ownerEmail : "eve_owner",
                        ownerDisplayName,
                        compactSummary,
                        ownerPhoto,
                        roleBadge,
                        EveAdminUiChrome.AvatarRowCell.BADGE_BLUE)));
        items.add(UItem.asShadow(null));

        // Card 2: Quick Control Card (Direct 1-tap Maintenance Mode switch)
        boolean canConfig = !hasSnapshot || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_CONFIG);
        boolean maintenanceActive = appConfig != null && appConfig.maintenanceMode;
        if (canConfig) {
            items.add(UItem.asHeader("Quick Control"));
            items.add(UItem.asCheck(ID_MAINTENANCE_SWITCH, "Maintenance Mode").setChecked(maintenanceActive));
            items.add(UItem.asShadow(maintenanceActive
                    ? "Maintenance Mode is ON. Tap switch to restore student access."
                    : "Tap switch to pause student access during maintenance."));
        }

        // Card 3: Admin Modules Card — Strictly Alphabetical (A -> Z), zero duplicates
        boolean canAdmins = !hasSnapshot || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_ADMINS_RIGHTS);
        boolean canAnalytics = !hasSnapshot || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_VIEW_ANALYTICS_LOGS);
        boolean canAnnouncements = !hasSnapshot
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_SEND_BROADCASTS)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MODERATE_FEEDBACK);
        boolean canExams = !hasSnapshot
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_EXAMS)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_SYLLABUS)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_GENERATED_TESTS)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_QUESTIONS);
        boolean canBanners = !hasSnapshot || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_CONTENT);
        boolean canModeration = !hasSnapshot
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MODERATE_FEEDBACK)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_CONTENT);
        boolean canPremium = !hasSnapshot || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_PREMIUM);
        boolean canStudents = !hasSnapshot || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_USERS);

        items.add(UItem.asHeader("Admin Modules"));

        // 1. Admin Roles
        if (canAdmins) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_ADMINS,
                    IconBackgroundColors.BLUE_DEEP.top, IconBackgroundColors.BLUE_DEEP.bottom,
                    R.drawable.settings_privacy,
                    "Admin Roles",
                    "Delegate granular team rights & permissions",
                    null));
        }

        // 2. Analytics & Health
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_MOD_INSIGHTS_SYSTEM,
                IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                R.drawable.msg_stats,
                "Analytics & Health",
                hasSnapshot
                        ? (snapshot.completedAttempts + " attempts \u2022 Avg " + String.format(Locale.US, "%.1f", snapshot.averageScorePct) + " \u2022 Crashes & Jobs")
                        : "Exam analytics, crashes, AI cost & D1/KV health",
                hasSnapshot ? String.valueOf(snapshot.completedAttempts) : null));

        // 3. Announcements & Quizzes
        if (canAnnouncements) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_ANNOUNCEMENTS_QUIZZES,
                    IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                    R.drawable.msg_send,
                    "Announcements & Quizzes",
                    "Broadcast channel, stickers, emojis & student quizzes",
                    null));
        }

        // 4. App & AI Settings
        if (canConfig) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_APP_CONFIG,
                    IconBackgroundColors.BLUE_ALT.top, IconBackgroundColors.BLUE_ALT.bottom,
                    R.drawable.msg_settings,
                    "App & AI Settings",
                    "Min supported version, AI prompts & system config",
                    null));
        }

        // 5. Audit Log
        if (canAnalytics) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_AUDIT,
                    IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                    R.drawable.msg_log,
                    "Audit Log",
                    "Categorized admin action history",
                    null));
        }

        // 6. Exams
        if (canExams) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_EXAMS,
                    IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                    R.drawable.msg_folders,
                    "Exams",
                    hasSnapshot
                            ? (snapshot.publishedExams + " live \u2022 " + snapshot.draftExams + " paused \u2022 Sub-Exams & Bank")
                            : "Sub-Exams, Mock Tests, Question Bank, Cutoffs & PDF",
                    hasSnapshot ? String.valueOf(snapshot.totalExams) : null));
        }

        // 7. Home Banners
        if (canBanners) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_BANNERS,
                    IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                    R.drawable.msg_photos,
                    "Home Banners",
                    "Video & image carousel links, ordering & media",
                    null));
        }

        // 8. Moderation & Legal
        if (canModeration) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_CONTENT_COMMS,
                    IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                    R.drawable.settings_sounds,
                    "Moderation & Legal",
                    "Flagged questions fixer, feedback & legal pages",
                    hasSnapshot && snapshot.pendingReports > 0 ? String.valueOf(snapshot.pendingReports) : null));
        }

        // 9. Premium & Payments
        if (canPremium) {
            String premSub = hasSnapshot
                    ? ("\u20B9" + snapshot.totalRevenueInr + " earned \u2022 " + snapshot.activePremiumUsers + " active \u2022 " + snapshot.pendingTransactions + " pending")
                    : "Plans & UPI QR, Payment Orders & Active Subscribers";
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_PREMIUM_HUB,
                    0xFFB659FF, 0xFF617CFF,
                    R.drawable.settings_premium,
                    "Premium & Payments",
                    premSub,
                    hasSnapshot && snapshot.pendingTransactions > 0 ? String.valueOf(snapshot.pendingTransactions) : null));
        }

        // 10. Students
        if (canStudents) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_STUDENTS,
                    IconBackgroundColors.BLUE_LIGHT.top, IconBackgroundColors.BLUE_LIGHT.bottom,
                    R.drawable.settings_account,
                    "Students",
                    hasSnapshot
                            ? (snapshot.activeStudents + " active now \u2022 Directory & performance")
                            : "Overview stats, quick filters, search & performance",
                    hasSnapshot ? String.valueOf(snapshot.totalStudents) : null));
        }

        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (!EveAdminConfig.isAdminAvailable()) {
            return;
        }
        EveAdminModels.AdminProfile profile = snapshot != null ? snapshot.adminProfile : null;
        switch (item.id) {
            case ID_ADMIN_REFRESH:
                refreshDashboard();
                break;
            case ID_OWNER_STATUS_CARD:
            case ID_ADMIN_SIGN_IN:
                startAdminSignIn();
                break;
            case ID_MOD_EXAMS:
                presentFragment(new EveAdminExamsFragment());
                break;
            case ID_MOD_ANNOUNCEMENTS_QUIZZES:
                presentFragment(new EveAdminBroadcastsFragment());
                break;
            case ID_MOD_BANNERS:
                presentFragment(new EveAdminBannersFragment());
                break;
            case ID_MOD_CONTENT_COMMS:
                presentFragment(new EveAdminContentAndCommsFragment(profile, true));
                break;
            case ID_MOD_STUDENTS:
                presentFragment(new EveAdminStudentsFragment());
                break;
            case ID_MOD_PREMIUM_HUB:
                presentFragment(new EveAdminPremiumHubFragment());
                break;
            case ID_MAINTENANCE_SWITCH:
                confirmAndToggleMaintenance(view);
                break;
            case ID_MOD_APP_CONFIG:
                openAppAndAiSettingsMenu();
                break;
            case ID_MOD_INSIGHTS_SYSTEM:
                presentFragment(new EveAdminInsightsAndSystemFragment(profile));
                break;
            case ID_MOD_ADMINS:
                presentFragment(new EveAdminRolesFragment());
                break;
            case ID_MOD_AUDIT:
                presentFragment(new EveAdminAuditLogFragment());
                break;
        }
    }

    private void confirmAndToggleMaintenance(View view) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        EveAdminModels.AppConfigModel currentCfg = appConfig != null ? appConfig : repo.getCachedAppConfig();
        boolean currentState = currentCfg != null && currentCfg.maintenanceMode;
        boolean nextState = !currentState;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle(nextState ? "Enable Maintenance Mode?" : "Disable Maintenance Mode?");
        builder.setMessage(nextState
                ? "Students will immediately see the maintenance screen until you turn it back off."
                : "Students will regain normal access to exams and mock tests.");
        builder.setPositiveButton(nextState ? "Enable Now" : "Disable Now", (d, w) -> {
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(nextState);
            }
            EveAdminRepository.ResultCallback<EveAdminModels.AppConfigModel> cb = new EveAdminRepository.ResultCallback<EveAdminModels.AppConfigModel>() {
                @Override
                public void onSuccess(EveAdminModels.AppConfigModel updated) {
                    if (destroyed) return;
                    if (updated != null) {
                        appConfig = updated;
                    }
                    BulletinFactory.of(EveAdminControlCenterActivity.this)
                            .createSuccessBulletin(updated != null && updated.maintenanceMode ? "Maintenance Mode enabled" : "Maintenance Mode disabled").show();
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                    refreshDashboard();
                }

                @Override
                public void onError(String message, int httpStatus) {
                    if (destroyed) return;
                    BulletinFactory.of(EveAdminControlCenterActivity.this)
                            .createErrorBulletin(!TextUtils.isEmpty(message) ? message : "Failed to update Maintenance Mode.").show();
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                }
            };
            repo.runWithAdminToken(cb, token -> {
                EveAdminModels.AppConfigModel latest = currentCfg != null ? currentCfg : repo.fetchAppConfig(token);
                int minVer = latest != null ? Math.max(1, latest.minSupportedVersionCode) : 1;
                String msg = latest != null && !TextUtils.isEmpty(latest.maintenanceMessage)
                        ? latest.maintenanceMessage
                        : "EVE is under scheduled maintenance. Please check back shortly.";
                String defMode = latest != null && !TextUtils.isEmpty(latest.defaultPublishMode)
                        ? latest.defaultPublishMode
                        : "paused";
                EveAdminModels.AppConfigModel res = repo.updateAppConfig(token, minVer, nextState, msg, defMode);
                repo.postSuccess(cb, res);
            });
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void openAppAndAiSettingsMenu() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle("App & AI Settings")
                .setItems(new String[] {
                        "App Configuration & Defaults",
                        "AI Prompt Studio"
                }, (d, which) -> {
                    if (which == 0) {
                        presentFragment(new EveAdminMaintenanceConfigFragment(appConfig, () -> {
                            appConfig = repo.getCachedAppConfig();
                            loadLiveAppConfig();
                            refreshDashboard();
                        }));
                    } else {
                        openAiPromptStudio();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openAiPromptStudio() {
        List<EveAdminModels.AdminExamItem> examList = snapshot != null && !snapshot.exams.isEmpty()
                ? snapshot.exams
                : null;
        if (examList == null) {
            EveAdminRepository.ExamsCatalogData cat = repo.getCachedExamsCatalog();
            if (cat != null && !cat.exams.isEmpty()) {
                examList = cat.exams;
            }
        }
        if (examList == null || examList.isEmpty()) {
            BulletinFactory.of(this).createErrorBulletin("Create an exam first under Exams & Syllabus.").show();
            presentFragment(new EveAdminExamsFragment());
            return;
        }
        presentFragment(new EveAdminExamPromptFragment(examList.get(0), examList, this::refreshDashboard));
    }

    private void startAdminSignIn() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        EveAuthManager.getInstance().signInWithGoogle(activity, new EveAuthManager.AuthCallback() {
            @Override
            public void onSuccess(@NonNull EveUserProfile profile, @NonNull EveAuthCoordinator.SyncOutcome syncOutcome) {
                BulletinFactory.of(EveAdminControlCenterActivity.this)
                        .createSimpleBulletin(R.raw.info, "Signed in as " + profile.email).show();
                refreshDashboard();
            }

            @Override
            public void onError(@NonNull String message, boolean accountDisabled) {
                BulletinFactory.of(EveAdminControlCenterActivity.this)
                        .createErrorBulletin(message).show();
            }
        });
    }
}
