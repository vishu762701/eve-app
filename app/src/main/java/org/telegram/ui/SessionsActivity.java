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
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Supporting upstream Telegram SessionsActivity (DrKLO/Telegram@21f7d109)
 * displaying the real active EVE device session and sign-out action.
 */
public class SessionsActivity extends BaseFragment {

    private final TelegramPrivacyDelegate eveDelegate;
    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private int currentSessionSectionRow;
    private int currentSessionRow;
    private int accountEmailRow;
    private int terminateRow;
    private int terminateDetailRow;
    private int rowCount;

    public SessionsActivity(TelegramPrivacyDelegate eveDelegate) {
        this.eveDelegate = eveDelegate;
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        updateRows();
        return true;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    private void updateRows() {
        rowCount = 0;
        currentSessionSectionRow = rowCount++;
        currentSessionRow = rowCount++;
        accountEmailRow = rowCount++;
        terminateRow = rowCount++;
        terminateDetailRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Devices");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listAdapter = new ListAdapter(context);

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        });
        listView.setVerticalScrollBarEnabled(false);
        listView.setItemAnimator(null);
        listView.setLayoutAnimation(null);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setAdapter(listAdapter);

        listView.setOnItemClickListener((view, position) -> {
            if (position == terminateRow) {
                Context parent = getParentActivity();
                if (parent == null) return;
                AlertDialog.Builder builder = new AlertDialog.Builder(parent, getResourceProvider());
                builder.setTitle("Sign Out");
                builder.setMessage("Sign out of your EVE account on this device?");
                builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
                builder.setPositiveButton("Sign Out", (dialog, which) -> {
                    if (eveDelegate != null) {
                        eveDelegate.onSignOutConfirmed();
                    }
                });
                AlertDialog dialog = builder.create();
                showDialog(dialog);
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(frameLayout, (v, insets) -> {
            if (listView != null) {
                int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime()).bottom;
                listView.setPadding(0, 0, 0, AndroidUtilities.dp(16) + bottom);
            }
            return insets;
        });

        return fragmentView;
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{HeaderCell.class, TextDetailSettingsCell.class, TextCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        return descriptions;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getAdapterPosition() == terminateRow;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == 0) {
                view = new HeaderCell(mContext);
            } else if (viewType == 1) {
                view = new TextDetailSettingsCell(mContext);
            } else if (viewType == 2) {
                view = new TextCell(mContext);
            } else {
                view = new TextInfoPrivacyCell(mContext);
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();
            if (type == 0) {
                ((HeaderCell) holder.itemView).setText("This device");
            } else if (type == 1) {
                TextDetailSettingsCell cell = (TextDetailSettingsCell) holder.itemView;
                if (position == currentSessionRow) {
                    String device = eveDelegate != null ? eveDelegate.getDeviceModelLabel() : "Android Device";
                    String appVer = eveDelegate != null ? eveDelegate.getAppVersionLabel() : "EVE Android";
                    cell.setTextAndValue(device, appVer + " • Active now", true);
                } else if (position == accountEmailRow) {
                    String email = eveDelegate != null ? eveDelegate.getLoginEmail() : "Signed-in account";
                    cell.setTextAndValue("Signed-in Account", email, true);
                }
            } else if (type == 2) {
                TextCell cell = (TextCell) holder.itemView;
                cell.setTextColor(Theme.getColor(Theme.key_text_RedRegular, getResourceProvider()));
                cell.setText("Sign Out of This Device", false);
            } else {
                ((TextInfoPrivacyCell) holder.itemView).setText("Your EVE exam session is authenticated via Firebase Auth on this device.");
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == currentSessionSectionRow) return 0;
            if (position == currentSessionRow || position == accountEmailRow) return 1;
            if (position == terminateRow) return 2;
            return 3;
        }
    }
}
