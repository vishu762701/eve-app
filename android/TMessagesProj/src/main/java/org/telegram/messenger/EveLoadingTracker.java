package org.telegram.messenger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DEBUG-only loading state tracker ensuring every inspected module reaches
 * a defined terminal outcome rather than spinning indefinitely.
 */
public final class EveLoadingTracker {

    public enum Outcome {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        OFFLINE,
        TIMEOUT,
        CANCELLED
    }

    public static final class Entry {
        public final String module;
        public final Outcome outcome;
        public final String detail;
        public final long updatedAtMs;

        public Entry(String module, Outcome outcome, String detail, long updatedAtMs) {
            this.module = module;
            this.outcome = outcome;
            this.detail = detail == null ? "" : detail;
            this.updatedAtMs = updatedAtMs;
        }

        public boolean isTerminal() {
            return outcome != Outcome.LOADING;
        }
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    private EveLoadingTracker() {
    }

    public static void record(String module, Outcome outcome, String detail) {
        if (!EveInspectionMode.isEnabledForCurrentBuild() || module == null || outcome == null) {
            return;
        }
        synchronized (ENTRIES) {
            ENTRIES.put(module, new Entry(module, outcome, detail, System.currentTimeMillis()));
        }
    }

    public static Entry get(String module) {
        synchronized (ENTRIES) {
            return ENTRIES.get(module);
        }
    }

    public static List<Entry> snapshot() {
        synchronized (ENTRIES) {
            return Collections.unmodifiableList(new ArrayList<>(ENTRIES.values()));
        }
    }

    public static void clear() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
    }

    public static int countNonTerminal() {
        int count = 0;
        synchronized (ENTRIES) {
            for (Entry e : ENTRIES.values()) {
                if (!e.isTerminal()) {
                    count++;
                }
            }
        }
        return count;
    }
}
