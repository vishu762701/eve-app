/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eve.app.ui.settings.PrivacyPreferences;
import com.eve.app.ui.settings.TelegramPrivacyDelegate;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Batch 3 (Refs 10, 11, 12): Restored original Telegram PrivacySettingsActivity
 * (DrKLO/Telegram@21f7d109da6ab3506c9e9577f98c6a38771ab335) preserving upstream
 * RecyclerListView + LinearLayoutManager + ListAdapter (6 view types: TextSettingsCell,
 * TextInfoPrivacyCell, HeaderCell, TextCheckCell, ShadowSectionCell, TextCell),
 * setSections() rounded cards, updateRows(), PrivacyControlActivity & SessionsActivity
 * navigation, and honest EVE bindings via TelegramPrivacyDelegate.
 */
public class PrivacySettingsActivity extends BaseFragment {

    private ListAdapter listAdapter;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private TelegramPrivacyDelegate eveDelegate;

    private int securitySectionRow;
    private int passcodeRow;
    private int passkeysRow;
    private int emailLoginRow;
    private int sessionsRow;
    private int sessionsDetailRow;

    private int privacySectionRow;
    private int phoneNumberRow;
    private int lastSeenRow;
    private int profilePhotoRow;
    private int birthdayRow;
    private int bioRow;
    private int groupsRow;
    private int privacyShadowRow;

    private int advancedSectionRow;
    private int deleteAccountRow;
    private int deleteAccountDetailRow;

    private int contactsSectionRow;
    private int contactsDeleteRow;
    private int contactsSyncRow;
    private int contactsSuggestRow;
    private int contactsDetailRow;

    private int secretSectionRow;
    private int secretWebpageRow;
    private int privacyPolicyRow;
    private int secretDetailRow;

    private int rowCount;

