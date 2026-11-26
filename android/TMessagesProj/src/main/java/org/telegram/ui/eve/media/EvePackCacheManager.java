package org.telegram.ui.eve.media;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Disk-backed offline pack cache with SHA-256 verification, atomic staging installs,
 * incremental asset reuse across version updates, and preview asset caching.
 */
public final class EvePackCacheManager {

    public interface AssetFetcher {
        byte[] fetchAssetBytes(EvePackModels.PackItem item) throws IOException;
    }

    public interface ProgressListener {
        void onItemCompleted(int completedCount, int totalCount, EvePackModels.PackItem item);
    }

    private static final String INDEX_FILE_NAME = "installed_packs.json";
    private static volatile EvePackCacheManager instance;

    private final File rootDir;
    private final File packsDir;
    private final File previewsDir;
    private final Object lock = new Object();

    public static EvePackCacheManager getInstance(Context context) {
        EvePackCacheManager current = instance;
        if (current == null) {
            synchronized (EvePackCacheManager.class) {
                current = instance;
                if (current == null) {
                    File base = context != null ? context.getApplicationContext().getFilesDir() : new File(".");
                    current = new EvePackCacheManager(new File(base, "eve_pack_cache"));
                    instance = current;
                }
            }
        }
        return current;
    }

    public EvePackCacheManager(File rootDir) {
        this.rootDir = rootDir != null ? rootDir : new File(".", "eve_pack_cache");
        this.packsDir = new File(this.rootDir, "packs");
        this.previewsDir = new File(this.rootDir, "previews");
        ensureDirectories();
    }

