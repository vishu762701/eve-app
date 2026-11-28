package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.SettingsActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/**
 * Telegram-native full-screen Banner Creator & Uploader supporting BOTH Images/GIF/WebP (<= 5MB)
 * and Videos MP4/WebM (<= 20MB), with optional link URL & button/caption label.
 */
public class EveAdminBannerEditorFragment extends BaseFragment {

    private static final int MENU_UPLOAD = 1;
    private static final int REQ_PICK_BANNER_MEDIA = 6301;
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_VIDEO_BYTES = 20 * 1024 * 1024;

    private static final int ID_PICK_MEDIA = 101;
    private static final int ID_UPLOAD = 102;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    @Nullable private final Runnable onUploaded;

    private UniversalRecyclerView listView;
    private BannerPreviewCard previewCard;
    private LinearLayout formCard;
    private EditTextBoldCursor linkUrlInput;
    private EditTextBoldCursor linkLabelInput;
    private TextView linkTitleLabel;
    private TextView labelTitleLabel;
    private TextView optionalHintLabel;

    @Nullable private Uri pickedUri;
    @Nullable private byte[] pickedBytes;
    private String pickedMime = "";
    private String pickedFileName = "";
    private boolean isVideo = false;
    private String linkUrl = "";
    private String linkLabel = "";
    private boolean uploading = false;
    private boolean destroyed = false;

    public EveAdminBannerEditorFragment(@Nullable Runnable onUploaded) {
        this.onUploaded = onUploaded;
    }

