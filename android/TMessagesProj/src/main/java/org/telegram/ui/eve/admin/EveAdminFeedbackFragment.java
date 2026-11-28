package org.telegram.ui.eve.admin;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextEmoji;
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
                        }
                    }
                },
                null
        );
        listView = chrome.listView;

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            menu.addItem(MENU_ADD_POST, R.drawable.msg_add, getResourceProvider());
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
        loading = messages.isEmpty() && posts.isEmpty();
        errorText = "";
        if (loading) {
            updateList();
        }

        EveAdminRepository.ResultCallback<List<EveAdminModels.FeedbackMessageItem>> cb =
                new EveAdminRepository.ResultCallback<List<EveAdminModels.FeedbackMessageItem>>() {
                    @Override
                    public void onSuccess(List<EveAdminModels.FeedbackMessageItem> msgData) {
                        if (destroyed) return;
                        messages = msgData != null ? msgData : Collections.emptyList();
                        loading = false;
                        autoSync.markSynced();
                        updateSubtitle();
                        updateList();
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (destroyed) return;
                        loading = false;
                        autoSync.markSynced();
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

        String sender = !TextUtils.isEmpty(msg.userEmail) ? msg.userEmail : "Student Feedback";
        EveAnimatedEmojiHelper.SheetHost host = new EveAnimatedEmojiHelper.SheetHost(act, sender, rp);

        TextView metaView = new TextView(act);
        metaView.setText(msg.message + "\n\nSent: " + EveAdminUiChrome.formatDateTime(msg.createdAt));
        metaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        metaView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        host.formColumn.addView(metaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        EditTextEmoji replyInput = EveAnimatedEmojiHelper.createEditTextEmoji(
                act, host.root, this, "Write admin reply...", msg.adminReply != null ? msg.adminReply : "", true, rp);
        host.formColumn.addView(replyInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView sendBtn = makeSheetActionButton(act, "Send Reply", true, false, rp);
        actions.addView(sendBtn, LayoutHelper.createLinear(0, 42, 1f, 0, 0, 6, 0));
        if (!msg.read) {
            TextView readBtn = makeSheetActionButton(act, "Mark Read", false, false, rp);
            readBtn.setOnClickListener(v -> {
                host.sheet.dismiss();
                runMutation(token -> repo.markFeedbackRead(token, msg.id), "Marked as read");
            });
            actions.addView(readBtn, LayoutHelper.createLinear(0, 42, 1f, 4, 0, 4, 0));
        }
        TextView deleteBtn = makeSheetActionButton(act, "Delete", false, true, rp);
        deleteBtn.setOnClickListener(v -> {
            host.sheet.dismiss();
            confirmDeleteMessage(msg);
        });
        actions.addView(deleteBtn, LayoutHelper.createLinear(0, 42, 1f, 6, 0, 0, 0));
        host.formColumn.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        sendBtn.setOnClickListener(v -> {
            String rText = EveAnimatedEmojiHelper.encode(replyInput.getText()).trim();
            if (!rText.isEmpty()) {
                host.sheet.dismiss();
                runMutation(token -> repo.replyFeedbackMessage(token, msg.id, rText), "Reply sent");
            }
        });
        host.sheet.setOnShowListener(d -> replyInput.onResume());
        host.sheet.setOnDismissListener(d -> {
            replyInput.onPause();
            replyInput.onDestroy();
        });
        showDialog(host.sheet);
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

        EveAnimatedEmojiHelper.SheetHost host = new EveAnimatedEmojiHelper.SheetHost(act, "New Feedback Post", rp);

        EditTextEmoji titleInput = EveAnimatedEmojiHelper.createEditTextEmoji(
                act, host.root, this, "Topic Title", "", false, rp);
        host.formColumn.addView(titleInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        EditTextEmoji bodyInput = EveAnimatedEmojiHelper.createEditTextEmoji(
                act, host.root, this, "Post message", "", true, rp);
        host.formColumn.addView(bodyInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancelBtn = makeSheetActionButton(act, "Cancel", false, false, rp);
        TextView createBtn = makeSheetActionButton(act, "Create", true, false, rp);
        actions.addView(cancelBtn, LayoutHelper.createLinear(0, 42, 1f, 0, 0, 8, 0));
        actions.addView(createBtn, LayoutHelper.createLinear(0, 42, 1f, 8, 0, 0, 0));
        host.formColumn.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        cancelBtn.setOnClickListener(v -> host.sheet.dismiss());
        createBtn.setOnClickListener(v -> {
            String t = EveAnimatedEmojiHelper.encode(titleInput.getText()).trim();
            String b = EveAnimatedEmojiHelper.encode(bodyInput.getText()).trim();
            if (t.isEmpty() || b.isEmpty()) {
                BulletinFactory.of(this).createErrorBulletin("Title and message are required.").show();
                return;
            }
            host.sheet.dismiss();
            runMutation(token -> repo.createFeedbackPost(token, t, b), "Feedback post created");
        });
        host.sheet.setOnShowListener(d -> {
            titleInput.onResume();
            bodyInput.onResume();
        });
        host.sheet.setOnDismissListener(d -> {
            titleInput.onPause();
            bodyInput.onPause();
            titleInput.onDestroy();
            bodyInput.onDestroy();
        });
        showDialog(host.sheet);
    }

    private TextView makeSheetActionButton(Context ctx, String label, boolean primary, boolean destructive, Theme.ResourcesProvider rp) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tv.setTypeface(AndroidUtilities.bold());
        int bgColor = primary
                ? Theme.getColor(Theme.key_featuredStickers_addButton, rp)
                : Theme.multAlpha(Theme.getColor(destructive ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteGrayText, rp), 0.14f);
        int fgColor = primary
                ? Theme.getColor(Theme.key_featuredStickers_buttonText, rp)
                : Theme.getColor(destructive ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlackText, rp);
        tv.setBackground(Theme.createRoundRectDrawable(dp(10), bgColor));
        tv.setTextColor(fgColor);
        return tv;
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
