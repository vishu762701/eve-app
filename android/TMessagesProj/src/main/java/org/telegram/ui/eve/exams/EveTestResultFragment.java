package org.telegram.ui.eve.exams;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.SimpleActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.admin.EveAnimatedEmojiHelper;
import org.telegram.ui.eve.admin.EveAdminUiChrome;
import org.telegram.ui.eve.auth.EveAuthManager;
import org.telegram.ui.eve.auth.EveUserProfile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Telegram-native Card Mode Performance & Answer Review screen.
 * Preserves server-graded metrics, bilingual explanations, bookmarking,
 * question reporting, leaderboard navigation, and reattempt confirmation.
 */
public class EveTestResultFragment extends BaseFragment {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_CORRECT = 1;
    private static final int FILTER_WRONG = 2;
    private static final int FILTER_SKIPPED = 3;

    private static final int MENU_TOGGLE_LANG = 1;
    private static final int MENU_LEADERBOARD = 2;
    private static final int MENU_MORE = 3;
    private static final int SUB_DOWNLOAD_PDF = 101;

    private static final int ID_HERO_CARD = 100;
    private static final int ID_TIME_CHART = 150;
    private static final int ID_ACTION_LEADERBOARD = 201;
    private static final int ID_ACTION_RETAKE = 202;
    private static final int ID_ACTION_BACK = 203;
    private static final int ID_FILTER_BAR = 300;
    private static final int BASE_ANSWER_ID = 1000;

    public static final class CutoffEvaluation {
        public final boolean hasCutoff;
        public final boolean cleared;
        public final String category;
        public final String label;

        public CutoffEvaluation(boolean hasCutoff, boolean cleared, String category, String label) {
            this.hasCutoff = hasCutoff;
            this.cleared = cleared;
            this.category = category != null && !category.trim().isEmpty() ? category.trim() : "General";
            this.label = label != null ? label : "";
        }
    }

    public static CutoffEvaluation evaluateCutoffStatus(double score, String studentCategory, Double cutoff) {
        String cat = studentCategory != null && !studentCategory.trim().isEmpty() ? studentCategory.trim() : "General";
        if (cutoff == null || Double.isNaN(cutoff) || Double.isInfinite(cutoff) || cutoff < 0) {
            return new CutoffEvaluation(false, false, cat, "");
        }
        double diff = score - cutoff;
        boolean cleared = diff >= -1e-6;
        String diffStr = formatScore(Math.abs(diff));
        String label = cleared
                ? ("Cleared " + cat + " Cutoff (+" + diffStr + ")")
                : ("Below " + cat + " Cutoff (-" + diffStr + ")");
        return new CutoffEvaluation(true, cleared, cat, label);
    }

    private final EveExamModels.ExamSummary exam;
    private final EveExamModels.GeneratedTestSummary test;
    private final String attemptKey;
    private final EveExamModels.AttemptResult result;

    private UniversalRecyclerView listView;
    private boolean preferHindi = safeIsHindiSelected();

    private static boolean safeIsHindiSelected() {
        try {
            return LocaleController.isHindiSelected();
        } catch (Throwable ignored) {
            return false;
        }
    }
    private int currentFilter = FILTER_ALL;
    private String rankText = "";
    private boolean destroyed = false;
    private final Set<String> expandedExplanations = new HashSet<>();

