## Done

- Mission 00C (Phase 1): port integrity gates, 324 manifest files and 12 blocks. Limit: the verifier does not prove that CUT files contain only upstream lines.
- Mission 00B code: crash telemetry (D1 migration 0031, `POST /api/crashes`, admin crash list, Crashlytics NDK, `CrashQueueWorker`, exit-info reader, Profile regression tests).
- Mission 01A, corrected: the dev-only `ExamListActivity` (debug manifest) shows 8 hard-coded fake rows, and `DialogCell.java` is a 27 KB rewrite labelled CUT, not the official cell.
- Mission 01B-R (NOT ACCEPTED): some screens host transplanted Telegram fragments through `TelegramHostActivity`; Profile, Settings, Theme and Cache are still Kotlin re-creations. Phone runtime testing is UNVERIFIED.
- Mission S0 (CI failure observability), scripts: `ci_failure_report.py`, `ci_failure_trace.py`, `ci_failure_inputs.py` and two test files in `.github/scripts`; 29 tests green in run 45018325678 for commit `0b95fac`.

## In progress

- Mission S0, wiring: `build.yml` must run the reporter after a failed FULL Gradle step. The patch is prepared but not committed (see Open decisions).
- Mission 00B: FULL has failed 4 times in a row at `:app:testDebugUnitTest` (runs 15, 17, 19, 21). The failing test names are not visible in the logs.

## Next

- Apply the `build.yml` patch, dispatch FULL once, read the failing tests and root causes, then repair them (Mission S1).
- Restore or replace the three scripts that `build.yml` calls but the repo lacks: `verify-native-ui.sh`, `eve_visual_review.py`, `verify-port-smoke.sh` (Mission S2).
- Owner dispatch of `deploy-worker.yml` for remote D1 migration 0031; that workflow has zero runs so far.
- Capture a real Profile crash trace from a device, then Mission 00A root-cause repair.

## Known bugs

- Profile-button crash on device, reported by the owner. No stack trace exists in the repo; root cause UNVERIFIED.
- FULL CI is red: unit tests fail, and the `native-ui` and `port-smoke` jobs will fail when reached because the scripts they call are missing.

## Open decisions

- Write path: the agent's file-write tool rewrites non-Python files as Markdown, so YAML, JSON, Gradle, Java and Kotlin cannot be committed faithfully. Owner to choose how source changes reach `main`.
- `BUILD_FAILURE_RULES.md` Rule 7: three fix cycles for the FULL unit-test failure are used; a fourth needs the owner's decision.
- Retire or keep the legacy Firebase Functions and Firestore backend next to the Worker and D1.