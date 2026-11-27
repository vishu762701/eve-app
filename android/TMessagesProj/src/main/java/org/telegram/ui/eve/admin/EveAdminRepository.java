package org.telegram.ui.eve.admin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.eve.auth.EveAuthManager;
import org.telegram.ui.eve.auth.EveBackendClient;
import org.telegram.ui.eve.auth.EveUserProfile;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Authoritative EVE Admin Control Center repository backed by Cloudflare Worker + D1.
 * Every administrative read and write is authenticated via Firebase ID token Bearer auth
 * and enforced server-side by D1 admin membership and granular permissions.
 */
public final class EveAdminRepository {

    public interface ResultCallback<T> {
        void onSuccess(T data);
        void onError(String message, int httpStatus);
    }

    private static volatile EveAdminRepository instance;

    private static final String PREFS_DASHBOARD_CACHE = "eve_admin_dashboard_cache";
    private static final String KEY_SNAPSHOT_PREFIX = "dashboard_snapshot_v2_";
    private static final long DASHBOARD_STALE_AFTER_MS = 60_000L;

    private final EveBackendClient client;
    private final ExecutorService executor = Executors.newFixedThreadPool(6);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object dashboardLock = new Object();
    private final List<ResultCallback<EveAdminModels.DashboardSnapshot>> pendingDashboardCallbacks = new ArrayList<>();
    private boolean dashboardRefreshInFlight;
    private volatile EveAdminModels.DashboardSnapshot cachedSnapshot;
    private volatile long cachedSnapshotAtMs;

    public static EveAdminRepository getInstance() {
        EveAdminRepository current = instance;
        if (current == null) {
            synchronized (EveAdminRepository.class) {
                current = instance;
                if (current == null) {
                    current = new EveAdminRepository(new EveBackendClient());
                    instance = current;
                }
            }
        }
        return current;
    }

    public EveAdminRepository(EveBackendClient client) {
        this.client = client != null ? client : new EveBackendClient();
    }

    public EveAdminModels.DashboardSnapshot getCachedSnapshot() {
        EveAdminModels.DashboardSnapshot memory = cachedSnapshot;
        if (memory != null) {
            boolean stale = (System.currentTimeMillis() - cachedSnapshotAtMs) > DASHBOARD_STALE_AFTER_MS;
            return stale != memory.isStale || !memory.isFromCache ? memory.asCacheCopy(stale) : memory;
        }
        EveAdminModels.DashboardSnapshot disk = readPersistedSnapshot();
        if (disk != null) {
            cachedSnapshot = disk;
            cachedSnapshotAtMs = disk.loadedAtMs;
        }
        return disk;
    }

    public long getCachedSnapshotAtMs() {
        return cachedSnapshotAtMs;
    }

    public void invalidateCache() {
        cachedSnapshot = null;
        cachedSnapshotAtMs = 0L;
        clearPersistedSnapshot();
    }

    public void cancelDashboardCallback(ResultCallback<EveAdminModels.DashboardSnapshot> callback) {
        if (callback == null) {
            return;
        }
        synchronized (dashboardLock) {
            pendingDashboardCallbacks.remove(callback);
        }
    }

    public static final class ExamsCatalogData {
        public final List<EveAdminModels.AdminExamItem> exams;
        public final List<EveAdminModels.AdminGeneratedTestItem> generatedTests;

        public ExamsCatalogData(List<EveAdminModels.AdminExamItem> exams, List<EveAdminModels.AdminGeneratedTestItem> generatedTests) {
            this.exams = exams != null ? exams : Collections.emptyList();
            this.generatedTests = generatedTests != null ? generatedTests : Collections.emptyList();
        }
    }

    public static final class InsightsBundle {
        public final EveAdminModels.AppConfigModel appConfig;
        public final List<EveAdminModels.SystemServiceStatus> serviceStatuses;
        public final List<EveAdminModels.GenerationJobItem> generationJobs;

        public InsightsBundle(
                EveAdminModels.AppConfigModel appConfig,
                List<EveAdminModels.SystemServiceStatus> serviceStatuses,
                List<EveAdminModels.GenerationJobItem> generationJobs) {
            this.appConfig = appConfig;
            this.serviceStatuses = serviceStatuses != null ? serviceStatuses : Collections.emptyList();
            this.generationJobs = generationJobs != null ? generationJobs : Collections.emptyList();
        }
    }

    public void loadExamsCatalogAsync(ResultCallback<ExamsCatalogData> callback) {
        withAdminToken(callback, token -> {
            Future<List<EveAdminModels.AdminExamItem>> examsFuture = executor.submit(() ->
                    EveAdminModels.AdminExamItem.parseList(client.fetchExamsJson(token)));
            Future<List<EveAdminModels.AdminGeneratedTestItem>> testsFuture = executor.submit(() ->
                    EveAdminModels.AdminGeneratedTestItem.parseList(client.fetchGeneratedTestsJson(token, null)));
            try {
                ExamsCatalogData result = new ExamsCatalogData(examsFuture.get(), testsFuture.get());
                postSuccess(callback, result);
            } catch (Exception ex) {
                throw unwrapBackendException(ex, "/api/exams");
            }
        });
    }

