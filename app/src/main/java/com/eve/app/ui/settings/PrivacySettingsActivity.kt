package com.eve.app.ui.settings

import android.content.Intent
import android.os.Bundle
import com.eve.app.data.repository.AppContentRepository
import com.eve.app.ui.about.ContentDisplayActivity
import com.eve.app.ui.common.TelegramHostActivity
import com.eve.app.ui.home.HomeSearchHistory
import com.eve.app.util.AppBulletin
import com.eve.app.util.PasscodeManager
import com.google.firebase.auth.FirebaseAuth
import org.telegram.ui.PrivacySettingsActivity as TelegramPrivacySettingsActivity

/**
 * Host Activity for Batch 3 (Refs 10, 11, 12): Original Telegram PrivacySettingsActivity
 * bound to real EVE passcode, biometric, account email, search history, and privacy preferences.
 */
class PrivacySettingsActivity : TelegramHostActivity<TelegramPrivacySettingsActivity>(), TelegramPrivacyDelegate {

    private fun currentUid(): String? =
        com.eve.app.ui.profile.ProfileEditorAccountGuard.testOverrideUid
            ?: FirebaseAuth.getInstance().currentUser?.uid

    override fun createTelegramFragment(savedInstanceState: Bundle?): TelegramPrivacySettingsActivity {
        return TelegramPrivacySettingsActivity().apply {
            setEveDelegate(this@PrivacySettingsActivity)
        }
    }

    override fun getPasscodeStatus(): String =
        if (PasscodeManager.isPasscodeEnabled(this)) "On" else "Off"

    override fun getBiometricStatus(): String =
        if (PasscodeManager.isFingerprintEnabled(this)) "On" else "Off"

    override fun getLoginEmail(): String =
        FirebaseAuth.getInstance().currentUser?.email?.takeIf { it.isNotBlank() } ?: "Signed-in account"

    override fun getActiveSessionLabel(): String = "1 device"

    override fun getLeaderboardVisibility(): String =
        PrivacyPreferences.getLeaderboardVisibility(this, currentUid())

    override fun getProfileVisibility(): String =
        PrivacyPreferences.getProfileVisibility(this, currentUid())

    override fun getActivityVisibility(): String =
        PrivacyPreferences.getActivityVisibility(this, currentUid())

    override fun isScreenshotProtectionEnabled(): Boolean =
        PrivacyPreferences.isScreenshotProtectionEnabled(this, currentUid())

    override fun onRowClicked(rowId: Int) {
        if (isFinishing || isDestroyed) return
        when (rowId) {
            TelegramPrivacySettingsActivity.ROW_PASSCODE -> {
                startActivity(Intent(this, PasscodeActivity::class.java))
            }
            TelegramPrivacySettingsActivity.ROW_BIOMETRIC -> {
                val next = !PasscodeManager.isFingerprintEnabled(this)
                PasscodeManager.setFingerprintEnabled(this, next)
                AppBulletin.showInfo(this, if (next) "Biometric unlock enabled" else "Biometric unlock disabled")
            }
            TelegramPrivacySettingsActivity.ROW_LOGIN_EMAIL -> {
                AppBulletin.showInfo(this, "Signed in as ${getLoginEmail()}")
            }
            TelegramPrivacySettingsActivity.ROW_ACTIVE_SESSION -> {
                AppBulletin.showInfo(this, "Current EVE session active on this device")
            }
            TelegramPrivacySettingsActivity.ROW_SCREENSHOT_PROTECTION -> {
                val uid = currentUid()
                val next = !PrivacyPreferences.isScreenshotProtectionEnabled(this, uid)
                PrivacyPreferences.setScreenshotProtectionEnabled(this, uid, next)
            }
            TelegramPrivacySettingsActivity.ROW_PRIVACY_POLICY -> {
                val intent = Intent(this, ContentDisplayActivity::class.java).apply {
                    putExtra(ContentDisplayActivity.EXTRA_CONTENT_TYPE, AppContentRepository.TYPE_PRIVACY)
                }
                startActivity(intent)
            }
            TelegramPrivacySettingsActivity.ROW_DELETE_ACCOUNT -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                AppBulletin.showInfo(this, "Use the Settings overflow menu to confirm permanent account deletion")
            }
        }
    }

    override fun onVisibilitySelected(rowId: Int, choice: String) {
        val uid = currentUid()
        when (rowId) {
            TelegramPrivacySettingsActivity.ROW_LEADERBOARD_VISIBILITY ->
                PrivacyPreferences.setLeaderboardVisibility(this, uid, choice)
            TelegramPrivacySettingsActivity.ROW_PROFILE_VISIBILITY ->
                PrivacyPreferences.setProfileVisibility(this, uid, choice)
            TelegramPrivacySettingsActivity.ROW_ACTIVITY_VISIBILITY ->
                PrivacyPreferences.setActivityVisibility(this, uid, choice)
        }
    }

    override fun onClearSearchHistoryConfirmed() {
        val uid = currentUid()
        if (!uid.isNullOrBlank()) {
            HomeSearchHistory.clear(this, uid)
        }
        AppBulletin.showSuccess(this, "Search history cleared")
    }
}
