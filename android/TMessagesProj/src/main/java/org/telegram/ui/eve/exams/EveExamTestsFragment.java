package org.telegram.ui.eve.exams;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Native Telegram BaseFragment showing real exam details and published mock tests
 * from the EVE Worker backend when an exam row is opened from the main Exams screen.
 */
public class EveExamTestsFragment extends BaseFragment {

    private static final int ID_REFRESH = 1;

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
        items.add(UItem.asHeader("Exam Configuration"));
        items.add(UItem.asButton(100, "Category", exam.category));
        items.add(UItem.asButton(101, "Time Limit", exam.timeLimitMinutes + " min"));
        items.add(UItem.asButton(102, "Questions per Test", String.valueOf(exam.questionCount)));
        items.add(UItem.asButton(103, "Negative Marking", exam.negativeMarkingText));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Available Tests"));
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
        int idBase = 1000;
        for (int i = 0; i < tests.size(); i++) {
            EveExamModels.GeneratedTestSummary test = tests.get(i);
            if (!test.isLive()) {
                continue;
            }
            String value = test.questionCount > 0 ? test.questionCount + " Qs" : "Live";
            items.add(UItem.asButton(idBase + i, test.getDisplayTitle(), value));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 200) {
            loadTests();
            return;
        }
        if (item.id >= 1000) {
            int idx = item.id - 1000;
            if (idx >= 0 && idx < tests.size()) {
                EveExamModels.GeneratedTestSummary selected = tests.get(idx);
                BulletinFactory.of(this).createSimpleBulletin(
                        R.raw.info,
                        selected.getDisplayTitle() + " (" + selected.questionCount + " Qs) selected."
                ).show();
            }
        }
    }
}