    public void setEveDelegate(TelegramPrivacyDelegate delegate) {
        this.eveDelegate = delegate;
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        updateRows(false);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        eveDelegate = null;
        listView = null;
        listAdapter = null;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Privacy and Security");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listAdapter = new ListAdapter(context);

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        });
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutAnimation(null);
        listView.setItemAnimator(null);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setAdapter(listAdapter);

        listView.setOnItemClickListener((view, position) -> {
            if (!view.isEnabled()) {
                return;
            }
            if (position == passcodeRow) {
                if (eveDelegate != null) eveDelegate.onPasscodeClicked();
            } else if (position == passkeysRow) {
                if (eveDelegate != null) {
                    eveDelegate.onBiometricClicked();
                    updateRows(true);
                }
            } else if (position == emailLoginRow) {
                if (eveDelegate != null) eveDelegate.onLoginEmailClicked();
            } else if (position == sessionsRow) {
                presentFragment(new SessionsActivity(eveDelegate));
            } else if (position == phoneNumberRow) {
                presentFragment(new PrivacyControlActivity(
                    PrivacyPreferences.RULE_PHONE,
                    "Phone Number",
                    "Who can see my phone number?",
                    "Control whether your phone number is visible on your EVE profile.",
                    eveDelegate
                ));
            } else if (position == lastSeenRow) {
                presentFragment(new PrivacyControlActivity(
                    PrivacyPreferences.RULE_ACTIVITY,
                    "Exam Activity & Streak",
                    "Who can see my exam streak?",
                    "Control whether other EVE learners can view your study streak and recent exam activity.",
                    eveDelegate
                ));
            } else if (position == profilePhotoRow) {
                presentFragment(new PrivacyControlActivity(
                    PrivacyPreferences.RULE_PROFILE_PHOTO,
                    "Profile Photos",
                    "Who can see my profile photo?",
                    "Control whether your profile photo appears on public leaderboards and shared profile links.",
                    eveDelegate
                ));
            } else if (position == birthdayRow) {
                presentFragment(new PrivacyControlActivity(
                    PrivacyPreferences.RULE_BIRTHDAY,
                    "Birthday",
                    "Who can see my birthday?",
                    "Control whether your birthday is visible on your EVE profile.",
                    eveDelegate
                ));
            } else if (position == bioRow) {
                presentFragment(new PrivacyControlActivity(
                    PrivacyPreferences.RULE_BIO,
                    "Bio",
                    "Who can see my bio?",
                    "Control whether your bio is visible on your EVE profile.",
                    eveDelegate
                ));
            } else if (position == groupsRow) {
                presentFragment(new PrivacyControlActivity(
                    PrivacyPreferences.RULE_LEADERBOARD,
                    "Leaderboard Visibility",
                    "Who can see my leaderboard rank?",
                    "Control whether your display name and rank appear on EVE public leaderboards.",
                    eveDelegate
                ));
            } else if (position == deleteAccountRow) {
                if (eveDelegate != null) eveDelegate.onDeleteAccountClicked();
            } else if (position == contactsDeleteRow) {
                showClearSearchConfirmDialog();
            } else if (position == contactsSyncRow) {
                if (eveDelegate != null) {
                    boolean next = !eveDelegate.isAutoSyncProgressEnabled();
                    eveDelegate.setAutoSyncProgressEnabled(next);
                    if (view instanceof TextCheckCell) {
                        ((TextCheckCell) view).setChecked(next);
                    }
                }
            } else if (position == contactsSuggestRow) {
                if (eveDelegate != null) {
                    boolean next = !eveDelegate.isSuggestRecentExamsEnabled();
                    eveDelegate.setSuggestRecentExamsEnabled(next);
                    if (view instanceof TextCheckCell) {
                        ((TextCheckCell) view).setChecked(next);
                    }
                }
            } else if (position == secretWebpageRow) {
                if (eveDelegate != null) {
                    boolean next = !eveDelegate.isScreenshotProtectionEnabled();
                    eveDelegate.setScreenshotProtectionEnabled(next);
                    if (view instanceof TextCheckCell) {
                        ((TextCheckCell) view).setChecked(next);
                    }
                }
            } else if (position == privacyPolicyRow) {
                if (eveDelegate != null) eveDelegate.onPrivacyPolicyClicked();
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(frameLayout, (v, insets) -> {
            if (listView != null) {
                int bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime()).bottom;
                listView.setPadding(0, 0, 0, AndroidUtilities.dp(24) + bottomInset);
            }
            return insets;
        });

        return fragmentView;
    }

    public AlertDialog showClearSearchConfirmDialog() {
        Context context = getParentActivity();
        if (context == null) return null;
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle("Clear Search History");
        builder.setMessage("Remove all recent exam search suggestions stored on this device?");
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton("Clear", (dialog, which) -> {
            if (eveDelegate != null) {
                eveDelegate.onClearSearchHistoryConfirmed();
            }
        });
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        return dialog;
    }

    public void updateRows() {
        updateRows(true);
    }

    public void updateRows(boolean notify) {
        rowCount = 0;

        // Ref 12: Security section
        securitySectionRow = rowCount++;
        passcodeRow = rowCount++;
        passkeysRow = rowCount++;
        emailLoginRow = rowCount++;
        sessionsRow = rowCount++;
        sessionsDetailRow = rowCount++;

        // Ref 11: Privacy section
        privacySectionRow = rowCount++;
        phoneNumberRow = rowCount++;
        lastSeenRow = rowCount++;
        profilePhotoRow = rowCount++;
        birthdayRow = rowCount++;
        bioRow = rowCount++;
        groupsRow = rowCount++;
        privacyShadowRow = rowCount++;

        // Ref 10: Delete my account section
        advancedSectionRow = rowCount++;
        deleteAccountRow = rowCount++;
        deleteAccountDetailRow = rowCount++;

        // Ref 10: Data & Suggestions section
        contactsSectionRow = rowCount++;
        contactsDeleteRow = rowCount++;
        contactsSyncRow = rowCount++;
        contactsSuggestRow = rowCount++;
        contactsDetailRow = rowCount++;

        // Ref 10: Exam Security & Policy section
        secretSectionRow = rowCount++;
        secretWebpageRow = rowCount++;
        privacyPolicyRow = rowCount++;
        secretDetailRow = rowCount++;

        if (listAdapter != null && notify) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == passcodeRow || position == passkeysRow || position == emailLoginRow || position == sessionsRow
                || position == phoneNumberRow || position == lastSeenRow || position == profilePhotoRow
                || position == birthdayRow || position == bioRow || position == groupsRow
                || position == deleteAccountRow || position == contactsDeleteRow
                || position == contactsSyncRow || position == contactsSuggestRow
                || position == secretWebpageRow || position == privacyPolicyRow;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new TextSettingsCell(mContext);
                    break;
                case 1:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case 2:
                    view = new HeaderCell(mContext);
                    break;
                case 4:
                    view = new ShadowSectionCell(mContext);
                    break;
                case 5:
                    view = new TextCell(mContext);
                    break;
                case 3:
                default:
                    view = new TextCheckCell(mContext);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 0: {
                    TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                    textCell.setBetterLayout(true);
                    if (position == phoneNumberRow) {
                        String val = eveDelegate != null ? eveDelegate.getRuleVisibility(PrivacyPreferences.RULE_PHONE) : PrivacyPreferences.VISIBILITY_ONLY_ME;
                        textCell.setTextAndValue("Phone Number", val, true);
                    } else if (position == lastSeenRow) {
                        String val = eveDelegate != null ? eveDelegate.getRuleVisibility(PrivacyPreferences.RULE_ACTIVITY) : PrivacyPreferences.VISIBILITY_EVERYBODY;
                        textCell.setTextAndValue("Exam Activity & Streak", val, true);
                    } else if (position == profilePhotoRow) {
                        String val = eveDelegate != null ? eveDelegate.getRuleVisibility(PrivacyPreferences.RULE_PROFILE_PHOTO) : PrivacyPreferences.VISIBILITY_EVERYBODY;
                        textCell.setTextAndValue("Profile Photos", val, true);
                    } else if (position == birthdayRow) {
                        String val = eveDelegate != null ? eveDelegate.getRuleVisibility(PrivacyPreferences.RULE_BIRTHDAY) : PrivacyPreferences.VISIBILITY_EVERYBODY;
                        textCell.setTextAndValue("Birthday", val, true);
                    } else if (position == bioRow) {
                        String val = eveDelegate != null ? eveDelegate.getRuleVisibility(PrivacyPreferences.RULE_BIO) : PrivacyPreferences.VISIBILITY_EVERYBODY;
                        textCell.setTextAndValue("Bio", val, true);
                    } else if (position == groupsRow) {
                        String val = eveDelegate != null ? eveDelegate.getRuleVisibility(PrivacyPreferences.RULE_LEADERBOARD) : PrivacyPreferences.VISIBILITY_EVERYBODY;
                        textCell.setTextAndValue("Leaderboard Visibility", val, false);
                    } else if (position == deleteAccountRow) {
                        textCell.setTextAndValue("Delete My Account", "Permanent", false);
                    } else if (position == contactsDeleteRow) {
                        textCell.setText("Clear Search History", true);
                    } else if (position == privacyPolicyRow) {
                        textCell.setText(LocaleController.getString(R.string.PrivacyPolicy), false);
                    }
                    break;
                }
                case 1: {
                    TextInfoPrivacyCell privacyCell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == sessionsDetailRow) {
                        privacyCell.setText("Review the device where you are logged in to your EVE account.");
                    } else if (position == privacyShadowRow) {
                        privacyCell.setText("You can restrict which learners can view your profile details and leaderboard rank.");
                    } else if (position == deleteAccountDetailRow) {
                        privacyCell.setText("Deleting your EVE account permanently erases your profile, test attempts, results, and leaderboard entries.");
                    } else if (position == contactsDetailRow) {
                        privacyCell.setText("Display recently practiced exams at the top of search for quick access.");
                    } else if (position == secretDetailRow) {
                        privacyCell.setText("Exam Screenshot Protection blocks screen captures during active mock tests.");
                    }
                    break;
                }
                case 2: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == securitySectionRow) {
                        headerCell.setText("Security");
                    } else if (position == privacySectionRow) {
                        headerCell.setText("Privacy");
                    } else if (position == advancedSectionRow) {
                        headerCell.setText("Delete my account");
                    } else if (position == contactsSectionRow) {
                        headerCell.setText("Data & Suggestions");
                    } else if (position == secretSectionRow) {
                        headerCell.setText("Exam Security");
                    }
                    break;
                }
                case 3: {
                    TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                    if (position == contactsSyncRow) {
                        boolean enabled = eveDelegate == null || eveDelegate.isAutoSyncProgressEnabled();
                        checkCell.setTextAndCheck("Auto-Sync Progress", enabled, true);
                    } else if (position == contactsSuggestRow) {
                        boolean enabled = eveDelegate == null || eveDelegate.isSuggestRecentExamsEnabled();
                        checkCell.setTextAndCheck("Suggest Recent Exams", enabled, false);
                    } else if (position == secretWebpageRow) {
                        boolean enabled = eveDelegate == null || eveDelegate.isScreenshotProtectionEnabled();
                        checkCell.setTextAndCheck("Exam Screenshot Protection", enabled, true);
                    }
                    break;
                }
                case 5: {
                    TextCell textCell = (TextCell) holder.itemView;
                    if (position == passcodeRow) {
                        String val = eveDelegate != null ? eveDelegate.getPasscodeStatus() : "Off";
                        textCell.setTextAndValueAndIcon("Passcode Lock", val, com.eve.app.R.drawable.ic_lock_header, true);
                    } else if (position == passkeysRow) {
                        String val = eveDelegate != null ? eveDelegate.getBiometricStatus() : "Off";
                        textCell.setTextAndValueAndIcon("Biometric Unlock", val, com.eve.app.R.drawable.ic_key, true);
                    } else if (position == emailLoginRow) {
                        String val = eveDelegate != null ? eveDelegate.getLoginEmail() : "Signed-in account";
                        textCell.setTextAndValueAndIcon("Login Email", val, com.eve.app.R.drawable.ic_email, true);
                    } else if (position == sessionsRow) {
                        String val = eveDelegate != null ? eveDelegate.getActiveSessionCount() : "1";
                        textCell.setTextAndValueAndIcon("Devices", val, R.drawable.settings_devices, false);
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == phoneNumberRow || position == lastSeenRow || position == profilePhotoRow
                || position == birthdayRow || position == bioRow || position == groupsRow
                || position == deleteAccountRow || position == contactsDeleteRow || position == privacyPolicyRow) {
                return 0;
            } else if (position == sessionsDetailRow || position == privacyShadowRow
                || position == deleteAccountDetailRow || position == contactsDetailRow || position == secretDetailRow) {
                return 1;
            } else if (position == securitySectionRow || position == privacySectionRow
                || position == advancedSectionRow || position == contactsSectionRow || position == secretSectionRow) {
                return 2;
            } else if (position == contactsSyncRow || position == contactsSuggestRow || position == secretWebpageRow) {
                return 3;
            } else if (position == passcodeRow || position == passkeysRow || position == emailLoginRow || position == sessionsRow) {
                return 5;
            }
            return 0;
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextSettingsCell.class, HeaderCell.class, TextCheckCell.class, TextCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrack));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackChecked));
        return themeDescriptions;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void addThemeColorKeys(HashSet<Integer> keys) {
        super.addThemeColorKeys(keys);
        keys.add(Theme.key_windowBackgroundWhiteGrayText2);
        keys.add(Theme.key_windowBackgroundWhiteBlackText);
        keys.add(Theme.key_windowBackgroundWhiteGrayText4);
        keys.add(Theme.key_windowBackgroundGrayShadow);
        keys.add(Theme.key_windowBackgroundWhiteBlueHeader);
        keys.add(Theme.key_windowBackgroundWhiteValueText);
        keys.add(Theme.key_divider);
        keys.add(Theme.key_listSelector);
        keys.add(Theme.key_actionBarDefaultSelector);
        keys.add(Theme.key_actionBarDefaultTitle);
        keys.add(Theme.key_actionBarDefaultIcon);
        keys.add(Theme.key_actionBarDefault);
    }
}
