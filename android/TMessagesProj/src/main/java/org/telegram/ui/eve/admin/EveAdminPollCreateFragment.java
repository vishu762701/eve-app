package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.PollCreateCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.EditTextEmoji;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Telegram Quiz Creator using {@link EditTextEmoji} (question, options, explanation),
 * {@link CheckBox2} to mark the correct answer, and {@link PollCreateCheckCell}.
 */
public class EveAdminPollCreateFragment extends BaseFragment {

    private static final int MAX_QUESTION_LEN = 255;
    private static final int MAX_OPTION_LEN = 120;
    private static final int MAX_EXPLANATION_LEN = 400;
    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 10;

    private static final int MENU_CREATE = 1;
    private static final int ID_QUESTION_CELL = 100;
    private static final int ID_EXPLANATION_CELL = 150;
    private static final int ID_ADD_OPTION = 200;
    private static final int ID_ACTIVE_TOGGLE = 300;
    private static final int ID_CREATE_BUTTON = 400;
    private static final int BASE_OPTION_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    @Nullable private final Runnable onCreated;

    private SizeNotifierFrameLayout rootEmojiContainer;
    private UniversalRecyclerView listView;
    private LinearLayout questionContainer;
    private EditTextEmoji questionEditEmoji;
    private TextView questionCountView;
    private LinearLayout explanationContainer;
    private EditTextEmoji explanationEditEmoji;
    private PollCreateCheckCell activeCheckCell;
    private final List<OptionRow> optionRows = new ArrayList<>();
    private int nextOptionSlotId = 1;
    private int selectedCorrectSlotId = -1;
    private boolean activeImmediately = true;
    private boolean saving = false;
    private boolean destroyed = false;

