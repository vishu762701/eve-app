package org.telegram.messenger;

import androidx.annotation.Nullable;

import org.telegram.tgnet.TLRPC;

/**
 * DEBUG-only Local UI Inspection Mode controller.
 *
 * Allows inspecting the original Telegram-derived UI (with the 13 removed feature
 * ecosystems absent) directly on launch without requiring Google Sign-In,
 * Email/Password login, Telegram phone OTP, or any fabricated user session.
 *
 * Strictly disabled in release/production builds (BuildConfig.DEBUG_VERSION == false).
 */
public final class EveInspectionMode {

    public static final long LOCAL_INSPECTION_USER_ID = 900000001L;
    public static final String LOCAL_INSPECTION_FIRST_NAME = "EVE";
    public static final String LOCAL_INSPECTION_LAST_NAME = "Local Inspector";
    public static final String LOCAL_INSPECTION_USERNAME = "eve_local_inspector";
    public static final String LOCAL_INSPECTION_BIO = "Local UI Inspection Identity (Offline — No Telegram Account)";
    public static final String LOCAL_INSPECTION_QR_URL = "eve://local-inspection/profile/eve_local_inspector";
    public static final String LOCAL_INSPECTION_QR_CAPTION = "EVE LOCAL INSPECTION";

    private EveInspectionMode() {
    }

    /**
     * Pure helper for deterministic JVM unit testing of the release-build safety gate.
     */
    public static boolean isEnabledForBuild(boolean debugVersion) {
        return debugVersion;
    }

    /**
     * Returns true ONLY in debug builds; guaranteed false in release builds.
     */
    public static boolean isEnabledForCurrentBuild() {
        return isEnabledForBuild(BuildConfig.DEBUG_VERSION);
    }

    /**
     * Local UI inspection session bypass is disabled on the real authentication/client path.
     * Unauthenticated sessions must route through official Telegram IntroActivity / LoginActivity
     * and never fabricate a synthetic user or block MTProto requests.
     */
    public static boolean isActive(int account) {
        return false;
    }

    public static boolean isLocalInspectionUser(long userId) {
        return isEnabledForCurrentBuild() && userId == LOCAL_INSPECTION_USER_ID;
    }

    public static String getLocalInspectionQrUrl() {
        return LOCAL_INSPECTION_QR_URL;
    }

    public static String getLocalInspectionQrCaption() {
        return LOCAL_INSPECTION_QR_CAPTION;
    }

    public static TLRPC.TL_user buildLocalInspectionUser() {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = LOCAL_INSPECTION_USER_ID;
        user.first_name = LOCAL_INSPECTION_FIRST_NAME;
        user.last_name = LOCAL_INSPECTION_LAST_NAME;
        user.username = LOCAL_INSPECTION_USERNAME;
        user.phone = "";
        user.self = false;
        user.flags |= 2 | 4 | 8;
        return user;
    }

    public static TLRPC.TL_userFull buildLocalInspectionUserFull() {
        TLRPC.TL_userFull full = new TLRPC.TL_userFull();
        full.id = LOCAL_INSPECTION_USER_ID;
        full.about = LOCAL_INSPECTION_BIO;
        full.flags |= 2;
        return full;
    }

    /**
     * Registers a clearly labeled in-memory local inspection user & UserFull
     * in MessagesController ONLY while in DEBUG inspection mode.
     * Never activates UserConfig or fabricates a server session.
     */
    @Nullable
    public static TLRPC.User ensureLocalInspectionUser(int account) {
        if (!isActive(account)) {
            return null;
        }
        try {
            MessagesController mc = MessagesController.getInstance(account);
            TLRPC.User existing = mc.getUser(LOCAL_INSPECTION_USER_ID);
            if (existing == null) {
                existing = buildLocalInspectionUser();
                mc.putUser(existing, true);
            }
            if (mc.getUserFull(LOCAL_INSPECTION_USER_ID) == null) {
                mc.putLocalInspectionUserFull(LOCAL_INSPECTION_USER_ID, buildLocalInspectionUserFull());
            }
            return existing;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    /**
     * Resets local preview diagnostics cleanly without fabricating any Telegram user or dialogs.
     */
    public static void resetLocalPreviewState(int account) {
        if (!isEnabledForCurrentBuild()) {
            return;
        }
        try {
            EveLoadingTracker.clear();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
