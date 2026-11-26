package org.telegram.ui.eve.media;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.PremiumPreviewFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Native Telegram BottomSheet displaying full pack metadata, animated/static item grid,
 * offline install / update / remove actions, EVE Premium unlock routing, and Admin preview mode.
 */
public class EvePackDetailBottomSheet extends BottomSheet {

    private final BaseFragment parentFragment;
    private final String packId;
    private final boolean adminPreviewMode;
    private final Runnable onStateChanged;

    private EvePackModels.PackSummary packSummary;
    private boolean loading = true;
    private boolean downloading = false;
    private int downloadDone = 0;
    private int downloadTotal = 0;
    private String errorMessage = "";

    private TextView titleView;
    private TextView subtitleView;
    private TextView descriptionView;
    private TextView badgeView;
    private TextView statusNoteView;
    private GridLayout gridLayout;
    private TextView primaryButton;
    private TextView removeButton;
    private final List<EvePackAssetView> activeAssetViews = new ArrayList<>();

    public EvePackDetailBottomSheet(
            BaseFragment parentFragment,
            EvePackModels.PackSummary initialSummary,
            Runnable onStateChanged) {
        this(parentFragment, initialSummary, false, onStateChanged);
    }

    public EvePackDetailBottomSheet(
            BaseFragment parentFragment,
            EvePackModels.PackSummary initialSummary,
            boolean adminPreviewMode,
            Runnable onStateChanged) {
        super(parentFragment.getContext(), false, parentFragment.getResourceProvider());
        this.parentFragment = parentFragment;
        this.packSummary = initialSummary;
        this.packId = initialSummary != null ? initialSummary.id : "";
        this.adminPreviewMode = adminPreviewMode;
        this.onStateChanged = onStateChanged;

        buildLayout(parentFragment.getContext());
        bindSummary(initialSummary);
        fetchFullDetails();
    }

