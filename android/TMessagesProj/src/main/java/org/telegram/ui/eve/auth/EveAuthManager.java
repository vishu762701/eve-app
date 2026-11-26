package org.telegram.ui.eve.auth;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.UserInfo;
import com.google.firebase.auth.UserProfileChangeRequest;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central EVE Authentication & Session Manager powered exclusively by Firebase Authentication,
 * Android Credential Manager (3-stage Google Sign-In with explicit account-picker fallback),
 * and the EVE Cloudflare Worker / D1 backend.
 *
 * Security invariants:
 *  - Never logs passwords, full Firebase ID tokens, OAuth tokens, or private keys.
 *  - Always requests fresh Firebase ID tokens (getIdToken(true)) for cloud profile synchronization.
 *  - Never fakes an EVE ID or marks cloud sync successful on failure.
 *  - Queues safe automatic connectivity retry when offline without blocking EVE Home navigation.
 */
public final class EveAuthManager {

    private static final String PREFS_NAME = "eve_auth_session";
    private static final String KEY_CACHED_PROFILE = "cached_profile_json";

    private static volatile EveAuthManager instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean syncInFlight = new AtomicBoolean(false);
    private final EveBackendClient backendClient;

    @Nullable
    private FirebaseAuth firebaseAuth;
    @Nullable
    private volatile EveUserProfile cachedProfile;
    @NonNull
    private volatile EveAuthCoordinator.SyncState currentSyncState = EveAuthCoordinator.SyncState.SIGNED_IN;
    @NonNull
    private volatile String lastSyncDiagnostic = "";
    @Nullable
    private volatile AuthCredential pendingLinkCredential;
    @Nullable
    private volatile String pendingLinkEmail;
    private volatile boolean initialized;
    private volatile boolean offlineRetryQueued;
    @Nullable
    private ConnectivityManager.NetworkCallback connectivityCallback;

    public interface AuthCallback {
        void onSuccess(@NonNull EveUserProfile profile, @NonNull EveAuthCoordinator.SyncOutcome syncOutcome);
        void onError(@NonNull String message, boolean accountDisabled);
    }

    public interface SimpleCallback {
        void onSuccess(@NonNull String message);
        void onError(@NonNull String message);
    }

    public interface TokenCallback {
        void onToken(@Nullable String token, @Nullable String errorMessage);
    }

    public interface SessionListener {
        void onSessionChanged(
                boolean authenticated,
                @Nullable EveUserProfile profile,
                @NonNull EveAuthCoordinator.SyncState syncState,
                @NonNull String diagnosticMessage);
    }

