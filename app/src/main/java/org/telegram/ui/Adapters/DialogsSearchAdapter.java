package org.telegram.ui.Adapters;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.eve.app.telegram.exams.ExamListFakeData;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

public class DialogsSearchAdapter extends RecyclerListView.SelectionAdapter {

    public static final int VIEW_TYPE_DIALOG = 0;
    public static final int VIEW_TYPE_EMPTY_RESULT = 10;

    private final Context mContext;
    private final List<DialogCell.CustomDialog> searchResults = new ArrayList<>();
    private String lastSearchQuery = "";
    private boolean isSearching = false;

    public DialogsSearchAdapter(Context context) {
        this.mContext = context;
    }

    public boolean isSearching() {
        return isSearching;
    }

    public boolean hasSearchResults() {
        return isSearching && !searchResults.isEmpty();
    }

    public void searchDialogs(String query) {
        lastSearchQuery = query != null ? query.trim() : "";
        searchResults.clear();

        if (TextUtils.isEmpty(lastSearchQuery)) {
            isSearching = false;
        } else {
            isSearching = true;
            String lower = lastSearchQuery.toLowerCase();
            List<DialogCell.CustomDialog> allExams = ExamListFakeData.getFakeExams();
            for (DialogCell.CustomDialog exam : allExams) {
                if (exam.name != null && exam.name.toLowerCase().contains(lower)) {
                    searchResults.add(exam);
                } else if (exam.message != null && exam.message.toLowerCase().contains(lower)) {
                    searchResults.add(exam);
                }
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public boolean isEnabled(RecyclerView.ViewHolder holder) {
        return holder.getItemViewType() == VIEW_TYPE_DIALOG;
    }

    @Override
    public int getItemCount() {
        if (!isSearching) {
            return 0;
        }
        if (searchResults.isEmpty()) {
            return 1; // VIEW_TYPE_EMPTY_RESULT
        }
        return searchResults.size();
    }

    @Override
    public int getItemViewType(int position) {
        if (isSearching && searchResults.isEmpty()) {
            return VIEW_TYPE_EMPTY_RESULT;
        }
        return VIEW_TYPE_DIALOG;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_EMPTY_RESULT) {
            FrameLayout layout = new FrameLayout(mContext);
            layout.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(160)));

            TextView textView = new TextView(mContext);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            textView.setGravity(Gravity.CENTER);
            textView.setText("No results");
            layout.addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
            return new RecyclerListView.Holder(layout);
        }

        DialogCell cell = new DialogCell(null, mContext, false, false);
        cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new RecyclerListView.Holder(cell);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder.getItemViewType() == VIEW_TYPE_DIALOG) {
            DialogCell cell = (DialogCell) holder.itemView;
            cell.useSeparator = (position != searchResults.size() - 1);
            cell.setDialog(searchResults.get(position));
        }
    }
}
