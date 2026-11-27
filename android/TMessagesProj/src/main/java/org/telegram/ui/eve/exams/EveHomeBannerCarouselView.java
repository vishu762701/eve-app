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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.eve.admin.EveAdminModels;
import org.telegram.ui.eve.auth.EveBackendClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Horizontal Telegram-native Banner Carousel rendered above EVE Home Exams.
 * Exact dimensions: width matches exam row container (12dp horizontal margins matching Telegram cards),
 * height = 2.5x single exam row height (Math.round(dp(72) * 2.5f) = 180dp).
 */
public class EveHomeBannerCarouselView extends FrameLayout implements Theme.Colorable {

    private final Theme.ResourcesProvider resourcesProvider;
    private final RecyclerView recyclerView;
    private final LinearLayoutManager layoutManager;
    private final DotsIndicatorView dotsView;
    private final BannerAdapter adapter;
    private final List<EveAdminModels.BannerItem> items = new ArrayList<>();
    private final Set<BannerCardHolder> activeVideoHolders = new HashSet<>();
    private int currentPage = 0;

    public static int computeBannerHeightPx() {
        return Math.round(dp(72) * 2.5f);
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

    private final class BannerAdapter extends RecyclerView.Adapter<BannerCardHolder> {
        @NonNull
        @Override
        public BannerCardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout slot = new FrameLayout(parent.getContext());
            slot.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    computeBannerHeightPx()
            ));
            slot.setPadding(dp(12), 0, dp(12), 0);
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
        private final TextView captionPill;
        @Nullable private VideoPlayer player;
        private int boundPosition = -1;
        private String boundVideoUrl = "";

        BannerCardHolder(FrameLayout outerSlot) {
            super(outerSlot);
            Context context = outerSlot.getContext();

            card = new FrameLayout(context);
            card.setClipToOutline(true);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
                }
            });
            card.setBackgroundColor(0xFF1B2434);

            imageView = new BackupImageView(context);
            imageView.setAspectFit(false);
            imageView.setRoundRadius(dp(14));
            card.addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            textureView = new TextureView(context);
            textureView.setVisibility(GONE);
            card.addView(textureView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

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
                imageView.setVisibility(GONE);
                textureView.setVisibility(VISIBLE);
                if (!resolvedUrl.equals(boundVideoUrl) || player == null) {
                    releaseVideo();
                    boundVideoUrl = resolvedUrl;
                    player = new VideoPlayer(false, true);
                    player.setTextureView(textureView);
                    player.setLooping(true);
                    player.setMute(true);
                    player.preparePlayer(Uri.parse(resolvedUrl), "other");
                }
                if (position == currentPage) {
                    player.play();
                } else {
                    player.pause();
                }
            } else {
                releaseVideo();
                textureView.setVisibility(GONE);
                imageView.setVisibility(VISIBLE);
                if (!resolvedUrl.isEmpty()) {
                    imageView.setImage(ImageLocation.getForPath(resolvedUrl), null, (android.graphics.drawable.Drawable) null, null);
                } else {
                    imageView.setImageBitmap(null);
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

        void pauseVideo() {
            if (player != null) {
                player.pause();
            }
        }

        void resumeVideo() {
            if (player != null && !boundVideoUrl.isEmpty()) {
                player.setMute(true);
                player.play();
            }
        }

        void releaseVideo() {
            boundVideoUrl = "";
            if (player != null) {
                player.releasePlayer(true);
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
        protected void onDraw(@NonNull Canvas canvas) {
            if (count <= 1) return;
            float cy = getHeight() / 2f;
            float startX = dp(6);
            for (int i = 0; i < count; i++) {
                boolean active = i == selected;
                paint.setColor(active ? 0xFFFFFFFF : 0x80FFFFFF);
                canvas.drawCircle(startX + i * dp(12), cy, dp(active ? 3.5f : 2.5f), paint);
            }
        }
    }
}
