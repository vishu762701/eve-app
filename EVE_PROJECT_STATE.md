# EVE project state

## A. Project identity
Repository: https://github.com/vishu762701/eve-app ; development branch: `main` only. Telegram UI authority: https://github.com/DrKLO/Telegram at `21f7d109da6ab3506c9e9577f98c6a38771ab335`. EVE is an Android exam-preparation app. Kotlin/Java host and business logic bind to Telegram Java components. Firebase Auth and FCM, a Cloudflare Worker and D1, and legacy Firebase components have separate responsibilities. No production deployment is implied by a code change.

## B. Current verified baseline
- Observed remote main: `a5a22bf26d2b8a5ef857dd721cb696aacc4a7125`. This is the inspected implementation baseline, not a prediction of the commit carrying this note. Verify HEAD on resumption. No shared working tree is assumed.
- [FAST 29](https://github.com/vishu762701/eve-app/actions/runs/45019915017) passed on that SHA; the FULL Gradle and emulator jobs did not run. [Source-integrity job](https://github.com/vishu762701/eve-app/actions/runs/45019915017/job/137200313458) passed the existing verifier. Its 324 files and 12 blocks do not establish deletion-only CUTs or runtime use.
- [FULL 21](https://github.com/vishu762701/eve-app/actions/runs/45011538913/job/137174828172) failed on `8911615d4e4d16071f19ce110041558647e4820e` at `:app:testDebugUnitTest`. No artifact was retained for that run. It is not a FULL result for the later main SHA.
- Working change for S0: patch validated locally against workflow blob `e3cd2435986f82e7e0ae80bfe4d8e2e584f5874a`, not yet installed on this baseline. The original seven jobs remain; only one step ID and two failure-evidence steps are added.

## C. Development rules
- Direct, non-forced main pushes only, one feature per reversible commit series. No staging branches, PRs, history edits or probes on main. Check bytes before writing, then verify the remote blob, head and Actions verdict for the exact SHA.
- Inspect the pinned class and its complete dependency closure. Prefer verbatim imports; only documented deletion-only cuts. Preserve headers, attribution, component relationships and UI behavior. No Kotlin/XML/Compose/Material substitutes. EVE adapters own data, never Telegram drawing or gestures.
- Require real navigation, runtime classes/hierarchy, comparable emulator screenshots or video, and full applicable tests (unit, backend, instrumentation, native renderer, lint, source integrity). Track phone-only acceptance separately. Only the owner can accept. Never skip or weaken a check.
- For crashes: reproduce, capture a real exception and first causal application frame, demonstrate a failing regression, make the smallest justified fix and rerun. No speculative fixes, secrets, production-data changes, or weaker auth, access, signing or license controls.

## D. Mission and screen tracker
Statuses describe this delivery and verification effort, not an assertion that legacy features do not exist.

| Mission | Status | Scope or dependency |
|---|---|---|
| S0 | IN PROGRESS | FULL JUnit evidence; exact patch prepared, no patched main FULL result |
| S1 | BLOCKED | Repair identified unit failures after S0 evidence |
| S2 | IN PROGRESS | Restore native UI, visual review and port smoke checks; actual emulator results required |
| 00A / 00B | IN PROGRESS | Profile crash evidence and regression; telemetry deployment not assumed |
| 00C | IN PROGRESS | Existing hash gate lacks deletion-only proof |
| T1 | BLOCKED | Original navigation host, drawer, stack, transitions and swipe-back; stability and evidence gates open |
| T2 | BLOCKED | Runtime services, images and animation; T1 open |
| T3 | BLOCKED | Dialogs, sheets, menu and bulletins; foundational runtime open |
| T4 | BLOCKED | DialogCell, recycler, adapters, loading and animation |
| T5 | BLOCKED | Published Worker exams through T4; eight debug rows are not a catalogue |
| T6 | BLOCKED | Original shell, drawer, tabs, search and categories |
| T7 | BLOCKED | Original Profile, avatar and actions; separate from 00A |
| T8 | BLOCKED | QR sharing, username and scanner; verify each separately |
| T9 | BLOCKED | Settings root, language, appearance/theme, notifications, passcode, cache, privacy and profile editors; separate screen evidence |
| T10 | BLOCKED | Remove only validated, unused replacements |
| E1 authentication; E2 catalogue; E3 test engine; E4 results; E5 leaderboard; E6 practice; E7 notifications; E8 polls; E9 premium; E10 admin | NOT STARTED | New mission-level verification; existing code is not acceptance |
| E11 legacy backend decision | BLOCKED | Keep existing systems until a deliberate retirement decision |
| E12 backend hardening | NOT STARTED | Preserve access and scoring boundaries |
| E13 release readiness | BLOCKED | Full checks, crash and screen evidence outstanding |

## E. Completed work - append-only history
- 2026-11-22, S0 reporter scripts milestone only: `.github/scripts/ci_failure_{report,inputs,trace}.py` and `test_ci_failure_{report,trace}.py`. Integrated JUnit, Gradle and trace reading; no Telegram classes, port modes, source hashes or UI mapping apply. Commit `0b95fac27cc820738e54a134262a1094db9dfe59`, [FAST run 28](https://github.com/vishu762701/eve-app/actions/runs/45018325678). The recorded Python suite passed (29); not an Android FULL or screen verdict.
- 2026-11-22, S0 package validation only: `.github/workflows/build.yml`, 16 insertions. [Read-only job](https://github.com/vishu762701/eve-app/actions/runs/45024773271/job/137215287202) applied the patch to the baseline, checked bytes and passed 17 reporter tests. Packaged commit `e090ad55ea0c2d9c0576a37b0f7f3bc5607f5635` is not a main push. No app, Telegram or production data change. FULL still outstanding.

## F. Active bugs
- PROFILE-001: tapping Profile crashes on the owner's device. No verified stack or first causal frame, no proven root cause and no fix commit. `ProfileNavigationNativeTest.kt:33-41` clicks the drawer avatar without asserting the destination; lines 51-62 allow a finishing activity. Neither proves authenticated Profile rendered. Preserve the existing Robolectric tests. Obtain sanitized logcat/exit info or Crashlytics for the same build and route before changing production code. Status: IN PROGRESS.
- FULL-001: FULL fails at unit tests. The last Gradle wrapper exception is not the application frame. Exact test names and causes remain unknown; do not treat reporter fixtures as production traces. Status: BLOCKED on S0.
- SMOKE-001: `PortedScreensSmokeTest.kt:249-255,290-303` reads a class name but can fall back to one pilot screen; a nonempty view alone is not proof of that Telegram class. Status: IN PROGRESS, no validated repair.

## G. Current work position
NEXT ACTION: on current main, check the workflow preimage, apply `eve-s0-full-junit.patch` in a byte-preserving Git checkout and verify exactly 18,832 bytes, SHA-256 `8711d0ebff93dcee58bc2903d782d81c9ccd7ba133939d47d9802b6477dd4577`. Push directly to main, re-read by immutable SHA, then dispatch `build.yml` with `publish_verified_apk=false`. Read the JUnit evidence. Do not dispatch a production deployment.

Transport blocker: a full-text upload previously returned only 13,751 bytes. An old-file SHA guards against concurrent changes, not corruption of new content. Do not replay that upload on main or merge the old probes. Targeted short writes do not establish that a large workflow is safe. Independently restore the three missing helpers and collect Profile evidence; neither is a reason to start a dependent screen.

## H. Permanent handoff
Read `EVE_PROJECT_STATE.md`, `AGENTS.md`, `BUILD_FAILURE_RULES.md`, `HANDOFF.md` and `PORT_MANIFEST.json` before editing. Refresh the actual remote head and runs, then continue from NEXT ACTION; do not repeat the audit. Keep HANDOFF concise. Record each milestone with the checked implementation SHA, test and Actions results, evidence, and remaining gaps. Append history; correct old claims with a dated entry rather than erasing them. A checkpoint cannot contain its own Git SHA: read the carrying commit from Git.
