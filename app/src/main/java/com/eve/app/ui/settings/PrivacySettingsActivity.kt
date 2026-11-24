package com.eve.app.ui.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.eve.app.data.repository.AppContentRepository
import com.eve.app.ui.about.ContentDisplayActivity
import com.eve.app.ui.common.TelegramHostActivity
import com.eve.app.ui.home.HomeSearchHistory
import com.eve.app.ui.login.LoginActivity
import com.eve.app.util.AppBulletin
import com.eve.app.util.PasscodeManager
import com.google.firebase.auth.FirebaseAuth
import org.telegram.ui.PrivacySettingsActivity as TelegramPrivacySettingsActivity

/**
 * Host Activity for Batch 3 (Refs 10, 11, 12): Original Telegram PrivacySettingsActivity
 * (with in-stack PrivacyControlActivity and SessionsActivity) bound to real EVE security,
 * passcode, biometric, Firebase Auth session, search history, and privacy preferences.
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

    override fun getActiveSessionCount(): String = "1"

    override fun getDeviceModelLabel(): String {
        val mfg = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return if (model.startsWith(mfg, ignoreCase = true)) model else "$mfg $model".trim().ifBlank { "Android Device" }
    }

    override fun getAppVersionLabel(): String {
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "1.0"
        }
        return "EVE Android v$ver • Android ${Build.VERSION.RELEASE}"
    }

    override fun getRuleVisibility(ruleType: Int): String =
        PrivacyPreferences.getRuleVisibility(this, currentUid(), ruleType)

    override fun setRuleVisibility(ruleType: Int, value: String) {
        PrivacyPreferences.setRuleVisibility(this, currentUid(), ruleType, value)
    }

    override fun isAutoSyncProgressEnabled(): Boolean =
        PrivacyPreferences.isAutoSyncProgressEnabled(this, currentUid())

    override fun setAutoSyncProgressEnabled(enabled: Boolean) {
        PrivacyPreferences.setAutoSyncProgressEnabled(this, currentUid(), enabled)
    }

    override fun isSuggestRecentExamsEnabled(): Boolean =
        PrivacyPreferences.isSuggestRecentExamsEnabled(this, currentUid())

    override fun setSuggestRecentExamsEnabled(enabled: Boolean) {
        PrivacyPreferences.setSuggestRecentExamsEnabled(this, currentUid(), enabled)
    }

    override fun isScreenshotProtectionEnabled(): Boolean =
        PrivacyPreferences.isScreenshotProtectionEnabled(this, currentUid())

    override fun setScreenshotProtectionEnabled(enabled: Boolean) {
        PrivacyPreferences.setScreenshotProtectionEnabled(this, currentUid(), enabled)
    }

    override fun onPasscodeClicked() {
        if (!isFinishing && !isDestroyed) {
            startActivity(Intent(this, PasscodeActivity::class.java))
        }
    }

    override fun onBiometricClicked() {
        val next = !PasscodeManager.isFingerprintEnabled(this)
        PasscodeManager.setFingerprintEnabled(this, next)
        AppBulletin.show(this, if (next) "Biometric unlock enabled" else "Biometric unlock disabled")
    }

    override fun onLoginEmailClicked() {
        AppBulletin.show(this, "Signed in as ${getLoginEmail()}")
    }

    override fun onDeleteAccountClicked() {
        if (!isFinishing && !isDestroyed) {
            startActivity(Intent(this, SettingsActivity::class.java))
            AppBulletin.show(this, "Use the Settings overflow menu to confirm permanent account deletion")
        }
    }

    override fun onPrivacyPolicyClicked() {
        if (!isFinishing && !isDestroyed) {
            val intent = Intent(this, ContentDisplayActivity::class.java).apply {
                putExtra(ContentDisplayActivity.EXTRA_CONTENT_TYPE, AppContentRepository.TYPE_PRIVACY)
            }
            startActivity(intent)
        }
    }

    override fun onClearSearchHistoryConfirmed() {
        val uid = currentUid()
        if (!uid.isNullOrBlank()) {
            HomeSearchHistory.clear(this, uid)
        }
        AppBulletin.showSuccess(this, "Search history cleared")
    }

    override fun onSignOutConfirmed() {
        com.eve.app.util.SessionManager.clear()
        FirebaseAuth.getInstance().signOut()
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finishAffinity()
    }
}
