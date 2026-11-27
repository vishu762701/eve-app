# EVE Handoff

**Authoritative Android client:** `android/` (`:TMessagesProj_App:assembleAfatDebug`), NOT legacy `app/`.
**Branch & Git rule:** Commit and push directly to `main` (no PRs/branches/force-push). Keep this file <= 30 lines.

## Done
- **Test Ordering & Live Polls (`EveExamTestsFragment`):** Fixed reverse mock test numbering (`generatedTests.ts` `ORDER BY generated_at ASC` + `NATURAL_TEST_COMPARATOR`), removed `SUB_REFRESH`, added `AutoSyncController`, and rendered live interactive `EveTelegramPollCardView` polls directly on Exam pages.
- **Exam & Sub-Exam Avatars + Hierarchy:** Added migrations `0033` & `0034`, `POST/GET/DELETE /api/exams/:id/avatar` (R2/Supabase JPEG/PNG/WebP/GIF + emoji/color), `EveAdminExamDetailFragment` avatar & **Sub-Exams** card, `EveAdminExamsFragment` grouping, and `EveSubExamsFragment` + `DialogCell` circular avatars.
- **Student Stats, History, UPI QR & Banners:** Replaced raw attempt dump with inset **Performance & Progress** card + `View Full Attempt History (N)` (`EveAdminStudentDetailFragment`); upgraded UPI QR (`ErrorCorrectionLevel.H` + `EvePremiumQrSheet`); added `EveAdminStudentsFragment` overview + `All`/`Online`/`Premium`/`Banned` filters (`admin.ts`); removed `Floating Community Link`; added Photo/Video banners (`banners.ts`, `EveAdminBannerEditorFragment`, `EveHomeBannerCarouselView` at `2.5x` row height in `DialogsActivity`).

## Next
- Add `CLOUDFLARE_API_TOKEN` in GitHub Repo Secrets, run `deploy-worker.yml` (`preflight_only: true` then `false`) so `0033` & `0034` migrations + Worker routes go live.
- Verify green CI on `main` and deliver fresh `Eve-debug-apk` for device testing.
