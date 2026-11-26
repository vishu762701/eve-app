package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Edge-to-Edge Audit Log directory replacing AlertDialog audit viewers.
 */
public class EveAdminAuditLogFragment extends BaseFragment {

    private static final int MENU_SEARCH = 1;
    private static final int MENU_REFRESH = 2;

    private static final int ID_RETRY = 101;
    private static final int BASE_LOG_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private ActionBarMenuItem searchItem;
    private boolean loading = true;
    private boolean destroyed = false;
    private String errorText = "";
    private String searchQuery = "";
    private List<EveAdminModels.AuditLogEntry> entries = Collections.emptyList();
    private final List<EveAdminModels.AuditLogEntry> filteredEntries = new ArrayList<>();

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
                "Audit Log",
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
            searchItem = menu.addItem(MENU_SEARCH, R.drawable.outline_header_search, getResourceProvider())
                    .setIsSearchField(true)
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
            searchItem.setSearchFieldHint("Search action, description, or admin");
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

        EveAdminRepository.ResultCallback<List<EveAdminModels.AuditLogEntry>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.AuditLogEntry>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.AuditLogEntry> data) {
                        if (destroyed) return;
                        loading = false;
                        errorText = "";
                        entries = data != null ? data : Collections.emptyList();
                        applyFilter(searchQuery);
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
                        errorText = message != null ? message : "Unable to load audit log.";
                        updateSubtitle();
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchAuditLogs(token, 120)));
    }

    private void applyFilter(String query) {
        searchQuery = query != null ? query.trim() : "";
        filteredEntries.clear();
        String q = searchQuery.toLowerCase(Locale.US);
        for (EveAdminModels.AuditLogEntry e : entries) {
            if (q.isEmpty()
                    || e.actionType.toLowerCase(Locale.US).contains(q)
                    || e.description.toLowerCase(Locale.US).contains(q)
                    || e.adminEmail.toLowerCase(Locale.US).contains(q)) {
                filteredEntries.add(e);
            }
        }
        updateSubtitle();
        updateList();
    }

    private void updateSubtitle() {
        if (simpleActionBar == null) return;
        if (!errorText.isEmpty() && entries.isEmpty()) {
            simpleActionBar.setSubtitle("Unable to load");
        } else if (!searchQuery.isEmpty()) {
            simpleActionBar.setSubtitle(filteredEntries.size() + " matching");
        } else {
            simpleActionBar.setSubtitle(entries.size() + " entries");
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
                    "Retry Loading Audit Log",
                    errorText,
                    "Retry"));
        }

        if (loading && entries.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (filteredEntries.isEmpty()) {
            items.add(UItem.asHeader("Audit Log"));
            items.add(UItem.asShadow("No audit entries found."));
            return;
        }

        for (int i = 0; i < filteredEntries.size(); i++) {
            EveAdminModels.AuditLogEntry entry = filteredEntries.get(i);
            String upper = entry.actionType.toUpperCase(Locale.US);
            int topColor;
            int bottomColor;
            int iconRes;
            if (upper.contains("DELETE") || upper.contains("REMOVE") || upper.contains("REVOKE") || upper.contains("DISABLE")) {
                topColor = IconBackgroundColors.RED.top;
                bottomColor = IconBackgroundColors.RED.bottom;
                iconRes = R.drawable.msg_delete;
            } else if (upper.contains("CREATE") || upper.contains("ADD") || upper.contains("GRANT") || upper.contains("PUBLISH")) {
                topColor = IconBackgroundColors.GREEN.top;
                bottomColor = IconBackgroundColors.GREEN.bottom;
                iconRes = R.drawable.msg_add;
            } else if (upper.contains("UPDATE") || upper.contains("EDIT") || upper.contains("CONFIG") || upper.contains("SAVE")) {
                topColor = IconBackgroundColors.BLUE.top;
                bottomColor = IconBackgroundColors.BLUE.bottom;
                iconRes = R.drawable.msg_edit;
            } else {
                topColor = IconBackgroundColors.ORANGE.top;
                bottomColor = IconBackgroundColors.ORANGE.bottom;
                iconRes = R.drawable.msg_log;
            }

            String sub = (!TextUtils.isEmpty(entry.description) ? entry.description + " • " : "")
                    + (!TextUtils.isEmpty(entry.adminEmail) ? entry.adminEmail : "System");

            items.add(SettingsActivity.SettingCell.Factory.of(
                    BASE_LOG_ID + i,
                    topColor, bottomColor,
                    iconRes,
                    EveAdminUiChrome.formatActionTitle(entry.actionType),
                    sub,
                    EveAdminUiChrome.formatDate(entry.timestamp)));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
            return;
        }
        int idx = item.id - BASE_LOG_ID;
        if (idx >= 0 && idx < filteredEntries.size()) {
            showEntryDetail(filteredEntries.get(idx));
        }
    }

    private void showEntryDetail(EveAdminModels.AuditLogEntry entry) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        String msg = "Action: " + EveAdminUiChrome.formatActionTitle(entry.actionType)
                + "\nRaw Type: " + entry.actionType
                + "\nAdmin: " + (!TextUtils.isEmpty(entry.adminEmail) ? entry.adminEmail : "System")
                + "\nTime: " + EveAdminUiChrome.formatDateTime(entry.timestamp)
                + (!TextUtils.isEmpty(entry.description) ? "\n\n" + entry.description : "");
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Audit Event");
        builder.setMessage(msg);
        builder.setPositiveButton("Close", null);
        showDialog(builder.create());
    }
}
