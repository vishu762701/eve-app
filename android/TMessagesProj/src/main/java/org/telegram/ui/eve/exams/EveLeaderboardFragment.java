package org.telegram.ui.eve.exams;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Displays authoritative exam-specific or overall student leaderboards
 * plus rank/percentile summary from the EVE backend.
 */
public class EveLeaderboardFragment extends BaseFragment {

    private static final int ID_REFRESH = 1;

    private final String examIdOrOverall;
    private final String titleText;
    private UniversalRecyclerView listView;
    private EveExamModels.LeaderboardBundle bundle;
    private boolean loading = true;
    private String errorMessage = "";

    public EveLeaderboardFragment(String examIdOrOverall, String titleText) {
        this.examIdOrOverall = examIdOrOverall != null && !examIdOrOverall.trim().isEmpty()
                ? examIdOrOverall.trim()
                : "overall";
        this.titleText = titleText != null && !titleText.trim().isEmpty()
                ? titleText.trim()
                : LocaleController.getString(R.string.EveLeaderboard);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.EveLeaderboard));
        actionBar.setSubtitle(titleText);
        actionBar.createMenu().addItem(ID_REFRESH, R.drawable.msg_retry);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == ID_REFRESH) {
                    loadData();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = frameLayout;

        loadData();
        return frameLayout;
    }

    private void loadData() {
        loading = true;
        errorMessage = "";
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        EveExamRepository.getInstance().loadLeaderboard(examIdOrOverall, (loaded, err) -> {
            loading = false;
            bundle = loaded;
            errorMessage = err != null ? err : "";
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        });
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (loading) {
            items.add(UItem.asShadow("Loading leaderboard from EVE Cloud..."));
            return;
        }
        if (!errorMessage.isEmpty()) {
            items.add(UItem.asButton(100, "Retry", errorMessage));
            return;
        }
        if (bundle != null && bundle.stats != null) {
            EveExamModels.LeaderboardStats stats = bundle.stats;
            items.add(UItem.asHeader(LocaleController.getString(R.string.EveYourRank)));
            String rankText = stats.myRank != null
                    ? ("#" + stats.myRank + " / " + stats.participants)
                    : ("Not ranked (" + stats.participants + " participants)");
            items.add(UItem.asButton(200, LocaleController.getString(R.string.EveYourRank), rankText));
            if (stats.myPercentile != null) {
                items.add(UItem.asButton(201, "Percentile", String.format(Locale.US, "%.1f%%", stats.myPercentile)));
            }
            items.add(UItem.asButton(202, "Topper Score", String.valueOf(stats.topperScore)));
            items.add(UItem.asShadow(null));
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.EveLeaderboard)));
        if (bundle == null || bundle.entries.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.EveNoLeaderboard)));
            return;
        }
        for (int i = 0; i < bundle.entries.size(); i++) {
            EveExamModels.LeaderboardEntry entry = bundle.entries.get(i);
            String right = entry.score + "/" + entry.total;
            if (entry.accuracy > 0) {
                right += " (" + entry.accuracy + "%)";
            } else if (entry.timeTakenSeconds > 0) {
                right += " • " + (entry.timeTakenSeconds / 60) + "m";
            }
            items.add(UItem.asButton(1000 + i, "#" + (i + 1) + "  " + entry.displayName, right));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 100) {
            loadData();
        }
    }
}
