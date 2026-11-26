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
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.exams.EveExamModels;
import org.telegram.ui.eve.exams.EveExamTestsFragment;
import org.telegram.utils.glass.source.GlassSourceRenderNode;

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
    private static final int ID_RETRY = 12;
    private static final int BASE_EXAM_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private boolean loading = true;
    private boolean destroyed = false;
    private String errorText = "";
    private List<EveAdminModels.AdminExamItem> exams = Collections.emptyList();
    private List<EveAdminModels.AdminGeneratedTestItem> generatedTests = Collections.emptyList();

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
    public GlassSourceRenderNode getGlassSource() {
        return getBaseSimpleGlass().getGlassSource();
    }

    @Override
    public GlassSourceRenderNode getFrostedGlassSource() {
        return getBaseSimpleGlass().getFrostedGlassSource();
    }

    @Override
    public View createView(Context context) {
        destroyed = false;
        setHasOwnBackground(true);

        EveAdminModels.DashboardSnapshot cached = repo.getCachedSnapshot();
        if (cached != null) {
            exams = cached.exams;
            generatedTests = cached.generatedTests;
        }

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Exams & Syllabus",
                exams.isEmpty() ? null : exams.size() + " exams",
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_ADD) {
                            EveAdminDialogs.showCreateOrEditExamDialog(
                                    EveAdminExamsFragment.this, repo, null, EveAdminExamsFragment.this::reload);
                        } else if (id == MENU_REFRESH) {
                            reload();
                        }
                    }
                },
                null
        );
        listView = chrome.listView;

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            menu.addItem(MENU_ADD, R.drawable.msg_add, getResourceProvider());
            menu.addItem(MENU_REFRESH, R.drawable.msg_retry, getResourceProvider());
        }

        fragmentView = chrome.rootView;
        reload();
        return chrome.rootView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }

    private void reload() {
        if (destroyed) return;
        loading = true;
        errorText = "";
        updateList();
        repo.loadExamsCatalogAsync(new EveAdminRepository.ResultCallback<EveAdminRepository.ExamsCatalogData>() {
            @Override
            public void onSuccess(EveAdminRepository.ExamsCatalogData data) {
                if (destroyed) return;
                loading = false;
                if (data != null) {
                    exams = data.exams;
                    generatedTests = data.generatedTests;
                }
                if (simpleActionBar != null) {
                    simpleActionBar.setSubtitle(exams.size() + " exams");
                }
                updateList();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
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
        EveAdminUiChrome.addTopSpace(items);

        items.add(UItem.asHeader("Actions"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_CREATE,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                R.drawable.msg_add,
                "Create New Exam",
                "Add subject, timer, questions & publish status",
                null));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_MANAGE_TESTS,
                IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                R.drawable.msg_list,
                "Manage Mock Tests",
                "Inspect, schedule, publish, pause or delete tests",
                generatedTests.size() + " tests"));
        if (!errorText.isEmpty()) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_RETRY,
                    IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                    R.drawable.msg_retry,
                    "Retry Loading Exams",
                    errorText,
                    "Retry"));
        }
        items.add(UItem.asShadow("Tap any exam below to edit, duplicate, preview, publish, manage syllabus, or delete."));

        items.add(UItem.asHeader("Exams (" + exams.size() + ")"));
        if (loading && exams.isEmpty()) {
            items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            items.add(UItem.asShadow("Loading exams..."));
            return;
        }
        if (exams.isEmpty()) {
            items.add(UItem.asShadow("No exams found."));
            return;
        }
        for (int i = 0; i < exams.size(); i++) {
            EveAdminModels.AdminExamItem exam = exams.get(i);
            String statusLabel;
            IconBackgroundColors iconBg;
            int iconRes;
            if (exam.isArchived()) {
                statusLabel = "Archived";
                iconBg = IconBackgroundColors.GRAY;
                iconRes = R.drawable.msg_archive;
            } else if (exam.isPaused()) {
                statusLabel = "Paused";
                iconBg = IconBackgroundColors.ORANGE;
                iconRes = R.drawable.msg_recent;
            } else {
                statusLabel = "Live";
                iconBg = IconBackgroundColors.GREEN;
                iconRes = R.drawable.msg_folders;
            }
            String syllabusTag = !exam.syllabusFileName.isEmpty() ? " • PDF: " + exam.syllabusFileName : "";
            String sub = String.format(Locale.US, "%s • %d min • %d Qs%s",
                    exam.category, exam.timeLimitMinutes, exam.questionCount, syllabusTag);
            items.add(SettingsActivity.SettingCell.Factory.of(
                    BASE_EXAM_ID + i,
                    iconBg.top, iconBg.bottom,
                    iconRes,
                    exam.examName,
                    sub,
                    statusLabel));
        }
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_CREATE) {
            EveAdminDialogs.showCreateOrEditExamDialog(this, repo, null, this::reload);
        } else if (item.id == ID_MANAGE_TESTS) {
            presentFragment(new EveAdminGeneratedTestsFragment());
        } else if (item.id == ID_RETRY) {
            reload();
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
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
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
                .setNegativeButton(LocaleController.getString(R.string.Close), null)
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
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
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
                            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
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
