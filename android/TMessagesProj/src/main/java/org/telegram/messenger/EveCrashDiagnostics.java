package org.telegram.messenger;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * DEBUG-only crash & runtime diagnostics collector.
 *
 * Captures:
 * - Uncaught Java exceptions via chained UncaughtExceptionHandler
 * - Previous process exit reasons via ApplicationExitInfo (Android 11+ / API 30+)
 * - Navigation, resource, and network error events
 * - Native library load status
 *
 * All exported or persisted reports redact sensitive credentials, tokens, emails, and phone numbers.
 * Completely inactive in release builds.
 */
public final class EveCrashDiagnostics {

    private static final String PREFS_NAME = "eve_debug_crash_diagnostics";
    private static final String KEY_LAST_CRASH = "last_crash_report";
    private static final String KEY_EXIT_HISTORY = "last_exit_reasons";
    private static final int MAX_EVENTS = 60;

    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-~+/=]+");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\+?[0-9][0-9\\-\\s]{8,15}[0-9]");
    private static final Pattern SECRET_KV_PATTERN = Pattern.compile("(?i)(token|password|secret|apikey|api_key|auth|cookie)\\s*[:=]\\s*[^\\s,;\"']+");

    private static final List<String> EVENTS = new ArrayList<>();
    private static volatile boolean installed = false;
    private static volatile String lastExitSummary = "Not inspected yet";

    private EveCrashDiagnostics() {
    }

    public static synchronized void install(Context context) {
        if (!EveInspectionMode.isEnabledForCurrentBuild() || installed || context == null) {
            return;
        }
        installed = true;
        final Context appContext = context.getApplicationContext();

        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                persistCrash(appContext, thread, throwable);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });

        try {
            inspectHistoricalExitReasons(appContext);
        } catch (Throwable t) {
            recordEvent("DIAGNOSTICS", "ExitInfo inspection error: " + t.getClass().getSimpleName());
        }
    }

    private static void persistCrash(Context context, Thread thread, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("Timestamp: " + formatNow());
        pw.println("Thread: " + (thread != null ? thread.getName() : "unknown"));
        if (throwable != null) {
            throwable.printStackTrace(pw);
        }
        pw.flush();
        String redacted = redactSensitiveData(sw.toString());
        if (redacted.length() > 8000) {
            redacted = redacted.substring(0, 8000) + "\n...[truncated]";
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_CRASH, redacted).commit();
    }

    public static void inspectHistoricalExitReasons(Context context) {
        if (!EveInspectionMode.isEnabledForCurrentBuild() || context == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            lastExitSummary = "ApplicationExitInfo requires Android 11+ (current API " + Build.VERSION.SDK_INT + ")";
            return;
        }
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                lastExitSummary = "ActivityManager unavailable";
                return;
            }
            List<ApplicationExitInfo> reasons = am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 5);
            if (reasons == null || reasons.isEmpty()) {
                lastExitSummary = "No previous abnormal process exits recorded";
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < reasons.size(); i++) {
                    ApplicationExitInfo info = reasons.get(i);
                    sb.append("#").append(i + 1).append(" ")
                            .append(exitReasonName(info.getReason()))
                            .append(" (status=").append(info.getStatus()).append(") ")
                            .append(formatTime(info.getTimestamp()));
                    if (info.getDescription() != null && !info.getDescription().isEmpty()) {
                        sb.append(" — ").append(redactSensitiveData(info.getDescription()));
                    }
                    sb.append("\n");
                    if (i == 0 && (info.getReason() == ApplicationExitInfo.REASON_CRASH
                            || info.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE
                            || info.getReason() == ApplicationExitInfo.REASON_ANR)) {
                        String trace = readShortTrace(info);
                        if (trace != null && !trace.isEmpty()) {
                            sb.append("Trace excerpt:\n").append(redactSensitiveData(trace)).append("\n");
                        }
                    }
                }
                lastExitSummary = sb.toString().trim();
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_EXIT_HISTORY, lastExitSummary)
                    .apply();
        } catch (Throwable t) {
            lastExitSummary = "ExitInfo unavailable: " + t.getClass().getSimpleName();
        }
    }

    @Nullable
    private static String readShortTrace(ApplicationExitInfo info) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }
        try (InputStream is = info.getTraceInputStream()) {
            if (is == null) return null;
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines < 25) {
                sb.append(line).append('\n');
                lines++;
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String exitReasonName(int reason) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "UNKNOWN(" + reason + ")";
        }
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH_JAVA";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INIT_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            default: return "OTHER(" + reason + ")";
        }
    }

    public static void recordEvent(String category, String message) {
        if (!EveInspectionMode.isEnabledForCurrentBuild()) {
            return;
        }
        String entry = "[" + formatNow() + "] [" + category + "] " + redactSensitiveData(message == null ? "" : message);
        synchronized (EVENTS) {
            if (EVENTS.size() >= MAX_EVENTS) {
                EVENTS.remove(0);
            }
            EVENTS.add(entry);
        }
    }

    public static List<String> getRecentEvents() {
        synchronized (EVENTS) {
            return Collections.unmodifiableList(new ArrayList<>(EVENTS));
        }
    }

    public static String getLastCrashReport(Context context) {
        if (!EveInspectionMode.isEnabledForCurrentBuild() || context == null) {
            return "Disabled in release builds";
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LAST_CRASH, "No uncaught Java crashes captured.");
    }

    public static String getLastExitSummary(Context context) {
        if (!EveInspectionMode.isEnabledForCurrentBuild() || context == null) {
            return "Disabled in release builds";
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_EXIT_HISTORY, lastExitSummary);
    }

    public static void clearReports(Context context) {
        if (!EveInspectionMode.isEnabledForCurrentBuild()) {
            return;
        }
        synchronized (EVENTS) {
            EVENTS.clear();
        }
        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
        }
    }

    public static String redactSensitiveData(@Nullable String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String out = BEARER_PATTERN.matcher(input).replaceAll("$1[REDACTED]");
        out = SECRET_KV_PATTERN.matcher(out).replaceAll("$1=[REDACTED]");
        out = EMAIL_PATTERN.matcher(out).replaceAll("[REDACTED_EMAIL]");
        out = PHONE_PATTERN.matcher(out).replaceAll("[REDACTED_PHONE]");
        return out;
    }

    public static String buildSanitizedReport(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== EVE DEBUG DIAGNOSTIC REPORT ===\n");
        sb.append("Generated: ").append(formatNow()).append("\n");
        sb.append("App ID: ").append(context != null ? context.getPackageName() : "com.eve.app").append("\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Debug Inspection Mode: ").append(EveInspectionMode.isEnabledForCurrentBuild()).append("\n");
        sb.append("Native Lib Loaded: ").append(NativeLoader.loaded()).append("\n\n");

        sb.append("--- PREVIOUS PROCESS EXIT REASONS ---\n");
        sb.append(getLastExitSummary(context)).append("\n\n");

        sb.append("--- LAST UNCAUGHT JAVA CRASH ---\n");
        sb.append(getLastCrashReport(context)).append("\n\n");

        sb.append("--- LOADING STATES ---\n");
        List<EveLoadingTracker.Entry> loadingEntries = EveLoadingTracker.snapshot();
        if (loadingEntries.isEmpty()) {
            sb.append("(none recorded yet)\n");
        } else {
            for (EveLoadingTracker.Entry e : loadingEntries) {
                sb.append("- ").append(e.module).append(": ").append(e.outcome)
                        .append(" (").append(e.detail).append(")\n");
            }
        }
        sb.append("\n--- RECENT DIAGNOSTIC EVENTS ---\n");
        List<String> events = getRecentEvents();
        if (events.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (String ev : events) {
                sb.append(ev).append("\n");
            }
        }
        return redactSensitiveData(sb.toString());
    }

    private static String formatNow() {
        return formatTime(System.currentTimeMillis());
    }

    private static String formatTime(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }
}
