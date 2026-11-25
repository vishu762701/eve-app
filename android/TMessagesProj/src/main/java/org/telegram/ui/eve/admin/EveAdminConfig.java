package org.telegram.ui.eve.admin;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.EveInspectionMode;
import org.telegram.messenger.FileLog;
import org.telegram.ui.eve.auth.EveAuthManager;
import org.telegram.ui.eve.auth.EveUserProfile;

import java.io.File;

/**
 * EVE Admin Control Center availability and client-side visibility helpers.
 *
 * Server authority lives exclusively in the Cloudflare Worker and D1 (`admins` + `admin_rights`).
 * In release builds, only verified authenticated administrators see the Admin Control Center;
 * DEBUG inspection builds additionally allow local diagnostics without granting any backend privilege.
 */
public final class EveAdminConfig {

    private static final String PREFS_NAME = "eve_debug_admin_config";

    public static final String SECTION_ACCOUNT = "section_account";
    public static final String SECTION_CHAT_SETTINGS = "section_chat_settings";
    public static final String SECTION_PRIVACY = "section_privacy";
    public static final String SECTION_NOTIFICATIONS = "section_notifications";
    public static final String SECTION_DATA_STORAGE = "section_data_storage";
    public static final String SECTION_CHAT_FOLDERS = "section_chat_folders";
    public static final String SECTION_DEVICES = "section_devices";
    public static final String SECTION_POWER_SAVING = "section_power_saving";
    public static final String SECTION_LANGUAGE = "section_language";

    public static final String[] ALL_SECTIONS = new String[] {
        SECTION_ACCOUNT,
        SECTION_CHAT_SETTINGS,
        SECTION_PRIVACY,
        SECTION_NOTIFICATIONS,
        SECTION_DATA_STORAGE,
        SECTION_CHAT_FOLDERS,
        SECTION_DEVICES,
        SECTION_POWER_SAVING,
        SECTION_LANGUAGE
    };

    private EveAdminConfig() {
    }

    public static boolean isServerAdminProfile(EveUserProfile profile) {
        return profile != null
                && profile.isAdmin
                && profile.emailVerified
                && !profile.email.isEmpty();
    }

    public static boolean isServerAdminSession() {
        try {
            return isServerAdminProfile(EveAuthManager.getInstance().getCachedProfile());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasRight(EveAdminModels.AdminProfile profile, String right) {
        if (profile == null) {
            return false;
        }
        if (profile.isOwner) {
            return true;
        }
        return right != null && profile.rights.contains(right);
    }

    public static boolean isAdminAvailable() {
        return isServerAdminSession() || EveInspectionMode.isEnabledForCurrentBuild();
    }

    private static SharedPreferences prefs() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return null;
        }
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isSectionVisible(String sectionKey) {
        if (!isAdminAvailable() || sectionKey == null) {
            return true;
        }
        SharedPreferences p = prefs();
        return p == null || p.getBoolean(sectionKey, true);
    }

    public static void setSectionVisible(String sectionKey, boolean visible) {
        if (!isAdminAvailable() || sectionKey == null) {
            return;
        }
        SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putBoolean(sectionKey, visible).apply();
        }
    }

    public static boolean toggleSectionVisible(String sectionKey) {
        boolean next = !isSectionVisible(sectionKey);
        setSectionVisible(sectionKey, next);
        return next;
    }

    public static void resetAllSectionVisibility() {
        if (!isAdminAvailable()) {
            return;
        }
        SharedPreferences p = prefs();
        if (p != null) {
            SharedPreferences.Editor editor = p.edit();
            for (String key : ALL_SECTIONS) {
                editor.putBoolean(key, true);
            }
            editor.apply();
        }
    }

    public static final class CacheStats {
        public final int fileCount;
        public final long totalBytes;

        public CacheStats(int fileCount, long totalBytes) {
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
        }
    }

    public static CacheStats inspectCache(Context context) {
        if (context == null) {
            return new CacheStats(0, 0);
        }
        int[] count = new int[1];
        long[] bytes = new long[1];
        walkDir(context.getCacheDir(), count, bytes);
        walkDir(context.getExternalCacheDir(), count, bytes);
        return new CacheStats(count[0], bytes[0]);
    }

    public static int clearSafeCache(Context context) {
        if (!isAdminAvailable() || context == null) {
            return 0;
        }
        int deleted = 0;
        deleted += deleteChildren(context.getCacheDir());
        deleted += deleteChildren(context.getExternalCacheDir());
        return deleted;
    }

    private static void walkDir(File dir, int[] count, long[] bytes) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                walkDir(f, count, bytes);
            } else {
                count[0]++;
                bytes[0] += f.length();
            }
        }
    }

    private static int deleteChildren(File dir) {
        if (dir == null || !dir.exists()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int deleted = 0;
        for (File f : files) {
            try {
                if (f.isDirectory()) {
                    deleted += deleteChildren(f);
                    f.delete();
                } else if (f.delete()) {
                    deleted++;
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        return deleted;
    }
}
