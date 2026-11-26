package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
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
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen Student Profile & Account Management screen for EVE Admin Control Center.
 */
public class EveAdminStudentDetailFragment extends BaseFragment {

    private static final int MENU_REFRESH = 1;

    private static final int ID_INFO_EMAIL = 101;
    private static final int ID_INFO_EVE_ID = 102;
    private static final int ID_INFO_USERNAME = 103;
    private static final int ID_INFO_PHONE = 104;
    private static final int ID_INFO_CATEGORY = 105;
    private static final int ID_INFO_BIO = 106;
    private static final int ID_INFO_LAST_ACTIVE = 107;
    private static final int ID_INFO_JOINED = 108;

    private static final int ID_ACT_TOGGLE_DISABLE = 201;
    private static final int ID_ACT_GRANT_PREMIUM = 202;
    private static final int ID_ACT_EXTEND_PREMIUM = 203;
    private static final int ID_ACT_REVOKE_PREMIUM = 204;

    private static final int ID_ATTEMPTS_RETRY = 301;
    private static final int BASE_ATTEMPT_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private final Runnable onStudentMutated;
    private EveAdminModels.AdminStudentItem student;
    private EveAdminModels.AdminProfile adminProfile;

    private UniversalRecyclerView listView;
    private StudentHeaderView headerView;
    private final List<EveAdminModels.StudentAttemptItem> attempts = new ArrayList<>();
    private boolean loadingAttempts = true;
    private String attemptsError = "";
    private boolean destroyed = false;

    public EveAdminStudentDetailFragment(EveAdminModels.AdminStudentItem student, Runnable onStudentMutated) {
        this.student = student;
        this.onStudentMutated = onStudentMutated;
    }

    @Override
    public boolean onFragmentCreate() {
        if (!EveAdminConfig.isAdminAvailable() || student == null) {
            return false;
        }
        EveAdminModels.DashboardSnapshot cached = repo.getCachedSnapshot();
        if (cached != null) {
            adminProfile = cached.adminProfile;
        }
        return super.onFragmentCreate();
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

        headerView = new StudentHeaderView(context, getResourceProvider());
        headerView.bind(student);

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Student Profile",
                null,
                true,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_REFRESH) {
                            loadAttempts();
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

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            menu.addItem(MENU_REFRESH, R.drawable.msg_retry, getResourceProvider());
        }

