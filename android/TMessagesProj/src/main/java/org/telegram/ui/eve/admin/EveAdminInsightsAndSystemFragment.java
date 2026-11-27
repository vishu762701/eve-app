package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.exams.EveLeaderboardFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * Telegram-native Java Results, Analytics, App Configuration & System Health hub.
 */
public class EveAdminInsightsAndSystemFragment extends BaseFragment {

    private static final int ID_EXAM_ANALYTICS = 101;
    private static final int ID_LEADERBOARD = 102;
    private static final int ID_CRASH_REPORTS = 103;
    private static final int ID_API_USAGE = 104;
    private static final int ID_EXPORT_EXAMS = 105;

    private static final int ID_APP_CONFIG = 201;

    private static final int ID_SERVICE_HEALTH = 301;
    private static final int ID_AI_HEALTH = 302;
    private static final int ID_FAILED_JOBS = 303;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private final EveAdminModels.AdminProfile profile;
    private UniversalRecyclerView listView;
    private boolean destroyed = false;
    private List<EveAdminModels.SystemServiceStatus> serviceStatuses = new ArrayList<>();
    private List<EveAdminModels.GenerationJobItem> generationJobs = new ArrayList<>();
    private EveAdminModels.AppConfigModel appConfig;
    private final EveAdminUiChrome.AutoSyncController autoSync = new EveAdminUiChrome.AutoSyncController(this::loadLiveHealth);

