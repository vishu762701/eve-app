# EVE Handoff

**Authoritative Android client:** `android/` (`:TMessagesProj_App:assembleAfatDebug`), NOT legacy `app/`.
**Branch & Git rule:** Commit and push directly to `main` (no PRs/branches/force-push). Keep this file <= 30 lines.

## Done
- **0ms Speed Overhaul:** Added atomic Android SWR disk cache (`EveLocalDiskCache.java` under `cacheDir/eve_swr_cache/`, cleared on sign-out) wired into `EveExamRepository` & `EveAdminRepository` + `EveLeaderboardFragment`/`EveExamTestsFragment`/`EveAdminAuditLogFragment` UI diff updates + `EvePremiumRepository` `getIdToken(false)`-first retry + `EveBackendClient` gzip/keep-alive/8s connect timeout. Added Cloudflare Edge Cache (`backend/src/util/edgeCache.ts`) on student GETs (`/api/exams`, `/api/banners`, `/api/app-config` when non-maintenance, `/api/leaderboard`, public `/api/generated-tests`), automatic purges on mutations, and D1 `batch`/`Promise.all` parallelization (`monitoring.ts`, `leaderboard.ts`, `polls.ts`).
- **Multi-Provider AI Factory & Bank Assembler:** Added `backend/src/services/aiProviderRouter.ts` (`openrouter`, `groq`, `openai`, `gemini`, `"auto"` failover), Paper-Setter rubric + SHA-256 duplicate prevention (`ai/generator.ts`, `services/generation.ts`), and 0ms Rs-0 Question Bank Auto-Assembler (`POST /api/generated-tests/assemble-from-bank` + `EveAdminExamDetailFragment`), plus AI Provider/Model settings in `app_config` (`PUT /api/admin/config`, `EveAdminMaintenanceConfigFragment`).
- **Telegram Bot Admin Center & Mini App:** Added `backend/src/routes/telegramWebhook.ts` (`POST /api/telegram/webhook`), `backend/src/routes/telegramMiniApp.ts` (`GET /admin-miniapp` + HMAC-SHA256 verified `/api/telegram/miniapp/*`), `backend/src/services/telegramAdminAlerts.ts` (UPI/flags/feedback/crashes alerts), `POST /api/admin/telegram/setup-webhook`, `.github/workflows/deploy-worker.yml` secret sync, and `backend/test/speed_ai_telegram_control.test.mjs` (`412/412` backend pass).

## Next
- Check latest `Build Eve APK` workflow result on `main`, run `Deploy Eve Worker & Apply D1 Migrations`, set `TELEGRAM_OWNER_ID` (and optional `OPENROUTER_API_KEY`/`GROQ_API_KEY`/`OPENAI_API_KEY`/`TELEGRAM_WEBHOOK_SECRET`), then call `POST /api/admin/telegram/setup-webhook`.
