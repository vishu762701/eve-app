package org.telegram.ui.eve.exams;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Native Telegram BaseFragment showing real exam details, instructions,
 * published mock tests, result history, and leaderboards from EVE Cloud.
 */
public class EveExamTestsFragment extends BaseFragment {

    private static final int ID_REFRESH = 1;
    private static final int ID_HISTORY = 300;
    private static final int ID_OVERALL_LEADERBOARD = 301;
    private static final int ID_ANNOUNCEMENTS = 302;

    private final EveExamModels.ExamSummary exam;
    private UniversalRecyclerView listView;
    private List<EveExamModels.GeneratedTestSummary> tests = Collections.emptyList();
    private boolean loading = true;
    private String errorMessage = "";

    public EveExamTestsFragment(EveExamModels.ExamSummary exam) {
        this.exam = exam;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(exam != null && !exam.examName.isEmpty() ? exam.examName : "Exam");
        if (exam != null) {
            actionBar.setSubtitle(exam.category + " • " + exam.timeLimitMinutes + " min");
        }
        actionBar.createMenu().addItem(ID_REFRESH, R.drawable.msg_retry);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == ID_REFRESH) {
                    loadTests();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = frameLayout;

        loadTests();
        return frameLayout;
    }

    private void loadTests() {
        if (exam == null) {
            loading = false;
            return;
        }
        loading = true;
        errorMessage = "";
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        EveExamRepository.getInstance().loadTestsForExam(exam.id, (loaded, err) -> {
            loading = false;
            tests = loaded != null ? loaded : Collections.emptyList();
            errorMessage = err != null ? err : "";
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        });
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (exam == null) {
            return;
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.EveExamConfiguration)));
        items.add(UItem.asButton(100, LocaleController.getString(R.string.EveCategory), exam.category));
        items.add(UItem.asButton(101, LocaleController.getString(R.string.EveTimeLimit), exam.timeLimitMinutes + " min"));
        items.add(UItem.asButton(102, LocaleController.getString(R.string.EveQuestionsPerTest), String.valueOf(exam.questionCount)));
        items.add(UItem.asButton(103, LocaleController.getString(R.string.EveNegativeMarking), exam.negativeMarkingText));
        items.add(UItem.asShadow(null));

        items.add(UItem.asButton(ID_HISTORY, LocaleController.getString(R.string.EveResultHistory)));
        items.add(UItem.asButton(ID_OVERALL_LEADERBOARD, LocaleController.getString(R.string.EveLeaderboard)));
        items.add(UItem.asButton(ID_ANNOUNCEMENTS, LocaleController.getString(R.string.EveAnnouncements)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.EveExamTests)));
        if (loading) {
            items.add(UItem.asShadow("Loading published tests from EVE Cloud..."));
            return;
        }
        if (!errorMessage.isEmpty()) {
            items.add(UItem.asButton(200, "Retry Loading Tests", errorMessage));
            return;
        }
        if (tests.isEmpty()) {
            items.add(UItem.asShadow("No published tests available yet for this exam."));
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());
        long nowMs = System.currentTimeMillis();
        int idBase = 1000;
        for (int i = 0; i < tests.size(); i++) {
            EveExamModels.GeneratedTestSummary test = tests.get(i);
            if (!test.isLive()) {
                continue;
            }
            String value;
            if (test.availableFromMs > nowMs) {
                value = "Opens " + fmt.format(new Date(test.availableFromMs));
            } else {
                value = test.questionCount > 0 ? test.questionCount + " Qs • " + LocaleController.getString(R.string.EveStartTest) : LocaleController.getString(R.string.EveStartTest);
            }
            items.add(UItem.asButton(idBase + i, test.getDisplayTitle(), value));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 200) {
            loadTests();
            return;
        }
        if (item.id == ID_HISTORY) {
            presentFragment(new EveResultHistoryFragment());
            return;
        }
        if (item.id == ID_OVERALL_LEADERBOARD) {
            presentFragment(new EveLeaderboardFragment("overall", LocaleController.getString(R.string.EveLeaderboard)));
            return;
        }
        if (item.id == ID_ANNOUNCEMENTS) {
            presentFragment(new EveAnnouncementsFragment());
            return;
        }
        if (item.id >= 1000) {
            int idx = item.id - 1000;
            if (idx >= 0 && idx < tests.size()) {
                EveExamModels.GeneratedTestSummary selected = tests.get(idx);
                openTestLaunchDialog(selected);
            }
        }
    }

    private void openTestLaunchDialog(EveExamModels.GeneratedTestSummary selected) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        String attemptKey = EveExamModels.buildAttemptKey(exam != null ? exam.id : selected.examId, selected.id);
        int qCount = selected.questionCount > 0 ? selected.questionCount : (exam != null ? exam.questionCount : 20);
        int mins = exam != null ? exam.timeLimitMinutes : 30;
        String neg = exam != null ? exam.negativeMarkingText : "0";

        StringBuilder msg = new StringBuilder();
        msg.append(LocaleController.getString(R.string.EveQuestionsPerTest)).append(": ").append(qCount).append("\n");
        msg.append(LocaleController.getString(R.string.EveTimeLimit)).append(": ").append(mins).append(" min\n");
        msg.append(LocaleController.getString(R.string.EveNegativeMarking)).append(": ").append(neg).append("\n\n");
        msg.append("• Timer runs monotonically once started.\n");
        msg.append("• Answers are evaluated authoritatively by EVE Cloud.\n");
        msg.append("• You can mark questions for review and switch sections anytime.");

        new AlertDialog.Builder(activity)
                .setTitle(selected.getDisplayTitle())
                .setMessage(msg.toString())
                .setPositiveButton(LocaleController.getString(R.string.EveStartTest), (d, w) ->
                        presentFragment(new EveTestPlayerFragment(exam, selected)))
                .setNeutralButton(LocaleController.getString(R.string.EveLeaderboard), (d, w) ->
                        presentFragment(new EveLeaderboardFragment(attemptKey, selected.getDisplayTitle())))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }
}
