package org.telegram.ui.eve.auth;

import android.content.Context;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thread-safe persistent disk SWR (Stale-While-Revalidate) JSON cache stored under
 * {@code context.getCacheDir()/eve_swr_cache/} with timestamp metadata and atomic temp-file writes.
 */
public final class EveLocalDiskCache {

    private static final String CACHE_DIR_NAME = "eve_swr_cache";
    private static final String FILE_EXT = ".json";

    public static final class CacheEntry {
        public final String key;
        public final String payload;
        public final long updatedAtMs;

        public CacheEntry(String key, String payload, long updatedAtMs) {
            this.key = key != null ? key : "";
            this.payload = payload != null ? payload : "";
            this.updatedAtMs = Math.max(0L, updatedAtMs);
        }

        public boolean isEmpty() {
            return payload.isEmpty();
        }
    }

    private static volatile EveLocalDiskCache instance;

    private final ConcurrentHashMap<String, CacheEntry> memoryCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();
    private final ExecutorService diskWriter = Executors.newSingleThreadExecutor();
    @Nullable
    private final File explicitDir;

    public static EveLocalDiskCache getInstance() {
        EveLocalDiskCache local = instance;
        if (local == null) {
            synchronized (EveLocalDiskCache.class) {
                local = instance;
                if (local == null) {
                    local = new EveLocalDiskCache(null);
                    instance = local;
                }
            }
        }
        return local;
    }

    public EveLocalDiskCache(@Nullable File customCacheDir) {
        this.explicitDir = customCacheDir;
    }

    @Nullable
    private File resolveCacheDir() {
        if (explicitDir != null) {
            if (!explicitDir.exists()) {
                explicitDir.mkdirs();
            }
            return explicitDir;
        }
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return null;
            }
            File base = ctx.getCacheDir();
            if (base == null) {
                return null;
            }
            File dir = new File(base, CACHE_DIR_NAME);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return dir;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object lockFor(String key) {
        Object lock = keyLocks.get(key);
        if (lock == null) {
            Object created = new Object();
            Object existing = keyLocks.putIfAbsent(key, created);
            lock = existing != null ? existing : created;
        }
        return lock;
    }

    public void put(@NonNull String key, @NonNull String rawJson) {
        if (key == null || key.trim().isEmpty() || rawJson == null) {
            return;
        }
        final String normalizedKey = key.trim();
        final long now = System.currentTimeMillis();
        final CacheEntry entry = new CacheEntry(normalizedKey, rawJson, now);
        memoryCache.put(normalizedKey, entry);

        if (isMainThread()) {
            diskWriter.execute(() -> writeEntryToDisk(normalizedKey, entry));
        } else {
            writeEntryToDisk(normalizedKey, entry);
        }
    }

    @NonNull
    public String get(@NonNull String key) {
        CacheEntry entry = getEntry(key);
        return entry != null ? entry.payload : "";
    }

