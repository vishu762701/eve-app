# EVE Handoff

**Authoritative Android client:** `android/` (`:TMessagesProj_App:assembleAfatDebug`), NOT legacy `app/`.
**Branch & Git rule:** Commit and push directly to `main` (no PRs/branches/force-push). Keep this file <= 30 lines.

## Done
- **9 Critical Stability, Search, Emoji, Sticker, Quiz & Admin IA Upgrades (`android/` & `backend/`):**
  1. `EveTestPlayerFragment` & `EveExamRepository`: added null/`Bundle` constructors, null-safe `perQuestionSeconds`, bounds/OEM guards on `CircularTimerView`/`ProgressBar`/`inlinePaletteView`, `"null"` JSON sanitization, `try/catch` crash boundary with Retry/Go Back card, and Telegram Quiz-style MCQ & Review cards with `💡 Explanation`.
  2. Video Banner & Cache Fixes: added RFC byte-range `206 Partial Content` + `Accept-Ranges: bytes` in `backend/src/util/mediaCache.ts` (for `GET /api/banners/:id/media`), single-fetch 40 MB LRU local `.mp4` cache + `try/catch` poster fallback in `EveHomeBannerCarouselView.java`, 10 MB LRU cap in `EveLocalDiskCache.java`, and throttled `AutoSyncController` (`EveAdminUiChrome.java`).
  3. `Exams` Tab Over-Scroll & Search Isolation (`DialogsActivity.java`, `DialogsAdapter.java`, `EveExamDialogsMapper.java`): clamped viewport-fitting list scroll, zeroed `LastEmptyView` in exams mode, and restricted search strictly to top-level main exams (`isMainExam()`).
  4. Announcements UI, `EditTextEmoji`, Animated Emojis/Stickers & Telegram Quizzes: overhauled `EveAnnouncementsFragment.java` (centered date pill, natural `<=88%` rounded bubble, clean ActionBar title only, `[tg_emoji:...]` `AnimatedEmojiSpan` & animated sticker rendering); created `EveAnimatedEmojiHelper.java`; wired `EditTextEmoji` into Broadcasts/Channel Name (`EveAdminBroadcastsFragment`), Exam/Sub-Exam Name (`EveAdminDialogs`), and Quiz Creator (`EveAdminPollCreateFragment`); added `backend/migrations/0037_quizzes_and_broadcast_stickers.sql` + `PRAGMA table_info` fallbacks (`polls.ts`, `broadcasts.ts`); upgraded `EveTelegramPollCardView` & `EvePollModel` to Quiz Mode; consolidated Admin Dashboard into 3 zero-duplicate cards (`EveAdminControlCenterActivity`, `EveAdminContentAndCommsFragment`, `EveAdminInsightsAndSystemFragment`).

## Next
- Owner runs `Deploy Eve Worker & Apply D1 Migrations` (`confirm_production: true`, `preflight_only: false`) to apply migration `0037_quizzes_and_broadcast_stickers.sql` and install the green CI debug APK (`:TMessagesProj_App:assembleAfatDebug`) to verify on device.
