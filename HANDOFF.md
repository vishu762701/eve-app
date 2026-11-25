# EVE handoff

Read `EVE_PROJECT_STATE.md` for the permanent record and NEXT ACTION. Development is on `main` only.

## Authoritative base
- New Android client: `android/` (`android/TMessagesProj_App`, applicationId `com.eve.app`, variant `:TMessagesProj_App:assembleAfatDebug`, AGP `8.13.2`, Gradle `8.13`, SDK `36`, NDK `27.2.12479018`, CMake `3.22.1`, Telegram `21f7d109da6ab3506c9e9577f98c6a38771ab335`).
- Legacy root `app/` is preserved intact as reference for migrating EVE features (do not develop or publish `app/` as primary output). `eve-mock-test-app` is retired (`82918aec`, clean).
- Existing systems intact: Cloudflare Worker + D1, Firebase Auth, Firestore, Firebase Functions, and security rules.

## Done
- Handover & submodule sync: old workspaces (`eve-mock-test-app` @ `82918aec`, `eve-app-migration-push` @ `8a69126f`) verified clean; `android/` git submodules initialized recursively.
- CI & first build recovery (`02f9f73c86f2918c8a3615ea13a53857339e5059`, [run 45560197466](https://github.com/vishu762701/eve-app/actions/runs/45560197466)):
  - `.github/workflows/build.yml` now builds `android/` (`:TMessagesProj_App:assembleAfatDebug` + `:TMessagesProj:testDebugUnitTest`), initializes exact pinned submodules, installs SDK 36 + NDK `27.2.12479018` + CMake `3.22.1` + `nasm`, verifies `aapt2` `package: name='com.eve.app' versionCode='73039' versionName='13.2.2'`, and uploads `Eve-debug-apk` (`71,945,434` bytes).
  - All 4 CI jobs passed: `Telegram Port Integrity`, `firebase-functions`, `backend`, and `Build New Android App (android/)`.

## Next
- Verify `Eve-debug-apk` from `android/TMessagesProj_App` on owner's phone.
- Begin migrating EVE domain features (auth, exam catalogue, mock test engine, scoring, results, leaderboards, practice, admin, notifications) from `app/` into `android/` via EVE adapters while keeping Telegram Java UI classes in charge of UI/interactions.

## Open decisions
- Legacy Firebase retirement and production D1 migration remain separate from normal CI. No production deployment is authorized by this handoff.
