package org.telegram.ui.eve.exams;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.eve.admin.EveAdminModels;
import org.telegram.ui.eve.auth.EveBackendClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Horizontal Telegram-native Banner Carousel rendered above EVE Home Exams.
 * Video banners are downloaded once per banner ID into a capped (40 MB LRU) disk cache
 * under {@code cacheDir/eve_banner_media/<bannerId>.mp4} and played locally with full
 * defensive try/catch fallback so corrupt/unsupported streams can never crash the home tab.
 */
public class EveHomeBannerCarouselView extends FrameLayout implements Theme.Colorable {

    private static final String BANNER_MEDIA_CACHE_DIR = "eve_banner_media";
    private static final long MAX_VIDEO_CACHE_BYTES = 40L * 1024L * 1024L;
    private static final long MAX_SINGLE_BANNER_BYTES = 24L * 1024L * 1024L;

    private static final ExecutorService VIDEO_FETCH_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<String, List<BannerDownloadListener>> IN_FLIGHT = new ConcurrentHashMap<>();

    private interface BannerDownloadListener {
        void onComplete(@Nullable File cachedFile);
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final RecyclerView recyclerView;
    private final LinearLayoutManager layoutManager;
    private final DotsIndicatorView dotsView;
    private final BannerAdapter adapter;
    private final List<EveAdminModels.BannerItem> items = new ArrayList<>();
    private final Set<BannerCardHolder> activeVideoHolders = new HashSet<>();
    private int currentPage = 0;

    public static int computeBannerHeightPx() {
        return dp(120);
    }

