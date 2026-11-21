/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Cells;
import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.LocaleController.getString;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.annotation.NonNull;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.ButtonBounce;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ForegroundColorSpanThemable;
import org.telegram.ui.Components.StaticLayoutEx;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.Stories.StoriesUtilities;
public class DialogCell extends BaseCell { // EVE-CUT: cut stories and bio interfaces
    public boolean drawArchive = true;
    public static final int SENT_STATE_NOTHING = -1;
    public static final int SENT_STATE_PROGRESS = 0;
    public static final int SENT_STATE_SENT = 1;
    public static final int SENT_STATE_READ = 2;
    public boolean drawMonoforumAvatar = false;
    private boolean drawCommunityAvatar;
    private boolean isShareToStoryCell;
    public int messagePaddingStart = 72;
    public int heightDefault = 70;
    public int heightThreeLines = 76;
    public int addHeightForTags = 3;
    public int addForumHeightForTags = 11;
    public TLRPC.TL_forumTopic forumTopic;
    private boolean twoLinesForName;
    private boolean nameIsEllipsized;
    private Paint buttonBackgroundPaint;
    public boolean drawAvatarSelector;
    public static class CustomDialog {
        public String name;
        public String message;
        public int id;
        public int unread_count;
        public boolean pinned;
        public boolean muted;
        public int type;
        public int date;
        public boolean verified;
        public boolean isMedia;
        public int sent = -1;
        public String dateString; // EVE-CUT: date string for placeholder rows
    } // EVE-CUT: end CustomDialog
    private int paintIndex;
    private int currentAccount;
    private CustomDialog customDialog;
    private long currentDialogId;
    private int currentEditDate;
    public boolean isDialogCell;
    private int lastMessageDate;
    private int unreadCount;
    private boolean lastUnreadState;
    private boolean dialogMuted;
    private float dialogMutedProgress;
    private int messageId;
    protected boolean forbidDraft;
    public ImageReceiver avatarImage = new ImageReceiver(this);
    private AvatarDrawable avatarDrawable = new AvatarDrawable();
    public boolean useForceThreeLines;
    public boolean useSeparator;
    public int nameLeft;
    public int nameWidth;
    public StaticLayout nameLayout;
    public float nameLayoutTranslateX;
    private int nameMuteLeft;
    private int timeLeft;
    private int timeTop;
    private StaticLayout timeLayout;
    private boolean drawCheck1;
    private boolean drawCheck2;
    private boolean drawClock;
    private int checkDrawLeft;
    private int checkDrawLeft1;
    private int clockDrawLeft;
    private int checkDrawTop;
    private int halfCheckDrawLeft;
    private int messageTop;
    private int messageLeft;
    private int typingLeft;
    private StaticLayout messageLayout;
    private StaticLayout typingLayout;
    private int messageNameTop;
    private int messageNameLeft;
    private int errorLeft;
    private boolean drawPin;
    private int pinTop;
    private int pinLeft;
    private boolean drawCount;
    private boolean drawCount2 = true;
    private int countTop;
    private int countLeft;
    private int countWidth;
    private StaticLayout countLayout;
    private boolean drawVerified;
    private RectF counterPathRect;
    public final StoriesUtilities.AvatarStoryParams storyParams = new StoriesUtilities.AvatarStoryParams(false); // EVE-CUT: minimal storyParams
    private DialogsActivity parentFragment; // EVE-CUT: parent fragment
    private final Theme.ResourcesProvider resourcesProvider; // EVE-CUT: resources provider
    private boolean attachedToWindow; // EVE-CUT: window attachment
    private boolean updateLayout; // EVE-CUT: update flag
    private int lastSize; // EVE-CUT: size tracking
    private RectF rect = new RectF(); // EVE-CUT: temp rect
    private int thumbSize; // EVE-CUT: thumb size
    private boolean drawNameLock; // EVE-CUT: name lock
    private int nameLockTop; // EVE-CUT: name lock top
    private int nameLockLeft; // EVE-CUT: name lock left
    private int nameTop; // EVE-CUT: name top
    private int messageWidth; // EVE-CUT: message width
    private int timeWidth; // EVE-CUT: time width
    private int thumbsCount; // EVE-CUT: thumbs count
    public DialogCell(DialogsActivity fragment, Context context, boolean needCheck, boolean forceThreeLines) {
        this(fragment, context, needCheck, forceThreeLines, UserConfig.selectedAccount, null);
    }

