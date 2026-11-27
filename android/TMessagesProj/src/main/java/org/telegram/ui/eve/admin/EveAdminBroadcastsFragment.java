package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
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

/**
 * Edge-to-Edge Broadcast History & Composer replacing AlertDialog broadcast lists.
 */
public class EveAdminBroadcastsFragment extends BaseFragment {

    private static final int MENU_ADD = 1;

    private static final int ID_RETRY = 101;
    private static final int ID_CHANNEL_AVATAR = 102;
    private static final int ID_CHANNEL_NAME = 103;
    private static final int BASE_ITEM_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private final boolean openComposerOnStart;
    private UniversalRecyclerView listView;
    private EveTelegramAvatarPicker channelAvatarPicker;
    private EveAdminModels.AppConfigModel appConfig;
    private List<EveAdminModels.BroadcastItem> broadcasts = Collections.emptyList();
    private boolean loading = true;
    private boolean destroyed = false;
    private boolean composerOpened = false;
    private String errorText = "";
    private final EveAdminUiChrome.AutoSyncController autoSync = new EveAdminUiChrome.AutoSyncController(this::reload);

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

        List<EveAdminModels.BroadcastItem> cachedBroadcasts = repo.getCachedBroadcasts();
        if (cachedBroadcasts != null && !cachedBroadcasts.isEmpty()) {
            broadcasts = cachedBroadcasts;
            loading = false;
        }
        EveAdminModels.AppConfigModel cachedCfg = repo.getCachedAppConfig();
        if (cachedCfg != null) {
            appConfig = cachedCfg;
        }

        channelAvatarPicker = new EveTelegramAvatarPicker(this, true, new EveTelegramAvatarPicker.Delegate() {
            @Override
            public void onAvatarCaptured(@NonNull byte[] imageBytes, @NonNull String mimeType, @Nullable Bitmap previewBitmap) {
                uploadChannelAvatar(imageBytes, mimeType);
            }

            @Override
            public void onAvatarRemoved() {
                removeChannelAvatar();
            }
        });

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Broadcasts",
                broadcasts.isEmpty() ? "Loading..." : broadcasts.size() + " sent",
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
    public void onResume() {
        super.onResume();
        if (channelAvatarPicker != null) {
            channelAvatarPicker.onResume();
        }
        autoSync.onResume();
    }

