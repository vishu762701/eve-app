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
        assertNotNull(org.telegram.ui.eve.admin.EveAdminUiChrome.class);
        assertNotNull(org.telegram.ui.eve.admin.EveAdminExamsFragment.class);
        assertNotNull(org.telegram.ui.eve.admin.EveAdminGeneratedTestsFragment.class);
        assertNotNull(org.telegram.ui.eve.admin.EveAdminStudentsFragment.class);
        assertNotNull(org.telegram.ui.eve.admin.EveAdminStudentDetailFragment.class);
        assertNotNull(org.telegram.ui.eve.admin.EveAdminContentAndCommsFragment.class);
        assertNotNull(org.telegram.ui.eve.admin.EveAdminInsightsAndSystemFragment.class);
        assertNotNull(org.telegram.ui.eve.admin.EveDebugDiagnosticsFragment.class);

        org.json.JSONArray studentsArr = new org.json.JSONArray()
                .put(new JSONObject()
                        .put("id", "u1")
                        .put("eveId", "EV-123456")
                        .put("email", "rahul@example.com")
                        .put("displayName", "Rahul Sharma")
                        .put("category", "UPSC")
                        .put("username", "rahul_upsc")
                        .put("phone", "+919876543210")
                        .put("bio", "Target CSE")
                        .put("photoUrl", "https://cdn.example.com/rahul.png")
                        .put("dob", "2001-05-12")
                        .put("createdAt", 1700000000000L)
                        .put("lastActive", 1700005000000L)
                        .put("disabled", false))
                .put(new JSONObject()
                        .put("id", "u2")
                        .put("email", "legacy@example.com")
                        .put("displayName", "Legacy Student")
                        .put("disabled", true));
        java.util.List<EveAdminModels.AdminStudentItem> parsedStudents = EveAdminModels.AdminStudentItem.parseList(studentsArr);
        assertEquals(2, parsedStudents.size());
        assertEquals("rahul_upsc", parsedStudents.get(0).username);
        assertEquals("+919876543210", parsedStudents.get(0).phone);
        assertEquals("Target CSE", parsedStudents.get(0).bio);
        assertEquals("https://cdn.example.com/rahul.png", parsedStudents.get(0).photoUrl);
        assertEquals("2001-05-12", parsedStudents.get(0).dob);
        assertEquals(1700000000000L, parsedStudents.get(0).createdAt);
        assertTrue(parsedStudents.get(0).withDisabled(true).disabled);
        assertEquals("", parsedStudents.get(1).username);
        assertEquals("", parsedStudents.get(1).photoUrl);
        assertEquals(0L, parsedStudents.get(1).createdAt);

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
        assertTrue(snapshot.loadDurationMs >= 1L);
        assertFalse(snapshot.isFromCache);
        assertTrue(snapshot.partialWarnings.isEmpty());

        JSONObject cachedJson = snapshot.toCacheJson();
        EveAdminModels.DashboardSnapshot restored = EveAdminModels.DashboardSnapshot.fromDashboardJson(cachedJson, 0L);
        assertNotNull(restored);
        assertEquals(500, restored.totalStudents);
        assertEquals(1194L, restored.totalRevenueInr);
        assertEquals(snapshot.loadDurationMs, restored.loadDurationMs);
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

    @Test
    public void restructuredAdminScreensAndChromeHelpers_existAndFormatCleanly() throws Exception {
        assertEquals("Exam Created", org.telegram.ui.eve.admin.EveAdminUiChrome.formatActionTitle("EXAM_CREATED"));
        assertEquals("Manage Admins Rights", org.telegram.ui.eve.admin.EveAdminUiChrome.formatActionTitle("manage_admins_rights"));

        String[] classes = new String[] {
                "org.telegram.ui.eve.admin.EveAdminControlCenterActivity",
                "org.telegram.ui.eve.admin.EveAdminExamsFragment",
                "org.telegram.ui.eve.admin.EveAdminExamDetailFragment",
                "org.telegram.ui.eve.admin.EveAdminGeneratedTestsFragment",
                "org.telegram.ui.eve.admin.EveAdminQuestionsFragment",
                "org.telegram.ui.eve.admin.EveAdminStudentsFragment",
                "org.telegram.ui.eve.admin.EveAdminStudentDetailFragment",
                "org.telegram.ui.eve.admin.EveAdminContentAndCommsFragment",
                "org.telegram.ui.eve.admin.EveAdminInsightsAndSystemFragment",
                "org.telegram.ui.eve.admin.EveAdminPremiumUsersFragment",
                "org.telegram.ui.eve.admin.EveAdminPaymentsFragment",
                "org.telegram.ui.eve.admin.EveAdminPremiumConfigFragment",
                "org.telegram.ui.eve.admin.EveAdminRolesFragment",
                "org.telegram.ui.eve.admin.EveAdminRoleEditFragment",
                "org.telegram.ui.eve.admin.EveAdminAuditLogFragment",
                "org.telegram.ui.eve.admin.EveAdminSystemHealthFragment",
                "org.telegram.ui.eve.admin.EveAdminExamAnalyticsFragment",
                "org.telegram.ui.eve.admin.EveAdminCrashReportsFragment",
                "org.telegram.ui.eve.admin.EveAdminApiUsageFragment",
                "org.telegram.ui.eve.admin.EveAdminMaintenanceConfigFragment",
                "org.telegram.ui.eve.admin.EveAdminBannerEditorFragment",
                "org.telegram.ui.eve.admin.EveAdminStudentDetailFragment$AttemptHistoryFragment",
                "org.telegram.ui.eve.premium.EvePremiumQrSheet",
                "org.telegram.ui.eve.exams.EveHomeBannerCarouselView",
                "org.telegram.ui.eve.exams.EveSubExamsFragment",
                "org.telegram.ui.eve.admin.EveAdminUiChrome$AutoSyncController",
                "org.telegram.ui.eve.admin.EveAdminPollsFragment",
                "org.telegram.ui.eve.admin.EveAdminPollCreateFragment",
                "org.telegram.ui.eve.admin.EveAdminBroadcastsFragment",
                "org.telegram.ui.eve.admin.EveAdminBannersFragment",
                "org.telegram.ui.eve.admin.EveAdminFlaggedReportsFragment",
                "org.telegram.ui.eve.admin.EveAdminFeedbackFragment",
                "org.telegram.ui.eve.admin.EveAdminContentEditorFragment"
        };
        for (String name : classes) {
            assertNotNull(Class.forName(name));
        }
    }

    @Test
    public void adminPollContracts_parseVoteCountsAndSendActiveBooleanOnToggle() throws Exception {
        org.json.JSONArray pollArr = new org.json.JSONArray("[{"
                + "\"id\":\"p-42\","
                + "\"question\":\"Best Daily Test Time?\","
                + "\"options\":[\"8 AM\",\"8 PM\",\"10 PM\"],"
                + "\"voteCounts\":{\"0\":5,\"1\":12,\"2\":3},"
                + "\"active\":true,"
                + "\"createdAt\":1700001000000,"
                + "\"endsAt\":0,"
                + "\"createdBy\":\"owner@example.com\""
                + "}]");
        java.util.List<EveAdminModels.PollItem> parsed = EveAdminModels.PollItem.parseList(pollArr);
        assertEquals(1, parsed.size());
        EveAdminModels.PollItem item = parsed.get(0);
        assertEquals("p-42", item.id);
        assertEquals(3, item.options.size());
        assertEquals(20, item.totalVotes);
        assertEquals(12, item.getVotesForOption(1));
        assertTrue(item.active);
        assertEquals("owner@example.com", item.createdBy);

        Map<String, String> captured = new HashMap<>();
        EveBackendClient client = new EveBackendClient("https://eve-backend.anyqueairdrop.workers.dev", (method, url, token, body) -> {
            captured.put("method", method);
            captured.put("url", url);
            captured.put("body", body);
            return new EveBackendClient.HttpResponse(200, "{\"success\":true}");
        });
        EveAdminRepository repo = new EveAdminRepository(client);
        repo.setPollActive("admin-jwt", "p-42", false);
        assertEquals("PUT", captured.get("method"));
        assertTrue(captured.get("url").endsWith("/api/polls/p-42/status"));
        JSONObject sent = new JSONObject(captured.get("body"));
        assertTrue(sent.has("active"));
        assertFalse(sent.has("isActive"));
        assertFalse(sent.getBoolean("active"));
    }

    @Test
    public void adminAuditDeleteCrashResolveAndAnalyticsContracts_parseAndRouteCleanly() throws Exception {
        assertEquals(
                org.telegram.ui.eve.admin.EveAdminAuditLogFragment.CAT_EXAMS,
                org.telegram.ui.eve.admin.EveAdminAuditLogFragment.classifyCategory("EXAM_UPDATED")
        );
        assertEquals(
                org.telegram.ui.eve.admin.EveAdminAuditLogFragment.CAT_STUDENTS,
                org.telegram.ui.eve.admin.EveAdminAuditLogFragment.classifyCategory("PREMIUM_GRANTED")
        );
        assertEquals(
                org.telegram.ui.eve.admin.EveAdminAuditLogFragment.CAT_CONTENT,
                org.telegram.ui.eve.admin.EveAdminAuditLogFragment.classifyCategory("POLL_CREATED")
        );
        assertEquals(
                org.telegram.ui.eve.admin.EveAdminAuditLogFragment.CAT_SYSTEM,
                org.telegram.ui.eve.admin.EveAdminAuditLogFragment.classifyCategory("CONFIG_UPDATED")
        );

        EveAdminModels.ExamAnalyticsItem analytics = new EveAdminModels.ExamAnalyticsItem(
                "ex-9", "SSC CGL Mock", 42, 74.5, 96.0
        );
        assertEquals(96.0, analytics.highestScore, 0.01);

        JSONObject crashJson = new JSONObject("{"
                + "\"signature\":\"sig-abc\","
                + "\"exceptionClass\":\"IllegalStateException\","
                + "\"topFrame\":\"EveTestPlayerFragment.java:210\","
                + "\"sanitizedMessage\":\"Fragment not attached\","
                + "\"occurrenceCount\":5,"
                + "\"status\":\"open\","
                + "\"suggestedCause\":\"Lifecycle detach\","
                + "\"firstSeen\":1700000000000,"
                + "\"lastSeen\":1700000500000,"
                + "\"plainEnglishSummary\":\"Screen closed before timer finished.\","
                + "\"whereItHappened\":\"Exam player screen\""
                + "}");
        EveAdminModels.CrashSignatureItem crashItem = EveAdminModels.CrashSignatureItem.fromJson(crashJson);
        assertNotNull(crashItem);
        assertFalse(crashItem.isResolved());
        assertEquals("Screen closed before timer finished.", crashItem.plainEnglishSummary);
        assertEquals("Exam player screen", crashItem.whereItHappened);

        java.util.List<String> calls = new java.util.ArrayList<>();
        EveBackendClient client = new EveBackendClient("https://eve-backend.anyqueairdrop.workers.dev", (method, url, token, body) -> {
            calls.add(method + " " + url);
            if ("DELETE".equals(method) && url.contains("/api/admin/audit-log")) {
                return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"deletedCount\":7}");
            }
            if (url.contains("/api/admin/system/crashes") && "GET".equals(method)) {
                return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":[" + crashJson.toString() + "]}");
            }
            if (url.endsWith("/api/admin/system/security-events")) {
                return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":{\"events\":[],\"securityCount\":0,\"errorCount\":0}}");
            }
            if (url.contains("/api/admin/users/stu-1")) {
                return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":{\"id\":\"stu-1\",\"displayName\":\"Riya\",\"email\":\"riya@example.com\"}}");
            }
            return new EveBackendClient.HttpResponse(200, "{\"success\":true}");
        });
        EveAdminRepository repo = new EveAdminRepository(client);
        assertEquals(7, repo.deleteAuditLogs("owner-jwt", "exams"));
        assertTrue(calls.get(calls.size() - 1).contains("/api/admin/audit-log?category=exams"));

        assertEquals(1, repo.fetchCrashSignatures("admin-jwt", "open").size());
        repo.resolveCrashSignature("admin-jwt", "sig-abc");
        assertTrue(calls.get(calls.size() - 1).startsWith("POST "));
        assertTrue(calls.get(calls.size() - 1).contains("/api/admin/system/crashes/sig-abc/resolve"));

        repo.deleteCrashSignature("owner-jwt", "sig-abc");
        assertTrue(calls.get(calls.size() - 1).startsWith("DELETE "));
        assertTrue(calls.get(calls.size() - 1).contains("/api/admin/system/crashes/sig-abc"));

        assertNotNull(repo.fetchSecurityEvents("admin-jwt"));
        assertTrue(calls.get(calls.size() - 1).endsWith("/api/admin/system/security-events"));

        EveAdminModels.AdminStudentItem student = repo.fetchStudentById("admin-jwt", "stu-1", "Fallback");
        assertEquals("Riya", student.displayName);

        // Student Performance & Progress aggregation
        java.util.List<EveAdminModels.StudentAttemptItem> attempts = new java.util.ArrayList<>();
        attempts.add(new EveAdminModels.StudentAttemptItem("a1", "SSC", 60.0, 100, 65, 20, 1000L));
        attempts.add(new EveAdminModels.StudentAttemptItem("a2", "SSC", 70.0, 100, 75, 15, 2000L));
        attempts.add(new EveAdminModels.StudentAttemptItem("a3", "SSC", 90.0, 100, 92, 8, 3000L));
        org.telegram.ui.eve.admin.EveAdminStudentDetailFragment.PerformanceSummary perf =
                org.telegram.ui.eve.admin.EveAdminStudentDetailFragment.PerformanceSummary.compute(attempts, 4000L);
        assertEquals(3, perf.totalTests);
        assertEquals(90.0, perf.bestScore, 0.01);
        assertEquals(73.33, perf.averageScore, 0.05);
        assertTrue(perf.accuracyPercent > 80.0);
        assertTrue(perf.progressHeadline.contains("Improving"));

        // Exam avatar parsing & endpoints
        org.json.JSONArray examArr = new org.json.JSONArray("[{"
                + "\"id\":\"ex-7\",\"examName\":\"Banking PO\",\"category\":\"Banking\","
                + "\"avatarUrl\":\"/api/exams/ex-7/avatar?v=2\",\"avatarEmoji\":\"🎯\",\"avatarColor\":2"
                + "}]");
        java.util.List<EveAdminModels.AdminExamItem> exams = EveAdminModels.AdminExamItem.parseList(examArr);
        assertEquals(1, exams.size());
        assertEquals("/api/exams/ex-7/avatar?v=2", exams.get(0).avatarUrl);
        assertEquals("🎯", exams.get(0).avatarEmoji);
        assertEquals(2, exams.get(0).avatarColor);

        repo.setExamAvatarEmoji("admin-jwt", "ex-7", "🎯", 2);
        assertTrue(calls.get(calls.size() - 1).startsWith("POST "));
        assertTrue(calls.get(calls.size() - 1).endsWith("/api/exams/ex-7/avatar"));

        repo.deleteExamAvatar("admin-jwt", "ex-7");
        assertTrue(calls.get(calls.size() - 1).startsWith("DELETE "));
        assertTrue(calls.get(calls.size() - 1).endsWith("/api/exams/ex-7/avatar"));

        // Styled UPI QR matrix encoder produces valid high-EC ZXing BitMatrix in pure JVM
        com.google.zxing.common.BitMatrix styledQrMatrix = org.telegram.ui.eve.premium.EvePremiumRepository.encodeUpiQrMatrix(
                "upi://pay?pa=eve@upi&pn=EVE&am=99&cu=INR&tn=Premium");
        assertNotNull(styledQrMatrix);
        assertTrue(styledQrMatrix.getWidth() >= 21);
        assertEquals(styledQrMatrix.getWidth(), styledQrMatrix.getHeight());
        assertTrue(styledQrMatrix.get(0, 0));

        // Banner mediaType & StudentsPageResult summary parsing
        org.json.JSONArray bannerArr = new org.json.JSONArray("["
                + "{\"id\":\"b-1\",\"imageUrl\":\"/api/banners/b-1/media\",\"mediaType\":\"video\",\"active\":true,\"linkUrl\":\"\",\"linkLabel\":\"\"},"
                + "{\"id\":\"b-2\",\"imageUrl\":\"/api/banners/b-2/media\",\"mediaType\":\"image\",\"active\":false,\"linkUrl\":\"https://eve.app\",\"linkLabel\":\"Promo\"}"
                + "]");
        java.util.List<EveAdminModels.BannerItem> parsedBanners = EveAdminModels.BannerItem.parseList(bannerArr);
        assertEquals(2, parsedBanners.size());
        assertTrue(parsedBanners.get(0).isVideo());
        assertTrue(parsedBanners.get(0).active);
        assertFalse(parsedBanners.get(1).isVideo());
        assertFalse(parsedBanners.get(1).active);

        org.json.JSONArray usersArr = new org.json.JSONArray("["
                + "{\"id\":\"u-1\",\"displayName\":\"Karan\",\"isPremium\":true,\"isOnline\":true,\"disabled\":false}"
                + "]");
        java.util.List<EveAdminModels.AdminStudentItem> parsedUsers = EveAdminModels.AdminStudentItem.parseList(usersArr);
        assertEquals(1, parsedUsers.size());
        assertTrue(parsedUsers.get(0).isPremium);
        assertTrue(parsedUsers.get(0).isOnline);
    }

    @Test
    public void sixUpgradesContracts_coverAiPromptStudioTestAvatarsChannelAndFlagFix() throws Exception {
        ClassLoader cl = EveAdminControlCenterTest.class.getClassLoader();
        for (String cls : new String[] {
                "org.telegram.ui.eve.admin.EveTelegramAvatarPicker",
                "org.telegram.ui.eve.auth.EveMaintenanceFragment",
                "org.telegram.ui.eve.admin.EveAdminFlaggedQuestionFixFragment",
                "org.telegram.ui.eve.admin.EveAdminExamPromptFragment"
        }) {
            assertNotNull(Class.forName(cls, false, cl));
        }

        final java.util.List<String> requests = new java.util.ArrayList<>();
        final Map<String, String> bodies = new HashMap<>();
        EveBackendClient client = new EveBackendClient("https://eve-backend.anyqueairdrop.workers.dev", (method, url, token, body) -> {
            requests.add(method + " " + url);
            bodies.put(method + " " + url, body != null ? body : "");
            if (url.endsWith("/api/questions/gt-1_2")) {
                return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":{"
                        + "\"id\":\"gt-1_2\",\"examId\":\"ex-1\",\"questionText\":\"Old Q\","
                        + "\"optionA\":\"A1\",\"optionB\":\"B1\",\"optionC\":\"C1\",\"optionD\":\"D1\","
                        + "\"correctAnswer\":\"B\",\"explanation\":\"Because\",\"topic\":\"Polity\"}}");
            }
            if (url.endsWith("/api/admin/announcement-channel") || url.endsWith("/api/admin/announcement-channel/avatar")) {
                return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":{"
                        + "\"maintenanceMode\":false,\"maintenanceMessage\":\"Ok\","
                        + "\"minSupportedVersionCode\":1,\"defaultPublishMode\":\"paused\","
                        + "\"announcementChannelName\":\"EVE Official\","
                        + "\"announcementChannelAvatarUrl\":\"/api/announcement-channel/avatar?v=2\"}}");
            }
            if (url.endsWith("/api/generated-tests/generate-now")) {
                return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":{\"title\":\"SSC Bilingual 1\",\"status\":\"draft\"}}");
            }
            return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":{}}");
        });

        EveAdminRepository repo = new EveAdminRepository(client);

        // 1. AI Exam Prompt Studio contracts
        org.json.JSONArray examArr = new org.json.JSONArray("[{"
                + "\"id\":\"ex-9\",\"examName\":\"SSC GD\",\"category\":\"SSC\","
                + "\"aiPrompt\":\"Focus on PYQs\",\"aiLanguage\":\"bilingual\",\"aiDifficulty\":\"hard\""
                + "}]");
        java.util.List<EveAdminModels.AdminExamItem> exams = EveAdminModels.AdminExamItem.parseList(examArr);
        assertEquals(1, exams.size());
        assertEquals("Focus on PYQs", exams.get(0).aiPrompt);
        assertEquals("bilingual", exams.get(0).aiLanguage);
        assertEquals("hard", exams.get(0).aiDifficulty);
        EveAdminModels.AdminExamItem updatedExam = exams.get(0).withAiSettings("Hindi focus", "hindi", "medium");
        assertEquals("Hindi focus", updatedExam.aiPrompt);
        assertEquals("hindi", updatedExam.aiLanguage);
        assertEquals("medium", updatedExam.aiDifficulty);

        repo.saveExamAiSettings("admin-jwt", "ex-9", "Hindi focus", "hindi", "medium");
        assertTrue(requests.get(requests.size() - 1).equals("PUT https://eve-backend.anyqueairdrop.workers.dev/api/exams/ex-9"));
        JSONObject aiPayload = new JSONObject(bodies.get("PUT https://eve-backend.anyqueairdrop.workers.dev/api/exams/ex-9"));
        assertEquals("Hindi focus", aiPayload.getString("aiPrompt"));
        assertEquals("hindi", aiPayload.getString("aiLanguage"));
        assertEquals("medium", aiPayload.getString("aiDifficulty"));

        JSONObject genRes = repo.generateTestNow("admin-jwt", "ex-9", 25, "Mock 9", "Hindi focus", "bilingual", "hard");
        assertEquals("SSC Bilingual 1", genRes.getJSONObject("data").getString("title"));
        JSONObject genBody = new JSONObject(bodies.get("POST https://eve-backend.anyqueairdrop.workers.dev/api/generated-tests/generate-now"));
        assertEquals("bilingual", genBody.getString("aiLanguage"));
        assertEquals("hard", genBody.getString("aiDifficulty"));

        // 2. Mock Test avatar fields + delete route
        org.json.JSONArray testArr = new org.json.JSONArray("[{"
                + "\"id\":\"gt-1\",\"examId\":\"ex-9\",\"title\":\"Mock 9\",\"status\":\"live\","
                + "\"questionCount\":25,\"generatedAt\":1000,\"avatarUrl\":\"/api/generated-tests/gt-1/avatar?v=1\""
                + "}]");
        java.util.List<EveAdminModels.AdminGeneratedTestItem> tests = EveAdminModels.AdminGeneratedTestItem.parseList(testArr);
        assertEquals(1, tests.size());
        assertEquals("/api/generated-tests/gt-1/avatar?v=1", tests.get(0).avatarUrl);
        repo.deleteGeneratedTestAvatar("admin-jwt", "gt-1");
        assertEquals("DELETE https://eve-backend.anyqueairdrop.workers.dev/api/generated-tests/gt-1/avatar",
                requests.get(requests.size() - 1));

        // 3. Flagged Question Fixer contracts (Question Bank & Generated Test index ID)
        org.json.JSONArray repArr = new org.json.JSONArray("[{"
                + "\"id\":\"rep-1\",\"questionId\":\"gt-1_2\",\"examName\":\"SSC GD\","
                + "\"questionText\":\"Old Q\",\"reason\":\"Wrong Answer\",\"status\":\"pending\""
                + "}]");
        java.util.List<EveAdminModels.QuestionReportItem> reps = EveAdminModels.QuestionReportItem.parseList(repArr);
        assertEquals("gt-1_2", reps.get(0).questionId);

        EveAdminModels.AdminQuestionItem qItem = repo.fetchQuestionById("admin-jwt", "gt-1_2");
        assertNotNull(qItem);
        assertEquals("B", qItem.correctAnswer);

        repo.fixFlaggedQuestion("admin-jwt", "rep-1", "Fixed Q", "A", "B", "C", "D", "C", "Fixed exp", "Polity");
        assertEquals("PUT https://eve-backend.anyqueairdrop.workers.dev/api/admin/reports/rep-1/fix-question",
                requests.get(requests.size() - 1));
        repo.deleteFlaggedQuestion("admin-jwt", "rep-1");
        assertEquals("DELETE https://eve-backend.anyqueairdrop.workers.dev/api/admin/reports/rep-1/question",
                requests.get(requests.size() - 1));

        // 4. Announcements Channel Name & Avatar Admin routes
        EveAdminModels.AppConfigModel chCfg = repo.updateAnnouncementChannelName("admin-jwt", "EVE Official");
        assertEquals("EVE Official", chCfg.announcementChannelName);
        assertEquals("/api/announcement-channel/avatar?v=2", chCfg.announcementChannelAvatarUrl);
        repo.deleteAnnouncementChannelAvatar("admin-jwt");
        assertEquals("DELETE https://eve-backend.anyqueairdrop.workers.dev/api/admin/announcement-channel/avatar",
                requests.get(requests.size() - 1));
    }

    @Test
    public void adminUpgrades_cutoffsBulkCsvAnalyticsAndPremiumHub() throws Exception {
        ClassLoader cl = EveAdminControlCenterTest.class.getClassLoader();
        assertNotNull(Class.forName("org.telegram.ui.eve.admin.EveAdminPremiumHubFragment", false, cl));
        assertNotNull(Class.forName("org.telegram.ui.eve.admin.EveAdminAttemptsBarChartView", false, cl));
        assertNotNull(Class.forName("org.telegram.ui.eve.admin.EveAdminModels$QuestionAnalyticsItem", false, cl));

        // 1. AdminExamItem cutoffs, draft publishMode, and daily auto-gen fields
        org.json.JSONArray arr = new org.json.JSONArray("[{"
                + "\"id\":\"ex-77\",\"examName\":\"SSC CGL Tier 2\",\"category\":\"SSC\","
                + "\"publishMode\":\"draft\",\"autoGenerationEnabled\":true,\"autoGenTime\":\"07:15\","
                + "\"lastGeneratedDate\":\"2026-11-27\",\"lastGenerationStatus\":\"success\","
                + "\"cutoffs\":{\"General\":140,\"OBC\":132.5,\"SC\":118,\"ST\":110,\"EWS\":136}"
                + "}]");
        java.util.List<EveAdminModels.AdminExamItem> exams = EveAdminModels.AdminExamItem.parseList(arr);
        assertEquals(1, exams.size());
        EveAdminModels.AdminExamItem ex = exams.get(0);
        assertTrue(ex.isDraft());
        assertTrue(ex.autoGenerationEnabled);
        assertEquals("07:15", ex.autoGenTime);
        assertEquals("success", ex.lastGenerationStatus);
        assertEquals(Double.valueOf(132.5), ex.getCutoffForCategory("OBC"));

        // 2. Bulk import parser: JSON + CSV with quoted commas + invalid row detection
        EveAdminDialogs.BulkImportParseResult jsonRes = EveAdminDialogs.parseBulkQuestionsInput(
                "[{\"questionText\":\"Capital of France?\",\"optionA\":\"Rome\",\"optionB\":\"Paris\",\"optionC\":\"Berlin\",\"optionD\":\"Madrid\",\"correctAnswer\":\"B\"}]",
                "ex-77");
        assertEquals("JSON", jsonRes.format);
        assertEquals(1, jsonRes.validQuestions.length());
        assertEquals("ex-77", jsonRes.validQuestions.getJSONObject(0).getString("examId"));

        String csvSample = "questionText,optionA,optionB,optionC,optionD,correctAnswer,explanation,topic\n"
                + "\"2, 4, 6, ?\",7,8,9,10,B,Even sequence,Quant\n"
                + "\"Bad row missing options\",A,B\n";
        EveAdminDialogs.BulkImportParseResult csvRes = EveAdminDialogs.parseBulkQuestionsInput(csvSample, "ex-77");
        assertEquals("CSV", csvRes.format);
        assertEquals(1, csvRes.validQuestions.length());
        assertEquals("2, 4, 6, ?", csvRes.validQuestions.getJSONObject(0).getString("questionText"));
        assertEquals(1, csvRes.errors.size());

        // 3. Admin repository analytics ?days=7, question analytics, reassignSyllabus & updateExamSettings
        final java.util.List<String> urls = new java.util.ArrayList<>();
        final Map<String, String> bodies = new HashMap<>();
        EveBackendClient client = new EveBackendClient("https://eve-backend.anyqueairdrop.workers.dev", (method, url, token, body) -> {
            urls.add(method + " " + url);
            bodies.put(method + " " + url, body != null ? body : "");
            if (url.contains("/api/admin/analytics/questions")) {
                return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":[{"
                        + "\"id\":\"qa-1\",\"examId\":\"ex-77\",\"examName\":\"SSC CGL Tier 2\","
                        + "\"questionId\":\"q-1\",\"questionNumber\":3,\"questionText\":\"Hard Q?\","
                        + "\"topic\":\"Quant\",\"attempts\":20,\"correct\":4,\"wrong\":14,\"unattempted\":2,"
                        + "\"wrongRate\":70.0,\"accuracy\":20.0}]}");
            }
            if (url.contains("/api/admin/analytics/exams")) {
                return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":[{"
                        + "\"examId\":\"ex-77\",\"examName\":\"SSC CGL Tier 2\",\"category\":\"SSC\","
                        + "\"attemptCount\":18,\"averageScore\":112.5,\"highestScore\":145.0}]}");
            }
            return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":{}}");
        });

        EveAdminRepository repo = new EveAdminRepository(client);
        java.util.List<EveAdminModels.ExamAnalyticsItem> analytics7 = repo.fetchExamAnalytics("tok", 7);
        assertEquals(1, analytics7.size());
        assertTrue(urls.get(urls.size() - 1).endsWith("/api/admin/analytics/exams?days=7"));

        java.util.List<EveAdminModels.QuestionAnalyticsItem> qAnalytics = repo.fetchQuestionAnalytics("tok", "ex-77");
        assertEquals(1, qAnalytics.size());
        assertEquals(70.0, qAnalytics.get(0).wrongRate, 0.01);
        assertEquals("Hard Q?", qAnalytics.get(0).questionText);

        repo.reassignSyllabus("tok", "ex-77", "ex-88", "/api/exams/ex-77/syllabus", "syllabus.pdf");
        JSONObject reassignPayload = new JSONObject(bodies.get("POST https://eve-backend.anyqueairdrop.workers.dev/api/exams/ex-77/syllabus/reassign"));
        assertEquals("ex-88", reassignPayload.getString("targetExamId"));
        assertEquals("/api/exams/ex-77/syllabus", reassignPayload.getString("expectedSyllabusUrl"));
        assertEquals("syllabus.pdf", reassignPayload.getString("expectedSyllabusFileName"));

        java.util.Map<String, Double> nextCutoffs = new java.util.LinkedHashMap<>();
        nextCutoffs.put("General", 145.0);
        repo.updateExamSettings("tok", "ex-77", "SSC CGL Tier 2", "SSC", 60, 30, "0.5", "draft", nextCutoffs, true, "08:00");
        JSONObject updatePayload = new JSONObject(bodies.get("PUT https://eve-backend.anyqueairdrop.workers.dev/api/exams/ex-77"));
        assertEquals("draft", updatePayload.getString("publishMode"));
        assertEquals(true, updatePayload.getBoolean("autoGenEnabled"));
        assertEquals("08:00", updatePayload.getString("autoGenTime"));
        assertEquals(145.0, updatePayload.getJSONObject("cutoffs").getDouble("General"), 0.01);
    }
}
