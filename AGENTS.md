# AGENTS.md — EVE authoritative engineering rules

EVE is an Android application for Indian government-exam preparation. Its required Telegram experience is defined by `docs/EVE_TELEGRAM_UI_REQUIREMENTS.md`. That file is the final product/source/UI contract and overrides conflicting older prompts, plans, matrices, comments and handoffs.

## 1. Mandatory reading and precedence

Before any implementation, planning or review mission, read:
1. `AGENTS.md`;
2. `docs/EVE_TELEGRAM_UI_REQUIREMENTS.md`;
3. `BUILD_FAILURE_RULES.md`;
4. `EVE_PROJECT_STATE.md` and `HANDOFF.md`;
5. `PORT_MANIFEST.json` and the affected source/tests.

The owner-approved final specification and exact 25-image archive are mandatory. Never substitute other screenshots, web images, AI mockups or an agent's preferred design. If instructions conflict, the final requirements document wins unless the owner explicitly changes it.

## 2. Source and screen fidelity — no shortcuts

- Telegram source of truth: `https://github.com/DrKLO/Telegram` at `21f7d109da6ab3506c9e9577f98c6a38771ab335`.
- Visual source of truth: `docs/reference/eve-telegram-ui-reference-pack-25.zip`, SHA-256 `ca5e07965817862ede93c206ba78dfc41d88234d0426b699ad25503c9d0c0ff7`.
- All 25 images/states are mandatory. Inspect the actual images.
- Use the actual original Telegram Activities/Fragments/views/cells/adapters/resources/theme/gesture/animation code and the complete required dependency closure for every applicable screen.
- Adapt original screens/components to EVE data and actions. Do not recreate their visuals with XML, Compose, Material, custom drawing or third-party UI where upstream code exists.
- A copied class that is not navigated to and present in the live view hierarchy is not implemented.
- Keep per-file provenance in `PORT_MANIFEST.json` and screen-level source/runtime/visual evidence in `docs/EVE_TELEGRAM_SCREEN_SOURCE_MAP.md`.
- Preserve upstream behavior and visuals. Deviations require prior explicit owner approval.

## 3. Product preservation and customization

- EVE branding, authenticated user data, exam data and EVE actions must replace screenshot sample data.
- Preserve all existing EVE product functionality and its backend/data/security contracts during migration.
- Every visible control needs a real EVE action. No dead controls, fake data or fabricated success states.
- Do not add unrelated Telegram backend/services solely because their labels appear in a screenshot. Map them to real EVE capabilities or record an owner decision/blocker; never silently skip a required screen/state.
- Do not remove existing code/data/migrations until callers, data, APIs, tests and replacement behavior are audited and owner approval is recorded where needed.

## 4. Mission execution

- The overall mission includes all 25 reference screens and complete EVE functionality. Work in coherent, reviewable batches, but do not redefine the scope to one screen and stop.
- First reconcile the live repository/PR/branch/CI. Then maintain `docs/EVE_TELEGRAM_SCREEN_SOURCE_MAP.md` with upstream paths/classes, dependency closure, provenance, EVE mapping, runtime entry points, tests and visual evidence for each image.
- Diagnose actual code and logs before changing code. State assumptions and blockers; do not guess.
- Keep a complete audit of existing EVE modules and explicitly track `PASS`, `FAIL`, `UNVERIFIED`, `BLOCKED`.
- A green compile or source hash is not proof of functional or visual fidelity.

## 5. Validation and evidence

- Run the relevant unit, backend, lint, native, AndroidTest, emulator, source-integrity and full CI gates.
- Capture actual EVE emulator/device screenshots and compare them to the exact supplied references under equivalent conditions.
- Prove navigation/runtime hierarchy and verify all visible interactions.
- Physical-device checks, production auth/network traffic and CI emulator checks are distinct; report each honestly.
- Never say `PASS`, `verified` or `fixed` without evidence. Use `UNVERIFIED` when not run.
- Preserve all tests and CI checks. See `BUILD_FAILURE_RULES.md` on every failure.

## 6. Branch, security and delivery

- Use a feature branch and PR into `main`. Never push directly to `main`, force-push, or rewrite history.
- Keep secrets, tokens, release keys and service-account files out of the repository.
- Enforce admin authority and scoring on the backend/rules; never trust client claims.
- Never weaken authentication, authorization, data rules, answer integrity, signing or release safeguards to get a green build.
- No production deploy, destructive migration, release or APK publication without explicit owner authorization.
- Preserve original license/copyright headers and comply with the pinned Telegram license. EVE must not misrepresent itself as the official Telegram app.
- Every report must include changed paths, commit SHA, exact workflow URL/status, test totals, artifacts, and unverified limitations.

## 7. Permanent project records

- `EVE_PROJECT_STATE.md` is the current factual checkpoint, not a stale backlog. Update it after meaningful milestones.
- `HANDOFF.md` is the concise entry point and must agree with the state file.
- `docs/EVE_TELEGRAM_UI_REQUIREMENTS.md` is the immutable owner-approved product/source/UI contract; only an explicit owner instruction may change it.
- Historical matrices or archived documents are reference material only and cannot overrule the final requirements.
