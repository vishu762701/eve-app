package org.telegram.ui.eve.exams;

import android.content.Context;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.SimpleActionBarMenuItem;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.admin.EveAdminUiChrome;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Edge-to-Edge Telegram-native Result History screen with accuracy color badges,
 * live search, and tap-to-review detail navigation.
 */
public class EveResultHistoryFragment extends BaseFragment {

    private static final int MENU_SEARCH = 1;
    private static final int MENU_REFRESH = 2;

    private static final int ID_RETRY = 100;
    private static final int BASE_ATTEMPT_ID = 1000;

    private UniversalRecyclerView listView;
    private SimpleActionBarMenuItem searchItem;
    private List<EveExamModels.AttemptResult> attempts = Collections.emptyList();
    private final List<EveExamModels.AttemptResult> filteredAttempts = new ArrayList<>();
    private boolean loading = true;
    private boolean destroyed = false;
    private String errorMessage = "";
    private String searchQuery = "";

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
    public boolean onBackPressed(boolean invoked) {
        if (simpleActionBar != null && simpleActionBar.isSearchFieldVisible()) {
            if (invoked) {
                simpleActionBar.closeSearchField();
            }
            return false;
        }
        return super.onBackPressed(invoked);
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
                LocaleController.getString(R.string.EveResultHistory),
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
                            loadHistory();
                        }
                    }
                },
                null
        );
        listView = chrome.listView;

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            searchItem = menu.addItem(MENU_SEARCH, R.drawable.outline_header_search, getResourceProvider());
            searchItem.setIsSearchField(true)
                    .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                        @Override
                        public void onSearchExpand() {
                            applyFilter("");
                        }

                        @Override
                        public void onSearchCollapse() {
                            applyFilter("");
                        }

                        @Override
                        public void onTextChanged(EditText editText) {
                            String next = editText.getText() != null ? editText.getText().toString() : "";
                            applyFilter(next);
                        }
                    });
            searchItem.setSearchFieldHint("Search exam history");
            menu.addItem(MENU_REFRESH, R.drawable.msg_retry, getResourceProvider());
        }

        fragmentView = chrome.rootView;
        loadHistory();
        return chrome.rootView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }

    private void loadHistory() {
        loading = true;
        errorMessage = "";
        updateList();
        EveExamRepository.getInstance().loadAttemptHistory((list, err) -> {
            if (destroyed) return;
            loading = false;
            attempts = list != null ? list : Collections.emptyList();
            errorMessage = err != null ? err : "";
            applyFilter(searchQuery);
        });
    }

    private void applyFilter(String query) {
        searchQuery = query != null ? query.trim() : "";
        filteredAttempts.clear();
        String q = searchQuery.toLowerCase(Locale.US);
        for (EveExamModels.AttemptResult item : attempts) {
            if (q.isEmpty()
                    || item.examName.toLowerCase(Locale.US).contains(q)
                    || item.category.toLowerCase(Locale.US).contains(q)) {
                filteredAttempts.add(item);
            }
        }
        updateSubtitle();
        updateList();
    }

    private void updateSubtitle() {
        if (simpleActionBar == null) return;
        if (!errorMessage.isEmpty() && attempts.isEmpty()) {
            simpleActionBar.setSubtitle("Unable to load");
        } else if (!searchQuery.isEmpty()) {
            simpleActionBar.setSubtitle(filteredAttempts.size() + " matching");
        } else {
            simpleActionBar.setSubtitle(attempts.size() + " attempt(s)");
        }
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        if (loading && attempts.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }
        if (!errorMessage.isEmpty()) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_RETRY,
                    IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                    R.drawable.msg_retry,
                    "Retry Loading History",
                    errorMessage,
                    "Retry"));
            return;
        }
        if (filteredAttempts.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.EveResultHistory)));
            items.add(UItem.asShadow(searchQuery.isEmpty()
                    ? LocaleController.getString(R.string.EveNoHistory)
                    : "No matching attempt history found."));
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault());
        for (int i = 0; i < filteredAttempts.size(); i++) {
            EveExamModels.AttemptResult item = filteredAttempts.get(i);
            int acc = item.getAccuracyPercent();
            IconBackgroundColors badge = acc >= 70
                    ? IconBackgroundColors.GREEN
                    : (acc >= 40 ? IconBackgroundColors.ORANGE : IconBackgroundColors.RED);

            String dateStr = item.timestampMs > 0 ? fmt.format(new Date(item.timestampMs)) : item.category;
            String rightValue = formatScore(item.score) + "/" + item.total + " (" + acc + "%)";

            items.add(SettingsActivity.SettingCell.Factory.of(
                    BASE_ATTEMPT_ID + i,
                    badge.top, badge.bottom,
                    R.drawable.msg_stats,
                    item.examName,
                    dateStr,
                    rightValue));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            loadHistory();
            return;
        }
        int idx = item.id - BASE_ATTEMPT_ID;
        if (idx >= 0 && idx < filteredAttempts.size()) {
            EveExamModels.AttemptResult attempt = filteredAttempts.get(idx);
            presentFragment(new EveTestResultFragment(null, null, attempt.examId, attempt));
        }
    }

    private static String formatScore(double score) {
        if (Math.abs(score - Math.rint(score)) < 0.001) {
            return String.valueOf((long) Math.rint(score));
        }
        return String.format(Locale.US, "%.1f", score);
    }
}