    public EveTestResultFragment(
            EveExamModels.ExamSummary exam,
            EveExamModels.GeneratedTestSummary test,
            String attemptKey,
            EveExamModels.AttemptResult result) {
        this.exam = exam;
        this.test = test;
        this.attemptKey = attemptKey != null && !attemptKey.isEmpty()
                ? attemptKey
                : (result != null ? result.examId : "");
        this.result = result;
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

        String rawTitle = result != null && !result.examName.isEmpty() ? result.examName : "Test Result";
        CharSequence title = org.telegram.ui.eve.admin.EveAnimatedEmojiHelper.decode(
                rawTitle, simpleActionBar != null ? simpleActionBar.getTitleFontMetricsInt() : null);
        String sub = result != null
                ? (LocaleController.getString(R.string.EveScore) + ": " + formatScore(result.score) + " / " + result.total)
                : null;

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                title,
                sub,
                true,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_TOGGLE_LANG) {
                            preferHindi = !preferHindi;
                            updateList();
                        } else if (id == MENU_LEADERBOARD) {
                            openLeaderboard();
                        } else if (id == SUB_DOWNLOAD_PDF) {
                            exportResultPdf();
                        }
                    }
                },
                null
        );
        listView = chrome.listView;

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            SimpleActionBarMenuItem langItem = menu.addItem(MENU_TOGGLE_LANG, R.drawable.msg_translate, getResourceProvider());
            boolean hasHindi = false;
            if (result != null) {
                for (EveExamModels.AttemptAnswerReview ans : result.answers) {
                    if (!ans.questionTextHi.isEmpty() || !ans.explanationHi.isEmpty()) {
                        hasHindi = true;
                        break;
                    }
                }
            }
            langItem.setVisibility(hasHindi ? View.VISIBLE : View.GONE);
            menu.addItem(MENU_LEADERBOARD, R.drawable.msg_stats, getResourceProvider());
            SimpleActionBarMenuItem moreItem = menu.addItem(MENU_MORE, R.drawable.ic_ab_other, getResourceProvider());
            moreItem.addSubItem(SUB_DOWNLOAD_PDF, R.drawable.msg_view_file, "Download Result & Solutions PDF");
        }

        fragmentView = chrome.rootView;
        loadRankBanner();
        return chrome.rootView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }

    private void loadRankBanner() {
        if (TextUtils.isEmpty(attemptKey) || result == null) {
            return;
        }
        rankText = LocaleController.getString(R.string.EveYourRank) + ": Loading...";
        updateList();
        EveExamRepository.getInstance().loadLeaderboard(attemptKey, (bundle, err) -> {
            if (destroyed) return;
            if (bundle == null || bundle.stats == null || bundle.stats.myRank == null) {
                rankText = LocaleController.getString(R.string.EveYourRank) + ": Unranked";
            } else {
                String pct = bundle.stats.myPercentile != null
                        ? String.format(Locale.US, " • %.1f%%ile", bundle.stats.myPercentile)
                        : "";
                rankText = LocaleController.getString(R.string.EveYourRank)
                        + ": #" + bundle.stats.myRank + " / " + bundle.stats.participants
                        + pct
                        + " (Topper: " + formatScore(bundle.stats.topperScore) + ")";
            }
            updateList();
        });
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private CutoffEvaluation resolveCutoffEvaluation() {
        if (result == null) {
            return new CutoffEvaluation(false, false, "General", "");
        }
        EveUserProfile profile = EveAuthManager.getInstance().getCachedProfile();
        String studentCat = profile != null && !TextUtils.isEmpty(profile.category) ? profile.category : "General";
        EveExamModels.ExamSummary resolvedExam = exam != null ? exam : EveExamRepository.getInstance().findExamSummary(attemptKey);
        if (resolvedExam == null && !TextUtils.isEmpty(result.examId)) {
            resolvedExam = EveExamRepository.getInstance().findExamSummary(result.examId);
        }
        Double cutoff = resolvedExam != null ? resolvedExam.getCutoffForCategory(studentCat) : null;
        return evaluateCutoffStatus(result.score, studentCat, cutoff);
    }

    private void exportResultPdf() {
        if (result == null) return;
        EveUserProfile profile = EveAuthManager.getInstance().getCachedProfile();
        String studentName = profile != null && !TextUtils.isEmpty(profile.displayName)
                ? profile.displayName
                : (!TextUtils.isEmpty(result.displayName) ? result.displayName : "Student");
        CutoffEvaluation eval = resolveCutoffEvaluation();
        EvePdfHelper.generateAndExportResultPdf(this, result, studentName, eval.category, eval.label, preferHindi);
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        if (result == null) {
            items.add(UItem.asShadow("Result unavailable."));
            return;
        }

        CutoffEvaluation cutoffEval = resolveCutoffEvaluation();
        items.add(HeroSummaryView.Factory.of(ID_HERO_CARD, result, rankText, cutoffEval));
        items.add(UItem.asShadow(null));

        if (result.answers != null && !result.answers.isEmpty()) {
            items.add(UItem.asHeader("Time Analysis"));
            items.add(EveQuestionTimeChartView.Factory.of(ID_TIME_CHART, result.answers));
            items.add(UItem.asShadow(null));
        }

        items.add(UItem.asHeader("Actions"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_ACTION_LEADERBOARD,
                IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                R.drawable.msg_stats,
                LocaleController.getString(R.string.EveLeaderboard),
                "Compare score, rank & percentile",
                null));
        if (exam != null || !TextUtils.isEmpty(attemptKey)) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_ACTION_RETAKE,
                    IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                    R.drawable.msg_retry,
                    "Retake Test",
                    "Reset attempt and practice again",
                    null));
        }
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_ACTION_BACK,
                IconBackgroundColors.GRAY.top, IconBackgroundColors.GRAY.bottom,
                R.drawable.ic_ab_back,
                "Back to Exams",
                "Return to test list",
                null));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Answer Review"));
        items.add(FilterRowView.Factory.of(ID_FILTER_BAR, result, currentFilter, f -> {
            currentFilter = f;
            updateList();
        }));

        List<EveExamModels.AttemptAnswerReview> answers = result.answers;
        int addedCount = 0;
        for (int i = 0; i < answers.size(); i++) {
            EveExamModels.AttemptAnswerReview ans = answers.get(i);
            if (currentFilter == FILTER_CORRECT && !ans.isCorrect()) continue;
            if (currentFilter == FILTER_WRONG && (ans.isUnattempted() || ans.isCorrect())) continue;
            if (currentFilter == FILTER_SKIPPED && !ans.isUnattempted()) continue;
            addedCount++;
            String expKey = !TextUtils.isEmpty(ans.questionId) ? ans.questionId : ("q_" + i);
            boolean expExpanded = expandedExplanations.contains(expKey);
            items.add(AnswerReviewView.Factory.of(
                    BASE_ANSWER_ID + i,
                    ans,
                    preferHindi,
                    expExpanded,
                    v -> {
                        if (expandedExplanations.contains(expKey)) {
                            expandedExplanations.remove(expKey);
                        } else {
                            expandedExplanations.add(expKey);
                        }
                        updateList();
                    },
                    this::toggleBookmark,
                    this::showReportDialog));
        }
        if (addedCount == 0) {
            items.add(UItem.asShadow("No questions match this filter."));
        }
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ACTION_LEADERBOARD) {
            openLeaderboard();
        } else if (item.id == ID_ACTION_RETAKE) {
            promptReattempt();
        } else if (item.id == ID_ACTION_BACK) {
            finishFragment();
        }
    }

    private void toggleBookmark(EveExamModels.AttemptAnswerReview ans) {
        if (ans.questionId.isEmpty()) return;
        EveExamRepository.getInstance().toggleBookmark(attemptKey, result.examName, ans, (bookmarked, err) -> {
            if (destroyed || getParentActivity() == null) return;
            if (!err.isEmpty() || bookmarked == null) {
                BulletinFactory.of(this).createErrorBulletin(!err.isEmpty() ? err : "Could not update bookmark.").show();
                return;
            }
            ans.isBookmarked = bookmarked;
            BulletinFactory.of(this).createSimpleBulletin(
                    R.raw.info, bookmarked ? "Question bookmarked" : "Bookmark removed").show();
            updateList();
        });
    }

    public static final String[] REPORT_REASONS = new String[] {
            "Wrong Question",
            "No Solution",
            "Wrong Translation",
            "Out of Syllabus",
            "Formatting Issues",
            "Other"
    };

    public static RadioGroup buildReportReasonGroup(Context context, String[] reasons, int[] pickedIdx, int textColor) {
        RadioGroup reasonGroup = new RadioGroup(context);
        reasonGroup.setOrientation(RadioGroup.VERTICAL);
        pickedIdx[0] = 0;
        for (int i = 0; i < reasons.length; i++) {
            RadioButton rb = new RadioButton(context);
            rb.setId(i + 1);
            rb.setText(reasons[i]);
            rb.setTextColor(textColor);
            if (i == 0) {
                rb.setChecked(true);
            }
            reasonGroup.addView(rb);
        }
        reasonGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int idx = checkedId - 1;
            if (idx >= 0 && idx < reasons.length) {
                pickedIdx[0] = idx;
            }
        });
        return reasonGroup;
    }

    private void showReportDialog(EveExamModels.AttemptAnswerReview ans) {
        Activity activity = getParentActivity();
        if (activity == null || ans.questionId.isEmpty()) return;
        Theme.ResourcesProvider rp = getResourceProvider();
        final String[] reasons = REPORT_REASONS;
        final int[] pickedIdx = { 0 };
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(8), pad, 0);

        RadioGroup reasonGroup = buildReportReasonGroup(
                activity,
                reasons,
                pickedIdx,
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp)
        );
        box.addView(reasonGroup);

        EditText commentInput = new EditText(activity);
        commentInput.setHint("Optional details (up to 500 chars)");
        commentInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        commentInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        box.addView(commentInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, rp);
        builder.setTitle(LocaleController.getString(R.string.EveReportQuestion));
        builder.setView(scroll);
        builder.setPositiveButton("Submit Report", (d, w) -> {
            String reason = reasons[pickedIdx[0]];
            String comment = commentInput.getText() != null ? commentInput.getText().toString().trim() : "";
            EveExamRepository.getInstance().submitQuestionReport(
                    ans.questionId,
                    attemptKey,
                    result.examName,
                    ans.questionText,
                    reason,
                    comment,
                    (msg, err) -> {
                        if (destroyed || getParentActivity() == null) return;
                        if (!err.isEmpty()) {
                            BulletinFactory.of(this).createErrorBulletin(err).show();
                        } else {
                            BulletinFactory.of(this).createSuccessBulletin(msg).show();
                        }
                    }
            );
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void promptReattempt() {
        Activity activity = getParentActivity();
        if (activity == null || attemptKey.isEmpty()) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.EveReattemptConfirmTitle));
        builder.setMessage(LocaleController.getString(R.string.EveReattemptConfirmBody));
        builder.setPositiveButton(LocaleController.getString(R.string.EveReattempt), (d, w) ->
                EveExamRepository.getInstance().resetForReattempt(attemptKey, (ok, err) -> {
                    if (destroyed || getParentActivity() == null) return;
                    if (!err.isEmpty()) {
                        BulletinFactory.of(this).createErrorBulletin(err).show();
                        return;
                    }
                    presentFragment(new EveTestPlayerFragment(exam, test), true);
                }));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void openLeaderboard() {
        String key = attemptKey.isEmpty() ? "overall" : attemptKey;
        String title = result != null && !result.examName.isEmpty() ? result.examName : LocaleController.getString(R.string.EveLeaderboard);
        presentFragment(new EveLeaderboardFragment(key, title));
    }

    private static String formatScore(double score) {
        if (Math.abs(score - Math.rint(score)) < 0.001) {
            return String.valueOf((long) Math.rint(score));
        }
        return String.format(Locale.US, "%.2f", score);
    }

    private static String formatDuration(int seconds) {
        int mins = seconds / 60;
        int secs = seconds % 60;
        return String.format(Locale.US, "%dm %02ds", mins, secs);
    }

    private static final class HeroSummaryView extends LinearLayout implements Theme.Colorable {
        private final Theme.ResourcesProvider resourcesProvider;
        private final TextView scoreText;
        private final TextView accuracyBadge;
        private final TextView cutoffBadge;
        private final TextView correctVal;
        private final TextView wrongVal;
        private final TextView skippedVal;
        private final TextView metaLine;
        private final TextView rankLine;

        HeroSummaryView(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.resourcesProvider = rp;
            setOrientation(VERTICAL);
            setPadding(dp(18), dp(18), dp(18), dp(16));

            LinearLayout topRow = new LinearLayout(context);
            topRow.setOrientation(HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            addView(topRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

            scoreText = new TextView(context);
            scoreText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
            scoreText.setTypeface(AndroidUtilities.bold());
            topRow.addView(scoreText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            accuracyBadge = new TextView(context);
            accuracyBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            accuracyBadge.setTypeface(AndroidUtilities.bold());
            accuracyBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
            topRow.addView(accuracyBadge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            cutoffBadge = new TextView(context);
            cutoffBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            cutoffBadge.setTypeface(AndroidUtilities.bold());
            cutoffBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
            cutoffBadge.setVisibility(GONE);
            addView(cutoffBadge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

            LinearLayout metricsRow = new LinearLayout(context);
            metricsRow.setOrientation(HORIZONTAL);
            addView(metricsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

            correctVal = addMetricColumn(context, metricsRow, LocaleController.getString(R.string.EveCorrect), 0xFF2E9E5B);
            wrongVal = addMetricColumn(context, metricsRow, LocaleController.getString(R.string.EveWrong), 0xFFD93025);
            skippedVal = addMetricColumn(context, metricsRow, "Skipped", 0xFF788896);

            metaLine = new TextView(context);
            metaLine.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            addView(metaLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

            rankLine = new TextView(context);
            rankLine.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            rankLine.setTypeface(AndroidUtilities.bold());
            addView(rankLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            updateColors();
        }

        private TextView addMetricColumn(Context ctx, LinearLayout row, String label, int color) {
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(dp(6), dp(8), dp(6), dp(8));
            col.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(color, 0.10f)));

            TextView val = new TextView(ctx);
            val.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            val.setTypeface(AndroidUtilities.bold());
            val.setTextColor(color);
            val.setGravity(Gravity.CENTER);
            col.addView(val);

            TextView lbl = new TextView(ctx);
            lbl.setText(label);
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            lbl.setTextColor(color);
            lbl.setGravity(Gravity.CENTER);
            col.addView(lbl);

            row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 3, 0, 3, 0));
            return val;
        }

        void bind(EveExamModels.AttemptResult r, String rank, CutoffEvaluation cutoffEval) {
            scoreText.setText(formatScore(r.score) + " / " + r.total);
            accuracyBadge.setText(r.getAccuracyPercent() + "% Accuracy");
            if (cutoffEval != null && cutoffEval.hasCutoff && !TextUtils.isEmpty(cutoffEval.label)) {
                cutoffBadge.setVisibility(VISIBLE);
                cutoffBadge.setText(cutoffEval.label);
                int cColor = cutoffEval.cleared ? 0xFF2E9E5B : 0xFFE68A00;
                cutoffBadge.setTextColor(cColor);
                cutoffBadge.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(cColor, 0.14f)));
            } else {
                cutoffBadge.setVisibility(GONE);
            }
            correctVal.setText(String.valueOf(r.correct));
            wrongVal.setText(String.valueOf(r.wrong));
            skippedVal.setText(String.valueOf(r.unattempted));
            metaLine.setText(LocaleController.getString(R.string.EveTimeTaken) + ": " + formatDuration(r.timeTakenSeconds)
                    + (r.counted == 0 ? "  •  Extra Attempt" : ""));
            if (TextUtils.isEmpty(rank)) {
                rankLine.setVisibility(GONE);
            } else {
                rankLine.setVisibility(VISIBLE);
                rankLine.setText(rank);
            }
            updateColors();
        }

        @Override
        public void updateColors() {
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
            scoreText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            accuracyBadge.setTextColor(accent);
            accuracyBadge.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.multAlpha(accent, 0.14f)));
            metaLine.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            rankLine.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_windowBackgroundWhiteBlackText);
            colors.add(Theme.key_windowBackgroundWhiteGrayText);
            colors.add(Theme.key_windowBackgroundWhiteBlueText);
            colors.add(Theme.key_featuredStickers_addButton);
        }

        static final class Payload {
            final EveExamModels.AttemptResult result;
            final String rankText;
            final CutoffEvaluation cutoffEval;

            Payload(EveExamModels.AttemptResult result, String rankText, CutoffEvaluation cutoffEval) {
                this.result = result;
                this.rankText = rankText != null ? rankText : "";
                this.cutoffEval = cutoffEval;
            }
        }

        static final class Factory extends UItem.UItemFactory<HeroSummaryView> {
            static { setup(new Factory()); }

            @Override
            public HeroSummaryView createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider rp) {
                return new HeroSummaryView(context, rp);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                if (item.object instanceof Payload) {
                    Payload p = (Payload) item.object;
                    ((HeroSummaryView) view).bind(p.result, p.rankText, p.cutoffEval);
                }
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                if (!(a.object instanceof Payload) || !(b.object instanceof Payload)) {
                    return Objects.equals(a.object, b.object);
                }
                Payload pa = (Payload) a.object;
                Payload pb = (Payload) b.object;
                String la = pa.cutoffEval != null ? pa.cutoffEval.label : "";
                String lb = pb.cutoffEval != null ? pb.cutoffEval.label : "";
                return pa.result == pb.result && TextUtils.equals(pa.rankText, pb.rankText) && TextUtils.equals(la, lb);
            }

            static UItem of(int id, EveExamModels.AttemptResult result, String rankText, CutoffEvaluation cutoffEval) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.object = new Payload(result, rankText, cutoffEval);
                return item;
            }
        }
    }

    private interface IntConsumer {
        void accept(int value);
    }

    private static final class FilterRowView extends LinearLayout implements Theme.Colorable {
        private final Theme.ResourcesProvider resourcesProvider;
        private final TextView[] chips = new TextView[4];
        private int activeFilter = FILTER_ALL;

        FilterRowView(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.resourcesProvider = rp;
            setOrientation(HORIZONTAL);
            setPadding(dp(12), dp(8), dp(12), dp(8));
            for (int i = 0; i < 4; i++) {
                TextView tv = new TextView(context);
                tv.setGravity(Gravity.CENTER);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
                tv.setTypeface(AndroidUtilities.bold());
                tv.setPadding(dp(8), dp(7), dp(8), dp(7));
                chips[i] = tv;
                addView(tv, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 3, 0, 3, 0));
            }
        }

        void bind(EveExamModels.AttemptResult r, int selectedFilter, IntConsumer onSelect) {
            this.activeFilter = selectedFilter;
            String[] labels = new String[] {
                    "All (" + r.answers.size() + ")",
                    LocaleController.getString(R.string.EveCorrect) + " (" + r.correct + ")",
                    LocaleController.getString(R.string.EveWrong) + " (" + r.wrong + ")",
                    "Skipped (" + r.unattempted + ")"
            };
            for (int i = 0; i < 4; i++) {
                final int filterId = i;
                chips[i].setText(labels[i]);
                chips[i].setOnClickListener(v -> onSelect.accept(filterId));
            }
            updateColors();
        }

        @Override
        public void updateColors() {
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
            int btnText = Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider);
            int normalBg = Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider);
            int normalText = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);

            for (int i = 0; i < 4; i++) {
                boolean active = activeFilter == i;
                chips[i].setBackground(Theme.createRoundRectDrawable(dp(8), active ? accent : normalBg));
                chips[i].setTextColor(active ? btnText : normalText);
            }
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_featuredStickers_addButton);
            colors.add(Theme.key_featuredStickers_buttonText);
            colors.add(Theme.key_windowBackgroundGray);
            colors.add(Theme.key_windowBackgroundWhiteBlackText);
        }

        static final class Payload {
            final EveExamModels.AttemptResult result;
            final int filter;
            final IntConsumer onSelect;

            Payload(EveExamModels.AttemptResult result, int filter, IntConsumer onSelect) {
                this.result = result;
                this.filter = filter;
                this.onSelect = onSelect;
            }
        }

        static final class Factory extends UItem.UItemFactory<FilterRowView> {
            static { setup(new Factory()); }

            @Override
            public FilterRowView createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider rp) {
                return new FilterRowView(context, rp);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                if (item.object instanceof Payload) {
                    Payload p = (Payload) item.object;
                    ((FilterRowView) view).bind(p.result, p.filter, p.onSelect);
                }
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                return a.id == b.id;
            }

            static UItem of(int id, EveExamModels.AttemptResult r, int filter, IntConsumer onSelect) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.object = new Payload(r, filter, onSelect);
                return item;
            }
        }
    }

    private interface ReviewCallback {
        void accept(EveExamModels.AttemptAnswerReview ans);
    }

    private static final class AnswerReviewView extends LinearLayout implements Theme.Colorable {
        private final Theme.ResourcesProvider resourcesProvider;
        private final TextView qHeader;
        private final TextView statusPill;
        private final TextView qText;
        private final TextView yourAns;
        private final TextView correctAns;
        private final TextView expToggleBtn;
        private final TextView expView;
        private final TextView bookmarkBtn;
        private final TextView reportBtn;
        private int statusColor;
        private boolean bookmarked;

        AnswerReviewView(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.resourcesProvider = rp;
            setOrientation(VERTICAL);
            setPadding(dp(16), dp(14), dp(16), dp(14));

            LinearLayout headerRow = new LinearLayout(context);
            headerRow.setOrientation(HORIZONTAL);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);
            addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

            qHeader = new TextView(context);
            qHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            qHeader.setTypeface(AndroidUtilities.bold());
            headerRow.addView(qHeader, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            statusPill = new TextView(context);
            statusPill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            statusPill.setTypeface(AndroidUtilities.bold());
            statusPill.setPadding(dp(8), dp(3), dp(8), dp(3));
            headerRow.addView(statusPill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            qText = EveAnimatedEmojiHelper.createEmojiTextView(context);
            qText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            qText.setLineSpacing(dp(3), 1f);
            addView(qText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

            yourAns = EveAnimatedEmojiHelper.createEmojiTextView(context);
            yourAns.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            yourAns.setPadding(dp(10), dp(8), dp(10), dp(8));
            addView(yourAns, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

            correctAns = EveAnimatedEmojiHelper.createEmojiTextView(context);
            correctAns.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            correctAns.setPadding(dp(10), dp(8), dp(10), dp(8));
            correctAns.setTextColor(0xFF2E9E5B);
            addView(correctAns, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

            expToggleBtn = new TextView(context);
            expToggleBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            expToggleBtn.setTypeface(AndroidUtilities.bold());
            expToggleBtn.setPadding(0, dp(2), 0, dp(6));
            addView(expToggleBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            expView = EveAnimatedEmojiHelper.createEmojiTextView(context);
            expView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            expView.setPadding(dp(12), dp(10), dp(12), dp(10));
            expView.setLineSpacing(dp(3), 1f);
            addView(expView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

            LinearLayout qActions = new LinearLayout(context);
            qActions.setOrientation(HORIZONTAL);
            addView(qActions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            bookmarkBtn = new TextView(context);
            bookmarkBtn.setGravity(Gravity.CENTER);
            bookmarkBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            bookmarkBtn.setTypeface(AndroidUtilities.bold());
            qActions.addView(bookmarkBtn, LayoutHelper.createLinear(0, 36, 1f, 0, 0, 8, 0));

            reportBtn = new TextView(context);
            reportBtn.setGravity(Gravity.CENTER);
            reportBtn.setText(LocaleController.getString(R.string.EveReportQuestion));
            reportBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            reportBtn.setTypeface(AndroidUtilities.bold());
            qActions.addView(reportBtn, LayoutHelper.createLinear(0, 36, 1f));
        }

        void bind(EveExamModels.AttemptAnswerReview ans, boolean hi, boolean expanded, View.OnClickListener onToggleExp, ReviewCallback onBookmark, ReviewCallback onReport) {
            this.bookmarked = ans.isBookmarked;
            String statusText;
            if (ans.isUnattempted()) {
                statusText = LocaleController.getString(R.string.EveUnattempted);
                statusColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
            } else if (ans.isCorrect()) {
                statusText = "\u2713 " + LocaleController.getString(R.string.EveCorrect);
                statusColor = 0xFF2E9E5B;
            } else {
                statusText = "\u2717 " + LocaleController.getString(R.string.EveWrong);
                statusColor = 0xFFD93025;
            }
            String timeMeta = ans.timeTakenSeconds > 0 ? (" • " + ans.timeTakenSeconds + "s") : "";
            qHeader.setText("Question " + ans.number + (ans.topic.isEmpty() ? "" : " • " + ans.topic) + timeMeta);
            statusPill.setText(statusText);
            EveAnimatedEmojiHelper.bindText(qText, ans.getLocalizedQuestion(hi));

            String yourPrefix = ans.isUnattempted() ? "" : (ans.isCorrect() ? "\u2713 " : "\u2717 ");
            String yourText = ans.isUnattempted()
                    ? "-"
                    : (ans.selected + (!ans.getLocalizedSelectedText(hi).isEmpty() ? " — " + ans.getLocalizedSelectedText(hi) : ""));
            EveAnimatedEmojiHelper.bindText(yourAns, yourPrefix + LocaleController.getString(R.string.EveYourAnswer) + ": " + yourText);
            yourAns.setTextColor(statusColor);
            yourAns.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(statusColor, 0.11f)));

            EveAnimatedEmojiHelper.bindText(correctAns, "\u2713 " + LocaleController.getString(R.string.EveCorrectAnswer) + ": "
                    + ans.correct
                    + (!ans.getLocalizedCorrectText(hi).isEmpty() ? " — " + ans.getLocalizedCorrectText(hi) : ""));
            correctAns.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(0xFF2E9E5B, 0.11f)));

            String explanation = ans.getLocalizedExplanation(hi);
            if (!explanation.trim().isEmpty()) {
                expToggleBtn.setVisibility(VISIBLE);
                expToggleBtn.setText(expanded ? "\uD83D\uDCA1 Hide Explanation \u25B2" : "\uD83D\uDCA1 View Explanation \u25BC");
                expToggleBtn.setOnClickListener(onToggleExp);
                expView.setVisibility(expanded ? VISIBLE : GONE);
                EveAnimatedEmojiHelper.bindText(expView, "\uD83D\uDCA1 " + LocaleController.getString(R.string.EveExplanation) + "\n" + explanation);
            } else {
                expToggleBtn.setVisibility(GONE);
                expView.setVisibility(GONE);
            }

            bookmarkBtn.setText(LocaleController.getString(ans.isBookmarked ? R.string.EveBookmarked : R.string.EveBookmark));
            bookmarkBtn.setOnClickListener(v -> onBookmark.accept(ans));
            reportBtn.setOnClickListener(v -> onReport.accept(ans));
            updateColors();
        }

        @Override
        public void updateColors() {
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
            int grayBg = Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider);
            int black = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            int grayText = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);

            qHeader.setTextColor(grayText);
            statusPill.setTextColor(statusColor);
            statusPill.setBackground(Theme.createRoundRectDrawable(dp(8), Theme.multAlpha(statusColor, 0.14f)));
            qText.setTextColor(black);
            expToggleBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider));
            expView.setTextColor(black);
            expView.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(accent, 0.08f)));

            bookmarkBtn.setBackground(Theme.createRoundRectDrawable(dp(8), bookmarked ? accent : grayBg));
            bookmarkBtn.setTextColor(bookmarked ? Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider) : black);
            reportBtn.setBackground(Theme.createRoundRectDrawable(dp(8), grayBg));
            reportBtn.setTextColor(black);
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_featuredStickers_addButton);
            colors.add(Theme.key_featuredStickers_buttonText);
            colors.add(Theme.key_windowBackgroundGray);
            colors.add(Theme.key_windowBackgroundWhiteBlackText);
            colors.add(Theme.key_windowBackgroundWhiteGrayText);
        }

        static final class Payload {
            final EveExamModels.AttemptAnswerReview ans;
            final boolean hi;
            final boolean expanded;
            final View.OnClickListener onToggleExp;
            final ReviewCallback onBookmark;
            final ReviewCallback onReport;

            Payload(EveExamModels.AttemptAnswerReview ans, boolean hi, boolean expanded, View.OnClickListener onToggleExp, ReviewCallback onBookmark, ReviewCallback onReport) {
                this.ans = ans;
                this.hi = hi;
                this.expanded = expanded;
                this.onToggleExp = onToggleExp;
                this.onBookmark = onBookmark;
                this.onReport = onReport;
            }
        }

        static final class Factory extends UItem.UItemFactory<AnswerReviewView> {
            static { setup(new Factory()); }

            @Override
            public AnswerReviewView createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider rp) {
                return new AnswerReviewView(context, rp);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                if (item.object instanceof Payload) {
                    Payload p = (Payload) item.object;
                    ((AnswerReviewView) view).bind(p.ans, p.hi, p.expanded, p.onToggleExp, p.onBookmark, p.onReport);
                }
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                if (!(a.object instanceof Payload) || !(b.object instanceof Payload)) {
                    return Objects.equals(a.object, b.object);
                }
                Payload pa = (Payload) a.object;
                Payload pb = (Payload) b.object;
                return pa.hi == pb.hi
                        && pa.expanded == pb.expanded
                        && pa.ans.isBookmarked == pb.ans.isBookmarked
                        && TextUtils.equals(pa.ans.questionId, pb.ans.questionId);
            }

            static UItem of(int id, EveExamModels.AttemptAnswerReview ans, boolean hi, boolean expanded, View.OnClickListener onToggleExp, ReviewCallback onBookmark, ReviewCallback onReport) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.object = new Payload(ans, hi, expanded, onToggleExp, onBookmark, onReport);
                return item;
            }
        }
    }
}
