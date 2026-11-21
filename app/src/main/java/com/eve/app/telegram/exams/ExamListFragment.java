package com.eve.app.telegram.exams;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.text.Editable;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eve.app.R;
import com.eve.app.ui.leaderboard.LeaderboardActivity;
import com.eve.app.ui.practice.PracticeActivity;
import com.eve.app.ui.profile.ProfileActivity;
import com.eve.app.ui.settings.SettingsActivity;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Adapters.DialogsSearchAdapter;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.FragmentSearchField;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.glass.GlassTabView;
import org.telegram.ui.MainTabsLayout;

import java.util.List;

public class ExamListFragment extends BaseFragment {

    private static final int SEARCH_FIELD_HEIGHT_DP = 48; // EVE-REF: ui/DialogsActivity.java:5255

    private RecyclerListView listView;
    private ExamListAdapter adapter;
    private DialogsSearchAdapter searchAdapter;

    private FragmentSearchField searchField;
    private MainTabsLayout tabsView;
    private GlassTabView[] tabs;
    private FragmentFloatingButton floatingButton;

    private boolean isSearchVisible = false;
    private ValueAnimator searchAnimator;
    private float searchAnimationProgress = 0f;

    @Override
    public View createView(Context context) {
        initThemeResources(context);

        actionBar.setItemsBackgroundColor(Theme.getColor(Theme.key_avatar_actionBarSelectorBlue), false);
        actionBar.setItemsColor(Theme.getColor(Theme.key_actionBarDefaultIcon), false);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Eve");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (isSearchVisible) {
                        showSearch(false, true);
                    } else {
                        finishFragment();
                    }
                } else if (id == 1) { // search icon
                    showSearch(!isSearchVisible, true);
                } else if (id == 2) { // 3-dot menu (visual only)
                    // Visual popup or action
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        // EVE-REF: ui/DialogsActivity.java:3141
        menu.addItem(1, R.drawable.outline_header_search);
        // EVE-REF: ui/DialogsActivity.java:3174 (3-dot visual menu)
        ActionBarMenuItem otherItem = menu.addItem(2, R.drawable.ic_ab_other);
        otherItem.addSubItem(10, R.drawable.msg_settings, "Settings");

        FrameLayout frameLayout = new FrameLayout(context) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                return super.dispatchTouchEvent(ev);
            }
        };
        fragmentView = frameLayout;
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        // 1. RecyclerListView
        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(true);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setClipToPadding(false);
        // EVE-REF: ui/MainTabsActivity.java:143 (bottom offset for floating tabs)
        listView.setPadding(0, AndroidUtilities.dp(SEARCH_FIELD_HEIGHT_DP), 0, AndroidUtilities.dp(84));
        adapter = new ExamListAdapter(context);
        searchAdapter = new DialogsSearchAdapter(context);
        listView.setAdapter(adapter);

        // Pull to search scroll listener
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (!isSearchVisible) {
                    LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                    if (lm != null && lm.findFirstVisibleItemPosition() == 0) {
                        View firstView = lm.findViewByPosition(0);
                        if (firstView != null) {
                            // EVE-REF: ui/DialogsActivity.java:1019
                            int top = firstView.getTop() - AndroidUtilities.dp(SEARCH_FIELD_HEIGHT_DP);
                            if (top > 0) {
                                // EVE-REF: ui/DialogsActivity.java:1019
                                float progress = Math.min(1f, top / (float) AndroidUtilities.dp(SEARCH_FIELD_HEIGHT_DP));
                                updateSearchFieldTranslation(progress);
                            } else {
                                updateSearchFieldTranslation(0f);
                            }
                        }
                    } else {
                        updateSearchFieldTranslation(0f);
                    }
                }
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_IDLE && !isSearchVisible) {
                    LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                    if (lm != null && lm.findFirstVisibleItemPosition() == 0) {
                        View firstView = lm.findViewByPosition(0);
                        // EVE-REF: ui/DialogsActivity.java:1019
                        if (firstView != null && firstView.getTop() > AndroidUtilities.dp(SEARCH_FIELD_HEIGHT_DP + 24)) {
                            showSearch(true, true);
                        }
                    }
                }
            }
        });

        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // 2. Search Field (Pull-down & Collapsing Header)
        // EVE-REF: ui/DialogsActivity.java:3204
        searchField = new FragmentSearchField(context, null);
        // EVE-REF: ui/DialogsActivity.java:5255
        searchField.setTranslationY(-AndroidUtilities.dp(SEARCH_FIELD_HEIGHT_DP));
        searchField.setAlpha(0f);
        searchField.setCloseButtonOnClickListener(() -> {
            showSearch(false, true);
        });
        searchField.editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                String query = s != null ? s.toString() : "";
                searchAdapter.searchDialogs(query);
                if (searchAdapter.isSearching()) {
                    if (listView.getAdapter() != searchAdapter) {
                        listView.setAdapter(searchAdapter);
                    }
                } else {
                    if (listView.getAdapter() != adapter) {
                        listView.setAdapter(adapter);
                    }
                }
            }
        });
        frameLayout.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, SEARCH_FIELD_HEIGHT_DP, Gravity.TOP | Gravity.LEFT));

        // 3. Floating Action Button (Compose Button)
        // EVE-REF: ui/DialogsActivity.java:4657
        floatingButton = new FragmentFloatingButton(context, null);
        floatingButton.setImageResource(R.drawable.filled_fab_compose_32);
        floatingButton.setContentDescription("New Exam");
        floatingButton.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(context, PracticeActivity.class);
                context.startActivity(intent);
            } catch (Exception e) {
                // Ignore if not exported or registered
            }
        });
        // EVE-REF: ui/Components/FragmentFloatingButton.java:227-230
        frameLayout.addView(floatingButton, LayoutHelper.createFrame(48, 48,
                (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.BOTTOM,
                20, 0, 20, 72));

        // 4. Bottom Tabs (MainTabsLayout)
        // EVE-REF: ui/MainTabsActivity.java:324
        tabsView = new MainTabsLayout(context, null);
        tabsView.setClipChildren(false);
        // EVE-REF: ui/MainTabsActivity.java:326
        tabsView.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        // EVE-REF: ui/MainTabsActivity.java:327
        tabsView.setMaxWidth(AndroidUtilities.dp(344));

        tabs = new GlassTabView[4];
        // Exams tab
        tabs[0] = GlassTabView.createTab(context, null, R.drawable.filled_chatlist2, "Exams");
        tabs[0].setSelected(true, false);
        tabs[0].setOnClickListener(v -> selectTab(0));
        tabsView.addView(tabs[0]);

        // Leaderboard tab
        tabs[1] = GlassTabView.createTab(context, null, R.drawable.msg_stats, "Leaderboard");
        tabs[1].setOnClickListener(v -> {
            selectTab(1);
            try {
                Intent intent = new Intent(context, LeaderboardActivity.class);
                context.startActivity(intent);
            } catch (Exception e) {
                // Handled gracefully
            }
        });
        tabsView.addView(tabs[1]);

        // Settings tab
        tabs[2] = GlassTabView.createTab(context, null, R.drawable.msg_settings, "Settings");
        tabs[2].setOnClickListener(v -> {
            selectTab(2);
            try {
                Intent intent = new Intent(context, SettingsActivity.class);
                context.startActivity(intent);
            } catch (Exception e) {
                // Handled gracefully
            }
        });
        tabsView.addView(tabs[2]);

        // Profile tab
        tabs[3] = GlassTabView.createTab(context, null, R.drawable.msg_contacts, "Profile");
        tabs[3].setOnClickListener(v -> {
            selectTab(3);
            try {
                Intent intent = new Intent(context, ProfileActivity.class);
                context.startActivity(intent);
            } catch (Exception e) {
                // Handled gracefully
            }
        });
        tabsView.addView(tabs[3]);

        // EVE-REF: ui/MainTabsActivity.java:143
        frameLayout.addView(tabsView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

        return fragmentView;
    }

    private void selectTab(int index) {
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setSelected(i == index, true);
        }
    }

    private void updateSearchFieldTranslation(float progress) {
        searchAnimationProgress = progress;
        // EVE-REF: ui/DialogsActivity.java:1627
        float ty = -AndroidUtilities.dp(SEARCH_FIELD_HEIGHT_DP) * (1f - progress);
        searchField.setTranslationY(ty);
        searchField.setAlpha(progress);
    }

    private void showSearch(boolean show, boolean animated) {
        if (isSearchVisible == show) return;
        isSearchVisible = show;

        if (searchAnimator != null) {
            searchAnimator.cancel();
            searchAnimator = null;
        }

        if (animated) {
            // EVE-REF: ui/DialogsActivity.java:310 (animatorSearchVisible)
            searchAnimator = ValueAnimator.ofFloat(searchAnimationProgress, show ? 1f : 0f);
            searchAnimator.setDuration(250);
            searchAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            searchAnimator.addUpdateListener(animation -> {
                updateSearchFieldTranslation((Float) animation.getAnimatedValue());
            });
            searchAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (show) {
                        searchField.editText.requestFocus();
                        AndroidUtilities.showKeyboard(searchField.editText);
                    } else {
                        searchField.editText.setText("");
                        AndroidUtilities.hideKeyboard(searchField.editText);
                        searchField.editText.clearFocus();
                        if (listView.getAdapter() != adapter) {
                            listView.setAdapter(adapter);
                        }
                    }
                }
            });
            searchAnimator.start();
        } else {
            updateSearchFieldTranslation(show ? 1f : 0f);
            if (show) {
                searchField.editText.requestFocus();
                AndroidUtilities.showKeyboard(searchField.editText);
            } else {
                searchField.editText.setText("");
                AndroidUtilities.hideKeyboard(searchField.editText);
                searchField.editText.clearFocus();
                if (listView.getAdapter() != adapter) {
                    listView.setAdapter(adapter);
                }
            }
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (isSearchVisible) {
            showSearch(false, true);
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private void initThemeResources(Context context) {
        if (Theme.dialogs_namePaint == null) {
            android.content.res.Resources resources = context.getResources();
            Theme.dialogs_namePaint = new TextPaint[2];
            Theme.dialogs_messagePaint = new TextPaint[2];
            for (int a = 0; a < 2; a++) {
                Theme.dialogs_namePaint[a] = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
                Theme.dialogs_namePaint[a].setTypeface(AndroidUtilities.bold());
                Theme.dialogs_namePaint[a].setColor(Theme.getColor(Theme.key_chats_name));
                Theme.dialogs_messagePaint[a] = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
                Theme.dialogs_messagePaint[a].setColor(Theme.getColor(Theme.key_chats_message));
            }
            Theme.dialogs_timePaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            // EVE-REF: ui/Cells/DialogCell.java:623
            Theme.dialogs_timePaint.setTextSize(AndroidUtilities.dp(12));
            Theme.dialogs_timePaint.setColor(Theme.getColor(Theme.key_chats_date));

            Theme.dialogs_countPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Theme.dialogs_countPaint.setColor(Theme.getColor(Theme.key_chats_unreadCounter));

            Theme.dialogs_countGrayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Theme.dialogs_countGrayPaint.setColor(Theme.getColor(Theme.key_chats_unreadCounterMuted));

            Theme.dialogs_countTextPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            Theme.dialogs_countTextPaint.setTypeface(AndroidUtilities.bold());
            // EVE-REF: ui/Cells/DialogCell.java:635
            Theme.dialogs_countTextPaint.setTextSize(AndroidUtilities.dp(12));
            Theme.dialogs_countTextPaint.setColor(Theme.getColor(Theme.key_chats_unreadCounterText));

            Theme.dialogs_countTextPaint2 = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            Theme.dialogs_countTextPaint2.setTypeface(AndroidUtilities.bold());
            // EVE-REF: ui/Cells/DialogCell.java:640
            Theme.dialogs_countTextPaint2.setTextSize(AndroidUtilities.dp(13));
            Theme.dialogs_countTextPaint2.setColor(Theme.getColor(Theme.key_chats_unreadCounterText));

            Theme.dialogs_pinnedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Theme.dialogs_pinnedPaint.setColor(Theme.getColor(Theme.key_chats_pinnedOverlay));

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
