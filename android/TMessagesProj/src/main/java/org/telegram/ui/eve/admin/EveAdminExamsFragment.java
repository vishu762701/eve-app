package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
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
 * Telegram-native Java Exam & Syllabus Management module for EVE Admin Control Center.
 * Covers Create, Edit, Duplicate, Preview, Publish/Pause/Archive, AI Generation, Syllabus, and Safe Delete.
 */
public class EveAdminExamsFragment extends BaseFragment {

    private static final int MENU_ADD = 1;
    private static final int MENU_REFRESH = 2;
    private static final int ID_CREATE = 10;
    private static final int ID_MANAGE_TESTS = 11;
    private static final int BASE_EXAM_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private boolean loading = true;
    private String errorText = "";
    private List<EveAdminModels.AdminExamItem> exams = Collections.emptyList();
    private List<EveAdminModels.AdminGeneratedTestItem> generatedTests = Collections.emptyList();

    @Override
    public boolean onFragmentCreate() {
        return EveAdminConfig.isAdminAvailable() && super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Exam & Syllabus Manager");
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_ADD, R.drawable.msg_add);
        menu.addItem(MENU_REFRESH, R.drawable.msg_retry);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_ADD) {
                    EveAdminDialogs.showCreateOrEditExamDialog(EveAdminExamsFragment.this, repo, null, EveAdminExamsFragment.this::reload);
                } else if (id == MENU_REFRESH) {
                    reload();
                }
            }
        });

        EveAdminModels.DashboardSnapshot cached = repo.getCachedSnapshot();
        if (cached != null) {
            exams = cached.exams;
            generatedTests = cached.generatedTests;
        }

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        reload();
        return root;
    }

    private void reload() {
        loading = true;
        errorText = "";
        updateList();
        repo.loadExamsCatalogAsync(new EveAdminRepository.ResultCallback<EveAdminRepository.ExamsCatalogData>() {
            @Override
            public void onSuccess(EveAdminRepository.ExamsCatalogData data) {
                loading = false;
                if (data != null) {
                    exams = data.exams;
                    generatedTests = data.generatedTests;
                }
                updateList();
            }

            @Override
            public void onError(String message, int httpStatus) {
                loading = false;
                errorText = message != null ? message : "Failed to load exams.";
                updateList();
            }
        });
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Exam Operations"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_CREATE, 0xFF4CAF50, 0xFF2E7D32, R.drawable.msg_add,
                "Create New Exam", "Add subject, timer, questions & publish status", null));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_MANAGE_TESTS, 0xFF3D8BEB, 0xFF2A6FD9, R.drawable.msg_list,
                "Manage Generated Tests & Schedule",
                "Inspect, schedule, publish, pause or delete mock tests",
                generatedTests.size() + " tests"));
        if (!errorText.isEmpty()) {
            items.add(UItem.asShadow("Error: " + errorText));
        } else if (loading && exams.isEmpty()) {
            items.add(UItem.asShadow("Loading exams from D1..."));
        } else {
            items.add(UItem.asShadow("Tap any exam below to Edit, Duplicate, Preview, Publish/Pause/Archive, manage Syllabus, or Delete."));
        }

        items.add(UItem.asHeader("Exam Catalog (" + exams.size() + ")"));
        for (int i = 0; i < exams.size(); i++) {
            EveAdminModels.AdminExamItem exam = exams.get(i);
            String statusLabel = exam.isArchived() ? "Archived" : (exam.isPaused() ? "Paused" : "Live");
            String syllabusTag = !exam.syllabusFileName.isEmpty() ? " • PDF: " + exam.syllabusFileName : "";
            String sub = String.format(Locale.US, "%s • %d min • %d Qs%s",
                    exam.category, exam.timeLimitMinutes, exam.questionCount, syllabusTag);
            items.add(SettingsActivity.SettingCell.Factory.of(BASE_EXAM_ID + i, 0, 0, 0, exam.examName, sub, statusLabel));
        }
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_CREATE) {
            EveAdminDialogs.showCreateOrEditExamDialog(this, repo, null, this::reload);
        } else if (item.id == ID_MANAGE_TESTS) {
            EveAdminDialogs.showGeneratedTestsManager(this, repo, new ArrayList<>(generatedTests), this::reload);
        } else if (item.id >= BASE_EXAM_ID) {
            int idx = item.id - BASE_EXAM_ID;
            if (idx >= 0 && idx < exams.size()) {
                showExamActions(exams.get(idx));
            }
        }
    }

    private void showExamActions(EveAdminModels.AdminExamItem exam) {
        Activity act = getParentActivity();
        if (act == null) return;
        String toggleLabel = exam.isPaused() || exam.isArchived() ? "Publish Live" : "Pause Exam";
        String archiveLabel = exam.isArchived() ? "Restore from Archive" : "Archive Exam";
        String[] actions = new String[]{
                "Edit Exam Settings",
                "Duplicate Exam",
                "Preview Student Tests",
                toggleLabel,
                archiveLabel,
                "Generate AI Test Now",
                "Manage Syllabus PDF",
                "Delete Exam"
        };
        showDialog(new AlertDialog.Builder(act, getResourceProvider())
                .setTitle(exam.examName)
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0:
                            EveAdminDialogs.showCreateOrEditExamDialog(this, repo, exam, this::reload);
                            break;
                        case 1:
                            EveAdminDialogs.showDuplicateExamDialog(this, repo, exam, this::reload);
                            break;
                        case 2:
                            EveExamModels.ExamSummary summary = new EveExamModels.ExamSummary(
                                    exam.id, exam.examName, exam.timeLimitMinutes, exam.category,
                                    exam.questionCount, exam.testNumber, exam.negativeMarkingText,
                                    exam.parentExamId, exam.publishMode, 0L, 0, exam.testNumber, 0);
                            presentFragment(new EveExamTestsFragment(summary));
                            break;
                        case 3:
                            updateExamPublishMode(exam, (exam.isPaused() || exam.isArchived()) ? "live" : "paused");
                            break;
                        case 4:
                            updateExamPublishMode(exam, exam.isArchived() ? "paused" : "archived");
                            break;
                        case 5:
                            List<EveAdminModels.AdminExamItem> one = new ArrayList<>();
                            one.add(exam);
                            EveAdminDialogs.showGenerateTestDialog(this, repo, one, this::reload);
                            break;
                        case 6:
                            showSyllabusActions(exam);
                            break;
                        case 7:
                            confirmSafeDeleteExam(exam);
                            break;
                    }
                })
                .setNegativeButton(org.telegram.messenger.LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void updateExamPublishMode(EveAdminModels.AdminExamItem exam, String mode) {
        EveAdminRepository.ResultCallback<String> cb = new EveAdminRepository.ResultCallback<String>() {
            @Override
            public void onSuccess(String msg) {
                repo.invalidateCache();
                BulletinFactory.of(EveAdminExamsFragment.this).createSimpleBulletin(R.raw.contact_check, msg).show();
                reload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                BulletinFactory.of(EveAdminExamsFragment.this).createErrorBulletin(message).show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.updateExam(token, exam.id, exam.examName, exam.category, exam.timeLimitMinutes,
                    exam.questionCount, exam.negativeMarkingText, mode);
            repo.postSuccess(cb, "Exam set to " + mode);
        });
    }

    private void showSyllabusActions(EveAdminModels.AdminExamItem exam) {
        Activity act = getParentActivity();
        if (act == null) return;
        String current = exam.syllabusFileName.isEmpty() ? "No PDF attached" : exam.syllabusFileName;
        showDialog(new AlertDialog.Builder(act, getResourceProvider())
                .setTitle("Syllabus: " + exam.examName)
                .setMessage("Current: " + current + (exam.syllabusUrl.isEmpty() ? "" : "\n" + exam.syllabusUrl))
                .setPositiveButton("Reassign to Another Exam", (d, w) -> promptReassignSyllabus(exam))
                .setNeutralButton("Remove Syllabus", (d, w) -> repo.runWithAdminToken(new EveAdminRepository.ResultCallback<Void>() {
                    @Override public void onSuccess(Void data) {}
                    @Override public void onError(String message, int httpStatus) {
                        BulletinFactory.of(EveAdminExamsFragment.this).createErrorBulletin(message).show();
                    }
                }, token -> {
                    repo.removeSyllabus(token, exam.id);
                    repo.postSuccess(new EveAdminRepository.ResultCallback<Void>() {
                        @Override public void onSuccess(Void data) {
                            BulletinFactory.of(EveAdminExamsFragment.this).createSimpleBulletin(R.raw.contact_check, "Syllabus removed.").show();
                            reload();
                        }
                        @Override public void onError(String message, int httpStatus) {}
                    }, null);
                }))
                .setNegativeButton(org.telegram.messenger.LocaleController.getString(R.string.Close), null)
                .create());
    }

    private void promptReassignSyllabus(EveAdminModels.AdminExamItem sourceExam) {
        if (exams.size() < 2) {
            BulletinFactory.of(this).createErrorBulletin("Need at least two exams to reassign syllabus.").show();
            return;
        }
        List<EveAdminModels.AdminExamItem> targets = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (EveAdminModels.AdminExamItem e : exams) {
            if (!e.id.equals(sourceExam.id)) {
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
                    repo.runWithAdminToken(new EveAdminRepository.ResultCallback<Void>() {
                        @Override public void onSuccess(Void data) {}
                        @Override public void onError(String message, int httpStatus) {
                            BulletinFactory.of(EveAdminExamsFragment.this).createErrorBulletin(message).show();
                        }
                    }, token -> {
                        repo.reassignSyllabus(token, sourceExam.id, target.id);
                        repo.postSuccess(new EveAdminRepository.ResultCallback<Void>() {
                            @Override public void onSuccess(Void data) {
                                BulletinFactory.of(EveAdminExamsFragment.this).createSimpleBulletin(R.raw.contact_check, "Syllabus reassigned to " + target.examName).show();
                                reload();
                            }
                            @Override public void onError(String message, int httpStatus) {}
                        }, null);
                    });
                })
                .setNegativeButton(org.telegram.messenger.LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void confirmSafeDeleteExam(EveAdminModels.AdminExamItem exam) {
        repo.runWithAdminToken(new EveAdminRepository.ResultCallback<JSONObject>() {
            @Override public void onSuccess(JSONObject data) {}
            @Override public void onError(String message, int httpStatus) {
                BulletinFactory.of(EveAdminExamsFragment.this).createErrorBulletin(message).show();
            }
        }, token -> {
            JSONObject info = repo.fetchExamDeletionInfo(token, exam.id);
            repo.postSuccess(new EveAdminRepository.ResultCallback<JSONObject>() {
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
                            .setPositiveButton("Delete", (d, w) -> executeDeleteExam(exam))
                            .setNegativeButton(org.telegram.messenger.LocaleController.getString(R.string.Cancel), null)
                            .makeRed(AlertDialog.BUTTON_POSITIVE)
                            .create());
                }
                @Override public void onError(String message, int httpStatus) {}
            }, info);
        });
    }

    private void executeDeleteExam(EveAdminModels.AdminExamItem exam) {
        repo.runWithAdminToken(new EveAdminRepository.ResultCallback<Void>() {
            @Override public void onSuccess(Void data) {}
            @Override public void onError(String message, int httpStatus) {
                BulletinFactory.of(EveAdminExamsFragment.this).createErrorBulletin(message).show();
            }
        }, token -> {
            repo.deleteExam(token, exam.id);
            repo.postSuccess(new EveAdminRepository.ResultCallback<Void>() {
                @Override public void onSuccess(Void data) {
                    BulletinFactory.of(EveAdminExamsFragment.this).createSimpleBulletin(R.raw.ic_delete, "Deleted " + exam.examName).show();
                    reload();
                }
                @Override public void onError(String message, int httpStatus) {}
            }, null);
        });
    }
}
