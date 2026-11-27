package org.telegram.ui.eve.exams;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.SimpleActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.eve.admin.EveAnimatedEmojiHelper;
import org.telegram.ui.eve.admin.EveAdminUiChrome;

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
 * Supports Question Palette bottom sheet, section filtering, mark for review, answer persistence,
 * English/Hindi toggle, monotonic countdown timer pill, question reporting, and server grading.
 */
public class EveTestPlayerFragment extends BaseFragment {

    private static final String PREFS_DRAFTS = "eve_test_drafts";
    private static final int ID_TOGGLE_LANG = 1;
    private static final int ID_PALETTE_MENU = 2;
    private static final int ID_SUBMIT_MENU = 3;

    public enum PaletteState {
        UNVISITED,
        ANSWERED,
        MARKED,
        ANSWERED_AND_MARKED
    }

    public static PaletteState resolvePaletteState(boolean answered, boolean marked) {
        if (answered && marked) {
            return PaletteState.ANSWERED_AND_MARKED;
        }
        if (marked) {
            return PaletteState.MARKED;
        }
        if (answered) {
            return PaletteState.ANSWERED;
        }
        return PaletteState.UNVISITED;
    }

    public static boolean isLowTimeWarning(int secondsLeft, int timeLimitSeconds) {
        return timeLimitSeconds > 0 && secondsLeft <= 120;
    }

    public static String validateStartupSession(String attemptKey, EveExamModels.StartAttemptResult res, String error) {
        if (attemptKey == null || attemptKey.trim().isEmpty()) {
            return "Invalid mock test identifier. Please go back and try again.";
        }
        if (error != null && !error.trim().isEmpty()) {
            return error.trim();
        }
        if (res == null) {
            return "Could not initialize test session.";
        }
        if (res.alreadySubmitted) {
            if (res.completedAttemptId != null && !res.completedAttemptId.trim().isEmpty()) {
                return "ALREADY_SUBMITTED";
            }
            return "This attempt was already submitted, but the result reference is missing.";
        }
        if (res.questions == null || res.questions.isEmpty()) {
            return "No questions are available in this test yet.";
        }
        return "";
    }

    private final EveExamModels.ExamSummary exam;
    private final EveExamModels.GeneratedTestSummary test;
    private final String attemptKey;

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Map<String, String> selectedAnswers = new HashMap<>();
    private final Set<String> markedForReview = new HashSet<>();
    private final Set<String> bookmarkedIds = new HashSet<>();
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
    private volatile boolean destroyed = false;
    private int navBarHeight = 0;

