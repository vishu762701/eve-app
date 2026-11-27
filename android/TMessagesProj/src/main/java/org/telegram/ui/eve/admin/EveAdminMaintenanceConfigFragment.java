package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Full-screen Telegram Settings-style Maintenance Mode & App Config editor.
 */
public class EveAdminMaintenanceConfigFragment extends BaseFragment {

    private static final int MENU_SAVE = 1;
    private static final int MAX_MESSAGE_CHARS = 1000;
    private static final String DEFAULT_MAINTENANCE_MSG =
            "Eve Mock Test is currently undergoing scheduled maintenance. Please check back shortly.";

    private static final int ID_MAINTENANCE_SWITCH = 101;
    private static final int ID_MIN_VERSION_INPUT = 201;
    private static final int ID_MESSAGE_INPUT = 301;
    private static final int ID_MODE_LIVE = 401;
    private static final int ID_MODE_PAUSED = 402;
    private static final int ID_MODE_DRAFT = 403;
    private static final int ID_SAVE_BUTTON = 501;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private final Runnable onSaved;

    private UniversalRecyclerView listView;
    private LinearLayout minVersionCard;
    private EditTextBoldCursor minVersionInput;
    private LinearLayout messageCard;
    private EditTextBoldCursor messageInput;
    private TextView charCountView;

    private boolean maintenanceMode;
    private int minSupportedVersionCode;
    private String maintenanceMessage;
    private String defaultPublishMode;
    private boolean saving = false;
    private boolean destroyed = false;

