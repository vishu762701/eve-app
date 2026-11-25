# EVE handoff

Read `EVE_PROJECT_STATE.md` for the permanent record and NEXT ACTION. Development is on `main` only.

## Authoritative base
- New Android client: `android/` (`android/TMessagesProj_App`, applicationId `com.eve.app`, variant `:TMessagesProj_App:assembleAfatDebug`, AGP `8.13.2`, Gradle `8.13`, SDK `36`, NDK `27.2.12479018`, CMake `3.22.1`, Telegram `21f7d109da6ab3506c9e9577f98c6a38771ab335`).
- Legacy root `app/` is preserved intact as reference for migrating EVE features (do not develop or publish `app/` as primary output).
- Existing systems intact: Cloudflare Worker + D1, Firebase Auth, Firestore, Firebase Functions, and security rules.

## Done
- Handover & login (`02f9f73`..`02721cf`, [run 45598672732](https://github.com/vishu762701/eve-app/actions/runs/45598672732)): working Telegram phone login verified on device.
- Mission 1 (`94c90cc`, [run 45614744216](https://github.com/vishu762701/eve-app/actions/runs/45614744216)): EVE branding, icons, `eve_splash_320`, third-party API disclosure (`Eve-debug-apk` `15023551658`).
- Mission 2 (`63ba35d`, [run 45621367786](https://github.com/vishu762701/eve-app/actions/runs/45621367786)): Chats -> Exams backed by Worker API (`Eve-debug-apk` `15026278212`).
- Mission 3 (`ac93600`, [run 45628596616](https://github.com/vishu762701/eve-app/actions/runs/45628596616)): Account cleanup in `UserInfoActivity` (`Eve-debug-apk` `15031321621`).
- Mission 4 (Exam Settings): `ChatSettings`/`SettingsChat` -> `"Exam Settings"`, `ThemeActivity` removes App Icon & Chat list swipe gesture (`ProfileActivity` search cleaned), `EveExamSettingsTest`.

## Next
- Verify Mission 4 CI green run on `main`, then execute Mission 5 (English-only language in `LanguageSelectActivity.java` and `LocaleController.java`).

## Open decisions
- Legacy Firebase retirement and production D1 migration remain separate from normal CI. No production deployment is authorized by this handoff.
