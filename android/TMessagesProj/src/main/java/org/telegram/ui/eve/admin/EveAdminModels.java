package org.telegram.ui.eve.admin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable data contracts and JSON parsers matching EVE Worker + D1 admin routes.
 */
public final class EveAdminModels {

    private EveAdminModels() {
    }

    public static final String RIGHT_MANAGE_EXAMS = "manage_exams";
    public static final String RIGHT_MANAGE_QUESTIONS = "manage_questions";
    public static final String RIGHT_MANAGE_GENERATED_TESTS = "manage_generated_tests";
    public static final String RIGHT_MANAGE_SYLLABUS = "manage_syllabus";
    public static final String RIGHT_MANAGE_USERS = "manage_users";
    public static final String RIGHT_MANAGE_ADMINS_RIGHTS = "manage_admins_rights";
    public static final String RIGHT_MANAGE_PREMIUM = "manage_premium";
    public static final String RIGHT_SEND_BROADCASTS = "send_broadcasts";
    public static final String RIGHT_MODERATE_FEEDBACK = "moderate_feedback";
    public static final String RIGHT_MANAGE_CONFIG = "manage_config_maintenance";
    public static final String RIGHT_MANAGE_CONTENT = "manage_content";
    public static final String RIGHT_VIEW_ANALYTICS_LOGS = "view_analytics_logs";
    public static final String RIGHT_EXPORT_DATA = "export_data";

    public static final String[] ALL_RIGHTS = new String[] {
            RIGHT_MANAGE_EXAMS,
            RIGHT_MANAGE_QUESTIONS,
            RIGHT_MANAGE_GENERATED_TESTS,
            RIGHT_MANAGE_SYLLABUS,
            RIGHT_MANAGE_USERS,
            RIGHT_MANAGE_ADMINS_RIGHTS,
            RIGHT_MANAGE_PREMIUM,
            RIGHT_SEND_BROADCASTS,
            RIGHT_MODERATE_FEEDBACK,
            RIGHT_MANAGE_CONFIG,
            RIGHT_MANAGE_CONTENT,
            RIGHT_VIEW_ANALYTICS_LOGS,
            RIGHT_EXPORT_DATA
    };

    public static final class AdminProfile {
        public final String email;
        public final boolean isOwner;
        public final String customTitle;
        public final Set<String> rights;

        public AdminProfile(String email, boolean isOwner, String customTitle, Set<String> rights) {
            this.email = email != null ? email : "";
            this.isOwner = isOwner;
            this.customTitle = customTitle != null && !customTitle.trim().isEmpty() ? customTitle.trim() : (isOwner ? "Owner" : "Admin");
            this.rights = rights != null ? Collections.unmodifiableSet(new HashSet<>(rights)) : Collections.emptySet();
        }

