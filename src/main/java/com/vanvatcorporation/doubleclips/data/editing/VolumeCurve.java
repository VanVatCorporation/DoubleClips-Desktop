package com.vanvatcorporation.doubleclips.data.editing;

import com.vanvatcorporation.doubleclips.data.editing.VideoProperties.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * A clip's loudness over its own length: the plain {@code videoProperties.valueVolume} (the volume every platform shares), or, when some keyframe holds the clip's
 * "volume" channel (iOS's keyframed audio volume; the same channel Android's volume field keys), the value interpolated
 * through those keyframes like any other animated property. 1 = as recorded, 0 = silent, 2 = twice as loud.
 *
 * <p>Three readers share it, so what you hear while editing is what the export writes:
 * <ul>
 * <li>the preview's mixer asks {@link #gainAt} for the clip's gain at the two ends of every 20 ms chunk and ramps between them;</li>
 * <li>the FFmpeg audio graph (which also feeds the OpenGL export's audio) gets {@link #ffmpegStage}, the same curve as one
 *     {@code volume} filter expression of the clip's own time;</li>
 * <li>the waveforms scale their bars by {@link #gainAt}, and {@link #signature} tells them when to redraw.</li>
 * </ul>
 *
 * <p>Keyframe times are seconds into the clip on the TIMELINE (speed changes don't move them), the clock the audio mix
 * already runs on, so no speed mapping is needed. The static volume is {@code valueVolume}, as on iOS and Android; while the volume
 * is animated it is not read (the keyframes decide), and the last keyframe taken off leaves it with the value it had.
 */
public final class VolumeCurve {

    /** Loudest gain a keyframed volume may reach (400 %), like iOS. A plain static volume is left as it is typed. */
    public static final float MAX_GAIN = 4f;
    /** An eased segment is written to FFmpeg as this many straight pieces (a linear one is exact in one). */
    static final int EASE_PIECES = 6;

    private VolumeCurve() {}

    /** The clip's plain volume ({@code valueVolume}). */
    public static float plain(Clip clip) {
        return clip == null || clip.videoProperties == null ? 1f : clip.videoProperties.valueVolume;
    }

    /**
     * Is the clip's volume keyframed? A keyframe that lists the volume channel by name counts (written by the iOS and Android
     * builds, or by the Audio Volume diamond); the "all properties" keyframes older builds wrote for position, scale... count
     * only once the user has keyed the volume on this clip ({@link Clip#volumeKeyed}), or their stale volume (always 1) would
     * replace the clip's own {@code valueVolume}.
     */
    public static boolean isAnimated(Clip clip) {
        if (clip == null || clip.keyframes == null || clip.keyframes.keyframes == null) return false;
        if (!clip.keyframes.isAnimated(ValueType.Volume)) return false;
        if (clip.volumeKeyed) return true;
        for (Keyframe k : clip.keyframes.keyframesHolding(ValueType.Volume)) {
            if (k.channels != null) return true; // it names the channel: written on purpose
        }
        return false;
    }

    /**
     * The clip's gain at a global (timeline) time. Safe to call from the audio thread: should the keyframes be edited under it
     * the plain volume is used for that one read.
     */
    public static float gainAt(Clip clip, float time) {
        float raw;
        boolean animated = false;
        try {
            animated = isAnimated(clip);
            raw = animated ? clip.keyframes.getValueAtTime(clip, time, ValueType.Volume) : plain(clip);
        } catch (RuntimeException keyframesChanged) {
            raw = plain(clip);
        }
        if (Float.isNaN(raw) || Float.isInfinite(raw)) return 1f;
        return animated ? Math.min(Math.max(raw, 0f), MAX_GAIN) : Math.max(raw, 0f);
    }

    /** What a waveform / thumbnail depends on: the plain volume, and every keyframe that holds the volume (time, value, easing). */
    public static String signature(Clip clip) {
        StringBuilder sb = new StringBuilder().append(plain(clip));
        if (isAnimated(clip)) {
            try {
                for (Keyframe k : clip.keyframes.keyframesHolding(ValueType.Volume)) {
                    sb.append('|').append(k.getLocalTime()).append(':').append(k.value == null ? 1f : k.value.getValue(ValueType.Volume))
                            .append(':').append(k.easing);
                }
            } catch (RuntimeException ignored) {
                // edited while being read: the next refresh reads it again
            }
        }
        return sb.toString();
    }

    // ---- FFmpeg --------------------------------------------------------------------------------

    /**
     * The filters that apply the keyframed volume to a clip's audio, or "" when the volume is not animated (the caller then
     * writes the plain {@code volume=} as before). Ends with a comma so it can sit in a chain. It must come right after the
     * clip's {@code atrim}, before any {@code adelay} / {@code apad}: {@code asetpts=PTS-STARTPTS} makes {@code t} the
     * time into the clip.
     *
     * <p>A reversed clip is reversed LAST in the chain, so its curve is read backwards here and plays forwards after it.
     */
    public static String ffmpegStage(Clip clip) {
        if (!isAnimated(clip)) return "";
        List<Keyframe> holders = clip.keyframes.keyframesHolding(ValueType.Volume);
        float[] times = new float[holders.size()];
        float[] values = new float[holders.size()];
        boolean[] hold = new boolean[holders.size()];
        DoubleUnaryOperator[] ease = new DoubleUnaryOperator[holders.size()];
        for (int i = 0; i < holders.size(); i++) {
            Keyframe k = holders.get(i);
            times[i] = k.getLocalTime();
            values[i] = k.value == null ? 1f : k.value.getValue(ValueType.Volume);
            EasingType type = k.easing == null ? EasingType.LINEAR : k.easing;
            hold[i] = type == EasingType.NONE;
            final EasingType fixed = type;
            ease[i] = type == EasingType.LINEAR ? null : u -> AnimatedProperty.ease((float) u, fixed);
        }
        String time = clip.isReverse() ? "(" + number(clip.duration) + "-t)" : "t";
        String expr = expression(times, values, hold, ease, time);
        return "asetpts=PTS-STARTPTS,volume=volume='" + expr + "':eval=frame,";
    }

    // ---- the pure part (tested on its own) -------------------------------------------------------

    /**
     * A piecewise-linear FFmpeg expression of {@code time} for a curve through keys at {@code times} (ascending) with
     * {@code values}: before the first key it holds the first value, after the last the last one. Segment i runs from key i
     * to key i + 1 with {@code ease[i]} (null = linear; {@code hold[i]} = the value stays until the next key, then jumps).
     * Values are clamped to 0..{@link #MAX_GAIN}.
     */
    static String expression(float[] times, float[] values, boolean[] hold, DoubleUnaryOperator[] ease, String time) {
        int n = times.length;
        if (n == 0) return "1";
        if (n == 1) return number(clamp(values[0]));

        // pieces: {end time, start time, start value, end value}, evaluated while time < end time
        List<double[]> pieces = new ArrayList<>();
        pieces.add(new double[]{times[0], times[0], clamp(values[0]), clamp(values[0])}); // before the first key
        for (int i = 0; i + 1 < n; i++) {
            double t0 = times[i], t1 = times[i + 1];
            double rawA = values[i], rawB = values[i + 1];
            if (t1 - t0 < 1e-6) continue;                       // two keys on one moment: the later one wins from there
            if (hold[i]) {
                pieces.add(new double[]{t1, t0, clamp(rawA), clamp(rawA)});
            } else if (ease[i] == null && inRange(rawA) && inRange(rawB)) {
                pieces.add(new double[]{t1, t0, rawA, rawB});   // a straight line is exact in one piece
            } else {
                // Eased, or a line that leaves 0..MAX_GAIN: cut into pieces of the curve (clamped like the preview reads it).
                for (int j = 0; j < EASE_PIECES; j++) {
                    double u0 = j / (double) EASE_PIECES, u1 = (j + 1) / (double) EASE_PIECES;
                    double v0 = clamp(rawA + (rawB - rawA) * (ease[i] == null ? u0 : ease[i].applyAsDouble(u0)));
                    double v1 = (j + 1 == EASE_PIECES) ? clamp(rawB)
                            : clamp(rawA + (rawB - rawA) * (ease[i] == null ? u1 : ease[i].applyAsDouble(u1)));
                    pieces.add(new double[]{t0 + (t1 - t0) * u1, t0 + (t1 - t0) * u0, v0, v1});
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (double[] p : pieces) {
            sb.append("if(lt(").append(time).append(',').append(number(p[0])).append("),");
            if (p[2] == p[3]) sb.append(number(p[2]));
            else {
                double slope = (p[3] - p[2]) / (p[0] - p[1]);
                sb.append(number(p[2])).append('+').append(number(slope)).append("*(").append(time).append('-').append(number(p[1])).append(')');
            }
            sb.append(',');
        }
        sb.append(number(clamp(values[n - 1])));
        for (int i = 0; i < pieces.size(); i++) sb.append(')');
        return sb.toString();
    }

    private static boolean inRange(double v) {
        return v >= 0.0 && v <= MAX_GAIN;
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) return 1.0;
        return Math.min(Math.max(v, 0.0), MAX_GAIN);
    }

    /** A plain decimal (never "1.0E-4"), which every FFmpeg expression parser reads. */
    static String number(double v) {
        String s = String.format(Locale.ROOT, "%.6f", v);
        if (s.indexOf('.') >= 0) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.equals("-0") || s.isEmpty() ? "0" : s;
    }
}
