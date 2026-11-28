package org.telegram.ui.eve.admin;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.EditTextEmoji;
import org.telegram.ui.Components.EmojiView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encodes Telegram {@link AnimatedEmojiSpan} runs into portable {@code [tg_emoji:<docId>:<fallback>]}
 * markup for D1 persistence, and decodes them back into live {@link AnimatedEmojiSpan} instances
 * when Telegram MTProto is active (with clean {@link Emoji#replaceEmoji} offline fallback).
 */
public final class EveAnimatedEmojiHelper {

    public static final Pattern TAG_PATTERN = Pattern.compile("\\[tg_emoji:(-?\\d+):([^\\]]*)\\]");

    private EveAnimatedEmojiHelper() {
    }

    @NonNull
    public static String encode(@Nullable CharSequence input) {
        if (input == null || input.length() == 0) {
            return "";
        }
        if (!(input instanceof Spanned)) {
            return input.toString();
        }
        Spanned spanned = (Spanned) input;
        AnimatedEmojiSpan[] spans = spanned.getSpans(0, spanned.length(), AnimatedEmojiSpan.class);
        if (spans == null || spans.length == 0) {
            return input.toString();
        }
        Arrays.sort(spans, Comparator.comparingInt(spanned::getSpanStart));

        StringBuilder out = new StringBuilder(input.length() + spans.length * 24);
        int cursor = 0;
        for (AnimatedEmojiSpan span : spans) {
            if (span == null) {
                continue;
            }
            int start = spanned.getSpanStart(span);
            int end = spanned.getSpanEnd(span);
            if (start < cursor || end <= start || start < 0 || end > spanned.length()) {
                continue;
            }
            out.append(spanned, cursor, start);
            String rawSlice = spanned.subSequence(start, end).toString();
            String fallback = !TextUtils.isEmpty(span.emoji) ? span.emoji : rawSlice;
            fallback = sanitizeFallback(fallback);
            long docId = span.getDocumentId();
            if (docId != 0L) {
                out.append("[tg_emoji:").append(docId).append(':').append(fallback).append(']');
            } else {
                out.append(fallback);
            }
            cursor = end;
        }
        if (cursor < spanned.length()) {
            out.append(spanned, cursor, spanned.length());
        }
        return out.toString();
    }

    @NonNull
    public static SpannableStringBuilder decode(@Nullable String raw, @Nullable Paint.FontMetricsInt fontMetrics) {
        return decode(raw, fontMetrics, isMtprotoAvailable());
    }

    @NonNull
    public static SpannableStringBuilder decode(
            @Nullable String raw,
            @Nullable Paint.FontMetricsInt fontMetrics,
            boolean useAnimatedSpans) {
        String source = raw != null ? raw : "";
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (source.isEmpty()) {
            return out;
        }

        Matcher matcher = TAG_PATTERN.matcher(source);
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                appendStandardText(out, source.substring(lastEnd, matcher.start()), fontMetrics);
            }
            long docId = 0L;
            try {
                docId = Long.parseLong(matcher.group(1));
            } catch (Throwable ignored) {
            }
            String fallback = matcher.group(2);
            if (TextUtils.isEmpty(fallback)) {
                fallback = "\u2728";
            }
            if (useAnimatedSpans && docId != 0L) {
                int spanStart = out.length();
                out.append(fallback);
                int spanEnd = out.length();
                AnimatedEmojiSpan span = new AnimatedEmojiSpan(docId, fontMetrics);
                span.emoji = fallback;
                out.setSpan(span, spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                appendStandardText(out, fallback, fontMetrics);
            }
            lastEnd = matcher.end();
        }
        if (lastEnd < source.length()) {
            appendStandardText(out, source.substring(lastEnd), fontMetrics);
        }
        return out;
    }

    /**
     * Decodes {@code [tg_emoji:<docId>:<fallback>]} placeholders inside an already-styled {@link CharSequence}
     * (e.g. output of {@link AndroidUtilities#replaceTags(String)}) while preserving existing spans.
     */
    @NonNull
    public static SpannableStringBuilder decodePreservingSpans(
            @Nullable CharSequence raw,
            @Nullable Paint.FontMetricsInt fontMetrics) {
        if (raw == null || raw.length() == 0) {
            return new SpannableStringBuilder();
        }
        SpannableStringBuilder out = raw instanceof SpannableStringBuilder
                ? (SpannableStringBuilder) raw
                : new SpannableStringBuilder(raw);
        boolean useAnimatedSpans = isMtprotoAvailable();
        java.util.List<int[]> matches = new java.util.ArrayList<>();
        java.util.List<String[]> groups = new java.util.ArrayList<>();
        Matcher matcher = TAG_PATTERN.matcher(out.toString());
        while (matcher.find()) {
            matches.add(new int[] { matcher.start(), matcher.end() });
            groups.add(new String[] { matcher.group(1), matcher.group(2) });
        }
        for (int i = matches.size() - 1; i >= 0; i--) {
            int start = matches.get(i)[0];
            int end = matches.get(i)[1];
            String idStr = groups.get(i)[0];
            String fallback = groups.get(i)[1];
            if (TextUtils.isEmpty(fallback)) {
                fallback = "\u2728";
            }
            long docId = 0L;
            try {
                docId = Long.parseLong(idStr);
            } catch (Throwable ignored) {
            }
            SpannableStringBuilder replacement = new SpannableStringBuilder();
            if (useAnimatedSpans && docId != 0L) {
                replacement.append(fallback);
                AnimatedEmojiSpan span = new AnimatedEmojiSpan(docId, fontMetrics);
                span.emoji = fallback;
                replacement.setSpan(span, 0, replacement.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                appendStandardText(replacement, fallback, fontMetrics);
            }
            out.replace(start, end, replacement);
        }
        return out;
    }

    @NonNull
    public static AnimatedEmojiSpan.TextViewEmojis createEmojiTextView(@NonNull Context context) {
        AnimatedEmojiSpan.TextViewEmojis tv = new AnimatedEmojiSpan.TextViewEmojis(context);
        tv.setCacheType(AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES);
        return tv;
    }

    public static void bindText(@Nullable TextView textView, @Nullable String rawMarkup) {
        if (textView == null) {
            return;
        }
        Paint.FontMetricsInt fm = textView.getPaint() != null ? textView.getPaint().getFontMetricsInt() : null;
        try {
            textView.setText(decode(rawMarkup, fm));
        } catch (Throwable ignored) {
            textView.setText(stripMarkupToPlainText(rawMarkup));
        }
    }

    public static void setEditableText(@Nullable Editable editable, @Nullable String rawMarkup, @Nullable Paint.FontMetricsInt fontMetrics) {
        if (editable == null) {
            return;
        }
        SpannableStringBuilder decoded = decode(rawMarkup, fontMetrics);
        editable.replace(0, editable.length(), decoded);
    }

    /**
     * Creates an official Telegram {@link EditTextEmoji} pre-styled for Light & Dark EVE admin editors,
     * hydrated with decoded {@link AnimatedEmojiSpan} runs from backend markup.
     */
    @NonNull
    public static EditTextEmoji createEditTextEmoji(
            @NonNull Context context,
            @NonNull SizeNotifierFrameLayout emojiParent,
            @Nullable BaseFragment fragment,
            @NonNull String hint,
            @Nullable String initialMarkup,
            boolean multiLine,
            @Nullable Theme.ResourcesProvider rp) {
        EditTextEmoji field = new EditTextEmoji(
                context,
                emojiParent,
                fragment,
                EditTextEmoji.STYLE_FRAGMENT,
                true,
                rp
        );
        field.allowEmojisForNonPremium(true);
        if (emojiParent instanceof EmojiHostSizeNotifierFrameLayout) {
            ((EmojiHostSizeNotifierFrameLayout) emojiParent).registerEditTextEmoji(field);
        }
        if (field.getEditText() != null) {
            field.getEditText().setHint(hint);
            field.getEditText().setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            field.getEditText().setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
            field.getEditText().setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
            field.getEditText().setSingleLine(!multiLine);
            if (multiLine) {
                field.getEditText().setMaxLines(5);
                field.getEditText().setMinLines(3);
                field.getEditText().setGravity(Gravity.TOP | Gravity.START);
                field.getEditText().setInputType(
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            } else {
                field.getEditText().setMaxLines(2);
                field.getEditText().setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                field.getEditText().setInputType(
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            }
            if (!TextUtils.isEmpty(initialMarkup)) {
                Paint.FontMetricsInt fm = field.getEditText().getPaint() != null
                        ? field.getEditText().getPaint().getFontMetricsInt()
                        : null;
                field.setText(decode(initialMarkup, fm));
                field.setSelection(field.length());
            }
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(10));
        bg.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp), 0.08f));
        bg.setStroke(Math.max(1, AndroidUtilities.dp(0.75f)), Theme.multAlpha(Theme.getColor(Theme.key_divider, rp), 0.7f));
        field.setBackground(bg);
        field.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));
        return field;
    }

    public static final String ANIMATED_AVATAR_PREFIX = "tg_anim:";

    public static final class AnimatedAvatarSpec {
        public final long documentId;
        public final int[] backgroundColors;

        public AnimatedAvatarSpec(long documentId, int[] backgroundColors) {
            this.documentId = documentId;
            this.backgroundColors = backgroundColors != null ? backgroundColors : new int[0];
        }
    }

    @NonNull
    public static String encodeAnimatedAvatarDescriptor(long documentId, @Nullable int[] backgroundColors) {
        if (documentId == 0L) {
            return "";
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append(ANIMATED_AVATAR_PREFIX).append(documentId).append(':');
        if (backgroundColors != null && backgroundColors.length > 0) {
            int count = Math.min(4, backgroundColors.length);
            int appended = 0;
            for (int i = 0; i < count; i++) {
                int c = backgroundColors[i];
                if (i > 0 && c == 0) {
                    continue;
                }
                if (appended > 0) {
                    sb.append(',');
                }
                sb.append(String.format(Locale.US, "%08X", c | 0xFF000000));
                appended++;
            }
        }
        String res = sb.toString();
        return res.length() > 128 ? res.substring(0, 128) : res;
    }

    @Nullable
    public static AnimatedAvatarSpec parseAnimatedAvatarDescriptor(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith(ANIMATED_AVATAR_PREFIX)) {
            return null;
        }
        String body = trimmed.substring(ANIMATED_AVATAR_PREFIX.length());
        int colon = body.indexOf(':');
        String idPart = colon >= 0 ? body.substring(0, colon) : body;
        String colorsPart = colon >= 0 ? body.substring(colon + 1) : "";
        long docId;
        try {
            docId = Long.parseLong(idPart.trim());
        } catch (Throwable ignored) {
            return null;
        }
        if (docId == 0L) {
            return null;
        }
        List<Integer> colors = new ArrayList<>(4);
        if (!colorsPart.trim().isEmpty()) {
            String[] parts = colorsPart.split(",");
            for (String p : parts) {
                if (colors.size() >= 4) break;
                String hex = p != null ? p.trim().replace("#", "") : "";
                if (hex.isEmpty()) continue;
                try {
                    long parsed = Long.parseLong(hex, 16);
                    if (hex.length() <= 6) {
                        parsed |= 0xFF000000L;
                    }
                    colors.add((int) parsed);
                } catch (Throwable ignored) {
                }
            }
        }
        if (colors.isEmpty()) {
            colors.add(0xFF5A8FBB);
            colors.add(0xFF3E709C);
        }
        int[] out = new int[colors.size()];
        for (int i = 0; i < colors.size(); i++) {
            out[i] = colors.get(i);
        }
        return new AnimatedAvatarSpec(docId, out);
    }

    @Nullable
    public static TLRPC.TL_videoSizeEmojiMarkup toVideoSizeMarkup(@Nullable AnimatedAvatarSpec spec) {
        if (spec == null || spec.documentId == 0L) {
            return null;
        }
        TLRPC.TL_videoSizeEmojiMarkup markup = new TLRPC.TL_videoSizeEmojiMarkup();
        markup.emoji_id = spec.documentId;
        if (spec.backgroundColors.length > 0) {
            for (int c : spec.backgroundColors) {
                markup.background_colors.add(c | 0xFF000000);
            }
        } else {
            markup.background_colors.add(0xFF5A8FBB);
            markup.background_colors.add(0xFF3E709C);
        }
        return markup;
    }

    /**
     * SizeNotifierFrameLayout subclass mirroring Telegram's official ChannelCreateActivity layout:
     * keeps every {@link EmojiView} popup anchored strictly at the bottom keyboard slot (never inside
     * a ScrollView or mid-screen) and coordinates multiple {@link EditTextEmoji} fields so opening
     * one field's emoji keyboard closes any sibling field's open emoji panel.
     */
    public static class EmojiHostSizeNotifierFrameLayout extends SizeNotifierFrameLayout {
        private final List<EditTextEmoji> emojiFields = new ArrayList<>();
        @Nullable
        private EditTextEmoji activePopupField;
        @Nullable
        private View scrollContentTarget;
        private boolean ignoreLayout;

        public EmojiHostSizeNotifierFrameLayout(@NonNull Context context) {
            super(context);
        }

        public void registerEditTextEmoji(@Nullable EditTextEmoji field) {
            if (field == null) {
                return;
            }
            if (!emojiFields.contains(field)) {
                emojiFields.add(field);
            }
            if (field.getEditText() != null) {
                field.getEditText().setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus && activePopupField != null && activePopupField != field) {
                        hideOtherPopups(field);
                    }
                });
            }
        }

        public void setScrollContentTarget(@Nullable View target) {
            this.scrollContentTarget = target;
        }

        public boolean hasRegisteredFields() {
            return !emojiFields.isEmpty();
        }

        public boolean isAnyPopupShowing() {
            for (int i = 0; i < emojiFields.size(); i++) {
                EditTextEmoji f = emojiFields.get(i);
                if (f != null && f.isPopupShowing()) {
                    return true;
                }
            }
            return false;
        }

        public void hideAllPopups(boolean byBackButton) {
            for (int i = 0; i < emojiFields.size(); i++) {
                EditTextEmoji f = emojiFields.get(i);
                if (f != null && f.isPopupShowing()) {
                    f.hidePopup(byBackButton);
                }
            }
            activePopupField = null;
        }

        public int getActiveEmojiPadding() {
            int max = 0;
            for (int i = 0; i < emojiFields.size(); i++) {
                EditTextEmoji f = emojiFields.get(i);
                if (f != null && f.isPopupShowing()) {
                    max = Math.max(max, f.getEmojiPadding());
                }
            }
            return max;
        }

        protected boolean isActionBarChild(@NonNull View child) {
            return false;
        }

        protected boolean isPopupChild(@NonNull View child) {
            if (child instanceof EmojiView) {
                return true;
            }
            for (int i = 0; i < emojiFields.size(); i++) {
                EditTextEmoji f = emojiFields.get(i);
                if (f != null && f.isPopupView(child)) {
                    return true;
                }
            }
            return false;
        }

        private void hideOtherPopups(@Nullable EditTextEmoji keep) {
            for (int i = 0; i < emojiFields.size(); i++) {
                EditTextEmoji f = emojiFields.get(i);
                if (f != null && f != keep && f.isPopupShowing()) {
                    f.hidePopup(false);
                    f.hideEmojiView();
                }
            }
        }

        private void reconcileExclusivePopups() {
            EditTextEmoji newlyOpened = null;
            int showingCount = 0;
            for (int i = 0; i < emojiFields.size(); i++) {
                EditTextEmoji f = emojiFields.get(i);
                if (f != null && f.isPopupShowing()) {
                    showingCount++;
                    if (f != activePopupField) {
                        newlyOpened = f;
                    }
                }
            }
            if (showingCount > 1) {
                EditTextEmoji winner = newlyOpened != null ? newlyOpened : activePopupField;
                ignoreLayout = true;
                hideOtherPopups(winner);
                ignoreLayout = false;
                activePopupField = winner;
            } else if (showingCount == 1) {
                for (int i = 0; i < emojiFields.size(); i++) {
                    EditTextEmoji f = emojiFields.get(i);
                    if (f != null && f.isPopupShowing()) {
                        activePopupField = f;
                        break;
                    }
                }
            } else {
                activePopupField = null;
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (emojiFields.isEmpty()) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }
            int widthSize = MeasureSpec.getSize(widthMeasureSpec);
            int heightSize = MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension(widthSize, heightSize);
            int usableHeight = heightSize - getPaddingTop();

            int keyboardSize = measureKeyboardHeight();
            if (keyboardSize > AndroidUtilities.dp(20)) {
                ignoreLayout = true;
                for (int i = 0; i < emojiFields.size(); i++) {
                    EditTextEmoji f = emojiFields.get(i);
                    if (f != null) {
                        f.hideEmojiView();
                    }
                }
                activePopupField = null;
                ignoreLayout = false;
            } else {
                reconcileExclusivePopups();
            }

            int activePadding = (keyboardSize <= AndroidUtilities.dp(20)
                    && !AndroidUtilities.isInMultiwindow && !AndroidUtilities.isTablet())
                    ? getActiveEmojiPadding() : 0;

            int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                if (child == null || child.getVisibility() == GONE) {
                    continue;
                }
                if (isActionBarChild(child)) {
                    measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
                } else if (isPopupChild(child)) {
                    if (AndroidUtilities.isInMultiwindow || AndroidUtilities.isTablet()) {
                        int targetH = AndroidUtilities.isTablet()
                                ? Math.min(AndroidUtilities.dp(200), Math.max(AndroidUtilities.dp(120), usableHeight - AndroidUtilities.statusBarHeight + getPaddingTop()))
                                : Math.max(AndroidUtilities.dp(160), usableHeight - AndroidUtilities.statusBarHeight + getPaddingTop());
                        child.measure(
                                MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec(targetH, MeasureSpec.EXACTLY));
                    } else {
                        int lpH = child.getLayoutParams() != null && child.getLayoutParams().height > 0
                                ? child.getLayoutParams().height
                                : Math.max(AndroidUtilities.dp(220), activePadding);
                        child.measure(
                                MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec(lpH, MeasureSpec.EXACTLY));
                    }
                } else {
                    int usedH = (scrollContentTarget != null && child == scrollContentTarget && activePadding > 0)
                            ? activePadding : 0;
                    measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, usedH);
                }
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            if (emojiFields.isEmpty()) {
                super.onLayout(changed, l, t, r, b);
                return;
            }
            final int count = getChildCount();
            int keyboardSize = measureKeyboardHeight();
            int paddingBottom = keyboardSize <= AndroidUtilities.dp(20)
                    && !AndroidUtilities.isInMultiwindow && !AndroidUtilities.isTablet()
                    ? getActiveEmojiPadding() : 0;
            setBottomClip(scrollContentTarget != null ? 0 : paddingBottom);

            for (int i = 0; i < count; i++) {
                final View child = getChildAt(i);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                final int width = child.getMeasuredWidth();
                final int height = child.getMeasuredHeight();

                int childLeft;
                int childTop;

                int gravity = lp.gravity;
                if (gravity == -1) {
                    gravity = Gravity.TOP | Gravity.LEFT;
                }

                final int absoluteGravity = gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
                final int verticalGravity = gravity & Gravity.VERTICAL_GRAVITY_MASK;

                switch (absoluteGravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
                    case Gravity.CENTER_HORIZONTAL:
                        childLeft = (r - l - width) / 2 + lp.leftMargin - lp.rightMargin;
                        break;
                    case Gravity.RIGHT:
                        childLeft = r - l - width - lp.rightMargin;
                        break;
                    case Gravity.LEFT:
                    default:
                        childLeft = lp.leftMargin;
                }

                switch (verticalGravity) {
                    case Gravity.TOP:
                        childTop = lp.topMargin + getPaddingTop();
                        break;
                    case Gravity.CENTER_VERTICAL:
                        childTop = ((b - paddingBottom) - t - height) / 2 + lp.topMargin - lp.bottomMargin;
                        break;
                    case Gravity.BOTTOM:
                        childTop = ((b - paddingBottom) - t) - height - lp.bottomMargin;
                        break;
                    default:
                        childTop = lp.topMargin;
                }

                if (isPopupChild(child)) {
                    childLeft = 0;
                    if (AndroidUtilities.isTablet()) {
                        childTop = getMeasuredHeight() - child.getMeasuredHeight();
                    } else {
                        childTop = getMeasuredHeight() + Math.max(0, keyboardSize) - child.getMeasuredHeight();
                    }
                }
                child.layout(childLeft, childTop, childLeft + width, childTop + height);
            }
            notifyHeightChanged();
        }

        @Override
        public void requestLayout() {
            if (ignoreLayout) {
                return;
            }
            super.requestLayout();
        }
    }

    /**
     * Hosts a modal Telegram {@link BottomSheet} backed by a {@link EmojiHostSizeNotifierFrameLayout} so
     * {@link EditTextEmoji} popup keyboards (emoji & custom animated emoji) anchor cleanly at the bottom
     * keyboard slot of the sheet rather than floating mid-screen inside a ScrollView.
     */
    public static final class SheetHost {
        public final BottomSheet sheet;
        public final EmojiHostSizeNotifierFrameLayout root;
        public final ScrollView scrollView;
        public final LinearLayout formColumn;

        public SheetHost(@NonNull Context context, @NonNull String title, @Nullable Theme.ResourcesProvider rp) {
            sheet = new BottomSheet(context, true, rp) {
                @Override
                public void onBackPressed() {
                    if (root != null && root.isAnyPopupShowing()) {
                        root.hideAllPopups(true);
                        return;
                    }
                    super.onBackPressed();
                }
            };
            sheet.setApplyTopPadding(false);
            sheet.setApplyBottomPadding(false);

            LinearLayout wrapper = new LinearLayout(context);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(12));

            root = new EmojiHostSizeNotifierFrameLayout(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int w = MeasureSpec.getSize(widthMeasureSpec);
                    int maxH = Math.max(AndroidUtilities.dp(460), (int) (AndroidUtilities.displaySize.y * 0.86f));
                    int targetH;
                    if (isAnyPopupShowing()) {
                        targetH = maxH;
                    } else {
                        wrapper.measure(
                                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
                        targetH = Math.min(maxH, Math.max(wrapper.getMeasuredHeight(), AndroidUtilities.dp(240)));
                    }
                    super.onMeasure(
                            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(targetH, MeasureSpec.EXACTLY));
                }
            };
            root.setScrollContentTarget(wrapper);
            root.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, rp));

            TextView header = createEmojiTextView(context);
            bindText(header, title);
            header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            header.setTypeface(AndroidUtilities.bold());
            header.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
            header.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(2), AndroidUtilities.dp(20), AndroidUtilities.dp(10));
            wrapper.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            scrollView = new ScrollView(context);
            scrollView.setClipToPadding(false);
            formColumn = new LinearLayout(context);
            formColumn.setOrientation(LinearLayout.VERTICAL);
            formColumn.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(4), AndroidUtilities.dp(20), AndroidUtilities.dp(12));
            scrollView.addView(formColumn, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
            wrapper.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

            root.addView(wrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
            sheet.setCustomView(root);
        }
    }

    @NonNull
    public static String stripMarkupToPlainText(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        Matcher matcher = TAG_PATTERN.matcher(raw);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String fallback = matcher.group(2);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(!TextUtils.isEmpty(fallback) ? fallback : "\u2728"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public static boolean isMtprotoAvailable() {
        try {
            int account = UserConfig.selectedAccount;
            return UserConfig.getInstance(account).isClientActivated();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void appendStandardText(
            @NonNull SpannableStringBuilder target,
            @NonNull String chunk,
            @Nullable Paint.FontMetricsInt fontMetrics) {
        if (chunk.isEmpty()) {
            return;
        }
        try {
            CharSequence replaced = Emoji.replaceEmoji(chunk, fontMetrics, false);
            target.append(replaced != null ? replaced : chunk);
        } catch (Throwable ignored) {
            target.append(chunk);
        }
    }

    private static String sanitizeFallback(String s) {
        if (s == null) {
            return "";
        }
        String cleaned = s.replace("[", "").replace("]", "").replace(":", "");
        return cleaned.trim();
    }
}
