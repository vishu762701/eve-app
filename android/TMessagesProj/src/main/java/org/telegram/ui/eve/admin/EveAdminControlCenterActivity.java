package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.auth.EveAuthCoordinator;
import org.telegram.ui.eve.auth.EveAuthManager;
import org.telegram.ui.eve.auth.EveUserProfile;
import org.telegram.ui.eve.media.EveAdminPackManagerFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Structured EVE Admin Control Center powered by Cloudflare Worker + D1.
 * Provides full administrative parity with the legacy Kotlin Admin Dashboard
 * across Modules A–M while keeping debug-only device tools strictly separate.
 */
public class EveAdminControlCenterActivity extends BaseFragment {

    private static final int MENU_REFRESH = 1;

    private static final int ID_ADMIN_REFRESH = 1001;
    private static final int ID_ADMIN_SIGN_IN = 1002;

    private static final int ID_KPI_STUDENTS = 1011;
    private static final int ID_KPI_EXAMS = 1012;
    private static final int ID_KPI_TESTS = 1013;
    private static final int ID_KPI_ATTEMPTS = 1014;
    private static final int ID_KPI_SCORE = 1015;
    private static final int ID_KPI_REVENUE = 1016;
    private static final int ID_KPI_PENDING = 1017;
    private static final int ID_KPI_PREMIUM = 1018;
    private static final int ID_KPI_MODERATION = 1019;

