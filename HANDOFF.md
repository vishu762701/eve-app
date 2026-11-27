# EVE Handoff

**Authoritative Android client:** `android/` (`:TMessagesProj_App:assembleAfatDebug`), NOT legacy `app/`.
**Branch & Git rule:** Commit and push directly to `main` (no PRs/branches/force-push). Keep this file <= 30 lines.

## Done
- **DialogCell Circular Avatars Fix (`Chats` & `Exams`):** Fixed missing `avatarImage.setImageCoords(originalAvatarRect)` in `DialogCell.buildLayout()` (56dp/52dp) and `onDraw()` normal branch (regression from stripped `StoriesUtilities.drawAvatarWithStory`), reset stale dialog flags in `setDialog(CustomDialog)`, ensured `CENTER_CROP` + 26/28dp round radius, and added regression assertions in `EveTelegramDualSessionTest`.
- **Production DC Default & Onboarding Copy:** Verified `EveTelegramSessionManager.shouldUseTestDc() == false` + `recoverStuckTestDcIfNeeded(account)` (`ConnectionsManager.switchBackend(false)`) and updated `EveTelegramChatOnboardingFragment.LOGIN_CAPTION`.
- **Owner/Admin EVE Premium & Dual-Session Chats:** `MainTabsActivity`, `DialogsActivity`, `0036_user_telegram_link.sql`, `auth.ts`, `admin.ts`, `appConfig.ts`, and `premium.ts`/`emoji.ts` Owner auto-unlock live on `main` (`backend/` 401/401 pass).

## Next
- Confirm `Build Eve APK` CI green on `main` (`:TMessagesProj:testDebugUnitTest` + `:TMessagesProj_App:assembleAfatDebug`).
- Test circular avatars across `Chats` (Archived Chats, Saved Messages, Telegram, groups/channels) & `Exams` (photos, emojis, initials) + Production DC login on phone.