    public void loadInsightsOverviewAsync(EveAdminModels.AdminProfile profile, ResultCallback<InsightsBundle> callback) {
        withAdminToken(callback, token -> {
            Future<EveAdminModels.AppConfigModel> cfgFuture = executor.submit(() -> {
                try {
                    return fetchAppConfig(token);
                } catch (Throwable ignored) {
                    return null;
                }
            });
            Future<List<EveAdminModels.SystemServiceStatus>> healthFuture = executor.submit(() -> {
                if (!EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_VIEW_ANALYTICS_LOGS)) {
                    return Collections.emptyList();
                }
                try {
                    return fetchSystemHealth(token);
                } catch (Throwable ignored) {
                    return Collections.emptyList();
                }
            });
            Future<List<EveAdminModels.GenerationJobItem>> jobsFuture = executor.submit(() -> {
                if (!EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_VIEW_ANALYTICS_LOGS)) {
                    return Collections.emptyList();
                }
                try {
                    return fetchFailedJobs(token);
                } catch (Throwable ignored) {
                    return Collections.emptyList();
                }
            });
            try {
                postSuccess(callback, new InsightsBundle(cfgFuture.get(), healthFuture.get(), jobsFuture.get()));
            } catch (Exception ex) {
                throw unwrapBackendException(ex, "/api/admin/system/health");
            }
        });
    }

    public void loadDashboardAsync(ResultCallback<EveAdminModels.DashboardSnapshot> callback) {
        final boolean startRefresh;
        synchronized (dashboardLock) {
            if (callback != null && !pendingDashboardCallbacks.contains(callback)) {
                pendingDashboardCallbacks.add(callback);
            }
            if (dashboardRefreshInFlight) {
                startRefresh = false;
            } else {
                dashboardRefreshInFlight = true;
                startRefresh = true;
            }
        }
        if (!startRefresh) {
            return;
        }
        ResultCallback<EveAdminModels.DashboardSnapshot> dispatcher = new ResultCallback<EveAdminModels.DashboardSnapshot>() {
            @Override
            public void onSuccess(EveAdminModels.DashboardSnapshot data) {
                List<ResultCallback<EveAdminModels.DashboardSnapshot>> targets;
                synchronized (dashboardLock) {
                    dashboardRefreshInFlight = false;
                    targets = new ArrayList<>(pendingDashboardCallbacks);
                    pendingDashboardCallbacks.clear();
                }
                for (ResultCallback<EveAdminModels.DashboardSnapshot> target : targets) {
                    if (target != null) {
                        target.onSuccess(data);
                    }
                }
            }

            @Override
            public void onError(String message, int httpStatus) {
                List<ResultCallback<EveAdminModels.DashboardSnapshot>> targets;
                synchronized (dashboardLock) {
                    dashboardRefreshInFlight = false;
                    targets = new ArrayList<>(pendingDashboardCallbacks);
                    pendingDashboardCallbacks.clear();
                }
                for (ResultCallback<EveAdminModels.DashboardSnapshot> target : targets) {
                    if (target != null) {
                        target.onError(message, httpStatus);
                    }
                }
            }
        };
        withAdminToken(dispatcher, token -> {
            EveAdminModels.DashboardSnapshot snapshot = loadDashboardSnapshot(token);
            cachedSnapshot = snapshot;
            cachedSnapshotAtMs = snapshot != null ? snapshot.loadedAtMs : System.currentTimeMillis();
            persistSnapshot(snapshot);
            postSuccess(dispatcher, snapshot);
        });
    }

    public EveAdminModels.DashboardSnapshot loadDashboardSnapshot(String token) throws EveBackendClient.BackendException {
        long startNs = System.nanoTime();
        try {
            JSONObject root = client.getJsonRoot("/api/admin/dashboard", token);
            JSONObject data = root != null ? root.optJSONObject("data") : null;
            if (data != null && data.optJSONObject("adminProfile") != null) {
                long elapsedMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
                EveAdminModels.DashboardSnapshot unified = EveAdminModels.DashboardSnapshot.fromDashboardJson(data, elapsedMs);
                if (unified != null) {
                    return unified;
                }
            }
        } catch (EveBackendClient.BackendException e) {
            // Propagate auth failures immediately; fall back only if aggregated route is unavailable on an older deployment.
            if (e.httpStatus != 404 && e.httpStatus != 405 && e.httpStatus != 501) {
                throw e;
            }
        }
        return loadDashboardLegacyParallel(token, startNs);
    }

    private EveAdminModels.DashboardSnapshot loadDashboardLegacyParallel(String token, long startNs) throws EveBackendClient.BackendException {
        Future<EveAdminModels.AdminProfile> meFuture = executor.submit(() -> {
            JSONObject meRoot = client.getJsonRoot("/api/admin/me", token);
            return EveAdminModels.AdminProfile.fromJson(meRoot.optJSONObject("data"));
        });
        Future<List<EveAdminModels.AdminExamItem>> examsFuture = executor.submit(() ->
                EveAdminModels.AdminExamItem.parseList(client.fetchExamsJson(token)));
        Future<List<EveAdminModels.AdminGeneratedTestItem>> testsFuture = executor.submit(() ->
                EveAdminModels.AdminGeneratedTestItem.parseList(client.fetchGeneratedTestsJson(token, null)));

        final EveAdminModels.AdminProfile profile;
        try {
            profile = meFuture.get();
        } catch (Exception ex) {
            throw unwrapBackendException(ex, "/api/admin/me");
        }

        final List<String> partialWarnings = new ArrayList<>();
        List<EveAdminModels.AdminExamItem> exams = Collections.emptyList();
        List<EveAdminModels.AdminGeneratedTestItem> tests = Collections.emptyList();
        try {
            exams = examsFuture.get();
        } catch (Exception ex) {
            partialWarnings.add("Exams summary unavailable");
        }
        try {
            tests = testsFuture.get();
        } catch (Exception ex) {
            partialWarnings.add("Generated tests count unavailable");
        }

        int publishedExams = 0;
        int draftExams = 0;
        for (EveAdminModels.AdminExamItem exam : exams) {
            if (exam.isPaused()) {
                draftExams++;
            } else {
                publishedExams++;
            }
        }

        int liveTests = 0;
        int draftTests = 0;
        for (EveAdminModels.AdminGeneratedTestItem test : tests) {
            if (test.isLive()) {
                liveTests++;
            } else {
                draftTests++;
            }
        }

        Future<JSONObject> userStatsFuture = executor.submit(() -> {
            if (!EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_USERS)) {
                return null;
            }
            return client.getJsonRoot("/api/admin/stats/users", token).optJSONObject("data");
        });
        Future<JSONArray> examAnalyticsFuture = executor.submit(() -> {
            if (!EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_VIEW_ANALYTICS_LOGS)) {
                return null;
            }
            return client.getJsonRoot("/api/admin/analytics/exams", token).optJSONArray("data");
        });
        Future<List<EveAdminModels.AuditLogEntry>> logsFuture = executor.submit(() -> {
            if (!EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_VIEW_ANALYTICS_LOGS)) {
                return new ArrayList<>();
            }
            JSONArray logs = client.getJsonRoot("/api/admin/audit-log?limit=5", token).optJSONArray("data");
            return EveAdminModels.AuditLogEntry.parseList(logs);
        });
        Future<List<EveAdminModels.PaymentTransactionItem>> txFuture = executor.submit(() -> {
            if (!EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_PREMIUM)) {
                return Collections.emptyList();
            }
            return fetchTransactions(token, 100);
        });
        Future<Integer> premiumCountFuture = executor.submit(() -> {
            if (!EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_PREMIUM)) {
                return 0;
            }
            return fetchPremiumUsers(token).size();
        });
        Future<Integer> pendingReportsFuture = executor.submit(() -> {
            if (!EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MODERATE_FEEDBACK)) {
                return 0;
            }
            int count = 0;
            for (EveAdminModels.QuestionReportItem r : fetchReports(token, null)) {
                if ("pending".equalsIgnoreCase(r.status)) {
                    count++;
                }
            }
            return count;
        });

        int totalStudents = 0;
        int activeStudents = 0;
        int totalAttempts = 0;
        double scoreSum = 0.0;
        int scoreCount = 0;
        List<EveAdminModels.AuditLogEntry> recentActions = new ArrayList<>();
        int totalTransactions = 0;
        int paidTransactions = 0;
        int pendingTransactions = 0;
        long totalRevenueInr = 0;
        int activePremiumUsers = 0;
        int pendingReports = 0;

        try {
            JSONObject userStats = userStatsFuture.get();
            if (userStats != null) {
                totalStudents = userStats.optInt("totalUsers", 0);
                activeStudents = userStats.optInt("onlineUsers", 0);
            }
        } catch (Exception ignored) {
            partialWarnings.add("Student counts unavailable");
        }
        try {
            JSONArray examAnalytics = examAnalyticsFuture.get();
            if (examAnalytics != null) {
                for (int i = 0; i < examAnalytics.length(); i++) {
                    JSONObject row = examAnalytics.optJSONObject(i);
                    if (row != null) {
                        int count = row.optInt("attemptCount", 0);
                        totalAttempts += count;
                        if (count > 0 && row.has("averageScore")) {
                            scoreSum += row.optDouble("averageScore", 0.0) * count;
                            scoreCount += count;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            partialWarnings.add("Exam analytics unavailable");
        }
        try {
            recentActions = logsFuture.get();
        } catch (Exception ignored) {
            partialWarnings.add("Audit log preview unavailable");
        }
        try {
            List<EveAdminModels.PaymentTransactionItem> txList = txFuture.get();
            totalTransactions = txList.size();
            for (EveAdminModels.PaymentTransactionItem tx : txList) {
                if ("PAID".equalsIgnoreCase(tx.status)) {
                    paidTransactions++;
                    totalRevenueInr += tx.amount;
                } else if ("PENDING".equalsIgnoreCase(tx.status)) {
                    pendingTransactions++;
                }
            }
        } catch (Exception ignored) {
            partialWarnings.add("Payment summary unavailable");
        }
        try {
            activePremiumUsers = premiumCountFuture.get();
        } catch (Exception ignored) {
            partialWarnings.add("Premium subscriber count unavailable");
        }
        try {
            pendingReports = pendingReportsFuture.get();
        } catch (Exception ignored) {
            partialWarnings.add("Moderation count unavailable");
        }

        double averageScorePct = scoreCount > 0 ? (scoreSum / scoreCount) : 0.0;
        long elapsedMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);

        return new EveAdminModels.DashboardSnapshot(
                profile,
                totalStudents,
                activeStudents,
                exams.size(),
                publishedExams,
                draftExams,
                tests.size(),
                liveTests,
                draftTests,
                totalAttempts,
                totalAttempts,
                totalTransactions,
                paidTransactions,
                totalRevenueInr,
                pendingTransactions,
                activePremiumUsers,
                pendingReports,
                averageScorePct,
                exams,
                tests,
                recentActions,
                false,
                false,
                partialWarnings,
                System.currentTimeMillis(),
                elapsedMs
        );
    }

    private static SharedPreferences getCachePrefs() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return null;
            }
            return ctx.getSharedPreferences(PREFS_DASHBOARD_CACHE, Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String resolveCurrentEmailScope() {
        try {
            EveUserProfile profile = EveAuthManager.getInstance().getCachedProfile();
            if (profile != null && profile.email != null && !profile.email.trim().isEmpty()) {
                return profile.email.trim().toLowerCase(Locale.US);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static void persistSnapshot(EveAdminModels.DashboardSnapshot snapshot) {
        if (snapshot == null || snapshot.adminProfile == null) {
            return;
        }
        SharedPreferences prefs = getCachePrefs();
        if (prefs == null) {
            return;
        }
        String email = snapshot.adminProfile.email != null ? snapshot.adminProfile.email.trim().toLowerCase(Locale.US) : "";
        if (email.isEmpty()) {
            return;
        }
        prefs.edit().putString(KEY_SNAPSHOT_PREFIX + email, snapshot.toCacheJson().toString()).apply();
    }

    private static EveAdminModels.DashboardSnapshot readPersistedSnapshot() {
        SharedPreferences prefs = getCachePrefs();
        if (prefs == null) {
            return null;
        }
        String email = resolveCurrentEmailScope();
        if (email.isEmpty()) {
            return null;
        }
        String raw = prefs.getString(KEY_SNAPSHOT_PREFIX + email, null);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(raw);
            EveAdminModels.DashboardSnapshot restored = EveAdminModels.DashboardSnapshot.fromDashboardJson(json, json.optLong("durationMs", 0L));
            if (restored == null) {
                return null;
            }
            boolean stale = (System.currentTimeMillis() - restored.loadedAtMs) > DASHBOARD_STALE_AFTER_MS;
            return restored.asCacheCopy(stale);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void clearPersistedSnapshot() {
        SharedPreferences prefs = getCachePrefs();
        if (prefs == null) {
            return;
        }
        prefs.edit().clear().apply();
    }

    private static EveBackendClient.BackendException unwrapBackendException(Throwable t, String fallbackEndpoint) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof EveBackendClient.BackendException) {
                return (EveBackendClient.BackendException) cur;
            }
            cur = cur.getCause();
        }
        return new EveBackendClient.BackendException(
                500,
                fallbackEndpoint,
                "ADMIN_LOAD_FAILED",
                EveBackendClient.ErrorKind.D1_OR_SERVER_ERROR,
                t != null && t.getMessage() != null ? t.getMessage() : "Unable to load admin data."
        );
    }

    public String createExam(
            String token,
            String examName,
            String category,
            int timeLimitMinutes,
            int questionCount,
            String testNumber,
            String negativeMarkingText,
            String publishMode,
            String generationPrompt) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("examName", examName);
            payload.put("category", category);
            payload.put("timeLimitMinutes", timeLimitMinutes);
            payload.put("questionCount", questionCount);
            payload.put("testNumber", testNumber);
            payload.put("negativeMarkingText", negativeMarkingText);
            payload.put("publishMode", publishMode);
            payload.put("generationPrompt", generationPrompt != null ? generationPrompt : "");
        } catch (JSONException ignored) {
        }
        JSONObject root = client.postJsonRoot("/api/exams", token, payload);
        JSONObject data = root.optJSONObject("data");
        return data != null ? data.optString("id", "") : "";
    }

    public void updateExam(
            String token,
            String examId,
            String examName,
            String category,
            int timeLimitMinutes,
            int questionCount,
            String negativeMarkingText,
            String publishMode) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("examName", examName);
            payload.put("category", category);
            payload.put("timeLimitMinutes", timeLimitMinutes);
            payload.put("questionCount", questionCount);
            payload.put("negativeMarkingText", negativeMarkingText);
            payload.put("publishMode", publishMode);
        } catch (JSONException ignored) {
        }
        client.putJsonRoot("/api/exams/" + encode(examId), token, payload);
    }

    public JSONObject generateTestNow(
            String token,
            String examId,
            int questionCount,
            String testNumber,
            String customPromptNotes) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("examId", examId);
            payload.put("questionCount", questionCount);
            payload.put("testNumber", testNumber);
            payload.put("customPromptNotes", customPromptNotes != null ? customPromptNotes : "");
        } catch (JSONException ignored) {
        }
        return client.postJsonRoot("/api/generated-tests/generate-now", token, payload);
    }

    public void updateGeneratedTestStatus(String token, String testId, String status) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("status", status);
        } catch (JSONException ignored) {
        }
        client.putJsonRoot("/api/generated-tests/" + encode(testId) + "/status", token, payload);
    }

    public void deleteGeneratedTest(String token, String testId) throws EveBackendClient.BackendException {
        client.deleteJsonRoot("/api/generated-tests/" + encode(testId), token);
    }

    public List<EveAdminModels.AdminQuestionItem> fetchQuestions(String token, String examId) throws EveBackendClient.BackendException {
        JSONObject root = client.getJsonRoot("/api/questions/exam/" + encode(examId), token);
        return EveAdminModels.AdminQuestionItem.parseList(root.optJSONArray("data"));
    }

    public String createQuestion(
            String token,
            String examId,
            String questionText,
            String optionA,
            String optionB,
            String optionC,
            String optionD,
            String correctAnswer,
            String explanation,
            String topic) throws EveBackendClient.BackendException {
        JSONObject payload = buildQuestionPayload(examId, questionText, optionA, optionB, optionC, optionD, correctAnswer, explanation, topic);
        JSONObject root = client.postJsonRoot("/api/questions", token, payload);
        JSONObject data = root.optJSONObject("data");
        return data != null ? data.optString("id", "") : "";
    }

    public void unpublishExamTests(String token, String examId) throws EveBackendClient.BackendException {
        client.postJsonRoot("/api/exams/" + encode(examId) + "/unpublish-tests", token, new JSONObject());
    }

    public void scheduleGeneratedTest(String token, String testId, long availableFromMs) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("availableFrom", Math.max(0L, availableFromMs));
        } catch (JSONException ignored) {
        }
        client.putJsonRoot("/api/generated-tests/" + encode(testId) + "/schedule", token, payload);
    }

    public List<EveAdminModels.AdminQuestionItem> fetchGeneratedTestQuestions(String token, String testId) throws EveBackendClient.BackendException {
        JSONObject root = client.getJsonRoot("/api/generated-tests/" + encode(testId), token);
        JSONObject data = root.optJSONObject("data");
        return EveAdminModels.AdminQuestionItem.parseList(data != null ? data.optJSONArray("questions") : null);
    }

    public int batchImportQuestions(String token, JSONArray questionsArray) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("questions", questionsArray != null ? questionsArray : new JSONArray());
        } catch (JSONException ignored) {
        }
        JSONObject root = client.postJsonRoot("/api/questions/batch", token, payload);
        return root.optInt("count", questionsArray != null ? questionsArray.length() : 0);
    }

    public void updateQuestion(
            String token,
            String questionId,
            String examId,
            String questionText,
            String optionA,
            String optionB,
            String optionC,
            String optionD,
            String correctAnswer,
            String explanation,
            String topic) throws EveBackendClient.BackendException {
        JSONObject payload = buildQuestionPayload(examId, questionText, optionA, optionB, optionC, optionD, correctAnswer, explanation, topic);
        client.putJsonRoot("/api/questions/" + encode(questionId), token, payload);
    }

    public void deleteQuestion(String token, String questionId) throws EveBackendClient.BackendException {
        client.deleteJsonRoot("/api/questions/" + encode(questionId), token);
    }

    public List<EveAdminModels.AdminStudentItem> searchStudents(String token, String query, int page, int pageSize) throws EveBackendClient.BackendException {
        String path = "/api/admin/users?page=" + page + "&pageSize=" + pageSize;
        if (query != null && !query.trim().isEmpty()) {
            path += "&q=" + encode(query.trim());
        }
        JSONObject root = client.getJsonRoot(path, token);
        return EveAdminModels.AdminStudentItem.parseList(root.optJSONArray("data"));
    }

    public void setStudentDisabled(String token, String userId, boolean disabled) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("disabled", disabled);
        } catch (JSONException ignored) {
        }
        client.postJsonRoot("/api/admin/users/" + encode(userId) + "/status", token, payload);
    }

    public List<EveAdminModels.StudentAttemptItem> fetchStudentAttempts(String token, String userId) throws EveBackendClient.BackendException {
        JSONObject root = client.getJsonRoot("/api/admin/users/" + encode(userId) + "/attempts", token);
        return EveAdminModels.StudentAttemptItem.parseList(root.optJSONArray("data"));
    }

    public void grantPremium(String token, String userIdOrEmail, String planName, int durationDays, boolean isLifetime) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("userIdOrEmail", userIdOrEmail);
            payload.put("planName", planName);
            payload.put("isLifetime", isLifetime);
            if (isLifetime) {
                payload.put("confirmLifetime", true);
            } else {
                payload.put("durationDays", durationDays);
            }
        } catch (JSONException ignored) {
        }
        client.postJsonRoot("/api/admin/premium/users/grant", token, payload);
    }

    public void extendPremium(String token, String userId, int additionalDays) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("userId", userId);
            payload.put("additionalDays", additionalDays);
        } catch (JSONException ignored) {
        }
        client.postJsonRoot("/api/admin/premium/users/extend", token, payload);
    }

    public void revokePremium(String token, String userId) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("userId", userId);
        } catch (JSONException ignored) {
        }
        client.postJsonRoot("/api/admin/premium/users/revoke", token, payload);
    }

    public EveAdminModels.PremiumConfigModel fetchPremiumConfig(String token) throws EveBackendClient.BackendException {
        JSONObject root = client.getJsonRoot("/api/admin/premium/config", token);
        return EveAdminModels.PremiumConfigModel.fromJson(root.optJSONObject("data"));
    }

    public void updatePremiumConfig(String token, EveAdminModels.PremiumConfigModel config) throws EveBackendClient.BackendException {
        client.putJsonRoot("/api/admin/premium/config", token, config.toPutJson());
    }

    public List<EveAdminModels.PaymentTransactionItem> fetchTransactions(String token, int limit) throws EveBackendClient.BackendException {
        return fetchTransactions(token, limit, "");
    }

    public List<EveAdminModels.PaymentTransactionItem> fetchTransactions(String token, int limit, String statusFilter) throws EveBackendClient.BackendException {
        String path = "/api/admin/premium/transactions?limit=" + Math.max(1, Math.min(limit, 200));
        if (statusFilter != null && !statusFilter.trim().isEmpty()) {
            path += "&status=" + encode(statusFilter.trim().toUpperCase(Locale.US));
        }
        JSONObject root = client.getJsonRoot(path, token);
        return EveAdminModels.PaymentTransactionItem.parseList(root.optJSONArray("data"));
    }

    public List<EveAdminModels.AdminMemberItem> fetchAdmins(String token) throws EveBackendClient.BackendException {
        JSONObject root = client.getJsonRoot("/api/admin/admins", token);
        return EveAdminModels.AdminMemberItem.parseList(root.optJSONArray("data"));
    }

    public void addAdmin(String token, String email, List<String> rights, String customTitle) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("email", email.trim().toLowerCase(Locale.US));
            payload.put("customTitle", customTitle != null && !customTitle.trim().isEmpty() ? customTitle.trim() : "Admin");
            JSONArray arr = new JSONArray();
            if (rights != null) {
                for (String r : rights) arr.put(r);
            }
            payload.put("rights", arr);
        } catch (JSONException ignored) {
        }
        client.postJsonRoot("/api/admin/admins", token, payload);
    }

    public void updateAdminRights(String token, String email, List<String> rights, String customTitle) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            if (customTitle != null && !customTitle.trim().isEmpty()) {
                payload.put("customTitle", customTitle.trim());
            }
            JSONArray arr = new JSONArray();
            if (rights != null) {
                for (String r : rights) arr.put(r);
            }
            payload.put("rights", arr);
        } catch (JSONException ignored) {
        }
        client.putJsonRoot("/api/admin/admins/" + encode(email.trim().toLowerCase(Locale.US)) + "/rights", token, payload);
    }

    public void removeAdmin(String token, String email) throws EveBackendClient.BackendException {
        client.deleteJsonRoot("/api/admin/admins/" + encode(email.trim().toLowerCase(Locale.US)), token);
    }

    public List<EveAdminModels.AuditLogEntry> fetchAuditLogs(String token, int limit) throws EveBackendClient.BackendException {
        JSONObject root = client.getJsonRoot("/api/admin/audit-log?limit=" + Math.max(1, Math.min(limit, 100)), token);
        return EveAdminModels.AuditLogEntry.parseList(root.optJSONArray("data"));
    }

    public int deleteAuditLogs(String token, String category) throws EveBackendClient.BackendException {
        String path = "/api/admin/audit-log";
        if (category != null && !category.trim().isEmpty() && !"all".equalsIgnoreCase(category.trim())) {
            path += "?category=" + encode(category.trim().toLowerCase(Locale.US));
        }
        JSONObject root = client.deleteJsonRoot(path, token);
        return root != null ? root.optInt("deletedCount", 0) : 0;
    }

    public EveAdminModels.AdminProfile fetchAdminMe(String token) throws EveBackendClient.BackendException {
        JSONObject root = client.getJsonRoot("/api/admin/me", token);
        return EveAdminModels.AdminProfile.fromJson(root.optJSONObject("data"));
    }

    public EveAdminModels.AdminStudentItem fetchStudentById(String token, String userId, String fallbackName) throws EveBackendClient.BackendException {
        if (userId == null || userId.trim().isEmpty()) {
            return null;
        }
        String trimmedId = userId.trim();
        try {
            JSONObject root = client.getJsonRoot("/api/admin/users/" + encode(trimmedId), token);
            JSONObject data = root != null ? root.optJSONObject("data") : null;
            if (data != null) {
                JSONArray arr = new JSONArray();
                arr.put(data);
                List<EveAdminModels.AdminStudentItem> parsed = EveAdminModels.AdminStudentItem.parseList(arr);
                if (!parsed.isEmpty()) {
                    return parsed.get(0);
                }
            }
        } catch (EveBackendClient.BackendException e) {
            if (e.httpStatus != 404 && e.httpStatus != 405 && e.httpStatus != 501) {
                throw e;
            }
        }
        // Fallback for older Worker deployments without GET /api/admin/users/:id
        if (fallbackName != null && !fallbackName.trim().isEmpty()) {
            for (EveAdminModels.AdminStudentItem item : searchStudents(token, fallbackName.trim(), 1, 50)) {
                if (trimmedId.equals(item.id)) {
                    return item;
                }
            }
        }
        for (EveAdminModels.AdminStudentItem item : searchStudents(token, "", 1, 200)) {
            if (trimmedId.equals(item.id)) {
                return item;
            }
        }
        return null;
    }

    public JSONObject fetchExamDeletionInfo(String token, String examId) throws EveBackendClient.BackendException {
        return client.getJsonRoot("/api/exams/" + encode(examId) + "/deletion-info", token).optJSONObject("data");
    }

    public void deleteExam(String token, String examId) throws EveBackendClient.BackendException {
        client.deleteJsonRoot("/api/exams/" + encode(examId), token);
    }

    public void removeSyllabus(String token, String examId) throws EveBackendClient.BackendException {
        client.deleteJsonRoot("/api/exams/" + encode(examId) + "/syllabus", token);
    }

    public void reassignSyllabus(String token, String sourceExamId, String targetExamId) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("targetExamId", targetExamId);
        } catch (JSONException ignored) {
        }
        client.postJsonRoot("/api/exams/" + encode(sourceExamId) + "/syllabus/reassign", token, payload);
    }

    public List<EveAdminModels.PremiumUserItem> fetchPremiumUsers(String token) throws EveBackendClient.BackendException {
        JSONObject root = client.getJsonRoot("/api/admin/premium/users", token);
        return EveAdminModels.PremiumUserItem.parseList(root.optJSONArray("data"));
    }

    public List<EveAdminModels.BannerItem> fetchBanners(String token) throws EveBackendClient.BackendException {
        JSONObject root = client.getJsonRoot("/api/banners", token);
        return EveAdminModels.BannerItem.parseList(root.optJSONArray("data"));
    }

    public void updateBannerLink(String token, String bannerId, String linkUrl, String linkLabel) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("linkUrl", linkUrl != null ? linkUrl : "");
            payload.put("linkLabel", linkLabel != null ? linkLabel : "");
        } catch (JSONException ignored) {
        }
        client.putJsonRoot("/api/banners/" + encode(bannerId) + "/link", token, payload);
    }

    public void reorderBanner(String token, String bannerId, boolean moveUp) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("moveUp", moveUp);
        } catch (JSONException ignored) {
        }
        client.putJsonRoot("/api/banners/" + encode(bannerId) + "/reorder", token, payload);
    }

    public void deleteBanner(String token, String bannerId) throws EveBackendClient.BackendException {
        client.deleteJsonRoot("/api/banners/" + encode(bannerId), token);
    }

    public JSONObject fetchFloatingLink(String token) throws EveBackendClient.BackendException {
        return client.getJsonRoot("/api/admin/floating-link", token);
    }

    public void updateFloatingLink(String token, String url) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("url", url != null ? url.trim() : "");
        } catch (JSONException ignored) {
        }
        client.putJsonRoot("/api/admin/floating-link", token, payload);
    }

    public List<EveAdminModels.PollItem> fetchPolls(String token) throws EveBackendClient.BackendException {
        JSONObject root = client.getJsonRoot("/api/polls", token);
        return EveAdminModels.PollItem.parseList(root.optJSONArray("data"));
    }

    public void createPoll(String token, String question, List<String> options) throws EveBackendClient.BackendException {
        createPoll(token, question, options, true);
    }

    public void createPoll(String token, String question, List<String> options, boolean active) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("question", question);
            JSONArray arr = new JSONArray();
            if (options != null) {
                for (String opt : options) {
                    if (opt != null && !opt.trim().isEmpty()) {
                        arr.put(opt.trim());
                    }
                }
            }
            payload.put("options", arr);
            payload.put("active", active);
        } catch (JSONException ignored) {
        }
        client.postJsonRoot("/api/polls", token, payload);
    }

    public void setPollActive(String token, String pollId, boolean active) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("active", active);
        } catch (JSONException ignored) {
        }
        client.putJsonRoot("/api/polls/" + encode(pollId) + "/status", token, payload);
    }

    public void deletePoll(String token, String pollId) throws EveBackendClient.BackendException {
        client.deleteJsonRoot("/api/polls/" + encode(pollId), token);
    }

    public List<EveAdminModels.BroadcastItem> fetchBroadcasts(String token) throws EveBackendClient.BackendException {
        JSONObject root = client.getJsonRoot("/api/broadcasts?limit=50", token);
        return EveAdminModels.BroadcastItem.parseList(root.optJSONArray("data"));
    }

    public void sendBroadcast(String token, String title, String message) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("title", title);
            payload.put("message", message);
        } catch (JSONException ignored) {
        }
        client.postJsonRoot("/api/broadcasts", token, payload);
    }

    public void deleteBroadcast(String token, String id) throws EveBackendClient.BackendException {
        client.deleteJsonRoot("/api/broadcasts/" + encode(id), token);
    }

    public List<EveAdminModels.QuestionReportItem> fetchReports(String token, String type) throws EveBackendClient.BackendException {
        String path = "/api/admin/reports" + (type != null && !type.isEmpty() ? "?type=" + encode(type) : "");
        JSONObject root = client.getJsonRoot(path, token);
        return EveAdminModels.QuestionReportItem.parseList(root.optJSONArray("data"));
    }

    public void dismissReport(String token, String id) throws EveBackendClient.BackendException {
        client.putJsonRoot("/api/admin/reports/" + encode(id) + "/dismiss", token, new JSONObject());
    }

    public void deleteReport(String token, String id) throws EveBackendClient.BackendException {
        client.deleteJsonRoot("/api/admin/reports/" + encode(id), token);
    }

    public List<EveAdminModels.FeedbackMessageItem> fetchFeedbackMessages(String token) throws EveBackendClient.BackendException {
        JSONObject root = client.getJsonRoot("/api/feedback/messages", token);
        return EveAdminModels.FeedbackMessageItem.parseList(root.optJSONArray("data"));
    }

    public void replyFeedbackMessage(String token, String id, String reply) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("reply", reply);
        } catch (JSONException ignored) {
        }
        client.postJsonRoot("/api/feedback/messages/" + encode(id) + "/reply", token, payload);
    }

    public void markFeedbackRead(String token, String id) throws EveBackendClient.BackendException {
        client.putJsonRoot("/api/feedback/messages/" + encode(id) + "/read", token, new JSONObject());
    }

    public void deleteFeedbackMessage(String token, String id) throws EveBackendClient.BackendException {
        client.deleteJsonRoot("/api/feedback/messages/" + encode(id), token);
    }

    public List<EveAdminModels.FeedbackPostItem> fetchFeedbackPosts(String token) throws EveBackendClient.BackendException {
        JSONObject root = client.getJsonRoot("/api/feedback/posts", token);
        return EveAdminModels.FeedbackPostItem.parseList(root.optJSONArray("data"));
    }

    public void createFeedbackPost(String token, String title, String message) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("title", title);
            payload.put("body", message);
        } catch (JSONException ignored) {
        }
        client.postJsonRoot("/api/feedback/posts", token, payload);
    }

    public void deleteFeedbackPost(String token, String id) throws EveBackendClient.BackendException {
        client.deleteJsonRoot("/api/feedback/posts/" + encode(id), token);
    }

    public JSONObject fetchAppContent(String token, String type) throws EveBackendClient.BackendException {
        return client.getJsonRoot("/api/app-content/" + encode(type), token).optJSONObject("data");
    }

    public void updateAppContent(String token, String type, String title, String body) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("title", title);
            payload.put("body", body);
        } catch (JSONException ignored) {
        }
        client.putJsonRoot("/api/app-content/" + encode(type), token, payload);
    }

    public EveAdminModels.AppConfigModel fetchAppConfig(String token) throws EveBackendClient.BackendException {
        JSONObject root = client.getJsonRoot("/api/app-config", token);
        return EveAdminModels.AppConfigModel.fromJson(root.optJSONObject("data"));
    }

    public EveAdminModels.AppConfigModel updateAppConfig(
            String token,
            int minVersionCode,
            boolean maintenanceMode,
            String maintenanceMessage,
            String defaultPublishMode) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("minimum_supported_version_code", Math.max(1, minVersionCode));
            payload.put("maintenance_mode", maintenanceMode);
            payload.put("maintenance_message", maintenanceMessage != null ? maintenanceMessage : "");
            payload.put("default_publish_mode", defaultPublishMode != null ? defaultPublishMode : "paused");
        } catch (JSONException ignored) {
        }
        JSONObject root = client.putJsonRoot("/api/admin/config", token, payload);
        return EveAdminModels.AppConfigModel.fromJson(root.optJSONObject("data"));
    }

    public List<EveAdminModels.ExamAnalyticsItem> fetchExamAnalytics(String token) throws EveBackendClient.BackendException {
        JSONObject root = client.getJsonRoot("/api/admin/analytics/exams", token);
        return EveAdminModels.ExamAnalyticsItem.parseList(root.optJSONArray("data"));
    }

    public List<EveAdminModels.CrashSignatureItem> fetchCrashSignatures(String token) throws EveBackendClient.BackendException {
        return fetchCrashSignatures(token, "open");
    }

    public List<EveAdminModels.CrashSignatureItem> fetchCrashSignatures(String token, String status) throws EveBackendClient.BackendException {
        String st = status != null && !status.trim().isEmpty() ? status.trim().toLowerCase(Locale.US) : "open";
        JSONObject root = client.getJsonRoot("/api/admin/system/crashes?status=" + encode(st), token);
        return EveAdminModels.CrashSignatureItem.parseList(root.optJSONArray("data"));
    }

    public EveAdminModels.CrashDetailBundle fetchCrashDetail(String token, String signature) throws EveBackendClient.BackendException {
        JSONObject root = client.getJsonRoot("/api/admin/system/crashes/" + encode(signature), token);
        JSONObject data = root != null ? root.optJSONObject("data") : null;
        if (data == null) {
            return new EveAdminModels.CrashDetailBundle(null, Collections.emptyList());
        }
        EveAdminModels.CrashSignatureItem sig = EveAdminModels.CrashSignatureItem.fromJson(data.optJSONObject("signature"));
        List<EveAdminModels.CrashIncidentItem> incidents = EveAdminModels.CrashIncidentItem.parseList(data.optJSONArray("incidents"));
        return new EveAdminModels.CrashDetailBundle(sig, incidents);
    }

    public void resolveCrashSignature(String token, String signature) throws EveBackendClient.BackendException {
        client.postJsonRoot("/api/admin/system/crashes/" + encode(signature) + "/resolve", token, new JSONObject());
    }

    public void deleteCrashSignature(String token, String signature) throws EveBackendClient.BackendException {
        client.deleteJsonRoot("/api/admin/system/crashes/" + encode(signature), token);
    }

    public EveAdminModels.SecurityEventsBundle fetchSecurityEvents(String token) throws EveBackendClient.BackendException {
        JSONObject root = client.getJsonRoot("/api/admin/system/security-events", token);
        JSONObject data = root != null ? root.optJSONObject("data") : null;
        if (data == null) {
            return new EveAdminModels.SecurityEventsBundle(Collections.emptyList(), 0, 0);
        }
        List<EveAdminModels.SecurityEventItem> events = EveAdminModels.SecurityEventItem.parseList(data.optJSONArray("events"));
        return new EveAdminModels.SecurityEventsBundle(
                events,
                data.optInt("securityCount", 0),
                data.optInt("errorCount", 0)
        );
    }

    public JSONObject fetchApiUsage(String token) throws EveBackendClient.BackendException {
        return client.getJsonRoot("/api/admin/api-usage", token).optJSONObject("data");
    }

    public JSONObject exportExams(String token) throws EveBackendClient.BackendException {
        return client.getJsonRoot("/api/admin/export/exams?limit=50&offset=0", token).optJSONObject("data");
    }

    public List<EveAdminModels.SystemServiceStatus> fetchSystemHealth(String token) throws EveBackendClient.BackendException {
        JSONObject data = client.getJsonRoot("/api/admin/system/health", token).optJSONObject("data");
        List<EveAdminModels.SystemServiceStatus> list = new ArrayList<>();
        if (data != null) {
            java.util.Iterator<String> keys = data.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject item = data.optJSONObject(k);
                if (item != null) {
                    list.add(new EveAdminModels.SystemServiceStatus(k, item.optString("status", "unknown"), item.optString("detail", "")));
                }
            }
        }
        return list;
    }

    public List<EveAdminModels.GenerationJobItem> fetchFailedJobs(String token) throws EveBackendClient.BackendException {
        JSONObject data = client.getJsonRoot("/api/admin/system/operations?offset=0", token).optJSONObject("data");
        return EveAdminModels.GenerationJobItem.parseList(data != null ? data.optJSONArray("jobs") : null);
    }

    public void retryGenerationJob(String token, String jobId) throws EveBackendClient.BackendException {
        client.postJsonRoot("/api/admin/system/jobs/" + encode(jobId) + "/retry", token, new JSONObject());
    }

    public JSONObject checkAiHealth(String token) throws EveBackendClient.BackendException {
        return client.postJsonRoot("/api/admin/ai-health", token, new JSONObject()).optJSONObject("data");
    }

    public String reformatExplanation(String token, String questionText, String explanation) throws EveBackendClient.BackendException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("questionText", questionText);
            payload.put("explanation", explanation);
        } catch (JSONException ignored) {
        }
        JSONObject data = client.postJsonRoot("/api/admin/questions/reformat-explanation", token, payload).optJSONObject("data");
        return data != null ? data.optString("explanation", explanation) : explanation;
    }

    public interface TokenWork {
        void run(String token) throws EveBackendClient.BackendException;
    }

    public void runWithAdminToken(ResultCallback<?> errorTarget, TokenWork work) {
        withAdminToken(errorTarget, work);
    }

    private void withAdminToken(ResultCallback<?> callback, TokenWork work) {
        withAdminTokenInternal(callback, work, true);
    }

    private void withAdminTokenInternal(ResultCallback<?> callback, TokenWork work, boolean allowRetryOn401) {
        executor.execute(() -> {
            FirebaseUser user = resolveFirebaseUser();
            if (user == null || user.isAnonymous()) {
                postError(callback, "Sign in with a verified EVE administrator account to access the Admin Control Center.", 401);
                return;
            }
            user.getIdToken(!allowRetryOn401).addOnCompleteListener(task -> {
                if (!task.isSuccessful() || task.getResult() == null || task.getResult().getToken() == null) {
                    if (allowRetryOn401) {
                        withAdminTokenInternal(callback, work, false);
                        return;
                    }
                    postError(callback, "Unable to refresh Firebase admin credentials. Sign in again.", 401);
                    return;
                }
                final String token = task.getResult().getToken();
                executor.execute(() -> {
                    try {
                        work.run(token);
                    } catch (EveBackendClient.BackendException e) {
                        if (e.unauthorized && allowRetryOn401) {
                            withAdminTokenInternal(callback, work, false);
                            return;
                        }
                        postError(callback, e.getMessage() != null ? e.getMessage() : "Admin operation failed.", e.httpStatus);
                    }
                });
            });
        });
    }

    public <T> void postSuccess(ResultCallback<T> callback, T value) {
        if (callback != null) {
            mainHandler.post(() -> callback.onSuccess(value));
        }
    }

    public void postError(ResultCallback<?> callback, String message, int httpStatus) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(message != null ? message : "Admin error", httpStatus));
        }
    }

    private static FirebaseUser resolveFirebaseUser() {
        try {
            if (FirebaseApp.getApps(org.telegram.messenger.ApplicationLoader.applicationContext).isEmpty()) {
                return null;
            }
            return FirebaseAuth.getInstance().getCurrentUser();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JSONObject buildQuestionPayload(
            String examId,
            String questionText,
            String optionA,
            String optionB,
            String optionC,
            String optionD,
            String correctAnswer,
            String explanation,
            String topic) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("examId", examId);
            payload.put("questionText", questionText);
            payload.put("optionA", optionA);
            payload.put("optionB", optionB);
            payload.put("optionC", optionC);
            payload.put("optionD", optionD);
            payload.put("correctAnswer", correctAnswer != null ? correctAnswer.trim().toUpperCase(Locale.US) : "A");
            payload.put("explanation", explanation != null ? explanation : "");
            payload.put("topic", topic != null ? topic : "");
        } catch (JSONException ignored) {
        }
        return payload;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value != null ? value : "", "UTF-8");
        } catch (UnsupportedEncodingException ignored) {
            return value != null ? value : "";
        }
    }
}
