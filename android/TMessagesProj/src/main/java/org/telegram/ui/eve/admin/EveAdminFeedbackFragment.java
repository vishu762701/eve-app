package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-to-Edge Student Feedback Messages & Feedback Posts manager replacing AlertDialog lists.
 */
public class EveAdminFeedbackFragment extends BaseFragment {

    private static final int MENU_ADD_POST = 1;
    private static final int MENU_REFRESH = 2;

    private static final int ID_RETRY = 101;
    private static final int BASE_MSG_ID = 1000;
    private static final int BASE_POST_ID = 5000;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private UniversalRecyclerView listView;
    private List<EveAdminModels.FeedbackMessageItem> messages = Collections.emptyList();
    private List<EveAdminModels.FeedbackPostItem> posts = Collections.emptyList();
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
                "Student Feedback",
                "Loading...",
                false,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_ADD_POST) {
                            showCreatePostDialog();
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
            menu.addItem(MENU_ADD_POST, R.drawable.msg_add, getResourceProvider());
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

        EveAdminRepository.ResultCallback<List<EveAdminModels.FeedbackMessageItem>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.FeedbackMessageItem>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.FeedbackMessageItem> msgData) {
                        if (destroyed) return;
                        messages = msgData != null ? msgData : Collections.emptyList();
                        loading = false;
                        updateSubtitle();
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
                        errorText = message != null ? message : "Unable to load feedback.";
                        updateSubtitle();
                        updateList();
                    }
                };

        repo.runWithAdminToken(cb, token -> {
            List<EveAdminModels.FeedbackMessageItem> msgs = repo.fetchFeedbackMessages(token);
            List<EveAdminModels.FeedbackPostItem> fetchedPosts = Collections.emptyList();
            try {
                fetchedPosts = repo.fetchFeedbackPosts(token);
            } catch (Exception ignored) {
            }
            final List<EveAdminModels.FeedbackPostItem> finalPosts = fetchedPosts;
            AndroidUtilities.runOnUIThread(() -> {
                if (!destroyed) {
                    posts = finalPosts != null ? finalPosts : Collections.emptyList();
                }
            });
            repo.postSuccess(cb, msgs);
        });
    }

    private void updateSubtitle() {
        if (simpleActionBar == null) return;
        if (!errorText.isEmpty() && messages.isEmpty() && posts.isEmpty()) {
            simpleActionBar.setSubtitle("Unable to load");
        } else {
            simpleActionBar.setSubtitle(messages.size() + " messages • " + posts.size() + " posts");
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
                    "Retry Loading Feedback",
                    errorText,
                    "Retry"));
        }

        if (loading && messages.isEmpty() && posts.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }

        items.add(UItem.asHeader("Feedback Messages"));
        if (messages.isEmpty()) {
            items.add(UItem.asShadow("No student feedback messages."));
        } else {
            for (int i = 0; i < messages.size(); i++) {
                EveAdminModels.FeedbackMessageItem m = messages.get(i);
                boolean unread = !m.read;
                IconBackgroundColors badgeColor = unread ? IconBackgroundColors.BLUE_LIGHT : IconBackgroundColors.GRAY;
                String rightBadge = unread ? "New" : (!TextUtils.isEmpty(m.adminReply) ? "Replied" : "Read");
                items.add(SettingsActivity.SettingCell.Factory.of(
                        BASE_MSG_ID + i,
                        badgeColor.top, badgeColor.bottom,
                        R.drawable.settings_ask,
                        !TextUtils.isEmpty(m.userEmail) ? m.userEmail : "Student",
                        m.message,
                        rightBadge));
            }
        }

        items.add(UItem.asHeader("Feedback Posts"));
        if (posts.isEmpty()) {
            items.add(UItem.asShadow("No feedback topics posted. Tap + to create one."));
        } else {
            for (int i = 0; i < posts.size(); i++) {
                EveAdminModels.FeedbackPostItem p = posts.get(i);
                items.add(SettingsActivity.SettingCell.Factory.of(
                        BASE_POST_ID + i,
                        IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                        R.drawable.settings_faq,
                        p.title,
                        p.message,
                        EveAdminUiChrome.formatDate(p.createdAt)));
            }
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            reload();
            return;
        }
        if (item.id >= BASE_POST_ID) {
            int pIdx = item.id - BASE_POST_ID;
            if (pIdx >= 0 && pIdx < posts.size()) {
                showPostDetailDialog(posts.get(pIdx));
            }
        } else if (item.id >= BASE_MSG_ID) {
            int mIdx = item.id - BASE_MSG_ID;
            if (mIdx >= 0 && mIdx < messages.size()) {
                showMessageDetailDialog(messages.get(mIdx));
            }
        }
    }

    private void showMessageDetailDialog(EveAdminModels.FeedbackMessageItem msg) {
        Activity act = getParentActivity();
        if (act == null) return;
        Theme.ResourcesProvider rp = getResourceProvider();

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        EditText replyInput = new EditText(act);
        replyInput.setHint("Write admin reply...");
        replyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        replyInput.setMinLines(2);
        replyInput.setText(msg.adminReply);
        replyInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        replyInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        box.addView(replyInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(act, rp);
        builder.setTitle(!TextUtils.isEmpty(msg.userEmail) ? msg.userEmail : "Student Feedback");
        builder.setMessage(msg.message + "\n\nSent: " + EveAdminUiChrome.formatDateTime(msg.createdAt));
        builder.setView(box);
        builder.setPositiveButton("Send Reply", (d, w) -> {
            String rText = replyInput.getText().toString().trim();
            if (!rText.isEmpty()) {
                runMutation(token -> repo.replyFeedbackMessage(token, msg.id, rText), "Reply sent");
            }
        });
        if (!msg.read) {
            builder.setNeutralButton("Mark Read", (d, w) ->
                    runMutation(token -> repo.markFeedbackRead(token, msg.id), "Marked as read"));
        }
        builder.setNegativeButton("Delete", (d, w) -> confirmDeleteMessage(msg));
        showDialog(builder.create());
    }

    private void confirmDeleteMessage(EveAdminModels.FeedbackMessageItem msg) {
        Activity act = getParentActivity();
        if (act == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(act, getResourceProvider());
        builder.setTitle("Delete Feedback");
        builder.setMessage("Delete message from " + msg.userEmail + "?");
        builder.setPositiveButton("Delete", (d, w) ->
                runMutation(token -> repo.deleteFeedbackMessage(token, msg.id), "Message deleted"));
        builder.setNegativeButton("Cancel", null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (pos instanceof TextView) {
            ((TextView) pos).setTextColor(Theme.getColor(Theme.key_text_RedBold, getResourceProvider()));
        }
    }

    private void showCreatePostDialog() {
        Activity act = getParentActivity();
        if (act == null) return;
        Theme.ResourcesProvider rp = getResourceProvider();

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(12), dp(22), dp(8));

        EditText titleInput = new EditText(act);
        titleInput.setHint("Topic Title");
        titleInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        titleInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        box.addView(titleInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        EditText bodyInput = new EditText(act);
        bodyInput.setHint("Post message");
        bodyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        bodyInput.setMinLines(3);
        bodyInput.setGravity(Gravity.TOP | Gravity.START);
        bodyInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        bodyInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        box.addView(bodyInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(act, rp);
        builder.setTitle("New Feedback Post");
        builder.setView(box);
        builder.setPositiveButton("Create", (d, w) -> {
            String t = titleInput.getText().toString().trim();
            String b = bodyInput.getText().toString().trim();
            if (t.isEmpty() || b.isEmpty()) {
                BulletinFactory.of(this).createErrorBulletin("Title and message are required.").show();
                return;
            }
            runMutation(token -> repo.createFeedbackPost(token, t, b), "Feedback post created");
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showPostDetailDialog(EveAdminModels.FeedbackPostItem post) {
        Activity act = getParentActivity();
        if (act == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(act, getResourceProvider());
        builder.setTitle(post.title);
        builder.setMessage(post.message + "\n\nCreated: " + EveAdminUiChrome.formatDateTime(post.createdAt));
        builder.setPositiveButton("Close", null);
        builder.setNeutralButton("Delete Post", (d, w) -> {
            AlertDialog.Builder confirm = new AlertDialog.Builder(act, getResourceProvider());
            confirm.setTitle("Delete Feedback Post");
            confirm.setMessage("Remove \"" + post.title + "\"?");
            confirm.setPositiveButton("Delete", (dd, ww) ->
                    runMutation(token -> repo.deleteFeedbackPost(token, post.id), "Post deleted"));
            confirm.setNegativeButton("Cancel", null);
            showDialog(confirm.create());
        });
        showDialog(builder.create());
    }

    private void runMutation(EveAdminRepository.TokenWork work, String okMessage) {
        EveAdminRepository.ResultCallback<Void> cb = new EveAdminRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void data) {
                if (destroyed) return;
                BulletinFactory.of(EveAdminFeedbackFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, okMessage).show();
                reload();
            }

            @Override
            public void onError(String message, int httpStatus) {
                if (destroyed) return;
                BulletinFactory.of(EveAdminFeedbackFragment.this)
                        .createErrorBulletin(message != null ? message : "Operation failed.").show();
            }
        };
        repo.runWithAdminToken(cb, token -> {
            work.run(token);
            repo.postSuccess(cb, null);
        });
    }
}
