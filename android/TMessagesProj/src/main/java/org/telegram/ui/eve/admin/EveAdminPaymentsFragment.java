package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.Components.BulletinFactory;
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
 * Edge-to-Edge Payment Orders & Transactions screen replacing AlertDialog transaction lists.
 */
public class EveAdminPaymentsFragment extends BaseFragment {

    private static final int MENU_SEARCH = 1;
    private static final int MENU_FILTER = 2;

    private static final int ID_RETRY = 101;
    private static final int BASE_TX_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private ActionBarMenuItem searchItem;
    private boolean loading = true;
    private boolean destroyed = false;
    private String errorText = "";
    private String statusFilter = "ALL";
    private String searchQuery = "";
    private List<EveAdminModels.PaymentTransactionItem> transactions = Collections.emptyList();
    private final List<EveAdminModels.PaymentTransactionItem> filteredTransactions = new ArrayList<>();
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
                "Payment Orders",
                "Loading...",
                false,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_FILTER) {
                            showStatusFilterPicker();
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
                            applyLocalSearch("");
                        }

                        @Override
                        public void onSearchCollapse() {
                            applyLocalSearch("");
                        }

                        @Override
                        public void onTextChanged(EditText editText) {
                            String next = editText.getText() != null ? editText.getText().toString() : "";
                            applyLocalSearch(next);
                        }
                    });
            searchItem.setSearchFieldHint("Search order ID or student email");
            menu.addItem(MENU_FILTER, R.drawable.msg_select, getResourceProvider());
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

    private void reload() {
        loading = transactions.isEmpty();
        errorText = "";
        if (loading) {
            updateList();
        }

        final String serverFilter = "ALL".equals(statusFilter) ? "" : statusFilter;
        EveAdminRepository.ResultCallback<List<EveAdminModels.PaymentTransactionItem>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.PaymentTransactionItem>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.PaymentTransactionItem> data) {
                        if (destroyed) return;
                        loading = false;
                        errorText = "";
                        autoSync.markSynced();
                        transactions = data != null ? data : Collections.emptyList();
                        applyLocalSearch(searchQuery);
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
                        autoSync.markSynced();
                        errorText = message != null ? message : "Unable to load payment orders.";
                        updateSubtitle();
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchTransactions(token, 100, serverFilter)));
    }

    private void applyLocalSearch(String query) {
        searchQuery = query != null ? query.trim() : "";
        filteredTransactions.clear();
        String q = searchQuery.toLowerCase(Locale.US);
        for (EveAdminModels.PaymentTransactionItem tx : transactions) {
            if (q.isEmpty()
                    || tx.orderId.toLowerCase(Locale.US).contains(q)
                    || tx.userEmail.toLowerCase(Locale.US).contains(q)
                    || tx.planName.toLowerCase(Locale.US).contains(q)
                    || tx.status.toLowerCase(Locale.US).contains(q)) {
                filteredTransactions.add(tx);
            }
        }
        updateSubtitle();
        updateList();
    }

    private void updateSubtitle() {
        if (simpleActionBar == null) return;
        if (!errorText.isEmpty() && transactions.isEmpty()) {
            simpleActionBar.setSubtitle("Unable to load");
        } else if ("ALL".equals(statusFilter)) {
            simpleActionBar.setSubtitle(filteredTransactions.size() + " orders");
        } else {
            simpleActionBar.setSubtitle(filteredTransactions.size() + " " + statusFilter.toLowerCase(Locale.US) + " orders");
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
                    "Retry Loading Orders",
                    errorText,
                    "Retry"));
        }

        if (loading && transactions.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (filteredTransactions.isEmpty()) {
            items.add(UItem.asHeader("Payment Orders"));
            items.add(UItem.asShadow("No matching payment orders found."));
            return;
        }

        for (int i = 0; i < filteredTransactions.size(); i++) {
            EveAdminModels.PaymentTransactionItem tx = filteredTransactions.get(i);
            String st = tx.status.toUpperCase(Locale.US);
            int topColor;
            int bottomColor;
            int iconRes;
            if (st.contains("PAID") || st.contains("SUCCESS") || st.contains("CAPTUR")) {
                topColor = IconBackgroundColors.GREEN.top;
                bottomColor = IconBackgroundColors.GREEN.bottom;
                iconRes = R.drawable.settings_premium;
            } else if (st.contains("FAIL") || st.contains("CANCEL") || st.contains("EXPIRE")) {
                topColor = IconBackgroundColors.RED.top;
                bottomColor = IconBackgroundColors.RED.bottom;
                iconRes = R.drawable.msg_warning;
            } else {
                topColor = IconBackgroundColors.ORANGE.top;
                bottomColor = IconBackgroundColors.ORANGE.bottom;
                iconRes = R.drawable.msg_stats;
            }

            String currencyPrefix = "INR".equalsIgnoreCase(tx.currency) ? "₹" : (tx.currency + " ");
            String title = currencyPrefix + tx.amount + " — " + (!TextUtils.isEmpty(tx.userEmail) ? tx.userEmail : tx.orderId);
            String sub = (!TextUtils.isEmpty(tx.planName) ? tx.planName : "EVE Premium")
                    + " • " + tx.paymentMethod
                    + " • " + EveAdminUiChrome.formatDateTime(tx.createdAt);

            items.add(SettingsActivity.SettingCell.Factory.of(
                    BASE_TX_ID + i,
                    topColor, bottomColor,
                    iconRes,
                    title,
                    sub,
                    st));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
            return;
        }
        int idx = item.id - BASE_TX_ID;
        if (idx >= 0 && idx < filteredTransactions.size()) {
            showOrderDetailDialog(filteredTransactions.get(idx));
        }
    }

    private void showStatusFilterPicker() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        final String[] labels = new String[] { "All Orders", "Paid", "Pending", "Failed" };
        final String[] values = new String[] { "ALL", "PAID", "PENDING", "FAILED" };
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Filter Payment Orders");
        builder.setItems(labels, (d, which) -> {
            statusFilter = values[which];
            reload();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showOrderDetailDialog(EveAdminModels.PaymentTransactionItem tx) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        String currencyPrefix = "INR".equalsIgnoreCase(tx.currency) ? "₹" : (tx.currency + " ");
        String details = "Order ID: " + tx.orderId
                + "\nStudent: " + (!TextUtils.isEmpty(tx.userEmail) ? tx.userEmail : "-")
                + "\nPlan: " + (!TextUtils.isEmpty(tx.planName) ? tx.planName : "EVE Premium")
                + "\nAmount: " + currencyPrefix + tx.amount
                + "\nMethod: " + tx.paymentMethod
                + "\nStatus: " + tx.status
                + "\nCreated: " + EveAdminUiChrome.formatDateTime(tx.createdAt);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Order Detail");
        builder.setMessage(details);
        builder.setPositiveButton("Copy Order ID", (d, w) -> {
            AndroidUtilities.addToClipboard(tx.orderId);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Order ID copied").show();
        });
        builder.setNegativeButton("Close", null);
        showDialog(builder.create());
    }
}
