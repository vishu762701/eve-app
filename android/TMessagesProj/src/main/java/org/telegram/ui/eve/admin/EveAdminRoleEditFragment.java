package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Full-screen Admin Role & Permissions Editor modeled on Telegram's ChatRightsEditActivity.
 * Supports Add Admin, Edit Admin, and Owner read-only inspection modes.
 */
public class EveAdminRoleEditFragment extends BaseFragment {

    private static final int MENU_SAVE = 1;

    private static final int ID_HEADER = 100;
    private static final int ID_EMAIL_INPUT = 201;
    private static final int ID_TITLE_INPUT = 202;
    private static final int BASE_RIGHT_ID = 1000;
    private static final int ID_REMOVE_ADMIN = 9001;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    @Nullable
    private final EveAdminModels.AdminMemberItem existing;
    @Nullable
    private final Runnable onSaved;

    private final Set<String> selectedRights = new HashSet<>();
    private UniversalRecyclerView listView;
    private AdminHeaderView headerView;
    private LinearLayout emailRow;
    private EditTextBoldCursor emailInput;
    private LinearLayout titleRow;
    private EditTextBoldCursor titleInput;

    private boolean saving = false;
    private boolean destroyed = false;

    public EveAdminRoleEditFragment(
            @Nullable EveAdminModels.AdminMemberItem existing,
            @Nullable Runnable onSaved) {
        this.existing = existing;
        this.onSaved = onSaved;
        if (existing != null) {
            if (existing.isOwner) {
                for (String r : EveAdminModels.ALL_RIGHTS) {
                    selectedRights.add(r);
                }
            } else {
                selectedRights.addAll(existing.rights);
            }
        }
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

    private boolean isReadOnlyOwner() {
        return existing != null && existing.isOwner;
    }

    @Override
    public View createView(Context context) {
        destroyed = false;
        setHasOwnBackground(true);
        Theme.ResourcesProvider rp = getResourceProvider();

        if (existing != null) {
            headerView = new AdminHeaderView(context, rp);
            headerView.bind(existing);
        }

        if (existing == null) {
            emailRow = makeLabeledFieldContainer(context, "Admin Google Email", rp);
            emailInput = makeField(context, "e.g. moderator@gmail.com",
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, rp);
            emailRow.addView(emailInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        if (!isReadOnlyOwner()) {
            titleRow = makeLabeledFieldContainer(context, "Custom Role Title", rp);
            titleInput = makeField(context, "e.g. Moderator",
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, rp);
            titleInput.setText(existing != null ? existing.customTitle : "Moderator");
            titleRow.addView(titleInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        String screenTitle;
        String screenSub;
        if (isReadOnlyOwner()) {
            screenTitle = "Owner Permissions";
            screenSub = "Permanent full access";
        } else if (existing == null) {
            screenTitle = "Add Admin";
            screenSub = "Assign role & permissions";
        } else {
            screenTitle = "Admin Rights";
            screenSub = existing.email;
        }

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                rp,
                screenTitle,
                screenSub,
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
                            saveAdmin();
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

        if (simpleActionBar != null && !isReadOnlyOwner()) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            menu.addItem(MENU_SAVE, R.drawable.ic_ab_done, rp);
        }

        fragmentView = chrome.rootView;
        return chrome.rootView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        if (headerView != null) {
            items.add(UItem.asCustom(ID_HEADER, headerView));
            items.add(UItem.asShadow(null));
        }

        if (existing == null) {
            items.add(UItem.asHeader("Administrator Details"));
            items.add(UItem.asCustom(ID_EMAIL_INPUT, emailRow));
            items.add(UItem.asCustom(ID_TITLE_INPUT, titleRow));
            items.add(UItem.asShadow(null));
        } else if (!isReadOnlyOwner()) {
            items.add(UItem.asHeader("Role Title"));
            items.add(UItem.asCustom(ID_TITLE_INPUT, titleRow));
            items.add(UItem.asShadow(null));
        }

        items.add(UItem.asHeader("What can this admin do?"));
        for (int i = 0; i < EveAdminModels.ALL_RIGHTS.length; i++) {
            String right = EveAdminModels.ALL_RIGHTS[i];
            UItem row = UItem.asButtonCheck(
                    BASE_RIGHT_ID + i,
                    labelForRight(right),
                    descriptionForRight(right));
            row.checked = isReadOnlyOwner() || selectedRights.contains(right);
            row.enabled = !isReadOnlyOwner() && !saving;
            items.add(row);
        }

        if (isReadOnlyOwner()) {
            items.add(UItem.asShadow("The Owner holds permanent full permissions across EVE Admin Control Center."));
            return;
        }

        items.add(UItem.asShadow("Admins can only grant permissions they personally hold."));

        if (existing != null) {
            UItem removeBtn = UItem.asButton(ID_REMOVE_ADMIN, R.drawable.msg_delete, "Remove Admin");
            removeBtn.red = true;
            removeBtn.enabled = !saving;
            items.add(removeBtn);
            items.add(UItem.asShadow(null));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (saving || isReadOnlyOwner()) {
            return;
        }
        if (item.id == ID_REMOVE_ADMIN && existing != null) {
            confirmRemoveAdmin();
            return;
        }
        int idx = item.id - BASE_RIGHT_ID;
        if (idx >= 0 && idx < EveAdminModels.ALL_RIGHTS.length) {
            String right = EveAdminModels.ALL_RIGHTS[idx];
            if (selectedRights.contains(right)) {
                selectedRights.remove(right);
            } else {
                selectedRights.add(right);
            }
            updateList();
        }
    }

    private void saveAdmin() {
        if (saving || destroyed || isReadOnlyOwner()) {
            return;
        }
        String email = existing != null
                ? existing.email
                : (emailInput != null && emailInput.getText() != null ? emailInput.getText().toString().trim().toLowerCase(Locale.US) : "");
        if (TextUtils.isEmpty(email) || !email.contains("@")) {
            BulletinFactory.of(this).createErrorBulletin("Enter a valid Google email address.").show();
            return;
        }
        String customTitle = titleInput != null && titleInput.getText() != null
                ? titleInput.getText().toString().trim()
                : "";
        if (customTitle.isEmpty()) {
            customTitle = existing == null ? "Moderator" : "Admin";
        }
        final String finalTitle = customTitle;
        final ArrayList<String> rightsToSave = new ArrayList<>();
        for (String r : EveAdminModels.ALL_RIGHTS) {
            if (selectedRights.contains(r)) {
                rightsToSave.add(r);
            }
        }

        saving = true;
        updateList();
        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                if (destroyed) return;
                saving = false;
                repo.invalidateCache();
                BulletinFactory.of(EveAdminRoleEditFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, existing == null ? "Admin added." : "Admin rights saved.").show();
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
                BulletinFactory.of(EveAdminRoleEditFragment.this)
                        .createErrorBulletin(message != null ? message : "Unable to save admin rights.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            if (existing == null) {
                repo.addAdmin(token, email, rightsToSave, finalTitle);
            } else {
                repo.updateAdminRights(token, email, rightsToSave, finalTitle);
            }
            repo.postSuccess(cb, null);
        });
    }

    private void confirmRemoveAdmin() {
        Activity act = getParentActivity();
        if (act == null || existing == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(act, getResourceProvider())
                .setTitle("Remove Admin")
                .setMessage("Remove all admin permissions from " + existing.email + "?")
                .setPositiveButton("Remove", (d, w) -> executeRemoveAdmin())
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE);
        showDialog(builder.create());
    }

    private void executeRemoveAdmin() {
        if (saving || destroyed || existing == null) return;
        saving = true;
        updateList();
        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                if (destroyed) return;
                saving = false;
                repo.invalidateCache();
                BulletinFactory.of(EveAdminRoleEditFragment.this)
                        .createSimpleBulletin(R.raw.ic_delete, "Admin removed.").show();
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
                BulletinFactory.of(EveAdminRoleEditFragment.this)
                        .createErrorBulletin(message != null ? message : "Remove failed.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.removeAdmin(token, existing.email);
            repo.postSuccess(cb, null);
        });
    }

    private static LinearLayout makeLabeledFieldContainer(Context context, String label, Theme.ResourcesProvider rp) {
        LinearLayout ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(20), dp(10), dp(20), dp(10));
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, rp));
        ll.addView(tv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));
        return ll;
    }

    private static EditTextBoldCursor makeField(Context context, String hint, int inputType, Theme.ResourcesProvider rp) {
        EditTextBoldCursor et = new EditTextBoldCursor(context);
        et.setHint(hint);
        et.setInputType(inputType);
        et.setSingleLine(true);
        et.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        et.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        et.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        et.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp));
        et.setBackground(null);
        return et;
    }

    public static String labelForRight(String right) {
        if (right == null) return "Permission";
        switch (right) {
            case EveAdminModels.RIGHT_MANAGE_EXAMS: return "Manage Exams";
            case EveAdminModels.RIGHT_MANAGE_QUESTIONS: return "Manage Question Bank";
            case EveAdminModels.RIGHT_MANAGE_GENERATED_TESTS: return "Manage Mock Tests";
            case EveAdminModels.RIGHT_MANAGE_SYLLABUS: return "Manage Syllabus PDFs";
            case EveAdminModels.RIGHT_MANAGE_USERS: return "Manage Students";
            case EveAdminModels.RIGHT_MANAGE_ADMINS_RIGHTS: return "Manage Admin Team";
            case EveAdminModels.RIGHT_MANAGE_PREMIUM: return "Manage Premium & Orders";
            case EveAdminModels.RIGHT_SEND_BROADCASTS: return "Send Broadcasts";
            case EveAdminModels.RIGHT_MODERATE_FEEDBACK: return "Moderate Reports & Polls";
            case EveAdminModels.RIGHT_MANAGE_CONFIG: return "App Config & Maintenance";
            case EveAdminModels.RIGHT_MANAGE_CONTENT: return "Manage Home & Legal Copy";
            case EveAdminModels.RIGHT_VIEW_ANALYTICS_LOGS: return "View Analytics & Logs";
            case EveAdminModels.RIGHT_EXPORT_DATA: return "Export Exam Data";
            default: return EveAdminUiChrome.formatActionTitle(right);
        }
    }

    public static String descriptionForRight(String right) {
        if (right == null) return "";
        switch (right) {
            case EveAdminModels.RIGHT_MANAGE_EXAMS: return "Create, edit, publish, pause and delete exams";
            case EveAdminModels.RIGHT_MANAGE_QUESTIONS: return "Add, edit, bulk import and AI-format questions";
            case EveAdminModels.RIGHT_MANAGE_GENERATED_TESTS: return "Generate, schedule, publish and delete mock tests";
            case EveAdminModels.RIGHT_MANAGE_SYLLABUS: return "Attach, reassign and remove exam syllabi";
            case EveAdminModels.RIGHT_MANAGE_USERS: return "Inspect student profiles, attempts and suspensions";
            case EveAdminModels.RIGHT_MANAGE_ADMINS_RIGHTS: return "Add administrators and edit delegated rights";
            case EveAdminModels.RIGHT_MANAGE_PREMIUM: return "Configure pricing, UPI QR and grant memberships";
            case EveAdminModels.RIGHT_SEND_BROADCASTS: return "Deliver student broadcasts and push announcements";
            case EveAdminModels.RIGHT_MODERATE_FEEDBACK: return "Manage student polls, flagged questions and feedback";
            case EveAdminModels.RIGHT_MANAGE_CONFIG: return "Toggle maintenance mode and minimum app version";
            case EveAdminModels.RIGHT_MANAGE_CONTENT: return "Edit home banners, floating link and info pages";
            case EveAdminModels.RIGHT_VIEW_ANALYTICS_LOGS: return "Inspect exam analytics, crashes, health and audit logs";
            case EveAdminModels.RIGHT_EXPORT_DATA: return "Export exam and question JSON backups";
            default: return "";
        }
    }

    private static final class AdminHeaderView extends LinearLayout implements Theme.Colorable {
        private final Theme.ResourcesProvider resourcesProvider;
        private final AvatarDrawable avatarDrawable;
        private final BackupImageView avatarView;
        private final TextView titleView;
        private final TextView emailView;
        private final TextView badgeView;
        private boolean isOwner;

        AdminHeaderView(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.resourcesProvider = rp;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(18), dp(14), dp(18), dp(14));

            avatarDrawable = new AvatarDrawable();
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(28));
            addView(avatarView, LayoutHelper.createLinear(56, 56, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

            LinearLayout col = new LinearLayout(context);
            col.setOrientation(VERTICAL);
            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            emailView = new TextView(context);
            emailView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            emailView.setSingleLine(true);
            emailView.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(emailView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

            badgeView = new TextView(context);
            badgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            badgeView.setTypeface(AndroidUtilities.bold());
            badgeView.setPadding(dp(10), dp(4), dp(10), dp(4));
            addView(badgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

            updateColors();
        }

        void bind(EveAdminModels.AdminMemberItem member) {
            this.isOwner = member.isOwner;
            EveAdminUiChrome.bindAvatar(avatarView, avatarDrawable, member.email, member.customTitle, "", 56);
            titleView.setText(member.customTitle);
            emailView.setText(member.email);
            badgeView.setText(member.isOwner ? "Owner" : "Admin");
            updateColors();
        }

        @Override
        public void updateColors() {
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            emailView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            int badgeColor = isOwner
                    ? 0xFF9F55DF
                    : Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider);
            badgeView.setTextColor(badgeColor);
            badgeView.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(badgeColor, 0.14f)));
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_windowBackgroundWhiteBlackText);
            colors.add(Theme.key_windowBackgroundWhiteGrayText);
            colors.add(Theme.key_windowBackgroundWhiteBlueText);
        }
    }
}