    @Override
    public boolean onFragmentCreate() {
        return EveAdminConfig.isAdminAvailable() && super.onFragmentCreate();
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    protected boolean useFadeDrawableForActionBar() {
        return false;
    }

    @Nullable
    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    @Override
    public View createView(Context context) {
        destroyed = false;
        setHasOwnBackground(true);

        previewCard = new BannerPreviewCard(context, getResourceProvider());
        buildFormCard(context);

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "New Home Banner",
                "Photo, GIF, WebP or MP4/WebM Video",
                true,
                true,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_UPLOAD) {
                            uploadBanner();
                        }
                    }
                },
                () -> {
                    if (previewCard != null) previewCard.updateColors();
                    updateFormColors();
                }
        );
        listView = chrome.listView;

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            menu.addItem(MENU_UPLOAD, R.drawable.ic_ab_done, getResourceProvider());
        }

        fragmentView = chrome.rootView;
        return chrome.rootView;
    }

    @Override
    public void onPause() {
        if (previewCard != null) previewCard.pauseVideo();
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        if (previewCard != null) previewCard.releaseVideo();
        super.onFragmentDestroy();
    }

    private void buildFormCard(Context context) {
        formCard = new LinearLayout(context);
        formCard.setOrientation(LinearLayout.VERTICAL);
        formCard.setPadding(dp(16), dp(14), dp(16), dp(14));

        linkTitleLabel = new TextView(context);
        linkTitleLabel.setText("Click Link URL (Optional)");
        linkTitleLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        linkTitleLabel.setTypeface(AndroidUtilities.bold());
        formCard.addView(linkTitleLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        linkUrlInput = new EditTextBoldCursor(context);
        linkUrlInput.setHint("https://example.com (leave empty for display-only banner)");
        linkUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkUrlInput.setSingleLine(true);
        linkUrlInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        linkUrlInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        linkUrlInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                linkUrl = s != null ? s.toString().trim() : "";
            }
        });
        formCard.addView(linkUrlInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        labelTitleLabel = new TextView(context);
        labelTitleLabel.setText("Caption / Button Label (Optional)");
        labelTitleLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        labelTitleLabel.setTypeface(AndroidUtilities.bold());
        formCard.addView(labelTitleLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        linkLabelInput = new EditTextBoldCursor(context);
        linkLabelInput.setHint("e.g. Open Test Series (Optional)");
        linkLabelInput.setInputType(InputType.TYPE_CLASS_TEXT);
        linkLabelInput.setSingleLine(true);
        linkLabelInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        linkLabelInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        linkLabelInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                linkLabel = s != null ? s.toString().trim() : "";
            }
        });
        formCard.addView(linkLabelInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        optionalHintLabel = new TextView(context);
        optionalHintLabel.setText("Display-only banners need no link URL. Tapping them on Home does nothing.");
        optionalHintLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        formCard.addView(optionalHintLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        updateFormColors();
    }

    private void updateFormColors() {
        if (formCard == null) return;
        Theme.ResourcesProvider rp = getResourceProvider();
        int black = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp);
        int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp);
        int hint = Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp);
        int blue = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp);
        int fieldBg = Theme.multAlpha(gray, 0.09f);

        linkTitleLabel.setTextColor(blue);
        labelTitleLabel.setTextColor(blue);
        optionalHintLabel.setTextColor(gray);

        for (EditTextBoldCursor et : new EditTextBoldCursor[] { linkUrlInput, linkLabelInput }) {
            et.setTextColor(black);
            et.setHintTextColor(hint);
            et.setCursorColor(blue);
            et.setBackground(Theme.createRoundRectDrawable(dp(10), fieldBg));
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        items.add(UItem.asHeader("Media Preview"));
        items.add(UItem.asCustom(previewCard));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_PICK_MEDIA,
                IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                R.drawable.msg_photos,
                pickedBytes == null ? "Select Image or Video" : "Change Selected Media",
                pickedBytes == null
                        ? "Photo/GIF/WebP (<= 5MB) or MP4/WebM Video (<= 20MB)"
                        : (pickedFileName + " • " + (pickedBytes.length / 1024) + " KB"),
                isVideo ? "Video" : (pickedBytes != null ? "Photo" : null)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Optional Link & Caption"));
        items.add(UItem.asCustom(formCard));
        items.add(UItem.asShadow(null));

        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_UPLOAD,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                R.drawable.ic_ab_done,
                uploading ? "Uploading Banner..." : "Upload & Publish Banner",
                "Renders as a compact 120dp banner on Student Home",
                null));
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_PICK_MEDIA) {
            launchMediaPicker();
        } else if (item.id == ID_UPLOAD) {
            uploadBanner();
        }
    }

    private void launchMediaPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                    "image/jpeg", "image/png", "image/webp", "image/gif", "video/mp4", "video/webm"
            });
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, "Pick Banner Image or Video"), REQ_PICK_BANNER_MEDIA);
        } catch (Throwable t) {
            BulletinFactory.of(this).createErrorBulletin("Unable to open device media picker.").show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_BANNER_MEDIA || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Context ctx = getParentActivity();
        if (ctx == null) return;
        final Uri uri = data.getData();

        Utilities.globalQueue.postRunnable(() -> {
            try (InputStream is = ctx.getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                if (is == null) throw new IllegalStateException("Cannot read selected file.");
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                    if (bos.size() > MAX_VIDEO_BYTES) {
                        throw new IllegalArgumentException("File exceeds maximum 20MB limit.");
                    }
                }
                byte[] bytes = bos.toByteArray();
                String mime = ctx.getContentResolver().getType(uri);
                String lower = uri.toString().toLowerCase(Locale.US);
                if (mime == null || mime.isEmpty() || "application/octet-stream".equals(mime)) {
                    if (lower.endsWith(".mp4")) mime = "video/mp4";
                    else if (lower.endsWith(".webm")) mime = "video/webm";
                    else if (lower.endsWith(".png")) mime = "image/png";
                    else if (lower.endsWith(".webp")) mime = "image/webp";
                    else if (lower.endsWith(".gif")) mime = "image/gif";
                    else mime = "image/jpeg";
                }
                boolean vid = mime.startsWith("video/");
                if (!vid && bytes.length > MAX_IMAGE_BYTES) {
                    throw new IllegalArgumentException("Images must be 5MB or smaller.");
                }
                String finalMime = mime;
                String name = uri.getLastPathSegment() != null ? uri.getLastPathSegment() : (vid ? "banner.mp4" : "banner.jpg");
                AndroidUtilities.runOnUIThread(() -> {
                    if (destroyed) return;
                    pickedUri = uri;
                    pickedBytes = bytes;
                    pickedMime = finalMime;
                    pickedFileName = name;
                    isVideo = vid;
                    previewCard.bindLocalMedia(uri, bytes, vid);
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                });
            } catch (Throwable t) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!destroyed) {
                        BulletinFactory.of(this).createErrorBulletin(
                                t.getMessage() != null ? t.getMessage() : "Could not read selected media.").show();
                    }
                });
            }
        });
    }

    private void uploadBanner() {
        if (uploading) return;
        if (pickedBytes == null || pickedBytes.length == 0) {
            BulletinFactory.of(this).createErrorBulletin("Select an image or video first.").show();
            return;
        }
        String urlVal = linkUrlInput != null && linkUrlInput.getText() != null ? linkUrlInput.getText().toString().trim() : linkUrl;
        String labelVal = linkLabelInput != null && linkLabelInput.getText() != null ? linkLabelInput.getText().toString().trim() : linkLabel;
        if (!urlVal.isEmpty() && !(urlVal.startsWith("https://") || urlVal.startsWith("http://"))) {
            BulletinFactory.of(this).createErrorBulletin("Link URL must start with https:// or http:// (or leave empty).").show();
            return;
        }
        uploading = true;
        if (simpleActionBar != null) simpleActionBar.setSubtitle("Uploading...");
        if (listView != null && listView.adapter != null) listView.adapter.update(true);

        EveAdminRepository.ResultCallback<EveAdminModels.BannerItem> cb =
                new EveAdminRepository.ResultCallback<EveAdminModels.BannerItem>() {
                    @Override
                    public void onSuccess(EveAdminModels.BannerItem item) {
                        if (destroyed) return;
                        uploading = false;
                        BulletinFactory.of(EveAdminBannerEditorFragment.this)
                                .createSimpleBulletin(R.raw.contact_check, "Banner published!").show();
                        if (onUploaded != null) onUploaded.run();
                        finishFragment();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        uploading = false;
                        if (simpleActionBar != null) simpleActionBar.setSubtitle("Photo, GIF, WebP or MP4/WebM Video");
                        if (listView != null && listView.adapter != null) listView.adapter.update(true);
                        BulletinFactory.of(EveAdminBannerEditorFragment.this)
                                .createErrorBulletin(message != null ? message : "Banner upload failed.").show();
                    }
                };
        repo.runWithAdminToken(cb, token -> {
            EveAdminModels.BannerItem item = repo.uploadBannerMedia(token, pickedBytes, pickedMime, urlVal, labelVal);
            repo.postSuccess(cb, item);
        });
    }

    private static final class BannerPreviewCard extends FrameLayout implements Theme.Colorable {

        private final Theme.ResourcesProvider rp;
        private final BackupImageView imageView;
        private final TextureView videoTexture;
        private final TextView emptyLabel;
        @Nullable private VideoPlayer player;

        BannerPreviewCard(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.rp = rp;
            setPadding(dp(14), dp(10), dp(14), dp(10));

            FrameLayout card = new FrameLayout(context);
            card.setClipToOutline(true);
            card.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
                }
            });

            imageView = new BackupImageView(context);
            imageView.setAspectFit(false);
            imageView.setRoundRadius(dp(18));
            card.addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            videoTexture = new TextureView(context);
            videoTexture.setVisibility(GONE);
            card.addView(videoTexture, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            emptyLabel = new TextView(context);
            emptyLabel.setGravity(Gravity.CENTER);
            emptyLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            emptyLabel.setText("Preview appears here (Compact Home Banner • 120dp)");
            card.addView(emptyLabel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            int targetHeight = dp(120);
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, targetHeight));
            updateColors();
        }

        void bindLocalMedia(Uri uri, byte[] bytes, boolean video) {
            releaseVideo();
            emptyLabel.setVisibility(GONE);
            if (video) {
                imageView.setVisibility(GONE);
                videoTexture.setVisibility(VISIBLE);
                player = new VideoPlayer(false, true);
                player.setTextureView(videoTexture);
                player.setLooping(true);
                player.setMute(true);
                player.preparePlayer(uri, "other");
                player.play();
            } else {
                videoTexture.setVisibility(GONE);
                imageView.setVisibility(VISIBLE);
                if (uri != null && (bytes == null || bytes.length == 0)) {
                    imageView.setImage(ImageLocation.getForPath(uri.toString()), null, (android.graphics.drawable.Drawable) null, null);
                } else {
                    imageView.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
                }
            }
        }

        void pauseVideo() {
            if (player != null) {
                player.pause();
            }
        }

        void releaseVideo() {
            if (player != null) {
                player.releasePlayer(true);
                player = null;
            }
        }

        @Override
        public void updateColors() {
            int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp);
            emptyLabel.setTextColor(gray);
            emptyLabel.setBackground(Theme.createRoundRectDrawable(dp(18), Theme.multAlpha(gray, 0.10f)));
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_windowBackgroundWhiteGrayText);
        }
    }
}
