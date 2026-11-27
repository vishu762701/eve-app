package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen Telegram-native AI Exam Prompt Studio.
 * Lets Admins inspect/switch exams, author Master AI Prompts, choose Language & Difficulty defaults,
 * attach or manage Syllabus PDFs, and trigger on-demand AI Mock Test generation.
 */
public class EveAdminExamPromptFragment extends BaseFragment {

    private static final int REQ_PICK_SYLLABUS_PDF = 6401;
    private static final int MAX_SYLLABUS_BYTES = 18 * 1024 * 1024;

    private static final int ID_SELECT_EXAM = 101;
    private static final int ID_SYLLABUS_UPLOAD = 201;
    private static final int ID_SYLLABUS_REMOVE = 202;
    private static final int ID_SAVE_SETTINGS = 301;
    private static final int ID_GENERATE_NOW = 302;

    private static final String[] LANG_VALUES = new String[] { "english", "hindi", "bilingual" };
    private static final String[] LANG_LABELS = new String[] { "English", "Hindi", "Bilingual" };
    private static final String[] DIFF_VALUES = new String[] { "mixed", "easy", "medium", "hard" };
    private static final String[] DIFF_LABELS = new String[] { "Mixed", "Easy", "Medium", "Hard" };

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private final List<EveAdminModels.AdminExamItem> availableExams;
    @Nullable
    private final Runnable onChanged;

    private EveAdminModels.AdminExamItem exam;
    private UniversalRecyclerView listView;
    private StudioFormView studioForm;
    private boolean busy = false;
    private boolean destroyed = false;

