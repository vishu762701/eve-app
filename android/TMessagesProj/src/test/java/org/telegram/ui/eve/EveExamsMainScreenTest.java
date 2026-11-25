package org.telegram.ui.eve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.eve.auth.EveBackendClient;
import org.telegram.ui.eve.exams.EveExamDialogsMapper;
import org.telegram.ui.eve.exams.EveExamModels;
import org.telegram.ui.eve.exams.EveExamRepository;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Behavioral regression tests for Mission 2 (Main Screen: Chats becomes Exams).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
public class EveExamsMainScreenTest {

    private Context appContext;

    @Before
    public void setUp() {
        appContext = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = appContext;
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
    }

    @Test
    public void bottomTabAndSearchStrings_matchEveExamsSpecification() {
        assertEquals("Exams", appContext.getString(R.string.MainTabsExams));
        assertEquals("Search exams", appContext.getString(R.string.SearchExams));
        assertFalse(appContext.getString(R.string.EveExamsEmptyTitle).toLowerCase().contains("telegram"));
        assertFalse(appContext.getString(R.string.EveExamsAuthTitle).toLowerCase().contains("telegram"));
    }

    @Test
    public void parseAndMapRealBackendCatalog_populatesCustomDialogAndFiltersPaused() throws Exception {
        JSONArray exams = new JSONArray("["
                + "{\"id\":\"ex-ssc\",\"examName\":\"SSC CGL Tier 1\",\"timeLimitMinutes\":60,\"category\":\"SSC\",\"questionCount\":100,\"publishMode\":\"inherit\",\"parentExamId\":\"\"},"
                + "{\"id\":\"ex-upsc\",\"examName\":\"UPSC Prelims\",\"timeLimitMinutes\":120,\"category\":\"UPSC\",\"questionCount\":100,\"publishMode\":\"live\",\"parentExamId\":\"\"},"
                + "{\"id\":\"ex-sub\",\"examName\":\"SSC Quant Drill\",\"timeLimitMinutes\":20,\"category\":\"SSC\",\"questionCount\":25,\"publishMode\":\"live\",\"parentExamId\":\"ex-ssc\"},"
                + "{\"id\":\"ex-paused\",\"examName\":\"Draft Exam\",\"timeLimitMinutes\":30,\"category\":\"Other\",\"questionCount\":20,\"publishMode\":\"paused\",\"parentExamId\":\"\"}"
                + "]");
        JSONArray tests = new JSONArray("["
                + "{\"id\":\"t1\",\"examId\":\"ex-ssc\",\"examName\":\"SSC CGL Tier 1\",\"testNumber\":\"Mock 1\",\"title\":\"Full Mock 1\",\"generatedAt\":1700000000000,\"status\":\"live\",\"questionCount\":100},"
                + "{\"id\":\"t2\",\"examId\":\"ex-ssc\",\"examName\":\"SSC CGL Tier 1\",\"testNumber\":\"Mock 2\",\"title\":\"Full Mock 2\",\"generatedAt\":1700100000000,\"status\":\"published\",\"questionCount\":100},"
                + "{\"id\":\"t3\",\"examId\":\"ex-ssc\",\"examName\":\"SSC CGL Tier 1\",\"testNumber\":\"Mock 3\",\"title\":\"Draft Mock 3\",\"generatedAt\":1700200000000,\"status\":\"paused\",\"questionCount\":100}"
                + "]");

        List<EveExamModels.ExamSummary> parsed = EveExamRepository.parseExamCatalog(exams, tests);
        assertEquals(2, parsed.size());
        assertEquals("SSC CGL Tier 1", parsed.get(0).examName);
        assertEquals(2, parsed.get(0).liveTestCount);
        assertEquals("Full Mock 2", parsed.get(0).nextTestLabel);
        assertEquals(1700100000, parsed.get(0).latestTestTimestampSec);
        assertEquals("UPSC Prelims", parsed.get(1).examName);
        assertEquals(0, parsed.get(1).liveTestCount);

        DialogCell.CustomDialog dialog = EveExamDialogsMapper.toCustomDialog(parsed.get(0));
        assertEquals("SSC CGL Tier 1", dialog.name);
        assertEquals(2, dialog.unread_count);
        assertTrue(dialog.message.contains("SSC"));
        assertTrue(dialog.message.contains("60 min"));
        assertTrue(dialog.message.contains("100 Qs"));
        assertTrue(dialog.message.contains("Full Mock 2"));
        assertTrue(dialog.verified);

        List<EveExamModels.ExamSummary> filtered = EveExamDialogsMapper.filterExams(parsed, "upsc");
        assertEquals(1, filtered.size());
        assertEquals("ex-upsc", filtered.get(0).id);
    }

