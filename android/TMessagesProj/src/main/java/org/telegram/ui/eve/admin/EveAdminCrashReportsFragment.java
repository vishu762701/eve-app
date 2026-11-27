package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen Crash Reports, App & API Errors, and Security Alerts Monitor.
 * Presents human-readable explanations, affected screens, full stack report copying,
 * and one-tap crash resolution.
 */
public class EveAdminCrashReportsFragment extends BaseFragment {

    private static final int TAB_CRASHES = 0;
    private static final int TAB_APP_ERRORS = 1;
    private static final int TAB_SECURITY = 2;

    private static final int ID_SEGMENT_TABS = 100;
    private static final int ID_TOGGLE_RESOLVED = 101;
    private static final int ID_RETRY = 102;
    private static final int BASE_ITEM_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private final EveAdminModels.AdminProfile profile;
    private UniversalRecyclerView listView;
    private LinearLayout tabsContainer;
    private final TextView[] tabViews = new TextView[3];

    private int activeTab = TAB_CRASHES;
    private boolean showResolved = false;
    private boolean loading = true;
    private boolean destroyed = false;
    private String errorText = "";

    private List<EveAdminModels.CrashSignatureItem> crashes = Collections.emptyList();
    private List<EveAdminModels.SecurityEventItem> allEvents = Collections.emptyList();
    private int securityCount = 0;
    private int appErrorCount = 0;
    private final EveAdminUiChrome.AutoSyncController autoSync = new EveAdminUiChrome.AutoSyncController(this::reload);

