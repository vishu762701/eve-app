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
        public final String parentExamId;
        public final String syllabusFileName;
        public final String syllabusUrl;
        public final String imageUrl;

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
            this(id, examName, category, timeLimitMinutes, questionCount, testNumber,
                    negativeMarkingText, publishMode, generationPrompt, "", "", "", "");
        }

        public AdminExamItem(
                String id,
                String examName,
                String category,
                int timeLimitMinutes,
                int questionCount,
                String testNumber,
                String negativeMarkingText,
                String publishMode,
                String generationPrompt,
                String parentExamId,
                String syllabusFileName,
                String syllabusUrl,
                String imageUrl) {
            this.id = id != null ? id : "";
            this.examName = examName != null ? examName : "Untitled Exam";
            this.category = category != null && !category.isEmpty() ? category : "General";
            this.timeLimitMinutes = timeLimitMinutes;
            this.questionCount = questionCount;
            this.testNumber = testNumber != null && !testNumber.isEmpty() ? testNumber : "Test 1";
            this.negativeMarkingText = negativeMarkingText != null && !negativeMarkingText.isEmpty() ? negativeMarkingText : "0";
            this.publishMode = publishMode != null && !publishMode.isEmpty() ? publishMode.toLowerCase(Locale.US) : "inherit";
            this.generationPrompt = generationPrompt != null ? generationPrompt : "";
            this.parentExamId = parentExamId != null ? parentExamId : "";
            this.syllabusFileName = syllabusFileName != null ? syllabusFileName : "";
            this.syllabusUrl = syllabusUrl != null ? syllabusUrl : "";
            this.imageUrl = imageUrl != null ? imageUrl : "";
        }

        public boolean isPaused() {
            return "paused".equalsIgnoreCase(publishMode);
        }

        public boolean isArchived() {
            return "archived".equalsIgnoreCase(publishMode);
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
                        clean(o, "generationPrompt", ""),
                        clean(o, "parentExamId", ""),
                        clean(o, "syllabusFileName", ""),
                        clean(o, "syllabusUrl", ""),
                        clean(o, "imageUrl", "")
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
        public final int pendingTransactions;
        public final int activePremiumUsers;
        public final int pendingReports;
        public final double averageScorePct;
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
            this(adminProfile, totalStudents, activeStudents, totalExams, publishedExams, draftExams,
                    totalTests, liveTests, draftTests, totalAttempts, completedAttempts,
                    totalTransactions, paidTransactions, totalRevenueInr, 0, 0, 0, 0.0,
                    exams, generatedTests, recentActions);
        }

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
                int pendingTransactions,
                int activePremiumUsers,
                int pendingReports,
                double averageScorePct,
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
            this.pendingTransactions = pendingTransactions;
            this.activePremiumUsers = activePremiumUsers;
            this.pendingReports = pendingReports;
            this.averageScorePct = averageScorePct;
            this.exams = exams != null ? exams : Collections.emptyList();
            this.generatedTests = generatedTests != null ? generatedTests : Collections.emptyList();
            this.recentActions = recentActions != null ? recentActions : Collections.emptyList();
        }
    }

    public static final class BannerItem {
        public final String id;
        public final String imageUrl;
        public final String linkUrl;
        public final String linkLabel;
        public final int sortOrder;

        public BannerItem(String id, String imageUrl, String linkUrl, String linkLabel, int sortOrder) {
            this.id = id != null ? id : "";
            this.imageUrl = imageUrl != null ? imageUrl : "";
            this.linkUrl = linkUrl != null ? linkUrl : "";
            this.linkLabel = linkLabel != null ? linkLabel : "";
            this.sortOrder = sortOrder;
        }

        public static List<BannerItem> parseList(JSONArray arr) {
            List<BannerItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new BannerItem(
                        clean(o, "id", ""),
                        clean(o, "imageUrl", clean(o, "image_url", "")),
                        clean(o, "linkUrl", clean(o, "link_url", "")),
                        clean(o, "linkLabel", clean(o, "link_label", "")),
                        o.optInt("sortOrder", o.optInt("sort_order", i))
                ));
            }
            return out;
        }
    }

    public static final class PollItem {
        public final String id;
        public final String question;
        public final List<String> options;
        public final boolean active;
        public final int totalVotes;

        public PollItem(String id, String question, List<String> options, boolean active, int totalVotes) {
            this.id = id != null ? id : "";
            this.question = question != null ? question : "";
            this.options = options != null ? options : Collections.emptyList();
            this.active = active;
            this.totalVotes = totalVotes;
        }

        public static List<PollItem> parseList(JSONArray arr) {
            List<PollItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                List<String> opts = new ArrayList<>();
                JSONArray oArr = o.optJSONArray("options");
                if (oArr != null) {
                    for (int j = 0; j < oArr.length(); j++) {
                        String s = oArr.optString(j, "").trim();
                        if (!s.isEmpty()) opts.add(s);
                    }
                }
                out.add(new PollItem(
                        clean(o, "id", ""),
                        clean(o, "question", ""),
                        opts,
                        o.optBoolean("isActive", o.optBoolean("active", o.optInt("is_active", 1) == 1)),
                        o.optInt("totalVotes", 0)
                ));
            }
            return out;
        }
    }

    public static final class BroadcastItem {
        public final String id;
        public final String title;
        public final String message;
        public final long sentAt;
        public final String sentBy;

        public BroadcastItem(String id, String title, String message, long sentAt, String sentBy) {
            this.id = id != null ? id : "";
            this.title = title != null ? title : "";
            this.message = message != null ? message : "";
            this.sentAt = sentAt;
            this.sentBy = sentBy != null ? sentBy : "";
        }

        public static List<BroadcastItem> parseList(JSONArray arr) {
            List<BroadcastItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new BroadcastItem(
                        clean(o, "id", ""),
                        clean(o, "title", ""),
                        clean(o, "message", ""),
                        o.optLong("sentAt", o.optLong("sent_at", 0L)),
                        clean(o, "sentBy", clean(o, "sent_by", ""))
                ));
            }
            return out;
        }
    }

    public static final class QuestionReportItem {
        public final String id;
        public final String examName;
        public final String questionText;
        public final String reason;
        public final String comment;
        public final String studentEmail;
        public final String status;
        public final long timestamp;

        public QuestionReportItem(String id, String examName, String questionText, String reason, String comment, String studentEmail, String status, long timestamp) {
            this.id = id != null ? id : "";
            this.examName = examName != null ? examName : "";
            this.questionText = questionText != null ? questionText : "";
            this.reason = reason != null ? reason : "";
            this.comment = comment != null ? comment : "";
            this.studentEmail = studentEmail != null ? studentEmail : "";
            this.status = status != null ? status : "pending";
            this.timestamp = timestamp;
        }

        public static List<QuestionReportItem> parseList(JSONArray arr) {
            List<QuestionReportItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new QuestionReportItem(
                        clean(o, "id", ""),
                        clean(o, "examName", ""),
                        clean(o, "questionText", ""),
                        clean(o, "reason", ""),
                        clean(o, "comment", ""),
                        clean(o, "studentEmail", ""),
                        clean(o, "status", "pending"),
                        o.optLong("timestamp", 0L)
                ));
            }
            return out;
        }
    }

    public static final class FeedbackMessageItem {
        public final String id;
        public final String userEmail;
        public final String message;
        public final String adminReply;
        public final boolean read;
        public final long createdAt;

        public FeedbackMessageItem(String id, String userEmail, String message, String adminReply, boolean read, long createdAt) {
            this.id = id != null ? id : "";
            this.userEmail = userEmail != null ? userEmail : "";
            this.message = message != null ? message : "";
            this.adminReply = adminReply != null ? adminReply : "";
            this.read = read;
            this.createdAt = createdAt;
        }

        public static List<FeedbackMessageItem> parseList(JSONArray arr) {
            List<FeedbackMessageItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new FeedbackMessageItem(
                        clean(o, "id", ""),
                        clean(o, "userEmail", clean(o, "email", "")),
                        clean(o, "message", ""),
                        clean(o, "adminReply", clean(o, "reply", "")),
                        o.optBoolean("isRead", o.optInt("is_read", 0) == 1),
                        o.optLong("timestamp", o.optLong("createdAt", 0L))
                ));
            }
            return out;
        }
    }

    public static final class FeedbackPostItem {
        public final String id;
        public final String title;
        public final String message;
        public final long createdAt;

        public FeedbackPostItem(String id, String title, String message, long createdAt) {
            this.id = id != null ? id : "";
            this.title = title != null ? title : "";
            this.message = message != null ? message : "";
            this.createdAt = createdAt;
        }

        public static List<FeedbackPostItem> parseList(JSONArray arr) {
            List<FeedbackPostItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new FeedbackPostItem(
                        clean(o, "id", ""),
                        clean(o, "title", ""),
                        clean(o, "body", clean(o, "message", "")),
                        o.optLong("createdAt", o.optLong("timestamp", 0L))
                ));
            }
            return out;
        }
    }

    public static final class ExamAnalyticsItem {
        public final String examId;
        public final String examName;
        public final int attemptCount;
        public final double averageScore;
        public final double highestScore;

        public ExamAnalyticsItem(String examId, String examName, int attemptCount, double averageScore, double highestScore) {
            this.examId = examId != null ? examId : "";
            this.examName = examName != null ? examName : "";
            this.attemptCount = attemptCount;
            this.averageScore = averageScore;
            this.highestScore = highestScore;
        }

        public static List<ExamAnalyticsItem> parseList(JSONArray arr) {
            List<ExamAnalyticsItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new ExamAnalyticsItem(
                        clean(o, "examId", ""),
                        clean(o, "examName", ""),
                        o.optInt("attemptCount", 0),
                        o.optDouble("averageScore", 0.0),
                        o.optDouble("highestScore", 0.0)
                ));
            }
            return out;
        }
    }

    public static final class CrashSignatureItem {
        public final String signature;
        public final String exceptionClass;
        public final String topFrame;
        public final String sanitizedMessage;
        public final int occurrenceCount;
        public final String status;
        public final String suggestedCause;
        public final long lastSeen;

        public CrashSignatureItem(String signature, String exceptionClass, String topFrame, String sanitizedMessage, int occurrenceCount, String status, String suggestedCause, long lastSeen) {
            this.signature = signature != null ? signature : "";
            this.exceptionClass = exceptionClass != null ? exceptionClass : "";
            this.topFrame = topFrame != null ? topFrame : "";
            this.sanitizedMessage = sanitizedMessage != null ? sanitizedMessage : "";
            this.occurrenceCount = occurrenceCount;
            this.status = status != null ? status : "open";
            this.suggestedCause = suggestedCause != null ? suggestedCause : "";
            this.lastSeen = lastSeen;
        }

        public static List<CrashSignatureItem> parseList(JSONArray arr) {
            List<CrashSignatureItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new CrashSignatureItem(
                        clean(o, "signature", ""),
                        clean(o, "exception_class", clean(o, "exceptionClass", "")),
                        clean(o, "top_frame", clean(o, "topFrame", "")),
                        clean(o, "sanitized_message", clean(o, "sanitizedMessage", "")),
                        o.optInt("occurrence_count", o.optInt("occurrenceCount", 1)),
                        clean(o, "status", "open"),
                        clean(o, "suggested_cause", clean(o, "suggestedCause", "")),
                        o.optLong("last_seen", o.optLong("lastSeen", 0L))
                ));
            }
            return out;
        }
    }

    public static final class AppConfigModel {
        public final int minSupportedVersionCode;
        public final boolean maintenanceMode;
        public final String maintenanceMessage;
        public final String defaultPublishMode;

        public AppConfigModel(int minSupportedVersionCode, boolean maintenanceMode, String maintenanceMessage, String defaultPublishMode) {
            this.minSupportedVersionCode = Math.max(1, minSupportedVersionCode);
            this.maintenanceMode = maintenanceMode;
            this.maintenanceMessage = maintenanceMessage != null ? maintenanceMessage : "";
            this.defaultPublishMode = defaultPublishMode != null && !defaultPublishMode.isEmpty() ? defaultPublishMode : "paused";
        }

        public static AppConfigModel fromJson(JSONObject data) {
            if (data == null) {
                return new AppConfigModel(1, false, "", "paused");
            }
            return new AppConfigModel(
                    data.optInt("minimum_supported_version_code", 1),
                    data.optBoolean("maintenance_mode", false),
                    clean(data, "maintenance_message", ""),
                    clean(data, "default_publish_mode", "paused")
            );
        }
    }

    public static final class PremiumUserItem {
        public final String userId;
        public final String email;
        public final String displayName;
        public final String planName;
        public final long expiresAt;
        public final boolean active;

        public PremiumUserItem(String userId, String email, String displayName, String planName, long expiresAt, boolean active) {
            this.userId = userId != null ? userId : "";
            this.email = email != null ? email : "";
            this.displayName = displayName != null ? displayName : "";
            this.planName = planName != null ? planName : "";
            this.expiresAt = expiresAt;
            this.active = active;
        }

        public static List<PremiumUserItem> parseList(JSONArray arr) {
            List<PremiumUserItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new PremiumUserItem(
                        clean(o, "userId", clean(o, "id", "")),
                        clean(o, "email", ""),
                        clean(o, "displayName", "Student"),
                        clean(o, "planName", "EVE Premium"),
                        o.optLong("expiresAt", 0L),
                        o.optBoolean("isPremium", o.optBoolean("active", true))
                ));
            }
            return out;
        }
    }

    public static final class SystemServiceStatus {
        public final String name;
        public final String status;
        public final String detail;

        public SystemServiceStatus(String name, String status, String detail) {
            this.name = name;
            this.status = status != null ? status : "unknown";
            this.detail = detail != null ? detail : "";
        }
    }

    public static final class GenerationJobItem {
        public final String id;
        public final String examId;
        public final String status;
        public final boolean retryable;
        public final String errorCategory;
        public final long startedAt;

        public GenerationJobItem(String id, String examId, String status, boolean retryable, String errorCategory, long startedAt) {
            this.id = id != null ? id : "";
            this.examId = examId != null ? examId : "";
            this.status = status != null ? status : "";
            this.retryable = retryable;
            this.errorCategory = errorCategory != null ? errorCategory : "";
            this.startedAt = startedAt;
        }

        public static List<GenerationJobItem> parseList(JSONArray arr) {
            List<GenerationJobItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new GenerationJobItem(
                        clean(o, "id", ""),
                        clean(o, "exam_id", clean(o, "examId", "")),
                        clean(o, "status", ""),
                        o.optInt("retryable", 0) == 1 || o.optBoolean("retryable", false),
                        clean(o, "error_category", clean(o, "errorCategory", "")),
                        o.optLong("started_at", o.optLong("startedAt", 0L))
                ));
            }
            return out;
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
