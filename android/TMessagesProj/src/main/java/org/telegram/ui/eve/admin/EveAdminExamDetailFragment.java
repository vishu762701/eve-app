package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.exams.EveExamModels;
import org.telegram.ui.eve.exams.EveExamTestsFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Card/Hub management screen for a single exam: Mock Tests, Question Bank, Syllabus,
 * Exam Controls, and Safe Deletion.
 */
public class EveAdminExamDetailFragment extends BaseFragment {

    private static final int MENU_REFRESH = 1;

    private static final int ID_MOCK_TESTS = 101;
    private static final int ID_QUESTION_BANK = 102;
    private static final int ID_SYLLABUS = 103;

    private static final int ID_EDIT_SETTINGS = 201;
    private static final int ID_GENERATE_TEST = 202;
    private static final int ID_PREVIEW_STUDENT = 203;
    private static final int ID_TOGGLE_PUBLISH = 204;
    private static final int ID_TOGGLE_ARCHIVE = 205;
    private static final int ID_DUPLICATE = 206;

    private static final int ID_DELETE_EXAM = 301;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private final Runnable onExamMutated;
    private EveAdminModels.AdminExamItem exam;
    private List<EveAdminModels.AdminExamItem> allExams = Collections.emptyList();
    private List<EveAdminModels.AdminGeneratedTestItem> allTests = Collections.emptyList();
    private UniversalRecyclerView listView;
    private boolean destroyed = false;

