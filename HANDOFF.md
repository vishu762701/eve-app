# EVE handoff

Read `EVE_PROJECT_STATE.md` for the permanent record and NEXT ACTION. Development is on `main` only.

## Authoritative base
- New Android client: `android/` (`android/TMessagesProj_App`, applicationId `com.eve.app`, variant `:TMessagesProj_App:assembleAfatDebug`, AGP `8.13.2`, Gradle `8.13`, SDK `36`, NDK `27.2.12479018`, CMake `3.22.1`, Telegram `21f7d109da6ab3506c9e9577f98c6a38771ab335`).
- Legacy root `app/` is preserved intact as reference only (do not develop or publish `app/` as primary output).
- Existing systems intact: Cloudflare Worker + D1, Firebase Auth, Firestore, Firebase Functions, and security rules.

## Done
- Handover, login & Missions 1..8 foundation (`02f9f73`..`15a5123`): Telegram login, Exams catalog tab, Account cleanup, Exam Settings, Settings cleanup & EVE Premium, Admin Control Center.
- Single-Batch Product Completion & Defect Correction (`android/`, `.github/workflows/build.yml`, `docs/privacy-policy.html`):
  - Signing keystore fallback matching `google-services.json` SHA-1 (`49246051f90d465fb15ede4fef1e77008b607693`) + Google Sign-In fallback + strict server-only `EveAdminConfig.isAdminAvailable()`.
  - Removed bottom DEBUG Settings section & cloud string overrides; restored EVE footer, `"Exam Settings"`, EVE Help/FAQ/Privacy + Telegram API disclosure.
  - Dual language `English` + `हिन्दी` with fixed radio selection & translated strings (`values-hi/strings.xml`).
  - Complete Telegram push/notification suppression (`EveNotificationHelper`, `EVE_DISABLE_TELEGRAM_PUSH = true`).
  - EVE Premium Worker-backed plan/status/UPI+QR checkout (`EvePremiumRepository`) preserving `Icon3D.TYPE_STAR` & `StarParticlesView`; enhanced Admin modules + Merchant QR preview.
  - Full Student Exam Execution: `EveTestPlayerFragment`, `EveTestResultFragment`, `EveResultHistoryFragment`, `EveLeaderboardFragment`, `EveAnnouncementsFragment`, `EveExamTestsFragment`, `DialogsActivity` Exams menu.

## Next
- Verify the single-batch CI green run on `main`, then owner installs `Eve-debug-apk` on device and completes phone screenshot/video acceptance.

## Open decisions
- Legacy Firebase retirement and production D1 migration remain separate from normal CI. No production deployment is authorized by this handoff.
