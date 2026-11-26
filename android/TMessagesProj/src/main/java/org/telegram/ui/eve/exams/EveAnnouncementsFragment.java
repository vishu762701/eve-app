package org.telegram.ui.eve.exams;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
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
 * Displays EVE-only broadcasts and exam announcements inside the EVE app,
 * completely isolated from Telegram conversations and channels.
 */
public class EveAnnouncementsFragment extends BaseFragment {

    private static final int ID_REFRESH = 1;

    private UniversalRecyclerView listView;
    private List<EveExamModels.BroadcastItem> itemsList = Collections.emptyList();
    private boolean loading = true;
    private String errorMessage = "";

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.EveAnnouncements));
        actionBar.createMenu().addItem(ID_REFRESH, R.drawable.msg_retry);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == ID_REFRESH) {
                    loadBroadcasts();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = frameLayout;

        loadBroadcasts();
        return frameLayout;
    }

    private void loadBroadcasts() {
        loading = true;
        errorMessage = "";
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        EveExamRepository.getInstance().loadBroadcasts((list, err) -> {
            loading = false;
            itemsList = list != null ? list : Collections.emptyList();
            errorMessage = err != null ? err : "";
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        });
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.EveAnnouncements)));
        if (loading) {
            items.add(UItem.asShadow("Loading EVE announcements..."));
            return;
        }
        if (!errorMessage.isEmpty()) {
            items.add(UItem.asButton(100, "Retry", errorMessage));
            return;
        }
        if (itemsList.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.EveNoAnnouncements)));
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM", Locale.getDefault());
        for (int i = 0; i < itemsList.size(); i++) {
            EveExamModels.BroadcastItem b = itemsList.get(i);
            String dateStr = b.sentAtMs > 0 ? fmt.format(new Date(b.sentAtMs)) : b.type;
            items.add(UItem.asButton(1000 + i, b.title, dateStr));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 100) {
            loadBroadcasts();
            return;
        }
        int idx = item.id - 1000;
        if (idx >= 0 && idx < itemsList.size()) {
            EveExamModels.BroadcastItem b = itemsList.get(idx);
            Activity activity = getParentActivity();
            if (activity != null) {
                new AlertDialog.Builder(activity)
                        .setTitle(b.title)
                        .setMessage(b.message)
                        .setPositiveButton(LocaleController.getString(R.string.OK), null)
                        .show();
            }
        }
    }
}
