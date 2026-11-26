package org.telegram.ui.eve.media;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.eve.auth.EveBackendClient;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Coordinates cloud pack catalogue browsing, offline manifest download + SHA-256 verification,
 * preview caching, and admin emoji/sticker pack management against the Cloudflare Worker API.
 * Works completely independently of Telegram MTProto login.
 */
public final class EvePackRepository {

    public interface Callback<T> {
        void onSuccess(T data);
        void onError(String message, int httpStatus, String serverCode);
    }

    public interface InstallProgressCallback {
        void onProgress(int completed, int total, EvePackModels.PackItem item);
        void onCompleted(EvePackModels.InstalledPackRecord record);
        void onError(String message, int httpStatus, String serverCode);
    }

    public interface TelegramImportProgressCallback {
        void onBatch(int batchNumber, EvePackModels.AdminImportResult cumulative, boolean finished);
        void onError(String message, int httpStatus);
    }

    private static volatile EvePackRepository instance;

    private final EveBackendClient client;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static EvePackRepository getInstance() {
        EvePackRepository current = instance;
        if (current == null) {
            synchronized (EvePackRepository.class) {
                current = instance;
                if (current == null) {
                    current = new EvePackRepository(new EveBackendClient());
                    instance = current;
                }
            }
        }
        return current;
    }

    public EvePackRepository(EveBackendClient client) {
        this.client = client != null ? client : new EveBackendClient();
    }

    public void loadPacksAsync(
            String type,
            String query,
            String category,
            boolean featuredOnly,
            Callback<List<EvePackModels.PackSummary>> callback) {
        withOptionalToken(token -> {
            try {
                StringBuilder path = new StringBuilder("/api/emoji/packs?limit=60");
                if (type != null && !type.isEmpty()) {
                    path.append("&type=").append(encode(type));
                }
                if (query != null && !query.trim().isEmpty()) {
                    path.append("&q=").append(encode(query.trim()));
                }
                if (category != null && !category.trim().isEmpty() && !"All".equalsIgnoreCase(category.trim())) {
                    path.append("&category=").append(encode(category.trim()));
                }
                if (featuredOnly) {
                    path.append("&featured=1");
                }
                JSONObject root = client.getOptionalJsonRoot(path.toString(), token);
                JSONArray arr = root.optJSONArray("data");
                postSuccess(callback, EvePackModels.PackSummary.parseList(arr));
            } catch (EveBackendClient.BackendException e) {
                postError(callback, e.getMessage(), e.httpStatus, e.serverCode);
            } catch (Throwable t) {
                postError(callback, t.getMessage(), 0, "");
            }
        });
    }

    public void loadPackDetailAsync(String packId, Callback<EvePackModels.PackSummary> callback) {
        withOptionalToken(token -> {
            try {
                JSONObject root = client.getOptionalJsonRoot("/api/emoji/packs/" + encode(packId), token);
                postSuccess(callback, EvePackModels.PackSummary.fromJson(root.optJSONObject("data")));
            } catch (EveBackendClient.BackendException e) {
                postError(callback, e.getMessage(), e.httpStatus, e.serverCode);
            } catch (Throwable t) {
                postError(callback, t.getMessage(), 0, "");
            }
        });
    }

    public void installOrUpdatePackAsync(Context context, String packId, InstallProgressCallback callback) {
        withOptionalToken(token -> {
            try {
                JSONObject root = client.getOptionalJsonRoot("/api/emoji/packs/" + encode(packId) + "/manifest", token);
                EvePackModels.PackManifest manifest = EvePackModels.PackManifest.fromJson(root.optJSONObject("data"));
                if (manifest.packId.isEmpty() || manifest.items.isEmpty()) {
                    mainHandler.post(() -> callback.onError("Pack has no downloadable items yet.", 404, "EMPTY_PACK"));
                    return;
                }
                EvePackCacheManager cache = EvePackCacheManager.getInstance(context);
                EvePackModels.InstalledPackRecord installed = cache.installPack(
                        manifest,
                        item -> {
                            String url = item.preferredDownloadUrl();
                            if (url.isEmpty()) {
                                throw new IOException("Missing asset URL for " + item.id);
                            }
                            return EveBackendClient.executeBinaryGet(url, token);
                        },
                        (completed, total, item) -> mainHandler.post(() -> callback.onProgress(completed, total, item))
                );
                mainHandler.post(() -> callback.onCompleted(installed));
            } catch (EveBackendClient.BackendException e) {
                mainHandler.post(() -> callback.onError(e.getMessage(), e.httpStatus, e.serverCode));
            } catch (Throwable t) {
                String msg = t.getMessage() != null ? t.getMessage() : "Pack download failed.";
                mainHandler.post(() -> callback.onError(msg, 0, "INSTALL_FAILED"));
            }
        });
    }

