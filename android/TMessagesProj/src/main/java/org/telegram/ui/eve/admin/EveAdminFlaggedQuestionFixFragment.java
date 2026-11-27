package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Full-screen Telegram-native Flagged Question Fixer.
 * Fetches the underlying question (either a Question Bank row or a Generated Mock Test question),
 * lets Admins edit text/options/explanation/correct option using EditTextBoldCursor and CheckBox2,
 * and saves fixes atomically while resolving all pending reports for that question.
 */
public class EveAdminFlaggedQuestionFixFragment extends BaseFragment {

    private static final int ID_SAVE_FIX = 201;
    private static final int ID_DISMISS_REPORT = 202;
    private static final int ID_DELETE_QUESTION = 203;
    private static final int ID_RETRY = 204;

    private static final String[] OPTION_KEYS = new String[] { "A", "B", "C", "D" };

    private final EveAdminModels.QuestionReportItem report;
    @Nullable
    private final Runnable onMutated;
    private final EveAdminRepository repo = EveAdminRepository.getInstance();

    private UniversalRecyclerView listView;
    private ReportHeaderView reportCard;
    private QuestionEditorCard editorCard;

    private EveAdminModels.AdminQuestionItem loadedQuestion;
    private boolean loadingQuestion = true;
    private boolean saving = false;
    private boolean destroyed = false;
    private String loadError = "";

    public EveAdminFlaggedQuestionFixFragment(
            EveAdminModels.QuestionReportItem report,
            @Nullable Runnable onMutated) {
        this.report = report;
        this.onMutated = onMutated;
    }

