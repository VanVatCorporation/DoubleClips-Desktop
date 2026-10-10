package com.vanvatcorporation.doubleclips;

/**
 * The values of every animatable channel for ONE output frame, as produced by
 * {@link ClipAnimation#evaluate(float)}. Immutable. A channel an animation does not
 * define sits at its neutral value, so "no animation" and "animation outside its window"
 * are both just {@link #NEUTRAL}.
 *
 * HOW EACH VALUE IS COMBINED WITH THE CLIP'S OWN (keyframed) PROPERTIES is fixed in code,
 * never chosen by an animation file (see OpenGLEdit.buildDrawCommand):
 *   additive       offsetX, offsetY, rotation, hue, brightness, temperature
 *   multiplicative opacity, scale, saturation
 *   standalone     contrast, exposure, blur, warp.*, motionBlur/blurAngle, edgeFill, edgeSlide(Y),
 *                  squeezeX/squeezeAnchor (the clip itself has no such property)
 * {@link #merged} stacks two frames (a transition style's side on top of the clip's own In / Out
 * animation) with the same rules, so both look right at once.
 * Plain Java (no Android classes) so the desktop port can use it as-is.
 */
public final class ClipAnimationFrame {

    /** Every channel at its neutral value. */
    public static final ClipAnimationFrame NEUTRAL = new ClipAnimationFrame(neutralValues());

    private final float[] v;

    ClipAnimationFrame(float[] values) {
        this.v = values;
    }

    static float[] neutralValues() {
        ClipAnimation.Channel[] all = ClipAnimation.Channel.values();
        float[] a = new float[all.length];
        for (ClipAnimation.Channel c : all) a[c.ordinal()] = (float) c.neutral;
        return a;
    }

    public float get(ClipAnimation.Channel channel) { return v[channel.ordinal()]; }

    /** True when nothing is animated this frame (callers may skip all extra work). */
    public boolean isNeutral() { return this == NEUTRAL; }

    /** Multiplier, 1 = unchanged. */
    public float opacity() { return get(ClipAnimation.Channel.OPACITY); }
    /** Uniform scale multiplier about the clip's pivot, 1 = unchanged. */
    public float scale() { return get(ClipAnimation.Channel.SCALE); }
    /** Offset as a fraction of the canvas WIDTH (+ = right). */
    public float offsetX() { return get(ClipAnimation.Channel.OFFSET_X); }
    /** Offset as a fraction of the canvas HEIGHT (+ = down). */
    public float offsetY() { return get(ClipAnimation.Channel.OFFSET_Y); }
    /** Extra rotation in degrees. */
    public float rotationDegrees() { return get(ClipAnimation.Channel.ROTATION); }
    /** Extra hue rotation in degrees. */
    public float hueDegrees() { return get(ClipAnimation.Channel.HUE); }
    /** Multiplier, 1 = unchanged. */
    public float saturation() { return get(ClipAnimation.Channel.SATURATION); }
    /** Additive, same -10..10 scale as VideoProperties.Brightness. */
    public float brightness() { return get(ClipAnimation.Channel.BRIGHTNESS); }
    /** Multiplier about mid-grey, 1 = unchanged. */
    public float contrast() { return get(ClipAnimation.Channel.CONTRAST); }
    /** Additive colour-temperature offset in Kelvin (0 = unchanged). */
    public float temperatureKelvin() { return get(ClipAnimation.Channel.TEMPERATURE); }
    /** Gaussian blur sigma as a fraction of the canvas WIDTH (0 = none). */
    public float blurWidthFraction() { return get(ClipAnimation.Channel.BLUR); }
    /** Top-edge width of the top-centre-anchored squish, 1 = unchanged. */
    public float warpTopWidth() { return get(ClipAnimation.Channel.WARP_TOP_WIDTH); }
    /** Bottom-edge width of the squish, 1 = unchanged. */
    public float warpBottomWidth() { return get(ClipAnimation.Channel.WARP_BOTTOM_WIDTH); }
    /** Height of the squish (top edge fixed), 1 = unchanged. */
    public float warpHeight() { return get(ClipAnimation.Channel.WARP_HEIGHT); }

