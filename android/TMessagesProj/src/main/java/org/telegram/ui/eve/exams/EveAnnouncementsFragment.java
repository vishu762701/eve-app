package org.telegram.ui.eve.exams;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.text.TextUtils;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextEmoji;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.admin.EveAdminBroadcastsFragment;
import org.telegram.ui.eve.admin.EveAdminConfig;
import org.telegram.ui.eve.admin.EveAdminModels;
import org.telegram.ui.eve.admin.EveAdminPollCreateFragment;
import org.telegram.ui.eve.admin.EveAdminRepository;
import org.telegram.ui.eve.admin.EveAdminUiChrome;
import org.telegram.ui.eve.admin.EveAnimatedEmojiHelper;
import org.telegram.ui.eve.auth.EveBackendClient;
import org.telegram.ui.eve.polls.EvePollModel;
import org.telegram.ui.eve.polls.EveTelegramPollCardView;
import org.telegram.utils.glass.BaseFragmentSimpleGlass;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Official EVE Announcements Channel feed modeled after a Telegram Channel chat:
 * ActionBar with editable channel avatar + verified badge, chat-tinted wallpaper surface,
 * centered pill date dividers, Telegram message bubbles for Broadcasts, and inline interactive Polls.
 */
public class EveAnnouncementsFragment extends BaseFragment {

    private static final int ID_RETRY = 101;
    private static final int BASE_FEED_ID = 1000;

    private final EveExamRepository repo = EveExamRepository.getInstance();
    private UniversalRecyclerView listView;
    private EveAnimatedEmojiHelper.EmojiHostSizeNotifierFrameLayout chatRoot;
    @Nullable
    private View actionBarFadeViewRef;
    @Nullable
    private EveAdminUiChrome.AvatarHeaderHandle avatarHeader;
    @Nullable
    private LinearLayout composerBar;
    @Nullable
    private View composerDivider;
    @Nullable
    private EditTextEmoji composerInput;
    @Nullable
    private ImageView quizBtn;
    @Nullable
    private ImageView sendBtn;
    private int topInsetPx = 0;
    private int bottomInsetPx = 0;
    private boolean sendingPost = false;

    private String channelName = "Announcements";
    private String channelAvatarUrl = "";
    private String channelAvatarEmoji = "";
    private List<EveExamModels.BroadcastItem> broadcasts = Collections.emptyList();
    private List<EvePollModel> polls = Collections.emptyList();
    private final List<FeedEntry> feedEntries = new ArrayList<>();
    private final java.util.Map<Integer, FeedEntry> entryByItemId = new java.util.HashMap<>();

    private String votingPollId = "";
    private int votingOptionIdx = -1;
    private boolean loading = true;
    private boolean destroyed = false;
    private boolean scrolledInitial = false;
    private String errorMessage = "";
    private final EveAdminUiChrome.AutoSyncController autoSync = new EveAdminUiChrome.AutoSyncController(this::reload);

    private static final class FeedEntry {
        final long timestampMs;
        final EveExamModels.BroadcastItem broadcast;
        final EvePollModel poll;

        FeedEntry(EveExamModels.BroadcastItem broadcast) {
            this.timestampMs = broadcast.sentAtMs;
            this.broadcast = broadcast;
            this.poll = null;
        }

        FeedEntry(EvePollModel poll) {
            this.timestampMs = poll.createdAt;
            this.broadcast = null;
            this.poll = poll;
        }
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
        if (composerInput != null && composerInput.isPopupShowing()) {
            if (invoked) {
                composerInput.hidePopup(true);
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private boolean isChannelAdmin() {
        return EveAdminUiChrome.hasCachedAdminRight(EveAdminModels.RIGHT_SEND_BROADCASTS);
    }

    private boolean canManageQuizzes() {
        return EveAdminUiChrome.hasCachedAdminRight(EveAdminModels.RIGHT_MODERATE_FEEDBACK)
                || isChannelAdmin();
    }

    @Override
    public View createView(Context context) {
        destroyed = false;
        setHasOwnBackground(true);

        hydrateCachedChannelData();

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                EveAnimatedEmojiHelper.decode(channelName, null),
                null,
                false,
                false,
                this::fillItems,
                this::onClick,
                this::onLongClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        }
                    }
                },
                this::applyChatTheme
        );
        listView = chrome.listView;
        chatRoot = chrome.rootView;
        actionBarFadeViewRef = chrome.actionBarFadeView;

