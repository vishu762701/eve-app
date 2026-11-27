# EVE Handoff

**Authoritative Android client:** `android/` (`:TMessagesProj_App:assembleAfatDebug`), NOT legacy `app/`.
**Branch & Git rule:** Commit and push directly to `main` (no PRs/branches/force-push). Keep this file <= 30 lines.

## Done
- **Telegram ImageUpdater Avatars (Exams, Sub-Exams, Mock Tests, Channel):** Deleted `showEmojiAndColorAvatarSheet()` / `SwatchView` / manual emoji inputs (`EveAdminExamDetailFragment`, `EveAdminDialogs`); wrapped real `ImageUpdater` (`setUploadAfterSelect(false)`) in `EveTelegramAvatarPicker` for Exams, Sub-Exams, Mock Tests (`0035` `generated_tests.avatar_url`, `/api/generated-tests/:id/avatar`, `EveAdminGeneratedTestsFragment`, `EveExamTestsFragment`), and Channel.
- **Maintenance Mode End-to-End:** Server `503 MAINTENANCE_MODE` guard on student routes (`authMiddleware.ts`) while Admins/Owners, `/api/users/sync`, and `/api/app-config` stay open; non-dismissible `EveMaintenanceFragment` (`LaunchActivity`, `DialogsActivity`, `EveBackendClient`) + clickable Admin notice bar (`"Maintenance Mode is ON (Students blocked)"`).
- **Instant Performance (SWR + Cached Auth + N+1 Elimination):** Switched normal requests to `getIdToken(false)` with single `401` retry (`EveAuthManager`, `EveAdminRepository`, `EveExamRepository`); added SWR disk+memory caches across Admin & Student screens, `keep-alive` + `gzip` + `10s`/`15s` timeouts (`EveBackendClient`), and single-query poll counts (`polls.ts`).
- **Home Announcements Channel & Telegram Channel UI:** Moved Polls off `EveExamTestsFragment` into a pinned verified Home channel row (`DialogsAdapter`, `DialogsActivity`, `EveExamDialogsMapper`, editable name/avatar in `EveAdminBroadcastsFragment`) opening `EveAnnouncementsFragment` Telegram Chat bubbles + inline `EveTelegramPollCardView` polls with unread badge tracking.
- **Flagged Question Fixer & AI Exam Prompt Studio:** Added `GET /api/questions/:id`, atomic `PUT /api/admin/reports/:id/fix-question` & `DELETE /api/admin/reports/:id/question` + `EveAdminFlaggedQuestionFixFragment` (`EditTextBoldCursor` + `CheckBox2`); added `0035` `exams.(ai_prompt, ai_language, ai_difficulty)`, Hindi/Bilingual AI prompts (`generator.ts`), and `EveAdminExamPromptFragment`; zero `android.widget.CheckBox` or `MENU_REFRESH`.

## Next
- Ensure `CLOUDFLARE_API_TOKEN` is set in GitHub Secrets and run `deploy-worker.yml` (`preflight_only: true` then `false`) so D1 migration `0035` (plus `0033`/`0034`) & updated Worker routes go live.
- Verify fresh `Build Eve APK` CI green run on `main` and test on phone.
