package org.telegram.ui.eve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.telegram.ui.eve.admin.EveAdminConfig;
import org.telegram.ui.eve.admin.EveAdminModels;
import org.telegram.ui.eve.admin.EveAdminRepository;
import org.telegram.ui.eve.auth.EveBackendClient;
import org.telegram.ui.eve.auth.EveUserProfile;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Contract, RBAC, and repository regression tests for Mission 7 (EVE Admin Control Center).
 */
@RunWith(JUnit4.class)
public class EveAdminControlCenterTest {

    @Test
    public void rbacPolicy_requiresVerifiedServerAdminAndRespectsGranularRights() {
        EveUserProfile owner = new EveUserProfile(
                "uid-1", "EVE0001", "Owner", "owner@example.com", "", "google.com",
                true, EveUserProfile.STATUS_ACTIVE, true, "owner", "General", "", 1L, 1L, 1L, true);
        EveUserProfile unverifiedAdmin = new EveUserProfile(
                "uid-2", "EVE0002", "Unverified", "admin@example.com", "", "password",
                false, EveUserProfile.STATUS_UNVERIFIED, true, "", "General", "", 1L, 1L, 1L, true);
        EveUserProfile student = new EveUserProfile(
                "uid-3", "EVE0003", "Student", "student@example.com", "", "google.com",
                true, EveUserProfile.STATUS_ACTIVE, false, "", "General", "", 1L, 1L, 1L, true);

        assertTrue(EveAdminConfig.isServerAdminProfile(owner));
        assertFalse(EveAdminConfig.isServerAdminProfile(unverifiedAdmin));
        assertFalse(EveAdminConfig.isServerAdminProfile(student));
        // Signed-out / student runtime sessions must never expose Admin Control Center even in debug builds.
        assertFalse(EveAdminConfig.isAdminAvailable());

        EveAdminModels.AdminProfile subAdmin = new EveAdminModels.AdminProfile(
                "mod@example.com", false, "Exam Mod",
                Collections.singleton(EveAdminModels.RIGHT_MANAGE_EXAMS));
        assertTrue(EveAdminConfig.hasRight(subAdmin, EveAdminModels.RIGHT_MANAGE_EXAMS));
        assertFalse(EveAdminConfig.hasRight(subAdmin, EveAdminModels.RIGHT_MANAGE_PREMIUM));
        assertFalse(EveAdminConfig.hasRight(subAdmin, EveAdminModels.RIGHT_MANAGE_ADMINS_RIGHTS));

        EveAdminModels.AdminProfile ownerAdmin = new EveAdminModels.AdminProfile(
                "owner@example.com", true, "Owner", Collections.emptySet());
        assertTrue(EveAdminConfig.hasRight(ownerAdmin, EveAdminModels.RIGHT_MANAGE_PREMIUM));
        assertTrue(EveAdminConfig.hasRight(ownerAdmin, EveAdminModels.RIGHT_MANAGE_ADMINS_RIGHTS));
    }

