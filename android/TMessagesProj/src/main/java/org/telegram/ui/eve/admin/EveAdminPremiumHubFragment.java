/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.eve.admin;

import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;

/**
 * Unified Telegram-native Manage Premium hub grouping Plan & UPI Settings + Benefits +
 * Session Expiry + Telegram QR Preview, Premium Subscribers, and Payment Orders.
 */
public class EveAdminPremiumHubFragment extends BaseFragment {

    private static final int ID_PLAN_UPI_QR = 101;
    private static final int ID_SUBSCRIBERS = 102;
    private static final int ID_PAYMENT_ORDERS = 103;

    private UniversalRecyclerView listView;

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
        setHasOwnBackground(true);
        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Manage Premium",
                "Plan, UPI QR, subscribers & payment orders",
                false,
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
                null
        );
        listView = chrome.listView;
        fragmentView = chrome.rootView;
        return chrome.rootView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        items.add(UItem.asHeader("Membership & Checkout"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_PLAN_UPI_QR,
                IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                R.drawable.settings_premium,
                "Plan, UPI, Benefits & QR Preview",
                "Price, duration, perks, session expiry & live Telegram QR",
                null
        ));
        items.add(UItem.asShadow("Changes to Plan, Price, UPI ID, or Session Expiry immediately reflect on the student upgrade checkout flow."));

        items.add(UItem.asHeader("Subscribers & Orders"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_SUBSCRIBERS,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                R.drawable.msg_groups,
                "Premium Subscribers",
                "Grant, extend, inspect expiry or revoke active memberships",
                null
        ));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_PAYMENT_ORDERS,
                IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                R.drawable.msg_stats,
                "Payment Orders",
                "Verify UPI UTR receipts, approve pending & review refunds",
                null
        ));
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_PLAN_UPI_QR) {
            presentFragment(new EveAdminPremiumConfigFragment());
        } else if (item.id == ID_SUBSCRIBERS) {
            presentFragment(new EveAdminPremiumUsersFragment());
        } else if (item.id == ID_PAYMENT_ORDERS) {
            presentFragment(new EveAdminPaymentsFragment());
        }
    }
}
