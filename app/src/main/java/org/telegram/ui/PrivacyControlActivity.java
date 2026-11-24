/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eve.app.ui.settings.PrivacyPreferences;
import com.eve.app.ui.settings.TelegramPrivacyDelegate;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextRadioCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Supporting upstream Telegram PrivacyControlActivity (DrKLO/Telegram@21f7d109)
 * for per-rule privacy selection pushed from PrivacySettingsActivity.
 */
public class PrivacyControlActivity extends BaseFragment {

    private static final int done_button = 1;

    private final int rulesType;
    private final String screenTitle;
    private final String sectionPrompt;
    private final String detailText;
    private final TelegramPrivacyDelegate eveDelegate;

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private View doneButton;

    private String initialChoice;
    private String currentChoice;

    private int sectionRow;
    private int everybodyRow;
    private int nobodyRow;
    private int detailRow;
    private int rowCount;

    public PrivacyControlActivity(int rulesType, String screenTitle, String sectionPrompt, String detailText, TelegramPrivacyDelegate eveDelegate) {
        this.rulesType = rulesType;
        this.screenTitle = screenTitle;
        this.sectionPrompt = sectionPrompt;
        this.detailText = detailText;
        this.eveDelegate = eveDelegate;
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        initialChoice = eveDelegate != null ? eveDelegate.getRuleVisibility(rulesType) : PrivacyPreferences.VISIBILITY_EVERYBODY;
        currentChoice = initialChoice;
        updateRows();
        return true;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    private void updateRows() {
        rowCount = 0;
        sectionRow = rowCount++;
        everybodyRow = rowCount++;
        nobodyRow = rowCount++;
        detailRow = rowCount++;
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(screenTitle);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == done_button) {
                    if (eveDelegate != null) {
                        eveDelegate.setRuleVisibility(rulesType, currentChoice);
                    }
                    finishFragment();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        doneButton = menu.addItemWithWidth(done_button, R.drawable.ic_ab_done, AndroidUtilities.dp(56), LocaleController.getString(R.string.Done));
        doneButton.setVisibility(View.GONE);

        listAdapter = new ListAdapter(context);

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        });
        listView.setVerticalScrollBarEnabled(false);
        listView.setItemAnimator(null);
        listView.setLayoutAnimation(null);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setAdapter(listAdapter);

        listView.setOnItemClickListener((view, position) -> {
            if (position == everybodyRow) {
                currentChoice = PrivacyPreferences.VISIBILITY_EVERYBODY;
            } else if (position == nobodyRow) {
                currentChoice = PrivacyPreferences.VISIBILITY_ONLY_ME;
            } else {
                return;
            }
            if (doneButton != null) {
                doneButton.setVisibility(currentChoice.equals(initialChoice) ? View.GONE : View.VISIBLE);
            }
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(frameLayout, (v, insets) -> {
            if (listView != null) {
                int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime()).bottom;
                listView.setPadding(0, 0, 0, AndroidUtilities.dp(16) + bottom);
            }
            return insets;
        });

        return fragmentView;
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{HeaderCell.class, TextRadioCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextRadioCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        return descriptions;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int pos = holder.getAdapterPosition();
            return pos == everybodyRow || pos == nobodyRow;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == 0) {
                view = new HeaderCell(mContext);
            } else if (viewType == 1) {
                view = new TextRadioCell(mContext);
            } else {
                view = new TextInfoPrivacyCell(mContext);
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == 0) {
                ((HeaderCell) holder.itemView).setText(sectionPrompt);
            } else if (holder.getItemViewType() == 1) {
                TextRadioCell cell = (TextRadioCell) holder.itemView;
                if (position == everybodyRow) {
                    cell.setTextAndCheck(PrivacyPreferences.VISIBILITY_EVERYBODY, PrivacyPreferences.VISIBILITY_EVERYBODY.equals(currentChoice), true);
                } else if (position == nobodyRow) {
                    cell.setTextAndCheck(PrivacyPreferences.VISIBILITY_ONLY_ME, PrivacyPreferences.VISIBILITY_ONLY_ME.equals(currentChoice), false);
                }
            } else {
                ((TextInfoPrivacyCell) holder.itemView).setText(detailText);
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == sectionRow) return 0;
            if (position == everybodyRow || position == nobodyRow) return 1;
            return 2;
        }
    }
}
