package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.premium.EvePremiumQrSheet;
import org.telegram.ui.eve.premium.EvePremiumRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Telegram Settings-style Premium Plans & UPI configuration screen.
 */
public class EveAdminPremiumConfigFragment extends BaseFragment {

    private static final int MENU_SAVE = 1;

    private static final int ID_RETRY = 101;
    private static final int ID_ENABLE_PREMIUM = 201;
    private static final int ID_PLAN_NAME = 202;
    private static final int ID_PRICE = 203;
    private static final int ID_LIFETIME = 204;
    private static final int ID_DURATION = 205;
    private static final int ID_DESCRIPTION = 206;
    private static final int ID_BENEFITS = 207;

    private static final int ID_ENABLE_QR = 301;
    private static final int ID_ENABLE_UPI = 302;
    private static final int ID_MERCHANT_VPA = 303;
    private static final int ID_MERCHANT_NAME = 304;
    private static final int ID_SESSION_EXPIRY = 305;

    private static final int ID_PREVIEW_QR = 401;
    private static final int ID_SAVE = 402;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private boolean loading = true;
    private boolean saving = false;
    private boolean dirty = false;
    private boolean destroyed = false;
    private String errorText = "";

    private boolean isEnabled = true;
    private String planName = "EVE Premium";
    private int priceInr = 99;
    private int durationDays = 30;
    private boolean isLifetime = false;
    private String description = "Unlock all eligible tests";
    private List<String> benefits = Collections.singletonList("Unlimited eligible reattempts");
    private boolean qrEnabled = true;
    private boolean upiEnabled = true;
    private int sessionExpiryMinutes = 10;
    private String merchantVpa = "evemocktest@upi";
    private String merchantName = "EVE";

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

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Premium Plans & UPI",
                "Pricing & QR checkout",
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
                            saveChanges();
                        }
                    }
                },
                null
        );
        listView = chrome.listView;

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            menu.addItem(MENU_SAVE, R.drawable.ic_ab_done, getResourceProvider());
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
        loading = true;
        errorText = "";
        updateList();

        EveAdminRepository.ResultCallback<EveAdminModels.PremiumConfigModel> cb =
                new EveAdminRepository.ResultCallback<EveAdminModels.PremiumConfigModel>() {
                    @Override
                    public void onSuccess(EveAdminModels.PremiumConfigModel cfg) {
                        if (destroyed) return;
                        loading = false;
                        errorText = "";
                        applyModel(cfg);
                        dirty = false;
                        updateSubtitle();
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
                        errorText = message != null ? message : "Unable to load premium configuration.";
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchPremiumConfig(token)));
    }

    private void applyModel(EveAdminModels.PremiumConfigModel cfg) {
        if (cfg == null) return;
        isEnabled = cfg.isEnabled;
        planName = cfg.planName;
        priceInr = cfg.priceInr;
        durationDays = cfg.durationDays;
        isLifetime = cfg.isLifetime;
        description = cfg.description;
        benefits = new ArrayList<>(cfg.benefits);
        qrEnabled = cfg.qrEnabled;
        upiEnabled = cfg.upiEnabled;
        sessionExpiryMinutes = cfg.sessionExpiryMinutes;
        merchantVpa = cfg.merchantVpa;
        merchantName = cfg.merchantName;
    }

    private EveAdminModels.PremiumConfigModel toModel() {
        return new EveAdminModels.PremiumConfigModel(
                isEnabled,
                planName,
                priceInr,
                "INR",
                durationDays,
                isLifetime,
                description,
                benefits,
                qrEnabled,
                upiEnabled,
                sessionExpiryMinutes,
                merchantVpa,
                merchantName
        );
    }

    private void updateSubtitle() {
        if (simpleActionBar == null) return;
        if (saving) {
            simpleActionBar.setSubtitle("Saving...");
        } else if (dirty) {
            simpleActionBar.setSubtitle("Unsaved changes");
        } else {
            simpleActionBar.setSubtitle("Pricing & QR checkout");
        }
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        if (!errorText.isEmpty()) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_RETRY,
                    IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                    R.drawable.msg_retry,
                    "Retry Loading Configuration",
                    errorText,
                    "Retry"));
            items.add(UItem.asShadow(null));
        }

        if (loading && TextUtils.isEmpty(errorText) && !dirty) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        items.add(UItem.asHeader("Plan Details"));
        items.add(UItem.asCheck(ID_ENABLE_PREMIUM, "Enable EVE Premium").setChecked(isEnabled));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_PLAN_NAME,
                0xFFB659FF, 0xFF617CFF,
                R.drawable.settings_premium,
                "Plan Name",
                planName,
                null));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_PRICE,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                R.drawable.msg_stats,
                "Price (INR)",
                "₹" + priceInr,
                null));
        items.add(UItem.asCheck(ID_LIFETIME, "Lifetime Validity").setChecked(isLifetime));
        if (!isLifetime) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_DURATION,
                    IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                    R.drawable.msg_calendar2,
                    "Duration",
                    durationDays + " days",
                    null));
        }
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_DESCRIPTION,
                IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                R.drawable.msg_info,
                "Description",
                description,
                null));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_BENEFITS,
                IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                R.drawable.msg_list,
                "Plan Benefits",
                benefits.size() + " item(s) configured",
                String.valueOf(benefits.size())));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("UPI & QR Checkout"));
        items.add(UItem.asCheck(ID_ENABLE_QR, "Enable QR Code Payment").setChecked(qrEnabled));
        items.add(UItem.asCheck(ID_ENABLE_UPI, "Enable Direct UPI Intent").setChecked(upiEnabled));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_MERCHANT_VPA,
                IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                R.drawable.settings_account,
                "Merchant UPI VPA",
                merchantVpa,
                null));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_MERCHANT_NAME,
                IconBackgroundColors.BLUE_ALT.top, IconBackgroundColors.BLUE_ALT.bottom,
                R.drawable.msg_edit,
                "Merchant Display Name",
                merchantName,
                null));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_SESSION_EXPIRY,
                IconBackgroundColors.ORANGE_DEEP.top, IconBackgroundColors.ORANGE_DEEP.bottom,
                R.drawable.msg_recent,
                "Payment Session Expiry",
                sessionExpiryMinutes + " minutes",
                null));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Actions"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_PREVIEW_QR,
                IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                R.drawable.msg_qrcode,
                "Preview Payment QR",
                "Inspect generated UPI QR code",
                null));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_SAVE,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                R.drawable.ic_ab_done,
                saving ? "Saving Changes..." : "Save Configuration",
                dirty ? "Tap to publish updated pricing & UPI settings" : "Up to date with server",
                null));
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_RETRY:
                reload();
                break;
            case ID_ENABLE_PREMIUM:
                isEnabled = !isEnabled;
                markDirtyAndRefreshRow(view, isEnabled);
                break;
            case ID_LIFETIME:
                isLifetime = !isLifetime;
                dirty = true;
                updateSubtitle();
                updateList();
                break;
            case ID_ENABLE_QR:
                qrEnabled = !qrEnabled;
                markDirtyAndRefreshRow(view, qrEnabled);
                break;
            case ID_ENABLE_UPI:
                upiEnabled = !upiEnabled;
                markDirtyAndRefreshRow(view, upiEnabled);
                break;
            case ID_PLAN_NAME:
                promptEditText("Plan Name", planName, InputType.TYPE_CLASS_TEXT, false, val -> {
                    if (!val.isEmpty()) planName = val;
                });
                break;
            case ID_PRICE:
                promptEditText("Price (INR)", String.valueOf(priceInr), InputType.TYPE_CLASS_NUMBER, false, val -> {
                    int parsed = parsePositiveInt(val, priceInr);
                    priceInr = Math.max(1, parsed);
                });
                break;
            case ID_DURATION:
                promptEditText("Duration (Days)", String.valueOf(durationDays), InputType.TYPE_CLASS_NUMBER, false, val -> {
                    int parsed = parsePositiveInt(val, durationDays);
                    durationDays = Math.max(1, parsed);
                });
                break;
            case ID_DESCRIPTION:
                promptEditText("Plan Description", description, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, false, val -> {
                    if (!val.isEmpty()) description = val;
                });
                break;
            case ID_BENEFITS:
                promptEditText(
                        "Plan Benefits (one per line)",
                        TextUtils.join("\n", benefits),
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                        true,
                        val -> {
                            List<String> next = new ArrayList<>();
                            for (String line : Arrays.asList(val.split("\\r?\\n"))) {
                                String trimmed = line.trim();
                                if (!trimmed.isEmpty()) {
                                    next.add(trimmed);
                                }
                            }
                            if (!next.isEmpty()) {
                                benefits = next;
                            }
                        });
                break;
            case ID_MERCHANT_VPA:
                promptEditText("Merchant UPI VPA", merchantVpa, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, false, val -> {
                    if (!val.isEmpty()) merchantVpa = val;
                });
                break;
            case ID_MERCHANT_NAME:
                promptEditText("Merchant Name", merchantName, InputType.TYPE_CLASS_TEXT, false, val -> {
                    if (!val.isEmpty()) merchantName = val;
                });
                break;
            case ID_SESSION_EXPIRY:
                promptEditText("Session Expiry (Minutes)", String.valueOf(sessionExpiryMinutes), InputType.TYPE_CLASS_NUMBER, false, val -> {
                    int parsed = parsePositiveInt(val, sessionExpiryMinutes);
                    sessionExpiryMinutes = Math.max(1, parsed);
                });
                break;
            case ID_PREVIEW_QR:
                showQrPreviewDialog();
                break;
            case ID_SAVE:
                saveChanges();
                break;
        }
    }

    private void markDirtyAndRefreshRow(View view, boolean checked) {
        dirty = true;
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(checked);
        }
        updateSubtitle();
        updateList();
    }

    private interface StringConsumer {
        void accept(String value);
    }

    private void promptEditText(String title, String initial, int inputType, boolean multiline, StringConsumer onApply) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        Theme.ResourcesProvider rp = getResourceProvider();

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(12), dp(24), dp(8));

        EditTextBoldCursor input = new EditTextBoldCursor(activity);
        input.setInputType(inputType);
        if (multiline) {
            input.setSingleLine(false);
            input.setMinLines(4);
            input.setGravity(Gravity.TOP | Gravity.START);
        }
        input.setText(initial);
        input.setSelection(input.getText().length());
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        input.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp));
        layout.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, rp);
        builder.setTitle(title);
        builder.setView(layout);
        builder.setPositiveButton("Apply", (d, w) -> {
            String next = input.getText() != null ? input.getText().toString().trim() : "";
            onApply.accept(next);
            dirty = true;
            updateSubtitle();
            updateList();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showQrPreviewDialog() {
        Activity activity = getParentActivity();
        if (activity == null) return;

        String uri = "upi://pay?pa=" + Uri.encode(merchantVpa)
                + "&pn=" + Uri.encode(merchantName)
                + "&am=" + priceInr
                + "&cu=INR&tn=" + Uri.encode(planName);

        EvePremiumQrSheet.showAdminPreview(this, uri, merchantVpa, planName, priceInr);
    }

    private void saveChanges() {
        if (saving) return;
        saving = true;
        updateSubtitle();
        updateList();

        EveAdminModels.PremiumConfigModel model = toModel();
        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                if (destroyed) return;
                saving = false;
                dirty = false;
                repo.invalidateCache();
                updateSubtitle();
                updateList();
                BulletinFactory.of(EveAdminPremiumConfigFragment.this)
                        .createSimpleBulletin(R.raw.info, "Premium & UPI settings saved").show();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                saving = false;
                updateSubtitle();
                updateList();
                BulletinFactory.of(EveAdminPremiumConfigFragment.this)
                        .createErrorBulletin(message != null ? message : "Save failed").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.updatePremiumConfig(token, model);
            repo.postSuccess(cb, null);
        });
    }

    private static int parsePositiveInt(String val, int fallback) {
        try {
            int parsed = Integer.parseInt(val.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
