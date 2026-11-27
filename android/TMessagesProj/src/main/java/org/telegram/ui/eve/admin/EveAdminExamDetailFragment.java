package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.exams.EveExamModels;
import org.telegram.ui.eve.exams.EveExamTestsFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Card/Hub management screen for a single exam: Editable Photo/Emoji Avatar,
 * Mock Tests, Question Bank, Syllabus, Exam Controls, and Safe Deletion.
 */
public class EveAdminExamDetailFragment extends BaseFragment {

    private static final int ID_AVATAR_ACTION = 99;

    private static final int ID_MOCK_TESTS = 101;
    private static final int ID_QUESTION_BANK = 102;
    private static final int ID_SYLLABUS = 103;

    private static final int ID_EDIT_SETTINGS = 201;
    private static final int ID_GENERATE_TEST = 202;
    private static final int ID_ASSEMBLE_FROM_BANK = 207;
    private static final int ID_PREVIEW_STUDENT = 203;
    private static final int ID_TOGGLE_PUBLISH = 204;
    private static final int ID_TOGGLE_ARCHIVE = 205;
    private static final int ID_DUPLICATE = 206;

    private static final int ID_DELETE_EXAM = 301;
    private static final int ID_ADD_SUB_EXAM = 350;
    private static final int BASE_SUB_EXAM_ID = 1200;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private final Runnable onExamMutated;
    private EveAdminModels.AdminExamItem exam;
    private List<EveAdminModels.AdminExamItem> allExams = Collections.emptyList();
    private List<EveAdminModels.AdminExamItem> childSubExams = Collections.emptyList();
    private List<EveAdminModels.AdminGeneratedTestItem> allTests = Collections.emptyList();
    private UniversalRecyclerView listView;
    private ExamAvatarHeaderView headerView;
    private EveTelegramAvatarPicker avatarPicker;
    private boolean destroyed = false;
    private boolean uploadingAvatar = false;
    private final EveAdminUiChrome.AutoSyncController autoSync = new EveAdminUiChrome.AutoSyncController(this::reloadCatalog);

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

