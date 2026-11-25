package org.telegram.ui.eve.admin;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.EveCrashDiagnostics;
import org.telegram.messenger.EveInspectionMode;
import org.telegram.messenger.EveLoadingTracker;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.NativeLoader;
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
import org.telegram.ui.DebugSettingsActivity;
import org.telegram.ui.eve.auth.EveAuthCoordinator;
import org.telegram.ui.eve.auth.EveAuthManager;
import org.telegram.ui.eve.auth.EveUserProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Real server-backed EVE Admin Control Center powered by Cloudflare Worker + D1.
 * Enforces server-verified Firebase auth + D1 RBAC permissions on every administrative action,
 * while preserving DEBUG-only local diagnostics cleanly in debug builds.
 */
public class EveAdminControlCenterActivity extends BaseFragment {

    private static final int ID_ADMIN_REFRESH = 1001;
    private static final int ID_ADMIN_SIGN_IN = 1002;

    private static final int ID_EXAM_CREATE = 1101;
    private static final int ID_EXAM_EDIT_PICKER = 1102;
    private static final int ID_EXAM_GENERATE_NOW = 1103;
    private static final int ID_EXAM_TESTS_MANAGE = 1104;
    private static final int ID_QUESTION_BANK = 1201;
    private static final int ID_STUDENT_MANAGE = 1301;
    private static final int ID_PREMIUM_CONFIG = 1401;
    private static final int ID_PAYMENTS_TRANSACTIONS = 1402;
    private static final int ID_ADMIN_ROLES = 1501;
    private static final int ID_AUDIT_LOGS = 1502;

    private static final int ID_DEBUG_CRASH = 2001;
    private static final int ID_DEBUG_LOADING = 2002;
    private static final int ID_DEBUG_EVENTS = 2003;
    private static final int ID_DEBUG_EXPORT = 2004;
    private static final int ID_DEBUG_RLOTTIE = 2005;
    private static final int ID_DEBUG_SETTINGS = 2006;
    private static final int ID_DEBUG_CACHE_CLEAR = 2007;

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
        actionBar.setTitle("EVE Admin Control Center");
        actionBar.setSubtitle("Worker + D1 Ecosystem Control");
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

        refreshDashboard();
        return frameLayout;
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

        // A. AUTHENTICATION & ADMIN ROLE
        items.add(UItem.asHeader("Admin Session & Server Authorization"));
        items.add(UItem.asButton(ID_ADMIN_REFRESH,
                loading ? "Syncing Dashboard Metrics..." : "Refresh Dashboard from EVE Cloud",
                snapshot != null && snapshot.adminProfile != null
                        ? snapshot.adminProfile.customTitle + " (" + snapshot.adminProfile.rights.size() + " rights)"
                        : accountLabel));
        if (snapshot == null || lastHttpStatus == 401 || lastHttpStatus == 403 || cached == null) {
            items.add(UItem.asButton(ID_ADMIN_SIGN_IN, "Sign In with EVE Google Admin Account", accountLabel));
        }
        if (!loadError.isEmpty()) {
            items.add(UItem.asShadow("Status (" + (lastHttpStatus > 0 ? "HTTP " + lastHttpStatus : "Offline") + "): " + loadError));
        } else {
            items.add(UItem.asShadow("All dashboard data, exam changes, payments, and roles are enforced server-side by Cloudflare Worker + D1."));
        }

