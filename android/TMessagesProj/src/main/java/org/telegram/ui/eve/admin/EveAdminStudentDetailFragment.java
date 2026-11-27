package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen Student Profile & Account Management screen for EVE Admin Control Center.
 */
public class EveAdminStudentDetailFragment extends BaseFragment {

    private static final int ID_INFO_EMAIL = 101;
    private static final int ID_INFO_EVE_ID = 102;
    private static final int ID_INFO_USERNAME = 103;
    private static final int ID_INFO_PHONE = 104;
    private static final int ID_INFO_CATEGORY = 105;
    private static final int ID_INFO_BIO = 106;
    private static final int ID_INFO_LAST_ACTIVE = 107;
    private static final int ID_INFO_JOINED = 108;

    private static final int ID_ACT_TOGGLE_DISABLE = 201;
    private static final int ID_ACT_GRANT_PREMIUM = 202;
    private static final int ID_ACT_EXTEND_PREMIUM = 203;
    private static final int ID_ACT_REVOKE_PREMIUM = 204;

    private static final int ID_PERF_RETRY = 301;
    private static final int ID_PERF_TOTAL = 302;
    private static final int ID_PERF_SCORE = 303;
    private static final int ID_PERF_ACCURACY = 304;
    private static final int ID_PERF_PROGRESS = 305;
    private static final int ID_VIEW_FULL_ATTEMPTS = 306;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private final Runnable onStudentMutated;
    private EveAdminModels.AdminStudentItem student;
    private EveAdminModels.AdminProfile adminProfile;

    private UniversalRecyclerView listView;
    private StudentHeaderView headerView;
    private final List<EveAdminModels.StudentAttemptItem> attempts = new ArrayList<>();
    private boolean loadingAttempts = true;
    private String attemptsError = "";
    private boolean destroyed = false;
    private final EveAdminUiChrome.AutoSyncController autoSync = new EveAdminUiChrome.AutoSyncController(this::loadAttempts);

    public EveAdminStudentDetailFragment(EveAdminModels.AdminStudentItem student, Runnable onStudentMutated) {
        this.student = student;
        this.onStudentMutated = onStudentMutated;
    }

    @Override
    public boolean onFragmentCreate() {
        if (!EveAdminConfig.isAdminAvailable() || student == null) {
            return false;
        }
        EveAdminModels.DashboardSnapshot cached = repo.getCachedSnapshot();
        if (cached != null) {
            adminProfile = cached.adminProfile;
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

        headerView = new StudentHeaderView(context, getResourceProvider());
        headerView.bind(student);

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Student Profile",
                null,
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
                () -> {
                    if (headerView != null) {
                        headerView.updateColors();
                    }
                }
        );
        listView = chrome.listView;

        fragmentView = chrome.rootView;
        if (adminProfile == null) {
            repo.loadDashboardAsync(new EveAdminRepository.ResultCallback<EveAdminModels.DashboardSnapshot>() {
                @Override
                public void onSuccess(EveAdminModels.DashboardSnapshot data) {
                    if (!destroyed && data != null) {
                        adminProfile = data.adminProfile;
                        updateList();
                    }
                }

                @Override
                public void onError(String message, int httpStatus) {
                }
            });
        }
        loadAttempts();
        return chrome.rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        autoSync.onResume();
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
        super.onFragmentDestroy();
    }

