package org.telegram.ui.eve.auth;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLException;

/**
 * HTTPS client for the existing EVE Cloudflare Worker (eve-backend) and D1 database (eve-database).
 * Sends Authorization: Bearer <firebase_id_token> and X-Eve-Client: 2 on every protected request:
 *   - POST /api/users/sync    -> idempotent D1 users upsert + EVE ID allocation
 *   - GET  /api/auth/me       -> verifies Firebase ID token and admin status
 *   - GET  /api/users/profile -> retrieves full persisted D1 user profile
 *
 * Captures safe diagnostics (HTTP status, endpoint path, network failure category, server error code)
 * without ever logging passwords, full Firebase ID tokens, or credentials.
 */
public final class EveBackendClient {

    public static final String DEFAULT_WORKER_BASE_URL = "https://eve-backend.anyqueairdrop.workers.dev";
    public static final String HEADER_EVE_CLIENT = "X-Eve-Client";
    public static final String EVE_CLIENT_VERSION = "2";
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 25_000;

    public enum ErrorKind {
        UNAUTHORIZED,
        ACCOUNT_DISABLED,
        NETWORK_OFFLINE,
        NETWORK_TIMEOUT,
        TLS_ERROR,
        D1_OR_SERVER_ERROR,
        CLIENT_BAD_REQUEST,
        INVALID_RESPONSE
    }

    public interface HttpTransport {
        HttpResponse execute(String method, String url, String bearerToken, String jsonBody) throws IOException;
    }

