package com.eve.app.telegram.exams;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

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
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
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
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsLayout;

import java.util.List;

public class ExamListFragment extends BaseFragment implements FactorAnimator.Target {

    // EVE-COPY: ui/DialogsActivity.java:293-298 sha256:f360d3db9e1c426d06b304d73d5ae0d40e45e571ff35053e33173211a0b09899
    public static final int MAIN_TABS_HEIGHT = 56;
    public static final int MAIN_TABS_MARGIN = 8;
    public static final int MAIN_TABS_HEIGHT_WITH_MARGINS = MAIN_TABS_HEIGHT + MAIN_TABS_MARGIN * 2;
    public static final int FILTER_TABS_HEIGHT = 36;
    public static final int SEARCH_TABS_HEIGHT = 36 + 7 + 7;
    public static final int SEARCH_FIELD_HEIGHT = 48;

    // EVE-COPY: ui/DialogsActivity.java:300-304 sha256:5e1afe66ca6090d6ade74bfc2dd0c448be4f9f18546c362f74cd3ddc648bb923
    private static final int ANIMATOR_ID_SEARCH_VISIBLE = 1;
    private static final int ANIMATOR_ID_DONE_BUTTON_VISIBLE = 2;
    private static final int ANIMATOR_ID_SPEED_BUTTON_VISIBLE = 3;
    private static final int ANIMATOR_ID_SHADOW_VISIBLE = 4;
    private static final int ANIMATOR_ID_SEARCH_BUTTON_VISIBLE = 5;

    // EVE-COPY: ui/DialogsActivity.java:310-311 sha256:d881bf0711d68a7d1f9998aac1b1924e15083e7e2f1c5da27e7036b77dce1069
    private final BoolAnimator animatorSearchVisible = new BoolAnimator(ANIMATOR_ID_SEARCH_VISIBLE,
            this, CubicBezierInterpolator.EASE_OUT_QUINT, 350);

    // EVE-COPY: ui/DialogsActivity.java:318-319 sha256:0b0387967d872e1eed33dbfc2fd3d4bf9ab0e55f0ff689d7a52d88042dc9125c
    private final BoolAnimator animatorSearchButtonVisible = new BoolAnimator(ANIMATOR_ID_SEARCH_BUTTON_VISIBLE,
            this, CubicBezierInterpolator.EASE_OUT_QUINT, 350);

    private RecyclerListView listView;
    private ExamListAdapter adapter;
    private DialogsSearchAdapter searchAdapter;
    private kotlinx.coroutines.Job examsLoadJob;

    private FragmentSearchField searchField;
    private MainTabsLayout tabsView;
    private FrameLayout tabsViewWrapper;
    private GlassTabView[] tabs;
    private FragmentFloatingButton floatingButton;
    private ActionBarMenuItem searchItem;
    private ActionBarMenuItem optionsItem;