    public EveAdminInsightsAndSystemFragment(EveAdminModels.AdminProfile profile) {
        this.profile = profile;
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

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Analytics & Health",
                null,
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
        loadLiveHealth();
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

    private void loadLiveHealth() {
        if (destroyed) return;
        repo.loadInsightsOverviewAsync(profile, new EveAdminRepository.ResultCallback<EveAdminRepository.InsightsBundle>() {
            @Override
            public void onSuccess(EveAdminRepository.InsightsBundle data) {
                if (destroyed) return;
                autoSync.markSynced();
                if (data != null) {
                    appConfig = data.appConfig;
                    serviceStatuses = data.serviceStatuses;
                    generationJobs = data.generationJobs;
                }
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                autoSync.markSynced();
                BulletinFactory.of(EveAdminInsightsAndSystemFragment.this).createErrorBulletin(message).show();
            }
        });
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        items.add(UItem.asHeader("Analytics & Exports"));
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_VIEW_ANALYTICS_LOGS)) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_EXAM_ANALYTICS,
                    IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                    R.drawable.msg_stats,
                    "Exam & Score Analytics",
                    "Attempts, average and highest scores per exam",
                    null));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_CRASH_REPORTS,
                    IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                    R.drawable.msg_report,
                    "Crash Reports",
                    "Aggregated crash signatures & top stack frames",
                    null));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_API_USAGE,
                    IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                    R.drawable.msg_recent,
                    "API Usage & AI Cost Monitor",
                    "Monthly AI calls, creations & submissions",
                    null));
        }
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_LEADERBOARD,
                IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                R.drawable.msg_groups,
                "Student Leaderboard",
                "Overall student rankings across EVE exams",
                null));
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_EXPORT_DATA)) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_EXPORT_EXAMS,
                    IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                    R.drawable.msg_download,
                    "Export All Exam Data",
                    "Copy full exam export JSON to clipboard",
                    null));
        }
        items.add(UItem.asShadow(null));

        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_CONFIG)) {
            items.add(UItem.asHeader("App Configuration"));
            String cfgSummary = appConfig != null
                    ? (appConfig.maintenanceMode ? "MAINTENANCE ON" : "Online") + " • Min v" + appConfig.minSupportedVersionCode + " • Default: " + appConfig.defaultPublishMode
                    : "Configure Maintenance Mode & Min Version";
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_APP_CONFIG,
                    IconBackgroundColors.BLUE_DEEP.top, IconBackgroundColors.BLUE_DEEP.bottom,
                    R.drawable.msg_settings,
                    "Maintenance Mode & App Config",
                    cfgSummary,
                    null));
            items.add(UItem.asShadow(null));
        }

        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_VIEW_ANALYTICS_LOGS)) {
            items.add(UItem.asHeader("System Health"));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_SERVICE_HEALTH,
                    IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                    R.drawable.msg_select,
                    "Service & Infrastructure Health",
                    EveAdminSystemHealthFragment.buildSummarySubtitle(serviceStatuses),
                    serviceStatuses.isEmpty() ? null : String.valueOf(serviceStatuses.size())));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_AI_HEALTH,
                    IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                    R.drawable.msg_stats,
                    "AI Provider Health Probe",
                    "Check live AI generation connectivity",
                    null));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_FAILED_JOBS,
                    IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                    R.drawable.msg_list,
                    "Generation Jobs & Retry",
                    "Inspect failed or queued AI generation jobs",
                    generationJobs.size() + " jobs"));
            items.add(UItem.asShadow(null));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_EXAM_ANALYTICS:
                presentFragment(new EveAdminExamAnalyticsFragment());
                break;
            case ID_LEADERBOARD:
                presentFragment(new EveLeaderboardFragment("", "Overall Leaderboard", true));
                break;
            case ID_CRASH_REPORTS:
                presentFragment(new EveAdminCrashReportsFragment(profile));
                break;
            case ID_API_USAGE:
                presentFragment(new EveAdminApiUsageFragment());
                break;
            case ID_EXPORT_EXAMS:
                exportExams();
                break;
            case ID_APP_CONFIG:
                presentFragment(new EveAdminMaintenanceConfigFragment(appConfig, this::loadLiveHealth));
                break;
            case ID_SERVICE_HEALTH:
                presentFragment(new EveAdminSystemHealthFragment(serviceStatuses));
                break;
            case ID_AI_HEALTH:
                runAiProbe();
                break;
            case ID_FAILED_JOBS:
                EveAdminDialogs.showGenerationJobsDialog(this, repo, generationJobs, this::loadLiveHealth);
                break;
        }
    }

    private void exportExams() {
        repo.runWithAdminToken(errCb(), token -> {
            JSONObject data = repo.exportExams(token);
            repo.postSuccess(new EveAdminRepository.ResultCallback<JSONObject>() {
                @Override
                public void onSuccess(JSONObject obj) {
                    String preview = obj != null ? obj.toString() : "{}";
                    if (preview.length() > 1500) {
                        preview = preview.substring(0, 1500) + "\n... (truncated preview)";
                    }
                    AndroidUtilities.addToClipboard(obj != null ? obj.toString() : "{}");
                    showAlert("Exported Exam JSON (Copied)", preview);
                }
                @Override public void onError(String message, int httpStatus) {}
            }, data);
        });
    }

    private void runAiProbe() {
        repo.runWithAdminToken(errCb(), token -> {
            JSONObject res = repo.checkAiHealth(token);
            repo.postSuccess(new EveAdminRepository.ResultCallback<JSONObject>() {
                @Override
                public void onSuccess(JSONObject obj) {
                    showAlert("AI Health Probe", obj != null ? obj.toString() : "OK");
                }
                @Override public void onError(String message, int httpStatus) {}
            }, res);
        });
    }

    private void showAlert(String title, String message) {
        Activity act = getParentActivity();
        if (act == null) return;
        showDialog(new AlertDialog.Builder(act, getResourceProvider())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .create());
    }

    private <T> EveAdminRepository.ResultCallback<T> errCb() {
        return new EveAdminRepository.ResultCallback<T>() {
            @Override public void onSuccess(T data) {}
            @Override public void onError(String message, int httpStatus) {
                BulletinFactory.of(EveAdminInsightsAndSystemFragment.this).createErrorBulletin(message).show();
            }
        };
    }
}
