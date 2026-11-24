package com.eve.app.ui.settings

/**
 * EVE-owned CUT-INTERFACE supplying real EVE security, passcode, session,
 * and privacy preferences to org.telegram.ui.PrivacySettingsActivity,
 * org.telegram.ui.PrivacyControlActivity, and org.telegram.ui.SessionsActivity.
 */
interface TelegramPrivacyDelegate {
    fun getPasscodeStatus(): String
    fun getBiometricStatus(): String
    fun getLoginEmail(): String
    fun getActiveSessionCount(): String
    fun getDeviceModelLabel(): String
    fun getAppVersionLabel(): String
    fun getRuleVisibility(ruleType: Int): String
    fun setRuleVisibility(ruleType: Int, value: String)
    fun isAutoSyncProgressEnabled(): Boolean
    fun setAutoSyncProgressEnabled(enabled: Boolean)
    fun isSuggestRecentExamsEnabled(): Boolean
    fun setSuggestRecentExamsEnabled(enabled: Boolean)
    fun isScreenshotProtectionEnabled(): Boolean
    fun setScreenshotProtectionEnabled(enabled: Boolean)
    fun onPasscodeClicked()
    fun onBiometricClicked()
    fun onLoginEmailClicked()
    fun onDeleteAccountClicked()
    fun onPrivacyPolicyClicked()
    fun onClearSearchHistoryConfirmed()
    fun onSignOutConfirmed()
}
