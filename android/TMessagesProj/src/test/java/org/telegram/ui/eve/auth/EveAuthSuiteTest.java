package org.telegram.ui.eve.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Automated JVM unit test suite for Mission 08 & Mission 09 (EVE Firebase Auth,
 * Credential Manager Google Sign-In 3-stage fallback, Cloudflare Worker/D1 Cloud Sync,
 * Offline Retry, Navigation Policy, and Telegram Auth Removal).
 */
public class EveAuthSuiteTest {

    // -------------------------------------------------------------------------
    // 1. EMAIL / PASSWORD TESTS
    // -------------------------------------------------------------------------

    @Test
    public void emailPassword_existingLoginAndNewRegistrationValidation() {
        // Existing account login
        assertTrue(EveAuthValidator.validateLoginInput("student@eve.app", "MyPass123").valid);
        assertFalse(EveAuthValidator.validateLoginInput("", "MyPass123").valid);
        assertFalse(EveAuthValidator.validateLoginInput("student@eve.app", "").valid);

        // New account registration
        assertTrue(EveAuthValidator.validateRegistrationInput(
                "Aarav Sharma", "aarav@example.com", "SecurePass1", "SecurePass1").valid);
        assertFalse(EveAuthValidator.validateRegistrationInput(
                "A", "aarav@example.com", "SecurePass1", "SecurePass1").valid);
        assertFalse(EveAuthValidator.validateRegistrationInput(
                "Aarav Sharma", "aarav@example.com", "Pass1", "Pass1").valid);
        assertFalse(EveAuthValidator.validateRegistrationInput(
                "Aarav Sharma", "aarav@example.com", "PasswordOnly", "PasswordOnly").valid);
        EveAuthValidator.ValidationResult mismatch = EveAuthValidator.validateRegistrationInput(
                "Aarav Sharma", "aarav@example.com", "SecurePass1", "SecurePass2");
        assertFalse(mismatch.valid);
        assertEquals("Passwords do not match.", mismatch.errorMessage);
    }

    @Test
    public void emailPassword_wrongPasswordResetAndSessionRestoration() {
        // Wrong password error mapping
        assertTrue(EveAuthValidator.mapAuthErrorMessage("ERROR_WRONG_PASSWORD", null).contains("Invalid email or password"));
        assertTrue(EveAuthValidator.mapAuthErrorMessage("ERROR_INVALID_CREDENTIAL", null).contains("Invalid email or password"));
        assertTrue(EveAuthValidator.mapAuthErrorMessage("ERROR_EMAIL_ALREADY_IN_USE", null).contains("already exists"));

        // Password reset email validation
        assertTrue(EveAuthValidator.validateEmail("  eve.user+prep@domain.co.in  ").valid);
        assertFalse(EveAuthValidator.validateEmail("invalid-email").valid);
        assertFalse(EveAuthValidator.validateEmail("a..b@example.com").valid);

        // Session restoration & logout JSON round-trip
        EveUserProfile initial = new EveUserProfile(
                "uid_email_1", "", "Priya Verma", "PRIYA@Example.com",
                "", "password", true, EveUserProfile.STATUS_ACTIVE, false,
                "", "General", "", 1700000000000L, 1700000000000L, 1700000000000L, false
        );
        EveUserProfile synced = initial.withBackendSync(
                "EV-9K2M4P", "Priya Verma", "ev_9k2m4p", "UPSC", "Aspirant", false, 1700000050000L
        );
        EveUserProfile restored = EveUserProfile.fromJson(synced.toJson().toString());
        assertNotNull(restored);
        assertEquals("uid_email_1", restored.uid);
        assertEquals("EV-9K2M4P", restored.eveId);
        assertTrue(restored.syncedWithBackend);

        // Logout clears serialized profile
        assertNull(EveUserProfile.fromJson(""));
        assertNull(EveUserProfile.fromJson(null));
    }

    // -------------------------------------------------------------------------
    // 2. GOOGLE SIGN-IN & CREDENTIAL MANAGER FALLBACK TESTS
    // -------------------------------------------------------------------------

