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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
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

    public void startOrResumeAttempt(
            String attemptKey,
            String sourceExamId,
            String generatedTestId,
            String resumeAttemptId,
            ApiCallback<EveExamModels.StartAttemptResult> callback) {
        withFreshToken(callback, token -> {
            JSONObject payload = new JSONObject();
            payload.put("examId", attemptKey);
            if (resumeAttemptId != null && !resumeAttemptId.trim().isEmpty()) {
                payload.put("resumeAttemptId", resumeAttemptId.trim());
            }
            JSONObject root = backendClient.postJsonRoot("/api/attempts/start", token, payload);
            JSONObject data = root.optJSONObject("data");
            EveExamModels.StartAttemptResult parsed = parseStartAttemptResult(data, sourceExamId);
            if (!parsed.alreadySubmitted && parsed.questions.isEmpty() && generatedTestId != null && !generatedTestId.trim().isEmpty()) {
                JSONObject detailRoot = backendClient.getJsonRoot("/api/generated-tests/" + urlEncode(generatedTestId.trim()), token);
                JSONObject detailData = detailRoot.optJSONObject("data");
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

    public void loadLeaderboard(String examIdOrOverall, ApiCallback<EveExamModels.LeaderboardBundle> callback) {
        final String target = (examIdOrOverall == null || examIdOrOverall.trim().isEmpty())
                ? "overall"
                : examIdOrOverall.trim();
        withFreshToken(callback, token -> {
            String encoded = urlEncode(target);
            JSONObject lbRoot = backendClient.getJsonRoot("/api/leaderboard?examId=" + encoded + "&limit=50", token);
            List<EveExamModels.LeaderboardEntry> entries = parseLeaderboardEntries(lbRoot.optJSONArray("data"));

            EveExamModels.LeaderboardStats stats = null;
            if ("overall".equals(target)) {
                try {
                    JSONObject rankRoot = backendClient.getJsonRoot("/api/leaderboard/rank?examId=overall", token);
                    JSONObject rankData = rankRoot.optJSONObject("data");
                    if (rankData != null) {
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
                    stats = parseLeaderboardStats(statsRoot.optJSONObject("data"));
                } catch (Exception ignored) {
                }
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

    public void loadBroadcasts(ApiCallback<List<EveExamModels.BroadcastItem>> callback) {
        withFreshToken(callback, token -> {
            JSONObject root = backendClient.getJsonRoot("/api/broadcasts?limit=50", token);
            JSONArray arr = root.optJSONArray("data");
            List<EveExamModels.BroadcastItem> items = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) continue;
                    items.add(new EveExamModels.BroadcastItem(
                            obj.optString("id", ""),
                            obj.optString("title", "EVE Update"),
                            obj.optString("message", ""),
                            obj.optString("type", "announcement"),
                            obj.optLong("sentAt", 0L)
                    ));
                }
            }
            postComplete(callback, items, "");
        });
    }

    private <T> void withFreshToken(ApiCallback<T> callback, TokenTask task) {
        EveAuthManager authManager = EveAuthManager.getInstance();
        authManager.ensureInitialized();
        FirebaseUser user = authManager.getCurrentUser();
        if (user == null) {
            postComplete(callback, null, "Sign in to EVE Cloud to continue.");
            return;
        }
        user.getIdToken(false)
                .addOnSuccessListener(result -> {
                    String token = result != null ? result.getToken() : null;
                    if (token == null || token.trim().isEmpty()) {
                        postComplete(callback, null, "Authentication token unavailable.");
                        return;
                    }
                    ioExecutor.execute(() -> {
                        try {
                            task.execute(token);
                        } catch (Throwable t) {
                            String msg = t.getMessage() != null && !t.getMessage().trim().isEmpty()
                                    ? t.getMessage()
                                    : "Request to EVE Cloud failed.";
                            postComplete(callback, null, msg);
                        }
                    });
                })
                .addOnFailureListener(e -> postComplete(callback, null, "Could not verify EVE Cloud session."));
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
            String qId = obj.optString("id", "");
            if (qId.trim().isEmpty() && fallbackTestId != null && !fallbackTestId.trim().isEmpty()) {
                qId = fallbackTestId.trim() + "_" + i;
            }
            list.add(new EveExamModels.TestQuestion(
                    qId,
                    obj.optString("examId", fallbackExamId != null ? fallbackExamId : ""),
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
                    obj.optString("topic", "")
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