    public EveAdminExamDetailFragment(
            EveAdminModels.AdminExamItem exam,
            @Nullable List<EveAdminModels.AdminGeneratedTestItem> initialTests,
            @Nullable Runnable onExamMutated) {
        this.exam = exam;
        this.allTests = initialTests != null ? initialTests : Collections.emptyList();
        this.onExamMutated = onExamMutated;
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
    public View createView(Context context) {
        destroyed = false;
        setHasOwnBackground(true);

        EveAdminModels.DashboardSnapshot cached = repo.getCachedSnapshot();
        if (cached != null) {
            allExams = cached.exams;
            if (allTests.isEmpty()) {
                allTests = cached.generatedTests;
            }
        }

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                exam.examName,
                buildSubtitle(),
                true,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_REFRESH) {
                            reloadCatalog();
                        }
                    }
                },
                null
        );
        listView = chrome.listView;

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            menu.addItem(MENU_REFRESH, R.drawable.msg_retry, getResourceProvider());
        }

        fragmentView = chrome.rootView;
        reloadCatalog();
        return chrome.rootView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }

    private String buildSubtitle() {
        String status = exam.isArchived() ? "Archived" : (exam.isPaused() ? "Paused" : "Live");
        return String.format(Locale.US, "%s • %d min • %d Qs • %s",
                exam.category, exam.timeLimitMinutes, exam.questionCount, status);
    }

    private void reloadCatalog() {
        if (destroyed || exam == null) return;
        repo.loadExamsCatalogAsync(new EveAdminRepository.ResultCallback<EveAdminRepository.ExamsCatalogData>() {
            @Override
            public void onSuccess(EveAdminRepository.ExamsCatalogData data) {
                if (destroyed || data == null) return;
                allExams = data.exams;
                allTests = data.generatedTests;
                for (EveAdminModels.AdminExamItem item : data.exams) {
                    if (exam.id.equals(item.id)) {
                        exam = item;
                        break;
                    }
                }
                if (simpleActionBar != null) {
                    simpleActionBar.setTitle(exam.examName);
                    simpleActionBar.setSubtitle(buildSubtitle());
                }
                updateList();
            }

            @Override
            public void onError(String message, int httpStatus) {
            }
        });
    }

    private void notifyParentAndReload() {
        repo.invalidateCache();
        reloadCatalog();
        if (onExamMutated != null) {
            onExamMutated.run();
        }
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private int countTestsForExam() {
        int count = 0;
        for (EveAdminModels.AdminGeneratedTestItem t : allTests) {
            if (exam.id.equals(t.examId)) {
                count++;
            }
        }
        return count;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        int testCount = countTestsForExam();

        // Card 1: Exam Content
        items.add(UItem.asHeader("Exam Content"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_MOCK_TESTS,
                IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                R.drawable.msg_list,
                "Mock Tests",
                "Manage & schedule tests for this exam",
                String.valueOf(testCount)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_QUESTION_BANK,
                IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                R.drawable.msg_edit,
                "Question Bank",
                "Add, edit, AI-format & bulk import",
                null));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_SYLLABUS,
                IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                R.drawable.msg_folders,
                "Syllabus PDF",
                !exam.syllabusFileName.isEmpty() ? exam.syllabusFileName : "No syllabus attached",
                null));
        items.add(UItem.asShadow(null));

        // Card 2: Exam Controls
        items.add(UItem.asHeader("Exam Controls"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_EDIT_SETTINGS,
                IconBackgroundColors.BLUE_DEEP.top, IconBackgroundColors.BLUE_DEEP.bottom,
                R.drawable.msg_settings,
                "Edit Exam Settings",
                "Name, category, duration, marking & mode",
                null));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_GENERATE_TEST,
                IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                R.drawable.msg_add,
                "Generate AI Test Now",
                "Create a new mock test set for this exam",
                null));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_PREVIEW_STUDENT,
                IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                R.drawable.msg_info,
                "Preview Student View",
                "See how students view this exam",
                null));
        boolean canPublish = exam.isPaused() || exam.isArchived();
        IconBackgroundColors pubBg = canPublish ? IconBackgroundColors.GREEN : IconBackgroundColors.ORANGE;
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_TOGGLE_PUBLISH,
                pubBg.top, pubBg.bottom,
                canPublish ? R.drawable.msg_select : R.drawable.msg_recent,
                canPublish ? "Publish Live" : "Pause Exam",
                canPublish ? "Make exam visible to students" : "Hide exam from live catalog",
                null));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_TOGGLE_ARCHIVE,
                IconBackgroundColors.GRAY.top, IconBackgroundColors.GRAY.bottom,
                R.drawable.msg_archive,
                exam.isArchived() ? "Restore from Archive" : "Archive Exam",
                exam.isArchived() ? "Move back to paused state" : "Move exam to archive",
                null));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_DUPLICATE,
                IconBackgroundColors.BLUE_ALT.top, IconBackgroundColors.BLUE_ALT.bottom,
                R.drawable.msg_copy,
                "Duplicate Exam",
                "Create a draft copy of this exam",
                null));
        items.add(UItem.asShadow(null));

        // Card 3: Danger Zone
        items.add(UItem.asHeader("Danger Zone"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_DELETE_EXAM,
                IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                R.drawable.msg_delete,
                "Delete Exam",
                "Permanently delete exam and linked content",
                null));
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_MOCK_TESTS:
                presentFragment(new EveAdminGeneratedTestsFragment(exam));
                break;
            case ID_QUESTION_BANK:
                presentFragment(new EveAdminQuestionsFragment(exam));
                break;
            case ID_SYLLABUS:
                showSyllabusActions();
                break;
            case ID_EDIT_SETTINGS:
                EveAdminDialogs.showCreateOrEditExamDialog(this, repo, exam, this::notifyParentAndReload);
                break;
            case ID_GENERATE_TEST:
                EveAdminDialogs.showGeneratePromptDialog(this, repo, exam, this::notifyParentAndReload);
                break;
            case ID_PREVIEW_STUDENT:
                EveExamModels.ExamSummary summary = new EveExamModels.ExamSummary(
                        exam.id, exam.examName, exam.timeLimitMinutes, exam.category,
                        exam.questionCount, exam.testNumber, exam.negativeMarkingText,
                        exam.parentExamId, exam.publishMode, 0L, 0, exam.testNumber, 0);
                presentFragment(new EveExamTestsFragment(summary));
                break;
            case ID_TOGGLE_PUBLISH:
                updatePublishMode((exam.isPaused() || exam.isArchived()) ? "live" : "paused");
                break;
            case ID_TOGGLE_ARCHIVE:
                updatePublishMode(exam.isArchived() ? "paused" : "archived");
                break;
            case ID_DUPLICATE:
                EveAdminDialogs.showDuplicateExamDialog(this, repo, exam, this::notifyParentAndReload);
                break;
            case ID_DELETE_EXAM:
                confirmSafeDeleteExam();
                break;
        }
    }

    private void updatePublishMode(String mode) {
        EveAdminRepository.ResultCallback<String> cb = new EveAdminRepository.ResultCallback<String>() {
            @Override
            public void onSuccess(String msg) {
                BulletinFactory.of(EveAdminExamDetailFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, msg).show();
                notifyParentAndReload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                BulletinFactory.of(EveAdminExamDetailFragment.this)
                        .createErrorBulletin(message).show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.updateExam(token, exam.id, exam.examName, exam.category, exam.timeLimitMinutes,
                    exam.questionCount, exam.negativeMarkingText, mode);
            repo.postSuccess(cb, "Exam set to " + mode);
        });
    }

    private void showSyllabusActions() {
        Activity act = getParentActivity();
        if (act == null) return;
        String current = exam.syllabusFileName.isEmpty() ? "No PDF attached" : exam.syllabusFileName;
        showDialog(new AlertDialog.Builder(act, getResourceProvider())
                .setTitle("Syllabus: " + exam.examName)
                .setMessage("Current: " + current + (exam.syllabusUrl.isEmpty() ? "" : "\n" + exam.syllabusUrl))
                .setPositiveButton("Reassign to Another Exam", (d, w) -> promptReassignSyllabus())
                .setNeutralButton("Remove Syllabus", (d, w) -> {
                    EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
                        @Override
                        public void onSuccess(Void data) {
                            BulletinFactory.of(EveAdminExamDetailFragment.this)
                                    .createSimpleBulletin(R.raw.contact_check, "Syllabus removed.").show();
                            notifyParentAndReload();
                        }

                        @Override
                        public void onError(String message, int httpStatus) {
                            BulletinFactory.of(EveAdminExamDetailFragment.this)
                                    .createErrorBulletin(message).show();
                        }
                    };
                    repo.runWithAdminToken(cb, token -> {
                        repo.removeSyllabus(token, exam.id);
                        repo.postSuccess(cb, null);
                    });
                })
                .setNegativeButton(LocaleController.getString(R.string.Close), null)
                .create());
    }

    private void promptReassignSyllabus() {
        if (allExams.size() < 2) {
            BulletinFactory.of(this).createErrorBulletin("Need at least two exams to reassign syllabus.").show();
            return;
        }
        List<EveAdminModels.AdminExamItem> targets = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (EveAdminModels.AdminExamItem e : allExams) {
            if (!e.id.equals(exam.id)) {
                targets.add(e);
                labels.add(e.examName);
            }
        }
        Activity act = getParentActivity();
        if (act == null || targets.isEmpty()) return;
        showDialog(new AlertDialog.Builder(act, getResourceProvider())
                .setTitle("Reassign Syllabus To")
                .setItems(labels.toArray(new String[0]), (d, idx) -> {
                    EveAdminModels.AdminExamItem target = targets.get(idx);
                    EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
                        @Override
                        public void onSuccess(Void data) {
                            BulletinFactory.of(EveAdminExamDetailFragment.this)
                                    .createSimpleBulletin(R.raw.contact_check, "Syllabus reassigned to " + target.examName).show();
                            notifyParentAndReload();
                        }

                        @Override
                        public void onError(String message, int httpStatus) {
                            BulletinFactory.of(EveAdminExamDetailFragment.this)
                                    .createErrorBulletin(message).show();
                        }
                    };
                    repo.runWithAdminToken(cb, token -> {
                        repo.reassignSyllabus(token, exam.id, target.id);
                        repo.postSuccess(cb, null);
                    });
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void confirmSafeDeleteExam() {
        EveAdminRepository.ResultCallback<JSONObject> cb = new EveAdminRepository.ResultCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject data) {
                Activity act = getParentActivity();
                if (act == null) return;
                int attempts = data != null ? data.optInt("attemptCount", 0) : 0;
                int tests = data != null ? data.optInt("testCount", 0) : 0;
                int questions = data != null ? data.optInt("questionCount", 0) : 0;
                showDialog(new AlertDialog.Builder(act, getResourceProvider())
                        .setTitle("Delete " + exam.examName + "?")
                        .setMessage("Linked questions: " + questions + "\nGenerated tests: " + tests + "\nStudent attempts: " + attempts + "\n\nThis action is audited and permanent.")
                        .setPositiveButton("Delete", (d, w) -> executeDeleteExam())
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                        .makeRed(AlertDialog.BUTTON_POSITIVE)
                        .create());
            }

            @Override
            public void onError(String message, int httpStatus) {
                BulletinFactory.of(EveAdminExamDetailFragment.this).createErrorBulletin(message).show();
            }
        };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchExamDeletionInfo(token, exam.id)));
    }

    private void executeDeleteExam() {
        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                repo.invalidateCache();
                BulletinFactory.of(EveAdminExamDetailFragment.this)
                        .createSimpleBulletin(R.raw.ic_delete, "Deleted " + exam.examName).show();
                if (onExamMutated != null) {
                    onExamMutated.run();
                }
                finishFragment();
            }

            @Override
            public void onError(String message, int httpStatus) {
                BulletinFactory.of(EveAdminExamDetailFragment.this).createErrorBulletin(message).show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.deleteExam(token, exam.id);
            repo.postSuccess(cb, null);
        });
    }
}