    public EveAdminMaintenanceConfigFragment(
            @Nullable EveAdminModels.AppConfigModel initialConfig,
            @Nullable Runnable onSaved) {
        EveAdminModels.AppConfigModel cfg = initialConfig != null
                ? initialConfig
                : new EveAdminModels.AppConfigModel(1, false, DEFAULT_MAINTENANCE_MSG, "paused");
        this.maintenanceMode = cfg.maintenanceMode;
        this.minSupportedVersionCode = Math.max(1, cfg.minSupportedVersionCode);
        this.maintenanceMessage = !cfg.maintenanceMessage.isEmpty() ? cfg.maintenanceMessage : DEFAULT_MAINTENANCE_MSG;
        this.defaultPublishMode = normalizeMode(cfg.defaultPublishMode);
        this.onSaved = onSaved;
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

        buildInputViews(context, rp);

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                rp,
                "Maintenance & App Config",
                maintenanceMode ? "Maintenance Active" : "App is Online",
                true,
                true,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_SAVE) {
                            saveConfig();
                        }
                    }
                },
                null
        );
        listView = chrome.listView;

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            menu.addItem(MENU_SAVE, R.drawable.ic_ab_done, rp);
        }

        fragmentView = chrome.rootView;
        loadLatestConfig();
        return chrome.rootView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }

    private void buildInputViews(Context context, Theme.ResourcesProvider rp) {
        minVersionCard = new LinearLayout(context);
        minVersionCard.setOrientation(LinearLayout.VERTICAL);
        minVersionCard.setPadding(dp(20), dp(12), dp(20), dp(12));

        TextView minLabel = new TextView(context);
        minLabel.setText("Minimum Supported Version Code");
        minLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        minLabel.setTypeface(AndroidUtilities.bold());
        minLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, rp));
        minVersionCard.addView(minLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        minVersionInput = new EditTextBoldCursor(context);
        minVersionInput.setHint("e.g. 73039");
        minVersionInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        minVersionInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        minVersionInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        minVersionInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        minVersionInput.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp));
        minVersionInput.setBackground(null);
        minVersionInput.setText(String.valueOf(minSupportedVersionCode));
        minVersionCard.addView(minVersionInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        messageCard = new LinearLayout(context);
        messageCard.setOrientation(LinearLayout.VERTICAL);
        messageCard.setPadding(dp(20), dp(12), dp(20), dp(12));

        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView msgLabel = new TextView(context);
        msgLabel.setText("Maintenance Banner Message");
        msgLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        msgLabel.setTypeface(AndroidUtilities.bold());
        msgLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, rp));
        headerRow.addView(msgLabel, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        charCountView = new TextView(context);
        charCountView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        charCountView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        headerRow.addView(charCountView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        messageCard.addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        messageInput = new EditTextBoldCursor(context);
        messageInput.setHint(DEFAULT_MAINTENANCE_MSG);
        messageInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        messageInput.setSingleLine(false);
        messageInput.setMinLines(3);
        messageInput.setGravity(Gravity.TOP | Gravity.START);
        messageInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        messageInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        messageInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        messageInput.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp));
        messageInput.setBackground(null);
        messageInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(MAX_MESSAGE_CHARS) });
        messageInput.setText(maintenanceMessage);
        updateCharCounter(maintenanceMessage.length());
        messageInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                maintenanceMessage = s != null ? s.toString() : "";
                updateCharCounter(maintenanceMessage.length());
            }
        });
        messageCard.addView(messageInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void updateCharCounter(int len) {
        if (charCountView != null) {
            charCountView.setText(len + " / " + MAX_MESSAGE_CHARS);
        }
    }

    private void loadLatestConfig() {
        EveAdminRepository.ResultCallback<EveAdminModels.AppConfigModel> cb =
                new EveAdminRepository.ResultCallback<EveAdminModels.AppConfigModel>() {
                    @Override
                    public void onSuccess(EveAdminModels.AppConfigModel data) {
                        if (destroyed || data == null) return;
                        maintenanceMode = data.maintenanceMode;
                        minSupportedVersionCode = Math.max(1, data.minSupportedVersionCode);
                        maintenanceMessage = !data.maintenanceMessage.isEmpty() ? data.maintenanceMessage : DEFAULT_MAINTENANCE_MSG;
                        defaultPublishMode = normalizeMode(data.defaultPublishMode);
                        if (minVersionInput != null) {
                            minVersionInput.setText(String.valueOf(minSupportedVersionCode));
                        }
                        if (messageInput != null) {
                            messageInput.setText(maintenanceMessage);
                        }
                        if (simpleActionBar != null) {
                            simpleActionBar.setSubtitle(maintenanceMode ? "Maintenance Active" : "App is Online");
                        }
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchAppConfig(token)));
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        items.add(UItem.asHeader("Status & Maintenance"));
        UItem maintSwitch = UItem.asButtonCheck(
                ID_MAINTENANCE_SWITCH,
                "Maintenance Mode",
                maintenanceMode ? "Maintenance Active — Students Blocked" : "App is Online");
        maintSwitch.checked = maintenanceMode;
        items.add(maintSwitch);
        items.add(UItem.asShadow("When enabled, student sign-in and mock tests show the maintenance banner below."));

        items.add(UItem.asHeader("Minimum Supported Version"));
        items.add(UItem.asCustom(ID_MIN_VERSION_INPUT, minVersionCard));
        items.add(UItem.asShadow("Students running an app versionCode lower than this number will be prompted to update."));

        items.add(UItem.asHeader("Maintenance Banner"));
        items.add(UItem.asCustom(ID_MESSAGE_INPUT, messageCard));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Default Exam Publish Mode"));
        UItem liveOpt = UItem.asRadio(ID_MODE_LIVE, "Live");
        liveOpt.checked = "live".equals(defaultPublishMode);
        UItem pausedOpt = UItem.asRadio(ID_MODE_PAUSED, "Paused");
        pausedOpt.checked = "paused".equals(defaultPublishMode);
        UItem draftOpt = UItem.asRadio(ID_MODE_DRAFT, "Draft");
        draftOpt.checked = "draft".equals(defaultPublishMode);
        items.add(liveOpt);
        items.add(pausedOpt);
        items.add(draftOpt);
        items.add(UItem.asShadow("Default visibility applied when an exam uses 'Inherit' publish mode."));

        UItem saveBtn = UItem.asButton(ID_SAVE_BUTTON, R.drawable.ic_ab_done, saving ? "Saving..." : "Save Configuration");
        saveBtn.accent = true;
        saveBtn.enabled = !saving;
        items.add(saveBtn);
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_MAINTENANCE_SWITCH:
                maintenanceMode = !maintenanceMode;
                if (simpleActionBar != null) {
                    simpleActionBar.setSubtitle(maintenanceMode ? "Maintenance Active" : "App is Online");
                }
                updateList();
                break;
            case ID_MODE_LIVE:
                defaultPublishMode = "live";
                updateList();
                break;
            case ID_MODE_PAUSED:
                defaultPublishMode = "paused";
                updateList();
                break;
            case ID_MODE_DRAFT:
                defaultPublishMode = "draft";
                updateList();
                break;
            case ID_SAVE_BUTTON:
                saveConfig();
                break;
        }
    }

    private void saveConfig() {
        if (saving || destroyed) return;
        int minVer = 1;
        if (minVersionInput != null && minVersionInput.getText() != null) {
            try {
                minVer = Math.max(1, Integer.parseInt(minVersionInput.getText().toString().trim()));
            } catch (Throwable ignored) {
                BulletinFactory.of(this).createErrorBulletin("Enter a valid minimum version code.").show();
                return;
            }
        }
        String rawMsg = messageInput != null && messageInput.getText() != null
                ? messageInput.getText().toString().trim()
                : maintenanceMessage.trim();
        if (rawMsg.isEmpty()) {
            rawMsg = DEFAULT_MAINTENANCE_MSG;
        }

        saving = true;
        updateList();
        final int finalMinVer = minVer;
        final String finalMsg = rawMsg;
        final String finalMode = normalizeMode(defaultPublishMode);
        final boolean finalMaint = maintenanceMode;

        EveAdminRepository.ResultCallback<EveAdminModels.AppConfigModel> cb =
                new EveAdminRepository.ResultCallback<EveAdminModels.AppConfigModel>() {
                    @Override
                    public void onSuccess(EveAdminModels.AppConfigModel data) {
                        if (destroyed) return;
                        saving = false;
                        BulletinFactory.of(EveAdminMaintenanceConfigFragment.this)
                                .createSimpleBulletin(R.raw.contact_check, "Configuration saved.").show();
                        if (onSaved != null) {
                            onSaved.run();
                        }
                        finishFragment();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        saving = false;
                        updateList();
                        BulletinFactory.of(EveAdminMaintenanceConfigFragment.this).createErrorBulletin(message).show();
                    }
                };
        repo.runWithAdminToken(cb, token -> {
            EveAdminModels.AppConfigModel updated = repo.updateAppConfig(token, finalMinVer, finalMaint, finalMsg, finalMode);
            repo.postSuccess(cb, updated);
        });
    }

    private static String normalizeMode(String raw) {
        String lower = raw != null ? raw.trim().toLowerCase(Locale.US) : "paused";
        if ("live".equals(lower) || "draft".equals(lower) || "paused".equals(lower)) {
            return lower;
        }
        return "paused";
    }
}
