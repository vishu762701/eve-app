package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FlickerLoadingView;
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
import java.util.Locale;

/**
 * EVE Admin Control Center organized into 3 clean Telegram Settings cards.
 */
public class EveAdminControlCenterActivity extends BaseFragment {

    private static final int MENU_REFRESH = 1;

    private static final int ID_ADMIN_REFRESH = 1001;
    private static final int ID_ADMIN_SIGN_IN = 1002;

    private static final int ID_MOD_EXAMS = 1101;
    private static final int ID_MOD_CONTENT_COMMS = 1601;

    private static final int ID_MOD_STUDENTS = 1301;
    private static final int ID_MOD_PAYMENTS = 1501;
    private static final int ID_MOD_PREMIUM = 1502;
    private static final int ID_MOD_PREMIUM_USERS = 1503;

    private static final int ID_MOD_INSIGHTS_SYSTEM = 1401;
    private static final int ID_MOD_ADMINS = 1801;
    private static final int ID_MOD_AUDIT = 1802;

    private UniversalRecyclerView listView;
    private final EveAdminRepository repo = EveAdminRepository.getInstance();

    private boolean loading;
    private boolean destroyed;
    private String loadError = "";
    private int lastHttpStatus;
    private int activeRequestSeq;
    private EveAdminRepository.ResultCallback<EveAdminModels.DashboardSnapshot> activeCallback;
    private EveAdminModels.DashboardSnapshot snapshot;

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
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_REFRESH) {
                            refreshDashboard();
                        }
                    }
                },
                null
        );
        listView = chrome.listView;

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            menu.addItem(MENU_REFRESH, R.drawable.msg_retry, getResourceProvider());
        }

        fragmentView = chrome.rootView;
        refreshDashboard();
        return chrome.rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        EveAdminModels.DashboardSnapshot latestCached = repo.getCachedSnapshot();
        if (latestCached != null && latestCached != snapshot && snapshot == null) {
            snapshot = latestCached;
            updateHeaderSubtitle();
            updateList();
        }
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
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
        loading = true;
        loadError = "";
        updateList();
        EveAdminRepository.ResultCallback<EveAdminModels.DashboardSnapshot> callback =
                new EveAdminRepository.ResultCallback<EveAdminModels.DashboardSnapshot>() {
                    @Override
                    public void onSuccess(EveAdminModels.DashboardSnapshot data) {
                        if (destroyed || requestSeq != activeRequestSeq) {
                            return;
                        }
                        loading = false;
                        loadError = "";
                        lastHttpStatus = 200;
                        snapshot = data;
                        updateHeaderSubtitle();
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed || requestSeq != activeRequestSeq) {
                            return;
                        }
                        loading = false;
                        loadError = message != null ? message : "Unable to load admin dashboard.";
                        lastHttpStatus = httpStatus;
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

        if (loading && snapshot == null) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (snapshot == null || snapshot.adminProfile == null) {
            return;
        }

        EveAdminModels.AdminProfile profile = snapshot.adminProfile;

        // Card 1: Exams & Content
        boolean canExams = EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_EXAMS)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_SYLLABUS)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_GENERATED_TESTS)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_QUESTIONS);
        boolean canContent = EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_CONTENT)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_SEND_BROADCASTS)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MODERATE_FEEDBACK);

        if (canExams || canContent) {
            items.add(UItem.asHeader("Exams & Content"));
            if (canExams) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_EXAMS,
                        IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                        R.drawable.msg_folders,
                        "Exams & Syllabus",
                        snapshot.publishedExams + " live • " + snapshot.draftExams + " paused",
                        String.valueOf(snapshot.totalExams)));
            }
            if (canContent) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_CONTENT_COMMS,
                        IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                        R.drawable.settings_sounds,
                        "Content & Moderation",
                        "Banners, polls, broadcasts & flags",
                        snapshot.pendingReports > 0 ? String.valueOf(snapshot.pendingReports) : null));
            }
            items.add(UItem.asShadow(null));
        }

        // Card 2: Students & Revenue
        boolean canUsers = EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_USERS);
        boolean canPremium = EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_PREMIUM);
        if (canUsers || canPremium) {
            items.add(UItem.asHeader("Students & Revenue"));
            if (canUsers) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_STUDENTS,
                        IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                        R.drawable.settings_account,
                        "Students",
                        snapshot.activeStudents + " active now • Manage accounts",
                        String.valueOf(snapshot.totalStudents)));
            }
            if (canPremium) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_PAYMENTS,
                        IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                        R.drawable.msg_stats,
                        "Payment Orders",
                        "₹" + snapshot.totalRevenueInr + " earned • " + snapshot.pendingTransactions + " pending",
                        String.valueOf(snapshot.totalTransactions)));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_PREMIUM,
                        0xFFB659FF, 0xFF617CFF,
                        R.drawable.settings_premium,
                        "Premium Plans & UPI",
                        "Pricing, duration & UPI QR",
                        null));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_PREMIUM_USERS,
                        IconBackgroundColors.ORANGE_DEEP.top, IconBackgroundColors.ORANGE_DEEP.bottom,
                        R.drawable.msg_groups,
                        "Premium Subscribers",
                        "Active memberships",
                        String.valueOf(snapshot.activePremiumUsers)));
            }
            items.add(UItem.asShadow(null));
        }

        // Card 3: System & Access
        items.add(UItem.asHeader("System & Access"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_MOD_INSIGHTS_SYSTEM,
                IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                R.drawable.msg_stats,
                "Analytics & Health",
                snapshot.completedAttempts + " attempts • Avg " + String.format(Locale.US, "%.1f", snapshot.averageScorePct),
                null));
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_ADMINS_RIGHTS)) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_ADMINS,
                    IconBackgroundColors.BLUE_DEEP.top, IconBackgroundColors.BLUE_DEEP.bottom,
                    R.drawable.settings_privacy,
                    "Admin Roles",
                    "Manage team & permissions",
                    null));
        }
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_VIEW_ANALYTICS_LOGS)) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_AUDIT,
                    IconBackgroundColors.BLUE_ALT.top, IconBackgroundColors.BLUE_ALT.bottom,
                    R.drawable.msg_log,
                    "Audit Log",
                    "Admin action history",
                    null));
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
            case ID_ADMIN_SIGN_IN:
                startAdminSignIn();
                break;
            case ID_MOD_EXAMS:
                presentFragment(new EveAdminExamsFragment());
                break;
            case ID_MOD_CONTENT_COMMS:
                presentFragment(new EveAdminContentAndCommsFragment(profile));
                break;
            case ID_MOD_STUDENTS:
                presentFragment(new EveAdminStudentsFragment());
                break;
            case ID_MOD_PAYMENTS:
                presentFragment(new EveAdminPaymentsFragment());
                break;
            case ID_MOD_PREMIUM:
                presentFragment(new EveAdminPremiumConfigFragment());
                break;
            case ID_MOD_PREMIUM_USERS:
                presentFragment(new EveAdminPremiumUsersFragment());
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
