package org.telegram.ui.eve.admin;

import android.content.Context;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Edge-to-Edge Exam Directory for EVE Admin Control Center.
 * Tapping any exam opens {@link EveAdminExamDetailFragment}.
 */
public class EveAdminExamsFragment extends BaseFragment {

    private static final int MENU_SEARCH = 1;
    private static final int MENU_ADD = 2;

    private static final int ID_RETRY = 101;
    private static final int BASE_EXAM_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private ActionBarMenuItem searchItem;
    private boolean loading = true;
    private boolean destroyed = false;
    private String errorText = "";
    private String searchQuery = "";
    private List<EveAdminModels.AdminExamItem> exams = Collections.emptyList();
    private final List<EveAdminModels.AdminExamItem> filteredExams = new ArrayList<>();
    private List<EveAdminModels.AdminGeneratedTestItem> generatedTests = Collections.emptyList();
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

        EveAdminRepository.ExamsCatalogData cachedCatalog = repo.getCachedExamsCatalog();
        if (cachedCatalog != null && !cachedCatalog.exams.isEmpty()) {
            exams = cachedCatalog.exams;
            generatedTests = cachedCatalog.generatedTests;
        } else {
            EveAdminModels.DashboardSnapshot cached = repo.getCachedSnapshot();
            if (cached != null) {
                exams = cached.exams;
                generatedTests = cached.generatedTests;
            }
        }
        loading = exams.isEmpty();

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Exams & Syllabus",
                exams.isEmpty() ? null : exams.size() + " exams",
                false,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_ADD) {
                            EveAdminDialogs.showCreateOrEditExamDialog(
                                    EveAdminExamsFragment.this, repo, null, EveAdminExamsFragment.this::reload);
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
                            searchQuery = "";
                            applyFilter();
                        }

                        @Override
                        public void onSearchCollapse() {
                            searchQuery = "";
                            applyFilter();
                        }

                        @Override
                        public void onTextChanged(EditText editText) {
                            searchQuery = editText.getText() != null ? editText.getText().toString() : "";
                            applyFilter();
                        }
                    });
            searchItem.setSearchFieldHint("Search exams or category");
            menu.addItem(MENU_ADD, R.drawable.msg_add, getResourceProvider());
        }

        applyFilter();
        fragmentView = chrome.rootView;
        reload();
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
        super.onFragmentDestroy();
    }

    public void reload() {
        if (destroyed) return;
        loading = exams.isEmpty();
        errorText = "";
        if (loading) {
            updateList();
        }
        repo.loadExamsCatalogAsync(new EveAdminRepository.ResultCallback<EveAdminRepository.ExamsCatalogData>() {
            @Override
            public void onSuccess(EveAdminRepository.ExamsCatalogData data) {
                if (destroyed) return;
                loading = false;
                autoSync.markSynced();
                if (data != null) {
                    exams = data.exams;
                    generatedTests = data.generatedTests;
                }
                applyFilter();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                loading = false;
                autoSync.markSynced();
                errorText = message != null ? message : "Failed to load exams.";
                updateList();
            }
        });
    }

    private void applyFilter() {
        filteredExams.clear();
        String q = searchQuery != null ? searchQuery.trim().toLowerCase(Locale.US) : "";
        for (EveAdminModels.AdminExamItem exam : exams) {
            boolean isTopLevel = exam.parentExamId.trim().isEmpty();
            if (q.isEmpty()) {
                if (isTopLevel) {
                    filteredExams.add(exam);
                }
            } else if (exam.examName.toLowerCase(Locale.US).contains(q)
                    || exam.category.toLowerCase(Locale.US).contains(q)) {
                filteredExams.add(exam);
            }
        }
        if (simpleActionBar != null) {
            simpleActionBar.setSubtitle(q.isEmpty()
                    ? filteredExams.size() + " exams"
                    : filteredExams.size() + " matching");
        }
        updateList();
    }

    private int countSubExams(String parentId) {
        int count = 0;
        for (EveAdminModels.AdminExamItem e : exams) {
            if (parentId.equals(e.parentExamId.trim())) {
                count++;
            }
        }
        return count;
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
                    "Retry Loading Exams",
                    errorText,
                    "Retry"));
        }

        if (loading && exams.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (filteredExams.isEmpty()) {
            items.add(UItem.asShadow(searchQuery.trim().isEmpty()
                    ? "No exams found. Tap + above to create an exam."
                    : "No exams matched \"" + searchQuery.trim() + "\"."));
            return;
        }

        for (int i = 0; i < filteredExams.size(); i++) {
            EveAdminModels.AdminExamItem exam = filteredExams.get(i);
            String statusLabel;
            IconBackgroundColors iconBg;
            int iconRes;
            if (exam.isArchived()) {
                statusLabel = "Archived";
                iconBg = IconBackgroundColors.GRAY;
                iconRes = R.drawable.msg_archive;
            } else if (exam.isPaused()) {
                statusLabel = "Paused";
                iconBg = IconBackgroundColors.ORANGE;
                iconRes = R.drawable.msg_recent;
            } else {
                statusLabel = "Live";
                iconBg = IconBackgroundColors.GREEN;
                iconRes = R.drawable.msg_folders;
            }
            int subCount = exam.parentExamId.trim().isEmpty() ? countSubExams(exam.id) : 0;
            String subTag = subCount > 0 ? (" • " + subCount + (subCount == 1 ? " sub-exam" : " sub-exams")) : "";
            String parentTag = !exam.parentExamId.trim().isEmpty() ? "Sub-exam • " : "";
            String syllabusTag = !exam.syllabusFileName.isEmpty() ? " • PDF: " + exam.syllabusFileName : "";
            String sub = String.format(Locale.US, "%s%s • %d min • %d Qs%s%s",
                    parentTag, exam.category, exam.timeLimitMinutes, exam.questionCount, subTag, syllabusTag);
            items.add(EveAdminUiChrome.AvatarRowCell.Factory.of(
                    BASE_EXAM_ID + i,
                    exam,
                    new EveAdminUiChrome.AvatarRowCell.Payload(
                            exam.id,
                            exam.examName,
                            sub,
                            exam.avatarUrl,
                            exam.avatarEmoji,
                            exam.avatarColor,
                            statusLabel,
                            exam.isArchived()
                                    ? EveAdminUiChrome.AvatarRowCell.BADGE_NEUTRAL
                                    : (exam.isPaused()
                                            ? EveAdminUiChrome.AvatarRowCell.BADGE_RED
                                            : EveAdminUiChrome.AvatarRowCell.BADGE_GREEN))));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
        } else if (item.id >= BASE_EXAM_ID) {
            int idx = item.id - BASE_EXAM_ID;
            if (idx >= 0 && idx < filteredExams.size()) {
                EveAdminModels.AdminExamItem exam = filteredExams.get(idx);
                presentFragment(new EveAdminExamDetailFragment(exam, generatedTests, this::reload));
            }
        }
    }
}
