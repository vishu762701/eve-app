package org.telegram.ui.eve.admin;

import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen Exam & Score Analytics screen with attempt totals, average and highest score per exam,
 * and direct navigation into {@link EveAdminExamDetailFragment}.
 */
public class EveAdminExamAnalyticsFragment extends BaseFragment {

    private static final int ID_RETRY = 101;
    private static final int ID_SUM_ATTEMPTS = 201;
    private static final int ID_SUM_EXAMS = 202;
    private static final int ID_SUM_AVG = 203;
    private static final int BASE_EXAM_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private List<EveAdminModels.ExamAnalyticsItem> analytics = Collections.emptyList();
    private boolean loading = true;
    private boolean openingExam = false;
    private boolean destroyed = false;
    private String errorText = "";
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
    public View createView(Context context) {
        destroyed = false;
        setHasOwnBackground(true);

        List<EveAdminModels.ExamAnalyticsItem> cachedAnalytics = repo.getCachedExamAnalytics();
        if (cachedAnalytics != null && !cachedAnalytics.isEmpty()) {
            List<EveAdminModels.ExamAnalyticsItem> sortedCached = new ArrayList<>(cachedAnalytics);
            Collections.sort(sortedCached, new Comparator<EveAdminModels.ExamAnalyticsItem>() {
                @Override
                public int compare(EveAdminModels.ExamAnalyticsItem a, EveAdminModels.ExamAnalyticsItem b) {
                    int byAttempts = Integer.compare(b.attemptCount, a.attemptCount);
                    if (byAttempts != 0) return byAttempts;
                    return a.examName.compareToIgnoreCase(b.examName);
                }
            });
            analytics = sortedCached;
            loading = false;
        }

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Exam & Score Analytics",
                analytics.isEmpty() ? "Loading analytics..." : analytics.size() + " exams tracked",
                true,
                true,
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
        loading = analytics.isEmpty();
        errorText = "";
        if (loading) {
            updateList();
        }
        EveAdminRepository.ResultCallback<List<EveAdminModels.ExamAnalyticsItem>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.ExamAnalyticsItem>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.ExamAnalyticsItem> data) {
                        if (destroyed) return;
                        loading = false;
                        errorText = "";
                        autoSync.markSynced();
                        List<EveAdminModels.ExamAnalyticsItem> sorted = data != null ? new ArrayList<>(data) : new ArrayList<>();
                        Collections.sort(sorted, new Comparator<EveAdminModels.ExamAnalyticsItem>() {
                            @Override
                            public int compare(EveAdminModels.ExamAnalyticsItem a, EveAdminModels.ExamAnalyticsItem b) {
                                int byAttempts = Integer.compare(b.attemptCount, a.attemptCount);
                                if (byAttempts != 0) return byAttempts;
                                return a.examName.compareToIgnoreCase(b.examName);
                            }
                        });
                        analytics = sorted;
                        if (simpleActionBar != null) {
                            simpleActionBar.setSubtitle(analytics.size() + " exams tracked");
                        }
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
                        autoSync.markSynced();
                        errorText = message != null ? message : "Unable to load exam analytics.";
                        if (simpleActionBar != null && analytics.isEmpty()) {
                            simpleActionBar.setSubtitle("Unable to load");
                        }
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchExamAnalytics(token)));
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
                    "Retry Loading Analytics",
                    errorText,
                    "Retry"));
            items.add(UItem.asShadow(null));
        }

        if (loading && analytics.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        int totalAttempts = 0;
        int activeExams = 0;
        double weightedScoreSum = 0.0;
        for (EveAdminModels.ExamAnalyticsItem item : analytics) {
            totalAttempts += item.attemptCount;
            if (item.attemptCount > 0) {
                activeExams++;
                weightedScoreSum += item.averageScore * item.attemptCount;
            }
        }
        double overallAvg = totalAttempts > 0 ? (weightedScoreSum / totalAttempts) : 0.0;

        items.add(UItem.asHeader("Overall Performance"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_SUM_ATTEMPTS,
                IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                R.drawable.msg_stats,
                "Total Graded Attempts",
                "Counted student test submissions",
                String.valueOf(totalAttempts)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_SUM_EXAMS,
                IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                R.drawable.msg_folders,
                "Active Exams",
                "Exams with recorded student attempts",
                activeExams + " / " + analytics.size()));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_SUM_AVG,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                R.drawable.msg_select,
                "Overall Average Score",
                "Weighted across all counted attempts",
                String.format(Locale.US, "%.1f", overallAvg)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Exam Breakdown"));
        if (analytics.isEmpty()) {
            items.add(UItem.asShadow("No exam attempts recorded yet."));
            return;
        }
        for (int i = 0; i < analytics.size(); i++) {
            EveAdminModels.ExamAnalyticsItem item = analytics.get(i);
            String sub = String.format(Locale.US, "%s • %d attempts • Avg %.1f • High %.1f",
                    item.category, item.attemptCount, item.averageScore, item.highestScore);
            items.add(SettingsActivity.SettingCell.Factory.of(
                    BASE_EXAM_ID + i,
                    item.attemptCount > 0 ? IconBackgroundColors.CYAN.top : IconBackgroundColors.GRAY.top,
                    item.attemptCount > 0 ? IconBackgroundColors.CYAN.bottom : IconBackgroundColors.GRAY.bottom,
                    R.drawable.msg_stats,
                    item.examName,
                    sub,
                    item.attemptCount > 0 ? String.format(Locale.US, "%.1f", item.highestScore) : "0"));
        }
        items.add(UItem.asShadow("Tap any exam to open its Exam Detail & Mock Tests hub."));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
            return;
        }
        int idx = item.id - BASE_EXAM_ID;
        if (idx >= 0 && idx < analytics.size()) {
            openExamDetail(analytics.get(idx));
        }
    }

    private void openExamDetail(EveAdminModels.ExamAnalyticsItem analyticsItem) {
        if (openingExam || destroyed || analyticsItem == null) return;

        EveAdminModels.DashboardSnapshot cached = repo.getCachedSnapshot();
        if (cached != null) {
            for (EveAdminModels.AdminExamItem ex : cached.exams) {
                if (analyticsItem.examId.equals(ex.id)) {
                    presentFragment(new EveAdminExamDetailFragment(ex, cached.generatedTests, this::reload));
                    return;
                }
            }
        }

        openingExam = true;
        repo.loadExamsCatalogAsync(new EveAdminRepository.ResultCallback<EveAdminRepository.ExamsCatalogData>() {
            @Override
            public void onSuccess(EveAdminRepository.ExamsCatalogData data) {
                openingExam = false;
                if (destroyed || data == null) return;
                for (EveAdminModels.AdminExamItem ex : data.exams) {
                    if (analyticsItem.examId.equals(ex.id)) {
                        presentFragment(new EveAdminExamDetailFragment(ex, data.generatedTests, EveAdminExamAnalyticsFragment.this::reload));
                        return;
                    }
                }
                BulletinFactory.of(EveAdminExamAnalyticsFragment.this)
                        .createErrorBulletin("Exam record no longer exists.").show();
            }

            @Override
            public void onError(String message, int httpStatus) {
                openingExam = false;
                if (!destroyed) {
                    BulletinFactory.of(EveAdminExamAnalyticsFragment.this).createErrorBulletin(message).show();
                }
            }
        });
    }
}
