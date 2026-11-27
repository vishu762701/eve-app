# EVE Handoff

**Authoritative Android client:** `android/` (`:TMessagesProj_App:assembleAfatDebug`), NOT legacy `app/`.
**Branch & Git rule:** Commit and push directly to `main` (no PRs/branches/force-push). Keep this file <= 30 lines.

## Done
- **Backend:** Added Owner-only `DELETE /api/admin/audit-log` (`?category`/`?types`), `highestScore` in `GET /api/admin/analytics/exams`, `GET /api/admin/users/:id`, `'draft'` mode in `PUT /api/admin/config`, crash `open`/`resolved` workflow + plain-English summaries + recurrence reopen + Owner-only delete, and `GET /api/admin/system/security-events` + `operation_events` auth/rate-limit telemetry (`npm run build && npm test`: 386/386 pass).
- **Android Admin & Leaderboard:** Added `useCardInsetDividers` + `AutoSyncController` in `EveAdminUiChrome`; removed dashboard shimmer blocker & all `MENU_REFRESH` buttons + `android.widget.CheckBox`; added instant render + silent auto-sync; added full-screen `EveAdminRoleEditFragment`, `EveAdminSystemHealthFragment`, `EveAdminExamAnalyticsFragment`, `EveAdminCrashReportsFragment`, `EveAdminApiUsageFragment`, `EveAdminMaintenanceConfigFragment`; upgraded `EveAdminAuditLogFragment` (Categories-first + Owner clear) and `EveLeaderboardFragment` (Top-3 + verified admin-only student profile tap).

## Next
- Push directly to `main`, verify all 4 GitHub Actions jobs pass on the pushed SHA, and download/deliver fresh `Eve-debug-apk`.
- **Worker Deploy Needed:** Deploy `backend/` to Cloudflare Workers so the new Admin endpoints go live on production API.
