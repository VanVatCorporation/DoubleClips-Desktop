package com.vanvatcorporation.doubleclips.ui;

/**
 * Which ticks and labels the timeline ruler draws, kept free of JavaFX so it can be tested on its own.
 * <p>
 * The ruler used to create a {@code Line} / {@code Text} node for EVERY tick of the whole timeline on every refresh and every
 * zoom step (tens of thousands of nodes for a long timeline zoomed in to single frames). {@link #generate} emits only the ticks
 * that fall inside a pixel window, with exactly the positions, labels and looks the node-based ruler produced (frames counted
 * at the project's frame rate), so {@link RulerView} can draw just what is on screen.
 * <p>
 * Coordinates are timeline content pixels ({@code time * pixelsPerSecond}).
 */
public final class RulerTicks {

    private RulerTicks() {}

    /** Aim for at least this many pixels between major ticks. */
    private static final float MIN_MAJOR_SPACING = 80f;

    /** What a tick looks like. */
    public static final int MINOR = 0;   // a frame in between: short, faint
    public static final int MAJOR = 1;   // the interval's own tick
    public static final int SECOND = 2;  // the start of a whole second: tall and thick, whatever it is

    /** Receives the ticks of a window. All {@code x} are content pixels; every tick runs from {@code topY} to the ruler's bottom. */
    public interface Sink {
        void tick(double x, double topY, int kind);

        /** A label drawn at {@code x} (already offset from its tick), baseline y=9. {@code second}: bold and fully opaque. */
        void label(double x, String text, boolean second);
    }

    /**
     * The major-tick interval in seconds: 1, 2, 5, 10, 15 frames (the ones shorter than a second), then whole seconds, doubling;
     * the first with at least {@link #MIN_MAJOR_SPACING} pixels.
     */
    public static float interval(float pixelsPerSecond, int fps) {
        float[] intervals = new float[5 + 11];
        int n = 0;
        for (int frames : new int[]{1, 2, 5, 10, 15}) {
            if (frames < fps) intervals[n++] = frames / (float) fps;
        }
        for (float seconds = 1f; seconds <= 1024f; seconds *= 2f) intervals[n++] = seconds;
        for (int i = 0; i < n; i++) {
            if (intervals[i] * pixelsPerSecond >= MIN_MAJOR_SPACING) return intervals[i];
        }
        return intervals[n - 1];
    }

    /** "0s", "5s", "1m", "1m5s" for the start of a second; the frame number inside its second ("12f") for any other frame. */
    public static String label(long frame, int fps) {
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

    /**
     * Emits every tick that can touch the content pixel window [{@code fromPx}, {@code toPx}], plus one major step of margin each
     * side (so a label that starts just left of the window is still drawn). Ticks exist up to the end of the timeline
     * ({@code durationSeconds}) plus one interval, as before.
     */
    public static void generate(float pixelsPerSecond, float durationSeconds, int fps, double fromPx, double toPx, Sink sink) {
        if (pixelsPerSecond <= 0 || fps <= 0 || !(toPx >= fromPx)) return;

        final float rulerInterval = interval(pixelsPerSecond, fps);
        final long steps = (long) Math.ceil(durationSeconds / rulerInterval);
        // long and double: with a very high frame rate a second is more frames than an int holds
        final long framesInInterval = Math.max(1L, Math.round((double) rulerInterval * fps));
        final float pixelsPerFrame = pixelsPerSecond / fps;

        final double stepPx = framesInInterval * (double) pixelsPerSecond / fps;      // > 0
        final long firstStep = Math.max(0, (long) Math.floor(fromPx / stepPx) - 1);
        final long lastStep = Math.min(steps, (long) Math.ceil(toPx / stepPx) + 1);

        for (long step = firstStep; step <= lastStep; step++) {
            // counted in whole frames, so every tick and label is exact (no 1/30 s rounding)
            long frame = step * framesInInterval;
            mark(sink, frame * (double) pixelsPerSecond / fps, frame, fps, true, true);

            // Minor ticks (frames) between this major tick and the next
            if (step < steps) {
                if (pixelsPerFrame >= 4f) {
                    // Enough space for every frame (at least 4px between ticks); very zoomed in, each carries its own number
                    // inside its second. Only the frames of the window's own steps are visited.
                    for (long f = 1; f < framesInInterval; f++) {
                        long subFrame = frame + f;
                        mark(sink, subFrame * (double) pixelsPerSecond / fps, subFrame, fps, false,
                                pixelsPerFrame >= 40f && framesInInterval > 1);
                    }
                } else if (framesInInterval >= 10) {
                    // Too dense for every frame, but wide enough for a midpoint tick.
                    long mid = frame + framesInInterval / 2;
                    mark(sink, mid * (double) pixelsPerSecond / fps, mid, fps, false, false);
                }
            }
        }
    }

    private static void mark(Sink sink, double x, long frame, int fps, boolean major, boolean labelled) {
        boolean second = frame % fps == 0;
        sink.tick(x, second ? 2 : (major ? 10 : 18), second ? SECOND : (major ? MAJOR : MINOR));
        if (labelled || second) sink.label(x + 3, label(frame, fps), second);
    }
}
