package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.LanguageSelectActivity;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.ThemeActivity;
import org.telegram.ui.eve.exams.EveLeaderboardFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Telegram-native Java Results, Analytics, App Settings & System Health module for EVE Admin Control Center.
 * Parity with AdminAnalyticsActivity, ApiUsageActivity, SystemMonitorActivity, AppConfigActivity, and Export Data.
 */
public class EveAdminInsightsAndSystemFragment extends BaseFragment {

    private static final int ID_EXAM_ANALYTICS = 101;
    private static final int ID_LEADERBOARD = 102;
    private static final int ID_CRASH_REPORTS = 103;
    private static final int ID_API_USAGE = 104;
    private static final int ID_EXPORT_EXAMS = 105;

    private static final int ID_APP_CONFIG = 201;
    private static final int ID_EXAM_SETTINGS = 202;
    private static final int ID_LANGUAGE_SETTINGS = 203;
    private static final int ID_PREMIUM_CONFIG = 204;

    private static final int ID_HEALTH_REFRESH = 301;
    private static final int ID_AI_HEALTH = 302;
    private static final int ID_FAILED_JOBS = 303;
    private static final int BASE_SERVICE_ID = 500;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private final EveAdminModels.AdminProfile profile;
    private UniversalRecyclerView listView;
    private List<EveAdminModels.SystemServiceStatus> serviceStatuses = new ArrayList<>();
    private List<EveAdminModels.GenerationJobItem> generationJobs = new ArrayList<>();
    private EveAdminModels.AppConfigModel appConfig;

    public EveAdminInsightsAndSystemFragment(EveAdminModels.AdminProfile profile) {
        this.profile = profile;
    }

    @Override
    public boolean onFragmentCreate() {
        return EveAdminConfig.isAdminAvailable() && super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Analytics, Config & Health");
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
        loadLiveHealth();
        return root;
    }

