package org.telegram.ui.eve.exams;

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

import androidx.annotation.NonNull;
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
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.eve.admin.EveAdminUiChrome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Edge-to-Edge Sub-Exams directory matching Home Exams style with circular photo/GIF/emoji
 * avatars, test counts, and seamless drill-down into {@link EveExamTestsFragment}.
 */
public class EveSubExamsFragment extends BaseFragment {

    private static final int MENU_SEARCH = 1;
    private static final int BASE_SUB_ID = 1000;

    private final EveExamModels.ExamSummary parentExam;
    private UniversalRecyclerView listView;
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
                allSubExams.size() + " sub-exams",
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
                null
        );
        listView = chrome.listView;

        if (simpleActionBar != null) {
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

        fragmentView = chrome.rootView;
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
            String plainName = org.telegram.ui.eve.admin.EveAnimatedEmojiHelper.stripMarkupToPlainText(item.examName).toLowerCase(Locale.US);
            if (q.isEmpty() || plainName.contains(q)
                    || item.category.toLowerCase(Locale.US).contains(q)) {
                filtered.add(item);
            }
        }
        if (simpleActionBar != null) {
            simpleActionBar.setSubtitle(q.isEmpty()
                    ? allSubExams.size() + " sub-exams"
                    : filtered.size() + " matching");
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);
        if (filtered.isEmpty()) {
            String plainParent = org.telegram.ui.eve.admin.EveAnimatedEmojiHelper.stripMarkupToPlainText(parentExam.examName);
            items.add(UItem.asShadow(searchQuery.isEmpty()
                    ? "No active sub-exams under " + plainParent + "."
                    : "No matching sub-exams found."));
            return;
        }
        for (int i = 0; i < filtered.size(); i++) {
            items.add(SubExamRowCell.Factory.of(BASE_SUB_ID + i, filtered.get(i)));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        int idx = item.id - BASE_SUB_ID;
        if (idx >= 0 && idx < filtered.size()) {
            presentFragment(new EveExamTestsFragment(filtered.get(idx)));
        }
    }

    private static final class SubExamRowCell extends FrameLayout implements Theme.Colorable {

        private final Theme.ResourcesProvider rp;
        private final AvatarDrawable avatarDrawable;
        private final BackupImageView avatarView;
        private final org.telegram.ui.Components.AnimatedEmojiSpan.TextViewEmojis titleView;
        private final TextView subtitleView;
        private final TextView badgeView;

        SubExamRowCell(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.rp = rp;

            avatarDrawable = new AvatarDrawable();
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(26));

            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);

            titleView = org.telegram.ui.eve.admin.EveAnimatedEmojiHelper.createEmojiTextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16.5f);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

            badgeView = new TextView(context);
            badgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            badgeView.setTypeface(AndroidUtilities.bold());
            badgeView.setPadding(dp(9), dp(3), dp(9), dp(3));

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(avatarView, LayoutHelper.createLinear(52, 52, Gravity.CENTER_VERTICAL, 14, 0, 12, 0));
            row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
            row.addView(badgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

            addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 10, 0, 10));
            updateColors();
        }

        void bind(EveExamModels.ExamSummary sub) {
            EveAdminUiChrome.bindExamAvatar(
                    avatarView, avatarDrawable, sub.id, sub.examName,
                    sub.avatarUrl, sub.avatarEmoji, sub.avatarColor, 52, rp);
            org.telegram.ui.eve.admin.EveAnimatedEmojiHelper.bindText(titleView, sub.examName);
            subtitleView.setText(EveExamDialogsMapper.buildExamSubtitle(sub));
            if (sub.liveTestCount > 0) {
                badgeView.setText(String.valueOf(sub.liveTestCount));
                badgeView.setVisibility(VISIBLE);
            } else {
                badgeView.setVisibility(GONE);
            }
            updateColors();
        }

        @Override
        public void updateColors() {
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
            int blue = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp);
            badgeView.setTextColor(0xFFFFFFFF);
            badgeView.setBackground(Theme.createRoundRectDrawable(dp(12), blue));
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_windowBackgroundWhiteBlackText);
            colors.add(Theme.key_windowBackgroundWhiteGrayText);
            colors.add(Theme.key_windowBackgroundWhiteBlueText);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(72), MeasureSpec.EXACTLY));
        }

        static final class Factory extends UItem.UItemFactory<SubExamRowCell> {
            static { setup(new Factory()); }

            @Override
            public SubExamRowCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new SubExamRowCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                if (item.object instanceof EveExamModels.ExamSummary) {
                    ((SubExamRowCell) view).bind((EveExamModels.ExamSummary) item.object);
                }
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                if (a.object instanceof EveExamModels.ExamSummary && b.object instanceof EveExamModels.ExamSummary) {
                    return TextUtils.equals(((EveExamModels.ExamSummary) a.object).id,
                            ((EveExamModels.ExamSummary) b.object).id);
                }
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                if (a.object instanceof EveExamModels.ExamSummary && b.object instanceof EveExamModels.ExamSummary) {
                    EveExamModels.ExamSummary ea = (EveExamModels.ExamSummary) a.object;
                    EveExamModels.ExamSummary eb = (EveExamModels.ExamSummary) b.object;
                    return ea.liveTestCount == eb.liveTestCount
                            && Objects.equals(ea.examName, eb.examName)
                            && Objects.equals(ea.avatarUrl, eb.avatarUrl)
                            && Objects.equals(ea.avatarEmoji, eb.avatarEmoji)
                            && ea.avatarColor == eb.avatarColor;
                }
                return Objects.equals(a.object, b.object);
            }

            static UItem of(int id, EveExamModels.ExamSummary sub) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.object = sub;
                return item;
            }
        }
    }
}