        headerView = new ExamAvatarHeaderView(context, getResourceProvider(), this::showAvatarPickerDialog);
        headerView.bind(exam);
        avatarPicker = new EveTelegramAvatarPicker(this, true, new EveTelegramAvatarPicker.Delegate() {
            @Override
            public void onAvatarCaptured(@NonNull byte[] imageBytes, @NonNull String mimeType, @Nullable Bitmap previewBitmap) {
                uploadAvatarBytes(imageBytes, mimeType);
            }

            @Override
            public void onAvatarRemoved() {
                resetExamAvatar();
            }
        });

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
                () -> {
                    if (headerView != null) {
                        headerView.updateColors();
                    }
                }
        );
        listView = chrome.listView;

        fragmentView = chrome.rootView;
        reloadCatalog();
        return chrome.rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (avatarPicker != null) {
            avatarPicker.onResume();
        }
        autoSync.onResume();
    }

    @Override
    public void onPause() {
        if (avatarPicker != null) {
            avatarPicker.onPause();
        }
        autoSync.onPause();
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        autoSync.destroy();
        if (avatarPicker != null) {
            avatarPicker.destroy();
            avatarPicker = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (avatarPicker != null) {
            avatarPicker.onActivityResult(requestCode, resultCode, data);
        }
        super.onActivityResultFragment(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (avatarPicker != null) {
            avatarPicker.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
        super.onRequestPermissionsResultFragment(requestCode, permissions, grantResults);
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
                autoSync.markSynced();
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
        if (headerView != null && exam != null) {
            headerView.bind(exam);
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private int countTestsForExam() {
        return countTestsForExamId(exam.id);
    }

    private int countTestsForExamId(String targetExamId) {
        int count = 0;
        for (EveAdminModels.AdminGeneratedTestItem t : allTests) {
            if (targetExamId.equals(t.examId)) {
                count++;
            }
        }
        return count;
    }

    private List<EveAdminModels.AdminExamItem> computeChildSubExams() {
        if (exam == null || !exam.parentExamId.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<EveAdminModels.AdminExamItem> children = new ArrayList<>();
        for (EveAdminModels.AdminExamItem item : allExams) {
            if (item != null && exam.id.equals(item.parentExamId.trim())) {
                children.add(item);
            }
        }
        return children;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        int testCount = countTestsForExam();

        // Avatar Profile Header Card
        items.add(UItem.asCustom(headerView));
        items.add(UItem.asShadow(null));

        // Card 1: Exam Content
        items.add(UItem.asHeader("Exam Content"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_AVATAR_ACTION,
                IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                R.drawable.msg_photos,
                "Set Exam Photo or Emoji",
                uploadingAvatar ? "Uploading avatar..." : "Upload photo/GIF/WebP or pick emoji & color",
                null));
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
                "AI Prompt & Question Studio",
                "Edit master prompt, language, difficulty & generate mock test",
                null));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_ASSEMBLE_FROM_BANK,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                R.drawable.msg_list,
                "Assemble Test from Question Bank",
                "Instant ₹0 non-repeating test from verified bank questions",
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

        // Optional Sub-Exams Hierarchy (only for top-level exams)
        if (exam.parentExamId.trim().isEmpty()) {
            childSubExams = computeChildSubExams();
            items.add(UItem.asHeader("Sub-Exams" + (childSubExams.isEmpty() ? "" : " (" + childSubExams.size() + ")")));
            for (int i = 0; i < childSubExams.size(); i++) {
                EveAdminModels.AdminExamItem sub = childSubExams.get(i);
                int subTests = countTestsForExamId(sub.id);
                String statusBadge = sub.isArchived() ? "Archived" : (sub.isPaused() ? "Paused" : subTests + " tests");
                String meta = String.format(Locale.US, "%d min • %d Qs • -%s",
                        sub.timeLimitMinutes, sub.questionCount, sub.negativeMarkingText);
                items.add(EveAdminUiChrome.AvatarRowCell.Factory.of(
                        BASE_SUB_EXAM_ID + i,
                        sub,
                        new EveAdminUiChrome.AvatarRowCell.Payload(
                                sub.id,
                                sub.examName,
                                meta,
                                sub.avatarUrl,
                                sub.avatarEmoji,
                                sub.avatarColor,
                                statusBadge,
                                sub.isPaused() ? EveAdminUiChrome.AvatarRowCell.BADGE_NEUTRAL : EveAdminUiChrome.AvatarRowCell.BADGE_BLUE)));
            }
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_ADD_SUB_EXAM,
                    IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                    R.drawable.msg_add,
                    "+ Add Sub-Exam",
                    "Create a sub-exam under " + exam.examName + " with custom logo",
                    null));
            items.add(UItem.asShadow(null));
        } else {
            childSubExams = Collections.emptyList();
        }

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
            case ID_AVATAR_ACTION:
                showAvatarPickerDialog();
                break;
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
                presentFragment(new EveAdminExamPromptFragment(exam, allExams, this::notifyParentAndReload));
                break;
            case ID_ASSEMBLE_FROM_BANK:
                assembleTestFromBank();
                break;
            case ID_PREVIEW_STUDENT:
                EveExamModels.ExamSummary summary = new EveExamModels.ExamSummary(
                        exam.id, exam.examName, exam.timeLimitMinutes, exam.category,
                        exam.questionCount, exam.testNumber, exam.negativeMarkingText,
                        exam.parentExamId, exam.publishMode, 0L, 0, exam.testNumber, 0,
                        false, exam.avatarUrl, exam.avatarEmoji, exam.avatarColor);
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
            case ID_ADD_SUB_EXAM:
                EveAdminDialogs.showCreateSubExamDialog(this, repo, exam, this::notifyParentAndReload);
                break;
            case ID_DELETE_EXAM:
                confirmSafeDeleteExam();
                break;
            default:
                if (item.id >= BASE_SUB_EXAM_ID) {
                    int idx = item.id - BASE_SUB_EXAM_ID;
                    if (idx >= 0 && idx < childSubExams.size()) {
                        presentFragment(new EveAdminExamDetailFragment(
                                childSubExams.get(idx), allTests, this::notifyParentAndReload));
                    }
                }
                break;
        }
    }

    private void assembleTestFromBank() {
        if (destroyed || exam == null) return;
        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Assembling test from Question Bank...").show();
        EveAdminRepository.ResultCallback<JSONObject> cb = new EveAdminRepository.ResultCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject root) {
                if (destroyed) return;
                JSONObject data = root != null ? root.optJSONObject("data") : null;
                int count = data != null ? data.optInt("questionCount", exam.questionCount) : exam.questionCount;
                String tNum = data != null ? data.optString("testNumber", exam.testNumber) : exam.testNumber;
                BulletinFactory.of(EveAdminExamDetailFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, "Assembled " + tNum + " (" + count + " Qs)").show();
                notifyParentAndReload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                BulletinFactory.of(EveAdminExamDetailFragment.this)
                        .createErrorBulletin(message != null ? message : "Could not assemble test from Question Bank.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb,
                repo.assembleTestFromBank(token, exam.id, exam.questionCount, exam.testNumber)));
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

    private void showAvatarPickerDialog() {
        if (avatarPicker == null || exam == null) return;
        avatarPicker.open(exam.hasCustomAvatar());
    }

    private void uploadAvatarBytes(byte[] bytes, String mimeType) {
        if (destroyed || exam == null) return;
        uploadingAvatar = true;
        updateList();
        EveAdminRepository.ResultCallback<JSONObject> cb = new EveAdminRepository.ResultCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject data) {
                if (destroyed) return;
                uploadingAvatar = false;
                String nextUrl = data != null ? data.optString("avatarUrl", "") : "";
                exam = exam.withAvatar(nextUrl, "", -1);
                updateList();
                BulletinFactory.of(EveAdminExamDetailFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, "Exam photo updated.").show();
                notifyParentAndReload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                uploadingAvatar = false;
                updateList();
                BulletinFactory.of(EveAdminExamDetailFragment.this)
                        .createErrorBulletin(message != null ? message : "Failed to upload avatar.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            JSONObject res = repo.uploadExamAvatarImage(token, exam.id, bytes, mimeType);
            repo.postSuccess(cb, res);
        });
    }

    private void resetExamAvatar() {
        if (destroyed || exam == null) return;
        EveAdminRepository.ResultCallback<JSONObject> cb = new EveAdminRepository.ResultCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject data) {
                if (destroyed) return;
                exam = exam.withAvatar("", "", -1);
                updateList();
                BulletinFactory.of(EveAdminExamDetailFragment.this)
                        .createSimpleBulletin(R.raw.ic_delete, "Exam avatar reset.").show();
                notifyParentAndReload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                BulletinFactory.of(EveAdminExamDetailFragment.this)
                        .createErrorBulletin(message != null ? message : "Failed to reset avatar.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            JSONObject res = repo.deleteExamAvatar(token, exam.id);
            repo.postSuccess(cb, res);
        });
    }

    private static final class ExamAvatarHeaderView extends LinearLayout implements Theme.Colorable {

        private final Theme.ResourcesProvider resourcesProvider;
        private final AvatarDrawable avatarDrawable;
        private final BackupImageView avatarView;
        private final TextView titleView;
        private final TextView actionHintView;

        ExamAvatarHeaderView(Context context, Theme.ResourcesProvider resourcesProvider, Runnable onEditClick) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(dp(20), dp(18), dp(20), dp(16));

            avatarDrawable = new AvatarDrawable();
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(36));
            avatarView.setOnClickListener(v -> {
                if (onEditClick != null) onEditClick.run();
            });
            addView(avatarView, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 10));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setGravity(Gravity.CENTER);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

            actionHintView = new TextView(context);
            actionHintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            actionHintView.setTypeface(AndroidUtilities.bold());
            actionHintView.setGravity(Gravity.CENTER);
            actionHintView.setPadding(dp(12), dp(5), dp(12), dp(5));
            actionHintView.setText("Set Exam Photo or Emoji");
            actionHintView.setOnClickListener(v -> {
                if (onEditClick != null) onEditClick.run();
            });
            addView(actionHintView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

            updateColors();
        }

        void bind(EveAdminModels.AdminExamItem exam) {
            if (exam == null) return;
            EveAdminUiChrome.bindExamAvatar(
                    avatarView, avatarDrawable, exam.id, exam.examName,
                    exam.avatarUrl, exam.avatarEmoji, exam.avatarColor, 72, resourcesProvider);
            titleView.setText(exam.examName);
            updateColors();
        }

        @Override
        public void updateColors() {
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            int blue = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider);
            actionHintView.setTextColor(blue);
            actionHintView.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.multAlpha(blue, 0.12f)));
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_windowBackgroundWhiteBlackText);
            colors.add(Theme.key_windowBackgroundWhiteBlueText);
        }
    }
}
