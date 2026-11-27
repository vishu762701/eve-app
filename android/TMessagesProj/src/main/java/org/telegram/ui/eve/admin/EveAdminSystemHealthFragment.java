package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
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
 * Full-screen Service & Infrastructure Health monitor for EVE Admin Control Center.
 */
public class EveAdminSystemHealthFragment extends BaseFragment {

    private static final int ID_RETRY = 101;
    private static final int ID_AI_PROBE = 201;
    private static final int BASE_SERVICE_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private List<EveAdminModels.SystemServiceStatus> serviceStatuses;
    private boolean loading;
    private boolean probingAi;
    private boolean destroyed;
    private String errorText = "";
    private final EveAdminUiChrome.AutoSyncController autoSync = new EveAdminUiChrome.AutoSyncController(this::reload);

    public EveAdminSystemHealthFragment(@Nullable List<EveAdminModels.SystemServiceStatus> initialStatuses) {
        this.serviceStatuses = initialStatuses != null ? new ArrayList<>(initialStatuses) : new ArrayList<>();
        this.loading = this.serviceStatuses.isEmpty();
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
                "Service & Infrastructure Health",
                buildSummarySubtitle(serviceStatuses),
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

    public static String buildSummarySubtitle(@Nullable List<EveAdminModels.SystemServiceStatus> list) {
        if (list == null || list.isEmpty()) {
            return "Inspect backend, database, Firebase & media";
        }
        int verified = 0;
        int configured = 0;
        int unavailable = 0;
        for (EveAdminModels.SystemServiceStatus s : list) {
            String st = s.status != null ? s.status.toLowerCase(Locale.US) : "";
            if (st.contains("verified") || st.contains("ok") || st.contains("healthy") || st.contains("online") || st.contains("up")) {
                verified++;
            } else if (st.contains("configured") && !st.contains("unconfigured")) {
                configured++;
            } else {
                unavailable++;
            }
        }
        return verified + " verified • " + configured + " configured • " + unavailable + " unavailable";
    }

    private void reload() {
        if (destroyed) return;
        loading = serviceStatuses.isEmpty();
        errorText = "";
        if (loading) {
            updateList();
        }
        EveAdminRepository.ResultCallback<List<EveAdminModels.SystemServiceStatus>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.SystemServiceStatus>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.SystemServiceStatus> data) {
                        if (destroyed) return;
                        loading = false;
                        errorText = "";
                        autoSync.markSynced();
                        serviceStatuses = data != null ? data : Collections.emptyList();
                        if (simpleActionBar != null) {
                            simpleActionBar.setSubtitle(buildSummarySubtitle(serviceStatuses));
                        }
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
                        autoSync.markSynced();
                        errorText = message != null ? message : "Unable to load service health.";
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchSystemHealth(token)));
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
                    "Retry Health Check",
                    errorText,
                    "Retry"));
            items.add(UItem.asShadow(null));
        }

        if (loading && serviceStatuses.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        items.add(UItem.asHeader("Live Diagnostics"));
        items.add(SettingsActivity.SettingCell.Factory.of(
                ID_AI_PROBE,
                IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                R.drawable.msg_stats,
                probingAi ? "Probing AI Provider..." : "Run AI Provider Health Probe",
                "Test live Gemini key slots and model fallback chain",
                null));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Service Statuses"));
        if (serviceStatuses.isEmpty()) {
            items.add(UItem.asShadow("No service status entries returned."));
            return;
        }

        for (int i = 0; i < serviceStatuses.size(); i++) {
            EveAdminModels.SystemServiceStatus s = serviceStatuses.get(i);
            String statusLower = s.status != null ? s.status.toLowerCase(Locale.US) : "";
            IconBackgroundColors bg;
            int iconRes;
            String badge;
            if (statusLower.contains("verified") || statusLower.contains("ok") || statusLower.contains("healthy") || statusLower.contains("online") || statusLower.contains("up")) {
                bg = IconBackgroundColors.GREEN;
                iconRes = R.drawable.msg_select;
                badge = "Verified";
            } else if (statusLower.contains("configured") && !statusLower.contains("unconfigured")) {
                bg = IconBackgroundColors.BLUE;
                iconRes = R.drawable.msg_info;
                badge = "Configured";
            } else if (statusLower.contains("warn") || statusLower.contains("degrad")) {
                bg = IconBackgroundColors.ORANGE;
                iconRes = R.drawable.msg_warning;
                badge = "Degraded";
            } else {
                bg = IconBackgroundColors.RED;
                iconRes = R.drawable.msg_close;
                badge = formatStatusBadge(s.status);
            }

            items.add(SettingsActivity.SettingCell.Factory.of(
                    BASE_SERVICE_ID + i,
                    bg.top, bg.bottom,
                    iconRes,
                    friendlyServiceName(s.name),
                    s.detail != null && !s.detail.isEmpty() ? s.detail : "No detail reported",
                    badge));
        }
        items.add(UItem.asShadow("Statuses reflect real-time Worker checks for D1, Firebase Auth, storage bindings, and AI configuration."));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
        } else if (item.id == ID_AI_PROBE) {
            runAiProbe();
        } else if (item.id >= BASE_SERVICE_ID) {
            int idx = item.id - BASE_SERVICE_ID;
            if (idx >= 0 && idx < serviceStatuses.size()) {
                EveAdminModels.SystemServiceStatus s = serviceStatuses.get(idx);
                showAlert(friendlyServiceName(s.name) + " (" + formatStatusBadge(s.status) + ")", s.detail);
            }
        }
    }

    private void runAiProbe() {
        if (probingAi || destroyed) return;
        probingAi = true;
        updateList();
        EveAdminRepository.ResultCallback<JSONObject> cb = new EveAdminRepository.ResultCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject obj) {
                if (destroyed) return;
                probingAi = false;
                updateList();
                if (obj == null) {
                    showAlert("AI Provider Probe", "No response received from AI health endpoint.");
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("Primary Model: ").append(obj.optString("primaryModel", "-")).append('\n');
                String working = obj.optString("workingModel", "");
                sb.append("Working Model: ").append(working.isEmpty() || "null".equals(working) ? "None available" : working).append('\n');
                JSONArray slots = obj.optJSONArray("slots");
                if (slots != null && slots.length() > 0) {
                    sb.append('\n');
                    for (int i = 0; i < slots.length(); i++) {
                        JSONObject slot = slots.optJSONObject(i);
                        if (slot == null) continue;
                        sb.append("• ").append(slot.optString("slot", "Key"))
                                .append(": ").append(slot.optBoolean("ok", false) ? "Healthy" : slot.optString("providerStatus", "Failed"))
                                .append(" (").append(slot.optInt("latencyMs", 0)).append(" ms)");
                        String msg = slot.optString("message", "");
                        if (!msg.isEmpty() && !"Healthy".equalsIgnoreCase(msg)) {
                            sb.append(" — ").append(msg);
                        }
                        sb.append('\n');
                    }
                }
                showAlert("AI Provider Health Probe", sb.toString().trim());
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                probingAi = false;
                updateList();
                BulletinFactory.of(EveAdminSystemHealthFragment.this).createErrorBulletin(message).show();
            }
        };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.checkAiHealth(token)));
    }

    private static String friendlyServiceName(String raw) {
        if (raw == null) return "Service";
        switch (raw.toLowerCase(Locale.US)) {
            case "backend": return "Backend Worker API";
            case "database": return "Cloudflare D1 Database";
            case "firebase": return "Firebase Authentication";
            case "firestore": return "Cloud Firestore";
            case "messaging": return "FCM Push Messaging";
            case "media": return "Media Storage";
            case "r2": return "Cloudflare R2 Bucket";
            case "ai": return "AI Generation Engine";
            default: return EveAdminUiChrome.formatActionTitle(raw);
        }
    }

    private static String formatStatusBadge(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Unknown";
        return EveAdminUiChrome.formatActionTitle(raw);
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
}
