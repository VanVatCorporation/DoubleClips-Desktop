package com.vanvatcorporation.doubleclips;

import java.util.Locale;

/**
 * The numbers behind a reversed clip, kept free of any UI / GL so they can be checked on their own
 * (see {@link ReverseMapSelfTest}). Ported from the iOS app (EditingView+Reverse.swift).
 * <p>
 * A reversed clip plays a reversed COPY of its whole source file, cached next to the source as
 * {@code <clipName>.reversed.mp4} (the same name iOS and Android use, so a project that moves between
 * the apps finds the copy it already has). The clip keeps its trims and speed on the ORIGINAL's timeline;
 * only the time handed to the decoder changes: time t of the original is time (copyLength - t) in the copy.
 */
public final class ReverseMap {

    private ReverseMap() {}

    /** The cache file name of a source file's reversed copy. */
    public static final String SUFFIX = ".reversed.mp4";

    /** {@code clip.mp4} -> {@code clip.mp4.reversed.mp4}. */
    public static String cacheName(String sourceName) {
        return sourceName + SUFFIX;
    }

    /** True for a file name that is a reversed copy itself (never reverse those again). */
    public static boolean isCacheName(String name) {
        return name != null && name.endsWith(SUFFIX);
    }

    /**
     * Where the decoder should be in the reversed copy when the clip has played {@code sourceElapsed} seconds of
     * source time. The clip's used range is [startClipTrim, originalLength - endClipTrim] of the original and it plays
     * backwards from its end, so the copy's start offset is {@code copyLength - (originalLength - endClipTrim)}.
     *
     * @param copyVideoLength the reversed copy's VIDEO length (frames / fps), not its container length: when the
     *                        original's audio runs longer than its video the container is longer than the video
     * @param originalLength  {@code clip.originalDuration}, the source's length as the app measured it
     */
    public static double copyTime(double copyVideoLength, double originalLength, double endClipTrim, double sourceElapsed) {
        double t = copyVideoLength - (originalLength - endClipTrim) + sourceElapsed;
        return Math.max(0.0, t);
    }

    /**
     * A copy is only worth using when its length is close to the original's (a different length means it was cut
     * short, e.g. the app was closed while it was being made); the same tolerance iOS uses.
     */
    public static boolean plausibleLength(double copyLength, double originalLength) {
        if (copyLength <= 0 || originalLength <= 0) return false;
        double tolerance = Math.max(1.0, originalLength * 0.05);
        return Math.abs(copyLength - originalLength) <= tolerance;
    }

    /**
     * How many seconds of video one ffmpeg {@code reverse} pass may hold. The filter keeps every frame of the pass in
     * memory (decoded, 4:2:0 = 1.5 bytes a pixel), so the length is picked from a memory budget instead of
     * reversing a whole 20-minute file in one go. Between 1 and 20 seconds.
     */
    public static double chunkSeconds(int width, int height, double fps) {
        double bytesPerSecond = Math.max(1, width) * (double) Math.max(1, height) * 1.5 * Math.max(1.0, fps);
        double seconds = 400_000_000.0 / bytesPerSecond;
        return Math.max(1.0, Math.min(20.0, seconds));
    }

    /** Frames in one video chunk (at least 1). */
    public static int chunkFrames(int width, int height, double fps) {
        return Math.max(1, (int) Math.floor(chunkSeconds(width, height, fps) * fps));
    }

    /**
     * The window of source time that belongs to chunk {@code k} when chunks hold {@code framesPerChunk} frames:
     * frame i sits at i/fps, and every boundary is put a hair (1 ms, less at very high rates) EARLIER than a frame's own
     * time. A timestamp that is a rounding error off can then never move a frame into the neighbouring chunk (every frame
     * lands in exactly one), and the seeked chunk's frames still sit on whole frame times, which keeps ffmpeg's frame-rate
     * conversion from rounding a half-frame offset either way (that dropped a frame per join when the guard was half a frame).
     * Use {@link #exactFps} for the rate, or the boundaries drift on a long NTSC file.
     *
     * @return {start, end} in seconds; end is {@link Double#POSITIVE_INFINITY} for the open last chunk when {@code last}
     */
    public static double[] chunkWindow(int k, int framesPerChunk, double fps, boolean last) {
        double dt = 1.0 / fps;
        double guard = Math.min(0.001, dt / 4.0);
        double start = k == 0 ? 0.0 : Math.max(0.0, k * (double) framesPerChunk * dt - guard);
        double end = last ? Double.POSITIVE_INFINITY : (k + 1) * (double) framesPerChunk * dt - guard;
        return new double[] {start, end};
    }

    /** The true rate behind a rounded one: 29.97 is 30000/1001 (a long file drifts by milliseconds otherwise). */
    public static double exactFps(double fps) {
        double[][] ntsc = {{23.976, 24000}, {29.97, 30000}, {47.952, 48000}, {59.94, 60000}, {119.88, 120000}};
        for (double[] n : ntsc) {
            if (Math.abs(fps - n[0]) < 0.01) return n[1] / 1001.0;
        }
        return fps;
    }

    /** ffmpeg wants the real fraction for the NTSC rates (29.97 is 30000/1001), or a long file drifts. */
    public static String rationalFps(double fps) {
        double[][] ntsc = {{23.976, 24000}, {29.97, 30000}, {47.952, 48000}, {59.94, 60000}, {119.88, 120000}};
        for (double[] n : ntsc) {
            if (Math.abs(fps - n[0]) < 0.01) return (long) n[1] + "/1001";
        }
        if (Math.abs(fps - Math.rint(fps)) < 0.001) return Long.toString(Math.round(fps));
        return String.format(Locale.ROOT, "%.5f", fps);
    }
}
