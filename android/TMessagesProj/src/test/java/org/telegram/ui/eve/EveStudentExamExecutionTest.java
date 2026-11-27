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

        // Exact Home banner carousel height = 2.5x single exam row (72dp * 2.5 = 180dp)
        assertEquals(
                Math.round(org.telegram.messenger.AndroidUtilities.dp(72) * 2.5f),
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
}
