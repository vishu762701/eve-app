# EVE Handoff

**Authoritative Android client:** `android/` (`:TMessagesProj_App:assembleAfatDebug`), NOT legacy `app/`.
**Branch & Git rule:** Commit and push directly to `main` (no PRs/branches/force-push). Keep this file <= 30 lines.

## Done
- **0ms SWR Speed Overhaul, Multi-Provider AI Factory & Telegram Bot/Mini App (`48e92c8`):** Shipped `EveLocalDiskCache.java` on Android, Cloudflare Edge Cache (`edgeCache.ts`), `aiProviderRouter.ts`, `telegramWebhook.ts`, `telegramMiniApp.ts`, `telegramAdminAlerts.ts`, `assemble-from-bank`, and `speed_ai_telegram_control.test.mjs` (`Build Eve APK` `45991525704` `success`).
- **D1 Read Replication, Smart Placement, Mini App/Bot `db.batch` & Mini App `localStorage` SWR:** Enabled `[placement] mode = "smart"` in `backend/wrangler.toml`; added `backend/src/util/d1Session.ts` (`getReadDb` using `withSession("first-unconstrained")` + `batchReadRows` single-round-trip helper with safe mock-D1 fallback); wired read-only sessions across `index.ts`, `exams.ts`, `generatedTests.ts`, `banners.ts`, `leaderboard.ts`, `polls.ts`, `admin.ts`, `telegramWebhook.ts`, and `telegramMiniApp.ts` (never touching write/post-write paths); collapsed Mini App & Bot KPI/data loads into single `db.batch([...])` calls; added 0ms `localStorage` SWR caching + diff-render + mutation invalidation in `GET /admin-miniapp` UI (`414/414` backend tests pass).

## Next
- Owner adds `TELEGRAM_OWNER_ID` (plus optional `TELEGRAM_WEBHOOK_SECRET`, `OPENROUTER_API_KEY`, `GROQ_API_KEY`, `OPENAI_API_KEY`) to GitHub Secrets, runs `Deploy Eve Worker & Apply D1 Migrations`, and calls `POST /api/admin/telegram/setup-webhook`.