    @Test
    public void googleSignIn_threeStageCredentialManagerFallbackSequence() {
        // Stage 1: Authorized accounts only -> fallback to Stage 2: All device accounts (filter=false)
        EveAuthCoordinator.GoogleSignInStage s1 = EveAuthCoordinator.nextGoogleFallbackStage(null);
        assertEquals(EveAuthCoordinator.GoogleSignInStage.AUTHORIZED_ACCOUNTS_ONLY, s1);

        EveAuthCoordinator.GoogleSignInStage s2 = EveAuthCoordinator.nextGoogleFallbackStage(s1);
        assertEquals(EveAuthCoordinator.GoogleSignInStage.ALL_DEVICE_ACCOUNTS_ONE_TAP, s2);

        // Stage 2 -> fallback to Stage 3: Explicit Sign in with Google picker
        EveAuthCoordinator.GoogleSignInStage s3 = EveAuthCoordinator.nextGoogleFallbackStage(s2);
        assertEquals(EveAuthCoordinator.GoogleSignInStage.EXPLICIT_SIGN_IN_WITH_GOOGLE_PICKER, s3);

        // Exhausted -> null
        assertNull(EveAuthCoordinator.nextGoogleFallbackStage(s3));
    }

    @Test
    public void googleSignIn_webClientIdValidationLinkingAndErrors() {
        assertTrue(EveAuthValidator.isValidWebOAuthClientId(
                "118032999217-nijut9hsj7te9276b1ire3jerk03os9v.apps.googleusercontent.com"));
        assertFalse(EveAuthValidator.isValidWebOAuthClientId(""));
        assertFalse(EveAuthValidator.isValidWebOAuthClientId("com.eve.app"));

        // No credential guidance
        String noCredMsg = EveAuthValidator.mapAuthErrorMessage("ERROR_NO_GOOGLE_CREDENTIAL", null);
        assertTrue(noCredMsg.contains("Android Settings"));
        assertTrue(noCredMsg.contains("SHA-1"));

        // Account linking guidance (no duplicate user / no overwrite)
        String linkMsg = EveAuthValidator.mapAuthErrorMessage("ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL", null);
        assertTrue(linkMsg.contains("link Google securely"));

        // SHA-1 / OAuth config mismatch mapping
        String devErr = EveAuthValidator.mapAuthErrorMessage(null, "16: DEVELOPER_ERROR 28436");
        assertTrue(devErr.contains("SHA-1"));

        // Cancellation & disabled account
        assertTrue(EveAuthValidator.mapAuthErrorMessage("ERROR_CREDENTIAL_CANCELLED", null).contains("cancelled"));
        assertTrue(EveAuthValidator.mapAuthErrorMessage("ERROR_USER_DISABLED", null).contains("disabled"));
    }

    // -------------------------------------------------------------------------
    // 3. CLOUD SYNC (CLOUDFLARE WORKER + D1) CONTRACT TESTS
    // -------------------------------------------------------------------------

