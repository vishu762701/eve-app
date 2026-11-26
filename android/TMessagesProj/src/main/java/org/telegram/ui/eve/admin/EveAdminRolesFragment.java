package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Edge-to-Edge Admin Roles & Permissions directory replacing AlertDialog admin managers.
 */
public class EveAdminRolesFragment extends BaseFragment {

    private static final int MENU_ADD = 1;
    private static final int MENU_REFRESH = 2;

    private static final int ID_RETRY = 101;
    private static final int BASE_ADMIN_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private boolean loading = true;
    private boolean destroyed = false;
    private String errorText = "";
    private List<EveAdminModels.AdminMemberItem> admins = Collections.emptyList();

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
                false,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_ADD) {
                            showAddOrEditAdminDialog(null);
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
            menu.addItem(MENU_ADD, R.drawable.msg_add, getResourceProvider());
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

        EveAdminRepository.ResultCallback<List<EveAdminModels.AdminMemberItem>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.AdminMemberItem>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.AdminMemberItem> data) {
                        if (destroyed) return;
                        loading = false;
                        errorText = "";
                        admins = data != null ? data : Collections.emptyList();
                        updateSubtitle();
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
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

        for (int i = 0; i < admins.size(); i++) {
            EveAdminModels.AdminMemberItem a = admins.get(i);
            String permsText = a.isOwner ? "All Permissions" : (a.rights.size() + " permission(s)");
            String sub = a.email + " • " + permsText;
            EveAdminUiChrome.AvatarRowCell.Payload payload = new EveAdminUiChrome.AvatarRowCell.Payload(
                    a.email,
                    a.customTitle,
                    sub,
                    "",
                    a.isOwner ? "Owner" : "Admin",
                    a.isOwner ? EveAdminUiChrome.AvatarRowCell.BADGE_PURPLE : EveAdminUiChrome.AvatarRowCell.BADGE_BLUE
            );
            items.add(EveAdminUiChrome.AvatarRowCell.Factory.of(BASE_ADMIN_ID + i, a, payload));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
            return;
        }
        if (item.id >= BASE_ADMIN_ID && item.object instanceof EveAdminModels.AdminMemberItem) {
            showAdminActions((EveAdminModels.AdminMemberItem) item.object);
        }
    }

    private void showAdminActions(EveAdminModels.AdminMemberItem member) {
        Activity activity = getParentActivity();
        if (activity == null) return;

        if (member.isOwner) {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
            builder.setTitle(member.customTitle);
            builder.setMessage(member.email + "\n\nThe Owner holds permanent full permissions across EVE Admin Control Center.");
            builder.setPositiveButton("Close", null);
            showDialog(builder.create());
            return;
        }

        String[] options = new String[] { "Edit Rights & Title", "Remove Admin" };
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle(member.customTitle + " (" + member.email + ")");
        builder.setItems(options, (d, which) -> {
            if (which == 0) {
                showAddOrEditAdminDialog(member);
            } else if (which == 1) {
                confirmRemoveAdmin(member);
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showAddOrEditAdminDialog(@Nullable EveAdminModels.AdminMemberItem existing) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        Theme.ResourcesProvider rp = getResourceProvider();

        ScrollView scroll = new ScrollView(activity);
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(22), dp(12), dp(22), dp(8));
        scroll.addView(container);

        EditText emailInput = new EditText(activity);
        emailInput.setHint("Admin Google Email");
        emailInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        emailInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        if (existing != null) {
            emailInput.setText(existing.email);
            emailInput.setEnabled(false);
        }
        container.addView(emailInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        EditText titleInput = new EditText(activity);
        titleInput.setHint("Custom Role Title (e.g. Moderator)");
        titleInput.setInputType(InputType.TYPE_CLASS_TEXT);
        titleInput.setText(existing != null ? existing.customTitle : "Moderator");
        titleInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        titleInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        container.addView(titleInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        TextView label = new TextView(activity);
        label.setText("Permissions");
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        container.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        Map<String, CheckBox> rightBoxes = new HashMap<>();
        for (String right : EveAdminModels.ALL_RIGHTS) {
            CheckBox cb = new CheckBox(activity);
            cb.setText(EveAdminUiChrome.formatActionTitle(right));
            cb.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
            cb.setChecked(existing != null && existing.rights.contains(right));
            container.addView(cb, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 2));
            rightBoxes.put(right, cb);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, rp);
        builder.setTitle(existing == null ? "Add Admin" : "Edit Admin Rights");
        builder.setView(scroll);
        builder.setPositiveButton(existing == null ? "Add Admin" : "Save Rights", (d, w) -> {
            String email = existing != null ? existing.email : emailInput.getText().toString().trim();
            String customTitle = titleInput.getText().toString().trim();
            if (TextUtils.isEmpty(email)) {
                BulletinFactory.of(this).createErrorBulletin("Email is required.").show();
                return;
            }
            List<String> selected = new ArrayList<>();
            for (String right : EveAdminModels.ALL_RIGHTS) {
                CheckBox box = rightBoxes.get(right);
                if (box != null && box.isChecked()) {
                    selected.add(right);
                }
            }
            EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
                @Override
                public void onSuccess(Void data) {
                    if (destroyed) return;
                    repo.invalidateCache();
                    BulletinFactory.of(EveAdminRolesFragment.this)
                            .createSimpleBulletin(R.raw.info, existing == null ? "Admin added" : "Rights updated").show();
                    reload();
                }

                @Override
                public void onError(String message, int httpStatus) {
                    if (destroyed) return;
                    BulletinFactory.of(EveAdminRolesFragment.this)
                            .createErrorBulletin(message != null ? message : "Admin update failed").show();
                }
            };
            repo.runWithAdminToken(cb, token -> {
                if (existing == null) {
                    repo.addAdmin(token, email, selected, customTitle.isEmpty() ? "Moderator" : customTitle);
                } else {
                    repo.updateAdminRights(token, email, selected, customTitle.isEmpty() ? "Admin" : customTitle);
                }
                repo.postSuccess(cb, null);
            });
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void confirmRemoveAdmin(EveAdminModels.AdminMemberItem member) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Remove Admin");
        builder.setMessage("Remove admin privileges from " + member.email + "?");
        builder.setPositiveButton("Remove", (d, w) -> {
            EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
                @Override
                public void onSuccess(Void data) {
                    if (destroyed) return;
                    repo.invalidateCache();
                    BulletinFactory.of(EveAdminRolesFragment.this)
                            .createSimpleBulletin(R.raw.info, "Admin removed").show();
                    reload();
                }

                @Override
                public void onError(String message, int httpStatus) {
                    if (destroyed) return;
                    BulletinFactory.of(EveAdminRolesFragment.this)
                            .createErrorBulletin(message != null ? message : "Remove failed").show();
                }
            };
            repo.runWithAdminToken(cb, token -> {
                repo.removeAdmin(token, member.email);
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
