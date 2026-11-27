# EVE Handoff

**Authoritative Android client:** `android/` (`:TMessagesProj_App:assembleAfatDebug`), NOT legacy `app/`.
**Branch & Git rule:** Commit and push directly to `main` (no PRs/branches/force-push). Keep this file <= 30 lines.

## Done
- **Dual-Session Telegram Chats + Test DC (`+999662XXXX` / `22222`):** Added `EveTelegramSessionManager` (`shouldUseTestDc() == BuildVars.DEBUG_VERSION`, real MTProto session tracking distinct from EVE synthetic UI user, `launchTelegramLogin`, `disconnectTelegramSession`), guarded `EveAuthManager.ensureLocalUiUser` + `LaunchActivity.switchToAvailableAccountOrLogout` so Firebase & Telegram coexist without overwriting each other, and hooked `LoginActivity` completion.
- **4-Tab Shell (`Exams | Chats | Settings | Profile`):** Updated `MainTabsActivity` (`TABS_COUNT = 4`, unread badge on `Chats` only) + `DialogsActivity` (`eve_exams_mode` flag), plus `EveTelegramChatOnboardingFragment` when Telegram is disconnected and `SettingsActivity` `Telegram Chat & Sync` row.
- **Identity Bridge & Official Telegram Offload:** Added `backend/migrations/0036_user_telegram_link.sql` (`telegram_user_id`, `telegram_username`), defensive `PRAGMA table_info(users)` sync in `POST /api/users/sync` (`auth.ts`), `officialTelegramChannelUsername` & `officialTelegramSupportUsername` in `appConfig.ts` + `PUT /api/admin/config` (`admin.ts`) + `EveAdminMaintenanceConfigFragment` + native `ChatActivity` offload (`openByUserName`) with `EveAnnouncementsFragment` / dialog fallback.
- **Previous 6 Upgrades & CI:** `Build Eve APK` (`5c70f19`) passed (`45959674651`); backend suite `400/400` pass locally (`telegram_dual_session_and_config.test.mjs`).

## Next
- Monitor GitHub Actions `Build Eve APK` on `main` for `:TMessagesProj:testDebugUnitTest` + `:TMessagesProj_App:assembleAfatDebug`.
- Run `Deploy Eve Worker & Apply D1 Migrations` (`preflight_only: true` then `false`) so migrations `0035` and `0036` go live on D1/Worker, then test `+9996628429` (`22222`) on Debug APK.
