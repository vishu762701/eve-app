package org.telegram.ui.eve.polls;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.eve.admin.EveAnimatedEmojiHelper;
import org.telegram.ui.eve.admin.EveAdminUiChrome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Genuine Telegram chat-style poll bubble modeled after {@code ChatMessagePoll.java}.
 * Supports unvoted interactive student voting, animated percentage progress bars,
 * Light & Dark themes, and optional Admin controls (Close/Reopen + Delete).
 */
public class EveTelegramPollCardView extends FrameLayout implements Theme.Colorable {

    public interface StudentVoteListener {
        void onVoteOption(EvePollModel poll, int optionIndex);
    }

    public interface AdminActionListener {
        void onToggleActive(EvePollModel poll);
        void onDeletePoll(EvePollModel poll);
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final LinearLayout bubbleContainer;
    private final TextView questionView;
    private final TextView subtitleView;
    private final LinearLayout optionsContainer;
    private final TextView explanationView;
    private final View footerDivider;
    private final TextView footerView;
    private final View adminDivider;
    private final LinearLayout adminActionsRow;
    private final TextView toggleActiveBtn;
    private final TextView deleteBtn;

    private EvePollModel poll;
    private boolean adminPreviewMode;
    private int submittingOptionIndex = -1;
    private StudentVoteListener studentListener;
    private AdminActionListener adminListener;

    private final List<OptionRowHolder> optionHolders = new ArrayList<>();
    private ValueAnimator resultsAnimator;
    private float resultsAnimProgress = 1f;
    private String lastAnimatedPollKey = "";

    public EveTelegramPollCardView(Context context, @Nullable Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setPadding(dp(12), dp(6), dp(12), dp(6));

        bubbleContainer = new LinearLayout(context);
        bubbleContainer.setOrientation(LinearLayout.VERTICAL);
        bubbleContainer.setPadding(dp(16), dp(14), dp(16), dp(12));
        addView(bubbleContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        questionView = EveAnimatedEmojiHelper.createEmojiTextView(context);
        questionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        questionView.setTypeface(AndroidUtilities.bold());
        questionView.setLineSpacing(dp(2), 1f);
        bubbleContainer.addView(questionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        bubbleContainer.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 10));

        optionsContainer = new LinearLayout(context);
        optionsContainer.setOrientation(LinearLayout.VERTICAL);
        bubbleContainer.addView(optionsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        explanationView = EveAnimatedEmojiHelper.createEmojiTextView(context);
        explanationView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        explanationView.setLineSpacing(dp(2.5f), 1f);
        explanationView.setPadding(dp(12), dp(9), dp(12), dp(9));
        explanationView.setVisibility(GONE);
        bubbleContainer.addView(explanationView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 2));

        footerDivider = new View(context);
        bubbleContainer.addView(footerDivider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 8, 0, 8));

        footerView = new TextView(context);
        footerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        bubbleContainer.addView(footerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        adminDivider = new View(context);
        bubbleContainer.addView(adminDivider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 10, 0, 8));

        adminActionsRow = new LinearLayout(context);
        adminActionsRow.setOrientation(LinearLayout.HORIZONTAL);
        adminActionsRow.setGravity(Gravity.CENTER_VERTICAL);
        bubbleContainer.addView(adminActionsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        toggleActiveBtn = new TextView(context);
        toggleActiveBtn.setGravity(Gravity.CENTER);
        toggleActiveBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        toggleActiveBtn.setTypeface(AndroidUtilities.bold());
        toggleActiveBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        adminActionsRow.addView(toggleActiveBtn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));

        deleteBtn = new TextView(context);
        deleteBtn.setGravity(Gravity.CENTER);
        deleteBtn.setText("Delete");
        deleteBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        deleteBtn.setTypeface(AndroidUtilities.bold());
        deleteBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        adminActionsRow.addView(deleteBtn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        toggleActiveBtn.setOnClickListener(v -> {
            if (poll != null && adminListener != null) {
                adminListener.onToggleActive(poll);
            }
        });
        deleteBtn.setOnClickListener(v -> {
            if (poll != null && adminListener != null) {
                adminListener.onDeletePoll(poll);
            }
        });

        updateColors();
    }