    public void loadOrFetchPreviewFileAsync(
            Context context,
            EvePackModels.PackItem item,
            Callback<File> callback) {
        if (item == null || context == null) {
            postError(callback, "Invalid pack item", 400, "");
            return;
        }
        EvePackCacheManager cache = EvePackCacheManager.getInstance(context);
        File existing = cache.getLocalAssetFile(item.packId, item);
        if (existing != null && existing.exists() && existing.length() > 0) {
            postSuccess(callback, existing);
            return;
        }
        String url = item.preferredDownloadUrl();
        if (url.isEmpty() || url.startsWith("res:raw/")) {
            postError(callback, "No remote asset URL", 404, "");
            return;
        }
        withOptionalToken(token -> {
            try {
                byte[] bytes = EveBackendClient.executeBinaryGet(url, token);
                File cached = cache.writePreviewFile(item, bytes);
                postSuccess(callback, cached);
            } catch (Throwable t) {
                postError(callback, t.getMessage(), 0, "PREVIEW_FAILED");
            }
        });
    }

    public void loadAdminPacksAsync(Callback<EvePackModels.AdminPackCatalog> callback) {
        withRequiredAdminToken(callback, token -> {
            JSONObject root = client.getJsonRoot("/api/admin/emoji/packs", token);
            postSuccess(callback, EvePackModels.AdminPackCatalog.fromJson(root.optJSONObject("data")));
        });
    }

    public void loadAdminPackDetailAsync(String packId, Callback<EvePackModels.PackSummary> callback) {
        withRequiredAdminToken(callback, token -> {
            JSONObject root = client.getJsonRoot("/api/admin/emoji/packs/" + encode(packId), token);
            postSuccess(callback, EvePackModels.PackSummary.fromJson(root.optJSONObject("data")));
        });
    }

    public void createAdminPackAsync(JSONObject payload, Callback<EvePackModels.AdminPackSummary> callback) {
        withRequiredAdminToken(callback, token -> {
            JSONObject root = client.postJsonRoot("/api/admin/emoji/packs", token, payload);
            postSuccess(callback, EvePackModels.AdminPackSummary.fromJson(root.optJSONObject("data")));
        });
    }

    public void updateAdminPackAsync(String packId, JSONObject payload, Callback<Boolean> callback) {
        withRequiredAdminToken(callback, token -> {
            client.putJsonRoot("/api/admin/emoji/packs/" + encode(packId), token, payload);
            postSuccess(callback, Boolean.TRUE);
        });
    }

    public void publishAdminPackAsync(String packId, String publicationStatus, Callback<Boolean> callback) {
        withRequiredAdminToken(callback, token -> {
            JSONObject payload = new JSONObject();
            payload.put("publicationStatus", publicationStatus);
            client.postJsonRoot("/api/admin/emoji/packs/" + encode(packId) + "/publish", token, payload);
            postSuccess(callback, Boolean.TRUE);
        });
    }

    public void toggleAdminPackStatusAsync(String packId, boolean enabled, Callback<Boolean> callback) {
        withRequiredAdminToken(callback, token -> {
            JSONObject payload = new JSONObject();
            payload.put("enabled", enabled);
            client.putJsonRoot("/api/admin/emoji/packs/" + encode(packId) + "/status", token, payload);
            postSuccess(callback, Boolean.TRUE);
        });
    }

    public void toggleGlobalTelegramStatusAsync(boolean enabled, Callback<Boolean> callback) {
        withRequiredAdminToken(callback, token -> {
            JSONObject payload = new JSONObject();
            payload.put("enabled", enabled);
            client.putJsonRoot("/api/admin/emoji/global-telegram-status", token, payload);
            postSuccess(callback, Boolean.TRUE);
        });
    }

    public void deleteAdminPackAsync(String packId, Callback<Boolean> callback) {
        withRequiredAdminToken(callback, token -> {
            client.deleteJsonRoot("/api/admin/emoji/packs/" + encode(packId), token);
            postSuccess(callback, Boolean.TRUE);
        });
    }

    public void importNotoPresetAsync(String presetCategory, Callback<String> callback) {
        withRequiredAdminToken(callback, token -> {
            JSONObject payload = new JSONObject();
            payload.put("category", presetCategory != null ? presetCategory : "popular");
            JSONObject root = client.postJsonRoot("/api/admin/emoji/import/noto", token, payload);
            JSONObject data = root.optJSONObject("data");
            int count = data != null ? data.optInt("importedCount", 0) : 0;
            postSuccess(callback, "Imported " + count + " Google Noto animated emoji.");
        });
    }

    public void uploadCustomAssetAsync(
            String packId,
            String altEmoji,
            String contentType,
            byte[] rawBytes,
            Callback<EvePackModels.PackItem> callback) {
        withRequiredAdminToken(callback, token -> {
            String path = "/api/admin/emoji/upload?packId=" + encode(packId)
                    + "&altEmoji=" + encode(altEmoji != null && !altEmoji.isEmpty() ? altEmoji : "✨");
            JSONObject root = client.postBinaryRoot(path, token, contentType, rawBytes);
            postSuccess(callback, EvePackModels.PackItem.fromJson(root.optJSONObject("data"), packId));
        });
    }

