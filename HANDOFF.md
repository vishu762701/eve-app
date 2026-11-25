# EVE handoff

Read `EVE_PROJECT_STATE.md` for the permanent record and NEXT ACTION. Development is on `main` only.

## Authoritative base
- New Android client: `android/` (`android/TMessagesProj_App`, applicationId `com.eve.app`, variant `:TMessagesProj_App:assembleAfatDebug`, AGP `8.13.2`, Gradle `8.13`, SDK `36`, NDK `27.2.12479018`, CMake `3.22.1`, Telegram `21f7d109da6ab3506c9e9577f98c6a38771ab335`).
- Legacy root `app/` is preserved intact as reference for migrating EVE features (do not develop or publish `app/` as primary output).
- Existing systems intact: Cloudflare Worker + D1, Firebase Auth, Firestore, Firebase Functions, and security rules.

## Done
- Handover & login (`02f9f73`..`02721cf`, [run 45598672732](https://github.com/vishu762701/eve-app/actions/runs/45598672732)): working Telegram phone login verified on device.
- Mission 1 (`94c90cc`, [run 45614744216](https://github.com/vishu762701/eve-app/actions/runs/45614744216)): EVE branding & disclosure (`Eve-debug-apk` `15023551658`).
- Mission 2 (`63ba35d`, [run 45621367786](https://github.com/vishu762701/eve-app/actions/runs/45621367786)): Chats -> Exams backed by Worker API (`Eve-debug-apk` `15026278212`).
- Mission 3 (`ac93600`, [run 45628596616](https://github.com/vishu762701/eve-app/actions/runs/45628596616)): Account cleanup (`Eve-debug-apk` `15031321621`).
- Mission 4 (`85e4baa`, [run 45631733282](https://github.com/vishu762701/eve-app/actions/runs/45631733282)): Exam Settings cleanup (`Eve-debug-apk` `15032148596`).
- Mission 5 (`953d972`, [run 45635757790](https://github.com/vishu762701/eve-app/actions/runs/45635757790)): English-only locale lock (`Eve-debug-apk` `15033762124`).
- Mission 6 (`1ce565f`, [run 45639900836](https://github.com/vishu762701/eve-app/actions/runs/45639900836)): `Telegram Features` removed, `EVE Premium` + star animation preserved (`Eve-debug-apk` `15037010963`).
- Mission 7 (`28b6bae`, [run 45645287608](https://github.com/vishu762701/eve-app/actions/runs/45645287608)): Worker+D1 EVE Admin Control Center (`Eve-debug-apk` `15039206873`).
- Mission 8 (Integration & Regression): `EveIntegrationRegressionTest` verifies Missions 1..7 + MTProto/Worker/D1 boundaries.

## Next
- Verify final Mission 8 CI green run on `main`, then owner runs device screenshot/video acceptance on latest `Eve-debug-apk`.

## Open decisions
- Legacy Firebase retirement and production D1 migration remain separate from normal CI. No production deployment is authorized by this handoff.
