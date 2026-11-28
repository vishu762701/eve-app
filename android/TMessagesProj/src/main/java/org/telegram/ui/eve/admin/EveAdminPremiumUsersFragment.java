package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Edge-to-Edge Premium Subscribers directory replacing AlertDialog subscriber lists.
 */
public class EveAdminPremiumUsersFragment extends BaseFragment {

    private static final int MENU_SEARCH = 1;

    private static final int ID_RETRY = 101;
    private static final int BASE_USER_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private ActionBarMenuItem searchItem;
    private boolean loading = true;
    private boolean destroyed = false;
    private String errorText = "";
    private String searchQuery = "";
    private List<EveAdminModels.PremiumUserItem> users = Collections.emptyList();
    private final List<EveAdminModels.PremiumUserItem> filteredUsers = new ArrayList<>();
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
                "Premium Subscribers",
                "Loading...",
                false,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
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
            searchItem.setSearchFieldHint("Search subscriber name or email");
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
        loading = users.isEmpty();
        errorText = "";
        if (loading) {
            updateList();
        }

        EveAdminRepository.ResultCallback<List<EveAdminModels.PremiumUserItem>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.PremiumUserItem>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.PremiumUserItem> data) {
                        if (destroyed) return;
                        loading = false;
                        errorText = "";
                        autoSync.markSynced();
                        users = data != null ? data : Collections.emptyList();
                        applyFilter(searchQuery);
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
                        autoSync.markSynced();
                        errorText = message != null ? message : "Unable to load subscribers.";
                        updateSubtitle();
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchPremiumUsers(token)));
    }

    private void applyFilter(String query) {
        searchQuery = query != null ? query.trim() : "";
        filteredUsers.clear();
        String q = searchQuery.toLowerCase(Locale.US);
        for (EveAdminModels.PremiumUserItem u : users) {
            if (q.isEmpty()
                    || u.displayName.toLowerCase(Locale.US).contains(q)
                    || u.email.toLowerCase(Locale.US).contains(q)
                    || u.planName.toLowerCase(Locale.US).contains(q)) {
                filteredUsers.add(u);
            }
        }
        updateSubtitle();
        updateList();
    }

    private void updateSubtitle() {
        if (simpleActionBar == null) return;
        if (!errorText.isEmpty() && users.isEmpty()) {
            simpleActionBar.setSubtitle("Unable to load");
        } else if (!searchQuery.isEmpty()) {
            simpleActionBar.setSubtitle(filteredUsers.size() + " matching");
        } else {
            simpleActionBar.setSubtitle(users.size() + " subscribers");
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
                    "Retry Loading Subscribers",
                    errorText,
                    "Retry"));
        }

        if (loading && users.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (filteredUsers.isEmpty()) {
            items.add(UItem.asHeader("Premium Subscribers"));
            items.add(UItem.asShadow(searchQuery.isEmpty()
                    ? "No active premium subscribers."
                    : "No matching subscribers found."));
            return;
        }

        for (int i = 0; i < filteredUsers.size(); i++) {
            EveAdminModels.PremiumUserItem u = filteredUsers.get(i);
            String title = !TextUtils.isEmpty(u.displayName) ? u.displayName : (!TextUtils.isEmpty(u.email) ? u.email : u.userId);
            StringBuilder sub = new StringBuilder();
            if (!TextUtils.isEmpty(u.email)) {
                sub.append(u.email);
            }
            if (!TextUtils.isEmpty(u.planName)) {
                if (sub.length() > 0) sub.append(" • ");
                sub.append(u.planName);
            }
            if (u.expiresAt > 0L) {
                if (sub.length() > 0) sub.append(" • ");
                sub.append("Exp ").append(EveAdminUiChrome.formatDate(u.expiresAt));
            }
            String seed = !TextUtils.isEmpty(u.userId) ? u.userId : u.email;
            EveAdminUiChrome.AvatarRowCell.Payload payload = new EveAdminUiChrome.AvatarRowCell.Payload(
                    seed,
                    title,
                    sub.toString(),
                    "",
                    u.active ? "Active" : "Expired",
                    u.active ? EveAdminUiChrome.AvatarRowCell.BADGE_GREEN : EveAdminUiChrome.AvatarRowCell.BADGE_RED
            );
            items.add(EveAdminUiChrome.AvatarRowCell.Factory.of(BASE_USER_ID + i, u, payload));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
            return;
        }
        if (item.id >= BASE_USER_ID && item.object instanceof EveAdminModels.PremiumUserItem) {
            showSubscriberActions((EveAdminModels.PremiumUserItem) item.object);
        }
    }

    private void showSubscriberActions(EveAdminModels.PremiumUserItem user) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        String title = !TextUtils.isEmpty(user.displayName) ? user.displayName : user.email;
        String[] options = new String[] {
                "Extend +30 Days",
                "Extend Custom Days...",
                "Revoke Premium"
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle(title);
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) {
                extendMembership(user, 30);
            } else if (which == 1) {
                promptCustomExtendDays(user);
            } else if (which == 2) {
                confirmRevokeMembership(user);
            }
        });
        builder.setNegativeButton("Close", null);
        showDialog(builder.create());
    }

    private void promptCustomExtendDays(EveAdminModels.PremiumUserItem user) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        Theme.ResourcesProvider rp = getResourceProvider();

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(12), AndroidUtilities.dp(24), AndroidUtilities.dp(8));

        TextView hint = new TextView(activity);
        hint.setText("Days to add for " + (!TextUtils.isEmpty(user.email) ? user.email : user.displayName));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        layout.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        EditTextBoldCursor daysInput = new EditTextBoldCursor(activity);
        daysInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        daysInput.setText("30");
        daysInput.setSelection(daysInput.getText().length());
        daysInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        daysInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        daysInput.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp));
        layout.addView(daysInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, rp);
        builder.setTitle("Extend Premium");
        builder.setView(layout);
        builder.setPositiveButton("Extend", (d, w) -> {
            int days = 30;
            try {
                days = Integer.parseInt(daysInput.getText().toString().trim());
            } catch (Exception ignored) {
            }
            if (days <= 0) {
                BulletinFactory.of(this).createErrorBulletin("Enter a positive day count.").show();
                return;
            }
            extendMembership(user, days);
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void extendMembership(EveAdminModels.PremiumUserItem user, int days) {
        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                if (destroyed) return;
                repo.invalidateCache();
                BulletinFactory.of(EveAdminPremiumUsersFragment.this)
                        .createSimpleBulletin(R.raw.info, "Extended +" + days + " days").show();
                reload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                BulletinFactory.of(EveAdminPremiumUsersFragment.this)
                        .createErrorBulletin(message != null ? message : "Extend failed").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.extendPremium(token, user.userId, days);
            repo.postSuccess(cb, null);
        });
    }

    private void confirmRevokeMembership(EveAdminModels.PremiumUserItem user) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        String label = !TextUtils.isEmpty(user.displayName) ? user.displayName : user.email;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Revoke Premium");
        builder.setMessage("Revoke active premium access for " + label + "?");
        builder.setPositiveButton("Revoke", (d, w) -> {
            EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
                @Override
                public void onSuccess(Void data) {
                    if (destroyed) return;
                    repo.invalidateCache();
                    BulletinFactory.of(EveAdminPremiumUsersFragment.this)
                            .createSimpleBulletin(R.raw.info, "Premium access revoked").show();
                    reload();
                }

                @Override
                public void onError(String message, int httpStatus) {
                    if (destroyed) return;
                    BulletinFactory.of(EveAdminPremiumUsersFragment.this)
                            .createErrorBulletin(message != null ? message : "Revoke failed").show();
                }
            };
            repo.runWithAdminToken(cb, token -> {
                repo.revokePremium(token, user.userId);
                repo.postSuccess(cb, null);
            });
        });
        builder.setNegativeButton("Cancel", null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive instanceof TextView) {
            ((TextView) positive).setTextColor(Theme.getColor(Theme.key_text_RedBold, getResourceProvider()));
        }
    }
}