    @Test
    public void backendClient_fetchExamsAndTests_parsesArrayPayloadWithAuthHeader() throws Exception {
        AtomicBoolean sawBearer = new AtomicBoolean(false);
        EveBackendClient client = new EveBackendClient("https://eve-backend.anyqueairdrop.workers.dev", (method, url, token, body) -> {
            sawBearer.set("firebase-jwt".equals(token));
            if (url.endsWith("/api/exams")) {
                return new EveBackendClient.HttpResponse(200,
                        "{\"success\":true,\"data\":[{\"id\":\"ex1\",\"examName\":\"RRB NTPC\",\"timeLimitMinutes\":90,\"category\":\"Railways\",\"questionCount\":100}]}");
            }
            return new EveBackendClient.HttpResponse(200,
                    "{\"success\":true,\"data\":[{\"id\":\"gt1\",\"examId\":\"ex1\",\"title\":\"Shift 1 Mock\",\"status\":\"live\",\"questionCount\":100,\"generatedAt\":1700000000000}]}");
        });

        JSONArray exams = client.fetchExamsJson("firebase-jwt");
        JSONArray tests = client.fetchGeneratedTestsJson("firebase-jwt", "ex1");
        assertTrue(sawBearer.get());
        assertEquals(1, exams.length());
        assertEquals(1, tests.length());
        List<EveExamModels.ExamSummary> catalog = EveExamRepository.parseExamCatalog(exams, tests);
        assertEquals(1, catalog.size());
        assertEquals("RRB NTPC", catalog.get(0).examName);
        assertEquals(1, catalog.get(0).liveTestCount);
    }

    @Test
    public void emptyStateResolution_mapsLoadStatesToEveCopyWithoutTelegramStrings() {
        EveExamDialogsMapper.EmptyStateSpec loading = EveExamDialogsMapper.resolveEmptyState(
                EveExamModels.LoadState.LOADING, "", "");
        assertNotNull(loading);
        assertTrue(loading.autoRepeat);
        assertEquals(R.string.EveExamsLoadingTitle, loading.titleRes);
        assertFalse(appContext.getString(loading.titleRes).toLowerCase().contains("telegram"));

        EveExamDialogsMapper.EmptyStateSpec auth = EveExamDialogsMapper.resolveEmptyState(
                EveExamModels.LoadState.AUTH_REQUIRED, "", "");
        assertFalse(auth.autoRepeat);
        assertEquals(R.string.EveExamsAuthTitle, auth.titleRes);
        assertFalse(appContext.getString(auth.helpRes).toLowerCase().contains("telegram"));

        EveExamDialogsMapper.EmptyStateSpec searchEmpty = EveExamDialogsMapper.resolveEmptyState(
                EveExamModels.LoadState.LOADED, "ssc", "");
        assertEquals(R.string.EveExamsSearchEmptyTitle, searchEmpty.titleRes);
        assertFalse(appContext.getString(searchEmpty.helpRes).toLowerCase().contains("telegram"));

        EveExamDialogsMapper.EmptyStateSpec empty = EveExamDialogsMapper.resolveEmptyState(
                EveExamModels.LoadState.EMPTY, "", "");
        assertEquals(R.string.EveExamsEmptyTitle, empty.titleRes);
        assertFalse(appContext.getString(empty.helpRes).toLowerCase().contains("telegram"));

        EveExamDialogsMapper.EmptyStateSpec error = EveExamDialogsMapper.resolveEmptyState(
                EveExamModels.LoadState.ERROR, "", "HTTP 503");
        assertEquals(R.string.EveExamsErrorTitle, error.titleRes);
        assertEquals("HTTP 503", error.errorPrefix);
    }
}
