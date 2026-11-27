package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.eve.premium.EvePremiumRepository;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dialog workflows for the authoritative EVE Admin Control Center.
 * Every mutation executes against Cloudflare Worker + D1 through EveAdminRepository
 * with fresh Firebase ID token authentication and server-enforced RBAC.
 */
public final class EveAdminDialogs {

    private EveAdminDialogs() {
    }

    public static void showCreateOrEditExamDialog(
            BaseFragment fragment,
            EveAdminRepository repo,
            EveAdminModels.AdminExamItem existing,
            Runnable onSaved) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        Theme.ResourcesProvider rp = fragment.getResourceProvider();

        LinearLayout container = makeFormContainer(activity);
        EditText nameInput = addField(activity, container, "Exam Name (e.g. SSC CGL Tier 1)", existing != null ? existing.examName : "", InputType.TYPE_CLASS_TEXT, rp);
        EditText categoryInput = addField(activity, container, "Category (SSC, UPSC, Banking, Railways)", existing != null ? existing.category : "SSC", InputType.TYPE_CLASS_TEXT, rp);
        EditText minutesInput = addField(activity, container, "Time Limit (minutes 1-600)", String.valueOf(existing != null ? existing.timeLimitMinutes : 60), InputType.TYPE_CLASS_NUMBER, rp);
        EditText countInput = addField(activity, container, "Questions Per Test (1-200)", String.valueOf(existing != null ? existing.questionCount : 25), InputType.TYPE_CLASS_NUMBER, rp);

