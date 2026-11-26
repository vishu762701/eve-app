# EVE handoff

Read `EVE_PROJECT_STATE.md` for the permanent record and NEXT ACTION. Development is on `main` only.

## Authoritative base
- New Android client: `android/` (`android/TMessagesProj_App`, applicationId `com.eve.app`, variant `:TMessagesProj_App:assembleAfatDebug`, AGP `8.13.2`, Gradle `8.13`, SDK `36`, NDK `27.2.12479018`, CMake `3.22.1`, Telegram `21f7d109da6ab3506c9e9577f98c6a38771ab335`).
- Legacy root `app/` is preserved intact as reference only (do not develop or publish `app/` as primary output).
- Existing systems intact: Cloudflare Worker + D1 (+ R2/Supabase storage), Firebase Auth, Firestore, Firebase Functions, and security rules.

## Done
- Prior green CI baseline (`fec8e0f`, run `45745958364`, artifact `15084086589`): bilingual `en`/`hi` Exams UI, Exam Player/Results/History/Leaderboard/Announcements, EVE Premium UPI/QR checkout, Admin Control Center, FCM/Telegram notification isolation.
- Final Completion & Closure Batch: Google Sign-In (`EveSignInFragment`), profile editing (`PUT /api/users/profile`), cloud Emoji & Sticker Pack Library (`0032`, R2/Supabase, `EvePackLibraryFragment`).
- Admin UI Overhaul & Student Directory Batch:
  - Removed custom Emoji & Sticker Packs entries from `SettingsActivity` and `EveAdminControlCenterActivity` (while keeping built-in Telegram emoji/sticker engine intact).
  - Added shared `EveAdminUiChrome` (`SimpleActionBar` + `actionBarFadeView` + rounded `UniversalRecyclerView` sections) across all admin fragments.
  - Redesigned `EveAdminControlCenterActivity` into 3 clean cards (`Exams & Content`, `Students & Revenue`, `System & Access`); added `EveAdminGeneratedTestsFragment`, `EveAdminStudentsFragment`, and `EveAdminStudentDetailFragment`.
  - Extended `GET /api/admin/users` safely for `username`, `phone`, `bio`, `photoUrl`, and `@username` search; cleaned `EveAdminExamsFragment`, `EveAdminInsightsAndSystemFragment`, `EveAdminContentAndCommsFragment`, and `EveAdminDialogs`.

## Next
- Verify all GitHub Actions jobs (`backend`, `firebase-functions`, `Telegram Port Integrity`, `Build New Android App`) are green on the latest `main` push, fix any CI issues if found, and test `Eve-debug-apk` on device.

## Open decisions
- Production Worker secrets (`TELEGRAM_BOT_TOKEN`), R2 bucket binding (`EMOJI_BUCKET` in `wrangler.toml`), and D1 migration `0032` deployment happen separately outside automated CI.