    @Override
    public boolean onFragmentCreate() {
        return EveAdminConfig.isAdminAvailable() && report != null && super.onFragmentCreate();
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

        reportCard = new ReportHeaderView(context, getResourceProvider());
        reportCard.bind(report);

        editorCard = new QuestionEditorCard(context, getResourceProvider());
        editorCard.bindFallback(report);

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Fix Flagged Question",
                !TextUtils.isEmpty(report.examName) ? report.examName : "Reported Item",
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
                    if (reportCard != null) reportCard.updateColors();
                    if (editorCard != null) editorCard.updateColors();
                }
        );
        listView = chrome.listView;
        fragmentView = chrome.rootView;
        loadQuestion();
        return chrome.rootView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }

    private void loadQuestion() {
        if (destroyed) return;
        if (TextUtils.isEmpty(report.questionId)) {
            loadingQuestion = false;
            loadError = "This legacy report has no questionId attached; you can still dismiss or inspect the snippet.";
            updateList();
            return;
        }
        loadingQuestion = true;
        loadError = "";
        updateList();

        EveAdminRepository.ResultCallback<EveAdminModels.AdminQuestionItem> cb =
                new EveAdminRepository.ResultCallback<EveAdminModels.AdminQuestionItem>() {
                    @Override
                    public void onSuccess(EveAdminModels.AdminQuestionItem data) {
                        if (destroyed) return;
                        loadingQuestion = false;
                        loadError = "";
                        loadedQuestion = data;
                        if (data != null && editorCard != null) {
                            editorCard.bindQuestion(data);
                        }
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loadingQuestion = false;
                        loadError = message != null && !message.isEmpty()
                                ? message
                                : "Unable to load source question (" + report.questionId + ").";
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> {
            EveAdminModels.AdminQuestionItem q = repo.fetchQuestionById(token, report.questionId);
            if (q == null) {
                throw new IllegalStateException("Question not found (may have already been deleted).");
            }
            repo.postSuccess(cb, q);
        });
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        items.add(UItem.asCustom(reportCard));
        items.add(UItem.asShadow(null));

        if (!loadError.isEmpty()) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_RETRY,
                    IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                    R.drawable.msg_retry,
                    "Unable to Fetch Full Question",
                    loadError,
                    "Retry"));
            items.add(UItem.asShadow(null));
        }

        if (loadingQuestion) {
            items.add(UItem.asHeader("Loading Question Details"));
            for (int i = 0; i < 4; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (loadedQuestion != null || !TextUtils.isEmpty(report.questionId)) {
            items.add(UItem.asHeader("Edit Question & Answer"));
            items.add(UItem.asCustom(editorCard));
            items.add(UItem.asShadow("Saving will immediately update the live test/question bank and mark all pending flags for this question resolved."));

            items.add(UItem.asHeader("Resolution Actions"));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_SAVE_FIX,
                    IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                    R.drawable.msg_select,
                    saving ? "Saving Fix..." : "Save Fix & Resolve Flag",
                    "Applies edits cleanly without creating duplicate questions",
                    null));
        } else {
            items.add(UItem.asHeader("Resolution Actions"));
        }

        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_DISMISS_REPORT,
                IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                R.drawable.msg_info,
                "Dismiss False Report",
                "Keep existing question unchanged and mark flag dismissed",
                null));

        if (!TextUtils.isEmpty(report.questionId)) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_DELETE_QUESTION,
                    IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                    R.drawable.msg_delete,
                    "Delete Bad Question",
                    "Removes this question and resolves pending reports",
                    null));
        }
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (saving) return;
        if (item.id == ID_RETRY) {
            loadQuestion();
        } else if (item.id == ID_SAVE_FIX) {
            saveFixAndResolve();
        } else if (item.id == ID_DISMISS_REPORT) {
            dismissFalseReport();
        } else if (item.id == ID_DELETE_QUESTION) {
            confirmDeleteQuestion();
        }
    }

    private void saveFixAndResolve() {
        String qText = editorCard.getQuestionText();
        String optA = editorCard.getOptionA();
        String optB = editorCard.getOptionB();
        String optC = editorCard.getOptionC();
        String optD = editorCard.getOptionD();
        String correct = editorCard.getSelectedCorrect();
        String explanation = editorCard.getExplanation();
        String topic = editorCard.getTopic();

        if (qText.isEmpty() || optA.isEmpty() || optB.isEmpty() || optC.isEmpty() || optD.isEmpty()) {
            BulletinFactory.of(this).createErrorBulletin("Question text and all four options (A-D) are required.").show();
            return;
        }
        saving = true;
        updateList();

        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                if (destroyed) return;
                saving = false;
                repo.invalidateCache();
                BulletinFactory.of(EveAdminFlaggedQuestionFixFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, "Question updated & flag resolved").show();
                if (onMutated != null) onMutated.run();
                finishFragment();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                saving = false;
                updateList();
                BulletinFactory.of(EveAdminFlaggedQuestionFixFragment.this)
                        .createErrorBulletin(message != null ? message : "Failed to save fix.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.fixFlaggedQuestion(token, report.id, qText, optA, optB, optC, optD, correct, explanation, topic);
            repo.postSuccess(cb, null);
        });
    }

    private void dismissFalseReport() {
        saving = true;
        updateList();
        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                if (destroyed) return;
                saving = false;
                repo.invalidateCache();
                BulletinFactory.of(EveAdminFlaggedQuestionFixFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, "Report dismissed").show();
                if (onMutated != null) onMutated.run();
                finishFragment();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                saving = false;
                updateList();
                BulletinFactory.of(EveAdminFlaggedQuestionFixFragment.this)
                        .createErrorBulletin(message != null ? message : "Failed to dismiss report.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.dismissReport(token, report.id);
            repo.postSuccess(cb, null);
        });
    }

    private void confirmDeleteQuestion() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Delete Question Permanently?");
        builder.setMessage("This will permanently remove the flagged question from the database or generated test and close pending reports.");
        builder.setPositiveButton("Delete Question", (d, w) -> {
            saving = true;
            updateList();
            EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
                @Override
                public void onSuccess(Void data) {
                    if (destroyed) return;
                    saving = false;
                    repo.invalidateCache();
                    BulletinFactory.of(EveAdminFlaggedQuestionFixFragment.this)
                            .createSimpleBulletin(R.raw.ic_delete, "Question removed & flags closed").show();
                    if (onMutated != null) onMutated.run();
                    finishFragment();
                }

                @Override
                public void onError(String message, int httpStatus) {
                    if (destroyed) return;
                    saving = false;
                    updateList();
                    BulletinFactory.of(EveAdminFlaggedQuestionFixFragment.this)
                            .createErrorBulletin(message != null ? message : "Failed to delete question.").show();
                }
            };
            repo.runWithAdminToken(cb, token -> {
                repo.deleteFlaggedQuestion(token, report.id);
                repo.postSuccess(cb, null);
            });
        });
        builder.setNegativeButton("Cancel", null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (pos instanceof TextView) {
            ((TextView) pos).setTextColor(Theme.getColor(Theme.key_text_RedBold, getResourceProvider()));
        }
    }

    private static final class ReportHeaderView extends LinearLayout {
        private final Theme.ResourcesProvider rp;
        private final TextView titleView;
        private final TextView metaView;
        private final TextView commentView;
        private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ReportHeaderView(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.rp = rp;
            setOrientation(VERTICAL);
            setWillNotDraw(false);
            setPadding(dp(16), dp(14), dp(16), dp(14));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTypeface(AndroidUtilities.bold());
            addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            metaView = new TextView(context);
            metaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            addView(metaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            commentView = new TextView(context);
            commentView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            commentView.setPadding(dp(10), dp(8), dp(10), dp(8));
            addView(commentView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

            updateColors();
        }

        void bind(EveAdminModels.QuestionReportItem report) {
            String reason = !TextUtils.isEmpty(report.reason) ? report.reason : "Flagged Issue";
            titleView.setText(reason + " • " + report.status.toUpperCase(Locale.US));
            String exam = !TextUtils.isEmpty(report.examName) ? report.examName : "Exam";
            String reporter = !TextUtils.isEmpty(report.studentEmail) ? report.studentEmail : "Student";
            String when = EveAdminUiChrome.formatDateTime(report.timestamp);
            metaView.setText(exam + " • Reported by " + reporter + " • " + when);

            if (!TextUtils.isEmpty(report.comment)) {
                commentView.setVisibility(VISIBLE);
                commentView.setText("Student note: \"" + report.comment + "\"");
            } else {
                commentView.setVisibility(GONE);
            }
        }

        void updateColors() {
            cardPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, rp));
            titleView.setTextColor(Theme.getColor(Theme.key_text_RedBold, rp));
            metaView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, rp));
            commentView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
            commentView.setBackground(Theme.createRoundRectDrawable(
                    dp(8),
                    Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton, rp), 0.10f)));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRect(0, 0, getWidth(), getHeight(), cardPaint);
        }
    }

    private static final class QuestionEditorCard extends LinearLayout {
        private final Theme.ResourcesProvider rp;
        private final EditTextBoldCursor questionInput;
        private final EditTextBoldCursor optionAInput;
        private final EditTextBoldCursor optionBInput;
        private final EditTextBoldCursor optionCInput;
        private final EditTextBoldCursor optionDInput;
        private final EditTextBoldCursor explanationInput;
        private final EditTextBoldCursor topicInput;

        private final LinearLayout[] optionRows = new LinearLayout[4];
        private final CheckBox2[] radioBoxes = new CheckBox2[4];
        private final TextView[] radioLabels = new TextView[4];
        private final ArrayList<TextView> fieldLabels = new ArrayList<>();
        private final ArrayList<EditTextBoldCursor> fields = new ArrayList<>();
        private String selectedCorrect = "A";

        QuestionEditorCard(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.rp = rp;
            setOrientation(VERTICAL);
            setPadding(dp(16), dp(12), dp(16), dp(14));

            questionInput = addField(context, "Question Text", "Enter full question stem", true, 3);
            optionAInput = addField(context, "Option A", "Option A text", false, 1);
            optionBInput = addField(context, "Option B", "Option B text", false, 1);
            optionCInput = addField(context, "Option C", "Option C text", false, 1);
            optionDInput = addField(context, "Option D", "Option D text", false, 1);

            TextView answerHeader = new TextView(context);
            answerHeader.setText("Correct Answer");
            answerHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            answerHeader.setTypeface(AndroidUtilities.bold());
            addView(answerHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 6));
            fieldLabels.add(answerHeader);

            LinearLayout radiosWrap = new LinearLayout(context);
            radiosWrap.setOrientation(HORIZONTAL);
            for (int i = 0; i < 4; i++) {
                final int idx = i;
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(HORIZONTAL);
                row.setGravity(Gravity.CENTER);
                row.setPadding(dp(8), dp(8), dp(8), dp(8));

                CheckBox2 check = new CheckBox2(context, 21, rp);
                check.setColor(Theme.key_radioBackgroundChecked, Theme.key_radioBackground, Theme.key_checkboxCheck);
                check.setDrawUnchecked(true);
                check.setDrawBackgroundAsArc(10);
                check.setChecked(i == 0, false);

                TextView label = new TextView(context);
                label.setText(OPTION_KEYS[i]);
                label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                label.setTypeface(AndroidUtilities.bold());
                label.setPadding(dp(6), 0, 0, 0);

                row.addView(check, LayoutHelper.createLinear(21, 21));
                row.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
                row.setOnClickListener(v -> setSelectedCorrect(OPTION_KEYS[idx], true));

                optionRows[i] = row;
                radioBoxes[i] = check;
                radioLabels[i] = label;
                radiosWrap.addView(row, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, i == 0 ? 0 : 4, 0, i == 3 ? 0 : 4, 0));
            }
            addView(radiosWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

            explanationInput = addField(context, "Explanation / Solution", "Step-by-step explanation shown after submission", true, 3);
            topicInput = addField(context, "Topic / Subject Tag", "e.g. Polity, Quantitative Aptitude", false, 1);

            updateColors();
        }

        private EditTextBoldCursor addField(Context context, String label, String hint, boolean multiLine, int minLines) {
            TextView tv = new TextView(context);
            tv.setText(label);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            tv.setTypeface(AndroidUtilities.bold());
            addView(tv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 4));
            fieldLabels.add(tv);

            EditTextBoldCursor et = new EditTextBoldCursor(context);
            et.setHint(hint);
            et.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            et.setPadding(dp(12), dp(10), dp(12), dp(10));
            et.setCursorSize(dp(20));
            et.setCursorWidth(1.5f);
            if (multiLine) {
                et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                et.setSingleLine(false);
                et.setMinLines(minLines);
                et.setGravity(Gravity.TOP | Gravity.START);
            } else {
                et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                et.setSingleLine(true);
                et.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            }
            addView(et, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            fields.add(et);
            return et;
        }

        void bindFallback(EveAdminModels.QuestionReportItem report) {
            if (report != null && !TextUtils.isEmpty(report.questionText) && TextUtils.isEmpty(questionInput.getText())) {
                questionInput.setText(report.questionText);
            }
        }

        void bindQuestion(@NonNull EveAdminModels.AdminQuestionItem q) {
            questionInput.setText(q.questionText);
            optionAInput.setText(q.optionA);
            optionBInput.setText(q.optionB);
            optionCInput.setText(q.optionC);
            optionDInput.setText(q.optionD);
            explanationInput.setText(q.explanation);
            topicInput.setText(q.topic);
            setSelectedCorrect(q.correctAnswer, false);
        }

        void setSelectedCorrect(String answer, boolean animated) {
            String normalized = answer != null ? answer.trim().toUpperCase(Locale.US) : "A";
            int pickedIdx = 0;
            for (int i = 0; i < OPTION_KEYS.length; i++) {
                if (OPTION_KEYS[i].equals(normalized)) {
                    pickedIdx = i;
                    break;
                }
            }
            selectedCorrect = OPTION_KEYS[pickedIdx];
            for (int i = 0; i < 4; i++) {
                radioBoxes[i].setChecked(i == pickedIdx, animated);
            }
            updateRadioContainers();
        }

        private void updateRadioContainers() {
            int blue = Theme.getColor(Theme.key_featuredStickers_addButton, rp);
            int gray = Theme.getColor(Theme.key_windowBackgroundGray, rp);
            int black = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp);
            for (int i = 0; i < 4; i++) {
                boolean active = OPTION_KEYS[i].equals(selectedCorrect);
                optionRows[i].setBackground(Theme.createRoundRectDrawable(
                        dp(8),
                        active ? Theme.multAlpha(blue, 0.14f) : gray));
                radioLabels[i].setTextColor(active ? blue : black);
            }
        }

        void updateColors() {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, rp));
            int blue = Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, rp);
            int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp);
            int hint = Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp);
            int cursor = Theme.getColor(Theme.key_windowBackgroundWhiteValueText, rp);
            int fieldBg = Theme.getColor(Theme.key_windowBackgroundGray, rp);

            for (TextView lbl : fieldLabels) {
                lbl.setTextColor(blue);
            }
            for (EditTextBoldCursor et : fields) {
                et.setTextColor(text);
                et.setHintTextColor(hint);
                et.setCursorColor(cursor);
                et.setBackground(Theme.createRoundRectDrawable(dp(10), fieldBg));
            }
            updateRadioContainers();
        }

        String getQuestionText() {
            return text(questionInput);
        }

        String getOptionA() {
            return text(optionAInput);
        }

        String getOptionB() {
            return text(optionBInput);
        }

        String getOptionC() {
            return text(optionCInput);
        }

        String getOptionD() {
            return text(optionDInput);
        }

        String getExplanation() {
            return text(explanationInput);
        }

        String getTopic() {
            return text(topicInput);
        }

        String getSelectedCorrect() {
            return selectedCorrect;
        }

        private static String text(EditTextBoldCursor et) {
            return et.getText() != null ? et.getText().toString().trim() : "";
        }
    }
}
