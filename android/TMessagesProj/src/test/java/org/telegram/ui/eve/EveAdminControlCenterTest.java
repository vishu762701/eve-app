package org.telegram.ui.eve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
