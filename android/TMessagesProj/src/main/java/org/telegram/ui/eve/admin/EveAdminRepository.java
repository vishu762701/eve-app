package org.telegram.ui.eve.admin;

import android.os.Handler;
import android.os.Looper;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.ui.eve.auth.EveBackendClient;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private final EveBackendClient client;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

    public void loadDashboardAsync(ResultCallback<EveAdminModels.DashboardSnapshot> callback) {
        withAdminToken(callback, token -> {
            EveAdminModels.DashboardSnapshot snapshot = loadDashboardSnapshot(token);
            postSuccess(callback, snapshot);
        });
    }

    public EveAdminModels.DashboardSnapshot loadDashboardSnapshot(String token) throws EveBackendClient.BackendException {
        JSONObject meRoot = client.getJsonRoot("/api/admin/me", token);
        EveAdminModels.AdminProfile profile = EveAdminModels.AdminProfile.fromJson(meRoot.optJSONObject("data"));

        List<EveAdminModels.AdminExamItem> exams = EveAdminModels.AdminExamItem.parseList(client.fetchExamsJson(token));
        int publishedExams = 0;
        int draftExams = 0;
        for (EveAdminModels.AdminExamItem exam : exams) {
            if (exam.isPaused()) {
                draftExams++;
            } else {
                publishedExams++;
            }
        }

        List<EveAdminModels.AdminGeneratedTestItem> tests = EveAdminModels.AdminGeneratedTestItem.parseList(client.fetchGeneratedTestsJson(token, null));
        int liveTests = 0;
        int draftTests = 0;
        for (EveAdminModels.AdminGeneratedTestItem test : tests) {
            if (test.isLive()) {
                liveTests++;
            } else {
                draftTests++;
            }
        }

        int totalStudents = 0;
        int activeStudents = 0;
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_USERS)) {
            JSONObject userStats = client.getJsonRoot("/api/admin/stats/users", token).optJSONObject("data");
            if (userStats != null) {
                totalStudents = userStats.optInt("totalUsers", 0);
                activeStudents = userStats.optInt("onlineUsers", 0);
            }
        }

        int totalAttempts = 0;
        List<EveAdminModels.AuditLogEntry> recentActions = new ArrayList<>();
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_VIEW_ANALYTICS_LOGS)) {
            JSONArray examAnalytics = client.getJsonRoot("/api/admin/analytics/exams", token).optJSONArray("data");
            if (examAnalytics != null) {
                for (int i = 0; i < examAnalytics.length(); i++) {
                    JSONObject row = examAnalytics.optJSONObject(i);
                    if (row != null) {
                        totalAttempts += row.optInt("attemptCount", 0);
                    }
                }
            }
            JSONArray logs = client.getJsonRoot("/api/admin/audit-log?limit=15", token).optJSONArray("data");
            recentActions = EveAdminModels.AuditLogEntry.parseList(logs);
        }

        int totalTransactions = 0;
        int paidTransactions = 0;
        long totalRevenueInr = 0;
        if (EveAdminConfig.hasRight(profile, EveAdminModels.RIGHT_MANAGE_PREMIUM)) {
            List<EveAdminModels.PaymentTransactionItem> txList = fetchTransactions(token, 100);
            totalTransactions = txList.size();
            for (EveAdminModels.PaymentTransactionItem tx : txList) {
                if ("PAID".equalsIgnoreCase(tx.status)) {
                    paidTransactions++;
                    totalRevenueInr += tx.amount;
                }
            }
        }

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
                exams,
                tests,
                recentActions
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

    public interface TokenWork {
        void run(String token) throws EveBackendClient.BackendException;
    }

    public void runWithAdminToken(ResultCallback<?> errorTarget, TokenWork work) {
        withAdminToken(errorTarget, work);
    }

    private void withAdminToken(ResultCallback<?> callback, TokenWork work) {
        executor.execute(() -> {
            FirebaseUser user = resolveFirebaseUser();
            if (user == null || user.isAnonymous()) {
                postError(callback, "Sign in with a verified EVE administrator account to access the Admin Control Center.", 401);
                return;
            }
            user.getIdToken(true).addOnCompleteListener(task -> {
                if (!task.isSuccessful() || task.getResult() == null || task.getResult().getToken() == null) {
                    postError(callback, "Unable to refresh Firebase admin credentials. Sign in again.", 401);
                    return;
                }
                final String token = task.getResult().getToken();
                executor.execute(() -> {
                    try {
                        work.run(token);
                    } catch (EveBackendClient.BackendException e) {
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