    public EveHomeBannerCarouselView(Context context, @Nullable Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        int bannerHeight = computeBannerHeightPx();
        setPadding(0, dp(8), 0, dp(6));

        FrameLayout carouselWrapper = new FrameLayout(context);

        recyclerView = new RecyclerView(context);
        layoutManager = new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setNestedScrollingEnabled(false);
        recyclerView.setOverScrollMode(OVER_SCROLL_NEVER);
        new PagerSnapHelper().attachToRecyclerView(recyclerView);

        adapter = new BannerAdapter();
        recyclerView.setAdapter(adapter);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    int pos = layoutManager.findFirstCompletelyVisibleItemPosition();
                    if (pos < 0) pos = layoutManager.findFirstVisibleItemPosition();
                    if (pos >= 0 && pos != currentPage) {
                        currentPage = pos;
                        dotsView.setSelectedIndex(currentPage);
                        updateVideoPlaybackForVisiblePage();
                    }
                }
            }
        });

        carouselWrapper.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, bannerHeight));
        addView(carouselWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, bannerHeight, Gravity.TOP));

        dotsView = new DotsIndicatorView(context, resourcesProvider);
        addView(dotsView, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, 14,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                0, 0, 0, 8));
    }

    public void setBanners(@Nullable List<EveAdminModels.BannerItem> banners) {
        items.clear();
        if (banners != null) {
            for (EveAdminModels.BannerItem b : banners) {
                if (b != null && b.active && !TextUtils.isEmpty(b.imageUrl)) {
                    items.add(b);
                }
            }
        }
        if (currentPage >= items.size()) {
            currentPage = 0;
        }
        dotsView.setCount(items.size());
        dotsView.setSelectedIndex(currentPage);
        dotsView.setVisibility(items.size() > 1 ? VISIBLE : GONE);
        adapter.notifyDataSetChanged();
    }

    public void onPauseCarousel() {
        for (BannerCardHolder holder : activeVideoHolders) {
            holder.pauseVideo();
        }
    }

    public void onResumeCarousel() {
        updateVideoPlaybackForVisiblePage();
    }

    public void releaseCarousel() {
        for (BannerCardHolder holder : new ArrayList<>(activeVideoHolders)) {
            holder.releaseVideo();
        }
        activeVideoHolders.clear();
    }

    @Override
    protected void onDetachedFromWindow() {
        onPauseCarousel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        onResumeCarousel();
    }

    private void updateVideoPlaybackForVisiblePage() {
        for (BannerCardHolder holder : activeVideoHolders) {
            if (holder.boundPosition == currentPage) {
                holder.resumeVideo();
            } else {
                holder.pauseVideo();
            }
        }
    }

    @Override
    public void updateColors() {
        if (dotsView != null) {
            dotsView.invalidate();
        }
    }

    @Override
    public void addColorKeys(HashSet<Integer> colors) {
        colors.add(Theme.key_windowBackgroundWhiteBlueText);
        colors.add(Theme.key_windowBackgroundWhiteGrayText);
    }

    @Nullable
    private static File resolveBannerMediaCacheDir(@Nullable Context ctx) {
        try {
            Context appCtx = ctx != null ? ctx.getApplicationContext() : ApplicationLoader.applicationContext;
            if (appCtx == null) {
                return null;
            }
            File base = appCtx.getCacheDir();
            if (base == null) {
                return null;
            }
            File dir = new File(base, BANNER_MEDIA_CACHE_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return dir;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @NonNull
    private static String safeBannerFileKey(@NonNull EveAdminModels.BannerItem banner, @NonNull String url) {
        String id = !TextUtils.isEmpty(banner.id) ? banner.id.trim() : Integer.toHexString(url.hashCode());
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < id.length() && clean.length() < 48; i++) {
            char c = id.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                clean.append(c);
            } else {
                clean.append('_');
            }
        }
        if (clean.length() == 0) {
            clean.append("banner_").append(Math.abs(url.hashCode()));
        }
        return clean.toString();
    }

    private static void fetchVideoToCacheOnce(
            @Nullable Context ctx,
            @NonNull String cacheKey,
            @NonNull String remoteUrl,
            @NonNull BannerDownloadListener listener) {
        File dir = resolveBannerMediaCacheDir(ctx);
        if (dir == null) {
            AndroidUtilities.runOnUIThread(() -> listener.onComplete(null));
            return;
        }
        File target = new File(dir, cacheKey + ".mp4");
        if (target.exists() && target.isFile() && target.length() > 0L) {
            target.setLastModified(System.currentTimeMillis());
            AndroidUtilities.runOnUIThread(() -> listener.onComplete(target));
            return;
        }

        boolean shouldStartDownload = false;
        synchronized (IN_FLIGHT) {
            List<BannerDownloadListener> listeners = IN_FLIGHT.get(cacheKey);
            if (listeners == null) {
                listeners = new ArrayList<>();
                listeners.add(listener);
                IN_FLIGHT.put(cacheKey, listeners);
                shouldStartDownload = true;
            } else {
                listeners.add(listener);
            }
        }
        if (!shouldStartDownload) {
            return;
        }

        VIDEO_FETCH_EXECUTOR.execute(() -> {
            File resultFile = null;
            File tmp = new File(dir, cacheKey + ".tmp");
            HttpURLConnection conn = null;
            try {
                if (target.exists() && target.isFile() && target.length() > 0L) {
                    target.setLastModified(System.currentTimeMillis());
                    resultFile = target;
                } else {
                    conn = (HttpURLConnection) new URL(remoteUrl).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(25000);
                    conn.setInstanceFollowRedirects(true);
                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) {
                        long totalWritten = 0L;
                        byte[] buffer = new byte[16384];
                        try (InputStream is = conn.getInputStream();
                             FileOutputStream fos = new FileOutputStream(tmp, false)) {
                            int read;
                            while ((read = is.read(buffer)) != -1) {
                                totalWritten += read;
                                if (totalWritten > MAX_SINGLE_BANNER_BYTES) {
                                    throw new IllegalStateException("Banner video exceeds cap");
                                }
                                fos.write(buffer, 0, read);
                            }
                            fos.flush();
                        }
                        if (totalWritten > 0L && (tmp.renameTo(target) || (!target.exists() || target.delete()) && tmp.renameTo(target))) {
                            target.setLastModified(System.currentTimeMillis());
                            resultFile = target;
                        }
                    }
                    trimBannerVideoCacheLocked(dir, target);
                }
            } catch (Throwable err) {
                FileLog.e("EveHomeBannerCarouselView video fetch failed: " + remoteUrl, err);
            } finally {
                if (tmp.exists()) {
                    tmp.delete();
                }
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }

            List<BannerDownloadListener> waiters;
            synchronized (IN_FLIGHT) {
                waiters = IN_FLIGHT.remove(cacheKey);
            }
            final File finalResult = resultFile;
            if (waiters != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    for (BannerDownloadListener cb : waiters) {
                        if (cb != null) {
                            cb.onComplete(finalResult);
                        }
                    }
                });
            }
        });
    }

    private static void trimBannerVideoCacheLocked(@NonNull File dir, @Nullable File keepFile) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                return;
            }
            List<File> videos = new ArrayList<>();
            long total = 0L;
            for (File f : files) {
                if (f == null || !f.isFile()) {
                    continue;
                }
                if (f.getName().endsWith(".tmp")) {
                    if (System.currentTimeMillis() - f.lastModified() > 120_000L) {
                        f.delete();
                    }
                    continue;
                }
                if (f.getName().endsWith(".mp4")) {
                    videos.add(f);
                    total += Math.max(0L, f.length());
                }
            }
            if (total <= MAX_VIDEO_CACHE_BYTES) {
                return;
            }
            videos.sort(Comparator.comparingLong(File::lastModified));
            for (File f : videos) {
                if (total <= MAX_VIDEO_CACHE_BYTES) {
                    break;
                }
                if (keepFile != null && keepFile.equals(f)) {
                    continue;
                }
                long len = Math.max(0L, f.length());
                if (f.delete()) {
                    total = Math.max(0L, total - len);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private final class BannerAdapter extends RecyclerView.Adapter<BannerCardHolder> {
        @NonNull
        @Override
        public BannerCardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout slot = new FrameLayout(parent.getContext());
            slot.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    computeBannerHeightPx()
            ));
            slot.setPadding(dp(14), 0, dp(14), 0);
            BannerCardHolder holder = new BannerCardHolder(slot);
            activeVideoHolders.add(holder);
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull BannerCardHolder holder, int position) {
            holder.bind(items.get(position), position);
        }

        @Override
        public void onViewRecycled(@NonNull BannerCardHolder holder) {
            holder.releaseVideo();
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private final class BannerCardHolder extends RecyclerView.ViewHolder {
        private final FrameLayout card;
        private final BackupImageView imageView;
        private final TextureView textureView;
        private final TextView placeholderText;
        private final TextView captionPill;
        @Nullable private VideoPlayer player;
        private int boundPosition = -1;
        private String boundKey = "";
        private String boundVideoLocalPath = "";

        BannerCardHolder(FrameLayout outerSlot) {
            super(outerSlot);
            Context context = outerSlot.getContext();

            card = new FrameLayout(context);
            card.setClipToOutline(true);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
                }
            });
            card.setBackgroundColor(0xFF1B2434);

            imageView = new BackupImageView(context);
            imageView.setAspectFit(false);
            imageView.setRoundRadius(dp(18));
            card.addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            textureView = new TextureView(context);
            textureView.setVisibility(GONE);
            card.addView(textureView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            placeholderText = new TextView(context);
            placeholderText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            placeholderText.setTypeface(AndroidUtilities.bold());
            placeholderText.setTextColor(0xCCFFFFFF);
            placeholderText.setGravity(Gravity.CENTER);
            placeholderText.setPadding(dp(20), dp(12), dp(20), dp(12));
            placeholderText.setVisibility(GONE);
            card.addView(placeholderText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            captionPill = new TextView(context);
            captionPill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            captionPill.setTypeface(AndroidUtilities.bold());
            captionPill.setTextColor(0xFFFFFFFF);
            captionPill.setSingleLine(true);
            captionPill.setEllipsize(TextUtils.TruncateAt.END);
            captionPill.setPadding(dp(10), dp(4), dp(10), dp(4));
            captionPill.setBackground(Theme.createRoundRectDrawable(dp(10), 0x99000000));
            card.addView(captionPill, LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.START,
                    12, 0, 12, 12));

            outerSlot.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }

        void bind(EveAdminModels.BannerItem banner, int position) {
            this.boundPosition = position;
            String resolvedUrl = EveBackendClient.resolveMediaUrl(banner.imageUrl);
            if (TextUtils.isEmpty(banner.linkLabel)) {
                captionPill.setVisibility(GONE);
            } else {
                captionPill.setText(banner.linkLabel);
                captionPill.setVisibility(VISIBLE);
            }

            if (banner.isVideo() && !resolvedUrl.isEmpty()) {
                String cacheKey = safeBannerFileKey(banner, resolvedUrl);
                boundKey = cacheKey;
                showPosterFallback(!TextUtils.isEmpty(banner.linkLabel) ? banner.linkLabel : "Loading banner...");
                fetchVideoToCacheOnce(card.getContext(), cacheKey, resolvedUrl, cachedFile -> {
                    if (!cacheKey.equals(boundKey)) {
                        return;
                    }
                    if (cachedFile == null || !cachedFile.exists() || cachedFile.length() <= 0L) {
                        showPosterFallback(!TextUtils.isEmpty(banner.linkLabel) ? banner.linkLabel : "Announcement Banner");
                        return;
                    }
                    startLocalVideoSafely(cachedFile, position);
                });
            } else {
                boundKey = "";
                releaseVideo();
                placeholderText.setVisibility(GONE);
                textureView.setVisibility(GONE);
                imageView.setVisibility(VISIBLE);
                try {
                    if (!resolvedUrl.isEmpty()) {
                        imageView.setImage(ImageLocation.getForPath(resolvedUrl), null, (android.graphics.drawable.Drawable) null, null);
                    } else {
                        imageView.setImageBitmap(null);
                    }
                } catch (Throwable ignored) {
                }
            }

            final String clickUrl = banner.linkUrl != null ? banner.linkUrl.trim() : "";
            card.setClickable(!clickUrl.isEmpty());
            card.setOnClickListener(clickUrl.isEmpty() ? null : v -> {
                try {
                    Browser.openUrl(card.getContext(), clickUrl);
                } catch (Throwable ignored) {
                }
            });
        }

        private void startLocalVideoSafely(@NonNull File localFile, int position) {
            try {
                String localPath = localFile.getAbsolutePath();
                placeholderText.setVisibility(GONE);
                imageView.setVisibility(GONE);
                textureView.setVisibility(VISIBLE);

                if (!localPath.equals(boundVideoLocalPath) || player == null) {
                    releaseVideo();
                    boundVideoLocalPath = localPath;
                    player = new VideoPlayer(false, true);
                    player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                        @Override
                        public void onStateChanged(boolean playWhenReady, int playbackState) {
                        }

                        @Override
                        public void onError(VideoPlayer p, Exception e) {
                            FileLog.e("EveHomeBannerCarouselView VideoPlayer runtime error", e);
                            AndroidUtilities.runOnUIThread(() -> {
                                releaseVideo();
                                showPosterFallback("Video preview unavailable");
                            });
                        }

                        @Override
                        public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                        }

                        @Override
                        public void onRenderedFirstFrame() {
                            AndroidUtilities.runOnUIThread(() -> {
                                placeholderText.setVisibility(GONE);
                                imageView.setVisibility(GONE);
                            });
                        }

                        @Override
                        public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surfaceTexture) {
                        }

                        @Override
                        public boolean onSurfaceDestroyed(android.graphics.SurfaceTexture surfaceTexture) {
                            return false;
                        }
                    });
                    player.setTextureView(textureView);
                    player.setLooping(true);
                    player.setMute(true);
                    player.preparePlayer(Uri.fromFile(localFile), "other");
                }
                if (position == currentPage) {
                    player.play();
                } else {
                    player.pause();
                }
            } catch (Throwable err) {
                FileLog.e("EveHomeBannerCarouselView startLocalVideoSafely guarded", err);
                releaseVideo();
                showPosterFallback("Video preview unavailable");
            }
        }

        private void showPosterFallback(@NonNull String message) {
            try {
                textureView.setVisibility(GONE);
                imageView.setVisibility(VISIBLE);
                imageView.setImageBitmap(null);
                placeholderText.setText(message);
                placeholderText.setVisibility(VISIBLE);
            } catch (Throwable ignored) {
            }
        }

        void pauseVideo() {
            if (player != null) {
                try {
                    player.pause();
                } catch (Throwable ignored) {
                }
            }
        }

        void resumeVideo() {
            if (player != null && !boundVideoLocalPath.isEmpty()) {
                try {
                    player.setMute(true);
                    player.play();
                } catch (Throwable err) {
                    FileLog.e("EveHomeBannerCarouselView resumeVideo guarded", err);
                    releaseVideo();
                    showPosterFallback("Video preview unavailable");
                }
            }
        }

        void releaseVideo() {
            boundVideoLocalPath = "";
            if (player != null) {
                try {
                    player.releasePlayer(true);
                } catch (Throwable ignored) {
                }
                player = null;
            }
        }
    }

    private static final class DotsIndicatorView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Theme.ResourcesProvider rp;
        private int count = 0;
        private int selected = 0;

        DotsIndicatorView(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.rp = rp;
        }

        void setCount(int count) {
            this.count = Math.max(0, count);
            requestLayout();
            invalidate();
        }

        void setSelectedIndex(int idx) {
            this.selected = Math.max(0, idx);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = count > 1 ? (count * dp(12)) : 0;
            setMeasuredDimension(w, dp(12));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (count <= 1) return;
            int activeColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp);
            int inactiveColor = Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp), 0.35f);
            float cy = getHeight() * 0.5f;
            float rActive = dp(3.5f);
            float rInactive = dp(2.5f);
            float startX = dp(6);
            for (int i = 0; i < count; i++) {
                boolean isSel = i == selected;
                paint.setColor(isSel ? activeColor : inactiveColor);
                canvas.drawCircle(startX + i * dp(12), cy, isSel ? rActive : rInactive, paint);
            }
        }
    }
}
