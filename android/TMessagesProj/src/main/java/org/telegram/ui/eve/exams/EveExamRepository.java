package org.telegram.ui.eve.exams;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.ui.eve.admin.EveAdminModels;
import org.telegram.ui.eve.auth.EveAuthManager;
import org.telegram.ui.eve.auth.EveBackendClient;
import org.telegram.ui.eve.auth.EveLocalDiskCache;
import org.telegram.ui.eve.polls.EvePollModel;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Authoritative repository loading available exams, published tests,
 * live test sessions, server grading, history, leaderboards, and broadcasts
 * from the EVE Cloudflare Worker + D1 backend.
 */
public final class EveExamRepository {

    public interface CatalogListener {
        void onExamCatalogUpdated(EveExamModels.ExamCatalogResult result);
    }

    public interface ExamTestsCallback {
        void onLoaded(List<EveExamModels.GeneratedTestSummary> tests, String errorMessage);
    }

    public interface ApiCallback<T> {
        void onComplete(T data, String errorMessage);
    }

    private interface TokenTask {
        void execute(String token) throws Exception;
    }

    private static volatile EveExamRepository instance;

    private static final String PREFS_CACHE = "eve_exam_catalog_cache";
    private static final String PREFS_SWR = "eve_student_swr_cache";
    private static final String KEY_EXAMS_JSON = "exams_json";
    private static final String KEY_TESTS_JSON = "tests_json";
    private static final String KEY_PINS_JSON = "pinned_exam_ids_json";
    private static final String KEY_CACHED_AT = "cached_at_ms";
    private static final String KEY_LAST_SEEN_ANNOUNCEMENT_MS = "last_seen_announcement_ms";

    public static final class AnnouncementChannelSummary {
        public final String channelName;
        public final String channelAvatarUrl;
        public final String channelAvatarEmoji;
        public final String latestPreview;
        public final int latestTimestampSec;
        public final long latestTimestampMs;
        public final int unreadCount;

        public AnnouncementChannelSummary(
                String channelName,
                String channelAvatarUrl,
                String latestPreview,
                int latestTimestampSec,
                long latestTimestampMs,
                int unreadCount) {
            this(channelName, channelAvatarUrl, latestPreview, latestTimestampSec, latestTimestampMs, unreadCount, "");
        }

        public AnnouncementChannelSummary(
                String channelName,
                String channelAvatarUrl,
                String latestPreview,
                int latestTimestampSec,
                long latestTimestampMs,
                int unreadCount,
                String channelAvatarEmoji) {
            this.channelName = channelName != null && !channelName.trim().isEmpty() ? channelName.trim() : "Announcements";
            this.channelAvatarUrl = channelAvatarUrl != null ? channelAvatarUrl.trim() : "";
            this.channelAvatarEmoji = channelAvatarEmoji != null ? channelAvatarEmoji.trim() : "";
            this.latestPreview = latestPreview != null ? latestPreview : "";
            this.latestTimestampSec = Math.max(0, latestTimestampSec);
            this.latestTimestampMs = Math.max(0L, latestTimestampMs);
            this.unreadCount = Math.max(0, unreadCount);
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(6);
    private final CopyOnWriteArrayList<CatalogListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, String> swrMemoryCache = new ConcurrentHashMap<>();
    private final EveBackendClient backendClient;

    private volatile EveExamModels.ExamCatalogResult currentResult =
            new EveExamModels.ExamCatalogResult(EveExamModels.LoadState.LOADING, Collections.emptyList(), "");
    private volatile List<EveExamModels.ExamSummary> allExamSummaries = Collections.emptyList();
    private volatile List<EveAdminModels.BannerItem> cachedBanners = Collections.emptyList();
    private volatile EveBackendClient.AppConfigResult cachedAppConfig;
    private volatile AnnouncementChannelSummary cachedChannelSummary;
    private volatile Set<String> pinnedExamIds = Collections.emptySet();
    private volatile long lastSuccessLoadMs;

    private EveExamRepository() {
        this.backendClient = new EveBackendClient();
        hydrateFromDiskCache();
    }

    private void putSwrJson(String key, String raw) {
        if (key == null || raw == null) return;
        swrMemoryCache.put(key, raw);
        try {
            EveLocalDiskCache.getInstance().put("student_" + key, raw);
        } catch (Throwable ignored) {
        }
    }

    private String getSwrJson(String key) {
        if (key == null) return "";
        String mem = swrMemoryCache.get(key);
        if (mem != null && !mem.isEmpty()) return mem;
        try {
            String disk = EveLocalDiskCache.getInstance().get("student_" + key);
            if (disk != null && !disk.isEmpty()) {
                swrMemoryCache.put(key, disk);
                return disk;
            }
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) return "";
            String legacy = ctx.getSharedPreferences(PREFS_SWR, Context.MODE_PRIVATE).getString(key, "");
            if (legacy != null && !legacy.isEmpty()) {
                swrMemoryCache.put(key, legacy);
                EveLocalDiskCache.getInstance().put("student_" + key, legacy);
                return legacy;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private void invalidateSwrKey(String key) {
        if (key == null) return;
        swrMemoryCache.remove(key);
        try {
            EveLocalDiskCache.getInstance().invalidate("student_" + key);
        } catch (Throwable ignored) {
        }
    }

    public void clearCacheOnSignOut() {
        swrMemoryCache.clear();
        allExamSummaries = Collections.emptyList();
        cachedBanners = Collections.emptyList();
        cachedAppConfig = null;
        cachedChannelSummary = null;
        pinnedExamIds = Collections.emptySet();
        lastSuccessLoadMs = 0L;
        currentResult = new EveExamModels.ExamCatalogResult(EveExamModels.LoadState.LOADING, Collections.emptyList(), "");
        try {
            EveLocalDiskCache.getInstance().clearAll();
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                ctx.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE).edit().clear().apply();
                ctx.getSharedPreferences(PREFS_SWR, Context.MODE_PRIVATE).edit().clear().apply();
            }
        } catch (Throwable ignored) {
        }
    }

    private void hydrateFromDiskCache() {
        try {
            String bannersRaw = getSwrJson("banners_home");
            if (!bannersRaw.isEmpty()) {
                List<EveAdminModels.BannerItem> bList = EveAdminModels.BannerItem.parseList(new JSONArray(bannersRaw));
                List<EveAdminModels.BannerItem> activeBanners = new ArrayList<>();
                for (EveAdminModels.BannerItem b : bList) {
                    if (b != null && b.active) activeBanners.add(b);
                }
                if (!activeBanners.isEmpty()) {
                    cachedBanners = Collections.unmodifiableList(activeBanners);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String cfgRaw = getSwrJson("app_config");
            if (!cfgRaw.isEmpty()) {
                cachedAppConfig = EveBackendClient.AppConfigResult.fromJson(new JSONObject(cfgRaw));
            }
        } catch (Throwable ignored) {
        }
        try {
            String catalogRaw = EveLocalDiskCache.getInstance().get("student_catalog_v1");
            if (!catalogRaw.isEmpty()) {
                JSONObject cat = new JSONObject(catalogRaw);
                JSONArray pinsArr = cat.optJSONArray("pins");
                if (pinsArr != null) {
                    pinnedExamIds = parsePinnedIds(pinsArr);
                }
                JSONArray examsArr = cat.optJSONArray("exams");
                JSONArray testsArr = cat.optJSONArray("tests");
                if (examsArr != null) {
                    List<EveExamModels.ExamSummary> parsed = parseExamCatalog(examsArr, testsArr, pinnedExamIds);
                    allExamSummaries = parseAllExamsCatalog(examsArr, testsArr, pinnedExamIds);
                    if (!parsed.isEmpty()) {
                        currentResult = new EveExamModels.ExamCatalogResult(EveExamModels.LoadState.LOADED, parsed, "");
                    }
                    return;
                }
            }
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) return;
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE);
            String pinsRaw = prefs.getString(KEY_PINS_JSON, "[]");
            if (pinsRaw != null && !pinsRaw.trim().isEmpty()) {
                pinnedExamIds = parsePinnedIds(new JSONArray(pinsRaw));
            }
            String examsRaw = prefs.getString(KEY_EXAMS_JSON, "");
            if (examsRaw == null || examsRaw.trim().isEmpty()) return;
            JSONArray examsArr = new JSONArray(examsRaw);
            String testsRaw = prefs.getString(KEY_TESTS_JSON, "[]");
            JSONArray testsArr = (testsRaw != null && !testsRaw.trim().isEmpty()) ? new JSONArray(testsRaw) : new JSONArray();
            List<EveExamModels.ExamSummary> parsed = parseExamCatalog(examsArr, testsArr, pinnedExamIds);
            allExamSummaries = parseAllExamsCatalog(examsArr, testsArr, pinnedExamIds);
            if (!parsed.isEmpty()) {
                currentResult = new EveExamModels.ExamCatalogResult(EveExamModels.LoadState.LOADED, parsed, "");
            }
        } catch (Throwable ignored) {
        }
    }

    private void saveDiskCache(JSONArray examsJson, JSONArray testsJson, Set<String> pins) {
        try {
            if (examsJson != null) {
                JSONObject cat = new JSONObject();
                cat.put("exams", examsJson);
                cat.put("tests", testsJson != null ? testsJson : new JSONArray());
                cat.put("pins", new JSONArray(pins != null ? pins : Collections.emptySet()));
                EveLocalDiskCache.getInstance().put("student_catalog_v1", cat.toString());
            }
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null || examsJson == null) return;
            SharedPreferences.Editor editor = ctx.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_EXAMS_JSON, examsJson.toString())
                    .putString(KEY_TESTS_JSON, testsJson != null ? testsJson.toString() : "[]")
                    .putLong(KEY_CACHED_AT, System.currentTimeMillis());
            if (pins != null) {
                editor.putString(KEY_PINS_JSON, new JSONArray(pins).toString());
            }
            editor.apply();
        } catch (Throwable ignored) {
        }
    }