    public DialogCell(DialogsActivity fragment, Context context, boolean needCheck, boolean forceThreeLines, int account, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        storyParams.allowLongress = true;
        this.resourcesProvider = resourcesProvider; // EVE-CUT: resource provider
        parentFragment = fragment; // EVE-CUT: parent fragment
        drawMonoforumAvatar = false;
        drawCommunityAvatar = false;
        avatarImage.setRoundRadius(dp(26));
        useForceThreeLines = forceThreeLines;
        currentAccount = account;
        avatarImage.setAllowLoadingOnAttachedOnly(true);
    } // EVE-CUT: end constructor
    public void setDialog(CustomDialog dialog) {
        customDialog = dialog;
        messageId = 0;
        update(0);
    } // EVE-CUT: end setDialog
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        topClip = 0;
    } // EVE-CUT: end onMeasure
    private int computeHeight() {
        int height;
        if (isForumCell() && !isTransitionSupport && !collapsed) {
            height = dp(useForceThreeLines || SharedConfig.useThreeLinesLayout ? 86 : 91);
            if (useSeparator) {
                height += 1;
            }
            if (hasTags()) {
                height += dp(addForumHeightForTags);
            }
        } else {
            height = getCollapsedHeight();
        }
        return height;
    }

    private int getCollapsedHeight() {
        int height = dp(useForceThreeLines || SharedConfig.useThreeLinesLayout ? heightThreeLines : heightDefault);
        if (useSeparator || true) {
            height += 1;
        }
        if (twoLinesForName) {
            height += dp(20);
        }
        if (hasTags() && (!(useForceThreeLines || SharedConfig.useThreeLinesLayout) || isForumCell())) {
            height += dp(isForumCell() ? addForumHeightForTags : addHeightForTags);
        }
        return height;
    }

    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (currentDialogId == 0 && customDialog == null) {
            return;
        }
        int size = getMeasuredHeight() + getMeasuredWidth() << 16;
        if (size != lastSize || updateLayout) {
            updateLayout = false;
            lastSize = size;
            try {
                buildLayout();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }
    private static final float BADGE_SIZE = 20.666f;
    private static final float BADGE_TEXT_PADDING = 6.333f;
    private static final float BADGE_TEXT_MIN_WIDTH = 8f;
    private static final float BADGE_GAP = 25 - BADGE_TEXT_MIN_WIDTH;
    private static final float BADGE_MARGIN = 15.666f;
    public void buildLayout() {
        if (useForceThreeLines || SharedConfig.useThreeLinesLayout || true) {
            Theme.dialogs_namePaint[0].setTextSize(dp(17));
            Theme.dialogs_nameEncryptedPaint[0].setTextSize(dp(17));
            Theme.dialogs_messagePaint[0].setTextSize(dp(16));
            Theme.dialogs_messagePrintingPaint[0].setTextSize(dp(16));

            Theme.dialogs_namePaint[1].setTextSize(dp(16));
            Theme.dialogs_nameEncryptedPaint[1].setTextSize(dp(16));
            Theme.dialogs_messagePaint[1].setTextSize(dp(15));
            Theme.dialogs_messagePrintingPaint[1].setTextSize(dp(15));

            Theme.dialogs_messagePaint[1].setColor(Theme.dialogs_messagePaint[1].linkColor = Theme.getColor(Theme.key_chats_message_threeLines, resourcesProvider));
            paintIndex = 1;
            thumbSize = 18;
        } else {
            Theme.dialogs_namePaint[0].setTextSize(dp(17));
            Theme.dialogs_nameEncryptedPaint[0].setTextSize(dp(17));
            Theme.dialogs_messagePaint[0].setTextSize(dp(16));
            Theme.dialogs_messagePrintingPaint[0].setTextSize(dp(16));

            Theme.dialogs_messagePaint[0].setColor(Theme.dialogs_messagePaint[0].linkColor = Theme.getColor(Theme.key_chats_message, resourcesProvider));
            paintIndex = 0;
            thumbSize = 19;
        }
        currentDialogFolderDialogsCount = 0;
        CharSequence nameString = "";
        String timeString = "";
        String countString = null;
        String mentionString = null;
        CharSequence messageString = "";
        CharSequence typingString = "";
        CharSequence messageNameString = null;
        CharSequence printingString = null;
        CharSequence buttonString = null;
        currentMessagePaint = Theme.dialogs_messagePaint[paintIndex];
        drawNameLock = false; // EVE-CUT: default flags
        drawVerified = false; // EVE-CUT: default flags
        thumbsCount = 0; // EVE-CUT: default flags
        int offsetName = 0; // EVE-CUT: default flags
        boolean drawTime = true; // EVE-CUT: default flags
        int messagePaddingStart = 72; // EVE-CUT: default flags
        boolean twoLinesForName = false; // EVE-CUT: default flags
        boolean drawError = false; // EVE-CUT: default flags
        int messageFormatType = 1; // EVE-CUT: default flags
        int avatarStart = 10; // EVE-CUT: default flags
        int thumbsCountFinal = 0; // EVE-CUT: default flags
        Layout.Alignment align = LocaleController.isRTL ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL; // EVE-CUT: align
        if (customDialog != null) {
                drawVerified = !forbidVerified && customDialog.verified;
                if (useForceThreeLines || SharedConfig.useThreeLinesLayout) {
                    if (!LocaleController.isRTL) {
                        nameLeft = dp(messagePaddingStart + 6);
                    } else {
                        nameLeft = dp(22);
                    }
                } else {
                    if (!LocaleController.isRTL) {
                        nameLeft = dp(messagePaddingStart + 4);
                    } else {
                        nameLeft = dp(18);
                    }
                }
            messageString = customDialog.message != null ? customDialog.message : ""; // EVE-CUT: simplified messageString
            if (customDialog.isMedia) { currentMessagePaint = Theme.dialogs_messagePrintingPaint[paintIndex]; } // EVE-CUT: media paint
            if (customDialog.dateString != null) { timeString = customDialog.dateString; } else { timeString = ""; } // EVE-CUT: dateString
            if (customDialog.unread_count != 0) {
                drawCount = true;
                countString = String.format("%d", customDialog.unread_count);
            } else {
                drawCount = false;
            }

            if (customDialog.sent == SENT_STATE_PROGRESS) {
                drawClock = true;
                drawCheck1 = false;
                drawCheck2 = false;
            } else if (customDialog.sent == SENT_STATE_READ) {
                drawCheck1 = true;
                drawCheck2 = true;
                drawClock = false;
            } else if (customDialog.sent == SENT_STATE_SENT) {
                drawCheck1 = false;
                drawCheck2 = true;
                drawClock = false;
            } else {
                drawClock = false;
                drawCheck1 = false;
                drawCheck2 = false;
            }

            drawError = false;
            nameString = customDialog.name;
        } // EVE-CUT: end if customDialog
        int messageWidth;
        int avatarLeft;
        int avatarTop;
        int thumbLeft;
        if (useForceThreeLines || SharedConfig.useThreeLinesLayout) {
            avatarTop = dp(11);
            messageNameTop = dp(32);
            timeTop = dp(13);
            errorTop = dp(42.33f);
            pinTop = dp(43);
            countTop = dp(42.33f);
            checkDrawTop = dp(13);
            messageWidth = getMeasuredWidth() - dp(messagePaddingStart + 21);

            if (LocaleController.isRTL) {
                buttonLeft = typingLeft = messageLeft = messageNameLeft = dp(16);
                avatarLeft = getMeasuredWidth() - dp(56 + avatarStart);
                thumbLeft = avatarLeft - dp(13 + 18);
            } else {
                buttonLeft = typingLeft = messageLeft = messageNameLeft = dp(messagePaddingStart + 6);
                avatarLeft = dp(avatarStart);
                thumbLeft = avatarLeft + dp(56 + 13);
            }
            storyParams.originalAvatarRect.set(avatarLeft, avatarTop, avatarLeft + dp(56), avatarTop + dp(56));
        } else { // EVE-CUT: else
            avatarTop = dp(9);
            messageNameTop = dp(31);
            timeTop = dp(16);
            errorTop = dp(38);
            pinTop = dp(39);
            countTop = isTopic ? dp(35f) : dp(38f);
            checkDrawTop = dp(17);
            messageWidth = getMeasuredWidth() - dp(messagePaddingStart + 20 - (LocaleController.isRTL ? 0 : 12));

            if (LocaleController.isRTL) {
                buttonLeft = typingLeft = messageLeft = messageNameLeft = dp(22);
                avatarLeft = getMeasuredWidth() - dp(52 + avatarStart);
                thumbLeft = avatarLeft - dp(11 + (thumbsCount * (thumbSize + 2) - 2));
            } else {
                buttonLeft = typingLeft = messageLeft = messageNameLeft = dp(messagePaddingStart + 4);
                avatarLeft = dp(avatarStart);
                thumbLeft = avatarLeft + dp(56 + 11);
            }
            storyParams.originalAvatarRect.set(avatarLeft, avatarTop, avatarLeft + dp(52), avatarTop + dp(52));
        } // EVE-CUT: end if
        if (getIsPinned()) {
            if (!LocaleController.isRTL) {
                pinLeft = getMeasuredWidth() - Theme.dialogs_pinnedDrawable.getIntrinsicWidth() - dp(14);
            } else {
                pinLeft = dp(14);
            }
        }
            if (countString != null) {
                countWidth = Math.max(dp(BADGE_TEXT_MIN_WIDTH), (int) Math.ceil(Theme.dialogs_countTextPaint2.measureText(countString)));
                countLayout = new StaticLayout(countString, Theme.dialogs_countTextPaint2, countWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
                int w = countWidth + dp(BADGE_GAP);
                messageWidth -= w;
                if (!LocaleController.isRTL) {
                    countLeft = getMeasuredWidth() - dp(BADGE_MARGIN) - (countWidth + dp(BADGE_TEXT_PADDING * 2));
                } else {
                    countLeft = dp(BADGE_MARGIN);
                    messageLeft += w;
                    typingLeft += w;
                    buttonLeft += w;
                    messageNameLeft += w;
                }
                drawCount = true;
            } else {
                countWidth = 0;
            }
        TextPaint timePaint = Theme.dialogs_timePaint; // EVE-CUT: timeLayout
        timeWidth = (int) Math.ceil(timePaint.measureText(timeString)); // EVE-CUT: timeLayout
        timeLayout = new StaticLayout(timeString, timePaint, timeWidth, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false); // EVE-CUT: timeLayout
        if (!LocaleController.isRTL) { timeLeft = getMeasuredWidth() - timeWidth - dp(15); } else { timeLeft = dp(15); } // EVE-CUT: timeLayout
        nameWidth = getMeasuredWidth() - nameLeft - dp(14) - timeWidth - (drawPin ? dp(20) : 0); // EVE-CUT: nameLayout
        if (nameWidth < 0) nameWidth = 0; // EVE-CUT: nameLayout
        CharSequence nameFinal = TextUtils.ellipsize(nameString, Theme.dialogs_namePaint[paintIndex], nameWidth, TextUtils.TruncateAt.END); // EVE-CUT: nameLayout
        nameLayout = StaticLayoutEx.createStaticLayout(nameFinal, Theme.dialogs_namePaint[paintIndex], nameWidth, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false, TextUtils.TruncateAt.END, nameWidth, 1); // EVE-CUT: nameLayout
        messageWidth = getMeasuredWidth() - messageLeft - dp(16) - (drawCount ? countWidth + dp(BADGE_TEXT_PADDING * 2) + dp(6) : (drawPin ? dp(20) : 0)); // EVE-CUT: messageLayout
        if (messageWidth < 0) messageWidth = 0; // EVE-CUT: messageLayout
        CharSequence messageFinal = TextUtils.ellipsize(messageString, currentMessagePaint, messageWidth, TextUtils.TruncateAt.END); // EVE-CUT: messageLayout
        messageLayout = new StaticLayout(messageFinal, currentMessagePaint, messageWidth, align, 1.0f, 0.0f, false); // EVE-CUT: messageLayout
        nameTop = dp(useForceThreeLines || SharedConfig.useThreeLinesLayout ? 10 : 14); // EVE-CUT: nameTop
        if (!LocaleController.isRTL) { nameMuteLeft = nameLeft + (nameLayout != null && nameLayout.getLineCount() > 0 ? (int) Math.ceil(nameLayout.getLineWidth(0)) : 0) + dp(6); } else { nameMuteLeft = nameLeft - dp(6) - Theme.dialogs_muteDrawable.getIntrinsicWidth(); } // EVE-CUT: mute
    } // EVE-CUT: end buildLayout
    private void drawCheckStatus(Canvas canvas, boolean drawClock, boolean drawCheck1, boolean drawCheck2, boolean moveCheck,  float alpha) {
        if (alpha == 0 && !moveCheck) {
            return;
        }
        float scale = 0.5f + 0.5f * alpha;
        if (drawClock) {
            setDrawableBounds(Theme.dialogs_clockDrawable, clockDrawLeft, checkDrawTop);
            if (alpha != 1f) {
                canvas.save();
                canvas.scale(scale, scale, Theme.dialogs_clockDrawable.getBounds().centerX(), Theme.dialogs_halfCheckDrawable.getBounds().centerY());
                Theme.dialogs_clockDrawable.setAlpha((int) (255 * alpha));
            }
            Theme.dialogs_clockDrawable.draw(canvas);
            if (alpha != 1f) {
                canvas.restore();
                Theme.dialogs_clockDrawable.setAlpha(255);
            }
            invalidate();
        } else if (drawCheck2) {
            if (drawCheck1) {
                setDrawableBounds(Theme.dialogs_halfCheckDrawable, halfCheckDrawLeft, checkDrawTop);
                if (moveCheck) {
                    canvas.save();
                    canvas.scale(scale, scale, Theme.dialogs_halfCheckDrawable.getBounds().centerX(), Theme.dialogs_halfCheckDrawable.getBounds().centerY());
                    Theme.dialogs_halfCheckDrawable.setAlpha((int) (255 * alpha));
                }
                if (!moveCheck && alpha != 0) {
                    canvas.save();
                    canvas.scale(scale, scale, Theme.dialogs_halfCheckDrawable.getBounds().centerX(), Theme.dialogs_halfCheckDrawable.getBounds().centerY());
                    Theme.dialogs_halfCheckDrawable.setAlpha((int) (255 * alpha));
                    Theme.dialogs_checkReadDrawable.setAlpha((int) (255 * alpha));
                }

                Theme.dialogs_halfCheckDrawable.draw(canvas);

                if (moveCheck) {
                    canvas.restore();
                    canvas.save();
                    canvas.translate(dp(4) * (1f - alpha), 0);
                }
                setDrawableBounds(Theme.dialogs_checkReadDrawable, checkDrawLeft, checkDrawTop);
                Theme.dialogs_checkReadDrawable.draw(canvas);
                if (moveCheck) {
                    canvas.restore();
                    Theme.dialogs_halfCheckDrawable.setAlpha(255);
                }

                if (!moveCheck && alpha != 0) {
                    canvas.restore();
                    Theme.dialogs_halfCheckDrawable.setAlpha(255);
                    Theme.dialogs_checkReadDrawable.setAlpha(255);
                }
            } else {
                setDrawableBounds(Theme.dialogs_checkDrawable, checkDrawLeft1, checkDrawTop);
                if (alpha != 1f) {
                    canvas.save();
                    canvas.scale(scale, scale, Theme.dialogs_checkDrawable.getBounds().centerX(), Theme.dialogs_halfCheckDrawable.getBounds().centerY());
                    Theme.dialogs_checkDrawable.setAlpha((int) (255 * alpha));
                }
                Theme.dialogs_checkDrawable.draw(canvas);
                if (alpha != 1f) {
                    canvas.restore();
                    Theme.dialogs_checkDrawable.setAlpha(255);
                }
            }
        }
    }
    public boolean update(int mask) {
        return update(mask, true);
    }
    public boolean update(int mask, boolean animated) {
        boolean requestLayout = false;
        boolean rebuildLayout = false;
        boolean invalidate = false;
        boolean oldIsForumCell = isForumCell();
        if (customDialog != null) {
            lastMessageDate = customDialog.date;
            lastUnreadState = customDialog.unread_count != 0;
            unreadCount = customDialog.unread_count;
            drawPin = customDialog.pinned;
            dialogMuted = customDialog.muted;
            avatarDrawable.setInfo(customDialog.id, customDialog.name, null);
            avatarImage.setImage(null, "50_50", avatarDrawable, null, 0); // EVE-CUT: direct avatar
            drawMonoforumAvatar = false;
            drawCommunityAvatar = false;
            avatarImage.setRoundRadius(dp(26));
        } // EVE-CUT: cut TLRPC update branch
        if (getMeasuredWidth() != 0 || getMeasuredHeight() != 0) { rebuildLayout = true; } // EVE-CUT: rebuild
        invalidate(); // EVE-CUT: invalidate
        if (rebuildLayout) { buildLayout(); } // EVE-CUT: buildLayout
        return false; // EVE-CUT: return
    } // EVE-CUT: end update
    @Override
    protected void onDraw(Canvas canvas) {
        if (currentDialogId == 0 && customDialog == null) {
            return;
        }
        avatarImage.setImageCoords(storyParams.originalAvatarRect); // EVE-CUT: avatar
        avatarImage.draw(canvas); // EVE-CUT: avatar
        if (nameLayout != null) { canvas.save(); canvas.translate(nameLeft, nameTop); nameLayout.draw(canvas); canvas.restore(); } // EVE-CUT: name
        if (timeLayout != null) { canvas.save(); canvas.translate(timeLeft, timeTop); timeLayout.draw(canvas); canvas.restore(); } // EVE-CUT: time
        if (messageLayout != null) { canvas.save(); canvas.translate(messageLeft, messageTop); messageLayout.draw(canvas); canvas.restore(); } // EVE-CUT: message
        checkDrawTop = timeTop; // EVE-CUT: checks
        checkDrawLeft = timeLeft - Theme.dialogs_checkDrawable.getIntrinsicWidth() - dp(4); // EVE-CUT: checks
        checkDrawLeft1 = checkDrawLeft; // EVE-CUT: checks
        halfCheckDrawLeft = checkDrawLeft - dp(5); // EVE-CUT: checks
        drawCheckStatus(canvas, drawClock, drawCheck1, drawCheck2, false, 1f); // EVE-CUT: checks
        if (dialogMuted) { setDrawableBounds(Theme.dialogs_muteDrawable, nameMuteLeft, nameTop + dp(3)); Theme.dialogs_muteDrawable.draw(canvas); } // EVE-CUT: mute
        if (drawPin) { setDrawableBounds(Theme.dialogs_pinnedDrawable, pinLeft, pinTop); Theme.dialogs_pinnedDrawable.draw(canvas); } // EVE-CUT: pin
        if (drawCount && countLayout != null) { drawCounter(canvas, dialogMuted, countTop, countLeft, countLeft, 1f, false); } // EVE-CUT: count
        if (useSeparator) { if (LocaleController.isRTL) { canvas.drawLine(0, getMeasuredHeight() - 1, getMeasuredWidth() - dp(AndroidUtilities.leftBaseline), getMeasuredHeight() - 1, Theme.dividerPaint); } else { canvas.drawLine(dp(AndroidUtilities.leftBaseline), getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint); } } // EVE-CUT: divider
    } // EVE-CUT: end onDraw
    private void drawCounter(Canvas canvas, boolean drawCounterMuted, int countTop, int countLeftLocal, int countLeftOld, float globalScale, boolean outline) {
        if (drawCount && drawCount2) { // EVE-CUT: counter
            Paint paint = drawCounterMuted ? Theme.dialogs_countGrayPaint : Theme.dialogs_countPaint; // EVE-CUT: counter
            StaticLayout drawLayout = countLayout; // EVE-CUT: counter
            int x = countLeftLocal; // EVE-CUT: counter
            rect.set(x, countTop, x + countWidth + dp(BADGE_TEXT_PADDING * 2), countTop + dp(BADGE_SIZE)); // EVE-CUT: counter
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint); // EVE-CUT: counter
            canvas.save(); // EVE-CUT: counter
            canvas.translate(x + dp(BADGE_TEXT_PADDING), countTop + dp(3.5f)); // EVE-CUT: counter
            if (drawLayout != null) drawLayout.draw(canvas); // EVE-CUT: counter
            canvas.restore(); // EVE-CUT: counter
        } // EVE-CUT: counter
    } // EVE-CUT: counter
    public boolean isCounterMuted() { return dialogMuted; } // EVE-CUT: helper
    public boolean getIsPinned() { return drawPin; } // EVE-CUT: helper
    public boolean getIsMuted() { return dialogMuted; } // EVE-CUT: helper
    public boolean hasTags() { return false; } // EVE-CUT: helper
    public boolean isForumCell() { return false; } // EVE-CUT: helper
    private TextPaint getTimeTextPaint() { return Theme.dialogs_timePaint; } // EVE-CUT: helper
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attachedToWindow = true; avatarImage.onAttachedToWindow(); } // EVE-CUT: helper
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); attachedToWindow = false; avatarImage.onDetachedFromWindow(); } // EVE-CUT: helper
} // EVE-CUT: end class
