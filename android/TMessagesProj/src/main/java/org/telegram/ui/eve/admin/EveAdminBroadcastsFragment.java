package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
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
 * Edge-to-Edge Broadcast History & Composer replacing AlertDialog broadcast lists.
 */
public class EveAdminBroadcastsFragment extends BaseFragment {

    private static final int MENU_ADD = 1;
    private static final int MENU_REFRESH = 2;

    private static final int ID_RETRY = 101;
    private static final int BASE_ITEM_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private final boolean openComposerOnStart;
    private UniversalRecyclerView listView;
    private List<EveAdminModels.BroadcastItem> broadcasts = Collections.emptyList();
    private boolean loading = true;
    private boolean destroyed = false;
    private boolean composerOpened = false;
    private String errorText = "";

    public EveAdminBroadcastsFragment() {
        this(false);
    }

    public EveAdminBroadcastsFragment(boolean openComposerOnStart) {
        this.openComposerOnStart = openComposerOnStart;
    }

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
                "Broadcasts",
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
                            showBroadcastComposer();
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
        if (openComposerOnStart && !composerOpened) {
            composerOpened = true;
            AndroidUtilities.runOnUIThread(() -> {
                if (!destroyed) {
                    showBroadcastComposer();
                }
            }, 120);
        }
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

        EveAdminRepository.ResultCallback<List<EveAdminModels.BroadcastItem>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.BroadcastItem>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.BroadcastItem> data) {
                        if (destroyed) return;
                        loading = false;
                        errorText = "";
                        broadcasts = data != null ? data : Collections.emptyList();
                        updateSubtitle();
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
                        errorText = message != null ? message : "Unable to load broadcasts.";
                        updateSubtitle();
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchBroadcasts(token)));
    }

    private void updateSubtitle() {
        if (simpleActionBar == null) return;
        if (!errorText.isEmpty() && broadcasts.isEmpty()) {
            simpleActionBar.setSubtitle("Unable to load");
        } else {
            simpleActionBar.setSubtitle(broadcasts.size() + " sent");
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
                    "Retry Loading Broadcasts",
                    errorText,
                    "Retry"));
        }

        if (loading && broadcasts.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (broadcasts.isEmpty()) {
            items.add(UItem.asHeader("Broadcasts"));
            items.add(UItem.asShadow("No broadcasts sent yet. Tap + to deliver an announcement."));
            return;
        }

        for (int i = 0; i < broadcasts.size(); i++) {
            EveAdminModels.BroadcastItem b = broadcasts.get(i);
            String preview = b.message;
            if (!TextUtils.isEmpty(b.sentBy)) {
                preview = preview + " • " + b.sentBy;
            }
            items.add(SettingsActivity.SettingCell.Factory.of(
                    BASE_ITEM_ID + i,
                    IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                    R.drawable.msg_send,
                    b.title,
                    preview,
                    EveAdminUiChrome.formatDate(b.sentAt)));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
            return;
        }
        int idx = item.id - BASE_ITEM_ID;
        if (idx >= 0 && idx < broadcasts.size()) {
            showBroadcastDetail(broadcasts.get(idx));
        }
    }

    private void showBroadcastComposer() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        Theme.ResourcesProvider rp = getResourceProvider();

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(12), dp(22), dp(8));

        EditText titleInput = new EditText(activity);
        titleInput.setHint("Broadcast Title");
        titleInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        titleInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        box.addView(titleInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        EditText msgInput = new EditText(activity);
        msgInput.setHint("Notification message (FCM + EVE Announcements)");
        msgInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        msgInput.setMinLines(4);
        msgInput.setGravity(Gravity.TOP | Gravity.START);
        msgInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        msgInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        box.addView(msgInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, rp);
        builder.setTitle("Send Broadcast Notification");
        builder.setView(box);
        builder.setPositiveButton("Send Broadcast", (d, w) -> {
            String title = titleInput.getText().toString().trim();
            String msg = msgInput.getText().toString().trim();
            if (title.isEmpty() || msg.isEmpty()) {
                BulletinFactory.of(this).createErrorBulletin("Title and message are required.").show();
                return;
            }
            EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
                @Override
                public void onSuccess(Void data) {
                    if (destroyed) return;
                    BulletinFactory.of(EveAdminBroadcastsFragment.this)
                            .createSimpleBulletin(R.raw.contact_check, "Broadcast delivered").show();
                    reload();
                }

                @Override
                public void onError(String message, int httpStatus) {
                    if (destroyed) return;
                    BulletinFactory.of(EveAdminBroadcastsFragment.this)
                            .createErrorBulletin(message != null ? message : "Send failed.").show();
                }
            };
            repo.runWithAdminToken(cb, token -> {
                repo.sendBroadcast(token, title, msg);
                repo.postSuccess(cb, null);
            });
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showBroadcastDetail(EveAdminModels.BroadcastItem b) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        String detail = b.message
                + "\n\nSent by: " + (!TextUtils.isEmpty(b.sentBy) ? b.sentBy : "Admin")
                + "\nTime: " + EveAdminUiChrome.formatDateTime(b.sentAt);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle(b.title);
        builder.setMessage(detail);
        builder.setPositiveButton("Close", null);
        builder.setNeutralButton("Delete", (d, w) -> confirmDeleteBroadcast(b));
        showDialog(builder.create());
    }

    private void confirmDeleteBroadcast(EveAdminModels.BroadcastItem b) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Delete Broadcast");
        builder.setMessage("Remove \"" + b.title + "\" from EVE Announcements?");
        builder.setPositiveButton("Delete", (d, w) -> {
            EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
                @Override
                public void onSuccess(Void data) {
                    if (destroyed) return;
                    BulletinFactory.of(EveAdminBroadcastsFragment.this)
                            .createSimpleBulletin(R.raw.info, "Broadcast deleted").show();
                    reload();
                }

                @Override
                public void onError(String message, int httpStatus) {
                    if (destroyed) return;
                    BulletinFactory.of(EveAdminBroadcastsFragment.this)
                            .createErrorBulletin(message != null ? message : "Delete failed.").show();
                }
            };
            repo.runWithAdminToken(cb, token -> {
                repo.deleteBroadcast(token, b.id);
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
