package org.telegram.ui.eve.auth;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LoginActivity;
import org.telegram.ui.eve.exams.EveAnnouncementsFragment;
import org.telegram.ui.eve.exams.EveExamRepository;

/**
 * Coordinates Telegram MTProto connectivity alongside EVE's Firebase / Cloudflare Worker session.
 *
 * Dual-session invariants:
 *  - Both Debug and Release builds target Production DC (`shouldUseTestDc() == false`) because Telegram
 *    disabled +99966XYYYY Test DC accounts; `recoverStuckTestDcIfNeeded(...)` returns stale Test DC clients to Production.
 *  - Real MTProto sessions are tracked separately from EVE's synthetic in-memory UI mirror user so
 *    EveAuthManager.ensureLocalUiUser(...) never overwrites a real Telegram UserConfig / MessagesController user.
 *  - Disconnecting Telegram MTProto never signs out of Firebase / EVE Exams / Admin Control Center.
 */
public final class EveTelegramSessionManager {

    private static final String PREFS_NAME = "eve_telegram_session";
    private static final String KEY_REAL_MTPROTO_PREFIX = "real_mtproto_account_";
    private static final String KEY_REAL_USER_ID_PREFIX = "real_mtproto_uid_";

    private static volatile EveTelegramSessionManager instance;

    private EveTelegramSessionManager() {
    }

