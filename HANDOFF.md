# EVE Handoff

**Authoritative Android client:** `android/` (`:TMessagesProj_App:assembleAfatDebug`), NOT legacy `app/`.
**Branch & Git rule:** Commit and push directly to `main` (no PRs/branches/force-push). Keep this file <= 30 lines.

## Done
- **0ms SWR, AI Factory, Bot/Mini App & D1 Read Replication (`48e92c8`, `c813efa`):** Shipped `EveLocalDiskCache.java`, `edgeCache.ts`, `aiProviderRouter.ts`, `telegramWebhook.ts`, `telegramAdminAlerts.ts`, `wrangler.toml` `[placement] mode="smart"`, and `d1Session.ts` (`withSession("first-unconstrained")`).
- **1:1 Telegram-Native Admin Mini App Control Center (`GET /admin-miniapp` + `/api/telegram/miniapp/*`):** Modularized frontend into `backend/src/miniapp/` (`styles.ts`, `template.ts`, `script.ts`); replaced bottom tabs with `Telegram.WebApp.BackButton` stack, `HapticFeedback`, `showConfirm`, native iOS toggle switches, and exact 3-card Settings Hub (`Exams & Content`, `Students & Revenue`, `System & Access`); wired `Cache-Control: public, max-age=300` + 0ms `localStorage` SWR (`eve_miniapp_swr_v2:*`), D1 `getReadDb`+`batchReadRows`, and 100% parity APIs across Exams/Sub-Exams/Avatars/Syllabus PDF/AI Studio/Tests/Question Bank+Bulk JSON, Banners/Broadcasts/Channel/Polls/Flagged Question Fixer/Feedback, Students Directory+Profile/Orders/Plans & UPI/Subscribers, and Analytics/Crashes/Errors/Security/AI Usage/Health/Leaderboard/Maintenance & AI Config/Admin Roles/Categorized Audit Log (`415/415` pass).

## Next
- Owner runs `Deploy Eve Worker & Apply D1 Migrations` (`confirm_production: true`, `preflight_only: false`) with `TELEGRAM_OWNER_ID` + secrets configured, then calls `POST /api/admin/telegram/setup-webhook`.
