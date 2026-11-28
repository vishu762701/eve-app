package org.telegram.ui.eve.admin;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ImageUpdater;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * Reusable wrapper around Telegram's official ImageUpdater (Gallery Grid, Camera,
 * Circular PhotoCropActivity, and Avatar/Emoji Constructor) in local-capture mode
 * (setUploadAfterSelect(false)). Intercepts the cropped 800x800 image bytes locally
 * before MTProto upload so Exams, Sub-Exams, Mock Tests, and the Announcements
 * Channel can upload directly to EVE's Cloudflare Worker backend.
 */
public final class EveTelegramAvatarPicker {

    public interface Delegate {
        void onAvatarCaptured(@NonNull byte[] imageBytes, @NonNull String mimeType, @Nullable Bitmap previewBitmap);
        default void onAnimatedEmojiAvatarCaptured(
                long documentId,
                @NonNull int[] backgroundColors,
                @NonNull byte[] fallbackPngBytes,
                @NonNull String mimeType,
                @Nullable Bitmap previewBitmap) {
            onAvatarCaptured(fallbackPngBytes, mimeType, previewBitmap);
        }
        default void onAvatarRemoved() {}
    }

    private static final int MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

    private final BaseFragment parentFragment;
    private final Delegate delegate;
    private final ImageUpdater imageUpdater;
    private final int currentAccount;
    private boolean destroyed = false;

    public EveTelegramAvatarPicker(@NonNull BaseFragment parentFragment, boolean isChannel, @NonNull Delegate delegate) {
        this.parentFragment = parentFragment;
        this.delegate = delegate;
        this.currentAccount = UserConfig.selectedAccount;

        // Open Telegram's official ChatAttachAlert/PhotoCropActivity pipeline with local-only output.
        this.imageUpdater = new ImageUpdater(false, isChannel ? ImageUpdater.FOR_TYPE_CHANNEL : ImageUpdater.FOR_TYPE_GROUP, true);
        this.imageUpdater.parentFragment = parentFragment;
        this.imageUpdater.setEveLocalAvatarMode(true);
        this.imageUpdater.setSearchAvailable(false, true);
        this.imageUpdater.setUploadAfterSelect(false);
        this.imageUpdater.setDelegate(new ImageUpdater.ImageUpdaterDelegate() {
            @Override
            public void didUploadPhoto(
                    TLRPC.InputFile photo,
                    TLRPC.InputFile video,
                    double videoStartTimestamp,
                    String videoPath,
                    TLRPC.PhotoSize bigSize,
                    TLRPC.PhotoSize smallSize,
                    boolean isVideo,
                    TLRPC.VideoSize emojiMarkup) {
                extractCapturedBytes(bigSize, smallSize, emojiMarkup);
            }
        });
    }

