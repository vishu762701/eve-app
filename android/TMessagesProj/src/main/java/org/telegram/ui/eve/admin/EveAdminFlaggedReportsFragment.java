package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Edge-to-Edge Flagged Question Reports inbox replacing AlertDialog report lists.
 */
public class EveAdminFlaggedReportsFragment extends BaseFragment {

    private static final int MENU_FILTER = 1;
    private static final int MENU_REFRESH = 2;

    private static final int ID_RETRY = 101;
    private static final int BASE_REPORT_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private List<EveAdminModels.QuestionReportItem> reports = Collections.emptyList();
    private final List<EveAdminModels.QuestionReportItem> filteredReports = new ArrayList<>();
    private String statusFilter = "ALL";
    private boolean loading = true;
    private boolean destroyed = false;
    private String errorText = "";

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
                "Flagged Questions",
                "Loading...",
                false,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_FILTER) {
                            showFilterPicker();
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
            menu.addItem(MENU_FILTER, R.drawable.msg_select, getResourceProvider());
            menu.addItem(MENU_REFRESH, R.drawable.msg_retry, getResourceProvider());
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

        EveAdminRepository.ResultCallback<List<EveAdminModels.QuestionReportItem>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.QuestionReportItem>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.QuestionReportItem> data) {
                        if (destroyed) return;
                        loading = false;
                        errorText = "";
                        reports = data != null ? data : Collections.emptyList();
                        applyFilter();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
                        errorText = message != null ? message : "Unable to load flagged reports.";
                        updateSubtitle();
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchReports(token, null)));
    }

    private void applyFilter() {
        filteredReports.clear();
        for (EveAdminModels.QuestionReportItem r : reports) {
            String st = r.status.toUpperCase(Locale.US);
            if ("ALL".equals(statusFilter)
                    || ("PENDING".equals(statusFilter) && !"DISMISSED".equals(st))
                    || ("DISMISSED".equals(statusFilter) && "DISMISSED".equals(st))) {
                filteredReports.add(r);
            }
        }
        updateSubtitle();
        updateList();
    }

    private void updateSubtitle() {
        if (simpleActionBar == null) return;
        if (!errorText.isEmpty() && reports.isEmpty()) {
            simpleActionBar.setSubtitle("Unable to load");
        } else if ("ALL".equals(statusFilter)) {
            simpleActionBar.setSubtitle(filteredReports.size() + " report(s)");
        } else {
            simpleActionBar.setSubtitle(filteredReports.size() + " " + statusFilter.toLowerCase(Locale.US));
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
                    "Retry Loading Reports",
                    errorText,
                    "Retry"));
        }

        if (loading && reports.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (filteredReports.isEmpty()) {
            items.add(UItem.asHeader("Flagged Questions"));
            items.add(UItem.asShadow("No flagged question reports in this view."));
            return;
        }

        for (int i = 0; i < filteredReports.size(); i++) {
            EveAdminModels.QuestionReportItem r = filteredReports.get(i);
            boolean dismissed = "dismissed".equalsIgnoreCase(r.status);
            IconBackgroundColors badge = dismissed ? IconBackgroundColors.GREEN : IconBackgroundColors.ORANGE_DEEP;
            String title = (!TextUtils.isEmpty(r.examName) ? r.examName : "Exam") + " • " + r.reason;
            String sub = (!TextUtils.isEmpty(r.questionText) ? r.questionText : "No question snippet")
                    + (!TextUtils.isEmpty(r.studentEmail) ? (" • " + r.studentEmail) : "");

            items.add(SettingsActivity.SettingCell.Factory.of(
                    BASE_REPORT_ID + i,
                    badge.top, badge.bottom,
                    dismissed ? R.drawable.ic_ab_done : R.drawable.msg_report,
                    title,
                    sub,
                    dismissed ? "Dismissed" : "Pending"));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
            return;
        }
        int idx = item.id - BASE_REPORT_ID;
        if (idx >= 0 && idx < filteredReports.size()) {
            showReportDetail(filteredReports.get(idx));
        }
    }

    private void showFilterPicker() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        String[] labels = new String[] { "All Reports", "Pending Only", "Dismissed Only" };
        String[] vals = new String[] { "ALL", "PENDING", "DISMISSED" };
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Filter Reports");
        builder.setItems(labels, (d, which) -> {
            statusFilter = vals[which];
            applyFilter();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showReportDetail(EveAdminModels.QuestionReportItem r) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        String msg = "Exam: " + (!TextUtils.isEmpty(r.examName) ? r.examName : "-")
                + "\nReason: " + r.reason
                + "\nStatus: " + r.status
                + "\nReporter: " + (!TextUtils.isEmpty(r.studentEmail) ? r.studentEmail : "-")
                + "\nReported: " + EveAdminUiChrome.formatDateTime(r.timestamp)
                + "\n\nQuestion:\n" + (!TextUtils.isEmpty(r.questionText) ? r.questionText : "-")
                + (!TextUtils.isEmpty(r.comment) ? "\n\nStudent Comment:\n" + r.comment : "");

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Flagged Question");
        builder.setMessage(msg);
        if (!"dismissed".equalsIgnoreCase(r.status)) {
            builder.setPositiveButton("Dismiss", (d, w) -> dismissReport(r));
        } else {
            builder.setPositiveButton("Close", null);
        }
        builder.setNeutralButton("Delete", (d, w) -> confirmDeleteReport(r));
        if (!"dismissed".equalsIgnoreCase(r.status)) {
            builder.setNegativeButton("Close", null);
        }
        showDialog(builder.create());
    }

    private void dismissReport(EveAdminModels.QuestionReportItem r) {
        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                if (destroyed) return;
                repo.invalidateCache();
                BulletinFactory.of(EveAdminFlaggedReportsFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, "Report dismissed").show();
                reload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                BulletinFactory.of(EveAdminFlaggedReportsFragment.this)
                        .createErrorBulletin(message != null ? message : "Dismiss failed.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.dismissReport(token, r.id);
            repo.postSuccess(cb, null);
        });
    }

    private void confirmDeleteReport(EveAdminModels.QuestionReportItem r) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Delete Report");
        builder.setMessage("Permanently remove this flagged question report?");
        builder.setPositiveButton("Delete", (d, w) -> {
            EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
                @Override
                public void onSuccess(Void data) {
                    if (destroyed) return;
                    repo.invalidateCache();
                    BulletinFactory.of(EveAdminFlaggedReportsFragment.this)
                            .createSimpleBulletin(R.raw.info, "Report deleted").show();
                    reload();
                }

                @Override
                public void onError(String message, int httpStatus) {
                    if (destroyed) return;
                    BulletinFactory.of(EveAdminFlaggedReportsFragment.this)
                            .createErrorBulletin(message != null ? message : "Delete failed.").show();
                }
            };
            repo.runWithAdminToken(cb, token -> {
                repo.deleteReport(token, r.id);
                repo.postSuccess(cb, null);
            });
        });
        builder.setNegativeButton("Cancel", null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (pos instanceof TextView) {
            ((TextView) pos).setTextColor(Theme.getColor(Theme.key_text_RedBold, getResourceProvider()));
        }
    }
}
