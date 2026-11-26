# EVE handoff

Read `EVE_PROJECT_STATE.md` for the permanent record and NEXT ACTION. Development is on `main` only.

## Authoritative base
- New Android client: `android/` (`android/TMessagesProj_App`, applicationId `com.eve.app`, variant `:TMessagesProj_App:assembleAfatDebug`, AGP `8.13.2`, Gradle `8.13`, SDK `36`, NDK `27.2.12479018`, CMake `3.22.1`, Telegram `21f7d109da6ab3506c9e9577f98c6a38771ab335`).
- Legacy root `app/` is preserved intact as reference only (do not develop or publish `app/` as primary output).
- Existing systems intact: Cloudflare Worker + D1 (+ R2/Supabase storage), Firebase Auth, Firestore, Firebase Functions, and security rules.

## Done
- Prior green CI baseline (`263b0e2a`, run `45863242906`): dual-mode `EveAdminUiChrome`, exam-scoped Admin hierarchy, native Admin screens, Exams home Swipe-to-Pin/Unpin.
- Student Exam Flow + Chat-Style Polls + Admin Content & Moderation Overhaul:
  - Polished all 6 student screens (`EveExamTestsFragment` live mock tests at top + overflow menu + flicker shimmer; `EveTestPlayerFragment` timer pill + progress bar + Question Palette `BottomSheet` + Report button; `EveTestResultFragment` Card Mode hero score card + review cards; `EveLeaderboardFragment` rank medals + `AvatarDrawable` + self highlight; `EveResultHistoryFragment` accuracy-colored edge-to-edge search list; `EveAnnouncementsFragment` live polls + broadcasts feed).
  - Fixed `EveAdminRepository.setPollActive` payload (`"active"` instead of `"isActive"`) & `PollItem` `voteCounts` parsing; added student poll APIs in `EveExamRepository` and shared Telegram chat-style `EveTelegramPollCardView`.
  - Replaced all remaining Content & Moderation popups with native screens: `EveAdminPollCreateFragment` (`PollEditTextCell` + `PollCreateCheckCell`), `EveAdminPollsFragment`, `EveAdminBroadcastsFragment`, `EveAdminBannersFragment`, `EveAdminFlaggedReportsFragment`, `EveAdminFeedbackFragment`, and `EveAdminContentEditorFragment`.

## Next
- Verify all 4 GitHub Actions jobs pass on the latest `main` push, fix any CI issues if found, and validate `Eve-debug-apk` on device.

## Open decisions
- Production Worker secrets (`TELEGRAM_BOT_TOKEN`), R2 bucket binding (`EMOJI_BUCKET` in `wrangler.toml`), and D1 migration `0032` deployment happen separately outside automated CI.
