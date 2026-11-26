package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.widget.CheckBox;
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
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.eve.premium.EvePremiumRepository;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

        LinearLayout container = makeFormContainer(activity);
        EditText nameInput = addField(activity, container, "Exam Name (e.g. SSC CGL Tier 1)", existing != null ? existing.examName : "", InputType.TYPE_CLASS_TEXT);
        EditText categoryInput = addField(activity, container, "Category (SSC, UPSC, Banking, Railways)", existing != null ? existing.category : "SSC", InputType.TYPE_CLASS_TEXT);
        EditText minutesInput = addField(activity, container, "Time Limit (minutes 1-600)", String.valueOf(existing != null ? existing.timeLimitMinutes : 60), InputType.TYPE_CLASS_NUMBER);
        EditText countInput = addField(activity, container, "Questions Per Test (1-200)", String.valueOf(existing != null ? existing.questionCount : 25), InputType.TYPE_CLASS_NUMBER);
        EditText negInput = addField(activity, container, "Negative Marking (0, 1/3, 1/4, 0.25)", existing != null ? existing.negativeMarkingText : "0.25", InputType.TYPE_CLASS_TEXT);
        EditText publishInput = addField(activity, container, "Publish Mode (inherit | live | paused)", existing != null ? existing.publishMode : "live", InputType.TYPE_CLASS_TEXT);
        EditText promptInput = existing == null
                ? addField(activity, container, "AI Generation Prompt Notes (optional)", "", InputType.TYPE_CLASS_TEXT)
                : null;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(existing == null ? "Create Exam" : "Edit Exam: " + existing.examName)
                .setView(wrapScroll(activity, container))
                .setPositiveButton(existing == null ? "Create" : "Save", (d, w) -> {
                    String name = text(nameInput);
                    String category = textOr(categoryInput, "General");
                    int minutes = parseIntBounded(minutesInput, 60, 1, 600);
                    int qCount = parseIntBounded(countInput, 25, 1, 200);
                    String neg = textOr(negInput, "0");
                    String mode = normalizePublishMode(text(publishInput));
                    String prompt = promptInput != null ? text(promptInput) : "";
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
                            repo.postSuccess(cb, "Exam created (" + id + ").");
                        } else {
                            repo.updateExam(token, existing.id, name, category, minutes, qCount, neg, mode);
                            repo.postSuccess(cb, "Exam updated and logged.");
                        }
                    });
                })
                .setNegativeButton("Cancel", null);
        if (existing != null) {
            builder.setNeutralButton("Duplicate / Pause", (d, w) -> new AlertDialog.Builder(activity)
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
                .setItems(labels, (d, which) -> showGeneratePromptDialog(fragment, repo, exams.get(which), onGenerated))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void showGeneratePromptDialog(
            BaseFragment fragment,
            EveAdminRepository repo,
            EveAdminModels.AdminExamItem exam,
            Runnable onGenerated) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        LinearLayout container = makeFormContainer(activity);
        EditText testNumberInput = addField(activity, container, "Test Label / Number", exam.testNumber, InputType.TYPE_CLASS_TEXT);
        EditText countInput = addField(activity, container, "Question Count (1-200)", String.valueOf(Math.max(5, Math.min(exam.questionCount, 200))), InputType.TYPE_CLASS_NUMBER);
        EditText notesInput = addField(activity, container, "Custom Topic / Prompt Notes (optional)", exam.generationPrompt, InputType.TYPE_CLASS_TEXT);

        new AlertDialog.Builder(activity)
                .setTitle("Generate Test: " + exam.examName)
                .setView(wrapScroll(activity, container))
                .setPositiveButton("Generate Now", (d, w) -> {
                    String label = textOr(testNumberInput, "Mock Test");
                    int count = parseIntBounded(countInput, exam.questionCount, 1, 200);
                    String notes = text(notesInput);
                    showInfo(fragment, "Generating mock test on EVE Cloud...");
                    EveAdminRepository.ResultCallback<String> cb = callback(fragment, msg -> {
                        showInfo(fragment, msg);
                        if (onGenerated != null) onGenerated.run();
                    });
                    repo.runWithAdminToken(cb, token -> {
                        JSONObject res = repo.generateTestNow(token, exam.id, count, label, notes);
                        JSONObject data = res.optJSONObject("data");
                        String title = data != null ? data.optString("title", label) : label;
                        String status = data != null ? data.optString("status", "draft") : "draft";
                        repo.postSuccess(cb, "Generated '" + title + "' (status: " + status + ").");
                    });
                })
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
            showInfo(fragment, "No generated mock tests found in D1.");
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

    private static void showBulkImportQuestionsDialog(
            BaseFragment fragment,
            EveAdminRepository repo,
            EveAdminModels.AdminExamItem exam,
            Runnable onImported) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        LinearLayout container = makeFormContainer(activity);
        EditText jsonInput = addField(activity, container,
                "Paste JSON array of questions ([{\"questionText\",\"optionA\",\"optionB\",\"optionC\",\"optionD\",\"correctAnswer\",\"explanation\",\"topic\"}])",
                "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(activity)
                .setTitle("Bulk Import: " + exam.examName)
                .setView(wrapScroll(activity, container))
                .setPositiveButton("Import", (d, w) -> {
                    String raw = text(jsonInput);
                    if (raw.isEmpty()) {
                        showError(fragment, "Paste a JSON array of questions.");
                        return;
                    }
                    org.json.JSONArray parsed;
                    try {
                        parsed = new org.json.JSONArray(raw);
                        for (int i = 0; i < parsed.length(); i++) {
                            JSONObject q = parsed.getJSONObject(i);
                            if (!q.has("examId") || q.optString("examId").isEmpty()) {
                                q.put("examId", exam.id);
                            }
                        }
                    } catch (Exception e) {
                        showError(fragment, "Invalid JSON array: " + e.getMessage());
                        return;
                    }
                    EveAdminRepository.ResultCallback<String> cb = callback(fragment, msg -> {
                        showInfo(fragment, msg);
                        if (onImported != null) onImported.run();
                    });
                    repo.runWithAdminToken(cb, token -> {
                        int count = repo.batchImportQuestions(token, parsed);
                        repo.postSuccess(cb, "Imported " + count + " questions.");
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void showQuestionActions(
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

    private static void showEditQuestionDialog(
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
            CheckBox enabledCheck = new CheckBox(activity);
            enabledCheck.setText("Premium Subscriptions Enabled");
            enabledCheck.setChecked(cfg.isEnabled);
            container.addView(enabledCheck);

            CheckBox lifetimeCheck = new CheckBox(activity);
            lifetimeCheck.setText("Lifetime Plan");
            lifetimeCheck.setChecked(cfg.isLifetime);
            container.addView(lifetimeCheck);

            CheckBox upiCheck = new CheckBox(activity);
            upiCheck.setText("UPI Payments Enabled");
            upiCheck.setChecked(cfg.upiEnabled);
            container.addView(upiCheck);

            CheckBox qrCheck = new CheckBox(activity);
            qrCheck.setText("QR Code Checkout Enabled");
            qrCheck.setChecked(cfg.qrEnabled);
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
                            repo.postSuccess(saveCb, "Pricing & UPI/QR configuration saved to D1.");
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
                        Bitmap qr = EvePremiumRepository.generateQrBitmap(uri, AndroidUtilities.dp(220));
                        LinearLayout qrBox = new LinearLayout(activity);
                        qrBox.setOrientation(LinearLayout.VERTICAL);
                        qrBox.setGravity(Gravity.CENTER_HORIZONTAL);
                        int pad = AndroidUtilities.dp(18);
                        qrBox.setPadding(pad, pad, pad, pad);
                        if (qr != null) {
                            ImageView qrView = new ImageView(activity);
                            qrView.setImageBitmap(qr);
                            qrBox.addView(qrView, new LinearLayout.LayoutParams(AndroidUtilities.dp(220), AndroidUtilities.dp(220)));
                        }
                        TextView note = new TextView(activity);
                        note.setGravity(Gravity.CENTER);
                        note.setPadding(0, AndroidUtilities.dp(12), 0, 0);
                        note.setText("VPA: " + vpa + "\nMerchant: " + (merchant.isEmpty() ? "EVE" : merchant) + "\nAmount: ₹" + amount);
                        qrBox.addView(note);
                        new AlertDialog.Builder(activity)
                                .setTitle("Merchant UPI QR Preview")
                                .setView(qrBox)
                                .setPositiveButton("Close", null)
                                .show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchPremiumConfig(token)));
    }

    public static void showTransactionsDialog(BaseFragment fragment, EveAdminRepository repo) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        String[] statuses = new String[] { "ALL", "SUCCESS", "CREATED", "EXPIRED", "REFUNDED" };
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

        List<CheckBox> rightChecks = new ArrayList<>();
        Set<String> currentRights = existing != null ? existing.rights : new HashSet<>();
        for (String right : EveAdminModels.ALL_RIGHTS) {
            CheckBox check = new CheckBox(activity);
            check.setText(right);
            check.setChecked(currentRights.contains(right));
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
                sb.append("No server audit records yet.");
            } else {
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
                for (EveAdminModels.AuditLogEntry e : entries) {
                    sb.append("• [").append(e.actionType).append("] ").append(e.description).append("\n  ")
                            .append(e.adminEmail).append(" • ")
                            .append(e.timestamp > 0 ? fmt.format(new Date(e.timestamp)) : "-")
                            .append("\n\n");
                }
            }
            showTextDialog(activity, "Server Audit Trail (" + entries.size() + ")", sb.toString().trim());
        });
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchAuditLogs(token, 50)));
    }

    private static LinearLayout makeFormContainer(Context ctx) {
        LinearLayout ll = new LinearLayout(ctx);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(16);
        ll.setPadding(pad, pad, pad, pad);
        return ll;
    }

    private static ScrollView wrapScroll(Context ctx, LinearLayout inner) {
        ScrollView sv = new ScrollView(ctx);
        sv.addView(inner);
        return sv;
    }

    private static EditText addField(Context ctx, LinearLayout parent, String label, String initial, int inputType) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(2));
        parent.addView(tv);

        EditText et = new EditText(ctx);
        et.setInputType(inputType);
        et.setText(initial != null ? initial : "");
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

    private static String normalizePublishMode(String mode) {
        String lower = mode.trim().toLowerCase(Locale.US);
        if ("live".equals(lower) || "paused".equals(lower)) {
            return lower;
        }
        return "inherit";
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
}
