# EVE — current project state

**Last reconciled:** 2026-11-24  
**Repository:** https://github.com/vishu762701/eve-app  
**Current working PR:** https://github.com/vishu762701/eve-app/pull/5  
**PR branch:** `gemini/eve-full-implementation`  
**Observed PR HEAD:** `0ba658eb4842a2e71d08edea08fe9aee6b7d36a8`  
**Pinned Telegram:** `DrKLO/Telegram@21f7d109da6ab3506c9e9577f98c6a38771ab335`

## 1. Final owner requirements

The active, mandatory specification is `docs/EVE_TELEGRAM_UI_REQUIREMENTS.md` plus its exact 25-image archive at `docs/reference/eve-telegram-ui-reference-pack-25.zip`.

Archive SHA-256: `ca5e07965817862ede93c206ba78dfc41d88234d0426b699ad25503c9d0c0ff7`.

All 25 reference screens/states are mandatory. Actual pinned Telegram screen/component source and complete dependency closure must be integrated and run at runtime, customized with real EVE data/actions. No screenshot-only replicas or partial ports. The final requirements document controls over older prompts, trackers and matrix claims.

## 2. Live GitHub/CI checkpoint

PR #5 is open, from `gemini/eve-full-implementation` into `main`, with observed head `0ba658eb4842a2e71d08edea08fe9aee6b7d36a8`.

FULL workflow run #45180331464 was checked against GitHub on 2026-11-24. These seven verification jobs completed successfully:

- Telegram Port Integrity — success;
- build — success;
- backend — success;
- firebase-functions — success;
- Port Smoke — success;
- Native UI (gesture) — success;
- Native UI (three-button) — success.

The separate `Publish verified debug APK` job was **skipped**, not passed, because `publish_verified_apk` was false. Therefore that run did not publish an `Eve-debug-apk` workflow artifact. Do not claim an APK artifact exists for run #45180331464.

Run URL: https://github.com/vishu762701/eve-app/actions/runs/45180331464
PR URL: https://github.com/vishu762701/eve-app/pull/5

## 3. Important status distinction

The PR's previous implementation matrix and claims predate the owner's final locked source-code/screen requirements. Treat all prior `COMPLETE AND VERIFIED` labels as **PREVIOUS REPORT — REVALIDATION REQUIRED** against the final requirement set. A prior pass does not prove that all 25 screens use the original upstream screen/component code, full dependency closure, runtime navigation and visual parity.

No screen may be accepted under the final specification until its row in `docs/EVE_TELEGRAM_SCREEN_SOURCE_MAP.md` has complete source, dependency, runtime, functional and visual evidence and owner review.

## 4. Immediate next actions

1. Apply the replacement repository rules in this rules-update package on the current feature branch; verify exact files and commit SHA. Do not modify application code in the rules-only commit.
2. Reconcile all active instruction files (including nested `AGENTS.md` files) so none conflicts with the final requirements. `GEMINI.md` and `CLAUDE.md` should continue to point to root `AGENTS.md` only.
3. Re-audit the existing EVE app against the pinned Telegram commit and all 25 images. Produce `docs/EVE_TELEGRAM_SCREEN_SOURCE_MAP.md` with one row per image, exact upstream classes/files, full dependency closure, EVE data/action mapping, runtime entry point and tests/evidence.
4. Inspect the latest PR and CI again before source changes. Preserve current EVE functionality/backend/data. Plan the migration based on actual source evidence; do not start with cosmetic rewrites.
5. Implement all 25 mandatory reference screens/states in verified batches, using original Telegram code and complete dependencies, then run full CI and visual/functional acceptance.
6. Publish the verified APK only after explicit owner authorization and successful verification; confirm the artifact is actually present.

## 5. Rules for maintaining this file

This file is the factual current checkpoint. Update status only from current GitHub/source/test evidence. Do not copy stale NEXT ACTION entries from older project snapshots. Preserve important factual history only when it remains useful, but never let old instructions override `AGENTS.md` or `docs/EVE_TELEGRAM_UI_REQUIREMENTS.md`.
