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
import org.telegram.ui.ActionBar.SimpleActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
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
import java.util.Locale;
import java.util.Objects;

/**
 * Edge-to-Edge Exam-scoped Question Bank list for EVE Admin Control Center.
 */
public class EveAdminQuestionsFragment extends BaseFragment {

    private static final int MENU_SEARCH = 1;
    private static final int MENU_ADD = 2;
    private static final int MENU_MORE = 3;
    private static final int MENU_IMPORT_JSON = 4;

    private static final int ID_RETRY = 101;
    private static final int BASE_QUESTION_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private final EveAdminModels.AdminExamItem exam;
    private UniversalRecyclerView listView;
    private ActionBarMenuItem searchItem;

    private boolean loading = true;
    private boolean destroyed = false;
    private String errorText = "";
    private String searchQuery = "";
    private final List<EveAdminModels.AdminQuestionItem> allQuestions = new ArrayList<>();
    private final List<EveAdminModels.AdminQuestionItem> filteredQuestions = new ArrayList<>();
    private final EveAdminUiChrome.AutoSyncController autoSync = new EveAdminUiChrome.AutoSyncController(this::reload);

    public EveAdminQuestionsFragment(EveAdminModels.AdminExamItem exam) {
        this.exam = exam;
    }

    @Override
    public boolean onFragmentCreate() {
        return EveAdminConfig.isAdminAvailable() && exam != null && super.onFragmentCreate();
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

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Question Bank",
                exam.examName,
                false,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_ADD) {
                            EveAdminDialogs.showEditQuestionDialog(
                                    EveAdminQuestionsFragment.this, repo, exam, null, EveAdminQuestionsFragment.this::reload);
                        } else if (id == MENU_IMPORT_JSON) {
                            EveAdminDialogs.showBulkImportQuestionsDialog(
                                    EveAdminQuestionsFragment.this, repo, exam, EveAdminQuestionsFragment.this::reload);
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
            searchItem.setSearchFieldHint("Search question or topic");
            menu.addItem(MENU_ADD, R.drawable.msg_add, getResourceProvider());
            SimpleActionBarMenuItem moreItem = menu.addItem(MENU_MORE, R.drawable.ic_ab_other, getResourceProvider());
            moreItem.addSubItem(MENU_IMPORT_JSON, R.drawable.msg_download, "Bulk Import JSON");
        }

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

