package com.vanvatcorporation.doubleclips.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-check for {@link RulerTicks} (no JavaFX, no framework): the windowed ticks must be the ones the old node-per-tick ruler
 * drew. Run: {@code java com.vanvatcorporation.doubleclips.ui.RulerTicksSelfTest}; exits 1 and prints what failed.
 */
public final class RulerTicksSelfTest {

    private RulerTicksSelfTest() {}

    // ---- the old ruler, copied from EditorWindow.buildRuler / addRulerMark / getRulerInterval / rulerLabel ----

    private static float oldInterval(float pixelsPerSecond, int fps) {
        List<Float> intervals = new ArrayList<>();
        for (int frames : new int[]{1, 2, 5, 10, 15}) if (frames < fps) intervals.add(frames / (float) fps);
        for (float seconds = 1f; seconds <= 1024f; seconds *= 2f) intervals.add(seconds);
        for (float interval : intervals) if (interval * pixelsPerSecond >= 80f) return interval;
        return intervals.get(intervals.size() - 1);
    }

    private static String oldLabel(long frame, int fps) {
        long sec = frame / fps;
        int f = (int) (frame % fps);
        if (f == 0) {
            long m = sec / 60;
            long s = sec % 60;
            if (m > 0) return s == 0 ? m + "m" : m + "m" + s + "s";
            return s + "s";
        }
        return f + "f";
    }

    private static void oldMark(List<String> out, double x, long frame, int fps, boolean major, boolean labelled) {
        boolean second = frame % fps == 0;
        out.add(String.format("T %.4f %d %d", x, second ? 2 : (major ? 10 : 18), second ? 2 : (major ? 1 : 0)));
        if (labelled || second) out.add(String.format("L %.4f %s %b", x + 3, oldLabel(frame, fps), second));
    }

    private static List<String> oldRuler(float pps, float duration, int fps) {
        List<String> out = new ArrayList<>();
        float rulerInterval = oldInterval(pps, fps);
        long steps = (long) Math.ceil(duration / rulerInterval);
        long framesInInterval = Math.max(1L, Math.round((double) rulerInterval * fps));
        float pixelsPerFrame = pps / fps;
        for (long step = 0; step <= steps; step++) {
            long frame = step * framesInInterval;
            oldMark(out, frame * (double) pps / fps, frame, fps, true, true);
            if (step < steps) {
                if (pixelsPerFrame >= 4f) {
                    for (long f = 1; f < framesInInterval; f++) {
                        long subFrame = frame + f;
                        oldMark(out, subFrame * (double) pps / fps, subFrame, fps, false, pixelsPerFrame >= 40f && framesInInterval > 1);
                    }
                } else if (framesInInterval >= 10) {
                    long mid = frame + framesInInterval / 2;
                    oldMark(out, mid * (double) pps / fps, mid, fps, false, false);
                }
            }
        }
        return out;
    }

    private static List<String> windowed(float pps, float duration, int fps, double from, double to) {
        List<String> out = new ArrayList<>();
        RulerTicks.generate(pps, duration, fps, from, to, new RulerTicks.Sink() {
            @Override public void tick(double x, double topY, int kind) { out.add(String.format("T %.4f %d %d", x, (int) topY, kind)); }
            @Override public void label(double x, String text, boolean second) { out.add(String.format("L %.4f %s %b", x, text, second)); }
        });
        return out;
    }

    public static void main(String[] args) {
        List<String> fails = new ArrayList<>();
        float[] zooms = {1f, 5f, 20f, 100f, 400f, 1500f, 6000f};
        int[] rates = {24, 30, 60, 120};
        float duration = 95.5f;

        for (int fps : rates) {
            for (float pps : zooms) {
                if (RulerTicks.interval(pps, fps) != oldInterval(pps, fps)) fails.add("interval differs at " + pps + " px/s, " + fps + " fps");

                // 1. the whole range gives exactly what the old ruler drew
                List<String> want = oldRuler(pps, duration, fps);
                List<String> all = windowed(pps, duration, fps, 0, 1e12);
                if (!want.equals(all)) fails.add("full range differs at " + pps + " px/s, " + fps + " fps (" + want.size() + " vs " + all.size() + ")");

                // 2. every old tick / label inside a window is drawn by the windowed call; and nothing absurd is emitted
                double span = Math.min(3000, duration * pps);
                for (double from : new double[]{0, span / 3, Math.max(0, duration * pps - span)}) {
                    double to = from + span;
                    List<String> got = windowed(pps, duration, fps, from, to);
                    java.util.HashSet<String> gotSet = new java.util.HashSet<>(got);
                    for (String line : want) {
                        String[] p = line.split(" ");
                        double x = Double.parseDouble(p[1]);
                        boolean inside = p[0].equals("T") ? (x >= from && x <= to) : (x >= from && x - 3 <= to) ;
                        if (inside && !gotSet.contains(line)) { fails.add("missing in window [" + from + "," + to + "] at " + pps + " px/s, " + fps + " fps: " + line); break; }
                    }
                    for (String line : got) if (!new java.util.HashSet<>(want).contains(line)) { fails.add("invented " + line); break; }
                    // a 3000 px window of the ruler: a few thousand marks at most, however long the timeline
                    if (got.size() > 12000) fails.add("window emits " + got.size() + " marks at " + pps + " px/s, " + fps + " fps");
                }
            }
        }

        // 3. a long timeline zoomed far in: the old ruler made hundreds of thousands of nodes, the window a few thousand marks
        List<String> hugeWindow = windowed(6000f, 3600f, 60, 100_000, 103_000);
        if (hugeWindow.size() > 12000) fails.add("an hour at 6000 px/s: window emits " + hugeWindow.size());
        if (hugeWindow.isEmpty()) fails.add("an hour at 6000 px/s: window is empty");
        System.out.println("hour at 6000 px/s, 60 fps, 3000 px window: " + hugeWindow.size() + " marks (old ruler: "
                + oldRuler(6000f, 3600f, 60).size() + ")");

        if (fails.isEmpty()) System.out.println("RulerTicksSelfTest: all checks passed");
        else { fails.forEach(f -> System.out.println("FAIL " + f)); System.exit(1); }
    }
}
