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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.EditTextEmoji;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import java.util.Arrays;
import java.util.Comparator;
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
        field.setAllowEmojisForNonPremium(true);
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

    /**
     * Hosts a modal Telegram {@link BottomSheet} backed by a {@link SizeNotifierFrameLayout} so
     * {@link EditTextEmoji} popup keyboards (emoji & custom animated emoji) anchor cleanly inside dialogs.
     */
    public static final class SheetHost {
        public final BottomSheet sheet;
        public final SizeNotifierFrameLayout root;
        public final ScrollView scrollView;
        public final LinearLayout formColumn;

        public SheetHost(@NonNull Context context, @NonNull String title, @Nullable Theme.ResourcesProvider rp) {
            sheet = new BottomSheet(context, true, rp);
            sheet.setApplyTopPadding(false);
            sheet.setApplyBottomPadding(false);

            root = new SizeNotifierFrameLayout(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int w = MeasureSpec.getSize(widthMeasureSpec);
                    int maxH = Math.max(AndroidUtilities.dp(420), (int) (AndroidUtilities.displaySize.y * 0.82f));
                    super.onMeasure(
                            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
                }
            };
            root.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, rp));

            LinearLayout wrapper = new LinearLayout(context);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(12));

            TextView header = new TextView(context);
            header.setText(title);
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
            wrapper.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

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
            if (!UserConfig.getInstance(account).isClientActivated()) {
                return false;
            }
            int state = ConnectionsManager.getInstance(account).getConnectionState();
            return state == ConnectionsManager.ConnectionStateConnected
                    || state == ConnectionsManager.ConnectionStateUpdating
                    || state == ConnectionsManager.ConnectionStateConnectingToProxy
                    || state == ConnectionsManager.ConnectionStateWaitingForNetwork;
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
