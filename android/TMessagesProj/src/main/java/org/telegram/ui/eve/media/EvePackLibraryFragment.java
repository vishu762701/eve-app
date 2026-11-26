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
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.PremiumPreviewFragment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Telegram-native Emoji & Sticker Pack Library for EVE.
 * Features compact Stickers / Emoji Packs / Installed tabs, ActionBar search, category & featured filters,
 * animated preview strips, offline download/update/remove, and EVE Premium unlock flow.
 */
public class EvePackLibraryFragment extends BaseFragment {

    private static final int TAB_STICKERS = 0;
    private static final int TAB_EMOJIS = 1;
    private static final int TAB_INSTALLED = 2;

    private static final int MENU_SEARCH = 1;
    private static final int MENU_REFRESH = 2;

    private int activeTab = TAB_STICKERS;
    private String searchQuery = "";
    private String selectedCategory = "All";
    private boolean featuredOnly = false;

    private boolean loading = true;
    private String errorMessage = "";
    private final List<EvePackModels.PackSummary> cloudPacks = new ArrayList<>();
    private final List<EvePackModels.PackSummary> visiblePacks = new ArrayList<>();
    private final Map<String, String> downloadProgressByPack = new ConcurrentHashMap<>();

    private LinearLayout tabsBar;
    private TextView stickersTabView;
    private TextView emojiTabView;
    private TextView installedTabView;
    private LinearLayout categoriesRow;
    private RecyclerListView listView;
    private PackAdapter adapter;
    private LinearLayout statusContainer;
    private TextView statusTitleView;
    private TextView statusBodyView;
    private TextView retryButtonView;

    public EvePackLibraryFragment() {
        this(TAB_STICKERS);
    }

