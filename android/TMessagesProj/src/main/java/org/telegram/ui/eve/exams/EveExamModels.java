package org.telegram.ui.eve.exams;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable data contracts for EVE backend exams, published mock tests,
 * live player sessions, server-graded results, leaderboards, and broadcasts.
 */
public final class EveExamModels {

    private EveExamModels() {
    }

    public enum LoadState {
        LOADING,
        LOADED,
        EMPTY,
        AUTH_REQUIRED,
        ERROR
    }

    public static final class ExamSummary {
        public final String id;
        public final String examName;
        public final int timeLimitMinutes;
        public final String category;
        public final int questionCount;
        public final String testNumber;
        public final String negativeMarkingText;
        public final String parentExamId;
        public final String publishMode;
        public final long lastGenerationTimeMs;
        public final int liveTestCount;
        public final String nextTestLabel;
        public final int latestTestTimestampSec;
        public final boolean isPinned;
        public final String avatarUrl;
        public final String avatarEmoji;
        public final int avatarColor;
        public final String syllabusUrl;
        public final String syllabusFileName;
        public final Map<String, Double> cutoffs;

        public ExamSummary(
                String id,
                String examName,
                int timeLimitMinutes,
                String category,
                int questionCount,
                String testNumber,
                String negativeMarkingText,
                String parentExamId,
                String publishMode,
                long lastGenerationTimeMs,
                int liveTestCount,
                String nextTestLabel,
                int latestTestTimestampSec) {
            this(id, examName, timeLimitMinutes, category, questionCount, testNumber,
                    negativeMarkingText, parentExamId, publishMode, lastGenerationTimeMs,
                    liveTestCount, nextTestLabel, latestTestTimestampSec, false, "", "", -1, "", "", null);
        }

        public ExamSummary(
                String id,
                String examName,
                int timeLimitMinutes,
                String category,
                int questionCount,
                String testNumber,
                String negativeMarkingText,
                String parentExamId,
                String publishMode,
                long lastGenerationTimeMs,
                int liveTestCount,
                String nextTestLabel,
                int latestTestTimestampSec,
                boolean isPinned) {
            this(id, examName, timeLimitMinutes, category, questionCount, testNumber,
                    negativeMarkingText, parentExamId, publishMode, lastGenerationTimeMs,
                    liveTestCount, nextTestLabel, latestTestTimestampSec, isPinned, "", "", -1, "", "", null);
        }

        public ExamSummary(
                String id,
                String examName,
                int timeLimitMinutes,
                String category,
                int questionCount,
                String testNumber,
                String negativeMarkingText,
                String parentExamId,
                String publishMode,
                long lastGenerationTimeMs,
                int liveTestCount,
                String nextTestLabel,
                int latestTestTimestampSec,
                boolean isPinned,
                String avatarUrl,
                String avatarEmoji,
                int avatarColor) {
            this(id, examName, timeLimitMinutes, category, questionCount, testNumber,
                    negativeMarkingText, parentExamId, publishMode, lastGenerationTimeMs,
                    liveTestCount, nextTestLabel, latestTestTimestampSec, isPinned,
                    avatarUrl, avatarEmoji, avatarColor, "", "", null);
        }

        public ExamSummary(
                String id,
                String examName,
                int timeLimitMinutes,
                String category,
                int questionCount,
                String testNumber,
                String negativeMarkingText,
                String parentExamId,
                String publishMode,
                long lastGenerationTimeMs,
                int liveTestCount,
                String nextTestLabel,
                int latestTestTimestampSec,
                boolean isPinned,
                String avatarUrl,
                String avatarEmoji,
                int avatarColor,
                String syllabusUrl,
                String syllabusFileName,
                Map<String, Double> cutoffs) {
            this.id = id != null ? id : "";
            this.examName = examName != null ? examName : "";
            this.timeLimitMinutes = timeLimitMinutes;
            this.category = category != null && !category.trim().isEmpty() ? category.trim() : "General";
            this.questionCount = questionCount;
            this.testNumber = testNumber != null ? testNumber : "";
            this.negativeMarkingText = negativeMarkingText != null ? negativeMarkingText : "0";
            this.parentExamId = parentExamId != null ? parentExamId : "";
            this.publishMode = publishMode != null ? publishMode : "inherit";
            this.lastGenerationTimeMs = lastGenerationTimeMs;
            this.liveTestCount = Math.max(0, liveTestCount);
            this.nextTestLabel = nextTestLabel != null ? nextTestLabel : "";
            this.latestTestTimestampSec = Math.max(0, latestTestTimestampSec);
            this.isPinned = isPinned;
            this.avatarUrl = avatarUrl != null ? avatarUrl.trim() : "";
            this.avatarEmoji = avatarEmoji != null ? avatarEmoji.trim() : "";
            this.avatarColor = avatarColor;
            this.syllabusUrl = syllabusUrl != null ? syllabusUrl.trim() : "";
            this.syllabusFileName = syllabusFileName != null ? syllabusFileName.trim() : "";
            this.cutoffs = cutoffs != null && !cutoffs.isEmpty()
                    ? Collections.unmodifiableMap(new LinkedHashMap<>(cutoffs))
                    : Collections.emptyMap();
        }