    public EveAdminExamPromptFragment(
            EveAdminModels.AdminExamItem exam,
            @Nullable List<EveAdminModels.AdminExamItem> allExams,
            @Nullable Runnable onChanged) {
        this.exam = exam;
        this.availableExams = allExams != null ? new ArrayList<>(allExams) : new ArrayList<>();
        if (this.availableExams.isEmpty() && exam != null) {
            this.availableExams.add(exam);
        }
        this.onChanged = onChanged;
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

        studioForm = new StudioFormView(context, getResourceProvider());
        studioForm.bindExam(exam);

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "AI Exam Prompt Studio",
                exam.examName,
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
                    if (studioForm != null) studioForm.updateColors();
                }
        );
        listView = chrome.listView;
        fragmentView = chrome.rootView;
        return chrome.rootView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }

    private void updateList() {
        if (simpleActionBar != null && exam != null) {
            simpleActionBar.setSubtitle(exam.examName + " • " + exam.questionCount + " Qs");
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        if (availableExams.size() > 1) {
            items.add(UItem.asHeader("Target Exam"));
            items.add(EveAdminUiChrome.AvatarRowCell.Factory.of(
                    ID_SELECT_EXAM,
                    exam,
                    new EveAdminUiChrome.AvatarRowCell.Payload(
                            exam.id,
                            exam.examName,
                            exam.category + " • " + exam.timeLimitMinutes + " min • Tap to switch exam",
                            exam.avatarUrl,
                            exam.avatarEmoji,
                            exam.avatarColor,
                            "Switch",
                            EveAdminUiChrome.AvatarRowCell.BADGE_BLUE)));
            items.add(UItem.asShadow(null));
        }

        items.add(UItem.asHeader("Prompt & Generation Profile"));
        items.add(UItem.asCustom(studioForm));
        items.add(UItem.asShadow("Master Prompt applies both to scheduled cron generation and instant manual generation below."));

        items.add(UItem.asHeader("Syllabus PDF Context"));
        String syllabusSub = !TextUtils.isEmpty(exam.syllabusFileName)
                ? "Attached: " + exam.syllabusFileName
                : "No PDF attached — attach official syllabus for grounded AI questions";
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_SYLLABUS_UPLOAD,
                IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                R.drawable.msg_folders,
                TextUtils.isEmpty(exam.syllabusFileName) ? "Upload Syllabus PDF" : "Replace Syllabus PDF",
                syllabusSub,
                null));
        if (!TextUtils.isEmpty(exam.syllabusFileName)) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_SYLLABUS_REMOVE,
                    IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                    R.drawable.msg_delete,
                    "Remove Attached Syllabus",
                    exam.syllabusFileName,
                    null));
        }
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Studio Actions"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_SAVE_SETTINGS,
                IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                R.drawable.msg_select,
                busy ? "Working..." : "Save Prompt Settings",
                "Persists prompt, language (" + studioForm.getSelectedLanguageLabel() + ") & difficulty (" + studioForm.getSelectedDifficultyLabel() + ")",
                null));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_GENERATE_NOW,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                R.drawable.msg_send,
                busy ? "Generating AI Mock Test..." : "Generate AI Mock Test Now",
                "Saves prompt settings and calls Gemini generator on EVE Cloud",
                null));
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (busy) return;
        if (item.id == ID_SELECT_EXAM) {
            showExamSwitcher();
        } else if (item.id == ID_SYLLABUS_UPLOAD) {
            launchSyllabusPdfPicker();
        } else if (item.id == ID_SYLLABUS_REMOVE) {
            confirmRemoveSyllabus();
        } else if (item.id == ID_SAVE_SETTINGS) {
            savePromptSettingsOnly();
        } else if (item.id == ID_GENERATE_NOW) {
            saveAndGenerateNow();
        }
    }

    private void showExamSwitcher() {
        Activity activity = getParentActivity();
        if (activity == null || availableExams.size() < 2) return;
        String[] labels = new String[availableExams.size()];
        for (int i = 0; i < availableExams.size(); i++) {
            EveAdminModels.AdminExamItem item = availableExams.get(i);
            labels[i] = item.examName + " (" + item.category + ")";
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Select Exam for AI Studio");
        builder.setItems(labels, (d, which) -> {
            if (which >= 0 && which < availableExams.size()) {
                exam = availableExams.get(which);
                studioForm.bindExam(exam);
                updateList();
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void launchSyllabusPdfPicker() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        startActivityForResult(Intent.createChooser(intent, "Select Syllabus PDF"), REQ_PICK_SYLLABUS_PDF);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK_SYLLABUS_PDF && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            handlePickedPdf(data.getData());
            return;
        }
        super.onActivityResultFragment(requestCode, resultCode, data);
    }

    private void handlePickedPdf(Uri uri) {
        Activity activity = getParentActivity();
        if (activity == null || uri == null) return;
        busy = true;
        updateList();

        final EveAdminRepository.ResultCallback<EveAdminModels.AdminExamItem> cb =
                new EveAdminRepository.ResultCallback<EveAdminModels.AdminExamItem>() {
                    @Override
                    public void onSuccess(EveAdminModels.AdminExamItem updated) {
                        if (destroyed) return;
                        busy = false;
                        if (updated != null) {
                            replaceCurrentExam(updated);
                        }
                        repo.invalidateCache();
                        BulletinFactory.of(EveAdminExamPromptFragment.this)
                                .createSimpleBulletin(R.raw.contact_check, "Syllabus attached: " + exam.syllabusFileName).show();
                        if (onChanged != null) onChanged.run();
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        busy = false;
                        updateList();
                        BulletinFactory.of(EveAdminExamPromptFragment.this)
                                .createErrorBulletin(message != null ? message : "Syllabus upload failed.").show();
                    }
                };
        repo.runWithAdminToken(cb, token -> {
            final byte[] bytes;
            try {
                bytes = readUriBytes(activity, uri, MAX_SYLLABUS_BYTES);
            } catch (Exception ex) {
                repo.postError(cb, ex.getMessage() != null ? ex.getMessage() : "Could not read selected PDF.", 0);
                return;
            }
            if (bytes == null || bytes.length == 0) {
                repo.postError(cb, "Could not read selected PDF or file exceeds 18MB.", 0);
                return;
            }
            String fileName = resolveFileName(activity, uri);
            JSONObject data = repo.uploadExamSyllabusPdf(token, exam.id, fileName, bytes);
            String url = data != null ? data.optString("syllabusUrl", exam.syllabusUrl) : exam.syllabusUrl;
            String savedName = data != null ? data.optString("syllabusFileName", fileName) : fileName;
            EveAdminModels.AdminExamItem updated = new EveAdminModels.AdminExamItem(
                    exam.id, exam.examName, exam.category, exam.timeLimitMinutes, exam.questionCount,
                    exam.testNumber, exam.negativeMarkingText, exam.publishMode, exam.generationPrompt,
                    exam.parentExamId, savedName, url, exam.imageUrl, exam.avatarUrl, exam.avatarEmoji,
                    exam.avatarColor, exam.aiPrompt, exam.aiLanguage, exam.aiDifficulty);
            repo.postSuccess(cb, updated);
        });
    }

    private void confirmRemoveSyllabus() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Remove Syllabus PDF");
        builder.setMessage("Detach \"" + exam.syllabusFileName + "\" from " + exam.examName + "?");
        builder.setPositiveButton("Remove", (d, w) -> {
            busy = true;
            updateList();
            EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
                @Override
                public void onSuccess(Void data) {
                    if (destroyed) return;
                    busy = false;
                    replaceCurrentExam(new EveAdminModels.AdminExamItem(
                            exam.id, exam.examName, exam.category, exam.timeLimitMinutes, exam.questionCount,
                            exam.testNumber, exam.negativeMarkingText, exam.publishMode, exam.generationPrompt,
                            exam.parentExamId, "", "", exam.imageUrl, exam.avatarUrl, exam.avatarEmoji,
                            exam.avatarColor, exam.aiPrompt, exam.aiLanguage, exam.aiDifficulty));
                    repo.invalidateCache();
                    BulletinFactory.of(EveAdminExamPromptFragment.this)
                            .createSimpleBulletin(R.raw.info, "Syllabus removed").show();
                    if (onChanged != null) onChanged.run();
                    updateList();
                }

                @Override
                public void onError(String message, int httpStatus) {
                    if (destroyed) return;
                    busy = false;
                    updateList();
                    BulletinFactory.of(EveAdminExamPromptFragment.this)
                            .createErrorBulletin(message != null ? message : "Remove failed.").show();
                }
            };
            repo.runWithAdminToken(cb, token -> {
                repo.removeSyllabus(token, exam.id);
                repo.postSuccess(cb, null);
            });
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void savePromptSettingsOnly() {
        String prompt = studioForm.getPromptText();
        String lang = studioForm.getSelectedLanguage();
        String diff = studioForm.getSelectedDifficulty();
        busy = true;
        updateList();

        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                if (destroyed) return;
                busy = false;
                replaceCurrentExam(exam.withAiSettings(prompt, lang, diff));
                repo.invalidateCache();
                BulletinFactory.of(EveAdminExamPromptFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, "AI prompt settings saved").show();
                if (onChanged != null) onChanged.run();
                updateList();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                busy = false;
                updateList();
                BulletinFactory.of(EveAdminExamPromptFragment.this)
                        .createErrorBulletin(message != null ? message : "Save failed.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.saveExamAiSettings(token, exam.id, prompt, lang, diff);
            repo.postSuccess(cb, null);
        });
    }

    private void saveAndGenerateNow() {
        String prompt = studioForm.getPromptText();
        String lang = studioForm.getSelectedLanguage();
        String diff = studioForm.getSelectedDifficulty();
        String label = studioForm.getTestLabel();
        int count = studioForm.getQuestionCount(exam.questionCount);

        busy = true;
        updateList();

        EveAdminRepository.ResultCallback<String> cb = new EveAdminRepository.ResultCallback<String>() {
            @Override
            public void onSuccess(String summary) {
                if (destroyed) return;
                busy = false;
                replaceCurrentExam(exam.withAiSettings(prompt, lang, diff));
                repo.invalidateCache();
                BulletinFactory.of(EveAdminExamPromptFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, summary).show();
                if (onChanged != null) onChanged.run();
                updateList();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                busy = false;
                updateList();
                BulletinFactory.of(EveAdminExamPromptFragment.this)
                        .createErrorBulletin(message != null ? message : "Generation failed.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.saveExamAiSettings(token, exam.id, prompt, lang, diff);
            JSONObject res = repo.generateTestNow(token, exam.id, count, label, prompt, lang, diff);
            JSONObject data = res != null ? res.optJSONObject("data") : null;
            String title = data != null ? data.optString("title", label) : label;
            String status = data != null ? data.optString("status", "draft") : "draft";
            repo.postSuccess(cb, "Generated \"" + title + "\" (" + status + ")");
        });
    }

    private void replaceCurrentExam(EveAdminModels.AdminExamItem updated) {
        this.exam = updated;
        for (int i = 0; i < availableExams.size(); i++) {
            if (availableExams.get(i).id.equals(updated.id)) {
                availableExams.set(i, updated);
                break;
            }
        }
    }

    static byte[] readUriBytes(Context context, Uri uri, int maxBytes) throws Exception {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int read;
            while ((read = is.read(buf)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalArgumentException("PDF exceeds 18MB limit.");
                }
                baos.write(buf, 0, read);
            }
            return baos.toByteArray();
        }
    }

    static String resolveFileName(Context context, Uri uri) {
        String name = "syllabus.pdf";
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String candidate = cursor.getString(idx);
                    if (candidate != null && !candidate.trim().isEmpty()) {
                        name = candidate.trim();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return name;
    }

    private static final class StudioFormView extends LinearLayout {
        private final Theme.ResourcesProvider rp;
        private final EditTextBoldCursor promptInput;
        private final EditTextBoldCursor testLabelInput;
        private final EditTextBoldCursor countInput;
        private final TextView[] langChips = new TextView[LANG_VALUES.length];
        private final TextView[] diffChips = new TextView[DIFF_VALUES.length];
        private final ArrayList<TextView> sectionHeaders = new ArrayList<>();

        private String selectedLang = "english";
        private String selectedDiff = "mixed";

        StudioFormView(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.rp = rp;
            setOrientation(VERTICAL);
            setPadding(dp(16), dp(12), dp(16), dp(14));

            TextView promptLbl = addLabel(context, "Master AI Prompt");
            promptInput = new EditTextBoldCursor(context);
            promptInput.setHint("Specify topic weights, exam tier difficulty, previous-year trends, bilingual rules, or exact formatting requirements...");
            promptInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
            promptInput.setPadding(dp(12), dp(10), dp(12), dp(10));
            promptInput.setCursorSize(dp(20));
            promptInput.setCursorWidth(1.5f);
            promptInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            promptInput.setSingleLine(false);
            promptInput.setMinLines(5);
            promptInput.setGravity(Gravity.TOP | Gravity.START);
            addView(promptInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            addLabel(context, "Question Language Mode");
            LinearLayout langRow = new LinearLayout(context);
            langRow.setOrientation(HORIZONTAL);
            for (int i = 0; i < LANG_VALUES.length; i++) {
                final int idx = i;
                TextView chip = new TextView(context);
                chip.setText(LANG_LABELS[i]);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
                chip.setTypeface(AndroidUtilities.bold());
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(8), dp(9), dp(8), dp(9));
                chip.setOnClickListener(v -> {
                    selectedLang = LANG_VALUES[idx];
                    updateChipStyles();
                });
                langChips[i] = chip;
                langRow.addView(chip, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, i == 0 ? 0 : 4, 0, i == LANG_VALUES.length - 1 ? 0 : 4, 0));
            }
            addView(langRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            addLabel(context, "Difficulty Calibration");
            LinearLayout diffRow = new LinearLayout(context);
            diffRow.setOrientation(HORIZONTAL);
            for (int i = 0; i < DIFF_VALUES.length; i++) {
                final int idx = i;
                TextView chip = new TextView(context);
                chip.setText(DIFF_LABELS[i]);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
                chip.setTypeface(AndroidUtilities.bold());
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(6), dp(9), dp(6), dp(9));
                chip.setOnClickListener(v -> {
                    selectedDiff = DIFF_VALUES[idx];
                    updateChipStyles();
                });
                diffChips[i] = chip;
                diffRow.addView(chip, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, i == 0 ? 0 : 3, 0, i == DIFF_VALUES.length - 1 ? 0 : 3, 0));
            }
            addView(diffRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            addLabel(context, "On-Demand Test Generation");
            LinearLayout genRow = new LinearLayout(context);
            genRow.setOrientation(HORIZONTAL);

            testLabelInput = new EditTextBoldCursor(context);
            testLabelInput.setHint("Test label (e.g. Mock Test)");
            testLabelInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
            testLabelInput.setPadding(dp(12), dp(10), dp(12), dp(10));
            testLabelInput.setCursorSize(dp(20));
            testLabelInput.setCursorWidth(1.5f);
            testLabelInput.setSingleLine(true);

            countInput = new EditTextBoldCursor(context);
            countInput.setHint("Qs (1-200)");
            countInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
            countInput.setPadding(dp(12), dp(10), dp(12), dp(10));
            countInput.setCursorSize(dp(20));
            countInput.setCursorWidth(1.5f);
            countInput.setSingleLine(true);
            countInput.setInputType(InputType.TYPE_CLASS_NUMBER);

            genRow.addView(testLabelInput, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 2f, 0, 0, 8, 0));
            genRow.addView(countInput, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 0, 0));
            addView(genRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            updateColors();
        }

        private TextView addLabel(Context context, String text) {
            TextView tv = new TextView(context);
            tv.setText(text);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            tv.setTypeface(AndroidUtilities.bold());
            addView(tv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, sectionHeaders.isEmpty() ? 0 : 12, 0, 6));
            sectionHeaders.add(tv);
            return tv;
        }

        void bindExam(EveAdminModels.AdminExamItem exam) {
            if (exam == null) return;
            String bestPrompt = !TextUtils.isEmpty(exam.aiPrompt) ? exam.aiPrompt : exam.generationPrompt;
            promptInput.setText(bestPrompt != null ? bestPrompt : "");
            selectedLang = normalize(exam.aiLanguage, LANG_VALUES, "english");
            selectedDiff = normalize(exam.aiDifficulty, DIFF_VALUES, "mixed");
            testLabelInput.setText(!TextUtils.isEmpty(exam.testNumber) ? exam.testNumber : "Mock Test");
            countInput.setText(String.valueOf(Math.max(5, Math.min(200, exam.questionCount > 0 ? exam.questionCount : 25))));
            updateChipStyles();
        }

        void updateColors() {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, rp));
            int blue = Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, rp);
            int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp);
            int hint = Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp);
            int cursor = Theme.getColor(Theme.key_windowBackgroundWhiteValueText, rp);
            int fieldBg = Theme.getColor(Theme.key_windowBackgroundGray, rp);

            for (TextView tv : sectionHeaders) {
                tv.setTextColor(blue);
            }
            for (EditTextBoldCursor et : new EditTextBoldCursor[] { promptInput, testLabelInput, countInput }) {
                et.setTextColor(text);
                et.setHintTextColor(hint);
                et.setCursorColor(cursor);
                et.setBackground(Theme.createRoundRectDrawable(dp(10), fieldBg));
            }
            updateChipStyles();
        }

        private void updateChipStyles() {
            int blue = Theme.getColor(Theme.key_featuredStickers_addButton, rp);
            int gray = Theme.getColor(Theme.key_windowBackgroundGray, rp);
            int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp);
            for (int i = 0; i < LANG_VALUES.length; i++) {
                boolean active = LANG_VALUES[i].equalsIgnoreCase(selectedLang);
                langChips[i].setTextColor(active ? blue : text);
                langChips[i].setBackground(Theme.createRoundRectDrawable(
                        dp(8), active ? Theme.multAlpha(blue, 0.16f) : gray));
            }
            for (int i = 0; i < DIFF_VALUES.length; i++) {
                boolean active = DIFF_VALUES[i].equalsIgnoreCase(selectedDiff);
                diffChips[i].setTextColor(active ? blue : text);
                diffChips[i].setBackground(Theme.createRoundRectDrawable(
                        dp(8), active ? Theme.multAlpha(blue, 0.16f) : gray));
            }
        }

        String getPromptText() {
            return promptInput.getText() != null ? promptInput.getText().toString().trim() : "";
        }

        String getSelectedLanguage() {
            return selectedLang;
        }

        String getSelectedLanguageLabel() {
            for (int i = 0; i < LANG_VALUES.length; i++) {
                if (LANG_VALUES[i].equals(selectedLang)) return LANG_LABELS[i];
            }
            return "English";
        }

        String getSelectedDifficulty() {
            return selectedDiff;
        }

        String getSelectedDifficultyLabel() {
            for (int i = 0; i < DIFF_VALUES.length; i++) {
                if (DIFF_VALUES[i].equals(selectedDiff)) return DIFF_LABELS[i];
            }
            return "Mixed";
        }

        String getTestLabel() {
            String v = testLabelInput.getText() != null ? testLabelInput.getText().toString().trim() : "";
            return v.isEmpty() ? "Mock Test" : v;
        }

        int getQuestionCount(int fallback) {
            try {
                String raw = countInput.getText() != null ? countInput.getText().toString().trim() : "";
                int val = Integer.parseInt(raw);
                return Math.max(1, Math.min(200, val));
            } catch (Throwable ignored) {
                return Math.max(1, Math.min(200, fallback));
            }
        }

        private static String normalize(String raw, String[] valid, String fallback) {
            String clean = raw != null ? raw.trim().toLowerCase(Locale.US) : "";
            for (String v : valid) {
                if (v.equals(clean)) return v;
            }
            return fallback;
        }
    }
}
