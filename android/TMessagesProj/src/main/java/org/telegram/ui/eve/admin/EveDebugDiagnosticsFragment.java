package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.EveCrashDiagnostics;
import org.telegram.messenger.EveInspectionMode;
import org.telegram.messenger.EveLoadingTracker;
import org.telegram.messenger.NativeLoader;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Isolated DEBUG-build local diagnostics screen.
 * Separated from normal user Settings and from the production Admin Dashboard.
 * Grants zero backend administrative authority.
 */
public class EveDebugDiagnosticsFragment extends BaseFragment {

    private static final int ID_CRASH = 1;
    private static final int ID_LOADING = 2;
    private static final int ID_EVENTS = 3;
    private static final int ID_EXPORT = 4;
    private static final int ID_RLOTTIE = 5;
    private static final int ID_CACHE_CLEAR = 6;

    private UniversalRecyclerView listView;

    @Override
    public boolean onFragmentCreate() {
        return EveInspectionMode.isEnabledForCurrentBuild() && super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Internal Debug Diagnostics");
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
        EveAdminConfig.CacheStats stats = EveAdminConfig.inspectCache(getContext());
        items.add(UItem.asHeader("Internal Debug Diagnostics (DEBUG Build Only)"));
        items.add(UItem.asButton(ID_CRASH, "Local Crash Reports & ApplicationExitInfo"));
        items.add(UItem.asButton(ID_LOADING, "Loading States Viewer", EveLoadingTracker.countNonTerminal() + " active"));
        items.add(UItem.asButton(ID_EVENTS, "Recent Nav / Network Events", String.valueOf(EveCrashDiagnostics.getRecentEvents().size())));
        items.add(UItem.asButton(ID_EXPORT, "Export Redacted Diagnostic Report"));
        items.add(UItem.asButton(ID_RLOTTIE, "Run RLottie Native Probe", NativeLoader.loaded() ? "Native OK" : "Not Loaded"));
        items.add(UItem.asButton(ID_CACHE_CLEAR, "Clear Local Cache",
                String.format(Locale.US, "%d files (%.1f MB)", stats.fileCount, stats.totalBytes / (1024f * 1024f))));
        items.add(UItem.asShadow("Local diagnostics never grant backend administrative privileges."));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (!EveInspectionMode.isEnabledForCurrentBuild()) {
            return;
        }
        switch (item.id) {
            case ID_CRASH:
                showInfo("Crash Reports & Exit Reasons",
                        "=== Process Exit Reasons ===\n"
                                + EveCrashDiagnostics.getLastExitSummary(getContext())
                                + "\n\n=== Last Uncaught Java Crash ===\n"
                                + EveCrashDiagnostics.getLastCrashReport(getContext()));
                break;
            case ID_LOADING:
                List<EveLoadingTracker.Entry> entries = EveLoadingTracker.snapshot();
                StringBuilder sb = new StringBuilder();
                for (EveLoadingTracker.Entry e : entries) {
                    sb.append("• ").append(e.module).append(": ").append(e.outcome).append(" (").append(e.detail).append(")\n");
                }
                showInfo("Loading States", sb.length() > 0 ? sb.toString().trim() : "No loading trackers recorded.");
                break;
            case ID_EVENTS:
                List<String> events = EveCrashDiagnostics.getRecentEvents();
                StringBuilder ev = new StringBuilder();
                for (String line : events) ev.append("• ").append(line).append("\n");
                showInfo("Recent Events", ev.length() > 0 ? ev.toString().trim() : "No recent events recorded.");
                break;
            case ID_EXPORT:
                String report = EveCrashDiagnostics.buildSanitizedReport(getContext());
                AndroidUtilities.addToClipboard(report);
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_SUBJECT, "EVE Diagnostic Report (Redacted)");
                share.putExtra(Intent.EXTRA_TEXT, report);
                Activity act = getParentActivity();
                if (act != null) {
                    act.startActivity(Intent.createChooser(share, "Export Redacted Diagnostic Report"));
                }
                break;
            case ID_RLOTTIE:
                RLottieDrawable d = new RLottieDrawable(R.raw.sun_outline, AndroidUtilities.dp(28), AndroidUtilities.dp(28), true, null);
                int frames = d.getFramesCount();
                d.recycle(false);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "RLottie native OK (frames=" + frames + ")").show();
                break;
            case ID_CACHE_CLEAR:
                int deleted = EveAdminConfig.clearSafeCache(getContext());
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Cleared " + deleted + " cache files.").show();
                if (listView != null && listView.adapter != null) listView.adapter.update(true);
                break;
        }
    }

    private void showInfo(String title, String message) {
        Activity act = getParentActivity();
        if (act == null) return;
        showDialog(new AlertDialog.Builder(act, getResourceProvider())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .create());
    }
}
