# BUILD_FAILURE_RULES.md — mandatory failure diagnosis and repair policy

These rules apply to every agent and developer. They supplement `AGENTS.md` and may be made stricter by the owner, never weaker.

## 1. First causal error before edits

1. Fetch the exact failing workflow run, commit SHA, failed job and complete logs.
2. Find the first causal error/assertion/exception (not the last cascading Gradle message).
3. Record file, line, task/test, actual exception and why it is causal.
4. Classify exactly one primary category:
   - `TELEGRAM_SOURCE`: pinned upstream Java/Kotlin/resource/JNI code;
   - `EVE_ANDROID`: EVE Android Java/Kotlin/UI/business code;
   - `RESOURCE`: Android resource, theme, drawable, manifest or AAPT;
   - `DEPENDENCY_TOOLCHAIN`: Gradle/Maven/NDK/CMake/Rust/SDK resolution or mismatch;
   - `BACKEND_DATA`: Worker, D1, Firebase, rules, migrations or API;
   - `CI_CONFIG`: workflow/script/runner wiring or missing CI file;
   - `INFRA`: runner/network/service outage or proven infrastructure timeout.
5. Do not edit code until the root cause and evidence are written down.

## 2. Infrastructure failures

Retry once without code changes. If it passes, report it as flaky infrastructure. Do not change application code to compensate for an infrastructure-only failure. If it repeats, capture runner/network evidence and continue independent work.

## 3. Telegram-derived source failures

Check the affected file against `PORT_MANIFEST.json` and the pinned Telegram SHA.

- `VERBATIM`: imported file bytes must match the pinned upstream source exactly.
- `CUT`: differences must be deletion-only and precisely recorded.
- `SHIM`: minimal and explicitly documented; never a replacement UI.
- `TRANSPLANT` / `EVE-COPY`: only byte-identical copied blocks may use these labels.
- `COMPOSED` / `EVE-REF`: adapted code must be separately reviewed and must not replace original Telegram rendering/interaction when the upstream component can be used.
- `NEW`: EVE-owned code only; it must not be represented as original Telegram code.

A hash match does not prove runtime use. Check the actual navigation entry point, live view hierarchy and behavior. Restore unapproved drift. Update manifest hashes/provenance atomically with source changes.

## 4. Fix order

1. Reproduce failure with the smallest relevant test or workflow job.
2. Add/retain a regression test that fails before the fix.
3. Inspect the pinned upstream implementation and full dependency/resource closure.
4. Make the smallest root-cause fix that preserves original Telegram UI behavior and EVE functionality.
5. Do not change Telegram measurement, rendering, colors, dimensions, gesture physics or animations to hide a failure.
6. Run the focused test, affected module tests, relevant integration tests and required CI gates.
7. Read back the exact committed bytes and verify the tested SHA matches the commit.

Never replace an upstream screen/component with a custom lookalike as a build workaround.

## 5. Build/toolchain configuration

Do not change SDK, NDK, CMake, AGP, Gradle, Rust, native renderer or dependency pins just to make a failure disappear. Inspect the pinned Telegram configuration and current EVE configuration, provide evidence of incompatibility, explain the impact and obtain owner approval for a version/pin change. Preserve the QR/native renderer and all ABI coverage unless the owner explicitly approves a product change.

## 6. Tests, security and integrity are non-negotiable

Never delete, skip, weaken or rewrite assertions to make CI green. Never disable lint, source-integrity checks, emulator gates, screenshot review, backend rules or security checks. Never suppress warnings/errors on Telegram-derived code to hide drift. Never loosen auth, RBAC, Firestore/Storage rules, server-authoritative scoring, submission integrity or release signing.

A stale source-contract test must be corrected to assert the actual migrated upstream behavior, not deleted or weakened. A test must not force reintroduction of obsolete UI solely to satisfy a stale assertion.

## 7. Required evidence and reporting

For every failure/fix cycle, record:

- run URL, tested commit SHA and failed job;
- category and first causal error with file:line;
- reproduction and regression-test evidence;
- files/lines changed and reason;
- focused test and full relevant CI results;
- whether emulator/physical-device behavior was actually tested;
- remaining `UNVERIFIED` or `BLOCKED` items.

Use `NOT FIXED` until a relevant green CI run proves the fix. A green build alone does not prove Telegram source fidelity, runtime use, screen completeness or visual acceptance.
