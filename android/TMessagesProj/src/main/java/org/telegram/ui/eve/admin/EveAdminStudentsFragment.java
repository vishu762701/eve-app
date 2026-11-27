package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Full-screen Telegram-native Student Directory with Overview Stats card,
 * 1-tap quick filters (All, Online, Premium, Banned), live search, and pagination.
 */
public class EveAdminStudentsFragment extends BaseFragment {

    private static final int MENU_SEARCH = 1;

    private static final int ID_RETRY = 101;
    private static final int ID_LOAD_MORE = 102;
    private static final int BASE_STUDENT_ID = 1000;
    private static final int PAGE_SIZE = 50;
    private static final long SEARCH_DEBOUNCE_MS = 300L;

    public static final String FILTER_ALL = "all";
    public static final String FILTER_ONLINE = "online";
    public static final String FILTER_PREMIUM = "premium";
    public static final String FILTER_BANNED = "banned";

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private ActionBarMenuItem searchItem;
    private StudentsOverviewView overviewHeader;

    private final List<EveAdminModels.AdminStudentItem> students = new ArrayList<>();
    private String currentQuery = "";
    private String currentFilter = FILTER_ALL;
    private int summaryTotal = 0;
    private int summaryPremium = 0;
    private int summaryOnline = 0;
    private int summaryBanned = 0;
    private int currentPage = 1;
    private boolean loading = true;
    private boolean loadingMore = false;
    private boolean destroyed = false;
    private String errorText = "";
    private int searchSeq = 0;

    private final Runnable pendingSearchRunnable = () -> loadPage(1, false);
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
    public boolean onBackPressed(boolean invoked) {
        if (simpleActionBar != null && simpleActionBar.isSearchFieldVisible()) {
            if (invoked) {
                simpleActionBar.closeSearchField();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public View createView(Context context) {
        destroyed = false;
        setHasOwnBackground(true);

        EveAdminModels.DashboardSnapshot cached = repo.getCachedSnapshot();
        if (cached != null) {
            summaryTotal = cached.totalStudents;
            summaryPremium = cached.activePremiumUsers;
            summaryOnline = cached.activeStudents;
        }

        overviewHeader = new StudentsOverviewView(context, getResourceProvider(), filter -> {
            if (!Objects.equals(currentFilter, filter)) {
                currentFilter = filter;
                overviewHeader.bind(summaryTotal, summaryPremium, summaryOnline, summaryBanned, currentFilter);
                loadPage(1, false);
            }
        });
        overviewHeader.bind(summaryTotal, summaryPremium, summaryOnline, summaryBanned, currentFilter);

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Students",
                "Loading directory...",
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
                () -> {
                    if (overviewHeader != null) {
                        overviewHeader.updateColors();
                    }
                }
        );
        listView = chrome.listView;

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            searchItem = menu.addItem(MENU_SEARCH, R.drawable.outline_header_search, getResourceProvider())
                    .setIsSearchField(true)
                    .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                        @Override
                        public void onSearchExpand() {
                            scheduleSearch("");
                        }

                        @Override
                        public void onSearchCollapse() {
                            scheduleSearch("");
                        }

                        @Override
                        public void onTextChanged(EditText editText) {
                            String next = editText.getText() != null ? editText.getText().toString() : "";
                            scheduleSearch(next);
                        }
                    });
            searchItem.setSearchFieldHint("Search name, @username, email, EVE ID");
        }