    public void bind(
            @NonNull EvePollModel poll,
            boolean adminPreviewMode,
            int submittingOptionIndex,
            @Nullable StudentVoteListener studentListener,
            @Nullable AdminActionListener adminListener) {
        this.poll = poll;
        this.adminPreviewMode = adminPreviewMode;
        this.submittingOptionIndex = submittingOptionIndex;
        this.studentListener = studentListener;
        this.adminListener = adminListener;

        EveAnimatedEmojiHelper.bindText(questionView, poll.question);

        long now = System.currentTimeMillis();
        boolean isOpen = poll.isOpen(now);
        boolean showResults = adminPreviewMode || !isOpen || poll.myVoteIndex != null;
        String kindLabel = poll.isQuiz ? "Quiz" : "Poll";
        if (isOpen) {
            subtitleView.setText(adminPreviewMode ? kindLabel + " • Active (Live Results)" : kindLabel + " • Active");
        } else {
            subtitleView.setText(kindLabel + " • Final results");
        }

        String animKey = poll.id + ":" + showResults + ":" + poll.totalVotes + ":" + poll.myVoteIndex + ":" + poll.correctOptionIndex;
        boolean shouldAnimate = showResults && !TextUtils.equals(animKey, lastAnimatedPollKey);
        lastAnimatedPollKey = animKey;

        rebuildOptions(showResults);

        if (showResults && !TextUtils.isEmpty(poll.explanation)) {
            explanationView.setVisibility(VISIBLE);
            EveAnimatedEmojiHelper.bindText(explanationView, "\uD83D\uDCA1 Explanation\n" + poll.explanation);
        } else {
            explanationView.setVisibility(GONE);
        }

        String votesLabel;
        if (poll.totalVotes <= 0) {
            votesLabel = "No answers yet";
        } else if (poll.totalVotes == 1) {
            votesLabel = "1 answer";
        } else {
            votesLabel = poll.totalVotes + " answers";
        }
        String dateLabel = poll.createdAt > 0L ? (" • " + EveAdminUiChrome.formatDate(poll.createdAt)) : "";
        footerView.setText(votesLabel + dateLabel);

        boolean showAdmin = adminListener != null;
        adminDivider.setVisibility(showAdmin ? VISIBLE : GONE);
        adminActionsRow.setVisibility(showAdmin ? VISIBLE : GONE);
        if (showAdmin) {
            toggleActiveBtn.setText(poll.active ? "Close Quiz" : "Reopen Quiz");
        }

        updateColors();

        if (shouldAnimate) {
            startResultsAnimation();
        } else {
            resultsAnimProgress = showResults ? 1f : 0f;
            for (OptionRowHolder h : optionHolders) {
                h.progressView.setAnimatedProgress(resultsAnimProgress);
            }
        }
    }

