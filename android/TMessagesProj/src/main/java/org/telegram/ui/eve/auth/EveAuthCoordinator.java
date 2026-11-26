package org.telegram.ui.eve.auth;

/**
 * Pure-Java state machine and policy coordinator for EVE Authentication,
 * Google Sign-In Credential Manager fallback sequencing, Cloud Profile Sync,
 * and EVE Home/Login navigation decisions.
 *
 * Kept free of Android framework dependencies so every branch can be verified
 * deterministically in JVM unit tests.
 */
public final class EveAuthCoordinator {

    public enum SyncState {
        SIGNED_IN,
        SYNCING,
        CLOUD_SYNCHRONIZED,
        OFFLINE_RETRY_REQUIRED,
        AUTH_EXPIRED,
        ACCOUNT_DISABLED
    }

    public enum GoogleSignInStage {
        AUTHORIZED_ACCOUNTS_ONLY,
        ALL_DEVICE_ACCOUNTS_ONE_TAP,
        EXPLICIT_SIGN_IN_WITH_GOOGLE_PICKER
    }

    public enum NavDestination {
        EVE_LOGIN,
        EVE_HOME
    }

    public static final class SyncOutcome {
        public final SyncState state;
        public final EveUserProfile profile;
        public final int httpStatus;
        public final String endpoint;
        public final String errorCode;
        public final String message;
        public final boolean shouldQueueOfflineRetry;
        public final boolean shouldSignOut;

        public SyncOutcome(
                SyncState state,
                EveUserProfile profile,
                int httpStatus,
                String endpoint,
                String errorCode,
                String message,
                boolean shouldQueueOfflineRetry,
                boolean shouldSignOut) {
            this.state = state;
            this.profile = profile;
            this.httpStatus = httpStatus;
            this.endpoint = endpoint != null ? endpoint : "";
            this.errorCode = errorCode != null ? errorCode : "";
            this.message = message != null ? message : "";
            this.shouldQueueOfflineRetry = shouldQueueOfflineRetry;
            this.shouldSignOut = shouldSignOut;
        }
    }

    private EveAuthCoordinator() {
    }

    public static NavDestination resolveStartupDestination(boolean isFirebaseAuthenticated) {
        return isFirebaseAuthenticated ? NavDestination.EVE_HOME : NavDestination.EVE_LOGIN;
    }

    public static NavDestination resolvePostAuthDestination(boolean isFirebaseAuthenticated, SyncState syncState) {
        if (!isFirebaseAuthenticated || syncState == SyncState.ACCOUNT_DISABLED || syncState == SyncState.AUTH_EXPIRED) {
            return NavDestination.EVE_LOGIN;
        }
        // Valid local Firebase session always reaches EVE Home even when cloud sync is offline/retrying.
        return NavDestination.EVE_HOME;
    }

    /**
     * Determines the next Credential Manager Google Sign-In stage when the current stage
     * throws NoCredentialException. Returns null when all 3 stages have been exhausted.
     */
    public static GoogleSignInStage nextGoogleFallbackStage(GoogleSignInStage currentStage) {
        if (currentStage == null) {
            return GoogleSignInStage.AUTHORIZED_ACCOUNTS_ONLY;
        }
        switch (currentStage) {
            case AUTHORIZED_ACCOUNTS_ONLY:
                return GoogleSignInStage.ALL_DEVICE_ACCOUNTS_ONE_TAP;
            case ALL_DEVICE_ACCOUNTS_ONE_TAP:
                return GoogleSignInStage.EXPLICIT_SIGN_IN_WITH_GOOGLE_PICKER;
            case EXPLICIT_SIGN_IN_WITH_GOOGLE_PICKER:
            default:
                return null;
        }
    }