    private void ensureDirectories() {
        if (!rootDir.exists()) rootDir.mkdirs();
        if (!packsDir.exists()) packsDir.mkdirs();
        if (!previewsDir.exists()) previewsDir.mkdirs();
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes != null ? bytes : new byte[0]);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static boolean verifyAssetIntegrity(byte[] bytes, String expectedSha256) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        if (expectedSha256 == null || expectedSha256.trim().isEmpty()) {
            return true;
        }
        return sha256Hex(bytes).equalsIgnoreCase(expectedSha256.trim());
    }

    private static String sanitizeSegment(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "unknown";
        }
        return value.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private File packBaseDir(String packId) {
        return new File(packsDir, sanitizeSegment(packId));
    }

    private File versionDir(String packId, int version) {
        return new File(packBaseDir(packId), "v" + Math.max(1, version));
    }

    private File assetFileInDir(File dir, EvePackModels.PackItem item) {
        return new File(dir, sanitizeSegment(item.id) + "." + item.fileExtension());
    }

    public File getPreviewCacheFile(EvePackModels.PackItem item) {
        if (item == null || item.id.isEmpty()) {
            return null;
        }
        String hashPart = !item.sha256.isEmpty() ? sanitizeSegment(item.sha256.substring(0, Math.min(12, item.sha256.length()))) : "raw";
        return new File(previewsDir, sanitizeSegment(item.id) + "_" + hashPart + "." + item.fileExtension());
    }

    public File writePreviewFile(EvePackModels.PackItem item, byte[] bytes) throws IOException {
        if (!verifyAssetIntegrity(bytes, item.sha256)) {
            throw new IOException("SHA-256 mismatch while caching preview for " + item.id);
        }
        ensureDirectories();
        File target = getPreviewCacheFile(item);
        if (target == null) {
            throw new IOException("Invalid pack item for preview cache");
        }
        writeBytesAtomic(target, bytes);
        return target;
    }

    public EvePackModels.InstalledPackRecord installPack(
            EvePackModels.PackManifest manifest,
            AssetFetcher fetcher,
            ProgressListener progressListener) throws IOException {
        if (manifest == null || manifest.packId.isEmpty()) {
            throw new IOException("Pack manifest is missing packId");
        }
        if (fetcher == null) {
            throw new IOException("Asset fetcher is required");
        }

        ensureDirectories();
        File baseDir = packBaseDir(manifest.packId);
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw new IOException("Unable to create pack directory");
        }

        File targetVersionDir = versionDir(manifest.packId, manifest.version);
        File stagingDir = new File(baseDir, "v" + manifest.version + ".staging_" + System.currentTimeMillis());
        if (!stagingDir.mkdirs()) {
            throw new IOException("Unable to create staging directory for pack install");
        }

        try {
            EvePackModels.InstalledPackRecord existingRecord;
            synchronized (lock) {
                existingRecord = readIndexLocked().get(manifest.packId);
            }
            File existingVersionDir = existingRecord != null ? versionDir(manifest.packId, existingRecord.version) : null;

            long downloadedBytes = 0L;
            int total = manifest.items.size();
            for (int i = 0; i < total; i++) {
                EvePackModels.PackItem item = manifest.items.get(i);
                File destFile = assetFileInDir(stagingDir, item);

                byte[] reusedBytes = tryReadReusableAsset(existingVersionDir, item);
                if (reusedBytes != null) {
                    writeBytesDirect(destFile, reusedBytes);
                    downloadedBytes += reusedBytes.length;
                } else if (item.assetUrl.startsWith("res:raw/")) {
                    // Built-in APK raw resource pointer needs no binary download.
                } else {
                    byte[] fetched = fetcher.fetchAssetBytes(item);
                    if (!verifyAssetIntegrity(fetched, item.sha256)) {
                        throw new IOException("Asset integrity check failed (SHA-256 mismatch) for " + item.altEmoji + " (" + item.id + ")");
                    }
                    writeBytesDirect(destFile, fetched);
                    downloadedBytes += fetched.length;
                }

                if (progressListener != null) {
                    progressListener.onItemCompleted(i + 1, total, item);
                }
            }

            EvePackModels.InstalledPackRecord newRecord = new EvePackModels.InstalledPackRecord(
                    manifest.packId,
                    manifest.name,
                    manifest.title,
                    manifest.packType,
                    manifest.version,
                    manifest.items.size(),
                    Math.max(manifest.totalBytes, downloadedBytes),
                    manifest.manifestSha256,
                    System.currentTimeMillis(),
                    manifest.items
            );
            writeBytesDirect(new File(stagingDir, "manifest.json"), newRecord.toJson().toString().getBytes(StandardCharsets.UTF_8));

            synchronized (lock) {
                if (targetVersionDir.exists()) {
                    deleteRecursively(targetVersionDir);
                }
                if (!stagingDir.renameTo(targetVersionDir)) {
                    throw new IOException("Failed to finalize pack installation directory");
                }
                Map<String, EvePackModels.InstalledPackRecord> index = readIndexLocked();
                index.put(manifest.packId, newRecord);
                writeIndexLocked(index);

                // Clean obsolete older version directories after atomic install succeeds
                File[] children = baseDir.listFiles();
                if (children != null) {
                    String keepName = targetVersionDir.getName();
                    for (File child : children) {
                        if (!keepName.equals(child.getName())) {
                            deleteRecursively(child);
                        }
                    }
                }
            }
            return newRecord;
        } catch (IOException | RuntimeException e) {
            deleteRecursively(stagingDir);
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    private byte[] tryReadReusableAsset(File previousDir, EvePackModels.PackItem item) {
        if (previousDir == null || !previousDir.exists() || item.sha256.isEmpty()) {
            return null;
        }
        File candidate = assetFileInDir(previousDir, item);
        if (!candidate.exists() || !candidate.isFile()) {
            return null;
        }
        try {
            byte[] bytes = readAllBytes(candidate);
            if (verifyAssetIntegrity(bytes, item.sha256)) {
                return bytes;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    public boolean removePack(String packId) {
        if (packId == null || packId.trim().isEmpty()) {
            return false;
        }
        synchronized (lock) {
            Map<String, EvePackModels.InstalledPackRecord> index = readIndexLocked();
            boolean existed = index.remove(packId) != null;
            writeIndexLocked(index);
            File dir = packBaseDir(packId);
            if (dir.exists()) {
                deleteRecursively(dir);
            }
            return existed;
        }
    }

    public List<EvePackModels.InstalledPackRecord> getInstalledPacks() {
        synchronized (lock) {
            List<EvePackModels.InstalledPackRecord> list = new ArrayList<>(readIndexLocked().values());
            Collections.sort(list, Comparator.comparingLong((EvePackModels.InstalledPackRecord r) -> r.installedAt).reversed());
            return list;
        }
    }

    public EvePackModels.InstalledPackRecord getInstalledPack(String packId) {
        if (packId == null || packId.isEmpty()) {
            return null;
        }
        synchronized (lock) {
            return readIndexLocked().get(packId);
        }
    }

    public boolean isPackInstalled(String packId) {
        return getInstalledPack(packId) != null;
    }

    public boolean needsUpdate(EvePackModels.PackSummary summary) {
        if (summary == null) {
            return false;
        }
        EvePackModels.InstalledPackRecord installed = getInstalledPack(summary.id);
        return installed != null && summary.version > installed.version;
    }

    public File getLocalAssetFile(String packId, EvePackModels.PackItem item) {
        if (item == null) {
            return null;
        }
        if (packId != null && !packId.isEmpty()) {
            EvePackModels.InstalledPackRecord rec = getInstalledPack(packId);
            if (rec != null) {
                File dir = versionDir(packId, rec.version);
                File candidate = assetFileInDir(dir, item);
                if (candidate.exists() && candidate.length() > 0) {
                    return candidate;
                }
            }
        }
        File preview = getPreviewCacheFile(item);
        if (preview != null && preview.exists() && preview.length() > 0) {
            return preview;
        }
        return null;
    }

    public long getTotalCacheBytes() {
        synchronized (lock) {
            return computeDirBytes(rootDir);
        }
    }

    public void clearAllCache() {
        synchronized (lock) {
            deleteRecursively(packsDir);
            deleteRecursively(previewsDir);
            File idx = new File(rootDir, INDEX_FILE_NAME);
            if (idx.exists()) idx.delete();
            ensureDirectories();
        }
    }

    private Map<String, EvePackModels.InstalledPackRecord> readIndexLocked() {
        Map<String, EvePackModels.InstalledPackRecord> result = new LinkedHashMap<>();
        File indexFile = new File(rootDir, INDEX_FILE_NAME);
        if (!indexFile.exists()) {
            return result;
        }
        try {
            String raw = new String(readAllBytes(indexFile), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                EvePackModels.InstalledPackRecord rec = EvePackModels.InstalledPackRecord.fromJson(arr.optJSONObject(i));
                if (rec != null && versionDir(rec.packId, rec.version).exists()) {
                    result.put(rec.packId, rec);
                }
            }
        } catch (IOException | JSONException ignored) {
        }
        return result;
    }

    private void writeIndexLocked(Map<String, EvePackModels.InstalledPackRecord> index) {
        JSONArray arr = new JSONArray();
        for (EvePackModels.InstalledPackRecord rec : index.values()) {
            arr.put(rec.toJson());
        }
        try {
            writeBytesAtomic(new File(rootDir, INDEX_FILE_NAME), arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static void writeBytesAtomic(File target, byte[] bytes) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(parent, target.getName() + ".tmp");
        writeBytesDirect(tmp, bytes);
        if (target.exists() && !target.delete()) {
            // Best-effort overwrite fallback
        }
        if (!tmp.renameTo(target)) {
            writeBytesDirect(target, bytes);
            tmp.delete();
        }
    }

    private static void writeBytesDirect(File file, byte[] bytes) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bytes);
            fos.getFD().sync();
        }
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static long computeDirBytes(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        if (dir.isFile()) return dir.length();
        long sum = 0L;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                sum += computeDirBytes(f);
            }
        }
        return sum;
    }

    private static void deleteRecursively(File target) {
        if (target == null || !target.exists()) return;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        target.delete();
    }
}