    public EveAdminPollCreateFragment(@Nullable Runnable onCreated) {
        this.onCreated = onCreated;
    }

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
    public View createView(Context context) {
        destroyed = false;
        setHasOwnBackground(true);
        Theme.ResourcesProvider rp = getResourceProvider();

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                rp,
                "New Quiz",
                "Select correct answer • Emojis supported",
                true,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_CREATE) {
                            submitQuiz();
                        }
                    }
                },
                null
        );
        listView = chrome.listView;
        rootEmojiContainer = chrome.rootView;

        questionContainer = buildQuestionInputCard(context, rp);
        explanationContainer = buildExplanationInputCard(context, rp);

        optionRows.clear();
        selectedCorrectSlotId = -1;
        addOptionRow(context, false);
        addOptionRow(context, false);
        if (!optionRows.isEmpty()) {
            selectedCorrectSlotId = optionRows.get(0).slotId;
            syncOptionHintsAndChecks();
        }

        activeCheckCell = new PollCreateCheckCell(context, rp);
        activeCheckCell.setTextAndValueAndIconAndCheck(
                "Active Immediately",
                "Publish quiz to Announcements channel immediately",
                IconBackgroundColors.GREEN,
                R.drawable.msg_notifications,
                activeImmediately
        );
        activeCheckCell.setAnimationsEnabled(true);

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            menu.addItem(MENU_CREATE, R.drawable.ic_ab_done, rp);
        }

        fragmentView = chrome.rootView;
        updateList();
        return chrome.rootView;
    }

    private LinearLayout buildQuestionInputCard(Context context, Theme.ResourcesProvider rp) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(10), dp(16), dp(8));

        questionEditEmoji = EveAnimatedEmojiHelper.createEditTextEmoji(
                context, rootEmojiContainer, this, "Ask a quiz question", "", true, rp);
        registerExclusivePopupFocus(questionEditEmoji);
        if (questionEditEmoji.getEditText() != null) {
            questionEditEmoji.getEditText().setFilters(new InputFilter[] { new InputFilter.LengthFilter(MAX_QUESTION_LEN) });
            questionEditEmoji.getEditText().addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    int left = MAX_QUESTION_LEN - (s != null ? s.length() : 0);
                    if (questionCountView != null) {
                        questionCountView.setText(Math.max(0, left) + " chars left");
                    }
                }
            });
        }
        card.addView(questionEditEmoji, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        questionCountView = new TextView(context);
        questionCountView.setText(MAX_QUESTION_LEN + " chars left");
        questionCountView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        questionCountView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        questionCountView.setGravity(Gravity.END);
        card.addView(questionCountView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        return card;
    }

    private LinearLayout buildExplanationInputCard(Context context, Theme.ResourcesProvider rp) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(8), dp(16), dp(10));

        explanationEditEmoji = EveAnimatedEmojiHelper.createEditTextEmoji(
                context, rootEmojiContainer, this, "Explanation (shown after answering)", "", true, rp);
        registerExclusivePopupFocus(explanationEditEmoji);
        if (explanationEditEmoji.getEditText() != null) {
            explanationEditEmoji.getEditText().setFilters(new InputFilter[] { new InputFilter.LengthFilter(MAX_EXPLANATION_LEN) });
        }
        card.addView(explanationEditEmoji, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return card;
    }

    private void registerExclusivePopupFocus(@NonNull EditTextEmoji target) {
        if (rootEmojiContainer instanceof EveAnimatedEmojiHelper.EmojiHostSizeNotifierFrameLayout) {
            ((EveAnimatedEmojiHelper.EmojiHostSizeNotifierFrameLayout) rootEmojiContainer).registerEditTextEmoji(target);
        }
    }

    private void hideOtherPopups(@Nullable EditTextEmoji except) {
        if (rootEmojiContainer instanceof EveAnimatedEmojiHelper.EmojiHostSizeNotifierFrameLayout && except == null) {
            ((EveAnimatedEmojiHelper.EmojiHostSizeNotifierFrameLayout) rootEmojiContainer).hideAllPopups(true);
        }
        if (questionEditEmoji != null && questionEditEmoji != except && questionEditEmoji.isPopupShowing()) {
            questionEditEmoji.hidePopup(true);
        }
        if (explanationEditEmoji != null && explanationEditEmoji != except && explanationEditEmoji.isPopupShowing()) {
            explanationEditEmoji.hidePopup(true);
        }
        for (OptionRow row : optionRows) {
            if (row.editTextEmoji != null && row.editTextEmoji != except && row.editTextEmoji.isPopupShowing()) {
                row.editTextEmoji.hidePopup(true);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (questionEditEmoji != null) questionEditEmoji.onResume();
        if (explanationEditEmoji != null) explanationEditEmoji.onResume();
        for (OptionRow row : optionRows) {
            if (row.editTextEmoji != null) row.editTextEmoji.onResume();
        }
    }

    @Override
    public void onPause() {
        hideOtherPopups(null);
        if (questionEditEmoji != null) questionEditEmoji.onPause();
        if (explanationEditEmoji != null) explanationEditEmoji.onPause();
        for (OptionRow row : optionRows) {
            if (row.editTextEmoji != null) row.editTextEmoji.onPause();
        }
        super.onPause();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (isAnyPopupShowing()) {
            if (invoked) {
                hideOtherPopups(null);
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private boolean isAnyPopupShowing() {
        if (questionEditEmoji != null && questionEditEmoji.isPopupShowing()) return true;
        if (explanationEditEmoji != null && explanationEditEmoji.isPopupShowing()) return true;
        for (OptionRow row : optionRows) {
            if (row.editTextEmoji != null && row.editTextEmoji.isPopupShowing()) return true;
        }
        return false;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        if (questionEditEmoji != null) questionEditEmoji.onDestroy();
        if (explanationEditEmoji != null) explanationEditEmoji.onDestroy();
        for (OptionRow row : optionRows) {
            if (row.editTextEmoji != null) row.editTextEmoji.onDestroy();
        }
        super.onFragmentDestroy();
    }

    private void addOptionRow(Context context, boolean requestFocus) {
        if (optionRows.size() >= MAX_OPTIONS || rootEmojiContainer == null) {
            return;
        }
        Theme.ResourcesProvider rp = getResourceProvider();
        int slotId = nextOptionSlotId++;

        LinearLayout rowLayout = new LinearLayout(context);
        rowLayout.setOrientation(LinearLayout.HORIZONTAL);
        rowLayout.setGravity(Gravity.CENTER_VERTICAL);
        rowLayout.setPadding(dp(12), dp(5), dp(12), dp(5));

        CheckBox2 correctBox = new CheckBox2(context, 22, rp);
        correctBox.setColor(Theme.key_featuredStickers_addButton, Theme.key_windowBackgroundWhiteGrayText2, Theme.key_featuredStickers_buttonText);
        correctBox.setDrawBackgroundAsArc(10);
        rowLayout.addView(correctBox, LayoutHelper.createLinear(26, 26, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

        EditTextEmoji editField = EveAnimatedEmojiHelper.createEditTextEmoji(
                context, rootEmojiContainer, this, "Option " + (optionRows.size() + 1), "", false, rp);
        registerExclusivePopupFocus(editField);
        if (editField.getEditText() != null) {
            editField.getEditText().setFilters(new InputFilter[] { new InputFilter.LengthFilter(MAX_OPTION_LEN) });
        }
        rowLayout.addView(editField, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        ImageView deleteBtn = new ImageView(context);
        deleteBtn.setImageResource(R.drawable.msg_close);
        deleteBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        deleteBtn.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        rowLayout.addView(deleteBtn, LayoutHelper.createLinear(34, 34, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));

        final OptionRow row = new OptionRow(slotId, rowLayout, correctBox, editField, deleteBtn);
        correctBox.setOnClickListener(v -> {
            selectedCorrectSlotId = row.slotId;
            syncOptionHintsAndChecks();
        });
        deleteBtn.setOnClickListener(v -> removeOptionRow(row));

        optionRows.add(row);
        syncOptionHintsAndChecks();
        updateList();
        if (requestFocus && editField.getEditText() != null) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!destroyed && editField.getEditText() != null) {
                    editField.getEditText().requestFocus();
                    AndroidUtilities.showKeyboard(editField.getEditText());
                }
            }, 60);
        }
    }

    private void removeOptionRow(OptionRow target) {
        if (optionRows.size() <= MIN_OPTIONS) {
            return;
        }
        if (target.editTextEmoji != null) {
            target.editTextEmoji.onPause();
            target.editTextEmoji.onDestroy();
        }
        optionRows.remove(target);
        if (selectedCorrectSlotId == target.slotId && !optionRows.isEmpty()) {
            selectedCorrectSlotId = optionRows.get(0).slotId;
        }
        syncOptionHintsAndChecks();
        updateList();
    }

    private void syncOptionHintsAndChecks() {
        boolean canDelete = optionRows.size() > MIN_OPTIONS;
        for (int i = 0; i < optionRows.size(); i++) {
            OptionRow row = optionRows.get(i);
            if (row.editTextEmoji != null && row.editTextEmoji.getEditText() != null) {
                row.editTextEmoji.getEditText().setHint("Option " + (i + 1));
            }
            if (row.correctBox != null) {
                row.correctBox.setChecked(row.slotId == selectedCorrectSlotId, true);
            }
            if (row.deleteBtn != null) {
                row.deleteBtn.setVisibility(canDelete ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        items.add(UItem.asHeader("Quiz Question"));
        items.add(UItem.asCustom(ID_QUESTION_CELL, questionContainer));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Quiz Options (Tap circle to mark Correct Answer)"));
        for (int i = 0; i < optionRows.size(); i++) {
            OptionRow row = optionRows.get(i);
            items.add(UItem.asCustom(BASE_OPTION_ID + row.slotId, row.container));
        }
        if (optionRows.size() < MAX_OPTIONS) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_ADD_OPTION,
                    IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                    R.drawable.outline_poll_add_24,
                    "Add an option...",
                    (MAX_OPTIONS - optionRows.size()) + " remaining",
                    null));
        }
        items.add(UItem.asShadow("At least 2 options required. Mark one option as the correct answer."));

        items.add(UItem.asHeader("Explanation (shown after answering)"));
        items.add(UItem.asCustom(ID_EXPLANATION_CELL, explanationContainer));
        items.add(UItem.asShadow("Optional explanation revealed right after a student taps an answer."));

        items.add(UItem.asHeader("Settings"));
        items.add(UItem.asCustom(ID_ACTIVE_TOGGLE, activeCheckCell));
        items.add(UItem.asShadow(null));

        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_CREATE_BUTTON,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                R.drawable.ic_ab_done,
                saving ? "Creating Quiz..." : "Create Quiz",
                "Publish to EVE Announcements Channel",
                null));
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ADD_OPTION) {
            Context ctx = getContext();
            if (ctx != null) {
                addOptionRow(ctx, true);
            }
        } else if (item.id == ID_ACTIVE_TOGGLE) {
            activeImmediately = !activeImmediately;
            if (activeCheckCell != null) {
                activeCheckCell.setChecked(activeImmediately);
            }
        } else if (item.id == ID_CREATE_BUTTON) {
            submitQuiz();
        }
    }

    private void submitQuiz() {
        if (saving) return;
        String question = questionEditEmoji != null
                ? EveAnimatedEmojiHelper.encode(questionEditEmoji.getText()).trim()
                : "";
        List<String> opts = new ArrayList<>();
        int mappedCorrectIdx = -1;
        for (OptionRow row : optionRows) {
            String trimmed = row.editTextEmoji != null
                    ? EveAnimatedEmojiHelper.encode(row.editTextEmoji.getText()).trim()
                    : "";
            if (!trimmed.isEmpty()) {
                if (row.slotId == selectedCorrectSlotId) {
                    mappedCorrectIdx = opts.size();
                }
                opts.add(trimmed);
            }
        }
        if (TextUtils.isEmpty(question)) {
            BulletinFactory.of(this).createErrorBulletin("Enter a quiz question.").show();
            return;
        }
        if (opts.size() < MIN_OPTIONS) {
            BulletinFactory.of(this).createErrorBulletin("Enter at least 2 non-empty options.").show();
            return;
        }
        if (mappedCorrectIdx < 0) {
            BulletinFactory.of(this).createErrorBulletin("Tap a circle to mark the correct answer.").show();
            return;
        }

        String explanation = explanationEditEmoji != null
                ? EveAnimatedEmojiHelper.encode(explanationEditEmoji.getText()).trim()
                : "";

        saving = true;
        updateList();

        final int correctIndexFinal = mappedCorrectIdx;
        final boolean activeFlag = activeImmediately;
        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                if (destroyed) return;
                saving = false;
                AndroidUtilities.hideKeyboard(fragmentView);
                BulletinFactory.of(EveAdminPollCreateFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, "Quiz created").show();
                if (onCreated != null) {
                    onCreated.run();
                }
                finishFragment();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                saving = false;
                updateList();
                BulletinFactory.of(EveAdminPollCreateFragment.this)
                        .createErrorBulletin(message != null ? message : "Failed to create quiz.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.createPoll(token, question, opts, activeFlag, true, correctIndexFinal, explanation);
            repo.postSuccess(cb, null);
        });
    }

    private static final class OptionRow {
        final int slotId;
        final LinearLayout container;
        final CheckBox2 correctBox;
        final EditTextEmoji editTextEmoji;
        final ImageView deleteBtn;

        OptionRow(
                int slotId,
                LinearLayout container,
                CheckBox2 correctBox,
                EditTextEmoji editTextEmoji,
                ImageView deleteBtn) {
            this.slotId = slotId;
            this.container = container;
            this.correctBox = correctBox;
            this.editTextEmoji = editTextEmoji;
            this.deleteBtn = deleteBtn;
        }
    }
}