    /**
     * Executes deterministic cloud profile synchronization against the EVE Cloudflare Worker.
     * Step 1: POST /api/users/sync (required for server-confirmed EVE ID & D1 upsert).
     * Step 2: GET  /api/users/profile & GET /api/auth/me (best-effort enrichment; never
     *         invalidates an already-confirmed EVE ID from Step 1).
     */
    public static SyncOutcome executeCloudSync(
            EveBackendClient backendClient,
            EveUserProfile baseProfile,
            String firebaseIdToken) {
        if (baseProfile == null) {
            return new SyncOutcome(
                    SyncState.AUTH_EXPIRED, null, 401, "/api/users/sync", "NO_LOCAL_PROFILE",
                    "Session is not initialized. Please sign in again.", false, true);
        }
        if (firebaseIdToken == null || firebaseIdToken.trim().isEmpty()) {
            return new SyncOutcome(
                    SyncState.AUTH_EXPIRED, baseProfile, 401, "/api/users/sync", "MISSING_TOKEN",
                    "Authentication token unavailable. Please sign in again.", false, false);
        }

        try {
            EveBackendClient.SyncResponse syncResp = backendClient.syncUserProfile(firebaseIdToken, baseProfile.displayName);
            String resolvedEveId = syncResp.eveId;
            String resolvedName = baseProfile.displayName;
            String resolvedUsername = baseProfile.username;
            String resolvedCategory = baseProfile.category;
            String resolvedBio = baseProfile.bio;
            String resolvedDob = baseProfile.dob;
            String resolvedPhone = baseProfile.phone;
            boolean resolvedIsAdmin = baseProfile.isAdmin;

            try {
                EveBackendClient.ProfileResponse profileResp = backendClient.fetchUserProfile(firebaseIdToken);
                if (profileResp.eveId != null && !profileResp.eveId.isEmpty()) {
                    resolvedEveId = profileResp.eveId;
                }
                if (profileResp.displayName != null && !profileResp.displayName.trim().isEmpty()) {
                    resolvedName = profileResp.displayName;
                }
                resolvedUsername = profileResp.username;
                resolvedCategory = profileResp.category;
                resolvedBio = profileResp.bio;
                resolvedDob = profileResp.dob;
                resolvedPhone = profileResp.phone;
            } catch (EveBackendClient.BackendException enrichmentErr) {
                if (enrichmentErr.accountDisabled) {
                    throw enrichmentErr;
                }
                // Best-effort enrichment; keep confirmed EVE ID from /api/users/sync.
            }

            try {
                EveBackendClient.AuthMeResponse meResp = backendClient.fetchAuthMe(firebaseIdToken);
                resolvedIsAdmin = meResp.isAdmin;
            } catch (EveBackendClient.BackendException enrichmentErr) {
                if (enrichmentErr.accountDisabled) {
                    throw enrichmentErr;
                }
            }

            EveUserProfile synced = baseProfile.withBackendSync(
                    resolvedEveId,
                    resolvedName,
                    resolvedUsername,
                    resolvedCategory,
                    resolvedBio,
                    resolvedDob,
                    resolvedPhone,
                    resolvedIsAdmin,
                    System.currentTimeMillis()
            );
            return new SyncOutcome(
                    SyncState.CLOUD_SYNCHRONIZED,
                    synced,
                    200,
                    "/api/users/sync",
                    "OK",
                    "Profile synchronized with EVE Cloud (" + resolvedEveId + ").",
                    false,
                    false
            );
        } catch (EveBackendClient.BackendException e) {
            if (e.accountDisabled) {
                return new SyncOutcome(
                        SyncState.ACCOUNT_DISABLED,
                        baseProfile,
                        e.httpStatus,
                        e.endpoint,
                        e.serverCode,
                        e.toSafeDiagnosticSummary(),
                        false,
                        true
                );
            }
            if (e.unauthorized) {
                return new SyncOutcome(
                        SyncState.AUTH_EXPIRED,
                        baseProfile,
                        e.httpStatus,
                        e.endpoint,
                        e.serverCode,
                        e.toSafeDiagnosticSummary(),
                        false,
                        false
                );
            }
            return new SyncOutcome(
                    SyncState.OFFLINE_RETRY_REQUIRED,
                    baseProfile,
                    e.httpStatus,
                    e.endpoint,
                    e.serverCode.isEmpty() ? e.kind.name() : e.serverCode,
                    e.toSafeDiagnosticSummary(),
                    true,
                    false
            );
        }
    }
}
