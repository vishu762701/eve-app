package org.telegram.ui.eve.admin;

import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
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

/**
 * Telegram Settings-style Admin Roles & Permissions directory.
 * Tapping '+' or any member opens {@link EveAdminRoleEditFragment}.
 */
public class EveAdminRolesFragment extends BaseFragment {

    private static final int MENU_ADD = 1;

    private static final int ID_RETRY = 101;
    private static final int BASE_ADMIN_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private boolean loading = true;
    private boolean destroyed = false;
    private String errorText = "";
    private List<EveAdminModels.AdminMemberItem> admins = Collections.emptyList();
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

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Admin Roles",
                "Loading...",
                true,
                true,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_ADD) {
                            presentFragment(new EveAdminRoleEditFragment(null, EveAdminRolesFragment.this::reload));
                        }
                    }
                },
                null
        );
        listView = chrome.listView;

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

    private void reload() {
        loading = admins.isEmpty();
        errorText = "";
        if (loading) {
            updateList();
        }

        EveAdminRepository.ResultCallback<List<EveAdminModels.AdminMemberItem>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.AdminMemberItem>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.AdminMemberItem> data) {
                        if (destroyed) return;
                        loading = false;
                        errorText = "";
                        autoSync.markSynced();
                        admins = data != null ? data : Collections.emptyList();
                        updateSubtitle();
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
                        autoSync.markSynced();
                        errorText = message != null ? message : "Unable to load admin roles.";
                        updateSubtitle();
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchAdmins(token)));
    }

    private void updateSubtitle() {
        if (simpleActionBar == null) return;
        if (!errorText.isEmpty() && admins.isEmpty()) {
            simpleActionBar.setSubtitle("Unable to load");
        } else {
            simpleActionBar.setSubtitle(admins.size() + " team members");
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
                    "Retry Loading Roles",
                    errorText,
                    "Retry"));
            items.add(UItem.asShadow(null));
        }

        if (loading && admins.isEmpty()) {
            for (int i = 0; i < 4; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (admins.isEmpty()) {
            items.add(UItem.asHeader("Admin Team"));
            items.add(UItem.asShadow("No delegated admins configured."));
            return;
        }

        List<Integer> ownerIndices = new ArrayList<>();
        List<Integer> adminIndices = new ArrayList<>();
        for (int i = 0; i < admins.size(); i++) {
            if (admins.get(i).isOwner) {
                ownerIndices.add(i);
            } else {
                adminIndices.add(i);
            }
        }

        if (!ownerIndices.isEmpty()) {
            items.add(UItem.asHeader("Owner"));
            for (int idx : ownerIndices) {
                items.add(buildAdminRow(idx, admins.get(idx)));
            }
            items.add(UItem.asShadow(null));
        }

        items.add(UItem.asHeader("Administrators"));
        if (adminIndices.isEmpty()) {
            items.add(UItem.asShadow("No delegated admins added yet. Tap + above to add one."));
            return;
        }
        for (int idx : adminIndices) {
            items.add(buildAdminRow(idx, admins.get(idx)));
        }
        items.add(UItem.asShadow("Tap any administrator to edit their role title or permissions."));
    }

    private UItem buildAdminRow(int index, EveAdminModels.AdminMemberItem a) {
        String permsText = a.isOwner ? "All " + EveAdminModels.ALL_RIGHTS.length + " permissions" : (a.rights.size() + " permission(s)");
        String sub = a.email + " • " + permsText;
        EveAdminUiChrome.AvatarRowCell.Payload payload = new EveAdminUiChrome.AvatarRowCell.Payload(
                a.email,
                a.customTitle,
                sub,
                "",
                a.isOwner ? "Owner" : "Admin",
                a.isOwner ? EveAdminUiChrome.AvatarRowCell.BADGE_PURPLE : EveAdminUiChrome.AvatarRowCell.BADGE_BLUE
        );
        return EveAdminUiChrome.AvatarRowCell.Factory.of(BASE_ADMIN_ID + index, a, payload);
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
            return;
        }
        if (item.id >= BASE_ADMIN_ID && item.object instanceof EveAdminModels.AdminMemberItem) {
            EveAdminModels.AdminMemberItem member = (EveAdminModels.AdminMemberItem) item.object;
            presentFragment(new EveAdminRoleEditFragment(member, this::reload));
        }
    }
}
