package org.telegram.messenger;

import org.junit.After;
import org.junit.Test;
import org.telegram.tgnet.TLRPC;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * JVM unit tests covering Mission 12 stabilization contracts:
 * - DEBUG-only local inspection identity & QR payload (never real t.me auth)
 * - Release-build safety gate (disabled when debugVersion == false)
 * - Terminal loading outcome tracking (no indefinite LOADING states)
 * - Crash diagnostic sensitive-data redaction
 */
public class EveMission12StabilizationTest {

    @After
    public void tearDown() {
        EveLoadingTracker.clear();
    }

    @Test
    public void releaseBuildDisablesLocalInspectionMode() {
        assertFalse(EveInspectionMode.isEnabledForBuild(false));
        assertTrue(EveInspectionMode.isEnabledForBuild(true));
    }

    @Test
    public void localInspectionIdentityIsClearlyLabeledAndOffline() {
        TLRPC.TL_user user = EveInspectionMode.buildLocalInspectionUser();
        assertNotNull(user);
        assertEquals(EveInspectionMode.LOCAL_INSPECTION_USER_ID, user.id);
        assertEquals("EVE", user.first_name);
        assertEquals("Local Inspector", user.last_name);
        assertEquals("eve_local_inspector", user.username);
        assertFalse("Local inspection user must not masquerade as authenticated self", user.self);

        TLRPC.TL_userFull full = EveInspectionMode.buildLocalInspectionUserFull();
        assertNotNull(full);
        assertTrue(full.about.contains("Offline"));
    }

    @Test
    public void localInspectionQrPayloadDoesNotFabricateTelegramAccountLink() {
        String qrUrl = EveInspectionMode.getLocalInspectionQrUrl();
        assertNotNull(qrUrl);
        assertTrue(qrUrl.startsWith("eve://local-inspection/"));
        assertFalse("Must not fabricate a real t.me account QR", qrUrl.contains("t.me"));
        assertEquals("EVE LOCAL INSPECTION", EveInspectionMode.getLocalInspectionQrCaption());
    }

    @Test
    public void loadingTrackerRecordsTerminalOutcomes() {
        EveLoadingTracker.clear();
        EveLoadingTracker.record("Chats", EveLoadingTracker.Outcome.EMPTY, "No server account");
        EveLoadingTracker.record("Privacy & Security", EveLoadingTracker.Outcome.OFFLINE, "Offline inspection");
        EveLoadingTracker.record("QR Generator", EveLoadingTracker.Outcome.SUCCESS, "Local QR rendered");

        assertEquals(0, EveLoadingTracker.countNonTerminal());
        List<EveLoadingTracker.Entry> snapshot = EveLoadingTracker.snapshot();
        assertEquals(3, snapshot.size());
        assertEquals(EveLoadingTracker.Outcome.EMPTY, EveLoadingTracker.get("Chats").outcome);
        assertEquals(EveLoadingTracker.Outcome.OFFLINE, EveLoadingTracker.get("Privacy & Security").outcome);
        assertEquals(EveLoadingTracker.Outcome.SUCCESS, EveLoadingTracker.get("QR Generator").outcome);
    }

    @Test
    public void crashDiagnosticsRedactsSensitiveData() {
        String raw = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.secret.sig "
                + "email=user@example.com phone=+919876543210 token=abc123xyz";
        String sanitized = EveCrashDiagnostics.redactSensitiveData(raw);

        assertFalse(sanitized.contains("eyJhbGciOiJIUzI1NiJ9"));
        assertFalse(sanitized.contains("user@example.com"));
        assertFalse(sanitized.contains("919876543210"));
        assertFalse(sanitized.contains("abc123xyz"));
        assertTrue(sanitized.contains("[REDACTED]"));
        assertTrue(sanitized.contains("[REDACTED_EMAIL]"));
        assertTrue(sanitized.contains("[REDACTED_PHONE]"));
    }
}
