package org.telegram.ui.eve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Color;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.PushListenerController;
import org.telegram.ui.eve.admin.EveAnimatedEmojiHelper;
import org.telegram.ui.eve.auth.EveLocalDiskCache;
import org.telegram.ui.eve.exams.EveAnnouncementsFragment;
import org.telegram.ui.eve.exams.EveExamModels;
import org.telegram.ui.eve.exams.EveExamRepository;
import org.telegram.ui.eve.exams.EveLeaderboardFragment;
import org.telegram.ui.eve.exams.EveResultHistoryFragment;
import org.telegram.ui.eve.exams.EveTestPlayerFragment;
import org.telegram.ui.eve.exams.EveTestResultFragment;
import org.telegram.ui.eve.notifications.EveNotificationHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies interactive student exam execution contracts (start, bilingual questions,
 * server-authoritative grading/review, history, leaderboard) and strict isolation
 * between Telegram chat push notifications and EVE FCM exam notifications.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
public class EveStudentExamExecutionTest {

    @Test
    public void startAttemptAndBilingualQuestions_parseServerPayload() throws Exception {
        String key = EveExamModels.buildAttemptKey("exam-ssc", "test-01");
        assertEquals("exam-ssc__test-01", key);

        JSONObject startData = new JSONObject("{"
                + "\"alreadySubmitted\":false,"
                + "\"clientAttemptId\":\"sess-uuid-1\","
                + "\"startedAt\":1700000000000,"
                + "\"timeLimitSeconds\":1800,"
                + "\"remainingSeconds\":1500,"
                + "\"questions\":[{"
                + "  \"id\":\"test-01_0\","
                + "  \"examId\":\"exam-ssc\","
                + "  \"questionText\":\"Capital of India?\","
                + "  \"questionTextHi\":\"भारत की राजधानी क्या है?\","
                + "  \"optionA\":\"Mumbai\",\"optionB\":\"New Delhi\",\"optionC\":\"Kolkata\",\"optionD\":\"Chennai\","
                + "  \"optionAHi\":\"मुंबई\",\"optionBHi\":\"नई दिल्ली\",\"optionCHi\":\"कोलकाता\",\"optionDHi\":\"चेन्नई\","
                + "  \"topic\":\"Polity\""
                + "}]}");

        EveExamModels.StartAttemptResult start = EveExamRepository.parseStartAttemptResult(startData, "exam-ssc");
        assertFalse(start.alreadySubmitted);
        assertEquals("sess-uuid-1", start.clientAttemptId);
        assertEquals(1800, start.timeLimitSeconds);
        assertEquals(1500, start.remainingSeconds);
        assertEquals(1, start.questions.size());

        EveExamModels.TestQuestion q = start.questions.get(0);
        assertTrue(q.hasHindiVariant());
        assertEquals("Capital of India?", q.getQuestionText(false));
        assertEquals("भारत की राजधानी क्या है?", q.getQuestionText(true));
        assertEquals("New Delhi", q.getOptionText("B", false));
        assertEquals("नई दिल्ली", q.getOptionText("B", true));
    }

