package org.telegram.ui.eve.notifications;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.eve.exams.EveAnnouncementsFragment;

import java.util.ArrayList;

/**
 * EVE-native Notification Preferences powered by Telegram ActionBar + UniversalRecyclerView.
 * Manages EVE exam reminders, announcements, results, and payment alerts via FCM independently from Telegram.
 */
public class EveNotificationSettingsFragment extends BaseFragment {

    private static final int ID_EXAM_REMINDERS = 1;
    private static final int ID_ANNOUNCEMENTS = 2;
    private static final int ID_RESULTS = 3;
    private static final int ID_PAYMENTS = 4;
    private static final int ID_SOUND = 5;
    private static final int ID_VIBRATE = 6;
    private static final int ID_OPEN_ANNOUNCEMENTS = 7;
    private static final int ID_SYNC_FCM = 8;

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.SettingsNotifications));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        return root;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        Context ctx = getContext();
        items.add(UItem.asHeader(LocaleController.getString(R.string.EveNotifCategoriesHeader)));
        items.add(UItem.asCheck(ID_EXAM_REMINDERS, LocaleController.getString(R.string.EveNotifExamReminders))
                .setChecked(EveNotificationHelper.isCategoryEnabled(ctx, EveNotificationHelper.PREF_EXAM_REMINDERS)));
        items.add(UItem.asCheck(ID_ANNOUNCEMENTS, LocaleController.getString(R.string.EveNotifAnnouncements))
                .setChecked(EveNotificationHelper.isCategoryEnabled(ctx, EveNotificationHelper.PREF_ANNOUNCEMENTS)));
        items.add(UItem.asCheck(ID_RESULTS, LocaleController.getString(R.string.EveNotifResults))
                .setChecked(EveNotificationHelper.isCategoryEnabled(ctx, EveNotificationHelper.PREF_RESULTS)));
        items.add(UItem.asCheck(ID_PAYMENTS, LocaleController.getString(R.string.EveNotifPayments))
                .setChecked(EveNotificationHelper.isCategoryEnabled(ctx, EveNotificationHelper.PREF_PAYMENTS)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.EveNotifIsolationInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.EveNotifDeliveryHeader)));
        items.add(UItem.asCheck(ID_SOUND, LocaleController.getString(R.string.EveNotifSound))
                .setChecked(EveNotificationHelper.isCategoryEnabled(ctx, EveNotificationHelper.PREF_SOUND)));
        items.add(UItem.asCheck(ID_VIBRATE, LocaleController.getString(R.string.EveNotifVibrate))
                .setChecked(EveNotificationHelper.isCategoryEnabled(ctx, EveNotificationHelper.PREF_VIBRATE)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asButton(ID_OPEN_ANNOUNCEMENTS, LocaleController.getString(R.string.EveAnnouncements)));
        items.add(UItem.asButton(ID_SYNC_FCM, LocaleController.getString(R.string.EveNotifSyncToken)));
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        Context ctx = getContext();
        switch (item.id) {
            case ID_EXAM_REMINDERS:
                EveNotificationHelper.toggleCategory(ctx, EveNotificationHelper.PREF_EXAM_REMINDERS);
                break;
            case ID_ANNOUNCEMENTS:
                EveNotificationHelper.toggleCategory(ctx, EveNotificationHelper.PREF_ANNOUNCEMENTS);
                break;
            case ID_RESULTS:
                EveNotificationHelper.toggleCategory(ctx, EveNotificationHelper.PREF_RESULTS);
                break;
            case ID_PAYMENTS:
                EveNotificationHelper.toggleCategory(ctx, EveNotificationHelper.PREF_PAYMENTS);
                break;
            case ID_SOUND:
                EveNotificationHelper.toggleCategory(ctx, EveNotificationHelper.PREF_SOUND);
                break;
            case ID_VIBRATE:
                EveNotificationHelper.toggleCategory(ctx, EveNotificationHelper.PREF_VIBRATE);
                break;
            case ID_OPEN_ANNOUNCEMENTS:
                org.telegram.ui.eve.auth.EveTelegramSessionManager.getInstance().openAnnouncementsDestination(EveNotificationSettingsFragment.this);
                return;
            case ID_SYNC_FCM:
                EveNotificationHelper.syncFcmTokenIfSignedIn();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.EveNotifTokenSynced)).show();
                return;
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
