## Done
- Mission 01A: 8 placeholder exam rows rendered by official Telegram DialogCell.
- Mission 00C: Added BUILD_FAILURE_RULES.md and linked from AGENTS.md.
- Mission 01B-R: Replaced hand-written screen scaffolding with transplanted Telegram code (BoolAnimator, search field margins, tabs, floating button).
- Permanent Port Guards: Added .github/scripts/verify_telegram_port.py and test_verify_telegram_port.py. Expanded PORT_MANIFEST.json to 324 tracked entries (all org/telegram, me/, and eve importing files). Removed paths-ignore bypasses and gated build on integrity job.

## In progress
- Guardrail mission in progress until green CI on exact SHA.

## Next
- Owner review of guardrail commit and build.
- Owner will provide the 23 newer mission .md/prompt files.
- Owner device acceptance testing for 01B-R (remains OPEN until owner tests APK and provides comparison/logcat).

## Known bugs
- 01B-R is NOT ACCEPTED; phone/runtime testing is UNVERIFIED.

## Open decisions
- None.
