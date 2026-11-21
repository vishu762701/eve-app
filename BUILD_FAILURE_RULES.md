# BUILD_FAILURE_RULES.md

Binding rules for any agent (Antigravity, Codex, or any other) when a CI build fails. They apply on top of AGENTS.md.

## Rule 0 — Precedence
A mission file may make these rules stricter (for example fewer fix cycles). It may never loosen them or widen its own scope to get a build green.

## Rule 1 — Find the root error, then classify
Read the log from the FIRST `error`, `FAILED`, `E0xxx` or `fatal` line. The Gradle stack trace at the end is usually only a symptom. Quote the root error with file and line, then put the failure in exactly one category. No fix starts before this is written down.
- `NATIVE_TELEGRAM`: Telegram's jni/C++ code.
- `NATIVE_EVE`: Eve's own native code (`eve_*`, Rust `tlottie`) or its toolchain.
- `JAVA_TELEGRAM`: failing file is under `org/telegram/`.
- `KOTLIN_EVE`: failing file is under `com/eve/`.
- `RESOURCE`: aapt, missing drawable, string, dimen, style or color.
- `DEPENDENCY`: Gradle or Maven resolution, version conflict.
- `CI_CONFIG`: `.github/`, a missing path, or a missing tool.
- `INFRA`: network error, runner problem, timeout, HTTP 5xx.

## Rule 2 — INFRA failures
Re-run once with no code change. If it passes, report it as flaky. Never change code because of an INFRA failure.

## Rule 3 — A Telegram-derived file fails
Look the file up in `PORT_MANIFEST.json`.
- `VERBATIM`: its sha256 must equal `upstream_sha256`. If not, restore the verbatim file from the pinned source and rebuild. If equal and it still fails, go to Rule 4.
- `CUT` or `SHIM`: its sha256 must equal the recorded `eve_sha256`. If not, someone edited it without recording; report the drift. Compare with upstream: every difference must sit inside an `EVE-CUT` or `EVE-SHIM` block. Restore upstream for any difference outside those blocks, then go to Rule 4.
- Not in the manifest: it is Eve-owned code.

## Rule 4 — The file matches its record and still fails
This is usually a missing dependency or resource, not an upstream bug. Try in this order and stop at the first that works:
1. The missing class, resource or string exists upstream at the pinned revision: add it verbatim, record it as `VERBATIM`, only if it is UI-only and within the mission's stop limits.
2. The missing symbol is used only by a feature the mission cuts: remove the usage inside an `EVE-CUT` block.
3. A minimal `EVE-SHIM` with only the members actually used, only if 1 and 2 are impossible.
4. If none work: stop and report file, line, exact error, and what upstream has at that line.
Never change Telegram layout, measure, draw, dimension, color or logic to make a build pass.

## Rule 5 — Build config pins (checked against the pinned Telegram source on 2026-11-21)
- Must match Telegram: `compileSdk` 36, `targetSdk` 36, NDK `27.2.12479018`, CMake `3.22.1`.
- Differ today and must NOT be changed without an owner decision: `minSdk` (Eve 24, Telegram 23), Android Gradle Plugin (Eve 8.5.2, Telegram 8.13.2), Gradle version.
- Eve-only components with no Telegram equivalent (the Rust toolchain for `tlottie` and the QR native renderer) are pinned by `.github/scripts/prepare-qr-native.sh`. Follow that file; do not change those pins without an owner decision.
- If a failure traces to one of these differences, stop and report the evidence. Do not bump versions on your own.

## Rule 6 — Every fix is recorded
- Every changed Telegram-derived file uses `// EVE-CUT` or `// EVE-SHIM` markers.
- `PORT_MANIFEST.json` records the file with `mode` and the new `eve_sha256`.
- The report lists every changed file and line.

## Rule 7 — Limits
At most 3 push-and-fix cycles per failure (or fewer if the mission says so). If a cycle makes things worse (a new category or more errors), revert that commit before trying anything else. After 3 cycles stop and report: category, root error, the last 40 lines of the failing step, and what was tried. A fourth cycle needs the owner's decision.

## Rule 8 — Report format
Category, root error (file:line), fix applied or none, files changed, CI run URL and status for every cycle, and everything UNVERIFIED. "Fixed" means a green CI run URL is in the report. Without it write `NOT FIXED`.

## Never allowed
- Deleting or weakening tests, lint, or CI steps to get green.
- Suppressing warnings or errors (`@SuppressWarnings`, lint baselines, `-w`, disabling checks) on Telegram files.
- Writing "verified" or "fixed" without a green CI run URL.