    @Nullable
    public CacheEntry getEntry(@NonNull String key) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        final String normalizedKey = key.trim();
        CacheEntry mem = memoryCache.get(normalizedKey);
        if (mem != null) {
            return mem;
        }
        CacheEntry disk = readEntryFromDisk(normalizedKey);
        if (disk != null) {
            memoryCache.putIfAbsent(normalizedKey, disk);
            return disk;
        }
        return null;
    }

    public long getUpdatedAtMs(@NonNull String key) {
        CacheEntry entry = getEntry(key);
        return entry != null ? entry.updatedAtMs : 0L;
    }

    public void invalidate(@NonNull String key) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }
        final String normalizedKey = key.trim();
        memoryCache.remove(normalizedKey);
        if (isMainThread()) {
            diskWriter.execute(() -> deleteKeyFile(normalizedKey));
        } else {
            deleteKeyFile(normalizedKey);
        }
    }

    public void invalidatePrefix(@NonNull String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return;
        }
        final String normalizedPrefix = prefix.trim();
        for (Map.Entry<String, CacheEntry> e : memoryCache.entrySet()) {
            if (e.getKey().startsWith(normalizedPrefix)) {
                memoryCache.remove(e.getKey());
            }
        }
        Runnable deleteTask = () -> {
            File dir = resolveCacheDir();
            if (dir == null || !dir.exists()) {
                return;
            }
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            for (File f : files) {
                if (f == null || !f.isFile() || !f.getName().endsWith(FILE_EXT)) {
                    continue;
                }
                CacheEntry entry = readEntryFile(f);
                if (entry != null && entry.key.startsWith(normalizedPrefix)) {
                    synchronized (lockFor(entry.key)) {
                        f.delete();
                    }
                }
            }
        };
        if (isMainThread()) {
            diskWriter.execute(deleteTask);
        } else {
            deleteTask.run();
        }
    }

    public void clearAll() {
        memoryCache.clear();
        Runnable clearTask = () -> {
            File dir = resolveCacheDir();
            if (dir == null || !dir.exists()) {
                return;
            }
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            for (File f : files) {
                if (f != null && f.isFile()) {
                    f.delete();
                }
            }
        };
        if (isMainThread()) {
            diskWriter.execute(clearTask);
        } else {
            clearTask.run();
        }
    }

    private void writeEntryToDisk(String key, CacheEntry entry) {
        File dir = resolveCacheDir();
        if (dir == null) {
            return;
        }
        synchronized (lockFor(key)) {
            String fileName = safeFileName(key);
            File target = new File(dir, fileName + FILE_EXT);
            File tmp = new File(dir, fileName + ".tmp");
            try {
                JSONObject envelope = new JSONObject();
                envelope.put("key", entry.key);
                envelope.put("updatedAt", entry.updatedAtMs);
                envelope.put("payload", entry.payload);
                byte[] bytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream fos = new FileOutputStream(tmp, false)) {
                    fos.write(bytes);
                    fos.flush();
                }
                if (!tmp.renameTo(target)) {
                    if (target.exists()) {
                        target.delete();
                    }
                    if (!tmp.renameTo(target)) {
                        try (FileOutputStream fos = new FileOutputStream(target, false)) {
                            fos.write(bytes);
                            fos.flush();
                        }
                        tmp.delete();
                    }
                }
            } catch (Throwable ignored) {
                if (tmp.exists()) {
                    tmp.delete();
                }
            }
        }
    }

    @Nullable
    private CacheEntry readEntryFromDisk(String key) {
        File dir = resolveCacheDir();
        if (dir == null || !dir.exists()) {
            return null;
        }
        synchronized (lockFor(key)) {
            File target = new File(dir, safeFileName(key) + FILE_EXT);
            if (!target.exists() || !target.isFile()) {
                return null;
            }
            CacheEntry entry = readEntryFile(target);
            if (entry != null && (entry.key.isEmpty() || key.equals(entry.key))) {
                return new CacheEntry(key, entry.payload, entry.updatedAtMs);
            }
            return null;
        }
    }

    @Nullable
    private static CacheEntry readEntryFile(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            String raw = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            if (raw.trim().isEmpty()) {
                return null;
            }
            JSONObject envelope = new JSONObject(raw);
            String storedKey = envelope.optString("key", "");
            long updatedAt = envelope.optLong("updatedAt", file.lastModified());
            String payload = envelope.optString("payload", "");
            if (payload.isEmpty()) {
                return null;
            }
            return new CacheEntry(storedKey, payload, updatedAt);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void deleteKeyFile(String key) {
        File dir = resolveCacheDir();
        if (dir == null || !dir.exists()) {
            return;
        }
        synchronized (lockFor(key)) {
            String fileName = safeFileName(key);
            File target = new File(dir, fileName + FILE_EXT);
            if (target.exists()) {
                target.delete();
            }
            File tmp = new File(dir, fileName + ".tmp");
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    private static String safeFileName(String key) {
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < key.length() && prefix.length() < 40; i++) {
            char c = key.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                prefix.append(c);
            } else {
                prefix.append('_');
            }
        }
        String hash = sha256Short(key);
        return (prefix.length() > 0 ? prefix.toString() : "k") + "_" + hash;
    }

    private static String sha256Short(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8 && i < digest.length; i++) {
                hex.append(String.format("%02x", digest[i] & 0xff));
            }
            return hex.toString();
        } catch (Throwable ignored) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static boolean isMainThread() {
        try {
            Looper main = Looper.getMainLooper();
            return main != null && main.getThread() == Thread.currentThread();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