    private static final int ID_MOD_EXAMS = 1101;
    private static final int ID_MOD_GENERATE_TEST = 1102;
    private static final int ID_MOD_QUESTIONS = 1201;
    private static final int ID_MOD_STUDENTS = 1301;
    private static final int ID_MOD_INSIGHTS_SYSTEM = 1401;
    private static final int ID_MOD_PAYMENTS = 1501;
    private static final int ID_MOD_PREMIUM = 1502;
    private static final int ID_MOD_PREMIUM_USERS = 1503;
    private static final int ID_MOD_CONTENT_COMMS = 1601;
    private static final int ID_MOD_PACK_MANAGER = 1701;
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
    public View createView(Context context) {
        destroyed = false;
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.EveAdminControlCenter));
        actionBar.setSubtitle("Worker + D1 Control Center");
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_REFRESH, R.drawable.msg_retry);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_REFRESH) {
                    refreshDashboard();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        snapshot = repo.getCachedSnapshot();
        if (snapshot != null && snapshot.adminProfile != null) {
            actionBar.setSubtitle(snapshot.adminProfile.customTitle + " • " + snapshot.adminProfile.email);
        }
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = frameLayout;

        refreshDashboard();
        return frameLayout;
    }

    @Override
    public void onResume() {
        super.onResume();
        EveAdminModels.DashboardSnapshot latestCached = repo.getCachedSnapshot();
        if (latestCached != null && latestCached != snapshot && snapshot == null) {
            snapshot = latestCached;
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
                        if (actionBar != null && data != null && data.adminProfile != null) {
                            actionBar.setSubtitle(data.adminProfile.customTitle + " • " + data.adminProfile.email);
                        }
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
        EveUserProfile cached = EveAuthManager.getInstance().getCachedProfile();
        String accountLabel = cached != null && !cached.email.isEmpty() ? cached.email : "Not signed in";

        items.add(UItem.asHeader("Admin Session & Access"));
        String sessionTitle;
        String sessionSubtitle;
        String sessionBadge = null;
        if (loading && snapshot == null) {
            sessionTitle = "Loading Dashboard";
            sessionSubtitle = "Connecting to Worker for " + accountLabel;
        } else if (snapshot != null && snapshot.adminProfile != null) {
            if (loading && snapshot.isFromCache) {
                sessionTitle = "Syncing Live Metrics";
                sessionSubtitle = "Showing cached summary • " + snapshot.adminProfile.email;
            } else if (loading) {
                sessionTitle = "Refreshing Metrics";
                sessionSubtitle = "Updating from Worker • " + snapshot.adminProfile.email;
            } else if (!snapshot.partialWarnings.isEmpty()) {
                sessionTitle = "Dashboard Partial Sync";
                sessionSubtitle = snapshot.adminProfile.email + " • Tap to retry";
            } else {
                sessionTitle = "Refresh Dashboard";
                String timing = snapshot.loadDurationMs > 0 ? " (" + snapshot.loadDurationMs + " ms)" : "";
                sessionSubtitle = (snapshot.isStale ? "Cached • " : "Verified • ") + snapshot.adminProfile.email + timing;
            }
            sessionBadge = snapshot.adminProfile.customTitle + " (" + snapshot.adminProfile.rights.size() + ")";
        } else {
            sessionTitle = "Retry Dashboard Sync";
            sessionSubtitle = accountLabel;
        }
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_ADMIN_REFRESH,
                0xFF3D8BEB, 0xFF2A6FD9, R.drawable.msg_retry,
                sessionTitle,
                sessionSubtitle,
                sessionBadge));

        boolean needsAuthAction = cached == null || lastHttpStatus == 401 || lastHttpStatus == 403;
        if (needsAuthAction) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_ADMIN_SIGN_IN,
                    0xFF56C35A, 0xFF2FA138, R.drawable.settings_account,
                    cached == null || lastHttpStatus == 401 ? "Sign In Admin Account" : "Switch Admin Account",
                    accountLabel,
                    null));
        }
        if (!loadError.isEmpty()) {
            items.add(UItem.asShadow("Status (" + (lastHttpStatus > 0 ? "HTTP " + lastHttpStatus : "Offline") + "): " + loadError));
        } else if (snapshot != null && !snapshot.partialWarnings.isEmpty()) {
            items.add(UItem.asShadow("Partial load warning: " + snapshot.partialWarnings.get(0)));
        } else {
            items.add(UItem.asShadow("Server-verified RBAC enforced on Cloudflare Worker + D1."));
        }

        if (snapshot == null || snapshot.adminProfile == null) {
            if (loading) {
                items.add(UItem.asHeader("A. Dashboard Overview"));
                for (int i = 0; i < 6; i++) {
                    items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
                }
            }
            return;
        }
        EveAdminModels.AdminProfile profile = snapshot.adminProfile;

        // A. DASHBOARD OVERVIEW (all 9 required metrics clearly separated, zero truncation)
        items.add(UItem.asHeader("A. Dashboard Overview"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_KPI_STUDENTS,
                0xFF3D8BEB, 0xFF2A6FD9, R.drawable.settings_account,
                "Students",
                snapshot.activeStudents + " active right now",
                String.valueOf(snapshot.totalStudents)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_KPI_EXAMS,
                0xFFFF9800, 0xFFEF6C00, R.drawable.msg_list,
                "Exams",
                snapshot.publishedExams + " live • " + snapshot.draftExams + " paused",
                String.valueOf(snapshot.totalExams)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_KPI_TESTS,
                0xFF9C27B0, 0xFF6A1B9A, R.drawable.msg_add,
                "Generated Tests",
                snapshot.liveTests + " live • " + snapshot.draftTests + " draft",
                String.valueOf(snapshot.totalTests)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_KPI_ATTEMPTS,
                0xFF3F51B5, 0xFF283593, R.drawable.msg_stats,
                "Exam Attempts",
                "Counted mock test submissions",
                String.valueOf(snapshot.completedAttempts)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_KPI_SCORE,
                0xFF00BCD4, 0xFF00838F, R.drawable.msg_info,
                "Average Score",
                "Across counted submissions",
                String.format(Locale.US, "%.1f", snapshot.averageScorePct)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_KPI_REVENUE,
                0xFF4CAF50, 0xFF2E7D32, R.drawable.settings_premium,
                "Revenue",
                snapshot.paidTransactions + " paid orders completed",
                "₹" + snapshot.totalRevenueInr));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_KPI_PENDING,
                0xFFFFA000, 0xFFE65100, R.drawable.msg_retry,
                "Pending Payments",
                snapshot.totalTransactions + " total checkout orders",
                String.valueOf(snapshot.pendingTransactions)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_KPI_PREMIUM,
                0xFFB659FF, 0xFF617CFF, R.drawable.msg_groups,
                "Premium Subscribers",
                "Active EVE Premium memberships",
                String.valueOf(snapshot.activePremiumUsers)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_KPI_MODERATION,
                snapshot.pendingReports > 0 ? 0xFFE53935 : 0xFF607D8B,
                snapshot.pendingReports > 0 ? 0xFFB71C1C : 0xFF37474F,
                R.drawable.settings_privacy,
                "Pending Moderation",
                snapshot.pendingReports > 0 ? "Flagged question reports awaiting review" : "No pending question flags",
                String.valueOf(snapshot.pendingReports)));
        items.add(UItem.asShadow(null));

        // B & C. EXAMS & QUESTION BANK
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_EXAMS)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_SYLLABUS)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_GENERATED_TESTS)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_QUESTIONS)) {
            items.add(UItem.asHeader("B & C. Exams, Syllabus & Question Bank"));
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_EXAMS)
                    || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_SYLLABUS)) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_EXAMS,
                        0xFFFF9800, 0xFFEF6C00, R.drawable.msg_list,
                        "Exams & Syllabus",
                        "Edit, publish, pause, archive & syllabus",
                        String.valueOf(snapshot.totalExams)));
            }
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_GENERATED_TESTS)) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_GENERATE_TEST,
                        0xFF9C27B0, 0xFF6A1B9A, R.drawable.msg_add,
                        "Generate AI Test",
                        "Create & schedule new mock test sets",
                        String.valueOf(snapshot.totalTests)));
            }
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_QUESTIONS)) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_QUESTIONS,
                        0xFF2196F3, 0xFF1565C0, R.drawable.msg_stats,
                        "Question Bank",
                        "Add, edit, AI-format & bulk JSON import",
                        null));
            }
            items.add(UItem.asShadow(null));
        }

        // D, F & G. STUDENTS, PAYMENTS & EVE PREMIUM
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_USERS)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_PREMIUM)) {
            items.add(UItem.asHeader("D, F & G. Students, Payments & Premium"));
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_USERS)) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_STUDENTS,
                        0xFF4CAF50, 0xFF2E7D32, R.drawable.settings_account,
                        "Student Directory",
                        "Search, suspend/enable & inspect attempts",
                        String.valueOf(snapshot.totalStudents)));
            }
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_PREMIUM)) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_PAYMENTS,
                        0xFF009688, 0xFF00695C, R.drawable.msg_stats,
                        "Payment Orders",
                        "Filter paid, pending, failed & expired",
                        String.valueOf(snapshot.totalTransactions)));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_PREMIUM,
                        0xFFB659FF, 0xFF617CFF, R.drawable.settings_premium,
                        "Premium & UPI",
                        "Pricing, duration, VPA & checkout config",
                        null));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_PREMIUM_USERS,
                        0xFFFF7043, 0xFFD84315, R.drawable.msg_groups,
                        "Premium Users",
                        "View active subscribers, extend or revoke",
                        String.valueOf(snapshot.activePremiumUsers)));
            }
            items.add(UItem.asShadow(null));
        }

        // H, I & J. CONTENT, NOTIFICATIONS & PACKS
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_CONTENT)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_SEND_BROADCASTS)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MODERATE_FEEDBACK)) {
            items.add(UItem.asHeader("H, I & J. Content, Polls & Media"));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_CONTENT_COMMS,
                    0xFF3F51B5, 0xFF283593, R.drawable.settings_sounds,
                    "Content & Moderation",
                    "Banners, polls, broadcasts & question flags",
                    snapshot.pendingReports > 0 ? String.valueOf(snapshot.pendingReports) : null));
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_CONTENT)) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_PACK_MANAGER,
                        0xFFFF5722, 0xFFE64A19, R.drawable.msg_sticker,
                        LocaleController.getString(R.string.EveAdminPackManager),
                        LocaleController.getString(R.string.EveAdminPackManagerSubtitle),
                        null));
            }
            items.add(UItem.asShadow(null));
        }

        // E, K, L & M. ANALYTICS, ROLES, APP CONFIG & SYSTEM HEALTH
        items.add(UItem.asHeader("E, K, L & M. Analytics, Roles & Health"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_MOD_INSIGHTS_SYSTEM,
                0xFF00BCD4, 0xFF00838F, R.drawable.settings_devices,
                "Insights & System",
                "Analytics, crashes, export, config & Worker/D1/R2",
                null));
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_ADMINS_RIGHTS)) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_ADMINS,
                    0xFF607D8B, 0xFF37474F, R.drawable.settings_privacy,
                    "Admin Roles",
                    "Manage admin emails, titles & 13 rights",
                    null));
        }
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_VIEW_ANALYTICS_LOGS)) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_AUDIT,
                    0xFF795548, 0xFF4E342E, R.drawable.msg_list,
                    "Audit Log",
                    "Recent server-verified admin operations",
                    snapshot.recentActions.isEmpty() ? null : String.valueOf(snapshot.recentActions.size())));
        }
        items.add(UItem.asShadow(null));

        // RECENT SERVER ACTIVITY (compact preview)
        items.add(UItem.asHeader("Recent Activity"));
        if (snapshot.recentActions.isEmpty()) {
            items.add(UItem.asShadow("No recent admin operations recorded yet."));
        } else {
            int previewLimit = Math.min(snapshot.recentActions.size(), 3);
            for (int i = 0; i < previewLimit; i++) {
                EveAdminModels.AuditLogEntry entry = snapshot.recentActions.get(i);
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_AUDIT,
                        0, 0, 0,
                        formatActionTitle(entry.actionType),
                        entry.description,
                        null));
            }
            items.add(UItem.asShadow("Tap any activity entry above to open full audit log."));
        }
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
            case ID_KPI_EXAMS:
            case ID_KPI_TESTS:
            case ID_MOD_EXAMS:
                presentFragment(new EveAdminExamsFragment());
                break;
            case ID_MOD_GENERATE_TEST:
                EveAdminDialogs.showGenerateTestDialog(this, repo, getExams(), this::refreshDashboard);
                break;
            case ID_MOD_QUESTIONS:
                EveAdminDialogs.showQuestionBankPicker(this, repo, getExams());
                break;
            case ID_KPI_STUDENTS:
            case ID_MOD_STUDENTS:
                EveAdminDialogs.showStudentSearchDialog(this, repo, this::refreshDashboard);
                break;
            case ID_KPI_REVENUE:
            case ID_KPI_PENDING:
            case ID_MOD_PAYMENTS:
                EveAdminDialogs.showTransactionsDialog(this, repo);
                break;
            case ID_MOD_PREMIUM:
                EveAdminDialogs.showPricingAndUpiDialog(this, repo, this::refreshDashboard);
                break;
            case ID_KPI_PREMIUM:
            case ID_MOD_PREMIUM_USERS:
                EveAdminDialogs.showPremiumUsersDialog(this, repo, this::refreshDashboard);
                break;
            case ID_KPI_MODERATION:
            case ID_MOD_CONTENT_COMMS:
                presentFragment(new EveAdminContentAndCommsFragment(profile));
                break;
            case ID_MOD_PACK_MANAGER:
                presentFragment(new EveAdminPackManagerFragment());
                break;
            case ID_KPI_ATTEMPTS:
            case ID_KPI_SCORE:
            case ID_MOD_INSIGHTS_SYSTEM:
                presentFragment(new EveAdminInsightsAndSystemFragment(profile));
                break;
            case ID_MOD_ADMINS:
                EveAdminDialogs.showAdminsManager(this, repo, this::refreshDashboard);
                break;
            case ID_MOD_AUDIT:
                EveAdminDialogs.showAuditLogDialog(this, repo);
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

    private List<EveAdminModels.AdminExamItem> getExams() {
        return snapshot != null ? snapshot.exams : Collections.emptyList();
    }

    private static String formatActionTitle(String rawType) {
        if (rawType == null || rawType.trim().isEmpty()) {
            return "Admin Action";
        }
        String normalized = rawType.trim().replace('_', ' ').toLowerCase(Locale.US);
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }
}
