package org.telegram.ui.eve.exams;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.eve.admin.EveAdminConfig;
import org.telegram.ui.eve.admin.EveAdminExamDetailFragment;
import org.telegram.ui.eve.admin.EveAdminModels;
import org.telegram.ui.eve.admin.EveAdminRepository;
import org.telegram.ui.eve.admin.EveAdminUiChrome;
import org.telegram.ui.eve.admin.EveAnimatedEmojiHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Edge-to-Edge Sub-Exams directory matching Telegram Chats style via {@link EveExamChatRowCell}
 * (circular 54dp avatars, 16sp bold AnimatedEmojiSpan titles, muted metadata subtitles,
 * right-aligned counter pill badges, and DialogCell-aligned inset hairline dividers).
 */
public class EveSubExamsFragment extends BaseFragment {

    private static final int MENU_SEARCH = 1;
    private static final int BASE_SUB_ID = 1000;

    private final EveExamModels.ExamSummary parentExam;
    private UniversalRecyclerView listView;
    @Nullable
    private EveAdminUiChrome.AvatarHeaderHandle avatarHeader;
    private List<EveExamModels.ExamSummary> allSubExams = Collections.emptyList();
    private final List<EveExamModels.ExamSummary> filtered = new ArrayList<>();
    private String searchQuery = "";
    private boolean destroyed = false;
    private final EveAdminUiChrome.AutoSyncController autoSync = new EveAdminUiChrome.AutoSyncController(this::refreshFromRepo);

