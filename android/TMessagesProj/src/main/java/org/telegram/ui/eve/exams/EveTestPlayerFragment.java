package org.telegram.ui.eve.exams;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Interactive server-backed MCQ player inside Telegram's BaseFragment architecture.
 * Supports question palette, section filtering, mark for review, answer persistence,
 * English/Hindi toggle, monotonic countdown timer, auto-submit, and server grading.
 */
public class EveTestPlayerFragment extends BaseFragment {

    private static final String PREFS_DRAFTS = "eve_test_drafts";
    private static final int ID_TOGGLE_LANG = 1;
    private static final int ID_SUBMIT_MENU = 2;

    private final EveExamModels.ExamSummary exam;
    private final EveExamModels.GeneratedTestSummary test;
    private final String attemptKey;

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Map<String, String> selectedAnswers = new HashMap<>();
    private final Set<String> markedForReview = new HashSet<>();
    private final Map<String, Integer> perQuestionSeconds = new HashMap<>();

    private List<EveExamModels.TestQuestion> allQuestions = new ArrayList<>();
    private List<Integer> filteredIndices = new ArrayList<>();
    private List<String> sections = new ArrayList<>();
    private String selectedSection = "";

    private String clientAttemptId = "";
    private int currentQuestionIdx = 0;
    private int timeLimitSeconds = 0;
    private int remainingSeconds = 0;
    private long timerAnchorElapsedMs = 0L;
    private long questionShownElapsedMs = 0L;
    private boolean preferHindi = LocaleController.isHindiSelected();
    private boolean submitting = false;
    private boolean finishedAttempt = false;

