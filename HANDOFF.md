# EVE handoff — final requirements locked

1. Read `AGENTS.md`, `docs/EVE_TELEGRAM_UI_REQUIREMENTS.md`, `BUILD_FAILURE_RULES.md`, `EVE_PROJECT_STATE.md`, and `PORT_MANIFEST.json` before work.
2. Telegram source of truth: `DrKLO/Telegram@21f7d109da6ab3506c9e9577f98c6a38771ab335`.
3. Sole visual source: `docs/reference/eve-telegram-ui-reference-pack-25.zip` (25 owner screenshots; SHA-256 `ca5e07965817862ede93c206ba78dfc41d88234d0426b699ad25503c9d0c0ff7`).
4. All 25 screens/states are mandatory. Use actual upstream Telegram screens/components plus their full dependency closure; customize with real EVE data/actions.
5. No screenshot-only imitation, no custom replacement where upstream exists, no fake/dead controls, no silent omission.
6. Preserve existing EVE exam, account, admin, backend, data and security functionality.
7. First task after this rule update: create/complete `docs/EVE_TELEGRAM_SCREEN_SOURCE_MAP.md` for references 01–25, based on actual pinned source inspection.
8. Previous implementation-matrix `COMPLETE` claims are not final acceptance; revalidate every screen against the final contract.
9. PR #5: `gemini/eve-full-implementation` → `main`; observed HEAD `0ba658eb4842a2e71d08edea08fe9aee6b7d36a8`.
10. FULL run `45180331464`: 7/7 verification jobs passed; APK publication was skipped, so no APK artifact was uploaded by that run.
11. Use feature branches + PRs. Never push directly to main or rewrite history. Do not weaken tests, source integrity, auth, data, scoring or release gates.
12. Report exact commit, CI run, tests, visual evidence, APK artifact status and all unverified limitations. Only owner accepts final screens.