    public static byte[] executeBinaryGet(String urlString, String bearerToken) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty(HEADER_EVE_CLIENT, EVE_CLIENT_VERSION);
            if (bearerToken != null && !bearerToken.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Asset download failed with HTTP " + code);
            }
            try (InputStream is = conn.getInputStream();
                 java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 2 * 1024 * 1024) {
                        throw new IOException("Asset exceeds 2MB safety cap.");
                    }
                }
                return bos.toByteArray();
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static final class HttpResponse {
        public final int statusCode;
        public final String body;

        public HttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body != null ? body : "";
        }
    }

    public static final class BackendException extends Exception {
        public final int httpStatus;
        public final String endpoint;
        public final String serverCode;
        public final ErrorKind kind;
        public final boolean accountDisabled;
        public final boolean unauthorized;
        public final boolean networkError;

        public BackendException(
                int httpStatus,
                String endpoint,
                String serverCode,
                ErrorKind kind,
                String message) {
            super(message);
            this.httpStatus = httpStatus;
            this.endpoint = endpoint != null ? endpoint : "";
            this.serverCode = serverCode != null ? serverCode : "";
            this.kind = kind != null ? kind : ErrorKind.D1_OR_SERVER_ERROR;
            this.accountDisabled = this.kind == ErrorKind.ACCOUNT_DISABLED;
            this.unauthorized = this.kind == ErrorKind.UNAUTHORIZED;
            this.networkError = this.kind == ErrorKind.NETWORK_OFFLINE
                    || this.kind == ErrorKind.NETWORK_TIMEOUT
                    || this.kind == ErrorKind.TLS_ERROR;
        }

        public String toSafeDiagnosticSummary() {
            StringBuilder sb = new StringBuilder();
            if (getMessage() != null && !getMessage().isEmpty()) {
                sb.append(getMessage());
            } else {
                sb.append("Cloud sync failed.");
            }
            sb.append(" [");
            if (httpStatus > 0) {
                sb.append("HTTP ").append(httpStatus);
            } else {
                sb.append(kind.name());
            }
            if (!endpoint.isEmpty()) {
                sb.append(" ").append(endpoint);
            }
            if (!serverCode.isEmpty()) {
                sb.append(" ").append(serverCode);
            }
            sb.append("]");
            return sb.toString();
        }
    }

    public static final class AuthMeResponse {
        public final String uid;
        public final String email;
        public final String displayName;
        public final boolean emailVerified;
        public final boolean isAdmin;

        public AuthMeResponse(String uid, String email, String displayName, boolean emailVerified, boolean isAdmin) {
            this.uid = uid;
            this.email = email;
            this.displayName = displayName;
            this.emailVerified = emailVerified;
            this.isAdmin = isAdmin;
        }
    }

    public static final class SyncResponse {
        public final String eveId;

        public SyncResponse(String eveId) {
            this.eveId = eveId;
        }
    }

    public static final class ProfileResponse {
        public final String id;
        public final String eveId;
        public final String email;
        public final String displayName;
        public final String dob;
        public final String category;
        public final String username;
        public final String bio;
        public final String phone;
        public final boolean isOnline;

        public ProfileResponse(
                String id,
                String eveId,
                String email,
                String displayName,
                String dob,
                String category,
                String username,
                String bio,
                boolean isOnline) {
            this(id, eveId, email, displayName, dob, category, username, bio, "", isOnline);
        }

        public ProfileResponse(
                String id,
                String eveId,
                String email,
                String displayName,
                String dob,
                String category,
                String username,
                String bio,
                String phone,
                boolean isOnline) {
            this.id = id;
            this.eveId = eveId;
            this.email = email;
            this.displayName = displayName;
            this.dob = dob;
            this.category = category;
            this.username = username;
            this.bio = bio;
            this.phone = phone != null ? phone : "";
            this.isOnline = isOnline;
        }
    }

    private final String baseUrl;
    private final HttpTransport transport;

    public EveBackendClient() {
        this(DEFAULT_WORKER_BASE_URL, new UrlConnectionTransport());
    }

    public EveBackendClient(String baseUrl, HttpTransport transport) {
        String normalized = (baseUrl == null || baseUrl.trim().isEmpty()) ? DEFAULT_WORKER_BASE_URL : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.baseUrl = normalized;
        this.transport = transport != null ? transport : new UrlConnectionTransport();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public AuthMeResponse fetchAuthMe(String firebaseIdToken) throws BackendException {
        requireToken(firebaseIdToken, "/api/auth/me");
        JSONObject data = executeJsonRequest("GET", "/api/auth/me", firebaseIdToken, null);
        return new AuthMeResponse(
                optCleanString(data, "uid", ""),
                optCleanString(data, "email", ""),
                optCleanString(data, "displayName", "Student"),
                data.optBoolean("emailVerified", false),
                data.optBoolean("isAdmin", false)
        );
    }

    public SyncResponse syncUserProfile(String firebaseIdToken, String displayName) throws BackendException {
        requireToken(firebaseIdToken, "/api/users/sync");
        String safeName = (displayName != null && !displayName.trim().isEmpty()) ? displayName.trim() : "Student";
        if (safeName.length() > 100) {
            safeName = safeName.substring(0, 100);
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("displayName", safeName);
        } catch (JSONException e) {
            throw new BackendException(0, "/api/users/sync", "SERIALIZE_ERROR", ErrorKind.CLIENT_BAD_REQUEST,
                    "Failed to serialize profile sync payload.");
        }
        JSONObject data = executeJsonRequest("POST", "/api/users/sync", firebaseIdToken, payload.toString());
        String eveId = optCleanString(data, "eveId", "");
        if (eveId.isEmpty()) {
            throw new BackendException(200, "/api/users/sync", "MISSING_EVE_ID", ErrorKind.INVALID_RESPONSE,
                    "EVE Cloud did not return a confirmed EVE ID.");
        }
        return new SyncResponse(eveId);
    }

    public ProfileResponse fetchUserProfile(String firebaseIdToken) throws BackendException {
        requireToken(firebaseIdToken, "/api/users/profile");
        JSONObject data = executeJsonRequest("GET", "/api/users/profile", firebaseIdToken, null);
        return new ProfileResponse(
                optCleanString(data, "id", ""),
                optCleanString(data, "eveId", ""),
                optCleanString(data, "email", ""),
                optCleanString(data, "displayName", "Student"),
                optCleanString(data, "dob", ""),
                optCleanString(data, "category", "General"),
                optCleanString(data, "username", ""),
                optCleanString(data, "bio", ""),
                optCleanString(data, "phone", ""),
                data.optBoolean("isOnline", false)
        );
    }

    public void updateUserProfile(String firebaseIdToken, JSONObject updates) throws BackendException {
        requireToken(firebaseIdToken, "/api/users/profile");
        executeRootJsonRequest("PUT", "/api/users/profile", firebaseIdToken, updates != null ? updates.toString() : "{}");
    }

    public void deleteAccount(String firebaseIdToken) throws BackendException {
        requireToken(firebaseIdToken, "/api/users/account");
        executeRootJsonRequest("DELETE", "/api/users/account", firebaseIdToken, null);
    }

    public JSONArray fetchExamsJson(String firebaseIdToken) throws BackendException {
        requireToken(firebaseIdToken, "/api/exams");
        JSONObject root = executeRootJsonRequest("GET", "/api/exams", firebaseIdToken, null);
        JSONArray arr = root.optJSONArray("data");
        return arr != null ? arr : new JSONArray();
    }

    public JSONArray fetchGeneratedTestsJson(String firebaseIdToken, String examId) throws BackendException {
        String path = "/api/generated-tests";
        if (examId != null && !examId.trim().isEmpty()) {
            try {
                path = "/api/generated-tests?examId=" + URLEncoder.encode(examId.trim(), "UTF-8");
            } catch (UnsupportedEncodingException ignored) {
                path = "/api/generated-tests?examId=" + examId.trim();
            }
        }
        requireToken(firebaseIdToken, path);
        JSONObject root = executeRootJsonRequest("GET", path, firebaseIdToken, null);
        JSONArray arr = root.optJSONArray("data");
        return arr != null ? arr : new JSONArray();
    }

    public JSONArray fetchPinnedExamIdsJson(String firebaseIdToken) throws BackendException {
        String path = "/api/pins";
        requireToken(firebaseIdToken, path);
        JSONObject root = executeRootJsonRequest("GET", path, firebaseIdToken, null);
        JSONArray arr = root.optJSONArray("data");
        return arr != null ? arr : new JSONArray();
    }

    public boolean toggleExamPin(String firebaseIdToken, String examId) throws BackendException {
        String encoded;
        try {
            encoded = URLEncoder.encode(examId != null ? examId.trim() : "", "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException ignored) {
            encoded = examId != null ? examId.trim() : "";
        }
        String path = "/api/pins/" + encoded + "/toggle";
        requireToken(firebaseIdToken, path);
        JSONObject data = executeJsonRequest("POST", path, firebaseIdToken, "{}");
        return data.optBoolean("isPinned", false);
    }

    public JSONObject getJsonRoot(String path, String firebaseIdToken) throws BackendException {
        requireToken(firebaseIdToken, path);
        return executeRootJsonRequest("GET", path, firebaseIdToken, null);
    }

    public JSONObject getOptionalJsonRoot(String path, String firebaseIdToken) throws BackendException {
        return executeRootJsonRequest("GET", path, firebaseIdToken, null);
    }

    public JSONObject postJsonRoot(String path, String firebaseIdToken, JSONObject body) throws BackendException {
        requireToken(firebaseIdToken, path);
        return executeRootJsonRequest("POST", path, firebaseIdToken, body != null ? body.toString() : "{}");
    }

    public JSONObject postBinaryRoot(String path, String firebaseIdToken, String contentType, byte[] payloadBytes) throws BackendException {
        requireToken(firebaseIdToken, path);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty(HEADER_EVE_CLIENT, EVE_CLIENT_VERSION);
            conn.setRequestProperty("Authorization", "Bearer " + firebaseIdToken);
            conn.setRequestProperty("Content-Type", contentType != null ? contentType : "application/octet-stream");
            conn.setDoOutput(true);
            byte[] bytes = payloadBytes != null ? payloadBytes : new byte[0];
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }
            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String bodyText = UrlConnectionTransport.readStream(stream);
            if (code >= 200 && code < 300) {
                return new JSONObject(bodyText.isEmpty() ? "{}" : bodyText);
            }
            JSONObject parsedErr = null;
            try {
                if (!bodyText.isEmpty()) parsedErr = new JSONObject(bodyText);
            } catch (JSONException ignored) {}
            String serverCode = parsedErr != null ? optCleanString(parsedErr, "code", "") : "";
            String errMsg = parsedErr != null ? optCleanString(parsedErr, "error", "") : "";
            if (errMsg.isEmpty()) errMsg = "Upload failed (" + code + ").";
            throw new BackendException(code, path, serverCode, ErrorKind.CLIENT_BAD_REQUEST, errMsg);
        } catch (BackendException be) {
            throw be;
        } catch (IOException e) {
            throw new BackendException(0, path, "UPLOAD_IO_ERROR", ErrorKind.NETWORK_OFFLINE,
                    e.getMessage() != null ? e.getMessage() : "Upload failed due to network error.");
        } catch (JSONException e) {
            throw new BackendException(502, path, "INVALID_JSON", ErrorKind.INVALID_RESPONSE, "Invalid response from EVE Cloud.");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public JSONObject putJsonRoot(String path, String firebaseIdToken, JSONObject body) throws BackendException {
        requireToken(firebaseIdToken, path);
        return executeRootJsonRequest("PUT", path, firebaseIdToken, body != null ? body.toString() : "{}");
    }

    public JSONObject deleteJsonRoot(String path, String firebaseIdToken) throws BackendException {
        requireToken(firebaseIdToken, path);
        return executeRootJsonRequest("DELETE", path, firebaseIdToken, null);
    }

    private static String optCleanString(JSONObject obj, String key, String fallback) {
        if (obj == null || obj.isNull(key)) {
            return fallback;
        }
        String value = obj.optString(key, fallback);
        return (value == null || "null".equalsIgnoreCase(value.trim())) ? fallback : value;
    }

    private void requireToken(String token, String endpoint) throws BackendException {
        if (token == null || token.trim().isEmpty()) {
            throw new BackendException(401, endpoint, "MISSING_TOKEN", ErrorKind.UNAUTHORIZED,
                    "Missing Firebase authentication token.");
        }
    }

    private JSONObject executeJsonRequest(String method, String path, String token, String jsonBody) throws BackendException {
        JSONObject parsed = executeRootJsonRequest(method, path, token, jsonBody);
        JSONObject data = parsed.optJSONObject("data");
        return data != null ? data : new JSONObject();
    }

    private JSONObject executeRootJsonRequest(String method, String path, String token, String jsonBody) throws BackendException {
        HttpResponse response;
        try {
            response = transport.execute(method, baseUrl + path, token, jsonBody);
        } catch (SocketTimeoutException e) {
            throw new BackendException(0, path, "SOCKET_TIMEOUT", ErrorKind.NETWORK_TIMEOUT,
                    "Connection to EVE Cloud timed out. Will retry when network stabilizes.");
        } catch (UnknownHostException e) {
            throw new BackendException(0, path, "DNS_OFFLINE", ErrorKind.NETWORK_OFFLINE,
                    "No internet connection. Working with cached session until connectivity returns.");
        } catch (SSLException e) {
            throw new BackendException(0, path, "SSL_HANDSHAKE_ERROR", ErrorKind.TLS_ERROR,
                    "Secure HTTPS connection to EVE Cloud failed.");
        } catch (IOException e) {
            throw new BackendException(0, path, "IO_NETWORK_ERROR", ErrorKind.NETWORK_OFFLINE,
                    "Unable to reach EVE Cloud. Working offline with cached session.");
        }

        JSONObject parsed = null;
        if (response.body != null && !response.body.trim().isEmpty()) {
            try {
                parsed = new JSONObject(response.body);
            } catch (JSONException ignored) {
            }
        }

        String serverCode = parsed != null ? optCleanString(parsed, "code", "") : "";

        if (response.statusCode == 401) {
            String err = parsed != null
                    ? optCleanString(parsed, "error", "Authentication expired. Please sign in again.")
                    : "Authentication expired. Please sign in again.";
            throw new BackendException(401, path, serverCode.isEmpty() ? "AUTH_REQUIRED" : serverCode,
                    ErrorKind.UNAUTHORIZED, err);
        }
        if (response.statusCode == 403) {
            String err = parsed != null
                    ? optCleanString(parsed, "error", "This account is disabled.")
                    : "This account is disabled.";
            boolean disabled = err.toLowerCase().contains("disabled");
            throw new BackendException(403, path, serverCode.isEmpty() ? "FORBIDDEN" : serverCode,
                    disabled ? ErrorKind.ACCOUNT_DISABLED : ErrorKind.UNAUTHORIZED, err);
        }
        if (response.statusCode >= 400 && response.statusCode < 500) {
            String err = parsed != null
                    ? optCleanString(parsed, "error", "Request rejected (" + response.statusCode + ").")
                    : "Request rejected (" + response.statusCode + ").";
            throw new BackendException(response.statusCode, path, serverCode, ErrorKind.CLIENT_BAD_REQUEST, err);
        }
        if (response.statusCode < 200 || response.statusCode >= 300) {
            String err = parsed != null
                    ? optCleanString(parsed, "error", "EVE Cloud server error (" + response.statusCode + ").")
                    : "EVE Cloud server error (" + response.statusCode + ").";
            throw new BackendException(response.statusCode, path, serverCode.isEmpty() ? "SERVER_ERROR" : serverCode,
                    ErrorKind.D1_OR_SERVER_ERROR, err);
        }
        if (parsed == null || !parsed.optBoolean("success", false)) {
            String err = parsed != null
                    ? optCleanString(parsed, "error", "Unexpected response from EVE Cloud.")
                    : "Unexpected response from EVE Cloud.";
            throw new BackendException(response.statusCode, path, serverCode.isEmpty() ? "INVALID_PAYLOAD" : serverCode,
                    ErrorKind.INVALID_RESPONSE, err);
        }
        return parsed;
    }

    public static final class UrlConnectionTransport implements HttpTransport {
        @Override
        public HttpResponse execute(String method, String urlString, String bearerToken, String jsonBody) throws IOException {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlString);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setUseCaches(false);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty(HEADER_EVE_CLIENT, EVE_CLIENT_VERSION);
                if (bearerToken != null && !bearerToken.trim().isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
                }
                if (jsonBody != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                    conn.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(bytes);
                    }
                }
                int code = conn.getResponseCode();
                InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String body = readStream(stream);
                return new HttpResponse(code, body);
            } catch (IOException e) {
                if (conn != null) {
                    conn.disconnect();
                }
                throw e;
            }
        }

        private static String readStream(InputStream stream) throws IOException {
            if (stream == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        }
    }
}