    @Test
    public void cloudSync_validTokenIdempotentD1UpsertAndProfileUpdate() {
        Map<String, String> d1Users = new HashMap<>();
        AtomicReference<String> capturedToken = new AtomicReference<>();

        EveBackendClient client = new EveBackendClient("https://eve-backend.anyqueairdrop.workers.dev", (method, url, token, body) -> {
            capturedToken.set(token);
            if ("POST".equals(method) && url.endsWith("/api/users/sync")) {
                String name;
                try {
                    name = new JSONObject(body).getString("displayName");
                } catch (JSONException je) {
                    throw new IOException(je);
                }
                d1Users.putIfAbsent("uid_1", "EV-4R7T2N");
                d1Users.put("uid_1_name", name);
                return new EveBackendClient.HttpResponse(200,
                        "{\"success\":true,\"data\":{\"eveId\":\"" + d1Users.get("uid_1") + "\"}}");
            }
            if ("GET".equals(method) && url.endsWith("/api/users/profile")) {
                return new EveBackendClient.HttpResponse(200,
                        "{\"success\":true,\"data\":{\"id\":\"uid_1\",\"eveId\":\"EV-4R7T2N\",\"email\":\"student@eve.app\",\"displayName\":\""
                                + d1Users.get("uid_1_name") + "\",\"dob\":null,\"category\":\"SSC\",\"username\":\"ev_4r7t2n\",\"bio\":\"\",\"isOnline\":true}}");
            }
            if ("GET".equals(method) && url.endsWith("/api/auth/me")) {
                return new EveBackendClient.HttpResponse(200,
                        "{\"success\":true,\"data\":{\"uid\":\"uid_1\",\"email\":\"student@eve.app\",\"displayName\":\"Rohan\",\"emailVerified\":true,\"isAdmin\":false}}");
            }
            return new EveBackendClient.HttpResponse(404, "{\"success\":false,\"error\":\"Not found\"}");
        });

        EveUserProfile base = new EveUserProfile(
                "uid_1", "", "Rohan", "student@eve.app", "", "password", true,
                EveUserProfile.STATUS_ACTIVE, false, "", "General", "", 1L, 1L, 1L, false);

        // First sync: creates D1 row and returns server-confirmed EVE ID
        EveAuthCoordinator.SyncOutcome first = EveAuthCoordinator.executeCloudSync(client, base, "fresh.firebase.jwt");
        assertEquals(EveAuthCoordinator.SyncState.CLOUD_SYNCHRONIZED, first.state);
        assertEquals(200, first.httpStatus);
        assertEquals("EV-4R7T2N", first.profile.eveId);
        assertTrue(first.profile.syncedWithBackend);
        assertEquals("fresh.firebase.jwt", capturedToken.get());

        // Second sync with updated displayName: prevents duplicate user, preserves EVE ID
        EveUserProfile renamed = new EveUserProfile(
                "uid_1", "", "Rohan Kumar", "student@eve.app", "", "password", true,
                EveUserProfile.STATUS_ACTIVE, false, "", "General", "", 1L, 2L, 2L, false);
        EveAuthCoordinator.SyncOutcome second = EveAuthCoordinator.executeCloudSync(client, renamed, "fresh.firebase.jwt");
        assertEquals(EveAuthCoordinator.SyncState.CLOUD_SYNCHRONIZED, second.state);
        assertEquals("EV-4R7T2N", second.profile.eveId);
        assertEquals("Rohan Kumar", second.profile.displayName);
    }

    @Test
    public void cloudSync_preservesConfirmedEveIdEvenIfEnrichmentFails() {
        EveBackendClient client = new EveBackendClient("https://eve-backend.anyqueairdrop.workers.dev", (method, url, token, body) -> {
            if ("POST".equals(method) && url.endsWith("/api/users/sync")) {
                return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":{\"eveId\":\"EV-8Z3P1Q\"}}");
            }
            // Simulate transient 503 on enrichment endpoints
            return new EveBackendClient.HttpResponse(503, "{\"success\":false,\"error\":\"Temporary D1 timeout\"}");
        });

        EveUserProfile base = new EveUserProfile(
                "uid_2", "", "Meera", "meera@eve.app", "", "google.com", true,
                EveUserProfile.STATUS_ACTIVE, false, "", "General", "", 1L, 1L, 1L, false);