    private void rebuildOptions(boolean showResults) {
        optionsContainer.removeAllViews();
        optionHolders.clear();
        Context ctx = getContext();
        int total = Math.max(0, poll.totalVotes);
        int winningVotes = poll.getWinningVotes();

        for (int i = 0; i < poll.options.size(); i++) {
            final int optionIdx = i;
            String optText = poll.options.get(i);
            int optVotes = poll.getOptionVotes(i);
            int percent = total > 0 ? Math.round((optVotes * 100f) / total) : 0;
            float ratio = total > 0 ? Math.min(1f, Math.max(0.03f, optVotes / (float) total)) : 0.03f;
            if (optVotes == 0) {
                ratio = 0.03f;
            }
            boolean isChosen = poll.myVoteIndex != null && poll.myVoteIndex == i;
            boolean hasCorrectKey = poll.isQuiz && poll.correctOptionIndex >= 0 && poll.correctOptionIndex < poll.options.size();
            boolean isCorrectOption = hasCorrectKey && poll.correctOptionIndex == i;
            boolean isWrongChosen = hasCorrectKey && isChosen && !isCorrectOption;
            boolean isAccentOption = isCorrectOption || (!hasCorrectKey && (isChosen || (adminPreviewMode && optVotes > 0 && optVotes == winningVotes)));
            boolean isSubmittingThis = !showResults && submittingOptionIndex == i;

            int quizState = OptionRowHolder.STATE_NEUTRAL;
            if (showResults && hasCorrectKey) {
                if (isCorrectOption) {
                    quizState = OptionRowHolder.STATE_CORRECT;
                } else if (isWrongChosen) {
                    quizState = OptionRowHolder.STATE_WRONG;
                }
            } else if (showResults && isChosen) {
                quizState = OptionRowHolder.STATE_CHOSEN;
            }

            OptionRowHolder holder = new OptionRowHolder(
                    ctx, showResults, percent, ratio, isChosen, isAccentOption, isSubmittingThis, quizState, optText, optVotes);
            holder.rowRoot.setOnClickListener(v -> {
                if (!showResults && submittingOptionIndex < 0 && studentListener != null) {
                    studentListener.onVoteOption(poll, optionIdx);
                }
            });
            holder.rowRoot.setClickable(!showResults && submittingOptionIndex < 0 && studentListener != null);
            optionsContainer.addView(holder.rowRoot, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            optionHolders.add(holder);
        }
    }

    private void startResultsAnimation() {
        if (resultsAnimator != null) {
            resultsAnimator.cancel();
        }
        resultsAnimProgress = 0f;
        resultsAnimator = ValueAnimator.ofFloat(0f, 1f);
        resultsAnimator.setDuration(360);
        resultsAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        resultsAnimator.addUpdateListener(anim -> {
            resultsAnimProgress = (float) anim.getAnimatedValue();
            for (OptionRowHolder h : optionHolders) {
                h.progressView.setAnimatedProgress(resultsAnimProgress);
            }
        });
        resultsAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (resultsAnimator != null) {
            resultsAnimator.cancel();
            resultsAnimator = null;
        }
    }

    @Override
    public void updateColors() {
        int bubbleColor = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        int dividerColor = Theme.getColor(Theme.key_divider, resourcesProvider);
        int blackText = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        int grayText = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        int redText = Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);

        GradientDrawable bubbleBg = new GradientDrawable();
        bubbleBg.setCornerRadius(dp(16));
        bubbleBg.setColor(bubbleColor);
        bubbleBg.setStroke(Math.max(1, dp(0.75f)), Theme.multAlpha(dividerColor, 0.85f));
        bubbleContainer.setBackground(bubbleBg);

        questionView.setTextColor(blackText);
        subtitleView.setTextColor(grayText);
        explanationView.setTextColor(blackText);
        explanationView.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(accentColor, 0.08f)));
        footerDivider.setBackgroundColor(Theme.multAlpha(dividerColor, 0.65f));
        footerView.setTextColor(grayText);
        adminDivider.setBackgroundColor(Theme.multAlpha(dividerColor, 0.65f));

        GradientDrawable toggleBg = new GradientDrawable();
        toggleBg.setCornerRadius(dp(10));
        toggleBg.setColor(Theme.multAlpha(accentColor, 0.12f));
        toggleActiveBtn.setBackground(toggleBg);
        toggleActiveBtn.setTextColor(accentColor);

        GradientDrawable deleteBg = new GradientDrawable();
        deleteBg.setCornerRadius(dp(10));
        deleteBg.setColor(Theme.multAlpha(redText, 0.12f));
        deleteBtn.setBackground(deleteBg);
        deleteBtn.setTextColor(redText);