        public static AdminProfile fromJson(JSONObject data) {
            if (data == null) {
                return new AdminProfile("", false, "Admin", Collections.emptySet());
            }
            Set<String> rightsSet = new HashSet<>();
            JSONArray arr = data.optJSONArray("rights");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String r = arr.optString(i, "").trim();
                    if (!r.isEmpty()) {
                        rightsSet.add(r);
                    }
                }
            }
            return new AdminProfile(
                    clean(data, "email", ""),
                    data.optBoolean("isOwner", false),
                    clean(data, "customTitle", ""),
                    rightsSet
            );
        }
    }

    public static final class AdminExamItem {
        public final String id;
        public final String examName;
        public final String category;
        public final int timeLimitMinutes;
        public final int questionCount;
        public final String testNumber;
        public final String negativeMarkingText;
        public final String publishMode;
        public final String generationPrompt;

        public AdminExamItem(
                String id,
                String examName,
                String category,
                int timeLimitMinutes,
                int questionCount,
                String testNumber,
                String negativeMarkingText,
                String publishMode,
                String generationPrompt) {
            this.id = id != null ? id : "";
            this.examName = examName != null ? examName : "Untitled Exam";
            this.category = category != null && !category.isEmpty() ? category : "General";
            this.timeLimitMinutes = timeLimitMinutes;
            this.questionCount = questionCount;
            this.testNumber = testNumber != null && !testNumber.isEmpty() ? testNumber : "Test 1";
            this.negativeMarkingText = negativeMarkingText != null && !negativeMarkingText.isEmpty() ? negativeMarkingText : "0";
            this.publishMode = publishMode != null && !publishMode.isEmpty() ? publishMode.toLowerCase(Locale.US) : "inherit";
            this.generationPrompt = generationPrompt != null ? generationPrompt : "";
        }

        public boolean isPaused() {
            return "paused".equalsIgnoreCase(publishMode);
        }

        public static List<AdminExamItem> parseList(JSONArray arr) {
            List<AdminExamItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new AdminExamItem(
                        clean(o, "id", ""),
                        clean(o, "examName", "Untitled Exam"),
                        clean(o, "category", "General"),
                        o.optInt("timeLimitMinutes", 30),
                        o.optInt("questionCount", 20),
                        clean(o, "testNumber", "Test 1"),
                        clean(o, "negativeMarkingText", "0"),
                        clean(o, "publishMode", "inherit"),
                        clean(o, "generationPrompt", "")
                ));
            }
            return out;
        }
    }

    public static final class AdminGeneratedTestItem {
        public final String id;
        public final String examId;
        public final String examName;
        public final String testNumber;
        public final String title;
        public final String status;
        public final int questionCount;
        public final long generatedAtMs;
        public final String validationError;

        public AdminGeneratedTestItem(
                String id,
                String examId,
                String examName,
                String testNumber,
                String title,
                String status,
                int questionCount,
                long generatedAtMs,
                String validationError) {
            this.id = id != null ? id : "";
            this.examId = examId != null ? examId : "";
            this.examName = examName != null ? examName : "";
            this.testNumber = testNumber != null ? testNumber : "";
            this.title = title != null && !title.isEmpty() ? title : (testNumber != null ? testNumber : "Mock Test");
            this.status = status != null ? status.toLowerCase(Locale.US) : "draft";
            this.questionCount = questionCount;
            this.generatedAtMs = generatedAtMs;
            this.validationError = validationError != null ? validationError : "";
        }

        public boolean isLive() {
            return "live".equalsIgnoreCase(status) || "published".equalsIgnoreCase(status);
        }

        public static List<AdminGeneratedTestItem> parseList(JSONArray arr) {
            List<AdminGeneratedTestItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new AdminGeneratedTestItem(
                        clean(o, "id", ""),
                        clean(o, "examId", ""),
                        clean(o, "examName", ""),
                        clean(o, "testNumber", ""),
                        clean(o, "title", ""),
                        clean(o, "status", "draft"),
                        o.optInt("questionCount", 0),
                        o.optLong("generatedAt", 0L),
                        clean(o, "validationError", "")
                ));
            }
            return out;
        }
    }

    public static final class AdminQuestionItem {
        public final String id;
        public final String examId;
        public final String questionText;
        public final String optionA;
        public final String optionB;
        public final String optionC;
        public final String optionD;
        public final String correctAnswer;
        public final String explanation;
        public final String topic;

        public AdminQuestionItem(
                String id,
                String examId,
                String questionText,
                String optionA,
                String optionB,
                String optionC,
                String optionD,
                String correctAnswer,
                String explanation,
                String topic) {
            this.id = id != null ? id : "";
            this.examId = examId != null ? examId : "";
            this.questionText = questionText != null ? questionText : "";
            this.optionA = optionA != null ? optionA : "";
            this.optionB = optionB != null ? optionB : "";
            this.optionC = optionC != null ? optionC : "";
            this.optionD = optionD != null ? optionD : "";
            this.correctAnswer = correctAnswer != null ? correctAnswer : "";
            this.explanation = explanation != null ? explanation : "";
            this.topic = topic != null ? topic : "";
        }

        public static List<AdminQuestionItem> parseList(JSONArray arr) {
            List<AdminQuestionItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new AdminQuestionItem(
                        clean(o, "id", ""),
                        clean(o, "examId", ""),
                        clean(o, "questionText", ""),
                        clean(o, "optionA", ""),
                        clean(o, "optionB", ""),
                        clean(o, "optionC", ""),
                        clean(o, "optionD", ""),
                        clean(o, "correctAnswer", ""),
                        clean(o, "explanation", ""),
                        clean(o, "topic", "")
                ));
            }
            return out;
        }
    }

    public static final class AdminStudentItem {
        public final String id;
        public final String eveId;
        public final String email;
        public final String displayName;
        public final String category;
        public final long lastActive;
        public final boolean disabled;

        public AdminStudentItem(
                String id,
                String eveId,
                String email,
                String displayName,
                String category,
                long lastActive,
                boolean disabled) {
            this.id = id != null ? id : "";
            this.eveId = eveId != null ? eveId : "";
            this.email = email != null ? email : "";
            this.displayName = displayName != null && !displayName.isEmpty() ? displayName : "Student";
            this.category = category != null ? category : "General";
            this.lastActive = lastActive;
            this.disabled = disabled;
        }

        public static List<AdminStudentItem> parseList(JSONArray arr) {
            List<AdminStudentItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new AdminStudentItem(
                        clean(o, "id", ""),
                        clean(o, "eveId", ""),
                        clean(o, "email", ""),
                        clean(o, "displayName", "Student"),
                        clean(o, "category", "General"),
                        o.optLong("lastActive", 0L),
                        o.optBoolean("disabled", false)
                ));
            }
            return out;
        }
    }

    public static final class StudentAttemptItem {
        public final String id;
        public final String examName;
        public final double score;
        public final int total;
        public final int correct;
        public final int wrong;
        public final long timestamp;

        public StudentAttemptItem(String id, String examName, double score, int total, int correct, int wrong, long timestamp) {
            this.id = id != null ? id : "";
            this.examName = examName != null ? examName : "Mock Test";
            this.score = score;
            this.total = total;
            this.correct = correct;
            this.wrong = wrong;
            this.timestamp = timestamp;
        }

        public static List<StudentAttemptItem> parseList(JSONArray arr) {
            List<StudentAttemptItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new StudentAttemptItem(
                        clean(o, "id", ""),
                        clean(o, "examName", "Mock Test"),
                        o.optDouble("score", 0.0),
                        o.optInt("total", 0),
                        o.optInt("correct", 0),
                        o.optInt("wrong", 0),
                        o.optLong("timestamp", 0L)
                ));
            }
            return out;
        }
    }

    public static final class PremiumConfigModel {
        public final boolean isEnabled;
        public final String planName;
        public final int priceInr;
        public final String currency;
        public final int durationDays;
        public final boolean isLifetime;
        public final String description;
        public final List<String> benefits;
        public final boolean qrEnabled;
        public final boolean upiEnabled;
        public final int sessionExpiryMinutes;
        public final String merchantVpa;
        public final String merchantName;

        public PremiumConfigModel(
                boolean isEnabled,
                String planName,
                int priceInr,
                String currency,
                int durationDays,
                boolean isLifetime,
                String description,
                List<String> benefits,
                boolean qrEnabled,
                boolean upiEnabled,
                int sessionExpiryMinutes,
                String merchantVpa,
                String merchantName) {
            this.isEnabled = isEnabled;
            this.planName = planName != null && !planName.trim().isEmpty() ? planName.trim() : "EVE Premium";
            this.priceInr = Math.max(1, priceInr);
            this.currency = currency != null && !currency.trim().isEmpty() ? currency.trim().toUpperCase(Locale.US) : "INR";
            this.durationDays = Math.max(1, durationDays);
            this.isLifetime = isLifetime;
            this.description = description != null && !description.trim().isEmpty() ? description.trim() : "Unlock EVE Premium access";
            this.benefits = benefits != null ? new ArrayList<>(benefits) : new ArrayList<>();
            this.qrEnabled = qrEnabled;
            this.upiEnabled = upiEnabled;
            this.sessionExpiryMinutes = Math.max(1, sessionExpiryMinutes);
            this.merchantVpa = merchantVpa != null && !merchantVpa.trim().isEmpty() ? merchantVpa.trim() : "eve@upi";
            this.merchantName = merchantName != null && !merchantName.trim().isEmpty() ? merchantName.trim() : "EVE Exams";
        }

        public JSONObject toPutJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("isEnabled", isEnabled);
                obj.put("planName", planName);
                obj.put("priceInr", priceInr);
                obj.put("currency", "INR");
                obj.put("durationDays", durationDays);
                obj.put("isLifetime", isLifetime);
                obj.put("description", description);
                JSONArray b = new JSONArray();
                if (benefits.isEmpty()) {
                    b.put("Access to eligible mock tests");
                } else {
                    for (String item : benefits) {
                        if (item != null && !item.trim().isEmpty()) {
                            b.put(item.trim());
                        }
                    }
                }
                obj.put("benefits", b);
                obj.put("qrEnabled", qrEnabled);
                obj.put("upiEnabled", upiEnabled);
                obj.put("sessionExpiryMinutes", sessionExpiryMinutes);
                obj.put("merchantVpa", merchantVpa);
                obj.put("merchantName", merchantName);
            } catch (Exception ignored) {
            }
            return obj;
        }

        public static PremiumConfigModel fromJson(JSONObject data) {
            if (data == null) {
                return new PremiumConfigModel(true, "EVE Premium", 99, "INR", 30, false,
                        "Unlock all eligible tests", Collections.singletonList("Unlimited eligible reattempts"),
                        true, true, 10, "evemocktest@upi", "EVE");
            }
            List<String> benefitList = new ArrayList<>();
            JSONArray arr = data.optJSONArray("benefits");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, "").trim();
                    if (!s.isEmpty()) benefitList.add(s);
                }
            }
            return new PremiumConfigModel(
                    data.optBoolean("isEnabled", data.optInt("is_enabled", 1) == 1),
                    clean(data, "planName", clean(data, "plan_name", "EVE Premium")),
                    data.optInt("priceInr", data.optInt("price_inr", 99)),
                    clean(data, "currency", "INR"),
                    data.optInt("durationDays", data.optInt("duration_days", 30)),
                    data.optBoolean("isLifetime", data.optInt("is_lifetime", 0) == 1),
                    clean(data, "description", "Unlock all eligible tests"),
                    benefitList,
                    data.optBoolean("qrEnabled", data.optInt("qr_enabled", 1) == 1),
                    data.optBoolean("upiEnabled", data.optInt("upi_enabled", 1) == 1),
                    data.optInt("sessionExpiryMinutes", data.optInt("session_expiry_minutes", 10)),
                    clean(data, "merchantVpa", clean(data, "merchant_vpa", "evemocktest@upi")),
                    clean(data, "merchantName", clean(data, "merchant_name", "EVE"))
            );
        }
    }

    public static final class PaymentTransactionItem {
        public final String orderId;
        public final String userEmail;
        public final String planName;
        public final int amount;
        public final String currency;
        public final String paymentMethod;
        public final String status;
        public final long createdAt;

        public PaymentTransactionItem(
                String orderId,
                String userEmail,
                String planName,
                int amount,
                String currency,
                String paymentMethod,
                String status,
                long createdAt) {
            this.orderId = orderId != null ? orderId : "";
            this.userEmail = userEmail != null ? userEmail : "";
            this.planName = planName != null ? planName : "";
            this.amount = amount;
            this.currency = currency != null ? currency : "INR";
            this.paymentMethod = paymentMethod != null ? paymentMethod : "UPI";
            this.status = status != null ? status : "PENDING";
            this.createdAt = createdAt;
        }

        public static List<PaymentTransactionItem> parseList(JSONArray arr) {
            List<PaymentTransactionItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new PaymentTransactionItem(
                        clean(o, "orderId", ""),
                        clean(o, "userEmail", ""),
                        clean(o, "planName", ""),
                        o.optInt("amount", 0),
                        clean(o, "currency", "INR"),
                        clean(o, "paymentMethod", "UPI"),
                        clean(o, "status", "PENDING"),
                        o.optLong("createdAt", 0L)
                ));
            }
            return out;
        }
    }

    public static final class AuditLogEntry {
        public final String id;
        public final String actionType;
        public final String description;
        public final String adminEmail;
        public final long timestamp;

        public AuditLogEntry(String id, String actionType, String description, String adminEmail, long timestamp) {
            this.id = id != null ? id : "";
            this.actionType = actionType != null ? actionType : "";
            this.description = description != null ? description : "";
            this.adminEmail = adminEmail != null ? adminEmail : "";
            this.timestamp = timestamp;
        }

        public static List<AuditLogEntry> parseList(JSONArray arr) {
            List<AuditLogEntry> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new AuditLogEntry(
                        clean(o, "id", ""),
                        clean(o, "actionType", "ACTION"),
                        clean(o, "description", ""),
                        clean(o, "adminEmail", ""),
                        o.optLong("timestamp", 0L)
                ));
            }
            return out;
        }
    }

    public static final class AdminMemberItem {
        public final String email;
        public final boolean isOwner;
        public final String customTitle;
        public final Set<String> rights;

        public AdminMemberItem(String email, boolean isOwner, String customTitle, Set<String> rights) {
            this.email = email != null ? email : "";
            this.isOwner = isOwner;
            this.customTitle = customTitle != null && !customTitle.isEmpty() ? customTitle : (isOwner ? "Owner" : "Admin");
            this.rights = rights != null ? Collections.unmodifiableSet(new HashSet<>(rights)) : Collections.emptySet();
        }

        public static List<AdminMemberItem> parseList(JSONArray arr) {
            List<AdminMemberItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Set<String> rightsSet = new HashSet<>();
                JSONArray rArr = o.optJSONArray("rights");
                if (rArr != null) {
                    for (int j = 0; j < rArr.length(); j++) {
                        String right = rArr.optString(j, "").trim();
                        if (!right.isEmpty()) rightsSet.add(right);
                    }
                }
                out.add(new AdminMemberItem(
                        clean(o, "email", ""),
                        o.optBoolean("isOwner", false),
                        clean(o, "customTitle", "Admin"),
                        rightsSet
                ));
            }
            return out;
        }
    }

    public static final class DashboardSnapshot {
        public final AdminProfile adminProfile;
        public final int totalStudents;
        public final int activeStudents;
        public final int totalExams;
        public final int publishedExams;
        public final int draftExams;
        public final int totalTests;
        public final int liveTests;
        public final int draftTests;
        public final int totalAttempts;
        public final int completedAttempts;
        public final int totalTransactions;
        public final int paidTransactions;
        public final long totalRevenueInr;
        public final List<AdminExamItem> exams;
        public final List<AdminGeneratedTestItem> generatedTests;
        public final List<AuditLogEntry> recentActions;

        public DashboardSnapshot(
                AdminProfile adminProfile,
                int totalStudents,
                int activeStudents,
                int totalExams,
                int publishedExams,
                int draftExams,
                int totalTests,
                int liveTests,
                int draftTests,
                int totalAttempts,
                int completedAttempts,
                int totalTransactions,
                int paidTransactions,
                long totalRevenueInr,
                List<AdminExamItem> exams,
                List<AdminGeneratedTestItem> generatedTests,
                List<AuditLogEntry> recentActions) {
            this.adminProfile = adminProfile;
            this.totalStudents = totalStudents;
            this.activeStudents = activeStudents;
            this.totalExams = totalExams;
            this.publishedExams = publishedExams;
            this.draftExams = draftExams;
            this.totalTests = totalTests;
            this.liveTests = liveTests;
            this.draftTests = draftTests;
            this.totalAttempts = totalAttempts;
            this.completedAttempts = completedAttempts;
            this.totalTransactions = totalTransactions;
            this.paidTransactions = paidTransactions;
            this.totalRevenueInr = totalRevenueInr;
            this.exams = exams != null ? exams : Collections.emptyList();
            this.generatedTests = generatedTests != null ? generatedTests : Collections.emptyList();
            this.recentActions = recentActions != null ? recentActions : Collections.emptyList();
        }
    }

    private static String clean(JSONObject o, String key, String fallback) {
        if (o == null || o.isNull(key)) {
            return fallback;
        }
        String value = o.optString(key, fallback);
        return (value == null || "null".equalsIgnoreCase(value.trim())) ? fallback : value;
    }
}