        EveAuthCoordinator.SyncOutcome outcome = EveAuthCoordinator.executeCloudSync(client, base, "valid.jwt");
        assertEquals(EveAuthCoordinator.SyncState.CLOUD_SYNCHRONIZED, outcome.state);
        assertEquals("EV-8Z3P1Q", outcome.profile.eveId);
        assertTrue(outcome.profile.syncedWithBackend);
    }

    @Test
    public void cloudSync_handlesInvalidExpiredTokenD1ErrorDisabledAndOfflineRetry() {
        EveUserProfile base = new EveUserProfile(
                "uid_3", "", "Vikram", "vikram@eve.app", "", "password", true,
                EveUserProfile.STATUS_ACTIVE, false, "", "General", "", 1L, 1L, 1L, false);

        // 1. Missing/empty token -> AUTH_EXPIRED
        EveBackendClient dummy = new EveBackendClient("https://eve-backend.anyqueairdrop.workers.dev",
                (m, u, t, b) -> new EveBackendClient.HttpResponse(401, "{\"success\":false,\"error\":\"Invalid or expired authentication.\",\"code\":\"AUTH_REQUIRED\"}"));
        EveAuthCoordinator.SyncOutcome missing = EveAuthCoordinator.executeCloudSync(dummy, base, "");
        assertEquals(EveAuthCoordinator.SyncState.AUTH_EXPIRED, missing.state);
        assertFalse(missing.profile.syncedWithBackend);

        // 2. Expired/invalid token -> 401 AUTH_EXPIRED
        EveAuthCoordinator.SyncOutcome expired = EveAuthCoordinator.executeCloudSync(dummy, base, "expired.jwt");
        assertEquals(EveAuthCoordinator.SyncState.AUTH_EXPIRED, expired.state);
        assertEquals(401, expired.httpStatus);
        assertTrue(expired.message.contains("HTTP 401"));

        // 3. Disabled account -> 403 ACCOUNT_DISABLED + shouldSignOut=true
        EveBackendClient disabledClient = new EveBackendClient("https://eve-backend.anyqueairdrop.workers.dev",
                (m, u, t, b) -> new EveBackendClient.HttpResponse(403, "{\"success\":false,\"error\":\"This account is disabled. Contact an administrator.\"}"));
        EveAuthCoordinator.SyncOutcome disabled = EveAuthCoordinator.executeCloudSync(disabledClient, base, "valid.jwt");
        assertEquals(EveAuthCoordinator.SyncState.ACCOUNT_DISABLED, disabled.state);
        assertTrue(disabled.shouldSignOut);

        // 4. D1 / Worker 500 error -> OFFLINE_RETRY_REQUIRED with exact HTTP 500 diagnostic, never fake eveId
        EveBackendClient d1ErrClient = new EveBackendClient("https://eve-backend.anyqueairdrop.workers.dev",
                (m, u, t, b) -> new EveBackendClient.HttpResponse(500, "{\"success\":false,\"error\":\"D1 write failed\",\"code\":\"INTERNAL_ERROR\"}"));
        EveAuthCoordinator.SyncOutcome d1Err = EveAuthCoordinator.executeCloudSync(d1ErrClient, base, "valid.jwt");
        assertEquals(EveAuthCoordinator.SyncState.OFFLINE_RETRY_REQUIRED, d1Err.state);
        assertTrue(d1Err.shouldQueueOfflineRetry);
        assertEquals("", d1Err.profile.eveId);
        assertFalse(d1Err.profile.syncedWithBackend);
        assertTrue(d1Err.message.contains("HTTP 500"));

        // 5. Offline -> queued retry -> succeeds when connectivity returns
        AtomicBoolean online = new AtomicBoolean(false);
        EveBackendClient flakyClient = new EveBackendClient("https://eve-backend.anyqueairdrop.workers.dev", (m, u, t, b) -> {
            if (!online.get()) {
                throw new UnknownHostException("eve-backend.anyqueairdrop.workers.dev");
            }
            if (u.endsWith("/api/users/sync")) {
                return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":{\"eveId\":\"EV-RETRY1\"}}");
            }
            return new EveBackendClient.HttpResponse(200, "{\"success\":true,\"data\":{\"eveId\":\"EV-RETRY1\",\"displayName\":\"Vikram\"}}");
        });

        EveAuthCoordinator.SyncOutcome offline = EveAuthCoordinator.executeCloudSync(flakyClient, base, "valid.jwt");
        assertEquals(EveAuthCoordinator.SyncState.OFFLINE_RETRY_REQUIRED, offline.state);
        assertTrue(offline.shouldQueueOfflineRetry);
        assertFalse(offline.profile.syncedWithBackend);

        // Connectivity returns -> retry succeeds
        online.set(true);
        EveAuthCoordinator.SyncOutcome retried = EveAuthCoordinator.executeCloudSync(flakyClient, offline.profile, "valid.jwt");
        assertEquals(EveAuthCoordinator.SyncState.CLOUD_SYNCHRONIZED, retried.state);
        assertEquals("EV-RETRY1", retried.profile.eveId);
        assertTrue(retried.profile.syncedWithBackend);
        assertFalse(retried.shouldQueueOfflineRetry);
    }

    @Test
    public void cloudSync_timeoutErrorClassifiedSeparately() {
        EveBackendClient timeoutClient = new EveBackendClient("https://eve-backend.anyqueairdrop.workers.dev",
                (m, u, t, b) -> { throw new SocketTimeoutException("Read timed out"); });
        try {
            timeoutClient.syncUserProfile("tok", "Test");
            fail("Expected timeout exception");
        } catch (EveBackendClient.BackendException e) {
            assertEquals(EveBackendClient.ErrorKind.NETWORK_TIMEOUT, e.kind);
            assertTrue(e.networkError);
            assertTrue(e.toSafeDiagnosticSummary().contains("SOCKET_TIMEOUT"));
        }
    }

    // -------------------------------------------------------------------------
    // 4. NAVIGATION POLICY TESTS
    // -------------------------------------------------------------------------

    @Test
    public void navigation_emailGoogleOfflineLogoutAndRestartRouting() {
        // Successful Email/Password or Google login with synced cloud -> EVE_HOME
        assertEquals(EveAuthCoordinator.NavDestination.EVE_HOME,
                EveAuthCoordinator.resolvePostAuthDestination(true, EveAuthCoordinator.SyncState.CLOUD_SYNCHRONIZED));

        // Offline cloud sync does NOT create a dead end -> still reaches EVE_HOME
        assertEquals(EveAuthCoordinator.NavDestination.EVE_HOME,
                EveAuthCoordinator.resolvePostAuthDestination(true, EveAuthCoordinator.SyncState.OFFLINE_RETRY_REQUIRED));
        assertEquals(EveAuthCoordinator.NavDestination.EVE_HOME,
                EveAuthCoordinator.resolvePostAuthDestination(true, EveAuthCoordinator.SyncState.SYNCING));

        // Disabled account or expired auth -> EVE_LOGIN
        assertEquals(EveAuthCoordinator.NavDestination.EVE_LOGIN,
                EveAuthCoordinator.resolvePostAuthDestination(true, EveAuthCoordinator.SyncState.ACCOUNT_DISABLED));
        assertEquals(EveAuthCoordinator.NavDestination.EVE_LOGIN,
                EveAuthCoordinator.resolvePostAuthDestination(false, EveAuthCoordinator.SyncState.SIGNED_IN));

        // Restart restores valid session; logout returns to EVE_LOGIN
        assertEquals(EveAuthCoordinator.NavDestination.EVE_HOME,
                EveAuthCoordinator.resolveStartupDestination(true));
        assertEquals(EveAuthCoordinator.NavDestination.EVE_LOGIN,
                EveAuthCoordinator.resolveStartupDestination(false));
    }

    // -------------------------------------------------------------------------
    // 5. FIREBASE JWT CLAIMS CONTRACT & TELEGRAM REMOVAL TESTS
    // -------------------------------------------------------------------------

    @Test
    public void firebaseJwtContract_validatesAlgAudIssExpAndSub() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        assertTrue(verifyJwtClaimsContract(buildUnsignedJwt("RS256", "kid-1", "eve-fb0e3", "https://securetoken.google.com/eve-fb0e3", "uid_1", now + 3600), now));
        assertFalse(verifyJwtClaimsContract(buildUnsignedJwt("RS256", "kid-1", "eve-fb0e3", "https://securetoken.google.com/eve-fb0e3", "uid_1", now - 120), now));
        assertFalse(verifyJwtClaimsContract(buildUnsignedJwt("HS256", "kid-1", "eve-fb0e3", "https://securetoken.google.com/eve-fb0e3", "uid_1", now + 3600), now));
        assertFalse(verifyJwtClaimsContract(buildUnsignedJwt("RS256", "kid-1", "other-project", "https://securetoken.google.com/eve-fb0e3", "uid_1", now + 3600), now));
        assertFalse(verifyJwtClaimsContract(buildUnsignedJwt("RS256", "kid-1", "eve-fb0e3", "https://evil.example.com/eve-fb0e3", "uid_1", now + 3600), now));
        assertFalse(verifyJwtClaimsContract("not-a-jwt", now));
    }

    private static String buildUnsignedJwt(String alg, String kid, String aud, String iss, String sub, long exp) throws Exception {
        JSONObject header = new JSONObject().put("alg", alg).put("kid", kid);
        JSONObject payload = new JSONObject().put("aud", aud).put("iss", iss).put("sub", sub).put("exp", exp);
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8)) + "."
                + enc.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    private static boolean verifyJwtClaimsContract(String jwt, long nowSeconds) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length != 3) return false;
            Base64.Decoder dec = Base64.getUrlDecoder();
            JSONObject header = new JSONObject(new String(dec.decode(parts[0]), StandardCharsets.UTF_8));
            JSONObject payload = new JSONObject(new String(dec.decode(parts[1]), StandardCharsets.UTF_8));
            if (!"RS256".equals(header.optString("alg"))) return false;
            if (header.optString("kid").isEmpty()) return false;
            if (payload.optLong("exp", 0) < nowSeconds - 60) return false;
            String aud = payload.optString("aud");
            if (!"eve-fb0e3".equals(aud) && !"118032999217".equals(aud)) return false;
            String iss = payload.optString("iss");
            if (!"https://securetoken.google.com/eve-fb0e3".equals(iss)
                    && !"https://securetoken.google.com/118032999217".equals(iss)) return false;
            return !payload.optString("sub").isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void officialTelegramLoginFilesAndManifestEntriesAreRestored() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        if (!new File(moduleDir, "src/main/AndroidManifest.xml").exists()) {
            moduleDir = new File("/home/spark/telegram-baseline/TMessagesProj");
        }
        assertTrue(new File(moduleDir, "src/main/java/org/telegram/ui/LoginActivity.java").exists());
        assertTrue(new File(moduleDir, "src/main/java/org/telegram/ui/IntroActivity.java").exists());
        assertTrue(new File(moduleDir, "src/main/java/org/telegram/messenger/SmsReceiver.java").exists());
        assertTrue(new File(moduleDir, "src/main/java/org/telegram/messenger/CallReceiver.java").exists());
        assertTrue(new File(moduleDir, "src/main/java/org/telegram/messenger/FirebasePhoneNumberVerificationHelper.java").exists());
        assertTrue(new File(moduleDir, "src/main/java/org/telegram/messenger/AuthTokensHelper.java").exists());
        assertFalse(new File(moduleDir, "src/main/java/org/telegram/ui/eve/auth/EveAuthFragment.java").exists());

        String manifest = new String(Files.readAllBytes(new File(moduleDir, "src/main/AndroidManifest.xml").toPath()), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("SmsReceiver"));
        assertTrue(manifest.contains("CallReceiver"));
        assertTrue(manifest.contains("READ_PHONE_NUMBERS"));
        assertTrue(manifest.contains("READ_PHONE_STATE"));

        String gradle = new String(Files.readAllBytes(new File(moduleDir, "build.gradle").toPath()), StandardCharsets.UTF_8);
        assertTrue(gradle.contains("com.google.firebase:firebase-auth"));
        assertTrue(gradle.contains("com.google.android.libraries.identity.googleid:googleid"));
        assertTrue(gradle.contains("implementation \"com.google.firebase:firebase-pnv\""));
    }
}
