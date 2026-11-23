package com.eve.app.ui.settings

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import com.eve.app.R
import com.eve.app.ui.common.EveBaseActivity
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.HeaderCell
import org.telegram.ui.Cells.ShadowSectionCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Cells.TextInfoPrivacyCell
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.SlideChooseView

/**
 * Telegram-native Power Saving settings screen aligned with reference screenshot 07_power_saving_settings.jpg.
 * Controls battery-based animation throttling and per-feature motion preferences via [PowerSavingPreferences].
 */
class PowerSavingActivity : EveBaseActivity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var actionBar: ActionBar
    private lateinit var contentLayout: LinearLayout
    private lateinit var thresholdHeader: HeaderCell
    private lateinit var thresholdInfo: TextInfoPrivacyCell

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        }

        actionBar = ActionBar(this).apply {
            setBackButtonImage(R.drawable.ic_ab_back)
            setTitle("Power Saving")
            setAllowOverlayTitle(true)
            setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    if (id == -1) finish()
                }
            })
        }
        rootLayout.addView(
            actionBar,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(
            contentLayout,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat())
        )
        rootLayout.addView(
            scrollView,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f)
        )

        val currentThreshold = PowerSavingPreferences.getThresholdIndex(this)
        thresholdHeader = HeaderCell(this).apply {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            setText("Power Saving Mode • ${PowerSavingPreferences.formatThresholdStatus(currentThreshold)}")
        }
        contentLayout.addView(
            thresholdHeader,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        val thresholdSlider = SlideChooseView(this).apply {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            setOptions(currentThreshold, *PowerSavingPreferences.THRESHOLD_LABELS)
            setCallback(object : SlideChooseView.Callback {
                override fun onOptionSelected(index: Int) {
                    PowerSavingPreferences.setThresholdIndex(this@PowerSavingActivity, index)
                    updateThresholdLabels(index)
                }
            })
        }
        contentLayout.addView(
            thresholdSlider,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50)
        )

        thresholdInfo = TextInfoPrivacyCell(this)
        updateThresholdLabels(currentThreshold)
        contentLayout.addView(
            thresholdInfo,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        val optionsHeader = HeaderCell(this).apply {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            setText("Power saving options")
        }
        contentLayout.addView(
            optionsHeader,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        fun addToggle(title: String, initialChecked: Boolean, divider: Boolean, onToggle: (Boolean) -> Unit) {
            val cell = TextCheckCell(this).apply {
                setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
                setTextAndCheck(title, initialChecked, divider)
                setOnClickListener {
                    val next = !isChecked
                    setChecked(next)
                    onToggle(next)
                }
            }
            contentLayout.addView(
                cell,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
            )
        }

        addToggle(
            "Animated Emoji",
            PowerSavingPreferences.isAnimatedEmojisEnabled(this),
            true
        ) { PowerSavingPreferences.setAnimatedEmojisEnabled(this, it) }

        addToggle(
            "Autoplay Animations",
            PowerSavingPreferences.isAutoplayAnimationsEnabled(this),
            true
        ) { PowerSavingPreferences.setAutoplayAnimationsEnabled(this, it) }

        addToggle(
            "Particles & Visual Effects",
            PowerSavingPreferences.isParticlesEnabled(this),
            false
        ) { PowerSavingPreferences.setParticlesEnabled(this, it) }

        contentLayout.addView(
            ShadowSectionCell(this),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        addToggle(
            "Enable Smooth Transitions",
            PowerSavingPreferences.isSmoothTransitionsEnabled(this),
            false
        ) { PowerSavingPreferences.setSmoothTransitionsEnabled(this, it) }

        val transitionInfo = TextInfoPrivacyCell(this).apply {
            setText("You can disable animated transitions between different sections of the app to save battery.")
        }
        contentLayout.addView(
            transitionInfo,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        setContentView(rootLayout)
        applyPalette()
    }

    private fun updateThresholdLabels(index: Int) {
        val status = PowerSavingPreferences.formatThresholdStatus(index)
        thresholdHeader.setText("Power Saving Mode • $status")
        thresholdInfo.setText(
            when (index) {
                0 -> "Power Saving Mode is off. Animations run normally."
                4 -> "Power Saving Mode is always on to minimize battery usage."
                else -> "Automatically reduce power usage and animations when your battery is below ${PowerSavingPreferences.THRESHOLD_PERCENTS[index]}%."
            }
        )
    }

    private fun applyPalette() {
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefault))
        actionBar.setTitleColor(Theme.getColor(Theme.key_actionBarDefaultTitle))
        actionBar.setItemsColor(Theme.getColor(Theme.key_actionBarDefaultIcon), false)
    }

    override fun onApplyThemePalette(palette: com.eve.app.theme.TelegramThemeEngine.ResolvedPalette) {
        if (::actionBar.isInitialized) {
            applyPalette()
        }
    }
}
