package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.EditTextEmoji;
import org.telegram.ui.Components.EmojiView;
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
    private static final int ID_STUDENT_QUIZZES = 104;
    private static final int ID_OFFICIAL_TG_CHANNEL = 105;
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
                uploadChannelAvatar(imageBytes, mimeType, null);
            }

            @Override
            public void onAnimatedEmojiAvatarCaptured(
                    long documentId,
                    @NonNull int[] backgroundColors,
                    @NonNull byte[] fallbackPngBytes,
                    @NonNull String mimeType,
                    @Nullable Bitmap previewBitmap) {
                String descriptor = EveAnimatedEmojiHelper.encodeAnimatedAvatarDescriptor(documentId, backgroundColors);
                uploadChannelAvatar(fallbackPngBytes, mimeType, descriptor);
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

        items.add(UItem.asHeader("Official Telegram Channel & Quizzes"));
        String tgChannel = appConfig != null ? appConfig.officialTelegramChannelUsername : "";
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_OFFICIAL_TG_CHANNEL,
                IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                R.drawable.msg_channel,
                "Official Telegram Channel",
                TextUtils.isEmpty(tgChannel)
                        ? "Not linked — tap to connect @channel for real Telegram quizzes & posts"
                        : (tgChannel + " \u2022 Tapping Announcements opens real Telegram Chat"),
                TextUtils.isEmpty(tgChannel) ? "Link" : "Edit"));
        String chName = appConfig != null && !TextUtils.isEmpty(appConfig.announcementChannelName)
                ? appConfig.announcementChannelName : "Announcements";
        String chAvatar = appConfig != null ? appConfig.announcementChannelAvatarUrl : "";
        String chAvatarEmoji = appConfig != null ? appConfig.announcementChannelAvatarEmoji : "";
        items.add(EveAdminUiChrome.AvatarRowCell.Factory.of(
                ID_CHANNEL_AVATAR,
                "channel_identity",
                new EveAdminUiChrome.AvatarRowCell.Payload(
                        "eve_announcements",
                        chName,
                        "Channel Avatar • Tap to set via Telegram Image Editor",
                        chAvatar,
                        chAvatarEmoji,
                        4,
                        "Photo",
                        EveAdminUiChrome.AvatarRowCell.BADGE_BLUE)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_CHANNEL_NAME,
                IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                R.drawable.msg_edit,
                "Channel Display Name",
                EveAnimatedEmojiHelper.decode(chName, null),
                "Edit"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_STUDENT_QUIZZES,
                IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                R.drawable.msg_list,
                "Manage Student Quizzes",
                "Create Telegram-style quizzes with answer key & explanation",
                null));
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
            String rawTitle = !TextUtils.isEmpty(b.title)
                    ? b.title
                    : (!TextUtils.isEmpty(b.stickerDocId) ? "\u2728 Animated Sticker" : "Announcement");
            CharSequence decodedTitle = EveAnimatedEmojiHelper.decode(rawTitle, null);
            String previewRaw = !TextUtils.isEmpty(b.message)
                    ? b.message
                    : (!TextUtils.isEmpty(b.stickerEmoji) ? b.stickerEmoji + " Sticker" : "");
            if (!TextUtils.isEmpty(b.sentBy)) {
                previewRaw = previewRaw.isEmpty() ? b.sentBy : (previewRaw + " • " + b.sentBy);
            }
            CharSequence decodedPreview = EveAnimatedEmojiHelper.decode(previewRaw, null);
            items.add(SettingsActivity.SettingCell.Factory.of(
                    BASE_ITEM_ID + i,
                    IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                    R.drawable.msg_send,
                    decodedTitle,
                    decodedPreview,
                    EveAdminUiChrome.formatDate(b.sentAt)));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
            return;
        }
        if (item.id == ID_OFFICIAL_TG_CHANNEL) {
            showEditOfficialTelegramChannelDialog();
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
        if (item.id == ID_STUDENT_QUIZZES) {
            presentFragment(new EveAdminPollsFragment());
            return;
        }
        int idx = item.id - BASE_ITEM_ID;
        if (idx >= 0 && idx < broadcasts.size()) {
            showBroadcastDetail(broadcasts.get(idx));
        }
    }

    private void showEditOfficialTelegramChannelDialog() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        Theme.ResourcesProvider rp = getResourceProvider();

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), dp(4));

        EditTextBoldCursor input = new EditTextBoldCursor(activity);
        input.setHint("@channel_username or https://t.me/...");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        input.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp));
        String current = appConfig != null ? appConfig.officialTelegramChannelUsername : "";
        input.setText(current);
        input.setSelection(input.getText() != null ? input.getText().length() : 0);
        box.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, rp);
        builder.setTitle("Official Telegram Channel");
        builder.setMessage("Enter @username or t.me link for your Telegram Announcements & Native Quizzes channel. Leave blank to fall back to built-in EVE Announcements.");
        builder.setView(box);
        builder.setPositiveButton("Save", (d, w) -> {
            String nextChannel = input.getText() != null ? input.getText().toString().trim() : "";
            EveAdminModels.AppConfigModel baseCfg = appConfig != null ? appConfig : repo.getCachedAppConfig();
            EveAdminRepository.ResultCallback<EveAdminModels.AppConfigModel> cb =
                    new EveAdminRepository.ResultCallback<EveAdminModels.AppConfigModel>() {
                        @Override
                        public void onSuccess(EveAdminModels.AppConfigModel data) {
                            if (destroyed) return;
                            if (data != null) appConfig = data;
                            updateList();
                            BulletinFactory.of(EveAdminBroadcastsFragment.this)
                                    .createSimpleBulletin(
                                            R.raw.contact_check,
                                            data != null && !TextUtils.isEmpty(data.officialTelegramChannelUsername)
                                                    ? ("Linked " + data.officialTelegramChannelUsername)
                                                    : "Telegram Channel unlinked")
                                    .show();
                        }

                        @Override
                        public void onError(String message, int httpStatus) {
                            if (destroyed) return;
                            BulletinFactory.of(EveAdminBroadcastsFragment.this)
                                    .createErrorBulletin(message != null ? message : "Update failed.").show();
                        }
                    };
            repo.runWithAdminToken(cb, token -> {
                EveAdminModels.AppConfigModel latest = baseCfg != null ? baseCfg : repo.fetchAppConfig(token);
                int minVer = latest != null ? Math.max(1, latest.minSupportedVersionCode) : 1;
                boolean maint = latest != null && latest.maintenanceMode;
                String maintMsg = latest != null ? latest.maintenanceMessage : "";
                String pubMode = latest != null && !TextUtils.isEmpty(latest.defaultPublishMode) ? latest.defaultPublishMode : "paused";
                String supportUser = latest != null ? latest.officialTelegramSupportUsername : "";
                String aiProv = latest != null && !TextUtils.isEmpty(latest.aiProvider) ? latest.aiProvider : "auto";
                String aiMod = latest != null ? latest.aiModel : "";
                EveAdminModels.AppConfigModel updated = repo.updateAppConfig(
                        token, minVer, maint, maintMsg, pubMode, nextChannel, supportUser, aiProv, aiMod);
                repo.postSuccess(cb, updated);
            });
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showEditChannelNameDialog() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        Theme.ResourcesProvider rp = getResourceProvider();
        String currentName = appConfig != null && !TextUtils.isEmpty(appConfig.announcementChannelName)
                ? appConfig.announcementChannelName : "Announcements";

        EveAnimatedEmojiHelper.SheetHost host = new EveAnimatedEmojiHelper.SheetHost(activity, "Edit Channel Name", rp);
        EditTextEmoji input = EveAnimatedEmojiHelper.createEditTextEmoji(
                activity, host.root, this, "Channel display name", currentName, false, rp);
        host.formColumn.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancelBtn = buildSheetButton(activity, "Cancel", false, rp);
        TextView saveBtn = buildSheetButton(activity, "Save", true, rp);
        actions.addView(cancelBtn, LayoutHelper.createLinear(0, 42, 1f, 0, 0, 8, 0));
        actions.addView(saveBtn, LayoutHelper.createLinear(0, 42, 1f, 8, 0, 0, 0));
        host.formColumn.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        cancelBtn.setOnClickListener(v -> host.sheet.dismiss());
        saveBtn.setOnClickListener(v -> {
            String nextName = EveAnimatedEmojiHelper.encode(input.getText()).trim();
            if (nextName.isEmpty()) {
                BulletinFactory.of(this).createErrorBulletin("Channel name cannot be empty.").show();
                return;
            }
            host.sheet.dismiss();
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

        host.sheet.setOnShowListener(d -> input.onResume());
        host.sheet.setOnDismissListener(d -> {
            input.onPause();
            input.onDestroy();
        });
        showDialog(host.sheet);
    }

    private void uploadChannelAvatar(byte[] bytes, String mimeType, @Nullable String avatarEmojiDescriptor) {
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
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.uploadAnnouncementChannelAvatar(token, bytes, mimeType, avatarEmojiDescriptor)));
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

        EveAnimatedEmojiHelper.SheetHost host = new EveAnimatedEmojiHelper.SheetHost(activity, "Send Broadcast Notification", rp);

        EditTextEmoji titleInput = EveAnimatedEmojiHelper.createEditTextEmoji(
                activity, host.root, this, "Broadcast Title (Optional)", "", false, rp);
        host.formColumn.addView(titleInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        EditTextEmoji msgInput = EveAnimatedEmojiHelper.createEditTextEmoji(
                activity, host.root, this, "Notification message (FCM + EVE Announcements)", "", true, rp);
        host.formColumn.addView(msgInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        // Optional Animated Telegram Sticker attachment strip
        final TLRPC.Document[] pickedSticker = new TLRPC.Document[1];
        final String[] pickedStickerEmoji = new String[] { "" };

        LinearLayout stickerRow = new LinearLayout(activity);
        stickerRow.setOrientation(LinearLayout.HORIZONTAL);
        stickerRow.setGravity(Gravity.CENTER_VERTICAL);
        stickerRow.setPadding(dp(10), dp(8), dp(10), dp(8));
        stickerRow.setBackground(Theme.createRoundRectDrawable(
                dp(10), Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp), 0.08f)));

        BackupImageView stickerPreview = new BackupImageView(activity);
        stickerPreview.setAspectFit(true);
        stickerPreview.setVisibility(View.GONE);
        stickerRow.addView(stickerPreview, LayoutHelper.createLinear(44, 44, 0, 0, 10, 0));

        TextView stickerLabel = new TextView(activity);
        stickerLabel.setText("Attach Animated Sticker (Optional)");
        stickerLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        stickerLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, rp));
        stickerRow.addView(stickerLabel, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView clearStickerBtn = new TextView(activity);
        clearStickerBtn.setText("Remove");
        clearStickerBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        clearStickerBtn.setTypeface(AndroidUtilities.bold());
        clearStickerBtn.setTextColor(Theme.getColor(Theme.key_text_RedRegular, rp));
        clearStickerBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        clearStickerBtn.setVisibility(View.GONE);
        clearStickerBtn.setOnClickListener(v -> {
            pickedSticker[0] = null;
            pickedStickerEmoji[0] = "";
            stickerPreview.setVisibility(View.GONE);
            clearStickerBtn.setVisibility(View.GONE);
            stickerLabel.setText("Attach Animated Sticker (Optional)");
            stickerLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, rp));
        });
        stickerRow.addView(clearStickerBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        stickerRow.setOnClickListener(v -> openAnimatedStickerPicker(activity, rp, (doc, emoji) -> {
            pickedSticker[0] = doc;
            pickedStickerEmoji[0] = emoji != null ? emoji : "";
            stickerPreview.setVisibility(View.VISIBLE);
            clearStickerBtn.setVisibility(View.VISIBLE);
            stickerLabel.setText("Animated sticker attached" + (!pickedStickerEmoji[0].isEmpty() ? " " + pickedStickerEmoji[0] : ""));
            stickerLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
            try {
                stickerPreview.setImage(
                        ImageLocation.getForDocument(doc),
                        "44_44",
                        DocumentObject.getSvgThumb(doc, Theme.key_windowBackgroundGray, 0.25f),
                        null);
            } catch (Throwable ignored) {
            }
        }));
        host.formColumn.addView(stickerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancelBtn = buildSheetButton(activity, "Cancel", false, rp);
        TextView sendBtn = buildSheetButton(activity, "Send Broadcast", true, rp);
        actions.addView(cancelBtn, LayoutHelper.createLinear(0, 42, 1f, 0, 0, 8, 0));
        actions.addView(sendBtn, LayoutHelper.createLinear(0, 42, 1f, 8, 0, 0, 0));
        host.formColumn.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        cancelBtn.setOnClickListener(v -> host.sheet.dismiss());
        sendBtn.setOnClickListener(v -> {
            String title = EveAnimatedEmojiHelper.encode(titleInput.getText()).trim();
            String msg = EveAnimatedEmojiHelper.encode(msgInput.getText()).trim();
            if (title.isEmpty() && msg.isEmpty() && pickedSticker[0] == null) {
                BulletinFactory.of(this).createErrorBulletin("Enter a title, message, or sticker.").show();
                return;
            }
            TLRPC.Document doc = pickedSticker[0];
            String docId = doc != null ? String.valueOf(doc.id) : "";
            String accessHash = doc != null ? String.valueOf(doc.access_hash) : "";
            int dcId = doc != null ? Math.max(0, doc.dc_id) : 0;
            String stEmoji = pickedStickerEmoji[0];
            host.sheet.dismiss();

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
                repo.sendBroadcast(token, title, msg, docId, accessHash, dcId, stEmoji);
                repo.postSuccess(cb, null);
            });
        });

        host.sheet.setOnShowListener(d -> {
            titleInput.onResume();
            msgInput.onResume();
        });
        host.sheet.setOnDismissListener(d -> {
            titleInput.onPause();
            msgInput.onPause();
            titleInput.onDestroy();
            msgInput.onDestroy();
        });
        showDialog(host.sheet);
    }

    private interface StickerPickCallback {
        void onPicked(TLRPC.Document doc, String emoji);
    }

    private void openAnimatedStickerPicker(
            @NonNull Activity activity,
            @Nullable Theme.ResourcesProvider rp,
            @NonNull StickerPickCallback callback) {
        BottomSheet pickerSheet = new BottomSheet(activity, false, rp);
        pickerSheet.setApplyTopPadding(false);
        pickerSheet.setApplyBottomPadding(false);
        pickerSheet.setCanDismissWithSwipe(false);
        pickerSheet.setAllowNestedScroll(false);

        FrameLayout container = new FrameLayout(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int w = MeasureSpec.getSize(widthMeasureSpec);
                int h = Math.max(dp(340), Math.round(AndroidUtilities.displaySize.y * 0.62f));
                super.onMeasure(
                        MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            }
        };
        EmojiView emojiView = new EmojiView(
                this, false, true, false, activity, false, null, null, true, rp, false, false);
        emojiView.setDelegate(new EmojiView.EmojiViewDelegate() {
            @Override
            public void onStickerSelected(
                    View view,
                    TLRPC.Document sticker,
                    String query,
                    Object parent,
                    MessageObject.SendAnimationData sendAnimationData,
                    boolean notify,
                    int scheduleDate,
                    int scheduleRepeatPeriod) {
                if (sticker == null) return;
                String emoji = MessageObject.findAnimatedEmojiEmoticon(sticker, "");
                pickerSheet.dismiss();
                callback.onPicked(sticker, emoji);
            }
        });
        container.addView(emojiView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        pickerSheet.setCustomView(container);
        pickerSheet.setOnDismissListener(d -> {
            try {
                emojiView.onDestroy();
            } catch (Throwable ignored) {
            }
        });
        showDialog(pickerSheet);
    }

    private TextView buildSheetButton(Context context, String label, boolean primary, Theme.ResourcesProvider rp) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tv.setTypeface(AndroidUtilities.bold());
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton, rp);
        tv.setBackground(Theme.createRoundRectDrawable(
                dp(10),
                primary ? accent : Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp), 0.14f)));
        tv.setTextColor(primary
                ? Theme.getColor(Theme.key_featuredStickers_buttonText, rp)
                : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        return tv;
    }

    private void showBroadcastDetail(EveAdminModels.BroadcastItem b) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        String safeTitle = EveAnimatedEmojiHelper.stripMarkupToPlainText(b.title);
        String bodyPlain = EveAnimatedEmojiHelper.stripMarkupToPlainText(b.message);
        if (TextUtils.isEmpty(bodyPlain) && !TextUtils.isEmpty(b.stickerDocId)) {
            bodyPlain = (!TextUtils.isEmpty(b.stickerEmoji) ? b.stickerEmoji + " " : "") + "Animated Sticker";
        }
        String detail = bodyPlain
                + "\n\nSent by: " + (!TextUtils.isEmpty(b.sentBy) ? b.sentBy : "Admin")
                + "\nTime: " + EveAdminUiChrome.formatDateTime(b.sentAt);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle(!TextUtils.isEmpty(safeTitle) ? safeTitle : "Announcement");
        builder.setMessage(detail);
        builder.setPositiveButton("Close", null);
        builder.setNeutralButton("Delete", (d, w) -> confirmDeleteBroadcast(b));
        showDialog(builder.create());
    }

    private void confirmDeleteBroadcast(EveAdminModels.BroadcastItem b) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        String safeTitle = EveAnimatedEmojiHelper.stripMarkupToPlainText(b.title);
        String targetLabel = !TextUtils.isEmpty(safeTitle) ? ("\"" + safeTitle + "\"") : "this announcement";
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Delete Broadcast");
        builder.setMessage("Remove " + targetLabel + " from EVE Announcements?");
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
