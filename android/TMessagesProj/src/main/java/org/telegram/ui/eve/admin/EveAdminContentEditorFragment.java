package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
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

import java.util.ArrayList;

/**
 * Full-screen Telegram Card Mode Content Editor for About EVE, Privacy Policy,
 * Terms of Service, and Floating Community Link.
 */
public class EveAdminContentEditorFragment extends BaseFragment {

    public static final String MODE_ABOUT = "about";
    public static final String MODE_PRIVACY = "privacy";
    public static final String MODE_TERMS = "terms";
    public static final String MODE_FLOATING_LINK = "floating_link";

    private static final int MENU_SAVE = 1;
    private static final int MENU_REFRESH = 2;

    private static final int ID_RETRY = 101;
    private static final int ID_TITLE_INPUT = 201;
    private static final int ID_BODY_INPUT = 202;
    private static final int ID_LINK_INPUT = 203;
    private static final int ID_SAVE_ROW = 301;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private final String mode;
    private final String screenLabel;

    private UniversalRecyclerView listView;
    private EditTextBoldCursor titleInput;
    private EditTextBoldCursor bodyInput;
    private EditTextBoldCursor linkInput;
    private LinearLayout titleContainer;
    private LinearLayout bodyContainer;
    private LinearLayout linkContainer;

    private boolean loading = true;
    private boolean saving = false;
    private boolean destroyed = false;
    private String errorText = "";

    public EveAdminContentEditorFragment(String mode, String screenLabel) {
        this.mode = mode != null ? mode : MODE_ABOUT;
        this.screenLabel = screenLabel != null && !screenLabel.isEmpty() ? screenLabel : "Edit Content";
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

        if (isFloatingLinkMode()) {
            linkContainer = buildFieldContainer(context);
            linkInput = buildInput(context, rp, "https://... (leave empty to hide)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
            linkContainer.addView(linkInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        } else {
            titleContainer = buildFieldContainer(context);
            titleInput = buildInput(context, rp, "Page Title", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, false);
            titleInput.setText(screenLabel);
            titleContainer.addView(titleInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            bodyContainer = buildFieldContainer(context);
            bodyInput = buildInput(context, rp, "Markdown or plain text content...", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, true);
            bodyContainer.addView(bodyInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                rp,
                screenLabel,
                isFloatingLinkMode() ? "Home floating shortcut" : "Hosted page copy",
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
            menu.addItem(MENU_SAVE, R.drawable.ic_ab_done, rp);
            menu.addItem(MENU_REFRESH, R.drawable.msg_retry, rp);
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

    private boolean isFloatingLinkMode() {
        return MODE_FLOATING_LINK.equals(mode);
    }

    private LinearLayout buildFieldContainer(Context context) {
        LinearLayout ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(18), dp(10), dp(18), dp(12));
        return ll;
    }

    private EditTextBoldCursor buildInput(Context context, Theme.ResourcesProvider rp, String hint, int inputType, boolean multiline) {
        EditTextBoldCursor et = new EditTextBoldCursor(context);
        et.setHint(hint);
        et.setInputType(inputType);
        et.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        et.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        et.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        et.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp));
        et.setBackground(null);
        if (multiline) {
            et.setSingleLine(false);
            et.setMinLines(8);
            et.setGravity(Gravity.TOP | Gravity.START);
            et.setLineSpacing(dp(3), 1f);
        }
        return et;
    }

    private void reload() {
        loading = true;
        errorText = "";
        updateList();

        if (isFloatingLinkMode()) {
            EveAdminRepository.ResultCallback<String> cb = new EveAdminRepository.ResultCallback<String>() {
                @Override
                public void onSuccess(String urlText) {
                    if (destroyed) return;
                    loading = false;
                    errorText = "";
                    if (linkInput != null) {
                        linkInput.setText(urlText != null ? urlText : "");
                    }
                    updateList();
                }

                @Override
                public void onError(String message, int httpStatus) {
                    if (destroyed) return;
                    loading = false;
                    errorText = message != null ? message : "Unable to load floating link.";
                    updateList();
                }
            };
            repo.runWithAdminToken(cb, token -> {
                JSONObject obj = repo.fetchFloatingLink(token);
                String currentUrl = obj != null ? obj.optString("url", "") : "";
                repo.postSuccess(cb, currentUrl);
            });
        } else {
            EveAdminRepository.ResultCallback<JSONObject> cb = new EveAdminRepository.ResultCallback<JSONObject>() {
                @Override
                public void onSuccess(JSONObject obj) {
                    if (destroyed) return;
                    loading = false;
                    errorText = "";
                    if (titleInput != null) {
                        titleInput.setText(obj != null ? obj.optString("title", screenLabel) : screenLabel);
                    }
                    if (bodyInput != null) {
                        bodyInput.setText(obj != null ? obj.optString("body", "") : "");
                    }
                    updateList();
                }

                @Override
                public void onError(String message, int httpStatus) {
                    if (destroyed) return;
                    loading = false;
                    errorText = message != null ? message : "Unable to load content.";
                    updateList();
                }
            };
            repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchAppContent(token, mode)));
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
                    "Retry Loading Content",
                    errorText,
                    "Retry"));
            items.add(UItem.asShadow(null));
        }

