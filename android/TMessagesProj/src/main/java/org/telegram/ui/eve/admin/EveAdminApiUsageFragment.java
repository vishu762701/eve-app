package org.telegram.ui.eve.admin;

import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
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
 * Full-screen API Usage & AI Cost Monitor for EVE Admin Control Center.
 */
public class EveAdminApiUsageFragment extends BaseFragment {

    private static final int ID_RETRY = 101;
    private static final int ID_MONTH_SUMMARY = 201;
    private static final int ID_TOTAL_CALLS = 301;
    private static final int ID_SUCCESS_CALLS = 302;
    private static final int ID_FAILED_CALLS = 303;
    private static final int ID_DOCS_CREATED = 304;
    private static final int ID_SUBMISSIONS = 305;
    private static final int ID_INSPECT_JOBS = 401;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private JSONObject usageObj;
    private List<EveAdminModels.GenerationJobItem> generationJobs = Collections.emptyList();
    private boolean loading = true;
    private boolean destroyed = false;
    private String errorText = "";
    private final EveAdminUiChrome.AutoSyncController autoSync = new EveAdminUiChrome.AutoSyncController(this::reload);

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
                "API Usage & AI Monitor",
                "Monthly AI calls & submissions",
                true,
                true,
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
        reload();
        return chrome.rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        autoSync.onResume();
    }

    @Override
    public void onPause() {
        autoSync.onPause();
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        autoSync.destroy();
        super.onFragmentDestroy();
    }

    private void reload() {
        if (destroyed) return;
        loading = usageObj == null;
        errorText = "";
        if (loading) {
            updateList();
        }
        EveAdminRepository.ResultCallback<JSONObject> cb = new EveAdminRepository.ResultCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject data) {
                if (destroyed) return;
                loading = false;
                errorText = "";
                autoSync.markSynced();
                usageObj = data;
                if (simpleActionBar != null && data != null) {
                    simpleActionBar.setSubtitle(data.optString("monthDisplayName", data.optString("yearMonth", "Monthly telemetry")));
                }
                updateList();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                loading = false;
                autoSync.markSynced();
                errorText = message != null ? message : "Unable to load API usage.";
                updateList();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            JSONObject usage = repo.fetchApiUsage(token);
            List<EveAdminModels.GenerationJobItem> jobs = Collections.emptyList();
            try {
                jobs = repo.fetchFailedJobs(token);
            } catch (Throwable ignored) {
            }
            generationJobs = jobs != null ? jobs : Collections.emptyList();
            repo.postSuccess(cb, usage);
        });
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
                    "Retry Loading API Usage",
                    errorText,
                    "Retry"));
            items.add(UItem.asShadow(null));
        }

        if (loading && usageObj == null) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (usageObj == null) {
            items.add(UItem.asShadow("No API usage telemetry recorded for this month yet."));
            return;
        }

        String monthName = usageObj.optString("monthDisplayName", usageObj.optString("yearMonth", "Current Month"));
        long totalCalls = usageObj.optLong("geminiCalls", 0L);
        long okCalls = usageObj.optLong("geminiSuccessfulCalls", 0L);
        long failCalls = usageObj.optLong("geminiFailedCalls", 0L);
        long docsCreated = usageObj.optLong("documentsCreated", 0L);
        long submissions = usageObj.optLong("testSubmissions", 0L);
        int ratePct = totalCalls > 0 ? (int) Math.round((okCalls * 100.0) / totalCalls) : 100;

        items.add(UItem.asHeader("Monthly Summary"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_MONTH_SUMMARY,
                ratePct >= 80 ? IconBackgroundColors.GREEN.top : IconBackgroundColors.ORANGE.top,
                ratePct >= 80 ? IconBackgroundColors.GREEN.bottom : IconBackgroundColors.ORANGE.bottom,
                R.drawable.msg_stats,
                monthName,
                String.format(Locale.US, "%d%% success rate • %d / %d calls", ratePct, okCalls, totalCalls),
                ratePct + "%"));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Usage Breakdown"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_TOTAL_CALLS,
                IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                R.drawable.msg_recent,
                "Total AI Calls",
                "Gemini generation & explanation requests",
                String.valueOf(totalCalls)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_SUCCESS_CALLS,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                R.drawable.msg_select,
                "Successful AI Calls",
                "Completed provider responses",
                String.valueOf(okCalls)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_FAILED_CALLS,
                failCalls > 0 ? IconBackgroundColors.RED.top : IconBackgroundColors.GRAY.top,
                failCalls > 0 ? IconBackgroundColors.RED.bottom : IconBackgroundColors.GRAY.bottom,
                failCalls > 0 ? R.drawable.msg_warning : R.drawable.msg_select,
                "Failed AI Calls",
                failCalls > 0 ? "Rate-limited, timeout, or provider errors" : "Zero failed AI calls",
                String.valueOf(failCalls)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_DOCS_CREATED,
                IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                R.drawable.msg_folders,
                "Documents Created",
                "Exams, mock tests & question items saved",
                String.valueOf(docsCreated)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_SUBMISSIONS,
                IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                R.drawable.msg_list,
                "Test Submissions",
                "Graded student attempt submissions",
                String.valueOf(submissions)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Troubleshooting"));
        int failedJobCount = 0;
        for (EveAdminModels.GenerationJobItem j : generationJobs) {
            if ("failed".equalsIgnoreCase(j.status)) {
                failedJobCount++;
            }
        }
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_INSPECT_JOBS,
                IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                R.drawable.msg_list,
                "Inspect Failed Generation Jobs",
                "View queued/failed AI test jobs and retry",
                failedJobCount > 0 ? (failedJobCount + " failed") : (generationJobs.size() + " jobs")));
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
        } else if (item.id == ID_INSPECT_JOBS) {
            if (generationJobs.isEmpty()) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "No recent generation jobs recorded.").show();
                return;
            }
            EveAdminDialogs.showGenerationJobsDialog(this, repo, generationJobs, this::reload);
        }
    }
}