    private ProgressBar loadingBar;
    private TextView statusMessageView;
    private LinearLayout contentContainer;
    private HorizontalScrollView sectionStripScroll;
    private LinearLayout sectionStripLayout;
    private RecyclerView paletteRecycler;
    private PaletteAdapter paletteAdapter;
    private ScrollView questionScrollView;
    private TextView questionMetaView;
    private TextView questionTextView;
    private final LinearLayout[] optionCards = new LinearLayout[4];
    private final TextView[] optionBadges = new TextView[4];
    private final TextView[] optionTexts = new TextView[4];
    private TextView prevBtn;
    private TextView reviewBtn;
    private TextView clearBtn;
    private TextView nextBtn;
    private ActionBarMenuItem langMenuItem;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!finishedAttempt && !submitting && timeLimitSeconds > 0 && timerAnchorElapsedMs > 0L) {
                long elapsedSec = (SystemClock.elapsedRealtime() - timerAnchorElapsedMs) / 1000L;
                int left = Math.max(0, remainingSeconds - (int) elapsedSec);
                updateTimerSubtitle(left);
                if (left <= 0) {
                    performSubmit(true);
                    return;
                }
            }
            timerHandler.postDelayed(this, 1000L);
        }
    };

    public EveTestPlayerFragment(EveExamModels.ExamSummary exam, EveExamModels.GeneratedTestSummary test) {
        this.exam = exam;
        this.test = test;
        this.attemptKey = EveExamModels.buildAttemptKey(
                exam != null ? exam.id : (test != null ? test.examId : ""),
                test != null ? test.id : ""
        );
    }

    @Override
    public View createView(Context context) {
        String title = test != null ? test.getDisplayTitle() : (exam != null ? exam.examName : "Mock Test");
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(title);
        actionBar.setSubtitle(LocaleController.getString(R.string.EveStartTest));

        ActionBarMenu menu = actionBar.createMenu();
        langMenuItem = menu.addItem(ID_TOGGLE_LANG, R.drawable.msg_translate);
        langMenuItem.setVisibility(View.GONE);
        ActionBarMenuItem submitItem = menu.addItem(ID_SUBMIT_MENU, R.drawable.ic_ab_done);
        submitItem.setContentDescription(LocaleController.getString(R.string.EveSubmitTest));

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    onBackPressed(true);
                } else if (id == ID_TOGGLE_LANG) {
                    preferHindi = !preferHindi;
                    renderCurrentQuestion();
                } else if (id == ID_SUBMIT_MENU) {
                    promptSubmitConfirmation();
                }
            }
        });

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        loadingBar = new ProgressBar(context);
        LinearLayout.LayoutParams loadingLp = LayoutHelper.createLinear(48, 48, Gravity.CENTER, 0, 48, 0, 12);
        root.addView(loadingBar, loadingLp);

        statusMessageView = new TextView(context);
        statusMessageView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        statusMessageView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        statusMessageView.setGravity(Gravity.CENTER);
        statusMessageView.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(12), AndroidUtilities.dp(24), AndroidUtilities.dp(12));
        root.addView(statusMessageView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        contentContainer = new LinearLayout(context);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setVisibility(View.GONE);
        root.addView(contentContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        sectionStripScroll = new HorizontalScrollView(context);
        sectionStripScroll.setHorizontalScrollBarEnabled(false);
        sectionStripScroll.setVisibility(View.GONE);
        sectionStripLayout = new LinearLayout(context);
        sectionStripLayout.setOrientation(LinearLayout.HORIZONTAL);
        sectionStripLayout.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(4));
        sectionStripScroll.addView(sectionStripLayout);
        contentContainer.addView(sectionStripScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        paletteRecycler = new RecyclerView(context);
        paletteRecycler.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        paletteRecycler.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(10), AndroidUtilities.dp(6));
        paletteRecycler.setClipToPadding(false);
        paletteAdapter = new PaletteAdapter();
        paletteRecycler.setAdapter(paletteAdapter);
        contentContainer.addView(paletteRecycler, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(52)));

        questionScrollView = new ScrollView(context);
        LinearLayout questionCard = new LinearLayout(context);
        questionCard.setOrientation(LinearLayout.VERTICAL);
        questionCard.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        questionCard.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(20));
        questionScrollView.addView(questionCard, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        contentContainer.addView(questionScrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        questionMetaView = new TextView(context);
        questionMetaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        questionMetaView.setTypeface(AndroidUtilities.bold());
        questionMetaView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        questionCard.addView(questionMetaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        questionTextView = new TextView(context);
        questionTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        questionTextView.setLineSpacing(AndroidUtilities.dp(4), 1f);
        questionTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        questionCard.addView(questionTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        String[] letters = new String[] { "A", "B", "C", "D" };
        for (int i = 0; i < 4; i++) {
            final int optionIdx = i;
            final String letter = letters[i];
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
            card.setOnClickListener(v -> selectOption(letter));

            TextView badge = new TextView(context);
            badge.setText(letter);
            badge.setGravity(Gravity.CENTER);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            badge.setTypeface(AndroidUtilities.bold());
            card.addView(badge, LayoutHelper.createLinear(30, 30, 0, 0, 12, 0));

            TextView text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            card.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            optionCards[optionIdx] = card;
            optionBadges[optionIdx] = badge;
            optionTexts[optionIdx] = text;
            questionCard.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
        }

        LinearLayout footerBar = new LinearLayout(context);
        footerBar.setOrientation(LinearLayout.HORIZONTAL);
        footerBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        footerBar.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        contentContainer.addView(footerBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        prevBtn = createActionButton(context, LocaleController.getString(R.string.EvePrevious), false);
        prevBtn.setOnClickListener(v -> navigateRelative(-1));
        footerBar.addView(prevBtn, LayoutHelper.createLinear(0, 42, 1f, 4, 0, 4, 0));

        reviewBtn = createActionButton(context, LocaleController.getString(R.string.EveMarkForReview), false);
        reviewBtn.setOnClickListener(v -> toggleReviewForCurrent());
        footerBar.addView(reviewBtn, LayoutHelper.createLinear(0, 42, 1.1f, 4, 0, 4, 0));

        clearBtn = createActionButton(context, LocaleController.getString(R.string.EveClearResponse), false);
        clearBtn.setOnClickListener(v -> clearCurrentAnswer());
        footerBar.addView(clearBtn, LayoutHelper.createLinear(0, 42, 0.8f, 4, 0, 4, 0));

        nextBtn = createActionButton(context, LocaleController.getString(R.string.EveSaveAndNext), true);
        nextBtn.setOnClickListener(v -> {
            if (isLastFilteredQuestion()) {
                promptSubmitConfirmation();
            } else {
                navigateRelative(1);
            }
        });
        footerBar.addView(nextBtn, LayoutHelper.createLinear(0, 42, 1.15f, 4, 0, 4, 0));

        fragmentView = root;
        startTestSession();
        return root;
    }

    private TextView createActionButton(Context context, String label, boolean primary) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tv.setTypeface(AndroidUtilities.bold());
        styleActionButton(tv, primary, false);
        return tv;
    }

    private void styleActionButton(TextView tv, boolean primary, boolean activeWarning) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(8));
        if (activeWarning) {
            bg.setColor(0xFFE68A00);
            tv.setTextColor(Color.WHITE);
        } else if (primary) {
            bg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            tv.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        } else {
            bg.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
            tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        }
        tv.setBackground(bg);
    }

    private void startTestSession() {
        String draftAttemptId = loadSavedClientAttemptId();
        String sourceExamId = exam != null ? exam.id : (test != null ? test.examId : "");
        String testId = test != null ? test.id : "";
        loadingBar.setVisibility(View.VISIBLE);
        statusMessageView.setVisibility(View.VISIBLE);
        statusMessageView.setText("Preparing live test session...");

        EveExamRepository.getInstance().startOrResumeAttempt(attemptKey, sourceExamId, testId, draftAttemptId, (res, err) -> {
            if (getParentActivity() == null) {
                return;
            }
            if (!err.isEmpty() || res == null) {
                loadingBar.setVisibility(View.GONE);
                statusMessageView.setText(!err.isEmpty() ? err : "Could not start test.");
                return;
            }
            if (res.alreadySubmitted && !res.completedAttemptId.isEmpty()) {
                clearSavedDraft();
                openExistingSubmittedAttempt(res.completedAttemptId);
                return;
            }
            if (res.questions.isEmpty()) {
                loadingBar.setVisibility(View.GONE);
                statusMessageView.setText("No questions available for this test.");
                return;
            }

            clientAttemptId = res.clientAttemptId;
            allQuestions = new ArrayList<>(res.questions);
            timeLimitSeconds = res.timeLimitSeconds;
            remainingSeconds = res.remainingSeconds > 0 ? res.remainingSeconds : res.timeLimitSeconds;
            timerAnchorElapsedMs = SystemClock.elapsedRealtime();

            restoreDraftAnswers();
            buildSections();
            applySectionFilter("");

            boolean anyHindi = false;
            for (EveExamModels.TestQuestion q : allQuestions) {
                if (q.hasHindiVariant()) {
                    anyHindi = true;
                    break;
                }
            }
            if (langMenuItem != null) {
                langMenuItem.setVisibility(anyHindi ? View.VISIBLE : View.GONE);
            }

            loadingBar.setVisibility(View.GONE);
            statusMessageView.setVisibility(View.GONE);
            contentContainer.setVisibility(View.VISIBLE);
            questionShownElapsedMs = SystemClock.elapsedRealtime();
            renderCurrentQuestion();
            updateTimerSubtitle(remainingSeconds);
            timerHandler.removeCallbacks(timerRunnable);
            timerHandler.postDelayed(timerRunnable, 1000L);
        });
    }

    private void openExistingSubmittedAttempt(String attemptId) {
        statusMessageView.setText("Loading submitted test result...");
        EveExamRepository.getInstance().loadAttemptById(attemptId, (attemptResult, err) -> {
            if (getParentActivity() == null) return;
            loadingBar.setVisibility(View.GONE);
            if (attemptResult == null) {
                statusMessageView.setText(!err.isEmpty() ? err : "Attempt already submitted.");
                return;
            }
            finishedAttempt = true;
            presentFragment(new EveTestResultFragment(exam, test, attemptKey, attemptResult), true);
        });
    }

    private void buildSections() {
        Set<String> unique = new LinkedHashSet<>();
        for (EveExamModels.TestQuestion q : allQuestions) {
            if (!q.topic.isEmpty()) {
                unique.add(q.topic);
            }
        }
        sections = new ArrayList<>(unique);
        if (sections.size() > 1 && getParentActivity() != null) {
            sectionStripScroll.setVisibility(View.VISIBLE);
            rebuildSectionPills();
        } else {
            sectionStripScroll.setVisibility(View.GONE);
        }
    }

    private void rebuildSectionPills() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        sectionStripLayout.removeAllViews();
        addSectionPill(activity, "", LocaleController.getString(R.string.EveAllSections));
        for (String sec : sections) {
            addSectionPill(activity, sec, sec);
        }
    }

    private void addSectionPill(Context context, String sectionKey, String label) {
        TextView chip = new TextView(context);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(6), AndroidUtilities.dp(12), AndroidUtilities.dp(6));
        boolean active = sectionKey.equals(selectedSection);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(14));
        bg.setColor(active ? Theme.getColor(Theme.key_featuredStickers_addButton) : Theme.getColor(Theme.key_windowBackgroundWhite));
        chip.setBackground(bg);
        chip.setTextColor(active ? Theme.getColor(Theme.key_featuredStickers_buttonText) : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        chip.setOnClickListener(v -> {
            recordCurrentQuestionTime();
            applySectionFilter(sectionKey);
            rebuildSectionPills();
            renderCurrentQuestion();
        });
        sectionStripLayout.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));
    }

    private void applySectionFilter(String sectionKey) {
        selectedSection = sectionKey != null ? sectionKey : "";
        filteredIndices = new ArrayList<>();
        for (int i = 0; i < allQuestions.size(); i++) {
            EveExamModels.TestQuestion q = allQuestions.get(i);
            if (selectedSection.isEmpty() || selectedSection.equalsIgnoreCase(q.topic)) {
                filteredIndices.add(i);
            }
        }
        if (filteredIndices.isEmpty() && !allQuestions.isEmpty()) {
            for (int i = 0; i < allQuestions.size(); i++) {
                filteredIndices.add(i);
            }
        }
        if (!filteredIndices.contains(currentQuestionIdx) && !filteredIndices.isEmpty()) {
            currentQuestionIdx = filteredIndices.get(0);
        }
        if (paletteAdapter != null) {
            paletteAdapter.notifyDataSetChanged();
        }
    }

    private void recordCurrentQuestionTime() {
        if (allQuestions.isEmpty() || questionShownElapsedMs <= 0L) return;
        EveExamModels.TestQuestion q = allQuestions.get(currentQuestionIdx);
        int deltaSec = (int) Math.max(0L, (SystemClock.elapsedRealtime() - questionShownElapsedMs) / 1000L);
        if (deltaSec > 0) {
            int prev = perQuestionSeconds.containsKey(q.id) ? perQuestionSeconds.get(q.id) : 0;
            perQuestionSeconds.put(q.id, prev + deltaSec);
        }
        questionShownElapsedMs = SystemClock.elapsedRealtime();
    }

    private void renderCurrentQuestion() {
        if (allQuestions.isEmpty() || currentQuestionIdx < 0 || currentQuestionIdx >= allQuestions.size()) {
            return;
        }
        EveExamModels.TestQuestion q = allQuestions.get(currentQuestionIdx);
        String meta = "Q" + q.number + " / " + allQuestions.size();
        if (!q.topic.isEmpty()) {
            meta += "  •  " + q.topic;
        }
        questionMetaView.setText(meta);
        questionTextView.setText(q.getQuestionText(preferHindi));

        String picked = selectedAnswers.get(q.id);
        String[] letters = new String[] { "A", "B", "C", "D" };
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        int divider = Theme.getColor(Theme.key_divider);
        int surface = Theme.getColor(Theme.key_windowBackgroundWhite);

        for (int i = 0; i < 4; i++) {
            String letter = letters[i];
            boolean isPicked = letter.equalsIgnoreCase(picked);
            optionTexts[i].setText(q.getOptionText(letter, preferHindi));

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setCornerRadius(AndroidUtilities.dp(10));
            cardBg.setStroke(AndroidUtilities.dp(isPicked ? 2 : 1), isPicked ? accent : divider);
            cardBg.setColor(isPicked ? Color.argb(24, Color.red(accent), Color.green(accent), Color.blue(accent)) : surface);
            optionCards[i].setBackground(cardBg);

            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(AndroidUtilities.dp(15));
            badgeBg.setColor(isPicked ? accent : Theme.getColor(Theme.key_windowBackgroundGray));
            optionBadges[i].setBackground(badgeBg);
            optionBadges[i].setTextColor(isPicked ? Theme.getColor(Theme.key_featuredStickers_buttonText) : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        }

        boolean marked = markedForReview.contains(q.id);
        reviewBtn.setText(LocaleController.getString(marked ? R.string.EveUnmarkReview : R.string.EveMarkForReview));
        styleActionButton(reviewBtn, false, marked);
        nextBtn.setText(LocaleController.getString(isLastFilteredQuestion() ? R.string.EveSubmitTest : R.string.EveSaveAndNext));

        prevBtn.setEnabled(filteredPosOf(currentQuestionIdx) > 0);
        prevBtn.setAlpha(prevBtn.isEnabled() ? 1f : 0.45f);

        if (paletteAdapter != null) {
            paletteAdapter.notifyDataSetChanged();
            int fPos = filteredPosOf(currentQuestionIdx);
            if (fPos >= 0) {
                paletteRecycler.smoothScrollToPosition(fPos);
            }
        }
        questionScrollView.smoothScrollTo(0, 0);
    }

    private void selectOption(String letter) {
        if (allQuestions.isEmpty()) return;
        EveExamModels.TestQuestion q = allQuestions.get(currentQuestionIdx);
        selectedAnswers.put(q.id, letter);
        saveDraftAnswers();
        renderCurrentQuestion();
    }

    private void toggleReviewForCurrent() {
        if (allQuestions.isEmpty()) return;
        EveExamModels.TestQuestion q = allQuestions.get(currentQuestionIdx);
        if (markedForReview.contains(q.id)) {
            markedForReview.remove(q.id);
        } else {
            markedForReview.add(q.id);
        }
        saveDraftAnswers();
        renderCurrentQuestion();
    }

    private void clearCurrentAnswer() {
        if (allQuestions.isEmpty()) return;
        EveExamModels.TestQuestion q = allQuestions.get(currentQuestionIdx);
        selectedAnswers.remove(q.id);
        saveDraftAnswers();
        renderCurrentQuestion();
    }

    private int filteredPosOf(int globalIdx) {
        return filteredIndices.indexOf(globalIdx);
    }

    private boolean isLastFilteredQuestion() {
        int pos = filteredPosOf(currentQuestionIdx);
        return pos >= 0 && pos == filteredIndices.size() - 1;
    }

    private void navigateRelative(int delta) {
        int pos = filteredPosOf(currentQuestionIdx);
        if (pos < 0) return;
        int nextPos = pos + delta;
        if (nextPos >= 0 && nextPos < filteredIndices.size()) {
            recordCurrentQuestionTime();
            currentQuestionIdx = filteredIndices.get(nextPos);
            saveDraftAnswers();
            renderCurrentQuestion();
        }
    }

    private void updateTimerSubtitle(int secondsLeft) {
        if (actionBar == null) return;
        if (timeLimitSeconds <= 0) {
            actionBar.setSubtitle("Q " + (currentQuestionIdx + 1) + "/" + allQuestions.size());
            return;
        }
        int hrs = secondsLeft / 3600;
        int mins = (secondsLeft % 3600) / 60;
        int secs = secondsLeft % 60;
        String formatted = hrs > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
                : String.format(Locale.US, "%02d:%02d", mins, secs);
        actionBar.setSubtitle("⏱ " + formatted + "  •  " + selectedAnswers.size() + "/" + allQuestions.size());
    }

    private void promptSubmitConfirmation() {
        Activity activity = getParentActivity();
        if (activity == null || submitting || allQuestions.isEmpty()) return;
        recordCurrentQuestionTime();
        int answered = selectedAnswers.size();
        int marked = markedForReview.size();
        int unanswered = Math.max(0, allQuestions.size() - answered);
        String body = LocaleController.formatString(R.string.EveSubmitConfirmBody, answered, marked, unanswered);
        new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.EveSubmitConfirmTitle))
                .setMessage(body)
                .setPositiveButton(LocaleController.getString(R.string.EveSubmitTest), (d, w) -> performSubmit(false))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void performSubmit(boolean autoSubmitted) {
        if (submitting || finishedAttempt) return;
        submitting = true;
        recordCurrentQuestionTime();
        timerHandler.removeCallbacks(timerRunnable);

        if (actionBar != null) {
            actionBar.setSubtitle(autoSubmitted ? "Time up — submitting..." : "Submitting answers...");
        }

        JSONArray answersArr = new JSONArray();
        for (int i = 0; i < allQuestions.size(); i++) {
            EveExamModels.TestQuestion q = allQuestions.get(i);
            try {
                JSONObject obj = new JSONObject();
                obj.put("questionId", q.id);
                obj.put("number", q.number);
                obj.put("selected", selectedAnswers.containsKey(q.id) ? selectedAnswers.get(q.id) : "");
                obj.put("isBookmarked", false);
                obj.put("markedForReview", markedForReview.contains(q.id));
                obj.put("timeTakenSeconds", perQuestionSeconds.containsKey(q.id) ? perQuestionSeconds.get(q.id) : 0);
                answersArr.put(obj);
            } catch (Exception ignored) {
            }
        }

        String examName = exam != null && !exam.examName.isEmpty() ? exam.examName : (test != null ? test.getDisplayTitle() : "Test");
        String category = exam != null ? exam.category : "General";

        EveExamRepository.getInstance().submitAttempt(attemptKey, examName, category, clientAttemptId, answersArr, (result, err) -> {
            submitting = false;
            if (getParentActivity() == null) return;
            if (result == null || !err.isEmpty()) {
                BulletinFactory.of(this).createErrorBulletin(!err.isEmpty() ? err : "Submission failed. Please retry.").show();
                timerHandler.postDelayed(timerRunnable, 1000L);
                return;
            }
            finishedAttempt = true;
            clearSavedDraft();
            presentFragment(new EveTestResultFragment(exam, test, attemptKey, result), true);
        });
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!invoked) {
            return super.onBackPressed(false);
        }
        if (finishedAttempt || allQuestions.isEmpty()) {
            return super.onBackPressed(true);
        }
        Activity activity = getParentActivity();
        if (activity == null) {
            return super.onBackPressed(true);
        }
        new AlertDialog.Builder(activity)
                .setTitle("Pause or Submit Test?")
                .setMessage("Your answers are saved locally. You can pause and resume later, or submit now.")
                .setPositiveButton("Pause & Exit", (d, w) -> {
                    recordCurrentQuestionTime();
                    saveDraftAnswers();
                    EveExamRepository.getInstance().pauseAttempt(attemptKey);
                    finishedAttempt = true;
                    finishFragment();
                })
                .setNeutralButton(LocaleController.getString(R.string.EveSubmitTest), (d, w) -> performSubmit(false))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
        return false;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        timerHandler.removeCallbacks(timerRunnable);
        if (!finishedAttempt && !allQuestions.isEmpty()) {
            recordCurrentQuestionTime();
            saveDraftAnswers();
            EveExamRepository.getInstance().pauseAttempt(attemptKey);
        }
    }

    private String draftKey() {
        return "draft_" + attemptKey;
    }

    private String loadSavedClientAttemptId() {
        try {
            SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_DRAFTS, Context.MODE_PRIVATE);
            String raw = prefs.getString(draftKey(), "");
            if (raw.isEmpty()) return "";
            JSONObject obj = new JSONObject(raw);
            return obj.optString("clientAttemptId", "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void saveDraftAnswers() {
        try {
            JSONObject root = new JSONObject();
            root.put("clientAttemptId", clientAttemptId);
            root.put("currentQuestionIdx", currentQuestionIdx);
            JSONObject picks = new JSONObject();
            for (Map.Entry<String, String> entry : selectedAnswers.entrySet()) {
                picks.put(entry.getKey(), entry.getValue());
            }
            root.put("picks", picks);
            JSONArray reviewArr = new JSONArray();
            for (String qId : markedForReview) {
                reviewArr.put(qId);
            }
            root.put("review", reviewArr);
            JSONObject times = new JSONObject();
            for (Map.Entry<String, Integer> entry : perQuestionSeconds.entrySet()) {
                times.put(entry.getKey(), entry.getValue());
            }
            root.put("times", times);

            ApplicationLoader.applicationContext.getSharedPreferences(PREFS_DRAFTS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(draftKey(), root.toString())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void restoreDraftAnswers() {
        try {
            String raw = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_DRAFTS, Context.MODE_PRIVATE)
                    .getString(draftKey(), "");
            if (raw.isEmpty()) return;
            JSONObject root = new JSONObject(raw);
            String savedAttemptId = root.optString("clientAttemptId", "");
            if (!clientAttemptId.isEmpty() && !savedAttemptId.isEmpty() && !clientAttemptId.equals(savedAttemptId)) {
                return;
            }
            JSONObject picks = root.optJSONObject("picks");
            if (picks != null && picks.names() != null) {
                JSONArray names = picks.names();
                for (int i = 0; i < names.length(); i++) {
                    String k = names.getString(i);
                    selectedAnswers.put(k, picks.optString(k, ""));
                }
            }
            JSONArray reviewArr = root.optJSONArray("review");
            if (reviewArr != null) {
                for (int i = 0; i < reviewArr.length(); i++) {
                    markedForReview.add(reviewArr.getString(i));
                }
            }
            JSONObject times = root.optJSONObject("times");
            if (times != null && times.names() != null) {
                JSONArray names = times.names();
                for (int i = 0; i < names.length(); i++) {
                    String k = names.getString(i);
                    perQuestionSeconds.put(k, times.optInt(k, 0));
                }
            }
            int idx = root.optInt("currentQuestionIdx", 0);
            if (idx >= 0 && idx < allQuestions.size()) {
                currentQuestionIdx = idx;
            }
        } catch (Throwable ignored) {
        }
    }

    private void clearSavedDraft() {
        try {
            ApplicationLoader.applicationContext.getSharedPreferences(PREFS_DRAFTS, Context.MODE_PRIVATE)
                    .edit()
                    .remove(draftKey())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private final class PaletteAdapter extends RecyclerView.Adapter<PaletteAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            tv.setTypeface(AndroidUtilities.bold());
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(AndroidUtilities.dp(36), AndroidUtilities.dp(36));
            lp.setMargins(AndroidUtilities.dp(4), AndroidUtilities.dp(2), AndroidUtilities.dp(4), AndroidUtilities.dp(2));
            tv.setLayoutParams(lp);
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            int globalIdx = filteredIndices.get(position);
            EveExamModels.TestQuestion q = allQuestions.get(globalIdx);
            TextView tv = (TextView) holder.itemView;
            tv.setText(String.valueOf(q.number));

            boolean current = globalIdx == currentQuestionIdx;
            boolean answered = selectedAnswers.containsKey(q.id) && !selectedAnswers.get(q.id).isEmpty();
            boolean review = markedForReview.contains(q.id);

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(AndroidUtilities.dp(18));
            if (review) {
                bg.setColor(0xFFE68A00);
                tv.setTextColor(Color.WHITE);
            } else if (answered) {
                bg.setColor(0xFF2E9E5B);
                tv.setTextColor(Color.WHITE);
            } else {
                bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            }
            if (current) {
                bg.setStroke(AndroidUtilities.dp(2), Theme.getColor(Theme.key_featuredStickers_addButton));
            }
            tv.setBackground(bg);

            tv.setOnClickListener(v -> {
                recordCurrentQuestionTime();
                currentQuestionIdx = globalIdx;
                saveDraftAnswers();
                renderCurrentQuestion();
            });
        }

        @Override
        public int getItemCount() {
            return filteredIndices.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            Holder(@NonNull View itemView) {
                super(itemView);
            }
        }
    }
}
