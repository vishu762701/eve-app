package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
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
import java.util.Objects;

/**
 * Full-screen Exam & Score Analytics screen with attempt totals, average and highest score per exam,
 * and direct navigation into {@link EveAdminExamDetailFragment}.
 */
public class EveAdminExamAnalyticsFragment extends BaseFragment {

    private static final int ID_RETRY = 101;
    private static final int ID_RANGE_CHIPS = 150;
    private static final int ID_BAR_CHART = 160;
    private static final int ID_SUM_ATTEMPTS = 201;
    private static final int ID_SUM_EXAMS = 202;
    private static final int ID_SUM_AVG = 203;
    private static final int BASE_EXAM_ID = 1000;
    private static final int BASE_QUESTION_ID = 5000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private List<EveAdminModels.ExamAnalyticsItem> analytics = Collections.emptyList();
    private List<EveAdminModels.QuestionAnalyticsItem> questionAnalytics = Collections.emptyList();
    private int selectedDays = 0;
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

    private static final class AnalyticsBundle {
        final List<EveAdminModels.ExamAnalyticsItem> exams;
        final List<EveAdminModels.QuestionAnalyticsItem> questions;

        AnalyticsBundle(List<EveAdminModels.ExamAnalyticsItem> exams, List<EveAdminModels.QuestionAnalyticsItem> questions) {
            this.exams = exams;
            this.questions = questions;
        }
    }

    private String rangeLabelForDays(int days) {
        if (days == 7) return "7 Days";
        if (days == 30) return "30 Days";
        return "All Time";
    }

    private void selectDays(int nextDays) {
        if (selectedDays == nextDays) return;
        selectedDays = nextDays;
        analytics = Collections.emptyList();
        loading = true;
        updateList();
        reload();
    }