        // Negative Marking selector (preset chips + optional custom input)
        final String[] negPresets = new String[] { "0", "0.25", "1/3", "0.5", "Custom" };
        final String initialNeg = existing != null && !existing.negativeMarkingText.isEmpty() ? existing.negativeMarkingText : "0.25";
        final String[] selectedNeg = new String[] { initialNeg };
        addFieldLabel(activity, container, "Negative Marking", rp);
        LinearLayout chipsRow = new LinearLayout(activity);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(6));
        EditText customNegInput = new EditText(activity);
        customNegInput.setInputType(InputType.TYPE_CLASS_TEXT);
        customNegInput.setHint("Enter custom negative mark (e.g. 1/4)");
        customNegInput.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        customNegInput.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, rp));
        boolean isPresetInitial = "0".equals(initialNeg) || "0.25".equals(initialNeg) || "1/3".equals(initialNeg) || "0.5".equals(initialNeg);
        customNegInput.setText(isPresetInitial ? "" : initialNeg);
        customNegInput.setVisibility(isPresetInitial ? android.view.View.GONE : android.view.View.VISIBLE);

        final TextView[] chipViews = new TextView[negPresets.length];
        Runnable updateChipStyles = () -> {
            int black = Theme.getColor(Theme.key_dialogTextBlack, rp);
            int gray = Theme.getColor(Theme.key_dialogTextGray3, rp);
            int accent = Theme.getColor(Theme.key_dialogTextBlue2, rp);
            for (int i = 0; i < negPresets.length; i++) {
                String p = negPresets[i];
                boolean active = ("Custom".equals(p) && customNegInput.getVisibility() == android.view.View.VISIBLE)
                        || (!"Custom".equals(p) && customNegInput.getVisibility() != android.view.View.VISIBLE && p.equals(selectedNeg[0]));
                chipViews[i].setTextColor(active ? accent : black);
                chipViews[i].setBackground(Theme.createRoundRectDrawable(
                        AndroidUtilities.dp(8),
                        Theme.multAlpha(active ? accent : gray, active ? 0.16f : 0.10f)));
            }
        };
        for (int i = 0; i < negPresets.length; i++) {
            final String preset = negPresets[i];
            TextView chip = new TextView(activity);
            chip.setText(preset);
            chip.setTextSize(13);
            chip.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(10), AndroidUtilities.dp(6));
            chipViews[i] = chip;
            chip.setOnClickListener(v -> {
                if ("Custom".equals(preset)) {
                    customNegInput.setVisibility(android.view.View.VISIBLE);
                    customNegInput.requestFocus();
                } else {
                    customNegInput.setVisibility(android.view.View.GONE);
                    selectedNeg[0] = preset;
                }
                updateChipStyles.run();
            });
            chipsRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0));
        }
        updateChipStyles.run();
        container.addView(chipsRow);
        container.addView(customNegInput);

        // Publish Mode clickable picker row
        final String[] modeLabels = new String[] { "Inherit", "Live", "Paused", "Draft", "Archived" };
        final String[] modeValues = new String[] { "inherit", "live", "paused", "draft", "archived" };
        final String[] selectedMode = new String[] { normalizePublishMode(existing != null ? existing.publishMode : "live") };
        addFieldLabel(activity, container, "Publish Mode", rp);
        TextView publishPicker = new TextView(activity);
        publishPicker.setTextSize(15);
        publishPicker.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        publishPicker.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        publishPicker.setBackground(Theme.createRoundRectDrawable(
                AndroidUtilities.dp(8),
                Theme.multAlpha(Theme.getColor(Theme.key_dialogTextGray3, rp), 0.10f)));
        publishPicker.setText(formatPublishModeLabel(selectedMode[0]) + "  ▾");
        publishPicker.setOnClickListener(v -> {
            String[] displayLabels = new String[modeLabels.length];
            for (int i = 0; i < modeValues.length; i++) {
                displayLabels[i] = (modeValues[i].equals(selectedMode[0]) ? "✓ " : "    ") + modeLabels[i];
            }
            new AlertDialog.Builder(activity, rp)
                    .setTitle("Select Publish Mode")
                    .setItems(displayLabels, (dialog, which) -> {
                        selectedMode[0] = modeValues[which];
                        publishPicker.setText(modeLabels[which] + "  ▾");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        container.addView(publishPicker, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 8));

        // Category Cutoffs (General, OBC, SC, ST, EWS)
        addFieldLabel(activity, container, "Category Cutoffs (optional)", rp);
        final String[] cutoffCats = new String[] { "General", "OBC", "SC", "ST", "EWS" };
        final EditText[] cutoffInputs = new EditText[cutoffCats.length];
        for (int i = 0; i < cutoffCats.length; i++) {
            Double existingVal = existing != null ? existing.getCutoffForCategory(cutoffCats[i]) : null;
            String valStr = existingVal != null ? String.valueOf(existingVal) : "";
            cutoffInputs[i] = addField(activity, container, cutoffCats[i] + " Cutoff", valStr,
                    InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, rp);
        }

        // Daily Auto-Generation Toggle + Native Time Picker
        TextCheckCell autoGenCheck = new TextCheckCell(activity);
        boolean initialAutoGen = existing != null && existing.autoGenerationEnabled;
        autoGenCheck.setTextAndCheck("Daily Auto-Generation", initialAutoGen, true);
        autoGenCheck.setOnClickListener(v -> autoGenCheck.setChecked(!autoGenCheck.isChecked()));
        container.addView(autoGenCheck);

        final String[] selectedTime = new String[] {
                existing != null && !TextUtils.isEmpty(existing.autoGenTime) ? existing.autoGenTime : "06:00"
        };
        TextView timePickerBtn = new TextView(activity);
        timePickerBtn.setTextSize(15);
        timePickerBtn.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        timePickerBtn.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        timePickerBtn.setBackground(Theme.createRoundRectDrawable(
                AndroidUtilities.dp(8),
                Theme.multAlpha(Theme.getColor(Theme.key_dialogTextGray3, rp), 0.10f)));
        timePickerBtn.setText("Daily Generation Time: " + selectedTime[0] + " IST  ▾");
        timePickerBtn.setOnClickListener(v -> {
            int hour = 6;
            int min = 0;
            try {
                String[] parts = selectedTime[0].split(":");
                if (parts.length >= 2) {
                    hour = Math.max(0, Math.min(23, Integer.parseInt(parts[0].trim())));
                    min = Math.max(0, Math.min(59, Integer.parseInt(parts[1].trim())));
                }
            } catch (Throwable ignored) {
            }
            new android.app.TimePickerDialog(activity, (view, h, m) -> {
                selectedTime[0] = String.format(Locale.US, "%02d:%02d", h, m);
                timePickerBtn.setText("Daily Generation Time: " + selectedTime[0] + " IST  ▾");
            }, hour, min, true).show();
        });
        container.addView(timePickerBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 8));

        EditText promptInput = existing == null
                ? addField(activity, container, "AI Generation Prompt Notes (optional)", "", InputType.TYPE_CLASS_TEXT, rp)
                : null;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, rp)
                .setTitle(existing == null ? "Create Exam" : "Edit Exam: " + existing.examName)
                .setView(wrapScroll(activity, container))
                .setPositiveButton(existing == null ? "Create" : "Save Settings", (d, w) -> {
                    String name = text(nameInput);
                    String category = textOr(categoryInput, "General");
                    int minutes = parseIntBounded(minutesInput, 60, 1, 600);
                    int qCount = parseIntBounded(countInput, 25, 1, 200);
                    String neg = customNegInput.getVisibility() == android.view.View.VISIBLE
                            ? textOr(customNegInput, "0")
                            : selectedNeg[0];
                    String mode = selectedMode[0];
                    String prompt = promptInput != null ? text(promptInput) : "";
                    java.util.Map<String, Double> cutoffsMap = new java.util.LinkedHashMap<>();
                    for (int i = 0; i < cutoffCats.length; i++) {
                        String rawCut = text(cutoffInputs[i]);
                        if (!rawCut.isEmpty()) {
                            try {
                                double v = Double.parseDouble(rawCut);
                                if (!Double.isNaN(v) && !Double.isInfinite(v) && v >= 0.0) {
                                    cutoffsMap.put(cutoffCats[i], v);
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    boolean autoGenOn = autoGenCheck.isChecked();
                    String autoGenTimeVal = selectedTime[0];
                    if (name.isEmpty()) {
                        showError(fragment, "Exam name cannot be empty.");
                        return;
                    }
                    EveAdminRepository.ResultCallback<String> cb = callback(fragment, msg -> {
                        showInfo(fragment, msg);
                        if (onSaved != null) onSaved.run();
                    });
                    repo.runWithAdminToken(cb, token -> {
                        if (existing == null) {
                            String id = repo.createExam(token, name, category, minutes, qCount, "Mock 1", neg, mode, prompt);
                            if (!cutoffsMap.isEmpty() || !autoGenOn || !"00:00".equals(autoGenTimeVal)) {
                                repo.updateExamSettings(token, id, name, category, minutes, qCount, neg, mode, cutoffsMap, autoGenOn, autoGenTimeVal);
                            }
                            repo.postSuccess(cb, "Exam created (" + id + ").");
                        } else {
                            repo.updateExamSettings(token, existing.id, name, category, minutes, qCount, neg, mode, cutoffsMap, autoGenOn, autoGenTimeVal);
                            repo.postSuccess(cb, "Exam settings & cutoffs saved.");
                        }
                    });
                })
                .setNegativeButton("Cancel", null);
        if (existing != null) {
            builder.setNeutralButton("Duplicate / Pause", (d, w) -> new AlertDialog.Builder(activity, rp)
                    .setTitle(existing.examName)
                    .setItems(new String[] {
                            "Duplicate Exam as Copy",
                            "Pause Exam & Unpublish All Tests"
                    }, (d2, action) -> {
                        EveAdminRepository.ResultCallback<String> cb = callback(fragment, msg -> {
                            showInfo(fragment, msg);
                            if (onSaved != null) onSaved.run();
                        });
                        repo.runWithAdminToken(cb, token -> {
                            if (action == 0) {
                                String copyId = repo.createExam(token, existing.examName + " (Copy)", existing.category,
                                        existing.timeLimitMinutes, existing.questionCount, existing.testNumber,
                                        existing.negativeMarkingText, "paused", existing.generationPrompt);
                                repo.postSuccess(cb, "Duplicated as draft exam (" + copyId + ").");
                            } else {
                                repo.updateExam(token, existing.id, existing.examName, existing.category,
                                        existing.timeLimitMinutes, existing.questionCount, existing.negativeMarkingText, "paused");
                                repo.unpublishExamTests(token, existing.id);
                                repo.postSuccess(cb, "Exam paused & tests unpublished.");
                            }
                        });
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
        }
        builder.show();
    }

    public static void showDuplicateExamDialog(
            BaseFragment fragment,
            EveAdminRepository repo,
            EveAdminModels.AdminExamItem source,
            Runnable onSaved) {
        if (source == null) return;
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        LinearLayout container = makeFormContainer(activity);
        EditText nameInput = addField(activity, container, "New Exam Name", source.examName + " (Copy)", InputType.TYPE_CLASS_TEXT);
        EditText categoryInput = addField(activity, container, "Category", source.category, InputType.TYPE_CLASS_TEXT);
        EditText publishInput = addField(activity, container, "Publish Mode (paused | live | inherit)", "paused", InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(activity)
                .setTitle("Duplicate Exam")
                .setView(wrapScroll(activity, container))
                .setPositiveButton("Create Copy", (d, w) -> {
                    String name = text(nameInput);
                    if (name.isEmpty()) {
                        showError(fragment, "Exam name cannot be empty.");
                        return;
                    }
                    EveAdminRepository.ResultCallback<String> cb = callback(fragment, msg -> {
                        showInfo(fragment, msg);
                        if (onSaved != null) onSaved.run();
                    });
                    repo.runWithAdminToken(cb, token -> {
                        String copyId = repo.createExam(token, name, textOr(categoryInput, source.category),
                                source.timeLimitMinutes, source.questionCount, source.testNumber,
                                source.negativeMarkingText, normalizePublishMode(text(publishInput)), source.generationPrompt);
                        repo.postSuccess(cb, "Duplicated exam (" + copyId + ").");
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public static void showGenerateTestDialog(
            BaseFragment fragment,
            EveAdminRepository repo,
            List<EveAdminModels.AdminExamItem> exams,
            Runnable onGenerated) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        if (exams == null || exams.isEmpty()) {
            showError(fragment, "Create an exam first before generating mock tests.");
            return;
        }
        String[] labels = new String[exams.size()];
        for (int i = 0; i < exams.size(); i++) {
            EveAdminModels.AdminExamItem ex = exams.get(i);
            labels[i] = ex.examName + " (" + ex.category + " • " + ex.questionCount + " Qs)";
        }
        new AlertDialog.Builder(activity)
                .setTitle("Select Exam to Generate Mock Test")
                .setItems(labels, (d, which) -> fragment.presentFragment(
                        new EveAdminExamPromptFragment(exams.get(which), exams, onGenerated)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    public static void showGeneratedTestsManager(
            BaseFragment fragment,
            EveAdminRepository repo,
            List<EveAdminModels.AdminGeneratedTestItem> tests,
            Runnable onMutated) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        if (tests == null || tests.isEmpty()) {
            showInfo(fragment, "No mock tests found.");
            return;
        }
        String[] labels = new String[tests.size()];
        for (int i = 0; i < tests.size(); i++) {
            EveAdminModels.AdminGeneratedTestItem t = tests.get(i);
            labels[i] = t.title + " • " + t.examName + " [" + t.status.toUpperCase(Locale.US) + "] (" + t.questionCount + " Qs)";
        }
        new AlertDialog.Builder(activity)
                .setTitle("Mock Tests (" + tests.size() + ")")
                .setItems(labels, (d, which) -> showGeneratedTestActions(fragment, repo, tests.get(which), onMutated))
                .setNegativeButton("Close", null)
                .show();
    }

    private static void showGeneratedTestActions(
            BaseFragment fragment,
            EveAdminRepository repo,
            EveAdminModels.AdminGeneratedTestItem test,
            Runnable onMutated) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        String[] actions = new String[] {
                "Preview & Validate Questions",
                "Publish (Set LIVE)",
                "Schedule Release (Hours From Now)",
                "Move to DRAFT / Paused",
                "Delete Unused Test"
        };
        new AlertDialog.Builder(activity)
                .setTitle(test.title)
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        EveAdminRepository.ResultCallback<List<EveAdminModels.AdminQuestionItem>> pcb = callback(fragment, qs -> {
                            StringBuilder sb = new StringBuilder();
                            sb.append("Status: ").append(test.status).append(" • ").append(qs.size()).append(" questions\n\n");
                            for (int i = 0; i < Math.min(qs.size(), 15); i++) {
                                EveAdminModels.AdminQuestionItem q = qs.get(i);
                                sb.append(i + 1).append(". ").append(q.questionText)
                                        .append("\n   [Ans: ").append(q.correctAnswer).append("]\n");
                            }
                            if (qs.size() > 15) {
                                sb.append("\n...and ").append(qs.size() - 15).append(" more questions.");
                            }
                            showTextDialog(activity, "Test Preview: " + test.title, sb.toString().trim());
                        });
                        repo.runWithAdminToken(pcb, token -> repo.postSuccess(pcb, repo.fetchGeneratedTestQuestions(token, test.id)));
                        return;
                    }
                    if (which == 2) {
                        LinearLayout box = makeFormContainer(activity);
                        EditText hoursInput = addField(activity, box, "Release in how many hours? (0 = immediate)", "0", InputType.TYPE_CLASS_NUMBER);
                        new AlertDialog.Builder(activity)
                                .setTitle("Schedule: " + test.title)
                                .setView(wrapScroll(activity, box))
                                .setPositiveButton("Schedule", (d2, w2) -> {
                                    int hrs = parseIntBounded(hoursInput, 0, 0, 720);
                                    long availableFrom = hrs == 0 ? 0L : System.currentTimeMillis() + hrs * 3_600_000L;
                                    EveAdminRepository.ResultCallback<String> scb = callback(fragment, msg -> {
                                        showInfo(fragment, msg);
                                        if (onMutated != null) onMutated.run();
                                    });
                                    repo.runWithAdminToken(scb, token -> {
                                        repo.scheduleGeneratedTest(token, test.id, availableFrom);
                                        repo.postSuccess(scb, hrs == 0 ? "Test scheduled for immediate availability." : "Test scheduled in " + hrs + "h.");
                                    });
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                        return;
                    }
                    EveAdminRepository.ResultCallback<String> cb = callback(fragment, msg -> {
                        showInfo(fragment, msg);
                        if (onMutated != null) onMutated.run();
                    });
                    repo.runWithAdminToken(cb, token -> {
                        if (which == 1) {
                            repo.updateGeneratedTestStatus(token, test.id, "live");
                            repo.postSuccess(cb, "Test published LIVE.");
                        } else if (which == 3) {
                            repo.updateGeneratedTestStatus(token, test.id, "paused");
                            repo.postSuccess(cb, "Test paused / moved out of live feed.");
                        } else {
                            repo.deleteGeneratedTest(token, test.id);
                            repo.postSuccess(cb, "Unused test deleted.");
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public static void showQuestionBankPicker(
            BaseFragment fragment,
            EveAdminRepository repo,
            List<EveAdminModels.AdminExamItem> exams) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        if (exams == null || exams.isEmpty()) {
            showError(fragment, "No exams available for Question Bank.");
            return;
        }
        String[] labels = new String[exams.size()];
        for (int i = 0; i < exams.size(); i++) {
            labels[i] = exams.get(i).examName + " (" + exams.get(i).category + ")";
        }
        new AlertDialog.Builder(activity)
                .setTitle("Question Bank — Pick Exam")
                .setItems(labels, (d, which) -> loadAndShowExamQuestions(fragment, repo, exams.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void loadAndShowExamQuestions(
            BaseFragment fragment,
            EveAdminRepository repo,
            EveAdminModels.AdminExamItem exam) {
        EveAdminRepository.ResultCallback<List<EveAdminModels.AdminQuestionItem>> cb = callback(fragment, questions -> {
            Activity activity = fragment.getParentActivity();
            if (activity == null) return;
            String[] items = new String[questions.size() + 2];
            items[0] = "+ Add New Question to " + exam.examName;
            items[1] = "+ Bulk Import Questions (JSON)";
            for (int i = 0; i < questions.size(); i++) {
                EveAdminModels.AdminQuestionItem q = questions.get(i);
                String snippet = q.questionText.length() > 64 ? q.questionText.substring(0, 64) + "..." : q.questionText;
                items[i + 2] = (i + 1) + ". [" + q.correctAnswer + "] " + snippet;
            }
            new AlertDialog.Builder(activity)
                    .setTitle(exam.examName + " Questions (" + questions.size() + ")")
                    .setItems(items, (d, which) -> {
                        if (which == 0) {
                            showEditQuestionDialog(fragment, repo, exam, null, () -> loadAndShowExamQuestions(fragment, repo, exam));
                        } else if (which == 1) {
                            showBulkImportQuestionsDialog(fragment, repo, exam, () -> loadAndShowExamQuestions(fragment, repo, exam));
                        } else {
                            showQuestionActions(fragment, repo, exam, questions.get(which - 2), () -> loadAndShowExamQuestions(fragment, repo, exam));
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
        });
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchQuestions(token, exam.id)));
    }

    public static final class BulkImportParseResult {
        public final org.json.JSONArray validQuestions;
        public final List<String> errors;
        public final String format;

        public BulkImportParseResult(org.json.JSONArray validQuestions, List<String> errors, String format) {
            this.validQuestions = validQuestions != null ? validQuestions : new org.json.JSONArray();
            this.errors = errors != null ? errors : Collections.emptyList();
            this.format = format != null ? format : "unknown";
        }
    }

    public static BulkImportParseResult parseBulkQuestionsInput(String rawInput, String examId) {
        String raw = rawInput != null ? rawInput.trim() : "";
        List<String> errors = new ArrayList<>();
        org.json.JSONArray out = new org.json.JSONArray();
        if (raw.isEmpty()) {
            errors.add("Input is empty.");
            return new BulkImportParseResult(out, errors, "empty");
        }
        if (raw.startsWith("[")) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject q = arr.optJSONObject(i);
                    if (q == null) {
                        errors.add("Item #" + (i + 1) + " is not an object.");
                        continue;
                    }
                    String qText = q.optString("questionText", "").trim();
                    String optA = q.optString("optionA", "").trim();
                    String optB = q.optString("optionB", "").trim();
                    String optC = q.optString("optionC", "").trim();
                    String optD = q.optString("optionD", "").trim();
                    String ans = q.optString("correctAnswer", "A").trim().toUpperCase(Locale.US);
                    if (qText.isEmpty() || optA.isEmpty() || optB.isEmpty() || optC.isEmpty() || optD.isEmpty()) {
                        errors.add("Item #" + (i + 1) + " missing questionText or options.");
                        continue;
                    }
                    if (!"A".equals(ans) && !"B".equals(ans) && !"C".equals(ans) && !"D".equals(ans)) {
                        errors.add("Item #" + (i + 1) + " invalid correctAnswer (" + ans + ").");
                        continue;
                    }
                    JSONObject copy = new JSONObject(q.toString());
                    if (!copy.has("examId") || copy.optString("examId").trim().isEmpty()) {
                        copy.put("examId", examId != null ? examId : "");
                    }
                    copy.put("correctAnswer", ans);
                    out.put(copy);
                }
                return new BulkImportParseResult(out, errors, "JSON");
            } catch (Exception e) {
                errors.add("Invalid JSON array: " + e.getMessage());
                return new BulkImportParseResult(out, errors, "JSON");
            }
        }

        // CSV parser (supports header row + quoted fields with commas)
        List<List<String>> rows = parseCsvRows(raw);
        if (rows.isEmpty()) {
            errors.add("No CSV rows found.");
            return new BulkImportParseResult(out, errors, "CSV");
        }
        int startIdx = 0;
        List<String> first = rows.get(0);
        if (!first.isEmpty() && first.get(0).toLowerCase(Locale.US).contains("question")) {
            startIdx = 1;
        }
        for (int r = startIdx; r < rows.size(); r++) {
            List<String> cols = rows.get(r);
            if (cols.size() < 6) {
                errors.add("CSV row " + (r + 1) + " has fewer than 6 columns.");
                continue;
            }
            String qText = cols.get(0).trim();
            String optA = cols.get(1).trim();
            String optB = cols.get(2).trim();
            String optC = cols.get(3).trim();
            String optD = cols.get(4).trim();
            String ans = cols.get(5).trim().toUpperCase(Locale.US);
            String explanation = cols.size() > 6 ? cols.get(6).trim() : "";
            String topic = cols.size() > 7 ? cols.get(7).trim() : "";
            if (qText.isEmpty() || optA.isEmpty() || optB.isEmpty() || optC.isEmpty() || optD.isEmpty()) {
                errors.add("CSV row " + (r + 1) + " missing question or options.");
                continue;
            }
            if (!"A".equals(ans) && !"B".equals(ans) && !"C".equals(ans) && !"D".equals(ans)) {
                errors.add("CSV row " + (r + 1) + " invalid correctAnswer (" + ans + ").");
                continue;
            }
            try {
                JSONObject obj = new JSONObject();
                obj.put("examId", examId != null ? examId : "");
                obj.put("questionText", qText);
                obj.put("optionA", optA);
                obj.put("optionB", optB);
                obj.put("optionC", optC);
                obj.put("optionD", optD);
                obj.put("correctAnswer", ans);
                obj.put("explanation", explanation);
                obj.put("topic", topic);
                out.put(obj);
            } catch (Exception ignored) {
            }
        }
        return new BulkImportParseResult(out, errors, "CSV");
    }

    private static List<List<String>> parseCsvRows(String input) {
        List<List<String>> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                currentRow.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') {
                    i++;
                }
                currentRow.add(field.toString());
                field.setLength(0);
                boolean anyNonEmpty = false;
                for (String c : currentRow) {
                    if (!c.trim().isEmpty()) {
                        anyNonEmpty = true;
                        break;
                    }
                }
                if (anyNonEmpty) {
                    rows.add(currentRow);
                }
                currentRow = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        currentRow.add(field.toString());
        boolean anyNonEmpty = false;
        for (String c : currentRow) {
            if (!c.trim().isEmpty()) {
                anyNonEmpty = true;
                break;
            }
        }
        if (anyNonEmpty) {
            rows.add(currentRow);
        }
        return rows;
    }

    public static void showBulkImportQuestionsDialog(
            BaseFragment fragment,
            EveAdminRepository repo,
            EveAdminModels.AdminExamItem exam,
            Runnable onImported) {
        showBulkImportQuestionsDialog(fragment, repo, exam, "", null, onImported);
    }

    public static void showBulkImportQuestionsDialog(
            BaseFragment fragment,
            EveAdminRepository repo,
            EveAdminModels.AdminExamItem exam,
            String prefillText,
            Runnable onPickFile,
            Runnable onImported) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        Theme.ResourcesProvider rp = fragment.getResourceProvider();
        LinearLayout container = makeFormContainer(activity);
        TextView previewBanner = new TextView(activity);
        previewBanner.setTextSize(13);
        previewBanner.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
        previewBanner.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(6));
        previewBanner.setText("Paste JSON array or CSV (questionText,optionA,optionB,optionC,optionD,correctAnswer,explanation,topic).");
        container.addView(previewBanner);

        EditText input = addField(activity, container,
                "JSON array [...] or CSV rows",
                prefillText != null ? prefillText : "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                rp);

        Runnable refreshPreview = () -> {
            BulkImportParseResult res = parseBulkQuestionsInput(text(input), exam.id);
            previewBanner.setText(String.format(Locale.US,
                    "%s Preview: %d valid question(s), %d warning(s)%s",
                    res.format,
                    res.validQuestions.length(),
                    res.errors.size(),
                    res.errors.isEmpty() ? "" : (" — " + res.errors.get(0))));
        };
        if (prefillText != null && !prefillText.trim().isEmpty()) {
            refreshPreview.run();
        }
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) { refreshPreview.run(); }
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, rp)
                .setTitle("Bulk Import (JSON / CSV): " + exam.examName)
                .setView(wrapScroll(activity, container))
                .setPositiveButton("Validate & Upload", (d, w) -> {
                    BulkImportParseResult parsedRes = parseBulkQuestionsInput(text(input), exam.id);
                    if (parsedRes.validQuestions.length() == 0) {
                        showError(fragment, parsedRes.errors.isEmpty() ? "No valid questions found." : parsedRes.errors.get(0));
                        return;
                    }
                    org.json.JSONArray parsed = parsedRes.validQuestions;
                    EveAdminRepository.ResultCallback<String> cb = callback(fragment, msg -> {
                        showInfo(fragment, msg);
                        if (onImported != null) onImported.run();
                    });
                    repo.runWithAdminToken(cb, token -> {
                        List<EveAdminModels.AdminQuestionItem> existing = repo.fetchQuestions(token, exam.id);
                        java.util.Set<String> seenTexts = new java.util.HashSet<>();
                        for (EveAdminModels.AdminQuestionItem ex : existing) {
                            seenTexts.add(ex.questionText.trim().toLowerCase(Locale.US));
                        }
                        org.json.JSONArray deduped = new org.json.JSONArray();
                        int skipped = 0;
                        for (int i = 0; i < parsed.length(); i++) {
                            JSONObject q = parsed.optJSONObject(i);
                            if (q == null) continue;
                            String qt = q.optString("questionText", "").trim().toLowerCase(Locale.US);
                            if (qt.isEmpty() || seenTexts.contains(qt)) {
                                skipped++;
                                continue;
                            }
                            seenTexts.add(qt);
                            deduped.put(q);
                        }
                        if (deduped.length() == 0) {
                            repo.postSuccess(cb, "All " + skipped + " questions were empty or duplicates.");
                            return;
                        }
                        int count = repo.batchImportQuestions(token, deduped);
                        repo.postSuccess(cb, "Uploaded " + count + " " + parsedRes.format + " questions" + (skipped > 0 ? " (" + skipped + " duplicates skipped)." : "."));
                    });
                })
                .setNegativeButton("Cancel", null);
        if (onPickFile != null) {
            builder.setNeutralButton("Pick .json / .csv", (d, w) -> onPickFile.run());
        }
        builder.show();
    }

    public static void showQuestionActions(
            BaseFragment fragment,
            EveAdminRepository repo,
            EveAdminModels.AdminExamItem exam,
            EveAdminModels.AdminQuestionItem q,
            Runnable onMutated) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        new AlertDialog.Builder(activity)
                .setTitle("Question (" + q.topic + ")")
                .setMessage(q.questionText + "\n\nA) " + q.optionA + "\nB) " + q.optionB + "\nC) " + q.optionC + "\nD) " + q.optionD + "\n\nAnswer: " + q.correctAnswer)
                .setPositiveButton("Edit", (d, w) -> showEditQuestionDialog(fragment, repo, exam, q, onMutated))
                .setNeutralButton("Delete", (d, w) -> {
                    EveAdminRepository.ResultCallback<String> cb = callback(fragment, msg -> {
                        showInfo(fragment, msg);
                        if (onMutated != null) onMutated.run();
                    });
                    repo.runWithAdminToken(cb, token -> {
                        repo.deleteQuestion(token, q.id);
                        repo.postSuccess(cb, "Question deleted.");
                    });
                })
                .setNegativeButton("Close", null)
                .show();
    }

    public static void showEditQuestionDialog(
            BaseFragment fragment,
            EveAdminRepository repo,
            EveAdminModels.AdminExamItem exam,
            EveAdminModels.AdminQuestionItem existing,
            Runnable onSaved) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        LinearLayout container = makeFormContainer(activity);
        EditText qInput = addField(activity, container, "Question Text", existing != null ? existing.questionText : "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        EditText aInput = addField(activity, container, "Option A", existing != null ? existing.optionA : "", InputType.TYPE_CLASS_TEXT);
        EditText bInput = addField(activity, container, "Option B", existing != null ? existing.optionB : "", InputType.TYPE_CLASS_TEXT);
        EditText cInput = addField(activity, container, "Option C", existing != null ? existing.optionC : "", InputType.TYPE_CLASS_TEXT);
        EditText dInput = addField(activity, container, "Option D", existing != null ? existing.optionD : "", InputType.TYPE_CLASS_TEXT);
        EditText ansInput = addField(activity, container, "Correct Answer (A, B, C or D)", existing != null ? existing.correctAnswer : "A", InputType.TYPE_CLASS_TEXT);
        EditText topicInput = addField(activity, container, "Topic", existing != null ? existing.topic : exam.category, InputType.TYPE_CLASS_TEXT);
        EditText expInput = addField(activity, container, "Explanation", existing != null ? existing.explanation : "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        new AlertDialog.Builder(activity)
                .setTitle(existing == null ? "Add Question" : "Edit Question")
                .setView(wrapScroll(activity, container))
                .setPositiveButton("Save", (d, w) -> {
                    String qText = text(qInput);
                    String a = text(aInput);
                    String b = text(bInput);
                    String c = text(cInput);
                    String dOpt = text(dInput);
                    String ans = textOr(ansInput, "A").toUpperCase(Locale.US);
                    String topic = text(topicInput);
                    String exp = text(expInput);
                    if (qText.isEmpty() || a.isEmpty() || b.isEmpty() || c.isEmpty() || dOpt.isEmpty()) {
                        showError(fragment, "Question and all four options are required.");
                        return;
                    }
                    EveAdminRepository.ResultCallback<String> cb = callback(fragment, msg -> {
                        showInfo(fragment, msg);
                        if (onSaved != null) onSaved.run();
                    });
                    repo.runWithAdminToken(cb, token -> {
                        if (existing == null) {
                            repo.createQuestion(token, exam.id, qText, a, b, c, dOpt, ans, exp, topic);
                            repo.postSuccess(cb, "Question added.");
                        } else {
                            repo.updateQuestion(token, existing.id, exam.id, qText, a, b, c, dOpt, ans, exp, topic);
                            repo.postSuccess(cb, "Question updated.");
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("AI Format Explanation", (d, w) -> {
                    String qText = text(qInput);
                    String exp = text(expInput);
                    if (qText.isEmpty()) {
                        showError(fragment, "Enter question text first.");
                        return;
                    }
                    EveAdminRepository.ResultCallback<String> cb = callback(fragment, formatted ->
                            showTextDialog(activity, "AI Formatted Explanation", formatted));
                    repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.reformatExplanation(token, qText, exp)));
                })
                .show();
    }

    public static void showStudentSearchDialog(BaseFragment fragment, EveAdminRepository repo, Runnable onMutated) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        LinearLayout container = makeFormContainer(activity);
        EditText queryInput = addField(activity, container, "Search by name, email, or EVE ID (blank = latest)", "", InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(activity)
                .setTitle("Student Management")
                .setView(wrapScroll(activity, container))
                .setPositiveButton("Search", (d, w) -> loadStudents(fragment, repo, text(queryInput), onMutated))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void loadStudents(BaseFragment fragment, EveAdminRepository repo, String query, Runnable onMutated) {
        EveAdminRepository.ResultCallback<List<EveAdminModels.AdminStudentItem>> cb = callback(fragment, students -> {
            Activity activity = fragment.getParentActivity();
            if (activity == null) return;
            if (students.isEmpty()) {
                showInfo(fragment, "No students matched query.");
                return;
            }
            String[] labels = new String[students.size()];
            for (int i = 0; i < students.size(); i++) {
                EveAdminModels.AdminStudentItem s = students.get(i);
                labels[i] = s.displayName + " (" + s.email + ")" + (s.disabled ? " [DISABLED]" : "");
            }
            new AlertDialog.Builder(activity)
                    .setTitle("Students (" + students.size() + ")")
                    .setItems(labels, (d, which) -> showStudentActionSheet(fragment, repo, students.get(which), onMutated))
                    .setNegativeButton("Close", null)
                    .show();
        });
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.searchStudents(token, query, 1, 30)));
    }

    private static void showStudentActionSheet(
            BaseFragment fragment,
            EveAdminRepository repo,
            EveAdminModels.AdminStudentItem student,
            Runnable onMutated) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        String[] actions = new String[] {
                "View Attempt History",
                student.disabled ? "Enable Student Account" : "Disable / Suspend Student Account",
                "Grant EVE Premium (30 Days)",
                "Revoke EVE Premium"
        };
        new AlertDialog.Builder(activity)
                .setTitle(student.displayName + " (" + student.eveId + ")")
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        EveAdminRepository.ResultCallback<List<EveAdminModels.StudentAttemptItem>> acb = callback(fragment, attempts -> {
                            StringBuilder sb = new StringBuilder();
                            if (attempts.isEmpty()) {
                                sb.append("No recorded test attempts.");
                            } else {
                                for (EveAdminModels.StudentAttemptItem a : attempts) {
                                    sb.append("• ").append(a.examName).append(": ")
                                            .append(String.format(Locale.US, "%.1f", a.score)).append("/").append(a.total)
                                            .append(" (✓").append(a.correct).append(" ✗").append(a.wrong).append(")\n");
                                }
                            }
                            showTextDialog(activity, student.displayName + " Attempts", sb.toString().trim());
                        });
                        repo.runWithAdminToken(acb, token -> repo.postSuccess(acb, repo.fetchStudentAttempts(token, student.id)));
                    } else if (which == 1) {
                        EveAdminRepository.ResultCallback<String> scb = callback(fragment, msg -> {
                            showInfo(fragment, msg);
                            if (onMutated != null) onMutated.run();
                        });
                        repo.runWithAdminToken(scb, token -> {
                            boolean nextDisabled = !student.disabled;
                            repo.setStudentDisabled(token, student.id, nextDisabled);
                            repo.postSuccess(scb, nextDisabled ? "Student account disabled." : "Student account enabled.");
                        });
                    } else if (which == 2) {
                        EveAdminRepository.ResultCallback<String> gcb = callback(fragment, msg -> {
                            showInfo(fragment, msg);
                            if (onMutated != null) onMutated.run();
                        });
                        repo.runWithAdminToken(gcb, token -> {
                            repo.grantPremium(token, student.id, "EVE Premium", 30, false);
                            repo.postSuccess(gcb, "Granted 30 days EVE Premium.");
                        });
                    } else {
                        EveAdminRepository.ResultCallback<String> rcb = callback(fragment, msg -> {
                            showInfo(fragment, msg);
                            if (onMutated != null) onMutated.run();
                        });
                        repo.runWithAdminToken(rcb, token -> {
                            repo.revokePremium(token, student.id);
                            repo.postSuccess(rcb, "Premium access revoked.");
                        });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public static void showPricingAndUpiDialog(BaseFragment fragment, EveAdminRepository repo, Runnable onSaved) {
        EveAdminRepository.ResultCallback<EveAdminModels.PremiumConfigModel> cb = callback(fragment, cfg -> {
            Activity activity = fragment.getParentActivity();
            if (activity == null) return;
            LinearLayout container = makeFormContainer(activity);
            TextCheckCell enabledCheck = new TextCheckCell(activity);
            enabledCheck.setTextAndCheck("Premium Subscriptions Enabled", cfg.isEnabled, true);
            enabledCheck.setOnClickListener(v -> enabledCheck.setChecked(!enabledCheck.isChecked()));
            container.addView(enabledCheck);

            TextCheckCell lifetimeCheck = new TextCheckCell(activity);
            lifetimeCheck.setTextAndCheck("Lifetime Plan", cfg.isLifetime, true);
            lifetimeCheck.setOnClickListener(v -> lifetimeCheck.setChecked(!lifetimeCheck.isChecked()));
            container.addView(lifetimeCheck);

            TextCheckCell upiCheck = new TextCheckCell(activity);
            upiCheck.setTextAndCheck("UPI Payments Enabled", cfg.upiEnabled, true);
            upiCheck.setOnClickListener(v -> upiCheck.setChecked(!upiCheck.isChecked()));
            container.addView(upiCheck);

            TextCheckCell qrCheck = new TextCheckCell(activity);
            qrCheck.setTextAndCheck("QR Code Checkout Enabled", cfg.qrEnabled, false);
            qrCheck.setOnClickListener(v -> qrCheck.setChecked(!qrCheck.isChecked()));
            container.addView(qrCheck);

            EditText planInput = addField(activity, container, "Plan Name", cfg.planName, InputType.TYPE_CLASS_TEXT);
            EditText priceInput = addField(activity, container, "Price (INR 1-1000000)", String.valueOf(cfg.priceInr), InputType.TYPE_CLASS_NUMBER);
            EditText daysInput = addField(activity, container, "Duration Days (1-3650)", String.valueOf(cfg.durationDays), InputType.TYPE_CLASS_NUMBER);
            EditText vpaInput = addField(activity, container, "Merchant UPI VPA", cfg.merchantVpa, InputType.TYPE_CLASS_TEXT);
            EditText merchantInput = addField(activity, container, "Merchant Display Name", cfg.merchantName, InputType.TYPE_CLASS_TEXT);
            EditText expiryInput = addField(activity, container, "Payment Session Expiry (minutes)", String.valueOf(cfg.sessionExpiryMinutes), InputType.TYPE_CLASS_NUMBER);
            EditText descInput = addField(activity, container, "Plan Description", cfg.description, InputType.TYPE_CLASS_TEXT);

            new AlertDialog.Builder(activity)
                    .setTitle("Pricing, Plans & UPI / QR Settings")
                    .setView(wrapScroll(activity, container))
                    .setPositiveButton("Save Config", (d, w) -> {
                        EveAdminModels.PremiumConfigModel updated = new EveAdminModels.PremiumConfigModel(
                                enabledCheck.isChecked(),
                                textOr(planInput, "EVE Premium"),
                                parseIntBounded(priceInput, cfg.priceInr, 1, 1_000_000),
                                "INR",
                                parseIntBounded(daysInput, cfg.durationDays, 1, 3650),
                                lifetimeCheck.isChecked(),
                                textOr(descInput, "Unlock eligible EVE tests"),
                                cfg.benefits,
                                qrCheck.isChecked(),
                                upiCheck.isChecked(),
                                parseIntBounded(expiryInput, cfg.sessionExpiryMinutes, 1, 1440),
                                textOr(vpaInput, cfg.merchantVpa),
                                textOr(merchantInput, cfg.merchantName)
                        );
                        EveAdminRepository.ResultCallback<String> saveCb = callback(fragment, msg -> {
                            showInfo(fragment, msg);
                            if (onSaved != null) onSaved.run();
                        });
                        repo.runWithAdminToken(saveCb, token -> {
                            repo.updatePremiumConfig(token, updated);
                            repo.postSuccess(saveCb, "Pricing & UPI/QR configuration saved.");
                        });
                    })
                    .setNeutralButton("Preview QR", (d, w) -> {
                        String vpa = textOr(vpaInput, cfg.merchantVpa).trim();
                        String merchant = textOr(merchantInput, cfg.merchantName).trim();
                        int amount = parseIntBounded(priceInput, cfg.priceInr, 1, 1_000_000);
                        String plan = textOr(planInput, "EVE Premium").trim();
                        if (vpa.isEmpty()) {
                            showInfo(fragment, "Enter a Merchant UPI VPA first to preview the payment QR code.");
                            return;
                        }
                        String uri = "upi://pay?pa=" + Uri.encode(vpa)
                                + "&pn=" + Uri.encode(merchant.isEmpty() ? "EVE" : merchant)
                                + "&am=" + amount
                                + "&cu=INR"
                                + "&tn=" + Uri.encode(plan);
                        org.telegram.ui.eve.premium.EvePremiumQrSheet.showAdminPreview(
                                fragment,
                                uri,
                                vpa,
                                plan,
                                amount
                        );
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchPremiumConfig(token)));
    }

    public static void showTransactionsDialog(BaseFragment fragment, EveAdminRepository repo) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        String[] statuses = new String[] { "ALL", "PAID", "PENDING", "FAILED", "EXPIRED", "REFUNDED" };
        new AlertDialog.Builder(activity)
                .setTitle("Filter Payment Orders")
                .setItems(statuses, (d, which) -> {
                    String filter = which == 0 ? "" : statuses[which];
                    EveAdminRepository.ResultCallback<List<EveAdminModels.PaymentTransactionItem>> cb = callback(fragment, txs -> {
                        StringBuilder sb = new StringBuilder();
                        if (txs.isEmpty()) {
                            sb.append("No payment orders recorded for this filter.");
                        } else {
                            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
                            for (int i = 0; i < Math.min(txs.size(), 40); i++) {
                                EveAdminModels.PaymentTransactionItem tx = txs.get(i);
                                sb.append("• ").append(tx.planName).append(" — ₹").append(tx.amount)
                                        .append(" [").append(tx.status).append("]\n  ")
                                        .append(tx.orderId).append(" • ").append(tx.userEmail).append("\n  ")
                                        .append(tx.paymentMethod).append(" • ")
                                        .append(tx.createdAt > 0 ? fmt.format(new Date(tx.createdAt)) : "-")
                                        .append("\n");
                            }
                        }
                        showTextDialog(activity, "Payment Transactions (" + txs.size() + ")", sb.toString().trim());
                    });
                    repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchTransactions(token, 50, filter)));
                })
                .setNegativeButton("Close", null)
                .show();
    }

    public static void showAdminsManager(BaseFragment fragment, EveAdminRepository repo, Runnable onMutated) {
        EveAdminRepository.ResultCallback<List<EveAdminModels.AdminMemberItem>> cb = callback(fragment, admins -> {
            Activity activity = fragment.getParentActivity();
            if (activity == null) return;
            String[] labels = new String[admins.size() + 1];
            labels[0] = "+ Add Administrator";
            for (int i = 0; i < admins.size(); i++) {
                EveAdminModels.AdminMemberItem m = admins.get(i);
                labels[i + 1] = m.email + " — " + m.customTitle + " (" + m.rights.size() + " rights)";
            }
            new AlertDialog.Builder(activity)
                    .setTitle("Admin Roles & Permissions")
                    .setItems(labels, (d, which) -> {
                        if (which == 0) {
                            showAddOrEditAdminDialog(fragment, repo, null, onMutated);
                        } else {
                            EveAdminModels.AdminMemberItem member = admins.get(which - 1);
                            if (member.isOwner) {
                                showTextDialog(activity, member.email, "Built-in Owner — all " + EveAdminModels.ALL_RIGHTS.length + " server permissions enforced.");
                            } else {
                                showAddOrEditAdminDialog(fragment, repo, member, onMutated);
                            }
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
        });
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchAdmins(token)));
    }

    private static void showAddOrEditAdminDialog(
            BaseFragment fragment,
            EveAdminRepository repo,
            EveAdminModels.AdminMemberItem existing,
            Runnable onMutated) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        LinearLayout container = makeFormContainer(activity);
        EditText emailInput = addField(activity, container, "Admin Email", existing != null ? existing.email : "", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        if (existing != null) {
            emailInput.setEnabled(false);
        }
        EditText titleInput = addField(activity, container, "Custom Role Title", existing != null ? existing.customTitle : "Moderator", InputType.TYPE_CLASS_TEXT);

        List<TextCheckCell> rightChecks = new ArrayList<>();
        Set<String> currentRights = existing != null ? existing.rights : new HashSet<>();
        for (int i = 0; i < EveAdminModels.ALL_RIGHTS.length; i++) {
            String right = EveAdminModels.ALL_RIGHTS[i];
            TextCheckCell check = new TextCheckCell(activity);
            check.setTextAndCheck(EveAdminUiChrome.formatActionTitle(right), currentRights.contains(right), i < EveAdminModels.ALL_RIGHTS.length - 1);
            check.setOnClickListener(v -> check.setChecked(!check.isChecked()));
            rightChecks.add(check);
            container.addView(check);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(existing == null ? "Add Administrator" : "Edit Rights: " + existing.email)
                .setView(wrapScroll(activity, container))
                .setPositiveButton("Save", (d, w) -> {
                    String email = text(emailInput).toLowerCase(Locale.US);
                    String title = textOr(titleInput, "Admin");
                    if (email.isEmpty() || !email.contains("@")) {
                        showError(fragment, "Enter a valid administrator email.");
                        return;
                    }
                    List<String> selected = new ArrayList<>();
                    for (int i = 0; i < rightChecks.size(); i++) {
                        if (rightChecks.get(i).isChecked()) {
                            selected.add(EveAdminModels.ALL_RIGHTS[i]);
                        }
                    }
                    EveAdminRepository.ResultCallback<String> scb = callback(fragment, msg -> {
                        showInfo(fragment, msg);
                        if (onMutated != null) onMutated.run();
                    });
                    repo.runWithAdminToken(scb, token -> {
                        if (existing == null) {
                            repo.addAdmin(token, email, selected, title);
                            repo.postSuccess(scb, "Admin added with " + selected.size() + " permissions.");
                        } else {
                            repo.updateAdminRights(token, existing.email, selected, title);
                            repo.postSuccess(scb, "Admin rights updated.");
                        }
                    });
                })
                .setNegativeButton("Cancel", null);
        if (existing != null) {
            builder.setNeutralButton("Remove", (d, w) -> {
                EveAdminRepository.ResultCallback<String> rcb = callback(fragment, msg -> {
                    showInfo(fragment, msg);
                    if (onMutated != null) onMutated.run();
                });
                repo.runWithAdminToken(rcb, token -> {
                    repo.removeAdmin(token, existing.email);
                    repo.postSuccess(rcb, "Administrator removed.");
                });
            });
        }
        builder.show();
    }

    public static void showAuditLogDialog(BaseFragment fragment, EveAdminRepository repo) {
        EveAdminRepository.ResultCallback<List<EveAdminModels.AuditLogEntry>> cb = callback(fragment, entries -> {
            Activity activity = fragment.getParentActivity();
            if (activity == null) return;
            StringBuilder sb = new StringBuilder();
            if (entries.isEmpty()) {
                sb.append("No audit records yet.");
            } else {
                SimpleDateFormat fmt = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US);
                for (EveAdminModels.AuditLogEntry e : entries) {
                    String title = formatAuditActionTitle(e.actionType);
                    sb.append("• ").append(title);
                    if (!e.description.isEmpty()) {
                        sb.append("\n  ").append(e.description);
                    }
                    sb.append("\n  ").append(e.adminEmail.isEmpty() ? "Admin" : e.adminEmail)
                            .append(" • ")
                            .append(e.timestamp > 0 ? fmt.format(new Date(e.timestamp)) : "-")
                            .append("\n\n");
                }
            }
            showTextDialog(activity, "Audit Log (" + entries.size() + ")", sb.toString().trim());
        });
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchAuditLogs(token, 50)));
    }

    private static String formatAuditActionTitle(String rawType) {
        if (rawType == null || rawType.trim().isEmpty()) {
            return "Admin Action";
        }
        String[] parts = rawType.trim().toLowerCase(Locale.US).split("_+");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return out.length() > 0 ? out.toString() : "Admin Action";
    }

    private static String formatPublishModeLabel(String mode) {
        if ("live".equalsIgnoreCase(mode)) return "Live";
        if ("paused".equalsIgnoreCase(mode)) return "Paused";
        if ("archived".equalsIgnoreCase(mode)) return "Archived";
        return "Inherit";
    }

    private static LinearLayout makeFormContainer(Context ctx) {
        LinearLayout ll = new LinearLayout(ctx);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(18);
        ll.setPadding(pad, AndroidUtilities.dp(10), pad, AndroidUtilities.dp(10));
        return ll;
    }

    private static ScrollView wrapScroll(Context ctx, LinearLayout inner) {
        ScrollView sv = new ScrollView(ctx);
        sv.addView(inner);
        return sv;
    }

    private static void addFieldLabel(Context ctx, LinearLayout parent, String label, Theme.ResourcesProvider rp) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
        tv.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(2));
        parent.addView(tv);
    }

    private static EditText addField(Context ctx, LinearLayout parent, String label, String initial, int inputType) {
        return addField(ctx, parent, label, initial, inputType, null);
    }

    private static EditText addField(Context ctx, LinearLayout parent, String label, String initial, int inputType, Theme.ResourcesProvider rp) {
        addFieldLabel(ctx, parent, label, rp);

        EditText et = new EditText(ctx);
        et.setInputType(inputType);
        et.setText(initial != null ? initial : "");
        et.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        et.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, rp));
        parent.addView(et);
        return et;
    }

    private static String text(EditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }

    private static String textOr(EditText et, String fallback) {
        String val = text(et);
        return val.isEmpty() ? fallback : val;
    }

    private static int parseIntBounded(EditText et, int fallback, int min, int max) {
        try {
            int val = Integer.parseInt(text(et));
            return Math.max(min, Math.min(max, val));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static void showPremiumUsersDialog(BaseFragment fragment, EveAdminRepository repo, Runnable onMutated) {
        EveAdminRepository.ResultCallback<List<EveAdminModels.PremiumUserItem>> cb = callback(fragment, users -> {
            Activity activity = fragment.getParentActivity();
            if (activity == null) return;
            String[] labels = new String[users.size() + 1];
            labels[0] = "+ Grant / Manage by Student Search";
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            for (int i = 0; i < users.size(); i++) {
                EveAdminModels.PremiumUserItem u = users.get(i);
                String exp = u.expiresAt > 0 ? fmt.format(new Date(u.expiresAt)) : "Lifetime";
                labels[i + 1] = u.displayName + " (" + u.email + ") • " + u.planName + " • " + exp;
            }
            new AlertDialog.Builder(activity)
                    .setTitle("EVE Premium Subscribers (" + users.size() + ")")
                    .setItems(labels, (d, idx) -> {
                        if (idx == 0) {
                            fragment.presentFragment(new EveAdminStudentsFragment());
                        } else {
                            EveAdminModels.PremiumUserItem u = users.get(idx - 1);
                            new AlertDialog.Builder(activity)
                                    .setTitle(u.email)
                                    .setItems(new String[]{"Extend +30 Days", "Revoke Premium"}, (dd, actIdx) -> {
                                        EveAdminRepository.ResultCallback<String> rcb = callback(fragment, msg -> {
                                            showInfo(fragment, msg);
                                            if (onMutated != null) onMutated.run();
                                        });
                                        repo.runWithAdminToken(rcb, token -> {
                                            if (actIdx == 0) {
                                                repo.extendPremium(token, u.userId, 30);
                                                repo.postSuccess(rcb, "Extended +30 days.");
                                            } else {
                                                repo.revokePremium(token, u.userId);
                                                repo.postSuccess(rcb, "Premium revoked.");
                                            }
                                        });
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
        });
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchPremiumUsers(token)));
    }

    private static String normalizePublishMode(String mode) {
        String lower = mode.trim().toLowerCase(Locale.US);
        if ("live".equals(lower) || "paused".equals(lower) || "archived".equals(lower)) {
            return lower;
        }
        return "inherit";
    }

    public static void showGenerationJobsDialog(
            BaseFragment fragment,
            EveAdminRepository repo,
            List<EveAdminModels.GenerationJobItem> generationJobs,
            Runnable onRetried) {
        Activity act = fragment.getParentActivity();
        if (act == null) return;
        if (generationJobs == null || generationJobs.isEmpty()) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, "No recent generation jobs.").show();
            return;
        }
        String[] labels = new String[generationJobs.size()];
        for (int i = 0; i < generationJobs.size(); i++) {
            EveAdminModels.GenerationJobItem j = generationJobs.get(i);
            labels[i] = "[" + j.status + "] " + j.examId + (j.retryable ? " (Tap to Retry)" : "");
        }
        fragment.showDialog(new AlertDialog.Builder(act, fragment.getResourceProvider())
                .setTitle("Generation Jobs")
                .setItems(labels, (d, idx) -> {
                    EveAdminModels.GenerationJobItem j = generationJobs.get(idx);
                    if (!j.retryable) {
                        showTextDialog(act, "Job " + j.id, "Status: " + j.status + "\nCategory: " + j.errorCategory);
                        return;
                    }
                    EveAdminRepository.ResultCallback<Void> cb = callback(fragment, ignored -> {
                        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, "Job retried.").show();
                        if (onRetried != null) onRetried.run();
                    });
                    repo.runWithAdminToken(cb, token -> {
                        repo.retryGenerationJob(token, j.id);
                        repo.postSuccess(cb, null);
                    });
                })
                .setNegativeButton("Close", null)
                .create());
    }

    private static void showTextDialog(Activity activity, String title, String message) {
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Close", null)
                .show();
    }

    private static void showInfo(BaseFragment fragment, String message) {
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, message).show();
    }

    private static void showError(BaseFragment fragment, String message) {
        BulletinFactory.of(fragment).createErrorBulletin(message).show();
    }

    private interface SuccessConsumer<T> {
        void accept(T value);
    }

    private static <T> EveAdminRepository.ResultCallback<T> callback(BaseFragment fragment, SuccessConsumer<T> onSuccess) {
        return new EveAdminRepository.ResultCallback<T>() {
            @Override
            public void onSuccess(T data) {
                onSuccess.accept(data);
            }

            @Override
            public void onError(String message, int httpStatus) {
                showError(fragment, message);
            }
        };
    }

    public static void showCreateSubExamDialog(
            BaseFragment fragment,
            EveAdminRepository repo,
            EveAdminModels.AdminExamItem parentExam,
            Runnable onCreated) {
        Activity activity = fragment.getParentActivity();
        if (activity == null || parentExam == null) return;
        Theme.ResourcesProvider rp = fragment.getResourceProvider();

        LinearLayout container = makeFormContainer(activity);
        EditText nameInput = addField(activity, container, "Sub-Exam Name (e.g. Tier 1 Maths)", "", InputType.TYPE_CLASS_TEXT, rp);
        EditText minutesInput = addField(activity, container, "Time Limit (minutes 1-600)", String.valueOf(parentExam.timeLimitMinutes > 0 ? parentExam.timeLimitMinutes : 60), InputType.TYPE_CLASS_NUMBER, rp);
        EditText countInput = addField(activity, container, "Questions Per Test (1-200)", String.valueOf(parentExam.questionCount > 0 ? parentExam.questionCount : 25), InputType.TYPE_CLASS_NUMBER, rp);
        EditText negInput = addField(activity, container, "Negative Marking (0, 0.25, 1/3, 0.5)", parentExam.negativeMarkingText, InputType.TYPE_CLASS_TEXT, rp);
        EditText promptInput = addField(activity, container, "AI Prompt Notes (optional)", "", InputType.TYPE_CLASS_TEXT, rp);

        final String[] modeLabels = new String[] { "Live", "Paused", "Inherit" };
        final String[] modeValues = new String[] { "live", "paused", "inherit" };
        final String[] selectedMode = new String[] { "live" };
        addFieldLabel(activity, container, "Publish Mode", rp);
        TextView publishPicker = new TextView(activity);
        publishPicker.setTextSize(15);
        publishPicker.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        publishPicker.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        publishPicker.setBackground(Theme.createRoundRectDrawable(
                AndroidUtilities.dp(8),
                Theme.multAlpha(Theme.getColor(Theme.key_dialogTextGray3, rp), 0.10f)));
        publishPicker.setText("Live  ▾");
        publishPicker.setOnClickListener(v -> new AlertDialog.Builder(activity, rp)
                .setTitle("Publish Mode")
                .setItems(modeLabels, (d, which) -> {
                    selectedMode[0] = modeValues[which];
                    publishPicker.setText(modeLabels[which] + "  ▾");
                })
                .setNegativeButton("Cancel", null)
                .show());
        container.addView(publishPicker, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 8));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, rp)
                .setTitle("Add Sub-Exam under " + parentExam.examName)
                .setView(wrapScroll(activity, container))
                .setPositiveButton("Create Sub-Exam", (d, w) -> {
                    String name = text(nameInput);
                    if (name.isEmpty()) {
                        showError(fragment, "Sub-exam name cannot be empty.");
                        return;
                    }
                    int minutes = parseIntBounded(minutesInput, 60, 1, 600);
                    int qCount = parseIntBounded(countInput, 25, 1, 200);
                    String neg = textOr(negInput, "0");
                    String prompt = text(promptInput);
                    String mode = selectedMode[0];

                    EveAdminRepository.ResultCallback<EveAdminModels.AdminExamItem> cb = callback(fragment, createdItem -> {
                        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check,
                                "Sub-exam created! Tap its avatar to customize photo.").show();
                        repo.invalidateCache();
                        if (onCreated != null) onCreated.run();
                        if (createdItem != null) {
                            fragment.presentFragment(new EveAdminExamDetailFragment(createdItem, null, onCreated));
                        }
                    });
                    repo.runWithAdminToken(cb, token -> {
                        String newId = repo.createExamWithParent(
                                token, name, parentExam.category, minutes, qCount, "Test 1",
                                neg, mode, prompt, parentExam.id);
                        EveAdminModels.AdminExamItem item = !newId.isEmpty()
                                ? new EveAdminModels.AdminExamItem(
                                        newId, name, parentExam.category, minutes, qCount, "Test 1",
                                        neg, mode, prompt, parentExam.id, "", "", "", "", "", -1)
                                : null;
                        repo.postSuccess(cb, item);
                    });
                })
                .setNegativeButton("Cancel", null);
        fragment.showDialog(builder.create());
    }
}
