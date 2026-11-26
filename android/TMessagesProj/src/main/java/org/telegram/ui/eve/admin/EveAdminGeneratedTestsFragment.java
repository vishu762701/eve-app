package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
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
import org.telegram.utils.glass.source.GlassSourceRenderNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen Telegram-native Mock Tests manager for EVE Admin Control Center.
 */
public class EveAdminGeneratedTestsFragment extends BaseFragment {

    private static final int MENU_SEARCH = 1;
    private static final int MENU_ADD = 2;
    private static final int MENU_REFRESH = 3;

    private static final int ID_RETRY = 101;
    private static final int BASE_TEST_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private ActionBarMenuItem searchItem;

    private boolean loading = true;
    private boolean destroyed = false;
    private String errorText = "";
    private String searchQuery = "";
    private List<EveAdminModels.AdminExamItem> exams = Collections.emptyList();
    private List<EveAdminModels.AdminGeneratedTestItem> allTests = Collections.emptyList();
    private final List<EveAdminModels.AdminGeneratedTestItem> filteredTests = new ArrayList<>();

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
    public GlassSourceRenderNode getGlassSource() {
        return getBaseSimpleGlass().getGlassSource();
    }

    @Override
    public GlassSourceRenderNode getFrostedGlassSource() {
        return getBaseSimpleGlass().getFrostedGlassSource();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (simpleActionBar != null && simpleActionBar.isSearchFieldVisible()) {
            if (invoked) {
                simpleActionBar.closeSearchField();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public View createView(Context context) {
        destroyed = false;
        setHasOwnBackground(true);

        EveAdminModels.DashboardSnapshot cached = repo.getCachedSnapshot();
        if (cached != null) {
            exams = cached.exams;
            allTests = cached.generatedTests;
        }

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Mock Tests",
                allTests.isEmpty() ? "Manage generated tests" : allTests.size() + " tests",
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_ADD) {
                            EveAdminDialogs.showGenerateTestDialog(
                                    EveAdminGeneratedTestsFragment.this,
                                    repo,
                                    exams,
                                    EveAdminGeneratedTestsFragment.this::reload);
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
            searchItem = menu.addItem(MENU_SEARCH, R.drawable.outline_header_search, getResourceProvider())
                    .setIsSearchField(true)
                    .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                        @Override
                        public void onSearchExpand() {
                            searchQuery = "";
                            applyFilter();
                        }

                        @Override
                        public void onSearchCollapse() {
                            searchQuery = "";
                            applyFilter();
                        }

                        @Override
                        public void onTextChanged(EditText editText) {
                            searchQuery = editText.getText() != null ? editText.getText().toString() : "";
                            applyFilter();
                        }
                    });
            searchItem.setSearchFieldHint("Search tests or exams");
            menu.addItem(MENU_ADD, R.drawable.msg_add, getResourceProvider());
            menu.addItem(MENU_REFRESH, R.drawable.msg_retry, getResourceProvider());
        }

        applyFilter();
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
        if (destroyed) {
            return;
        }
        loading = true;
        errorText = "";
        updateList();
        repo.loadExamsCatalogAsync(new EveAdminRepository.ResultCallback<EveAdminRepository.ExamsCatalogData>() {
            @Override
            public void onSuccess(EveAdminRepository.ExamsCatalogData data) {
                if (destroyed) {
                    return;
                }
                loading = false;
                if (data != null) {
                    exams = data.exams;
                    allTests = data.generatedTests;
                }
                applyFilter();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) {
                    return;
                }
                loading = false;
                errorText = message != null ? message : "Unable to load mock tests.";
                updateList();
            }
        });
    }

    private void applyFilter() {
        filteredTests.clear();
        String q = searchQuery != null ? searchQuery.trim().toLowerCase(Locale.US) : "";
        for (EveAdminModels.AdminGeneratedTestItem test : allTests) {
            if (q.isEmpty()
                    || test.title.toLowerCase(Locale.US).contains(q)
                    || test.examName.toLowerCase(Locale.US).contains(q)
                    || test.testNumber.toLowerCase(Locale.US).contains(q)) {
                filteredTests.add(test);
            }
        }
        if (simpleActionBar != null) {
            if (q.isEmpty()) {
                simpleActionBar.setSubtitle(allTests.size() + " tests");
            } else {
                simpleActionBar.setSubtitle(filteredTests.size() + " matching");
            }
        }
        updateList();
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
                    "Retry Loading Tests",
                    errorText,
                    "Retry"));
            items.add(UItem.asShadow(null));
        }

        if (loading && allTests.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (filteredTests.isEmpty()) {
            items.add(UItem.asHeader("Mock Tests"));
            items.add(UItem.asShadow(searchQuery.trim().isEmpty()
                    ? "No mock tests found. Tap + above to generate an AI mock test."
                    : "No mock tests matched \"" + searchQuery.trim() + "\"."));
            return;
        }

        items.add(UItem.asHeader("Mock Tests (" + filteredTests.size() + ")"));
        for (int i = 0; i < filteredTests.size(); i++) {
            EveAdminModels.AdminGeneratedTestItem test = filteredTests.get(i);
            boolean live = test.isLive();
            IconBackgroundColors iconBg = live ? IconBackgroundColors.GREEN : IconBackgroundColors.ORANGE;
            String examLabel = !test.examName.isEmpty() ? test.examName : "Mock Test";
            String subtitle = examLabel + " • " + test.questionCount + " Qs";
            String badge = live ? "Live" : "Draft";
            items.add(SettingsActivity.SettingCell.Factory.of(
                    BASE_TEST_ID + i,
                    iconBg.top, iconBg.bottom,
                    R.drawable.msg_list,
                    test.title,
                    subtitle,
                    badge));
        }
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
        } else if (item.id >= BASE_TEST_ID) {
            int idx = item.id - BASE_TEST_ID;
            if (idx >= 0 && idx < filteredTests.size()) {
                showTestActions(filteredTests.get(idx));
            }
        }
    }

    private void showTestActions(EveAdminModels.AdminGeneratedTestItem test) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        boolean live = test.isLive();
        String[] actions = new String[]{
                live ? "Move to Draft / Pause" : "Publish Live",
                "Schedule Release",
                "View Questions",
                "Delete Test"
        };
        showDialog(new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle(test.title)
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0:
                            updateTestStatus(test, live ? "paused" : "live");
                            break;
                        case 1:
                            showScheduleDialog(test);
                            break;
                        case 2:
                            previewQuestions(test);
                            break;
                        case 3:
                            confirmDeleteTest(test);
                            break;
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void updateTestStatus(EveAdminModels.AdminGeneratedTestItem test, String nextStatus) {
        EveAdminRepository.ResultCallback<String> cb = new EveAdminRepository.ResultCallback<String>() {
            @Override
            public void onSuccess(String msg) {
                repo.invalidateCache();
                BulletinFactory.of(EveAdminGeneratedTestsFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, msg).show();
                reload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                BulletinFactory.of(EveAdminGeneratedTestsFragment.this)
                        .createErrorBulletin(message).show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            repo.updateGeneratedTestStatus(token, test.id, nextStatus);
            repo.postSuccess(cb, "live".equals(nextStatus) ? "Test published live." : "Test moved to draft.");
        });
    }

    private void showScheduleDialog(EveAdminModels.AdminGeneratedTestItem test) {
        Activity activity = getParentActivity();
        if (activity == null) return;

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        container.setPadding(pad, dp(10), pad, dp(4));

        TextView label = new TextView(activity);
        label.setText("Release in how many hours? (0 = immediate)");
        label.setTextSize(13);
        label.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, getResourceProvider()));
        container.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        EditText hoursInput = new EditText(activity);
        hoursInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        hoursInput.setText("0");
        hoursInput.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        hoursInput.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, getResourceProvider()));
        container.addView(hoursInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        showDialog(new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle("Schedule: " + test.title)
                .setView(container)
                .setPositiveButton("Schedule", (d, w) -> {
                    int hrs = 0;
                    try {
                        hrs = Math.max(0, Math.min(720, Integer.parseInt(hoursInput.getText().toString().trim())));
                    } catch (Throwable ignored) {
                    }
                    final int finalHrs = hrs;
                    final long availableFrom = finalHrs == 0 ? 0L : System.currentTimeMillis() + finalHrs * 3_600_000L;
                    EveAdminRepository.ResultCallback<String> cb = new EveAdminRepository.ResultCallback<String>() {
                        @Override
                        public void onSuccess(String msg) {
                            repo.invalidateCache();
                            BulletinFactory.of(EveAdminGeneratedTestsFragment.this)
                                    .createSimpleBulletin(R.raw.contact_check, msg).show();
                            reload();
                        }

                        @Override
                        public void onError(String message, int httpStatus) {
                            BulletinFactory.of(EveAdminGeneratedTestsFragment.this)
                                    .createErrorBulletin(message).show();
                        }
                    };
                    repo.runWithAdminToken(cb, token -> {
                        repo.scheduleGeneratedTest(token, test.id, availableFrom);
                        repo.postSuccess(cb, finalHrs == 0
                                ? "Test scheduled for immediate availability."
                                : "Test scheduled in " + finalHrs + "h.");
                    });
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void previewQuestions(EveAdminModels.AdminGeneratedTestItem test) {
        EveAdminRepository.ResultCallback<List<EveAdminModels.AdminQuestionItem>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.AdminQuestionItem>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.AdminQuestionItem> qs) {
                        Activity activity = getParentActivity();
                        if (activity == null) return;
                        StringBuilder sb = new StringBuilder();
                        sb.append(test.examName.isEmpty() ? "Mock Test" : test.examName)
                                .append(" • ").append(qs.size()).append(" questions\n\n");
                        for (int i = 0; i < Math.min(qs.size(), 20); i++) {
                            EveAdminModels.AdminQuestionItem q = qs.get(i);
                            sb.append(i + 1).append(". ").append(q.questionText)
                                    .append("\n   Answer: ").append(q.correctAnswer).append("\n\n");
                        }
                        if (qs.size() > 20) {
                            sb.append("...and ").append(qs.size() - 20).append(" more questions.");
                        }
                        showDialog(new AlertDialog.Builder(activity, getResourceProvider())
                                .setTitle("Questions: " + test.title)
                                .setMessage(sb.toString().trim())
                                .setPositiveButton(LocaleController.getString(R.string.Close), null)
                                .create());
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        BulletinFactory.of(EveAdminGeneratedTestsFragment.this)
                                .createErrorBulletin(message).show();
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchGeneratedTestQuestions(token, test.id)));
    }

    private void confirmDeleteTest(EveAdminModels.AdminGeneratedTestItem test) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        showDialog(new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle("Delete Mock Test?")
                .setMessage("Delete \"" + test.title + "\" permanently? This action cannot be undone.")
                .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                    EveAdminRepository.ResultCallback<String> cb = new EveAdminRepository.ResultCallback<String>() {
                        @Override
                        public void onSuccess(String msg) {
                            repo.invalidateCache();
                            BulletinFactory.of(EveAdminGeneratedTestsFragment.this)
                                    .createSimpleBulletin(R.raw.ic_delete, msg).show();
                            reload();
                        }

                        @Override
                        public void onError(String message, int httpStatus) {
                            BulletinFactory.of(EveAdminGeneratedTestsFragment.this)
                                    .createErrorBulletin(message).show();
                        }
                    };
                    repo.runWithAdminToken(cb, token -> {
                        repo.deleteGeneratedTest(token, test.id);
                        repo.postSuccess(cb, "Mock test deleted.");
                    });
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .create());
    }
}
