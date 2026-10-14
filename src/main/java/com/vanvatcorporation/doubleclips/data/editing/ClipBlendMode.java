package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.annotations.SerializedName;

/**
 * How a clip mixes with everything drawn BELOW it (lower tracks, in layer order) instead of just covering it
 * (CapCut's "Blend"; iOS EditingView+Blend.swift). The names are iOS's raw names, so the {@code blendMode} key on a
 * clip means the same on every platform; no key = {@link #NORMAL}.
 *
 * <p>Rules (same as iOS): the clip's opacity still applies (it scales the clip's alpha before the blend); a clip with
 * nothing drawn below it is drawn normally (blending with the empty black canvas would only turn Multiply / Overlay
 * clips black); in a transition the mode of the clip that is more on screen is used (outgoing before the middle,
 * incoming after); effect clips have none.
 *
 * <p>{@link #blend} is the reference maths (the W3C compositing formulas, on 0..1 channels): GlCompositor's shader
 * does the same, in the same {@link #ordinal() order}, and the picker tiles are drawn with it. Plain Java.
 */
public enum ClipBlendMode {
    @SerializedName("normal") NORMAL("Normal"),
    @SerializedName("multiply") MULTIPLY("Multiply"),
    @SerializedName("screen") SCREEN("Screen"),
    @SerializedName("overlay") OVERLAY("Overlay"),
    @SerializedName("darken") DARKEN("Darken"),
    @SerializedName("lighten") LIGHTEN("Lighten"),
    @SerializedName("colorDodge") COLOR_DODGE("Color Dodge"),
    @SerializedName("colorBurn") COLOR_BURN("Color Burn"),
    @SerializedName("softLight") SOFT_LIGHT("Soft Light"),
    @SerializedName("hardLight") HARD_LIGHT("Hard Light"),
    @SerializedName("difference") DIFFERENCE("Difference"),
    @SerializedName("exclusion") EXCLUSION("Exclusion"),
    @SerializedName("add") ADD("Add"),
    @SerializedName("hue") HUE("Hue"),
    @SerializedName("saturation") SATURATION("Saturation"),
    @SerializedName("color") COLOR("Color"),
    @SerializedName("luminosity") LUMINOSITY("Luminosity");

    public final String title;

    ClipBlendMode(String title) {
        this.title = title;
    }

    /** The shader's mode id. */
    public int glId() {
        return ordinal();
    }

    /** A clip's mode (null = normal) as the shader's id. */
    public static int glId(ClipBlendMode mode) {
        return mode == null ? 0 : mode.ordinal();
    }

    /**
     * {@code top} (straight, non-premultiplied colour, 0..1) mixed with {@code backdrop} (opaque) by this mode, at full
     * coverage; {@link #composite} adds the clip's alpha. NORMAL returns {@code top}.
     */
    public float[] blend(float[] backdrop, float[] top) {
        float[] cb = backdrop, cs = top;
        switch (this) {
            case NORMAL: return new float[]{cs[0], cs[1], cs[2]};
            case ADD: return new float[]{Math.min(1f, cb[0] + cs[0]), Math.min(1f, cb[1] + cs[1]), Math.min(1f, cb[2] + cs[2])};
            case HUE: return setLum(setSat(cs, sat(cb)), lum(cb));
            case SATURATION: return setLum(setSat(cb, sat(cs)), lum(cb));
            case COLOR: return setLum(cs, lum(cb));
            case LUMINOSITY: return setLum(cb, lum(cs));
            default:
                return new float[]{channel(cb[0], cs[0]), channel(cb[1], cs[1]), channel(cb[2], cs[2])};
        }
    }

    /**
     * One pixel: a top pixel of straight colour {@code top} and alpha {@code alpha} over an opaque {@code backdrop}.
     * ADD is the premultiplied add (backdrop + alpha * top), the others a mix of the plain "over" and the blended colour.
     */
    public float[] composite(float[] backdrop, float[] top, float alpha) {
        float a = Math.max(0f, Math.min(1f, alpha));
        float[] b = blend(backdrop, top);
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            out[i] = this == ADD
                    ? Math.min(1f, backdrop[i] + a * top[i])
                    : (1f - a) * backdrop[i] + a * b[i];
        }
        return out;
    }

    private float channel(float cb, float cs) {
        switch (this) {
            case MULTIPLY: return cb * cs;
            case SCREEN: return cb + cs - cb * cs;
            case OVERLAY: return hardLight(cs, cb);
            case DARKEN: return Math.min(cb, cs);
            case LIGHTEN: return Math.max(cb, cs);
            case COLOR_DODGE: return cb == 0f ? 0f : cs >= 1f ? 1f : Math.min(1f, cb / (1f - cs));
            case COLOR_BURN: return cb >= 1f ? 1f : cs <= 0f ? 0f : 1f - Math.min(1f, (1f - cb) / cs);
            case SOFT_LIGHT: {
                if (cs <= 0.5f) return cb - (1f - 2f * cs) * cb * (1f - cb);
                float d = cb <= 0.25f ? ((16f * cb - 12f) * cb + 4f) * cb : (float) Math.sqrt(cb);
                return cb + (2f * cs - 1f) * (d - cb);
            }
            case HARD_LIGHT: return hardLight(cb, cs);
            case DIFFERENCE: return Math.abs(cb - cs);
            case EXCLUSION: return cb + cs - 2f * cb * cs;
            default: return cs;
        }
    }

    private static float hardLight(float cb, float cs) {
        return cs <= 0.5f ? cb * 2f * cs : (cb + (2f * cs - 1f) - cb * (2f * cs - 1f));
    }

    // Non-separable helpers (hue / saturation / color / luminosity), W3C "Compositing and Blending".
    private static float lum(float[] c) { return 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2]; }

    private static float sat(float[] c) {
        return Math.max(Math.max(c[0], c[1]), c[2]) - Math.min(Math.min(c[0], c[1]), c[2]);
    }

    private static float[] clipColor(float[] c) {
        float l = lum(c);
        float n = Math.min(Math.min(c[0], c[1]), c[2]);
        float x = Math.max(Math.max(c[0], c[1]), c[2]);
        float[] o = {c[0], c[1], c[2]};
        if (n < 0f) for (int i = 0; i < 3; i++) o[i] = l + (o[i] - l) * l / (l - n);
        if (x > 1f) for (int i = 0; i < 3; i++) o[i] = l + (o[i] - l) * (1f - l) / (x - l);
        return o;
    }

    private static float[] setLum(float[] c, float l) {
        float d = l - lum(c);
        return clipColor(new float[]{c[0] + d, c[1] + d, c[2] + d});
    }

    private static float[] setSat(float[] c, float s) {
        float mx = Math.max(Math.max(c[0], c[1]), c[2]), mn = Math.min(Math.min(c[0], c[1]), c[2]);
        if (mx <= mn) return new float[]{0f, 0f, 0f};
        return new float[]{(c[0] - mn) * s / (mx - mn), (c[1] - mn) * s / (mx - mn), (c[2] - mn) * s / (mx - mn)};
    }
}
