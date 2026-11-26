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
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
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
    private String loadError = "";
    private int lastHttpStatus;
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
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.EveAdminControlCenter));
        actionBar.setSubtitle("Worker + D1 Ecosystem Control");
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
        if (latestCached != null && latestCached != snapshot) {
            snapshot = latestCached;
            updateList();
        }
    }

    private void refreshDashboard() {
        loading = true;
        loadError = "";
        updateList();
        repo.loadDashboardAsync(new EveAdminRepository.ResultCallback<EveAdminModels.DashboardSnapshot>() {
            @Override
            public void onSuccess(EveAdminModels.DashboardSnapshot data) {
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
                loading = false;
                loadError = message != null ? message : "Unable to load admin dashboard.";
                lastHttpStatus = httpStatus;
                updateList();
            }
        });
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveUserProfile cached = EveAuthManager.getInstance().getCachedProfile();
        String accountLabel = cached != null && !cached.email.isEmpty() ? cached.email : "Not signed in";

        items.add(UItem.asHeader("Admin Session & Server Authorization"));
        String sessionSubtitle;
        String sessionBadge = null;
        if (loading && snapshot == null) {
            sessionSubtitle = "Syncing live dashboard metrics for " + accountLabel + "...";
        } else if (snapshot != null && snapshot.adminProfile != null) {
            sessionSubtitle = (loading ? "Refreshing • " : "Verified • ") + snapshot.adminProfile.email;
            sessionBadge = snapshot.adminProfile.customTitle + " (" + snapshot.adminProfile.rights.size() + ")";
        } else {
            sessionSubtitle = accountLabel;
        }
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_ADMIN_REFRESH,
                0xFF3D8BEB, 0xFF2A6FD9, R.drawable.msg_retry,
                loading ? "Syncing Admin Dashboard..." : "Refresh Dashboard",
                sessionSubtitle,
                sessionBadge));
        if (lastHttpStatus == 401 || lastHttpStatus == 403 || cached == null) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_ADMIN_SIGN_IN,
                    0xFF56C35A, 0xFF2FA138, R.drawable.settings_account,
                    "Sign In with EVE Google Admin Account",
                    accountLabel,
                    null));
        }
        if (!loadError.isEmpty()) {
            items.add(UItem.asShadow("Status (" + (lastHttpStatus > 0 ? "HTTP " + lastHttpStatus : "Offline") + "): " + loadError));
        } else {
            items.add(UItem.asShadow("Server-verified RBAC enforced on Cloudflare Worker + D1."));
        }

        if (snapshot == null || snapshot.adminProfile == null) {
            return;
        }
        EveAdminModels.AdminProfile profile = snapshot.adminProfile;

        // A. DASHBOARD OVERVIEW
        items.add(UItem.asHeader("A. Dashboard Overview"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_MOD_STUDENTS, 0, 0, 0,
                "Students",
                snapshot.totalStudents + " registered • " + snapshot.activeStudents + " active",
                String.valueOf(snapshot.totalStudents)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_MOD_EXAMS, 0, 0, 0,
                "Exams & Mock Tests",
                snapshot.publishedExams + "/" + snapshot.totalExams + " live exams • "
                        + snapshot.liveTests + "/" + snapshot.totalTests + " live tests",
                snapshot.publishedExams + "/" + snapshot.totalExams));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_MOD_INSIGHTS_SYSTEM, 0, 0, 0,
                "Attempts & Average Score",
                snapshot.completedAttempts + " completed • Avg " + String.format(Locale.US, "%.1f", snapshot.averageScorePct),
                String.valueOf(snapshot.completedAttempts)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_MOD_PAYMENTS, 0, 0, 0,
                "Revenue & Pending Payments",
                snapshot.paidTransactions + " paid • " + snapshot.pendingTransactions + " pending • " + snapshot.activePremiumUsers + " premium",
                "₹" + snapshot.totalRevenueInr));
        if (snapshot.pendingReports > 0) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_CONTENT_COMMS, 0, 0, 0,
                    "Pending Moderation Alerts",
                    snapshot.pendingReports + " flagged question reports awaiting review",
                    String.valueOf(snapshot.pendingReports)));
        }
        items.add(UItem.asShadow(buildRecentActionsSummary(snapshot.recentActions)));

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
                        "Exam & Syllabus Manager",
                        "Edit, duplicate, preview, publish, pause, archive & syllabus",
                        snapshot.totalExams + " exams"));
            }
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_GENERATED_TESTS)) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_GENERATE_TEST,
                        0xFF9C27B0, 0xFF6A1B9A, R.drawable.msg_add,
                        "Generate AI Mock Test",
                        "Create & schedule new mock test sets",
                        snapshot.generatedTests.size() + " tests"));
            }
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_QUESTIONS)) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_QUESTIONS,
                        0xFF2196F3, 0xFF1565C0, R.drawable.msg_stats,
                        "Question Bank & Bulk JSON Import",
                        "Add, edit, AI-format explanations & import",
                        null));
            }
            items.add(UItem.asShadow(null));
        }

        // D, F & G. STUDENTS, PAYMENTS & EVE PREMIUM
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_USERS)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_PREMIUM)) {
            items.add(UItem.asHeader("D, F & G. Students, Payments & EVE Premium"));
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_USERS)) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_STUDENTS,
                        0xFF4CAF50, 0xFF2E7D32, R.drawable.settings_account,
                        "Student Directory & Attempts",
                        "Search, suspend/enable, grant premium & inspect attempts",
                        snapshot.totalStudents + " users"));
            }
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_PREMIUM)) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_PAYMENTS,
                        0xFF009688, 0xFF00695C, R.drawable.msg_stats,
                        "Payment Orders & Verification",
                        "Filter paid, pending, failed & expired orders",
                        snapshot.totalTransactions + " orders"));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_PREMIUM,
                        0xFFB659FF, 0xFF617CFF, R.drawable.settings_premium,
                        "EVE Premium Pricing, UPI VPA & QR",
                        "Edit plan pricing, duration & checkout settings",
                        null));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_MOD_PREMIUM_USERS,
                        0xFFFF7043, 0xFFD84315, R.drawable.settings_premium,
                        "Premium Subscribers",
                        "View active subscribers, extend or revoke",
                        snapshot.activePremiumUsers + " active"));
            }
            items.add(UItem.asShadow(null));
        }

        // H, I & J. CONTENT, NOTIFICATIONS & PACKS
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_CONTENT)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_SEND_BROADCASTS)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MODERATE_FEEDBACK)) {
            items.add(UItem.asHeader("H, I & J. Content, Notifications & Media Packs"));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_CONTENT_COMMS,
                    0xFF3F51B5, 0xFF283593, R.drawable.settings_sounds,
                    "Content, Banners, Polls & Moderation",
                    "Banners, floating link, broadcasts, flagged questions, feedback",
                    snapshot.pendingReports > 0 ? snapshot.pendingReports + " flagged" : null));
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
        items.add(UItem.asHeader("E, K, L & M. Analytics, Roles, Config & Health"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_MOD_INSIGHTS_SYSTEM,
                0xFF00BCD4, 0xFF00838F, R.drawable.settings_devices,
                "Analytics, Crash Reports, Config & Health",
                "Scores, D1 crashes, AI usage, export JSON, maintenance & Worker/D1/R2",
                null));
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_ADMINS_RIGHTS)) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_ADMINS,
                    0xFF607D8B, 0xFF37474F, R.drawable.settings_privacy,
                    "Admin Users & RBAC Permissions",
                    "Manage admin emails, custom titles & 13 granular rights",
                    null));
        }
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_VIEW_ANALYTICS_LOGS)) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_MOD_AUDIT,
                    0xFF795548, 0xFF4E342E, R.drawable.msg_list,
                    "Server Audit Log",
                    "Recent server-verified admin operations",
                    snapshot.recentActions.size() + " recent"));
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
            case ID_MOD_GENERATE_TEST:
                EveAdminDialogs.showGenerateTestDialog(this, repo, getExams(), this::refreshDashboard);
                break;
            case ID_MOD_QUESTIONS:
                EveAdminDialogs.showQuestionBankPicker(this, repo, getExams());
                break;
            case ID_MOD_STUDENTS:
                EveAdminDialogs.showStudentSearchDialog(this, repo, this::refreshDashboard);
                break;
            case ID_MOD_PAYMENTS:
                EveAdminDialogs.showTransactionsDialog(this, repo);
                break;
            case ID_MOD_PREMIUM:
                EveAdminDialogs.showPricingAndUpiDialog(this, repo, this::refreshDashboard);
                break;
            case ID_MOD_PREMIUM_USERS:
                EveAdminDialogs.showPremiumUsersDialog(this, repo, this::refreshDashboard);
                break;
            case ID_MOD_CONTENT_COMMS:
                presentFragment(new EveAdminContentAndCommsFragment(profile));
                break;
            case ID_MOD_PACK_MANAGER:
                presentFragment(new EveAdminPackManagerFragment());
                break;
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

    private static String buildRecentActionsSummary(List<EveAdminModels.AuditLogEntry> actions) {
        if (actions == null || actions.isEmpty()) {
            return "Recent admin actions: none recorded yet.";
        }
        StringBuilder sb = new StringBuilder("Recent admin actions:");
        int limit = Math.min(actions.size(), 3);
        for (int i = 0; i < limit; i++) {
            EveAdminModels.AuditLogEntry e = actions.get(i);
            sb.append("\n• [").append(e.actionType).append("] ").append(e.description);
        }
        return sb.toString();
    }
}