    public static EveTelegramSessionManager getInstance() {
        EveTelegramSessionManager local = instance;
        if (local == null) {
            synchronized (EveTelegramSessionManager.class) {
                local = instance;
                if (local == null) {
                    local = new EveTelegramSessionManager();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * Production DC is the default across both Debug and Release builds because Telegram disabled
     * +99966XYYYY Test DC accounts.
     */
    public static boolean shouldUseTestDc() {
        return false;
    }

    /**
     * Pure-Java helper distinguishing a real MTProto client user from EVE's synthetic placeholder ID.
     */
    public static boolean isRealMtprotoUserId(boolean clientActivated, long clientUserId, long eveSyntheticUserId) {
        if (!clientActivated || clientUserId <= 0L) {
            return false;
        }
        return eveSyntheticUserId <= 0L || clientUserId != eveSyntheticUserId;
    }

    /**
     * Sanitizes an optional @username or t.me link into a bare Telegram username for openByUserName.
     */
    @NonNull
    public static String sanitizeUsernameForResolve(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        cleaned = cleaned.replaceFirst("(?i)^https?://(www\\.)?t\\.me/", "");
        cleaned = cleaned.replaceFirst("(?i)^t\\.me/", "");
        int q = cleaned.indexOf('?');
        if (q >= 0) {
            cleaned = cleaned.substring(0, q);
        }
        cleaned = cleaned.replaceAll("^/+", "").replaceAll("/+$", "").replaceAll("^@+", "").trim();
        if (!cleaned.matches("[a-zA-Z0-9_]{3,32}")) {
            return "";
        }
        return cleaned;
    }

    @Nullable
    private static SharedPreferences getPrefs() {
        Context ctx = ApplicationLoader.applicationContext;
        return ctx != null ? ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) : null;
    }

    public boolean isRealMtprotoSession(int account) {
        try {
            UserConfig userConfig = UserConfig.getInstance(account);
            if (userConfig == null || !userConfig.isClientActivated() || userConfig.getCurrentUser() == null) {
                return false;
            }
            long clientUserId = userConfig.getClientUserId();
            long syntheticId = EveAuthManager.getInstance().getEffectiveUiUserId();
            if (!isRealMtprotoUserId(true, clientUserId, syntheticId)) {
                return false;
            }
            SharedPreferences prefs = getPrefs();
            if (prefs != null && prefs.getBoolean(KEY_REAL_MTPROTO_PREFIX + account, false)) {
                return true;
            }
            // Persisted UserConfig.currentUser from LoginActivity also proves a real MTProto login.
            return true;
        } catch (Throwable t) {
            FileLog.e("EveTelegramSessionManager: isRealMtprotoSession check failed", t);
            return false;
        }
    }

    public void onTelegramAuthSuccess(int account, @Nullable TLRPC.User user) {
        try {
            SharedPreferences prefs = getPrefs();
            if (prefs != null) {
                prefs.edit()
                        .putBoolean(KEY_REAL_MTPROTO_PREFIX + account, true)
                        .putLong(KEY_REAL_USER_ID_PREFIX + account, user != null ? user.id : 0L)
                        .apply();
            }
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.mainUserInfoChanged);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsUnreadCounterChanged);
            // Automatically link Telegram identity in D1 via POST /api/users/sync.
            EveAuthManager.getInstance().refreshSessionSilentlyIfSignedIn();
        } catch (Throwable t) {
            FileLog.e("EveTelegramSessionManager: onTelegramAuthSuccess failed", t);
        }
    }

    @Nullable
    public String getLinkedTelegramUserId(int account) {
        if (!isRealMtprotoSession(account)) {
            return null;
        }
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        return user != null && user.id > 0 ? String.valueOf(user.id) : null;
    }

    @Nullable
    public String getLinkedTelegramUsername(int account) {
        if (!isRealMtprotoSession(account)) {
            return null;
        }
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        if (user == null) {
            return null;
        }
        String username = UserObject.getPublicUsername(user);
        return !TextUtils.isEmpty(username) ? username.trim() : null;
    }

    /**
     * Automatically recovers any device previously switched to Test DC when no real MTProto session
     * is active, without requiring app reinstall or clearing app data.
     */
    public void recoverStuckTestDcIfNeeded(int account) {
        try {
            if (shouldUseTestDc() || isRealMtprotoSession(account)) {
                return;
            }
            ConnectionsManager cm = ConnectionsManager.getInstance(account);
            if (cm != null && cm.isTestBackend()) {
                cm.switchBackend(false);
            }
        } catch (Throwable t) {
            FileLog.e("EveTelegramSessionManager: recoverStuckTestDcIfNeeded failed", t);
        }
    }

    public void launchTelegramLogin(@Nullable BaseFragment host) {
        if (host == null || host.getParentActivity() == null) {
            return;
        }
        final int account = UserConfig.selectedAccount;
        try {
            ConnectionsManager cm = ConnectionsManager.getInstance(account);
            if (shouldUseTestDc() && !cm.isTestBackend()) {
                cm.switchBackend(false);
            } else if (!shouldUseTestDc() && cm.isTestBackend()) {
                cm.switchBackend(false);
            }
            if (!isRealMtprotoSession(account)) {
                UserConfig uc = UserConfig.getInstance(account);
                if (uc.isClientActivated()) {
                    // Only clear if an unexpected placeholder was activated.
                    uc.clearConfig();
                }
            }
            host.presentFragment(new LoginActivity(account));
        } catch (Throwable t) {
            FileLog.e("EveTelegramSessionManager: launchTelegramLogin failed", t);
        }
    }

    public void disconnectTelegramSession(@Nullable BaseFragment host, @Nullable Runnable onDisconnected) {
        if (host == null || host.getParentActivity() == null) {
            return;
        }
        final int account = UserConfig.selectedAccount;
        try {
            SharedPreferences prefs = getPrefs();
            if (prefs != null) {
                prefs.edit()
                        .remove(KEY_REAL_MTPROTO_PREFIX + account)
                        .remove(KEY_REAL_USER_ID_PREFIX + account)
                        .apply();
            }
            MessagesController.getInstance(account).performLogout(1);
            if (EveAuthManager.getInstance().isSignedInAccount()) {
                EveAuthManager.getInstance().ensureLocalUiUser(account);
            }
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.mainUserInfoChanged);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsUnreadCounterChanged);
            if (onDisconnected != null) {
                AndroidUtilities.runOnUIThread(onDisconnected);
            }
        } catch (Throwable t) {
            FileLog.e("EveTelegramSessionManager: disconnectTelegramSession failed", t);
        }
    }

    public void confirmAndDisconnect(@Nullable BaseFragment host, @Nullable Runnable onDisconnected) {
        if (host == null) {
            return;
        }
        Activity activity = host.getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, host.getResourceProvider());
        builder.setTitle("Disconnect Telegram Chat");
        builder.setMessage("Your Telegram Chat session will be signed out. Your EVE Exams, Mock Tests, and Google account remain signed in.");
        builder.setPositiveButton("Disconnect", (d, w) -> disconnectTelegramSession(host, onDisconnected));
        builder.setNegativeButton("Cancel", null);
        host.showDialog(builder.create());
    }

    @NonNull
    public String getConnectionStatusSubtitle(int account) {
        if (!isRealMtprotoSession(account)) {
            return "Not Connected — Tap to Connect";
        }
        boolean isTestDc = false;
        try {
            isTestDc = ConnectionsManager.getInstance(account).isTestBackend();
        } catch (Throwable ignored) {
        }
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        String handle = user != null ? UserObject.getPublicUsername(user) : null;
        StringBuilder sb = new StringBuilder("Connected");
        if (isTestDc) {
            sb.append(" • Test DC");
        }
        if (!TextUtils.isEmpty(handle)) {
            sb.append(" (@").append(handle).append(")");
        } else if (user != null && !TextUtils.isEmpty(user.phone)) {
            sb.append(" (+").append(user.phone).append(")");
        }
        return sb.toString();
    }

    public boolean openOfficialChannelIfConnected(@Nullable BaseFragment host) {
        if (host == null || host.getParentActivity() == null) {
            return false;
        }
        final int account = UserConfig.selectedAccount;
        if (!isRealMtprotoSession(account)) {
            return false;
        }
        EveBackendClient.AppConfigResult cfg = EveExamRepository.getInstance().getCachedAppConfig();
        String target = cfg != null ? sanitizeUsernameForResolve(cfg.officialTelegramChannelUsername) : "";
        if (target.isEmpty()) {
            return false;
        }
        MessagesController.getInstance(account).openByUserName(target, host, 0);
        return true;
    }

    public void openAnnouncementsDestination(@Nullable BaseFragment host) {
        if (host == null) {
            return;
        }
        if (!openOfficialChannelIfConnected(host)) {
            host.presentFragment(new EveAnnouncementsFragment());
        }
    }

    public boolean openOfficialSupportIfConnected(@Nullable BaseFragment host) {
        if (host == null || host.getParentActivity() == null) {
            return false;
        }
        final int account = UserConfig.selectedAccount;
        if (!isRealMtprotoSession(account)) {
            return false;
        }
        EveBackendClient.AppConfigResult cfg = EveExamRepository.getInstance().getCachedAppConfig();
        String target = cfg != null ? sanitizeUsernameForResolve(cfg.officialTelegramSupportUsername) : "";
        if (target.isEmpty()) {
            return false;
        }
        MessagesController.getInstance(account).openByUserName(target, host, 0);
        return true;
    }
}