    private void loadLiveHealth() {
        repo.loadInsightsOverviewAsync(profile, new EveAdminRepository.ResultCallback<EveAdminRepository.InsightsBundle>() {
            @Override
            public void onSuccess(EveAdminRepository.InsightsBundle data) {
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
                BulletinFactory.of(EveAdminInsightsAndSystemFragment.this).createErrorBulletin(message).show();
            }
        });
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("E. Results, Analytics & Exports"));
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_VIEW_ANALYTICS_LOGS)) {
            items.add(SettingsActivity.SettingCell.Factory.of(ID_EXAM_ANALYTICS, 0, 0, 0, "Exam & Score Analytics", "Attempts, average and highest scores per exam", null));
            items.add(SettingsActivity.SettingCell.Factory.of(ID_CRASH_REPORTS, 0, 0, 0, "Production Crash Reports (D1)", "Aggregated crash signatures & top stack frames", null));
            items.add(SettingsActivity.SettingCell.Factory.of(ID_API_USAGE, 0, 0, 0, "API Usage & AI Cost Monitor", "Monthly Gemini calls, creations & submissions", null));
        }
        items.add(SettingsActivity.SettingCell.Factory.of(ID_LEADERBOARD, 0, 0, 0, "Student Leaderboard", "Overall student rankings across EVE exams", null));
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_EXPORT_DATA)) {
            items.add(SettingsActivity.SettingCell.Factory.of(ID_EXPORT_EXAMS, 0, 0, 0, "Export All Exam Data (JSON)", "Copy full exam export JSON to clipboard", null));
        }
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("L. App Settings & Configuration"));
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_CONFIG)) {
            String cfgSummary = appConfig != null
                    ? (appConfig.maintenanceMode ? "MAINTENANCE ON" : "Online") + " • Min v" + appConfig.minSupportedVersionCode + " • Default: " + appConfig.defaultPublishMode
                    : "Configure Maintenance Mode & Min Version";
            items.add(SettingsActivity.SettingCell.Factory.of(ID_APP_CONFIG, 0, 0, 0, "Maintenance Mode & App Config", cfgSummary, null));
        }
        items.add(SettingsActivity.SettingCell.Factory.of(ID_EXAM_SETTINGS, 0, 0, 0, LocaleController.getString(R.string.SettingsChat), "Appearance, theme & exam display defaults", null));
        items.add(SettingsActivity.SettingCell.Factory.of(ID_LANGUAGE_SETTINGS, 0, 0, 0, LocaleController.getString(R.string.SettingsLanguage), "English / Hindi app locale", LocaleController.getCurrentLanguageName()));
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_PREMIUM)) {
            items.add(SettingsActivity.SettingCell.Factory.of(ID_PREMIUM_CONFIG, 0, 0, 0, "Premium Plan & UPI/QR Config", "Manage plan price, duration, UPI VPA & QR", null));
        }
        items.add(UItem.asShadow(null));

        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_VIEW_ANALYTICS_LOGS)) {
            items.add(UItem.asHeader("M. System Health (Worker, D1, R2, Firebase)"));
            items.add(SettingsActivity.SettingCell.Factory.of(ID_HEALTH_REFRESH, 0xFF3D8BEB, 0xFF2A6FD9, R.drawable.msg_retry, "Refresh Live Health Checks", "Probe Worker, D1, R2, Firebase & jobs", null));
            items.add(SettingsActivity.SettingCell.Factory.of(ID_AI_HEALTH, 0xFF9C27B0, 0xFF6A1B9A, R.drawable.msg_stats, "Run AI Provider Health Probe", "Check live Gemini generation connectivity", null));
            items.add(SettingsActivity.SettingCell.Factory.of(ID_FAILED_JOBS, 0xFFFF9800, 0xFFEF6C00, R.drawable.msg_list, "Generation Jobs & Retry", "Inspect failed or queued AI generation jobs", generationJobs.size() + " jobs"));
            for (int i = 0; i < serviceStatuses.size(); i++) {
                EveAdminModels.SystemServiceStatus s = serviceStatuses.get(i);
                items.add(SettingsActivity.SettingCell.Factory.of(BASE_SERVICE_ID + i, 0, 0, 0, s.name.toUpperCase(Locale.US), s.detail, s.status));
            }
            items.add(UItem.asShadow(null));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_EXAM_ANALYTICS:
                openExamAnalytics();
                break;
            case ID_LEADERBOARD:
                presentFragment(new EveLeaderboardFragment("", "Overall Leaderboard"));
                break;
            case ID_CRASH_REPORTS:
                openCrashReports();
                break;
            case ID_API_USAGE:
                openApiUsage();
                break;
            case ID_EXPORT_EXAMS:
                exportExams();
                break;
            case ID_APP_CONFIG:
                openAppConfigDialog();
                break;
            case ID_EXAM_SETTINGS:
                presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC));
                break;
            case ID_LANGUAGE_SETTINGS:
                presentFragment(new LanguageSelectActivity());
                break;
            case ID_PREMIUM_CONFIG:
                EveAdminDialogs.showPricingAndUpiDialog(this, repo, this::loadLiveHealth);
                break;
            case ID_HEALTH_REFRESH:
                loadLiveHealth();
                break;
            case ID_AI_HEALTH:
                runAiProbe();
                break;
            case ID_FAILED_JOBS:
                openJobsDialog();
                break;
            default:
                if (item.id >= BASE_SERVICE_ID) {
                    int idx = item.id - BASE_SERVICE_ID;
                    if (idx >= 0 && idx < serviceStatuses.size()) {
                        EveAdminModels.SystemServiceStatus s = serviceStatuses.get(idx);
                        showAlert(s.name.toUpperCase(Locale.US) + " (" + s.status + ")", s.detail);
                    }
                }
                break;
        }
    }

    private void openExamAnalytics() {
        repo.runWithAdminToken(errCb(), token -> {
            List<EveAdminModels.ExamAnalyticsItem> list = repo.fetchExamAnalytics(token);
            repo.postSuccess(new EveAdminRepository.ResultCallback<List<EveAdminModels.ExamAnalyticsItem>>() {
                @Override
                public void onSuccess(List<EveAdminModels.ExamAnalyticsItem> data) {
                    StringBuilder sb = new StringBuilder();
                    for (EveAdminModels.ExamAnalyticsItem item : data) {
                        sb.append("• ").append(item.examName)
                                .append("\n  Attempts: ").append(item.attemptCount)
                                .append(" | Avg: ").append(String.format(Locale.US, "%.1f", item.averageScore))
                                .append(" | High: ").append(String.format(Locale.US, "%.1f", item.highestScore))
                                .append("\n\n");
                    }
                    showAlert("Exam Analytics", sb.length() > 0 ? sb.toString() : "No attempts recorded yet.");
                }
                @Override public void onError(String message, int httpStatus) {}
            }, list);
        });
    }

    private void openCrashReports() {
        repo.runWithAdminToken(errCb(), token -> {
            List<EveAdminModels.CrashSignatureItem> list = repo.fetchCrashSignatures(token);
            repo.postSuccess(new EveAdminRepository.ResultCallback<List<EveAdminModels.CrashSignatureItem>>() {
                @Override
                public void onSuccess(List<EveAdminModels.CrashSignatureItem> data) {
                    StringBuilder sb = new StringBuilder();
                    for (EveAdminModels.CrashSignatureItem c : data) {
                        sb.append("• [").append(c.occurrenceCount).append("x] ").append(c.exceptionClass)
                                .append("\n  ").append(c.topFrame)
                                .append("\n  ").append(c.sanitizedMessage)
                                .append("\n\n");
                    }
                    showAlert("D1 Crash Signatures", sb.length() > 0 ? sb.toString() : "Zero production crash signatures recorded.");
                }
                @Override public void onError(String message, int httpStatus) {}
            }, list);
        });
    }

    private void openApiUsage() {
        repo.runWithAdminToken(errCb(), token -> {
            JSONObject data = repo.fetchApiUsage(token);
            repo.postSuccess(new EveAdminRepository.ResultCallback<JSONObject>() {
                @Override
                public void onSuccess(JSONObject obj) {
                    if (obj == null) {
                        showAlert("API Usage", "No usage telemetry available.");
                        return;
                    }
                    String text = "Month: " + obj.optString("monthDisplayName", obj.optString("yearMonth", "-"))
                            + "\nGemini Calls: " + obj.optLong("geminiCalls", 0)
                            + "\nSuccessful: " + obj.optLong("geminiSuccessfulCalls", 0)
                            + "\nFailed: " + obj.optLong("geminiFailedCalls", 0)
                            + "\nDocuments Created: " + obj.optLong("documentsCreated", 0)
                            + "\nTest Submissions: " + obj.optLong("testSubmissions", 0);
                    showAlert("API Usage & Cost Monitor", text);
                }
                @Override public void onError(String message, int httpStatus) {}
            }, data);
        });
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

    private void openAppConfigDialog() {
        Activity act = getParentActivity();
        if (act == null) return;
        EveAdminModels.AppConfigModel cfg = appConfig != null ? appConfig : new EveAdminModels.AppConfigModel(1, false, "", "paused");
        CheckBox maintCheck = new CheckBox(act);
        maintCheck.setText("Enable Maintenance Mode");
        maintCheck.setChecked(cfg.maintenanceMode);
        maintCheck.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));

        EditText minVer = new EditText(act);
        minVer.setHint("Minimum supported versionCode");
        minVer.setText(String.valueOf(cfg.minSupportedVersionCode));
        minVer.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));

        EditText maintMsg = new EditText(act);
        maintMsg.setHint("Maintenance message");
        maintMsg.setText(cfg.maintenanceMessage);
        maintMsg.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));

        EditText defPub = new EditText(act);
        defPub.setHint("Default publish mode (live / paused)");
        defPub.setText(cfg.defaultPublishMode);
        defPub.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(8));
        box.addView(maintCheck);
        box.addView(minVer);
        box.addView(maintMsg);
        box.addView(defPub);

        showDialog(new AlertDialog.Builder(act, getResourceProvider())
                .setTitle("App Config & Maintenance")
                .setView(box)
                .setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
                    int minV = 1;
                    try { minV = Math.max(1, Integer.parseInt(minVer.getText().toString().trim())); } catch (Throwable ignored) {}
                    String msg = maintMsg.getText().toString().trim();
                    String pub = "live".equalsIgnoreCase(defPub.getText().toString().trim()) ? "live" : "paused";
                    boolean maint = maintCheck.isChecked();
                    int finalMinV = minV;
                    repo.runWithAdminToken(errCb(), token -> {
                        EveAdminModels.AppConfigModel updated = repo.updateAppConfig(token, finalMinV, maint, msg, pub);
                        repo.postSuccess(new EveAdminRepository.ResultCallback<EveAdminModels.AppConfigModel>() {
                            @Override
                            public void onSuccess(EveAdminModels.AppConfigModel data) {
                                appConfig = data;
                                BulletinFactory.of(EveAdminInsightsAndSystemFragment.this).createSimpleBulletin(R.raw.contact_check, "App config saved.").show();
                                if (listView != null && listView.adapter != null) listView.adapter.update(true);
                            }
                            @Override public void onError(String message, int httpStatus) {}
                        }, updated);
                    });
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
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

    private void openJobsDialog() {
        Activity act = getParentActivity();
        if (act == null) return;
        if (generationJobs.isEmpty()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "No recent generation jobs.").show();
            return;
        }
        String[] labels = new String[generationJobs.size()];
        for (int i = 0; i < generationJobs.size(); i++) {
            EveAdminModels.GenerationJobItem j = generationJobs.get(i);
            labels[i] = "[" + j.status + "] " + j.examId + (j.retryable ? " (Tap to Retry)" : "");
        }
        showDialog(new AlertDialog.Builder(act, getResourceProvider())
                .setTitle("Generation Jobs")
                .setItems(labels, (d, idx) -> {
                    EveAdminModels.GenerationJobItem j = generationJobs.get(idx);
                    if (!j.retryable) {
                        showAlert("Job " + j.id, "Status: " + j.status + "\nCategory: " + j.errorCategory);
                        return;
                    }
                    repo.runWithAdminToken(errCb(), token -> {
                        repo.retryGenerationJob(token, j.id);
                        repo.postSuccess(new EveAdminRepository.ResultCallback<Void>() {
                            @Override public void onSuccess(Void data) {
                                BulletinFactory.of(EveAdminInsightsAndSystemFragment.this).createSimpleBulletin(R.raw.contact_check, "Job retried.").show();
                                loadLiveHealth();
                            }
                            @Override public void onError(String message, int httpStatus) {}
                        }, null);
                    });
                })
                .setNegativeButton(LocaleController.getString(R.string.Close), null)
                .create());
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