        fragmentView = chrome.rootView;
        if (adminProfile == null) {
            repo.loadDashboardAsync(new EveAdminRepository.ResultCallback<EveAdminModels.DashboardSnapshot>() {
                @Override
                public void onSuccess(EveAdminModels.DashboardSnapshot data) {
                    if (!destroyed && data != null) {
                        adminProfile = data.adminProfile;
                        updateList();
                    }
                }

                @Override
                public void onError(String message, int httpStatus) {
                }
            });
        }
        loadAttempts();
        return chrome.rootView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }

    private void loadAttempts() {
        if (destroyed || student == null) {
            return;
        }
        loadingAttempts = true;
        attemptsError = "";
        updateList();
        EveAdminRepository.ResultCallback<List<EveAdminModels.StudentAttemptItem>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.StudentAttemptItem>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.StudentAttemptItem> data) {
                        if (destroyed) return;
                        loadingAttempts = false;
                        attemptsError = "";
                        attempts.clear();
                        if (data != null) {
                            attempts.addAll(data);
                        }
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loadingAttempts = false;
                        attemptsError = message != null ? message : "Unable to load test attempts.";
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchStudentAttempts(token, student.id)));
    }

    private void updateList() {
        if (headerView != null && student != null) {
            headerView.bind(student);
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        // Header card
        items.add(UItem.asCustom(headerView));
        items.add(UItem.asShadow(null));

        // Card 1: Account Information
        items.add(UItem.asHeader("Account Information"));
        items.add(TextDetailCell.Factory.of(ID_INFO_EMAIL,
                !student.email.isEmpty() ? student.email : "Not set",
                "Email (tap to copy)"));
        items.add(TextDetailCell.Factory.of(ID_INFO_EVE_ID,
                !student.eveId.isEmpty() ? student.eveId : "Not assigned",
                "EVE ID (tap to copy)"));
        items.add(TextDetailCell.Factory.of(ID_INFO_USERNAME,
                !student.username.isEmpty() ? "@" + student.username : "Not set",
                student.username.isEmpty() ? "Username" : "Username (tap to copy)"));
        if (!student.phone.isEmpty()) {
            items.add(TextDetailCell.Factory.of(ID_INFO_PHONE,
                    student.phone,
                    "Phone (tap to copy)"));
        }
        items.add(TextDetailCell.Factory.of(ID_INFO_CATEGORY,
                student.category,
                "Exam Category"));
        if (!student.bio.isEmpty()) {
            items.add(TextDetailCell.Factory.of(ID_INFO_BIO,
                    student.bio,
                    "Bio"));
        }
        items.add(TextDetailCell.Factory.of(ID_INFO_LAST_ACTIVE,
                EveAdminUiChrome.formatDateTime(student.lastActive),
                "Last Active"));
        if (student.createdAt > 0) {
            items.add(TextDetailCell.Factory.of(ID_INFO_JOINED,
                    EveAdminUiChrome.formatDate(student.createdAt),
                    "Joined Date"));
        }
        items.add(UItem.asShadow(null));

        // Card 2: Account Actions
        boolean canManageUsers = adminProfile == null || EveAdminConfig.hasRight(adminProfile, EveAdminModels.RIGHT_MANAGE_USERS);
        boolean canManagePremium = adminProfile == null || EveAdminConfig.hasRight(adminProfile, EveAdminModels.RIGHT_MANAGE_PREMIUM);
        if (canManageUsers || canManagePremium) {
            items.add(UItem.asHeader("Account Actions"));
            if (canManageUsers) {
                IconBackgroundColors toggleBg = student.disabled ? IconBackgroundColors.GREEN : IconBackgroundColors.RED;
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_ACT_TOGGLE_DISABLE,
                        toggleBg.top, toggleBg.bottom,
                        student.disabled ? R.drawable.settings_account : R.drawable.msg_block,
                        student.disabled ? "Enable Account" : "Suspend Account",
                        student.disabled ? "Restore student login and test access" : "Block login and mock test access",
                        null));
            }
            if (canManagePremium) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_ACT_GRANT_PREMIUM,
                        0xFFB659FF, 0xFF617CFF,
                        R.drawable.settings_premium,
                        "Grant EVE Premium (30 Days)",
                        "Activate 30-day membership",
                        null));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_ACT_EXTEND_PREMIUM,
                        IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                        R.drawable.msg_add,
                        "Extend Premium (+30 Days)",
                        "Add 30 days to current subscription",
                        null));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_ACT_REVOKE_PREMIUM,
                        IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                        R.drawable.msg_delete,
                        "Revoke EVE Premium",
                        "Remove active premium membership",
                        null));
            }
            items.add(UItem.asShadow(null));
        }

        // Card 3: Test Attempts
        items.add(UItem.asHeader("Test Attempts" + (attempts.isEmpty() ? "" : " (" + attempts.size() + ")")));
        if (!attemptsError.isEmpty()) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_ATTEMPTS_RETRY,
                    IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                    R.drawable.msg_retry,
                    "Retry Loading Attempts",
                    attemptsError,
                    "Retry"));
        } else if (loadingAttempts) {
            items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
        } else if (attempts.isEmpty()) {
            items.add(UItem.asShadow("No test attempts yet."));
        } else {
            for (int i = 0; i < attempts.size(); i++) {
                EveAdminModels.StudentAttemptItem a = attempts.get(i);
                String sub = String.format(Locale.US, "✓ %d • ✗ %d • %s",
                        a.correct, a.wrong, EveAdminUiChrome.formatDateTime(a.timestamp));
                String scoreVal = String.format(Locale.US, "%.1f / %d", a.score, a.total);
                items.add(SettingsActivity.SettingCell.Factory.of(
                        BASE_ATTEMPT_ID + i,
                        IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                        R.drawable.msg_stats,
                        a.examName,
                        sub,
                        scoreVal));
            }
            items.add(UItem.asShadow(null));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_INFO_EMAIL:
                copyField("Email", student.email);
                break;
            case ID_INFO_EVE_ID:
                copyField("EVE ID", student.eveId);
                break;
            case ID_INFO_USERNAME:
                if (!student.username.isEmpty()) {
                    copyField("Username", "@" + student.username);
                }
                break;
            case ID_INFO_PHONE:
                copyField("Phone", student.phone);
                break;
            case ID_ACT_TOGGLE_DISABLE:
                confirmToggleDisabled();
                break;
            case ID_ACT_GRANT_PREMIUM:
                runPremiumAction("Granting 30 days EVE Premium...",
                        token -> repo.grantPremium(token, student.id, "EVE Premium", 30, false),
                        "Granted 30 days EVE Premium.");
                break;
            case ID_ACT_EXTEND_PREMIUM:
                runPremiumAction("Extending premium...",
                        token -> repo.extendPremium(token, student.id, 30),
                        "Extended premium by 30 days.");
                break;
            case ID_ACT_REVOKE_PREMIUM:
                confirmRevokePremium();
                break;
            case ID_ATTEMPTS_RETRY:
                loadAttempts();
                break;
        }
    }

    private void copyField(String label, String value) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        AndroidUtilities.addToClipboard(value);
        BulletinFactory.of(this).createCopyBulletin(label + " copied").show();
    }

    private void confirmToggleDisabled() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        final boolean nextDisabled = !student.disabled;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle(nextDisabled ? "Suspend Student Account?" : "Enable Student Account?")
                .setMessage(nextDisabled
                        ? "Suspend " + student.displayName + "? They will be blocked from signing in and taking tests."
                        : "Re-enable " + student.displayName + " and restore full access?")
                .setPositiveButton(nextDisabled ? "Suspend" : "Enable", (d, w) -> {
                    EveAdminRepository.ResultCallback<String> cb = new EveAdminRepository.ResultCallback<String>() {
                        @Override
                        public void onSuccess(String msg) {
                            if (destroyed) return;
                            student = student.withDisabled(nextDisabled);
                            repo.invalidateCache();
                            updateList();
                            BulletinFactory.of(EveAdminStudentDetailFragment.this)
                                    .createSimpleBulletin(R.raw.contact_check, msg).show();
                            if (onStudentMutated != null) {
                                onStudentMutated.run();
                            }
                        }

                        @Override
                        public void onError(String message, int httpStatus) {
                            BulletinFactory.of(EveAdminStudentDetailFragment.this)
                                    .createErrorBulletin(message).show();
                        }
                    };
                    repo.runWithAdminToken(cb, token -> {
                        repo.setStudentDisabled(token, student.id, nextDisabled);
                        repo.postSuccess(cb, nextDisabled ? "Student account suspended." : "Student account enabled.");
                    });
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (nextDisabled) {
            builder.makeRed(AlertDialog.BUTTON_POSITIVE);
        }
        showDialog(builder.create());
    }

    private void confirmRevokePremium() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        showDialog(new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle("Revoke EVE Premium?")
                .setMessage("Remove active EVE Premium access for " + student.displayName + "?")
                .setPositiveButton("Revoke", (d, w) -> runPremiumAction(
                        "Revoking premium...",
                        token -> repo.revokePremium(token, student.id),
                        "Premium access revoked."))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .create());
    }

    private void runPremiumAction(String progressMsg, EveAdminRepository.TokenWork work, String successMsg) {
        EveAdminRepository.ResultCallback<String> cb = new EveAdminRepository.ResultCallback<String>() {
            @Override
            public void onSuccess(String msg) {
                if (destroyed) return;
                repo.invalidateCache();
                BulletinFactory.of(EveAdminStudentDetailFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, msg).show();
                if (onStudentMutated != null) {
                    onStudentMutated.run();
                }
            }

            @Override
            public void onError(String message, int httpStatus) {
                BulletinFactory.of(EveAdminStudentDetailFragment.this)
                        .createErrorBulletin(message).show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            work.run(token);
            repo.postSuccess(cb, successMsg);
        });
    }

    private static final class StudentHeaderView extends LinearLayout implements Theme.Colorable {

        private final Theme.ResourcesProvider resourcesProvider;
        private final AvatarDrawable avatarDrawable;
        private final BackupImageView avatarView;
        private final TextView nameView;
        private final TextView usernameView;
        private final TextView badgeView;
        private boolean isDisabled;

        StudentHeaderView(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(dp(20), dp(18), dp(20), dp(18));

            avatarDrawable = new AvatarDrawable();
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(36));
            addView(avatarView, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

            nameView = new TextView(context);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setGravity(Gravity.CENTER);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            usernameView = new TextView(context);
            usernameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            usernameView.setGravity(Gravity.CENTER);
            usernameView.setSingleLine(true);
            usernameView.setEllipsize(TextUtils.TruncateAt.END);
            addView(usernameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 10));

            badgeView = new TextView(context);
            badgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            badgeView.setTypeface(AndroidUtilities.bold());
            badgeView.setPadding(dp(12), dp(4), dp(12), dp(4));
            addView(badgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

            updateColors();
        }

        void bind(EveAdminModels.AdminStudentItem student) {
            this.isDisabled = student.disabled;
            EveAdminUiChrome.bindStudentAvatar(avatarView, avatarDrawable, student, 72);
            nameView.setText(student.displayName);
            usernameView.setText(!student.username.isEmpty() ? "@" + student.username : "No username");
            badgeView.setText(student.disabled ? "Suspended" : "Active");
            updateColors();
        }

        @Override
        public void updateColors() {
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            usernameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            if (isDisabled) {
                int red = Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);
                badgeView.setTextColor(red);
                badgeView.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.multAlpha(red, 0.14f)));
            } else {
                int activeColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider);
                badgeView.setTextColor(activeColor);
                badgeView.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.multAlpha(activeColor, 0.14f)));
            }
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_windowBackgroundWhiteBlackText);
            colors.add(Theme.key_windowBackgroundWhiteGrayText);
            colors.add(Theme.key_text_RedRegular);
            colors.add(Theme.key_windowBackgroundWhiteBlueText);
        }
    }
}
