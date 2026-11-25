# EVE handoff

Read `EVE_PROJECT_STATE.md` for the permanent record and NEXT ACTION. Development is on `main` only.

## Authoritative base
- New Android client: `android/` (`android/TMessagesProj_App`, applicationId `com.eve.app`, variant `:TMessagesProj_App:assembleAfatDebug`, AGP `8.13.2`, Gradle `8.13`, SDK `36`, NDK `27.2.12479018`, CMake `3.22.1`, Telegram `21f7d109da6ab3506c9e9577f98c6a38771ab335`).
- Legacy root `app/` is preserved intact as reference for migrating EVE features (do not develop or publish `app/` as primary output).
- Existing systems intact: Cloudflare Worker + D1, Firebase Auth, Firestore, Firebase Functions, and security rules.

## Done
- Handover & base build (`02f9f73`, [run 45560197466](https://github.com/vishu762701/eve-app/actions/runs/45560197466)): `android/` authoritative APK + 4 green CI jobs.
- Mission T0 + credential preflight + India `+91` default (`ef335a5`..`02721cf`, [run 45598672732](https://github.com/vishu762701/eve-app/actions/runs/45598672732)): working Telegram phone login verified on device; `Eve-debug-apk` (`15016588526`, `72,117,361` bytes) with `HAS_CUSTOM_TELEGRAM_API_CREDENTIALS=true`.
- Mission 1 (EVE Brand Identity): EVE launcher/adaptive icons, `eve_splash_320`, `IntroActivity` EVE emblem/copy, `DialogsActivity` `"EVE"` ActionBar title, `SettingsActivity` EVE version + third-party Telegram API disclosure, `EveBrandIdentityTest`.

## Next
- Verify Mission 1 CI green run on `main`, then execute Mission 2 (Exams main screen on original `DialogsActivity` + `DialogCell` UI backed by EVE Worker API).

## Open decisions
- Legacy Firebase retirement and production D1 migration remain separate from normal CI. No production deployment is authorized by this handoff.