    public void importTelegramPackChunkedAsync(
            String packNameOrUrl,
            String packTypeOverride,
            String category,
            String accessTier,
            String rightsBasis,
            String licenseEvidence,
            int batchSize,
            TelegramImportProgressCallback callback) {
        getFreshIdToken(false, new TokenConsumer() {
            @Override
            public void onToken(String token) {
                if (token == null || token.trim().isEmpty()) {
                    mainHandler.post(() -> callback.onError("Admin Google sign-in required.", 401));
                    return;
                }
                executor.execute(() -> {
                    int offset = 0;
                    int limit = Math.max(1, Math.min(10, batchSize));
                    int batchNum = 0;
                    int cumulativeImported = 0;
                    int cumulativeFailed = 0;
                    List<String> cumulativeErrors = new ArrayList<>();
                    EvePackModels.AdminImportResult lastResult = null;
                    try {
                        while (true) {
                            batchNum++;
                            JSONObject body = new JSONObject();
                            body.put("packName", packNameOrUrl);
                            if (packTypeOverride != null && !packTypeOverride.isEmpty()) {
                                body.put("packType", packTypeOverride);
                            }
                            body.put("category", category != null && !category.isEmpty() ? category : "General");
                            body.put("accessTier", accessTier != null && !accessTier.isEmpty() ? accessTier : "free");
                            body.put("rightsBasis", rightsBasis != null ? rightsBasis : "");
                            body.put("licenseEvidence", licenseEvidence != null ? licenseEvidence : "");
                            body.put("offset", offset);
                            body.put("limit", limit);

                            JSONObject root = client.postJsonRoot("/api/admin/emoji/import/telegram", token, body);
                            EvePackModels.AdminImportResult res = EvePackModels.AdminImportResult.fromJson(root.optJSONObject("data"));
                            lastResult = res;
                            cumulativeImported += res.importedCount;
                            cumulativeFailed += res.failedCount;
                            cumulativeErrors.addAll(res.errors);

                            boolean finished = res.redistributionBlocked || res.nextOffset == null || batchNum >= 50;
                            EvePackModels.AdminImportResult snapshot = new EvePackModels.AdminImportResult(
                                    res.packId, res.packName, res.packTitle, res.packType, res.totalStickers,
                                    cumulativeImported, cumulativeFailed, res.redistributionBlocked,
                                    res.rightsStatus, res.externalReference, res.importStatus, res.nextOffset, cumulativeErrors
                            );
                            final int currentBatch = batchNum;
                            mainHandler.post(() -> callback.onBatch(currentBatch, snapshot, finished));
                            if (finished) {
                                break;
                            }
                            offset = res.nextOffset;
                        }
                    } catch (EveBackendClient.BackendException e) {
                        mainHandler.post(() -> callback.onError(e.getMessage(), e.httpStatus));
                    } catch (Throwable t) {
                        mainHandler.post(() -> callback.onError(t.getMessage() != null ? t.getMessage() : "Telegram import failed.", 0));
                    }
                });
            }
        });
    }

    private interface TokenTask {
        void run(String token) throws Exception;
    }

    private interface TokenConsumer {
        void onToken(String token);
    }

    private void withOptionalToken(TokenConsumer consumer) {
        getFreshIdToken(true, token -> executor.execute(() -> consumer.onToken(token)));
    }

    private <T> void withRequiredAdminToken(Callback<T> callback, TokenTask task) {
        getFreshIdToken(false, token -> {
            if (token == null || token.trim().isEmpty()) {
                postError(callback, "Sign in with an authorized EVE admin Google account first.", 401, "UNAUTHORIZED");
                return;
            }
            executor.execute(() -> {
                try {
                    task.run(token);
                } catch (EveBackendClient.BackendException e) {
                    postError(callback, e.getMessage(), e.httpStatus, e.serverCode);
                } catch (Throwable t) {
                    postError(callback, t.getMessage(), 0, "");
                }
            });
        });
    }

    private void getFreshIdToken(boolean optional, TokenConsumer consumer) {
        try {
            if (FirebaseApp.getApps(org.telegram.messenger.ApplicationLoader.applicationContext).isEmpty()) {
                consumer.onToken(optional ? "" : null);
                return;
            }
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null || user.isAnonymous()) {
                consumer.onToken(optional ? "" : null);
                return;
            }
            user.getIdToken(false)
                    .addOnSuccessListener(result -> consumer.onToken(result != null && result.getToken() != null ? result.getToken() : (optional ? "" : null)))
                    .addOnFailureListener(e -> consumer.onToken(optional ? "" : null));
        } catch (Throwable ignored) {
            consumer.onToken(optional ? "" : null);
        }
    }

    private <T> void postSuccess(Callback<T> callback, T data) {
        mainHandler.post(() -> callback.onSuccess(data));
    }

    private <T> void postError(Callback<T> callback, String message, int httpStatus, String serverCode) {
        mainHandler.post(() -> callback.onError(message != null ? message : "Request failed.", httpStatus, serverCode != null ? serverCode : ""));
    }

    private static String encode(String value) {
        if (value == null) return "";
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
