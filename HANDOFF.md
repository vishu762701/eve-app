# EVE Handoff

**Authoritative Android client:** `android/` (`:TMessagesProj_App:assembleAfatDebug`), NOT legacy `app/`.
**Branch & Git rule:** Commit and push directly to `main` (no PRs/branches/force-push). Keep this file <= 30 lines.

## Done
- **Production DC Default & Auto-Recovery:** Switched `EveTelegramSessionManager.shouldUseTestDc()` to `false` (Production DC in Debug + Release), added `recoverStuckTestDcIfNeeded(account)` (`ConnectionsManager.switchBackend(false)` in `LaunchActivity`, `MainTabsActivity`, `launchTelegramLogin`), updated `LoginActivity` return path to pop intermediate fragments back to `MainTabsActivity` `Chats`, and updated `EveTelegramChatOnboardingFragment` copy.
- **Owner/Admin EVE Premium Auto-Unlock & Billing Safety:** Updated `GET /api/premium/status` (`premium.ts`, `emoji.ts`) to auto-grant `"EVE Owner Access"` / `"EVE Admin Access"` (`isPremium: true, isLifetime: true`) without fake orders; added `EveAdminConfig.isOwner` / `hasEvePremiumAccess`, `EveAuthManager.hasEvePremiumOwnerAccess`, `EvePremiumRepository.resolveEffectiveEntitlement`, `PremiumPreviewFragment` Owner unlock + billing-unavailable guards (`BillingController.isReady`), and pack unlock checks (`EvePackDetailBottomSheet`, `EvePackLibraryFragment`).
- **Dual-Session Telegram Chats + Identity Bridge + Official Channel Offload:** `MainTabsActivity` (`Exams | Chats | Settings | Profile`), `DialogsActivity` (`eve_exams_mode`), `0036_user_telegram_link.sql`, `auth.ts`, `admin.ts`, `appConfig.ts`, and `EveAdminMaintenanceConfigFragment` all live & deployed (`45964168505` APK, `45968269456` Worker/D1).

## Next
- Monitor `Build Eve APK` and `Deploy Eve Worker & Apply D1 Migrations` for the Production DC + Owner Premium commit.
- Test real phone-number Telegram login on Production DC (`Chats` tab), `Exams` ↔ `Chats` switching, and Owner EVE Premium access on phone.
