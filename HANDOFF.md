# EVE handoff

Read `EVE_PROJECT_STATE.md` for the permanent record and NEXT ACTION. Development is on `main` only.

## Done
- T1 (Authentic Telegram Navigation Architecture): `ActionBarLayout.java` (CUT) and `DrawerLayoutContainer.java` (VERBATIM) integrated into `TelegramHostActivity.kt` and `MainActivity.kt`. Replaced fake stubs with genuine Telegram fragment stack, gesture physics, dual-container view recycling, and `INavigationLayout` delegation. Added `TelegramNavigationIntegrationTest.kt`. Expanded `PORT_MANIFEST.json` to 327 verified files; `verify_telegram_port.py` passes with zero errors.
- Main has a read-only, on-demand diagnostic for the entire debug JVM suite, the host startup correction for offline UI tests, and an additional sandbox-context regression. No production authorization or source pin changed.
- `AGENTS.md` and `BUILD_FAILURE_RULES.md` now permit evidence-based repair without routine approval. Only the owner accepts a screen.

## In progress
- S0: original patch verified against the main workflow preimage. Its 18,832-byte result is not yet on main; the whole-text transport previously truncated it. Use a verified blob or authenticated Git, not a probe or PR.
- S1: [before](https://github.com/vishu762701/eve-app/actions/runs/45026877717/job/137221732340) 255/1,305 failed; [after](https://github.com/vishu762701/eve-app/actions/runs/45027710308/job/137224271291) 153/1,306 failed, zero skipped. These are JVM results, not FULL.
- S2: three missing emulator/gallery entry points and strengthened checks are prepared as one patch, not installed or emulator-verified. T1-T10 stay open.

## Next
- Publish the exact S0 bytes on main, verify them by commit SHA, then run `build.yml` FULL with `publish_verified_apk=false`.
- Apply and test the Profile fix and manifest update atomically. Keep S2 separate. Never merge historical packaging probes.

## Known bugs
- PROFILE-001: confirmed JVM failure at `ProfileTelegramViewTree.kt:389`; the first value row is built at line 153, before `valueViews` at line 241. No post-fix Android run or same-build phone trace yet.
- FULL-001: 77 remaining JVM failures point to theme initialization; other failures include obsolete dialog casts and missing provenance files. Do not weaken assertions or invent evidence.

## Open decisions
- Legacy Firebase retirement and production D1 migration 0031 remain separate from normal CI. No production deployment is authorized by this handoff.
