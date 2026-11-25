package org.telegram.ui.eve.exams;

import java.util.Collections;
import java.util.List;

/**
 * Immutable data contracts for EVE backend exams and published generated tests.
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
        }

        public boolean isMainExam() {
            return parentExamId.trim().isEmpty();
        }

        public boolean isPaused() {
            return "paused".equalsIgnoreCase(publishMode.trim());
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
            this.id = id != null ? id : "";
            this.examId = examId != null ? examId : "";
            this.examName = examName != null ? examName : "";
            this.testNumber = testNumber != null ? testNumber : "";
            this.title = title != null ? title : "";
            this.generatedAtMs = generatedAtMs;
            this.status = status != null ? status : "paused";
            this.questionCount = Math.max(0, questionCount);
            this.availableFromMs = availableFromMs;
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
}
