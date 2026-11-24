# Backend agent entrypoint

Follow the repository-root `AGENTS.md`, `docs/EVE_TELEGRAM_UI_REQUIREMENTS.md`, and `BUILD_FAILURE_RULES.md`. This file adds no independent product/UI policy.

For backend work, inspect the actual Android caller contracts, Cloudflare Worker routes, D1 schema/migrations, Firebase Auth/Functions, Firestore/Storage rules, authorization and data ownership affected by the task. Preserve server-side authority, scoring integrity and backward compatibility. Add/run relevant backend and rule-emulator tests. Do not deploy, run destructive migrations or modify production data without explicit owner approval.
