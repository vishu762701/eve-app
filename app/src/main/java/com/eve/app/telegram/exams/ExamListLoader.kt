package com.eve.app.telegram.exams

import android.content.Context
import android.content.Intent
import com.eve.app.data.model.Exam
import com.eve.app.data.repository.ExamRepository
import com.eve.app.ui.home.ExamTestsActivity
import com.eve.app.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.telegram.ui.Cells.DialogCell

data class ExamListEntry(
    val exam: Exam,
    val dialog: DialogCell.CustomDialog
)

/**
 * Loads live Worker-backed exams for [ExamListFragment] and opens [ExamTestsActivity] on tap.
 */
object ExamListLoader {

    interface Callback {
        fun onLoaded(entries: List<ExamListEntry>)
        fun onError(message: String)
    }

    @JvmStatic
    fun mapExamsToEntries(exams: List<Exam>): List<ExamListEntry> {
        return exams.mapIndexed { index, exam ->
            val dialog = DialogCell.CustomDialog().apply {
                id = index + 1
                name = exam.examName.ifBlank { "Untitled Exam" }
                message = buildSubtitle(exam)
                dateString = exam.categoryOrOther
                unread_count = 0
                pinned = false
                muted = false
                sent = -1
            }
            ExamListEntry(exam = exam, dialog = dialog)
        }
    }

    @JvmStatic
    fun buildSubtitle(exam: Exam): String {
        val parts = mutableListOf<String>()
        if (exam.timeLimitMinutes > 0) {
            parts += "${exam.timeLimitMinutes} min"
        }
        if (exam.negativeMarkingValue > 0.0) {
            parts += "-${exam.negativeMarkingValue} neg"
        }
        return if (parts.isEmpty()) exam.categoryOrOther else parts.joinToString(" • ")
    }

    @JvmStatic
    fun loadLiveExams(callback: Callback): Job {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return scope.launch {
            try {
                val exams = ExamRepository().getExams()
                val entries = mapExamsToEntries(exams)
                withContext(Dispatchers.Main) {
                    callback.onLoaded(entries)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback.onError(e.localizedMessage ?: "Couldn't load exams")
                }
            }
        }
    }

    @JvmStatic
    fun openExam(context: Context, entry: ExamListEntry) {
        val intent = Intent(context, ExamTestsActivity::class.java).apply {
            putExtra(Constants.EXTRA_EXAM_ID, entry.exam.id)
            putExtra(Constants.EXTRA_EXAM_NAME, entry.exam.examName)
        }
        context.startActivity(intent)
    }
}
