package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.eve.auth.EveBackendClient;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.DialogRadioCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.RadioButtonCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.utils.glass.BaseFragmentSimpleGlass;
import org.telegram.utils.glass.GlassEngine;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared Telegram Settings-style glass header + UniversalRecyclerView chrome
 * supporting two explicit layout modes:
 * <ul>
 *     <li>{@code useSectionCards = true}: Card / Hub / Settings mode ({@code windowBackgroundGray} + rounded cards)</li>
 *     <li>{@code useSectionCards = false}: Edge-to-Edge Directory / List mode ({@code windowBackgroundWhite} + hairline dividers)</li>
 * </ul>
 */
public final class EveAdminUiChrome {

    private EveAdminUiChrome() {
    }

    public static final class Chrome {
        public final SizeNotifierFrameLayout rootView;
        public final UniversalRecyclerView listView;
        public final View actionBarFadeView;
        private int navigationBarHeight;

        private Chrome(SizeNotifierFrameLayout rootView, UniversalRecyclerView listView, View actionBarFadeView) {
            this.rootView = rootView;
            this.listView = listView;
            this.actionBarFadeView = actionBarFadeView;
        }
    }

    public static Chrome setup(
            @NonNull BaseFragment fragment,
            @NonNull Context context,
            @Nullable SimpleActionBar simpleActionBar,
            @Nullable BaseFragmentSimpleGlass simpleGlass,
            @Nullable GlassEngine glassEngine,
            @Nullable Theme.ResourcesProvider resourcesProvider,
            @NonNull CharSequence title,
            @Nullable CharSequence subtitle,
            boolean useSectionCards,
            @NonNull Utilities.Callback2<ArrayList<UItem>, UniversalAdapter> fillItems,
            @Nullable Utilities.Callback5<UItem, View, Integer, Float, Float> onClick,
            @Nullable ActionBar.ActionBarMenuOnItemClick menuClick,
            @Nullable Runnable onUpdateColors) {
        return setup(
                fragment, context, simpleActionBar, simpleGlass, glassEngine, resourcesProvider,
                title, subtitle, useSectionCards, false,
                fillItems, onClick, menuClick, onUpdateColors);
    }

