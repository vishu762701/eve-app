# EVE Handoff

**Authoritative Android client:** `android/` (`:TMessagesProj_App:assembleAfatDebug`), NOT legacy `app/`.
**Branch & Git rule:** Commit and push directly to `main` (no PRs/branches/force-push). Keep this file <= 30 lines.

## Done
- **Supabase Removal & Telegram Media Vault:** Replaced Supabase Storage completely with `backend/src/telegramMediaVault.ts` (`sendDocument` -> `tg:<messageId>:<fileId>`, `getFile`, `deleteMessage`, 20 MB cap), Cloudflare Edge Cache (`caches.default` + `Cache-Control: public, max-age=86400`) in `backend/src/util/mediaCache.ts`, and public `GET /api/exams/:id/syllabus` proxy. Deleted `backend/src/supabase.ts` and `.github/workflows/supabase-keepalive.yml`; cleaned `wrangler.toml`, `types.ts`, `storage.ts`, `mediaCleanup.ts`, `monitoring.ts`, `index.ts`, `deploy-worker.yml` (`TELEGRAM_BOT_TOKEN` & `TELEGRAM_MEDIA_CHANNEL_ID` secrets), `smoke-test.sh`, and `EvePackModels.java`; added `telegram_media_vault.test.mjs`.
- **DialogCell Avatars, Production DC & Owner EVE Premium:** `DialogCell` `syncAvatarImageCoords`, `EveTelegramSessionManager.shouldUseTestDc() == false`, and `premium.ts`/`emoji.ts` Owner auto-unlock live on `main` (`Build Eve APK` `45980791916` `success`).

## Next
- Owner runs `Deploy Eve Worker & Apply D1 Migrations` (`confirm_production: true`, `preflight_only: false`) with `TELEGRAM_BOT_TOKEN` & `TELEGRAM_MEDIA_CHANNEL_ID` secrets set to activate Telegram Media Vault live.
