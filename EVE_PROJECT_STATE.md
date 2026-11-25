# EVE project state

## A. Project identity
Repository: https://github.com/vishu762701/eve-app ; branch: `main`. Telegram Android: https://github.com/DrKLO/Telegram at `21f7d109da6ab3506c9e9577f98c6a38771ab335`. Authoritative Android client base: `android/` (`android/TMessagesProj_App`, applicationId `com.eve.app`, variant `:TMessagesProj_App:assembleAfatDebug`, AGP `8.13.2`, Gradle `8.13`, compileSdk/targetSdk `36`, NDK `27.2.12479018`, CMake `3.22.1`). Legacy root `app/` is preserved intact as reference for migrating EVE features. Backend: Firebase Auth/FCM, Firestore, Firebase Functions, Cloudflare Worker and D1.

## B. Current verified baseline
Authoritative verified build commit: `02721cfe70f7d2c93efd28d84e426bdef371eec6`. Green push CI: [run 45598672732](https://github.com/vishu762701/eve-app/actions/runs/45598672732) (`Telegram Port Integrity` `139163321602`, `backend` `139163322125`, `firebase-functions` `139163404854`, and `Build New Android App (android/)` `139163338221` all passed; credential preflight & `HAS_CUSTOM_TELEGRAM_API_CREDENTIALS=true` verified; `Eve-debug-apk` artifact `15016588526` `72,117,361` bytes, `aapt2` verified `package: name='com.eve.app' versionCode='73039' versionName='13.2.2'`). Owner confirmed Telegram phone-number login works on device in the EVE APK.

Historical legacy-`app/` baseline (superseded as primary app on 2026-11-25, preserved for reference):
- [FAST 41](https://github.com/vishu762701/eve-app/actions/runs/45027680760) passed at `a2ee6ef5d0e05314705a28c140586e64e8ce8be4`.
- [Last FULL (legacy)](https://github.com/vishu762701/eve-app/actions/runs/45011538913/job/137174828172) failed at `:app:testDebugUnitTest` on `8911615d4e4d16071f19ce110041558647e4820e`.
- Unfiltered JVM [before](https://github.com/vishu762701/eve-app/actions/runs/45026877717/job/137221732340): 255 failures of 1,305. [After](https://github.com/vishu762701/eve-app/actions/runs/45027710308/job/137224271291): 153 failures of 1,306.
- Legacy `e8d41490a372fb32fb2350c08730e52dd7d7ef94` push [run 45555445272](https://github.com/vishu762701/eve-app/actions/runs/45555445272) passed; dispatch [run 45555482891](https://github.com/vishu762701/eve-app/actions/runs/45555482891) failed on `Native UI (three-button)` (`139009946511`).

## C. Development rules
Direct non-forced main pushes only; one independently reversible feature per change series. Verify input bytes, entire diff, immutable remote SHA and exact-SHA Actions. No alternative branches or PRs. Inspect the complete pinned Telegram dependency closure; use verbatim originals or documented deletion-only cuts. Preserve headers, GPL attribution, layout, drawing and interaction. EVE adapters supply data, not substitute UI. Prove real navigation, runtime hierarchy, matched screenshots/video and full applicable unit/backend/native/instrumentation/emulator/lint/source checks. Only the owner accepts. Reproduce crashes, identify the first causal frame, add a failing regression, make the smallest justified fix and retest. Never weaken checks, authentication, permissions, signing or production-data safeguards.

## D. Mission and screen tracker
Statuses describe the new delivery effort, not the absence of existing code.

| Mission | Status | Scope |
|---|---|---|
| NEW-BASE-BUILD | COMPLETE | `android/:TMessagesProj_App:assembleAfatDebug` (`com.eve.app`), `:TMessagesProj:testDebugUnitTest`, submodules, `backend`, `firebase-functions`, `telegram-port-integrity` green on [run 45560197466](https://github.com/vishu762701/eve-app/actions/runs/45560197466) (`02f9f73`) |
| S0 (legacy) | SUPERSEDED | Legacy `app/` workflow superseded by `android/` authoritative build on `02f9f73` |
| S1 (legacy) | SUPERSEDED | Legacy `app/` JVM diagnostic superseded by `android/` base |
| S2 (legacy) | SUPERSEDED | Legacy `app/` emulator harness superseded; `app/` retained as migration reference |
| 00A / 00B (legacy) | SUPERSEDED | Legacy `app/` Profile crash and telemetry |
| 00C | IN PROGRESS | Strict deletion-only proof outstanding |
| T1 | IMPLEMENTED | Authentic Telegram ActionBarLayout + DrawerLayoutContainer navigation host integrated into TelegramHostActivity and MainActivity; stack push/pop, gesture physics, dual container rendering, and INavigationLayout delegation verified; 327 tracked manifest files passing all integrity gates |
| T2 | BLOCKED | Runtime services, images and animation |
| T3 | BLOCKED | Dialogs, menus, sheets and bulletins |
| T4 | BLOCKED | DialogCell, recycler, adapters and list animation |
| T5 | BLOCKED | Published Worker exams through T4, not debug rows |
| T6 | BLOCKED | Shell, tabs, drawer, categories and search |
| T7 | BLOCKED | Original Profile, avatar and actions; separate from 00A |
| T8 | BLOCKED | QR sharing, username and scanner |
| T9 | BLOCKED | Root, language, appearance, notifications, passcode, cache, privacy and profile editors; separate evidence |
| T10 | BLOCKED | Remove only validated, unused replacements |
| E1 authentication; E2 catalogue; E3 test engine; E4 results; E5 leaderboard; E6 practice; E7 notifications; E8 polls; E9 premium; E10 admin | NOT STARTED | New mission-by-mission verification |
| E11 legacy backend decision | BLOCKED | No retirement without decision |
| E12 backend hardening | NOT STARTED | Preserve server authority |
| E13 release readiness | BLOCKED | FULL and runtime evidence missing |

## E. Completed work - append-only history
- 2026-11-22, S0 reporter scripts milestone only: `.github/scripts/ci_failure_{report,inputs,trace}.py` and `test_ci_failure_{report,trace}.py`. Integrated JUnit, Gradle and trace reading; no Telegram classes, port modes, source hashes or UI mapping apply. Commit `0b95fac27cc820738e54a134262a1094db9dfe59`, [FAST run 28](https://github.com/vishu762701/eve-app/actions/runs/45018325678). The recorded Python suite passed (29); not an Android FULL or screen verdict.
- 2026-11-22, S0 package validation only: `.github/workflows/build.yml`, 16 insertions. [Read-only job](https://github.com/vishu762701/eve-app/actions/runs/45024773271/job/137215287202) applied the patch to the baseline, checked bytes and passed 17 reporter tests. Packaged commit `e090ad55ea0c2d9c0576a37b0f7f3bc5607f5635` is not a main push. No app, Telegram or production data change. FULL still outstanding.
- 2026-11-22, S1 diagnostics: `.github/workflows/unit-diagnostics.yml` at `4b5ab114a3f32b5a292812990da82805e0de878b` collected real Gradle/JUnit results with read-only permissions. [Job](https://github.com/vishu762701/eve-app/actions/runs/45026877717/job/137221732340). No Telegram class, port mode or data mapping applies; evidence collection succeeded, Android unit suite failed. Not FULL.
- 2026-11-22, T1 authentic Telegram navigation architecture:
  - Imported and integrated original Telegram navigation architecture: `ActionBarLayout.java` (CUT, 167,106 bytes, deletion-only cut of uncoupled optional features), `DrawerLayoutContainer.java` (VERBATIM, 7,257 bytes, SHA-256 `883035388ebadfaec375a5c5ca8e87183f63ac7d299b0172bb1fa63f9c3c9a5d`), `EdgeToEdgeSupportMode.java` (VERBATIM, 107 bytes, SHA-256 `41a360aeb5f842642adead431eaad9c90f7800a826d7b12fe5af2debf0061b00`), and official `layer_shadow.webp` drawables across hdpi/mdpi/xhdpi/xxhdpi.
  - Refactored `TelegramHostActivity.kt`: replaced fake shims (`presentFragment` returning false, disabled swipe, no transitions) with genuine `ActionBarLayout` and `DrawerLayoutContainer` hosting and lifecycle delegation.
  - Integrated `MainActivity.kt`: wrapped existing EVE Home content inside `EveHomeFragment : BaseFragment` and hosted through `ActionBarLayout` + `DrawerLayoutContainer`, preserving 100% of EVE authentication, HomeViewModel, exams, banners, polls, and back behavior while establishing authentic Telegram stack control.
  - Added focused regression test suite: `app/src/test/java/com/eve/app/ui/common/TelegramNavigationIntegrationTest.kt`.
  - Expanded `PORT_MANIFEST.json` to 327 verified entries covering 100% of `org/telegram` and referencing files; `verify_telegram_port.py` passes 100% with 0 errors.
- 2026-11-25, New Telegram-source Android base import (`8a69126fbc518337327fe0fca0da44ac3b4a9869`) and legacy `app/` stabilization (`8d88a63`, `2e26dfe`, `e8d4149`):
  - Imported transformed Telegram Android source (`eve-transformation`, Missions 01-12, `13,548` files) into `android/` (`android/TMessagesProj_App` `com.eve.app`, 16 git submodules in `.gitmodules`), preserving root `app/`, `backend/`, `functions/`, Firebase rules, docs, and `.github/`.
  - Legacy `e8d4149` push [run 45555445272](https://github.com/vishu762701/eve-app/actions/runs/45555445272) passed FAST; manual dispatch [run 45555482891](https://github.com/vishu762701/eve-app/actions/runs/45555482891) failed at `Native UI (three-button)` (`139009946511`).
- 2026-11-25, Owner final architecture decision & new-base build recovery (`02f9f73c86f2918c8a3615ea13a53857339e5059`, [run 45560197466](https://github.com/vishu762701/eve-app/actions/runs/45560197466)):
  - Audited old workspaces (`eve-mock-test-app` clean at `82918aec`, `eve-app-migration-push` clean at `8a69126f`) and initialized `android/` git submodules locally (`git submodule update --init --recursive`).
  - Updated `.github/workflows/build.yml` so `android/` (`:TMessagesProj_App:assembleAfatDebug`) is the authoritative APK build target with recursive submodule checkout at pinned SHAs, SDK `36`, NDK `27.2.12479018`, CMake `3.22.1`, `nasm`, `:TMessagesProj_App:tasks --all` verification, `:TMessagesProj:testDebugUnitTest`, `aapt2 dump badging` verification (`package: name='com.eve.app' versionCode='73039' versionName='13.2.2'`), and `Eve-debug-apk` (`71,945,434` bytes) artifact upload.
  - All 4 CI jobs passed on `02f9f73`: `Telegram Port Integrity` (`139021796081`, 20s), `firebase-functions` (`139021796497`, 1m0s), `backend` (`139021796623`, 35s), and `Build New Android App (android/)` (`139021947578`, 14m35s; `:TMessagesProj_App:assembleAfatDebug` 11m2s, `:TMessagesProj:testDebugUnitTest` 5s).
- 2026-11-25, Mission T0 Official Telegram Login restoration (`ef335a5`, `3ee6521`, `1d5e333`, `0dc11924c99ed01d1314287f141d66e28b7c920f`, [run 45591878290](https://github.com/vishu762701/eve-app/actions/runs/45591878290)):
  - Restored upstream Telegram login/intro/verification closure (`IntroActivity.java`, `AuthTokensHelper.java`, `CallReceiver.java`, `SmsReceiver.java`, `FirebasePhoneNumberVerificationHelper.java`, `res/raw/contacts_sync_on.json`, `LoginActivity.java`) and callers (`LaunchActivity`, `MainTabsActivity`, `LogoutActivity`), deleting obsolete `EveAuthFragment.java` and `EveHomeFragment.java`.
  - Removed `EveInspectionMode` login bypass, synthetic local user injection, and `-401` MTProto gate; added Gradle/BuildConfig injection (`TELEGRAM_APP_ID`, `TELEGRAM_APP_HASH`, `HAS_CUSTOM_TELEGRAM_API_CREDENTIALS`) and Robolectric behavioral suite `OfficialTelegramLoginTest.java`.
  - All 4 CI jobs passed on `0dc1192` (`Eve-debug-apk` `15012681733`, `72,115,076` bytes). Owner phone test hit `CONNECTION_API_ID_INVALID` because GitHub Actions secrets `EVE_TELEGRAM_APP_ID` / `EVE_TELEGRAM_APP_HASH` were not yet configured (`gh secret list` empty), resulting in `APP_ID=0`.
- 2026-11-25, Login credential preflight + India (`+91`) deterministic default (`edbe949`, `02721cfe70f7d2c93efd28d84e426bdef371eec6`, [run 45598672732](https://github.com/vishu762701/eve-app/actions/runs/45598672732)):
  - `LoginActivity.PhoneView` now selects India (`IN`, `+91`, name/flag/`XXXXX XXXXX` hint) deterministically from country data without SIM/`help.getNearestDc`, preserves India or manual selection across async `help.getCountriesList`, and prevents silent `API_ID_INVALID` swallowing in `TL_auth_sendCode`.
  - Added fail-fast credential preflight and masked `BuildConfig` verification in `.github/workflows/build.yml` and `android/TMessagesProj/build.gradle` for authoritative APK builds (`:TMessagesProj_App:assembleAfatDebug`), plus regression tests in `OfficialTelegramLoginTest.java`.
  - Owner configured `EVE_TELEGRAM_APP_ID` and `EVE_TELEGRAM_APP_HASH` secrets; all 4 CI jobs passed on `02721cf` (`Eve-debug-apk` `15016588526`, `72,117,361` bytes) and owner verified Telegram phone-number login works in the EVE APK.
- 2026-11-25, Baseline audit & Mission 1 (EVE Brand Identity) implemented on `main` (pushed on top of `02721cf`):
  - Reconciled baseline evidence (`02721cf`, `run 45598672732`, `verify_telegram_port.py` 327 files + 12 blocks + 29 Python unit tests, `functions` 19/19 local tests).
  - Replaced launcher icon & adaptive icon (`ic_eve_launcher_background.xml`, `ic_eve_launcher_foreground.xml`, `ic_eve_launcher_monochrome.xml`, `mipmap-anydpi-v26/ic_launcher*.xml`, `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher*.png`, `LauncherIconController.DEFAULT`), manifests (`config/{debug,release}/AndroidManifest*.xml`), splash animated vector (`eve_splash_320.xml` in `values-v31/styles.xml` and `values-night/styles.xml`), Intro branding (`IntroActivity.java`, `Page1Title`-`Page6Message`, `StartMessaging`), main screen ActionBar title (`DialogsActivity.java`), localized `AppName`/`AppNameBeta` (`EVE`), and Settings version + third-party Telegram API disclosure (`SettingsActivity.java`, `R.string.EveThirdPartyApiDisclosure`).
  - Added `android/TMessagesProj/src/test/java/org/telegram/ui/eve/EveBrandIdentityTest.java`.
  - CI cycle 1 (`f98cdcbe018ce605ad2447d49085b37b3db36bf9`, [run 45612581390](https://github.com/vishu762701/eve-app/actions/runs/45612581390)): `Telegram Port Integrity` (`139199652989`), `firebase-functions` (`139199653498`), `backend` (`139199653784`) passed; `Build New Android App (android/)` (`139199993993`) failed at `EveBrandIdentityTest.java:51` (`Page1Message` lacked `"EVE"`). Fixed `Page1Message` in `strings.xml` and localized `Page1Title`/`TelegramVersion` in `values-*/strings.xml`.

## F. Active bugs
- PROFILE-001, IN PROGRESS: owner reports a crash on tapping Profile. Two authenticated Robolectric tests reproduce `ProfileActivity.onCreate:187 -> ProfileTelegramViewTree.<init>:153 -> createCellValue:389`. `valueViews.add(this)` precedes the field initializer at line 241. This is the proven JVM cause; the exact phone event is not correlated. No main fix commit. The prepared change moves only the field, updates its EVE `NEW` hash and adds a four-row theme registration test. Post-fix Android and phone checks outstanding; not T7.
- FULL-001, IN PROGRESS: 77 remaining tests group at `Theme.java:2809` (54 initialization errors and 23 direct null-context errors); 17 dialog cast failures are also visible. Representative test-side cast: `EditExamDeferredDeleteTest.kt:68`. Missing provenance files and other assertions remain genuine failures.
- SMOKE-001, IN PROGRESS: the existing smoke test can fall back to one screen and accept a generic nonempty host. The prepared S2 change requires a packaged six-host inventory, attached fragment classes, live layout and fresh evidence.

## G. Current work position
**NEXT ACTION:** Verify Mission 1 CI green run on `main`, then execute Mission 2 (Main Screen: Chats becomes Exams using original `DialogsActivity` + `DialogCell` UI backed by EVE Worker API).

Historical legacy-`app/` notes (pre-handover):
- Offline fixture `ef2666ce97a833bf21deb4e0f954e1423cd606e3` and `a2ee6ef5d0e05314705a28c140586e64e8ce8be4` in `app/` remain preserved untouched in `app/`.
- Legacy patches in `EVE-main-repairs.zip` (`02-Profile.patch`, `03-S2.patch`, `eve-s0-full-junit.patch`) targeted legacy `app/` and are superseded by the authoritative `android/` base.

## H. Permanent handoff
Read `EVE_PROJECT_STATE.md`, `AGENTS.md`, `BUILD_FAILURE_RULES.md`, `HANDOFF.md` and `PORT_MANIFEST.json` before editing. Refresh actual main and its runs, then continue from NEXT ACTION, not a new audit. Append completed history without erasing entries. Record mission, date, files, source classes/modes/hashes, integration, tests, SHA, Actions URL and outstanding evidence. Keep HANDOFF concise. Read the carrying checkpoint's own SHA from Git; do not generate recursive status-only commits.
