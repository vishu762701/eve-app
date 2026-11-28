package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.auth.EveBackendClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Edge-to-Edge Home Screen Banners Manager supporting Image/GIF/Video banners,
 * live thumbnails, active/hidden toggles, optional CTA links, reordering, and creation.
 */
public class EveAdminBannersFragment extends BaseFragment {

    private static final int MENU_ADD = 1;
    private static final int ID_RETRY = 101;
    private static final int ID_ADD_BANNER = 102;
    private static final int BASE_BANNER_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private List<EveAdminModels.BannerItem> banners = Collections.emptyList();
    private boolean loading = true;
    private boolean destroyed = false;
    private String errorText = "";
    private final EveAdminUiChrome.AutoSyncController autoSync = new EveAdminUiChrome.AutoSyncController(this::reload);

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

        List<EveAdminModels.BannerItem> cachedBanners = repo.getCachedBanners();
        if (cachedBanners != null && !cachedBanners.isEmpty()) {
            banners = cachedBanners;
            loading = false;
        }

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Home Banners",
                "Loading...",
                false,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_ADD) {
                            openBannerEditor();
                        }
                    }
                },
                null
        );
        listView = chrome.listView;
        updateSubtitle();

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            menu.addItem(MENU_ADD, R.drawable.msg_add, getResourceProvider());
        }

        fragmentView = chrome.rootView;
        reload();
        return chrome.rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        autoSync.onResume();
    }

    @Override
    public void onPause() {
        autoSync.onPause();
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        autoSync.destroy();
        super.onFragmentDestroy();
    }

    private void openBannerEditor() {
        presentFragment(new EveAdminBannerEditorFragment(this::reload));
    }

    private void reload() {
        loading = banners.isEmpty();
        errorText = "";
        if (loading) {
            updateList();
        }

        EveAdminRepository.ResultCallback<List<EveAdminModels.BannerItem>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.BannerItem>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.BannerItem> data) {
                        if (destroyed) return;
                        loading = false;
                        errorText = "";
                        autoSync.markSynced();
                        banners = data != null ? data : Collections.emptyList();
                        updateSubtitle();
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
                        autoSync.markSynced();
                        errorText = message != null ? message : "Unable to load banners.";
                        updateSubtitle();
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchBanners(token)));
    }

    private void updateSubtitle() {
        if (simpleActionBar == null) return;
        if (!errorText.isEmpty() && banners.isEmpty()) {
            simpleActionBar.setSubtitle("Unable to load");
        } else {
            int activeCount = 0;
            for (EveAdminModels.BannerItem b : banners) {
                if (b != null && b.active) activeCount++;
            }
            simpleActionBar.setSubtitle(activeCount + " active • " + banners.size() + " total");
        }
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_ADD_BANNER,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                R.drawable.msg_add,
                "+ Add Banner",
                "Upload photo, GIF, WebP or MP4/WebM video (link optional)",
                null));

        if (!errorText.isEmpty()) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_RETRY,
                    IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                    R.drawable.msg_retry,
                    "Retry Loading Banners",
                    errorText,
                    "Retry"));
        }

        if (loading && banners.isEmpty()) {
            for (int i = 0; i < 4; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (banners.isEmpty()) {
            items.add(UItem.asHeader("Home Screen Banners"));
            items.add(UItem.asShadow("No home screen banners configured. Tap + Add Banner above."));
            return;
        }

        items.add(UItem.asHeader("Configured Banners (" + banners.size() + ")"));
        for (int i = 0; i < banners.size(); i++) {
            items.add(BannerRowCell.Factory.of(BASE_BANNER_ID + i, banners.get(i), i));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
            return;
        }
        if (item.id == ID_ADD_BANNER) {
            openBannerEditor();
            return;
        }
        int idx = item.id - BASE_BANNER_ID;
        if (idx >= 0 && idx < banners.size()) {
            showBannerActions(banners.get(idx), idx);
        }
    }

    private void showBannerActions(EveAdminModels.BannerItem banner, int index) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        String toggleText = banner.active ? "Hide Banner from Home" : "Activate Banner on Home";
        String[] opts = new String[] {
                "Edit Optional Link & Label",
                toggleText,
                "Move Up",
                "Move Down",
                "Delete Banner"
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Banner #" + (index + 1) + (banner.isVideo() ? " (Video)" : " (Photo)"));
        builder.setItems(opts, (d, which) -> {
            if (which == 0) {
                promptEditLink(banner);
            } else if (which == 1) {
                toggleBannerStatus(banner);
            } else if (which == 2 || which == 3) {
                reorderBanner(banner, which == 2);
            } else {
                confirmDeleteBanner(banner);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void toggleBannerStatus(EveAdminModels.BannerItem banner) {
        boolean nextActive = !banner.active;
        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                if (destroyed) return;
                BulletinFactory.of(EveAdminBannersFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, nextActive ? "Banner activated." : "Banner hidden.").show();
                reload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                BulletinFactory.of(EveAdminBannersFragment.this)
                        .createErrorBulletin(message != null ? message : "Status update failed.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.setBannerActive(token, banner.id, nextActive);
            repo.postSuccess(cb, null);
        });
    }

    private void reorderBanner(EveAdminModels.BannerItem banner, boolean moveUp) {
        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                if (destroyed) return;
                BulletinFactory.of(EveAdminBannersFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, "Banner reordered").show();
                reload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                BulletinFactory.of(EveAdminBannersFragment.this)
                        .createErrorBulletin(message != null ? message : "Reorder failed.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.reorderBanner(token, banner.id, moveUp);
            repo.postSuccess(cb, null);
        });
    }

    private void promptEditLink(EveAdminModels.BannerItem banner) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        Theme.ResourcesProvider rp = getResourceProvider();

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(12), dp(22), dp(8));

        EditTextBoldCursor urlInput = new EditTextBoldCursor(activity);
        urlInput.setHint("https://... (Optional)");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(banner.linkUrl);
        urlInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        urlInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        urlInput.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp));
        box.addView(urlInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        EditTextBoldCursor labelInput = new EditTextBoldCursor(activity);
        labelInput.setHint("Caption / Label (Optional)");
        labelInput.setInputType(InputType.TYPE_CLASS_TEXT);
        labelInput.setText(banner.linkLabel);
        labelInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        labelInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        labelInput.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp));
        box.addView(labelInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, rp);
        builder.setTitle("Edit Optional Link & Label");
        builder.setView(box);
        builder.setPositiveButton("Save", (d, w) -> {
            String nextUrl = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
            String nextLabel = labelInput.getText() != null ? labelInput.getText().toString().trim() : "";
            EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
                @Override
                public void onSuccess(Void data) {
                    if (destroyed) return;
                    BulletinFactory.of(EveAdminBannersFragment.this)
                            .createSimpleBulletin(R.raw.contact_check, "Banner updated").show();
                    reload();
                }

                @Override
                public void onError(String message, int httpStatus) {
                    if (destroyed) return;
                    BulletinFactory.of(EveAdminBannersFragment.this)
                            .createErrorBulletin(message != null ? message : "Update failed.").show();
                }
            };
            repo.runWithAdminToken(cb, token -> {
                repo.updateBannerLink(token, banner.id, nextUrl, nextLabel);
                repo.postSuccess(cb, null);
            });
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void confirmDeleteBanner(EveAdminModels.BannerItem banner) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Delete Banner?");
        builder.setMessage("Remove this banner from Home screen carousel and storage?");
        builder.setPositiveButton("Delete", (d, w) -> {
            EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
                @Override
                public void onSuccess(Void data) {
                    if (destroyed) return;
                    BulletinFactory.of(EveAdminBannersFragment.this)
                            .createSimpleBulletin(R.raw.ic_delete, "Banner deleted").show();
                    reload();
                }

                @Override
                public void onError(String message, int httpStatus) {
                    if (destroyed) return;
                    BulletinFactory.of(EveAdminBannersFragment.this)
                            .createErrorBulletin(message != null ? message : "Delete failed.").show();
                }
            };
            repo.runWithAdminToken(cb, token -> {
                repo.deleteBanner(token, banner.id);
                repo.postSuccess(cb, null);
            });
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.makeRed(AlertDialog.BUTTON_POSITIVE);
        showDialog(builder.create());
    }

    private static final class BannerRowCell extends FrameLayout implements Theme.Colorable {

        private final Theme.ResourcesProvider rp;
        private final BackupImageView thumbView;
        private final TextView titleView;
        private final TextView subView;
        private final TextView badgeView;
        private boolean isVideo;
        private boolean isActive;

        BannerRowCell(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.rp = rp;

            thumbView = new BackupImageView(context);
            thumbView.setRoundRadius(dp(10));

            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            subView = new TextView(context);
            subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subView.setSingleLine(true);
            subView.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(subView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            badgeView = new TextView(context);
            badgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            badgeView.setTypeface(AndroidUtilities.bold());
            badgeView.setPadding(dp(8), dp(3), dp(8), dp(3));

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(thumbView, LayoutHelper.createLinear(68, 44, Gravity.CENTER_VERTICAL, 16, 0, 12, 0));
            row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
            row.addView(badgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 16, 0));

            addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 10, 0, 10));
            updateColors();
        }

        void bind(EveAdminModels.BannerItem b, int index) {
            this.isVideo = b.isVideo();
            this.isActive = b.active;

            String mediaBadge = isVideo ? "Video" : "Photo";
            String title = !TextUtils.isEmpty(b.linkLabel) ? b.linkLabel : ("Banner #" + (index + 1));
            titleView.setText(title);

            String linkTag = !TextUtils.isEmpty(b.linkUrl) ? ("Linked • " + b.linkUrl) : "Display only (no link)";
            subView.setText(mediaBadge + " • " + linkTag);

            badgeView.setText(b.active ? ("#" + (index + 1) + " • Active") : "Hidden");

            String resolvedUrl = EveBackendClient.resolveMediaUrl(b.imageUrl);
            if (!isVideo && !resolvedUrl.isEmpty()) {
                thumbView.setImage(ImageLocation.getForPath(resolvedUrl), "100_100", (android.graphics.drawable.Drawable) null, null);
            } else {
                thumbView.setImageBitmap(null);
            }
            updateColors();
        }

        @Override
        public void updateColors() {
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
            int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp);
            subView.setTextColor(gray);
            thumbView.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(gray, 0.14f)));

            int badgeColor = isActive ? 0xFF27B434 : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp);
            badgeView.setTextColor(badgeColor);
            badgeView.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(badgeColor, 0.13f)));
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_windowBackgroundWhiteBlackText);
            colors.add(Theme.key_windowBackgroundWhiteGrayText);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(64), MeasureSpec.EXACTLY));
        }

        static final class Factory extends UItem.UItemFactory<BannerRowCell> {
            static { setup(new Factory()); }

            @Override
            public BannerRowCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new BannerRowCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                if (item.object instanceof EveAdminModels.BannerItem) {
                    ((BannerRowCell) view).bind((EveAdminModels.BannerItem) item.object, item.intValue);
                }
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                if (a.object instanceof EveAdminModels.BannerItem && b.object instanceof EveAdminModels.BannerItem) {
                    return TextUtils.equals(((EveAdminModels.BannerItem) a.object).id,
                            ((EveAdminModels.BannerItem) b.object).id);
                }
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                if (a.object instanceof EveAdminModels.BannerItem && b.object instanceof EveAdminModels.BannerItem) {
                    EveAdminModels.BannerItem ba = (EveAdminModels.BannerItem) a.object;
                    EveAdminModels.BannerItem bb = (EveAdminModels.BannerItem) b.object;
                    return ba.active == bb.active
                            && ba.sortOrder == bb.sortOrder
                            && Objects.equals(ba.mediaType, bb.mediaType)
                            && TextUtils.equals(ba.imageUrl, bb.imageUrl)
                            && TextUtils.equals(ba.linkUrl, bb.linkUrl)
                            && TextUtils.equals(ba.linkLabel, bb.linkLabel);
                }
                return Objects.equals(a.object, b.object);
            }

            static UItem of(int id, EveAdminModels.BannerItem banner, int index) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.object = banner;
                item.intValue = index;
                return item;
            }
        }
    }
}
