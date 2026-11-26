package org.telegram.ui.eve.exams;

import org.telegram.messenger.R;
import org.telegram.ui.Cells.DialogCell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java data adapter mapping authoritative EVE backend ExamSummary objects
 * to original Telegram DialogCell.CustomDialog rows and search filtering.
 */
public final class EveExamDialogsMapper {

    private EveExamDialogsMapper() {
    }

    public static List<EveExamModels.ExamSummary> sortExamsByPin(List<EveExamModels.ExamSummary> source) {
        List<EveExamModels.ExamSummary> out = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        for (EveExamModels.ExamSummary exam : source) {
            if (exam != null && exam.isPinned) {
                out.add(exam);
            }
        }
        for (EveExamModels.ExamSummary exam : source) {
            if (exam != null && !exam.isPinned) {
                out.add(exam);
            }
        }
        return out;
    }

    public static List<EveExamModels.ExamSummary> filterExams(
            List<EveExamModels.ExamSummary> source,
            String query) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.US);
        List<EveExamModels.ExamSummary> matched = new ArrayList<>();
        for (EveExamModels.ExamSummary exam : source) {
            if (exam == null || exam.isPaused()) {
                continue;
            }
            if (normalized.isEmpty()) {
                matched.add(exam);
                continue;
            }
            if (containsIgnoreCase(exam.examName, normalized)
                    || containsIgnoreCase(exam.category, normalized)
                    || containsIgnoreCase(exam.nextTestLabel, normalized)
                    || containsIgnoreCase(exam.testNumber, normalized)) {
                matched.add(exam);
            }
        }
        return sortExamsByPin(matched);
    }

    private static boolean containsIgnoreCase(String value, String needleLower) {
        return value != null && value.toLowerCase(Locale.US).contains(needleLower);
    }

    public static String buildExamSubtitle(EveExamModels.ExamSummary exam) {
        if (exam == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(exam.category);
        if (exam.timeLimitMinutes > 0) {
            sb.append(" • ").append(exam.timeLimitMinutes).append(" min");
        }
        if (exam.questionCount > 0) {
            sb.append(" • ").append(exam.questionCount).append(" Qs");
        }
        if (!exam.nextTestLabel.trim().isEmpty()) {
            sb.append(" • ").append(exam.nextTestLabel.trim());
        } else if (!exam.testNumber.trim().isEmpty()) {
            sb.append(" • ").append(exam.testNumber.trim());
        }
        return sb.toString();
    }

    public static int stableDialogIdForExam(String examId) {
        if (examId == null || examId.trim().isEmpty()) {
            return 1001;
        }
        int hash = examId.hashCode();
        if (hash == 0 || hash == Integer.MIN_VALUE) {
            return 1001;
        }
        return Math.abs(hash);
    }

    public static DialogCell.CustomDialog toCustomDialog(EveExamModels.ExamSummary exam) {
        DialogCell.CustomDialog custom = new DialogCell.CustomDialog();
        custom.id = stableDialogIdForExam(exam.id);
        custom.name = exam.examName.trim().isEmpty() ? "Untitled Exam" : exam.examName.trim();
        custom.message = buildExamSubtitle(exam);
        custom.unread_count = exam.liveTestCount;
        custom.date = exam.latestTestTimestampSec;
        custom.type = 1;
        custom.verified = exam.liveTestCount > 0;
        custom.pinned = exam.isPinned;
        custom.muted = false;
        custom.isMedia = false;
        custom.sent = -1;
        return custom;
    }

    public static final class EmptyStateSpec {
        public final int iconRes;
        public final int titleRes;
        public final int helpRes;
        public final boolean autoRepeat;
        public final String errorPrefix;

        public EmptyStateSpec(int iconRes, int titleRes, int helpRes, boolean autoRepeat, String errorPrefix) {
            this.iconRes = iconRes;
            this.titleRes = titleRes;
            this.helpRes = helpRes;
            this.autoRepeat = autoRepeat;
            this.errorPrefix = errorPrefix == null ? "" : errorPrefix;
        }
    }

    public static EmptyStateSpec resolveEmptyState(
            EveExamModels.LoadState state,
            String searchQuery,
            String errorMessage) {
        boolean hasQuery = searchQuery != null && !searchQuery.trim().isEmpty();
        if (hasQuery) {
            return new EmptyStateSpec(
                    R.raw.filter_no_chats,
                    R.string.EveExamsSearchEmptyTitle,
                    R.string.EveExamsSearchEmptyHelp,
                    false,
                    "");
        }
        if (state == EveExamModels.LoadState.LOADING) {
            return new EmptyStateSpec(
                    R.raw.utyan_newborn,
                    R.string.EveExamsLoadingTitle,
                    R.string.EveExamsLoadingHelp,
                    true,
                    "");
        }
        if (state == EveExamModels.LoadState.AUTH_REQUIRED) {
            return new EmptyStateSpec(
                    R.raw.utyan_newborn,
                    R.string.EveExamsAuthTitle,
                    R.string.EveExamsAuthHelp,
                    false,
                    "");
        }
        if (state == EveExamModels.LoadState.ERROR) {
            String prefix = (errorMessage != null && !errorMessage.trim().isEmpty())
                    ? errorMessage.trim()
                    : "";
            return new EmptyStateSpec(
                    R.raw.filter_no_chats,
                    R.string.EveExamsErrorTitle,
                    R.string.EveExamsErrorHelp,
                    false,
                    prefix);
        }
        return new EmptyStateSpec(
                R.raw.filter_no_chats,
                R.string.EveExamsEmptyTitle,
                R.string.EveExamsEmptyHelp,
                false,
                "");
    }
}