    private void savePinnedIdsCache(Set<String> pins) {
        try {
            String catalogRaw = EveLocalDiskCache.getInstance().get("student_catalog_v1");
            if (!catalogRaw.isEmpty()) {
                JSONObject cat = new JSONObject(catalogRaw);
                cat.put("pins", new JSONArray(pins != null ? pins : Collections.emptySet()));
                EveLocalDiskCache.getInstance().put("student_catalog_v1", cat.toString());
            }
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) return;
            ctx.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PINS_JSON, new JSONArray(pins != null ? pins : Collections.emptySet()).toString())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    public static EveExamRepository getInstance() {
        EveExamRepository local = instance;
        if (local == null) {
            synchronized (EveExamRepository.class) {
                local = instance;
                if (local == null) {
                    local = new EveExamRepository();
                    instance = local;
                }
            }
        }
        return local;
    }

    public EveExamModels.ExamCatalogResult getCurrentResult() {
        return currentResult;
    }

    public void addListener(CatalogListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(CatalogListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public void loadExams(boolean forceRefresh) {
        if (!forceRefresh
                && currentResult.state == EveExamModels.LoadState.LOADED
                && (System.currentTimeMillis() - lastSuccessLoadMs) < 30_000L) {
            notifyListeners(currentResult);
            return;
        }
        if (!loading.compareAndSet(false, true)) {
            return;
        }
        if (currentResult.exams.isEmpty()) {
            publishResult(new EveExamModels.ExamCatalogResult(EveExamModels.LoadState.LOADING, currentResult.exams, ""));
        }

        EveAuthManager authManager = EveAuthManager.getInstance();
        authManager.ensureInitialized();
        FirebaseUser user = authManager.getCurrentUser();
        if (user != null) {
            fetchWithUser(user);
            return;
        }

        // Student catalog reads require a verified Firebase token on the Worker.
        // Try anonymous Firebase session if enabled; otherwise surface AUTH_REQUIRED honestly.
        try {
            FirebaseAuth auth = FirebaseAuth.getInstance();
            auth.signInAnonymously()
                    .addOnSuccessListener(authResult -> {
                        FirebaseUser anon = authResult.getUser();
                        if (anon == null) {
                            loading.set(false);
                            publishResult(new EveExamModels.ExamCatalogResult(
                                    EveExamModels.LoadState.AUTH_REQUIRED,
                                    Collections.emptyList(),
                                    "Sign in to EVE Cloud to load available exams."));
                        } else {
                            fetchWithUser(anon);
                        }
                    })
                    .addOnFailureListener(e -> {
                        loading.set(false);
                        FileLog.d("EveExamRepository: Firebase anonymous auth unavailable: " + e.getMessage());
                        publishResult(new EveExamModels.ExamCatalogResult(
                                EveExamModels.LoadState.AUTH_REQUIRED,
                                Collections.emptyList(),
                                "Connect EVE Cloud account to load available exams."));
                    });
        } catch (Throwable t) {
            loading.set(false);
            publishResult(new EveExamModels.ExamCatalogResult(
                    EveExamModels.LoadState.AUTH_REQUIRED,
                    Collections.emptyList(),
                    "Connect EVE Cloud account to load available exams."));
        }
    }

    private void fetchWithUser(FirebaseUser user) {
        fetchWithUserInternal(user, true);
    }

    private void fetchWithUserInternal(FirebaseUser user, boolean allowRetryOn401) {
        user.getIdToken(!allowRetryOn401)
                .addOnSuccessListener(tokenResult -> {
                    String token = tokenResult != null ? tokenResult.getToken() : null;
                    if (token == null || token.trim().isEmpty()) {
                        if (allowRetryOn401) {
                            fetchWithUserInternal(user, false);
                            return;
                        }
                        loading.set(false);
                        publishResult(new EveExamModels.ExamCatalogResult(
                                EveExamModels.LoadState.AUTH_REQUIRED,
                                Collections.emptyList(),
                                "Sign in to EVE Cloud to refresh exams."));
                        return;
                    }
                    ioExecutor.execute(() -> {
                        try {
                            Future<JSONArray> testsFuture = ioExecutor.submit(() -> {
                                try {
                                    return backendClient.fetchGeneratedTestsJson(token, null);
                                } catch (Exception ignored) {
                                    return null;
                                }
                            });
                            Future<Set<String>> pinsFuture = ioExecutor.submit(() -> {
                                try {
                                    return parsePinnedIds(backendClient.fetchPinnedExamIdsJson(token));
                                } catch (Exception ignored) {
                                    return null;
                                }
                            });
                            JSONArray examsJson = backendClient.fetchExamsJson(token);
                            JSONArray testsJson = null;
                            try {
                                testsJson = testsFuture.get();
                            } catch (Exception ignored) {
                            }
                            Set<String> fetchedPins = null;
                            try {
                                fetchedPins = pinsFuture.get();
                            } catch (Exception ignored) {
                            }
                            if (fetchedPins != null) {
                                pinnedExamIds = fetchedPins;
                            }
                            saveDiskCache(examsJson, testsJson, pinnedExamIds);
                            allExamSummaries = parseAllExamsCatalog(examsJson, testsJson, pinnedExamIds);
                            List<EveExamModels.ExamSummary> parsed = parseExamCatalog(examsJson, testsJson, pinnedExamIds);
                            lastSuccessLoadMs = System.currentTimeMillis();
                            EveExamModels.LoadState state = parsed.isEmpty()
                                    ? EveExamModels.LoadState.EMPTY
                                    : EveExamModels.LoadState.LOADED;
                            loading.set(false);
                            publishResult(new EveExamModels.ExamCatalogResult(state, parsed, ""));
                        } catch (EveBackendClient.BackendException be) {
                            if (be.unauthorized && allowRetryOn401) {
                                fetchWithUserInternal(user, false);
                                return;
                            }
                            loading.set(false);
                            EveExamModels.LoadState errState = be.unauthorized
                                    ? EveExamModels.LoadState.AUTH_REQUIRED
                                    : EveExamModels.LoadState.ERROR;
                            publishResult(new EveExamModels.ExamCatalogResult(errState, currentResult.exams, be.getMessage()));
                        } catch (Throwable t) {
                            loading.set(false);
                            FileLog.e("EveExamRepository: failed to load exams", t);
                            publishResult(new EveExamModels.ExamCatalogResult(
                                    EveExamModels.LoadState.ERROR,
                                    currentResult.exams,
                                    "Could not load exams from EVE Cloud."));
                        }
                    });
                })
                .addOnFailureListener(e -> {
                    if (allowRetryOn401) {
                        fetchWithUserInternal(user, false);
                        return;
                    }
                    loading.set(false);
                    publishResult(new EveExamModels.ExamCatalogResult(
                            EveExamModels.LoadState.AUTH_REQUIRED,
                            currentResult.exams,
                            "Connect EVE Cloud account to load available exams."));
                });
    }

    public List<EveExamModels.GeneratedTestSummary> getCachedTestsForExam(String examId) {
        if (examId == null || examId.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String raw = getSwrJson("exam_tests_" + examId.trim());
        if (raw.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return parseGeneratedTests(new JSONArray(raw));
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    public void loadTestsForExam(String examId, ExamTestsCallback callback) {
        withFreshToken(new ApiCallback<List<EveExamModels.GeneratedTestSummary>>() {
            @Override
            public void onComplete(List<EveExamModels.GeneratedTestSummary> data, String errorMessage) {
                if (callback != null) {
                    callback.onLoaded(data != null ? data : Collections.emptyList(), errorMessage != null ? errorMessage : "");
                }
            }
        }, token -> {
            JSONArray arr = backendClient.fetchGeneratedTestsJson(token, examId);
            if (examId != null && !examId.trim().isEmpty() && arr != null) {
                putSwrJson("exam_tests_" + examId.trim(), arr.toString());
            }
            List<EveExamModels.GeneratedTestSummary> parsed = parseGeneratedTests(arr);
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onLoaded(parsed, "");
                }
            });
        });
    }

    public EveExamModels.ExamSummary findExamSummary(String examIdOrAttemptKey) {
        if (examIdOrAttemptKey == null || examIdOrAttemptKey.trim().isEmpty()) {
            return null;
        }
        String raw = examIdOrAttemptKey.trim();
        int sep = raw.indexOf("__");
        String baseExamId = sep > 0 ? raw.substring(0, sep).trim() : raw;
        List<EveExamModels.ExamSummary> list = allExamSummaries;
        if (list == null) {
            return null;
        }
        for (EveExamModels.ExamSummary item : list) {
            if (item != null && (baseExamId.equals(item.id) || raw.equals(item.id))) {
                return item;
            }
        }
        return null;
    }

    public void startOrResumeAttempt(
            String attemptKey,
            String sourceExamId,
            String generatedTestId,
            String resumeAttemptId,
            ApiCallback<EveExamModels.StartAttemptResult> callback) {
        if (attemptKey == null || attemptKey.trim().isEmpty()) {
            postComplete(callback, null, "Invalid test identifier.");
            return;
        }
        withFreshToken(callback, token -> {
            JSONObject payload = new JSONObject();
            payload.put("examId", attemptKey.trim());
            if (resumeAttemptId != null && !resumeAttemptId.trim().isEmpty()) {
                payload.put("resumeAttemptId", resumeAttemptId.trim());
            }
            JSONObject root = backendClient.postJsonRoot("/api/attempts/start", token, payload);
            JSONObject data = root != null ? root.optJSONObject("data") : null;
            EveExamModels.StartAttemptResult parsed = parseStartAttemptResult(data, sourceExamId);
            if (!parsed.alreadySubmitted && parsed.questions.isEmpty() && generatedTestId != null && !generatedTestId.trim().isEmpty()) {
                JSONObject detailRoot = backendClient.getJsonRoot("/api/generated-tests/" + urlEncode(generatedTestId.trim()), token);
                JSONObject detailData = detailRoot != null ? detailRoot.optJSONObject("data") : null;
                JSONArray qArr = detailData != null ? detailData.optJSONArray("questions") : null;
                List<EveExamModels.TestQuestion> fallbackQs = parseQuestionsArray(qArr, sourceExamId, generatedTestId);
                parsed = new EveExamModels.StartAttemptResult(
                        false,
                        parsed.completedAttemptId,
                        parsed.clientAttemptId,
                        parsed.startedAtMs,
                        parsed.timeLimitSeconds,
                        parsed.remainingSeconds,
                        fallbackQs
                );
            }
            postComplete(callback, parsed, "");
        });
    }

    public void pauseAttempt(String attemptKey) {
        EveAuthManager authManager = EveAuthManager.getInstance();
        authManager.ensureInitialized();
        FirebaseUser user = authManager.getCurrentUser();
        if (user == null || attemptKey == null || attemptKey.trim().isEmpty()) {
            return;
        }
        user.getIdToken(false).addOnSuccessListener(result -> {
            String token = result != null ? result.getToken() : null;
            if (token == null || token.trim().isEmpty()) {
                return;
            }
            ioExecutor.execute(() -> {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("examId", attemptKey);
                    backendClient.postJsonRoot("/api/attempts/pause", token, payload);
                } catch (Throwable ignored) {
                }
            });
        });
    }

    public void submitAttempt(
            String attemptKey,
            String examName,
            String category,
            String clientAttemptId,
            JSONArray answersPayload,
            ApiCallback<EveExamModels.AttemptResult> callback) {
        withFreshToken(callback, token -> {
            EveAuthManager auth = EveAuthManager.getInstance();
            String displayName = auth.getCachedProfile() != null && !auth.getCachedProfile().displayName.trim().isEmpty()
                    ? auth.getCachedProfile().displayName.trim()
                    : "Student";
            JSONObject payload = new JSONObject();
            payload.put("examId", attemptKey);
            payload.put("examName", examName != null && !examName.trim().isEmpty() ? examName : "Test");
            payload.put("category", category != null ? category : "General");
            payload.put("displayName", displayName);
            payload.put("clientAttemptId", clientAttemptId != null ? clientAttemptId : "");
            payload.put("answers", answersPayload != null ? answersPayload : new JSONArray());
            JSONObject root = backendClient.postJsonRoot("/api/attempts/submit", token, payload);
            invalidateSwrKey("leaderboard_overall");
            if (attemptKey != null && !attemptKey.trim().isEmpty()) {
                invalidateSwrKey("leaderboard_" + attemptKey.trim());
                int sep = attemptKey.indexOf("__");
                if (sep > 0) {
                    invalidateSwrKey("leaderboard_" + attemptKey.substring(0, sep));
                }
            }
            EveExamModels.AttemptResult result = parseAttemptResult(
                    root.optJSONObject("data"),
                    attemptKey,
                    examName,
                    category,
                    displayName
            );
            postComplete(callback, result, "");
        });
    }

    public void resetForReattempt(String attemptKey, ApiCallback<Boolean> callback) {
        withFreshToken(callback, token -> {
            JSONObject payload = new JSONObject();
            payload.put("examId", attemptKey);
            backendClient.postJsonRoot("/api/attempts/reset", token, payload);
            postComplete(callback, Boolean.TRUE, "");
        });
    }

    public void loadAttemptHistory(ApiCallback<List<EveExamModels.AttemptResult>> callback) {
        withFreshToken(callback, token -> {
            JSONObject root = backendClient.getJsonRoot("/api/attempts", token);
            List<EveExamModels.AttemptResult> list = parseAttemptHistory(root.optJSONArray("data"));
            postComplete(callback, list, "");
        });
    }

    public void loadAttemptById(String attemptId, ApiCallback<EveExamModels.AttemptResult> callback) {
        loadAttemptHistory((list, err) -> {
            if (!err.isEmpty()) {
                callback.onComplete(null, err);
                return;
            }
            if (list != null) {
                for (EveExamModels.AttemptResult item : list) {
                    if (attemptId != null && attemptId.equals(item.attemptId)) {
                        callback.onComplete(item, "");
                        return;
                    }
                }
            }
            callback.onComplete(null, "Submitted result not found.");
        });
    }

    public EveExamModels.LeaderboardBundle getCachedLeaderboard(String examIdOrOverall) {
        final String target = (examIdOrOverall == null || examIdOrOverall.trim().isEmpty())
                ? "overall"
                : examIdOrOverall.trim();
        String raw = getSwrJson("leaderboard_" + target);
        if (raw.isEmpty()) {
            return null;
        }
        try {
            JSONObject bundleJson = new JSONObject(raw);
            List<EveExamModels.LeaderboardEntry> entries = parseLeaderboardEntries(bundleJson.optJSONArray("entries"));
            EveExamModels.LeaderboardStats stats = null;
            JSONObject statsObj = bundleJson.optJSONObject("stats");
            if (statsObj != null) {
                if ("overall".equals(target) && statsObj.has("totalParticipants")) {
                    int participants = statsObj.optInt("totalParticipants", entries.size());
                    int myRank = statsObj.optInt("rank", 0);
                    double myScore = statsObj.optDouble("score", 0.0);
                    double topperScore = entries.isEmpty() ? myScore : entries.get(0).score;
                    stats = new EveExamModels.LeaderboardStats(
                            participants,
                            topperScore,
                            0.0,
                            myRank > 0 ? myRank : null,
                            myScore,
                            null,
                            null
                    );
                } else {
                    stats = parseLeaderboardStats(statsObj);
                }
            }
            return new EveExamModels.LeaderboardBundle(entries, stats);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void loadLeaderboard(String examIdOrOverall, ApiCallback<EveExamModels.LeaderboardBundle> callback) {
        final String target = (examIdOrOverall == null || examIdOrOverall.trim().isEmpty())
                ? "overall"
                : examIdOrOverall.trim();
        withFreshToken(callback, token -> {
            String encoded = urlEncode(target);
            JSONObject lbRoot = backendClient.getJsonRoot("/api/leaderboard?examId=" + encoded + "&limit=50", token);
            JSONArray entriesArr = lbRoot.optJSONArray("data");
            List<EveExamModels.LeaderboardEntry> entries = parseLeaderboardEntries(entriesArr);

            EveExamModels.LeaderboardStats stats = null;
            JSONObject rawStatsObj = null;
            if ("overall".equals(target)) {
                try {
                    JSONObject rankRoot = backendClient.getJsonRoot("/api/leaderboard/rank?examId=overall", token);
                    JSONObject rankData = rankRoot.optJSONObject("data");
                    if (rankData != null) {
                        rawStatsObj = rankData;
                        int participants = rankData.optInt("totalParticipants", entries.size());
                        int myRank = rankData.optInt("rank", 0);
                        double myScore = rankData.optDouble("score", 0.0);
                        double topperScore = entries.isEmpty() ? myScore : entries.get(0).score;
                        stats = new EveExamModels.LeaderboardStats(
                                participants,
                                topperScore,
                                0.0,
                                myRank > 0 ? myRank : null,
                                myScore,
                                null,
                                null
                        );
                    }
                } catch (Exception ignored) {
                }
            } else {
                try {
                    JSONObject statsRoot = backendClient.getJsonRoot("/api/leaderboard/stats?examId=" + encoded, token);
                    rawStatsObj = statsRoot.optJSONObject("data");
                    stats = parseLeaderboardStats(rawStatsObj);
                } catch (Exception ignored) {
                }
            }
            try {
                JSONObject cacheObj = new JSONObject();
                cacheObj.put("entries", entriesArr != null ? entriesArr : new JSONArray());
                if (rawStatsObj != null) {
                    cacheObj.put("stats", rawStatsObj);
                }
                putSwrJson("leaderboard_" + target, cacheObj.toString());
            } catch (Throwable ignored) {
            }
            postComplete(callback, new EveExamModels.LeaderboardBundle(entries, stats), "");
        });
    }

    public void toggleBookmark(
            String attemptKey,
            String examName,
            EveExamModels.AttemptAnswerReview answer,
            ApiCallback<Boolean> callback) {
        withFreshToken(callback, token -> {
            JSONObject payload = new JSONObject();
            payload.put("questionId", answer.questionId);
            payload.put("examId", attemptKey != null ? attemptKey : "");
            payload.put("examName", examName != null ? examName : "");
            payload.put("questionNumber", answer.number);
            payload.put("questionText", answer.questionText);
            payload.put("questionTextHi", answer.questionTextHi);
            payload.put("topic", answer.topic);
            JSONObject root = backendClient.postJsonRoot("/api/bookmarks/toggle", token, payload);
            JSONObject data = root.optJSONObject("data");
            boolean bookmarked = data != null && data.optBoolean("isBookmarked", false);
            postComplete(callback, bookmarked, "");
        });
    }

    public void submitQuestionReport(
            String questionId,
            String examId,
            String examName,
            String questionText,
            String reason,
            String comment,
            ApiCallback<String> callback) {
        withFreshToken(callback, token -> {
            JSONObject payload = new JSONObject();
            payload.put("questionId", questionId);
            payload.put("examId", examId != null ? examId : "");
            payload.put("examName", examName != null ? examName : "");
            payload.put("questionText", questionText != null ? questionText : "");
            payload.put("reason", reason);
            payload.put("comment", comment != null ? comment : "");
            JSONObject root = backendClient.postJsonRoot("/api/reports", token, payload);
            String msg = root.optString("message", "Report submitted to EVE review team.");
            postComplete(callback, msg, "");
        });
    }

    public List<EveExamModels.BroadcastItem> getCachedBroadcasts() {
        String raw = getSwrJson("broadcasts_all");
        if (raw.isEmpty()) return Collections.emptyList();
        try {
            return parseBroadcastsArray(new JSONArray(raw));
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private static List<EveExamModels.BroadcastItem> parseBroadcastsArray(JSONArray arr) {
        List<EveExamModels.BroadcastItem> items = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                items.add(new EveExamModels.BroadcastItem(
                        cleanOptString(obj, "id", ""),
                        cleanOptString(obj, "title", "EVE Update"),
                        cleanOptString(obj, "message", ""),
                        cleanOptString(obj, "type", "announcement"),
                        obj.optLong("sentAt", 0L),
                        cleanOptString(obj, "stickerDocId", cleanOptString(obj, "sticker_doc_id", "")),
                        cleanOptString(obj, "stickerAccessHash", cleanOptString(obj, "sticker_access_hash", "")),
                        obj.optInt("stickerDcId", obj.optInt("sticker_dc_id", 0)),
                        cleanOptString(obj, "stickerEmoji", cleanOptString(obj, "sticker_emoji", ""))
                ));
            }
        }
        return items;
    }

    public void loadBroadcasts(ApiCallback<List<EveExamModels.BroadcastItem>> callback) {
        withFreshToken(callback, token -> {
            JSONObject root = backendClient.getJsonRoot("/api/broadcasts?limit=50", token);
            JSONArray arr = root.optJSONArray("data");
            if (arr != null) {
                putSwrJson("broadcasts_all", arr.toString());
            }
            postComplete(callback, parseBroadcastsArray(arr), "");
        });
    }

    public List<EveExamModels.ExamSummary> getSubExamsForParent(String parentExamId) {
        if (parentExamId == null || parentExamId.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String parent = parentExamId.trim();
        List<EveExamModels.ExamSummary> out = new ArrayList<>();
        for (EveExamModels.ExamSummary item : allExamSummaries) {
            if (item != null && !item.isPaused() && parent.equals(item.parentExamId.trim())) {
                out.add(item);
            }
        }
        return out;
    }

    public List<EveAdminModels.BannerItem> getCachedBanners() {
        return cachedBanners;
    }

    public void loadActiveBanners(ApiCallback<List<EveAdminModels.BannerItem>> callback) {
        ioExecutor.execute(() -> {
            try {
                JSONObject root = backendClient.getPublicJsonRoot("/api/banners");
                JSONArray rawArr = root.optJSONArray("data");
                if (rawArr != null) {
                    putSwrJson("banners_home", rawArr.toString());
                }
                List<EveAdminModels.BannerItem> parsed = EveAdminModels.BannerItem.parseList(rawArr);
                List<EveAdminModels.BannerItem> activeOnly = new ArrayList<>();
                for (EveAdminModels.BannerItem b : parsed) {
                    if (b != null && b.active) {
                        activeOnly.add(b);
                    }
                }
                cachedBanners = Collections.unmodifiableList(activeOnly);
                postComplete(callback, cachedBanners, "");
            } catch (Throwable t) {
                postComplete(callback, cachedBanners, t.getMessage() != null ? t.getMessage() : "Unable to load banners.");
            }
        });
    }

    public List<EvePollModel> getCachedActivePolls() {
        String raw = getSwrJson("polls_active");
        if (raw.isEmpty()) return Collections.emptyList();
        try {
            return parsePollsFromArray(new JSONArray(raw), null);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private List<EvePollModel> parsePollsFromArray(JSONArray arr, String optionalTokenForLegacyFallback) {
        List<EvePollModel> activePolls = new ArrayList<>();
        long nowMs = System.currentTimeMillis();
        if (arr == null) return activePolls;
        List<Integer> needsFallbackIndices = new ArrayList<>();
        List<String> needsFallbackPollIds = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            boolean active = o.optBoolean("active", o.optBoolean("isActive", true));
            long endsAt = o.optLong("endsAt", 0L);
            if (!active || (endsAt > 0L && nowMs > endsAt)) {
                continue;
            }
            String id = o.optString("id", "");
            String question = o.optString("question", "");
            List<String> opts = new ArrayList<>();
            JSONArray optArr = o.optJSONArray("options");
            if (optArr != null) {
                for (int j = 0; j < optArr.length(); j++) {
                    String opt = optArr.optString(j, "").trim();
                    if (!opt.isEmpty()) opts.add(opt);
                }
            }
            if (id.isEmpty() || question.isEmpty() || opts.size() < 2) {
                continue;
            }
            Map<Integer, Integer> counts = new HashMap<>();
            int sumVotes = 0;
            JSONObject vc = o.optJSONObject("voteCounts");
            if (vc != null) {
                for (int j = 0; j < opts.size(); j++) {
                    int count = Math.max(0, vc.optInt(String.valueOf(j), 0));
                    counts.put(j, count);
                    sumVotes += count;
                }
            }
            Integer myVote = null;
            if (o.has("userVote")) {
                if (!o.isNull("userVote")) {
                    myVote = o.optInt("userVote");
                }
            } else if (optionalTokenForLegacyFallback != null && !optionalTokenForLegacyFallback.isEmpty()) {
                needsFallbackIndices.add(activePolls.size());
                needsFallbackPollIds.add(id);
            }
            boolean isQuiz = o.optBoolean("isQuiz", o.optInt("is_quiz", 1) == 1);
            int correctOptionIndex = (o.has("correctOptionIndex") && !o.isNull("correctOptionIndex"))
                    ? o.optInt("correctOptionIndex", -1)
                    : (o.has("correct_option_index") && !o.isNull("correct_option_index") ? o.optInt("correct_option_index", -1) : -1);
            Boolean isCorrect = (o.has("isCorrect") && !o.isNull("isCorrect")) ? Boolean.valueOf(o.optBoolean("isCorrect")) : null;
            String explanation = cleanOptString(o, "explanation", "");
            activePolls.add(new EvePollModel(
                    id,
                    question,
                    opts,
                    counts,
                    sumVotes,
                    true,
                    o.optLong("createdAt", 0L),
                    endsAt,
                    o.optString("createdBy", ""),
                    myVote,
                    isQuiz,
                    correctOptionIndex,
                    isCorrect,
                    explanation
            ));
        }
        // Only run concurrent vote fallback if an older Worker version did not embed userVote.
        if (!needsFallbackPollIds.isEmpty() && optionalTokenForLegacyFallback != null) {
            List<Future<Integer>> futures = new ArrayList<>(needsFallbackPollIds.size());
            for (String pid : needsFallbackPollIds) {
                futures.add(ioExecutor.submit(() -> {
                    try {
                        JSONObject voteRoot = backendClient.getJsonRoot("/api/polls/" + urlEncode(pid) + "/vote", optionalTokenForLegacyFallback);
                        JSONObject voteData = voteRoot.optJSONObject("data");
                        return (voteData != null && !voteData.isNull("optionIndex")) ? voteData.optInt("optionIndex") : null;
                    } catch (Exception ignored) {
                        return null;
                    }
                }));
            }
            for (int k = 0; k < needsFallbackIndices.size(); k++) {
                try {
                    Integer v = futures.get(k).get();
                    if (v != null) {
                        EvePollModel prev = activePolls.get(needsFallbackIndices.get(k));
                        activePolls.set(needsFallbackIndices.get(k), new EvePollModel(
                                prev.id, prev.question, prev.options, prev.voteCounts, prev.totalVotes,
                                prev.active, prev.createdAt, prev.endsAt, prev.createdBy, v));
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return activePolls;
    }

    public void loadActivePolls(ApiCallback<List<EvePollModel>> callback) {
        withFreshToken(callback, token -> {
            JSONObject root = backendClient.getJsonRoot("/api/polls", token);
            JSONArray arr = root.optJSONArray("data");
            if (arr != null) {
                putSwrJson("polls_active", arr.toString());
            }
            List<EvePollModel> activePolls = parsePollsFromArray(arr, token);
            postComplete(callback, activePolls, "");
        });
    }

    public EveBackendClient.AppConfigResult getCachedAppConfig() {
        return cachedAppConfig;
    }

    public void fetchAppConfig(ApiCallback<EveBackendClient.AppConfigResult> callback) {
        ioExecutor.execute(() -> {
            try {
                EveBackendClient.AppConfigResult cfg = backendClient.fetchPublicAppConfig();
                cachedAppConfig = cfg;
                if (cfg != null) {
                    putSwrJson("app_config", cfg.toJson().toString());
                }
                postComplete(callback, cfg, "");
            } catch (Throwable t) {
                postComplete(callback, cachedAppConfig, t.getMessage() != null ? t.getMessage() : "Could not check server status.");
            }
        });
    }

    public long getLastSeenAnnouncementMs() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) return 0L;
            return ctx.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE).getLong(KEY_LAST_SEEN_ANNOUNCEMENT_MS, 0L);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public void markAllAnnouncementsSeen(long latestTimestampMs) {
        long ts = Math.max(getLastSeenAnnouncementMs(), Math.max(System.currentTimeMillis(), latestTimestampMs));
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                ctx.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE).edit()
                        .putLong(KEY_LAST_SEEN_ANNOUNCEMENT_MS, ts).apply();
            }
        } catch (Throwable ignored) {
        }
        AnnouncementChannelSummary prev = cachedChannelSummary;
        if (prev != null) {
            cachedChannelSummary = new AnnouncementChannelSummary(
                    prev.channelName, prev.channelAvatarUrl, prev.latestPreview,
                    prev.latestTimestampSec, Math.max(prev.latestTimestampMs, ts), 0);
        }
    }

    public AnnouncementChannelSummary getCachedAnnouncementChannelSummary() {
        if (cachedChannelSummary != null) {
            return cachedChannelSummary;
        }
        EveBackendClient.AppConfigResult cfg = cachedAppConfig;
        List<EveExamModels.BroadcastItem> broadcasts = getCachedBroadcasts();
        List<EvePollModel> polls = getCachedActivePolls();
        if (cfg == null && broadcasts.isEmpty() && polls.isEmpty()) {
            return null;
        }
        return buildChannelSummary(cfg, broadcasts, polls, getLastSeenAnnouncementMs());
    }

    public void loadAnnouncementChannelSummary(ApiCallback<AnnouncementChannelSummary> callback) {
        AnnouncementChannelSummary immediate = getCachedAnnouncementChannelSummary();
        if (immediate != null && callback != null) {
            cachedChannelSummary = immediate;
        }
        EveAuthManager authManager = EveAuthManager.getInstance();
        authManager.ensureInitialized();
        FirebaseUser user = authManager.getCurrentUser();
        ioExecutor.execute(() -> {
            Future<EveBackendClient.AppConfigResult> cfgFuture = ioExecutor.submit(() -> {
                try {
                    EveBackendClient.AppConfigResult cfg = backendClient.fetchPublicAppConfig();
                    cachedAppConfig = cfg;
                    if (cfg != null) putSwrJson("app_config", cfg.toJson().toString());
                    return cfg;
                } catch (Throwable ignored) {
                    return cachedAppConfig;
                }
            });
            String token = null;
            if (user != null) {
                try {
                    com.google.android.gms.tasks.Task<com.google.firebase.auth.GetTokenResult> t = user.getIdToken(false);
                    com.google.android.gms.tasks.Tasks.await(t, 5, java.util.concurrent.TimeUnit.SECONDS);
                    if (t.isSuccessful() && t.getResult() != null) {
                        token = t.getResult().getToken();
                    }
                } catch (Throwable ignored) {
                }
            }
            final String authToken = token;
            Future<List<EveExamModels.BroadcastItem>> bcFuture = ioExecutor.submit(() -> {
                if (authToken == null || authToken.isEmpty()) return getCachedBroadcasts();
                try {
                    JSONObject root = backendClient.getJsonRoot("/api/broadcasts?limit=50", authToken);
                    JSONArray arr = root.optJSONArray("data");
                    if (arr != null) putSwrJson("broadcasts_all", arr.toString());
                    return parseBroadcastsArray(arr);
                } catch (Throwable ignored) {
                    return getCachedBroadcasts();
                }
            });
            Future<List<EvePollModel>> pollFuture = ioExecutor.submit(() -> {
                if (authToken == null || authToken.isEmpty()) return getCachedActivePolls();
                try {
                    JSONObject root = backendClient.getJsonRoot("/api/polls", authToken);
                    JSONArray arr = root.optJSONArray("data");
                    if (arr != null) putSwrJson("polls_active", arr.toString());
                    return parsePollsFromArray(arr, authToken);
                } catch (Throwable ignored) {
                    return getCachedActivePolls();
                }
            });
            try {
                AnnouncementChannelSummary summary = buildChannelSummary(
                        cfgFuture.get(), bcFuture.get(), pollFuture.get(), getLastSeenAnnouncementMs());
                cachedChannelSummary = summary;
                postComplete(callback, summary, "");
            } catch (Throwable t) {
                postComplete(callback, getCachedAnnouncementChannelSummary(),
                        t.getMessage() != null ? t.getMessage() : "Unable to load channel summary.");
            }
        });
    }

    private static AnnouncementChannelSummary buildChannelSummary(
            EveBackendClient.AppConfigResult cfg,
            List<EveExamModels.BroadcastItem> broadcasts,
            List<EvePollModel> polls,
            long lastSeenMs) {
        String name = cfg != null ? cfg.announcementChannelName : "Announcements";
        String avatarUrl = cfg != null ? cfg.announcementChannelAvatarUrl : "";
        String avatarEmoji = cfg != null ? cfg.announcementChannelAvatarEmoji : "";
        String preview = "Broadcasts & live polls";
        long latestMs = 0L;
        int unread = 0;

        if (broadcasts != null) {
            for (EveExamModels.BroadcastItem b : broadcasts) {
                if (b == null) continue;
                if (b.sentAtMs > latestMs) {
                    latestMs = b.sentAtMs;
                    preview = !b.message.trim().isEmpty()
                            ? (b.title + ": " + b.message.trim())
                            : b.title;
                }
                if (b.sentAtMs > lastSeenMs) {
                    unread++;
                }
            }
        }
        if (polls != null) {
            for (EvePollModel p : polls) {
                if (p == null) continue;
                if (p.createdAt > latestMs) {
                    latestMs = p.createdAt;
                    preview = "Poll: " + p.question;
                }
                if (p.createdAt > lastSeenMs) {
                    unread++;
                }
            }
        }
        int tsSec = latestMs > 0L ? (int) (latestMs / 1000L) : 0;
        return new AnnouncementChannelSummary(name, avatarUrl, preview, tsSec, latestMs, unread, avatarEmoji);
    }

    public void fetchMyPollVote(String pollId, ApiCallback<Integer> callback) {
        if (pollId == null || pollId.trim().isEmpty()) {
            postComplete(callback, null, "Invalid poll ID.");
            return;
        }
        withFreshToken(callback, token -> {
            JSONObject root = backendClient.getJsonRoot("/api/polls/" + urlEncode(pollId.trim()) + "/vote", token);
            JSONObject data = root.optJSONObject("data");
            Integer optionIndex = (data != null && !data.isNull("optionIndex")) ? data.optInt("optionIndex") : null;
            postComplete(callback, optionIndex, "");
        });
    }

    public static final class QuizVoteOutcome {
        public final boolean isQuiz;
        public final int correctOptionIndex;
        public final Boolean isCorrect;
        public final String explanation;

        public QuizVoteOutcome(boolean isQuiz, int correctOptionIndex, Boolean isCorrect, String explanation) {
            this.isQuiz = isQuiz;
            this.correctOptionIndex = correctOptionIndex;
            this.isCorrect = isCorrect;
            this.explanation = explanation != null ? explanation : "";
        }
    }

    public void submitPollVote(String pollId, int optionIndex, ApiCallback<Boolean> callback) {
        submitQuizVote(pollId, optionIndex, (outcome, err) -> {
            if (callback != null) {
                callback.onComplete(outcome != null, err);
            }
        });
    }

    public void submitQuizVote(String pollId, int optionIndex, ApiCallback<QuizVoteOutcome> callback) {
        if (pollId == null || pollId.trim().isEmpty() || optionIndex < 0) {
            postComplete(callback, null, "Invalid quiz vote.");
            return;
        }
        withFreshToken(callback, token -> {
            JSONObject payload = new JSONObject();
            payload.put("optionIndex", optionIndex);
            JSONObject root = backendClient.postJsonRoot("/api/polls/" + urlEncode(pollId.trim()) + "/vote", token, payload);
            invalidateSwrKey("polls_active");
            JSONObject data = root != null ? root.optJSONObject("data") : null;
            boolean isQuiz = data == null || data.optBoolean("isQuiz", true);
            int correctIdx = (data != null && data.has("correctOptionIndex") && !data.isNull("correctOptionIndex"))
                    ? data.optInt("correctOptionIndex", -1)
                    : -1;
            Boolean isCorrect = (data != null && data.has("isCorrect") && !data.isNull("isCorrect"))
                    ? Boolean.valueOf(data.optBoolean("isCorrect"))
                    : null;
            String explanation = data != null ? cleanOptString(data, "explanation", "") : "";
            postComplete(callback, new QuizVoteOutcome(isQuiz, correctIdx, isCorrect, explanation), "");
        });
    }

    private <T> void withFreshToken(ApiCallback<T> callback, TokenTask task) {
        withFreshTokenInternal(callback, task, true);
    }

    private <T> void withFreshTokenInternal(ApiCallback<T> callback, TokenTask task, boolean allowRetryOn401) {
        EveAuthManager authManager = EveAuthManager.getInstance();
        authManager.ensureInitialized();
        FirebaseUser user = authManager.getCurrentUser();
        if (user == null) {
            postComplete(callback, null, "Sign in to EVE Cloud to continue.");
            return;
        }
        user.getIdToken(!allowRetryOn401)
                .addOnSuccessListener(result -> {
                    String token = result != null ? result.getToken() : null;
                    if (token == null || token.trim().isEmpty()) {
                        if (allowRetryOn401) {
                            withFreshTokenInternal(callback, task, false);
                            return;
                        }
                        postComplete(callback, null, "Authentication token unavailable.");
                        return;
                    }
                    ioExecutor.execute(() -> {
                        try {
                            task.execute(token);
                        } catch (EveBackendClient.BackendException be) {
                            if (be.unauthorized && allowRetryOn401) {
                                withFreshTokenInternal(callback, task, false);
                                return;
                            }
                            String msg = be.getMessage() != null && !be.getMessage().trim().isEmpty()
                                    ? be.getMessage()
                                    : "Request to EVE Cloud failed.";
                            postComplete(callback, null, msg);
                        } catch (Throwable t) {
                            String msg = t.getMessage() != null && !t.getMessage().trim().isEmpty()
                                    ? t.getMessage()
                                    : "Request to EVE Cloud failed.";
                            postComplete(callback, null, msg);
                        }
                    });
                })
                .addOnFailureListener(e -> {
                    if (allowRetryOn401) {
                        withFreshTokenInternal(callback, task, false);
                        return;
                    }
                    postComplete(callback, null, "Could not verify EVE Cloud session.");
                });
    }

    private <T> void postComplete(ApiCallback<T> callback, T data, String error) {
        if (callback == null) {
            return;
        }
        mainHandler.post(() -> callback.onComplete(data, error != null ? error : ""));
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

    public static EveExamModels.StartAttemptResult parseStartAttemptResult(JSONObject data, String fallbackExamId) {
        if (data == null) {
            return new EveExamModels.StartAttemptResult(false, "", "", 0L, 0, 0, Collections.emptyList());
        }
        boolean alreadySubmitted = data.optBoolean("alreadySubmitted", false);
        String completedAttemptId = data.optString("completedAttemptId", "");
        String clientAttemptId = data.optString("clientAttemptId", "");
        long startedAt = data.optLong("startedAt", 0L);
        int timeLimit = data.optInt("timeLimitSeconds", 0);
        int remaining = data.optInt("remainingSeconds", timeLimit);
        List<EveExamModels.TestQuestion> questions = parseQuestionsArray(data.optJSONArray("questions"), fallbackExamId, "");
        return new EveExamModels.StartAttemptResult(
                alreadySubmitted,
                completedAttemptId,
                clientAttemptId,
                startedAt,
                timeLimit,
                remaining,
                questions
        );
    }

    private static String cleanOptString(JSONObject obj, String key, String fallback) {
        if (obj == null || key == null || !obj.has(key) || obj.isNull(key)) {
            return fallback;
        }
        String val = obj.optString(key, "").trim();
        if (val.isEmpty() || "null".equalsIgnoreCase(val)) {
            return fallback;
        }
        return val;
    }

    public static List<EveExamModels.TestQuestion> parseQuestionsArray(JSONArray arr, String fallbackExamId, String fallbackTestId) {
        List<EveExamModels.TestQuestion> list = new ArrayList<>();
        if (arr == null) {
            return list;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) {
                continue;
            }
            String qId = cleanOptString(obj, "id", "");
            if (qId.trim().isEmpty()) {
                if (fallbackTestId != null && !fallbackTestId.trim().isEmpty()) {
                    qId = fallbackTestId.trim() + "_" + i;
                } else {
                    qId = "q_" + (i + 1);
                }
            }
            list.add(new EveExamModels.TestQuestion(
                    qId,
                    cleanOptString(obj, "examId", fallbackExamId != null ? fallbackExamId : ""),
                    i + 1,
                    firstNonEmpty(obj, "questionText", "question_text"),
                    firstNonEmpty(obj, "questionTextHi", "question_text_hi"),
                    firstNonEmpty(obj, "optionA", "option_a"),
                    firstNonEmpty(obj, "optionB", "option_b"),
                    firstNonEmpty(obj, "optionC", "option_c"),
                    firstNonEmpty(obj, "optionD", "option_d"),
                    firstNonEmpty(obj, "optionAHi", "option_a_hi"),
                    firstNonEmpty(obj, "optionBHi", "option_b_hi"),
                    firstNonEmpty(obj, "optionCHi", "option_c_hi"),
                    firstNonEmpty(obj, "optionDHi", "option_d_hi"),
                    cleanOptString(obj, "topic", "")
            ));
        }
        return list;
    }

    public static EveExamModels.AttemptResult parseAttemptResult(
            JSONObject data,
            String fallbackExamId,
            String fallbackExamName,
            String fallbackCategory,
            String fallbackDisplayName) {
        if (data == null) {
            return null;
        }
        String attemptId = firstNonEmpty(data, "attemptId", "id");
        List<EveExamModels.AttemptAnswerReview> answers = parseAnswerReviewList(data.optJSONArray("answers"));
        return new EveExamModels.AttemptResult(
                attemptId,
                firstNonEmpty(data, "examId", "").isEmpty() ? (fallbackExamId != null ? fallbackExamId : "") : data.optString("examId", ""),
                firstNonEmpty(data, "examName", "").isEmpty() ? (fallbackExamName != null ? fallbackExamName : "Mock Test") : data.optString("examName", "Mock Test"),
                firstNonEmpty(data, "category", "").isEmpty() ? (fallbackCategory != null ? fallbackCategory : "General") : data.optString("category", "General"),
                firstNonEmpty(data, "displayName", "").isEmpty() ? (fallbackDisplayName != null ? fallbackDisplayName : "Student") : data.optString("displayName", "Student"),
                data.optDouble("score", 0.0),
                data.optInt("total", answers.size()),
                data.optInt("correct", 0),
                data.optInt("wrong", 0),
                data.optInt("unattempted", 0),
                data.optLong("timestamp", System.currentTimeMillis()),
                data.optInt("timeTakenSeconds", 0),
                data.optInt("counted", 1),
                answers
        );
    }

    public static List<EveExamModels.AttemptResult> parseAttemptHistory(JSONArray arr) {
        List<EveExamModels.AttemptResult> list = new ArrayList<>();
        if (arr == null) {
            return list;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            EveExamModels.AttemptResult item = parseAttemptResult(obj, "", "Mock Test", "General", "Student");
            if (item != null) {
                list.add(item);
            }
        }
        return list;
    }

    public static List<EveExamModels.AttemptAnswerReview> parseAnswerReviewList(JSONArray arr) {
        List<EveExamModels.AttemptAnswerReview> list = new ArrayList<>();
        if (arr == null) {
            return list;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            list.add(new EveExamModels.AttemptAnswerReview(
                    obj.optString("questionId", ""),
                    obj.optInt("number", i + 1),
                    obj.optString("questionText", ""),
                    obj.optString("questionTextHi", ""),
                    obj.optString("selected", ""),
                    obj.optString("selectedText", ""),
                    obj.optString("selectedTextHi", ""),
                    obj.optString("correct", ""),
                    obj.optString("correctText", ""),
                    obj.optString("correctTextHi", ""),
                    obj.optString("explanation", ""),
                    obj.optString("explanationHi", ""),
                    obj.optString("topic", ""),
                    obj.optBoolean("isBookmarked", false),
                    obj.optInt("timeTakenSeconds", 0)
            ));
        }
        return list;
    }

    public static List<EveExamModels.LeaderboardEntry> parseLeaderboardEntries(JSONArray arr) {
        List<EveExamModels.LeaderboardEntry> list = new ArrayList<>();
        if (arr == null) {
            return list;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            list.add(new EveExamModels.LeaderboardEntry(
                    obj.optString("userId", ""),
                    obj.optString("examId", ""),
                    obj.optString("examName", ""),
                    obj.optString("displayName", "Student"),
                    obj.optDouble("score", 0.0),
                    obj.optInt("total", 0),
                    obj.optInt("accuracy", 0),
                    obj.optLong("timestamp", 0L),
                    obj.optInt("timeTakenSeconds", 0)
            ));
        }
        return list;
    }

    public static EveExamModels.LeaderboardStats parseLeaderboardStats(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        Integer myRank = obj.isNull("myRank") ? null : obj.optInt("myRank", 0);
        Double myScore = obj.isNull("myScore") ? null : obj.optDouble("myScore", 0.0);
        Integer myTime = obj.isNull("myTimeSeconds") ? null : obj.optInt("myTimeSeconds", 0);
        Double myPercentile = obj.isNull("myPercentile") ? null : obj.optDouble("myPercentile", 0.0);
        return new EveExamModels.LeaderboardStats(
                obj.optInt("participants", 0),
                obj.optDouble("topperScore", 0.0),
                obj.optDouble("averageScore", 0.0),
                myRank,
                myScore,
                myTime,
                myPercentile
        );
    }

    private static String firstNonEmpty(JSONObject obj, String key1, String key2) {
        if (obj == null) {
            return "";
        }
        String v1 = obj.isNull(key1) ? "" : obj.optString(key1, "");
        if (v1 != null && !v1.trim().isEmpty() && !"null".equalsIgnoreCase(v1.trim())) {
            return v1;
        }
        if (key2 == null || key2.isEmpty()) {
            return "";
        }
        String v2 = obj.isNull(key2) ? "" : obj.optString(key2, "");
        return (v2 != null && !"null".equalsIgnoreCase(v2.trim())) ? v2 : "";
    }

    public void togglePinExam(String examId, ApiCallback<Boolean> callback) {
        String normalizedId = examId != null ? examId.trim() : "";
        if (normalizedId.isEmpty()) {
            postComplete(callback, false, "Invalid exam.");
            return;
        }
        final Set<String> previousPins = new HashSet<>(pinnedExamIds);
        final boolean wasPinned = previousPins.contains(normalizedId);
        final boolean optimisticPinned = !wasPinned;

        Set<String> nextPins = new HashSet<>(previousPins);
        if (optimisticPinned) {
            nextPins.add(normalizedId);
        } else {
            nextPins.remove(normalizedId);
        }
        applyPinnedIdsAndNotify(nextPins);

        withFreshToken(new ApiCallback<Boolean>() {
            @Override
            public void onComplete(Boolean data, String errorMessage) {
                if (errorMessage != null && !errorMessage.isEmpty()) {
                    applyPinnedIdsAndNotify(previousPins);
                    postComplete(callback, wasPinned, errorMessage);
                    return;
                }
                postComplete(callback, data != null ? data : optimisticPinned, "");
            }
        }, token -> {
            boolean serverPinned = backendClient.toggleExamPin(token, normalizedId);
            Set<String> syncedPins = new HashSet<>(pinnedExamIds);
            if (serverPinned) {
                syncedPins.add(normalizedId);
            } else {
                syncedPins.remove(normalizedId);
            }
            applyPinnedIdsAndNotify(syncedPins);
            postComplete(callback, serverPinned, "");
        });
    }

    private void applyPinnedIdsAndNotify(Set<String> pins) {
        Set<String> copy = pins != null ? Collections.unmodifiableSet(new HashSet<>(pins)) : Collections.emptySet();
        pinnedExamIds = copy;
        savePinnedIdsCache(copy);
        EveExamModels.ExamCatalogResult prev = currentResult;
        if (prev != null && !prev.exams.isEmpty()) {
            List<EveExamModels.ExamSummary> baseSorted = new ArrayList<>(prev.exams.size());
            for (EveExamModels.ExamSummary ex : prev.exams) {
                baseSorted.add(ex.withPinned(copy.contains(ex.id)));
            }
            Collections.sort(baseSorted, Comparator.comparing(e -> e.examName.toLowerCase()));
            List<EveExamModels.ExamSummary> reordered = EveExamDialogsMapper.sortExamsByPin(baseSorted);
            publishResult(new EveExamModels.ExamCatalogResult(prev.state, reordered, prev.errorMessage));
        }
    }

    private static Set<String> parsePinnedIds(JSONArray arr) {
        Set<String> out = new HashSet<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            String id = arr.optString(i, "").trim();
            if (!id.isEmpty()) {
                out.add(id);
            }
        }
        return out;
    }

    public static List<EveExamModels.ExamSummary> parseExamCatalog(JSONArray examsJson, JSONArray testsJson) {
        return parseExamCatalog(examsJson, testsJson, Collections.emptySet());
    }

    public static List<EveExamModels.ExamSummary> parseAllExamsCatalog(
            JSONArray examsJson,
            JSONArray testsJson,
            Set<String> pinnedIds) {
        return parseExamCatalogInternal(examsJson, testsJson, pinnedIds, false);
    }

    public static List<EveExamModels.ExamSummary> parseExamCatalog(
            JSONArray examsJson,
            JSONArray testsJson,
            Set<String> pinnedIds) {
        return parseExamCatalogInternal(examsJson, testsJson, pinnedIds, true);
    }

    private static List<EveExamModels.ExamSummary> parseExamCatalogInternal(
            JSONArray examsJson,
            JSONArray testsJson,
            Set<String> pinnedIds,
            boolean mainExamsOnly) {
        Map<String, Integer> liveCountByExam = new HashMap<>();
        Map<String, EveExamModels.GeneratedTestSummary> latestLiveByExam = new HashMap<>();

        if (testsJson != null) {
            List<EveExamModels.GeneratedTestSummary> tests = parseGeneratedTests(testsJson);
            for (EveExamModels.GeneratedTestSummary test : tests) {
                if (!test.isLive() || test.examId.isEmpty()) {
                    continue;
                }
                Integer prevCount = liveCountByExam.get(test.examId);
                liveCountByExam.put(test.examId, (prevCount == null ? 0 : prevCount) + 1);
                EveExamModels.GeneratedTestSummary existing = latestLiveByExam.get(test.examId);
                if (existing == null || test.generatedAtMs > existing.generatedAtMs) {
                    latestLiveByExam.put(test.examId, test);
                }
            }
        }

        List<EveExamModels.ExamSummary> list = new ArrayList<>();
        if (examsJson == null) {
            return list;
        }
        for (int i = 0; i < examsJson.length(); i++) {
            JSONObject obj = examsJson.optJSONObject(i);
            if (obj == null) {
                continue;
            }
            String id = obj.optString("id", "");
            if (id.trim().isEmpty()) {
                continue;
            }
            String publishMode = obj.optString("publishMode", "inherit");
            if ("paused".equalsIgnoreCase(publishMode.trim())) {
                continue;
            }
            String parentExamId = obj.optString("parentExamId", "");
            int liveCount = liveCountByExam.containsKey(id) ? liveCountByExam.get(id) : 0;
            EveExamModels.GeneratedTestSummary latestTest = latestLiveByExam.get(id);
            String nextLabel = latestTest != null ? latestTest.getDisplayTitle() : "";
            long lastGenTimeMs = obj.optLong("lastGenerationTime", 0L);
            long bestTimeMs = latestTest != null && latestTest.generatedAtMs > 0 ? latestTest.generatedAtMs : lastGenTimeMs;
            int dateSec = bestTimeMs > 0 ? (int) (bestTimeMs / 1000L) : 0;

            EveExamModels.ExamSummary summary = new EveExamModels.ExamSummary(
                    id,
                    obj.optString("examName", ""),
                    obj.optInt("timeLimitMinutes", 30),
                    obj.optString("category", "General"),
                    obj.optInt("questionCount", 20),
                    obj.optString("testNumber", "Test 1"),
                    obj.optString("negativeMarkingText", "0"),
                    parentExamId,
                    publishMode,
                    lastGenTimeMs,
                    liveCount,
                    nextLabel,
                    dateSec,
                    pinnedIds != null && pinnedIds.contains(id),
                    firstNonEmpty(obj, "avatarUrl", "imageUrl"),
                    firstNonEmpty(obj, "avatarEmoji", ""),
                    obj.isNull("avatarColor") ? -1 : obj.optInt("avatarColor", -1),
                    firstNonEmpty(obj, "syllabusUrl", "syllabus_url"),
                    firstNonEmpty(obj, "syllabusFileName", "syllabus_file_name"),
                    parseCutoffsMap(obj.optJSONObject("cutoffs"))
            );
            if (!mainExamsOnly || summary.isMainExam()) {
                list.add(summary);
            }
        }
        Collections.sort(list, Comparator.comparing(e -> e.examName.toLowerCase()));
        return EveExamDialogsMapper.sortExamsByPin(list);
    }

    public static Map<String, Double> parseCutoffsMap(JSONObject cutoffsObj) {
        if (cutoffsObj == null || cutoffsObj.length() == 0) {
            return Collections.emptyMap();
        }
        Map<String, Double> map = new LinkedHashMap<>();
        String[] keys = new String[] { "General", "OBC", "SC", "ST", "EWS" };
        for (String key : keys) {
            if (!cutoffsObj.has(key) || cutoffsObj.isNull(key)) {
                continue;
            }
            double val = cutoffsObj.optDouble(key, Double.NaN);
            if (!Double.isNaN(val) && !Double.isInfinite(val) && val >= 0.0) {
                map.put(key, val);
            }
        }
        return map.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(map);
    }

    public static List<EveExamModels.GeneratedTestSummary> parseGeneratedTests(JSONArray arr) {
        List<EveExamModels.GeneratedTestSummary> list = new ArrayList<>();
        if (arr == null) {
            return list;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) {
                continue;
            }
            String id = obj.optString("id", "");
            if (id.trim().isEmpty()) {
                continue;
            }
            list.add(new EveExamModels.GeneratedTestSummary(
                    id,
                    obj.optString("examId", ""),
                    obj.optString("examName", ""),
                    obj.optString("testNumber", ""),
                    obj.optString("title", ""),
                    obj.optLong("generatedAt", 0L),
                    obj.optString("status", "paused"),
                    obj.optInt("questionCount", 0),
                    obj.optLong("availableFrom", 0L),
                    firstNonEmpty(obj, "avatarUrl", ""),
                    firstNonEmpty(obj, "avatarEmoji", ""),
                    obj.isNull("avatarColor") ? -1 : obj.optInt("avatarColor", -1)
            ));
        }
        return list;
    }

    private void publishResult(EveExamModels.ExamCatalogResult result) {
        currentResult = result;
        notifyListeners(result);
    }

    private void notifyListeners(EveExamModels.ExamCatalogResult result) {
        mainHandler.post(() -> {
            for (CatalogListener listener : listeners) {
                listener.onExamCatalogUpdated(result);
            }
        });
    }
}