        if (snapshot != null && snapshot.adminProfile != null) {
            EveAdminModels.AdminProfile profile = snapshot.adminProfile;

            // A2. OVERVIEW METRICS
            items.add(UItem.asHeader("A. Admin Dashboard Overview"));
            items.add(UItem.asButton(ID_STUDENT_MANAGE, "Registered Students",
                    snapshot.totalStudents + " total • " + snapshot.activeStudents + " active"));
            items.add(UItem.asButton(ID_EXAM_EDIT_PICKER, "Exam Catalog",
                    snapshot.totalExams + " total • " + snapshot.publishedExams + " published • " + snapshot.draftExams + " paused"));
            items.add(UItem.asButton(ID_EXAM_TESTS_MANAGE, "Generated Mock Tests",
                    snapshot.totalTests + " total • " + snapshot.liveTests + " live • " + snapshot.draftTests + " draft"));
            items.add(UItem.asButton(ID_AUDIT_LOGS, "Completed Attempts", String.valueOf(snapshot.completedAttempts)));
            items.add(UItem.asButton(ID_PAYMENTS_TRANSACTIONS, "Revenue & Transactions",
                    "₹" + snapshot.totalRevenueInr + " (" + snapshot.paidTransactions + "/" + snapshot.totalTransactions + " paid)"));
            items.add(UItem.asShadow(buildRecentActionsSummary(snapshot.recentActions)));

            // B. EXAM ECOSYSTEM MANAGEMENT
            items.add(UItem.asHeader("B. Exam Ecosystem & AI Generator"));
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_EXAMS)) {
                items.add(UItem.asButton(ID_EXAM_CREATE, "Create New Exam"));
                items.add(UItem.asButton(ID_EXAM_EDIT_PICKER, "Edit Exams & Publish Mode", snapshot.exams.size() + " exams"));
            }
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_GENERATED_TESTS)) {
                items.add(UItem.asButton(ID_EXAM_GENERATE_NOW, "Generate Mock Test Now (AI Engine)"));
                items.add(UItem.asButton(ID_EXAM_TESTS_MANAGE, "Publish / Pause / Delete Tests", snapshot.generatedTests.size() + " tests"));
            }
            items.add(UItem.asShadow("Changes write directly to D1 via /api/exams and /api/generated-tests with atomic audit logging."));

            // C. QUESTION BANK MANAGEMENT
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_QUESTIONS)) {
                items.add(UItem.asHeader("C. Question Bank Management"));
                items.add(UItem.asButton(ID_QUESTION_BANK, "Manage Questions by Exam", snapshot.exams.size() + " exams"));
                items.add(UItem.asShadow("Supports browsing, creating, editing, validating answers, and deleting D1 questions."));
            }

            // D. STUDENT MANAGEMENT
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_USERS)) {
                items.add(UItem.asHeader("D. Student Management"));
                items.add(UItem.asButton(ID_STUDENT_MANAGE, "Search Students, Attempts & Account Status", snapshot.totalStudents + " users"));
                items.add(UItem.asShadow("Inspect student attempt history, suspend/enable accounts, or grant/revoke Premium."));
            }

            // E & F. PRICING, PLANS & UPI/QR PAYMENTS
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_PREMIUM)) {
                items.add(UItem.asHeader("E & F. Pricing, Plans & UPI / QR Payments"));
                items.add(UItem.asButton(ID_PREMIUM_CONFIG, "Configure Plan Pricing, UPI VPA & QR Checkout"));
                items.add(UItem.asButton(ID_PAYMENTS_TRANSACTIONS, "View Payment Orders & Revenue", snapshot.totalTransactions + " orders"));
                items.add(UItem.asShadow("Webhook secrets remain server-only on Cloudflare Worker; clients only manage merchant VPA and pricing rules."));
            }

            // G. SECURITY, ROLES & AUDIT LOG
            items.add(UItem.asHeader("G. Security, Admin Roles & Audit Trail"));
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_ADMINS_RIGHTS)) {
                items.add(UItem.asButton(ID_ADMIN_ROLES, "Manage Administrators & Permissions"));
            }
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_VIEW_ANALYTICS_LOGS)) {
                items.add(UItem.asButton(ID_AUDIT_LOGS, "View Server Audit Log", snapshot.recentActions.size() + " recent"));
            }
            items.add(UItem.asShadow(null));
        }

        // H. LOCAL DIAGNOSTICS (DEBUG BUILDS ONLY)
        if (EveInspectionMode.isEnabledForCurrentBuild()) {
            Context ctx = getContext();
            EveAdminConfig.CacheStats stats = EveAdminConfig.inspectCache(ctx);
            items.add(UItem.asHeader("H. Local Diagnostics (DEBUG Build Only)"));
            items.add(UItem.asButton(ID_DEBUG_CRASH, "Crash Reports & ApplicationExitInfo"));
            items.add(UItem.asButton(ID_DEBUG_LOADING, "Loading States Viewer",
                    EveLoadingTracker.countNonTerminal() + " active"));
            items.add(UItem.asButton(ID_DEBUG_EVENTS, "Recent Nav / Network Events",
                    String.valueOf(EveCrashDiagnostics.getRecentEvents().size())));
            items.add(UItem.asButton(ID_DEBUG_EXPORT, "Export Redacted Diagnostic Report"));
            items.add(UItem.asButton(ID_DEBUG_RLOTTIE, "Run RLottie Native Probe", NativeLoader.loaded() ? "Native OK" : "Not Loaded"));
            items.add(UItem.asButton(ID_DEBUG_CACHE_CLEAR, "Clear Local Cache",
                    String.format(Locale.US, "%d files (%.1f MB)", stats.fileCount, stats.totalBytes / (1024f * 1024f))));
            items.add(UItem.asButton(ID_DEBUG_SETTINGS, "Open Telegram Debug Settings"));
            items.add(UItem.asShadow("DEBUG diagnostics grant zero production backend authority."));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (!EveAdminConfig.isAdminAvailable()) {
            return;
        }
        switch (item.id) {
            case ID_ADMIN_REFRESH:
                refreshDashboard();
                break;
            case ID_ADMIN_SIGN_IN:
                startAdminSignIn();
                break;
            case ID_EXAM_CREATE:
                EveAdminDialogs.showCreateOrEditExamDialog(this, repo, null, this::refreshDashboard);
                break;
            case ID_EXAM_EDIT_PICKER:
                pickExamToEdit();
                break;
            case ID_EXAM_GENERATE_NOW:
                EveAdminDialogs.showGenerateTestDialog(this, repo, getExams(), this::refreshDashboard);
                break;
            case ID_EXAM_TESTS_MANAGE:
                EveAdminDialogs.showGeneratedTestsManager(this, repo, getGeneratedTests(), this::refreshDashboard);
                break;
            case ID_QUESTION_BANK:
                EveAdminDialogs.showQuestionBankPicker(this, repo, getExams());
                break;
            case ID_STUDENT_MANAGE:
                EveAdminDialogs.showStudentSearchDialog(this, repo, this::refreshDashboard);
                break;
            case ID_PREMIUM_CONFIG:
                EveAdminDialogs.showPricingAndUpiDialog(this, repo, this::refreshDashboard);
                break;
            case ID_PAYMENTS_TRANSACTIONS:
                EveAdminDialogs.showTransactionsDialog(this, repo);
                break;
            case ID_ADMIN_ROLES:
                EveAdminDialogs.showAdminsManager(this, repo, this::refreshDashboard);
                break;
            case ID_AUDIT_LOGS:
                EveAdminDialogs.showAuditLogDialog(this, repo);
                break;

            case ID_DEBUG_CRASH:
                showInfoDialog("Crash Reports & Exit Reasons",
                        "=== Process Exit Reasons ===\n"
                                + EveCrashDiagnostics.getLastExitSummary(getContext())
                                + "\n\n=== Last Uncaught Java Crash ===\n"
                                + EveCrashDiagnostics.getLastCrashReport(getContext()));
                break;
            case ID_DEBUG_LOADING:
                showLoadingStatesDialog();
                break;
            case ID_DEBUG_EVENTS:
                showEventsDialog();
                break;
            case ID_DEBUG_EXPORT:
                exportDiagnosticReport();
                break;
            case ID_DEBUG_RLOTTIE:
                runRLottieProbe();
                break;
            case ID_DEBUG_CACHE_CLEAR: {
                int removed = EveAdminConfig.clearSafeCache(getContext());
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Cleared " + removed + " cached files.").show();
                updateList();
                break;
            }
            case ID_DEBUG_SETTINGS:
                presentFragment(new DebugSettingsActivity());
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

    private void pickExamToEdit() {
        Activity activity = getParentActivity();
        List<EveAdminModels.AdminExamItem> exams = getExams();
        if (activity == null) return;
        if (exams.isEmpty()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "No exams available. Create one first.").show();
            return;
        }
        String[] labels = new String[exams.size()];
        for (int i = 0; i < exams.size(); i++) {
            EveAdminModels.AdminExamItem ex = exams.get(i);
            labels[i] = ex.examName + " [" + ex.publishMode.toUpperCase(Locale.US) + "] • " + ex.timeLimitMinutes + "m / " + ex.questionCount + " Qs";
        }
        new AlertDialog.Builder(activity)
                .setTitle("Select Exam to Edit")
                .setItems(labels, (d, which) -> EveAdminDialogs.showCreateOrEditExamDialog(this, repo, exams.get(which), this::refreshDashboard))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private List<EveAdminModels.AdminExamItem> getExams() {
        return snapshot != null ? snapshot.exams : Collections.emptyList();
    }

    private List<EveAdminModels.AdminGeneratedTestItem> getGeneratedTests() {
        return snapshot != null ? snapshot.generatedTests : Collections.emptyList();
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

    private void runRLottieProbe() {
        try {
            RLottieDrawable probe = new RLottieDrawable(R.raw.sun_outline, AndroidUtilities.dp(28), AndroidUtilities.dp(28), true, null);
            int frames = probe.getFramesCount();
            boolean loaded = NativeLoader.loaded();
            probe.recycle(false);
            showInfoDialog("RLottie Native Probe",
                    "Native lib loaded: " + loaded + "\n"
                            + "Decoded frames: " + frames + "\n"
                            + "Status: " + (frames > 0 ? "PASS" : "FAIL"));
        } catch (Throwable t) {
            showInfoDialog("RLottie Probe Error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void showLoadingStatesDialog() {
        List<EveLoadingTracker.Entry> entries = EveLoadingTracker.snapshot();
        StringBuilder sb = new StringBuilder();
        if (entries.isEmpty()) {
            sb.append("No tracked loading states recorded yet.");
        } else {
            for (EveLoadingTracker.Entry e : entries) {
                sb.append("• ").append(e.module).append(": ").append(e.outcome).append(" (").append(e.detail).append(")\n");
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
        intent.putExtra(Intent.EXTRA_SUBJECT, "EVE Diagnostic Report (Redacted)");
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