    public EvePackLibraryFragment(int initialTab) {
        this.activeTab = Math.max(0, Math.min(2, initialTab));
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.EveEmojiStickerPacks));

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem searchItem = menu.addItem(MENU_SEARCH, R.drawable.msg_search)
                .setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchExpand() {
                    }

                    @Override
                    public void onSearchCollapse() {
                        searchQuery = "";
                        loadCloudCatalogue();
                    }

                    @Override
                    public void onTextChanged(EditText editText) {
                        searchQuery = editText.getText() != null ? editText.getText().toString().trim() : "";
                        rebuildVisibleList();
                    }
                });
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search));
        menu.addItem(MENU_REFRESH, R.drawable.msg_retry);

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_REFRESH) {
                    loadCloudCatalogue();
                }
            }
        });

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        // Tab Bar (Stickers | Emoji Packs | Installed)
        tabsBar = new LinearLayout(context);
        tabsBar.setOrientation(LinearLayout.HORIZONTAL);
        tabsBar.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        tabsBar.setPadding(dp(12), dp(6), dp(12), dp(6));

        stickersTabView = buildTabView(context, LocaleController.getString(R.string.EveStickerPacksTab), TAB_STICKERS);
        emojiTabView = buildTabView(context, LocaleController.getString(R.string.EveEmojiPacksTab), TAB_EMOJIS);
        installedTabView = buildTabView(context, LocaleController.getString(R.string.EveInstalledPacksTab), TAB_INSTALLED);

        tabsBar.addView(stickersTabView, LayoutHelper.createLinear(0, dp(34), 1f, 0, 0, 6, 0));
        tabsBar.addView(emojiTabView, LayoutHelper.createLinear(0, dp(34), 1f, 0, 0, 6, 0));
        tabsBar.addView(installedTabView, LayoutHelper.createLinear(0, dp(34), 1f));
        root.addView(tabsBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Category & Featured Filter Strip
        HorizontalScrollView catScroll = new HorizontalScrollView(context);
        catScroll.setHorizontalScrollBarEnabled(false);
        catScroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        categoriesRow = new LinearLayout(context);
        categoriesRow.setOrientation(LinearLayout.HORIZONTAL);
        categoriesRow.setPadding(dp(12), dp(4), dp(12), dp(8));
        catScroll.addView(categoriesRow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        root.addView(catScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        View divider = new View(context);
        divider.setBackgroundColor(getThemedColor(Theme.key_divider));
        root.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        // Content Frame (List + Empty/Error State)
        FrameLayout contentFrame = new FrameLayout(context);
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        adapter = new PackAdapter();
        listView.setAdapter(adapter);
        contentFrame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        statusContainer = new LinearLayout(context);
        statusContainer.setOrientation(LinearLayout.VERTICAL);
        statusContainer.setGravity(Gravity.CENTER);
        statusContainer.setPadding(dp(32), dp(32), dp(32), dp(32));
        statusContainer.setVisibility(View.GONE);

        statusTitleView = new TextView(context);
        statusTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        statusTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        statusTitleView.setGravity(Gravity.CENTER);
        statusTitleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        statusContainer.addView(statusTitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        statusBodyView = new TextView(context);
        statusBodyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusBodyView.setGravity(Gravity.CENTER);
        statusBodyView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        statusBodyView.setPadding(0, dp(8), 0, dp(16));
        statusContainer.addView(statusBodyView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        retryButtonView = new TextView(context);
        retryButtonView.setText(LocaleController.getString(R.string.Retry));
        retryButtonView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        retryButtonView.setTypeface(Typeface.DEFAULT_BOLD);
        retryButtonView.setTextColor(Color.WHITE);
        retryButtonView.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable retryBg = new GradientDrawable();
        retryBg.setCornerRadius(dp(10));
        retryBg.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
        retryButtonView.setBackground(retryBg);
        retryButtonView.setOnClickListener(v -> loadCloudCatalogue());
        statusContainer.addView(retryButtonView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        contentFrame.addView(statusContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(contentFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        fragmentView = root;
        updateTabStyles();
        rebuildCategoryChips();
        loadCloudCatalogue();
        return root;
    }

    private TextView buildTabView(Context context, String label, int tabId) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setOnClickListener(v -> {
            if (activeTab != tabId) {
                activeTab = tabId;
                updateTabStyles();
                if (tabId == TAB_INSTALLED) {
                    rebuildVisibleList();
                } else {
                    loadCloudCatalogue();
                }
            }
        });
        return tv;
    }

    private void updateTabStyles() {
        int installedCount = EvePackCacheManager.getInstance(getContext()).getInstalledPacks().size();
        installedTabView.setText(LocaleController.getString(R.string.EveInstalledPacksTab)
                + (installedCount > 0 ? " (" + installedCount + ")" : ""));

        styleTab(stickersTabView, activeTab == TAB_STICKERS);
        styleTab(emojiTabView, activeTab == TAB_EMOJIS);
        styleTab(installedTabView, activeTab == TAB_INSTALLED);
    }

    private void styleTab(TextView tv, boolean selected) {
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        if (selected) {
            bg.setColor(accent);
            tv.setTextColor(Color.WHITE);
        } else {
            bg.setColor(getThemedColor(Theme.key_windowBackgroundGray));
            tv.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        }
        tv.setBackground(bg);
    }

    private void rebuildCategoryChips() {
        Context ctx = getContext();
        if (ctx == null || categoriesRow == null) return;
        categoriesRow.removeAllViews();

        Set<String> cats = new LinkedHashSet<>();
        cats.add("All");
        cats.add("Featured");
        for (EvePackModels.PackSummary p : cloudPacks) {
            if (!p.category.isEmpty()) {
                cats.add(p.category);
            }
        }
        cats.add("Study");
        cats.add("General");

        for (String cat : cats) {
            boolean isSelected = ("Featured".equals(cat) && featuredOnly)
                    || (!featuredOnly && cat.equalsIgnoreCase(selectedCategory));
            TextView chip = new TextView(ctx);
            chip.setText(cat);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            chip.setTypeface(isSelected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            chip.setPadding(dp(12), dp(5), dp(12), dp(5));

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            if (isSelected) {
                int accent = getThemedColor(Theme.key_windowBackgroundWhiteBlueText);
                bg.setColor(accent & 0x24FFFFFF);
                chip.setTextColor(accent);
            } else {
                bg.setColor(getThemedColor(Theme.key_windowBackgroundGray));
                chip.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            }
            chip.setBackground(bg);
            chip.setOnClickListener(v -> {
                if ("Featured".equals(cat)) {
                    featuredOnly = !featuredOnly;
                    selectedCategory = featuredOnly ? "Featured" : "All";
                } else {
                    featuredOnly = false;
                    selectedCategory = cat;
                }
                rebuildCategoryChips();
                rebuildVisibleList();
            });
            categoriesRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0));
        }
    }

    private void loadCloudCatalogue() {
        if (activeTab == TAB_INSTALLED) {
            loading = false;
            errorMessage = "";
            rebuildVisibleList();
            return;
        }
        loading = true;
        errorMessage = "";
        updateStatusOverlay();

        String type = activeTab == TAB_STICKERS ? "sticker" : "emoji";
        EvePackRepository.getInstance().loadPacksAsync(type, searchQuery, null, false,
                new EvePackRepository.Callback<List<EvePackModels.PackSummary>>() {
                    @Override
                    public void onSuccess(List<EvePackModels.PackSummary> data) {
                        loading = false;
                        cloudPacks.clear();
                        if (data != null) {
                            cloudPacks.addAll(data);
                        }
                        rebuildCategoryChips();
                        rebuildVisibleList();
                    }

                    @Override
                    public void onError(String message, int httpStatus, String serverCode) {
                        loading = false;
                        errorMessage = message != null && !message.isEmpty() ? message : "Unable to load packs from EVE Cloud.";
                        rebuildVisibleList();
                    }
                });
    }

    private void rebuildVisibleList() {
        visiblePacks.clear();
        String q = searchQuery.toLowerCase(Locale.US);

        if (activeTab == TAB_INSTALLED) {
            List<EvePackModels.InstalledPackRecord> installed = EvePackCacheManager.getInstance(getContext()).getInstalledPacks();
            for (EvePackModels.InstalledPackRecord rec : installed) {
                EvePackModels.PackSummary summary = rec.toPackSummary();
                if (!q.isEmpty() && !summary.title.toLowerCase(Locale.US).contains(q) && !summary.name.toLowerCase(Locale.US).contains(q)) {
                    continue;
                }
                visiblePacks.add(summary);
            }
        } else {
            for (EvePackModels.PackSummary p : cloudPacks) {
                if (featuredOnly && !p.featured) continue;
                if (!featuredOnly && !"All".equalsIgnoreCase(selectedCategory) && !selectedCategory.equalsIgnoreCase(p.category)) {
                    continue;
                }
                if (!q.isEmpty()
                        && !p.title.toLowerCase(Locale.US).contains(q)
                        && !p.name.toLowerCase(Locale.US).contains(q)
                        && !p.category.toLowerCase(Locale.US).contains(q)) {
                    continue;
                }
                visiblePacks.add(p);
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateTabStyles();
        updateStatusOverlay();
    }

    private void updateStatusOverlay() {
        if (statusContainer == null) return;
        if (!visiblePacks.isEmpty()) {
            statusContainer.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
            return;
        }
        listView.setVisibility(View.GONE);
        statusContainer.setVisibility(View.VISIBLE);
        if (loading) {
            statusTitleView.setText("Loading EVE Packs...");
            statusBodyView.setText("Fetching official sticker & emoji packs from EVE Cloud.");
            retryButtonView.setVisibility(View.GONE);
        } else if (!errorMessage.isEmpty() && activeTab != TAB_INSTALLED) {
            statusTitleView.setText("Could Not Load Packs");
            statusBodyView.setText(errorMessage);
            retryButtonView.setVisibility(View.VISIBLE);
        } else if (activeTab == TAB_INSTALLED) {
            statusTitleView.setText("No Installed Packs Yet");
            statusBodyView.setText("Browse the Stickers or Emoji Packs tab and tap Add to save packs for offline use.");
            retryButtonView.setVisibility(View.GONE);
        } else {
            statusTitleView.setText("No Packs Found");
            statusBodyView.setText(searchQuery.isEmpty()
                    ? "No published packs in this category yet."
                    : "Try a different search keyword or reset filters.");
            retryButtonView.setVisibility(View.VISIBLE);
        }
    }

    private void handlePackActionClick(EvePackModels.PackSummary pack) {
        if (pack == null) return;
        if (downloadProgressByPack.containsKey(pack.id)) {
            return;
        }
        if (pack.isLockedForUser) {
            presentFragment(new PremiumPreviewFragment("eve_pack_library"));
            return;
        }
        EvePackCacheManager cache = EvePackCacheManager.getInstance(getContext());
        EvePackModels.InstalledPackRecord installed = cache.getInstalledPack(pack.id);
        if (installed != null && pack.version <= installed.version) {
            openPackSheet(pack);
            return;
        }

        downloadProgressByPack.put(pack.id, "0/" + Math.max(1, pack.itemCount));
        if (adapter != null) adapter.notifyDataSetChanged();

        EvePackRepository.getInstance().installOrUpdatePackAsync(getContext(), pack.id, new EvePackRepository.InstallProgressCallback() {
            @Override
            public void onProgress(int completed, int total, EvePackModels.PackItem item) {
                downloadProgressByPack.put(pack.id, completed + "/" + total);
                if (adapter != null) adapter.notifyDataSetChanged();
            }

            @Override
            public void onCompleted(EvePackModels.InstalledPackRecord record) {
                downloadProgressByPack.remove(pack.id);
                updateTabStyles();
                if (adapter != null) adapter.notifyDataSetChanged();
                BulletinFactory.of(EvePackLibraryFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, record.title + " installed (" + record.itemCount + ").")
                        .show();
            }

            @Override
            public void onError(String message, int httpStatus, String serverCode) {
                downloadProgressByPack.remove(pack.id);
                if (adapter != null) adapter.notifyDataSetChanged();
                if ("EVE_PREMIUM_REQUIRED".equals(serverCode) || httpStatus == 403) {
                    presentFragment(new PremiumPreviewFragment("eve_pack_library"));
                    return;
                }
                BulletinFactory.of(EvePackLibraryFragment.this)
                        .createErrorBulletin(message != null ? message : "Pack download failed.")
                        .show();
            }
        });
    }

    private void openPackSheet(EvePackModels.PackSummary pack) {
        if (getContext() == null) return;
        new EvePackDetailBottomSheet(this, pack, () -> {
            rebuildVisibleList();
        }).show();
    }

    private class PackAdapter extends RecyclerView.Adapter<PackViewHolder> {
        @NonNull
        @Override
        public PackViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new PackViewHolder(new PackRowView(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(@NonNull PackViewHolder holder, int position) {
            holder.rowView.bind(visiblePacks.get(position));
        }

        @Override
        public int getItemCount() {
            return visiblePacks.size();
        }
    }

    private static class PackViewHolder extends RecyclerView.ViewHolder {
        final PackRowView rowView;

        PackViewHolder(PackRowView itemView) {
            super(itemView);
            this.rowView = itemView;
        }
    }

    private class PackRowView extends LinearLayout {
        private final EvePackAssetView thumbView;
        private final TextView titleView;
        private final TextView subtitleView;
        private final LinearLayout previewStrip;
        private final TextView actionPill;
        private final List<EvePackAssetView> stripViews = new ArrayList<>();

        PackRowView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

            LinearLayout top = new LinearLayout(context);
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setPadding(dp(14), dp(12), dp(14), dp(10));

            thumbView = new EvePackAssetView(context, 46);
            top.addView(thumbView, LayoutHelper.createLinear(46, 46, 0, 0, 12, 0));

            LinearLayout metaCol = new LinearLayout(context);
            metaCol.setOrientation(VERTICAL);

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            metaCol.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            subtitleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            subtitleView.setPadding(0, dp(2), 0, 0);
            metaCol.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            previewStrip = new LinearLayout(context);
            previewStrip.setOrientation(HORIZONTAL);
            previewStrip.setPadding(0, dp(8), 0, 0);
            for (int i = 0; i < 5; i++) {
                EvePackAssetView pv = new EvePackAssetView(context, 32);
                stripViews.add(pv);
                previewStrip.addView(pv, LayoutHelper.createLinear(32, 32, 0, 0, 8, 0));
            }
            metaCol.addView(previewStrip, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            top.addView(metaCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            actionPill = new TextView(context);
            actionPill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            actionPill.setTypeface(Typeface.DEFAULT_BOLD);
            actionPill.setGravity(Gravity.CENTER);
            actionPill.setPadding(dp(14), dp(7), dp(14), dp(7));
            actionPill.setMinWidth(dp(74));
            top.addView(actionPill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 10, 0, 0, 0));

            addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            View sep = new View(context);
            sep.setBackgroundColor(getThemedColor(Theme.key_divider));
            addView(sep, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 72, 0, 0, 0));
        }

        void bind(EvePackModels.PackSummary pack) {
            String titlePrefix = pack.featured ? "★ " : "";
            titleView.setText(titlePrefix + pack.title);
            subtitleView.setText(pack.countLabel() + " • " + pack.category + (pack.isPremium() ? " • EVE Premium" : ""));

            List<EvePackModels.PackItem> previews = pack.displayPreviewItems();
            if (!previews.isEmpty()) {
                thumbView.setItem(previews.get(0));
            } else {
                thumbView.setItem(null);
            }

            for (int i = 0; i < stripViews.size(); i++) {
                EvePackAssetView pv = stripViews.get(i);
                if (i < previews.size()) {
                    pv.setVisibility(VISIBLE);
                    pv.setItem(previews.get(i));
                } else {
                    pv.setVisibility(GONE);
                    pv.setItem(null);
                }
            }

            GradientDrawable pillBg = new GradientDrawable();
            pillBg.setCornerRadius(dp(16));
            String progress = downloadProgressByPack.get(pack.id);
            EvePackCacheManager cache = EvePackCacheManager.getInstance(getContext());
            EvePackModels.InstalledPackRecord installed = cache.getInstalledPack(pack.id);

            if (progress != null) {
                pillBg.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
                actionPill.setText(progress);
                actionPill.setTextColor(Color.WHITE);
            } else if (pack.isLockedForUser) {
                pillBg.setColor(0xFF7C4DFF);
                actionPill.setText("🔒 " + LocaleController.getString(R.string.EveUnlockPack));
                actionPill.setTextColor(Color.WHITE);
            } else if (installed != null && pack.version > installed.version) {
                pillBg.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
                actionPill.setText(LocaleController.getString(R.string.EveUpdatePack));
                actionPill.setTextColor(Color.WHITE);
            } else if (installed != null) {
                int addedColor = getThemedColor(Theme.key_windowBackgroundWhiteBlueText);
                pillBg.setColor(addedColor & 0x1AFFFFFF);
                actionPill.setText("✓ " + LocaleController.getString(R.string.EveInstalledPack));
                actionPill.setTextColor(addedColor);
            } else {
                pillBg.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
                actionPill.setText(LocaleController.getString(R.string.EveAddPack));
                actionPill.setTextColor(Color.WHITE);
            }
            actionPill.setBackground(pillBg);

            setOnClickListener(v -> openPackSheet(pack));
            actionPill.setOnClickListener(v -> handlePackActionClick(pack));
        }
    }
}
