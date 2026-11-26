package org.telegram.ui.eve.admin;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Telegram-native Java Content, Home, Notifications & Moderation Hub for EVE Admin Control Center.
 */
public class EveAdminContentAndCommsFragment extends BaseFragment {

    private static final int ID_BANNERS = 101;
    private static final int ID_FLOATING_LINK = 102;
    private static final int ID_POLLS = 103;
    private static final int ID_APP_CONTENT_ABOUT = 104;
    private static final int ID_APP_CONTENT_PRIVACY = 105;
    private static final int ID_APP_CONTENT_TERMS = 106;

    private static final int ID_SEND_BROADCAST = 201;
    private static final int ID_BROADCAST_HISTORY = 202;
    private static final int ID_FLAGGED_REPORTS = 203;
    private static final int ID_FEEDBACK_MESSAGES = 204;
    private static final int ID_FEEDBACK_POSTS = 205;

    private final EveAdminRepository repo = EveAdminRepository.getInstance();
    private final EveAdminModels.AdminProfile profile;
    private UniversalRecyclerView listView;

    public EveAdminContentAndCommsFragment(EveAdminModels.AdminProfile profile) {
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
        setHasOwnBackground(true);

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                "Content & Moderation",
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
        return chrome.rootView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_CONTENT)) {
            items.add(UItem.asHeader("Home & Pages"));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_BANNERS,
                    IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                    R.drawable.msg_photos,
                    "Home Screen Banners",
                    "View, edit links, reorder or remove banners",
                    null));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_FLOATING_LINK,
                    IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                    R.drawable.msg_link,
                    "Floating Community Link",
                    "Configure quick home community URL",
                    null));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_APP_CONTENT_ABOUT,
                    IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                    R.drawable.msg_info,
                    "Edit About Page",
                    "Update app About page copy",
                    null));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_APP_CONTENT_PRIVACY,
                    IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                    R.drawable.settings_privacy,
                    "Edit Privacy Policy Content",
                    "Update privacy policy served from backend",
                    null));
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_APP_CONTENT_TERMS,
                    IconBackgroundColors.BLUE_DEEP.top, IconBackgroundColors.BLUE_DEEP.bottom,
                    R.drawable.settings_policy,
                    "Edit Terms of Service",
                    "Update student terms of service copy",
                    null));
            items.add(UItem.asShadow(null));
        }

        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MODERATE_FEEDBACK)
                || EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_SEND_BROADCASTS)) {
            items.add(UItem.asHeader("Broadcasts, Polls & Moderation"));
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_SEND_BROADCASTS)) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_SEND_BROADCAST,
                        IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                        R.drawable.msg_send,
                        "Send Broadcast Notification",
                        "Delivers via EVE FCM & Announcements",
                        null));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_BROADCAST_HISTORY,
                        IconBackgroundColors.BLUE_ALT.top, IconBackgroundColors.BLUE_ALT.bottom,
                        R.drawable.msg_log,
                        "Broadcast History",
                        "Inspect or delete sent notifications",
                        null));
            }
            if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MODERATE_FEEDBACK)) {
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_POLLS,
                        IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                        R.drawable.msg_list,
                        "Manage Student Polls",
                        "Create, activate/close, or delete polls",
                        null));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_FLAGGED_REPORTS,
                        IconBackgroundColors.ORANGE_DEEP.top, IconBackgroundColors.ORANGE_DEEP.bottom,
                        R.drawable.msg_report,
                        "Flagged Questions Inbox",
                        "Review, dismiss, or delete student reports",
                        null));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_FEEDBACK_MESSAGES,
                        IconBackgroundColors.BLUE_LIGHT.top, IconBackgroundColors.BLUE_LIGHT.bottom,
                        R.drawable.settings_ask,
                        "Student Feedback Messages",
                        "Read, reply, mark read, or delete",
                        null));
                items.add(SettingsActivity.SettingCell.Factory.of(
                        ID_FEEDBACK_POSTS,
                        IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                        R.drawable.settings_faq,
                        "Feedback Posts",
                        "Create or remove feedback topics",
                        null));
            }
            items.add(UItem.asShadow(null));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_BANNERS:
                openBannersManager();
                break;
            case ID_FLOATING_LINK:
                openFloatingLinkEditor();
                break;
            case ID_POLLS:
                openPollsManager();
                break;
            case ID_APP_CONTENT_ABOUT:
                openAppContentEditor("about", "About EVE");
                break;
            case ID_APP_CONTENT_PRIVACY:
                openAppContentEditor("privacy", "Privacy Policy");
                break;
            case ID_APP_CONTENT_TERMS:
                openAppContentEditor("terms", "Terms of Service");
                break;
            case ID_SEND_BROADCAST:
                openSendBroadcastDialog();
                break;
            case ID_BROADCAST_HISTORY:
                openBroadcastHistory();
                break;
            case ID_FLAGGED_REPORTS:
                openFlaggedReports();
                break;
            case ID_FEEDBACK_MESSAGES:
                openFeedbackMessages();
                break;
            case ID_FEEDBACK_POSTS:
                openFeedbackPosts();
                break;
        }
    }

    private void openBannersManager() {
        repo.runWithAdminToken(errCallback(), token -> {
            List<EveAdminModels.BannerItem> banners = repo.fetchBanners(token);
            repo.postSuccess(new EveAdminRepository.ResultCallback<List<EveAdminModels.BannerItem>>() {
                @Override
                public void onSuccess(List<EveAdminModels.BannerItem> list) {
                    Activity act = getParentActivity();
                    if (act == null) return;
                    if (list.isEmpty()) {
                        BulletinFactory.of(EveAdminContentAndCommsFragment.this).createSimpleBulletin(R.raw.info, "No home banners configured.").show();
                        return;
                    }
                    String[] labels = new String[list.size()];
                    for (int i = 0; i < list.size(); i++) {
                        EveAdminModels.BannerItem b = list.get(i);
                        labels[i] = (i + 1) + ". " + (b.linkLabel.isEmpty() ? b.id : b.linkLabel)
                                + (b.linkUrl.isEmpty() ? "" : " (" + b.linkUrl + ")");
                    }
                    showDialog(new AlertDialog.Builder(act, getResourceProvider())
                            .setTitle("Home Banners (" + list.size() + ")")
                            .setItems(labels, (d, idx) -> showBannerActions(list.get(idx)))
                            .setNegativeButton(LocaleController.getString(R.string.Close), null)
                            .create());
                }
                @Override public void onError(String message, int httpStatus) {}
            }, banners);
        });
    }

    private void showBannerActions(EveAdminModels.BannerItem banner) {
        Activity act = getParentActivity();
        if (act == null) return;
        String[] opts = new String[]{"Edit Link & Label", "Move Up", "Move Down", "Delete Banner"};
        showDialog(new AlertDialog.Builder(act, getResourceProvider())
                .setTitle("Banner " + banner.id)
                .setItems(opts, (d, idx) -> {
                    if (idx == 0) {
                        EditText url = makeField(act, banner.linkUrl, "https://...");
                        EditText label = makeField(act, banner.linkLabel, "Label");
                        LinearLayout box = box(act, url, label);
                        showDialog(new AlertDialog.Builder(act, getResourceProvider())
                                .setTitle("Update Banner Link")
                                .setView(box)
                                .setPositiveButton(LocaleController.getString(R.string.Save), (dd, ww) -> runMutation(
                                        () -> {},
                                        token -> repo.updateBannerLink(token, banner.id, url.getText().toString().trim(), label.getText().toString().trim()),
                                        "Banner updated."))
                                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                                .create());
                    } else if (idx == 1 || idx == 2) {
                        boolean up = idx == 1;
                        runMutation(this::openBannersManager, token -> repo.reorderBanner(token, banner.id, up), "Banner reordered.");
                    } else {
                        runMutation(this::openBannersManager, token -> repo.deleteBanner(token, banner.id), "Banner deleted.");
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void openFloatingLinkEditor() {
        repo.runWithAdminToken(errCallback(), token -> {
            JSONObject obj = repo.fetchFloatingLink(token);
            String currentUrl = obj != null ? obj.optString("url", "") : "";
            repo.postSuccess(new EveAdminRepository.ResultCallback<String>() {
                @Override
                public void onSuccess(String urlText) {
                    Activity act = getParentActivity();
                    if (act == null) return;
                    EditText input = makeField(act, urlText, "https://... (empty to hide)");
                    showDialog(new AlertDialog.Builder(act, getResourceProvider())
                            .setTitle("Floating Community Link")
                            .setView(box(act, input))
                            .setPositiveButton(LocaleController.getString(R.string.Save), (d, w) ->
                                    runMutation(() -> {}, token -> repo.updateFloatingLink(token, input.getText().toString()), "Floating link saved."))
                            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                            .create());
                }
                @Override public void onError(String message, int httpStatus) {}
            }, currentUrl);
        });
    }

    private void openPollsManager() {
        repo.runWithAdminToken(errCallback(), token -> {
            List<EveAdminModels.PollItem> polls = repo.fetchPolls(token);
            repo.postSuccess(new EveAdminRepository.ResultCallback<List<EveAdminModels.PollItem>>() {
                @Override
                public void onSuccess(List<EveAdminModels.PollItem> list) {
                    Activity act = getParentActivity();
                    if (act == null) return;
                    String[] rows = new String[list.size() + 1];
                    rows[0] = "+ Create New Poll";
                    for (int i = 0; i < list.size(); i++) {
                        EveAdminModels.PollItem p = list.get(i);
                        rows[i + 1] = (p.active ? "[ACTIVE] " : "[CLOSED] ") + p.question + " (" + p.totalVotes + " votes)";
                    }
                    showDialog(new AlertDialog.Builder(act, getResourceProvider())
                            .setTitle("Student Polls")
                            .setItems(rows, (d, idx) -> {
                                if (idx == 0) {
                                    showCreatePollDialog();
                                } else {
                                    showPollActions(list.get(idx - 1));
                                }
                            })
                            .setNegativeButton(LocaleController.getString(R.string.Close), null)
                            .create());
                }
                @Override public void onError(String message, int httpStatus) {}
            }, polls);
        });
    }

    private void showCreatePollDialog() {
        Activity act = getParentActivity();
        if (act == null) return;
        EditText q = makeField(act, "", "Poll question");
        EditText opts = makeField(act, "", "Options (comma-separated, e.g. SSC CGL, UPSC, Banking)");
        showDialog(new AlertDialog.Builder(act, getResourceProvider())
                .setTitle("Create Student Poll")
                .setView(box(act, q, opts))
                .setPositiveButton("Create", (d, w) -> {
                    String qText = q.getText().toString().trim();
                    String[] split = opts.getText().toString().split(",");
                    List<String> list = new ArrayList<>();
                    for (String s : split) {
                        if (!s.trim().isEmpty()) list.add(s.trim());
                    }
                    if (qText.isEmpty() || list.size() < 2) {
                        BulletinFactory.of(this).createErrorBulletin("Enter a question and at least 2 options.").show();
                        return;
                    }
                    runMutation(this::openPollsManager, token -> repo.createPoll(token, qText, list), "Poll created.");
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void showPollActions(EveAdminModels.PollItem poll) {
        Activity act = getParentActivity();
        if (act == null) return;
        String[] opts = new String[]{poll.active ? "Close Poll" : "Activate Poll", "Delete Poll"};
        showDialog(new AlertDialog.Builder(act, getResourceProvider())
                .setTitle(poll.question)
                .setItems(opts, (d, idx) -> {
                    if (idx == 0) {
                        runMutation(this::openPollsManager, token -> repo.setPollActive(token, poll.id, !poll.active), "Poll status updated.");
                    } else {
                        runMutation(this::openPollsManager, token -> repo.deletePoll(token, poll.id), "Poll deleted.");
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void openAppContentEditor(String type, String label) {
        repo.runWithAdminToken(errCallback(), token -> {
            JSONObject data = repo.fetchAppContent(token, type);
            repo.postSuccess(new EveAdminRepository.ResultCallback<JSONObject>() {
                @Override
                public void onSuccess(JSONObject obj) {
                    Activity act = getParentActivity();
                    if (act == null) return;
                    EditText title = makeField(act, obj != null ? obj.optString("title", label) : label, "Title");
                    EditText body = makeField(act, obj != null ? obj.optString("body", "") : "", "Markdown / plain body");
                    body.setSingleLine(false);
                    body.setMinLines(5);
                    showDialog(new AlertDialog.Builder(act, getResourceProvider())
                            .setTitle("Edit " + label)
                            .setView(box(act, title, body))
                            .setPositiveButton(LocaleController.getString(R.string.Save), (d, w) ->
                                    runMutation(() -> {}, token -> repo.updateAppContent(token, type, title.getText().toString().trim(), body.getText().toString()), label + " saved."))
                            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                            .create());
                }
                @Override public void onError(String message, int httpStatus) {}
            }, data);
        });
    }

    private void openSendBroadcastDialog() {
        Activity act = getParentActivity();
        if (act == null) return;
        EditText title = makeField(act, "", "Broadcast title");
        EditText msg = makeField(act, "", "Notification message");
        msg.setSingleLine(false);
        msg.setMinLines(3);
        showDialog(new AlertDialog.Builder(act, getResourceProvider())
                .setTitle("Send EVE Broadcast")
                .setView(box(act, title, msg))
                .setPositiveButton("Send", (d, w) -> {
                    String t = title.getText().toString().trim();
                    String m = msg.getText().toString().trim();
                    if (t.isEmpty() || m.isEmpty()) {
                        BulletinFactory.of(this).createErrorBulletin("Title and message are required.").show();
                        return;
                    }
                    runMutation(() -> {}, token -> repo.sendBroadcast(token, t, m), "Broadcast delivered.");
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void openBroadcastHistory() {
        repo.runWithAdminToken(errCallback(), token -> {
            List<EveAdminModels.BroadcastItem> list = repo.fetchBroadcasts(token);
            repo.postSuccess(new EveAdminRepository.ResultCallback<List<EveAdminModels.BroadcastItem>>() {
                @Override
                public void onSuccess(List<EveAdminModels.BroadcastItem> items) {
                    Activity act = getParentActivity();
                    if (act == null) return;
                    if (items.isEmpty()) {
                        BulletinFactory.of(EveAdminContentAndCommsFragment.this).createSimpleBulletin(R.raw.info, "No broadcasts sent yet.").show();
                        return;
                    }
                    String[] labels = new String[items.size()];
                    for (int i = 0; i < items.size(); i++) {
                        labels[i] = items.get(i).title + "\n" + items.get(i).message;
                    }
                    showDialog(new AlertDialog.Builder(act, getResourceProvider())
                            .setTitle("Broadcast History (" + items.size() + ")")
                            .setItems(labels, (d, idx) -> {
                                EveAdminModels.BroadcastItem b = items.get(idx);
                                showDialog(new AlertDialog.Builder(act, getResourceProvider())
                                        .setTitle(b.title)
                                        .setMessage(b.message + "\n\nSent by: " + b.sentBy)
                                        .setPositiveButton(LocaleController.getString(R.string.Delete), (dd, ww) ->
                                                runMutation(EveAdminContentAndCommsFragment.this::openBroadcastHistory,
                                                        token -> repo.deleteBroadcast(token, b.id), "Broadcast deleted."))
                                        .setNegativeButton(LocaleController.getString(R.string.Close), null)
                                        .create());
                            })
                            .setNegativeButton(LocaleController.getString(R.string.Close), null)
                            .create());
                }
                @Override public void onError(String message, int httpStatus) {}
            }, list);
        });
    }

    private void openFlaggedReports() {
        repo.runWithAdminToken(errCallback(), token -> {
            List<EveAdminModels.QuestionReportItem> list = repo.fetchReports(token, null);
            repo.postSuccess(new EveAdminRepository.ResultCallback<List<EveAdminModels.QuestionReportItem>>() {
                @Override
                public void onSuccess(List<EveAdminModels.QuestionReportItem> items) {
                    Activity act = getParentActivity();
                    if (act == null) return;
                    if (items.isEmpty()) {
                        BulletinFactory.of(EveAdminContentAndCommsFragment.this).createSimpleBulletin(R.raw.contact_check, "No flagged questions.").show();
                        return;
                    }
                    String[] labels = new String[items.size()];
                    for (int i = 0; i < items.size(); i++) {
                        EveAdminModels.QuestionReportItem r = items.get(i);
                        labels[i] = "[" + r.status + "] " + r.reason + " — " + r.examName;
                    }
                    showDialog(new AlertDialog.Builder(act, getResourceProvider())
                            .setTitle("Flagged Questions (" + items.size() + ")")
                            .setItems(labels, (d, idx) -> {
                                EveAdminModels.QuestionReportItem r = items.get(idx);
                                showDialog(new AlertDialog.Builder(act, getResourceProvider())
                                        .setTitle(r.examName + " • " + r.reason)
                                        .setMessage(r.questionText + "\n\nComment: " + r.comment + "\nBy: " + r.studentEmail)
                                        .setPositiveButton("Dismiss Report", (dd, ww) ->
                                                runMutation(EveAdminContentAndCommsFragment.this::openFlaggedReports,
                                                        token -> repo.dismissReport(token, r.id), "Report dismissed."))
                                        .setNeutralButton(LocaleController.getString(R.string.Delete), (dd, ww) ->
                                                runMutation(EveAdminContentAndCommsFragment.this::openFlaggedReports,
                                                        token -> repo.deleteReport(token, r.id), "Report deleted."))
                                        .setNegativeButton(LocaleController.getString(R.string.Close), null)
                                        .create());
                            })
                            .setNegativeButton(LocaleController.getString(R.string.Close), null)
                            .create());
                }
                @Override public void onError(String message, int httpStatus) {}
            }, list);
        });
    }

    private void openFeedbackMessages() {
        repo.runWithAdminToken(errCallback(), token -> {
            List<EveAdminModels.FeedbackMessageItem> list = repo.fetchFeedbackMessages(token);
            repo.postSuccess(new EveAdminRepository.ResultCallback<List<EveAdminModels.FeedbackMessageItem>>() {
                @Override
                public void onSuccess(List<EveAdminModels.FeedbackMessageItem> items) {
                    Activity act = getParentActivity();
                    if (act == null) return;
                    if (items.isEmpty()) {
                        BulletinFactory.of(EveAdminContentAndCommsFragment.this).createSimpleBulletin(R.raw.info, "No feedback messages.").show();
                        return;
                    }
                    String[] labels = new String[items.size()];
                    for (int i = 0; i < items.size(); i++) {
                        EveAdminModels.FeedbackMessageItem m = items.get(i);
                        labels[i] = (m.read ? "" : "[NEW] ") + m.userEmail + ": " + m.message;
                    }
                    showDialog(new AlertDialog.Builder(act, getResourceProvider())
                            .setTitle("Student Feedback (" + items.size() + ")")
                            .setItems(labels, (d, idx) -> showFeedbackMessageDialog(items.get(idx)))
                            .setNegativeButton(LocaleController.getString(R.string.Close), null)
                            .create());
                }
                @Override public void onError(String message, int httpStatus) {}
            }, list);
        });
    }

    private void showFeedbackMessageDialog(EveAdminModels.FeedbackMessageItem msg) {
        Activity act = getParentActivity();
        if (act == null) return;
        EditText reply = makeField(act, msg.adminReply, "Write admin reply...");
        showDialog(new AlertDialog.Builder(act, getResourceProvider())
                .setTitle(msg.userEmail)
                .setMessage(msg.message)
                .setView(box(act, reply))
                .setPositiveButton("Send Reply", (d, w) -> {
                    String rText = reply.getText().toString().trim();
                    if (!rText.isEmpty()) {
                        runMutation(this::openFeedbackMessages, token -> repo.replyFeedbackMessage(token, msg.id, rText), "Reply sent.");
                    }
                })
                .setNeutralButton("Mark Read", (d, w) -> runMutation(this::openFeedbackMessages, token -> repo.markFeedbackRead(token, msg.id), "Marked as read."))
                .setNegativeButton(LocaleController.getString(R.string.Delete), (d, w) -> runMutation(this::openFeedbackMessages, token -> repo.deleteFeedbackMessage(token, msg.id), "Deleted."))
                .create());
    }

    private void openFeedbackPosts() {
        repo.runWithAdminToken(errCallback(), token -> {
            List<EveAdminModels.FeedbackPostItem> list = repo.fetchFeedbackPosts(token);
            repo.postSuccess(new EveAdminRepository.ResultCallback<List<EveAdminModels.FeedbackPostItem>>() {
                @Override
                public void onSuccess(List<EveAdminModels.FeedbackPostItem> items) {
                    Activity act = getParentActivity();
                    if (act == null) return;
                    String[] labels = new String[items.size() + 1];
                    labels[0] = "+ Create Feedback Post";
                    for (int i = 0; i < items.size(); i++) {
                        labels[i + 1] = items.get(i).title + " — " + items.get(i).message;
                    }
                    showDialog(new AlertDialog.Builder(act, getResourceProvider())
                            .setTitle("Feedback Posts")
                            .setItems(labels, (d, idx) -> {
                                if (idx == 0) {
                                    EditText t = makeField(act, "", "Post title");
                                    EditText b = makeField(act, "", "Post message");
                                    showDialog(new AlertDialog.Builder(act, getResourceProvider())
                                            .setTitle("New Feedback Post")
                                            .setView(box(act, t, b))
                                            .setPositiveButton("Create", (dd, ww) ->
                                                    runMutation(EveAdminContentAndCommsFragment.this::openFeedbackPosts,
                                                            token -> repo.createFeedbackPost(token, t.getText().toString().trim(), b.getText().toString().trim()), "Post created."))
                                            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                                            .create());
                                } else {
                                    EveAdminModels.FeedbackPostItem post = items.get(idx - 1);
                                    runMutation(EveAdminContentAndCommsFragment.this::openFeedbackPosts,
                                            token -> repo.deleteFeedbackPost(token, post.id), "Post deleted.");
                                }
                            })
                            .setNegativeButton(LocaleController.getString(R.string.Close), null)
                            .create());
                }
                @Override public void onError(String message, int httpStatus) {}
            }, list);
        });
    }

    private void runMutation(Runnable after, EveAdminRepository.TokenWork work, String okMessage) {
        repo.runWithAdminToken(errCallback(), token -> {
            work.run(token);
            repo.postSuccess(new EveAdminRepository.ResultCallback<Void>() {
                @Override
                public void onSuccess(Void data) {
                    BulletinFactory.of(EveAdminContentAndCommsFragment.this).createSimpleBulletin(R.raw.contact_check, okMessage).show();
                    if (after != null) after.run();
                }
                @Override public void onError(String message, int httpStatus) {}
            }, null);
        });
    }

    private <T> EveAdminRepository.ResultCallback<T> errCallback() {
        return new EveAdminRepository.ResultCallback<T>() {
            @Override public void onSuccess(T data) {}
            @Override public void onError(String message, int httpStatus) {
                BulletinFactory.of(EveAdminContentAndCommsFragment.this).createErrorBulletin(message).show();
            }
        };
    }

    private EditText makeField(Context ctx, String initial, String hint) {
        EditText et = new EditText(ctx);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setText(initial != null ? initial : "");
        et.setHint(hint);
        et.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        et.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, getResourceProvider()));
        return et;
    }

    private static LinearLayout box(Context ctx, View... views) {
        LinearLayout ll = new LinearLayout(ctx);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(8));
        for (View v : views) {
            ll.addView(v, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }
        return ll;
    }
}