    public static Chrome setup(
            @NonNull BaseFragment fragment,
            @NonNull Context context,
            @Nullable SimpleActionBar simpleActionBar,
            @Nullable BaseFragmentSimpleGlass simpleGlass,
            @Nullable GlassEngine glassEngine,
            @Nullable Theme.ResourcesProvider resourcesProvider,
            @NonNull CharSequence title,
            @Nullable CharSequence subtitle,
            boolean useSectionCards,
            boolean useCardInsetDividers,
            @NonNull Utilities.Callback2<ArrayList<UItem>, UniversalAdapter> fillItems,
            @Nullable Utilities.Callback5<UItem, View, Integer, Float, Float> onClick,
            @Nullable ActionBar.ActionBarMenuOnItemClick menuClick,
            @Nullable Runnable onUpdateColors) {

        final int bgColorKey = useSectionCards ? Theme.key_windowBackgroundGray : Theme.key_windowBackgroundWhite;
        final int horizontalPadTop = useSectionCards ? dp(12) : 0;

        final Chrome[] holder = new Chrome[1];
        final UniversalRecyclerView listView = new UniversalRecyclerView(fragment, fillItems, onClick, null);
        if (useSectionCards) {
            listView.setCaptureSectionsDecoratorAllowed(true);
            if (listView.adapter != null) {
                listView.adapter.setApplyBackground(false);
            }
            listView.setSections();
            listView.setSectionsDrawBackground(true);
            if (useCardInsetDividers) {
                listView.addItemDecoration(new CardInsetDividerDecoration(resourcesProvider));
            }
        } else {
            listView.setCaptureSectionsDecoratorAllowed(false);
            if (listView.adapter != null) {
                listView.adapter.setApplyBackground(true);
            }
            listView.addItemDecoration(new EdgeListDividerDecoration(resourcesProvider));
        }
        BaseFragmentSimpleGlass.setListViewParams(listView, 0, 0, horizontalPadTop, 0);
        listView.setClipToPadding(false);

        final View actionBarFadeView = new View(context);
        if (simpleGlass != null) {
            actionBarFadeView.setBackground(simpleGlass.createActionBarFadeDrawable(actionBarFadeView));
        }

        final SizeNotifierFrameLayout contentView = new SizeNotifierFrameLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                if (glassEngine != null) {
                    glassEngine.invalidate();
                }
                if (holder[0] != null && holder[0].navigationBarHeight > 0) {
                    AndroidUtilities.drawNavigationBarProtection(
                            canvas, this,
                            Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider),
                            holder[0].navigationBarHeight);
                }
            }

            @Override
            public void updateColors() {
                try {
                    super.updateColors();
                    setBackgroundColor(Theme.getColor(bgColorKey, resourcesProvider));
                    if (simpleActionBar != null) {
                        simpleActionBar.updateColors();
                    }
                    listView.invalidate();
                    if (onUpdateColors != null) {
                        onUpdateColors.run();
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        };
        contentView.setBackgroundColor(Theme.getColor(bgColorKey, resourcesProvider));

        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        contentView.addView(actionBarFadeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.TOP));

        if (simpleActionBar != null) {
            simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
            simpleActionBar.setTitle(title);
            if (subtitle != null) {
                simpleActionBar.setSubtitle(subtitle);
            }
            simpleActionBar.setBackground(null);
            if (menuClick != null) {
                simpleActionBar.setActionBarMenuOnItemClick(menuClick);
            }
            AndroidUtilities.removeFromParent(simpleActionBar);
            contentView.addView(simpleActionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.TOP));
        }

        final Chrome chrome = new Chrome(contentView, listView, actionBarFadeView);
        holder[0] = chrome;

        updateActionBarFadeHeight(actionBarFadeView, AndroidUtilities.statusBarHeight);
        ViewCompat.setOnApplyWindowInsetsListener(contentView, (v, insets) -> {
            final Insets systemInsets = AndroidUtilities.getDefaultWindowInsets(insets, false);
            chrome.navigationBarHeight = systemInsets.bottom;
            updateActionBarFadeHeight(actionBarFadeView, systemInsets.top);
            BaseFragmentSimpleGlass.setListViewParams(listView, systemInsets.top, systemInsets.bottom, horizontalPadTop, 0);
            return WindowInsetsCompat.CONSUMED;
        });

        return chrome;
    }

    public static void addTopSpace(ArrayList<UItem> items) {
        items.add(UItem.asSpace(ActionBar.getCurrentActionBarHeight()));
    }

    public static void updateActionBarFadeHeight(@Nullable View actionBarFadeView, int insetTop) {
        if (actionBarFadeView == null) {
            return;
        }
        int height = insetTop + ActionBar.getCurrentActionBarHeight();
        ViewGroup.LayoutParams params = actionBarFadeView.getLayoutParams();
        if (params != null && params.height != height) {
            params.height = height;
            actionBarFadeView.setLayoutParams(params);
        }
    }

    private static final class EdgeListDividerDecoration extends RecyclerView.ItemDecoration {
        private final Theme.ResourcesProvider resourcesProvider;

        EdgeListDividerDecoration(@Nullable Theme.ResourcesProvider resourcesProvider) {
            this.resourcesProvider = resourcesProvider;
        }

        @Override
        public void onDrawOver(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            Paint paint = resourcesProvider != null ? resourcesProvider.getPaint(Theme.key_paint_divider) : Theme.dividerPaint;
            if (paint == null) {
                paint = Theme.dividerPaint;
            }
            if (paint == null) {
                return;
            }
            int childCount = parent.getChildCount();
            int startX = dp(LocaleController.isRTL ? 0 : 18);
            int endX = parent.getWidth() - dp(LocaleController.isRTL ? 18 : 0);
            for (int i = 0; i < childCount - 1; i++) {
                View child = parent.getChildAt(i);
                View next = parent.getChildAt(i + 1);
                if (isNonRowDecorator(child) || isNonRowDecorator(next)) {
                    continue;
                }
                int y = Math.round(child.getBottom() + child.getTranslationY());
                canvas.drawLine(startX, y, endX, y, paint);
            }
        }

        private static boolean isNonRowDecorator(View view) {
            return view instanceof HeaderCell
                    || view instanceof ShadowSectionCell
                    || view instanceof TextInfoPrivacyCell
                    || view instanceof FlickerLoadingView
                    || view instanceof UniversalAdapter.SpaceView
                    || Integer.valueOf(RecyclerListView.TAG_NOT_SECTION).equals(view.getTag())
                    || view.getHeight() <= dp(20);
        }
    }

    private static final class CardInsetDividerDecoration extends RecyclerView.ItemDecoration {
        private final Theme.ResourcesProvider resourcesProvider;

        CardInsetDividerDecoration(@Nullable Theme.ResourcesProvider resourcesProvider) {
            this.resourcesProvider = resourcesProvider;
        }

        @Override
        public void onDrawOver(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            Paint paint = resourcesProvider != null ? resourcesProvider.getPaint(Theme.key_paint_divider) : Theme.dividerPaint;
            if (paint == null) {
                paint = Theme.dividerPaint;
            }
            if (paint == null) {
                return;
            }
            int childCount = parent.getChildCount();
            int cardEdgePad = dp(12);
            for (int i = 0; i < childCount - 1; i++) {
                View child = parent.getChildAt(i);
                View next = parent.getChildAt(i + 1);
                if (EdgeListDividerDecoration.isNonRowDecorator(child)
                        || EdgeListDividerDecoration.isNonRowDecorator(next)
                        || drawsOwnNativeDivider(child)) {
                    continue;
                }
                int leftInset = resolveLeftInset(child);
                int startX = LocaleController.isRTL ? cardEdgePad : (cardEdgePad + leftInset);
                int endX = parent.getWidth() - (LocaleController.isRTL ? (cardEdgePad + leftInset) : cardEdgePad);
                if (endX <= startX) {
                    continue;
                }
                int y = Math.round(child.getBottom() + child.getTranslationY());
                canvas.drawLine(startX, y, endX, y, paint);
            }
        }

        private static boolean drawsOwnNativeDivider(View view) {
            return view instanceof TextCheckCell
                    || view instanceof NotificationsCheckCell
                    || view instanceof TextSettingsCell
                    || view instanceof TextCell
                    || view instanceof TextDetailCell
                    || view instanceof DialogRadioCell
                    || view instanceof CheckBoxCell
                    || view instanceof RadioButtonCell;
        }

        private static int resolveLeftInset(View view) {
            if (view instanceof SettingsActivity.SettingCell) {
                return dp(58);
            }
            if (view instanceof AvatarRowCell) {
                return dp(66);
            }
            return dp(20);
        }
    }

    /**
     * Lightweight foreground auto-sync controller for Admin & Leaderboard screens.
     * Triggers silent refreshes on resume (when stale) and at a gentle foreground interval,
     * cancelling cleanly on pause/destroy without ever blocking loaded UI.
     */
    public static final class AutoSyncController {
        public static final long DEFAULT_MIN_RESUME_INTERVAL_MS = 15_000L;
        public static final long DEFAULT_PERIODIC_INTERVAL_MS = 25_000L;

        private final long minResumeIntervalMs;
        private final long periodicIntervalMs;
        private final Runnable syncAction;
        private boolean resumed;
        private boolean destroyed;
        private boolean hasSyncedOnce;
        private long lastSyncAtMs;

        private final Runnable periodicTask = new Runnable() {
            @Override
            public void run() {
                if (destroyed || !resumed) {
                    return;
                }
                lastSyncAtMs = System.currentTimeMillis();
                syncAction.run();
                scheduleNext();
            }
        };

        public AutoSyncController(@NonNull Runnable syncAction) {
            this(syncAction, DEFAULT_MIN_RESUME_INTERVAL_MS, DEFAULT_PERIODIC_INTERVAL_MS);
        }

        public AutoSyncController(@NonNull Runnable syncAction, long minResumeIntervalMs, long periodicIntervalMs) {
            this.syncAction = syncAction;
            this.minResumeIntervalMs = minResumeIntervalMs;
            this.periodicIntervalMs = periodicIntervalMs;
        }

        public void markSynced() {
            hasSyncedOnce = true;
            lastSyncAtMs = System.currentTimeMillis();
        }

        public void onResume() {
            if (destroyed) {
                return;
            }
            resumed = true;
            long now = System.currentTimeMillis();
            if (hasSyncedOnce && (now - lastSyncAtMs) >= minResumeIntervalMs) {
                lastSyncAtMs = now;
                syncAction.run();
            }
            scheduleNext();
        }

        public void onPause() {
            resumed = false;
            AndroidUtilities.cancelRunOnUIThread(periodicTask);
        }

        public void destroy() {
            destroyed = true;
            resumed = false;
            AndroidUtilities.cancelRunOnUIThread(periodicTask);
        }

        private void scheduleNext() {
            AndroidUtilities.cancelRunOnUIThread(periodicTask);
            if (!destroyed && resumed && periodicIntervalMs > 0) {
                AndroidUtilities.runOnUIThread(periodicTask, periodicIntervalMs);
            }
        }
    }

    private static final LruCache<String, Bitmap> AVATAR_CACHE = new LruCache<>(64);
    private static final ExecutorService AVATAR_EXECUTOR = Executors.newFixedThreadPool(2);

    public static void bindStudentAvatar(
            @NonNull BackupImageView avatarView,
            @NonNull AvatarDrawable avatarDrawable,
            @NonNull EveAdminModels.AdminStudentItem student,
            int sizeDp) {
        bindAvatar(avatarView, avatarDrawable, student.id, student.displayName, student.photoUrl, sizeDp);
    }

    public static void bindExamAvatar(
            @NonNull BackupImageView avatarView,
            @NonNull AvatarDrawable avatarDrawable,
            @Nullable String examId,
            @Nullable String examName,
            @Nullable String avatarUrl,
            @Nullable String avatarEmoji,
            int avatarColor,
            int sizeDp,
            @Nullable Theme.ResourcesProvider resourcesProvider) {
        String name = !TextUtils.isEmpty(examName) ? examName.trim() : "Exam";
        String effectiveSeed = !TextUtils.isEmpty(examId) ? examId : name;
        long seedHash = effectiveSeed.hashCode();
        String emoji = avatarEmoji != null ? avatarEmoji.trim() : "";
        if (!emoji.isEmpty()) {
            avatarDrawable.setInfo(seedHash, name, null, emoji);
        } else {
            String[] parts = name.split("\\s+", 2);
            String first = parts.length > 0 ? parts[0] : "E";
            String last = parts.length > 1 ? parts[1] : "";
            avatarDrawable.setInfo(seedHash, first, last);
        }
        if (avatarColor >= 0 && avatarColor < Theme.keys_avatar_background.length) {
            avatarDrawable.setColor(
                    Theme.getColor(Theme.keys_avatar_background[avatarColor], resourcesProvider),
                    Theme.getColor(Theme.keys_avatar_background2[avatarColor], resourcesProvider)
            );
        }
        avatarView.setRoundRadius(dp(sizeDp / 2f));
        String resolvedUrl = EveBackendClient.resolveMediaUrl(avatarUrl);
        avatarView.setTag(effectiveSeed + "|" + resolvedUrl + "|" + emoji + "|" + avatarColor);
        if (!resolvedUrl.isEmpty()) {
            avatarView.setImage(ImageLocation.getForPath(resolvedUrl), "100_100", avatarDrawable, null);
        } else {
            avatarView.setImage(null, "50_50", avatarDrawable, null);
            avatarView.setImageDrawable(avatarDrawable);
        }
    }

    public static void bindAvatar(
            @NonNull BackupImageView avatarView,
            @NonNull AvatarDrawable avatarDrawable,
            @Nullable String seedKey,
            @Nullable String displayName,
            @Nullable String photoUrl,
            int sizeDp) {
        String name = !TextUtils.isEmpty(displayName) ? displayName : "User";
        String[] parts = name.trim().split("\\s+", 2);
        String first = parts.length > 0 ? parts[0] : "U";
        String last = parts.length > 1 ? parts[1] : "";
        String effectiveSeed = !TextUtils.isEmpty(seedKey) ? seedKey : name;
        long colorSeed = effectiveSeed.hashCode();
        avatarDrawable.setInfo(colorSeed, first, last);
        avatarView.setRoundRadius(dp(sizeDp / 2f));
        avatarView.setImageDrawable(avatarDrawable);

        String url = photoUrl != null ? photoUrl.trim() : "";
        String tag = effectiveSeed + "|" + url;
        avatarView.setTag(tag);

        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            return;
        }
        Bitmap cached = AVATAR_CACHE.get(url);
        if (cached != null && !cached.isRecycled()) {
            avatarView.setImageBitmap(cached);
            return;
        }
        AVATAR_EXECUTOR.execute(() -> {
            Bitmap loaded = fetchBitmap(url);
            if (loaded != null) {
                AVATAR_CACHE.put(url, loaded);
                AndroidUtilities.runOnUIThread(() -> {
                    if (Objects.equals(avatarView.getTag(), tag)) {
                        avatarView.setImageBitmap(loaded);
                    }
                });
            }
        });
    }

    @Nullable
    private static Bitmap fetchBitmap(String rawUrl) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(rawUrl).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
                try (InputStream is = conn.getInputStream()) {
                    return BitmapFactory.decodeStream(is);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    public static String formatDateTime(long epochMs) {
        if (epochMs <= 0L) {
            return "Never";
        }
        return new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(new Date(epochMs));
    }

    public static String formatDate(long epochMs) {
        if (epochMs <= 0L) {
            return "-";
        }
        return new SimpleDateFormat("dd MMM yyyy", Locale.US).format(new Date(epochMs));
    }

    public static String formatActionTitle(String rawType) {
        if (rawType == null || rawType.trim().isEmpty()) {
            return "Admin Action";
        }
        String[] parts = rawType.trim().toLowerCase(Locale.US).split("_+");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return out.length() > 0 ? out.toString() : "Admin Action";
    }

    /**
     * Reusable Telegram-native 44dp circular avatar list row for user/subscriber/admin directories.
     */
    public static final class AvatarRowCell extends FrameLayout implements Theme.Colorable {

        public static final int BADGE_NEUTRAL = 0;
        public static final int BADGE_RED = 1;
        public static final int BADGE_GREEN = 2;
        public static final int BADGE_PURPLE = 3;
        public static final int BADGE_BLUE = 4;

        private final Theme.ResourcesProvider resourcesProvider;
        private final AvatarDrawable avatarDrawable;
        private final BackupImageView avatarView;
        private final TextView titleView;
        private final TextView subtitleView;
        private final TextView badgeView;
        private int badgeStyle = BADGE_NEUTRAL;

        public AvatarRowCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;

            avatarDrawable = new AvatarDrawable();
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(22));

            LinearLayout textColumn = new LinearLayout(context);
            textColumn.setOrientation(LinearLayout.VERTICAL);

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            textColumn.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            textColumn.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            badgeView = new TextView(context);
            badgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            badgeView.setTypeface(AndroidUtilities.bold());
            badgeView.setSingleLine(true);
            badgeView.setPadding(dp(8), dp(3), dp(8), dp(3));

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (LocaleController.isRTL) {
                row.addView(badgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 14, 0, 8, 0));
                row.addView(textColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 12, 0));
                row.addView(avatarView, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
            } else {
                row.addView(avatarView, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 16, 0, 12, 0));
                row.addView(textColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
                row.addView(badgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
            }

            addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 10, 0, 10));
            updateColors();
        }

        public void bind(String seed, CharSequence title, CharSequence subtitle, String photoUrl, CharSequence badge, int style) {
            bind(seed, title, subtitle, photoUrl, "", -1, badge, style);
        }

        public void bind(String seed, CharSequence title, CharSequence subtitle, String photoUrl, String emoji, int avatarColor, CharSequence badge, int style) {
            this.badgeStyle = style;
            if (!TextUtils.isEmpty(emoji) || avatarColor >= 0) {
                bindExamAvatar(avatarView, avatarDrawable, seed, title != null ? title.toString() : "", photoUrl, emoji, avatarColor, 44, resourcesProvider);
            } else {
                bindAvatar(avatarView, avatarDrawable, seed, title != null ? title.toString() : "", photoUrl, 44);
            }
            titleView.setText(title);
            subtitleView.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
            subtitleView.setText(subtitle);
            badgeView.setVisibility(TextUtils.isEmpty(badge) ? View.GONE : View.VISIBLE);
            badgeView.setText(badge);
            updateColors();
        }

        @Override
        public void updateColors() {
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
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
            badgeView.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(badgeColor, 0.12f)));
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_windowBackgroundWhiteBlackText);
            colors.add(Theme.key_windowBackgroundWhiteGrayText);
            colors.add(Theme.key_text_RedRegular);
            colors.add(Theme.key_windowBackgroundWhiteBlueText);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(64), MeasureSpec.EXACTLY));
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

            public Payload(String seed, String title, String subtitle, String photoUrl, String badge, int badgeStyle) {
                this(seed, title, subtitle, photoUrl, "", -1, badge, badgeStyle);
            }

            public Payload(String seed, String title, String subtitle, String photoUrl, String emoji, int avatarColor, String badge, int badgeStyle) {
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

        public static final class Factory extends UItem.UItemFactory<AvatarRowCell> {
            static {
                setup(new Factory());
            }

            @Override
            public AvatarRowCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new AvatarRowCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                if (item.object2 instanceof Payload) {
                    Payload p = (Payload) item.object2;
                    ((AvatarRowCell) view).bind(p.seed, p.title, p.subtitle, p.photoUrl, p.emoji, p.avatarColor, p.badge, p.badgeStyle);
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

            public static UItem of(int id, Object tagObject, Payload payload) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.object = tagObject;
                item.object2 = payload;
                return item;
            }
        }
    }
}
