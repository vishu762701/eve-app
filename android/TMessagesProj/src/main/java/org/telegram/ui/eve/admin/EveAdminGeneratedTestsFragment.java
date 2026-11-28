package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Edge-to-Edge Exam-scoped Mock Tests manager for EVE Admin Control Center.
 */
public class EveAdminGeneratedTestsFragment extends BaseFragment {

    private static final int MENU_SEARCH = 1;
    private static final int MENU_ADD = 2;

    private static final int ID_RETRY = 101;
    private static final int BASE_TEST_ID = 1000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    @Nullable
    private final EveAdminModels.AdminExamItem scopedExam;
    private UniversalRecyclerView listView;
    private ActionBarMenuItem searchItem;
    private EveTelegramAvatarPicker testAvatarPicker;
    @Nullable
    private EveAdminModels.AdminGeneratedTestItem pendingAvatarTest;

    private boolean loading = true;
    private boolean destroyed = false;
    private String errorText = "";
    private String searchQuery = "";
    private List<EveAdminModels.AdminExamItem> exams = Collections.emptyList();
    private List<EveAdminModels.AdminGeneratedTestItem> allTests = Collections.emptyList();
    private final List<EveAdminModels.AdminGeneratedTestItem> filteredTests = new ArrayList<>();
    private final EveAdminUiChrome.AutoSyncController autoSync = new EveAdminUiChrome.AutoSyncController(this::reload);

    public EveAdminGeneratedTestsFragment() {
        this(null);
    }

    public EveAdminGeneratedTestsFragment(@Nullable EveAdminModels.AdminExamItem scopedExam) {
        this.scopedExam = scopedExam;
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

        EveAdminRepository.ExamsCatalogData cachedCatalog = repo.getCachedExamsCatalog();
        if (cachedCatalog != null && !cachedCatalog.generatedTests.isEmpty()) {
            exams = cachedCatalog.exams;
            allTests = cachedCatalog.generatedTests;
            loading = false;
        } else {
            EveAdminModels.DashboardSnapshot cached = repo.getCachedSnapshot();
            if (cached != null) {
                exams = cached.exams;
                allTests = cached.generatedTests;
                loading = allTests.isEmpty();
            }
        }
        if (allTests.isEmpty() && scopedExam != null) {
            List<EveAdminModels.AdminGeneratedTestItem> cachedScoped = repo.getCachedGeneratedTests(scopedExam.id);
            if (cachedScoped != null && !cachedScoped.isEmpty()) {
                allTests = cachedScoped;
                loading = false;
            }
        }

        testAvatarPicker = new EveTelegramAvatarPicker(this, true, new EveTelegramAvatarPicker.Delegate() {
            @Override
            public void onAvatarCaptured(@NonNull byte[] imageBytes, @NonNull String mimeType, @Nullable Bitmap previewBitmap) {
                if (pendingAvatarTest != null) {
                    uploadTestAvatar(pendingAvatarTest, imageBytes, mimeType, null);
                }
            }

            @Override
            public void onAnimatedEmojiAvatarCaptured(
                    long documentId,
                    @NonNull int[] backgroundColors,
                    @NonNull byte[] fallbackPngBytes,
                    @NonNull String mimeType,
                    @Nullable Bitmap previewBitmap) {
                if (pendingAvatarTest != null) {
                    String descriptor = EveAnimatedEmojiHelper.encodeAnimatedAvatarDescriptor(documentId, backgroundColors);
                    uploadTestAvatar(pendingAvatarTest, fallbackPngBytes, mimeType, descriptor);
                }
            }

            @Override
            public void onAvatarRemoved() {
                if (pendingAvatarTest != null) {
                    removeTestAvatar(pendingAvatarTest);
                }
            }
        });

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Mock Tests",
                EveAnimatedEmojiHelper.decode(scopedExam != null ? scopedExam.examName : "All exams", null),
                false,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_ADD) {
                            if (scopedExam != null) {
                                presentFragment(new EveAdminExamPromptFragment(
                                        scopedExam, exams, EveAdminGeneratedTestsFragment.this::reload));
                            } else {
                                EveAdminDialogs.showGenerateTestDialog(
                                        EveAdminGeneratedTestsFragment.this,
                                        repo,
                                        exams,
                                        EveAdminGeneratedTestsFragment.this::reload);
                            }
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
            searchItem.setSearchFieldHint("Search mock tests");
            menu.addItem(MENU_ADD, R.drawable.msg_add, getResourceProvider());
        }

