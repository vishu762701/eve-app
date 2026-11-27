package org.telegram.ui.eve.media;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.ui.eve.auth.EveBackendClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Immutable data contracts and JSON parsers for EVE's cloud Emoji & Sticker Pack Library.
 */
public final class EvePackModels {

    private EvePackModels() {
    }

    public static String resolveUrl(String rawUrl) {
        if (rawUrl == null) {
            return "";
        }
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("res:raw/") || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.startsWith("/")) {
            return EveBackendClient.DEFAULT_WORKER_BASE_URL + trimmed;
        }
        return EveBackendClient.DEFAULT_WORKER_BASE_URL + "/" + trimmed;
    }

    private static String clean(JSONObject obj, String key, String fallback) {
        if (obj == null || obj.isNull(key)) {
            return fallback;
        }
        String v = obj.optString(key, fallback);
        return (v == null || "null".equalsIgnoreCase(v.trim())) ? fallback : v;
    }

    public static final class PackItem {
        public final String id;
        public final String packId;
        public final int index;
        public final String altEmoji;
        public final String kind;
        public final String fileFormat;
        public final String mimeType;
        public final boolean isAnimated;
        public final boolean isVideo;
        public final long byteSize;
        public final String sha256;
        public final String assetUrl;
        public final String previewUrl;
        public final boolean enabled;
        public final String externalReference;

        public PackItem(
                String id,
                String packId,
                int index,
                String altEmoji,
                String kind,
                String fileFormat,
                String mimeType,
                boolean isAnimated,
                boolean isVideo,
                long byteSize,
                String sha256,
                String assetUrl,
                String previewUrl,
                boolean enabled,
                String externalReference) {
            this.id = id != null ? id : "";
            this.packId = packId != null ? packId : "";
            this.index = index;
            this.altEmoji = altEmoji != null && !altEmoji.isEmpty() ? altEmoji : "✨";
            this.kind = kind != null ? kind : "lottie";
            this.fileFormat = fileFormat != null ? fileFormat : ("video".equals(this.kind) ? "webm" : "static".equals(this.kind) ? "webp" : "lottie");
            this.mimeType = mimeType != null ? mimeType : "application/json";
            this.isAnimated = isAnimated;
            this.isVideo = isVideo;
            this.byteSize = Math.max(0L, byteSize);
            this.sha256 = sha256 != null ? sha256 : "";
            this.assetUrl = resolveUrl(assetUrl);
            this.previewUrl = resolveUrl(previewUrl);
            this.enabled = enabled;
            this.externalReference = externalReference != null ? externalReference : "";
        }

        public String preferredDownloadUrl() {
            return !assetUrl.isEmpty() ? assetUrl : previewUrl;
        }

        public String fileExtension() {
            String fmt = fileFormat.toLowerCase(Locale.US);
            if ("lottie".equals(fmt) || "json".equals(fmt)) {
                return "json";
            }
            if ("tgs".equals(fmt) || "webm".equals(fmt) || "webp".equals(fmt) || "png".equals(fmt)) {
                return fmt;
            }
            if (isVideo || "video".equals(kind)) {
                return "webm";
            }
            if ("static".equals(kind)) {
                return "webp";
            }
            return "json";
        }

        public static PackItem fromJson(JSONObject obj, String defaultPackId) {
            if (obj == null) {
                return new PackItem("", defaultPackId, 0, "✨", "lottie", "lottie", "application/json", true, false, 0L, "", "", "", true, "");
            }
            String rawAsset = clean(obj, "assetUrl", "");
            if (rawAsset.isEmpty()) {
                rawAsset = clean(obj, "url", "");
            }
            String rawPreview = clean(obj, "previewUrl", "");
            if (rawPreview.isEmpty()) {
                rawPreview = rawAsset;
            }
            String kindVal = clean(obj, "kind", "lottie");
            return new PackItem(
                    clean(obj, "id", ""),
                    clean(obj, "packId", defaultPackId != null ? defaultPackId : ""),
                    obj.optInt("index", 0),
                    clean(obj, "altEmoji", "✨"),
                    kindVal,
                    clean(obj, "fileFormat", "lottie"),
                    clean(obj, "mimeType", "application/json"),
                    obj.optBoolean("isAnimated", "lottie".equals(kindVal) || "video".equals(kindVal)),
                    obj.optBoolean("isVideo", "video".equals(kindVal)),
                    obj.optLong("byteSize", 0L),
                    clean(obj, "sha256", ""),
                    rawAsset,
                    rawPreview,
                    obj.optBoolean("enabled", true),
                    clean(obj, "externalReference", "")
            );
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("packId", packId);
                obj.put("index", index);
                obj.put("altEmoji", altEmoji);
                obj.put("kind", kind);
                obj.put("fileFormat", fileFormat);
                obj.put("mimeType", mimeType);
                obj.put("isAnimated", isAnimated);
                obj.put("isVideo", isVideo);
                obj.put("byteSize", byteSize);
                obj.put("sha256", sha256);
                obj.put("assetUrl", assetUrl);
                obj.put("previewUrl", previewUrl);
                obj.put("enabled", enabled);
                obj.put("externalReference", externalReference);
            } catch (JSONException ignored) {
            }
            return obj;
        }
    }

    public static final class PackSummary {
        public final String id;
        public final String name;
        public final String title;
        public final String packType;
        public final String description;
        public final String category;
        public final boolean featured;
        public final int itemCount;
        public final int version;
        public final String accessTier;
        public final boolean isLockedForUser;
        public final String rightsStatus;
        public final String creatorAttribution;
        public final String licenseAttribution;
        public final String source;
        public final String thumbnailUrl;
        public final List<PackItem> previews;
        public final List<PackItem> items;
        public final long createdAt;
        public final long updatedAt;

        public PackSummary(
                String id,
                String name,
                String title,
                String packType,
                String description,
                String category,
                boolean featured,
                int itemCount,
                int version,
                String accessTier,
                boolean isLockedForUser,
                String rightsStatus,
                String creatorAttribution,
                String licenseAttribution,
                String source,
                String thumbnailUrl,
                List<PackItem> previews,
                List<PackItem> items,
                long createdAt,
                long updatedAt) {
            this.id = id != null ? id : "";
            this.name = name != null ? name : "";
            this.title = title != null && !title.isEmpty() ? title : this.name;
            this.packType = "sticker".equalsIgnoreCase(packType) ? "sticker" : "emoji";
            this.description = description != null ? description : "";
            this.category = category != null && !category.isEmpty() ? category : "General";
            this.featured = featured;
            this.itemCount = Math.max(0, itemCount);
            this.version = Math.max(1, version);
            this.accessTier = "premium".equalsIgnoreCase(accessTier) ? "premium" : "free";
            this.isLockedForUser = isLockedForUser;
            this.rightsStatus = rightsStatus != null ? rightsStatus : "approved";
            this.creatorAttribution = creatorAttribution != null ? creatorAttribution : "";
            this.licenseAttribution = licenseAttribution != null ? licenseAttribution : "";
            this.source = source != null ? source : "";
            this.thumbnailUrl = resolveUrl(thumbnailUrl);
            this.previews = previews != null ? Collections.unmodifiableList(new ArrayList<>(previews)) : Collections.emptyList();
            this.items = items != null ? Collections.unmodifiableList(new ArrayList<>(items)) : Collections.emptyList();
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public boolean isStickerPack() {
            return "sticker".equals(packType);
        }

        public boolean isEmojiPack() {
            return "emoji".equals(packType);
        }

        public boolean isPremium() {
            return "premium".equals(accessTier);
        }

        public String countLabel() {
            int n = itemCount > 0 ? itemCount : Math.max(items.size(), previews.size());
            if (isStickerPack()) {
                return n == 1 ? "1 sticker" : n + " stickers";
            }
            return n == 1 ? "1 emoji" : n + " emojis";
        }

        public List<PackItem> displayPreviewItems() {
            return !previews.isEmpty() ? previews : items;
        }

        public static PackSummary fromJson(JSONObject obj) {
            if (obj == null) {
                return new PackSummary("", "", "", "emoji", "", "General", false, 0, 1, "free", false, "approved", "", "", "", "", Collections.emptyList(), Collections.emptyList(), 0L, 0L);
            }
            String id = clean(obj, "id", "");
            List<PackItem> previewList = new ArrayList<>();
            JSONArray pArr = obj.optJSONArray("previews");
            if (pArr != null) {
                for (int i = 0; i < pArr.length(); i++) {
                    JSONObject itemObj = pArr.optJSONObject(i);
                    if (itemObj != null) {
                        previewList.add(PackItem.fromJson(itemObj, id));
                    }
                }
            }
            List<PackItem> itemList = new ArrayList<>();
            JSONArray iArr = obj.optJSONArray("items");
            if (iArr != null) {
                for (int i = 0; i < iArr.length(); i++) {
                    JSONObject itemObj = iArr.optJSONObject(i);
                    if (itemObj != null) {
                        itemList.add(PackItem.fromJson(itemObj, id));
                    }
                }
            }
            return new PackSummary(
                    id,
                    clean(obj, "name", ""),
                    clean(obj, "title", ""),
                    clean(obj, "packType", "emoji"),
                    clean(obj, "description", ""),
                    clean(obj, "category", "General"),
                    obj.optBoolean("featured", false),
                    obj.optInt("itemCount", !itemList.isEmpty() ? itemList.size() : previewList.size()),
                    obj.optInt("version", 1),
                    clean(obj, "accessTier", "free"),
                    obj.optBoolean("isLockedForUser", false),
                    clean(obj, "rightsStatus", "approved"),
                    clean(obj, "creatorAttribution", ""),
                    clean(obj, "licenseAttribution", ""),
                    clean(obj, "source", ""),
                    clean(obj, "thumbnailUrl", ""),
                    previewList,
                    itemList,
                    obj.optLong("createdAt", 0L),
                    obj.optLong("updatedAt", 0L)
            );
        }

        public static List<PackSummary> parseList(JSONArray arr) {
            if (arr == null) {
                return Collections.emptyList();
            }
            List<PackSummary> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    list.add(fromJson(obj));
                }
            }
            return list;
        }
    }

    public static final class PackManifest {
        public final String packId;
        public final String name;
        public final String title;
        public final String packType;
        public final int version;
        public final int itemCount;
        public final long totalBytes;
        public final String manifestSha256;
        public final List<PackItem> items;

        public PackManifest(
                String packId,
                String name,
                String title,
                String packType,
                int version,
                int itemCount,
                long totalBytes,
                String manifestSha256,
                List<PackItem> items) {
            this.packId = packId != null ? packId : "";
            this.name = name != null ? name : "";
            this.title = title != null && !title.isEmpty() ? title : this.name;
            this.packType = "sticker".equalsIgnoreCase(packType) ? "sticker" : "emoji";
            this.version = Math.max(1, version);
            this.itemCount = Math.max(0, itemCount);
            this.totalBytes = Math.max(0L, totalBytes);
            this.manifestSha256 = manifestSha256 != null ? manifestSha256 : "";
            this.items = items != null ? Collections.unmodifiableList(new ArrayList<>(items)) : Collections.emptyList();
        }

        public static PackManifest fromJson(JSONObject obj) {
            if (obj == null) {
                return new PackManifest("", "", "", "emoji", 1, 0, 0L, "", Collections.emptyList());
            }
            String packId = clean(obj, "packId", "");
            List<PackItem> list = new ArrayList<>();
            JSONArray arr = obj.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject itemObj = arr.optJSONObject(i);
                    if (itemObj != null) {
                        list.add(PackItem.fromJson(itemObj, packId));
                    }
                }
            }
            return new PackManifest(
                    packId,
                    clean(obj, "name", ""),
                    clean(obj, "title", ""),
                    clean(obj, "packType", "emoji"),
                    obj.optInt("version", 1),
                    obj.optInt("itemCount", list.size()),
                    obj.optLong("totalBytes", 0L),
                    clean(obj, "manifestSha256", ""),
                    list
            );
        }

        public PackSummary toSummary() {
            List<PackItem> previewSlice = items.subList(0, Math.min(6, items.size()));
            return new PackSummary(packId, name, title, packType, "", "General", false,
                    itemCount, version, "free", false, "approved", "", "", "", "", previewSlice, items, 0L, 0L);
        }
    }

    public static final class InstalledPackRecord {
        public final String packId;
        public final String name;
        public final String title;
        public final String packType;
        public final int version;
        public final int itemCount;
        public final long totalBytes;
        public final String manifestSha256;
        public final long installedAt;
        public final List<PackItem> items;

        public InstalledPackRecord(
                String packId,
                String name,
                String title,
                String packType,
                int version,
                int itemCount,
                long totalBytes,
                String manifestSha256,
                long installedAt,
                List<PackItem> items) {
            this.packId = packId != null ? packId : "";
            this.name = name != null ? name : "";
            this.title = title != null ? title : "";
            this.packType = "sticker".equalsIgnoreCase(packType) ? "sticker" : "emoji";
            this.version = Math.max(1, version);
            this.itemCount = Math.max(0, itemCount);
            this.totalBytes = Math.max(0L, totalBytes);
            this.manifestSha256 = manifestSha256 != null ? manifestSha256 : "";
            this.installedAt = installedAt;
            this.items = items != null ? Collections.unmodifiableList(new ArrayList<>(items)) : Collections.emptyList();
        }

        public PackSummary toPackSummary() {
            List<PackItem> previewSlice = items.subList(0, Math.min(6, items.size()));
            return new PackSummary(packId, name, title, packType, "", "Installed", false,
                    itemCount, version, "free", false, "approved", "", "", "cached", "", previewSlice, items, installedAt, installedAt);
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("packId", packId);
                obj.put("name", name);
                obj.put("title", title);
                obj.put("packType", packType);
                obj.put("version", version);
                obj.put("itemCount", itemCount);
                obj.put("totalBytes", totalBytes);
                obj.put("manifestSha256", manifestSha256);
                obj.put("installedAt", installedAt);
                JSONArray arr = new JSONArray();
                for (PackItem item : items) {
                    arr.put(item.toJson());
                }
                obj.put("items", arr);
            } catch (JSONException ignored) {
            }
            return obj;
        }

        public static InstalledPackRecord fromJson(JSONObject obj) {
            if (obj == null) {
                return null;
            }
            String packId = clean(obj, "packId", "");
            if (packId.isEmpty()) {
                return null;
            }
            List<PackItem> list = new ArrayList<>();
            JSONArray arr = obj.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject itemObj = arr.optJSONObject(i);
                    if (itemObj != null) {
                        list.add(PackItem.fromJson(itemObj, packId));
                    }
                }
            }
            return new InstalledPackRecord(
                    packId,
                    clean(obj, "name", ""),
                    clean(obj, "title", ""),
                    clean(obj, "packType", "emoji"),
                    obj.optInt("version", 1),
                    obj.optInt("itemCount", list.size()),
                    obj.optLong("totalBytes", 0L),
                    clean(obj, "manifestSha256", ""),
                    obj.optLong("installedAt", 0L),
                    list
            );
        }
    }

    public static final class AdminPackSummary {
        public final String id;
        public final String name;
        public final String title;
        public final String packType;
        public final String description;
        public final String category;
        public final boolean featured;
        public final int version;
        public final String accessTier;
        public final String rightsStatus;
        public final String creatorAttribution;
        public final String licenseAttribution;
        public final String licenseEvidence;
        public final String publicationStatus;
        public final String importStatus;
        public final int importExpectedCount;
        public final int importFailedCount;
        public final String importErrorSummary;
        public final String thumbnailUrl;
        public final String source;
        public final boolean enabled;
        public final int itemCount;
        public final String storageBackend;
        public final long createdAt;
        public final long updatedAt;
        public final String createdBy;

        public AdminPackSummary(
                String id,
                String name,
                String title,
                String packType,
                String description,
                String category,
                boolean featured,
                int version,
                String accessTier,
                String rightsStatus,
                String creatorAttribution,
                String licenseAttribution,
                String licenseEvidence,
                String publicationStatus,
                String importStatus,
                int importExpectedCount,
                int importFailedCount,
                String importErrorSummary,
                String thumbnailUrl,
                String source,
                boolean enabled,
                int itemCount,
                String storageBackend,
                long createdAt,
                long updatedAt,
                String createdBy) {
            this.id = id != null ? id : "";
            this.name = name != null ? name : "";
            this.title = title != null && !title.isEmpty() ? title : this.name;
            this.packType = "sticker".equalsIgnoreCase(packType) ? "sticker" : "emoji";
            this.description = description != null ? description : "";
            this.category = category != null ? category : "General";
            this.featured = featured;
            this.version = Math.max(1, version);
            this.accessTier = "premium".equalsIgnoreCase(accessTier) ? "premium" : "free";
            this.rightsStatus = rightsStatus != null ? rightsStatus : "approved";
            this.creatorAttribution = creatorAttribution != null ? creatorAttribution : "";
            this.licenseAttribution = licenseAttribution != null ? licenseAttribution : "";
            this.licenseEvidence = licenseEvidence != null ? licenseEvidence : "";
            this.publicationStatus = publicationStatus != null ? publicationStatus : "published";
            this.importStatus = importStatus != null ? importStatus : "complete";
            this.importExpectedCount = importExpectedCount;
            this.importFailedCount = importFailedCount;
            this.importErrorSummary = importErrorSummary != null ? importErrorSummary : "";
            this.thumbnailUrl = resolveUrl(thumbnailUrl);
            this.source = source != null ? source : "";
            this.enabled = enabled;
            this.itemCount = itemCount;
            this.storageBackend = storageBackend != null ? storageBackend : "telegram";
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.createdBy = createdBy != null ? createdBy : "";
        }

        public static AdminPackSummary fromJson(JSONObject obj) {
            return new AdminPackSummary(
                    clean(obj, "id", ""),
                    clean(obj, "name", ""),
                    clean(obj, "title", ""),
                    clean(obj, "packType", "emoji"),
                    clean(obj, "description", ""),
                    clean(obj, "category", "General"),
                    obj.optBoolean("featured", false),
                    obj.optInt("version", 1),
                    clean(obj, "accessTier", "free"),
                    clean(obj, "rightsStatus", "approved"),
                    clean(obj, "creatorAttribution", ""),
                    clean(obj, "licenseAttribution", ""),
                    clean(obj, "licenseEvidence", ""),
                    clean(obj, "publicationStatus", "published"),
                    clean(obj, "importStatus", "complete"),
                    obj.optInt("importExpectedCount", 0),
                    obj.optInt("importFailedCount", 0),
                    clean(obj, "importErrorSummary", ""),
                    clean(obj, "thumbnailUrl", ""),
                    clean(obj, "source", ""),
                    obj.optBoolean("enabled", true),
                    obj.optInt("itemCount", 0),
                    clean(obj, "storageBackend", "telegram"),
                    obj.optLong("createdAt", 0L),
                    obj.optLong("updatedAt", 0L),
                    clean(obj, "createdBy", "")
            );
        }
    }

    public static final class AdminPackCatalog {
        public final List<AdminPackSummary> packs;
        public final boolean globalTelegramEnabled;
        public final boolean telegramBotConfigured;
        public final boolean r2Configured;

        public AdminPackCatalog(List<AdminPackSummary> packs, boolean globalTelegramEnabled, boolean telegramBotConfigured, boolean r2Configured) {
            this.packs = packs != null ? Collections.unmodifiableList(new ArrayList<>(packs)) : Collections.emptyList();
            this.globalTelegramEnabled = globalTelegramEnabled;
            this.telegramBotConfigured = telegramBotConfigured;
            this.r2Configured = r2Configured;
        }

        public static AdminPackCatalog fromJson(JSONObject data) {
            List<AdminPackSummary> list = new ArrayList<>();
            if (data != null) {
                JSONArray arr = data.optJSONArray("packs");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.optJSONObject(i);
                        if (obj != null) {
                            list.add(AdminPackSummary.fromJson(obj));
                        }
                    }
                }
            }
            return new AdminPackCatalog(
                    list,
                    data == null || data.optBoolean("globalTelegramEnabled", true),
                    data != null && data.optBoolean("telegramBotConfigured", false),
                    data != null && data.optBoolean("r2Configured", false)
            );
        }
    }

    public static final class AdminImportResult {
        public final String packId;
        public final String packName;
        public final String packTitle;
        public final String packType;
        public final int totalStickers;
        public final int importedCount;
        public final int failedCount;
        public final boolean redistributionBlocked;
        public final String rightsStatus;
        public final String externalReference;
        public final String importStatus;
        public final Integer nextOffset;
        public final List<String> errors;

        public AdminImportResult(
                String packId,
                String packName,
                String packTitle,
                String packType,
                int totalStickers,
                int importedCount,
                int failedCount,
                boolean redistributionBlocked,
                String rightsStatus,
                String externalReference,
                String importStatus,
                Integer nextOffset,
                List<String> errors) {
            this.packId = packId != null ? packId : "";
            this.packName = packName != null ? packName : "";
            this.packTitle = packTitle != null ? packTitle : "";
            this.packType = packType != null ? packType : "sticker";
            this.totalStickers = totalStickers;
            this.importedCount = importedCount;
            this.failedCount = failedCount;
            this.redistributionBlocked = redistributionBlocked;
            this.rightsStatus = rightsStatus != null ? rightsStatus : "approved";
            this.externalReference = externalReference != null ? externalReference : "";
            this.importStatus = importStatus != null ? importStatus : "complete";
            this.nextOffset = nextOffset;
            this.errors = errors != null ? Collections.unmodifiableList(new ArrayList<>(errors)) : Collections.emptyList();
        }

        public static AdminImportResult fromJson(JSONObject data) {
            List<String> errs = new ArrayList<>();
            if (data != null) {
                JSONArray arr = data.optJSONArray("errors");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String msg = arr.optString(i, "").trim();
                        if (!msg.isEmpty()) {
                            errs.add(msg);
                        }
                    }
                }
            }
            Integer nextOff = data != null && !data.isNull("nextOffset") && data.has("nextOffset") ? data.optInt("nextOffset") : null;
            return new AdminImportResult(
                    clean(data, "packId", ""),
                    clean(data, "packName", ""),
                    clean(data, "packTitle", ""),
                    clean(data, "packType", "sticker"),
                    data != null ? data.optInt("totalStickers", 0) : 0,
                    data != null ? data.optInt("importedCount", 0) : 0,
                    data != null ? data.optInt("failedCount", 0) : 0,
                    data != null && data.optBoolean("redistributionBlocked", false),
                    clean(data, "rightsStatus", "approved"),
                    clean(data, "externalReference", ""),
                    clean(data, "importStatus", "complete"),
                    nextOff,
                    errs
            );
        }
    }
}
