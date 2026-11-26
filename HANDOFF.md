# EVE handoff

Read `EVE_PROJECT_STATE.md` for the permanent record and NEXT ACTION. Development is on `main` only.

## Authoritative base
- New Android client: `android/` (`android/TMessagesProj_App`, applicationId `com.eve.app`, variant `:TMessagesProj_App:assembleAfatDebug`, AGP `8.13.2`, Gradle `8.13`, SDK `36`, NDK `27.2.12479018`, CMake `3.22.1`, Telegram `21f7d109da6ab3506c9e9577f98c6a38771ab335`).
- Legacy root `app/` is preserved intact as reference only (do not develop or publish `app/` as primary output).
- Existing systems intact: Cloudflare Worker + D1 (+ R2/Supabase storage), Firebase Auth, Firestore, Firebase Functions, and security rules.

## Done
- Prior green CI baseline (`fec8e0f`, run `45745958364`, artifact `15084086589`): bilingual `en`/`hi` Exams UI, Exam Player/Results/History/Leaderboard/Announcements, EVE Premium UPI/QR checkout, Admin Control Center, FCM/Telegram notification isolation.
- Final Completion & Closure Batch:
  - Removed Telegram phone/OTP login & session prompts; added `EveSignInFragment` (Google Sign-In) + `EveAuthManager` local UI mirror + `PUT /api/users/profile` editing (`UserInfoActivity`, `ProfileActivity`, `LogoutActivity`, `SettingsActivity`).
  - Implemented full cloud Emoji & Sticker Pack Library: Worker/D1 migration `0032`, `AssetStorage` (R2 + Supabase fallback), chunked Telegram/Noto import with redistribution rights gating, `/api/emoji/packs*` + `/assets/:id`.
  - Implemented Android Pack Library (`EvePackLibraryFragment`, `EvePackDetailBottomSheet`, `EvePackCacheManager` SHA-256 offline cache, `EvePackAssetView` native Lottie/WebM/WebP renderer, `EveAdminPackManagerFragment`) + regression tests.

## Next
- Verify all GitHub Actions jobs (`backend`, `firebase-functions`, `Telegram Port Integrity`, `Build New Android App`) are green on the latest `main` push, fix any CI issues if found, and test `Eve-debug-apk` on device.

## Open decisions
- Production Worker secrets (`TELEGRAM_BOT_TOKEN`), R2 bucket binding (`EMOJI_BUCKET` in `wrangler.toml`), and D1 migration `0032` deployment happen separately outside automated CI.