        if (loading && TextUtils.isEmpty(errorText)) {
            for (int i = 0; i < 4; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (isFloatingLinkMode()) {
            items.add(UItem.asHeader("Community URL"));
            items.add(UItem.asCustom(ID_LINK_INPUT, linkContainer));
            items.add(UItem.asShadow("Must start with http:// or https://, or leave empty to hide the home shortcut."));
        } else {
            items.add(UItem.asHeader("Page Title"));
            items.add(UItem.asCustom(ID_TITLE_INPUT, titleContainer));
            items.add(UItem.asShadow(null));

            items.add(UItem.asHeader("Page Content"));
            items.add(UItem.asCustom(ID_BODY_INPUT, bodyContainer));
            items.add(UItem.asShadow("Supports plain text or Markdown formatting."));
        }

        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_SAVE_ROW,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                R.drawable.ic_ab_done,
                saving ? "Saving..." : "Save Changes",
                "Publish update to EVE Cloud",
                null));
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
        } else if (item.id == ID_SAVE_ROW) {
            saveChanges();
        }
    }

    private void saveChanges() {
        if (saving) return;

        if (isFloatingLinkMode()) {
            String url = linkInput != null && linkInput.getText() != null ? linkInput.getText().toString().trim() : "";
            if (!url.isEmpty() && !(url.startsWith("https://") || url.startsWith("http://"))) {
                BulletinFactory.of(this).createErrorBulletin("URL must start with https:// or http:// (or leave empty).").show();
                return;
            }
            saving = true;
            updateList();
            EveAdminRepository.ResultCallback<Void> cb = saveCallback("Floating community link saved");
            repo.runWithAdminToken(cb, token -> {
                repo.updateFloatingLink(token, url);
                repo.postSuccess(cb, null);
            });
        } else {
            String title = titleInput != null && titleInput.getText() != null ? titleInput.getText().toString().trim() : "";
            String body = bodyInput != null && bodyInput.getText() != null ? bodyInput.getText().toString() : "";
            if (title.isEmpty()) {
                BulletinFactory.of(this).createErrorBulletin("Title cannot be empty.").show();
                return;
            }
            saving = true;
            updateList();
            EveAdminRepository.ResultCallback<Void> cb = saveCallback(screenLabel + " saved");
            repo.runWithAdminToken(cb, token -> {
                repo.updateAppContent(token, mode, title, body);
                repo.postSuccess(cb, null);
            });
        }
    }

    private EveAdminRepository.ResultCallback<Void> saveCallback(String okMsg) {
        return new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                if (destroyed) return;
                saving = false;
                AndroidUtilities.hideKeyboard(fragmentView);
                updateList();
                BulletinFactory.of(EveAdminContentEditorFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, okMsg).show();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                saving = false;
                updateList();
                BulletinFactory.of(EveAdminContentEditorFragment.this)
                        .createErrorBulletin(message != null ? message : "Save failed.").show();
            }
        };
    }
}
