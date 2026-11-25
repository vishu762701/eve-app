# EVE handoff

Read `EVE_PROJECT_STATE.md` for the permanent record and NEXT ACTION. Development is on `main` only.

## Authoritative base
- New Android client: `android/` (`android/TMessagesProj_App`, applicationId `com.eve.app`, variant `:TMessagesProj_App:assembleAfatDebug`, AGP `8.13.2`, Gradle `8.13`, SDK `36`, NDK `27.2.12479018`, CMake `3.22.1`, Telegram `21f7d109da6ab3506c9e9577f98c6a38771ab335`).
- Legacy root `app/` is preserved intact as reference for migrating EVE features (do not develop or publish `app/` as primary output).
- Existing systems intact: Cloudflare Worker + D1, Firebase Auth, Firestore, Firebase Functions, and security rules.

## Done
- Handover, T0 login & credential preflight (`02f9f73`..`02721cf`, [run 45598672732](https://github.com/vishu762701/eve-app/actions/runs/45598672732)): working Telegram phone login verified on device.
- Mission 1 (`94c90cc`, [run 45614744216](https://github.com/vishu762701/eve-app/actions/runs/45614744216)): EVE launcher/adaptive icons, `eve_splash_320`, Intro/Main/Settings EVE branding + third-party API disclosure (`Eve-debug-apk` `15023551658`).
- Mission 2 (Chats -> Exams): `MainTabsActivity` `"Exams"` label + `CHATS` icon, `DialogsActivity`/`DialogsAdapter`/`DialogCell` mapped to real EVE backend exams (`EveExamRepository` + `EveExamDialogsMapper` + `EveExamTestsFragment`), `"Search exams"`, `EveExamsMainScreenTest`.

## Next
- Verify Mission 2 CI green run on `main`, then execute Mission 3 (Account screen cleanup in `UserInfoActivity.java`).

## Open decisions
- Legacy Firebase retirement and production D1 migration remain separate from normal CI. No production deployment is authorized by this handoff.
