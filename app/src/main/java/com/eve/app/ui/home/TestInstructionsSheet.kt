package com.eve.app.ui.home

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import com.eve.app.designsystem.TelegramBottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.HeaderCell
import org.telegram.ui.Cells.ShadowSectionCell
import org.telegram.ui.Cells.TextDetailSettingsCell
import org.telegram.ui.Cells.TextInfoPrivacyCell
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Stories.recorder.ButtonWithCounterView
import java.math.BigDecimal

data class TestInstructionsSpec(
    val title: String,
    val category: String,
    val questionCount: Int,
    val timeLimitMinutes: Int,
    val negativeMarking: Double,
    val isResuming: Boolean = false
)

/**
 * Telegram-native pre-start instructions bottom sheet presented before entering [com.eve.app.ui.test.TestActivity].
 * Summarizes paper metadata, marking scheme, timer auto-submit behavior, and navigation/review rules.
 */
object TestInstructionsSheet {

    fun formatMarkingScheme(negativeMarking: Double): String {
        if (!negativeMarking.isFinite() || negativeMarking <= 0.0) {
            return "+1 for correct, no negative marking"
        }
        val clean = BigDecimal.valueOf(negativeMarking).stripTrailingZeros().toPlainString()
        return "+1 for correct, -$clean for wrong"
    }

    fun formatQuestionCount(questionCount: Int): String =
        if (questionCount > 0) "$questionCount questions" else "Full question paper"

    fun formatDuration(timeLimitMinutes: Int): String {
        val mins = timeLimitMinutes.coerceAtLeast(1)
        return if (mins >= 60 && mins % 60 == 0) {
            val hrs = mins / 60
            "$hrs hr ($mins min)"
        } else {
            "$mins minutes"
        }
    }

    fun primaryCtaLabel(isResuming: Boolean): String =
        if (isResuming) "Resume Test" else "Start Test"

    fun buildRuleLines(spec: TestInstructionsSpec): List<String> = listOf(
        "Marking: ${formatMarkingScheme(spec.negativeMarking)}.",
        "You can switch between questions, change answers, or mark questions for review at any time using the question palette.",
        "The ${formatDuration(spec.timeLimitMinutes)} timer runs continuously and automatically submits your answers when time expires.",
        "Your selected answers are saved locally during the test so you can resume safely if interrupted."
    )

    fun show(
        context: Context,
        spec: TestInstructionsSpec,
        onStartTest: () -> Unit
    ): TelegramBottomSheet {
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.getColor(Theme.key_dialogBackground))
        }
        scroll.addView(
            container,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        val header = HeaderCell(context).apply {
            setText(spec.title.ifBlank { "Mock Test" })
        }
        container.addView(
            header,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        fun addDetail(label: String, value: String, divider: Boolean) {
            val cell = TextDetailSettingsCell(context).apply {
                setTextAndValue(value, label, divider)
            }
            container.addView(
                cell,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
            )
        }

        addDetail("Category", spec.category.ifBlank { "Other" }, true)
        addDetail("Questions", formatQuestionCount(spec.questionCount), true)
        addDetail("Duration", formatDuration(spec.timeLimitMinutes), true)
        addDetail("Marking Scheme", formatMarkingScheme(spec.negativeMarking), false)

        container.addView(
            ShadowSectionCell(context),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        val rulesHeader = HeaderCell(context).apply {
            setText("Exam Instructions")
        }
        container.addView(
            rulesHeader,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        val rulesCell = TextInfoPrivacyCell(context).apply {
            setText(buildRuleLines(spec).joinToString(separator = "\n\n• ", prefix = "• "))
        }
        container.addView(
            rulesCell,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        val startButton = ButtonWithCounterView(context, null).apply {
            setText(primaryCtaLabel(spec.isResuming), false)
        }
        container.addView(
            startButton,
            LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                48,
                16f,
                12f,
                16f,
                16f
            )
        )

        val sheet = TelegramBottomSheet.Builder(context)
            .setCustomView(scroll)
            .create()

        startButton.setOnClickListener {
            sheet.dismiss()
            onStartTest()
        }

        sheet.show()
        return sheet
    }
}
