package org.telegram.ui.eve.exams;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.SimpleActionBarMenuItem;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.admin.EveAdminUiChrome;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edge-to-Edge Exam Tests directory displaying live mock tests immediately at the top,
 * with built-in Telegram search and clean overflow menu for Leaderboard, Result History,
 * Announcements & Polls, Exam Info, and Refresh.
 */
public class EveExamTestsFragment extends BaseFragment {

    private static final int MENU_SEARCH = 1;
    private static final int MENU_MORE = 2;
    private static final int SUB_LEADERBOARD = 101;
    private static final int SUB_HISTORY = 102;
    private static final int SUB_ANNOUNCEMENTS = 103;
    private static final int SUB_EXAM_INFO = 104;

    private static final int ID_RETRY = 200;
    private static final int BASE_TEST_ID = 1000;

    private final EveExamModels.ExamSummary exam;
    private UniversalRecyclerView listView;
    private SimpleActionBarMenuItem searchItem;
    private List<EveExamModels.GeneratedTestSummary> tests = Collections.emptyList();
    private final List<EveExamModels.GeneratedTestSummary> liveTests = new ArrayList<>();
    private final List<EveExamModels.GeneratedTestSummary> filteredTests = new ArrayList<>();
    private boolean loading = true;
    private boolean destroyed = false;
    private String errorMessage = "";
    private String searchQuery = "";
    private final EveAdminUiChrome.AutoSyncController autoSync = new EveAdminUiChrome.AutoSyncController(this::loadAll);