    public void open(boolean hasExistingAvatar) {
        if (destroyed || parentFragment.getParentActivity() == null) {
            return;
        }
        if (!hasExistingAvatar) {
            imageUpdater.openMenu(false, () -> {}, null, 0);
            return;
        }
        // When an avatar already exists, give a 1-tap Telegram sheet between updating via ImageUpdater
        // (Gallery / Camera / Crop / Avatar Constructor) or removing the current avatar.
        BottomSheet.Builder builder = new BottomSheet.Builder(parentFragment.getParentActivity(), false, parentFragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.ChoosePhoto), true);
        CharSequence[] items = new CharSequence[] {
                LocaleController.getString(R.string.ChooseFromGallery) + " / Camera",
                LocaleController.getString(R.string.DeletePhoto)
        };
        int[] icons = new int[] {
                R.drawable.msg_photos,
                R.drawable.msg_delete
        };
        builder.setItems(items, icons, (dialog, which) -> {
            if (which == 0) {
                imageUpdater.openMenu(false, () -> {}, null, 0);
            } else if (which == 1) {
                delegate.onAvatarRemoved();
            }
        });
        BottomSheet sheet = builder.create();
        parentFragment.showDialog(sheet);
        sheet.setItemColor(1, Theme.getColor(Theme.key_text_RedBold, parentFragment.getResourceProvider()),
                Theme.getColor(Theme.key_text_RedRegular, parentFragment.getResourceProvider()));
        if (sheet.getItemViews().size() > 1) {
            BottomSheet.BottomSheetCell deleteCell = sheet.getItemViews().get(1);
            if (deleteCell != null && deleteCell.getTextView() != null && deleteCell.getImageView() != null) {
                deleteCell.getTextView().setTextColor(Theme.getColor(Theme.key_text_RedBold, parentFragment.getResourceProvider()));
                deleteCell.getImageView().setColorFilter(new android.graphics.PorterDuffColorFilter(
                        Theme.getColor(Theme.key_text_RedRegular, parentFragment.getResourceProvider()),
                        android.graphics.PorterDuff.Mode.MULTIPLY));
            }
        }
    }

    private void extractCapturedBytes(
            @Nullable TLRPC.PhotoSize bigSize,
            @Nullable TLRPC.PhotoSize smallSize,
            @Nullable TLRPC.VideoSize emojiMarkup) {
        final long animDocId;
        final int[] animColors;
        if (emojiMarkup instanceof TLRPC.TL_videoSizeEmojiMarkup) {
            TLRPC.TL_videoSizeEmojiMarkup em = (TLRPC.TL_videoSizeEmojiMarkup) emojiMarkup;
            animDocId = em.emoji_id;
            animColors = new int[em.background_colors.size()];
            for (int i = 0; i < em.background_colors.size(); i++) {
                animColors[i] = em.background_colors.get(i);
            }
        } else {
            animDocId = 0L;
            animColors = new int[0];
        }
        Utilities.globalQueue.postRunnable(() -> {
            byte[] bytes = null;
            String mime = "image/jpeg";
            Bitmap preview = imageUpdater.getPreviewBitmap();

            File targetFile = resolveCacheFile(bigSize);
            if (targetFile == null || !targetFile.exists() || targetFile.length() == 0) {
                targetFile = resolveCacheFile(smallSize);
            }
            if (targetFile != null && targetFile.exists() && targetFile.length() > 0) {
                bytes = readFileBytesBounded(targetFile);
                if (targetFile.getName().toLowerCase().endsWith(".png")) {
                    mime = "image/png";
                } else if (targetFile.getName().toLowerCase().endsWith(".webp")) {
                    mime = "image/webp";
                }
            }
            if ((bytes == null || bytes.length == 0) && preview != null && !preview.isRecycled()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                preview.compress(Bitmap.CompressFormat.JPEG, 92, bos);
                bytes = bos.toByteArray();
                mime = "image/jpeg";
            }
            if (preview == null && bytes != null && bytes.length > 0) {
                try {
                    preview = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                } catch (Throwable ignored) {
                }
            }

            final byte[] finalBytes = bytes;
            final String finalMime = mime;
            final Bitmap finalPreview = preview;
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed) return;
                if (finalBytes != null && finalBytes.length > 0) {
                    if (animDocId != 0L) {
                        delegate.onAnimatedEmojiAvatarCaptured(animDocId, animColors, finalBytes, finalMime, finalPreview);
                    } else {
                        delegate.onAvatarCaptured(finalBytes, finalMime, finalPreview);
                    }
                }
            });
        });
    }

    @Nullable
    private File resolveCacheFile(@Nullable TLRPC.PhotoSize size) {
        if (size == null || size.location == null) {
            return null;
        }
        try {
            File f = FileLoader.getInstance(currentAccount).getPathToAttach(size, true);
            if (f != null && f.exists()) {
                return f;
            }
        } catch (Throwable ignored) {
        }
        try {
            File dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
            if (dir != null) {
                return new File(dir, size.location.volume_id + "_" + size.location.local_id + ".jpg");
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Nullable
    private static byte[] readFileBytesBounded(@NonNull File file) {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_UPLOAD_BYTES) {
                    FileLog.e("EveTelegramAvatarPicker: captured avatar exceeds 5MB cap");
                    return null;
                }
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            FileLog.e("EveTelegramAvatarPicker: failed reading cropped image", t);
            return null;
        }
    }

    public void onResume() {
        imageUpdater.onResume();
    }

    public void onPause() {
        imageUpdater.onPause();
    }

    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        imageUpdater.onActivityResult(requestCode, resultCode, data);
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        imageUpdater.onRequestPermissionsResultFragment(requestCode, permissions, grantResults);
    }

    public void destroy() {
        destroyed = true;
        imageUpdater.clear();
    }
}
