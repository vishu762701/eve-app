package com.eve.app.ui.settings

/**
 * EVE-owned CUT-INTERFACE supplying real EVE security, passcode, session,
 * and privacy preferences to org.telegram.ui.PrivacySettingsActivity.
 */
interface TelegramPrivacyDelegate {
    fun getPasscodeStatus(): String
    fun getBiometricStatus(): String
    fun getLoginEmail(): String
    fun getActiveSessionLabel(): String
    fun getLeaderboardVisibility(): String
    fun getProfileVisibility(): String
    fun getActivityVisibility(): String
    fun isScreenshotProtectionEnabled(): Boolean
    fun onRowClicked(rowId: Int)
    fun onVisibilitySelected(rowId: Int, choice: String)
    fun onClearSearchHistoryConfirmed()
}