    private void loadAttempts() {
        if (destroyed || student == null) {
            return;
        }
        loadingAttempts = attempts.isEmpty();
        attemptsError = "";
        if (loadingAttempts) {
            updateList();
        }
        EveAdminRepository.ResultCallback<List<EveAdminModels.StudentAttemptItem>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.StudentAttemptItem>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.StudentAttemptItem> data) {
                        if (destroyed) return;
                        loadingAttempts = false;
                        attemptsError = "";
                        autoSync.markSynced();
                        attempts.clear();
                        if (data != null) {
                            attempts.addAll(data);
                        }
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loadingAttempts = false;
                        autoSync.markSynced();
                        attemptsError = message != null ? message : "Unable to load test attempts.";
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchStudentAttempts(token, student.id)));
    }

    private void updateList() {
        if (headerView != null && student != null) {
            headerView.bind(student);
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        // Header card
        items.add(UItem.asCustom(headerView));
        items.add(UItem.asShadow(null));

        // Card 1: Account Information
        items.add(UItem.asHeader("Account Information"));
        items.add(TextDetailCell.Factory.of(ID_INFO_EMAIL,
                !student.email.isEmpty() ? student.email : "Not set",
                "Email (tap to copy)"));
        items.add(TextDetailCell.Factory.of(ID_INFO_EVE_ID,
                !student.eveId.isEmpty() ? student.eveId : "Not assigned",
                "EVE ID (tap to copy)"));
        items.add(TextDetailCell.Factory.of(ID_INFO_USERNAME,
                !student.username.isEmpty() ? "@" + student.username : "Not set",
                student.username.isEmpty() ? "Username" : "Username (tap to copy)"));
        if (!student.phone.isEmpty()) {
            items.add(TextDetailCell.Factory.of(ID_INFO_PHONE,
                    student.phone,
                    "Phone (tap to copy)"));
        }
        items.add(TextDetailCell.Factory.of(ID_INFO_CATEGORY,
                student.category,
                "Exam Category"));
        if (!student.bio.isEmpty()) {
            items.add(TextDetailCell.Factory.of(ID_INFO_BIO,
                    student.bio,
                    "Bio"));
        }
        items.add(TextDetailCell.Factory.of(ID_INFO_LAST_ACTIVE,
                EveAdminUiChrome.formatDateTime(student.lastActive),
                "Last Active"));
        if (student.createdAt > 0) {
            items.add(TextDetailCell.Factory.of(ID_INFO_JOINED,
                    EveAdminUiChrome.formatDate(student.createdAt),
                    "Joined Date"));
        }
        items.add(UItem.asShadow(null));

        // Card 2: Account Actions
        boolean canManageUsers = adminProfile == null || EveAdminConfig.hasRight(adminProfile, EveAdminModels.RIGHT_MANAGE_USERS);
        boolean canManagePremium = adminProfile == null || EveAdminConfig.hasRight(adminProfile, EveAdminModels.RIGHT_MANAGE_PREMIUM);
        if (canManageUsers || canManagePremium) {
            items.add(UItem.asHeader("Account Actions"));
            if (canManageUsers) {
                IconBackgroundColors toggleBg = student.disabled ? IconBackgroundColors.GREEN : IconBackgroundColors.RED;
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_ACT_TOGGLE_DISABLE,
                        toggleBg.top, toggleBg.bottom,
                        student.disabled ? R.drawable.settings_account : R.drawable.msg_block,
                        student.disabled ? "Enable Account" : "Suspend Account",
                        student.disabled ? "Restore student login and test access" : "Block login and mock test access",
                        null));
            }
            if (canManagePremium) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_ACT_GRANT_PREMIUM,
                        0xFFB659FF, 0xFF617CFF,
                        R.drawable.settings_premium,
                        "Grant EVE Premium (30 Days)",
                        "Activate 30-day membership",
                        null));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_ACT_EXTEND_PREMIUM,
                        IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                        R.drawable.msg_add,
                        "Extend Premium (+30 Days)",
                        "Add 30 days to current subscription",
                        null));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_ACT_REVOKE_PREMIUM,
                        IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                        R.drawable.msg_delete,
                        "Revoke EVE Premium",
                        "Remove active premium membership",
                        null));
            }
            items.add(UItem.asShadow(null));
        }

        // Card 3: Performance & Progress
        items.add(UItem.asHeader("Performance & Progress"));
        if (!attemptsError.isEmpty() && attempts.isEmpty()) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_PERF_RETRY,
                    IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                    R.drawable.msg_retry,
                    "Retry Loading Performance",
                    attemptsError,
                    "Retry"));
        } else if (loadingAttempts) {
            items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
        } else if (attempts.isEmpty()) {
            items.add(UItem.asShadow("No test attempts recorded yet for this student."));
        } else {
            PerformanceSummary summary = PerformanceSummary.compute(attempts, student.lastActive);
            String lastTestPart = summary.latestTestTimestampMs > 0
                    ? " • Last test " + EveAdminUiChrome.formatDate(summary.latestTestTimestampMs)
                    : "";
            items.add(TextDetailCell.Factory.of(
                    ID_PERF_TOTAL,
                    summary.totalTests + (summary.totalTests == 1 ? " Test" : " Tests"),
                    "Total Tests Taken" + lastTestPart));
            items.add(TextDetailCell.Factory.of(
                    ID_PERF_SCORE,
                    String.format(Locale.US, "%.1f Avg  •  %.1f Best", summary.averageScore, summary.bestScore),
                    "Overall Average & Best Score"));
            items.add(TextDetailCell.Factory.of(
                    ID_PERF_ACCURACY,
                    String.format(Locale.US, "%.1f%% (%d / %d correct)",
                            summary.accuracyPercent, summary.totalCorrect, summary.totalAttemptedQuestions),
                    "Overall Accuracy"));
            items.add(TextDetailCell.Factory.of(
                    ID_PERF_PROGRESS,
                    summary.progressHeadline,
                    summary.progressSubtitle));
            items.add(UItem.asShadow(null));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_VIEW_FULL_ATTEMPTS,
                    IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                    R.drawable.msg_stats,
                    "View Full Attempt History (" + attempts.size() + ")",
                    "Inspect individual scores, accuracy and timestamps",
                    String.valueOf(attempts.size())));
            items.add(UItem.asShadow(null));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_INFO_EMAIL:
                copyField("Email", student.email);
                break;
            case ID_INFO_EVE_ID:
                copyField("EVE ID", student.eveId);
                break;
            case ID_INFO_USERNAME:
                if (!student.username.isEmpty()) {
                    copyField("Username", "@" + student.username);
                }
                break;
            case ID_INFO_PHONE:
                copyField("Phone", student.phone);
                break;
            case ID_ACT_TOGGLE_DISABLE:
                confirmToggleDisabled();
                break;
            case ID_ACT_GRANT_PREMIUM:
                runPremiumAction("Granting 30 days EVE Premium...",
                        token -> repo.grantPremium(token, student.id, "EVE Premium", 30, false),
                        "Granted 30 days EVE Premium.");
                break;
            case ID_ACT_EXTEND_PREMIUM:
                runPremiumAction("Extending premium...",
                        token -> repo.extendPremium(token, student.id, 30),
                        "Extended premium by 30 days.");
                break;
            case ID_ACT_REVOKE_PREMIUM:
                confirmRevokePremium();
                break;
            case ID_PERF_RETRY:
                loadAttempts();
                break;
            case ID_VIEW_FULL_ATTEMPTS:
                presentFragment(new AttemptHistoryFragment(student.displayName, new ArrayList<>(attempts)));
                break;
        }
    }

    private void copyField(String label, String value) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        AndroidUtilities.addToClipboard(value);
        BulletinFactory.of(this).createCopyBulletin(label + " copied").show();
    }

    private void confirmToggleDisabled() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        final boolean nextDisabled = !student.disabled;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle(nextDisabled ? "Suspend Student Account?" : "Enable Student Account?")
                .setMessage(nextDisabled
                        ? "Suspend " + student.displayName + "? They will be blocked from signing in and taking tests."
                        : "Re-enable " + student.displayName + " and restore full access?")
                .setPositiveButton(nextDisabled ? "Suspend" : "Enable", (d, w) -> {
                    EveAdminRepository.ResultCallback<String> cb = new EveAdminRepository.ResultCallback<String>() {
                        @Override
                        public void onSuccess(String msg) {
                            if (destroyed) return;
                            student = student.withDisabled(nextDisabled);
                            repo.invalidateCache();
                            updateList();
                            BulletinFactory.of(EveAdminStudentDetailFragment.this)
                                    .createSimpleBulletin(R.raw.contact_check, msg).show();
                            if (onStudentMutated != null) {
                                onStudentMutated.run();
                            }
                        }

                        @Override
                        public void onError(String message, int httpStatus) {
                            BulletinFactory.of(EveAdminStudentDetailFragment.this)
                                    .createErrorBulletin(message).show();
                        }
                    };
                    repo.runWithAdminToken(cb, token -> {
                        repo.setStudentDisabled(token, student.id, nextDisabled);
                        repo.postSuccess(cb, nextDisabled ? "Student account suspended." : "Student account enabled.");
                    });
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (nextDisabled) {
            builder.makeRed(AlertDialog.BUTTON_POSITIVE);
        }
        showDialog(builder.create());
    }

    private void confirmRevokePremium() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        showDialog(new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle("Revoke EVE Premium?")
                .setMessage("Remove active EVE Premium access for " + student.displayName + "?")
                .setPositiveButton("Revoke", (d, w) -> runPremiumAction(
                        "Revoking premium...",
                        token -> repo.revokePremium(token, student.id),
                        "Premium access revoked."))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .create());
    }

    private void runPremiumAction(String progressMsg, EveAdminRepository.TokenWork work, String successMsg) {
        EveAdminRepository.ResultCallback<String> cb = new EveAdminRepository.ResultCallback<String>() {
            @Override
            public void onSuccess(String msg) {
                if (destroyed) return;
                repo.invalidateCache();
                BulletinFactory.of(EveAdminStudentDetailFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, msg).show();
                if (onStudentMutated != null) {
                    onStudentMutated.run();
                }
            }

            @Override
            public void onError(String message, int httpStatus) {
                BulletinFactory.of(EveAdminStudentDetailFragment.this)
                        .createErrorBulletin(message).show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            work.run(token);
            repo.postSuccess(cb, successMsg);
        });
    }

    private static final class StudentHeaderView extends LinearLayout implements Theme.Colorable {

        private final Theme.ResourcesProvider resourcesProvider;
        private final AvatarDrawable avatarDrawable;
        private final BackupImageView avatarView;
        private final TextView nameView;
        private final TextView usernameView;
        private final TextView badgeView;
        private boolean isDisabled;

        StudentHeaderView(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(dp(20), dp(18), dp(20), dp(18));

            avatarDrawable = new AvatarDrawable();
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(36));
            addView(avatarView, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

            nameView = new TextView(context);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setGravity(Gravity.CENTER);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            usernameView = new TextView(context);
            usernameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            usernameView.setGravity(Gravity.CENTER);
            usernameView.setSingleLine(true);
            usernameView.setEllipsize(TextUtils.TruncateAt.END);
            addView(usernameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 10));

            badgeView = new TextView(context);
            badgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            badgeView.setTypeface(AndroidUtilities.bold());
            badgeView.setPadding(dp(12), dp(4), dp(12), dp(4));
            addView(badgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

            updateColors();
        }

        void bind(EveAdminModels.AdminStudentItem student) {
            this.isDisabled = student.disabled;
            EveAdminUiChrome.bindStudentAvatar(avatarView, avatarDrawable, student, 72);
            nameView.setText(student.displayName);
            usernameView.setText(!student.username.isEmpty() ? "@" + student.username : "No username");
            badgeView.setText(student.disabled ? "Suspended" : "Active");
            updateColors();
        }

        @Override
        public void updateColors() {
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            usernameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            if (isDisabled) {
                int red = Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);
                badgeView.setTextColor(red);
                badgeView.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.multAlpha(red, 0.14f)));
            } else {
                int activeColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider);
                badgeView.setTextColor(activeColor);
                badgeView.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.multAlpha(activeColor, 0.14f)));
            }
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_windowBackgroundWhiteBlackText);
            colors.add(Theme.key_windowBackgroundWhiteGrayText);
            colors.add(Theme.key_text_RedRegular);
            colors.add(Theme.key_windowBackgroundWhiteBlueText);
        }
    }

    public static final class PerformanceSummary {
        public final int totalTests;
        public final double averageScore;
        public final double bestScore;
        public final int totalCorrect;
        public final int totalAttemptedQuestions;
        public final double accuracyPercent;
        public final long latestTestTimestampMs;
        public final String progressHeadline;
        public final String progressSubtitle;

        private PerformanceSummary(
                int totalTests,
                double averageScore,
                double bestScore,
                int totalCorrect,
                int totalAttemptedQuestions,
                double accuracyPercent,
                long latestTestTimestampMs,
                String progressHeadline,
                String progressSubtitle) {
            this.totalTests = totalTests;
            this.averageScore = averageScore;
            this.bestScore = bestScore;
            this.totalCorrect = totalCorrect;
            this.totalAttemptedQuestions = totalAttemptedQuestions;
            this.accuracyPercent = accuracyPercent;
            this.latestTestTimestampMs = latestTestTimestampMs;
            this.progressHeadline = progressHeadline;
            this.progressSubtitle = progressSubtitle;
        }

        public static PerformanceSummary compute(List<EveAdminModels.StudentAttemptItem> attempts, long studentLastActiveMs) {
            if (attempts == null || attempts.isEmpty()) {
                return new PerformanceSummary(0, 0.0, 0.0, 0, 0, 0.0, 0L,
                        "No attempts yet",
                        "Last active " + EveAdminUiChrome.formatDate(studentLastActiveMs));
            }
            List<EveAdminModels.StudentAttemptItem> chronological = new ArrayList<>();
            for (EveAdminModels.StudentAttemptItem item : attempts) {
                if (item != null) chronological.add(item);
            }
            if (chronological.isEmpty()) {
                return new PerformanceSummary(0, 0.0, 0.0, 0, 0, 0.0, 0L,
                        "No attempts yet",
                        "Last active " + EveAdminUiChrome.formatDate(studentLastActiveMs));
            }
            Collections.sort(chronological, new Comparator<EveAdminModels.StudentAttemptItem>() {
                @Override
                public int compare(EveAdminModels.StudentAttemptItem a, EveAdminModels.StudentAttemptItem b) {
                    return Long.compare(a.timestamp, b.timestamp);
                }
            });

            int n = chronological.size();
            double sumScore = 0.0;
            double maxScore = -Double.MAX_VALUE;
            int sumCorrect = 0;
            int sumAttempted = 0;
            int sumTotalQs = 0;
            long latestTs = 0L;

            for (EveAdminModels.StudentAttemptItem a : chronological) {
                sumScore += a.score;
                if (a.score > maxScore) maxScore = a.score;
                sumCorrect += Math.max(0, a.correct);
                sumAttempted += Math.max(0, a.correct) + Math.max(0, a.wrong);
                sumTotalQs += Math.max(0, a.total);
                if (a.timestamp > latestTs) latestTs = a.timestamp;
            }
            if (maxScore == -Double.MAX_VALUE) maxScore = 0.0;

            double avg = sumScore / n;
            int denom = sumAttempted > 0 ? sumAttempted : sumTotalQs;
            double acc = denom > 0 ? (sumCorrect * 100.0) / denom : 0.0;

            long effectiveActiveMs = Math.max(studentLastActiveMs, latestTs);
            String activeLabel = EveAdminUiChrome.formatDate(effectiveActiveMs);

            String headline;
            String subtitle;
            if (n == 1) {
                headline = "Baseline Set";
                subtitle = "1 test completed • Last active " + activeLabel;
            } else {
                int recentCount = n > 5 ? 5 : Math.max(1, (n + 1) / 2);
                int earlierCount = n - recentCount;
                double earlierSum = 0.0;
                double recentSum = 0.0;
                for (int i = 0; i < earlierCount; i++) {
                    earlierSum += chronological.get(i).score;
                }
                for (int i = earlierCount; i < n; i++) {
                    recentSum += chronological.get(i).score;
                }
                double earlierAvg = earlierCount > 0 ? earlierSum / earlierCount : avg;
                double recentAvg = recentCount > 0 ? recentSum / recentCount : avg;
                double delta = recentAvg - earlierAvg;

                if (delta >= 2.0) {
                    headline = String.format(Locale.US, "Improving (+%.1f pts)", delta);
                } else if (delta <= -2.0) {
                    headline = String.format(Locale.US, "Needs Focus (%.1f pts)", delta);
                } else {
                    headline = "Steady Progress";
                }
                subtitle = String.format(Locale.US, "Recent %d avg %.1f vs earlier %.1f • Active %s",
                        recentCount, recentAvg, earlierAvg, activeLabel);
            }

            return new PerformanceSummary(
                    n, avg, maxScore, sumCorrect, denom, acc, latestTs, headline, subtitle);
        }
    }

    /**
     * Secondary screen opened only on explicit tap of "View Full Attempt History (N)".
     */
    public static final class AttemptHistoryFragment extends BaseFragment {
        private final String studentName;
        private final List<EveAdminModels.StudentAttemptItem> attemptItems;
        private UniversalRecyclerView listView;

        public AttemptHistoryFragment(String studentName, List<EveAdminModels.StudentAttemptItem> attemptItems) {
            this.studentName = !TextUtils.isEmpty(studentName) ? studentName : "Student";
            this.attemptItems = attemptItems != null ? attemptItems : Collections.emptyList();
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
                    "Attempt History",
                    studentName + " • " + attemptItems.size() + " tests",
                    false,
                    (items, adapter) -> {
                        EveAdminUiChrome.addTopSpace(items);
                        if (attemptItems.isEmpty()) {
                            items.add(UItem.asShadow("No test attempts recorded."));
                            return;
                        }
                        for (int i = 0; i < attemptItems.size(); i++) {
                            EveAdminModels.StudentAttemptItem a = attemptItems.get(i);
                            String sub = String.format(Locale.US, "✓ %d • ✗ %d • %s",
                                    a.correct, a.wrong, EveAdminUiChrome.formatDateTime(a.timestamp));
                            String scoreVal = String.format(Locale.US, "%.1f / %d", a.score, a.total);
                            items.add(SettingsActivity.SettingCell.Factory.of(
                                    1000 + i,
                                    IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                                    R.drawable.msg_stats,
                                    a.examName,
                                    sub,
                                    scoreVal));
                        }
                    },
                    (item, view, position, x, y) -> {},
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
    }
}
