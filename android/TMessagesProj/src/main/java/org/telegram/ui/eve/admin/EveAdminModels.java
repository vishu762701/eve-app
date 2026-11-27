package org.telegram.ui.eve.admin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        public final String avatarUrl;
        public final String avatarEmoji;
        public final int avatarColor;
        public final String aiPrompt;
        public final String aiLanguage;
        public final String aiDifficulty;
        public final Map<String, Double> cutoffs;
        public final boolean autoGenerationEnabled;
        public final String autoGenTime;
        public final String lastGeneratedDate;
        public final String lastGenerationStatus;
        public final String lastGenerationError;
        public final long lastGenerationTimeMs;

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
                    negativeMarkingText, publishMode, generationPrompt, "", "", "", "", "", "", -1,
                    generationPrompt, "english", "mixed", null, false, "00:00", "", "idle", "", 0L);
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
            this(id, examName, category, timeLimitMinutes, questionCount, testNumber,
                    negativeMarkingText, publishMode, generationPrompt, parentExamId,
                    syllabusFileName, syllabusUrl, imageUrl, imageUrl, "", -1,
                    generationPrompt, "english", "mixed", null, false, "00:00", "", "idle", "", 0L);
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
                String imageUrl,
                String avatarUrl,
                String avatarEmoji,
                int avatarColor) {
            this(id, examName, category, timeLimitMinutes, questionCount, testNumber,
                    negativeMarkingText, publishMode, generationPrompt, parentExamId,
                    syllabusFileName, syllabusUrl, imageUrl, avatarUrl, avatarEmoji, avatarColor,
                    generationPrompt, "english", "mixed", null, false, "00:00", "", "idle", "", 0L);
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
                String imageUrl,
                String avatarUrl,
                String avatarEmoji,
                int avatarColor,
                String aiPrompt,
                String aiLanguage,
                String aiDifficulty) {
            this(id, examName, category, timeLimitMinutes, questionCount, testNumber,
                    negativeMarkingText, publishMode, generationPrompt, parentExamId,
                    syllabusFileName, syllabusUrl, imageUrl, avatarUrl, avatarEmoji, avatarColor,
                    aiPrompt, aiLanguage, aiDifficulty, null, false, "00:00", "", "idle", "", 0L);
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
                String imageUrl,
                String avatarUrl,
                String avatarEmoji,
                int avatarColor,
                String aiPrompt,
                String aiLanguage,
                String aiDifficulty,
                Map<String, Double> cutoffs,
                boolean autoGenerationEnabled,
                String autoGenTime,
                String lastGeneratedDate,
                String lastGenerationStatus,
                String lastGenerationError,
                long lastGenerationTimeMs) {
            this.id = id != null ? id : "";
            this.examName = examName != null ? examName : "Untitled Exam";
            this.category = category != null && !category.isEmpty() ? category : "General";
            this.timeLimitMinutes = timeLimitMinutes;
            this.questionCount = questionCount;
            this.testNumber = testNumber != null && !testNumber.isEmpty() ? testNumber : "Test 1";
            this.negativeMarkingText = negativeMarkingText != null && !negativeMarkingText.isEmpty() ? negativeMarkingText : "0";
            this.publishMode = publishMode != null && !publishMode.isEmpty() ? publishMode.toLowerCase(Locale.US) : "inherit";
            String resolvedPrompt = (aiPrompt != null && !aiPrompt.trim().isEmpty())
                    ? aiPrompt.trim()
                    : (generationPrompt != null ? generationPrompt : "");
            this.generationPrompt = resolvedPrompt;
            this.aiPrompt = resolvedPrompt;
            this.aiLanguage = normalizeLanguage(aiLanguage);
            this.aiDifficulty = normalizeDifficulty(aiDifficulty);
            this.parentExamId = parentExamId != null ? parentExamId : "";
            this.syllabusFileName = syllabusFileName != null ? syllabusFileName : "";
            this.syllabusUrl = syllabusUrl != null ? syllabusUrl : "";
            this.imageUrl = imageUrl != null ? imageUrl : "";
            String resolvedAvatarUrl = avatarUrl != null && !avatarUrl.trim().isEmpty() ? avatarUrl.trim() : this.imageUrl;
            this.avatarUrl = resolvedAvatarUrl;
            this.avatarEmoji = avatarEmoji != null ? avatarEmoji.trim() : "";
            this.avatarColor = avatarColor;
            this.cutoffs = cutoffs != null && !cutoffs.isEmpty()
                    ? Collections.unmodifiableMap(new LinkedHashMap<>(cutoffs))
                    : Collections.emptyMap();
            this.autoGenerationEnabled = autoGenerationEnabled;
            this.autoGenTime = autoGenTime != null && !autoGenTime.trim().isEmpty() ? autoGenTime.trim() : "00:00";
            this.lastGeneratedDate = lastGeneratedDate != null ? lastGeneratedDate.trim() : "";
            this.lastGenerationStatus = lastGenerationStatus != null && !lastGenerationStatus.trim().isEmpty() ? lastGenerationStatus.trim() : "idle";
            this.lastGenerationError = lastGenerationError != null ? lastGenerationError.trim() : "";
            this.lastGenerationTimeMs = Math.max(0L, lastGenerationTimeMs);
        }

        private static String normalizeLanguage(String raw) {
            String val = raw != null ? raw.trim().toLowerCase(Locale.US) : "";
            if ("hindi".equals(val) || "bilingual".equals(val)) return val;
            return "english";
        }

        private static String normalizeDifficulty(String raw) {
            String val = raw != null ? raw.trim().toLowerCase(Locale.US) : "";
            if ("easy".equals(val) || "medium".equals(val) || "hard".equals(val) || "mixed".equals(val)) return val;
            return "mixed";
        }

        public Double getCutoffForCategory(String cat) {
            if (cutoffs.isEmpty()) return null;
            for (Map.Entry<String, Double> e : cutoffs.entrySet()) {
                if (e.getKey().equalsIgnoreCase(cat)) {
                    return e.getValue();
                }
            }
            return null;
        }

        public AdminExamItem withAvatar(String nextAvatarUrl, String nextAvatarEmoji, int nextAvatarColor) {
            String cleanUrl = nextAvatarUrl != null ? nextAvatarUrl.trim() : "";
            return new AdminExamItem(
                    id, examName, category, timeLimitMinutes, questionCount, testNumber,
                    negativeMarkingText, publishMode, generationPrompt, parentExamId,
                    syllabusFileName, syllabusUrl, cleanUrl, cleanUrl, nextAvatarEmoji, nextAvatarColor,
                    aiPrompt, aiLanguage, aiDifficulty, cutoffs, autoGenerationEnabled, autoGenTime,
                    lastGeneratedDate, lastGenerationStatus, lastGenerationError, lastGenerationTimeMs);
        }

        public AdminExamItem withAiSettings(String nextPrompt, String nextLanguage, String nextDifficulty) {
            String p = nextPrompt != null ? nextPrompt.trim() : "";
            return new AdminExamItem(
                    id, examName, category, timeLimitMinutes, questionCount, testNumber,
                    negativeMarkingText, publishMode, p, parentExamId,
                    syllabusFileName, syllabusUrl, imageUrl, avatarUrl, avatarEmoji, avatarColor,
                    p, nextLanguage, nextDifficulty, cutoffs, autoGenerationEnabled, autoGenTime,
                    lastGeneratedDate, lastGenerationStatus, lastGenerationError, lastGenerationTimeMs);
        }

        public AdminExamItem withExtendedSettings(
                String nextName,
                String nextCategory,
                int nextTimeLimit,
                int nextQuestionCount,
                String nextNegMarking,
                String nextPublishMode,
                Map<String, Double> nextCutoffs,
                boolean nextAutoGenEnabled,
                String nextAutoGenTime) {
            return new AdminExamItem(
                    id, nextName, nextCategory, nextTimeLimit, nextQuestionCount, testNumber,
                    nextNegMarking, nextPublishMode, generationPrompt, parentExamId,
                    syllabusFileName, syllabusUrl, imageUrl, avatarUrl, avatarEmoji, avatarColor,
                    aiPrompt, aiLanguage, aiDifficulty, nextCutoffs, nextAutoGenEnabled, nextAutoGenTime,
                    lastGeneratedDate, lastGenerationStatus, lastGenerationError, lastGenerationTimeMs);
        }

        public boolean hasCustomAvatar() {
            return !avatarUrl.isEmpty() || !avatarEmoji.isEmpty() || avatarColor >= 0;
        }

        public boolean isPaused() {
            return "paused".equalsIgnoreCase(publishMode);
        }

        public boolean isDraft() {
            return "draft".equalsIgnoreCase(publishMode);
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
                String imgUrl = clean(o, "imageUrl", "");
                String avUrl = clean(o, "avatarUrl", imgUrl);
                String avEmoji = clean(o, "avatarEmoji", "");
                int avColor = o.isNull("avatarColor") ? -1 : o.optInt("avatarColor", -1);
                String genPrompt = clean(o, "generationPrompt", "");
                String aiPromptVal = clean(o, "aiPrompt", genPrompt);
                Map<String, Double> parsedCutoffs = new LinkedHashMap<>();
                JSONObject cObj = o.optJSONObject("cutoffs");
                if (cObj != null) {
                    String[] keys = new String[] { "General", "OBC", "SC", "ST", "EWS" };
                    for (String k : keys) {
                        if (cObj.has(k) && !cObj.isNull(k)) {
                            double v = cObj.optDouble(k, Double.NaN);
                            if (!Double.isNaN(v) && !Double.isInfinite(v) && v >= 0.0) {
                                parsedCutoffs.put(k, v);
                            }
                        }
                    }
                }
                out.add(new AdminExamItem(
                        clean(o, "id", ""),
                        clean(o, "examName", "Untitled Exam"),
                        clean(o, "category", "General"),
                        o.optInt("timeLimitMinutes", 30),
                        o.optInt("questionCount", 20),
                        clean(o, "testNumber", "Test 1"),
                        clean(o, "negativeMarkingText", "0"),
                        clean(o, "publishMode", "inherit"),
                        genPrompt,
                        clean(o, "parentExamId", ""),
                        clean(o, "syllabusFileName", ""),
                        clean(o, "syllabusUrl", ""),
                        imgUrl,
                        avUrl,
                        avEmoji,
                        avColor,
                        aiPromptVal,
                        clean(o, "aiLanguage", "english"),
                        clean(o, "aiDifficulty", "mixed"),
                        parsedCutoffs,
                        o.optBoolean("autoGenerationEnabled", false),
                        clean(o, "autoGenTime", "00:00"),
                        clean(o, "lastGeneratedDate", ""),
                        clean(o, "lastGenerationStatus", "idle"),
                        clean(o, "lastGenerationError", ""),
                        o.optLong("lastGenerationTime", 0L)
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
        public final String avatarUrl;
        public final String avatarEmoji;
        public final int avatarColor;

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
            this(id, examId, examName, testNumber, title, status, questionCount, generatedAtMs, validationError, "", "", -1);
        }

        public AdminGeneratedTestItem(
                String id,
                String examId,
                String examName,
                String testNumber,
                String title,
                String status,
                int questionCount,
                long generatedAtMs,
                String validationError,
                String avatarUrl,
                String avatarEmoji,
                int avatarColor) {
            this.id = id != null ? id : "";
            this.examId = examId != null ? examId : "";
            this.examName = examName != null ? examName : "";
            this.testNumber = testNumber != null ? testNumber : "";
            this.title = title != null && !title.isEmpty() ? title : (testNumber != null ? testNumber : "Mock Test");
            this.status = status != null ? status.toLowerCase(Locale.US) : "draft";
            this.questionCount = questionCount;
            this.generatedAtMs = generatedAtMs;
            this.validationError = validationError != null ? validationError : "";
            this.avatarUrl = avatarUrl != null ? avatarUrl.trim() : "";
            this.avatarEmoji = avatarEmoji != null ? avatarEmoji.trim() : "";
            this.avatarColor = avatarColor;
        }

        public AdminGeneratedTestItem withStatus(String nextStatus) {
            return new AdminGeneratedTestItem(id, examId, examName, testNumber, title, nextStatus, questionCount,
                    generatedAtMs, validationError, avatarUrl, avatarEmoji, avatarColor);
        }

        public AdminGeneratedTestItem withAvatar(String nextUrl, String nextEmoji, int nextColor) {
            return new AdminGeneratedTestItem(id, examId, examName, testNumber, title, status, questionCount,
                    generatedAtMs, validationError, nextUrl, nextEmoji, nextColor);
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
                        clean(o, "validationError", ""),
                        clean(o, "avatarUrl", ""),
                        clean(o, "avatarEmoji", ""),
                        o.isNull("avatarColor") ? -1 : o.optInt("avatarColor", -1)
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
        public final String username;
        public final String phone;
        public final String bio;
        public final String photoUrl;
        public final String dob;
        public final long createdAt;
        public final boolean isPremium;
        public final boolean isOnline;

        public AdminStudentItem(
                String id,
                String eveId,
                String email,
                String displayName,
                String category,
                long lastActive,
                boolean disabled) {
            this(id, eveId, email, displayName, category, lastActive, disabled, "", "", "", "", "", 0L, false, false);
        }

        public AdminStudentItem(
                String id,
                String eveId,
                String email,
                String displayName,
                String category,
                long lastActive,
                boolean disabled,
                String username,
                String phone,
                String bio,
                String photoUrl,
                String dob,
                long createdAt) {
            this(id, eveId, email, displayName, category, lastActive, disabled,
                    username, phone, bio, photoUrl, dob, createdAt, false,
                    lastActive > 0L && (System.currentTimeMillis() - lastActive) <= 5 * 60 * 1000L);
        }

        public AdminStudentItem(
                String id,
                String eveId,
                String email,
                String displayName,
                String category,
                long lastActive,
                boolean disabled,
                String username,
                String phone,
                String bio,
                String photoUrl,
                String dob,
                long createdAt,
                boolean isPremium,
                boolean isOnline) {
            this.id = id != null ? id : "";
            this.eveId = eveId != null ? eveId : "";
            this.email = email != null ? email : "";
            this.displayName = displayName != null && !displayName.isEmpty() ? displayName : "Student";
            this.category = category != null && !category.isEmpty() ? category : "General";
            this.lastActive = lastActive;
            this.disabled = disabled;
            this.username = username != null ? username.trim() : "";
            this.phone = phone != null ? phone.trim() : "";
            this.bio = bio != null ? bio.trim() : "";
            this.photoUrl = photoUrl != null ? photoUrl.trim() : "";
            this.dob = dob != null ? dob.trim() : "";
            this.createdAt = createdAt;
            this.isPremium = isPremium;
            this.isOnline = isOnline;
        }

        public AdminStudentItem withDisabled(boolean nextDisabled) {
            return new AdminStudentItem(
                    id, eveId, email, displayName, category, lastActive, nextDisabled,
                    username, phone, bio, photoUrl, dob, createdAt, isPremium, isOnline);
        }

        public static List<AdminStudentItem> parseList(JSONArray arr) {
            List<AdminStudentItem> out = new ArrayList<>();
            if (arr == null) return out;
            long nowMs = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                long lastAct = o.optLong("lastActive", 0L);
                boolean onlineDefault = lastAct > 0L && (nowMs - lastAct) <= 5 * 60 * 1000L;
                out.add(new AdminStudentItem(
                        clean(o, "id", ""),
                        clean(o, "eveId", ""),
                        clean(o, "email", ""),
                        clean(o, "displayName", "Student"),
                        clean(o, "category", "General"),
                        lastAct,
                        o.optBoolean("disabled", false),
                        clean(o, "username", ""),
                        clean(o, "phone", ""),
                        clean(o, "bio", ""),
                        clean(o, "photoUrl", clean(o, "avatarUrl", "")),
                        clean(o, "dob", ""),
                        o.optLong("createdAt", 0L),
                        o.optBoolean("isPremium", false),
                        o.optBoolean("isOnline", onlineDefault)
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
        public final boolean isFromCache;
        public final boolean isStale;
        public final List<String> partialWarnings;
        public final long loadedAtMs;
        public final long loadDurationMs;

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
            this(adminProfile, totalStudents, activeStudents, totalExams, publishedExams, draftExams,
                    totalTests, liveTests, draftTests, totalAttempts, completedAttempts,
                    totalTransactions, paidTransactions, totalRevenueInr,
                    pendingTransactions, activePremiumUsers, pendingReports, averageScorePct,
                    exams, generatedTests, recentActions,
                    false, false, Collections.emptyList(), System.currentTimeMillis(), 0L);
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
                List<AuditLogEntry> recentActions,
                boolean isFromCache,
                boolean isStale,
                List<String> partialWarnings,
                long loadedAtMs,
                long loadDurationMs) {
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
            this.isFromCache = isFromCache;
            this.isStale = isStale;
            this.partialWarnings = partialWarnings != null ? partialWarnings : Collections.emptyList();
            this.loadedAtMs = loadedAtMs;
            this.loadDurationMs = loadDurationMs;
        }

        public DashboardSnapshot asCacheCopy(boolean stale) {
            return new DashboardSnapshot(
                    adminProfile, totalStudents, activeStudents,
                    totalExams, publishedExams, draftExams,
                    totalTests, liveTests, draftTests,
                    totalAttempts, completedAttempts,
                    totalTransactions, paidTransactions, totalRevenueInr,
                    pendingTransactions, activePremiumUsers, pendingReports, averageScorePct,
                    exams, generatedTests, recentActions,
                    true, stale, partialWarnings, loadedAtMs, loadDurationMs);
        }

        public static DashboardSnapshot fromDashboardJson(JSONObject data, long fallbackDurationMs) {
            if (data == null) {
                return null;
            }
            JSONObject profileJson = data.optJSONObject("adminProfile");
            if (profileJson == null) {
                return null;
            }
            AdminProfile profile = AdminProfile.fromJson(profileJson);
            JSONObject metrics = data.optJSONObject("metrics");
            if (metrics == null) {
                metrics = new JSONObject();
            }
            List<AdminExamItem> exams = AdminExamItem.parseList(data.optJSONArray("exams"));
            List<AdminGeneratedTestItem> tests = AdminGeneratedTestItem.parseList(data.optJSONArray("generatedTests"));
            List<AuditLogEntry> recent = AuditLogEntry.parseList(data.optJSONArray("recentActions"));
            List<String> warnings = new ArrayList<>();
            JSONArray warnArr = data.optJSONArray("partialErrors");
            if (warnArr != null) {
                for (int i = 0; i < warnArr.length(); i++) {
                    String w = warnArr.optString(i, "").trim();
                    if (!w.isEmpty()) {
                        warnings.add(w);
                    }
                }
            }
            int totalExams = metrics.has("totalExams") ? metrics.optInt("totalExams", exams.size()) : exams.size();
            int publishedExams = metrics.optInt("publishedExams", 0);
            int draftExams = metrics.optInt("draftExams", Math.max(0, totalExams - publishedExams));
            int totalTests = metrics.optInt("totalTests", tests.size());
            int liveTests = metrics.optInt("liveTests", 0);
            int draftTests = metrics.optInt("draftTests", Math.max(0, totalTests - liveTests));
            int totalAttempts = metrics.optInt("totalAttempts", 0);
            int completedAttempts = metrics.optInt("completedAttempts", totalAttempts);
            long loadedAt = data.optLong("loadedAt", System.currentTimeMillis());
            long durationMs = fallbackDurationMs > 0 ? fallbackDurationMs : data.optLong("durationMs", 0L);

            return new DashboardSnapshot(
                    profile,
                    metrics.optInt("totalStudents", 0),
                    metrics.optInt("activeStudents", 0),
                    totalExams,
                    publishedExams,
                    draftExams,
                    totalTests,
                    liveTests,
                    draftTests,
                    totalAttempts,
                    completedAttempts,
                    metrics.optInt("totalTransactions", 0),
                    metrics.optInt("paidTransactions", 0),
                    metrics.optLong("totalRevenueInr", 0L),
                    metrics.optInt("pendingTransactions", 0),
                    metrics.optInt("activePremiumUsers", 0),
                    metrics.optInt("pendingReports", 0),
                    metrics.optDouble("averageScorePct", 0.0),
                    exams,
                    tests,
                    recent,
                    false,
                    false,
                    warnings,
                    loadedAt,
                    durationMs
            );
        }

        public JSONObject toCacheJson() {
            JSONObject root = new JSONObject();
            try {
                if (adminProfile != null) {
                    JSONObject p = new JSONObject();
                    p.put("email", adminProfile.email);
                    p.put("isOwner", adminProfile.isOwner);
                    p.put("customTitle", adminProfile.customTitle);
                    JSONArray rArr = new JSONArray();
                    for (String r : adminProfile.rights) {
                        rArr.put(r);
                    }
                    p.put("rights", rArr);
                    root.put("adminProfile", p);
                }
                JSONObject m = new JSONObject();
                m.put("totalStudents", totalStudents);
                m.put("activeStudents", activeStudents);
                m.put("totalExams", totalExams);
                m.put("publishedExams", publishedExams);
                m.put("draftExams", draftExams);
                m.put("totalTests", totalTests);
                m.put("liveTests", liveTests);
                m.put("draftTests", draftTests);
                m.put("totalAttempts", totalAttempts);
                m.put("completedAttempts", completedAttempts);
                m.put("totalTransactions", totalTransactions);
                m.put("paidTransactions", paidTransactions);
                m.put("totalRevenueInr", totalRevenueInr);
                m.put("pendingTransactions", pendingTransactions);
                m.put("activePremiumUsers", activePremiumUsers);
                m.put("pendingReports", pendingReports);
                m.put("averageScorePct", averageScorePct);
                root.put("metrics", m);

                JSONArray exArr = new JSONArray();
                for (AdminExamItem ex : exams) {
                    JSONObject o = new JSONObject();
                    o.put("id", ex.id);
                    o.put("examName", ex.examName);
                    o.put("category", ex.category);
                    o.put("timeLimitMinutes", ex.timeLimitMinutes);
                    o.put("questionCount", ex.questionCount);
                    o.put("testNumber", ex.testNumber);
                    o.put("negativeMarkingText", ex.negativeMarkingText);
                    o.put("publishMode", ex.publishMode);
                    o.put("parentExamId", ex.parentExamId);
                    o.put("syllabusFileName", ex.syllabusFileName);
                    o.put("syllabusUrl", ex.syllabusUrl);
                    o.put("imageUrl", ex.imageUrl);
                    exArr.put(o);
                }
                root.put("exams", exArr);

                JSONArray actArr = new JSONArray();
                for (AuditLogEntry entry : recentActions) {
                    JSONObject o = new JSONObject();
                    o.put("id", entry.id);
                    o.put("actionType", entry.actionType);
                    o.put("description", entry.description);
                    o.put("adminEmail", entry.adminEmail);
                    o.put("timestamp", entry.timestamp);
                    actArr.put(o);
                }
                root.put("recentActions", actArr);
                JSONArray warnArr = new JSONArray();
                for (String w : partialWarnings) {
                    warnArr.put(w);
                }
                root.put("partialErrors", warnArr);
                root.put("loadedAt", loadedAtMs);
                root.put("durationMs", loadDurationMs);
            } catch (Exception ignored) {
            }
            return root;
        }
    }

    public static final class BannerItem {
        public final String id;
        public final String imageUrl;
        public final String linkUrl;
        public final String linkLabel;
        public final int sortOrder;
        public final String mediaType;
        public final boolean active;
        public final String storagePath;

        public BannerItem(String id, String imageUrl, String linkUrl, String linkLabel, int sortOrder) {
            this(id, imageUrl, linkUrl, linkLabel, sortOrder, inferMediaType("", imageUrl), true, "");
        }

        public BannerItem(
                String id,
                String imageUrl,
                String linkUrl,
                String linkLabel,
                int sortOrder,
                String mediaType,
                boolean active,
                String storagePath) {
            this.id = id != null ? id : "";
            this.imageUrl = imageUrl != null ? imageUrl : "";
            this.linkUrl = linkUrl != null ? linkUrl : "";
            this.linkLabel = linkLabel != null ? linkLabel : "";
            this.sortOrder = sortOrder;
            this.mediaType = inferMediaType(mediaType, this.imageUrl + " " + (storagePath != null ? storagePath : ""));
            this.active = active;
            this.storagePath = storagePath != null ? storagePath : "";
        }

        public boolean isVideo() {
            return "video".equalsIgnoreCase(mediaType);
        }

        private static String inferMediaType(String declared, String urlHint) {
            if ("video".equalsIgnoreCase(declared)) return "video";
            if ("image".equalsIgnoreCase(declared)) return "image";
            String lower = urlHint != null ? urlHint.toLowerCase(Locale.US) : "";
            if (lower.contains(".mp4") || lower.contains(".webm")) return "video";
            return "image";
        }

        public static List<BannerItem> parseList(JSONArray arr) {
            List<BannerItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String url = clean(o, "imageUrl", clean(o, "image_url", ""));
                String sp = clean(o, "storagePath", clean(o, "storage_path", ""));
                String mType = clean(o, "mediaType", clean(o, "media_type", ""));
                out.add(new BannerItem(
                        clean(o, "id", ""),
                        url,
                        clean(o, "linkUrl", clean(o, "link_url", "")),
                        clean(o, "linkLabel", clean(o, "link_label", "")),
                        o.optInt("order", o.optInt("sortOrder", o.optInt("sort_order", i))),
                        inferMediaType(mType, url + " " + sp),
                        o.optBoolean("active", true),
                        sp
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
        public final java.util.Map<Integer, Integer> voteCounts;
        public final long createdAt;
        public final long endsAt;
        public final String createdBy;

        public PollItem(String id, String question, List<String> options, boolean active, int totalVotes) {
            this(id, question, options, active, totalVotes, null, 0L, 0L, "");
        }

        public PollItem(
                String id,
                String question,
                List<String> options,
                boolean active,
                int totalVotes,
                java.util.Map<Integer, Integer> voteCounts,
                long createdAt,
                long endsAt,
                String createdBy) {
            this.id = id != null ? id : "";
            this.question = question != null ? question : "";
            this.options = options != null ? Collections.unmodifiableList(new ArrayList<>(options)) : Collections.emptyList();
            this.active = active;
            this.totalVotes = Math.max(0, totalVotes);
            this.voteCounts = voteCounts != null
                    ? Collections.unmodifiableMap(new java.util.HashMap<>(voteCounts))
                    : Collections.emptyMap();
            this.createdAt = createdAt;
            this.endsAt = endsAt;
            this.createdBy = createdBy != null ? createdBy : "";
        }

        public int getVotesForOption(int index) {
            Integer v = voteCounts.get(index);
            return v != null ? Math.max(0, v) : 0;
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
                java.util.Map<Integer, Integer> countsMap = new java.util.HashMap<>();
                int sumVotes = 0;
                JSONObject vc = o.optJSONObject("voteCounts");
                if (vc != null) {
                    for (int j = 0; j < opts.size(); j++) {
                        int count = Math.max(0, vc.optInt(String.valueOf(j), 0));
                        countsMap.put(j, count);
                        sumVotes += count;
                    }
                } else {
                    sumVotes = Math.max(0, o.optInt("totalVotes", 0));
                }
                int total = o.has("totalVotes") && vc == null ? Math.max(0, o.optInt("totalVotes", sumVotes)) : sumVotes;
                out.add(new PollItem(
                        clean(o, "id", ""),
                        clean(o, "question", ""),
                        opts,
                        o.optBoolean("active", o.optBoolean("isActive", o.optInt("is_active", 1) == 1)),
                        total,
                        countsMap,
                        o.optLong("createdAt", o.optLong("created_at", 0L)),
                        o.optLong("endsAt", o.optLong("ends_at", 0L)),
                        clean(o, "createdBy", clean(o, "created_by", ""))
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
        public final String questionId;
        public final String examName;
        public final String questionText;
        public final String reason;
        public final String comment;
        public final String studentEmail;
        public final String status;
        public final long timestamp;

        public QuestionReportItem(String id, String examName, String questionText, String reason, String comment, String studentEmail, String status, long timestamp) {
            this(id, "", examName, questionText, reason, comment, studentEmail, status, timestamp);
        }

        public QuestionReportItem(String id, String questionId, String examName, String questionText, String reason, String comment, String studentEmail, String status, long timestamp) {
            this.id = id != null ? id : "";
            this.questionId = questionId != null ? questionId : "";
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
                        clean(o, "questionId", clean(o, "question_id", "")),
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
        public final String category;
        public final int attemptCount;
        public final double averageScore;
        public final double highestScore;

        public ExamAnalyticsItem(String examId, String examName, int attemptCount, double averageScore, double highestScore) {
            this(examId, examName, "General", attemptCount, averageScore, highestScore);
        }

        public ExamAnalyticsItem(String examId, String examName, String category, int attemptCount, double averageScore, double highestScore) {
            this.examId = examId != null ? examId : "";
            this.examName = examName != null ? examName : "";
            this.category = category != null && !category.trim().isEmpty() ? category.trim() : "General";
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
                        clean(o, "category", "General"),
                        o.optInt("attemptCount", 0),
                        o.optDouble("averageScore", 0.0),
                        o.optDouble("highestScore", 0.0)
                ));
            }
            return out;
        }
    }

    public static final class QuestionAnalyticsItem {
        public final String id;
        public final String examId;
        public final String examName;
        public final String questionId;
        public final int questionNumber;
        public final String questionText;
        public final String topic;
        public final int attempts;
        public final int correct;
        public final int wrong;
        public final int unattempted;
        public final double wrongRate;
        public final double accuracy;

        public QuestionAnalyticsItem(
                String id,
                String examId,
                String examName,
                String questionId,
                int questionNumber,
                String questionText,
                String topic,
                int attempts,
                int correct,
                int wrong,
                int unattempted,
                double wrongRate,
                double accuracy) {
            this.id = id != null ? id : "";
            this.examId = examId != null ? examId : "";
            this.examName = examName != null ? examName : "";
            this.questionId = questionId != null ? questionId : "";
            this.questionNumber = Math.max(0, questionNumber);
            this.questionText = questionText != null ? questionText : "";
            this.topic = topic != null ? topic : "";
            this.attempts = Math.max(0, attempts);
            this.correct = Math.max(0, correct);
            this.wrong = Math.max(0, wrong);
            this.unattempted = Math.max(0, unattempted);
            this.wrongRate = wrongRate;
            this.accuracy = accuracy;
        }

        public static List<QuestionAnalyticsItem> parseList(JSONArray arr) {
            List<QuestionAnalyticsItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new QuestionAnalyticsItem(
                        clean(o, "id", ""),
                        clean(o, "examId", ""),
                        clean(o, "examName", ""),
                        clean(o, "questionId", ""),
                        o.optInt("questionNumber", i + 1),
                        clean(o, "questionText", ""),
                        clean(o, "topic", ""),
                        o.optInt("attempts", 0),
                        o.optInt("correct", 0),
                        o.optInt("wrong", 0),
                        o.optInt("unattempted", 0),
                        o.optDouble("wrongRate", 0.0),
                        o.optDouble("accuracy", 0.0)
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
        public final long firstSeen;
        public final long lastSeen;
        public final String plainEnglishSummary;
        public final String whereItHappened;

        public CrashSignatureItem(String signature, String exceptionClass, String topFrame, String sanitizedMessage, int occurrenceCount, String status, String suggestedCause, long lastSeen) {
            this(signature, exceptionClass, topFrame, sanitizedMessage, occurrenceCount, status, suggestedCause, 0L, lastSeen, "", "");
        }

        public CrashSignatureItem(
                String signature,
                String exceptionClass,
                String topFrame,
                String sanitizedMessage,
                int occurrenceCount,
                String status,
                String suggestedCause,
                long firstSeen,
                long lastSeen,
                String plainEnglishSummary,
                String whereItHappened) {
            this.signature = signature != null ? signature : "";
            this.exceptionClass = exceptionClass != null ? exceptionClass : "";
            this.topFrame = topFrame != null ? topFrame : "";
            this.sanitizedMessage = sanitizedMessage != null ? sanitizedMessage : "";
            this.occurrenceCount = occurrenceCount;
            this.status = status != null ? status : "open";
            this.suggestedCause = suggestedCause != null ? suggestedCause : "";
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.plainEnglishSummary = plainEnglishSummary != null ? plainEnglishSummary : "";
            this.whereItHappened = whereItHappened != null ? whereItHappened : "";
        }

        public boolean isResolved() {
            return "resolved".equalsIgnoreCase(status);
        }

        public static CrashSignatureItem fromJson(JSONObject o) {
            if (o == null) return null;
            return new CrashSignatureItem(
                    clean(o, "signature", ""),
                    clean(o, "exception_class", clean(o, "exceptionClass", "")),
                    clean(o, "top_frame", clean(o, "topFrame", "")),
                    clean(o, "sanitized_message", clean(o, "sanitizedMessage", "")),
                    o.optInt("occurrence_count", o.optInt("occurrenceCount", 1)),
                    clean(o, "status", "open"),
                    clean(o, "suggested_cause", clean(o, "suggestedCause", "")),
                    o.optLong("first_seen", o.optLong("firstSeen", 0L)),
                    o.optLong("last_seen", o.optLong("lastSeen", 0L)),
                    clean(o, "plainEnglishSummary", clean(o, "plain_english_summary", "")),
                    clean(o, "whereItHappened", clean(o, "where_it_happened", ""))
            );
        }

        public static List<CrashSignatureItem> parseList(JSONArray arr) {
            List<CrashSignatureItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                CrashSignatureItem parsed = fromJson(arr.optJSONObject(i));
                if (parsed != null) {
                    out.add(parsed);
                }
            }
            return out;
        }
    }

    public static final class CrashIncidentItem {
        public final String id;
        public final String appVersionName;
        public final int appVersionCode;
        public final int androidSdk;
        public final String deviceModel;
        public final String anrOrExitReason;
        public final String sanitizedFramesJson;
        public final long timestamp;
        public final boolean isSynthetic;

        public CrashIncidentItem(
                String id,
                String appVersionName,
                int appVersionCode,
                int androidSdk,
                String deviceModel,
                String anrOrExitReason,
                String sanitizedFramesJson,
                long timestamp,
                boolean isSynthetic) {
            this.id = id != null ? id : "";
            this.appVersionName = appVersionName != null ? appVersionName : "";
            this.appVersionCode = appVersionCode;
            this.androidSdk = androidSdk;
            this.deviceModel = deviceModel != null ? deviceModel : "";
            this.anrOrExitReason = anrOrExitReason != null ? anrOrExitReason : "";
            this.sanitizedFramesJson = sanitizedFramesJson != null ? sanitizedFramesJson : "";
            this.timestamp = timestamp;
            this.isSynthetic = isSynthetic;
        }

        public static List<CrashIncidentItem> parseList(JSONArray arr) {
            List<CrashIncidentItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new CrashIncidentItem(
                        clean(o, "id", ""),
                        clean(o, "app_version_name", clean(o, "appVersionName", "")),
                        o.optInt("app_version_code", o.optInt("appVersionCode", 0)),
                        o.optInt("android_sdk", o.optInt("androidSdk", 0)),
                        clean(o, "device_model", clean(o, "deviceModel", "")),
                        clean(o, "anr_or_exit_reason", clean(o, "anrOrExitReason", "")),
                        clean(o, "sanitized_frames_json", clean(o, "sanitizedFramesJson", "")),
                        o.optLong("timestamp", 0L),
                        o.optInt("is_synthetic", 0) == 1 || o.optBoolean("isSynthetic", false)
                ));
            }
            return out;
        }
    }

    public static final class CrashDetailBundle {
        public final CrashSignatureItem signature;
        public final List<CrashIncidentItem> incidents;

        public CrashDetailBundle(CrashSignatureItem signature, List<CrashIncidentItem> incidents) {
            this.signature = signature;
            this.incidents = incidents != null ? Collections.unmodifiableList(incidents) : Collections.emptyList();
        }
    }

    public static final class SecurityEventItem {
        public final String id;
        public final String kind;
        public final String severity;
        public final String operation;
        public final String category;
        public final int status;
        public final boolean retryable;
        public final String correlationId;
        public final String plainEnglishTitle;
        public final String plainEnglishDetail;
        public final long timestamp;

        public SecurityEventItem(
                String id,
                String kind,
                String severity,
                String operation,
                String category,
                int status,
                boolean retryable,
                String correlationId,
                String plainEnglishTitle,
                String plainEnglishDetail,
                long timestamp) {
            this.id = id != null ? id : "";
            this.kind = kind != null ? kind : "app_error";
            this.severity = severity != null ? severity : "low";
            this.operation = operation != null ? operation : "";
            this.category = category != null ? category : "";
            this.status = status;
            this.retryable = retryable;
            this.correlationId = correlationId != null ? correlationId : "";
            this.plainEnglishTitle = plainEnglishTitle != null ? plainEnglishTitle : "";
            this.plainEnglishDetail = plainEnglishDetail != null ? plainEnglishDetail : "";
            this.timestamp = timestamp;
        }

        public boolean isSecurityAlert() {
            return "security".equalsIgnoreCase(kind);
        }

        public static List<SecurityEventItem> parseList(JSONArray arr) {
            List<SecurityEventItem> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new SecurityEventItem(
                        clean(o, "id", ""),
                        clean(o, "kind", "app_error"),
                        clean(o, "severity", "low"),
                        clean(o, "operation", ""),
                        clean(o, "category", ""),
                        o.optInt("status", 500),
                        o.optBoolean("retryable", false),
                        clean(o, "correlationId", ""),
                        clean(o, "plainEnglishTitle", ""),
                        clean(o, "plainEnglishDetail", ""),
                        o.optLong("timestamp", 0L)
                ));
            }
            return out;
        }
    }

    public static final class SecurityEventsBundle {
        public final List<SecurityEventItem> events;
        public final int securityCount;
        public final int errorCount;

        public SecurityEventsBundle(List<SecurityEventItem> events, int securityCount, int errorCount) {
            this.events = events != null ? Collections.unmodifiableList(events) : Collections.emptyList();
            this.securityCount = Math.max(0, securityCount);
            this.errorCount = Math.max(0, errorCount);
        }
    }

    public static final class AppConfigModel {
        public final int minSupportedVersionCode;
        public final boolean maintenanceMode;
        public final String maintenanceMessage;
        public final String defaultPublishMode;
        public final String announcementChannelName;
        public final String announcementChannelAvatarUrl;
        public final String officialTelegramChannelUsername;
        public final String officialTelegramSupportUsername;
        public final String aiProvider;
        public final String aiModel;

        public AppConfigModel(int minSupportedVersionCode, boolean maintenanceMode, String maintenanceMessage, String defaultPublishMode) {
            this(minSupportedVersionCode, maintenanceMode, maintenanceMessage, defaultPublishMode, "Announcements", "", "", "", "auto", "");
        }

        public AppConfigModel(
                int minSupportedVersionCode,
                boolean maintenanceMode,
                String maintenanceMessage,
                String defaultPublishMode,
                String announcementChannelName,
                String announcementChannelAvatarUrl) {
            this(minSupportedVersionCode, maintenanceMode, maintenanceMessage, defaultPublishMode,
                    announcementChannelName, announcementChannelAvatarUrl, "", "", "auto", "");
        }

        public AppConfigModel(
                int minSupportedVersionCode,
                boolean maintenanceMode,
                String maintenanceMessage,
                String defaultPublishMode,
                String announcementChannelName,
                String announcementChannelAvatarUrl,
                String officialTelegramChannelUsername,
                String officialTelegramSupportUsername) {
            this(minSupportedVersionCode, maintenanceMode, maintenanceMessage, defaultPublishMode,
                    announcementChannelName, announcementChannelAvatarUrl,
                    officialTelegramChannelUsername, officialTelegramSupportUsername, "auto", "");
        }

        public AppConfigModel(
                int minSupportedVersionCode,
                boolean maintenanceMode,
                String maintenanceMessage,
                String defaultPublishMode,
                String announcementChannelName,
                String announcementChannelAvatarUrl,
                String officialTelegramChannelUsername,
                String officialTelegramSupportUsername,
                String aiProvider,
                String aiModel) {
            this.minSupportedVersionCode = Math.max(1, minSupportedVersionCode);
            this.maintenanceMode = maintenanceMode;
            this.maintenanceMessage = maintenanceMessage != null ? maintenanceMessage : "";
            this.defaultPublishMode = defaultPublishMode != null && !defaultPublishMode.isEmpty() ? defaultPublishMode : "paused";
            this.announcementChannelName = announcementChannelName != null && !announcementChannelName.trim().isEmpty()
                    ? announcementChannelName.trim() : "Announcements";
            this.announcementChannelAvatarUrl = announcementChannelAvatarUrl != null ? announcementChannelAvatarUrl.trim() : "";
            this.officialTelegramChannelUsername = officialTelegramChannelUsername != null ? officialTelegramChannelUsername.trim() : "";
            this.officialTelegramSupportUsername = officialTelegramSupportUsername != null ? officialTelegramSupportUsername.trim() : "";
            String cleanProvider = aiProvider != null ? aiProvider.trim().toLowerCase(Locale.US) : "auto";
            this.aiProvider = cleanProvider.isEmpty() ? "auto" : cleanProvider;
            this.aiModel = aiModel != null ? aiModel.trim() : "";
        }

        public static AppConfigModel fromJson(JSONObject data) {
            if (data == null) {
                return new AppConfigModel(1, false, "", "paused", "Announcements", "", "", "", "auto", "");
            }
            int minVer = data.has("minSupportedVersionCode")
                    ? data.optInt("minSupportedVersionCode", 1)
                    : data.optInt("minimum_supported_version_code", 1);
            boolean maint = data.has("maintenanceMode")
                    ? data.optBoolean("maintenanceMode", false)
                    : data.optBoolean("maintenance_mode", false);
            String msg = clean(data, "maintenanceMessage", clean(data, "maintenance_message", ""));
            String pub = clean(data, "defaultPublishMode", clean(data, "default_publish_mode", "paused"));
            String chName = clean(data, "announcementChannelName", clean(data, "announcement_channel_name", "Announcements"));
            String chAvatar = clean(data, "announcementChannelAvatarUrl", clean(data, "announcement_channel_avatar_url", ""));
            String tgChannel = clean(data, "officialTelegramChannelUsername", clean(data, "official_telegram_channel_username", ""));
            String tgSupport = clean(data, "officialTelegramSupportUsername", clean(data, "official_telegram_support_username", ""));
            String aiProvider = clean(data, "aiProvider", clean(data, "ai_provider", "auto"));
            String aiModel = clean(data, "aiModel", clean(data, "ai_model", ""));
            return new AppConfigModel(minVer, maint, msg, pub, chName, chAvatar, tgChannel, tgSupport, aiProvider, aiModel);
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
