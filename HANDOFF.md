# EVE Handoff

**Authoritative Android client:** `android/` (`:TMessagesProj_App:assembleAfatDebug`), NOT legacy `app/`.
**Branch & Git rule:** Commit and push directly to `main` (no PRs/branches/force-push). Keep this file <= 30 lines.

## Done
- **Exam Start Crash Fix & Pro Student Player (`EveTestPlayerFragment`, `EvePdfHelper`, `EveQuestionTimeChartView`, `EveScoreTrendChartView`):** Fixed `destroyed` lifecycle race guards, null/empty/malformed payload crash paths, `timerHandler` teardown ordering before `super.onFragmentDestroy()`, and bounds-safe question access; added Telegram Error Card (`Retry` / `Go Back`), pure-Canvas `CircularTimerView` (red `<= 120s`), inline + expandable 4-color Question Palette, Bookmark toggle, per-question time tracking, Category Cutoff badge (`General/OBC/SC/ST/EWS`) on `EveTestResultFragment`, Canvas charts on Result & History, expandable solutions, 3-dot `Download Syllabus PDF` (`EveExamTestsFragment`), multi-page `PdfDocument` `Download Result & Solutions PDF`, and Student Profile `Exam Category` selector (`ProfileActivity` + `UserInfoActivity` with `CheckBox2` radio picker -> `PUT /api/users/profile`).
- **Admin Dashboard Upgrade (`EveAdminControlCenterActivity`, `EveAdminPremiumHubFragment`, `EveAdminExamDetailFragment`, `EveAdminExamAnalyticsFragment`, `EveAdminQuestionsFragment`, `backend/`):** Added unified `Manage Premium` hub, 1-tap `Maintenance Mode` switch with confirmation, Category Cutoffs + `draft` mode + Daily Auto-Gen switch & native `TimePickerDialog` + Last Run status, Syllabus PDF upload/view/reassign/remove (`expectedSyllabusUrl` fix), JSON+CSV `Bulk Import` parser + `.json/.csv` file picker, `7 Days`/`30 Days`/`All Time` analytics + `EveAdminAttemptsBarChartView` + Question Analytics, plus backend regression tests (`417/417` pass) and JVM unit tests (`EveStudentExamExecutionTest`, `EveAdminControlCenterTest`).

## Next
- Owner runs `Deploy Eve Worker & Apply D1 Migrations` (`confirm_production: true`, `preflight_only: false`), installs the CI debug APK (`:TMessagesProj_App:assembleAfatDebug`), and tests Exam Start, Pro Player, PDF export, Profile Category, and Admin Dashboard on device.