        public ExamSummary withPinned(boolean nextPinned) {
            return new ExamSummary(
                    id, examName, timeLimitMinutes, category, questionCount, testNumber,
                    negativeMarkingText, parentExamId, publishMode, lastGenerationTimeMs,
                    liveTestCount, nextTestLabel, latestTestTimestampSec, nextPinned,
                    avatarUrl, avatarEmoji, avatarColor, syllabusUrl, syllabusFileName, cutoffs);
        }

        public boolean hasSyllabus() {
            return !syllabusUrl.trim().isEmpty() || !syllabusFileName.trim().isEmpty();
        }

        public Double getCutoffForCategory(String studentCategory) {
            if (cutoffs.isEmpty()) {
                return null;
            }
            String normalized = studentCategory != null ? studentCategory.trim() : "";
            for (Map.Entry<String, Double> entry : cutoffs.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(normalized)) {
                    return entry.getValue();
                }
            }
            return cutoffs.get("General");
        }

        public boolean isMainExam() {
            return parentExamId.trim().isEmpty();
        }

        public boolean isPaused() {
            String m = publishMode.trim();
            return "paused".equalsIgnoreCase(m) || "archived".equalsIgnoreCase(m);
        }
    }

    public static final class GeneratedTestSummary {
        public final String id;
        public final String examId;
        public final String examName;
        public final String testNumber;
        public final String title;
        public final long generatedAtMs;
        public final String status;
        public final int questionCount;
        public final long availableFromMs;
        public final String avatarUrl;
        public final String avatarEmoji;
        public final int avatarColor;

        public GeneratedTestSummary(
                String id,
                String examId,
                String examName,
                String testNumber,
                String title,
                long generatedAtMs,
                String status,
                int questionCount,
                long availableFromMs) {
            this(id, examId, examName, testNumber, title, generatedAtMs, status, questionCount, availableFromMs, "", "", -1);
        }

        public GeneratedTestSummary(
                String id,
                String examId,
                String examName,
                String testNumber,
                String title,
                long generatedAtMs,
                String status,
                int questionCount,
                long availableFromMs,
                String avatarUrl,
                String avatarEmoji,
                int avatarColor) {
            this.id = id != null ? id : "";
            this.examId = examId != null ? examId : "";
            this.examName = examName != null ? examName : "";
            this.testNumber = testNumber != null ? testNumber : "";
            this.title = title != null ? title : "";
            this.generatedAtMs = generatedAtMs;
            this.status = status != null ? status : "paused";
            this.questionCount = Math.max(0, questionCount);
            this.availableFromMs = availableFromMs;
            this.avatarUrl = avatarUrl != null ? avatarUrl.trim() : "";
            this.avatarEmoji = avatarEmoji != null ? avatarEmoji.trim() : "";
            this.avatarColor = avatarColor;
        }

        public boolean isLive() {
            return "live".equalsIgnoreCase(status) || "published".equalsIgnoreCase(status);
        }

        public String getDisplayTitle() {
            if (!title.trim().isEmpty()) {
                return title.trim();
            }
            if (!testNumber.trim().isEmpty()) {
                return testNumber.trim();
            }
            return examName.trim().isEmpty() ? "Mock Test" : examName.trim();
        }
    }

    public static final class ExamCatalogResult {
        public final LoadState state;
        public final List<ExamSummary> exams;
        public final String errorMessage;

        public ExamCatalogResult(LoadState state, List<ExamSummary> exams, String errorMessage) {
            this.state = state != null ? state : LoadState.EMPTY;
            this.exams = exams != null ? Collections.unmodifiableList(exams) : Collections.emptyList();
            this.errorMessage = errorMessage != null ? errorMessage : "";
        }
    }

    public static final class TestQuestion {
        public final String id;
        public final String examId;
        public final int number;
        public final String questionText;
        public final String questionTextHi;
        public final String optionA;
        public final String optionB;
        public final String optionC;
        public final String optionD;
        public final String optionAHi;
        public final String optionBHi;
        public final String optionCHi;
        public final String optionDHi;
        public final String topic;

        public TestQuestion(
                String id,
                String examId,
                int number,
                String questionText,
                String questionTextHi,
                String optionA,
                String optionB,
                String optionC,
                String optionD,
                String optionAHi,
                String optionBHi,
                String optionCHi,
                String optionDHi,
                String topic) {
            this.id = id != null ? id : "";
            this.examId = examId != null ? examId : "";
            this.number = number;
            this.questionText = questionText != null ? questionText : "";
            this.questionTextHi = questionTextHi != null ? questionTextHi : "";
            this.optionA = optionA != null ? optionA : "";
            this.optionB = optionB != null ? optionB : "";
            this.optionC = optionC != null ? optionC : "";
            this.optionD = optionD != null ? optionD : "";
            this.optionAHi = optionAHi != null ? optionAHi : "";
            this.optionBHi = optionBHi != null ? optionBHi : "";
            this.optionCHi = optionCHi != null ? optionCHi : "";
            this.optionDHi = optionDHi != null ? optionDHi : "";
            this.topic = topic != null ? topic.trim() : "";
        }

        public boolean hasHindiVariant() {
            return !questionTextHi.trim().isEmpty();
        }

        public String getQuestionText(boolean preferHindi) {
            if (preferHindi && !questionTextHi.trim().isEmpty()) {
                return questionTextHi;
            }
            return questionText;
        }

        public String getOptionText(String letter, boolean preferHindi) {
            if ("A".equalsIgnoreCase(letter)) {
                return (preferHindi && !optionAHi.trim().isEmpty()) ? optionAHi : optionA;
            }
            if ("B".equalsIgnoreCase(letter)) {
                return (preferHindi && !optionBHi.trim().isEmpty()) ? optionBHi : optionB;
            }
            if ("C".equalsIgnoreCase(letter)) {
                return (preferHindi && !optionCHi.trim().isEmpty()) ? optionCHi : optionC;
            }
            if ("D".equalsIgnoreCase(letter)) {
                return (preferHindi && !optionDHi.trim().isEmpty()) ? optionDHi : optionD;
            }
            return "";
        }
    }

    public static final class StartAttemptResult {
        public final boolean alreadySubmitted;
        public final String completedAttemptId;
        public final String clientAttemptId;
        public final long startedAtMs;
        public final int timeLimitSeconds;
        public final int remainingSeconds;
        public final List<TestQuestion> questions;

        public StartAttemptResult(
                boolean alreadySubmitted,
                String completedAttemptId,
                String clientAttemptId,
                long startedAtMs,
                int timeLimitSeconds,
                int remainingSeconds,
                List<TestQuestion> questions) {
            this.alreadySubmitted = alreadySubmitted;
            this.completedAttemptId = completedAttemptId != null ? completedAttemptId : "";
            this.clientAttemptId = clientAttemptId != null ? clientAttemptId : "";
            this.startedAtMs = startedAtMs;
            this.timeLimitSeconds = Math.max(0, timeLimitSeconds);
            this.remainingSeconds = Math.max(0, remainingSeconds);
            this.questions = questions != null ? Collections.unmodifiableList(questions) : Collections.emptyList();
        }
    }

    public static final class AttemptAnswerReview {
        public final String questionId;
        public final int number;
        public final String questionText;
        public final String questionTextHi;
        public final String selected;
        public final String selectedText;
        public final String selectedTextHi;
        public final String correct;
        public final String correctText;
        public final String correctTextHi;
        public final String explanation;
        public final String explanationHi;
        public final String topic;
        public boolean isBookmarked;
        public final int timeTakenSeconds;

        public AttemptAnswerReview(
                String questionId,
                int number,
                String questionText,
                String questionTextHi,
                String selected,
                String selectedText,
                String selectedTextHi,
                String correct,
                String correctText,
                String correctTextHi,
                String explanation,
                String explanationHi,
                String topic,
                boolean isBookmarked,
                int timeTakenSeconds) {
            this.questionId = questionId != null ? questionId : "";
            this.number = number;
            this.questionText = questionText != null ? questionText : "";
            this.questionTextHi = questionTextHi != null ? questionTextHi : "";
            this.selected = selected != null ? selected : "";
            this.selectedText = selectedText != null ? selectedText : "";
            this.selectedTextHi = selectedTextHi != null ? selectedTextHi : "";
            this.correct = correct != null ? correct : "";
            this.correctText = correctText != null ? correctText : "";
            this.correctTextHi = correctTextHi != null ? correctTextHi : "";
            this.explanation = explanation != null ? explanation : "";
            this.explanationHi = explanationHi != null ? explanationHi : "";
            this.topic = topic != null ? topic : "";
            this.isBookmarked = isBookmarked;
            this.timeTakenSeconds = Math.max(0, timeTakenSeconds);
        }

        public boolean isUnattempted() {
            return selected.trim().isEmpty();
        }

        public boolean isCorrect() {
            return !isUnattempted() && selected.equalsIgnoreCase(correct);
        }

        public String getLocalizedQuestion(boolean hi) {
            return (hi && !questionTextHi.trim().isEmpty()) ? questionTextHi : questionText;
        }

        public String getLocalizedSelectedText(boolean hi) {
            return (hi && !selectedTextHi.trim().isEmpty()) ? selectedTextHi : selectedText;
        }

        public String getLocalizedCorrectText(boolean hi) {
            return (hi && !correctTextHi.trim().isEmpty()) ? correctTextHi : correctText;
        }

        public String getLocalizedExplanation(boolean hi) {
            return (hi && !explanationHi.trim().isEmpty()) ? explanationHi : explanation;
        }
    }

    public static final class AttemptResult {
        public final String attemptId;
        public final String examId;
        public final String examName;
        public final String category;
        public final String displayName;
        public final double score;
        public final int total;
        public final int correct;
        public final int wrong;
        public final int unattempted;
        public final long timestampMs;
        public final int timeTakenSeconds;
        public final int counted;
        public final List<AttemptAnswerReview> answers;

        public AttemptResult(
                String attemptId,
                String examId,
                String examName,
                String category,
                String displayName,
                double score,
                int total,
                int correct,
                int wrong,
                int unattempted,
                long timestampMs,
                int timeTakenSeconds,
                int counted,
                List<AttemptAnswerReview> answers) {
            this.attemptId = attemptId != null ? attemptId : "";
            this.examId = examId != null ? examId : "";
            this.examName = examName != null ? examName : "";
            this.category = category != null ? category : "";
            this.displayName = displayName != null ? displayName : "";
            this.score = score;
            this.total = Math.max(0, total);
            this.correct = Math.max(0, correct);
            this.wrong = Math.max(0, wrong);
            this.unattempted = Math.max(0, unattempted);
            this.timestampMs = timestampMs;
            this.timeTakenSeconds = Math.max(0, timeTakenSeconds);
            this.counted = counted;
            this.answers = answers != null ? Collections.unmodifiableList(answers) : Collections.emptyList();
        }

        public int getAccuracyPercent() {
            int attempted = correct + wrong;
            if (attempted <= 0) {
                return 0;
            }
            return (int) Math.round((correct * 100.0) / attempted);
        }
    }

    public static final class LeaderboardEntry {
        public final String userId;
        public final String examId;
        public final String examName;
        public final String displayName;
        public final double score;
        public final int total;
        public final int accuracy;
        public final long timestampMs;
        public final int timeTakenSeconds;

        public LeaderboardEntry(
                String userId,
                String examId,
                String examName,
                String displayName,
                double score,
                int total,
                int accuracy,
                long timestampMs,
                int timeTakenSeconds) {
            this.userId = userId != null ? userId : "";
            this.examId = examId != null ? examId : "";
            this.examName = examName != null ? examName : "";
            this.displayName = displayName != null && !displayName.trim().isEmpty() ? displayName : "Student";
            this.score = score;
            this.total = Math.max(0, total);
            this.accuracy = Math.max(0, accuracy);
            this.timestampMs = timestampMs;
            this.timeTakenSeconds = Math.max(0, timeTakenSeconds);
        }
    }

    public static final class LeaderboardStats {
        public final int participants;
        public final double topperScore;
        public final double averageScore;
        public final Integer myRank;
        public final Double myScore;
        public final Integer myTimeSeconds;
        public final Double myPercentile;

        public LeaderboardStats(
                int participants,
                double topperScore,
                double averageScore,
                Integer myRank,
                Double myScore,
                Integer myTimeSeconds,
                Double myPercentile) {
            this.participants = Math.max(0, participants);
            this.topperScore = topperScore;
            this.averageScore = averageScore;
            this.myRank = myRank;
            this.myScore = myScore;
            this.myTimeSeconds = myTimeSeconds;
            this.myPercentile = myPercentile;
        }
    }

    public static final class LeaderboardBundle {
        public final List<LeaderboardEntry> entries;
        public final LeaderboardStats stats;

        public LeaderboardBundle(List<LeaderboardEntry> entries, LeaderboardStats stats) {
            this.entries = entries != null ? Collections.unmodifiableList(entries) : Collections.emptyList();
            this.stats = stats;
        }
    }

    public static final class BroadcastItem {
        public final String id;
        public final String title;
        public final String message;
        public final String type;
        public final long sentAtMs;

        public BroadcastItem(String id, String title, String message, String type, long sentAtMs) {
            this.id = id != null ? id : "";
            this.title = title != null ? title : "";
            this.message = message != null ? message : "";
            this.type = type != null ? type : "announcement";
            this.sentAtMs = sentAtMs;
        }
    }

    public static String buildAttemptKey(String examId, String generatedTestId) {
        String base = examId != null ? examId.trim() : "";
        String test = generatedTestId != null ? generatedTestId.trim() : "";
        if (!base.isEmpty() && !test.isEmpty()) {
            return base + "__" + test;
        }
        return !base.isEmpty() ? base : test;
    }
}
