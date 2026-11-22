# EVE project state

## A. Project identity
Repository: https://github.com/vishu762701/eve-app ; branch: `main`. Telegram Android: https://github.com/DrKLO/Telegram at `21f7d109da6ab3506c9e9577f98c6a38771ab335`. Android Java/Kotlin, Firebase Auth/FCM, Cloudflare Worker and D1. The exam, account, scoring and service logic belongs to EVE. Legacy Firebase systems remain in place.

## B. Current verified baseline
Last observed remote main before this checkpoint: `d609093b1ca228c1bbde49a9909fb14988830fed` (handoff). Last tested implementation: `a2ee6ef5d0e05314705a28c140586e64e8ce8be4`. Local S0, Profile and S2 patches are not part of this tree. No assertion about another checkout's working tree.

- [FAST 41](https://github.com/vishu762701/eve-app/actions/runs/45027680760) passed at the tested SHA, including the [current source-integrity gate](https://github.com/vishu762701/eve-app/actions/runs/45027680760/job/137224215733). The 324-file, 12-block manifest is not proof of deletion-only cuts or runtime use.
- [Last FULL](https://github.com/vishu762701/eve-app/actions/runs/45011538913/job/137174828172) failed at `:app:testDebugUnitTest` on `8911615d4e4d16071f19ce110041558647e4820e`. Pushes are FAST; dispatch of `build.yml` is FULL. Do not equate either with the separate JVM diagnostic.
- Unfiltered JVM [before](https://github.com/vishu762701/eve-app/actions/runs/45026877717/job/137221732340): 255 failures of 1,305. [After](https://github.com/vishu762701/eve-app/actions/runs/45027710308/job/137224271291): 153 failures of 1,306. Zero skipped in both. Both jobs are red.

## C. Development rules
Direct non-forced main pushes only; one independently reversible feature per change series. Verify input bytes, entire diff, immutable remote SHA and exact-SHA Actions. No alternative branches or PRs. Inspect the complete pinned Telegram dependency closure; use verbatim originals or documented deletion-only cuts. Preserve headers, GPL attribution, layout, drawing and interaction. EVE adapters supply data, not substitute UI. Prove real navigation, runtime hierarchy, matched screenshots/video and full applicable unit/backend/native/instrumentation/emulator/lint/source checks. Only the owner accepts. Reproduce crashes, identify the first causal frame, add a failing regression, make the smallest justified fix and retest. Never weaken checks, authentication, permissions, signing or production-data safeguards.

## D. Mission and screen tracker
Statuses describe the new delivery effort, not the absence of existing code.

| Mission | Status | Scope |
|---|---|---|
| S0 | BLOCKED | Verified workflow bytes; safe publication outstanding |
| S1 | IN PROGRESS | 153 JVM failures remain |
| S2 | IN PROGRESS | Helper and runtime-evidence patch prepared, not installed |
| 00A / 00B | IN PROGRESS | Profile crash and telemetry |
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

## F. Active bugs
- PROFILE-001, IN PROGRESS: owner reports a crash on tapping Profile. Two authenticated Robolectric tests reproduce `ProfileActivity.onCreate:187 -> ProfileTelegramViewTree.<init>:153 -> createCellValue:389`. `valueViews.add(this)` precedes the field initializer at line 241. This is the proven JVM cause; the exact phone event is not correlated. No main fix commit. The prepared change moves only the field, updates its EVE `NEW` hash and adds a four-row theme registration test. Post-fix Android and phone checks outstanding; not T7.
- FULL-001, IN PROGRESS: 77 remaining tests group at `Theme.java:2809` (54 initialization errors and 23 direct null-context errors); 17 dialog cast failures are also visible. Representative test-side cast: `EditExamDeferredDeleteTest.kt:68`. Missing provenance files and other assertions remain genuine failures.
- SMOKE-001, IN PROGRESS: the existing smoke test can fall back to one screen and accept a generic nonempty host. The prepared S2 change requires a packaged six-host inventory, attached fragment classes, live layout and fresh evidence.

## G. Current work position
**NEXT ACTION:** apply the [exact S0 patch](https://github.com/vishu762701/eve-app/blob/a7a04f24b7e3244403c87b184b5b78c8e147a98d/.byte-probes/eve-s0-full-junit.patch) using authenticated Git or a checked Base64 blob on main. Verify 18,832 bytes and SHA-256 `8711d0ebff93dcee58bc2903d782d81c9ccd7ba133939d47d9802b6477dd4577`, then dispatch `build.yml` FULL with `publish_verified_apk=false`. Preimage blob `e3cd2435986f82e7e0ae80bfe4d8e2e584f5874a`; postimage `4577ad431452418869987a08934b4f8ff9e2f774`. All seven original jobs are retained. The whole-text route previously stored only 13,751 bytes. This is a transport blocker, not a branch-protection rejection. Do not repeat that upload, merge the historical probes or grant CI write access to bypass it.

S1 on main: offline fixture `ef2666ce97a833bf21deb4e0f954e1423cd606e3` mirrors production UI initialization without starting production network services. The audit-log suite uses that fixture and asserts context identity; final source at `a2ee6ef5d0e05314705a28c140586e64e8ce8be4`. No authorization assertion was removed. Next isolate plain-Application fixtures from the remaining traces.

Two separate, unapplied patches in `EVE-main-repairs.zip`: `02-Profile.patch` SHA-256 `bfdf325e292d975ad54ff3f422d5773d6bf634da348a0a16d783806d9a526969`; `03-S2.patch` SHA-256 `41a369ce4be5c49f358bc3435d7cdebd753b3dacd5a2685669508db2d71ad380`. S2 includes `verify-native-ui.sh`, `eve_visual_review.py`, `verify-port-smoke.sh` and stronger instrumentation. Five harness tests passed locally; emulator execution and patched-app verification are outstanding. No production deployment or screen acceptance.

## H. Permanent handoff
Read `EVE_PROJECT_STATE.md`, `AGENTS.md`, `BUILD_FAILURE_RULES.md`, `HANDOFF.md` and `PORT_MANIFEST.json` before editing. Refresh actual main and its runs, then continue from NEXT ACTION, not a new audit. Append completed history without erasing entries. Record mission, date, files, source classes/modes/hashes, integration, tests, SHA, Actions URL and outstanding evidence. Keep HANDOFF concise. Read the carrying checkpoint's own SHA from Git; do not generate recursive status-only commits.
