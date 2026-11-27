package org.telegram.ui.eve.exams;

import static org.telegram.messenger.AndroidUtilities.dp;

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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.admin.EveAdminUiChrome;
import org.telegram.ui.eve.auth.EveBackendClient;
import org.telegram.ui.eve.polls.EvePollModel;
import org.telegram.ui.eve.polls.EveTelegramPollCardView;

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
    private FrameLayout chatRoot;
    private ChannelHeaderTitleView titleView;

    private String channelName = "Announcements";
    private String channelAvatarUrl = "";
    private List<EveExamModels.BroadcastItem> broadcasts = Collections.emptyList();
    private List<EvePollModel> polls = Collections.emptyList();
    private final List<FeedEntry> feedEntries = new ArrayList<>();

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
                channelName,
                "official channel",
                false,
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
                this::applyChatTheme
        );
        listView = chrome.listView;
        chatRoot = chrome.rootView;

        if (simpleActionBar != null) {
            titleView = new ChannelHeaderTitleView(context, getResourceProvider());
            titleView.bind(channelName, channelAvatarUrl, feedEntries.size());
            simpleActionBar.setTitle(channelName);
            simpleActionBar.setSubtitle(buildChannelSubtitle());
            // Attach custom channel badge + avatar to center header container cleanly if space allows.
            if (simpleActionBar.getMenu() == null) {
                SimpleActionBarMenu menu = simpleActionBar.createMenu();
                menu.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
            }
        }

        applyChatTheme();
        rebuildFeedEntries();
        markVisibleAsSeen();

        fragmentView = chrome.rootView;
        reload();
        return chrome.rootView;
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
        } else if (cachedSum != null) {
            if (!TextUtils.isEmpty(cachedSum.channelName)) channelName = cachedSum.channelName;
            if (!TextUtils.isEmpty(cachedSum.channelAvatarUrl)) channelAvatarUrl = cachedSum.channelAvatarUrl;
        }
        List<EveExamModels.BroadcastItem> bc = repo.getCachedBroadcasts();
        List<EvePollModel> pl = repo.getCachedActivePolls();
        if (bc != null) broadcasts = bc;
        if (pl != null) polls = pl;
        loading = broadcasts.isEmpty() && polls.isEmpty();
    }

    private void applyChatTheme() {
        boolean dark = Theme.isCurrentThemeDark();
        int chatBg = dark ? 0xFF0E1621 : 0xFFE5EBEE;
        if (chatRoot != null) {
            chatRoot.setBackgroundColor(chatBg);
        }
        if (listView != null) {
            listView.setBackgroundColor(chatBg);
        }
        if (titleView != null) {
            titleView.updateColors();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        markVisibleAsSeen();
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

    private String buildChannelSubtitle() {
        if (feedEntries.isEmpty()) {
            return loading ? "updating..." : "official channel";
        }
        return feedEntries.size() + (feedEntries.size() == 1 ? " post" : " posts");
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
        if (simpleActionBar != null) {
            simpleActionBar.setTitle(channelName);
            simpleActionBar.setSubtitle(buildChannelSubtitle());
        }
        if (titleView != null) {
            titleView.bind(channelName, channelAvatarUrl, feedEntries.size());
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

        String lastDayKey = "";
        int itemIdCounter = BASE_FEED_ID;
        for (int i = 0; i < feedEntries.size(); i++) {
            FeedEntry entry = feedEntries.get(i);
            String dayKey = formatDayDividerLabel(entry.timestampMs);
            if (!dayKey.equals(lastDayKey)) {
                lastDayKey = dayKey;
                items.add(ChannelDatePillCell.Factory.of(500 + i, dayKey));
            }
            if (entry.broadcast != null) {
                items.add(ChannelPostBubbleView.Factory.of(
                        itemIdCounter++,
                        new ChannelPostBubbleView.Payload(channelName, entry.broadcast)));
            } else if (entry.poll != null) {
                int subIdx = entry.poll.id.equals(votingPollId) ? votingOptionIdx : -1;
                items.add(EveTelegramPollCardView.Factory.of(
                        itemIdCounter++,
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

        repo.submitPollVote(poll.id, optionIndex, (ok, err) -> {
            if (destroyed) return;
            votingPollId = "";
            votingOptionIdx = -1;
            if (Boolean.TRUE.equals(ok)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, "Vote recorded!").show();
                updateList();
            } else {
                polls = previousPolls;
                rebuildFeedEntries();
                updateList();
                BulletinFactory.of(this).createErrorBulletin(
                        !TextUtils.isEmpty(err) ? err : "Could not record vote.").show();
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

    private static final class ChannelHeaderTitleView extends LinearLayout {
        private final Theme.ResourcesProvider rp;
        private final AvatarDrawable avatarDrawable;
        private final BackupImageView avatarView;
        private final ImageView verifiedIcon;

        ChannelHeaderTitleView(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.rp = rp;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);

            verifiedIcon = new ImageView(context);
            verifiedIcon.setImageResource(R.drawable.msg_channel);
            verifiedIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            addView(verifiedIcon, LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

            avatarDrawable = new AvatarDrawable();
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(18));
            addView(avatarView, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL));

            updateColors();
        }

        void bind(String name, String avatarUrl, int postCount) {
            EveAdminUiChrome.bindExamAvatar(
                    avatarView, avatarDrawable, "eve_announcements",
                    !TextUtils.isEmpty(name) ? name : "Announcements",
                    avatarUrl, "", 4, 36, rp);
        }

        void updateColors() {
            verifiedIcon.setColorFilter(new PorterDuffColorFilter(
                    Theme.getColor(Theme.key_featuredStickers_addButton, rp), PorterDuff.Mode.SRC_IN));
        }
    }

    private static final class ChannelDatePillCell extends FrameLayout implements Theme.Colorable {
        private final Theme.ResourcesProvider rp;
        private final TextView pillView;

        ChannelDatePillCell(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.rp = rp;
            setPadding(dp(16), dp(8), dp(16), dp(4));

            pillView = new TextView(context);
            pillView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            pillView.setTypeface(AndroidUtilities.bold());
            pillView.setGravity(Gravity.CENTER);
            pillView.setPadding(dp(12), dp(4), dp(12), dp(4));
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
                    dp(14), dark ? 0x661F2C38 : 0x552E4A62));
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
                        && broadcast.sentAtMs == other.broadcast.sentAtMs;
            }

            @Override
            public int hashCode() {
                return (channelName + "|" + broadcast.id).hashCode();
            }
        }

        private final Theme.ResourcesProvider rp;
        private final LinearLayout bubble;
        private final TextView authorView;
        private final TextView titleView;
        private final TextView bodyView;
        private final TextView timeView;
        private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bubbleRect = new RectF();

        ChannelPostBubbleView(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.rp = rp;
            setPadding(dp(12), dp(5), dp(36), dp(5));

            bubble = new LinearLayout(context) {
                @Override
                protected void dispatchDraw(Canvas canvas) {
                    bubbleRect.set(0, 0, getWidth(), getHeight());
                    canvas.drawRoundRect(bubbleRect, dp(16), dp(16), bubblePaint);
                    super.dispatchDraw(canvas);
                }
            };
            bubble.setWillNotDraw(false);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(dp(14), dp(10), dp(14), dp(9));
            addView(bubble, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            authorView = new TextView(context);
            authorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            authorView.setTypeface(AndroidUtilities.bold());
            authorView.setSingleLine(true);
            authorView.setEllipsize(TextUtils.TruncateAt.END);
            bubble.addView(authorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 3));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setLineSpacing(dp(2), 1f);
            bubble.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

            bodyView = new TextView(context);
            bodyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
            bodyView.setLineSpacing(dp(2.5f), 1f);
            bodyView.setAutoLinkMask(Linkify.WEB_URLS);
            bubble.addView(bodyView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            timeView = new TextView(context);
            timeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            timeView.setGravity(Gravity.RIGHT);
            bubble.addView(timeView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 5, 0, 0));

            updateColors();
        }

        void bind(Payload payload) {
            authorView.setText(payload.channelName);
            EveExamModels.BroadcastItem b = payload.broadcast;
            if (!TextUtils.isEmpty(b.title)) {
                titleView.setVisibility(VISIBLE);
                titleView.setText(b.title);
            } else {
                titleView.setVisibility(GONE);
            }
            bodyView.setText(b.message);
            if (b.sentAtMs > 0L) {
                timeView.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(b.sentAtMs)));
            } else {
                timeView.setText("");
            }
            updateColors();
        }

        @Override
        public void updateColors() {
            boolean dark = Theme.isCurrentThemeDark();
            bubblePaint.setColor(dark ? 0xFF182533 : Theme.getColor(Theme.key_windowBackgroundWhite, rp));
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
