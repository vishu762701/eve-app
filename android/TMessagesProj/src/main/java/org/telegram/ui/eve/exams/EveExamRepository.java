package org.telegram.ui.eve.exams;

import android.os.Handler;
import android.os.Looper;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.ui.eve.auth.EveAuthManager;
import org.telegram.ui.eve.auth.EveBackendClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Authoritative repository loading available exams and published tests
 * from the EVE Cloudflare Worker + D1 backend.
 */
public final class EveExamRepository {

    public interface CatalogListener {
        void onExamCatalogUpdated(EveExamModels.ExamCatalogResult result);
    }

    public interface ExamTestsCallback {
        void onLoaded(List<EveExamModels.GeneratedTestSummary> tests, String errorMessage);
    }

    private static volatile EveExamRepository instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<CatalogListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final EveBackendClient backendClient;

    private volatile EveExamModels.ExamCatalogResult currentResult =
            new EveExamModels.ExamCatalogResult(EveExamModels.LoadState.LOADING, Collections.emptyList(), "");
    private volatile long lastSuccessLoadMs;

    private EveExamRepository() {
        this.backendClient = new EveBackendClient();
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
        user.getIdToken(false)
                .addOnSuccessListener(tokenResult -> {
                    String token = tokenResult != null ? tokenResult.getToken() : null;
                    if (token == null || token.trim().isEmpty()) {
                        loading.set(false);
                        publishResult(new EveExamModels.ExamCatalogResult(
                                EveExamModels.LoadState.AUTH_REQUIRED,
                                Collections.emptyList(),
                                "Sign in to EVE Cloud to refresh exams."));
                        return;
                    }
                    ioExecutor.execute(() -> {
                        try {
                            JSONArray examsJson = backendClient.fetchExamsJson(token);
                            JSONArray testsJson = null;
                            try {
                                testsJson = backendClient.fetchGeneratedTestsJson(token, null);
                            } catch (Exception ignored) {
                                // Exam list still renders even if generated-tests enrichment fails.
                            }
                            List<EveExamModels.ExamSummary> parsed = parseExamCatalog(examsJson, testsJson);
                            lastSuccessLoadMs = System.currentTimeMillis();
                            EveExamModels.LoadState state = parsed.isEmpty()
                                    ? EveExamModels.LoadState.EMPTY
                                    : EveExamModels.LoadState.LOADED;
                            loading.set(false);
                            publishResult(new EveExamModels.ExamCatalogResult(state, parsed, ""));
                        } catch (EveBackendClient.BackendException be) {
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
                    loading.set(false);
                    publishResult(new EveExamModels.ExamCatalogResult(
                            EveExamModels.LoadState.AUTH_REQUIRED,
                            currentResult.exams,
                            "Connect EVE Cloud account to load available exams."));
                });
    }

    public void loadTestsForExam(String examId, ExamTestsCallback callback) {
        EveAuthManager authManager = EveAuthManager.getInstance();
        authManager.ensureInitialized();
        FirebaseUser user = authManager.getCurrentUser();
        if (user == null) {
            mainHandler.post(() -> callback.onLoaded(Collections.emptyList(), "Sign in to EVE Cloud to view published tests."));
            return;
        }
        user.getIdToken(false)
                .addOnSuccessListener(tokenResult -> {
                    String token = tokenResult != null ? tokenResult.getToken() : null;
                    if (token == null || token.trim().isEmpty()) {
                        mainHandler.post(() -> callback.onLoaded(Collections.emptyList(), "Authentication token unavailable."));
                        return;
                    }
                    ioExecutor.execute(() -> {
                        try {
                            JSONArray arr = backendClient.fetchGeneratedTestsJson(token, examId);
                            List<EveExamModels.GeneratedTestSummary> parsed = parseGeneratedTests(arr);
                            mainHandler.post(() -> callback.onLoaded(parsed, ""));
                        } catch (Throwable t) {
                            mainHandler.post(() -> callback.onLoaded(Collections.emptyList(),
                                    t.getMessage() != null ? t.getMessage() : "Could not load tests."));
                        }
                    });
                })
                .addOnFailureListener(e -> mainHandler.post(() -> callback.onLoaded(Collections.emptyList(),
                        "Could not verify EVE Cloud session.")));
    }

    public static List<EveExamModels.ExamSummary> parseExamCatalog(JSONArray examsJson, JSONArray testsJson) {
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
                    dateSec
            );
            if (summary.isMainExam()) {
                list.add(summary);
            }
        }
        Collections.sort(list, Comparator.comparing(e -> e.examName.toLowerCase()));
        return list;
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
                    obj.optLong("availableFrom", 0L)
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
