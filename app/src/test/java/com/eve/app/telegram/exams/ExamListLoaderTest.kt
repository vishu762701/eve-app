package com.eve.app.telegram.exams

import com.eve.app.data.model.Exam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamListLoaderTest {

    @Test
    fun mapExamsToEntriesProducesDialogModelsWithRealExamMetadata() {
        val exams = listOf(
            Exam(
                id = "exam-ssc-1",
                examName = "SSC CGL Tier 1",
                category = "SSC",
                timeLimitMinutes = 60,
                negativeMarkingValue = 0.5
            ),
            Exam(
                id = "exam-rpsc-2",
                examName = "RPSC Grade 2",
                category = "RPSC",
                timeLimitMinutes = 120,
                negativeMarkingValue = 0.0
            )
        )

        val entries = ExamListLoader.mapExamsToEntries(exams)
        assertEquals(2, entries.size)
        assertEquals("exam-ssc-1", entries[0].exam.id)
        assertEquals("SSC CGL Tier 1", entries[0].dialog.name)
        assertEquals("60 min • -0.5 neg", entries[0].dialog.message)
        assertEquals("SSC", entries[0].dialog.dateString)

        assertEquals("exam-rpsc-2", entries[1].exam.id)
        assertEquals("RPSC Grade 2", entries[1].dialog.name)
        assertEquals("120 min", entries[1].dialog.message)
        assertEquals("RPSC", entries[1].dialog.dateString)
    }

    @Test
    fun mapExamsToEntriesReturnsEmptyListWhenNoExamsExist() {
        assertTrue(ExamListLoader.mapExamsToEntries(emptyList()).isEmpty())
    }
}
