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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Displays the student's submitted exam attempt history from EVE Cloud
 * and opens full interactive answer review on selection.
 */
public class EveResultHistoryFragment extends BaseFragment {

    private static final int ID_REFRESH = 1;

    private UniversalRecyclerView listView;
    private List<EveExamModels.AttemptResult> attempts = Collections.emptyList();
    private boolean loading = true;
    private String errorMessage = "";

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.EveResultHistory));
        actionBar.createMenu().addItem(ID_REFRESH, R.drawable.msg_retry);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == ID_REFRESH) {
                    loadHistory();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = frameLayout;

        loadHistory();
        return frameLayout;
    }

    private void loadHistory() {
        loading = true;
        errorMessage = "";
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        EveExamRepository.getInstance().loadAttemptHistory((list, err) -> {
            loading = false;
            attempts = list != null ? list : Collections.emptyList();
            errorMessage = err != null ? err : "";
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        });
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.EveResultHistory)));
        if (loading) {
            items.add(UItem.asShadow("Loading attempt history from EVE Cloud..."));
            return;
        }
        if (!errorMessage.isEmpty()) {
            items.add(UItem.asButton(100, "Retry", errorMessage));
            return;
        }
        if (attempts.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.EveNoHistory)));
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());
        for (int i = 0; i < attempts.size(); i++) {
            EveExamModels.AttemptResult item = attempts.get(i);
            String dateStr = item.timestampMs > 0 ? fmt.format(new Date(item.timestampMs)) : "";
            String value = item.score + "/" + item.total + " (" + item.getAccuracyPercent() + "%)" + (dateStr.isEmpty() ? "" : " • " + dateStr);
            items.add(UItem.asButton(1000 + i, item.examName, value));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 100) {
            loadHistory();
            return;
        }
        int idx = item.id - 1000;
        if (idx >= 0 && idx < attempts.size()) {
            EveExamModels.AttemptResult attempt = attempts.get(idx);
            presentFragment(new EveTestResultFragment(null, null, attempt.examId, attempt));
        }
    }
}
