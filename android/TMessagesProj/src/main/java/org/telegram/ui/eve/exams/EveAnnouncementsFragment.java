package org.telegram.ui.eve.exams;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.admin.EveAdminUiChrome;
import org.telegram.ui.eve.polls.EvePollModel;
import org.telegram.ui.eve.polls.EveTelegramPollCardView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Announcements + Live Telegram Chat-Style Polls feed for students.
 */
public class EveAnnouncementsFragment extends BaseFragment {

    private static final int ID_RETRY = 100;
    private static final int BASE_POLL_ID = 500;
    private static final int BASE_ANNOUNCEMENT_ID = 1000;

    private UniversalRecyclerView listView;
    private List<EvePollModel> activePolls = new ArrayList<>();
    private List<EveExamModels.BroadcastItem> announcements = Collections.emptyList();
    private final EveAdminUiChrome.AutoSyncController autoSync =
            new EveAdminUiChrome.AutoSyncController(() -> reloadAll(false));
    private boolean loadingPolls = true;
    private boolean loadingBroadcasts = true;
    private boolean destroyed = false;
    private String pollsError = "";
    private String broadcastsError = "";
    private String submittingPollId = "";
    private int submittingOptionIndex = -1;

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
                "Announcements & Polls",
                "Live polls & broadcasts",
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

        fragmentView = chrome.rootView;
        reloadAll(true);
        return chrome.rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        autoSync.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        autoSync.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        autoSync.destroy();
        super.onFragmentDestroy();
    }

    private void reloadAll() {
        reloadAll(true);
    }

    private void reloadAll(boolean showLoading) {
        if (showLoading) {
            loadingPolls = true;
            loadingBroadcasts = true;
            pollsError = "";
            broadcastsError = "";
            updateList();
        }

        EveExamRepository.getInstance().loadActivePolls((polls, err) -> {
            if (destroyed) return;
            loadingPolls = false;
            autoSync.markSynced();
            activePolls = polls != null ? new ArrayList<>(polls) : new ArrayList<>();
            pollsError = err != null ? err : "";
            updateList();
        });

        EveExamRepository.getInstance().loadBroadcasts((list, err) -> {
            if (destroyed) return;
            loadingBroadcasts = false;
            autoSync.markSynced();
            announcements = list != null ? list : Collections.emptyList();
            broadcastsError = err != null ? err : "";
            updateList();
        });
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        boolean stillInitialLoading = (loadingPolls || loadingBroadcasts) && activePolls.isEmpty() && announcements.isEmpty();
        if (stillInitialLoading) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (!activePolls.isEmpty()) {
            items.add(UItem.asHeader("Community Polls"));
            for (int i = 0; i < activePolls.size(); i++) {
                EvePollModel p = activePolls.get(i);
                int subIdx = TextUtils.equals(p.id, submittingPollId) ? submittingOptionIndex : -1;
                items.add(EveTelegramPollCardView.Factory.of(
                        BASE_POLL_ID + i,
                        new EveTelegramPollCardView.Payload(p, false, subIdx, this::handleVote, null)));
            }
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.EveAnnouncements)));
        if (!broadcastsError.isEmpty() && announcements.isEmpty()) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_RETRY,
                    IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                    R.drawable.msg_retry,
                    "Retry Loading Announcements",
                    broadcastsError,
                    "Retry"));
            return;
        }

        if (announcements.isEmpty()) {
            items.add(UItem.asShadow(activePolls.isEmpty()
                    ? LocaleController.getString(R.string.EveNoAnnouncements)
                    : "No recent announcements."));
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM", Locale.getDefault());
        for (int i = 0; i < announcements.size(); i++) {
            EveExamModels.BroadcastItem b = announcements.get(i);
            String dateStr = b.sentAtMs > 0 ? fmt.format(new Date(b.sentAtMs)) : b.type;
            items.add(SettingsActivity.SettingCell.Factory.of(
                    BASE_ANNOUNCEMENT_ID + i,
                    IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                    R.drawable.msg_notifications,
                    b.title,
                    b.message,
                    dateStr));
        }
    }

    private void handleVote(EvePollModel poll, int optionIndex) {
        if (!TextUtils.isEmpty(submittingPollId)) {
            return;
        }
        submittingPollId = poll.id;
        submittingOptionIndex = optionIndex;
        updateList();

        EveExamRepository.getInstance().submitPollVote(poll.id, optionIndex, (ok, err) -> {
            if (destroyed) return;
            submittingPollId = "";
            submittingOptionIndex = -1;
            if (Boolean.TRUE.equals(ok) && TextUtils.isEmpty(err)) {
                for (int i = 0; i < activePolls.size(); i++) {
                    if (TextUtils.equals(activePolls.get(i).id, poll.id)) {
                        activePolls.set(i, activePolls.get(i).withRecordedVote(optionIndex));
                        break;
                    }
                }
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, "Vote recorded").show();
            } else {
                BulletinFactory.of(this).createErrorBulletin(
                        !TextUtils.isEmpty(err) ? err : "Could not record vote.").show();
            }
            updateList();
        });
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reloadAll();
            return;
        }
        int idx = item.id - BASE_ANNOUNCEMENT_ID;
        if (idx >= 0 && idx < announcements.size()) {
            EveExamModels.BroadcastItem b = announcements.get(idx);
            Activity activity = getParentActivity();
            if (activity != null) {
                AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
                builder.setTitle(b.title);
                builder.setMessage(b.message);
                builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
                showDialog(builder.create());
            }
        }
    }
}