        avatarHeader = EveAdminUiChrome.attachAvatarActionBarHeader(
                context,
                simpleActionBar,
                16,
                getResourceProvider());

        if (isChannelAdmin()) {
            attachChannelComposer(context);
        }

        if (EveAdminConfig.isAdminAvailable() && EveAdminRepository.getInstance().getCachedSnapshot() == null) {
            EveAdminRepository.getInstance().loadDashboardAsync(new EveAdminRepository.ResultCallback<EveAdminModels.DashboardSnapshot>() {
                @Override
                public void onSuccess(EveAdminModels.DashboardSnapshot data) {
                    if (!destroyed && composerBar == null && isChannelAdmin() && getParentActivity() != null) {
                        attachChannelComposer(getParentActivity());
                        updateHeader();
                        applyListPaddingForComposer();
                    }
                }

                @Override
                public void onError(String message, int httpStatus) {
                }
            });
        }

        updateHeader();

        applyChatTheme();
        rebuildFeedEntries();
        markVisibleAsSeen();

        fragmentView = chrome.rootView;
        reload();
        return chrome.rootView;
    }

    private void attachChannelComposer(@NonNull Context context) {
        if (chatRoot == null || composerBar != null) {
            return;
        }
        Theme.ResourcesProvider rp = getResourceProvider();
        composerBar = new LinearLayout(context);
        composerBar.setOrientation(LinearLayout.VERTICAL);

        composerDivider = new View(context);
        composerBar.addView(composerDivider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));

        composerInput = EveAnimatedEmojiHelper.createEditTextEmoji(
                context, chatRoot, this, "Message", "", true, rp);
        if (composerInput.getEditText() != null) {
            composerInput.getEditText().setMaxLines(4);
            composerInput.getEditText().setMinLines(1);
        }
        composerInput.setBackground(null);
        composerInput.setPadding(0, 0, dp(4), 0);
        row.addView(composerInput, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        quizBtn = new ImageView(context);
        quizBtn.setScaleType(ImageView.ScaleType.CENTER);
        quizBtn.setImageResource(R.drawable.msg_polls);
        quizBtn.setContentDescription("Create Quiz");
        quizBtn.setOnClickListener(v -> presentFragment(new EveAdminPollCreateFragment(this::reload)));
        row.addView(quizBtn, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 2, 0, 2, 0));

        sendBtn = new ImageView(context);
        sendBtn.setScaleType(ImageView.ScaleType.CENTER);
        sendBtn.setImageResource(R.drawable.ic_send);
        sendBtn.setContentDescription("Send");
        sendBtn.setOnClickListener(v -> sendComposerPost());
        row.addView(sendBtn, LayoutHelper.createLinear(38, 38, Gravity.CENTER_VERTICAL, 4, 0, 2, 0));

        composerBar.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        chatRoot.addView(composerBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        ViewCompat.setOnApplyWindowInsetsListener(chatRoot, (v, insets) -> {
            final Insets systemInsets = AndroidUtilities.getDefaultWindowInsets(insets, false);
            topInsetPx = systemInsets.top;
            bottomInsetPx = systemInsets.bottom;
            EveAdminUiChrome.updateActionBarFadeHeight(actionBarFadeViewRef, topInsetPx);
            if (composerBar != null) {
                composerBar.setPadding(0, 0, 0, bottomInsetPx);
            }
            applyListPaddingForComposer();
            return WindowInsetsCompat.CONSUMED;
        });

        applyComposerThemeColors();
        applyListPaddingForComposer();
    }

    private void applyListPaddingForComposer() {
        if (listView == null) return;
        int extraBottom = composerBar != null ? dp(58) : 0;
        BaseFragmentSimpleGlass.setListViewParams(listView, topInsetPx, bottomInsetPx + extraBottom, 0, 0);
    }

    private void applyComposerThemeColors() {
        Theme.ResourcesProvider rp = getResourceProvider();
        if (composerBar != null) {
            composerBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, rp));
        }
        if (composerDivider != null) {
            composerDivider.setBackgroundColor(Theme.getColor(Theme.key_divider, rp));
        }
        if (quizBtn != null) {
            quizBtn.setColorFilter(new PorterDuffColorFilter(
                    Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp), PorterDuff.Mode.MULTIPLY));
            quizBtn.setBackground(Theme.createSelectorDrawable(
                    Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp), 0.14f), 1));
        }
        if (sendBtn != null) {
            int blue = Theme.getColor(Theme.key_featuredStickers_addButton, rp);
            int white = Theme.getColor(Theme.key_featuredStickers_buttonText, rp);
            sendBtn.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(38), blue, Theme.multAlpha(blue, 0.85f)));
            sendBtn.setColorFilter(new PorterDuffColorFilter(white, PorterDuff.Mode.MULTIPLY));
        }
    }

    private void sendComposerPost() {
        if (sendingPost || composerInput == null) {
            return;
        }
        String encoded = EveAnimatedEmojiHelper.encode(composerInput.getText()).trim();
        if (encoded.isEmpty()) {
            BulletinFactory.of(this).createErrorBulletin("Type a message or pick an emoji.").show();
            return;
        }
        sendingPost = true;
        if (sendBtn != null) sendBtn.setAlpha(0.5f);
        EveAdminRepository adminRepo = EveAdminRepository.getInstance();
        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                sendingPost = false;
                if (destroyed) return;
                if (sendBtn != null) sendBtn.setAlpha(1f);
                if (composerInput != null) {
                    composerInput.setText("");
                    composerInput.hidePopup(true);
                }
                scrolledInitial = false;
                reload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                sendingPost = false;
                if (destroyed) return;
                if (sendBtn != null) sendBtn.setAlpha(1f);
                BulletinFactory.of(EveAnnouncementsFragment.this)
                        .createErrorBulletin(message != null ? message : "Failed to post announcement.").show();
            }
        };
        adminRepo.runWithAdminToken(cb, token -> {
            adminRepo.sendBroadcast(token, "", encoded, "", "", 0, "");
            adminRepo.postSuccess(cb, null);
        });
    }

    private void hydrateCachedChannelData() {
        EveBackendClient.AppConfigResult cfg = repo.getCachedAppConfig();
        EveExamRepository.AnnouncementChannelSummary cachedSum = repo.getCachedAnnouncementChannelSummary();
        if (cfg != null) {
            if (!TextUtils.isEmpty(cfg.announcementChannelName)) {
                channelName = cfg.announcementChannelName;
            }
            if (!TextUtils.isEmpty(cfg.announcementChannelAvatarUrl)) {
                channelAvatarUrl = cfg.announcementChannelAvatarUrl;
            }
            if (!TextUtils.isEmpty(cfg.announcementChannelAvatarEmoji)) {
                channelAvatarEmoji = cfg.announcementChannelAvatarEmoji;
            }
        } else if (cachedSum != null) {
            if (!TextUtils.isEmpty(cachedSum.channelName)) channelName = cachedSum.channelName;
            if (!TextUtils.isEmpty(cachedSum.channelAvatarUrl)) channelAvatarUrl = cachedSum.channelAvatarUrl;
            if (!TextUtils.isEmpty(cachedSum.channelAvatarEmoji)) channelAvatarEmoji = cachedSum.channelAvatarEmoji;
        }
        List<EveExamModels.BroadcastItem> bc = repo.getCachedBroadcasts();
        List<EvePollModel> pl = repo.getCachedActivePolls();
        if (bc != null) broadcasts = bc;
        if (pl != null) polls = pl;
        loading = broadcasts.isEmpty() && polls.isEmpty();
    }

    private void applyChatTheme() {
        int bg = Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider());
        if (chatRoot != null) {
            chatRoot.setBackgroundColor(bg);
        }
        if (listView != null) {
            listView.setBackgroundColor(bg);
        }
        if (avatarHeader != null) {
            avatarHeader.updateColors();
        }
        applyComposerThemeColors();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (composerInput != null) {
            composerInput.onResume();
        }
        markVisibleAsSeen();
        autoSync.onResume();
    }

    @Override
    public void onPause() {
        if (composerInput != null) {
            composerInput.onPause();
        }
        autoSync.onPause();
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        autoSync.destroy();
        if (composerInput != null) {
            composerInput.onDestroy();
            composerInput = null;
        }
        super.onFragmentDestroy();
    }

    private void reload() {
        if (destroyed) return;
        loading = feedEntries.isEmpty();
        errorMessage = "";
        if (loading) {
            updateList();
        }

        final AtomicInteger remaining = new AtomicInteger(3);
        final String[] firstError = new String[] { "" };

        Runnable onBatchStepDone = () -> {
            if (destroyed) return;
            if (remaining.decrementAndGet() > 0) {
                rebuildFeedEntries();
                updateHeader();
                updateList();
                return;
            }
            loading = false;
            autoSync.markSynced();
            errorMessage = (feedEntries.isEmpty() && !firstError[0].isEmpty()) ? firstError[0] : "";
            rebuildFeedEntries();
            updateHeader();
            markVisibleAsSeen();
            updateList();
            if (!scrolledInitial && listView != null && listView.adapter != null && listView.adapter.getItemCount() > 0) {
                scrolledInitial = true;
                listView.post(() -> {
                    if (!destroyed && listView != null && listView.adapter != null && listView.adapter.getItemCount() > 0) {
                        listView.scrollToPosition(listView.adapter.getItemCount() - 1);
                    }
                });
            }
        };

        repo.fetchAppConfig((cfg, err) -> {
            if (!destroyed && cfg != null) {
                if (!TextUtils.isEmpty(cfg.announcementChannelName)) {
                    channelName = cfg.announcementChannelName;
                }
                channelAvatarUrl = cfg.announcementChannelAvatarUrl != null ? cfg.announcementChannelAvatarUrl : "";
                channelAvatarEmoji = cfg.announcementChannelAvatarEmoji != null ? cfg.announcementChannelAvatarEmoji : "";
            }
            onBatchStepDone.run();
        });

        repo.loadBroadcasts((loaded, err) -> {
            if (!destroyed) {
                if (loaded != null) {
                    broadcasts = loaded;
                } else if (err != null && !err.isEmpty() && firstError[0].isEmpty()) {
                    firstError[0] = err;
                }
            }
            onBatchStepDone.run();
        });

        repo.loadActivePolls((loaded, err) -> {
            if (!destroyed) {
                if (loaded != null) {
                    polls = loaded;
                } else if (err != null && !err.isEmpty() && firstError[0].isEmpty()) {
                    firstError[0] = err;
                }
            }
            onBatchStepDone.run();
        });
    }

    private void updateHeader() {
        if (avatarHeader != null) {
            avatarHeader.update(
                    "eve_announcements_channel",
                    channelName,
                    null,
                    channelAvatarUrl,
                    channelAvatarEmoji,
                    -1);
            if (isChannelAdmin()) {
                avatarHeader.setOnClickListener(v -> presentFragment(new EveAdminBroadcastsFragment()));
            } else {
                avatarHeader.setOnClickListener(null);
            }
        } else if (simpleActionBar != null) {
            simpleActionBar.setTitle(EveAnimatedEmojiHelper.decode(channelName, null));
            simpleActionBar.setSubtitle(null);
        }
    }

    private void rebuildFeedEntries() {
        feedEntries.clear();
        if (broadcasts != null) {
            for (EveExamModels.BroadcastItem b : broadcasts) {
                if (b != null) feedEntries.add(new FeedEntry(b));
            }
        }
        if (polls != null) {
            for (EvePollModel p : polls) {
                if (p != null) feedEntries.add(new FeedEntry(p));
            }
        }
        Collections.sort(feedEntries, new Comparator<FeedEntry>() {
            @Override
            public int compare(FeedEntry a, FeedEntry b) {
                return Long.compare(a.timestampMs, b.timestampMs);
            }
        });
    }

    private void markVisibleAsSeen() {
        long latest = 0L;
        for (FeedEntry entry : feedEntries) {
            if (entry.timestampMs > latest) {
                latest = entry.timestampMs;
            }
        }
        if (latest > 0L || !loading) {
            repo.markAllAnnouncementsSeen(latest);
        }
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        if (loading && feedEntries.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (!errorMessage.isEmpty() && feedEntries.isEmpty()) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_RETRY,
                    IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                    R.drawable.msg_retry,
                    "Retry Loading Channel",
                    errorMessage,
                    "Retry"));
            return;
        }

        if (feedEntries.isEmpty()) {
            items.add(ChannelDatePillCell.Factory.of(900, "No announcements or polls posted yet"));
            return;
        }

        entryByItemId.clear();
        String lastDayKey = "";
        int itemIdCounter = BASE_FEED_ID;
        for (int i = 0; i < feedEntries.size(); i++) {
            FeedEntry entry = feedEntries.get(i);
            String dayKey = formatDayDividerLabel(entry.timestampMs);
            if (!dayKey.equals(lastDayKey)) {
                lastDayKey = dayKey;
                items.add(ChannelDatePillCell.Factory.of(500 + i, dayKey));
            }
            int rowId = itemIdCounter++;
            entryByItemId.put(rowId, entry);
            if (entry.broadcast != null) {
                items.add(ChannelPostBubbleView.Factory.of(
                        rowId,
                        new ChannelPostBubbleView.Payload(channelName, entry.broadcast)));
            } else if (entry.poll != null) {
                int subIdx = entry.poll.id.equals(votingPollId) ? votingOptionIdx : -1;
                items.add(EveTelegramPollCardView.Factory.of(
                        rowId,
                        new EveTelegramPollCardView.Payload(entry.poll, false, subIdx, this::handlePollVote, null)));
            }
        }
        items.add(UItem.asSpace(dp(12)));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
        }
    }

    private boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item == null || item.id < BASE_FEED_ID) {
            return false;
        }
        FeedEntry entry = entryByItemId.get(item.id);
        if (entry == null) {
            return false;
        }
        if (entry.broadcast != null && isChannelAdmin()) {
            ItemOptions options = ItemOptions.makeOptions(this, view);
            options.add(R.drawable.msg_delete, "Delete Post", () -> confirmDeleteBroadcast(entry.broadcast));
            options.makeRed(0);
            options.show();
            return true;
        }
        if (entry.poll != null && canManageQuizzes()) {
            ItemOptions options = ItemOptions.makeOptions(this, view);
            options.add(
                    R.drawable.msg_archive,
                    entry.poll.active ? "Close Quiz" : "Reopen Quiz",
                    () -> toggleChannelPoll(entry.poll));
            options.add(
                    R.drawable.msg_delete,
                    "Delete Quiz",
                    () -> confirmDeleteChannelPoll(entry.poll));
            options.makeRed(1);
            options.show();
            return true;
        }
        return false;
    }

    private void confirmDeleteBroadcast(EveExamModels.BroadcastItem b) {
        Activity activity = getParentActivity();
        if (activity == null || b == null) return;
        showDialog(new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle("Delete Announcement Post")
                .setMessage("Remove this post from EVE Announcements?")
                .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                    EveAdminRepository adminRepo = EveAdminRepository.getInstance();
                    EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
                        @Override
                        public void onSuccess(Void data) {
                            if (destroyed) return;
                            BulletinFactory.of(EveAnnouncementsFragment.this)
                                    .createSimpleBulletin(R.raw.ic_delete, "Post deleted").show();
                            reload();
                        }

                        @Override
                        public void onError(String message, int httpStatus) {
                            if (destroyed) return;
                            BulletinFactory.of(EveAnnouncementsFragment.this)
                                    .createErrorBulletin(message != null ? message : "Delete failed.").show();
                        }
                    };
                    adminRepo.runWithAdminToken(cb, token -> {
                        adminRepo.deleteBroadcast(token, b.id);
                        adminRepo.postSuccess(cb, null);
                    });
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .create());
    }

    private void toggleChannelPoll(EvePollModel poll) {
        if (poll == null) return;
        EveAdminRepository adminRepo = EveAdminRepository.getInstance();
        boolean targetActive = !poll.active;
        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                if (destroyed) return;
                BulletinFactory.of(EveAnnouncementsFragment.this).createSimpleBulletin(
                        R.raw.contact_check, targetActive ? "Quiz reopened" : "Quiz closed").show();
                reload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                BulletinFactory.of(EveAnnouncementsFragment.this)
                        .createErrorBulletin(message != null ? message : "Status update failed.").show();
            }
        };
        adminRepo.runWithAdminToken(cb, token -> {
            adminRepo.setPollActive(token, poll.id, targetActive);
            adminRepo.postSuccess(cb, null);
        });
    }

    private void confirmDeleteChannelPoll(EvePollModel poll) {
        Activity activity = getParentActivity();
        if (activity == null || poll == null) return;
        showDialog(new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle("Delete Quiz")
                .setMessage("Delete \"" + EveAnimatedEmojiHelper.stripMarkupToPlainText(poll.question) + "\" and all recorded votes?")
                .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                    EveAdminRepository adminRepo = EveAdminRepository.getInstance();
                    EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
                        @Override
                        public void onSuccess(Void data) {
                            if (destroyed) return;
                            BulletinFactory.of(EveAnnouncementsFragment.this)
                                    .createSimpleBulletin(R.raw.ic_delete, "Quiz deleted").show();
                            reload();
                        }

                        @Override
                        public void onError(String message, int httpStatus) {
                            if (destroyed) return;
                            BulletinFactory.of(EveAnnouncementsFragment.this)
                                    .createErrorBulletin(message != null ? message : "Delete failed.").show();
                        }
                    };
                    adminRepo.runWithAdminToken(cb, token -> {
                        adminRepo.deletePoll(token, poll.id);
                        adminRepo.postSuccess(cb, null);
                    });
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .create());
    }

    private void handlePollVote(EvePollModel poll, int optionIndex) {
        if (poll == null || !TextUtils.isEmpty(votingPollId)) {
            return;
        }
        final List<EvePollModel> previousPolls = new ArrayList<>(polls);
        votingPollId = poll.id;
        votingOptionIdx = optionIndex;

        List<EvePollModel> optimistic = new ArrayList<>(polls.size());
        for (EvePollModel p : polls) {
            if (p != null && poll.id.equals(p.id)) {
                optimistic.add(p.withRecordedVote(optionIndex));
            } else {
                optimistic.add(p);
            }
        }
        polls = optimistic;
        rebuildFeedEntries();
        updateList();

        repo.submitQuizVote(poll.id, optionIndex, (outcome, err) -> {
            if (destroyed) return;
            votingPollId = "";
            votingOptionIdx = -1;
            if (outcome != null) {
                List<EvePollModel> revealed = new ArrayList<>(polls.size());
                for (EvePollModel p : polls) {
                    if (p != null && poll.id.equals(p.id)) {
                        revealed.add(p.withRecordedVote(
                                optionIndex,
                                outcome.correctOptionIndex,
                                outcome.isCorrect,
                                outcome.explanation));
                    } else {
                        revealed.add(p);
                    }
                }
                polls = revealed;
                rebuildFeedEntries();
                String notice = outcome.isCorrect != null
                        ? (outcome.isCorrect ? "Correct answer!" : "Answer recorded")
                        : "Answer recorded!";
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, notice).show();
                updateList();
            } else {
                polls = previousPolls;
                rebuildFeedEntries();
                updateList();
                BulletinFactory.of(this).createErrorBulletin(
                        !TextUtils.isEmpty(err) ? err : "Could not record answer.").show();
            }
        });
    }

    private static String formatDayDividerLabel(long timestampMs) {
        if (timestampMs <= 0L) return "Pinned";
        Calendar msgCal = Calendar.getInstance();
        msgCal.setTimeInMillis(timestampMs);
        Calendar nowCal = Calendar.getInstance();
        if (msgCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
                && msgCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)) {
            return "Today";
        }
        nowCal.add(Calendar.DAY_OF_YEAR, -1);
        if (msgCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
                && msgCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)) {
            return "Yesterday";
        }
        return new SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(new Date(timestampMs));
    }

    private static final class ChannelDatePillCell extends FrameLayout implements Theme.Colorable {
        private final Theme.ResourcesProvider rp;
        private final TextView pillView;

        ChannelDatePillCell(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.rp = rp;
            setTag(RecyclerListView.TAG_NOT_SECTION);
            setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            setPadding(dp(16), dp(10), dp(16), dp(6));

            pillView = new TextView(context);
            pillView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            pillView.setTypeface(AndroidUtilities.bold());
            pillView.setGravity(Gravity.CENTER);
            pillView.setPadding(dp(14), dp(4), dp(14), dp(4));
            addView(pillView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
            updateColors();
        }

        void bind(String label) {
            pillView.setText(label);
            updateColors();
        }

        @Override
        public void updateColors() {
            boolean dark = Theme.isCurrentThemeDark();
            pillView.setTextColor(0xFFFFFFFF);
            pillView.setBackground(Theme.createRoundRectDrawable(
                    dp(14), dark ? 0x7A22303C : 0x6B4B6478));
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_windowBackgroundWhite);
        }

        static class Factory extends UItem.UItemFactory<ChannelDatePillCell> {
            static {
                setup(new Factory());
            }

            static UItem of(int id, String label) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.text = label;
                item.selectable = false;
                return item;
            }

            @Override
            public ChannelDatePillCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new ChannelDatePillCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((ChannelDatePillCell) view).bind(item.text != null ? item.text.toString() : "");
            }

            @Override
            public boolean isClickable() {
                return false;
            }
        }
    }

    private static final class ChannelPostBubbleView extends FrameLayout implements Theme.Colorable {
        static final class Payload {
            final String channelName;
            final EveExamModels.BroadcastItem broadcast;

            Payload(String channelName, EveExamModels.BroadcastItem broadcast) {
                this.channelName = channelName != null ? channelName : "Announcements";
                this.broadcast = broadcast;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof Payload)) return false;
                Payload other = (Payload) o;
                return TextUtils.equals(channelName, other.channelName)
                        && TextUtils.equals(broadcast.id, other.broadcast.id)
                        && TextUtils.equals(broadcast.title, other.broadcast.title)
                        && TextUtils.equals(broadcast.message, other.broadcast.message)
                        && TextUtils.equals(broadcast.stickerDocId, other.broadcast.stickerDocId)
                        && broadcast.sentAtMs == other.broadcast.sentAtMs;
            }

            @Override
            public int hashCode() {
                return (channelName + "|" + broadcast.id).hashCode();
            }
        }

        private final Theme.ResourcesProvider rp;
        private final LinearLayout bubble;
        private final BackupImageView stickerView;
        private final TextView stickerFallbackEmojiView;
        private final TextView authorView;
        private final TextView titleView;
        private final TextView bodyView;
        private final TextView timeView;
        private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bubbleRect = new RectF();
        private String boundStickerId = "";

        ChannelPostBubbleView(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.rp = rp;
            setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            setPadding(dp(12), dp(5), dp(12), dp(5));

            bubble = new LinearLayout(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int avail = MeasureSpec.getSize(widthMeasureSpec);
                    int mode = MeasureSpec.getMode(widthMeasureSpec);
                    if (avail > 0 && mode != MeasureSpec.UNSPECIFIED) {
                        int maxBubbleW = Math.max(dp(180), Math.round(avail * 0.88f));
                        widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxBubbleW, MeasureSpec.AT_MOST);
                    }
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                }

                @Override
                protected void dispatchDraw(Canvas canvas) {
                    bubbleRect.set(0, 0, getWidth(), getHeight());
                    canvas.drawRoundRect(bubbleRect, dp(16), dp(16), bubblePaint);
                    super.dispatchDraw(canvas);
                }
            };
            bubble.setWillNotDraw(false);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(dp(14), dp(11), dp(14), dp(9));
            bubble.setElevation(0);
            addView(bubble, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.START));

            stickerView = new BackupImageView(context);
            stickerView.setAspectFit(true);
            stickerView.setVisibility(GONE);
            bubble.addView(stickerView, LayoutHelper.createLinear(132, 132, Gravity.START, 0, 2, 0, 8));

            stickerFallbackEmojiView = new TextView(context);
            stickerFallbackEmojiView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 48);
            stickerFallbackEmojiView.setVisibility(GONE);
            bubble.addView(stickerFallbackEmojiView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.START, 0, 2, 0, 8));

            authorView = EveAnimatedEmojiHelper.createEmojiTextView(context);
            authorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            authorView.setTypeface(AndroidUtilities.bold());
            authorView.setSingleLine(true);
            authorView.setEllipsize(TextUtils.TruncateAt.END);
            bubble.addView(authorView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 3));

            titleView = EveAnimatedEmojiHelper.createEmojiTextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setLineSpacing(dp(2), 1f);
            bubble.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

            bodyView = EveAnimatedEmojiHelper.createEmojiTextView(context);
            bodyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
            bodyView.setLineSpacing(dp(2.5f), 1f);
            bodyView.setAutoLinkMask(Linkify.WEB_URLS);
            bubble.addView(bodyView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            timeView = new TextView(context);
            timeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            timeView.setGravity(Gravity.RIGHT);
            bubble.addView(timeView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

            updateColors();
        }

        void bind(Payload payload) {
            EveAnimatedEmojiHelper.bindText(authorView, payload.channelName);
            EveExamModels.BroadcastItem b = payload.broadcast;
            bindSticker(b);
            if (!TextUtils.isEmpty(b.title)) {
                titleView.setVisibility(VISIBLE);
                EveAnimatedEmojiHelper.bindText(titleView, b.title);
            } else {
                titleView.setVisibility(GONE);
            }
            if (!TextUtils.isEmpty(b.message)) {
                bodyView.setVisibility(VISIBLE);
                EveAnimatedEmojiHelper.bindText(bodyView, b.message);
            } else {
                bodyView.setVisibility(GONE);
                bodyView.setText("");
            }
            if (b.sentAtMs > 0L) {
                timeView.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(b.sentAtMs)));
            } else {
                timeView.setText("");
            }
            updateColors();
        }

        private void bindSticker(EveExamModels.BroadcastItem b) {
            if (b == null || !b.hasSticker()) {
                boundStickerId = "";
                stickerView.setVisibility(GONE);
                stickerFallbackEmojiView.setVisibility(GONE);
                return;
            }
            boundStickerId = b.stickerDocId;
            long docId = 0L;
            long accessHash = 0L;
            try {
                docId = Long.parseLong(b.stickerDocId);
            } catch (Throwable ignored) {
            }
            try {
                if (!TextUtils.isEmpty(b.stickerAccessHash)) {
                    accessHash = Long.parseLong(b.stickerAccessHash);
                }
            } catch (Throwable ignored) {
            }

            TLRPC.Document resolvedDoc = findInstalledStickerDocument(docId);
            if (resolvedDoc == null && docId != 0L) {
                TLRPC.TL_document synthetic = new TLRPC.TL_document();
                synthetic.id = docId;
                synthetic.access_hash = accessHash;
                synthetic.dc_id = b.stickerDcId > 0 ? b.stickerDcId : 2;
                synthetic.mime_type = "application/x-tgsticker";
                synthetic.attributes.add(new TLRPC.TL_documentAttributeSticker());
                resolvedDoc = synthetic;
            }

            if (resolvedDoc != null && EveAnimatedEmojiHelper.isMtprotoAvailable()) {
                stickerFallbackEmojiView.setVisibility(GONE);
                stickerView.setVisibility(VISIBLE);
                final String targetId = b.stickerDocId;
                try {
                    stickerView.setImage(
                            ImageLocation.getForDocument(resolvedDoc),
                            "132_132",
                            DocumentObject.getSvgThumb(resolvedDoc, Theme.key_windowBackgroundGray, 0.25f),
                            null);
                } catch (Throwable ignored) {
                }
                if (resolvedDoc.size <= 0 && docId != 0L) {
                    try {
                        AnimatedEmojiDrawable.getDocumentFetcher(UserConfig.selectedAccount).fetchDocument(docId, fetched -> {
                            if (fetched != null && targetId.equals(boundStickerId)) {
                                try {
                                    stickerView.setImage(
                                            ImageLocation.getForDocument(fetched),
                                            "132_132",
                                            DocumentObject.getSvgThumb(fetched, Theme.key_windowBackgroundGray, 0.25f),
                                            null);
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            } else if (!TextUtils.isEmpty(b.stickerEmoji)) {
                stickerView.setVisibility(GONE);
                stickerFallbackEmojiView.setText(b.stickerEmoji);
                stickerFallbackEmojiView.setVisibility(VISIBLE);
            } else {
                stickerView.setVisibility(GONE);
                stickerFallbackEmojiView.setVisibility(GONE);
            }
        }

        @Nullable
        private static TLRPC.Document findInstalledStickerDocument(long docId) {
            if (docId == 0L) return null;
            try {
                MediaDataController mdc = MediaDataController.getInstance(UserConfig.selectedAccount);
                int[] types = new int[] { MediaDataController.TYPE_IMAGE, MediaDataController.TYPE_EMOJIPACKS };
                for (int type : types) {
                    ArrayList<TLRPC.TL_messages_stickerSet> sets = mdc.getStickerSets(type);
                    if (sets == null) continue;
                    for (TLRPC.TL_messages_stickerSet set : sets) {
                        if (set == null || set.documents == null) continue;
                        for (TLRPC.Document d : set.documents) {
                            if (d != null && d.id == docId) {
                                return d;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        @Override
        public void updateColors() {
            bubblePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, rp));
            authorView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, rp));
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
            bodyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
            bodyView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, rp));
            timeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, rp));
            bubble.invalidate();
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_windowBackgroundWhite);
            colors.add(Theme.key_windowBackgroundWhiteBlueHeader);
            colors.add(Theme.key_windowBackgroundWhiteBlackText);
            colors.add(Theme.key_windowBackgroundWhiteGrayText3);
        }

        static class Factory extends UItem.UItemFactory<ChannelPostBubbleView> {
            static {
                setup(new Factory());
            }

            static UItem of(int id, Payload payload) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.object = payload;
                item.selectable = false;
                return item;
            }

            @Override
            public ChannelPostBubbleView createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new ChannelPostBubbleView(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((ChannelPostBubbleView) view).bind((Payload) item.object);
            }

            @Override
            public boolean isClickable() {
                return false;
            }
        }
    }
}