        fragmentView = chrome.rootView;
        loadPage(1, false);
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
        searchSeq++;
        AndroidUtilities.cancelRunOnUIThread(pendingSearchRunnable);
        super.onFragmentDestroy();
    }

    public void reload() {
        loadPage(1, false);
    }

    private void scheduleSearch(String query) {
        String normalized = query != null ? query.trim() : "";
        if (TextUtils.equals(normalized, currentQuery) && !loading) {
            return;
        }
        currentQuery = normalized;
        AndroidUtilities.cancelRunOnUIThread(pendingSearchRunnable);
        AndroidUtilities.runOnUIThread(pendingSearchRunnable, SEARCH_DEBOUNCE_MS);
    }

    private void loadPage(int page, boolean append) {
        if (destroyed) {
            return;
        }
        final int seq = ++searchSeq;
        final String querySnapshot = currentQuery;
        final String filterSnapshot = currentFilter;
        if (append) {
            loadingMore = true;
            updateList();
        } else {
            loading = students.isEmpty();
            errorText = "";
            if (loading) {
                updateList();
            }
        }

        EveAdminRepository.ResultCallback<EveAdminRepository.StudentsPageResult> cb =
                new EveAdminRepository.ResultCallback<EveAdminRepository.StudentsPageResult>() {
                    @Override
                    public void onSuccess(EveAdminRepository.StudentsPageResult data) {
                        if (destroyed || seq != searchSeq) {
                            return;
                        }
                        loading = false;
                        loadingMore = false;
                        errorText = "";
                        autoSync.markSynced();
                        currentPage = page;
                        if (!append) {
                            students.clear();
                        }
                        if (data != null) {
                            students.addAll(data.users);
                            summaryTotal = data.totalCount;
                            summaryPremium = data.premiumCount;
                            summaryOnline = data.onlineCount;
                            summaryBanned = data.bannedCount;
                        }
                        if (overviewHeader != null) {
                            overviewHeader.bind(summaryTotal, summaryPremium, summaryOnline, summaryBanned, currentFilter);
                        }
                        updateHeaderSubtitle();
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed || seq != searchSeq) {
                            return;
                        }
                        loading = false;
                        loadingMore = false;
                        autoSync.markSynced();
                        errorText = message != null ? message : "Unable to load students.";
                        updateHeaderSubtitle();
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb,
                repo.searchStudentsWithSummary(token, querySnapshot, filterSnapshot, page, PAGE_SIZE)));
    }

    private void updateHeaderSubtitle() {
        if (simpleActionBar == null) {
            return;
        }
        if (!errorText.isEmpty() && students.isEmpty()) {
            simpleActionBar.setSubtitle("Unable to load");
        } else if (!currentQuery.isEmpty() || !FILTER_ALL.equals(currentFilter)) {
            simpleActionBar.setSubtitle(students.size() + " matching");
        } else {
            simpleActionBar.setSubtitle(summaryTotal + " registered");
        }
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        items.add(UItem.asCustom(overviewHeader));

        if (!errorText.isEmpty()) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_RETRY,
                    IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                    R.drawable.msg_retry,
                    "Retry Loading Students",
                    errorText,
                    "Retry"));
            items.add(UItem.asShadow(null));
        }

        if (loading && students.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (students.isEmpty()) {
            items.add(UItem.asHeader("Students"));
            items.add(UItem.asShadow(FILTER_ALL.equals(currentFilter)
                    ? "No students found"
                    : "No students match this filter"));
            return;
        }

        String listTitle;
        if (FILTER_ONLINE.equals(currentFilter)) listTitle = "Online Students";
        else if (FILTER_PREMIUM.equals(currentFilter)) listTitle = "Premium Subscribers";
        else if (FILTER_BANNED.equals(currentFilter)) listTitle = "Suspended / Banned Students";
        else listTitle = currentQuery.isEmpty() ? "Registered Students" : "Search Results";

        items.add(UItem.asHeader(listTitle));
        for (int i = 0; i < students.size(); i++) {
            items.add(StudentRowCell.Factory.of(BASE_STUDENT_ID + i, students.get(i)));
        }

        if (students.size() >= PAGE_SIZE * currentPage) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_LOAD_MORE,
                    IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                    R.drawable.msg_download,
                    loadingMore ? "Loading more students..." : "Load More Students",
                    "Showing " + students.size() + " students",
                    null));
        }
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
        } else if (item.id == ID_LOAD_MORE) {
            if (!loadingMore) {
                loadPage(currentPage + 1, true);
            }
        } else if (item.id >= BASE_STUDENT_ID) {
            int idx = item.id - BASE_STUDENT_ID;
            if (idx >= 0 && idx < students.size()) {
                EveAdminModels.AdminStudentItem student = students.get(idx);
                presentFragment(new EveAdminStudentDetailFragment(student, this::reload));
            }
        }
    }

    private interface FilterSelectListener {
        void onSelect(String filter);
    }

    private static final class StudentsOverviewView extends LinearLayout implements Theme.Colorable {

        private final Theme.ResourcesProvider resourcesProvider;
        private final FilterSelectListener listener;
        private final LinearLayout statsCard;
        private final TextView totalVal;
        private final TextView totalLbl;
        private final TextView premiumVal;
        private final TextView premiumLbl;
        private final TextView onlineVal;
        private final TextView onlineLbl;
        private final TextView bannedVal;
        private final TextView bannedLbl;
        private final TextView[] chips = new TextView[4];
        private final String[] chipKeys = new String[] { FILTER_ALL, FILTER_ONLINE, FILTER_PREMIUM, FILTER_BANNED };
        private final String[] chipLabels = new String[] { "All", "Online", "Premium", "Banned" };
        private String activeFilter = FILTER_ALL;

        StudentsOverviewView(Context context, Theme.ResourcesProvider resourcesProvider, FilterSelectListener listener) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            this.listener = listener;
            setOrientation(VERTICAL);
            setPadding(dp(14), dp(8), dp(14), dp(8));

            statsCard = new LinearLayout(context);
            statsCard.setOrientation(HORIZONTAL);
            statsCard.setPadding(dp(10), dp(10), dp(10), dp(10));

            TextView[] t = addStatColumn(context, statsCard);
            totalVal = t[0];
            totalLbl = t[1];
            totalLbl.setText("Registered");

            TextView[] p = addStatColumn(context, statsCard);
            premiumVal = p[0];
            premiumLbl = p[1];
            premiumLbl.setText("Premium");

            TextView[] o = addStatColumn(context, statsCard);
            onlineVal = o[0];
            onlineLbl = o[1];
            onlineLbl.setText("Online Now");

            TextView[] b = addStatColumn(context, statsCard);
            bannedVal = b[0];
            bannedLbl = b[1];
            bannedLbl.setText("Banned");

            addView(statsCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

            HorizontalScrollView chipScroll = new HorizontalScrollView(context);
            chipScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout chipRow = new LinearLayout(context);
            chipRow.setOrientation(HORIZONTAL);
            for (int i = 0; i < chipKeys.length; i++) {
                final String key = chipKeys[i];
                TextView chip = new TextView(context);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                chip.setTypeface(AndroidUtilities.bold());
                chip.setPadding(dp(14), dp(7), dp(14), dp(7));
                chip.setOnClickListener(v -> {
                    if (this.listener != null) {
                        this.listener.onSelect(key);
                    }
                });
                chips[i] = chip;
                chipRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));
            }
            chipScroll.addView(chipRow);
            addView(chipScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            updateColors();
        }

        private static TextView[] addStatColumn(Context context, LinearLayout row) {
            LinearLayout col = new LinearLayout(context);
            col.setOrientation(VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView val = new TextView(context);
            val.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            val.setTypeface(AndroidUtilities.bold());
            val.setGravity(Gravity.CENTER);
            col.addView(val, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            TextView lbl = new TextView(context);
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            lbl.setGravity(Gravity.CENTER);
            col.addView(lbl, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            return new TextView[] { val, lbl };
        }

        void bind(int total, int premium, int online, int banned, String filter) {
            this.activeFilter = filter != null ? filter : FILTER_ALL;
            totalVal.setText(String.valueOf(Math.max(0, total)));
            premiumVal.setText(String.valueOf(Math.max(0, premium)));
            onlineVal.setText(String.valueOf(Math.max(0, online)));
            bannedVal.setText(String.valueOf(Math.max(0, banned)));
            chips[0].setText(chipLabels[0] + " (" + Math.max(0, total) + ")");
            chips[1].setText(chipLabels[1] + " (" + Math.max(0, online) + ")");
            chips[2].setText(chipLabels[2] + " (" + Math.max(0, premium) + ")");
            chips[3].setText(chipLabels[3] + " (" + Math.max(0, banned) + ")");
            updateColors();
        }

        @Override
        public void updateColors() {
            int black = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
            int blue = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider);
            int red = Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);

            statsCard.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.multAlpha(gray, 0.09f)));
            totalVal.setTextColor(black);
            premiumVal.setTextColor(0xFF9F55DF);
            onlineVal.setTextColor(0xFF27B434);
            bannedVal.setTextColor(red);

            totalLbl.setTextColor(gray);
            premiumLbl.setTextColor(gray);
            onlineLbl.setTextColor(gray);
            bannedLbl.setTextColor(gray);

            for (int i = 0; i < chips.length; i++) {
                boolean selected = Objects.equals(chipKeys[i], activeFilter);
                chips[i].setTextColor(selected ? 0xFFFFFFFF : black);
                chips[i].setBackground(Theme.createRoundRectDrawable(
                        dp(16),
                        selected ? blue : Theme.multAlpha(gray, 0.11f)));
            }
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_windowBackgroundWhiteBlackText);
            colors.add(Theme.key_windowBackgroundWhiteGrayText);
            colors.add(Theme.key_windowBackgroundWhiteBlueText);
            colors.add(Theme.key_text_RedRegular);
        }
    }

    /**
     * Telegram-native UserCell-style student row with 44dp circular avatar, bold name,
     * metadata, and clear Online / Premium / Suspended badge.
     */
    public static final class StudentRowCell extends FrameLayout implements Theme.Colorable {

        private final Theme.ResourcesProvider resourcesProvider;
        private final AvatarDrawable avatarDrawable;
        private final BackupImageView avatarView;
        private final TextView nameView;
        private final TextView subtitleView;
        private final TextView statusView;
        private boolean isDisabled;
        private boolean isPremium;
        private boolean isOnline;

        public StudentRowCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;

            avatarDrawable = new AvatarDrawable();
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(22));

            LinearLayout textColumn = new LinearLayout(context);
            textColumn.setOrientation(LinearLayout.VERTICAL);

            nameView = new TextView(context);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            textColumn.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            textColumn.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            statusView = new TextView(context);
            statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            statusView.setTypeface(AndroidUtilities.bold());
            statusView.setSingleLine(true);
            statusView.setPadding(dp(8), dp(3), dp(8), dp(3));

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (LocaleController.isRTL) {
                row.addView(statusView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 14, 0, 8, 0));
                row.addView(textColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 12, 0));
                row.addView(avatarView, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
            } else {
                row.addView(avatarView, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 16, 0, 12, 0));
                row.addView(textColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
                row.addView(statusView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
            }

            addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 10, 0, 10));
            updateColors();
        }

        public void setStudent(EveAdminModels.AdminStudentItem student) {
            this.isDisabled = student.disabled;
            this.isPremium = student.isPremium;
            this.isOnline = student.isOnline;
            EveAdminUiChrome.bindStudentAvatar(avatarView, avatarDrawable, student, 44);

            nameView.setText(student.displayName);

            String baseSub;
            if (!student.username.isEmpty()) {
                baseSub = "@" + student.username + (!student.email.isEmpty() ? " • " + student.email : "");
            } else if (!student.eveId.isEmpty()) {
                baseSub = (!student.email.isEmpty() ? student.email + " • " : "") + student.eveId;
            } else {
                baseSub = student.email;
            }
            if (student.isOnline && student.isPremium) {
                baseSub = "● Online • " + baseSub;
            }
            subtitleView.setText(baseSub);

            if (student.disabled) {
                statusView.setText("Suspended");
            } else if (student.isPremium) {
                statusView.setText("Premium");
            } else if (student.isOnline) {
                statusView.setText("Online");
            } else {
                statusView.setText("Active");
            }
            updateColors();
        }

        @Override
        public void updateColors() {
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            if (isDisabled) {
                int red = Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);
                statusView.setTextColor(red);
                statusView.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(red, 0.12f)));
            } else if (isPremium) {
                int purple = 0xFF9F55DF;
                statusView.setTextColor(purple);
                statusView.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(purple, 0.12f)));
            } else if (isOnline) {
                int green = 0xFF27B434;
                statusView.setTextColor(green);
                statusView.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(green, 0.12f)));
            } else {
                int subtle = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
                statusView.setTextColor(subtle);
                statusView.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(subtle, 0.10f)));
            }
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_windowBackgroundWhiteBlackText);
            colors.add(Theme.key_windowBackgroundWhiteGrayText);
            colors.add(Theme.key_text_RedRegular);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(64), MeasureSpec.EXACTLY));
        }

        public static final class Factory extends UItem.UItemFactory<StudentRowCell> {
            static {
                setup(new Factory());
            }

            @Override
            public StudentRowCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new StudentRowCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                if (item.object instanceof EveAdminModels.AdminStudentItem) {
                    ((StudentRowCell) view).setStudent((EveAdminModels.AdminStudentItem) item.object);
                }
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                if (a.object instanceof EveAdminModels.AdminStudentItem && b.object instanceof EveAdminModels.AdminStudentItem) {
                    return TextUtils.equals(((EveAdminModels.AdminStudentItem) a.object).id,
                            ((EveAdminModels.AdminStudentItem) b.object).id);
                }
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                if (a.object instanceof EveAdminModels.AdminStudentItem && b.object instanceof EveAdminModels.AdminStudentItem) {
                    EveAdminModels.AdminStudentItem sa = (EveAdminModels.AdminStudentItem) a.object;
                    EveAdminModels.AdminStudentItem sb = (EveAdminModels.AdminStudentItem) b.object;
                    return sa.disabled == sb.disabled
                            && sa.isPremium == sb.isPremium
                            && sa.isOnline == sb.isOnline
                            && TextUtils.equals(sa.displayName, sb.displayName)
                            && TextUtils.equals(sa.username, sb.username)
                            && TextUtils.equals(sa.email, sb.email)
                            && TextUtils.equals(sa.photoUrl, sb.photoUrl);
                }
                return Objects.equals(a.object, b.object);
            }

            public static UItem of(int id, EveAdminModels.AdminStudentItem student) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.object = student;
                return item;
            }
        }
    }
}