    @Test
    public void dashboardSnapshotAndAdminMutations_bindRealWorkerContracts() throws Exception {
        Map<String, String> recordedRequests = new HashMap<>();
        EveBackendClient client = new EveBackendClient("https://eve-backend.anyqueairdrop.workers.dev", (method, url, token, body) -> {
            assertEquals("admin-jwt", token);
            recordedRequests.put(method + " " + url, body != null ? body : "");

            if ("GET".equals(method) && url.endsWith("/api/admin/me")) {
                return new EveBackendClient.HttpResponse(200,
                        "{\"success\":true,\"data\":{\"email\":\"owner@example.com\",\"isOwner\":true,\"customTitle\":\"Owner\",\"rights\":[\"manage_exams\",\"manage_users\",\"manage_premium\",\"view_analytics_logs\"]}}");
            }
            if ("GET".equals(method) && url.endsWith("/api/exams")) {
                return new EveBackendClient.HttpResponse(200,
                        "{\"success\":true,\"data\":[{\"id\":\"ex1\",\"examName\":\"SSC CGL\",\"category\":\"SSC\",\"timeLimitMinutes\":60,\"questionCount\":100,\"publishMode\":\"live\"},{\"id\":\"ex2\",\"examName\":\"Draft UPSC\",\"category\":\"UPSC\",\"timeLimitMinutes\":120,\"questionCount\":100,\"publishMode\":\"paused\"}]}");
            }
            if ("GET".equals(method) && url.endsWith("/api/generated-tests")) {
                return new EveBackendClient.HttpResponse(200,
                        "{\"success\":true,\"data\":[{\"id\":\"gt1\",\"examId\":\"ex1\",\"examName\":\"SSC CGL\",\"testNumber\":\"Mock 1\",\"title\":\"Full Mock 1\",\"status\":\"live\",\"questionCount\":100,\"generatedAt\":1700000000000},{\"id\":\"gt2\",\"examId\":\"ex1\",\"examName\":\"SSC CGL\",\"testNumber\":\"Mock 2\",\"title\":\"Draft Mock 2\",\"status\":\"draft\",\"questionCount\":100,\"generatedAt\":1700000100000}]}");
            }
            if ("GET".equals(method) && url.endsWith("/api/admin/stats/users")) {
                return new EveBackendClient.HttpResponse(200,
                        "{\"success\":true,\"data\":{\"totalUsers\":420,\"onlineUsers\":37}}");
            }
            if ("GET".equals(method) && url.endsWith("/api/admin/analytics/exams")) {
                return new EveBackendClient.HttpResponse(200,
                        "{\"success\":true,\"data\":[{\"examId\":\"ex1\",\"attemptCount\":185}]}");
            }
            if ("GET".equals(method) && url.contains("/api/admin/audit-log")) {
                return new EveBackendClient.HttpResponse(200,
                        "{\"success\":true,\"data\":[{\"id\":\"aud1\",\"actionType\":\"EXAM_CREATED\",\"description\":\"Created SSC CGL\",\"adminEmail\":\"owner@example.com\",\"timestamp\":1700000200000}]}");
            }
            if ("GET".equals(method) && url.contains("/api/admin/premium/transactions")) {
                return new EveBackendClient.HttpResponse(200,
                        "{\"success\":true,\"data\":[{\"orderId\":\"ord1\",\"userEmail\":\"s1@test.com\",\"planName\":\"EVE Premium\",\"amount\":199,\"currency\":\"INR\",\"paymentMethod\":\"UPI\",\"status\":\"PAID\",\"createdAt\":1700000300000},{\"orderId\":\"ord2\",\"userEmail\":\"s2@test.com\",\"planName\":\"EVE Premium\",\"amount\":199,\"currency\":\"INR\",\"paymentMethod\":\"QR\",\"status\":\"PENDING\",\"createdAt\":1700000400000}]}");
            }
            if ("POST".equals(method) && url.endsWith("/api/generated-tests/generate-now")) {
                return new EveBackendClient.HttpResponse(200,
                        "{\"success\":true,\"data\":{\"id\":\"gt3\",\"title\":\"SSC Full Mock 3\",\"status\":\"draft\"}}");
            }
            return new EveBackendClient.HttpResponse(200, "{\"success\":true}");
        });

        EveAdminRepository repo = new EveAdminRepository(client);
        EveAdminModels.DashboardSnapshot snapshot = repo.loadDashboardSnapshot("admin-jwt");

        assertEquals(420, snapshot.totalStudents);
        assertEquals(37, snapshot.activeStudents);
        assertEquals(2, snapshot.totalExams);
        assertEquals(1, snapshot.publishedExams);
        assertEquals(1, snapshot.draftExams);
        assertEquals(2, snapshot.totalTests);
        assertEquals(1, snapshot.liveTests);
        assertEquals(1, snapshot.draftTests);
        assertEquals(185, snapshot.completedAttempts);
        assertEquals(199L, snapshot.totalRevenueInr);
        assertEquals(1, snapshot.paidTransactions);
        assertEquals(2, snapshot.totalTransactions);
        assertEquals(1, snapshot.recentActions.size());

        JSONObject gen = repo.generateTestNow("admin-jwt", "ex1", 50, "Mock 3", "Focus Polity");
        assertTrue(gen.optBoolean("success"));
        assertEquals("SSC Full Mock 3", gen.optJSONObject("data").optString("title"));
        assertEquals(1, snapshot.pendingTransactions);
        assertTrue(org.telegram.ui.eve.exams.EveExamDialogsMapper.filterExams(
                Collections.singletonList(new org.telegram.ui.eve.exams.EveExamModels.ExamSummary(
                        "ex-arch", "Archived Exam", 60, "SSC", 50, "Mock 1", "0", "", "archived", 0L, 1, "Mock 1", 0)),
                "").isEmpty());
        assertNotNull(org.telegram.ui.eve.admin.EveAdminExamsFragment.class);
        assertNotNull(org.telegram.ui.eve.admin.EveAdminContentAndCommsFragment.class);
        assertNotNull(org.telegram.ui.eve.admin.EveAdminInsightsAndSystemFragment.class);
        assertNotNull(org.telegram.ui.eve.admin.EveDebugDiagnosticsFragment.class);

        EveAdminModels.PremiumConfigModel cfg = new EveAdminModels.PremiumConfigModel(
                true, "EVE Pro", 199, "INR", 90, false, "Full Access",
                Collections.singletonList("All Mocks"), true, true, 15, "eve@upi", "EVE");
        repo.updatePremiumConfig("admin-jwt", cfg);
        String putBody = recordedRequests.get("PUT https://eve-backend.anyqueairdrop.workers.dev/api/admin/premium/config");
        assertTrue(putBody.contains("\"merchantVpa\":\"eve@upi\""));
        assertTrue(putBody.contains("\"qrEnabled\":true"));
        assertTrue(putBody.contains("\"upiEnabled\":true"));
        assertFalse(putBody.toLowerCase().contains("webhook_secret"));

        repo.addAdmin("admin-jwt", "co@example.com", Arrays.asList("manage_exams", "manage_questions"), "Co-Admin");
        assertTrue(recordedRequests.containsKey("POST https://eve-backend.anyqueairdrop.workers.dev/api/admin/admins"));
    }