    private void buildLayout(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));

        // Top Header Row
        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleLine = new LinearLayout(context);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleLine.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        badgeView = new TextView(context);
        badgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        badgeView.setTypeface(Typeface.DEFAULT_BOLD);
        badgeView.setPadding(dp(8), dp(3), dp(8), dp(3));
        titleLine.addView(badgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 0, 0));

        titleCol.addView(titleLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setTextColor(getThemedColor(Theme.key_dialogTextGray3));
        subtitleView.setPadding(0, dp(3), 0, 0);
        titleCol.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        headerRow.addView(titleCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        ImageView closeBtn = new ImageView(context);
        closeBtn.setImageResource(R.drawable.msg_close);
        closeBtn.setColorFilter(getThemedColor(Theme.key_dialogTextGray3));
        closeBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        closeBtn.setOnClickListener(v -> dismiss());
        headerRow.addView(closeBtn, LayoutHelper.createLinear(34, 34, 8, 0, 0, 0));

        root.addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        descriptionView = new TextView(context);
        descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        descriptionView.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        descriptionView.setPadding(0, dp(6), 0, dp(6));
        descriptionView.setVisibility(View.GONE);
        root.addView(descriptionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        statusNoteView = new TextView(context);
        statusNoteView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        statusNoteView.setTextColor(getThemedColor(Theme.key_dialogTextGray3));
        statusNoteView.setPadding(0, dp(4), 0, dp(8));
        root.addView(statusNoteView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(true);
        gridLayout = new GridLayout(context);
        gridLayout.setColumnCount(4);
        gridLayout.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        scrollView.addView(gridLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, dp(300), 0, 4, 0, 12));

        LinearLayout footerRow = new LinearLayout(context);
        footerRow.setOrientation(LinearLayout.HORIZONTAL);

        primaryButton = new TextView(context);
        primaryButton.setGravity(Gravity.CENTER);
        primaryButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        primaryButton.setTypeface(Typeface.DEFAULT_BOLD);
        primaryButton.setTextColor(Color.WHITE);
        primaryButton.setPadding(dp(16), dp(12), dp(16), dp(12));
        primaryButton.setOnClickListener(v -> handlePrimaryAction());
        footerRow.addView(primaryButton, LayoutHelper.createLinear(0, dp(46), 1f));

        removeButton = new TextView(context);
        removeButton.setGravity(Gravity.CENTER);
        removeButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        removeButton.setTypeface(Typeface.DEFAULT_BOLD);
        removeButton.setText(LocaleController.getString(R.string.EveRemovePack));
        removeButton.setTextColor(getThemedColor(Theme.key_text_RedBold));
        removeButton.setPadding(dp(14), dp(12), dp(14), dp(12));
        removeButton.setVisibility(View.GONE);
        removeButton.setOnClickListener(v -> handleRemovePack());
        footerRow.addView(removeButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, dp(46), 8, 0, 0, 0));

        root.addView(footerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        setCustomView(root);
    }

    private void fetchFullDetails() {
        if (packId.isEmpty()) {
            loading = false;
            return;
        }
        loading = true;
        errorMessage = "";
        updateFooterState();

        EvePackRepository.Callback<EvePackModels.PackSummary> cb = new EvePackRepository.Callback<EvePackModels.PackSummary>() {
            @Override
            public void onSuccess(EvePackModels.PackSummary data) {
                loading = false;
                if (data != null && !data.id.isEmpty()) {
                    packSummary = data;
                    bindSummary(data);
                }
            }

            @Override
            public void onError(String message, int httpStatus, String serverCode) {
                loading = false;
                errorMessage = message != null ? message : "";
                EvePackModels.InstalledPackRecord installed = EvePackCacheManager.getInstance(getContext()).getInstalledPack(packId);
                if (installed != null) {
                    packSummary = installed.toPackSummary();
                    bindSummary(packSummary);
                    statusNoteView.setText("Showing offline cached pack (v" + installed.version + ").");
                } else {
                    updateFooterState();
                }
            }
        };

        if (adminPreviewMode) {
            EvePackRepository.getInstance().loadAdminPackDetailAsync(packId, cb);
        } else {
            EvePackRepository.getInstance().loadPackDetailAsync(packId, cb);
        }
    }

    private void bindSummary(EvePackModels.PackSummary summary) {
        if (summary == null) return;
        titleView.setText(summary.title);
        StringBuilder sub = new StringBuilder();
        sub.append(summary.countLabel());
        sub.append(" • ").append(summary.category);
        if (!summary.creatorAttribution.isEmpty()) {
            sub.append(" • ").append(summary.creatorAttribution);
        } else if (!summary.licenseAttribution.isEmpty()) {
            sub.append(" • ").append(summary.licenseAttribution);
        }
        subtitleView.setText(sub.toString());

        if (!summary.description.isEmpty()) {
            descriptionView.setText(summary.description);
            descriptionView.setVisibility(View.VISIBLE);
        } else {
            descriptionView.setVisibility(View.GONE);
        }

        String badgeText = (summary.isStickerPack() ? "STICKER" : "EMOJI")
                + (summary.isPremium() ? " • PREMIUM" : " • FREE");
        badgeView.setText(badgeText);
        badgeView.setTextColor(summary.isPremium() ? Color.WHITE : getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(10));
        badgeBg.setColor(summary.isPremium() ? 0xFF7C4DFF : (getThemedColor(Theme.key_windowBackgroundWhiteBlueText) & 0x22FFFFFF));
        badgeView.setBackground(badgeBg);

        if (adminPreviewMode) {
            statusNoteView.setText("Admin Preview • Rights: " + summary.rightsStatus + " • Version " + summary.version);
        } else if (summary.isLockedForUser) {
            statusNoteView.setText("Previewing sample items. Upgrade to EVE Premium to unlock & download all items offline.");
        } else if (loading) {
            statusNoteView.setText("Loading full pack preview...");
        } else if (!errorMessage.isEmpty()) {
            statusNoteView.setText(errorMessage);
        } else {
            EvePackCacheManager cache = EvePackCacheManager.getInstance(getContext());
            EvePackModels.InstalledPackRecord installed = cache.getInstalledPack(summary.id);
            if (installed != null) {
                statusNoteView.setText(String.format(Locale.US, "Saved offline (v%d • %.1f KB verified SHA-256)",
                        installed.version, installed.totalBytes / 1024f));
            } else {
                statusNoteView.setText("Tap Add to cache offline with SHA-256 verification.");
            }
        }

        renderGrid(summary);
        updateFooterState();
    }

    private void renderGrid(EvePackModels.PackSummary summary) {
        for (EvePackAssetView v : activeAssetViews) {
            v.recycleDrawables();
        }
        activeAssetViews.clear();
        gridLayout.removeAllViews();

        List<EvePackModels.PackItem> items = !summary.items.isEmpty() ? summary.items : summary.previews;
        if (items.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText(loading ? "Loading pack items..." : "No items in this pack yet.");
            empty.setTextColor(getThemedColor(Theme.key_dialogTextGray3));
            empty.setPadding(dp(8), dp(24), dp(8), dp(24));
            gridLayout.addView(empty);
            return;
        }

        Context ctx = getContext();
        int cellSize = dp(74);
        for (EvePackModels.PackItem item : items) {
            FrameLayout cell = new FrameLayout(ctx);
            cell.setPadding(dp(4), dp(4), dp(4), dp(4));

            EvePackAssetView assetView = new EvePackAssetView(ctx, 56);
            assetView.setItem(item);
            activeAssetViews.add(assetView);
            cell.addView(assetView, LayoutHelper.createFrame(56, 56, Gravity.CENTER));

            cell.setOnClickListener(v -> {
                String info = item.altEmoji + " • " + item.fileFormat.toUpperCase(Locale.US)
                        + (item.byteSize > 0 ? String.format(Locale.US, " (%.1f KB)", item.byteSize / 1024f) : "");
                if (parentFragment != null) {
                    BulletinFactory.of(parentFragment).createSimpleBulletin(R.raw.info, info).show();
                }
            });

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cellSize;
            lp.height = cellSize;
            gridLayout.addView(cell, lp);
        }
    }

    private void updateFooterState() {
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(dp(12));

        if (adminPreviewMode) {
            btnBg.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
            primaryButton.setBackground(btnBg);
            primaryButton.setText(LocaleController.getString(R.string.Close));
            removeButton.setVisibility(View.GONE);
            return;
        }

        if (downloading) {
            btnBg.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
            primaryButton.setBackground(btnBg);
            primaryButton.setText("Downloading " + downloadDone + "/" + Math.max(1, downloadTotal) + "...");
            removeButton.setVisibility(View.GONE);
            return;
        }

        if (packSummary != null && packSummary.isLockedForUser) {
            btnBg.setColor(0xFF7C4DFF);
            primaryButton.setBackground(btnBg);
            primaryButton.setText(LocaleController.getString(R.string.EveUnlockPack) + " (EVE Premium)");
            removeButton.setVisibility(View.GONE);
            return;
        }

        EvePackCacheManager cache = EvePackCacheManager.getInstance(getContext());
        EvePackModels.InstalledPackRecord installed = cache.getInstalledPack(packId);
        if (installed != null && packSummary != null && packSummary.version > installed.version) {
            btnBg.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
            primaryButton.setBackground(btnBg);
            primaryButton.setText(LocaleController.getString(R.string.EveUpdatePack) + " (v" + packSummary.version + ")");
            removeButton.setVisibility(View.VISIBLE);
        } else if (installed != null) {
            btnBg.setColor(0xFF2E7D32);
            primaryButton.setBackground(btnBg);
            primaryButton.setText(LocaleController.getString(R.string.EveInstalledPack) + " (" + installed.itemCount + ")");
            removeButton.setVisibility(View.VISIBLE);
        } else {
            btnBg.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
            primaryButton.setBackground(btnBg);
            String countText = packSummary != null ? " (" + packSummary.countLabel() + ")" : "";
            primaryButton.setText(LocaleController.getString(R.string.EveAddPack) + countText);
            removeButton.setVisibility(View.GONE);
        }
    }

    private void handlePrimaryAction() {
        if (adminPreviewMode) {
            dismiss();
            return;
        }
        if (downloading || packId.isEmpty()) {
            return;
        }
        if (packSummary != null && packSummary.isLockedForUser) {
            dismiss();
            if (parentFragment != null) {
                parentFragment.presentFragment(new PremiumPreviewFragment("eve_pack_library"));
            }
            return;
        }

        EvePackCacheManager cache = EvePackCacheManager.getInstance(getContext());
        EvePackModels.InstalledPackRecord installed = cache.getInstalledPack(packId);
        if (installed != null && (packSummary == null || packSummary.version <= installed.version)) {
            dismiss();
            return;
        }

        downloading = true;
        downloadDone = 0;
        downloadTotal = packSummary != null ? Math.max(1, packSummary.itemCount) : 1;
        updateFooterState();

        EvePackRepository.getInstance().installOrUpdatePackAsync(getContext(), packId, new EvePackRepository.InstallProgressCallback() {
            @Override
            public void onProgress(int completed, int total, EvePackModels.PackItem item) {
                downloadDone = completed;
                downloadTotal = total;
                updateFooterState();
            }

            @Override
            public void onCompleted(EvePackModels.InstalledPackRecord record) {
                downloading = false;
                bindSummary(packSummary != null ? packSummary : record.toPackSummary());
                if (onStateChanged != null) {
                    onStateChanged.run();
                }
                if (parentFragment != null) {
                    BulletinFactory.of(parentFragment)
                            .createSimpleBulletin(R.raw.contact_check, record.title + " saved offline.")
                            .show();
                }
            }

            @Override
            public void onError(String message, int httpStatus, String serverCode) {
                downloading = false;
                updateFooterState();
                if ("EVE_PREMIUM_REQUIRED".equals(serverCode) || httpStatus == 403) {
                    dismiss();
                    if (parentFragment != null) {
                        parentFragment.presentFragment(new PremiumPreviewFragment("eve_pack_library"));
                    }
                    return;
                }
                statusNoteView.setText(message != null ? message : "Download failed. Tap Add to retry.");
            }
        });
    }

    private void handleRemovePack() {
        if (packId.isEmpty()) return;
        EvePackCacheManager.getInstance(getContext()).removePack(packId);
        bindSummary(packSummary);
        if (onStateChanged != null) {
            onStateChanged.run();
        }
        if (parentFragment != null) {
            BulletinFactory.of(parentFragment).createSimpleBulletin(R.raw.info, "Removed offline pack.").show();
        }
    }

    @Override
    public void dismiss() {
        for (EvePackAssetView v : activeAssetViews) {
            v.recycleDrawables();
        }
        activeAssetViews.clear();
        super.dismiss();
    }
}
