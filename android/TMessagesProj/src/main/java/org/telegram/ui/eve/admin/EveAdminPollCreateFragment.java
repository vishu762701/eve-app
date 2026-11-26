package org.telegram.ui.eve.admin;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.PollCreateCheckCell;
import org.telegram.ui.Cells.PollEditTextCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Genuine Telegram-native Poll Creator using {@link PollEditTextCell} and {@link PollCreateCheckCell}.
 */
public class EveAdminPollCreateFragment extends BaseFragment {

    private static final int MAX_QUESTION_LEN = 255;
    private static final int MAX_OPTION_LEN = 100;
    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 10;

    private static final int MENU_CREATE = 1;
    private static final int ID_QUESTION_CELL = 100;
    private static final int ID_ADD_OPTION = 200;
    private static final int ID_ACTIVE_TOGGLE = 300;
    private static final int ID_CREATE_BUTTON = 400;
    private static final int BASE_OPTION_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    @Nullable private final Runnable onCreated;

    private UniversalRecyclerView listView;
    private PollEditTextCell questionCell;
    private PollCreateCheckCell activeCheckCell;
    private final List<OptionRow> optionRows = new ArrayList<>();
    private int nextOptionSlotId = 1;
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

        questionCell = new PollEditTextCell(context, false, PollEditTextCell.TYPE_DEFAULT, null, rp);
        questionCell.createErrorTextView();
        questionCell.setTextAndHint("", "Ask a question", false);
        questionCell.getEditField().setFilters(new InputFilter[] { new InputFilter.LengthFilter(MAX_QUESTION_LEN) });
        questionCell.setText2(String.valueOf(MAX_QUESTION_LEN));
        questionCell.addTextWatcher(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                int left = MAX_QUESTION_LEN - (s != null ? s.length() : 0);
                questionCell.setText2(String.valueOf(Math.max(0, left)));
            }
        });

        optionRows.clear();
        addOptionRow(context, false);
        addOptionRow(context, false);

        activeCheckCell = new PollCreateCheckCell(context, rp);
        activeCheckCell.setTextAndValueAndIconAndCheck(
                "Active Immediately",
                "Visible for student voting right after creation",
                IconBackgroundColors.GREEN,
                R.drawable.msg_notifications,
                activeImmediately
        );
        activeCheckCell.setAnimationsEnabled(true);

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                rp,
                "New Poll",
                "Up to 10 options",
                true,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_CREATE) {
                            submitPoll();
                        }
                    }
                },
                null
        );
        listView = chrome.listView;

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            menu.addItem(MENU_CREATE, R.drawable.ic_ab_done, rp);
        }

        fragmentView = chrome.rootView;
        return chrome.rootView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }

    private void addOptionRow(Context context, boolean requestFocus) {
        if (optionRows.size() >= MAX_OPTIONS) {
            return;
        }
        int slotId = nextOptionSlotId++;
        Theme.ResourcesProvider rp = getResourceProvider();
        final OptionRow row = new OptionRow(slotId, null);
        PollEditTextCell cell = new PollEditTextCell(context, false, PollEditTextCell.TYPE_DEFAULT, v -> removeOptionRow(row), rp);
        cell.getEditField().setFilters(new InputFilter[] { new InputFilter.LengthFilter(MAX_OPTION_LEN) });
        cell.setTextAndHint("", "Option " + (optionRows.size() + 1), true);
        if (cell.moveImageView != null) {
            cell.moveImageView.setVisibility(View.GONE);
        }
        row.cell = cell;
        optionRows.add(row);
        syncOptionHintsAndDeleteVisibility();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        if (requestFocus) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!destroyed && row.cell != null) {
                    row.cell.getEditField().requestFocus();
                    AndroidUtilities.showKeyboard(row.cell.getEditField());
                }
            }, 60);
        }
    }

    private void removeOptionRow(OptionRow target) {
        if (optionRows.size() <= MIN_OPTIONS) {
            return;
        }
        optionRows.remove(target);
        syncOptionHintsAndDeleteVisibility();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void syncOptionHintsAndDeleteVisibility() {
        boolean canDelete = optionRows.size() > MIN_OPTIONS;
        for (int i = 0; i < optionRows.size(); i++) {
            PollEditTextCell cell = optionRows.get(i).cell;
            if (cell == null) continue;
            cell.getEditField().setHint("Option " + (i + 1));
            if (cell.deleteImageView != null) {
                cell.deleteImageView.setVisibility(canDelete ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        items.add(UItem.asHeader("Poll Question"));
        items.add(UItem.asCustom(ID_QUESTION_CELL, questionCell));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Answer Options"));
        for (int i = 0; i < optionRows.size(); i++) {
            OptionRow row = optionRows.get(i);
            items.add(UItem.asCustom(BASE_OPTION_ID + row.slotId, row.cell));
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
        items.add(UItem.asShadow("Minimum 2 options, maximum 10 options."));

        items.add(UItem.asHeader("Settings"));
        items.add(UItem.asCustom(ID_ACTIVE_TOGGLE, activeCheckCell));
        items.add(UItem.asShadow(null));

        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_CREATE_BUTTON,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                R.drawable.ic_ab_done,
                saving ? "Creating Poll..." : "Create Poll",
                "Publish to EVE Announcements & Polls",
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
            submitPoll();
        }
    }

    private void submitPoll() {
        if (saving) return;
        String question = questionCell != null && questionCell.getText() != null
                ? questionCell.getText().trim()
                : "";
        List<String> opts = new ArrayList<>();
        for (OptionRow row : optionRows) {
            if (row.cell != null && row.cell.getText() != null) {
                String trimmed = row.cell.getText().trim();
                if (!trimmed.isEmpty()) {
                    opts.add(trimmed);
                }
            }
        }
        if (TextUtils.isEmpty(question)) {
            BulletinFactory.of(this).createErrorBulletin("Enter a poll question.").show();
            return;
        }
        if (opts.size() < MIN_OPTIONS) {
            BulletinFactory.of(this).createErrorBulletin("Enter at least 2 non-empty options.").show();
            return;
        }

        saving = true;
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }

        final boolean activeFlag = activeImmediately;
        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                if (destroyed) return;
                saving = false;
                AndroidUtilities.hideKeyboard(fragmentView);
                BulletinFactory.of(EveAdminPollCreateFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, "Poll created").show();
                if (onCreated != null) {
                    onCreated.run();
                }
                finishFragment();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                saving = false;
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
                BulletinFactory.of(EveAdminPollCreateFragment.this)
                        .createErrorBulletin(message != null ? message : "Failed to create poll.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.createPoll(token, question, opts, activeFlag);
            repo.postSuccess(cb, null);
        });
    }

    private static final class OptionRow {
        final int slotId;
        PollEditTextCell cell;

        OptionRow(int slotId, PollEditTextCell cell) {
            this.slotId = slotId;
            this.cell = cell;
        }
    }
}