    @Test
    public void attemptResultHistoryAndLeaderboard_parseServerGradingAndStats() throws Exception {
        JSONObject submitData = new JSONObject("{"
                + "\"attemptId\":\"att-1\","
                + "\"score\":3.75,"
                + "\"total\":5,"
                + "\"correct\":4,"
                + "\"wrong\":1,"
                + "\"unattempted\":0,"
                + "\"timeTakenSeconds\":140,"
                + "\"counted\":1,"
                + "\"answers\":[{"
                + "  \"questionId\":\"q1\",\"number\":1,"
                + "  \"questionText\":\"2+2?\",\"questionTextHi\":\"2+2?\","
                + "  \"selected\":\"B\",\"selectedText\":\"4\",\"correct\":\"B\",\"correctText\":\"4\","
                + "  \"explanation\":\"2+2 equals 4\",\"explanationHi\":\"2+2 बराबर 4\","
                + "  \"topic\":\"Quant\",\"isBookmarked\":true,\"timeTakenSeconds\":15"
                + "}]}");

        EveExamModels.AttemptResult result = EveExamRepository.parseAttemptResult(
                submitData, "ex__t1", "SSC Mock", "SSC", "Ravi");
        assertNotNull(result);
        assertEquals("att-1", result.attemptId);
        assertEquals(3.75, result.score, 0.001);
        assertEquals(80, result.getAccuracyPercent());
        assertEquals(1, result.answers.size());
        assertTrue(result.answers.get(0).isCorrect());
        assertTrue(result.answers.get(0).isBookmarked);

        JSONArray lbArr = new JSONArray("[{\"userId\":\"u1\",\"displayName\":\"Ravi\",\"score\":92.5,\"total\":100,\"accuracy\":94,\"timeTakenSeconds\":420}]");
        List<EveExamModels.LeaderboardEntry> entries = EveExamRepository.parseLeaderboardEntries(lbArr);
        assertEquals(1, entries.size());
        assertEquals("Ravi", entries.get(0).displayName);
        assertEquals(92.5, entries.get(0).score, 0.001);

        JSONObject statsJson = new JSONObject("{\"participants\":50,\"topperScore\":98.0,\"averageScore\":71.4,\"myRank\":3,\"myScore\":92.5,\"myTimeSeconds\":420,\"myPercentile\":95.9}");
        EveExamModels.LeaderboardStats stats = EveExamRepository.parseLeaderboardStats(statsJson);
        assertNotNull(stats);
        assertEquals(Integer.valueOf(3), stats.myRank);
        assertEquals(95.9, stats.myPercentile, 0.001);

        assertNotNull(EveTestPlayerFragment.class);
        assertNotNull(EveTestResultFragment.class);
        assertNotNull(EveResultHistoryFragment.class);
        assertNotNull(EveLeaderboardFragment.class);
        assertNotNull(EveAnnouncementsFragment.class);
    }

    @Test
    public void notificationIsolation_blocksTelegramPushAndAllowsEveExamPush() {
        assertTrue(PushListenerController.EVE_DISABLE_TELEGRAM_PUSH);

        Map<String, String> tgData = new HashMap<>();
        tgData.put("p", "encrypted-telegram-payload");
        assertTrue(EveNotificationHelper.isTelegramPushPayload(tgData));

        Map<String, String> eveData = new HashMap<>();
        eveData.put("eve_type", "NEW_TEST");
        eveData.put("title", "New SSC Mock Live");
        eveData.put("body", "Attempt Mock 4 now");
        assertFalse(EveNotificationHelper.isTelegramPushPayload(eveData));
    }

    @Test
    public void reportDialogReasonSelector_defaultsToFirstAndTracksSingleChoiceSelection() {
        int[] pickedIdx = new int[] { -1 };
        RadioGroup group = EveTestResultFragment.buildReportReasonGroup(
                ApplicationProvider.getApplicationContext(),
                EveTestResultFragment.REPORT_REASONS,
                pickedIdx,
                Color.BLACK
        );
        assertEquals(EveTestResultFragment.REPORT_REASONS.length, group.getChildCount());
        assertEquals(0, pickedIdx[0]);
        assertTrue(((RadioButton) group.getChildAt(0)).isChecked());

        group.check(3);
        assertEquals(2, pickedIdx[0]);
        assertEquals("Wrong Translation", EveTestResultFragment.REPORT_REASONS[pickedIdx[0]]);
        assertFalse(((RadioButton) group.getChildAt(0)).isChecked());
        assertTrue(((RadioButton) group.getChildAt(2)).isChecked());
    }

