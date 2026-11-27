package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.SimpleActionBarMenuItem;
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
import java.util.regex.Pattern;

/**
 * Categorized Telegram Settings-style Audit Log viewer with human-readable summaries
 * and Owner-only log clearing.
 */
public class EveAdminAuditLogFragment extends BaseFragment {

    public static final String CAT_EXAMS = "exams";
    public static final String CAT_STUDENTS = "students";
    public static final String CAT_CONTENT = "content";
    public static final String CAT_SYSTEM = "system";
    public static final String CAT_ALL = "all";

    private static final int MENU_SEARCH = 1;
    private static final int MENU_CLEAR = 2;

    private static final int ID_RETRY = 101;
    private static final int ID_CAT_EXAMS = 201;
    private static final int ID_CAT_STUDENTS = 202;
    private static final int ID_CAT_CONTENT = 203;
    private static final int ID_CAT_SYSTEM = 204;
    private static final int ID_CAT_ALL = 205;
    private static final int BASE_LOG_ID = 1000;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "'?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'?");

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private ActionBarMenuItem searchItem;
    private SimpleActionBarMenuItem clearItem;
    private boolean loading = true;
    private boolean destroyed = false;
    private boolean isOwner = false;
    private String errorText = "";
    private String searchQuery = "";
    @Nullable
    private String selectedCategory = null;
    private List<EveAdminModels.AuditLogEntry> entries = Collections.emptyList();
    private final List<EveAdminModels.AuditLogEntry> filteredEntries = new ArrayList<>();
    private final EveAdminUiChrome.AutoSyncController autoSync = new EveAdminUiChrome.AutoSyncController(this::reload);

    @Override
    public boolean onFragmentCreate() {
        if (!EveAdminConfig.isAdminAvailable()) {
            return false;
        }
        EveAdminModels.DashboardSnapshot cached = repo.getCachedSnapshot();
        if (cached != null && cached.adminProfile != null) {
            isOwner = cached.adminProfile.isOwner;
        }
        return super.onFragmentCreate();
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
    public boolean onBackPressed(boolean invoked) {
        if (simpleActionBar != null && simpleActionBar.isSearchFieldVisible()) {
            if (invoked) {
                simpleActionBar.closeSearchField();
            }
            return false;
        }
        if (selectedCategory != null) {
            if (invoked) {
                selectedCategory = null;
                applyFilter(searchQuery);
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public View createView(Context context) {
        destroyed = false;
        setHasOwnBackground(true);

        List<EveAdminModels.AuditLogEntry> cachedEntries = repo.getCachedAuditLogs();
        if (cachedEntries != null && !cachedEntries.isEmpty()) {
            entries = cachedEntries;
            loading = false;
        }

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Audit Log",
                entries.isEmpty() ? "Loading..." : entries.size() + " recent entries",
                true,
                true,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            if (selectedCategory != null) {
                                selectedCategory = null;
                                applyFilter(searchQuery);
                            } else {
                                finishFragment();
                            }
                        } else if (id == MENU_CLEAR) {
                            confirmClearLogs();
                        }
                    }
                },
                null
        );
        listView = chrome.listView;

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            searchItem = menu.addItem(MENU_SEARCH, R.drawable.outline_header_search, getResourceProvider())
                    .setIsSearchField(true)
                    .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                        @Override
                        public void onSearchExpand() {
                            applyFilter("");
                        }

                        @Override
                        public void onSearchCollapse() {
                            applyFilter("");
                        }

                        @Override
                        public void onTextChanged(EditText editText) {
                            String next = editText.getText() != null ? editText.getText().toString() : "";
                            applyFilter(next);
                        }
                    });
            searchItem.setSearchFieldHint("Search action, summary, or admin");
            clearItem = menu.addItem(MENU_CLEAR, R.drawable.msg_delete, getResourceProvider());
            clearItem.setVisibility(isOwner ? View.VISIBLE : View.GONE);
        }

        if (!entries.isEmpty()) {
            applyFilter(searchQuery);
        }
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
        loading = entries.isEmpty();
        errorText = "";
        if (loading) {
            updateList();
        }

        EveAdminRepository.ResultCallback<List<EveAdminModels.AuditLogEntry>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.AuditLogEntry>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.AuditLogEntry> data) {
                        if (destroyed) return;
                        loading = false;
                        errorText = "";
                        autoSync.markSynced();
                        entries = data != null ? data : Collections.emptyList();
                        if (clearItem != null) {
                            clearItem.setVisibility(isOwner ? View.VISIBLE : View.GONE);
                        }
                        applyFilter(searchQuery);
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
                        autoSync.markSynced();
                        errorText = message != null ? message : "Unable to load audit log.";
                        updateHeader();
                        updateList();
                    }
                };
        repo.runWithAdminToken(cb, token -> {
            if (!isOwner) {
                try {
                    EveAdminModels.AdminProfile me = repo.fetchAdminMe(token);
                    if (me != null) {
                        isOwner = me.isOwner;
                    }
                } catch (Throwable ignored) {
                }
            }
            repo.postSuccess(cb, repo.fetchAuditLogs(token, 100));
        });
    }

    private void applyFilter(String query) {
        searchQuery = query != null ? query.trim() : "";
        filteredEntries.clear();
        String q = searchQuery.toLowerCase(Locale.US);
        String activeCat = selectedCategory;
        for (EveAdminModels.AuditLogEntry e : entries) {
            if (activeCat != null && !CAT_ALL.equals(activeCat) && !activeCat.equals(classifyCategory(e.actionType))) {
                continue;
            }
            if (q.isEmpty()
                    || formatHumanTitle(e).toLowerCase(Locale.US).contains(q)
                    || e.actionType.toLowerCase(Locale.US).contains(q)
                    || e.description.toLowerCase(Locale.US).contains(q)
                    || e.adminEmail.toLowerCase(Locale.US).contains(q)) {
                filteredEntries.add(e);
            }
        }
        updateHeader();
        updateList();
    }

    private void updateHeader() {
        if (simpleActionBar == null) return;
        if (selectedCategory != null) {
            simpleActionBar.setTitle(categoryTitle(selectedCategory));
        } else {
            simpleActionBar.setTitle("Audit Log");
        }
        if (!errorText.isEmpty() && entries.isEmpty()) {
            simpleActionBar.setSubtitle("Unable to load");
        } else if (!searchQuery.isEmpty()) {
            simpleActionBar.setSubtitle(filteredEntries.size() + " matching");
        } else if (selectedCategory != null) {
            simpleActionBar.setSubtitle(filteredEntries.size() + " entries");
        } else {
            simpleActionBar.setSubtitle(entries.size() + " recent entries");
        }
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private boolean isShowingCategoriesHome() {
        return selectedCategory == null && searchQuery.isEmpty();
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        if (!errorText.isEmpty()) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_RETRY,
                    IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                    R.drawable.msg_retry,
                    "Retry Loading Audit Log",
                    errorText,
                    "Retry"));
            items.add(UItem.asShadow(null));
        }

        if (loading && entries.isEmpty()) {
            for (int i = 0; i < 5; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (isShowingCategoriesHome()) {
            int examsCount = countByCategory(CAT_EXAMS);
            int studentsCount = countByCategory(CAT_STUDENTS);
            int contentCount = countByCategory(CAT_CONTENT);
            int systemCount = countByCategory(CAT_SYSTEM);

            items.add(UItem.asHeader("Audit Categories"));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_CAT_EXAMS,
                    IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                    R.drawable.msg_folders,
                    "Exams & Tests",
                    "Exams, mock tests, questions & syllabus actions",
                    String.valueOf(examsCount)));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_CAT_STUDENTS,
                    IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                    R.drawable.settings_account,
                    "Students & Premium",
                    "Account status, premium grants, orders & plans",
                    String.valueOf(studentsCount)));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_CAT_CONTENT,
                    IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                    R.drawable.settings_sounds,
                    "Content & Polls",
                    "Polls, broadcasts, banners, reports & feedback",
                    String.valueOf(contentCount)));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_CAT_SYSTEM,
                    IconBackgroundColors.BLUE_DEEP.top, IconBackgroundColors.BLUE_DEEP.bottom,
                    R.drawable.settings_privacy,
                    "Admin & System",
                    "Admin roles, maintenance, crashes & config",
                    String.valueOf(systemCount)));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_CAT_ALL,
                    IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                    R.drawable.msg_log,
                    "All Activity",
                    "Full chronological stream across all categories",
                    String.valueOf(entries.size())));
            items.add(UItem.asShadow(isOwner
                    ? "Tap a category to inspect activity, or tap delete above to clear logs."
                    : "Tap any category to filter activity."));
            return;
        }

        items.add(UItem.asHeader(selectedCategory != null ? categoryTitle(selectedCategory) : "Search Results"));
        if (filteredEntries.isEmpty()) {
            items.add(UItem.asShadow("No audit entries found in this view."));
            return;
        }

        for (int i = 0; i < filteredEntries.size(); i++) {
            EveAdminModels.AuditLogEntry entry = filteredEntries.get(i);
            String upper = entry.actionType.toUpperCase(Locale.US);
            int topColor;
            int bottomColor;
            int iconRes;
            if (upper.contains("DELETE") || upper.contains("REMOVE") || upper.contains("REVOKE") || upper.contains("DISABLE")) {
                topColor = IconBackgroundColors.RED.top;
                bottomColor = IconBackgroundColors.RED.bottom;
                iconRes = R.drawable.msg_delete;
            } else if (upper.contains("CREATE") || upper.contains("ADD") || upper.contains("GRANT") || upper.contains("PUBLISH") || upper.contains("RESOLVE")) {
                topColor = IconBackgroundColors.GREEN.top;
                bottomColor = IconBackgroundColors.GREEN.bottom;
                iconRes = R.drawable.msg_add;
            } else if (upper.contains("UPDATE") || upper.contains("EDIT") || upper.contains("CONFIG") || upper.contains("SAVE") || upper.contains("RIGHTS")) {
                topColor = IconBackgroundColors.BLUE.top;
                bottomColor = IconBackgroundColors.BLUE.bottom;
                iconRes = R.drawable.msg_edit;
            } else {
                topColor = IconBackgroundColors.ORANGE.top;
                bottomColor = IconBackgroundColors.ORANGE.bottom;
                iconRes = R.drawable.msg_log;
            }

            String summary = sanitizeDescription(entry.description);
            String actor = formatActorName(entry.adminEmail);
            String sub = (!summary.isEmpty() ? summary + " • " : "")
                    + actor + " • " + EveAdminUiChrome.formatDateTime(entry.timestamp);

            items.add(SettingsActivity.SettingCell.Factory.of(
                    BASE_LOG_ID + i,
                    topColor, bottomColor,
                    iconRes,
                    formatHumanTitle(entry),
                    sub,
                    EveAdminUiChrome.formatDate(entry.timestamp)));
        }
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
            return;
        }
        if (item.id == ID_CAT_EXAMS) {
            selectedCategory = CAT_EXAMS;
            applyFilter(searchQuery);
            return;
        }
        if (item.id == ID_CAT_STUDENTS) {
            selectedCategory = CAT_STUDENTS;
            applyFilter(searchQuery);
            return;
        }
        if (item.id == ID_CAT_CONTENT) {
            selectedCategory = CAT_CONTENT;
            applyFilter(searchQuery);
            return;
        }
        if (item.id == ID_CAT_SYSTEM) {
            selectedCategory = CAT_SYSTEM;
            applyFilter(searchQuery);
            return;
        }
        if (item.id == ID_CAT_ALL) {
            selectedCategory = CAT_ALL;
            applyFilter(searchQuery);
            return;
        }
        int idx = item.id - BASE_LOG_ID;
        if (idx >= 0 && idx < filteredEntries.size()) {
            showEntryDetail(filteredEntries.get(idx));
        }
    }

    private void confirmClearLogs() {
        if (!isOwner) return;
        Activity activity = getParentActivity();
        if (activity == null) return;

        final boolean scopedCategory = selectedCategory != null && !CAT_ALL.equals(selectedCategory);
        String title = scopedCategory
                ? "Clear " + categoryTitle(selectedCategory) + " Logs"
                : "Clear All Audit Logs";
        String msg = scopedCategory
                ? "Permanently delete all audit log entries in " + categoryTitle(selectedCategory) + "? This cannot be undone."
                : "Permanently delete all audit log entries across EVE Admin Control Center? This cannot be undone.";

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("Clear Logs", (d, w) -> executeClearLogs(scopedCategory ? selectedCategory : null))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE);
        showDialog(builder.create());
    }

    private void executeClearLogs(@Nullable String category) {
        EveAdminRepository.ResultCallback<Integer> cb = new EveAdminRepository.ResultCallback<Integer>() {
            @Override
            public void onSuccess(Integer deletedCount) {
                if (destroyed) return;
                int count = deletedCount != null ? deletedCount : 0;
                BulletinFactory.of(EveAdminAuditLogFragment.this)
                        .createSimpleBulletin(R.raw.ic_delete, "Cleared " + count + " audit log entry(s).").show();
                reload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                BulletinFactory.of(EveAdminAuditLogFragment.this)
                        .createErrorBulletin(message != null ? message : "Unable to clear audit logs.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.deleteAuditLogs(token, category)));
    }

    private void showEntryDetail(EveAdminModels.AuditLogEntry entry) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        String cleanDesc = sanitizeDescription(entry.description);
        String msg = "Action: " + formatHumanTitle(entry)
                + "\nCategory: " + categoryTitle(classifyCategory(entry.actionType))
                + "\nPerformed By: " + (!TextUtils.isEmpty(entry.adminEmail) ? entry.adminEmail : "System")
                + "\nTime: " + EveAdminUiChrome.formatDateTime(entry.timestamp)
                + (!cleanDesc.isEmpty() ? "\n\nSummary:\n" + cleanDesc : "");
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle("Audit Event");
        builder.setMessage(msg);
        builder.setPositiveButton(LocaleController.getString(R.string.Close), null);
        showDialog(builder.create());
    }

    private int countByCategory(String cat) {
        int count = 0;
        for (EveAdminModels.AuditLogEntry e : entries) {
            if (cat.equals(classifyCategory(e.actionType))) {
                count++;
            }
        }
        return count;
    }

    public static String classifyCategory(String actionType) {
        String upper = actionType != null ? actionType.trim().toUpperCase(Locale.US) : "";
        if (upper.startsWith("EXAM_") || upper.startsWith("TEST_") || upper.startsWith("GENERATED_TEST_")
                || upper.startsWith("QUESTION_") || upper.startsWith("SYLLABUS_") || "JOB_RETRIED".equals(upper)) {
            return CAT_EXAMS;
        }
        if (upper.startsWith("USER_") || upper.startsWith("PREMIUM_") || upper.startsWith("PAYMENT_")) {
            return CAT_STUDENTS;
        }
        if (upper.startsWith("POLL_") || upper.startsWith("BROADCAST_") || upper.startsWith("BANNER_")
                || upper.startsWith("REPORT_") || upper.startsWith("FEEDBACK_")
                || upper.startsWith("APP_CONTENT_") || "FLOATING_LINK_UPDATED".equals(upper)) {
            return CAT_CONTENT;
        }
        return CAT_SYSTEM;
    }

    private static String categoryTitle(String cat) {
        if (CAT_EXAMS.equals(cat)) return "Exams & Tests";
        if (CAT_STUDENTS.equals(cat)) return "Students & Premium";
        if (CAT_CONTENT.equals(cat)) return "Content & Polls";
        if (CAT_SYSTEM.equals(cat)) return "Admin & System";
        return "All Activity";
    }

    private static String formatHumanTitle(EveAdminModels.AuditLogEntry entry) {
        String upper = entry.actionType != null ? entry.actionType.trim().toUpperCase(Locale.US) : "";
        switch (upper) {
            case "EXAM_CREATED": return "Created Exam";
            case "EXAM_UPDATED": return "Updated Exam Settings";
            case "EXAM_DELETED": return "Deleted Exam";
            case "EXAM_EXPORTED": return "Exported Exam Data";
            case "GENERATED_TEST_CREATED": return "Generated Mock Test";
            case "GENERATED_TEST_STATUS_UPDATED": return "Updated Mock Test Status";
            case "GENERATED_TEST_DELETED": return "Deleted Mock Test";
            case "QUESTION_CREATED": return "Added Question";
            case "QUESTION_UPDATED": return "Updated Question";
            case "QUESTION_DELETED": return "Deleted Question";
            case "SYLLABUS_UPLOADED": return "Uploaded Syllabus PDF";
            case "SYLLABUS_REMOVED": return "Removed Syllabus PDF";
            case "SYLLABUS_REASSIGNED": return "Reassigned Syllabus PDF";
            case "USER_STATUS_TOGGLED": return "Updated Student Account Status";
            case "PREMIUM_GRANTED": return "Granted EVE Premium";
            case "PREMIUM_EXTENDED": return "Extended EVE Premium";
            case "PREMIUM_REVOKED": return "Revoked EVE Premium";
            case "PREMIUM_CONFIG_UPDATED": return "Updated Premium & UPI Settings";
            case "ADMIN_ADDED": return "Added Administrator";
            case "ADMIN_RIGHTS_CHANGED": return "Updated Admin Permissions";
            case "ADMIN_REMOVED": return "Removed Administrator";
            case "MAINTENANCE_TOGGLED": return "Toggled Maintenance Mode";
            case "CONFIG_UPDATED": return "Updated App Configuration";
            case "CRASH_RESOLVED": return "Marked Crash Fixed";
            case "CRASH_DELETED": return "Deleted Crash Signature";
            case "REPORT_DISMISSED": return "Dismissed Question Report";
            case "REPORT_BATCH_DISMISSED": return "Batch Dismissed Reports";
            case "REPORT_DELETED": return "Deleted Question Report";
            case "JOB_RETRIED": return "Retried AI Generation Job";
            default: return EveAdminUiChrome.formatActionTitle(entry.actionType);
        }
    }

    private static String sanitizeDescription(String raw) {
        if (raw == null) return "";
        String cleaned = UUID_PATTERN.matcher(raw).replaceAll("").trim();
        cleaned = cleaned.replaceAll("\\s{2,}", " ").replaceAll("\\(\\s*\\)", "").trim();
        return cleaned;
    }

    private static String formatActorName(String email) {
        if (TextUtils.isEmpty(email)) return "System";
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
