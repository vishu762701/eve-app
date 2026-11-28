package org.telegram.ui.eve.exams;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.SimpleActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.admin.EveAdminConfig;
import org.telegram.ui.eve.admin.EveAdminExamDetailFragment;
import org.telegram.ui.eve.admin.EveAdminExamPromptFragment;
import org.telegram.ui.eve.admin.EveAdminModels;
import org.telegram.ui.eve.admin.EveAdminRepository;
import org.telegram.ui.eve.admin.EveAdminUiChrome;
import org.telegram.ui.eve.admin.EveAnimatedEmojiHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final int MENU_ADMIN_ADD = 3;
    private static final int SUB_LEADERBOARD = 101;
    private static final int SUB_HISTORY = 102;
    private static final int SUB_ANNOUNCEMENTS = 103;
    private static final int SUB_EXAM_INFO = 104;
    private static final int SUB_DOWNLOAD_SYLLABUS = 105;
    private static final int SUB_MANAGE_EXAM = 106;
    private static final int SUB_GENERATE_AI_TEST = 107;
    private static final int SUB_ASSEMBLE_BANK = 108;

    private static final int ID_RETRY = 200;
    private static final int BASE_TEST_ID = 1000;

    private final EveExamModels.ExamSummary exam;
    private UniversalRecyclerView listView;
    private SimpleActionBarMenuItem searchItem;
    private SimpleActionBarMenuItem adminAddItem;
    @Nullable
    private EveAdminUiChrome.AvatarHeaderHandle avatarHeader;
    private List<EveExamModels.GeneratedTestSummary> tests = Collections.emptyList();
    private final List<EveExamModels.GeneratedTestSummary> liveTests = new ArrayList<>();
    private final List<EveExamModels.GeneratedTestSummary> filteredTests = new ArrayList<>();
    private final Map<String, EveExamModels.AttemptResult> latestAttemptsByKey = new HashMap<>();
    private boolean loading = true;
    private boolean destroyed = false;
    private String errorMessage = "";
    private String searchQuery = "";
    private final EveAdminUiChrome.AutoSyncController autoSync = new EveAdminUiChrome.AutoSyncController(this::loadAll);

    public static final class SmartTestRowMeta {
        public final String subtitle;
        public final String badge;
        public final int badgeStyle;

        public SmartTestRowMeta(String subtitle, String badge, int badgeStyle) {
            this.subtitle = subtitle != null ? subtitle : "";
            this.badge = badge != null ? badge : "";
            this.badgeStyle = badgeStyle;
        }
    }

    public static SmartTestRowMeta buildSmartTestRowMeta(
            @NonNull EveExamModels.GeneratedTestSummary test,
            @Nullable EveExamModels.ExamSummary exam,
            @Nullable EveExamModels.AttemptResult attempt,
            long nowMs) {
        if (!test.isLive()) {
            int qCount = test.questionCount > 0 ? test.questionCount : (exam != null ? exam.questionCount : 0);
            String sub = qCount > 0 ? ("Draft • " + qCount + " Qs") : "Draft";
            return new SmartTestRowMeta(sub, "Draft", EveAdminUiChrome.AvatarRowCell.BADGE_RED);
        }
        if (test.availableFromMs > nowMs) {
            SimpleDateFormat fmt = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());
            return new SmartTestRowMeta(
                    "Opens " + fmt.format(new Date(test.availableFromMs)),
                    "Scheduled",
                    EveAdminUiChrome.AvatarRowCell.BADGE_NEUTRAL);
        }
        if (attempt != null) {
            String scoreStr = (attempt.score == Math.rint(attempt.score))
                    ? String.valueOf((long) attempt.score)
                    : String.format(Locale.US, "%.1f", attempt.score);
            int pct = attempt.total > 0
                    ? Math.max(0, (int) Math.round((attempt.score * 100.0) / attempt.total))
                    : Math.max(0, attempt.getAccuracyPercent());
            String sub = String.format(Locale.US, "\u2713 Scored %s/%d \u2022 %d%%", scoreStr, attempt.total, pct);
            return new SmartTestRowMeta(sub, "Retake", EveAdminUiChrome.AvatarRowCell.BADGE_GREEN);
        }
        String unattemptedSub = (test.questionCount > 0 && exam != null && exam.questionCount > 0
                && test.questionCount != exam.questionCount)
                ? (test.questionCount + " Qs")
                : "";
        return new SmartTestRowMeta(unattemptedSub, "Start Test", EveAdminUiChrome.AvatarRowCell.BADGE_BLUE);
    }

    private boolean isExamAdmin() {
        return EveAdminUiChrome.hasCachedAdminRight(EveAdminModels.RIGHT_MANAGE_EXAMS);
    }

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
        String subtitle = buildHeaderMetadataSubtitle();

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
                false,
                this::fillItems,
                this::onClick,
                this::onLongClick,
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
                            org.telegram.ui.eve.auth.EveTelegramSessionManager.getInstance().openAnnouncementsDestination(EveExamTestsFragment.this);
                        } else if (id == SUB_EXAM_INFO) {
                            showExamInfoDialog();
                        } else if (id == SUB_DOWNLOAD_SYLLABUS) {
                            if (exam != null) {
                                EvePdfHelper.downloadSyllabusPdf(
                                        EveExamTestsFragment.this,
                                        exam.id,
                                        exam.examName,
                                        exam.syllabusUrl,
                                        exam.syllabusFileName
                                );
                            }
                        } else if (id == SUB_MANAGE_EXAM) {
                            openExamAdminDetail();
                        } else if (id == SUB_GENERATE_AI_TEST) {
                            openGenerateAiMockTest();
                        } else if (id == SUB_ASSEMBLE_BANK) {
                            assembleTestFromBankNow();
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

        boolean examAdmin = isExamAdmin();
        if (simpleActionBar != null) {
            avatarHeader = EveAdminUiChrome.attachAvatarActionBarHeader(
                    context,
                    simpleActionBar,
                    examAdmin ? 132 : 92,
                    getResourceProvider());
            if (avatarHeader != null) {
                avatarHeader.update(
                        exam != null ? exam.id : "",
                        title,
                        subtitle,
                        exam != null ? exam.avatarUrl : "",
                        exam != null ? exam.avatarEmoji : "",
                        exam != null ? exam.avatarColor : -1);
                avatarHeader.setOnClickListener(v -> {
                    if (isExamAdmin()) {
                        openExamAdminDetail();
                    } else {
                        showExamInfoDialog();
                    }
                });
            }

            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            if (examAdmin) {
                adminAddItem = menu.addItem(MENU_ADMIN_ADD, R.drawable.msg_add, getResourceProvider());
                adminAddItem.addSubItem(SUB_MANAGE_EXAM, R.drawable.msg_settings, "Manage Exam");
                adminAddItem.addSubItem(SUB_GENERATE_AI_TEST, R.drawable.msg_add, "Generate AI Mock Test");
                adminAddItem.addSubItem(SUB_ASSEMBLE_BANK, R.drawable.msg_list, "Assemble from Question Bank");
            }
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
            if (exam != null && exam.hasSyllabus()) {
                moreItem.addSubItem(SUB_DOWNLOAD_SYLLABUS, R.drawable.msg_view_file, "Download Syllabus PDF");
            }
        }

        hydrateAttemptHistory(EveExamRepository.getInstance().getCachedAttemptHistory());
        if (EveAdminConfig.isAdminAvailable() && EveAdminRepository.getInstance().getCachedSnapshot() == null) {
            EveAdminRepository.getInstance().loadDashboardAsync(new EveAdminRepository.ResultCallback<EveAdminModels.DashboardSnapshot>() {
                @Override
                public void onSuccess(EveAdminModels.DashboardSnapshot data) {
                    if (!destroyed) {
                        loadAll();
                    }
                }

                @Override
                public void onError(String message, int httpStatus) {
                }
            });
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
        hydrateAttemptHistory(EveExamRepository.getInstance().getCachedAttemptHistory());
        EveExamRepository.getInstance().loadAttemptHistory((list, err) -> {
            if (destroyed || list == null) return;
            hydrateAttemptHistory(list);
            updateList();
        });
        loadTests();
    }

    private void hydrateAttemptHistory(@Nullable List<EveExamModels.AttemptResult> history) {
        if (history == null) return;
        for (EveExamModels.AttemptResult item : history) {
            if (item == null || TextUtils.isEmpty(item.examId)) continue;
            EveExamModels.AttemptResult prev = latestAttemptsByKey.get(item.examId);
            if (prev == null || item.timestampMs >= prev.timestampMs) {
                latestAttemptsByKey.put(item.examId, item);
            }
        }
    }

    private String buildHeaderMetadataSubtitle() {
        if (exam == null) {
            return "Mock Tests";
        }
        return String.format(Locale.US, "%d min \u2022 %d Qs",
                Math.max(1, exam.timeLimitMinutes),
                Math.max(1, exam.questionCount));
    }

    private void loadTests() {
        if (exam == null) {
            loading = false;
            updateList();
            return;
        }
        final boolean adminMode = isExamAdmin();
        if (tests.isEmpty()) {
            List<EveExamModels.GeneratedTestSummary> cached = EveExamRepository.getInstance().getCachedTestsForExam(exam.id);
            if (cached != null && !cached.isEmpty()) {
                tests = cached;
                liveTests.clear();
                for (EveExamModels.GeneratedTestSummary t : tests) {
                    if (t != null && (adminMode || t.isLive())) {
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
            boolean hadNoTests = tests.isEmpty();
            loading = false;
            autoSync.markSynced();
            List<EveExamModels.GeneratedTestSummary> nextTests = loaded != null ? loaded : Collections.emptyList();
            String nextErr = err != null ? err : "";
            List<EveExamModels.GeneratedTestSummary> nextLive = new ArrayList<>();
            for (EveExamModels.GeneratedTestSummary t : nextTests) {
                if (t != null && (adminMode || t.isLive())) {
                    nextLive.add(t);
                }
            }
            Collections.sort(nextLive, NATURAL_TEST_COMPARATOR);
            boolean changed = hadNoTests || !nextErr.equals(errorMessage) || !testsListEquals(liveTests, nextLive);
            tests = nextTests;
            errorMessage = nextErr;
            liveTests.clear();
            liveTests.addAll(nextLive);
            if (changed) {
                applyFilter(searchQuery);
            }
        });
    }

    private static boolean testsListEquals(
            List<EveExamModels.GeneratedTestSummary> a,
            List<EveExamModels.GeneratedTestSummary> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            EveExamModels.GeneratedTestSummary ta = a.get(i);
            EveExamModels.GeneratedTestSummary tb = b.get(i);
            if (!TextUtils.equals(ta.id, tb.id)
                    || !TextUtils.equals(ta.title, tb.title)
                    || !TextUtils.equals(ta.testNumber, tb.testNumber)
                    || !TextUtils.equals(ta.status, tb.status)
                    || ta.questionCount != tb.questionCount
                    || ta.availableFromMs != tb.availableFromMs
                    || !TextUtils.equals(ta.avatarUrl, tb.avatarUrl)) {
                return false;
            }
        }
        return true;
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

        long nowMs = System.currentTimeMillis();

        for (int i = 0; i < filteredTests.size(); i++) {
            EveExamModels.GeneratedTestSummary test = filteredTests.get(i);
            String attemptKey = EveExamModels.buildAttemptKey(exam.id, test.id);
            EveExamModels.AttemptResult attempt = latestAttemptsByKey.get(attemptKey);
            SmartTestRowMeta meta = buildSmartTestRowMeta(test, exam, attempt, nowMs);

            items.add(EveAdminUiChrome.AvatarRowCell.Factory.of(
                    BASE_TEST_ID + i,
                    test,
                    new EveAdminUiChrome.AvatarRowCell.Payload(
                            test.id,
                            test.getDisplayTitle(),
                            meta.subtitle,
                            test.avatarUrl,
                            test.avatarEmoji,
                            test.avatarColor,
                            meta.badge,
                            meta.badgeStyle)));
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

    private boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (!isExamAdmin() || item == null || item.id < BASE_TEST_ID) {
            return false;
        }
        int idx = item.id - BASE_TEST_ID;
        if (idx < 0 || idx >= filteredTests.size()) {
            return false;
        }
        EveExamModels.GeneratedTestSummary selected = filteredTests.get(idx);
        boolean isLive = selected.isLive();
        ItemOptions options = ItemOptions.makeOptions(this, view);
        options.add(
                isLive ? R.drawable.msg_archive : R.drawable.msg_folders,
                isLive ? "Move to Draft" : "Publish Live",
                () -> toggleTestStatus(selected, isLive ? "paused" : "live"));
        options.add(
                R.drawable.msg_delete,
                "Delete Test",
                () -> confirmDeleteTest(selected));
        options.makeRed(1);
        options.show();
        return true;
    }

    private void openExamAdminDetail() {
        EveAdminModels.AdminExamItem adminExam = EveAdminUiChrome.resolveAdminExamItem(exam);
        if (adminExam == null) return;
        EveAdminRepository.ExamsCatalogData catalog = EveAdminRepository.getInstance().getCachedExamsCatalog();
        presentFragment(new EveAdminExamDetailFragment(
                adminExam,
                catalog != null ? catalog.generatedTests : null,
                this::loadAll));
    }

    private void openGenerateAiMockTest() {
        EveAdminModels.AdminExamItem adminExam = EveAdminUiChrome.resolveAdminExamItem(exam);
        if (adminExam == null) return;
        EveAdminRepository.ExamsCatalogData catalog = EveAdminRepository.getInstance().getCachedExamsCatalog();
        presentFragment(new EveAdminExamPromptFragment(
                adminExam,
                catalog != null ? catalog.exams : Collections.singletonList(adminExam),
                this::loadAll));
    }

    private void assembleTestFromBankNow() {
        if (destroyed || exam == null) return;
        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Assembling test from Question Bank...").show();
        EveAdminRepository repo = EveAdminRepository.getInstance();
        EveAdminRepository.ResultCallback<JSONObject> cb = new EveAdminRepository.ResultCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject root) {
                if (destroyed) return;
                BulletinFactory.of(EveExamTestsFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, "Mock test assembled").show();
                loadAll();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                BulletinFactory.of(EveExamTestsFragment.this)
                        .createErrorBulletin(message != null ? message : "Assemble failed.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb,
                repo.assembleTestFromBank(token, exam.id, exam.questionCount, exam.testNumber)));
    }

    private void toggleTestStatus(EveExamModels.GeneratedTestSummary selected, String nextStatus) {
        if (destroyed || selected == null) return;
        EveAdminRepository repo = EveAdminRepository.getInstance();
        EveAdminRepository.ResultCallback<String> cb = new EveAdminRepository.ResultCallback<String>() {
            @Override
            public void onSuccess(String msg) {
                if (destroyed) return;
                repo.invalidateCache();
                BulletinFactory.of(EveExamTestsFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, msg).show();
                loadAll();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                BulletinFactory.of(EveExamTestsFragment.this)
                        .createErrorBulletin(message != null ? message : "Update failed.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.updateGeneratedTestStatus(token, selected.id, nextStatus);
            repo.postSuccess(cb, "live".equals(nextStatus) ? "Test published live." : "Test moved to draft.");
        });
    }

    private void confirmDeleteTest(EveExamModels.GeneratedTestSummary selected) {
        Activity activity = getParentActivity();
        if (activity == null || selected == null) return;
        String safeTitle = EveAnimatedEmojiHelper.stripMarkupToPlainText(selected.getDisplayTitle());
        showDialog(new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle("Delete Mock Test?")
                .setMessage("Delete \"" + safeTitle + "\" permanently? This action cannot be undone.")
                .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                    EveAdminRepository repo = EveAdminRepository.getInstance();
                    EveAdminRepository.ResultCallback<String> cb = new EveAdminRepository.ResultCallback<String>() {
                        @Override
                        public void onSuccess(String msg) {
                            if (destroyed) return;
                            repo.invalidateCache();
                            BulletinFactory.of(EveExamTestsFragment.this)
                                    .createSimpleBulletin(R.raw.ic_delete, msg).show();
                            loadAll();
                        }

                        @Override
                        public void onError(String message, int httpStatus) {
                            if (destroyed) return;
                            BulletinFactory.of(EveExamTestsFragment.this)
                                    .createErrorBulletin(message != null ? message : "Delete failed.").show();
                        }
                    };
                    repo.runWithAdminToken(cb, token -> {
                        repo.deleteGeneratedTest(token, selected.id);
                        repo.postSuccess(cb, "Mock test deleted.");
                    });
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .create());
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
        builder.setTitle(EveAnimatedEmojiHelper.decode(exam.examName, null));
        builder.setMessage(sb.toString());
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        showDialog(builder.create());
    }

    @NonNull
    public static List<String> getLaunchDialogActionOrder() {
        return java.util.Arrays.asList("Start Test", "Leaderboard", "Cancel");
    }

    private void openTestLaunchDialog(EveExamModels.GeneratedTestSummary selected) {
        Activity activity = getParentActivity();
        if (activity == null || selected == null) return;
        Theme.ResourcesProvider rp = getResourceProvider();
        String attemptKey = EveExamModels.buildAttemptKey(exam != null ? exam.id : selected.examId, selected.id);
        int qCount = selected.questionCount > 0 ? selected.questionCount : (exam != null ? exam.questionCount : 20);
        int mins = exam != null && exam.timeLimitMinutes > 0 ? exam.timeLimitMinutes : 30;
        String neg = exam != null && !TextUtils.isEmpty(exam.negativeMarkingText) ? exam.negativeMarkingText : "0";
        String negMeta = ("0".equals(neg) || "none".equalsIgnoreCase(neg))
                ? "No Negative Marking"
                : ("Negative Marking (-" + neg + ")");
        String metadata = qCount + " Qs • " + mins + " min • " + negMeta;
        EveExamModels.AttemptResult pastAttempt = latestAttemptsByKey.get(attemptKey);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(4), AndroidUtilities.dp(22), AndroidUtilities.dp(14));

        TextView metaView = new TextView(activity);
        metaView.setText(metadata);
        metaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        metaView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
        content.addView(metaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, pastAttempt != null ? 4 : 16));

        if (pastAttempt != null) {
            TextView prevView = new TextView(activity);
            String scoreStr = (pastAttempt.score == Math.rint(pastAttempt.score))
                    ? String.valueOf((long) pastAttempt.score)
                    : String.format(Locale.US, "%.1f", pastAttempt.score);
            int pct = pastAttempt.total > 0
                    ? Math.max(0, (int) Math.round((pastAttempt.score * 100.0) / pastAttempt.total))
                    : Math.max(0, pastAttempt.getAccuracyPercent());
            prevView.setText(String.format(Locale.US, "\u2713 Previous: Scored %s/%d (%d%%)", scoreStr, pastAttempt.total, pct));
            prevView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            prevView.setTypeface(AndroidUtilities.bold());
            prevView.setTextColor(0xFF27B434);
            content.addView(prevView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));
        }

        int blue = Theme.getColor(Theme.key_featuredStickers_addButton, rp);
        int blueFg = Theme.getColor(Theme.key_featuredStickers_buttonText, rp);
        int blueSecondary = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp);
        int redFg = Theme.getColor(Theme.key_text_RedBold, rp);

        final AlertDialog[] dialogHolder = new AlertDialog[1];

        TextView startBtn = new TextView(activity);
        startBtn.setText(LocaleController.getString(R.string.EveStartTest));
        startBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        startBtn.setTypeface(AndroidUtilities.bold());
        startBtn.setTextColor(blueFg);
        startBtn.setGravity(Gravity.CENTER);
        startBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(10),
                blue,
                Theme.multAlpha(blue, 0.85f)));
        startBtn.setOnClickListener(v -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            presentFragment(new EveTestPlayerFragment(exam, selected));
        });
        content.addView(startBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 0, 0, 0, 8));

        TextView leaderboardBtn = new TextView(activity);
        leaderboardBtn.setText(LocaleController.getString(R.string.EveLeaderboard));
        leaderboardBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        leaderboardBtn.setTypeface(AndroidUtilities.bold());
        leaderboardBtn.setTextColor(blueSecondary);
        leaderboardBtn.setGravity(Gravity.CENTER);
        leaderboardBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(10),
                Theme.multAlpha(blueSecondary, 0.11f),
                Theme.multAlpha(blueSecondary, 0.22f)));
        leaderboardBtn.setOnClickListener(v -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            presentFragment(new EveLeaderboardFragment(attemptKey, selected.getDisplayTitle()));
        });
        content.addView(leaderboardBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 0, 0, 6));

        TextView cancelBtn = new TextView(activity);
        cancelBtn.setText(LocaleController.getString(R.string.Cancel));
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        cancelBtn.setTypeface(AndroidUtilities.bold());
        cancelBtn.setTextColor(redFg);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(10),
                0,
                Theme.multAlpha(redFg, 0.10f)));
        cancelBtn.setOnClickListener(v -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
        });
        content.addView(cancelBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42));

        AlertDialog dialog = new AlertDialog.Builder(activity, rp)
                .setTitle(EveAnimatedEmojiHelper.decode(selected.getDisplayTitle(), null))
                .setView(content)
                .create();
        dialogHolder[0] = dialog;
        showDialog(dialog);
    }
}
