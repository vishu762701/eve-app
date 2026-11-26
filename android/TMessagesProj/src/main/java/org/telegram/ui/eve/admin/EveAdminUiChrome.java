package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.utils.glass.BaseFragmentSimpleGlass;
import org.telegram.utils.glass.GlassEngine;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared Telegram Settings-style glass header + rounded UniversalRecyclerView sections chrome
 * for all EVE Admin Control Center screens.
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
            @NonNull Utilities.Callback2<ArrayList<UItem>, UniversalAdapter> fillItems,
            @Nullable Utilities.Callback5<UItem, View, Integer, Float, Float> onClick,
            @Nullable ActionBar.ActionBarMenuOnItemClick menuClick,
            @Nullable Runnable onUpdateColors) {

        final Chrome[] holder = new Chrome[1];
        final UniversalRecyclerView listView = new UniversalRecyclerView(fragment, fillItems, onClick, null);
        listView.setCaptureSectionsDecoratorAllowed(true);
        if (listView.adapter != null) {
            listView.adapter.setApplyBackground(false);
        }
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        BaseFragmentSimpleGlass.setListViewParams(listView, 0, 0, dp(12), 0);
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
                    setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));
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
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));

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
            BaseFragmentSimpleGlass.setListViewParams(listView, systemInsets.top, systemInsets.bottom, dp(12), 0);
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

    private static final LruCache<String, Bitmap> AVATAR_CACHE = new LruCache<>(64);
    private static final ExecutorService AVATAR_EXECUTOR = Executors.newFixedThreadPool(2);

    public static void bindStudentAvatar(
            @NonNull BackupImageView avatarView,
            @NonNull AvatarDrawable avatarDrawable,
            @NonNull EveAdminModels.AdminStudentItem student,
            int sizeDp) {
        String name = !TextUtils.isEmpty(student.displayName) ? student.displayName : "Student";
        String[] parts = name.trim().split("\\s+", 2);
        String first = parts.length > 0 ? parts[0] : "S";
        String last = parts.length > 1 ? parts[1] : "";
        long colorSeed = !TextUtils.isEmpty(student.id) ? student.id.hashCode() : name.hashCode();
        avatarDrawable.setInfo(colorSeed, first, last);
        avatarView.setRoundRadius(dp(sizeDp / 2f));
        avatarView.setImageDrawable(avatarDrawable);

        String url = student.photoUrl != null ? student.photoUrl.trim() : "";
        String tag = student.id + "|" + url;
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
}