    private LinearLayout mainColumn;
    private View actionBarFadeView;
    private ProgressBar loadingBar;
    private TextView statusMessageView;
    private LinearLayout errorCard;
    private TextView errorTitleView;
    private TextView errorBodyView;
    private TextView errorRetryBtn;
    private TextView errorBackBtn;
    private LinearLayout contentContainer;
    private LinearLayout statusHeaderStrip;
    private TextView statusStripText;
    private CircularTimerView circularTimerView;
    private ProgressBar questionProgressBar;
    private RecyclerView inlinePaletteView;
    private PaletteAdapter inlinePaletteAdapter;
    private View statusDivider;
    private HorizontalScrollView sectionStripScroll;
    private LinearLayout sectionStripLayout;
    private ScrollView questionScrollView;
    private LinearLayout questionCard;
    private TextView questionMetaView;
    private TextView questionTextView;
    private final LinearLayout[] optionCards = new LinearLayout[4];
    private final TextView[] optionBadges = new TextView[4];
    private final TextView[] optionTexts = new TextView[4];
    private View footerDivider;
    private LinearLayout footerBar;
    private TextView prevBtn;
    private TextView reviewBtn;
    private TextView clearBtn;
    private ImageView bookmarkBtn;
    private ImageView reportBtn;
    private TextView nextBtn;
    private SimpleActionBarMenuItem langMenuItem;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            if (!finishedAttempt && !submitting && timeLimitSeconds > 0 && timerAnchorElapsedMs > 0L) {
                long elapsedSec = (SystemClock.elapsedRealtime() - timerAnchorElapsedMs) / 1000L;
                int left = Math.max(0, remainingSeconds - (int) elapsedSec);
                updateTimerPill(left);
                if (left <= 0) {
                    performSubmit(true);
                    return;
                }
            }
            timerHandler.postDelayed(this, 1000L);
        }
    };

    public EveTestPlayerFragment() {
        this(null, null);
    }

    public EveTestPlayerFragment(@Nullable Bundle args) {
        super(args);
        this.exam = null;
        this.test = null;
        String argExamId = args != null ? args.getString("examId", "") : "";
        String argTestId = args != null ? args.getString("testId", "") : "";
        this.attemptKey = EveExamModels.buildAttemptKey(argExamId, argTestId);
    }

    public EveTestPlayerFragment(@Nullable EveExamModels.ExamSummary exam, @Nullable EveExamModels.GeneratedTestSummary test) {
        this.exam = exam;
        this.test = test;
        this.attemptKey = EveExamModels.buildAttemptKey(
                exam != null ? exam.id : (test != null ? test.examId : ""),
                test != null ? test.id : ""
        );
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    protected boolean useFadeDrawableForActionBar() {
        return false;
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
        try {
            return createPlayerViewInternal(context);
        } catch (Throwable err) {
            FileLog.e("EveTestPlayerFragment createView crash guarded", err);
            return createCrashFallbackView(context, err);
        }
    }

    private View createCrashFallbackView(Context context, Throwable err) {
        Theme.ResourcesProvider rp = getResourceProvider();
        LinearLayout fallback = new LinearLayout(context);
        fallback.setOrientation(LinearLayout.VERTICAL);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPadding(dp(24), dp(24), dp(24), dp(24));
        fallback.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, rp));

        TextView title = new TextView(context);
        title.setText("Unable to open mock test");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        title.setGravity(Gravity.CENTER);
        fallback.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        TextView msg = new TextView(context);
        String errMsg = err != null && !TextUtils.isEmpty(err.getMessage()) ? err.getMessage() : "Unexpected startup error while preparing test view.";
        msg.setText(errMsg);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        msg.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        msg.setGravity(Gravity.CENTER);
        fallback.addView(msg, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 18));

        TextView backBtn = createActionButton(context, "Go Back", true);
        backBtn.setOnClickListener(v -> finishFragment());
        fallback.addView(backBtn, LayoutHelper.createLinear(160, 42));
        fragmentView = fallback;
        return fallback;
    }

    private View createPlayerViewInternal(Context context) {
        String title = test != null && !TextUtils.isEmpty(test.getDisplayTitle())
                ? test.getDisplayTitle()
                : (exam != null && !TextUtils.isEmpty(exam.examName) ? exam.examName : "Mock Test");
        Theme.ResourcesProvider rp = getResourceProvider();

        actionBarFadeView = new View(context);
        if (getBaseSimpleGlass() != null) {
            actionBarFadeView.setBackground(getBaseSimpleGlass().createActionBarFadeDrawable(actionBarFadeView));
        }

        SizeNotifierFrameLayout rootFrame = new SizeNotifierFrameLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                if (glassEngine != null) {
                    glassEngine.invalidate();
                }
                if (navBarHeight > 0) {
                    AndroidUtilities.drawNavigationBarProtection(
                            canvas, this, Theme.getColor(Theme.key_windowBackgroundWhite, rp), navBarHeight);
                }
            }

            @Override
            public void updateColors() {
                super.updateColors();
                setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, rp));
                if (simpleActionBar != null) {
                    simpleActionBar.updateColors();
                }
                applyThemeColors();
            }
        };
        rootFrame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, rp));

        mainColumn = new LinearLayout(context);
        mainColumn.setOrientation(LinearLayout.VERTICAL);
        rootFrame.addView(mainColumn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        loadingBar = new ProgressBar(context);
        mainColumn.addView(loadingBar, LayoutHelper.createLinear(48, 48, Gravity.CENTER, 0, 48, 0, 12));

        statusMessageView = new TextView(context);
        statusMessageView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        statusMessageView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        statusMessageView.setGravity(Gravity.CENTER);
        statusMessageView.setPadding(dp(24), dp(12), dp(24), dp(12));
        mainColumn.addView(statusMessageView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        errorCard = new LinearLayout(context);
        errorCard.setOrientation(LinearLayout.VERTICAL);
        errorCard.setGravity(Gravity.CENTER_HORIZONTAL);
        errorCard.setPadding(dp(20), dp(22), dp(20), dp(22));
        errorCard.setVisibility(View.GONE);
        mainColumn.addView(errorCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 20, 16, 0));

        errorTitleView = new TextView(context);
        errorTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        errorTitleView.setTypeface(AndroidUtilities.bold());
        errorTitleView.setGravity(Gravity.CENTER);
        errorCard.addView(errorTitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        errorBodyView = new TextView(context);
        errorBodyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        errorBodyView.setGravity(Gravity.CENTER);
        errorBodyView.setLineSpacing(dp(3), 1f);
        errorCard.addView(errorBodyView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 18));

        LinearLayout errorActions = new LinearLayout(context);
        errorActions.setOrientation(LinearLayout.HORIZONTAL);
        errorActions.setGravity(Gravity.CENTER);
        errorCard.addView(errorActions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        errorRetryBtn = createActionButton(context, "Retry", true);
        errorRetryBtn.setOnClickListener(v -> startTestSession());
        errorActions.addView(errorRetryBtn, LayoutHelper.createLinear(0, 42, 1f, 0, 0, 8, 0));

        errorBackBtn = createActionButton(context, "Go Back", false);
        errorBackBtn.setOnClickListener(v -> finishFragment());
        errorActions.addView(errorBackBtn, LayoutHelper.createLinear(0, 42, 1f, 8, 0, 0, 0));

        contentContainer = new LinearLayout(context);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setVisibility(View.GONE);
        mainColumn.addView(contentContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        // Status strip + Circular Timer + thin smooth ProgressBar + inline interactive Palette
        statusHeaderStrip = new LinearLayout(context);
        statusHeaderStrip.setOrientation(LinearLayout.VERTICAL);
        statusHeaderStrip.setPadding(dp(14), dp(10), dp(14), dp(8));
        contentContainer.addView(statusHeaderStrip, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout topStatusRow = new LinearLayout(context);
        topStatusRow.setOrientation(LinearLayout.HORIZONTAL);
        topStatusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusHeaderStrip.addView(topStatusRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        statusStripText = new TextView(context);
        statusStripText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        statusStripText.setTypeface(AndroidUtilities.bold());
        statusStripText.setSingleLine(true);
        statusStripText.setEllipsize(TextUtils.TruncateAt.END);
        topStatusRow.addView(statusStripText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));

        circularTimerView = new CircularTimerView(context, rp);
        circularTimerView.setOnClickListener(v -> showQuestionPaletteSheet());
        topStatusRow.addView(circularTimerView, LayoutHelper.createLinear(52, 52));

        questionProgressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        questionProgressBar.setMax(1000);
        statusHeaderStrip.addView(questionProgressBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 4, 0, 0, 0, 8));

        inlinePaletteView = new RecyclerView(context);
        inlinePaletteView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        inlinePaletteView.setClipToPadding(false);
        inlinePaletteAdapter = new PaletteAdapter(true, null);
        inlinePaletteView.setAdapter(inlinePaletteAdapter);
        statusHeaderStrip.addView(inlinePaletteView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

        statusDivider = new View(context);
        contentContainer.addView(statusDivider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        sectionStripScroll = new HorizontalScrollView(context);
        sectionStripScroll.setHorizontalScrollBarEnabled(false);
        sectionStripScroll.setVisibility(View.GONE);
        sectionStripLayout = new LinearLayout(context);
        sectionStripLayout.setOrientation(LinearLayout.HORIZONTAL);
        sectionStripLayout.setPadding(dp(12), dp(8), dp(12), dp(4));
        sectionStripScroll.addView(sectionStripLayout);
        contentContainer.addView(sectionStripScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        questionScrollView = new ScrollView(context);
        questionScrollView.setClipToPadding(false);
        questionScrollView.setPadding(dp(12), dp(12), dp(12), dp(16));
        contentContainer.addView(questionScrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        questionCard = new LinearLayout(context);
        questionCard.setOrientation(LinearLayout.VERTICAL);
        questionCard.setPadding(dp(16), dp(16), dp(16), dp(18));
        questionScrollView.addView(questionCard, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        questionMetaView = new TextView(context);
        questionMetaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        questionMetaView.setTypeface(AndroidUtilities.bold());
        questionCard.addView(questionMetaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        questionTextView = EveAnimatedEmojiHelper.createEmojiTextView(context);
        questionTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        questionTextView.setLineSpacing(dp(4), 1f);
        questionCard.addView(questionTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        String[] letters = new String[] { "A", "B", "C", "D" };
        for (int i = 0; i < 4; i++) {
            final int optionIdx = i;
            final String letter = letters[i];
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.setOnClickListener(v -> selectOption(letter));

            TextView badge = new TextView(context);
            badge.setText(letter);
            badge.setGravity(Gravity.CENTER);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            badge.setTypeface(AndroidUtilities.bold());
            card.addView(badge, LayoutHelper.createLinear(30, 30, 0, 0, 12, 0));

            TextView text = EveAnimatedEmojiHelper.createEmojiTextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            text.setLineSpacing(dp(2), 1f);
            card.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            optionCards[optionIdx] = card;
            optionBadges[optionIdx] = badge;
            optionTexts[optionIdx] = text;
            questionCard.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
        }

        footerDivider = new View(context);
        contentContainer.addView(footerDivider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        footerBar = new LinearLayout(context);
        footerBar.setOrientation(LinearLayout.HORIZONTAL);
        footerBar.setGravity(Gravity.CENTER_VERTICAL);
        footerBar.setPadding(dp(8), dp(8), dp(8), dp(8));
        contentContainer.addView(footerBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        prevBtn = createActionButton(context, LocaleController.getString(R.string.EvePrevious), false);
        prevBtn.setOnClickListener(v -> navigateRelative(-1));
        footerBar.addView(prevBtn, LayoutHelper.createLinear(0, 40, 0.9f, 3, 0, 3, 0));

        reviewBtn = createActionButton(context, "Mark", false);
        reviewBtn.setOnClickListener(v -> toggleReviewForCurrent());
        footerBar.addView(reviewBtn, LayoutHelper.createLinear(0, 40, 0.9f, 3, 0, 3, 0));

        clearBtn = createActionButton(context, "Clear", false);
        clearBtn.setOnClickListener(v -> clearCurrentAnswer());
        footerBar.addView(clearBtn, LayoutHelper.createLinear(0, 40, 0.8f, 3, 0, 3, 0));

        reportBtn = new ImageView(context);
        reportBtn.setScaleType(ImageView.ScaleType.CENTER);
        reportBtn.setImageResource(R.drawable.msg_report);
        reportBtn.setContentDescription(LocaleController.getString(R.string.EveReportQuestion));
        reportBtn.setOnClickListener(v -> showReportCurrentQuestionDialog());
        footerBar.addView(reportBtn, LayoutHelper.createLinear(38, 40, 2, 0, 2, 0));

        bookmarkBtn = new ImageView(context);
        bookmarkBtn.setScaleType(ImageView.ScaleType.CENTER);
        bookmarkBtn.setImageResource(R.drawable.outline_saved_24);
        bookmarkBtn.setContentDescription(LocaleController.getString(R.string.EveBookmark));
        bookmarkBtn.setOnClickListener(v -> toggleBookmarkForCurrent());
        footerBar.addView(bookmarkBtn, LayoutHelper.createLinear(38, 40, 2, 0, 2, 0));

        nextBtn = createActionButton(context, LocaleController.getString(R.string.EveSaveAndNext), true);
        nextBtn.setOnClickListener(v -> {
            if (isLastFilteredQuestion()) {
                promptSubmitConfirmation();
            } else {
                navigateRelative(1);
            }
        });
        footerBar.addView(nextBtn, LayoutHelper.createLinear(0, 40, 1.15f, 3, 0, 3, 0));

        rootFrame.addView(actionBarFadeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.TOP));
        if (simpleActionBar != null) {
            simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
            simpleActionBar.setTitle(title);
            simpleActionBar.setBackground(null);
            simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        onBackPressed(true);
                    } else if (id == ID_TOGGLE_LANG) {
                        preferHindi = !preferHindi;
                        renderCurrentQuestion();
                    } else if (id == ID_PALETTE_MENU) {
                        showQuestionPaletteSheet();
                    } else if (id == ID_SUBMIT_MENU) {
                        promptSubmitConfirmation();
                    }
                }
            });
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            langMenuItem = menu.addItem(ID_TOGGLE_LANG, R.drawable.msg_translate, rp);
            langMenuItem.setVisibility(View.GONE);
            menu.addItem(ID_PALETTE_MENU, R.drawable.msg_palette, rp);
            SimpleActionBarMenuItem submitItem = menu.addItem(ID_SUBMIT_MENU, R.drawable.ic_ab_done, rp);
            submitItem.setContentDescription(LocaleController.getString(R.string.EveSubmitTest));

            AndroidUtilities.removeFromParent(simpleActionBar);
            rootFrame.addView(simpleActionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.TOP));
        }

        EveAdminUiChrome.updateActionBarFadeHeight(actionBarFadeView, AndroidUtilities.statusBarHeight);
        mainColumn.setPadding(0, AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight(), 0, 0);
        ViewCompat.setOnApplyWindowInsetsListener(rootFrame, (v, insets) -> {
            final Insets sys = AndroidUtilities.getDefaultWindowInsets(insets, false);
            navBarHeight = sys.bottom;
            EveAdminUiChrome.updateActionBarFadeHeight(actionBarFadeView, sys.top);
            mainColumn.setPadding(0, sys.top + ActionBar.getCurrentActionBarHeight(), 0, sys.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        applyThemeColors();
        fragmentView = rootFrame;
        startTestSession();
        return rootFrame;
    }

    private void applyThemeColors() {
        Theme.ResourcesProvider rp = getResourceProvider();
        int white = Theme.getColor(Theme.key_windowBackgroundWhite, rp);
        int gray = Theme.getColor(Theme.key_windowBackgroundGray, rp);
        int divider = Theme.getColor(Theme.key_divider, rp);
        int black = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp);
        int grayText = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp);
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton, rp);

        statusHeaderStrip.setBackgroundColor(white);
        statusStripText.setTextColor(grayText);
        statusDivider.setBackgroundColor(divider);
        footerDivider.setBackgroundColor(divider);
        footerBar.setBackgroundColor(white);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(16));
        cardBg.setColor(white);
        cardBg.setStroke(Math.max(1, dp(0.6f)), Theme.multAlpha(divider, 0.7f));
        questionCard.setBackground(cardBg);
        questionCard.setElevation(dp(2));

        questionMetaView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp));
        questionTextView.setTextColor(black);
        for (TextView tv : optionTexts) {
            if (tv != null) tv.setTextColor(black);
        }

        if (questionProgressBar != null) {
            try {
                GradientDrawable track = new GradientDrawable();
                track.setCornerRadius(dp(2));
                track.setColor(Theme.multAlpha(divider, 0.55f));
                GradientDrawable fill = new GradientDrawable();
                fill.setCornerRadius(dp(2));
                fill.setColor(accent);
                ClipDrawable clip = new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL);
                LayerDrawable layers = new LayerDrawable(new android.graphics.drawable.Drawable[] { track, clip });
                layers.setId(0, android.R.id.background);
                layers.setId(1, android.R.id.progress);
                questionProgressBar.setProgressDrawable(layers);
            } catch (Throwable ignored) {
            }
        }

        GradientDrawable reportBg = new GradientDrawable();
        reportBg.setCornerRadius(dp(8));
        reportBg.setColor(gray);
        reportBtn.setBackground(reportBg);
        reportBtn.setColorFilter(new PorterDuffColorFilter(grayText, PorterDuff.Mode.MULTIPLY));

        if (bookmarkBtn != null) {
            bookmarkBtn.setBackground(reportBg);
        }

        if (errorCard != null) {
            GradientDrawable errBg = new GradientDrawable();
            errBg.setCornerRadius(dp(16));
            errBg.setColor(white);
            errBg.setStroke(Math.max(1, dp(0.6f)), Theme.multAlpha(divider, 0.8f));
            errorCard.setBackground(errBg);
            errorTitleView.setTextColor(black);
            errorBodyView.setTextColor(grayText);
            styleActionButton(errorRetryBtn, true, false);
            styleActionButton(errorBackBtn, false, false);
        }

        if (circularTimerView != null) {
            circularTimerView.updateColors();
        }
        if (inlinePaletteAdapter != null) {
            inlinePaletteAdapter.notifyDataSetChanged();
        }

        if (!allQuestions.isEmpty()) {
            renderCurrentQuestion();
        }
        updateTimerPill(computeSecondsLeft());
    }

    private int computeSecondsLeft() {
        if (timeLimitSeconds <= 0 || timerAnchorElapsedMs <= 0L) {
            return remainingSeconds;
        }
        long elapsedSec = (SystemClock.elapsedRealtime() - timerAnchorElapsedMs) / 1000L;
        return Math.max(0, remainingSeconds - (int) elapsedSec);
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
        Theme.ResourcesProvider rp = getResourceProvider();
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        if (activeWarning) {
            bg.setColor(0xFFE68A00);
            tv.setTextColor(Color.WHITE);
        } else if (primary) {
            bg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton, rp));
            tv.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, rp));
        } else {
            bg.setColor(Theme.getColor(Theme.key_windowBackgroundGray, rp));
            tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        }
        tv.setBackground(bg);
    }

    private void showErrorCard(String title, String message, boolean showRetry) {
        if (loadingBar != null) loadingBar.setVisibility(View.GONE);
        if (statusMessageView != null) statusMessageView.setVisibility(View.GONE);
        if (contentContainer != null) contentContainer.setVisibility(View.GONE);
        if (errorCard != null) {
            errorCard.setVisibility(View.VISIBLE);
            errorTitleView.setText(!TextUtils.isEmpty(title) ? title : "Unable to Load Test");
            errorBodyView.setText(!TextUtils.isEmpty(message) ? message : "Please try again or return to the test list.");
            errorRetryBtn.setVisibility(showRetry ? View.VISIBLE : View.GONE);
        }
    }

    private void startTestSession() {
        try {
            startTestSessionInternal();
        } catch (Throwable err) {
            FileLog.e("EveTestPlayerFragment startTestSession crash guarded", err);
            showErrorCard("Could Not Start Test", err.getMessage() != null ? err.getMessage() : "Unable to start test session.", true);
        }
    }

    private void startTestSessionInternal() {
        if (destroyed || getParentActivity() == null) {
            return;
        }
        if (TextUtils.isEmpty(attemptKey)) {
            showErrorCard("Test Unavailable", "Invalid mock test identifier. Please go back and try again.", false);
            return;
        }
        String draftAttemptId = loadSavedClientAttemptId();
        String sourceExamId = exam != null ? exam.id : (test != null ? test.examId : "");
        String testId = test != null ? test.id : "";
        if (errorCard != null) errorCard.setVisibility(View.GONE);
        if (contentContainer != null) contentContainer.setVisibility(View.GONE);
        loadingBar.setVisibility(View.VISIBLE);
        statusMessageView.setVisibility(View.VISIBLE);
        statusMessageView.setText("Preparing live test session...");

        EveExamRepository.getInstance().startOrResumeAttempt(attemptKey, sourceExamId, testId, draftAttemptId, (res, err) -> {
            if (destroyed || getParentActivity() == null) {
                return;
            }
            try {
                String guard = validateStartupSession(attemptKey, res, err);
                if ("ALREADY_SUBMITTED".equals(guard)) {
                    clearSavedDraft();
                    openExistingSubmittedAttempt(res.completedAttemptId);
                    return;
                }
                if (!guard.isEmpty()) {
                    boolean canRetry = res == null || !res.alreadySubmitted;
                    String title = (res != null && res.questions != null && res.questions.isEmpty())
                            ? "No Questions Available"
                            : "Could Not Start Test";
                    showErrorCard(title, guard, canRetry);
                    return;
                }

                List<EveExamModels.TestQuestion> cleanQuestions = new ArrayList<>();
                for (EveExamModels.TestQuestion q : res.questions) {
                    if (q != null) {
                        cleanQuestions.add(q);
                    }
                }
                if (cleanQuestions.isEmpty()) {
                    showErrorCard("No Questions Available", "No valid questions were found for this test.", false);
                    return;
                }

                clientAttemptId = res.clientAttemptId != null ? res.clientAttemptId : "";
                allQuestions = cleanQuestions;
                timeLimitSeconds = Math.max(0, res.timeLimitSeconds);
                remainingSeconds = res.remainingSeconds > 0 ? res.remainingSeconds : timeLimitSeconds;
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
                if (errorCard != null) errorCard.setVisibility(View.GONE);
                contentContainer.setVisibility(View.VISIBLE);
                questionShownElapsedMs = SystemClock.elapsedRealtime();
                renderCurrentQuestion();
                updateTimerPill(remainingSeconds);
                timerHandler.removeCallbacks(timerRunnable);
                timerHandler.postDelayed(timerRunnable, 1000L);
            } catch (Throwable callbackErr) {
                FileLog.e("EveTestPlayerFragment start callback crash guarded", callbackErr);
                showErrorCard("Could Not Start Test", "Malformed question data received. Please retry or go back.", true);
            }
        });
    }

    private void openExistingSubmittedAttempt(String attemptId) {
        if (destroyed || getParentActivity() == null) return;
        statusMessageView.setText("Loading submitted test result...");
        EveExamRepository.getInstance().loadAttemptById(attemptId, (attemptResult, err) -> {
            if (destroyed || getParentActivity() == null) return;
            if (attemptResult == null) {
                String msg = !TextUtils.isEmpty(err) ? err : "Attempt already submitted, unable to load saved result.";
                showErrorCard("Already Submitted", msg, true);
                return;
            }
            finishedAttempt = true;
            openResultSafely(attemptResult);
        });
    }

    private void openResultSafely(EveExamModels.AttemptResult attemptResult) {
        if (destroyed || getParentActivity() == null || attemptResult == null) {
            return;
        }
        try {
            presentFragment(new EveTestResultFragment(exam, test, attemptKey, attemptResult), true);
        } catch (Throwable t) {
            FileLog.e("EveTestPlayerFragment openResultSafely error", t);
            showErrorCard("Unable to Open Result", "Please reopen the test to view your saved result.", true);
        }
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
        Theme.ResourcesProvider rp = getResourceProvider();
        TextView chip = new TextView(context);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        boolean active = sectionKey.equals(selectedSection);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(active ? Theme.getColor(Theme.key_featuredStickers_addButton, rp) : Theme.getColor(Theme.key_windowBackgroundWhite, rp));
        chip.setBackground(bg);
        chip.setTextColor(active ? Theme.getColor(Theme.key_featuredStickers_buttonText, rp) : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
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
    }

    private EveExamModels.TestQuestion currentQuestion() {
        if (allQuestions == null || currentQuestionIdx < 0 || currentQuestionIdx >= allQuestions.size()) {
            return null;
        }
        return allQuestions.get(currentQuestionIdx);
    }

    private void recordCurrentQuestionTime() {
        if (questionShownElapsedMs <= 0L) return;
        EveExamModels.TestQuestion q = currentQuestion();
        if (q == null) return;
        int deltaSec = (int) Math.max(0L, (SystemClock.elapsedRealtime() - questionShownElapsedMs) / 1000L);
        if (deltaSec > 0) {
            Integer existingSec = perQuestionSeconds.get(q.id);
            int prev = existingSec != null ? existingSec : 0;
            perQuestionSeconds.put(q.id, prev + deltaSec);
        }
        questionShownElapsedMs = SystemClock.elapsedRealtime();
    }

    private void renderCurrentQuestion() {
        EveExamModels.TestQuestion q = currentQuestion();
        if (q == null || getParentActivity() == null) {
            return;
        }
        Theme.ResourcesProvider rp = getResourceProvider();

        int fPos = filteredPosOf(currentQuestionIdx);
        int totalFiltered = Math.max(1, filteredIndices.size());
        statusStripText.setText(String.format(
                Locale.US,
                "Q %d / %d • Answered %d • Marked %d",
                Math.max(1, fPos + 1),
                totalFiltered,
                selectedAnswers.size(),
                markedForReview.size()
        ));
        questionProgressBar.setProgress(Math.round(((fPos + 1) * 1000f) / totalFiltered));

        String meta = "MCQ • Q" + q.number + " / " + allQuestions.size();
        if (!TextUtils.isEmpty(q.topic)) {
            meta += "  •  " + q.topic;
        }
        questionMetaView.setText(meta);
        EveAnimatedEmojiHelper.bindText(questionTextView, q.getQuestionText(preferHindi));

        String picked = selectedAnswers.get(q.id);
        String[] letters = new String[] { "A", "B", "C", "D" };
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton, rp);
        int divider = Theme.getColor(Theme.key_divider, rp);
        int surface = Theme.getColor(Theme.key_windowBackgroundWhite, rp);
        int grayBg = Theme.getColor(Theme.key_windowBackgroundGray, rp);

        for (int i = 0; i < 4; i++) {
            String letter = letters[i];
            boolean isPicked = letter.equalsIgnoreCase(picked);
            EveAnimatedEmojiHelper.bindText(optionTexts[i], q.getOptionText(letter, preferHindi));

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setCornerRadius(dp(12));
            cardBg.setStroke(dp(isPicked ? 1.8f : 1f), isPicked ? accent : Theme.multAlpha(divider, 0.7f));
            cardBg.setColor(isPicked ? Theme.multAlpha(accent, 0.11f) : Theme.multAlpha(grayBg, 0.45f));
            optionCards[i].setBackground(cardBg);

            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(dp(15));
            badgeBg.setColor(isPicked ? accent : surface);
            if (!isPicked) {
                badgeBg.setStroke(Math.max(1, dp(1f)), Theme.multAlpha(divider, 0.9f));
            }
            optionBadges[i].setBackground(badgeBg);
            optionBadges[i].setText(isPicked ? "\u2713" : letter);
            optionBadges[i].setTextColor(isPicked ? Theme.getColor(Theme.key_featuredStickers_buttonText, rp) : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        }

        boolean marked = markedForReview.contains(q.id);
        reviewBtn.setText(marked ? "Unmark" : "Mark");
        styleActionButton(reviewBtn, false, marked);
        styleActionButton(prevBtn, false, false);
        styleActionButton(clearBtn, false, false);
        nextBtn.setText(LocaleController.getString(isLastFilteredQuestion() ? R.string.EveSubmitTest : R.string.EveSaveAndNext));
        styleActionButton(nextBtn, true, false);

        if (bookmarkBtn != null) {
            boolean bookmarked = bookmarkedIds.contains(q.id);
            int tint = bookmarked ? accent : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp);
            bookmarkBtn.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY));
        }

        if (inlinePaletteAdapter != null) {
            inlinePaletteAdapter.notifyDataSetChanged();
            if (fPos >= 0 && fPos < inlinePaletteAdapter.getItemCount() && inlinePaletteView != null) {
                try {
                    inlinePaletteView.scrollToPosition(fPos);
                } catch (Throwable ignored) {
                }
            }
        }

        prevBtn.setEnabled(fPos > 0);
        prevBtn.setAlpha(prevBtn.isEnabled() ? 1f : 0.45f);

        questionScrollView.smoothScrollTo(0, 0);
    }

    private void selectOption(String letter) {
        EveExamModels.TestQuestion q = currentQuestion();
        if (q == null) return;
        selectedAnswers.put(q.id, letter);
        saveDraftAnswers();
        renderCurrentQuestion();
    }

    private void toggleReviewForCurrent() {
        EveExamModels.TestQuestion q = currentQuestion();
        if (q == null) return;
        if (markedForReview.contains(q.id)) {
            markedForReview.remove(q.id);
        } else {
            markedForReview.add(q.id);
        }
        saveDraftAnswers();
        renderCurrentQuestion();
    }

    private void toggleBookmarkForCurrent() {
        EveExamModels.TestQuestion q = currentQuestion();
        if (q == null) return;
        boolean next;
        if (bookmarkedIds.contains(q.id)) {
            bookmarkedIds.remove(q.id);
            next = false;
        } else {
            bookmarkedIds.add(q.id);
            next = true;
        }
        saveDraftAnswers();
        renderCurrentQuestion();
        if (getParentActivity() != null) {
            BulletinFactory.of(this).createSimpleBulletin(
                    R.raw.info, next ? "Question bookmarked" : "Bookmark removed").show();
        }
    }

    private void clearCurrentAnswer() {
        EveExamModels.TestQuestion q = currentQuestion();
        if (q == null) return;
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

    private void updateTimerPill(int secondsLeft) {
        if (circularTimerView == null) return;
        if (timeLimitSeconds <= 0) {
            circularTimerView.setVisibility(View.GONE);
            return;
        }
        circularTimerView.setVisibility(View.VISIBLE);
        circularTimerView.setTime(secondsLeft, timeLimitSeconds);
    }

    private void showQuestionPaletteSheet() {
        Activity activity = getParentActivity();
        if (activity == null || allQuestions.isEmpty()) return;
        Theme.ResourcesProvider rp = getResourceProvider();

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(12), dp(16), dp(20));

        TextView title = new TextView(activity);
        title.setText("Question Palette");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        container.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        HorizontalScrollView legendScroll = new HorizontalScrollView(activity);
        legendScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout legendRow = new LinearLayout(activity);
        legendRow.setOrientation(LinearLayout.HORIZONTAL);
        legendRow.setGravity(Gravity.CENTER_VERTICAL);
        addLegendPill(activity, legendRow, "Answered", 0xFF2E9E5B);
        addLegendPill(activity, legendRow, "Marked", 0xFFF59E0B);
        addLegendPill(activity, legendRow, "Ans + Marked", 0xFF6C4CEB);
        addLegendPill(activity, legendRow, "Unvisited", Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        legendScroll.addView(legendRow);
        container.addView(legendScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        RecyclerView grid = new RecyclerView(activity);
        grid.setLayoutManager(new GridLayoutManager(activity, 5));
        BottomSheet[] sheetHolder = new BottomSheet[1];
        grid.setAdapter(new PaletteAdapter(false, () -> {
            if (sheetHolder[0] != null) {
                sheetHolder[0].dismiss();
            }
        }));
        container.addView(grid, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 300));

        BottomSheet sheet = new BottomSheet(activity, false, rp);
        sheet.setCustomView(container);
        sheetHolder[0] = sheet;
        showDialog(sheet);
    }

    private void addLegendPill(Context ctx, LinearLayout row, String label, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setTextColor(color);
        tv.setPadding(dp(8), dp(3), dp(8), dp(3));
        tv.setBackground(Theme.createRoundRectDrawable(dp(8), Theme.multAlpha(color, 0.14f)));
        row.addView(tv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0));
    }

    private void showReportCurrentQuestionDialog() {
        Activity activity = getParentActivity();
        EveExamModels.TestQuestion q = currentQuestion();
        if (activity == null || q == null) return;
        Theme.ResourcesProvider rp = getResourceProvider();

        final int[] pickedIdx = { 0 };
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(8), pad, 0);

        RadioGroup reasonGroup = EveTestResultFragment.buildReportReasonGroup(
                activity,
                EveTestResultFragment.REPORT_REASONS,
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

        String examName = exam != null && !exam.examName.isEmpty() ? exam.examName : (test != null ? test.getDisplayTitle() : "Test");
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, rp);
        builder.setTitle(LocaleController.getString(R.string.EveReportQuestion));
        builder.setView(scroll);
        builder.setPositiveButton("Submit Report", (d, w) -> {
            String reason = EveTestResultFragment.REPORT_REASONS[pickedIdx[0]];
            String comment = commentInput.getText() != null ? commentInput.getText().toString().trim() : "";
            EveExamRepository.getInstance().submitQuestionReport(
                    q.id,
                    attemptKey,
                    examName,
                    q.getQuestionText(preferHindi),
                    reason,
                    comment,
                    (msg, err) -> {
                        if (destroyed || getParentActivity() == null) return;
                        if (!TextUtils.isEmpty(err)) {
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

    private void promptSubmitConfirmation() {
        Activity activity = getParentActivity();
        if (activity == null || submitting || allQuestions.isEmpty()) return;
        recordCurrentQuestionTime();
        int answered = selectedAnswers.size();
        int marked = markedForReview.size();
        int unanswered = Math.max(0, allQuestions.size() - answered);
        String body = LocaleController.formatString(R.string.EveSubmitConfirmBody, answered, marked, unanswered);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.EveSubmitConfirmTitle));
        builder.setMessage(body);
        builder.setPositiveButton(LocaleController.getString(R.string.EveSubmitTest), (d, w) -> performSubmit(false));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void performSubmit(boolean autoSubmitted) {
        if (submitting || finishedAttempt) return;
        submitting = true;
        recordCurrentQuestionTime();
        timerHandler.removeCallbacks(timerRunnable);

        if (simpleActionBar != null) {
            simpleActionBar.setSubtitle(autoSubmitted ? "Time up — submitting..." : "Submitting answers...");
        }

        JSONArray answersArr = new JSONArray();
        for (int i = 0; i < allQuestions.size(); i++) {
            EveExamModels.TestQuestion q = allQuestions.get(i);
            try {
                JSONObject obj = new JSONObject();
                obj.put("questionId", q.id);
                obj.put("number", q.number);
                obj.put("selected", selectedAnswers.containsKey(q.id) ? selectedAnswers.get(q.id) : "");
                obj.put("isBookmarked", bookmarkedIds.contains(q.id));
                obj.put("markedForReview", markedForReview.contains(q.id));
                Integer recorded = perQuestionSeconds.get(q.id);
                obj.put("timeTakenSeconds", recorded != null ? recorded : 0);
                answersArr.put(obj);
            } catch (Exception ignored) {
            }
        }

        String examName = exam != null && !exam.examName.isEmpty() ? exam.examName : (test != null ? test.getDisplayTitle() : "Test");
        String category = exam != null ? exam.category : "General";

        EveExamRepository.getInstance().submitAttempt(attemptKey, examName, category, clientAttemptId, answersArr, (result, err) -> {
            submitting = false;
            if (destroyed || getParentActivity() == null) return;
            if (result == null || !TextUtils.isEmpty(err)) {
                BulletinFactory.of(this).createErrorBulletin(!TextUtils.isEmpty(err) ? err : "Submission failed. Please retry.").show();
                if (simpleActionBar != null) {
                    simpleActionBar.setSubtitle(null);
                }
                timerHandler.postDelayed(timerRunnable, 1000L);
                return;
            }
            finishedAttempt = true;
            clearSavedDraft();
            openResultSafely(result);
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
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Pause or Submit Test?");
        builder.setMessage("Your answers are saved locally. You can pause and resume later, or submit now.");
        builder.setPositiveButton("Pause & Exit", (d, w) -> {
            recordCurrentQuestionTime();
            saveDraftAnswers();
            EveExamRepository.getInstance().pauseAttempt(attemptKey);
            finishedAttempt = true;
            finishFragment();
        });
        builder.setNeutralButton(LocaleController.getString(R.string.EveSubmitTest), (d, w) -> performSubmit(false));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
        return false;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        timerHandler.removeCallbacks(timerRunnable);
        if (!finishedAttempt && !allQuestions.isEmpty()) {
            recordCurrentQuestionTime();
            saveDraftAnswers();
            EveExamRepository.getInstance().pauseAttempt(attemptKey);
        }
        super.onFragmentDestroy();
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
            JSONArray bookmarkArr = new JSONArray();
            for (String qId : bookmarkedIds) {
                bookmarkArr.put(qId);
            }
            root.put("bookmarks", bookmarkArr);
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
            JSONArray bookmarkArr = root.optJSONArray("bookmarks");
            if (bookmarkArr != null) {
                for (int i = 0; i < bookmarkArr.length(); i++) {
                    bookmarkedIds.add(bookmarkArr.getString(i));
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
        private final boolean inlineStrip;
        private final Runnable onPick;

        PaletteAdapter(boolean inlineStrip, Runnable onPick) {
            this.inlineStrip = inlineStrip;
            this.onPick = onPick;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, inlineStrip ? 12.5f : 14);
            tv.setTypeface(AndroidUtilities.bold());
            int size = dp(inlineStrip ? 34 : 44);
            int margin = dp(inlineStrip ? 4 : 5);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(size, size);
            lp.setMargins(margin, margin, margin, margin);
            tv.setLayoutParams(lp);
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            if (position < 0 || position >= filteredIndices.size()) {
                return;
            }
            Theme.ResourcesProvider rp = getResourceProvider();
            int globalIdx = filteredIndices.get(position);
            if (globalIdx < 0 || globalIdx >= allQuestions.size()) {
                return;
            }
            EveExamModels.TestQuestion q = allQuestions.get(globalIdx);
            TextView tv = (TextView) holder.itemView;
            tv.setText(String.valueOf(q.number));

            boolean current = globalIdx == currentQuestionIdx;
            boolean answered = !TextUtils.isEmpty(selectedAnswers.get(q.id));
            boolean review = markedForReview.contains(q.id);
            PaletteState state = resolvePaletteState(answered, review);

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(inlineStrip ? 17 : 22));
            if (state == PaletteState.ANSWERED_AND_MARKED) {
                bg.setColor(0xFF6C4CEB);
                tv.setTextColor(Color.WHITE);
            } else if (state == PaletteState.MARKED) {
                bg.setColor(0xFFF59E0B);
                tv.setTextColor(Color.WHITE);
            } else if (state == PaletteState.ANSWERED) {
                bg.setColor(0xFF2E9E5B);
                tv.setTextColor(Color.WHITE);
            } else {
                bg.setColor(Theme.getColor(Theme.key_windowBackgroundGray, rp));
                tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
            }
            if (current) {
                bg.setStroke(dp(2.5f), Theme.getColor(Theme.key_featuredStickers_addButton, rp));
            }
            tv.setBackground(bg);

            tv.setOnClickListener(v -> {
                recordCurrentQuestionTime();
                currentQuestionIdx = globalIdx;
                saveDraftAnswers();
                renderCurrentQuestion();
                if (onPick != null) {
                    onPick.run();
                }
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

    private static final class CircularTimerView extends View {
        private final Theme.ResourcesProvider resourcesProvider;
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arcRect = new RectF();
        private int secondsLeft = 0;
        private int totalSeconds = 1;
        private String formattedTime = "00:00";

        CircularTimerView(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.resourcesProvider = rp;
            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeWidth(dp(3.5f));
            progressPaint.setStyle(Paint.Style.STROKE);
            progressPaint.setStrokeCap(Paint.Cap.ROUND);
            progressPaint.setStrokeWidth(dp(3.5f));
            textPaint.setTypeface(AndroidUtilities.bold());
            textPaint.setTextSize(dp(10.5f));
            textPaint.setTextAlign(Paint.Align.CENTER);
            updateColors();
        }

        void updateColors() {
            boolean low = isLowTimeWarning(secondsLeft, totalSeconds);
            int color = low
                    ? Theme.getColor(Theme.key_text_RedRegular, resourcesProvider)
                    : Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
            progressPaint.setColor(color);
            trackPaint.setColor(Theme.multAlpha(color, 0.18f));
            textPaint.setColor(low ? color : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            invalidate();
        }

        void setTime(int leftSec, int totalSec) {
            this.secondsLeft = Math.max(0, leftSec);
            this.totalSeconds = Math.max(1, totalSec);
            int hrs = secondsLeft / 3600;
            int mins = (secondsLeft % 3600) / 60;
            int secs = secondsLeft % 60;
            this.formattedTime = hrs > 0
                    ? String.format(Locale.US, "%d:%02d", hrs, mins)
                    : String.format(Locale.US, "%02d:%02d", mins, secs);
            updateColors();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float pad = dp(4);
            if (w <= pad * 2f || h <= pad * 2f) {
                return;
            }
            float size = Math.min(w, h);
            arcRect.set((w - size) * 0.5f + pad, (h - size) * 0.5f + pad, (w + size) * 0.5f - pad, (h + size) * 0.5f - pad);
            canvas.drawArc(arcRect, 0f, 360f, false, trackPaint);
            float sweep = Math.max(0f, Math.min(360f, (secondsLeft * 360f) / totalSeconds));
            canvas.drawArc(arcRect, -90f, sweep, false, progressPaint);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float cy = h * 0.5f - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(formattedTime, w * 0.5f, cy, textPaint);
        }
    }
}
