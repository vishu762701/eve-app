# EVE handoff

Read `EVE_PROJECT_STATE.md` for the permanent record and NEXT ACTION. Development is on `main` only.

## Authoritative base
- New Android client: `android/` (`android/TMessagesProj_App`, applicationId `com.eve.app`, variant `:TMessagesProj_App:assembleAfatDebug`, AGP `8.13.2`, Gradle `8.13`, SDK `36`, NDK `27.2.12479018`, CMake `3.22.1`, Telegram `21f7d109da6ab3506c9e9577f98c6a38771ab335`).
- Legacy root `app/` is preserved intact as reference for migrating EVE features (do not develop or publish `app/` as primary output). `eve-mock-test-app` is retired (`82918aec`, clean).
- Existing systems intact: Cloudflare Worker + D1, Firebase Auth, Firestore, Firebase Functions, and security rules.

## Done
- Handover & base build (`02f9f73`, [run 45560197466](https://github.com/vishu762701/eve-app/actions/runs/45560197466)): `android/` authoritative APK + 4 green CI jobs.
- Mission T0 official Telegram login restoration (`ef335a5`..`0dc1192`, [run 45591878290](https://github.com/vishu762701/eve-app/actions/runs/45591878290)): restored `IntroActivity`/`LoginActivity`/auth closures, removed `EveInspectionMode` auth bypass, added `OfficialTelegramLoginTest.java` and credential injection.
- Credential preflight & India default (`LoginActivity.java`, `android/TMessagesProj/build.gradle`, `.github/workflows/build.yml`): deterministic India (`IN`, `+91`) country default without SIM/`getNearestDc` + async reload preservation, fail-fast credential preflight for authoritative APK builds, and masked `BuildConfig` verification.

## Next
- Owner configures `EVE_TELEGRAM_APP_ID` and `EVE_TELEGRAM_APP_HASH` in GitHub Repository Secrets (`Settings -> Secrets and variables -> Actions`) — currently `no secrets found` (`BLOCKED_CREDENTIALS`, root cause of `CONNECTION_API_ID_INVALID`).
- Re-run `Build Eve APK` to publish credential-enabled `Eve-debug-apk`, then verify real Telegram phone -> OTP -> 2FA -> logout flow on device before EVE domain feature migration.

## Open decisions
- Legacy Firebase retirement and production D1 migration remain separate from normal CI. No production deployment is authorized by this handoff.