    private boolean isSearchVisible = false;
    private float scrollYOffset = 0f;
    private int additionFloatingButtonOffset;

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
                } else if (id == 0) {
                    showSearch(true, true);
                } else if (id == 10) {
                    Intent intent = new Intent(context, SettingsActivity.class);
                    context.startActivity(intent);
                }
            }
        });

        // EVE-COPY: ui/DialogsActivity.java:3139-3141 sha256:c5de9120342fdaae8f54afb3fdd0d2fcc3a0fb8c3348f8508f357fda4ca8580d
        ActionBarMenu menu = actionBar.createMenu();
        menu.setTranslationX(-dp(5));
        searchItem = menu.addItem(0, R.drawable.outline_header_search).setIsSearchField(true, false);

        searchItem.setOnClickListener(v -> showSearch(true, true));

        // EVE-COPY: ui/DialogsActivity.java:3365-3365 sha256:4aec7a5b4d60afcff56fa53f054fa847f5303dc2a17e19d362fae48596336280
            optionsItem = menu.addItem(4, R.drawable.ic_ab_other);
        optionsItem.addSubItem(10, R.drawable.msg_settings, "Settings");

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
        // EVE-REF: ui/DialogsActivity.java:2066 (top padding) & ui/MainTabsActivity.java:143 (bottom offset for floating tabs)
        listView.setPadding(0, dp(SEARCH_FIELD_HEIGHT), 0, dp(DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN));
        adapter = new ExamListAdapter(context);
        searchAdapter = new DialogsSearchAdapter(context);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (listView.getAdapter() == adapter) {
                ExamListEntry entry = adapter.getEntry(position);
                if (entry != null) {
                    ExamListLoader.openExam(context, entry);
                }
            }
        });
        loadExamsFromWorker();

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
                            int top = firstView.getTop() - dp(SEARCH_FIELD_HEIGHT);
                            if (top > 0) {
                                scrollYOffset = top;
                                // EVE-REF: ui/DialogsActivity.java:13708
                                float progress = Math.min(1f, top / (float) dp(SEARCH_FIELD_HEIGHT));
                                searchField.setTranslationY(scrollYOffset + getSearchFieldAdditionOffset());
                                searchField.setAlpha(progress);
                                searchField.setVisibility(View.VISIBLE);
                                animatorSearchButtonVisible.setValue(progress <= 0.01f, true);
                            } else {
                                scrollYOffset = 0;
                                searchField.setTranslationY(getSearchFieldAdditionOffset());
                                searchField.setAlpha(0f);
                                searchField.setVisibility(View.GONE);
                                animatorSearchButtonVisible.setValue(true, true);
                            }
                        }
                    } else {
                        scrollYOffset = 0;
                        searchField.setTranslationY(getSearchFieldAdditionOffset());
                        searchField.setAlpha(0f);
                        searchField.setVisibility(View.GONE);
                        animatorSearchButtonVisible.setValue(true, true);
                    }
                }
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // 2. Search Field (Pull-down & Collapsing Header)
        searchField = new FragmentSearchField(context, null);
        searchField.setTranslationY(getSearchFieldAdditionOffset());
        searchField.setAlpha(0f);
        searchField.setVisibility(View.GONE);
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

        FrameLayout contentView = frameLayout;
        FragmentSearchField fragmentSearchField = searchField;
        // EVE-COPY: ui/DialogsActivity.java:5255-5255 sha256:9f1c881461066366fb1d3896088d5d132b0cce1fff9379866d84dfd6070f1cbb
            contentView.addView(fragmentSearchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP, 7, -2, 7, 0));

        // 3. Floating Action Button (Compose Button)
        floatingButton = new FragmentFloatingButton(context, null);
        floatingButton.setImageResource(R.drawable.filled_fab_compose_32);
        floatingButton.setContentDescription("New Exam");
        floatingButton.setOnClickListener(v -> {
            Intent intent = new Intent(context, PracticeActivity.class);
            context.startActivity(intent);
        });
        FragmentFloatingButton floatingButton3 = floatingButton;
        // EVE-COPY: ui/DialogsActivity.java:4658-4658 sha256:07be4a42fe643a2ae63bcb110f7b7051f761f80891fbae185b449bec5688e8d5
        contentView.addView(floatingButton3, FragmentFloatingButton.createDefaultLayoutParams());

        boolean hasMainTabs = true;
        // EVE-COPY: ui/DialogsActivity.java:2901-2901 sha256:9e139525573fc950960f801814c090056a65f0e88869a49cea338cb2da3a0024
        additionFloatingButtonOffset = hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN) : 0;
        // EVE-REF: ui/DialogsActivity.java:8801
        floatingButton.setTranslationY(-additionFloatingButtonOffset);

        // 4. Bottom Tabs (MainTabsLayout)
        tabsView = new MainTabsLayout(context, null);
        tabsView.setClipChildren(false);
        // EVE-COPY: ui/MainTabsActivity.java:326-327 sha256:098ab6ae84164839bb70b185a3c3e81a63e267d2dfe912d55914daea3a03a4c1
        tabsView.setPadding(dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4));
        tabsView.setMaxWidth(dp(328 + DialogsActivity.MAIN_TABS_MARGIN * 2));

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
            Intent intent = new Intent(context, LeaderboardActivity.class);
            context.startActivity(intent);
        });
        tabsView.addView(tabs[1]);

        // Settings tab
        tabs[2] = GlassTabView.createTab(context, null, R.drawable.msg_settings, "Settings");
        tabs[2].setOnClickListener(v -> {
            selectTab(2);
            Intent intent = new Intent(context, SettingsActivity.class);
            context.startActivity(intent);
        });
        tabsView.addView(tabs[2]);

        // Profile tab
        tabs[3] = GlassTabView.createTab(context, null, R.drawable.msg_contacts, "Profile");
        tabs[3].setOnClickListener(v -> {
            selectTab(3);
            Intent intent = new Intent(context, ProfileActivity.class);
            context.startActivity(intent);
        });
        tabsView.addView(tabs[3]);

        tabsViewWrapper = new FrameLayout(context);
        tabsViewWrapper.setClipToPadding(false);
        // EVE-COPY: ui/MainTabsActivity.java:401-401 sha256:122ea23949e76db2978fa126660529d97f19011836694cb17b7ffeb4bf89adc1
        tabsViewWrapper.addView(tabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        frameLayout.addView(tabsViewWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        return fragmentView;
    }

    private void selectTab(int index) {
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setSelected(i == index, true);
        }
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_SEARCH_VISIBLE) {
            checkUi_searchFieldVisibility();
        } else if (id == ANIMATOR_ID_SEARCH_BUTTON_VISIBLE) {
            FragmentFloatingButton.setAnimatedVisibility(searchItem, factor);
        }
    }

    private void checkUi_searchFieldVisibility() {
        if (searchField == null) {
            return;
        }
        float alpha = animatorSearchVisible.getFloatValue();
        searchField.setAlpha(alpha);
        searchField.setVisibility(alpha > 0 ? View.VISIBLE : View.GONE);
        searchField.setTranslationY(scrollYOffset + getSearchFieldAdditionOffset());
        animatorSearchButtonVisible.setValue(alpha <= 0.01f, true);
    }

    // EVE-COPY: ui/DialogsActivity.java:1626-1628 sha256:8e816a088b315fb4db7f394edba641b0735f00f2af4c75750219250d55975f12
    private float getSearchFieldAdditionOffset() {
        return -lerp(dp(4), dp(SEARCH_FIELD_HEIGHT), animatorSearchVisible.getFloatValue());
    }

    private void showSearch(boolean show, boolean animated) {
        if (isSearchVisible == show) return;
        isSearchVisible = show;

        animatorSearchVisible.setValue(show, animated);

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
            // EVE-REF: ui/ActionBar/Theme.java:7995
            Theme.dialogs_timePaint.setTextSize(AndroidUtilities.dp(12));
            Theme.dialogs_timePaint.setColor(Theme.getColor(Theme.key_chats_date));

            Theme.dialogs_countPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Theme.dialogs_countPaint.setColor(Theme.getColor(Theme.key_chats_unreadCounter));

            Theme.dialogs_countGrayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Theme.dialogs_countGrayPaint.setColor(Theme.getColor(Theme.key_chats_unreadCounterMuted));

            Theme.dialogs_countTextPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            Theme.dialogs_countTextPaint.setTypeface(AndroidUtilities.bold());
            // EVE-REF: ui/ActionBar/Theme.java:7894
            Theme.dialogs_countTextPaint.setTextSize(AndroidUtilities.dp(12));
            Theme.dialogs_countTextPaint.setColor(Theme.getColor(Theme.key_chats_unreadCounterText));

            Theme.dialogs_countTextPaint2 = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            Theme.dialogs_countTextPaint2.setTypeface(AndroidUtilities.bold());
            // EVE-REF: ui/ActionBar/Theme.java:7895
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

    private void loadExamsFromWorker() {
        if (examsLoadJob != null) {
            examsLoadJob.cancel(null);
        }
        examsLoadJob = ExamListLoader.loadLiveExams(new ExamListLoader.Callback() {
            @Override
            public void onLoaded(@NonNull List<ExamListEntry> entries) {
                if (adapter != null) {
                    adapter.submitEntries(entries);
                }
            }

            @Override
            public void onError(@NonNull String message) {
                // Keep current list state; empty list remains honest rather than showing fake exams.
            }
        });
    }

    @Override
    public void onFragmentDestroy() {
        if (examsLoadJob != null) {
            examsLoadJob.cancel(null);
            examsLoadJob = null;
        }
        super.onFragmentDestroy();
    }

    private class ExamListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;
        private final List<ExamListEntry> entries = new java.util.ArrayList<>();

        public ExamListAdapter(Context context) {
            this.context = context;
        }

        public void submitEntries(@NonNull List<ExamListEntry> newEntries) {
            entries.clear();
            entries.addAll(newEntries);
            notifyDataSetChanged();
        }

        public ExamListEntry getEntry(int position) {
            if (position < 0 || position >= entries.size()) {
                return null;
            }
            return entries.get(position);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return entries.size();
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
            cell.useSeparator = (position != entries.size() - 1);
            cell.setDialog(entries.get(position).getDialog());
        }
    }
}
