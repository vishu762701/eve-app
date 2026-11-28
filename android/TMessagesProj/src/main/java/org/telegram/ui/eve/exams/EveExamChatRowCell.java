/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui.eve.exams;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
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
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.eve.admin.EveAdminUiChrome;
import org.telegram.ui.eve.admin.EveAnimatedEmojiHelper;

import java.util.HashSet;
import java.util.Objects;

/**
 * Edge-to-edge Telegram Chats / DialogCell-styled row shared by {@link EveExamTestsFragment}
 * and {@link EveSubExamsFragment}: 54dp circular avatar, bold 16sp title with AnimatedEmojiSpan,
 * vertically centered 1-line layout when unattempted (or clean muted subtitle when attempted/meta),
 * right-aligned clean pill/badge, and DialogCell-aligned 78dp inset hairline divider.
 */
public final class EveExamChatRowCell extends FrameLayout implements Theme.Colorable {

    public static final int BADGE_NEUTRAL = EveAdminUiChrome.AvatarRowCell.BADGE_NEUTRAL;
    public static final int BADGE_RED = EveAdminUiChrome.AvatarRowCell.BADGE_RED;
    public static final int BADGE_GREEN = EveAdminUiChrome.AvatarRowCell.BADGE_GREEN;
    public static final int BADGE_PURPLE = EveAdminUiChrome.AvatarRowCell.BADGE_PURPLE;
    public static final int BADGE_BLUE = EveAdminUiChrome.AvatarRowCell.BADGE_BLUE;
    public static final int BADGE_SOLID_COUNTER = 10;

    private static final int AVATAR_SIZE_DP = 54;
    private static final int DIVIDER_INSET_DP = 78; // 12dp start margin + 54dp avatar + 12dp gap

    private final Theme.ResourcesProvider resourcesProvider;
    private final AvatarDrawable avatarDrawable;
    private final BackupImageView avatarView;
    private final AnimatedEmojiSpan.TextViewEmojis titleView;
    private final TextView subtitleView;
    private final TextView badgeView;

    private int badgeStyle = BADGE_BLUE;
    private boolean needDivider = false;

    public EveExamChatRowCell(@NonNull Context context, @Nullable Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);
        setBackground(Theme.getSelectorDrawable(false));