    private final FirebaseAuth.AuthStateListener authStateListener = auth -> {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            clearCachedProfile();
            currentSyncState = EveAuthCoordinator.SyncState.SIGNED_IN;
            lastSyncDiagnostic = "";
            unregisterConnectivityRetry();
            notifyListeners(false, null, currentSyncState, "");
        } else {
            EveUserProfile profile = cachedProfile;
            if (profile == null || !user.getUid().equals(profile.uid)) {
                profile = buildLocalProfile(user, null);
                saveCachedProfile(profile);
            }
            if (profile.syncedWithBackend && !profile.eveId.isEmpty()) {
                currentSyncState = EveAuthCoordinator.SyncState.CLOUD_SYNCHRONIZED;
            }
            notifyListeners(true, profile, currentSyncState, lastSyncDiagnostic);
        }
    };

    private EveAuthManager() {
        this.backendClient = new EveBackendClient();
        ensureInitialized();
    }

    public static EveAuthManager getInstance() {
        EveAuthManager local = instance;
        if (local == null) {
            synchronized (EveAuthManager.class) {
                local = instance;
                if (local == null) {
                    local = new EveAuthManager();
                    instance = local;
                }
            }
        }
        return local;
    }

    public synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        try {
            Context appContext = ApplicationLoader.applicationContext;
            if (appContext != null) {
                if (FirebaseApp.getApps(appContext).isEmpty()) {
                    FirebaseApp.initializeApp(appContext);
                }
                SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                cachedProfile = EveUserProfile.fromJson(prefs.getString(KEY_CACHED_PROFILE, null));
                if (cachedProfile != null && cachedProfile.syncedWithBackend && !cachedProfile.eveId.isEmpty()) {
                    currentSyncState = EveAuthCoordinator.SyncState.CLOUD_SYNCHRONIZED;
                }
            }
            firebaseAuth = FirebaseAuth.getInstance();
            firebaseAuth.addAuthStateListener(authStateListener);
            initialized = true;
        } catch (Throwable t) {
            FileLog.e("EveAuthManager: Firebase initialization failed", t);
        }
    }

    @Nullable
    public FirebaseUser getCurrentUser() {
        ensureInitialized();
        return firebaseAuth != null ? firebaseAuth.getCurrentUser() : null;
    }

    public boolean isAuthenticated() {
        return getCurrentUser() != null;
    }

    public boolean isSignedInAccount() {
        FirebaseUser user = getCurrentUser();
        return user != null && !user.isAnonymous();
    }

    public void refreshSessionSilentlyIfSignedIn() {
        ensureInitialized();
        FirebaseUser user = getCurrentUser();
        if (user == null || user.isAnonymous()) {
            return;
        }
        reloadUserAndSync(new AuthCallback() {
            @Override
            public void onSuccess(@NonNull EveUserProfile profile, @NonNull EveAuthCoordinator.SyncOutcome syncOutcome) {
                org.telegram.ui.eve.notifications.EveNotificationHelper.syncFcmTokenIfSignedIn();
            }

            @Override
            public void onError(@NonNull String message, boolean accountDisabled) {
            }
        });
    }

    public boolean isSyncInFlight() {
        return syncInFlight.get();
    }

    @NonNull
    public EveAuthCoordinator.SyncState getCurrentSyncState() {
        return currentSyncState;
    }

    @NonNull
    public String getLastSyncDiagnostic() {
        return lastSyncDiagnostic;
    }

    public boolean hasPendingGoogleLink() {
        return pendingLinkCredential != null;
    }

    @Nullable
    public String getPendingLinkEmail() {
        return pendingLinkEmail;
    }

    @Nullable
    public EveUserProfile getCachedProfile() {
        ensureInitialized();
        FirebaseUser user = getCurrentUser();
        if (user == null) {
            return null;
        }
        EveUserProfile local = cachedProfile;
        if (local == null || !user.getUid().equals(profileUid(local))) {
            local = buildLocalProfile(user, null);
            saveCachedProfile(local);
        }
        return local;
    }

    private static String profileUid(@Nullable EveUserProfile profile) {
        return profile != null && profile.uid != null ? profile.uid : "";
    }

    public void addSessionListener(@NonNull SessionListener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeSessionListener(@NonNull SessionListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(
            boolean authenticated,
            @Nullable EveUserProfile profile,
            @NonNull EveAuthCoordinator.SyncState state,
            @NonNull String diagnostic) {
        mainHandler.post(() -> {
            for (SessionListener listener : listeners) {
                listener.onSessionChanged(authenticated, profile, state, diagnostic);
            }
        });
    }

    public void signInWithEmail(@NonNull String email, @NonNull String password, @NonNull AuthCallback callback) {
        ensureInitialized();
        EveAuthValidator.ValidationResult validation = EveAuthValidator.validateLoginInput(email, password);
        if (!validation.valid) {
            postError(callback, validation.errorMessage, false);
            return;
        }
        if (firebaseAuth == null) {
            postError(callback, "Authentication service is unavailable.", false);
            return;
        }
        final String cleanEmail = EveAuthValidator.normalizeEmail(email);
        firebaseAuth.signInWithEmailAndPassword(cleanEmail, password)
                .addOnSuccessListener(authResult -> {
                    FirebaseUser user = authResult.getUser();
                    if (user == null) {
                        postError(callback, "Sign-in succeeded but user session could not be loaded.", false);
                        return;
                    }
                    // If the user previously attempted Google Sign-In for this email, link Google securely now.
                    AuthCredential linkCred = pendingLinkCredential;
                    if (linkCred != null) {
                        pendingLinkCredential = null;
                        pendingLinkEmail = null;
                        user.linkWithCredential(linkCred)
                                .addOnCompleteListener(linkTask -> {
                                    if (!linkTask.isSuccessful()) {
                                        FileLog.e("EveAuthManager: Google credential link skipped/failed", linkTask.getException());
                                    }
                                    completeLocalLoginAndStartSync(user, null, callback);
                                });
                    } else {
                        completeLocalLoginAndStartSync(user, null, callback);
                    }
                })
                .addOnFailureListener(e -> postError(callback, mapFirebaseError(e), isDisabledError(e)));
    }

    public void registerWithEmail(
            @NonNull String fullName,
            @NonNull String email,
            @NonNull String password,
            @NonNull String confirmPassword,
            @NonNull AuthCallback callback) {
        ensureInitialized();
        EveAuthValidator.ValidationResult validation = EveAuthValidator.validateRegistrationInput(
                fullName, email, password, confirmPassword);
        if (!validation.valid) {
            postError(callback, validation.errorMessage, false);
            return;
        }
        if (firebaseAuth == null) {
            postError(callback, "Authentication service is unavailable.", false);
            return;
        }
        final String trimmedName = fullName.trim();
        final String cleanEmail = EveAuthValidator.normalizeEmail(email);

        firebaseAuth.createUserWithEmailAndPassword(cleanEmail, password)
                .addOnSuccessListener(authResult -> {
                    FirebaseUser user = authResult.getUser();
                    if (user == null) {
                        postError(callback, "Account created but user session could not be loaded.", false);
                        return;
                    }
                    UserProfileChangeRequest profileUpdate = new UserProfileChangeRequest.Builder()
                            .setDisplayName(trimmedName)
                            .build();
                    user.updateProfile(profileUpdate).addOnCompleteListener(updateTask -> {
                        user.sendEmailVerification().addOnFailureListener(err ->
                                FileLog.e("EveAuthManager: sendEmailVerification failed", err));
                        completeLocalLoginAndStartSync(user, trimmedName, callback);
                    });
                })
                .addOnFailureListener(e -> postError(callback, mapFirebaseError(e), isDisabledError(e)));
    }

    private void completeLocalLoginAndStartSync(
            @NonNull FirebaseUser user,
            @Nullable String overrideDisplayName,
            @NonNull AuthCallback callback) {
        EveUserProfile localProfile = buildLocalProfile(user, overrideDisplayName);
        saveCachedProfile(localProfile);
        currentSyncState = EveAuthCoordinator.SyncState.SYNCING;
        lastSyncDiagnostic = "Synchronizing profile with EVE Cloud...";
        notifyListeners(true, localProfile, currentSyncState, lastSyncDiagnostic);
        syncSessionWithBackend(user, overrideDisplayName, callback);
    }

    public void sendPasswordReset(@NonNull String email, @NonNull SimpleCallback callback) {
        ensureInitialized();
        EveAuthValidator.ValidationResult validation = EveAuthValidator.validateEmail(email);
        if (!validation.valid) {
            mainHandler.post(() -> callback.onError(validation.errorMessage));
            return;
        }
        if (firebaseAuth == null) {
            mainHandler.post(() -> callback.onError("Authentication service is unavailable."));
            return;
        }
        String cleanEmail = EveAuthValidator.normalizeEmail(email);
        firebaseAuth.sendPasswordResetEmail(cleanEmail)
                .addOnSuccessListener(unused -> mainHandler.post(() ->
                        callback.onSuccess("Password reset instructions have been sent to " + cleanEmail + ".")))
                .addOnFailureListener(e -> mainHandler.post(() -> callback.onError(mapFirebaseError(e))));
    }

    public void sendEmailVerification(@NonNull SimpleCallback callback) {
        FirebaseUser user = getCurrentUser();
        if (user == null) {
            mainHandler.post(() -> callback.onError("Please sign in first."));
            return;
        }
        if (user.isEmailVerified()) {
            mainHandler.post(() -> callback.onSuccess("Your email address is already verified."));
            return;
        }
        user.sendEmailVerification()
                .addOnSuccessListener(unused -> mainHandler.post(() ->
                        callback.onSuccess("Verification email sent to " + (user.getEmail() != null ? user.getEmail() : "your email") + ".")))
                .addOnFailureListener(e -> mainHandler.post(() -> callback.onError(mapFirebaseError(e))));
    }

    public void reloadUserAndSync(@NonNull AuthCallback callback) {
        FirebaseUser user = getCurrentUser();
        if (user == null) {
            postError(callback, "No active session found. Please sign in.", false);
            return;
        }
        if (!syncInFlight.compareAndSet(false, true)) {
            EveUserProfile local = getCachedProfile();
            if (local != null) {
                postSuccess(callback, local, new EveAuthCoordinator.SyncOutcome(
                        EveAuthCoordinator.SyncState.SYNCING, local, 0, "/api/users/sync", "IN_FLIGHT",
                        "Profile sync already in progress...", false, false));
            }
            return;
        }
        currentSyncState = EveAuthCoordinator.SyncState.SYNCING;
        lastSyncDiagnostic = "Refreshing session and synchronizing with EVE Cloud...";
        notifyListeners(true, getCachedProfile(), currentSyncState, lastSyncDiagnostic);

        user.reload()
                .addOnSuccessListener(unused -> {
                    FirebaseUser refreshed = getCurrentUser();
                    if (refreshed == null) {
                        syncInFlight.set(false);
                        currentSyncState = EveAuthCoordinator.SyncState.AUTH_EXPIRED;
                        postError(callback, "Session is no longer valid. Please sign in again.", false);
                        return;
                    }
                    runFreshTokenSync(refreshed, null, true, callback);
                })
                .addOnFailureListener(e -> {
                    if (isDisabledError(e)) {
                        syncInFlight.set(false);
                        signOut(ApplicationLoader.applicationContext, () ->
                                postError(callback, EveAuthValidator.mapAuthErrorMessage("ERROR_USER_DISABLED", null), true));
                    } else {
                        // Even if user.reload() fails due to transient network, try fresh token sync or report offline clearly.
                        runFreshTokenSync(user, null, true, callback);
                    }
                });
    }

    /**
     * Initiates Google Sign-In via Android Credential Manager using the official 3-stage fallback:
     *   Stage 1: GetGoogleIdOption(filterByAuthorizedAccounts = true)
     *   Stage 2: GetGoogleIdOption(filterByAuthorizedAccounts = false, autoSelectEnabled = false)
     *   Stage 3: GetSignInWithGoogleOption (explicit Google account picker sheet)
     */
    public void signInWithGoogle(@NonNull Activity activity, @NonNull AuthCallback callback) {
        ensureInitialized();
        if (firebaseAuth == null) {
            postError(callback, "Authentication service is unavailable.", false);
            return;
        }
        String webClientId = resolveDefaultWebClientId(activity);
        if (!EveAuthValidator.isValidWebOAuthClientId(webClientId)) {
            postError(callback, EveAuthValidator.mapAuthErrorMessage("ERROR_MISSING_WEB_CLIENT_ID", null), false);
            return;
        }
        pendingLinkCredential = null;
        pendingLinkEmail = null;
        launchGoogleCredentialStage(
                activity,
                webClientId,
                EveAuthCoordinator.GoogleSignInStage.AUTHORIZED_ACCOUNTS_ONLY,
                callback
        );
    }

    private void launchGoogleCredentialStage(
            @NonNull Activity activity,
            @NonNull String webClientId,
            @NonNull EveAuthCoordinator.GoogleSignInStage stage,
            @NonNull AuthCallback callback) {
        CredentialManager credentialManager = CredentialManager.create(activity);
        GetCredentialRequest.Builder reqBuilder = new GetCredentialRequest.Builder();

        if (stage == EveAuthCoordinator.GoogleSignInStage.AUTHORIZED_ACCOUNTS_ONLY) {
            GetGoogleIdOption option = new GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(true)
                    .setServerClientId(webClientId)
                    .setAutoSelectEnabled(false)
                    .build();
            reqBuilder.addCredentialOption(option);
        } else if (stage == EveAuthCoordinator.GoogleSignInStage.ALL_DEVICE_ACCOUNTS_ONE_TAP) {
            GetGoogleIdOption option = new GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(webClientId)
                    .setAutoSelectEnabled(false)
                    .build();
            reqBuilder.addCredentialOption(option);
        } else {
            GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(webClientId)
                    .build();
            reqBuilder.addCredentialOption(option);
        }

        credentialManager.getCredentialAsync(
                activity,
                reqBuilder.build(),
                new CancellationSignal(),
                ioExecutor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        handleGoogleCredentialResponse(result, callback);
                    }

                    @Override
                    public void onError(@NonNull GetCredentialException e) {
                        if (e instanceof GetCredentialCancellationException) {
                            if (stage != EveAuthCoordinator.GoogleSignInStage.EXPLICIT_SIGN_IN_WITH_GOOGLE_PICKER) {
                                FileLog.d("EveAuthManager: Stage " + stage + " cancelled/dismissed; continuing to explicit Google picker.");
                                mainHandler.post(() -> launchGoogleCredentialStage(
                                        activity,
                                        webClientId,
                                        EveAuthCoordinator.GoogleSignInStage.EXPLICIT_SIGN_IN_WITH_GOOGLE_PICKER,
                                        callback));
                                return;
                            }
                            postError(callback, EveAuthValidator.mapAuthErrorMessage("ERROR_CREDENTIAL_CANCELLED", null), false);
                            return;
                        }
                        if (e instanceof NoCredentialException
                                || stage != EveAuthCoordinator.GoogleSignInStage.EXPLICIT_SIGN_IN_WITH_GOOGLE_PICKER) {
                            EveAuthCoordinator.GoogleSignInStage nextStage = EveAuthCoordinator.nextGoogleFallbackStage(stage);
                            if (nextStage != null) {
                                FileLog.d("EveAuthManager: Credential Manager stage " + stage + " failed (" + e.getClass().getSimpleName() + "); retrying with " + nextStage);
                                mainHandler.post(() -> launchGoogleCredentialStage(activity, webClientId, nextStage, callback));
                                return;
                            }
                            if (e instanceof NoCredentialException) {
                                FileLog.e("EveAuthManager: All Google Sign-In Credential Manager stages exhausted", e);
                                postError(callback, EveAuthValidator.mapAuthErrorMessage("ERROR_NO_GOOGLE_CREDENTIAL", e.getMessage()), false);
                                return;
                            }
                        }
                        FileLog.e("EveAuthManager: Credential Manager error at stage " + stage, e);
                        postError(callback, EveAuthValidator.mapAuthErrorMessage(null, e.getMessage()), false);
                    }
                }
        );
    }

    private void handleGoogleCredentialResponse(@NonNull GetCredentialResponse response, @NonNull AuthCallback callback) {
        Credential credential = response.getCredential();
        if (credential instanceof CustomCredential
                && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
            try {
                GoogleIdTokenCredential googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.getData());
                String idToken = googleIdTokenCredential.getIdToken();
                AuthCredential firebaseCredential = GoogleAuthProvider.getCredential(idToken, null);
                if (firebaseAuth == null) {
                    postError(callback, "Authentication service is unavailable.", false);
                    return;
                }
                firebaseAuth.signInWithCredential(firebaseCredential)
                        .addOnSuccessListener(authResult -> {
                            FirebaseUser user = authResult.getUser();
                            if (user == null) {
                                postError(callback, "Google Sign-In completed but user session was empty.", false);
                                return;
                            }
                            completeLocalLoginAndStartSync(user, googleIdTokenCredential.getDisplayName(), callback);
                        })
                        .addOnFailureListener(e -> {
                            if (e instanceof FirebaseAuthUserCollisionException) {
                                pendingLinkCredential = firebaseCredential;
                                pendingLinkEmail = googleIdTokenCredential.getId();
                                postError(callback, EveAuthValidator.mapAuthErrorMessage(
                                        "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL", e.getMessage()), false);
                            } else {
                                postError(callback, mapFirebaseError(e), isDisabledError(e));
                            }
                        });
            } catch (Throwable t) {
                FileLog.e("EveAuthManager: Failed to parse Google ID token credential", t);
                postError(callback, "Failed to verify Google account credential. Please try again.", false);
            }
        } else {
            postError(callback, "Unsupported credential type returned by Google Sign-In.", false);
        }
    }

    public void getFreshIdToken(boolean forceRefresh, @NonNull TokenCallback callback) {
        FirebaseUser user = getCurrentUser();
        if (user == null) {
            mainHandler.post(() -> callback.onToken(null, "Not signed in."));
            return;
        }
        user.getIdToken(forceRefresh)
                .addOnSuccessListener(result -> mainHandler.post(() -> callback.onToken(result.getToken(), null)))
                .addOnFailureListener(e -> mainHandler.post(() -> callback.onToken(null, mapFirebaseError(e))));
    }

    public void syncSessionWithBackend(
            @NonNull FirebaseUser user,
            @Nullable String overrideDisplayName,
            @NonNull AuthCallback callback) {
        if (!syncInFlight.compareAndSet(false, true)) {
            EveUserProfile local = getCachedProfile();
            if (local != null) {
                postSuccess(callback, local, new EveAuthCoordinator.SyncOutcome(
                        EveAuthCoordinator.SyncState.SYNCING, local, 0, "/api/users/sync", "IN_FLIGHT",
                        "Profile sync already in progress...", false, false));
            }
            return;
        }
        runFreshTokenSync(user, overrideDisplayName, true, callback);
    }

    private void runFreshTokenSync(
            @NonNull FirebaseUser user,
            @Nullable String overrideDisplayName,
            boolean allowRetryOn401,
            @NonNull AuthCallback callback) {
        final EveUserProfile baseProfile = buildLocalProfile(user, overrideDisplayName);
        // Preserve any previously confirmed eveId while syncing so we never regress UI state.
        final EveUserProfile existing = cachedProfile;
        final EveUserProfile workingProfile = (existing != null && user.getUid().equals(existing.uid) && !existing.eveId.isEmpty())
                ? baseProfile.withBackendSync(
                existing.eveId,
                baseProfile.displayName,
                existing.username,
                existing.category,
                existing.bio,
                existing.dob,
                existing.phone,
                existing.isAdmin,
                baseProfile.updatedAt)
                : baseProfile;
        saveCachedProfile(workingProfile);

        // Always request a fresh Firebase ID token (getIdToken(true)) for cloud profile sync.
        user.getIdToken(true)
                .addOnSuccessListener(tokenResult -> {
                    String idToken = tokenResult.getToken();
                    if (idToken == null || idToken.trim().isEmpty()) {
                        syncInFlight.set(false);
                        EveAuthCoordinator.SyncOutcome outcome = new EveAuthCoordinator.SyncOutcome(
                                EveAuthCoordinator.SyncState.AUTH_EXPIRED, workingProfile, 401,
                                "/api/users/sync", "EMPTY_ID_TOKEN",
                                "Firebase returned an empty ID token. Please sign in again.", false, false);
                        applySyncOutcome(outcome, callback);
                        return;
                    }
                    ioExecutor.execute(() -> {
                        EveAuthCoordinator.SyncOutcome outcome = EveAuthCoordinator.executeCloudSync(
                                backendClient, workingProfile, idToken);
                        if (outcome.state == EveAuthCoordinator.SyncState.AUTH_EXPIRED && allowRetryOn401) {
                            // One retry with another forced refresh in case of clock skew / key rotation.
                            runFreshTokenSync(user, overrideDisplayName, false, callback);
                            return;
                        }
                        syncInFlight.set(false);
                        applySyncOutcome(outcome, callback);
                    });
                })
                .addOnFailureListener(e -> {
                    syncInFlight.set(false);
                    if (isDisabledError(e)) {
                        signOut(ApplicationLoader.applicationContext, () ->
                                postError(callback, EveAuthValidator.mapAuthErrorMessage("ERROR_USER_DISABLED", null), true));
                        return;
                    }
                    String errCode = (e instanceof FirebaseAuthException)
                            ? ((FirebaseAuthException) e).getErrorCode()
                            : "FIREBASE_TOKEN_ERROR";
                    String safeMsg = mapFirebaseError(e) + " [" + errCode + "]";
                    EveAuthCoordinator.SyncOutcome outcome = new EveAuthCoordinator.SyncOutcome(
                            EveAuthCoordinator.SyncState.OFFLINE_RETRY_REQUIRED,
                            workingProfile,
                            0,
                            "firebase:getIdToken(true)",
                            errCode,
                            safeMsg,
                            true,
                            false
                    );
                    applySyncOutcome(outcome, callback);
                });
    }

    private void applySyncOutcome(@NonNull EveAuthCoordinator.SyncOutcome outcome, @NonNull AuthCallback callback) {
        currentSyncState = outcome.state;
        lastSyncDiagnostic = outcome.message;
        FileLog.d("EveAuthManager: Cloud sync state=" + outcome.state
                + " http=" + outcome.httpStatus
                + " endpoint=" + outcome.endpoint
                + " code=" + outcome.errorCode);

        if (outcome.shouldSignOut) {
            unregisterConnectivityRetry();
            signOut(ApplicationLoader.applicationContext, () ->
                    postError(callback, outcome.message, outcome.state == EveAuthCoordinator.SyncState.ACCOUNT_DISABLED));
            return;
        }

        if (outcome.profile != null) {
            saveCachedProfile(outcome.profile);
            ensureLocalUiUser(UserConfig.selectedAccount);
        }

        if (outcome.shouldQueueOfflineRetry) {
            queueConnectivityRetry();
        } else {
            unregisterConnectivityRetry();
        }

        notifyListeners(true, outcome.profile, outcome.state, outcome.message);
        if (outcome.profile != null) {
            postSuccess(callback, outcome.profile, outcome);
        } else {
            postError(callback, outcome.message, false);
        }
    }

    private synchronized void queueConnectivityRetry() {
        if (offlineRetryQueued) {
            return;
        }
        Context appContext = ApplicationLoader.applicationContext;
        if (appContext == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            connectivityCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    if (isAuthenticated() && currentSyncState == EveAuthCoordinator.SyncState.OFFLINE_RETRY_REQUIRED) {
                        FileLog.d("EveAuthManager: Network available; retrying queued cloud profile sync.");
                        mainHandler.post(() -> reloadUserAndSync(new AuthCallback() {
                            @Override
                            public void onSuccess(@NonNull EveUserProfile profile, @NonNull EveAuthCoordinator.SyncOutcome syncOutcome) {
                            }

                            @Override
                            public void onError(@NonNull String message, boolean accountDisabled) {
                            }
                        }));
                    }
                }
            };
            cm.registerDefaultNetworkCallback(connectivityCallback);
            offlineRetryQueued = true;
        } catch (Throwable t) {
            FileLog.e("EveAuthManager: Could not register connectivity retry callback", t);
        }
    }

    private synchronized void unregisterConnectivityRetry() {
        if (!offlineRetryQueued) {
            return;
        }
        offlineRetryQueued = false;
        Context appContext = ApplicationLoader.applicationContext;
        if (appContext != null && connectivityCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(connectivityCallback);
                }
            } catch (Throwable ignored) {
            }
        }
        connectivityCallback = null;
    }

    public void signOut(@Nullable Context context, @Nullable Runnable onComplete) {
        ensureInitialized();
        unregisterConnectivityRetry();
        pendingLinkCredential = null;
        pendingLinkEmail = null;
        syncInFlight.set(false);
        clearCachedProfile();
        currentSyncState = EveAuthCoordinator.SyncState.SIGNED_IN;
        lastSyncDiagnostic = "";
        if (firebaseAuth != null) {
            try {
                firebaseAuth.signOut();
            } catch (Throwable t) {
                FileLog.e("EveAuthManager: Firebase signOut error", t);
            }
        }
        Context targetContext = context != null ? context : ApplicationLoader.applicationContext;
        if (targetContext != null) {
            try {
                CredentialManager credentialManager = CredentialManager.create(targetContext);
                credentialManager.clearCredentialStateAsync(
                        new ClearCredentialStateRequest(),
                        new CancellationSignal(),
                        ioExecutor,
                        new CredentialManagerCallback<Void, ClearCredentialException>() {
                            @Override
                            public void onResult(Void result) {
                                finishSignOut(onComplete);
                            }

                            @Override
                            public void onError(@NonNull ClearCredentialException e) {
                                finishSignOut(onComplete);
                            }
                        }
                );
                return;
            } catch (Throwable t) {
                FileLog.e("EveAuthManager: clearCredentialStateAsync error", t);
            }
        }
        finishSignOut(onComplete);
    }

    private void finishSignOut(@Nullable Runnable onComplete) {
        notifyListeners(false, null, EveAuthCoordinator.SyncState.SIGNED_IN, "");
        if (onComplete != null) {
            mainHandler.post(onComplete);
        }
    }

    @Nullable
    private static String resolveDefaultWebClientId(@NonNull Context context) {
        int resId = context.getResources().getIdentifier("default_web_client_id", "string", context.getPackageName());
        if (resId == 0) {
            return null;
        }
        try {
            return context.getString(resId);
        } catch (Throwable t) {
            return null;
        }
    }

    private static EveUserProfile buildLocalProfile(@NonNull FirebaseUser user, @Nullable String overrideDisplayName) {
        String provider = "password";
        List<? extends UserInfo> providers = user.getProviderData();
        if (providers != null) {
            for (UserInfo info : providers) {
                if (info != null && "google.com".equals(info.getProviderId())) {
                    provider = "google.com";
                    break;
                }
            }
        }
        String name = overrideDisplayName;
        if (name == null || name.trim().isEmpty()) {
            name = user.getDisplayName();
        }
        if ((name == null || name.trim().isEmpty()) && user.getEmail() != null) {
            int at = user.getEmail().indexOf('@');
            name = at > 0 ? user.getEmail().substring(0, at) : "Student";
        }
        Uri photo = user.getPhotoUrl();
        long now = System.currentTimeMillis();
        long created = user.getMetadata() != null ? user.getMetadata().getCreationTimestamp() : now;
        long lastSignIn = user.getMetadata() != null ? user.getMetadata().getLastSignInTimestamp() : now;

        return new EveUserProfile(
                user.getUid(),
                "",
                name != null ? name : "Student",
                user.getEmail() != null ? user.getEmail() : "",
                photo != null ? photo.toString() : "",
                provider,
                user.isEmailVerified(),
                user.isEmailVerified() ? EveUserProfile.STATUS_ACTIVE : EveUserProfile.STATUS_UNVERIFIED,
                false,
                "",
                "General",
                "",
                created,
                now,
                lastSignIn,
                false
        );
    }

    private void saveCachedProfile(@NonNull EveUserProfile profile) {
        cachedProfile = profile;
        Context appContext = ApplicationLoader.applicationContext;
        if (appContext != null) {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CACHED_PROFILE, profile.toJson().toString())
                    .apply();
        }
    }

    private void clearCachedProfile() {
        cachedProfile = null;
        Context appContext = ApplicationLoader.applicationContext;
        if (appContext != null) {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply();
        }
    }

    private static String mapFirebaseError(@Nullable Exception e) {
        if (e instanceof FirebaseAuthException) {
            return EveAuthValidator.mapAuthErrorMessage(((FirebaseAuthException) e).getErrorCode(), e.getMessage());
        }
        return EveAuthValidator.mapAuthErrorMessage(null, e != null ? e.getMessage() : null);
    }

    private static boolean isDisabledError(@Nullable Exception e) {
        if (e instanceof FirebaseAuthException) {
            return "ERROR_USER_DISABLED".equals(((FirebaseAuthException) e).getErrorCode());
        }
        return e != null && e.getMessage() != null && e.getMessage().toLowerCase().contains("disabled");
    }

    public long getEffectiveUiUserId() {
        EveUserProfile profile = getCachedProfile();
        if (!isSignedInAccount() || profile == null || profile.uid.isEmpty()) {
            return 0L;
        }
        return 1_000_000L + (Math.abs((long) profile.uid.hashCode()) % 899_000_000L);
    }

    /**
     * Populates an in-memory TLRPC.User & TLRPC.UserFull mirror from the authenticated EVE profile
     * so Telegram's retained UI components (ProfileActivity, SettingsActivity, UserInfoActivity)
     * render EVE account data without requiring Telegram phone/OTP login or MTProto auth keys.
     */
    private volatile String lastEnsuredUiSignature = "";

    public long ensureLocalUiUser(int account) {
        long userId = getEffectiveUiUserId();
        EveUserProfile profile = getCachedProfile();
        if (userId == 0L || profile == null) {
            return 0L;
        }
        String signature = account + "|" + profile.uid + "|" + profile.displayName + "|"
                + profile.username + "|" + profile.bio + "|" + profile.dob + "|" + profile.phone;
        MessagesController mc = MessagesController.getInstance(account);
        if (signature.equals(lastEnsuredUiSignature) && mc.getUser(userId) != null && mc.getUserFull(userId) != null) {
            return userId;
        }
        try {
            TLRPC.TL_user user = new TLRPC.TL_user();
            user.id = userId;
            String fullName = profile.displayName != null ? profile.displayName.trim() : "Student";
            int sp = fullName.indexOf(' ');
            if (sp > 0) {
                user.first_name = fullName.substring(0, sp).trim();
                user.last_name = fullName.substring(sp + 1).trim();
            } else {
                user.first_name = fullName.isEmpty() ? "Student" : fullName;
                user.last_name = "";
            }
            user.phone = profile.phone != null ? profile.phone.replaceAll("[^0-9]", "") : "";
            if (profile.username != null && !profile.username.trim().isEmpty()) {
                user.username = profile.username.trim().replaceFirst("^@", "");
                TLRPC.TL_username uname = new TLRPC.TL_username();
                uname.username = user.username;
                uname.active = true;
                user.usernames.add(uname);
            }
            user.flags |= 2048;

            TLRPC.TL_userFull userFull = new TLRPC.TL_userFull();
            userFull.id = userId;
            userFull.user = user;
            userFull.about = profile.bio != null ? profile.bio : "";
            if (!userFull.about.isEmpty()) {
                userFull.flags |= 2;
            }
            if (profile.dob != null && profile.dob.matches("\\d{4}-\\d{2}-\\d{2}")) {
                String[] parts = profile.dob.split("-");
                TL_account.TL_birthday bday = new TL_account.TL_birthday();
                bday.year = Integer.parseInt(parts[0]);
                bday.month = Integer.parseInt(parts[1]);
                bday.day = Integer.parseInt(parts[2]);
                bday.flags |= 1;
                userFull.birthday = bday;
                userFull.flags2 |= 32;
            }

            mc.putUser(user, true);
            mc.putUserFull(userFull);
            lastEnsuredUiSignature = signature;
        } catch (Throwable t) {
            FileLog.e("EveAuthManager: ensureLocalUiUser failed", t);
        }
        return userId;
    }

    public void updateEveProfileFields(@NonNull JSONObject updates, @NonNull SimpleCallback callback) {
        ensureInitialized();
        FirebaseUser user = getCurrentUser();
        if (user == null || user.isAnonymous()) {
            mainHandler.post(() -> callback.onError("Sign in to EVE with Google first."));
            return;
        }
        user.getIdToken(true)
                .addOnSuccessListener(result -> {
                    String token = result != null ? result.getToken() : null;
                    if (token == null || token.trim().isEmpty()) {
                        mainHandler.post(() -> callback.onError("Authentication token unavailable."));
                        return;
                    }
                    ioExecutor.execute(() -> {
                        try {
                            backendClient.updateUserProfile(token, updates);
                            EveUserProfile current = getCachedProfile();
                            if (current != null) {
                                EveUserProfile updated = current.withBackendSync(
                                        current.eveId,
                                        updates.has("displayName") ? updates.optString("displayName", current.displayName) : current.displayName,
                                        updates.has("username") ? updates.optString("username", current.username) : current.username,
                                        updates.has("category") ? updates.optString("category", current.category) : current.category,
                                        updates.has("bio") ? updates.optString("bio", current.bio) : current.bio,
                                        updates.has("dob") ? updates.optString("dob", current.dob) : current.dob,
                                        updates.has("phone") ? updates.optString("phone", current.phone) : current.phone,
                                        current.isAdmin,
                                        System.currentTimeMillis()
                                );
                                saveCachedProfile(updated);
                            }
                            mainHandler.post(() -> {
                                ensureLocalUiUser(UserConfig.selectedAccount);
                                NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
                                NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
                                callback.onSuccess("Profile saved to EVE Cloud.");
                            });
                        } catch (Throwable t) {
                            String msg = t.getMessage() != null ? t.getMessage() : "Could not update EVE profile.";
                            mainHandler.post(() -> callback.onError(msg));
                        }
                    });
                })
                .addOnFailureListener(e -> mainHandler.post(() -> callback.onError(mapFirebaseError(e))));
    }

    public void deleteEveAccount(@Nullable Activity activity, @NonNull SimpleCallback callback) {
        ensureInitialized();
        FirebaseUser user = getCurrentUser();
        if (user == null || user.isAnonymous()) {
            mainHandler.post(() -> callback.onError("Please sign in to EVE first."));
            return;
        }
        user.getIdToken(true)
                .addOnSuccessListener(result -> {
                    String token = result != null ? result.getToken() : null;
                    if (token == null || token.trim().isEmpty()) {
                        mainHandler.post(() -> callback.onError("Authentication token unavailable."));
                        return;
                    }
                    ioExecutor.execute(() -> {
                        try {
                            backendClient.deleteAccount(token);
                            signOut(activity, () -> callback.onSuccess("EVE account deleted."));
                        } catch (Throwable t) {
                            String msg = t.getMessage() != null ? t.getMessage() : "Could not delete EVE account.";
                            mainHandler.post(() -> callback.onError(msg));
                        }
                    });
                })
                .addOnFailureListener(e -> mainHandler.post(() -> callback.onError(mapFirebaseError(e))));
    }

    private void postSuccess(
            @NonNull AuthCallback callback,
            @NonNull EveUserProfile profile,
            @NonNull EveAuthCoordinator.SyncOutcome syncOutcome) {
        mainHandler.post(() -> callback.onSuccess(profile, syncOutcome));
    }

    private void postError(@NonNull AuthCallback callback, @NonNull String message, boolean disabled) {
        mainHandler.post(() -> callback.onError(message, disabled));
    }
}