    // ---- iOS look channels --------------------------------------------------------------

    /** Brightness MULTIPLIER on the colours (alpha untouched), 1 = unchanged, 0.32 = Glitch Blur's dark hit. */
    public float exposure() { return get(ClipAnimation.Channel.EXPOSURE); }
    /** Motion blur: TOTAL streak length as a fraction of the canvas WIDTH (0 = none). */
    public float motionBlurWidthFraction() { return get(ClipAnimation.Channel.MOTION_BLUR); }
    /** Direction of the motion-blur streaks, degrees counter-clockwise from the right (0 = horizontal). */
    public float blurAngleDegrees() { return get(ClipAnimation.Channel.BLUR_ANGLE); }
    /** &gt; 0.5 = after placing the picture, its edge pixels are extended over the whole canvas (no black margin). */
    public float edgeFill() { return get(ClipAnimation.Channel.EDGE_FILL); }
    /** The picture slides sideways INSIDE its own box (fraction of canvas width, + = right); edge pixels stretch into the gap. */
    public float edgeSlideFraction() { return get(ClipAnimation.Channel.EDGE_SLIDE); }
    /** Like {@link #edgeSlideFraction()} but vertical (fraction of canvas HEIGHT, + = down). */
    public float edgeSlideYFraction() { return get(ClipAnimation.Channel.EDGE_SLIDE_Y); }
    /** Horizontal squeeze of the picture toward its anchor, 1 = unchanged; edge pixels smear into the gap. */
    public float squeezeX() { return get(ClipAnimation.Channel.SQUEEZE_X); }
    /** Where the squeeze holds still: 0 = the picture's left edge, 1 = its right edge. */
    public float squeezeAnchor() { return get(ClipAnimation.Channel.SQUEEZE_ANCHOR); }

    /** True when any of the three warp.* channels is off neutral. */
    public boolean hasWarp() { return warpTopWidth() != 1f || warpBottomWidth() != 1f || warpHeight() != 1f; }

    // ---- combining two frames -----------------------------------------------------------

    /**
     * {@code this} combined with {@code other} (null = unchanged), channel by channel with the same
     * rules the compositor uses for a clip's own properties: offsets, rotation, hue, brightness,
     * temperature, edge slides, motion blur and blur ADD; opacity, scale, saturation, contrast,
     * exposure, warp.* and squeezeX MULTIPLY; edgeFill takes the larger; blurAngle follows whichever
     * frame has the longer motion blur; squeezeAnchor follows whichever squeezes harder. Every result
     * is clamped to its channel's range. Neither input is modified.
     */
    public ClipAnimationFrame merged(ClipAnimationFrame other) {
        if (other == null || other.isNeutral()) return this;
        if (isNeutral()) return other;
        ClipAnimation.Channel[] all = ClipAnimation.Channel.values();
        float[] out = new float[all.length];
        float motionA = motionBlurWidthFraction(), motionB = other.motionBlurWidthFraction();
        float squeezeA = Math.abs(squeezeX() - 1f), squeezeB = Math.abs(other.squeezeX() - 1f);
        for (ClipAnimation.Channel ch : all) {
            int i = ch.ordinal();
            double a = v[i], b = other.v[i];
            double combined;
            switch (ch) {
                case OFFSET_X: case OFFSET_Y: case ROTATION: case HUE: case BRIGHTNESS: case TEMPERATURE:
                case EDGE_SLIDE: case EDGE_SLIDE_Y: case MOTION_BLUR: case BLUR:
                    combined = a + b;
                    break;
                case BLUR_ANGLE:
                    combined = motionA >= motionB ? a : b;
                    break;
                case SQUEEZE_ANCHOR:
                    combined = squeezeA >= squeezeB ? a : b;
                    break;
                case EDGE_FILL:
                    combined = Math.max(a, b);
                    break;
                default: // OPACITY, SCALE, SATURATION, CONTRAST, EXPOSURE, WARP_*, SQUEEZE_X
                    combined = a * b;
                    break;
            }
            out[i] = (float) Math.min(Math.max(combined, ch.min), ch.max);
        }
        return new ClipAnimationFrame(out);
    }
}