    private void reload() {
        if (destroyed) return;
        final int requestDays = selectedDays;
        loading = analytics.isEmpty();
        errorText = "";
        if (loading) {
            updateList();
        }
        EveAdminRepository.ResultCallback<AnalyticsBundle> cb =
                new EveAdminRepository.ResultCallback<AnalyticsBundle>() {
                    @Override
                    public void onSuccess(AnalyticsBundle data) {
                        if (destroyed || requestDays != selectedDays) return;
                        loading = false;
                        errorText = "";
                        autoSync.markSynced();
                        List<EveAdminModels.ExamAnalyticsItem> sorted = (data != null && data.exams != null)
                                ? new ArrayList<>(data.exams)
                                : new ArrayList<>();
                        Collections.sort(sorted, new Comparator<EveAdminModels.ExamAnalyticsItem>() {
                            @Override
                            public int compare(EveAdminModels.ExamAnalyticsItem a, EveAdminModels.ExamAnalyticsItem b) {
                                int byAttempts = Integer.compare(b.attemptCount, a.attemptCount);
                                if (byAttempts != 0) return byAttempts;
                                return a.examName.compareToIgnoreCase(b.examName);
                            }
                        });
                        analytics = sorted;
                        questionAnalytics = (data != null && data.questions != null)
                                ? data.questions
                                : Collections.emptyList();
                        if (simpleActionBar != null) {
                            simpleActionBar.setSubtitle(rangeLabelForDays(selectedDays) + " • " + analytics.size() + " exams");
                        }
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed || requestDays != selectedDays) return;
                        loading = false;
                        autoSync.markSynced();
                        errorText = message != null ? message : "Unable to load exam analytics.";
                        if (simpleActionBar != null && analytics.isEmpty()) {
                            simpleActionBar.setSubtitle("Unable to load");
                        }
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> {
            List<EveAdminModels.ExamAnalyticsItem> examList = repo.fetchExamAnalytics(token, requestDays);
            List<EveAdminModels.QuestionAnalyticsItem> qList;
            try {
                qList = repo.fetchQuestionAnalytics(token, null);
            } catch (Throwable ignored) {
                qList = Collections.emptyList();
            }
            repo.postSuccess(cb, new AnalyticsBundle(examList, qList));
        });
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        items.add(RangeChipsView.Factory.of(ID_RANGE_CHIPS, selectedDays, this::selectDays));
        items.add(UItem.asShadow(null));

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

        if (!analytics.isEmpty()) {
            items.add(UItem.asHeader("Attempts Chart (" + rangeLabelForDays(selectedDays) + ")"));
            items.add(EveAdminAttemptsBarChartView.Factory.of(ID_BAR_CHART, analytics, rangeLabelForDays(selectedDays)));
            items.add(UItem.asShadow(null));
        }

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

        items.add(UItem.asHeader("Question Analytics (Hardest Questions)"));
        if (questionAnalytics.isEmpty()) {
            items.add(UItem.asShadow("No per-question analytics recorded yet."));
        } else {
            int maxQ = Math.min(25, questionAnalytics.size());
            for (int i = 0; i < maxQ; i++) {
                EveAdminModels.QuestionAnalyticsItem q = questionAnalytics.get(i);
                String qTitle = !TextUtils.isEmpty(q.questionText)
                        ? q.questionText
                        : ("Question #" + q.questionNumber);
                if (qTitle.length() > 70) {
                    qTitle = qTitle.substring(0, 67) + "...";
                }
                String qSub = String.format(Locale.US, "%s • Q%d • %d attempts • %.0f%% acc",
                        !TextUtils.isEmpty(q.examName) ? q.examName : "Exam",
                        q.questionNumber, q.attempts, q.accuracy);
                items.add(SettingsActivity.SettingCell.Factory.of(
                        BASE_QUESTION_ID + i,
                        q.wrongRate >= 50.0 ? IconBackgroundColors.RED.top : IconBackgroundColors.ORANGE.top,
                        q.wrongRate >= 50.0 ? IconBackgroundColors.RED.bottom : IconBackgroundColors.ORANGE.bottom,
                        R.drawable.msg_info,
                        qTitle,
                        qSub,
                        String.format(Locale.US, "%.0f%% wrong", q.wrongRate)));
            }
            items.add(UItem.asShadow("Sorted by highest wrong rate across student attempts."));
        }
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

    private interface IntConsumer {
        void accept(int val);
    }

    private static final class RangeChipsView extends LinearLayout implements Theme.Colorable {
        private final Theme.ResourcesProvider resourcesProvider;
        private final TextView[] chips = new TextView[3];
        private final int[] dayValues = new int[] { 7, 30, 0 };
        private final String[] labels = new String[] { "7 Days", "30 Days", "All Time" };
        private int activeDays = 0;

        RangeChipsView(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.resourcesProvider = rp;
            setOrientation(HORIZONTAL);
            setPadding(dp(14), dp(8), dp(14), dp(8));
            for (int i = 0; i < chips.length; i++) {
                TextView tv = new TextView(context);
                tv.setText(labels[i]);
                tv.setGravity(Gravity.CENTER);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                tv.setTypeface(AndroidUtilities.bold());
                chips[i] = tv;
                addView(tv, LayoutHelper.createLinear(0, 36, 1f, i == 0 ? 0 : 4, 0, i == 2 ? 0 : 4, 0));
            }
            updateColors();
        }

        void bind(int selectedDays, IntConsumer onPick) {
            this.activeDays = selectedDays;
            for (int i = 0; i < chips.length; i++) {
                final int d = dayValues[i];
                chips[i].setOnClickListener(v -> {
                    if (onPick != null) onPick.accept(d);
                });
            }
            updateColors();
        }

        @Override
        public void updateColors() {
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
            int accentText = Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider);
            int grayBg = Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider);
            int normalText = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            for (int i = 0; i < chips.length; i++) {
                boolean active = dayValues[i] == activeDays;
                chips[i].setBackground(Theme.createRoundRectDrawable(dp(10), active ? accent : grayBg));
                chips[i].setTextColor(active ? accentText : normalText);
            }
        }

        static final class Payload {
            final int selectedDays;
            final IntConsumer onPick;

            Payload(int selectedDays, IntConsumer onPick) {
                this.selectedDays = selectedDays;
                this.onPick = onPick;
            }
        }

        static final class Factory extends UItem.UItemFactory<RangeChipsView> {
            static { setup(new Factory()); }

            @Override
            public RangeChipsView createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider rp) {
                return new RangeChipsView(context, rp);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                if (view instanceof RangeChipsView && item.object instanceof Payload) {
                    Payload p = (Payload) item.object;
                    ((RangeChipsView) view).bind(p.selectedDays, p.onPick);
                }
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                if (!(a.object instanceof Payload) || !(b.object instanceof Payload)) {
                    return Objects.equals(a.object, b.object);
                }
                return ((Payload) a.object).selectedDays == ((Payload) b.object).selectedDays;
            }

            static UItem of(int id, int selectedDays, IntConsumer onPick) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.object = new Payload(selectedDays, onPick);
                return item;
            }
        }
    }
}
