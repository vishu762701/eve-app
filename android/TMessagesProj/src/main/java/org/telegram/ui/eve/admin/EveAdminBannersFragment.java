package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-to-Edge Home Screen Banners manager replacing AlertDialog banner pickers.
 */
public class EveAdminBannersFragment extends BaseFragment {

    private static final int MENU_REFRESH = 1;
    private static final int ID_RETRY = 101;
    private static final int BASE_BANNER_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private List<EveAdminModels.BannerItem> banners = Collections.emptyList();
    private boolean loading = true;
    private boolean destroyed = false;
    private String errorText = "";

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
                        } else if (id == MENU_REFRESH) {
                            reload();
                        }
                    }
                },
                null
        );
        listView = chrome.listView;

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            menu.addItem(MENU_REFRESH, R.drawable.msg_retry, getResourceProvider());
        }

        fragmentView = chrome.rootView;
        reload();
        return chrome.rootView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }

    private void reload() {
        loading = true;
        errorText = "";
        updateList();

        EveAdminRepository.ResultCallback<List<EveAdminModels.BannerItem>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.BannerItem>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.BannerItem> data) {
                        if (destroyed) return;
                        loading = false;
                        errorText = "";
                        banners = data != null ? data : Collections.emptyList();
                        updateSubtitle();
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
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
            simpleActionBar.setSubtitle(banners.size() + " banner(s)");
        }
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

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
            items.add(UItem.asShadow("No home screen banners configured."));
            return;
        }

        for (int i = 0; i < banners.size(); i++) {
            EveAdminModels.BannerItem b = banners.get(i);
            String title = !TextUtils.isEmpty(b.linkLabel) ? b.linkLabel : ("Banner " + (i + 1));
            String sub = !TextUtils.isEmpty(b.linkUrl) ? b.linkUrl : "No click URL configured";
            items.add(SettingsActivity.SettingCell.Factory.of(
                    BASE_BANNER_ID + i,
                    IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                    R.drawable.msg_photos,
                    title,
                    sub,
                    "#" + (i + 1)));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
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
        String[] opts = new String[] { "Edit Link & Label", "Move Up", "Move Down", "Delete Banner" };
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Banner #" + (index + 1));
        builder.setItems(opts, (d, which) -> {
            if (which == 0) {
                promptEditLink(banner);
            } else if (which == 1 || which == 2) {
                boolean moveUp = which == 1;
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
            } else {
                confirmDeleteBanner(banner);
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void promptEditLink(EveAdminModels.BannerItem banner) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        Theme.ResourcesProvider rp = getResourceProvider();

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(12), dp(22), dp(8));

        EditText urlInput = new EditText(activity);
        urlInput.setHint("https://...");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(banner.linkUrl);
        urlInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        urlInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        box.addView(urlInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        EditText labelInput = new EditText(activity);
        labelInput.setHint("Display Label");
        labelInput.setInputType(InputType.TYPE_CLASS_TEXT);
        labelInput.setText(banner.linkLabel);
        labelInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        labelInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        box.addView(labelInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, rp);
        builder.setTitle("Edit Banner Link");
        builder.setView(box);
        builder.setPositiveButton("Save", (d, w) -> {
            String url = urlInput.getText().toString().trim();
            String label = labelInput.getText().toString().trim();
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
                repo.updateBannerLink(token, banner.id, url, label);
                repo.postSuccess(cb, null);
            });
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void confirmDeleteBanner(EveAdminModels.BannerItem banner) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Delete Banner");
        builder.setMessage("Remove this banner from the home screen?");
        builder.setPositiveButton("Delete", (d, w) -> {
            EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
                @Override
                public void onSuccess(Void data) {
                    if (destroyed) return;
                    BulletinFactory.of(EveAdminBannersFragment.this)
                            .createSimpleBulletin(R.raw.info, "Banner deleted").show();
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
        builder.setNegativeButton("Cancel", null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (pos instanceof TextView) {
            ((TextView) pos).setTextColor(Theme.getColor(Theme.key_text_RedBold, getResourceProvider()));
        }
    }
}
