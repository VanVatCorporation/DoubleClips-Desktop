package com.vanvatcorporation.doubleclips;

/**
 * The deterministic "random" numbers the effects shader (effects.frag) is handed, the same ones the iOS editor uses:
 * the same step and salt always give the same number in 0..1, so preview, scrubbing, export and the picker tiles
 * all show the same glitch / VHS / shake pattern at the same moment. Worked out here (not in the shader) so every
 * place that draws an effect agrees on them.
 *
 * <p>This is what is left of the old CPU effect renderer: the pictures themselves, including the picker tiles, are
 * drawn by the GPU now (see TileRenderer), so there is no second copy of the effect maths to keep in step.
 */
public final class EffectRandoms {

    private EffectRandoms() {}

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }

    /** Same pseudo-random as the iOS editor: the same step and salt always give the same number in 0..1. */
    static float random(long step, double salt) {
        double x = Math.sin(step * 12.9898 + salt * 78.233) * 43758.5453;
        return (float) (x - Math.floor(x));
    }

    /**
     * The random numbers an effect uses, by salt, for the step of the moment (the pattern of glitch and VHS jumps 15
     * times a second, shake 30).
     */
    public static float[] randoms(String style, float time) {
        long step = (long) Math.floor(time * ("shake".equals(style) ? 30.0 : 15.0));
        float[] r = new float[32];
        for (int salt = 0; salt < r.length; salt++) r[salt] = random(step, salt);
        return r;
    }

    /** The step number the VHS tracking line wanders by. */
    public static float wander(float time) {
        return fract((float) (Math.floor(time * 15.0) * 0.07));
    }
}
