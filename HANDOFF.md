## Done
- Mission 01A: 8 placeholder exam rows rendered by official Telegram DialogCell.
- Mission 00C (Phase 1): Port integrity gates, 324 manifest files + 12 blocks.
- Mission 01B-R: Replaced hand-written screen scaffolding with transplanted Telegram code.
- Mission 00B: Crash observability foundation & pipeline integration: D1 migration 0031, POST /api/crashes, admin GET /crashes, Crashlytics NDK, CrashQueueWorker atomic bounded persistence, DebugCrashReporter wiring, ApplicationExitInfoReader startup scan, and Profile navigation regression tests.

## In progress
- Mission 00B: Closed integration gaps; remote live D1 deployment and live device trace collection pending.

## Next
- Owner review / manual dispatch of deploy-worker.yml for remote D1 migration 0031.
- Capture live Profile crash trace from device via integrated telemetry.
- Execute Mission 00A root-cause repair once trace is confirmed.

## Known bugs
- Confirmed Profile-button crash on device; root-cause fix deferred to Mission 00A.
- 01B-R is NOT ACCEPTED; phone runtime testing is UNVERIFIED.

## Open decisions
- None.
