package com.eve.app.telegram.exams;

import android.content.Context;
import android.graphics.Paint;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.List;

public class ExamListFragment extends BaseFragment {

    private RecyclerListView listView;
    private ExamListAdapter adapter;

    @Override
    public View createView(Context context) {
        initThemeResources(context);

        actionBar.setItemsBackgroundColor(Theme.getColor(Theme.key_avatar_actionBarSelectorBlue, resourcesProvider), false);
        actionBar.setItemsColor(Theme.getColor(Theme.key_actionBarDefaultIcon, resourcesProvider), false);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Eve");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(1, R.drawable.outline_header_search);
        menu.addItem(2, R.drawable.ic_ab_other);

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(true);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        adapter = new ExamListAdapter(context);
        listView.setAdapter(adapter);

        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    private void initThemeResources(Context context) {
        if (Theme.dialogs_namePaint == null) {
            android.content.res.Resources resources = context.getResources();
            Theme.dialogs_namePaint = new TextPaint[2];
            Theme.dialogs_messagePaint = new TextPaint[2];
            for (int a = 0; a < 2; a++) {
                Theme.dialogs_namePaint[a] = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
                Theme.dialogs_namePaint[a].setTypeface(AndroidUtilities.bold());
                Theme.dialogs_namePaint[a].setColor(Theme.getColor(Theme.key_chats_name, resourcesProvider));
                Theme.dialogs_messagePaint[a] = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
                Theme.dialogs_messagePaint[a].setColor(Theme.getColor(Theme.key_chats_message, resourcesProvider));
            }
            Theme.dialogs_timePaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            Theme.dialogs_timePaint.setTextSize(AndroidUtilities.dp(12));
            Theme.dialogs_timePaint.setColor(Theme.getColor(Theme.key_chats_date, resourcesProvider));

            Theme.dialogs_countPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Theme.dialogs_countPaint.setColor(Theme.getColor(Theme.key_chats_unreadCounter, resourcesProvider));

            Theme.dialogs_countGrayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Theme.dialogs_countGrayPaint.setColor(Theme.getColor(Theme.key_chats_unreadCounterMuted, resourcesProvider));

            Theme.dialogs_countTextPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            Theme.dialogs_countTextPaint.setTypeface(AndroidUtilities.bold());
            Theme.dialogs_countTextPaint.setTextSize(AndroidUtilities.dp(12));
            Theme.dialogs_countTextPaint.setColor(Theme.getColor(Theme.key_chats_unreadCounterText, resourcesProvider));

            Theme.dialogs_countTextPaint2 = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            Theme.dialogs_countTextPaint2.setTypeface(AndroidUtilities.bold());
            Theme.dialogs_countTextPaint2.setTextSize(AndroidUtilities.dp(13));
            Theme.dialogs_countTextPaint2.setColor(Theme.getColor(Theme.key_chats_unreadCounterText, resourcesProvider));

            Theme.dialogs_pinnedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Theme.dialogs_pinnedPaint.setColor(Theme.getColor(Theme.key_chats_pinnedOverlay, resourcesProvider));

            Theme.dialogs_pinnedDrawable = resources.getDrawable(R.drawable.list_pin);
            Theme.setDrawableColorByKey(Theme.dialogs_pinnedDrawable, Theme.key_chats_pinnedIcon);

            Theme.dialogs_muteDrawable = resources.getDrawable(R.drawable.list_mute).mutate();
            Theme.setDrawableColorByKey(Theme.dialogs_muteDrawable, Theme.key_chats_muteIcon);

            Theme.dialogs_checkDrawable = resources.getDrawable(R.drawable.list_check).mutate();
            Theme.setDrawableColorByKey(Theme.dialogs_checkDrawable, Theme.key_chats_sentCheck);

            Theme.dialogs_checkReadDrawable = resources.getDrawable(R.drawable.list_check).mutate();
            Theme.setDrawableColorByKey(Theme.dialogs_checkReadDrawable, Theme.key_chats_sentReadCheck);

            Theme.dialogs_halfCheckDrawable = resources.getDrawable(R.drawable.list_halfcheck);
            Theme.setDrawableColorByKey(Theme.dialogs_halfCheckDrawable, Theme.key_chats_sentReadCheck);
        }
    }

    private class ExamListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;
        private final List<DialogCell.CustomDialog> items;

        public ExamListAdapter(Context context) {
            this.context = context;
            this.items = ExamListFakeData.getFakeExams();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            DialogCell cell = new DialogCell(null, context, false, false);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            DialogCell cell = (DialogCell) holder.itemView;
            cell.useSeparator = (position != items.size() - 1);
            cell.setDialog(items.get(position));
        }
    }
}