        avatarDrawable = new AvatarDrawable();
        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(AVATAR_SIZE_DP / 2f));

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);

        titleView = EveAnimatedEmojiHelper.createEmojiTextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        badgeView = new TextView(context);
        badgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        badgeView.setTypeface(AndroidUtilities.bold());
        badgeView.setSingleLine(true);
        badgeView.setGravity(Gravity.CENTER);
        badgeView.setMinWidth(dp(24));
        badgeView.setPadding(dp(9), dp(3.5f), dp(9), dp(3.5f));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (LocaleController.isRTL) {
            row.addView(badgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 14, 0, 8, 0));
            row.addView(textColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));
            row.addView(avatarView, LayoutHelper.createLinear(AVATAR_SIZE_DP, AVATAR_SIZE_DP, Gravity.CENTER_VERTICAL, 12, 0, 12, 0));
        } else {
            row.addView(avatarView, LayoutHelper.createLinear(AVATAR_SIZE_DP, AVATAR_SIZE_DP, Gravity.CENTER_VERTICAL, 12, 0, 12, 0));
            row.addView(textColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
            row.addView(badgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        }

        addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL));
        updateColors();
    }

    public void bind(@NonNull Payload p, boolean divider) {
        this.badgeStyle = p.badgeStyle;
        this.needDivider = divider;

        EveAdminUiChrome.bindExamAvatar(
                avatarView, avatarDrawable, p.seed, p.title,
                p.photoUrl, p.emoji, p.avatarColor, AVATAR_SIZE_DP, resourcesProvider);

        EveAnimatedEmojiHelper.bindText(titleView, p.title);
        if (TextUtils.isEmpty(p.subtitle)) {
            subtitleView.setVisibility(GONE);
            subtitleView.setText("");
        } else {
            subtitleView.setVisibility(VISIBLE);
            CharSequence subText = Emoji.replaceEmoji(p.subtitle, subtitleView.getPaint().getFontMetricsInt(), false);
            subtitleView.setText(subText != null ? subText : p.subtitle);
        }

        if (TextUtils.isEmpty(p.badge)) {
            badgeView.setVisibility(GONE);
            badgeView.setText("");
        } else {
            badgeView.setVisibility(VISIBLE);
            badgeView.setText(p.badge);
        }
        updateColors();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!needDivider) {
            return;
        }
        Paint paint = resourcesProvider != null ? resourcesProvider.getPaint(Theme.key_paint_divider) : Theme.dividerPaint;
        if (paint == null) {
            paint = Theme.dividerPaint;
        }
        if (paint == null) {
            return;
        }
        int y = getMeasuredHeight() - 1;
        if (LocaleController.isRTL) {
            canvas.drawLine(0, y, getMeasuredWidth() - dp(DIVIDER_INSET_DP), y, paint);
        } else {
            canvas.drawLine(dp(DIVIDER_INSET_DP), y, getMeasuredWidth(), y, paint);
        }
    }

    @Override
    public void updateColors() {
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));

        if (badgeStyle == BADGE_SOLID_COUNTER) {
            int counterBg = Theme.getColor(Theme.key_chats_unreadCounter, resourcesProvider);
            int counterFg = Theme.getColor(Theme.key_chats_unreadCounterText, resourcesProvider);
            badgeView.setTextColor(counterFg);
            badgeView.setBackground(Theme.createRoundRectDrawable(dp(12), counterBg));
            return;
        }

        int badgeColor;
        if (badgeStyle == BADGE_RED) {
            badgeColor = Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);
        } else if (badgeStyle == BADGE_GREEN) {
            badgeColor = 0xFF27B434;
        } else if (badgeStyle == BADGE_PURPLE) {
            badgeColor = 0xFF9F55DF;
        } else if (badgeStyle == BADGE_BLUE) {
            badgeColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider);
        } else {
            badgeColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        }
        badgeView.setTextColor(badgeColor);
        badgeView.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.multAlpha(badgeColor, 0.12f)));
    }

    @Override
    public void addColorKeys(HashSet<Integer> colors) {
        colors.add(Theme.key_windowBackgroundWhiteBlackText);
        colors.add(Theme.key_windowBackgroundWhiteGrayText);
        colors.add(Theme.key_text_RedRegular);
        colors.add(Theme.key_windowBackgroundWhiteBlueText);
        colors.add(Theme.key_chats_unreadCounter);
        colors.add(Theme.key_chats_unreadCounterText);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(72), MeasureSpec.EXACTLY));
    }

    public static final class Payload {
        public final String seed;
        public final String title;
        public final String subtitle;
        public final String photoUrl;
        public final String emoji;
        public final int avatarColor;
        public final String badge;
        public final int badgeStyle;

        public Payload(
                @Nullable String seed,
                @Nullable String title,
                @Nullable String subtitle,
                @Nullable String photoUrl,
                @Nullable String emoji,
                int avatarColor,
                @Nullable String badge,
                int badgeStyle) {
            this.seed = seed != null ? seed : "";
            this.title = title != null ? title : "";
            this.subtitle = subtitle != null ? subtitle : "";
            this.photoUrl = photoUrl != null ? photoUrl : "";
            this.emoji = emoji != null ? emoji : "";
            this.avatarColor = avatarColor;
            this.badge = badge != null ? badge : "";
            this.badgeStyle = badgeStyle;
        }
    }

    public static final class Factory extends UItem.UItemFactory<EveExamChatRowCell> {
        static {
            setup(new Factory());
        }

        @Override
        public EveExamChatRowCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new EveExamChatRowCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (item.object2 instanceof Payload) {
                ((EveExamChatRowCell) view).bind((Payload) item.object2, divider);
            }
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            if (a.object2 instanceof Payload && b.object2 instanceof Payload) {
                return TextUtils.equals(((Payload) a.object2).seed, ((Payload) b.object2).seed) && a.id == b.id;
            }
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            if (a.object2 instanceof Payload && b.object2 instanceof Payload) {
                Payload pa = (Payload) a.object2;
                Payload pb = (Payload) b.object2;
                return pa.badgeStyle == pb.badgeStyle
                        && pa.avatarColor == pb.avatarColor
                        && TextUtils.equals(pa.title, pb.title)
                        && TextUtils.equals(pa.subtitle, pb.subtitle)
                        && TextUtils.equals(pa.photoUrl, pb.photoUrl)
                        && TextUtils.equals(pa.emoji, pb.emoji)
                        && TextUtils.equals(pa.badge, pb.badge);
            }
            return Objects.equals(a.object2, b.object2);
        }

        public static UItem of(int id, @Nullable Object dataObject, @NonNull Payload payload) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.object = dataObject;
            item.object2 = payload;
            return item;
        }
    }
}