    public EveExamTestsFragment(EveExamModels.ExamSummary exam) {
        this.exam = exam;
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

        String title = exam != null && !exam.examName.isEmpty() ? exam.examName : "Exam";
        String subtitle = buildCompactSubtitle();

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                title,
                subtitle,
                false,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == SUB_LEADERBOARD) {
                            String target = exam != null && !exam.id.isEmpty() ? exam.id : "overall";
                            String lbTitle = exam != null && !exam.examName.isEmpty() ? exam.examName : LocaleController.getString(R.string.EveLeaderboard);
                            presentFragment(new EveLeaderboardFragment(target, lbTitle));
                        } else if (id == SUB_HISTORY) {
                            presentFragment(new EveResultHistoryFragment());
                        } else if (id == SUB_ANNOUNCEMENTS) {
                            presentFragment(new EveAnnouncementsFragment());
                        } else if (id == SUB_EXAM_INFO) {
                            showExamInfoDialog();
                        }
                    }
                },
                null
        );
        listView = chrome.listView;

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            searchItem = menu.addItem(MENU_SEARCH, R.drawable.outline_header_search, getResourceProvider());
            searchItem.setIsSearchField(true)
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
                            String next = editText.getText() != null ? editText.getText().toString() : "";
                            applyFilter(next);
                        }
                    });
            searchItem.setSearchFieldHint("Search mock tests");

            SimpleActionBarMenuItem moreItem = menu.addItem(MENU_MORE, R.drawable.ic_ab_other, getResourceProvider());
            moreItem.addSubItem(SUB_LEADERBOARD, R.drawable.msg_stats, LocaleController.getString(R.string.EveLeaderboard));
            moreItem.addSubItem(SUB_HISTORY, R.drawable.msg_recent, LocaleController.getString(R.string.EveResultHistory));
            moreItem.addSubItem(SUB_ANNOUNCEMENTS, R.drawable.msg_notifications, "Announcements & Polls");
            moreItem.addSubItem(SUB_EXAM_INFO, R.drawable.msg_info, "Exam Info");
        }

        fragmentView = chrome.rootView;
        loadAll();
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

    private void loadAll() {
        loadTests();
    }

    private String buildCompactSubtitle() {
        if (exam == null) {
            return "Mock Tests";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(exam.category)
                .append(" • ").append(exam.timeLimitMinutes).append(" min")
                .append(" • ").append(exam.questionCount).append(" Qs");
        String neg = exam.negativeMarkingText != null ? exam.negativeMarkingText.trim() : "";
        if (!neg.isEmpty() && !"0".equals(neg) && !"0.0".equals(neg)) {
            sb.append(" • -").append(neg);
        }
        return sb.toString();
    }

    private void loadTests() {
        if (exam == null) {
            loading = false;
            return;
        }
        if (tests.isEmpty()) {
            List<EveExamModels.GeneratedTestSummary> cached = EveExamRepository.getInstance().getCachedTestsForExam(exam.id);
            if (cached != null && !cached.isEmpty()) {
                tests = cached;
                liveTests.clear();
                for (EveExamModels.GeneratedTestSummary t : tests) {
                    if (t != null && t.isLive()) {
                        liveTests.add(t);
                    }
                }
                Collections.sort(liveTests, NATURAL_TEST_COMPARATOR);
                applyFilter(searchQuery);
            }
        }
        loading = tests.isEmpty();
        errorMessage = "";
        if (loading) {
            updateList();
        }
        EveExamRepository.getInstance().loadTestsForExam(exam.id, (loaded, err) -> {
            if (destroyed) return;
            loading = false;
            autoSync.markSynced();
            tests = loaded != null ? loaded : Collections.emptyList();
            errorMessage = err != null ? err : "";
            liveTests.clear();
            for (EveExamModels.GeneratedTestSummary t : tests) {
                if (t != null && t.isLive()) {
                    liveTests.add(t);
                }
            }
            Collections.sort(liveTests, NATURAL_TEST_COMPARATOR);
            applyFilter(searchQuery);
        });
    }

    private static final Pattern LABELED_NUMBER_PATTERN = Pattern.compile("(?:test|mock|set|paper|#)\\s*[-:#]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_NUMBER_PATTERN = Pattern.compile("(\\d+)\\s*$");
    private static final Pattern ANY_NUMBER_PATTERN = Pattern.compile("(\\d+)");

    public static final Comparator<EveExamModels.GeneratedTestSummary> NATURAL_TEST_COMPARATOR =
            EveExamTestsFragment::compareTestsAscending;

    public static int extractTestNumber(EveExamModels.GeneratedTestSummary test) {
        if (test == null) {
            return Integer.MAX_VALUE;
        }
        String label = test.testNumber != null ? test.testNumber.trim() : "";
        if (!label.isEmpty()) {
            Matcher m = LABELED_NUMBER_PATTERN.matcher(label);
            if (m.find()) {
                return safeParsePositiveInt(m.group(1));
            }
            Matcher mAny = ANY_NUMBER_PATTERN.matcher(label);
            if (mAny.find()) {
                return safeParsePositiveInt(mAny.group(1));
            }
        }
        String title = test.getDisplayTitle() != null ? test.getDisplayTitle().trim() : "";
        if (!title.isEmpty()) {
            Matcher m = LABELED_NUMBER_PATTERN.matcher(title);
            if (m.find()) {
                return safeParsePositiveInt(m.group(1));
            }
            Matcher mTrail = TRAILING_NUMBER_PATTERN.matcher(title);
            if (mTrail.find()) {
                return safeParsePositiveInt(mTrail.group(1));
            }
        }
        return Integer.MAX_VALUE;
    }

    private static int safeParsePositiveInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    public static int compareTestsAscending(
            EveExamModels.GeneratedTestSummary a,
            EveExamModels.GeneratedTestSummary b) {
        if (a == b) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        int numA = extractTestNumber(a);
        int numB = extractTestNumber(b);
        if (numA != numB) {
            return Integer.compare(numA, numB);
        }
        if (a.generatedAtMs != b.generatedAtMs) {
            if (a.generatedAtMs <= 0L) return 1;
            if (b.generatedAtMs <= 0L) return -1;
            return Long.compare(a.generatedAtMs, b.generatedAtMs);
        }
        int titleCmp = a.getDisplayTitle().compareToIgnoreCase(b.getDisplayTitle());
        if (titleCmp != 0) {
            return titleCmp;
        }
        return a.id.compareTo(b.id);
    }

    private void applyFilter(String query) {
        searchQuery = query != null ? query.trim() : "";
        filteredTests.clear();
        String q = searchQuery.toLowerCase(Locale.US);
        for (EveExamModels.GeneratedTestSummary t : liveTests) {
            if (q.isEmpty()
                    || t.getDisplayTitle().toLowerCase(Locale.US).contains(q)
                    || t.testNumber.toLowerCase(Locale.US).contains(q)) {
                filteredTests.add(t);
            }
        }
        Collections.sort(filteredTests, NATURAL_TEST_COMPARATOR);
        updateList();
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (exam == null) {
            return;
        }
        EveAdminUiChrome.addTopSpace(items);

        if (loading && tests.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (!errorMessage.isEmpty()) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_RETRY,
                    IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                    R.drawable.msg_retry,
                    "Retry Loading Tests",
                    errorMessage,
                    "Retry"));
            return;
        }

        if (filteredTests.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.EveExamTests)));
            items.add(UItem.asShadow(searchQuery.isEmpty()
                    ? "No published mock tests available yet for this exam."
                    : "No matching mock tests found."));
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());
        long nowMs = System.currentTimeMillis();
        int durationMins = exam.timeLimitMinutes > 0 ? exam.timeLimitMinutes : 30;

        for (int i = 0; i < filteredTests.size(); i++) {
            EveExamModels.GeneratedTestSummary test = filteredTests.get(i);
            int qCount = test.questionCount > 0 ? test.questionCount : exam.questionCount;
            boolean isScheduled = test.availableFromMs > nowMs;
            String subtitle = isScheduled
                    ? ("Opens " + fmt.format(new Date(test.availableFromMs)))
                    : (qCount + " Qs • " + durationMins + " min");
            String badge = isScheduled ? "Scheduled" : LocaleController.getString(R.string.EveStartTest);

            items.add(EveAdminUiChrome.AvatarRowCell.Factory.of(
                    BASE_TEST_ID + i,
                    test,
                    new EveAdminUiChrome.AvatarRowCell.Payload(
                            test.id,
                            test.getDisplayTitle(),
                            subtitle,
                            test.avatarUrl,
                            test.avatarEmoji,
                            test.avatarColor,
                            badge,
                            isScheduled
                                    ? EveAdminUiChrome.AvatarRowCell.BADGE_NEUTRAL
                                    : EveAdminUiChrome.AvatarRowCell.BADGE_BLUE)));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            loadTests();
            return;
        }
        int idx = item.id - BASE_TEST_ID;
        if (idx >= 0 && idx < filteredTests.size()) {
            openTestLaunchDialog(filteredTests.get(idx));
        }
    }

    private void showExamInfoDialog() {
        Activity activity = getParentActivity();
        if (activity == null || exam == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(LocaleController.getString(R.string.EveCategory)).append(": ").append(exam.category).append("\n");
        sb.append(LocaleController.getString(R.string.EveTimeLimit)).append(": ").append(exam.timeLimitMinutes).append(" min\n");
        sb.append(LocaleController.getString(R.string.EveQuestionsPerTest)).append(": ").append(exam.questionCount).append("\n");
        sb.append(LocaleController.getString(R.string.EveNegativeMarking)).append(": ").append(exam.negativeMarkingText).append("\n");
        sb.append("Live Mock Tests: ").append(liveTests.size());
        if (!TextUtils.isEmpty(exam.nextTestLabel)) {
            sb.append("\nLatest Test: ").append(exam.nextTestLabel);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle(exam.examName);
        builder.setMessage(sb.toString());
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        showDialog(builder.create());
    }

    private void openTestLaunchDialog(EveExamModels.GeneratedTestSummary selected) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        String attemptKey = EveExamModels.buildAttemptKey(exam != null ? exam.id : selected.examId, selected.id);
        int qCount = selected.questionCount > 0 ? selected.questionCount : (exam != null ? exam.questionCount : 20);
        int mins = exam != null ? exam.timeLimitMinutes : 30;
        String neg = exam != null ? exam.negativeMarkingText : "0";

        String msg = LocaleController.getString(R.string.EveQuestionsPerTest) + ": " + qCount + "\n"
                + LocaleController.getString(R.string.EveTimeLimit) + ": " + mins + " min\n"
                + LocaleController.getString(R.string.EveNegativeMarking) + ": " + neg;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle(selected.getDisplayTitle());
        builder.setMessage(msg);
        builder.setPositiveButton(LocaleController.getString(R.string.EveStartTest), (d, w) ->
                presentFragment(new EveTestPlayerFragment(exam, selected)));
        builder.setNeutralButton(LocaleController.getString(R.string.EveLeaderboard), (d, w) ->
                presentFragment(new EveLeaderboardFragment(attemptKey, selected.getDisplayTitle())));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }
}