        for (OptionRowHolder h : optionHolders) {
            h.updateColors();
        }
    }

    @Override
    public void addColorKeys(HashSet<Integer> colors) {
        colors.add(Theme.key_windowBackgroundWhite);
        colors.add(Theme.key_divider);
        colors.add(Theme.key_windowBackgroundWhiteBlackText);
        colors.add(Theme.key_windowBackgroundWhiteGrayText);
        colors.add(Theme.key_featuredStickers_addButton);
        colors.add(Theme.key_text_RedRegular);
    }

    private final class OptionRowHolder {
        static final int STATE_NEUTRAL = 0;
        static final int STATE_CHOSEN = 1;
        static final int STATE_CORRECT = 2;
        static final int STATE_WRONG = 3;

        final LinearLayout rowRoot;
        final PollCircleIndicator indicatorView;
        @Nullable final RadialProgressView spinnerView;
        final TextView percentView;
        final TextView labelView;
        final TextView countView;
        final PollBarView progressView;
        final boolean showResults;
        final boolean isChosen;
        final boolean isAccentOption;
        final int quizState;

        OptionRowHolder(
                Context ctx,
                boolean showResults,
                int percent,
                float ratio,
                boolean isChosen,
                boolean isAccentOption,
                boolean isSubmitting,
                int quizState,
                String label,
                int voteCount) {
            this.showResults = showResults;
            this.isChosen = isChosen;
            this.isAccentOption = isAccentOption;
            this.quizState = quizState;

            rowRoot = new LinearLayout(ctx);
            rowRoot.setOrientation(LinearLayout.VERTICAL);
            rowRoot.setPadding(dp(2), dp(8), dp(2), dp(8));
            if (!showResults) {
                rowRoot.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 2));
            }

            LinearLayout topRow = new LinearLayout(ctx);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            rowRoot.addView(topRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            indicatorView = new PollCircleIndicator(ctx, showResults, isChosen || quizState == STATE_CORRECT || quizState == STATE_WRONG, quizState == STATE_WRONG);
            percentView = new TextView(ctx);
            percentView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            percentView.setTypeface(AndroidUtilities.bold());
            percentView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);

            if (showResults) {
                percentView.setText(percent + "%");
                topRow.addView(percentView, LayoutHelper.createLinear(44, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
                if (isChosen || quizState == STATE_CORRECT || quizState == STATE_WRONG) {
                    topRow.addView(indicatorView, LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
                }
                spinnerView = null;
            } else if (isSubmitting) {
                indicatorView.setVisibility(GONE);
                spinnerView = new RadialProgressView(ctx, resourcesProvider);
                spinnerView.setSize(dp(18));
                topRow.addView(spinnerView, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
            } else {
                spinnerView = null;
                topRow.addView(indicatorView, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
            }

            labelView = EveAnimatedEmojiHelper.createEmojiTextView(ctx);
            EveAnimatedEmojiHelper.bindText(labelView, label);
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            labelView.setLineSpacing(dp(2), 1f);
            topRow.addView(labelView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

            countView = new TextView(ctx);
            countView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            if (showResults && voteCount > 0) {
                countView.setText(String.valueOf(voteCount));
                countView.setVisibility(VISIBLE);
            } else {
                countView.setVisibility(GONE);
            }
            topRow.addView(countView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

            progressView = new PollBarView(ctx, ratio, isAccentOption);
            if (showResults) {
                progressView.setVisibility(VISIBLE);
                rowRoot.addView(progressView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 5, 44, 6, 0, 2));
            } else {
                progressView.setVisibility(GONE);
            }

            updateColors();
        }

        void updateColors() {
            int blackText = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            int grayText = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
            int dividerColor = Theme.getColor(Theme.key_divider, resourcesProvider);
            int green = 0xFF2E9E5B;
            int red = 0xFFD93025;

            int activeColor = quizState == STATE_CORRECT
                    ? green
                    : (quizState == STATE_WRONG ? red : accent);

            labelView.setTextColor(quizState == STATE_CORRECT ? green : (quizState == STATE_WRONG ? red : blackText));
            percentView.setTextColor(quizState != STATE_NEUTRAL ? activeColor : blackText);
            countView.setTextColor(grayText);
            indicatorView.setColors(activeColor, grayText);
            int barColor = quizState == STATE_CORRECT
                    ? green
                    : (quizState == STATE_WRONG ? red : (isAccentOption ? accent : Theme.multAlpha(grayText, 0.55f)));
            progressView.setColors(activeColor, barColor, Theme.multAlpha(dividerColor, 0.55f));
        }
    }

    private static final class PollCircleIndicator extends View {
        private final boolean showResults;
        private final boolean isChosen;
        private final boolean drawCross;
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        PollCircleIndicator(Context context, boolean showResults, boolean isChosen, boolean drawCross) {
            super(context);
            this.showResults = showResults;
            this.isChosen = isChosen;
            this.drawCross = drawCross;
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(1.6f));
            fillPaint.setStyle(Paint.Style.FILL);
            checkPaint.setStyle(Paint.Style.STROKE);
            checkPaint.setStrokeWidth(dp(1.8f));
            checkPaint.setStrokeCap(Paint.Cap.ROUND);
            checkPaint.setStrokeJoin(Paint.Join.ROUND);
            checkPaint.setColor(Color.WHITE);
        }

        void setColors(int accentColor, int grayColor) {
            ringPaint.setColor(grayColor);
            fillPaint.setColor(accentColor);
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - dp(1.2f);
            if (!showResults) {
                canvas.drawCircle(cx, cy, r, ringPaint);
            } else if (isChosen) {
                canvas.drawCircle(cx, cy, r, fillPaint);
                if (drawCross) {
                    float d = r * 0.36f;
                    canvas.drawLine(cx - d, cy - d, cx + d, cy + d, checkPaint);
                    canvas.drawLine(cx + d, cy - d, cx - d, cy + d, checkPaint);
                } else {
                    canvas.drawLine(cx - r * 0.42f, cy + r * 0.02f, cx - r * 0.08f, cy + r * 0.36f, checkPaint);
                    canvas.drawLine(cx - r * 0.08f, cy + r * 0.36f, cx + r * 0.44f, cy - r * 0.28f, checkPaint);
                }
            }
        }
    }

    private static final class PollBarView extends View {
        private final float targetRatio;
        private float animProgress = 1f;
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        PollBarView(Context context, float targetRatio, boolean accent) {
            super(context);
            this.targetRatio = Math.max(0.03f, Math.min(1f, targetRatio));
        }

        void setAnimatedProgress(float progress) {
            this.animProgress = Math.max(0f, Math.min(1f, progress));
            invalidate();
        }

        void setColors(int accent, int fillColor, int trackColor) {
            fillPaint.setColor(fillColor);
            trackPaint.setColor(trackColor);
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) {
                return;
            }
            float r = h / 2f;
            rect.set(0f, 0f, w, h);
            canvas.drawRoundRect(rect, r, r, trackPaint);

            float fillW = Math.max(h, w * targetRatio * animProgress);
            rect.set(0f, 0f, Math.min(w, fillW), h);
            canvas.drawRoundRect(rect, r, r, fillPaint);
        }
    }

    public static final class Payload {
        public final EvePollModel poll;
        public final boolean adminPreviewMode;
        public final int submittingOptionIndex;
        @Nullable public final StudentVoteListener studentListener;
        @Nullable public final AdminActionListener adminListener;

        public Payload(
                EvePollModel poll,
                boolean adminPreviewMode,
                int submittingOptionIndex,
                @Nullable StudentVoteListener studentListener,
                @Nullable AdminActionListener adminListener) {
            this.poll = poll;
            this.adminPreviewMode = adminPreviewMode;
            this.submittingOptionIndex = submittingOptionIndex;
            this.studentListener = studentListener;
            this.adminListener = adminListener;
        }
    }

    public static final class Factory extends UItem.UItemFactory<EveTelegramPollCardView> {
        static {
            setup(new Factory());
        }

        @Override
        public EveTelegramPollCardView createView(
                Context context,
                RecyclerListView listView,
                int currentAccount,
                int classGuid,
                Theme.ResourcesProvider resourcesProvider) {
            return new EveTelegramPollCardView(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (item.object instanceof Payload) {
                Payload p = (Payload) item.object;
                ((EveTelegramPollCardView) view).bind(
                        p.poll,
                        p.adminPreviewMode,
                        p.submittingOptionIndex,
                        p.studentListener,
                        p.adminListener
                );
            }
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            if (a.object instanceof Payload && b.object instanceof Payload) {
                return TextUtils.equals(((Payload) a.object).poll.id, ((Payload) b.object).poll.id) && a.id == b.id;
            }
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            if (!(a.object instanceof Payload) || !(b.object instanceof Payload)) {
                return Objects.equals(a.object, b.object);
            }
            Payload pa = (Payload) a.object;
            Payload pb = (Payload) b.object;
            return pa.adminPreviewMode == pb.adminPreviewMode
                    && pa.submittingOptionIndex == pb.submittingOptionIndex
                    && pa.poll.active == pb.poll.active
                    && pa.poll.totalVotes == pb.poll.totalVotes
                    && pa.poll.correctOptionIndex == pb.poll.correctOptionIndex
                    && TextUtils.equals(pa.poll.explanation, pb.poll.explanation)
                    && Objects.equals(pa.poll.myVoteIndex, pb.poll.myVoteIndex)
                    && TextUtils.equals(pa.poll.question, pb.poll.question)
                    && Objects.equals(pa.poll.options, pb.poll.options)
                    && Objects.equals(pa.poll.voteCounts, pb.poll.voteCounts);
        }

        public static UItem of(int id, Payload payload) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.object = payload;
            return item;
        }
    }
}