    private void reload() {
        if (destroyed) return;
        loading = allQuestions.isEmpty();
        errorText = "";
        if (loading) {
            updateList();
        }
        EveAdminRepository.ResultCallback<List<EveAdminModels.AdminQuestionItem>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.AdminQuestionItem>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.AdminQuestionItem> data) {
                        if (destroyed) return;
                        loading = false;
                        errorText = "";
                        autoSync.markSynced();
                        allQuestions.clear();
                        if (data != null) {
                            allQuestions.addAll(data);
                        }
                        applyFilter();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
                        autoSync.markSynced();
                        errorText = message != null ? message : "Unable to load questions.";
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchQuestions(token, exam.id)));
    }

    private void applyFilter() {
        filteredQuestions.clear();
        String q = searchQuery != null ? searchQuery.trim().toLowerCase(Locale.US) : "";
        for (EveAdminModels.AdminQuestionItem item : allQuestions) {
            if (q.isEmpty()
                    || item.questionText.toLowerCase(Locale.US).contains(q)
                    || item.topic.toLowerCase(Locale.US).contains(q)) {
                filteredQuestions.add(item);
            }
        }
        if (simpleActionBar != null) {
            simpleActionBar.setSubtitle(exam.examName + " • " + filteredQuestions.size() + " Qs");
        }
        updateList();
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
                    "Retry Loading Questions",
                    errorText,
                    "Retry"));
        }

        if (loading && allQuestions.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (filteredQuestions.isEmpty()) {
            items.add(UItem.asShadow(searchQuery.trim().isEmpty()
                    ? "No questions found for this exam. Tap + to add or import JSON."
                    : "No questions matched \"" + searchQuery.trim() + "\"."));
            return;
        }

        for (int i = 0; i < filteredQuestions.size(); i++) {
            items.add(QuestionRowCell.Factory.of(BASE_QUESTION_ID + i, i + 1, filteredQuestions.get(i)));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
        } else if (item.id >= BASE_QUESTION_ID) {
            int idx = item.id - BASE_QUESTION_ID;
            if (idx >= 0 && idx < filteredQuestions.size()) {
                EveAdminDialogs.showQuestionActions(this, repo, exam, filteredQuestions.get(idx), this::reload);
            }
        }
    }

    public static final class QuestionRowCell extends FrameLayout implements Theme.Colorable {

        private final Theme.ResourcesProvider resourcesProvider;
        private final TextView numberBadge;
        private final TextView questionView;
        private final TextView metaView;
        private final TextView ansBadge;

        public QuestionRowCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;

            numberBadge = new TextView(context);
            numberBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            numberBadge.setTypeface(AndroidUtilities.bold());
            numberBadge.setGravity(Gravity.CENTER);

            LinearLayout center = new LinearLayout(context);
            center.setOrientation(LinearLayout.VERTICAL);

            questionView = new TextView(context);
            questionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            questionView.setMaxLines(2);
            questionView.setEllipsize(TextUtils.TruncateAt.END);
            center.addView(questionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            metaView = new TextView(context);
            metaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            metaView.setSingleLine(true);
            metaView.setEllipsize(TextUtils.TruncateAt.END);
            center.addView(metaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

            ansBadge = new TextView(context);
            ansBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            ansBadge.setTypeface(AndroidUtilities.bold());
            ansBadge.setSingleLine(true);
            ansBadge.setPadding(dp(8), dp(3), dp(8), dp(3));

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (LocaleController.isRTL) {
                row.addView(ansBadge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 16, 0, 8, 0));
                row.addView(center, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 8, 0, 12, 0));
                row.addView(numberBadge, LayoutHelper.createLinear(32, 32, Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
            } else {
                row.addView(numberBadge, LayoutHelper.createLinear(32, 32, Gravity.CENTER_VERTICAL, 16, 0, 12, 0));
                row.addView(center, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
                row.addView(ansBadge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
            }
            addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 12, 0, 12));
            updateColors();
        }

        public void bind(int number, EveAdminModels.AdminQuestionItem q) {
            numberBadge.setText("Q" + number);
            questionView.setText(q.questionText);
            metaView.setText(q.topic.isEmpty() ? "General" : q.topic);
            ansBadge.setText("Ans: " + (q.correctAnswer.isEmpty() ? "?" : q.correctAnswer));
            updateColors();
        }

        @Override
        public void updateColors() {
            int blue = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider);
            numberBadge.setTextColor(blue);
            numberBadge.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.multAlpha(blue, 0.12f)));
            questionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            metaView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            ansBadge.setTextColor(0xFF27B434);
            ansBadge.setBackground(Theme.createRoundRectDrawable(dp(8), Theme.multAlpha(0xFF27B434, 0.12f)));
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_windowBackgroundWhiteBlueText);
            colors.add(Theme.key_windowBackgroundWhiteBlackText);
            colors.add(Theme.key_windowBackgroundWhiteGrayText);
        }

        public static final class Factory extends UItem.UItemFactory<QuestionRowCell> {
            static {
                setup(new Factory());
            }

            @Override
            public QuestionRowCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new QuestionRowCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                if (item.object instanceof EveAdminModels.AdminQuestionItem) {
                    ((QuestionRowCell) view).bind(item.intValue, (EveAdminModels.AdminQuestionItem) item.object);
                }
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                if (a.object instanceof EveAdminModels.AdminQuestionItem && b.object instanceof EveAdminModels.AdminQuestionItem) {
                    return TextUtils.equals(((EveAdminModels.AdminQuestionItem) a.object).id,
                            ((EveAdminModels.AdminQuestionItem) b.object).id);
                }
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                if (a.object instanceof EveAdminModels.AdminQuestionItem && b.object instanceof EveAdminModels.AdminQuestionItem) {
                    EveAdminModels.AdminQuestionItem qa = (EveAdminModels.AdminQuestionItem) a.object;
                    EveAdminModels.AdminQuestionItem qb = (EveAdminModels.AdminQuestionItem) b.object;
                    return a.intValue == b.intValue
                            && TextUtils.equals(qa.questionText, qb.questionText)
                            && TextUtils.equals(qa.topic, qb.topic)
                            && TextUtils.equals(qa.correctAnswer, qb.correctAnswer);
                }
                return Objects.equals(a.object, b.object);
            }

            public static UItem of(int id, int number, EveAdminModels.AdminQuestionItem question) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.intValue = number;
                item.object = question;
                return item;
            }
        }
    }
}
