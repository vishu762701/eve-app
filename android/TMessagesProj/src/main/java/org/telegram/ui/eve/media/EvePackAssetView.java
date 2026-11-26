package org.telegram.ui.eve.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Components.AnimatedFileDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieDrawable;

import java.io.File;

/**
 * Self-contained native media view that renders EVE Emoji & Sticker pack assets
 * (.json/.tgs via RLottieDrawable, .webm via AnimatedFileDrawable, .webp/.png via Bitmap)
 * directly from offline cache or Worker CDN without requiring Telegram account login.
 */
public class EvePackAssetView extends FrameLayout {

    private final ImageView imageView;
    private final TextView fallbackEmojiView;

    private EvePackModels.PackItem currentItem;
    private int renderSizeDp = 44;

    private RLottieDrawable lottieDrawable;
    private AnimatedFileDrawable animatedFileDrawable;
    private Bitmap staticBitmap;
    private boolean attachedToWindow;

    public EvePackAssetView(Context context) {
        this(context, 44);
    }

    public EvePackAssetView(Context context, int sizeDp) {
        super(context);
        this.renderSizeDp = Math.max(20, sizeDp);

        fallbackEmojiView = new TextView(context);
        fallbackEmojiView.setGravity(Gravity.CENTER);
        fallbackEmojiView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, Math.max(16, Math.round(this.renderSizeDp * 0.56f)));
        fallbackEmojiView.setIncludeFontPadding(false);
        addView(fallbackEmojiView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setVisibility(GONE);
        addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    public void setRenderSizeDp(int sizeDp) {
        this.renderSizeDp = Math.max(20, sizeDp);
        fallbackEmojiView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, Math.max(16, Math.round(this.renderSizeDp * 0.56f)));
    }

    public void setItem(EvePackModels.PackItem item) {
        if (item == null) {
            currentItem = null;
            recycleDrawables();
            fallbackEmojiView.setText("");
            fallbackEmojiView.setVisibility(VISIBLE);
            imageView.setVisibility(GONE);
            return;
        }
        if (currentItem != null && item.id.equals(currentItem.id)
                && (lottieDrawable != null || animatedFileDrawable != null || staticBitmap != null)) {
            return;
        }
        currentItem = item;
        recycleDrawables();
        fallbackEmojiView.setText(item.altEmoji);
        fallbackEmojiView.setVisibility(VISIBLE);
        imageView.setVisibility(GONE);

        File cached = EvePackCacheManager.getInstance(getContext()).getLocalAssetFile(item.packId, item);
        if (cached != null && cached.exists() && cached.length() > 0) {
            bindLocalFile(item, cached);
            return;
        }

        final String targetId = item.id;
        EvePackRepository.getInstance().loadOrFetchPreviewFileAsync(getContext(), item, new EvePackRepository.Callback<File>() {
            @Override
            public void onSuccess(File file) {
                if (currentItem == null || !targetId.equals(currentItem.id) || file == null || !file.exists()) {
                    return;
                }
                bindLocalFile(currentItem, file);
            }

            @Override
            public void onError(String message, int httpStatus, String serverCode) {
                // Keep clean unicode altEmoji fallback when asset is missing or locked
            }
        });
    }

    private void bindLocalFile(EvePackModels.PackItem item, File file) {
        recycleDrawables();
        int px = AndroidUtilities.dp(renderSizeDp);
        try {
            String fmt = item.fileExtension();
            if ("json".equals(fmt) || "tgs".equals(fmt) || "lottie".equals(item.kind)) {
                RLottieDrawable drawable = new RLottieDrawable(file, null, px, px, null, false, null, 0, false);
                drawable.setAutoRepeat(1);
                drawable.setAllowDecodeSingleFrame(true);
                drawable.setCallback(imageView);
                lottieDrawable = drawable;
                imageView.setImageDrawable(drawable);
                imageView.setVisibility(VISIBLE);
                fallbackEmojiView.setVisibility(GONE);
                if (attachedToWindow) {
                    drawable.start();
                }
                return;
            }
            if ("webm".equals(fmt) || item.isVideo || "video".equals(item.kind)) {
                AnimatedFileDrawable video = new AnimatedFileDrawable(
                        file, true, 0L, 0, null, null, null, 0L,
                        UserConfig.selectedAccount, false, px, px, null, 0, true);
                video.setParentView(imageView);
                video.setAllowDecodeSingleFrame(true);
                animatedFileDrawable = video;
                imageView.setImageDrawable(video);
                imageView.setVisibility(VISIBLE);
                fallbackEmojiView.setVisibility(GONE);
                if (attachedToWindow) {
                    video.start();
                }
                return;
            }
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bmp != null) {
                staticBitmap = bmp;
                imageView.setImageBitmap(bmp);
                imageView.setVisibility(VISIBLE);
                fallbackEmojiView.setVisibility(GONE);
            }
        } catch (Throwable ignored) {
            recycleDrawables();
            fallbackEmojiView.setText(item.altEmoji);
            fallbackEmojiView.setVisibility(VISIBLE);
            imageView.setVisibility(GONE);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachedToWindow = true;
        if (lottieDrawable != null) {
            lottieDrawable.start();
        }
        if (animatedFileDrawable != null) {
            animatedFileDrawable.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attachedToWindow = false;
        if (lottieDrawable != null) {
            lottieDrawable.stop();
        }
        if (animatedFileDrawable != null) {
            animatedFileDrawable.stop();
        }
    }

    public void recycleDrawables() {
        imageView.setImageDrawable(null);
        if (lottieDrawable != null) {
            try {
                lottieDrawable.stop();
                lottieDrawable.recycle(true);
            } catch (Throwable ignored) {
            }
            lottieDrawable = null;
        }
        if (animatedFileDrawable != null) {
            try {
                animatedFileDrawable.stop();
                animatedFileDrawable.recycle();
            } catch (Throwable ignored) {
            }
            animatedFileDrawable = null;
        }
        if (staticBitmap != null) {
            if (!staticBitmap.isRecycled()) {
                staticBitmap.recycle();
            }
            staticBitmap = null;
        }
    }
}
