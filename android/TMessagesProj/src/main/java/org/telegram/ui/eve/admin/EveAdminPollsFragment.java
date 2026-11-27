package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.view.View;
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
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.polls.EvePollModel;
import org.telegram.ui.eve.polls.EveTelegramPollCardView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Full-screen Telegram Admin Polls Manager rendering every poll with
 * {@link EveTelegramPollCardView}, live results, status toggle, and confirmed deletion.
 */
public class EveAdminPollsFragment extends BaseFragment {

    private static final int MENU_ADD = 1;

    private static final int ID_RETRY = 101;
    private static final int BASE_POLL_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private List<EveAdminModels.PollItem> polls = Collections.emptyList();
    private boolean loading = true;
    private boolean destroyed = false;
    private String errorText = "";
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

        List<EveAdminModels.PollItem> cachedPolls = repo.getCachedPolls();
        if (cachedPolls != null && !cachedPolls.isEmpty()) {
            polls = cachedPolls;
            loading = false;
        }

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Student Quizzes",
                polls.isEmpty() ? "Loading..." : polls.size() + " quiz(zes)",
                true,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_ADD) {
                            presentFragment(new EveAdminPollCreateFragment(EveAdminPollsFragment.this::reload));
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

    public void reload() {
        loading = polls.isEmpty();
        errorText = "";
        if (loading) {
            updateList();
        }

        EveAdminRepository.ResultCallback<List<EveAdminModels.PollItem>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.PollItem>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.PollItem> data) {
                        if (destroyed) return;
                        loading = false;
                        errorText = "";
                        autoSync.markSynced();
                        polls = data != null ? data : Collections.emptyList();
                        updateSubtitle();
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
                        autoSync.markSynced();
                        errorText = message != null ? message : "Unable to load polls.";
                        updateSubtitle();
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchPolls(token)));
    }

    private void updateSubtitle() {
        if (simpleActionBar == null) return;
        if (!errorText.isEmpty() && polls.isEmpty()) {
            simpleActionBar.setSubtitle("Unable to load");
        } else {
            simpleActionBar.setSubtitle(polls.size() + " quiz(zes)");
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
                    "Retry Loading Quizzes",
                    errorText,
                    "Retry"));
            items.add(UItem.asShadow(null));
        }

        if (loading && polls.isEmpty()) {
            for (int i = 0; i < 4; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (polls.isEmpty()) {
            items.add(UItem.asHeader("Student Quizzes"));
            items.add(UItem.asShadow("No quizzes yet. Tap + in the top bar to create a Telegram Quiz."));
            return;
        }

        EveTelegramPollCardView.AdminActionListener adminListener = new EveTelegramPollCardView.AdminActionListener() {
            @Override
            public void onToggleActive(EvePollModel poll) {
                togglePollStatus(poll);
            }

            @Override
            public void onDeletePoll(EvePollModel poll) {
                confirmDeletePoll(poll);
            }
        };

        for (int i = 0; i < polls.size(); i++) {
            EvePollModel model = EvePollModel.fromAdminItem(polls.get(i));
            items.add(EveTelegramPollCardView.Factory.of(
                    BASE_POLL_ID + i,
                    new EveTelegramPollCardView.Payload(model, true, -1, null, adminListener)));
        }
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
        }
    }

    private void togglePollStatus(EvePollModel poll) {
        final boolean targetActive = !poll.active;
        final List<EveAdminModels.PollItem> previous = new ArrayList<>(polls);
        List<EveAdminModels.PollItem> optimistic = new ArrayList<>(polls.size());
        for (EveAdminModels.PollItem p : polls) {
            if (p.id.equals(poll.id)) {
                optimistic.add(new EveAdminModels.PollItem(
                        p.id, p.question, p.options, targetActive, p.totalVotes,
                        p.voteCounts, p.createdAt, p.endsAt, p.createdBy,
                        p.isQuiz, p.correctOptionIndex, p.explanation));
            } else {
                optimistic.add(p);
            }
        }
        polls = optimistic;
        updateSubtitle();
        updateList();

        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                if (destroyed) return;
                BulletinFactory.of(EveAdminPollsFragment.this).createSimpleBulletin(
                        R.raw.contact_check, targetActive ? "Poll reopened" : "Poll closed").show();
                reload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                polls = previous;
                updateSubtitle();
                updateList();
                BulletinFactory.of(EveAdminPollsFragment.this).createErrorBulletin(
                        message != null ? message : "Status update failed.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.setPollActive(token, poll.id, targetActive);
            repo.postSuccess(cb, null);
        });
    }

    private void confirmDeletePoll(EvePollModel poll) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Delete Poll");
        builder.setMessage("Delete \"" + poll.question + "\" and all recorded votes?");
        builder.setPositiveButton("Delete", (d, w) -> {
            final List<EveAdminModels.PollItem> previous = new ArrayList<>(polls);
            List<EveAdminModels.PollItem> optimistic = new ArrayList<>(polls.size());
            for (EveAdminModels.PollItem p : polls) {
                if (!p.id.equals(poll.id)) {
                    optimistic.add(p);
                }
            }
            polls = optimistic;
            updateSubtitle();
            updateList();

            EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
                @Override
                public void onSuccess(Void data) {
                    if (destroyed) return;
                    BulletinFactory.of(EveAdminPollsFragment.this)
                            .createSimpleBulletin(R.raw.info, "Poll deleted").show();
                    reload();
                }

                @Override
                public void onError(String message, int httpStatus) {
                    if (destroyed) return;
                    polls = previous;
                    updateSubtitle();
                    updateList();
                    BulletinFactory.of(EveAdminPollsFragment.this).createErrorBulletin(
                            message != null ? message : "Delete failed.").show();
                }
            };
            repo.runWithAdminToken(cb, token -> {
                repo.deletePoll(token, poll.id);
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
