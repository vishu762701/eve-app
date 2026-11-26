package org.telegram.ui.eve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

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
}
