package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import org.telegram.utils.glass.source.GlassSourceRenderNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Full-screen Telegram-native Student Directory with debounced live search and pagination.
 */
public class EveAdminStudentsFragment extends BaseFragment {

    private static final int MENU_SEARCH = 1;
    private static final int MENU_REFRESH = 2;

    private static final int ID_RETRY = 101;
    private static final int ID_LOAD_MORE = 102;
    private static final int BASE_STUDENT_ID = 1000;
    private static final int PAGE_SIZE = 50;
    private static final long SEARCH_DEBOUNCE_MS = 300L;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private ActionBarMenuItem searchItem;

    private final List<EveAdminModels.AdminStudentItem> students = new ArrayList<>();
    private String currentQuery = "";
    private int currentPage = 1;
    private boolean loading = true;
    private boolean loadingMore = false;
    private boolean destroyed = false;
    private String errorText = "";
    private int searchSeq = 0;

    private final Runnable pendingSearchRunnable = () -> loadPage(1, false);

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
    public GlassSourceRenderNode getGlassSource() {
        return getBaseSimpleGlass().getGlassSource();
    }

    @Override
    public GlassSourceRenderNode getFrostedGlassSource() {
        return getBaseSimpleGlass().getFrostedGlassSource();
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

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Students",
                "Loading directory...",
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_REFRESH) {
                            reload();
                        }
                    }
                },
                null
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
            menu.addItem(MENU_REFRESH, R.drawable.msg_retry, getResourceProvider());
        }

        fragmentView = chrome.rootView;
        loadPage(1, false);
        return chrome.rootView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
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
        if (append) {
            loadingMore = true;
        } else {
            loading = true;
            errorText = "";
        }
        updateList();

        EveAdminRepository.ResultCallback<List<EveAdminModels.AdminStudentItem>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.AdminStudentItem>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.AdminStudentItem> data) {
                        if (destroyed || seq != searchSeq) {
                            return;
                        }
                        loading = false;
                        loadingMore = false;
                        errorText = "";
                        currentPage = page;
                        if (!append) {
                            students.clear();
                        }
                        if (data != null) {
                            students.addAll(data);
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
                        errorText = message != null ? message : "Unable to load students.";
                        updateHeaderSubtitle();
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.searchStudents(token, querySnapshot, page, PAGE_SIZE)));
    }

    private void updateHeaderSubtitle() {
        if (simpleActionBar == null) {
            return;
        }
        if (!errorText.isEmpty() && students.isEmpty()) {
            simpleActionBar.setSubtitle("Unable to load");
        } else if (!currentQuery.isEmpty()) {
            simpleActionBar.setSubtitle(students.size() + " matching");
        } else {
            simpleActionBar.setSubtitle(students.size() + " registered");
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
            items.add(UItem.asShadow("No students found"));
            return;
        }

        items.add(UItem.asHeader(currentQuery.isEmpty() ? "Registered Students" : "Search Results"));
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

    /**
     * Telegram-native UserCell-style student row with 44dp circular avatar, bold name, metadata, and status badge.
     */
    public static final class StudentRowCell extends FrameLayout implements Theme.Colorable {

        private final Theme.ResourcesProvider resourcesProvider;
        private final AvatarDrawable avatarDrawable;
        private final BackupImageView avatarView;
        private final TextView nameView;
        private final TextView subtitleView;
        private final TextView statusView;
        private boolean isDisabled;

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
            EveAdminUiChrome.bindStudentAvatar(avatarView, avatarDrawable, student, 44);

            nameView.setText(student.displayName);

            String subtitle;
            if (!student.username.isEmpty()) {
                subtitle = "@" + student.username + (!student.email.isEmpty() ? " • " + student.email : "");
            } else if (!student.eveId.isEmpty()) {
                subtitle = (!student.email.isEmpty() ? student.email + " • " : "") + student.eveId;
            } else {
                subtitle = student.email;
            }
            subtitleView.setText(subtitle);

            statusView.setText(student.disabled ? "Suspended" : "Active");
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