    public EveSubExamsFragment(@NonNull EveExamModels.ExamSummary parentExam) {
        this.parentExam = parentExam;
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

        allSubExams = EveExamRepository.getInstance().getSubExamsForParent(parentExam.id);
        applyFilter("");

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                parentExam.examName,
                buildParentMetaSubtitle(),
                false,
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
                    if (avatarHeader != null) {
                        avatarHeader.updateColors();
                    }
                }
        );
        listView = chrome.listView;

        if (simpleActionBar != null) {
            avatarHeader = EveAdminUiChrome.attachAvatarActionBarHeader(
                    context,
                    simpleActionBar,
                    56,
                    getResourceProvider());
            if (avatarHeader != null) {
                avatarHeader.update(
                        parentExam.id,
                        parentExam.examName,
                        buildParentMetaSubtitle(),
                        parentExam.avatarUrl,
                        parentExam.avatarEmoji,
                        parentExam.avatarColor);
                avatarHeader.setOnClickListener(v -> onHeaderClicked());
            }

            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            menu.addItem(MENU_SEARCH, R.drawable.outline_header_search, getResourceProvider())
                    .setIsSearchField(true)
                    .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                        @Override
                        public void onSearchExpand() {
                            applyFilter("");
                        }

                        @Override
                        public void onSearchCollapse() {
                            applyFilter("");
                        }

                        @Override
                        public void onTextChanged(EditText editText) {
                            applyFilter(editText.getText() != null ? editText.getText().toString() : "");
                        }
                    })
                    .setSearchFieldHint("Search sub-exams");
        }

        if (EveAdminConfig.isAdminAvailable() && EveAdminRepository.getInstance().getCachedSnapshot() == null) {
            EveAdminRepository.getInstance().loadDashboardAsync(new EveAdminRepository.ResultCallback<EveAdminModels.DashboardSnapshot>() {
                @Override
                public void onSuccess(EveAdminModels.DashboardSnapshot data) {
                }

                @Override
                public void onError(String message, int httpStatus) {
                }
            });
        }

        fragmentView = chrome.rootView;
        return chrome.rootView;
    }

    private String buildParentMetaSubtitle() {
        return String.format(Locale.US, "%d min \u2022 %d Qs",
                Math.max(1, parentExam.timeLimitMinutes),
                Math.max(1, parentExam.questionCount));
    }

    private void onHeaderClicked() {
        if (EveAdminUiChrome.hasCachedAdminRight(EveAdminModels.RIGHT_MANAGE_EXAMS)) {
            EveAdminModels.AdminExamItem adminExam = EveAdminUiChrome.resolveAdminExamItem(parentExam);
            if (adminExam != null) {
                EveAdminRepository.ExamsCatalogData catalog = EveAdminRepository.getInstance().getCachedExamsCatalog();
                presentFragment(new EveAdminExamDetailFragment(
                        adminExam,
                        catalog != null ? catalog.generatedTests : null,
                        this::refreshFromRepo));
                return;
            }
        }
        Activity activity = getParentActivity();
        if (activity == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(LocaleController.getString(R.string.EveCategory)).append(": ").append(parentExam.category).append("\n");
        sb.append(LocaleController.getString(R.string.EveTimeLimit)).append(": ").append(parentExam.timeLimitMinutes).append(" min\n");
        sb.append(LocaleController.getString(R.string.EveQuestionsPerTest)).append(": ").append(parentExam.questionCount).append("\n");
        sb.append(LocaleController.getString(R.string.EveNegativeMarking)).append(": ").append(parentExam.negativeMarkingText).append("\n");
        sb.append("Sub-Exams: ").append(allSubExams.size());
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle(EveAnimatedEmojiHelper.decode(parentExam.examName, null));
        builder.setMessage(sb.toString());
        if (parentExam.hasSyllabus()) {
            builder.setNeutralButton("Syllabus PDF", (d, w) -> EvePdfHelper.downloadSyllabusPdf(
                    EveSubExamsFragment.this, parentExam.id, parentExam.examName, parentExam.syllabusUrl, parentExam.syllabusFileName));
        }
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        showDialog(builder.create());
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

    private void refreshFromRepo() {
        if (destroyed) return;
        EveExamRepository.getInstance().loadExams(true);
        allSubExams = EveExamRepository.getInstance().getSubExamsForParent(parentExam.id);
        applyFilter(searchQuery);
    }

    private void applyFilter(String query) {
        searchQuery = query != null ? query.trim() : "";
        filtered.clear();
        String q = searchQuery.toLowerCase(Locale.US);
        for (EveExamModels.ExamSummary item : allSubExams) {
            if (item == null || item.isPaused()) continue;
            String plainName = EveAnimatedEmojiHelper.stripMarkupToPlainText(item.examName).toLowerCase(Locale.US);
            if (q.isEmpty() || plainName.contains(q)
                    || item.category.toLowerCase(Locale.US).contains(q)) {
                filtered.add(item);
            }
        }
        if (avatarHeader != null) {
            String sub = q.isEmpty() ? buildParentMetaSubtitle() : (filtered.size() + " matching");
            avatarHeader.update(
                    parentExam.id, parentExam.examName, sub,
                    parentExam.avatarUrl, parentExam.avatarEmoji, parentExam.avatarColor);
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);
        if (filtered.isEmpty()) {
            String plainParent = EveAnimatedEmojiHelper.stripMarkupToPlainText(parentExam.examName);
            items.add(UItem.asShadow(searchQuery.isEmpty()
                    ? "No active sub-exams under " + plainParent + "."
                    : "No matching sub-exams found."));
            return;
        }
        for (int i = 0; i < filtered.size(); i++) {
            EveExamModels.ExamSummary sub = filtered.get(i);
            String badge = sub.liveTestCount > 0 ? String.valueOf(sub.liveTestCount) : "";
            items.add(EveExamChatRowCell.Factory.of(
                    BASE_SUB_ID + i,
                    sub,
                    new EveExamChatRowCell.Payload(
                            sub.id,
                            sub.examName,
                            EveExamDialogsMapper.buildExamSubtitle(sub),
                            sub.avatarUrl,
                            sub.avatarEmoji,
                            sub.avatarColor,
                            badge,
                            EveExamChatRowCell.BADGE_SOLID_COUNTER)));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        int idx = item.id - BASE_SUB_ID;
        if (idx >= 0 && idx < filtered.size()) {
            presentFragment(new EveExamTestsFragment(filtered.get(idx)));
        }
    }
}
