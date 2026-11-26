# EVE handoff

Read `EVE_PROJECT_STATE.md` for the permanent record and NEXT ACTION. Development is on `main` only.

## Authoritative base
- New Android client: `android/` (`android/TMessagesProj_App`, applicationId `com.eve.app`, variant `:TMessagesProj_App:assembleAfatDebug`, AGP `8.13.2`, Gradle `8.13`, SDK `36`, NDK `27.2.12479018`, CMake `3.22.1`, Telegram `21f7d109da6ab3506c9e9577f98c6a38771ab335`).
- Legacy root `app/` is preserved intact as reference only (do not develop or publish `app/` as primary output).
- Existing systems intact: Cloudflare Worker + D1 (+ R2/Supabase storage), Firebase Auth, Firestore, Firebase Functions, and security rules.

## Done
- Prior green CI baseline (`088785df`, run `45852968861`): Google Sign-In, Settings cleanup, `SimpleActionBar` + `EveAdminUiChrome` cards, Student Directory & Detail, Generated Mock Tests, Admin Overhaul.
- Current UI Polish, Exam Pinning & Admin Restructure Batch:
  - Dual-mode `EveAdminUiChrome` (`useSectionCards = true` cards for hubs/settings only; `useSectionCards = false` white edge-to-edge lists with hairline dividers for directories).
  - Exam-scoped Admin hierarchy: `EveAdminExamsFragment` -> `EveAdminExamDetailFragment` -> `EveAdminGeneratedTestsFragment` / `EveAdminQuestionsFragment`; removed redundant `Mock Tests` / `Question Bank` top-level rows from `EveAdminControlCenterActivity`.
  - Replaced all AlertDialog admin viewers with native screens: `EveAdminPremiumUsersFragment`, `EveAdminPaymentsFragment`, `EveAdminPremiumConfigFragment`, `EveAdminRolesFragment`, `EveAdminAuditLogFragment`.
  - Removed `EveEmojiStickerPacks` / `EvePackLibraryFragment` from `DialogsActivity` Exams overflow menu while leaving Telegram emoji/sticker engine untouched.
  - Fixed Exams home swipe: disabled Archive on EVE exam rows, enabled Swipe-to-Pin/Unpin (`GET /api/pins`, `POST /api/pins/:examId/toggle`, `SharedPreferences` cache, pinned-first sorting, smooth snap-back with zero stuck-swipe glitch).

## Next
- Verify all 4 GitHub Actions jobs pass on the latest `main` push, fix any CI issues if found, and validate `Eve-debug-apk` on device.

## Open decisions
- Production Worker secrets (`TELEGRAM_BOT_TOKEN`), R2 bucket binding (`EMOJI_BUCKET` in `wrangler.toml`), and D1 migration `0032` deployment happen separately outside automated CI.
