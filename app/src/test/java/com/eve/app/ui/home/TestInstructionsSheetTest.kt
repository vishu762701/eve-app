package com.eve.app.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestInstructionsSheetTest {

    @Test
    fun formatMarkingSchemeHandlesZeroAndFractionalNegativeMarking() {
        assertEquals("+1 for correct, no negative marking", TestInstructionsSheet.formatMarkingScheme(0.0))
        assertEquals("+1 for correct, no negative marking", TestInstructionsSheet.formatMarkingScheme(-0.5))
        assertEquals("+1 for correct, -0.25 for wrong", TestInstructionsSheet.formatMarkingScheme(0.25))
        assertEquals("+1 for correct, -0.33 for wrong", TestInstructionsSheet.formatMarkingScheme(0.33))
        assertEquals("+1 for correct, -0.5 for wrong", TestInstructionsSheet.formatMarkingScheme(0.5))
        assertEquals("+1 for correct, -1 for wrong", TestInstructionsSheet.formatMarkingScheme(1.0))
    }

    @Test
    fun formatDurationAndQuestionCountCoverStandardExamConfigs() {
        assertEquals("30 minutes", TestInstructionsSheet.formatDuration(30))
        assertEquals("1 hr (60 min)", TestInstructionsSheet.formatDuration(60))
        assertEquals("2 hr (120 min)", TestInstructionsSheet.formatDuration(120))
        assertEquals("90 minutes", TestInstructionsSheet.formatDuration(90))
        assertEquals("1 minutes", TestInstructionsSheet.formatDuration(0))

        assertEquals("100 questions", TestInstructionsSheet.formatQuestionCount(100))
        assertEquals("Full question paper", TestInstructionsSheet.formatQuestionCount(0))
    }

    @Test
    fun buildRuleLinesAndCtaLabelReflectTestSpec() {
        val spec = TestInstructionsSpec(
            title = "SSC CGL Mock 1",
            category = "SSC",
            questionCount = 100,
            timeLimitMinutes = 60,
            negativeMarking = 0.5,
            isResuming = false
        )
        val rules = TestInstructionsSheet.buildRuleLines(spec)
        assertEquals(4, rules.size)
        assertTrue(rules[0].contains("-0.5 for wrong"))
        assertTrue(rules[1].contains("mark questions for review"))
        assertTrue(rules[2].contains("1 hr (60 min)"))
        assertTrue(rules[2].contains("automatically submits"))
        assertEquals("Start Test", TestInstructionsSheet.primaryCtaLabel(false))
        assertEquals("Resume Test", TestInstructionsSheet.primaryCtaLabel(true))
    }

    @Test
    fun examTestsActivityPassesNegativeMarkingAndShowsInstructionsBeforeLaunch() {
        val src = File("src/main/java/com/eve/app/ui/home/ExamTestsActivity.kt").readText()
        assertTrue(
            "ExamTestsActivity must present TestInstructionsSheet before launching TestActivity",
            src.contains("TestInstructionsSheet.show(")
        )
        assertTrue(
            "ExamTestsActivity must forward EXTRA_NEGATIVE_MARKING to TestActivity",
            src.contains("Constants.EXTRA_NEGATIVE_MARKING")
        )
    }

    @Test
    fun examListFragmentUsesLiveWorkerLoaderInsteadOfFakeData() {
        val src = File("src/main/java/com/eve/app/telegram/exams/ExamListFragment.java").readText()
        assertFalse(
            "ExamListFragment must not populate production list with ExamListFakeData",
            src.contains("ExamListFakeData.getFakeExams()")
        )
        assertTrue(
            "ExamListFragment must load live exams via ExamListLoader",
            src.contains("ExamListLoader.loadLiveExams(")
        )
        assertTrue(
            "ExamListFragment must open clicked exam via ExamListLoader.openExam",
            src.contains("ExamListLoader.openExam(")
        )
    }
}