    @Test
    public void studentPollModel_tracksVotesCountsAndUiScreensExist() throws Exception {
        Map<Integer, Integer> counts = new HashMap<>();
        counts.put(0, 3);
        counts.put(1, 7);
        org.telegram.ui.eve.polls.EvePollModel model = new org.telegram.ui.eve.polls.EvePollModel(
                "poll-1",
                "Preferred Mock Timing?",
                java.util.Arrays.asList("Morning", "Evening"),
                counts,
                10,
                true,
                1700000000000L,
                0L,
                "owner@example.com",
                null
        );
        assertEquals(10, model.totalVotes);
        assertEquals(7, model.getWinningVotes());
        assertEquals(3, model.getOptionVotes(0));

        org.telegram.ui.eve.polls.EvePollModel voted = model.withRecordedVote(0);
        assertEquals(Integer.valueOf(0), voted.myVoteIndex);
        assertEquals(11, voted.totalVotes);
        assertEquals(4, voted.getOptionVotes(0));

        // Double vote on same immutable model snapshot is a no-op
        org.telegram.ui.eve.polls.EvePollModel secondVote = voted.withRecordedVote(1);
        assertEquals(Integer.valueOf(0), secondVote.myVoteIndex);
        assertEquals(11, secondVote.totalVotes);

        assertNotNull(Class.forName("org.telegram.ui.eve.polls.EveTelegramPollCardView"));
        assertNotNull(Class.forName("org.telegram.ui.eve.exams.EveExamTestsFragment"));

        org.telegram.ui.eve.exams.EveLeaderboardFragment studentLb =
                new org.telegram.ui.eve.exams.EveLeaderboardFragment("", "Leaderboard", true);
        assertFalse(studentLb.isAdminInspectionMode());

        // Ascending natural mock test ordering
        java.util.List<org.telegram.ui.eve.exams.EveExamModels.GeneratedTestSummary> unordered = new java.util.ArrayList<>();
        unordered.add(new org.telegram.ui.eve.exams.EveExamModels.GeneratedTestSummary("g10", "ex", "SSC", "Test 10", "Test 10", 5000L, "live", 20, 0L));
        unordered.add(new org.telegram.ui.eve.exams.EveExamModels.GeneratedTestSummary("g2", "ex", "SSC", "Test 2", "Test 2", 2000L, "live", 20, 0L));
        unordered.add(new org.telegram.ui.eve.exams.EveExamModels.GeneratedTestSummary("g1", "ex", "SSC", "Test 1", "Test 1", 1000L, "live", 20, 0L));
        unordered.add(new org.telegram.ui.eve.exams.EveExamModels.GeneratedTestSummary("g4", "ex", "SSC", "Mock 4", "Mock 4", 4000L, "live", 20, 0L));
        unordered.add(new org.telegram.ui.eve.exams.EveExamModels.GeneratedTestSummary("g3", "ex", "SSC", "Mock 3", "Mock 3", 3000L, "live", 20, 0L));
        java.util.Collections.sort(unordered, org.telegram.ui.eve.exams.EveExamTestsFragment.NATURAL_TEST_COMPARATOR);
        assertEquals("Test 1", unordered.get(0).testNumber);
        assertEquals("Test 2", unordered.get(1).testNumber);
        assertEquals("Mock 3", unordered.get(2).testNumber);
        assertEquals("Mock 4", unordered.get(3).testNumber);
        assertEquals("Test 10", unordered.get(4).testNumber);

        // ExamSummary -> CustomDialog carries avatar metadata & resolves relative URLs
        org.telegram.ui.eve.exams.EveExamModels.ExamSummary examWithAvatar =
                new org.telegram.ui.eve.exams.EveExamModels.ExamSummary(
                        "ex-av", "UPSC Prelims", 120, "UPSC", 100, "Test 1", "1/3",
                        "", "live", 0L, 4, "Test 1", 0, true,
                        "/api/exams/ex-av/avatar?v=1", "🏛️", 4);
        org.telegram.ui.Cells.DialogCell.CustomDialog dialog =
                org.telegram.ui.eve.exams.EveExamDialogsMapper.toCustomDialog(examWithAvatar);
        assertEquals("/api/exams/ex-av/avatar?v=1", dialog.avatarUrl);
        assertEquals("🏛️", dialog.avatarEmoji);
        assertEquals(4, dialog.avatarColor);
        assertEquals(
                "https://eve-backend.anyqueairdrop.workers.dev/api/exams/ex-av/avatar?v=1",
                org.telegram.ui.eve.auth.EveBackendClient.resolveMediaUrl(dialog.avatarUrl)
        );

        // Compact sleek Home banner carousel height = 120dp
        assertEquals(
                org.telegram.messenger.AndroidUtilities.dp(120),
                org.telegram.ui.eve.exams.EveHomeBannerCarouselView.computeBannerHeightPx()
        );

        // parseAllExamsCatalog preserves sub-exams while parseExamCatalog keeps top-level exams for Home
        org.json.JSONArray catalogArr = new org.json.JSONArray("["
                + "{\"id\":\"parent-1\",\"examName\":\"SSC CGL\",\"publishMode\":\"live\"},"
                + "{\"id\":\"sub-1\",\"examName\":\"Maths Tier 1\",\"parentExamId\":\"parent-1\",\"publishMode\":\"live\",\"avatarEmoji\":\"📘\",\"avatarColor\":1}"
                + "]");
        java.util.List<org.telegram.ui.eve.exams.EveExamModels.ExamSummary> topLevel =
                org.telegram.ui.eve.exams.EveExamRepository.parseExamCatalog(catalogArr, null);
        java.util.List<org.telegram.ui.eve.exams.EveExamModels.ExamSummary> allItems =
                org.telegram.ui.eve.exams.EveExamRepository.parseAllExamsCatalog(catalogArr, null, java.util.Collections.emptySet());
        assertEquals(1, topLevel.size());
        assertEquals("parent-1", topLevel.get(0).id);
        assertEquals(2, allItems.size());
    }

