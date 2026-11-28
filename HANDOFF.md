# EVE App Handoff (Authoritative Android Base = `android/`)
- Branch: `main` only (no alternate branches/PRs, direct commit+push to `main`).
- Latest batch (2026-11-28, `6e8a0bba` + CI fixes):
  1. **Announcements & Linked Channel**: `officialTelegramChannelUsername` setting in `EveAdminBroadcastsFragment` + `EveExamRepository.updateCachedAppConfig`, routing pinned Announcements to `ChatActivity` (`openByUserName(..., 1)`) when MTProto active (`EveAnnouncementsFragment` fallback; `title` optional).
  2. **Telegram Chats Inner Lists (`EveExamChatRowCell`)**: Unified `EveExamTestsFragment` & `EveSubExamsFragment` onto edge-to-edge `Theme.key_windowBackgroundWhite` rows (`54dp` circular avatar, `16sp` bold `AnimatedEmojiSpan`, centered 1-line unattempted rows vs attempted progress, clean pill/counter badge, `78dp` inset dividers).
  3. **Official Emoji/Photo Audit**: `EveAdminFeedbackFragment` sheets use bottom-slot `EditTextEmoji` (`SheetHost`); raw `EditText` replaced with `EditTextBoldCursor`; all avatars use `EveTelegramAvatarPicker` (`ImageUpdater`).
  4. **Fast Admin Portal Re-Open**: Scoped persisted unlock flag (`EveAdminConfig.isAdminPortalUnlocked`, cleared on sign-out / failed server check) — locked footer requires 2 long-presses; unlocked opens in 1 tap.
  5. **Cascade Live-Delete (Backend + Android)**: Atomic D1 cascade + `TelegramMediaVault` cleanup + audit + Edge Cache purge across live exams/sub-exams/tests (`403` RBAC preserved); red Live warning dialogs in Android.
  6. **Account Switcher, Native Username/Phone & Unified Identity**: Restored Telegram Account Switcher (`SettingsActivity` & `UserInfoActivity`), native `ChangeUsernameActivity` & `ActionIntroActivity` Change Phone, synced `{ telegramUserId, phone, username, displayName, bio }` via `POST /api/users/sync` + `0038`.
  7. **Clean Telegram Settings Header**: `SettingsActivity` subtitle renders ONLY `Formatted Phone Number • @username`.
  8. **CI Fixes (`46162906578`, `46164197166`)**: Made `UserInfoActivity.InfoCell` `public` for `SettingsActivity:850`, restored `import android.widget.EditText;` in `EveAdminPremiumUsersFragment` & `EveAdminGeneratedTestsFragment`, and updated `EveIntegrationRegressionTest:88` (`ACCOUNT_OPTION_ADD_ACCOUNT` -> `true`).
- Backend check: `cd backend && npm run build && npm test` passed (`421/421` at `6e8a0bba`; no backend edits).
- Next: Monitor pushed CI run (`:TMessagesProj:testDebugUnitTest`, `:TMessagesProj_App:assembleAfatDebug`, `Eve-debug-apk`), deploy `backend/` Worker + D1 `0038` when authorized, and verify on phone.