    @Override
    public void onPause() {
        if (channelAvatarPicker != null) {
            channelAvatarPicker.onPause();
        }
        autoSync.onPause();
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        autoSync.destroy();
        if (channelAvatarPicker != null) {
            channelAvatarPicker.destroy();
            channelAvatarPicker = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (channelAvatarPicker != null) {
            channelAvatarPicker.onActivityResult(requestCode, resultCode, data);
        }
        super.onActivityResultFragment(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (channelAvatarPicker != null) {
            channelAvatarPicker.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
        super.onRequestPermissionsResultFragment(requestCode, permissions, grantResults);
    }

    private static final class BroadcastsBundle {
        final List<EveAdminModels.BroadcastItem> broadcasts;
        final EveAdminModels.AppConfigModel config;

        BroadcastsBundle(List<EveAdminModels.BroadcastItem> broadcasts, EveAdminModels.AppConfigModel config) {
            this.broadcasts = broadcasts != null ? broadcasts : Collections.emptyList();
            this.config = config;
        }
    }

    private void reload() {
        loading = broadcasts.isEmpty();
        errorText = "";
        if (loading) {
            updateList();
        }

        EveAdminRepository.ResultCallback<BroadcastsBundle> cb =
                new EveAdminRepository.ResultCallback<BroadcastsBundle>() {
                    @Override
                    public void onSuccess(BroadcastsBundle data) {
                        if (destroyed) return;
                        loading = false;
                        errorText = "";
                        autoSync.markSynced();
                        if (data != null) {
                            broadcasts = data.broadcasts;
                            if (data.config != null) {
                                appConfig = data.config;
                            }
                        }
                        updateSubtitle();
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
                        autoSync.markSynced();
                        errorText = message != null ? message : "Unable to load broadcasts.";
                        updateSubtitle();
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> {
            List<EveAdminModels.BroadcastItem> bc = repo.fetchBroadcasts(token);
            EveAdminModels.AppConfigModel cfg = null;
            try {
                cfg = repo.fetchAppConfig(token);
            } catch (Throwable ignored) {
            }
            repo.postSuccess(cb, new BroadcastsBundle(bc, cfg));
        });
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

        items.add(UItem.asHeader("Announcements Channel Identity"));
        String chName = appConfig != null && !TextUtils.isEmpty(appConfig.announcementChannelName)
                ? appConfig.announcementChannelName : "Announcements";
        String chAvatar = appConfig != null ? appConfig.announcementChannelAvatarUrl : "";
        items.add(EveAdminUiChrome.AvatarRowCell.Factory.of(
                ID_CHANNEL_AVATAR,
                "channel_identity",
                new EveAdminUiChrome.AvatarRowCell.Payload(
                        "eve_announcements",
                        chName,
                        "Channel Avatar • Tap to set via Telegram Image Editor",
                        chAvatar,
                        "",
                        4,
                        "Photo",
                        EveAdminUiChrome.AvatarRowCell.BADGE_BLUE)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_CHANNEL_NAME,
                IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                R.drawable.msg_edit,
                "Channel Display Name",
                chName,
                "Edit"));
        items.add(UItem.asShadow("Shown as a pinned verified channel at the top of the student Home screen."));

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

        items.add(UItem.asHeader("Broadcast History"));
        if (broadcasts.isEmpty()) {
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
        if (item.id == ID_CHANNEL_AVATAR) {
            boolean hasAvatar = appConfig != null && !TextUtils.isEmpty(appConfig.announcementChannelAvatarUrl);
            if (channelAvatarPicker != null) {
                channelAvatarPicker.open(hasAvatar);
            }
            return;
        }
        if (item.id == ID_CHANNEL_NAME) {
            showEditChannelNameDialog();
            return;
        }
        int idx = item.id - BASE_ITEM_ID;
        if (idx >= 0 && idx < broadcasts.size()) {
            showBroadcastDetail(broadcasts.get(idx));
        }
    }

    private void showEditChannelNameDialog() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        Theme.ResourcesProvider rp = getResourceProvider();
        String currentName = appConfig != null && !TextUtils.isEmpty(appConfig.announcementChannelName)
                ? appConfig.announcementChannelName : "Announcements";

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(4));

        EditTextBoldCursor input = new EditTextBoldCursor(activity);
        input.setHint("Channel display name");
        input.setText(currentName);
        input.setSelection(currentName.length());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setCursorSize(dp(20));
        input.setCursorWidth(1.5f);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, rp));
        box.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, rp);
        builder.setTitle("Edit Channel Name");
        builder.setView(box);
        builder.setPositiveButton("Save", (d, w) -> {
            String nextName = input.getText() != null ? input.getText().toString().trim() : "";
            if (nextName.isEmpty()) {
                BulletinFactory.of(this).createErrorBulletin("Channel name cannot be empty.").show();
                return;
            }
            EveAdminRepository.ResultCallback<EveAdminModels.AppConfigModel> cb =
                    new EveAdminRepository.ResultCallback<EveAdminModels.AppConfigModel>() {
                        @Override
                        public void onSuccess(EveAdminModels.AppConfigModel data) {
                            if (destroyed) return;
                            if (data != null) appConfig = data;
                            updateList();
                            BulletinFactory.of(EveAdminBroadcastsFragment.this)
                                    .createSimpleBulletin(R.raw.contact_check, "Channel name updated").show();
                        }

                        @Override
                        public void onError(String message, int httpStatus) {
                            if (destroyed) return;
                            BulletinFactory.of(EveAdminBroadcastsFragment.this)
                                    .createErrorBulletin(message != null ? message : "Update failed.").show();
                        }
                    };
            repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.updateAnnouncementChannelName(token, nextName)));
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void uploadChannelAvatar(byte[] bytes, String mimeType) {
        if (destroyed) return;
        EveAdminRepository.ResultCallback<EveAdminModels.AppConfigModel> cb =
                new EveAdminRepository.ResultCallback<EveAdminModels.AppConfigModel>() {
                    @Override
                    public void onSuccess(EveAdminModels.AppConfigModel data) {
                        if (destroyed) return;
                        if (data != null) appConfig = data;
                        updateList();
                        BulletinFactory.of(EveAdminBroadcastsFragment.this)
                                .createSimpleBulletin(R.raw.contact_check, "Channel avatar updated").show();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        BulletinFactory.of(EveAdminBroadcastsFragment.this)
                                .createErrorBulletin(message != null ? message : "Avatar upload failed.").show();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.uploadAnnouncementChannelAvatar(token, bytes, mimeType)));
    }

    private void removeChannelAvatar() {
        if (destroyed) return;
        EveAdminRepository.ResultCallback<EveAdminModels.AppConfigModel> cb =
                new EveAdminRepository.ResultCallback<EveAdminModels.AppConfigModel>() {
                    @Override
                    public void onSuccess(EveAdminModels.AppConfigModel data) {
                        if (destroyed) return;
                        if (data != null) appConfig = data;
                        updateList();
                        BulletinFactory.of(EveAdminBroadcastsFragment.this)
                                .createSimpleBulletin(R.raw.ic_delete, "Channel avatar removed").show();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        BulletinFactory.of(EveAdminBroadcastsFragment.this)
                                .createErrorBulletin(message != null ? message : "Failed to remove avatar.").show();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.deleteAnnouncementChannelAvatar(token)));
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