    public EveAdminCrashReportsFragment(@Nullable EveAdminModels.AdminProfile profile) {
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
        buildTabsView(context);

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "System & Security Monitor",
                "Crashes, app errors & security alerts",
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
                this::updateTabColors
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

    private void buildTabsView(Context context) {
        tabsContainer = new LinearLayout(context);
        tabsContainer.setOrientation(LinearLayout.HORIZONTAL);
        tabsContainer.setPadding(dp(10), dp(8), dp(10), dp(8));

        String[] titles = new String[] { "Crashes", "App & API Errors", "Security Alerts" };
        for (int i = 0; i < 3; i++) {
            final int tabIdx = i;
            TextView tv = new TextView(context);
            tv.setText(titles[i]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            tv.setTypeface(AndroidUtilities.bold());
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setPadding(dp(8), dp(8), dp(8), dp(8));
            tv.setOnClickListener(v -> {
                if (activeTab != tabIdx) {
                    activeTab = tabIdx;
                    updateTabColors();
                    updateSubtitle();
                    updateList();
                }
            });
            tabViews[i] = tv;
            tabsContainer.addView(tv, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 3, 0, 3, 0));
        }
        updateTabColors();
    }

    private void updateTabColors() {
        Theme.ResourcesProvider rp = getResourceProvider();
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp);
        int textBlack = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp);
        int textGray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp);
        for (int i = 0; i < tabViews.length; i++) {
            TextView tv = tabViews[i];
            if (tv == null) continue;
            boolean selected = i == activeTab;
            tv.setTextColor(selected ? accent : textGray);
            tv.setBackground(Theme.createRoundRectDrawable(dp(10),
                    selected ? Theme.multAlpha(accent, 0.14f) : Theme.multAlpha(textBlack, 0.05f)));
        }
    }

    private void reload() {
        if (destroyed) return;
        loading = crashes.isEmpty() && allEvents.isEmpty();
        errorText = "";
        if (loading) {
            updateList();
        }
        final String crashStatus = showResolved ? "resolved" : "open";
        EveAdminRepository.ResultCallback<Object> cb = new EveAdminRepository.ResultCallback<Object>() {
            @Override
            public void onSuccess(Object ignored) {}

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                loading = false;
                autoSync.markSynced();
                errorText = message != null ? message : "Unable to load monitoring telemetry.";
                updateSubtitle();
                updateList();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            List<EveAdminModels.CrashSignatureItem> fetchedCrashes = repo.fetchCrashSignatures(token, crashStatus);
            EveAdminModels.SecurityEventsBundle bundle = repo.fetchSecurityEvents(token);
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed) return;
                loading = false;
                errorText = "";
                autoSync.markSynced();
                crashes = fetchedCrashes != null ? fetchedCrashes : Collections.emptyList();
                if (bundle != null) {
                    allEvents = bundle.events;
                    securityCount = bundle.securityCount;
                    appErrorCount = bundle.errorCount;
                }
                updateTabLabels();
                updateSubtitle();
                updateList();
            });
        });
    }

    private void updateTabLabels() {
        if (tabViews[0] != null) tabViews[0].setText("Crashes (" + crashes.size() + ")");
        if (tabViews[1] != null) tabViews[1].setText("Errors (" + appErrorCount + ")");
        if (tabViews[2] != null) tabViews[2].setText("Security (" + securityCount + ")");
    }

    private void updateSubtitle() {
        if (simpleActionBar == null) return;
        if (!errorText.isEmpty() && crashes.isEmpty() && allEvents.isEmpty()) {
            simpleActionBar.setSubtitle("Unable to load");
            return;
        }
        if (activeTab == TAB_CRASHES) {
            simpleActionBar.setSubtitle(crashes.size() + (showResolved ? " resolved crash(es)" : " open crash(es)"));
        } else if (activeTab == TAB_APP_ERRORS) {
            simpleActionBar.setSubtitle(appErrorCount + " backend/app error(s)");
        } else {
            simpleActionBar.setSubtitle(securityCount + " security alert(s)");
        }
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private List<EveAdminModels.SecurityEventItem> getTabEvents() {
        List<EveAdminModels.SecurityEventItem> out = new ArrayList<>();
        boolean wantSecurity = activeTab == TAB_SECURITY;
        for (EveAdminModels.SecurityEventItem e : allEvents) {
            if (e.isSecurityAlert() == wantSecurity) {
                out.add(e);
            }
        }
        return out;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        items.add(UItem.asCustom(ID_SEGMENT_TABS, tabsContainer));
        items.add(UItem.asShadow(null));

        if (!errorText.isEmpty()) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_RETRY,
                    IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                    R.drawable.msg_retry,
                    "Retry Loading Telemetry",
                    errorText,
                    "Retry"));
            items.add(UItem.asShadow(null));
        }

        if (loading && crashes.isEmpty() && allEvents.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (activeTab == TAB_CRASHES) {
            UItem resolvedToggle = UItem.asCheck(ID_TOGGLE_RESOLVED, "Show Resolved Crashes");
            resolvedToggle.checked = showResolved;
            items.add(resolvedToggle);
            items.add(UItem.asShadow(null));

            items.add(UItem.asHeader(showResolved ? "Resolved Crash Signatures" : "Active Crash Queue"));
            if (crashes.isEmpty()) {
                items.add(UItem.asShadow(showResolved
                        ? "No resolved crash signatures recorded."
                        : "Zero active crashes! All reported crash signatures are resolved."));
                return;
            }
            for (int i = 0; i < crashes.size(); i++) {
                EveAdminModels.CrashSignatureItem c = crashes.get(i);
                String title = !TextUtils.isEmpty(c.plainEnglishSummary)
                        ? c.plainEnglishSummary
                        : buildFallbackCrashTitle(c);
                String where = !TextUtils.isEmpty(c.whereItHappened) ? c.whereItHappened : c.topFrame;
                String sub = where + " • Last seen " + EveAdminUiChrome.formatDateTime(c.lastSeen);
                items.add(SettingsActivity.SettingCell.Factory.of(
                        BASE_ITEM_ID + i,
                        c.isResolved() ? IconBackgroundColors.GREEN.top : IconBackgroundColors.RED.top,
                        c.isResolved() ? IconBackgroundColors.GREEN.bottom : IconBackgroundColors.RED.bottom,
                        c.isResolved() ? R.drawable.msg_select : R.drawable.msg_report,
                        title,
                        sub,
                        c.occurrenceCount + "x"));
            }
            items.add(UItem.asShadow("Tap any crash to view device details, copy full report, or mark it fixed."));
            return;
        }

        List<EveAdminModels.SecurityEventItem> tabEvents = getTabEvents();
        items.add(UItem.asHeader(activeTab == TAB_SECURITY ? "Security & Access Alerts" : "App & Backend API Errors"));
        if (tabEvents.isEmpty()) {
            items.add(UItem.asShadow(activeTab == TAB_SECURITY
                    ? "No unauthorized admin probes or rate-limit alerts recorded."
                    : "No failed submissions or generation errors recorded."));
            return;
        }
        for (int i = 0; i < tabEvents.size(); i++) {
            EveAdminModels.SecurityEventItem ev = tabEvents.get(i);
            IconBackgroundColors bg;
            int iconRes;
            if ("high".equalsIgnoreCase(ev.severity)) {
                bg = IconBackgroundColors.RED;
                iconRes = R.drawable.msg_warning;
            } else if ("medium".equalsIgnoreCase(ev.severity)) {
                bg = IconBackgroundColors.ORANGE;
                iconRes = R.drawable.msg_report;
            } else {
                bg = IconBackgroundColors.BLUE;
                iconRes = R.drawable.msg_info;
            }
            String sub = ev.plainEnglishDetail + " • " + EveAdminUiChrome.formatDateTime(ev.timestamp);
            items.add(SettingsActivity.SettingCell.Factory.of(
                    BASE_ITEM_ID + i,
                    bg.top, bg.bottom,
                    iconRes,
                    !ev.plainEnglishTitle.isEmpty() ? ev.plainEnglishTitle : EveAdminUiChrome.formatActionTitle(ev.category),
                    sub,
                    "HTTP " + ev.status));
        }
        items.add(UItem.asShadow("Telemetry is sourced live from D1 operation & auth protection logs."));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
            return;
        }
        if (item.id == ID_TOGGLE_RESOLVED) {
            showResolved = !showResolved;
            crashes = Collections.emptyList();
            reload();
            return;
        }
        int idx = item.id - BASE_ITEM_ID;
        if (activeTab == TAB_CRASHES && idx >= 0 && idx < crashes.size()) {
            openCrashDetail(crashes.get(idx));
        } else if (activeTab != TAB_CRASHES) {
            List<EveAdminModels.SecurityEventItem> tabEvents = getTabEvents();
            if (idx >= 0 && idx < tabEvents.size()) {
                openSecurityEventDetail(tabEvents.get(idx));
            }
        }
    }

    private void openCrashDetail(EveAdminModels.CrashSignatureItem crash) {
        Activity act = getParentActivity();
        if (act == null) return;
        Theme.ResourcesProvider rp = getResourceProvider();

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));

        String causeText = !TextUtils.isEmpty(crash.plainEnglishSummary)
                ? crash.plainEnglishSummary
                : buildFallbackCrashTitle(crash);
        String whereText = !TextUtils.isEmpty(crash.whereItHappened) ? crash.whereItHappened : crash.topFrame;

        addSectionText(act, root, "What Went Wrong", causeText, true, rp);
        addSectionText(act, root, "Where It Happened", whereText, false, rp);
        final TextView deviceInfoView = addSectionText(act, root, "Impact & Device Info",
                crash.occurrenceCount + " occurrences • Last seen " + EveAdminUiChrome.formatDateTime(crash.lastSeen) + "\nLoading device & version details...", false, rp);

        final TextView stackHeader = new TextView(act);
        stackHeader.setText("Show Technical Stack Trace ▾");
        stackHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        stackHeader.setTypeface(AndroidUtilities.bold());
        stackHeader.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp));
        stackHeader.setPadding(0, dp(10), 0, dp(6));
        root.addView(stackHeader);

        final TextView stackView = new TextView(act);
        stackView.setText(crash.exceptionClass + "\n" + crash.topFrame + (crash.sanitizedMessage.isEmpty() ? "" : "\n" + crash.sanitizedMessage));
        stackView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        stackView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        stackView.setVisibility(View.GONE);
        stackView.setPadding(dp(10), dp(8), dp(10), dp(8));
        stackView.setBackground(Theme.createRoundRectDrawable(dp(8),
                Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp), 0.10f)));
        root.addView(stackView);

        stackHeader.setOnClickListener(v -> {
            boolean show = stackView.getVisibility() != View.VISIBLE;
            stackView.setVisibility(show ? View.VISIBLE : View.GONE);
            stackHeader.setText(show ? "Hide Technical Stack Trace ▴" : "Show Technical Stack Trace ▾");
        });

        final String[] fullReportHolder = new String[] {
                buildFullCrashClipboardReport(crash, causeText, whereText, null)
        };

        LinearLayout buttonsRow = new LinearLayout(act);
        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonsRow.setPadding(0, dp(16), 0, 0);

        TextView copyBtn = makeSheetButton(act, "Copy Full Report",
                Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp), rp);
        buttonsRow.addView(copyBtn, LayoutHelper.createLinear(0, 44, 1f, 0, 0, 6, 0));

        BottomSheet sheet = new BottomSheet(act, false, rp);
        copyBtn.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(fullReportHolder[0]);
            BulletinFactory.of(EveAdminCrashReportsFragment.this).createCopyBulletin("Crash report copied").show();
        });

        if (!crash.isResolved()) {
            TextView resolveBtn = makeSheetButton(act, "Mark Fixed", 0xFF27B434, rp);
            resolveBtn.setOnClickListener(v -> {
                sheet.dismiss();
                markCrashResolved(crash);
            });
            buttonsRow.addView(resolveBtn, LayoutHelper.createLinear(0, 44, 1f, 6, 0, 0, 0));
        } else if (profile != null && profile.isOwner) {
            TextView deleteBtn = makeSheetButton(act, "Delete", Theme.getColor(Theme.key_text_RedRegular, rp), rp);
            deleteBtn.setOnClickListener(v -> {
                sheet.dismiss();
                deleteCrashSignature(crash);
            });
            buttonsRow.addView(deleteBtn, LayoutHelper.createLinear(0, 44, 1f, 6, 0, 0, 0));
        }

        root.addView(buttonsRow);

        ScrollView scroll = new ScrollView(act);
        scroll.addView(root);
        sheet.setCustomView(scroll);
        showDialog(sheet);

        EveAdminRepository.ResultCallback<EveAdminModels.CrashDetailBundle> detailCb =
                new EveAdminRepository.ResultCallback<EveAdminModels.CrashDetailBundle>() {
                    @Override
                    public void onSuccess(EveAdminModels.CrashDetailBundle bundle) {
                        if (destroyed || bundle == null) return;
                        StringBuilder info = new StringBuilder();
                        info.append(crash.occurrenceCount).append(" occurrences • Last seen ")
                                .append(EveAdminUiChrome.formatDateTime(crash.lastSeen));
                        if (!bundle.incidents.isEmpty()) {
                            EveAdminModels.CrashIncidentItem latest = bundle.incidents.get(0);
                            info.append("\nLatest: v").append(latest.appVersionName.isEmpty() ? "?" : latest.appVersionName)
                                    .append(" (").append(latest.appVersionCode).append(")")
                                    .append(" • ").append(latest.deviceModel.isEmpty() ? "Android" : latest.deviceModel)
                                    .append(" (API ").append(latest.androidSdk).append(")");
                            String framesText = formatFramesJson(latest.sanitizedFramesJson);
                            if (!framesText.isEmpty()) {
                                stackView.setText(crash.exceptionClass
                                        + (crash.sanitizedMessage.isEmpty() ? "" : ": " + crash.sanitizedMessage)
                                        + "\n\n" + framesText);
                            }
                        }
                        deviceInfoView.setText(info.toString());
                        fullReportHolder[0] = buildFullCrashClipboardReport(crash, causeText, whereText, bundle);
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (!destroyed) {
                            deviceInfoView.setText(crash.occurrenceCount + " occurrences • Last seen "
                                    + EveAdminUiChrome.formatDateTime(crash.lastSeen));
                        }
                    }
                };
        repo.runWithAdminToken(detailCb, token -> repo.postSuccess(detailCb, repo.fetchCrashDetail(token, crash.signature)));
    }

    private void markCrashResolved(EveAdminModels.CrashSignatureItem crash) {
        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void ignored) {
                if (destroyed) return;
                List<EveAdminModels.CrashSignatureItem> next = new ArrayList<>();
                for (EveAdminModels.CrashSignatureItem item : crashes) {
                    if (!TextUtils.equals(item.signature, crash.signature)) {
                        next.add(item);
                    }
                }
                crashes = next;
                updateTabLabels();
                updateSubtitle();
                updateList();
                BulletinFactory.of(EveAdminCrashReportsFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, "Marked fixed and cleared from active queue.").show();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (!destroyed) {
                    BulletinFactory.of(EveAdminCrashReportsFragment.this).createErrorBulletin(message).show();
                }
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.resolveCrashSignature(token, crash.signature);
            repo.postSuccess(cb, null);
        });
    }

    private void deleteCrashSignature(EveAdminModels.CrashSignatureItem crash) {
        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void ignored) {
                if (destroyed) return;
                BulletinFactory.of(EveAdminCrashReportsFragment.this)
                        .createSimpleBulletin(R.raw.ic_delete, "Crash signature deleted.").show();
                reload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (!destroyed) {
                    BulletinFactory.of(EveAdminCrashReportsFragment.this).createErrorBulletin(message).show();
                }
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.deleteCrashSignature(token, crash.signature);
            repo.postSuccess(cb, null);
        });
    }

    private void openSecurityEventDetail(EveAdminModels.SecurityEventItem ev) {
        Activity act = getParentActivity();
        if (act == null) return;
        String report = "Title: " + ev.plainEnglishTitle
                + "\nCategory: " + ev.category
                + "\nSeverity: " + ev.severity.toUpperCase(Locale.US)
                + "\nHTTP Status: " + ev.status
                + "\nOperation: " + ev.operation
                + "\nTime: " + EveAdminUiChrome.formatDateTime(ev.timestamp)
                + (ev.correlationId.isEmpty() ? "" : "\nContext: " + ev.correlationId)
                + "\n\nSummary:\n" + ev.plainEnglishDetail;
        showDialog(new AlertDialog.Builder(act, getResourceProvider())
                .setTitle(ev.isSecurityAlert() ? "Security Alert" : "App / API Error")
                .setMessage(report)
                .setPositiveButton("Copy Report", (d, w) -> {
                    AndroidUtilities.addToClipboard(report);
                    BulletinFactory.of(this).createCopyBulletin("Report copied").show();
                })
                .setNegativeButton(LocaleController.getString(R.string.Close), null)
                .create());
    }

    private static TextView addSectionText(Context ctx, LinearLayout parent, String label, String value, boolean boldValue, Theme.ResourcesProvider rp) {
        TextView lbl = new TextView(ctx);
        lbl.setText(label);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        lbl.setTypeface(AndroidUtilities.bold());
        lbl.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        lbl.setPadding(0, dp(8), 0, dp(2));
        parent.addView(lbl);

        TextView val = new TextView(ctx);
        val.setText(value);
        val.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        if (boldValue) {
            val.setTypeface(AndroidUtilities.bold());
        }
        val.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        parent.addView(val);
        return val;
    }

    private static TextView makeSheetButton(Context ctx, String text, int color, Theme.ResourcesProvider rp) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        btn.setTypeface(AndroidUtilities.bold());
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(color);
        btn.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(color, 0.14f)));
        return btn;
    }

    private static String buildFallbackCrashTitle(EveAdminModels.CrashSignatureItem c) {
        String ex = c.exceptionClass;
        if (ex.contains("NullPointerException")) return "Missing null value accessed in app";
        if (ex.contains("OutOfMemoryError")) return "Device ran out of memory";
        if (ex.contains("IllegalStateException")) return "Screen lifecycle state error";
        int dot = ex.lastIndexOf('.');
        String shortName = dot >= 0 && dot + 1 < ex.length() ? ex.substring(dot + 1) : ex;
        return shortName.isEmpty() ? "Unexpected app crash" : shortName;
    }

    private static String formatFramesJson(String json) {
        if (json == null || json.trim().isEmpty()) return "";
        try {
            JSONArray arr = new JSONArray(json);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                if (i > 0) sb.append('\n');
                sb.append("at ").append(arr.optString(i, ""));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return json;
        }
    }

    private static String buildFullCrashClipboardReport(
            EveAdminModels.CrashSignatureItem crash,
            String causeText,
            String whereText,
            @Nullable EveAdminModels.CrashDetailBundle bundle) {
        StringBuilder sb = new StringBuilder();
        sb.append("EVE Crash Report\n");
        sb.append("Cause: ").append(causeText).append('\n');
        sb.append("Where: ").append(whereText).append('\n');
        sb.append("Occurrences: ").append(crash.occurrenceCount).append('\n');
        sb.append("Last Seen: ").append(EveAdminUiChrome.formatDateTime(crash.lastSeen)).append('\n');
        sb.append("Signature: ").append(crash.signature).append('\n');
        sb.append("Exception: ").append(crash.exceptionClass).append('\n');
        if (!crash.sanitizedMessage.isEmpty()) {
            sb.append("Message: ").append(crash.sanitizedMessage).append('\n');
        }
        sb.append("Top Frame: ").append(crash.topFrame).append('\n');
        if (bundle != null && !bundle.incidents.isEmpty()) {
            EveAdminModels.CrashIncidentItem latest = bundle.incidents.get(0);
            sb.append("Version: ").append(latest.appVersionName).append(" (").append(latest.appVersionCode).append(")\n");
            sb.append("Device: ").append(latest.deviceModel).append(" (API ").append(latest.androidSdk).append(")\n");
            String frames = formatFramesJson(latest.sanitizedFramesJson);
            if (!frames.isEmpty()) {
                sb.append("\nStack Trace:\n").append(frames);
            }
        }
        return sb.toString();
    }
}
