package org.telegram.ui.eve.auth;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Immutable authenticated EVE user profile synchronized between Firebase Authentication
 * and the EVE Cloudflare Worker / D1 backend.
 */
public final class EveUserProfile {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";
    public static final String STATUS_UNVERIFIED = "unverified";

    public final String uid;
    public final String eveId;
    public final String displayName;
    public final String email;
    public final String photoUrl;
    public final String authProvider;
    public final boolean emailVerified;
    public final String accountStatus;
    public final boolean isAdmin;
    public final String username;
    public final String category;
    public final String bio;
    public final String dob;
    public final String phone;
    public final long createdAt;
    public final long updatedAt;
    public final long lastLoginAt;
    public final boolean syncedWithBackend;

    public EveUserProfile(
            String uid,
            String eveId,
            String displayName,
            String email,
            String photoUrl,
            String authProvider,
            boolean emailVerified,
            String accountStatus,
            boolean isAdmin,
            String username,
            String category,
            String bio,
            long createdAt,
            long updatedAt,
            long lastLoginAt,
            boolean syncedWithBackend) {
        this(uid, eveId, displayName, email, photoUrl, authProvider, emailVerified, accountStatus,
                isAdmin, username, category, bio, "", "", createdAt, updatedAt, lastLoginAt, syncedWithBackend);
    }

    public EveUserProfile(
            String uid,
            String eveId,
            String displayName,
            String email,
            String photoUrl,
            String authProvider,
            boolean emailVerified,
            String accountStatus,
            boolean isAdmin,
            String username,
            String category,
            String bio,
            String dob,
            String phone,
            long createdAt,
            long updatedAt,
            long lastLoginAt,
            boolean syncedWithBackend) {
        this.uid = uid != null ? uid : "";
        this.eveId = eveId != null ? eveId : "";
        this.displayName = displayName != null && !displayName.trim().isEmpty() ? displayName.trim() : "Student";
        this.email = email != null ? email.trim().toLowerCase() : "";
        this.photoUrl = photoUrl != null ? photoUrl : "";
        this.authProvider = authProvider != null ? authProvider : "password";
        this.emailVerified = emailVerified;
        this.accountStatus = accountStatus != null ? accountStatus : (emailVerified ? STATUS_ACTIVE : STATUS_UNVERIFIED);
        this.isAdmin = isAdmin;
        this.username = username != null ? username : "";
        this.category = category != null && !category.isEmpty() ? category : "General";
        this.bio = bio != null ? bio : "";
        this.dob = dob != null ? dob : "";
        this.phone = phone != null ? phone : "";
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastLoginAt = lastLoginAt;
        this.syncedWithBackend = syncedWithBackend;
    }

    public EveUserProfile withBackendSync(
            String newEveId,
            String newDisplayName,
            String newUsername,
            String newCategory,
            String newBio,
            boolean newIsAdmin,
            long syncTime) {
        return withBackendSync(newEveId, newDisplayName, newUsername, newCategory, newBio, this.dob, this.phone, newIsAdmin, syncTime);
    }

    public EveUserProfile withBackendSync(
            String newEveId,
            String newDisplayName,
            String newUsername,
            String newCategory,
            String newBio,
            String newDob,
            String newPhone,
            boolean newIsAdmin,
            long syncTime) {
        return new EveUserProfile(
                this.uid,
                newEveId != null && !newEveId.isEmpty() ? newEveId : this.eveId,
                newDisplayName != null && !newDisplayName.trim().isEmpty() ? newDisplayName.trim() : this.displayName,
                this.email,
                this.photoUrl,
                this.authProvider,
                this.emailVerified,
                this.emailVerified ? STATUS_ACTIVE : STATUS_UNVERIFIED,
                newIsAdmin,
                newUsername != null ? newUsername : this.username,
                newCategory != null && !newCategory.isEmpty() ? newCategory : this.category,
                newBio != null ? newBio : this.bio,
                newDob != null ? newDob : this.dob,
                newPhone != null ? newPhone : this.phone,
                this.createdAt,
                syncTime,
                syncTime,
                true
        );
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("uid", uid);
            obj.put("eveId", eveId);
            obj.put("displayName", displayName);
            obj.put("email", email);
            obj.put("photoUrl", photoUrl);
            obj.put("authProvider", authProvider);
            obj.put("emailVerified", emailVerified);
            obj.put("accountStatus", accountStatus);
            obj.put("isAdmin", isAdmin);
            obj.put("username", username);
            obj.put("category", category);
            obj.put("bio", bio);
            obj.put("dob", dob);
            obj.put("phone", phone);
            obj.put("createdAt", createdAt);
            obj.put("updatedAt", updatedAt);
            obj.put("lastLoginAt", lastLoginAt);
            obj.put("syncedWithBackend", syncedWithBackend);
        } catch (JSONException ignored) {
        }
        return obj;
    }

    public static EveUserProfile fromJson(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject obj = new JSONObject(jsonString);
            String uid = obj.optString("uid", "");
            if (uid.isEmpty()) {
                return null;
            }
            return new EveUserProfile(
                    uid,
                    obj.optString("eveId", ""),
                    obj.optString("displayName", "Student"),
                    obj.optString("email", ""),
                    obj.optString("photoUrl", ""),
                    obj.optString("authProvider", "password"),
                    obj.optBoolean("emailVerified", false),
                    obj.optString("accountStatus", STATUS_ACTIVE),
                    obj.optBoolean("isAdmin", false),
                    obj.optString("username", ""),
                    obj.optString("category", "General"),
                    obj.optString("bio", ""),
                    obj.optString("dob", ""),
                    obj.optString("phone", ""),
                    obj.optLong("createdAt", 0L),
                    obj.optLong("updatedAt", 0L),
                    obj.optLong("lastLoginAt", 0L),
                    obj.optBoolean("syncedWithBackend", false)
            );
        } catch (JSONException e) {
            return null;
        }
    }
}