        applyFilter();
        fragmentView = chrome.rootView;
        reload();
        return chrome.rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (testAvatarPicker != null) {
            testAvatarPicker.onResume();
        }
        autoSync.onResume();
    }

    @Override
    public void onPause() {
        if (testAvatarPicker != null) {
            testAvatarPicker.onPause();
        }
        autoSync.onPause();
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        autoSync.destroy();
        if (testAvatarPicker != null) {
            testAvatarPicker.destroy();
            testAvatarPicker = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (testAvatarPicker != null) {
            testAvatarPicker.onActivityResult(requestCode, resultCode, data);
        }
        super.onActivityResultFragment(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (testAvatarPicker != null) {
            testAvatarPicker.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
        super.onRequestPermissionsResultFragment(requestCode, permissions, grantResults);
    }

    private void reload() {
        if (destroyed) {
            return;
        }
        loading = allTests.isEmpty();
        errorText = "";
        if (loading) {
            updateList();
        }
        repo.loadExamsCatalogAsync(new EveAdminRepository.ResultCallback<EveAdminRepository.ExamsCatalogData>() {
            @Override
            public void onSuccess(EveAdminRepository.ExamsCatalogData data) {
                if (destroyed) {
                    return;
                }
                loading = false;
                autoSync.markSynced();
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
            if (scopedExam != null && !scopedExam.id.equals(test.examId)) {
                continue;
            }
            if (q.isEmpty()
                    || test.title.toLowerCase(Locale.US).contains(q)
                    || test.examName.toLowerCase(Locale.US).contains(q)
                    || test.testNumber.toLowerCase(Locale.US).contains(q)) {
                filteredTests.add(test);
            }
        }
        if (simpleActionBar != null) {
            if (scopedExam != null) {
                simpleActionBar.setSubtitle(scopedExam.examName + " • " + filteredTests.size() + " tests");
            } else {
                simpleActionBar.setSubtitle(filteredTests.size() + " tests");
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
        }

        if (loading && allTests.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        if (filteredTests.isEmpty()) {
            items.add(UItem.asShadow(searchQuery.trim().isEmpty()
                    ? "No mock tests found. Tap + above to generate an AI mock test."
                    : "No mock tests matched \"" + searchQuery.trim() + "\"."));
            return;
        }

        for (int i = 0; i < filteredTests.size(); i++) {
            EveAdminModels.AdminGeneratedTestItem test = filteredTests.get(i);
            boolean live = test.isLive();
            String examLabel = !test.examName.isEmpty() ? test.examName : (scopedExam != null ? scopedExam.examName : "Mock Test");
            String subtitle = examLabel + " • " + test.questionCount + " Qs";
            String badge = live ? "Live" : "Draft";
            items.add(EveAdminUiChrome.AvatarRowCell.Factory.of(
                    BASE_TEST_ID + i,
                    test,
                    new EveAdminUiChrome.AvatarRowCell.Payload(
                            test.id,
                            test.title,
                            subtitle,
                            test.avatarUrl,
                            test.avatarEmoji,
                            test.avatarColor,
                            badge,
                            live
                                    ? EveAdminUiChrome.AvatarRowCell.BADGE_GREEN
                                    : EveAdminUiChrome.AvatarRowCell.BADGE_RED)));
        }
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
                "Set Test Avatar",
                "Schedule Release",
                "View Questions",
                "Delete Test"
        };
        showDialog(new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle(EveAnimatedEmojiHelper.decode(test.title, null))
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0:
                            updateTestStatus(test, live ? "paused" : "live");
                            break;
                        case 1:
                            pendingAvatarTest = test;
                            if (testAvatarPicker != null) {
                                testAvatarPicker.open(!TextUtils.isEmpty(test.avatarUrl));
                            }
                            break;
                        case 2:
                            showScheduleDialog(test);
                            break;
                        case 3:
                            previewQuestions(test);
                            break;
                        case 4:
                            confirmDeleteTest(test);
                            break;
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void uploadTestAvatar(EveAdminModels.AdminGeneratedTestItem test, byte[] bytes, String mimeType, @Nullable String avatarEmojiDescriptor) {
        if (destroyed || test == null || bytes == null || bytes.length == 0) return;
        EveAdminRepository.ResultCallback<JSONObject> cb = new EveAdminRepository.ResultCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject data) {
                if (destroyed) return;
                String nextUrl = data != null ? data.optString("avatarUrl", "") : "";
                String nextEmoji = data != null ? data.optString("avatarEmoji", avatarEmojiDescriptor != null ? avatarEmojiDescriptor : "")
                        : (avatarEmojiDescriptor != null ? avatarEmojiDescriptor : "");
                applyOptimisticAvatar(test.id, nextUrl, nextEmoji, -1);
                repo.invalidateCache();
                BulletinFactory.of(EveAdminGeneratedTestsFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, "Mock test avatar updated").show();
                reload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                BulletinFactory.of(EveAdminGeneratedTestsFragment.this)
                        .createErrorBulletin(message != null ? message : "Failed to upload test avatar.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb,
                repo.uploadGeneratedTestAvatarImage(token, test.id, bytes, mimeType, avatarEmojiDescriptor)));
    }

    private void removeTestAvatar(EveAdminModels.AdminGeneratedTestItem test) {
        if (destroyed || test == null) return;
        EveAdminRepository.ResultCallback<JSONObject> cb = new EveAdminRepository.ResultCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject data) {
                if (destroyed) return;
                applyOptimisticAvatar(test.id, "", "", -1);
                repo.invalidateCache();
                BulletinFactory.of(EveAdminGeneratedTestsFragment.this)
                        .createSimpleBulletin(R.raw.ic_delete, "Test avatar removed").show();
                reload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                BulletinFactory.of(EveAdminGeneratedTestsFragment.this)
                        .createErrorBulletin(message != null ? message : "Failed to remove test avatar.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.deleteGeneratedTestAvatar(token, test.id)));
    }

    private void applyOptimisticAvatar(String testId, String url, String emoji, int color) {
        List<EveAdminModels.AdminGeneratedTestItem> updated = new ArrayList<>(allTests.size());
        for (EveAdminModels.AdminGeneratedTestItem t : allTests) {
            updated.add(t.id.equals(testId) ? t.withAvatar(url, emoji, color) : t);
        }
        allTests = updated;
        applyFilter();
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
                .setTitle("Schedule: " + EveAnimatedEmojiHelper.stripMarkupToPlainText(test.title))
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
                .setMessage("Delete \"" + EveAnimatedEmojiHelper.stripMarkupToPlainText(test.title) + "\" permanently? This action cannot be undone.")
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
