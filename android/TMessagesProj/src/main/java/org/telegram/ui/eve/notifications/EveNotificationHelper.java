package org.telegram.ui.eve.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.eve.auth.EveBackendClient;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dedicated EVE Notification & FCM Token Controller.
 * Completely isolated from Telegram MTProto push registration and Telegram chat notifications.
 */
public final class EveNotificationHelper {

    public static final String CHANNEL_ID = "eve_exam_notifications";
    public static final String CHANNEL_NAME = "EVE Exam & Result Alerts";
    public static final String TOPIC_NEW_EXAMS = "new_exams";

    private static final String PREFS_NAME = "eve_notification_prefs";
    public static final String PREF_EXAM_REMINDERS = "notif_exam_reminders";
    public static final String PREF_ANNOUNCEMENTS = "notif_announcements";
    public static final String PREF_RESULTS = "notif_results";
    public static final String PREF_PAYMENTS = "notif_payments";
    public static final String PREF_SOUND = "notif_sound";
    public static final String PREF_VIBRATE = "notif_vibrate";

    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile String lastRegisteredToken = "";

    private EveNotificationHelper() {
    }

    public static boolean isCategoryEnabled(@Nullable Context context, @NonNull String key) {
        Context ctx = context != null ? context : ApplicationLoader.applicationContext;
        if (ctx == null) {
            return true;
        }
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(key, true);
    }

    public static boolean toggleCategory(@Nullable Context context, @NonNull String key) {
        Context ctx = context != null ? context : ApplicationLoader.applicationContext;
        boolean next = !isCategoryEnabled(ctx, key);
        if (ctx != null) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(key, next).apply();
        }
        return next;
    }

    private static boolean shouldDisplayForType(@NonNull Context context, @Nullable String eveType) {
        String t = eveType != null ? eveType.trim().toLowerCase(java.util.Locale.US) : "";
        if (t.contains("remind")) {
            return isCategoryEnabled(context, PREF_EXAM_REMINDERS);
        } else if (t.contains("result") || t.contains("score")) {
            return isCategoryEnabled(context, PREF_RESULTS);
        } else if (t.contains("payment") || t.contains("premium")) {
            return isCategoryEnabled(context, PREF_PAYMENTS);
        } else if (!t.isEmpty()) {
            return isCategoryEnabled(context, PREF_ANNOUNCEMENTS);
        }
        return isCategoryEnabled(context, PREF_ANNOUNCEMENTS) || isCategoryEnabled(context, PREF_EXAM_REMINDERS);
    }

    public static boolean isTelegramPushPayload(@Nullable Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        return data.containsKey("p") && !data.containsKey("title") && !data.containsKey("body") && !data.containsKey("eve_type");
    }

    public static boolean isEveNotificationPayload(@Nullable RemoteMessage message) {
        if (message == null) {
            return false;
        }
        Map<String, String> data = message.getData();
        if (isTelegramPushPayload(data)) {
            return false;
        }
        if (message.getNotification() != null) {
            return true;
        }
        return data != null && (data.containsKey("title")
                || data.containsKey("body")
                || data.containsKey("message")
                || data.containsKey("examId")
                || data.containsKey("eve_type"));
    }

    public static void handleIncomingFcmMessage(@NonNull Context context, @NonNull RemoteMessage message) {
        Map<String, String> data = message.getData();
        if (isTelegramPushPayload(data)) {
            // Strict isolation: never process or display Telegram chat/group pushes in EVE.
            return;
        }
        if (!isEveNotificationPayload(message)) {
            return;
        }
        RemoteMessage.Notification notif = message.getNotification();
        String title = notif != null && notif.getTitle() != null ? notif.getTitle() : safeData(data, "title");
        String body = notif != null && notif.getBody() != null ? notif.getBody() : safeData(data, "body");
        if (body.isEmpty()) {
            body = safeData(data, "message");
        }
        if (title.isEmpty()) {
            title = "EVE Exam Alert";
        }
        if (body.isEmpty()) {
            body = "A new update is available in EVE.";
        }
        String eveType = safeData(data, "eve_type");
        if (!shouldDisplayForType(context, eveType)) {
            return;
        }
        showExamNotification(context, title, body, safeData(data, "examId"), eveType);
    }

    public static void showExamNotification(
            @NonNull Context context,
            @NonNull String title,
            @NonNull String body,
            @Nullable String examId,
            @Nullable String type) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Notifications for published EVE exams, reminders, results, and announcements.");
                nm.createNotificationChannel(channel);
            }

            Intent launchIntent = new Intent(context, LaunchActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (examId != null && !examId.isEmpty()) {
                launchIntent.putExtra("eve_notification_exam_id", examId);
            }
            if (type != null && !type.isEmpty()) {
                launchIntent.putExtra("eve_notification_type", type);
            }

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, launchIntent, flags);

            int defaults = 0;
            if (isCategoryEnabled(context, PREF_SOUND)) {
                defaults |= android.app.Notification.DEFAULT_SOUND;
            }
            if (isCategoryEnabled(context, PREF_VIBRATE)) {
                defaults |= android.app.Notification.DEFAULT_VIBRATE;
            }
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(defaults)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent);

            int notifId = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
            nm.notify(notifId, builder.build());
        } catch (Throwable t) {
            FileLog.e("EveNotificationHelper: failed to post EVE notification", t);
        }
    }

    public static void syncFcmTokenIfSignedIn() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return;
            }
            if (FirebaseApp.getApps(ctx).isEmpty()) {
                FirebaseApp.initializeApp(ctx);
            }
            FirebaseMessaging messaging = FirebaseMessaging.getInstance();
            messaging.subscribeToTopic(TOPIC_NEW_EXAMS);
            messaging.getToken().addOnSuccessListener(token -> {
                if (token != null && !token.trim().isEmpty()) {
                    registerFcmToken(token.trim());
                }
            });
        } catch (Throwable t) {
            FileLog.e("EveNotificationHelper: FCM sync skipped", t);
        }
    }

    public static void registerFcmToken(@Nullable String token) {
        if (token == null || token.trim().isEmpty()) {
            return;
        }
        final String cleanToken = token.trim();
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null && FirebaseApp.getApps(ctx).isEmpty()) {
                FirebaseApp.initializeApp(ctx);
            }
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null || user.isAnonymous()) {
                return;
            }
            if (cleanToken.equals(lastRegisteredToken)) {
                return;
            }
            user.getIdToken(false).addOnSuccessListener(result -> {
                String idToken = result != null ? result.getToken() : null;
                if (idToken == null || idToken.trim().isEmpty()) {
                    return;
                }
                IO_EXECUTOR.execute(() -> {
                    try {
                        JSONObject payload = new JSONObject();
                        payload.put("fcmToken", cleanToken);
                        new EveBackendClient().postJsonRoot("/api/users/fcm-token", idToken, payload);
                        lastRegisteredToken = cleanToken;
                    } catch (Throwable t) {
                        FileLog.e("EveNotificationHelper: failed to register FCM token with EVE Worker", t);
                    }
                });
            });
        } catch (Throwable t) {
            FileLog.e("EveNotificationHelper: registerFcmToken error", t);
        }
    }

    private static String safeData(@Nullable Map<String, String> data, String key) {
        if (data == null) {
            return "";
        }
        String value = data.get(key);
        return value != null ? value.trim() : "";
    }
}
