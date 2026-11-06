package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Slow-call log for the timeline UI. Timing a call costs two {@code System.nanoTime()} reads; a line is written to
 * the persistent log only when a call is slower than its threshold, and at most one line per label every two
 * seconds (the first slow call is logged at once; later ones are counted and reported with the next slow call:
 * how many there were, the average and the worst). So it stays silent on a light
 * project and, on a heavy one, shows exactly which operation is the slow one:
 * <pre>[perf] refreshTimelineUI: 14 slow calls, avg 61.2 ms, max 118.4 ms | ruler=0.4 clips=48.0 renderer=9.1 (412 clips)</pre>
 */
final class TimelinePerf {

    private TimelinePerf() {}

    private static final long LOG_EVERY_MS = 2000;

    private static final class Stat {
        long lastLogAt;                 // 0: the first slow call is logged at once
        int count;
        double totalMs, maxMs;
        String lastInfo = "";
    }

    private static final Map<String, Stat> STATS = new HashMap<>();

    static long begin() {
        return System.nanoTime();
    }

    static double msSince(long t0) {
        return (System.nanoTime() - t0) / 1_000_000.0;
    }

    /**
     * @param slowMs calls faster than this are ignored
     * @param info   built by the caller only when it is about to be needed (pass null to skip)
     */
    static void end(String label, long t0, double slowMs, String info) {
        double ms = msSince(t0);
        if (ms < slowMs) return;
        record(label, ms, info);
    }

    private static synchronized void record(String label, double ms, String info) {
        Stat s = STATS.computeIfAbsent(label, k -> new Stat());
        s.count++;
        s.totalMs += ms;
        s.maxMs = Math.max(s.maxMs, ms);
        if (info != null) s.lastInfo = info;

        long now = System.currentTimeMillis();
        if (s.lastLogAt != 0 && now - s.lastLogAt < LOG_EVERY_MS) return;      // keep counting, log with the next slow call

        String line = String.format(java.util.Locale.US, "[perf] %s: %d slow call%s, avg %.1f ms, max %.1f ms%s",
                label, s.count, s.count == 1 ? "" : "s", s.totalMs / s.count, s.maxMs, s.lastInfo.isEmpty() ? "" : " | " + s.lastInfo);
        try {
            LoggingManager.LogToPersistentDataPath(line);
        } catch (Throwable ignored) {
            // logging must never break the editor
        }
        s.lastLogAt = now;
        s.count = 0;
        s.totalMs = 0;
        s.maxMs = 0;
    }
}
