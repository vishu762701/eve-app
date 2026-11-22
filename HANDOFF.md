## Done
- Mission 01A: 8 placeholder exam rows rendered by official Telegram DialogCell.
- Mission 00C (Phase 1): Added BUILD_FAILURE_RULES.md, verify_telegram_port.py, 324 manifest files + 12 blocks, fail-closed CI gate.
- Mission 01B-R: Replaced hand-written screen scaffolding with transplanted Telegram code.
- Mission 00B: Crash observability foundation. Added D1 migration 0031, authenticated POST /api/crashes with 8 KB limit & PII redaction, admin GET /crashes, Crashlytics NDK dependency, privacy-hardened CrashlyticsHelper, and WorkManager CrashQueueWorker.

## In progress
- Mission 00B verification in progress on main.

## Next
- Owner review and controlled test crash verification for Mission 00B acceptance.
- Mission 00A follows after 00B acceptance when trustworthy crash evidence is captured.
- Owner device acceptance testing for 01B-R (remains OPEN until owner tests APK and provides comparison/logcat).

## Known bugs
- 00A remains BLOCKED_EVIDENCE until real crash trace is accessible.
- 01B-R is NOT ACCEPTED; phone runtime testing is UNVERIFIED.

## Open decisions
- None.