    @Test
    public void testPlayerStartupGuards_paletteTimerStatesAndCutoffEvaluation() throws Exception {
        // 1. Startup guards against null/empty/malformed payloads
        assertFalse(EveTestPlayerFragment.validateStartupSession("", null, "").isEmpty());
        assertFalse(EveTestPlayerFragment.validateStartupSession("k1", null, "Network down").isEmpty());
        assertEquals("Network down", EveTestPlayerFragment.validateStartupSession("k1", null, "Network down"));
        assertFalse(EveTestPlayerFragment.validateStartupSession("k1", null, "").isEmpty());

        EveExamModels.StartAttemptResult emptyQs = new EveExamModels.StartAttemptResult(
                false, "", "c-1", 1000L, 1800, 1800, java.util.Collections.emptyList());
        assertEquals("No questions are available in this test yet.",
                EveTestPlayerFragment.validateStartupSession("k1", emptyQs, ""));

        EveExamModels.StartAttemptResult submittedNoId = new EveExamModels.StartAttemptResult(
                true, "", "c-1", 1000L, 1800, 1800, java.util.Collections.emptyList());
        assertFalse(EveTestPlayerFragment.validateStartupSession("k1", submittedNoId, "").isEmpty());

        EveExamModels.StartAttemptResult submittedValid = new EveExamModels.StartAttemptResult(
                true, "att-99", "c-1", 1000L, 1800, 1800, java.util.Collections.emptyList());
        assertEquals("ALREADY_SUBMITTED", EveTestPlayerFragment.validateStartupSession("k1", submittedValid, ""));

        java.util.List<EveExamModels.TestQuestion> validList = java.util.Collections.singletonList(
                new EveExamModels.TestQuestion("q1", "ex1", 1, "Q?", "", "A", "B", "C", "D", "", "", "", "", "GS"));
        EveExamModels.StartAttemptResult validStart = new EveExamModels.StartAttemptResult(
                false, "", "c-1", 1000L, 1800, 1800, validList);
        assertEquals("", EveTestPlayerFragment.validateStartupSession("k1", validStart, ""));

        // 2. All 4 Palette states & Low-time red warning (<= 120s)
        assertEquals(EveTestPlayerFragment.PaletteState.UNVISITED, EveTestPlayerFragment.resolvePaletteState(false, false));
        assertEquals(EveTestPlayerFragment.PaletteState.ANSWERED, EveTestPlayerFragment.resolvePaletteState(true, false));
        assertEquals(EveTestPlayerFragment.PaletteState.MARKED, EveTestPlayerFragment.resolvePaletteState(false, true));
        assertEquals(EveTestPlayerFragment.PaletteState.ANSWERED_AND_MARKED, EveTestPlayerFragment.resolvePaletteState(true, true));
        assertTrue(EveTestPlayerFragment.isLowTimeWarning(120, 1800));
        assertFalse(EveTestPlayerFragment.isLowTimeWarning(121, 1800));
        assertFalse(EveTestPlayerFragment.isLowTimeWarning(0, 0));

        // 3. ExamSummary cutoffs & syllabus parsing + Cutoff badge evaluation
        JSONArray cutCatalog = new JSONArray("[{"
                + "\"id\":\"ex-cut\",\"examName\":\"UPSC Prelims\",\"publishMode\":\"live\","
                + "\"syllabusUrl\":\"/api/exams/ex-cut/syllabus\",\"syllabusFileName\":\"upsc.pdf\","
                + "\"cutoffs\":{\"General\":105,\"OBC\":98.5,\"SC\":88,\"ST\":82,\"EWS\":100}"
                + "}]");
        List<EveExamModels.ExamSummary> parsedCatalog = EveExamRepository.parseExamCatalog(cutCatalog, null);
        assertEquals(1, parsedCatalog.size());
        EveExamModels.ExamSummary cutExam = parsedCatalog.get(0);
        assertTrue(cutExam.hasSyllabus());
        assertEquals(Double.valueOf(98.5), cutExam.getCutoffForCategory("OBC"));

        EveTestResultFragment.CutoffEvaluation cleared = EveTestResultFragment.evaluateCutoffStatus(104.0, "OBC", cutExam.getCutoffForCategory("OBC"));
        assertTrue(cleared.hasCutoff);
        assertTrue(cleared.cleared);
        assertEquals("Cleared OBC Cutoff (+5.50)", cleared.label);

        EveTestResultFragment.CutoffEvaluation missed = EveTestResultFragment.evaluateCutoffStatus(95.0, "General", cutExam.getCutoffForCategory("General"));
        assertTrue(missed.hasCutoff);
        assertFalse(missed.cleared);
        assertEquals("Below General Cutoff (-10)", missed.label);

        assertFalse(EveTestResultFragment.evaluateCutoffStatus(95.0, "General", null).hasCutoff);

        // 4. Deterministic question fallback ID + new PDF/Chart classes present
        List<EveExamModels.TestQuestion> noIdQs = EveExamRepository.parseQuestionsArray(
                new JSONArray("[{\"questionText\":\"Q without ID\",\"optionA\":\"1\",\"optionB\":\"2\",\"optionC\":\"3\",\"optionD\":\"4\"}]"),
                "ex-cut", "");
        assertEquals("q_1", noIdQs.get(0).id);

        assertNotNull(Class.forName("org.telegram.ui.eve.exams.EvePdfHelper"));
        assertNotNull(Class.forName("org.telegram.ui.eve.exams.EveQuestionTimeChartView"));
        assertNotNull(Class.forName("org.telegram.ui.eve.exams.EveScoreTrendChartView"));

        // 5. Defensive startup constructor guards & JSON null sanitization in TestPlayer / Repo
        EveTestPlayerFragment defaultPlayer = new EveTestPlayerFragment();
        android.os.Bundle emptyBundle = new android.os.Bundle();
        EveTestPlayerFragment bundledPlayer = new EveTestPlayerFragment(emptyBundle);
        assertNotNull(defaultPlayer);
        assertNotNull(bundledPlayer);
        assertFalse(EveTestPlayerFragment.validateStartupSession(
                "ex__t",
                new EveExamModels.StartAttemptResult(false, "", "", 0L, 600, 600, null),
                ""
        ).isEmpty());

        List<EveExamModels.TestQuestion> sanitizedNulls = EveExamRepository.parseQuestionsArray(
                new JSONArray("[{\"id\":\"q_n1\",\"examId\":null,\"topic\":null,\"questionText\":\"Q?\",\"optionA\":\"1\",\"optionB\":\"2\",\"optionC\":\"3\",\"optionD\":\"4\"}]"),
                "fallback-ex",
                "t-1"
        );
        assertEquals(1, sanitizedNulls.size());
        assertEquals("fallback-ex", sanitizedNulls.get(0).examId);
        assertEquals("", sanitizedNulls.get(0).topic);

        // 6. Animated emoji markup roundtrip (offline fallback without native MTProto)
        String rawMarkup = "Hello [tg_emoji:5368324170671202286:🔥] world";
        assertEquals("Hello 🔥 world", EveAnimatedEmojiHelper.stripMarkupToPlainText(rawMarkup));
        CharSequence offlineDecoded = EveAnimatedEmojiHelper.decode(rawMarkup, null, false);
        assertEquals("Hello 🔥 world", offlineDecoded.toString());
        android.text.SpannableStringBuilder fakeSpanText = new android.text.SpannableStringBuilder("Hi ✨");
        org.telegram.ui.Components.AnimatedEmojiSpan span = new org.telegram.ui.Components.AnimatedEmojiSpan(999999L, null);
        span.emoji = "✨";
        fakeSpanText.setSpan(span, 3, 4, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        assertEquals("Hi [tg_emoji:999999:✨]", EveAnimatedEmojiHelper.encode(fakeSpanText));

        // 7. Disk cache 10 MB cap enforcement
        java.io.File tempCache = java.nio.file.Files.createTempDirectory("eve_disk_cap_test").toFile();
        EveLocalDiskCache boundedCache = new EveLocalDiskCache(tempCache, 256L);
        boundedCache.put("k1", new String(new char[140]).replace('\0', 'a'));
        Thread.sleep(15L);
        boundedCache.put("k2", new String(new char[140]).replace('\0', 'b'));
        Thread.sleep(15L);
        boundedCache.put("k3", new String(new char[140]).replace('\0', 'c'));
        assertTrue(boundedCache.totalDiskBytes() <= 256L);
        tempCache.delete();

        // 8. EveTestPlayerFragment initialization, lifecycle safety & Start Test action order
        EveExamModels.ExamSummary launchExam = new EveExamModels.ExamSummary(
                "ex-launch", "[tg_emoji:4913925111:🔥] Reasoning", 30, "SSC", 20, "Test 1", "0.25",
                "", "live", 0L, 1, "Hello - Test 1", 0, false, "", "", -1);
        EveExamModels.GeneratedTestSummary launchTest = new EveExamModels.GeneratedTestSummary(
                "gt-1", "ex-launch", "SSC", "Hello - Test 1", "Hello - Test 1", 1000L, "live", 20, 0L);
        EveTestPlayerFragment playerFragment = new EveTestPlayerFragment(launchExam, launchTest);
        assertTrue(playerFragment.onFragmentCreate());
        playerFragment.onResume();
        playerFragment.onPause();
        assertTrue(playerFragment.onBackPressed(false));
        assertNotNull(EveTestPlayerFragment.class.getDeclaredMethod("onResume"));
        assertNotNull(EveTestPlayerFragment.class.getDeclaredMethod("onPause"));
        assertNotNull(EveTestPlayerFragment.class.getDeclaredMethod("onFragmentDestroy"));

        java.util.List<String> dialogActions = org.telegram.ui.eve.exams.EveExamTestsFragment.getLaunchDialogActionOrder();
        assertEquals(java.util.Arrays.asList("Start Test", "Leaderboard", "Cancel"), dialogActions);

        // 9. Animated avatar descriptor (tg_anim) roundtrip & AvatarConstructor local-only unlock flag
        String animDesc = EveAnimatedEmojiHelper.encodeAnimatedAvatarDescriptor(
                5368324170671202286L, new int[] { 0xFF1A73E8, 0xFF8E24AA });
        assertEquals("tg_anim:5368324170671202286:FF1A73E8,FF8E24AA", animDesc);
        EveAnimatedEmojiHelper.AnimatedAvatarSpec parsedAnim = EveAnimatedEmojiHelper.parseAnimatedAvatarDescriptor(animDesc);
        assertNotNull(parsedAnim);
        assertEquals(5368324170671202286L, parsedAnim.documentId);
        assertEquals(2, parsedAnim.backgroundColors.length);
        org.telegram.tgnet.TLRPC.TL_videoSizeEmojiMarkup tlMarkup = EveAnimatedEmojiHelper.toVideoSizeMarkup(parsedAnim);
        assertNotNull(tlMarkup);
        assertEquals(5368324170671202286L, tlMarkup.emoji_id);

        assertNotNull(org.telegram.ui.Components.AvatarConstructorFragment.class.getMethod("setEveLocalAvatarMode", boolean.class));
        assertNotNull(org.telegram.ui.Components.ImageUpdater.class.getMethod("setEveLocalAvatarMode", boolean.class));
        assertNotNull(org.telegram.ui.Components.ImageUpdater.class.getMethod("isEveLocalAvatarMode"));

        // 10. Mapper preserves [tg_emoji] for DialogCell, filters cleanly by stripped text, and keeps zero-date for 01.01.70 fix
        org.telegram.ui.Cells.DialogCell.CustomDialog emojiDialog =
                org.telegram.ui.eve.exams.EveExamDialogsMapper.toCustomDialog(launchExam);
        assertEquals("[tg_emoji:4913925111:🔥] Reasoning", emojiDialog.name);
        assertEquals(0, emojiDialog.date);
        assertEquals(1, org.telegram.ui.eve.exams.EveExamDialogsMapper.filterExams(
                java.util.Collections.singletonList(launchExam), "reasoning").size());

        EveExamRepository.AnnouncementChannelSummary chSum = new EveExamRepository.AnnouncementChannelSummary(
                "News [tg_emoji:123:📢]", "/api/admin/announcement-channel/avatar", "Hi", 0, 0L, 0, animDesc);
        org.telegram.ui.Cells.DialogCell.CustomDialog chDialog =
                org.telegram.ui.eve.exams.EveExamDialogsMapper.toAnnouncementChannelDialog(chSum);
        assertEquals("News [tg_emoji:123:📢]", chDialog.name);
        assertEquals(animDesc, chDialog.avatarEmoji);
        assertEquals(0, chDialog.date);

        // 11. EveTestPlayerFragment null-safe title/createView, Smart Mock Test rows & compact Banner height lock
        assertEquals("Hello - Test 1", EveTestPlayerFragment.resolveSafeTitle(launchExam, launchTest));
        assertEquals("Mock Test", EveTestPlayerFragment.resolveSafeTitle(null, null));
        assertTrue(playerFragment.createView(null) == null);

        EveExamTestsFragment.SmartTestRowMeta unattemptedDefault =
                EveExamTestsFragment.buildSmartTestRowMeta(launchTest, launchExam, null, 2000L);
        assertEquals("", unattemptedDefault.subtitle);
        assertEquals("Start Test", unattemptedDefault.badge);

        EveExamModels.GeneratedTestSummary customCountTest = new EveExamModels.GeneratedTestSummary(
                "gt-custom", "ex-launch", "SSC", "Mini Test 2", "Mini Test 2", 1000L, "live", 15, 0L);
        EveExamTestsFragment.SmartTestRowMeta unattemptedCustom =
                EveExamTestsFragment.buildSmartTestRowMeta(customCountTest, launchExam, null, 2000L);
        assertEquals("15 Qs", unattemptedCustom.subtitle);
        assertEquals("Start Test", unattemptedCustom.badge);

        EveExamModels.AttemptResult sampleAttempt = new EveExamModels.AttemptResult(
                "att-1", "ex-launch__gt-1", "SSC", "SSC", "Student",
                16.5, 20, 17, 2, 1, 5000L, 600, 1, java.util.Collections.emptyList());
        EveExamTestsFragment.SmartTestRowMeta attemptedMeta =
                EveExamTestsFragment.buildSmartTestRowMeta(launchTest, launchExam, sampleAttempt, 2000L);
        assertTrue(attemptedMeta.subtitle.contains("Scored 16.5/20"));
        assertTrue(attemptedMeta.subtitle.contains("83%"));
        assertEquals("Retake", attemptedMeta.badge);

        EveExamModels.GeneratedTestSummary draftTest = new EveExamModels.GeneratedTestSummary(
                "gt-draft", "ex-launch", "SSC", "Draft 3", "Draft 3", 1000L, "paused", 20, 0L);
        assertEquals("Draft", EveExamTestsFragment.buildSmartTestRowMeta(draftTest, launchExam, null, 2000L).badge);

        assertEquals(EveHomeBannerCarouselView.computeBannerHeightPx() + org.telegram.messenger.AndroidUtilities.dp(12),
                EveHomeBannerCarouselView.computeTotalHeightPx(1));
        assertEquals(EveHomeBannerCarouselView.computeBannerHeightPx() + org.telegram.messenger.AndroidUtilities.dp(24),
                EveHomeBannerCarouselView.computeTotalHeightPx(3));
    }
}
