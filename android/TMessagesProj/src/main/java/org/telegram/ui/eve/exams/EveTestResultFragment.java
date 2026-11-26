package org.telegram.ui.eve.exams;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;
import java.util.Locale;

/**
 * Displays server-graded score summary, rank/percentile, question-by-question
 * answer review with English/Hindi explanations, bookmarking, question reporting,
 * leaderboard access, and server-verified reattempt flow.
 */
public class EveTestResultFragment extends BaseFragment {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_CORRECT = 1;
    private static final int FILTER_WRONG = 2;
    private static final int FILTER_SKIPPED = 3;

    private static final int ID_TOGGLE_LANG = 1;
    private static final int ID_LEADERBOARD = 2;

    private final EveExamModels.ExamSummary exam;
    private final EveExamModels.GeneratedTestSummary test;
    private final String attemptKey;
    private final EveExamModels.AttemptResult result;

    private boolean preferHindi = LocaleController.isHindiSelected();
    private int currentFilter = FILTER_ALL;
    private LinearLayout questionsContainer;
    private LinearLayout filterRow;
    private TextView rankBannerView;

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
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(result != null && !result.examName.isEmpty() ? result.examName : "Test Result");
        if (result != null) {
            actionBar.setSubtitle(LocaleController.getString(R.string.EveScore) + ": " + formatScore(result.score) + " / " + result.total);
        }

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem langItem = menu.addItem(ID_TOGGLE_LANG, R.drawable.msg_translate);
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
        menu.addItem(ID_LEADERBOARD, R.drawable.msg_stats);

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == ID_TOGGLE_LANG) {
                    preferHindi = !preferHindi;
                    renderAnswers();
                } else if (id == ID_LEADERBOARD) {
                    openLeaderboard();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(24));
        scrollView.addView(root, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        if (result == null) {
            TextView empty = new TextView(context);
            empty.setText("Result unavailable.");
            empty.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24));
            root.addView(empty);
            fragmentView = scrollView;
            return scrollView;
        }

        LinearLayout summaryCard = createCard(context);
        summaryCard.setOrientation(LinearLayout.VERTICAL);
        root.addView(summaryCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        TextView headline = new TextView(context);
        headline.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        headline.setTypeface(AndroidUtilities.bold());
        headline.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        headline.setText(LocaleController.getString(R.string.EveScore) + ": " + formatScore(result.score) + " / " + result.total + "  (" + result.getAccuracyPercent() + "% " + LocaleController.getString(R.string.EveAccuracy) + ")");
        summaryCard.addView(headline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        TextView breakdown = new TextView(context);
        breakdown.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        breakdown.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        breakdown.setText(
                LocaleController.getString(R.string.EveCorrect) + ": " + result.correct
                        + "  •  " + LocaleController.getString(R.string.EveWrong) + ": " + result.wrong
                        + "  •  " + LocaleController.getString(R.string.EveUnattempted) + ": " + result.unattempted
                        + "\n" + LocaleController.getString(R.string.EveTimeTaken) + ": " + formatDuration(result.timeTakenSeconds)
                        + (result.counted == 0 ? "  •  Extra Attempt" : "")
        );
        summaryCard.addView(breakdown, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        rankBannerView = new TextView(context);
        rankBannerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        rankBannerView.setTypeface(AndroidUtilities.bold());
        rankBannerView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        rankBannerView.setText(LocaleController.getString(R.string.EveYourRank) + ": Loading...");
        summaryCard.addView(rankBannerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        LinearLayout actionBtns = new LinearLayout(context);
        actionBtns.setOrientation(LinearLayout.HORIZONTAL);
        summaryCard.addView(actionBtns, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView lbBtn = makePillButton(context, LocaleController.getString(R.string.EveLeaderboard), false);
        lbBtn.setOnClickListener(v -> openLeaderboard());
        actionBtns.addView(lbBtn, LayoutHelper.createLinear(0, 40, 1f, 0, 0, 8, 0));

        if (exam != null || !attemptKey.isEmpty()) {
            TextView reattemptBtn = makePillButton(context, LocaleController.getString(R.string.EveReattempt), true);
            reattemptBtn.setOnClickListener(v -> promptReattempt());
            actionBtns.addView(reattemptBtn, LayoutHelper.createLinear(0, 40, 1f));
        }

        filterRow = new LinearLayout(context);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(filterRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
        rebuildFilterChips(context);

        questionsContainer = new LinearLayout(context);
        questionsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(questionsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        renderAnswers();
        loadRankBanner();

        fragmentView = scrollView;
        return scrollView;
    }

    private void loadRankBanner() {
        String key = attemptKey;
        if (key.isEmpty()) {
            rankBannerView.setVisibility(View.GONE);
            return;
        }
        EveExamRepository.getInstance().loadLeaderboard(key, (bundle, err) -> {
            if (getParentActivity() == null) return;
            if (bundle == null || bundle.stats == null || bundle.stats.myRank == null) {
                rankBannerView.setText(LocaleController.getString(R.string.EveYourRank) + ": —");
                return;
            }
            String pct = bundle.stats.myPercentile != null
                    ? String.format(Locale.US, " • %.1f%%ile", bundle.stats.myPercentile)
                    : "";
            rankBannerView.setText(
                    LocaleController.getString(R.string.EveYourRank)
                            + ": #" + bundle.stats.myRank + " / " + bundle.stats.participants
                            + pct
                            + "  (Topper: " + formatScore(bundle.stats.topperScore) + ")"
            );
        });
    }

    private void rebuildFilterChips(Context context) {
        filterRow.removeAllViews();
        addFilterChip(context, FILTER_ALL, "All (" + result.answers.size() + ")");
        addFilterChip(context, FILTER_CORRECT, LocaleController.getString(R.string.EveCorrect) + " (" + result.correct + ")");
        addFilterChip(context, FILTER_WRONG, LocaleController.getString(R.string.EveWrong) + " (" + result.wrong + ")");
        addFilterChip(context, FILTER_SKIPPED, LocaleController.getString(R.string.EveUnattempted) + " (" + result.unattempted + ")");
    }

    private void addFilterChip(Context context, int filterId, String title) {
        TextView tv = new TextView(context);
        tv.setText(title);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(6), AndroidUtilities.dp(8), AndroidUtilities.dp(6));
        boolean active = currentFilter == filterId;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(8));
        bg.setColor(active ? Theme.getColor(Theme.key_featuredStickers_addButton) : Theme.getColor(Theme.key_windowBackgroundWhite));
        tv.setBackground(bg);
        tv.setTextColor(active ? Theme.getColor(Theme.key_featuredStickers_buttonText) : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        tv.setOnClickListener(v -> {
            currentFilter = filterId;
            rebuildFilterChips(context);
            renderAnswers();
        });
        filterRow.addView(tv, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 3, 0, 3, 0));
    }

    private void renderAnswers() {
        Activity activity = getParentActivity();
        if (activity == null || result == null) return;
        questionsContainer.removeAllViews();

        List<EveExamModels.AttemptAnswerReview> answers = result.answers;
        for (int i = 0; i < answers.size(); i++) {
            EveExamModels.AttemptAnswerReview ans = answers.get(i);
            if (currentFilter == FILTER_CORRECT && !ans.isCorrect()) continue;
            if (currentFilter == FILTER_WRONG && (ans.isUnattempted() || ans.isCorrect())) continue;
            if (currentFilter == FILTER_SKIPPED && !ans.isUnattempted()) continue;

            LinearLayout card = createCard(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            questionsContainer.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

            TextView qHeader = new TextView(activity);
            qHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            qHeader.setTypeface(AndroidUtilities.bold());
            String statusText;
            int statusColor;
            if (ans.isUnattempted()) {
                statusText = LocaleController.getString(R.string.EveUnattempted);
                statusColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
            } else if (ans.isCorrect()) {
                statusText = LocaleController.getString(R.string.EveCorrect);
                statusColor = 0xFF2E9E5B;
            } else {
                statusText = LocaleController.getString(R.string.EveWrong);
                statusColor = 0xFFD93025;
            }
            qHeader.setTextColor(statusColor);
            qHeader.setText("Q" + ans.number + " • " + statusText + (ans.topic.isEmpty() ? "" : " • " + ans.topic));
            card.addView(qHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

            TextView qText = new TextView(activity);
            qText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            qText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            qText.setText(ans.getLocalizedQuestion(preferHindi));
            card.addView(qText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

            TextView yourAns = new TextView(activity);
            yourAns.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            yourAns.setTextColor(statusColor);
            String yourText = ans.isUnattempted()
                    ? "-"
                    : (ans.selected + (!ans.getLocalizedSelectedText(preferHindi).isEmpty() ? " — " + ans.getLocalizedSelectedText(preferHindi) : ""));
            yourAns.setText(LocaleController.getString(R.string.EveYourAnswer) + ": " + yourText);
            card.addView(yourAns, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

            TextView correctAns = new TextView(activity);
            correctAns.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            correctAns.setTextColor(0xFF2E9E5B);
            correctAns.setText(LocaleController.getString(R.string.EveCorrectAnswer) + ": "
                    + ans.correct
                    + (!ans.getLocalizedCorrectText(preferHindi).isEmpty() ? " — " + ans.getLocalizedCorrectText(preferHindi) : ""));
            card.addView(correctAns, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

            String explanation = ans.getLocalizedExplanation(preferHindi);
            if (!explanation.trim().isEmpty()) {
                TextView expView = new TextView(activity);
                expView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                expView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                expView.setText(LocaleController.getString(R.string.EveExplanation) + ":\n" + explanation);
                card.addView(expView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
            }

            LinearLayout qActions = new LinearLayout(activity);
            qActions.setOrientation(LinearLayout.HORIZONTAL);
            card.addView(qActions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            TextView bookmarkBtn = makePillButton(
                    activity,
                    LocaleController.getString(ans.isBookmarked ? R.string.EveBookmarked : R.string.EveBookmark),
                    ans.isBookmarked
            );
            bookmarkBtn.setOnClickListener(v -> toggleBookmark(ans, bookmarkBtn));
            qActions.addView(bookmarkBtn, LayoutHelper.createLinear(0, 34, 1f, 0, 0, 8, 0));

            TextView reportBtn = makePillButton(activity, LocaleController.getString(R.string.EveReportQuestion), false);
            reportBtn.setOnClickListener(v -> showReportDialog(ans));
            qActions.addView(reportBtn, LayoutHelper.createLinear(0, 34, 1f));
        }
    }

    private void toggleBookmark(EveExamModels.AttemptAnswerReview ans, TextView btn) {
        if (ans.questionId.isEmpty()) return;
        EveExamRepository.getInstance().toggleBookmark(attemptKey, result.examName, ans, (bookmarked, err) -> {
            if (getParentActivity() == null) return;
            if (!err.isEmpty() || bookmarked == null) {
                BulletinFactory.of(this).createErrorBulletin(!err.isEmpty() ? err : "Could not update bookmark.").show();
                return;
            }
            ans.isBookmarked = bookmarked;
            btn.setText(LocaleController.getString(bookmarked ? R.string.EveBookmarked : R.string.EveBookmark));
            stylePillButton(btn, bookmarked);
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

    public static RadioGroup buildReportReasonGroup(Context context, String[] reasons, int[] pickedIdx) {
        RadioGroup reasonGroup = new RadioGroup(context);
        reasonGroup.setOrientation(RadioGroup.VERTICAL);
        pickedIdx[0] = 0;
        for (int i = 0; i < reasons.length; i++) {
            RadioButton rb = new RadioButton(context);
            rb.setId(i + 1);
            rb.setText(reasons[i]);
            rb.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
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
        final String[] reasons = REPORT_REASONS;
        final int[] pickedIdx = { 0 };
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(16);
        box.setPadding(pad, AndroidUtilities.dp(8), pad, 0);

        RadioGroup reasonGroup = buildReportReasonGroup(activity, reasons, pickedIdx);
        box.addView(reasonGroup);

        EditText commentInput = new EditText(activity);
        commentInput.setHint("Optional details (up to 500 chars)");
        commentInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        box.addView(commentInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);

        new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.EveReportQuestion))
                .setView(scroll)
                .setPositiveButton("Submit Report", (d, w) -> {
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
                                if (getParentActivity() == null) return;
                                if (!err.isEmpty()) {
                                    BulletinFactory.of(this).createErrorBulletin(err).show();
                                } else {
                                    BulletinFactory.of(this).createSuccessBulletin(msg).show();
                                }
                            }
                    );
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void promptReattempt() {
        Activity activity = getParentActivity();
        if (activity == null || attemptKey.isEmpty()) return;
        new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.EveReattemptConfirmTitle))
                .setMessage(LocaleController.getString(R.string.EveReattemptConfirmBody))
                .setPositiveButton(LocaleController.getString(R.string.EveReattempt), (d, w) ->
                        EveExamRepository.getInstance().resetForReattempt(attemptKey, (ok, err) -> {
                            if (getParentActivity() == null) return;
                            if (!err.isEmpty()) {
                                BulletinFactory.of(this).createErrorBulletin(err).show();
                                return;
                            }
                            presentFragment(new EveTestPlayerFragment(exam, test), true);
                        }))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void openLeaderboard() {
        String key = attemptKey.isEmpty() ? "overall" : attemptKey;
        String title = result != null && !result.examName.isEmpty() ? result.examName : LocaleController.getString(R.string.EveLeaderboard);
        presentFragment(new EveLeaderboardFragment(key, title));
    }

    private LinearLayout createCard(Context context) {
        LinearLayout card = new LinearLayout(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(12));
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        card.setBackground(bg);
        card.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14));
        return card;
    }

    private TextView makePillButton(Context context, String label, boolean primary) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tv.setTypeface(AndroidUtilities.bold());
        stylePillButton(tv, primary);
        return tv;
    }

    private void stylePillButton(TextView tv, boolean primary) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(8));
        bg.setColor(primary ? Theme.getColor(Theme.key_featuredStickers_addButton) : Theme.getColor(Theme.key_windowBackgroundGray));
        tv.setBackground(bg);
        tv.setTextColor(primary ? Theme.getColor(Theme.key_featuredStickers_buttonText) : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
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
}
