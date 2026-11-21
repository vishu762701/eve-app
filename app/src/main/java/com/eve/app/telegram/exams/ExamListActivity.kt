package com.eve.app.telegram.exams

import android.os.Bundle
import com.eve.app.ui.common.TelegramHostActivity

class ExamListActivity : TelegramHostActivity<ExamListFragment>() {
    override fun createTelegramFragment(savedInstanceState: Bundle?): ExamListFragment {
        return ExamListFragment()
    }
}