    @Test
    public void parallelDashboardSnapshot_propagatesUnauthorizedAndManagesCache() {
        EveBackendClient failingClient = new EveBackendClient("https://eve-backend.anyqueairdrop.workers.dev", (method, url, token, body) -> {
            if (url.endsWith("/api/admin/me")) {
                return new EveBackendClient.HttpResponse(401, "{\"success\":false,\"error\":\"Token expired\",\"code\":\"AUTH_REQUIRED\"}");
            }
            return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":[]}");
        });
        EveAdminRepository repo = new EveAdminRepository(failingClient);
        try {
            repo.loadDashboardSnapshot("expired-jwt");
            org.junit.Assert.fail("Expected BackendException for 401 /api/admin/me");
        } catch (EveBackendClient.BackendException expected) {
            assertTrue(expected.unauthorized);
            assertEquals(401, expected.httpStatus);
        }
        repo.invalidateCache();
        assertEquals(0L, repo.getCachedSnapshotAtMs());
    }

    @Test
    public void unifiedDashboardEndpoint_loadsInSingleRequestAndSupportsCacheAndPartialFallback() throws Exception {
        java.util.List<String> calls = new java.util.ArrayList<>();
        EveBackendClient unifiedClient = new EveBackendClient("https://eve-backend.anyqueairdrop.workers.dev", (method, url, token, body) -> {
            calls.add(method + " " + url);
            assertEquals("admin-jwt", token);
            if ("GET".equals(method) && url.endsWith("/api/admin/dashboard")) {
                return new EveBackendClient.HttpResponse(200,
                        "{\"success\":true,\"data\":{"
                                + "\"adminProfile\":{\"email\":\"owner@example.com\",\"isOwner\":true,\"customTitle\":\"Owner\",\"rights\":[\"manage_exams\",\"manage_users\",\"manage_premium\",\"view_analytics_logs\",\"moderate_feedback\"]},"
                                + "\"metrics\":{\"totalStudents\":500,\"activeStudents\":42,\"totalExams\":4,\"publishedExams\":3,\"draftExams\":1,\"totalTests\":12,\"liveTests\":10,\"draftTests\":2,\"totalAttempts\":99,\"completedAttempts\":99,\"averageScorePct\":68.4,\"totalTransactions\":8,\"paidTransactions\":6,\"pendingTransactions\":2,\"totalRevenueInr\":1194,\"activePremiumUsers\":6,\"pendingReports\":3},"
                                + "\"exams\":[{\"id\":\"ex1\",\"examName\":\"SSC CGL\",\"category\":\"SSC\",\"timeLimitMinutes\":60,\"questionCount\":100,\"publishMode\":\"live\"}],"
                                + "\"recentActions\":[{\"id\":\"a1\",\"actionType\":\"EXAM_CREATED\",\"description\":\"Created SSC CGL\",\"adminEmail\":\"owner@example.com\",\"timestamp\":1700000200000}],"
                                + "\"partialErrors\":[],"
                                + "\"loadedAt\":1700000500000,"
                                + "\"durationMs\":27"
                                + "}}");
            }
            return new EveBackendClient.HttpResponse(500, "{\"success\":false,\"error\":\"Unexpected extra call\"}");
        });

        EveAdminRepository repo = new EveAdminRepository(unifiedClient);
        EveAdminModels.DashboardSnapshot snapshot = repo.loadDashboardSnapshot("admin-jwt");
        assertEquals(1, calls.size());
        assertEquals("GET https://eve-backend.anyqueairdrop.workers.dev/api/admin/dashboard", calls.get(0));
        assertEquals(500, snapshot.totalStudents);
        assertEquals(42, snapshot.activeStudents);
        assertEquals(4, snapshot.totalExams);
        assertEquals(12, snapshot.totalTests);
        assertEquals(99, snapshot.completedAttempts);
        assertEquals(68.4, snapshot.averageScorePct, 0.01);
        assertEquals(1194L, snapshot.totalRevenueInr);
        assertEquals(2, snapshot.pendingTransactions);
        assertEquals(6, snapshot.activePremiumUsers);
        assertEquals(3, snapshot.pendingReports);
        assertEquals(27L, snapshot.loadDurationMs);
        assertFalse(snapshot.isFromCache);
        assertTrue(snapshot.partialWarnings.isEmpty());

        JSONObject cachedJson = snapshot.toCacheJson();
        EveAdminModels.DashboardSnapshot restored = EveAdminModels.DashboardSnapshot.fromDashboardJson(cachedJson, 0L);
        assertNotNull(restored);
        assertEquals(500, restored.totalStudents);
        assertEquals(1194L, restored.totalRevenueInr);
        assertTrue(restored.asCacheCopy(true).isFromCache);
        assertTrue(restored.asCacheCopy(true).isStale);

        // 404 from unified endpoint triggers legacy fallback and tolerates optional section failures
        EveBackendClient fallbackClient = new EveBackendClient("https://eve-backend.anyqueairdrop.workers.dev", (method, url, token, body) -> {
            if (url.endsWith("/api/admin/dashboard")) {
                return new EveBackendClient.HttpResponse(404, "{\"success\":false,\"error\":\"Route not found\"}");
            }
            if (url.endsWith("/api/admin/me")) {
                return new EveBackendClient.HttpResponse(200,
                        "{\"success\":true,\"data\":{\"email\":\"owner@example.com\",\"isOwner\":true,\"customTitle\":\"Owner\",\"rights\":[\"manage_users\",\"manage_premium\",\"view_analytics_logs\"]}}");
            }
            if (url.endsWith("/api/admin/stats/users")) {
                return new EveBackendClient.HttpResponse(500, "{\"success\":false,\"error\":\"Temporary D1 timeout\"}");
            }
            if (url.endsWith("/api/exams")) {
                return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":[{\"id\":\"ex1\",\"examName\":\"SSC\",\"publishMode\":\"live\"}]}");
            }
            if (url.endsWith("/api/generated-tests")) {
                return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":[]}");
            }
            if (url.contains("/api/admin/analytics/exams") || url.contains("/api/admin/audit-log")
                    || url.contains("/api/admin/premium/transactions") || url.contains("/api/admin/premium/users")
                    || url.contains("/api/admin/reports")) {
                return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":[]}");
            }
            return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":[]}");
        });

        EveAdminRepository fallbackRepo = new EveAdminRepository(fallbackClient);
        EveAdminModels.DashboardSnapshot partial = fallbackRepo.loadDashboardSnapshot("admin-jwt");
        assertEquals(1, partial.totalExams);
        assertEquals(0, partial.totalStudents);
        assertFalse(partial.partialWarnings.isEmpty());

        // 403 on unified dashboard rejects immediately without calling /api/admin/me
        java.util.List<String> authRejectedCalls = new java.util.ArrayList<>();
        EveBackendClient forbiddenClient = new EveBackendClient("https://eve-backend.anyqueairdrop.workers.dev", (method, url, token, body) -> {
            authRejectedCalls.add(url);
            return new EveBackendClient.HttpResponse(403, "{\"success\":false,\"error\":\"Forbidden\"}");
        });
        try {
            new EveAdminRepository(forbiddenClient).loadDashboardSnapshot("student-jwt");
            org.junit.Assert.fail("Expected BackendException for 403 /api/admin/dashboard");
        } catch (EveBackendClient.BackendException expected) {
            assertEquals(403, expected.httpStatus);
            assertEquals(1, authRejectedCalls.size());
        }
    }
}
