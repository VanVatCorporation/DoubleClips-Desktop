package com.vanvatcorporation.doubleclips;

/**
 * The deterministic "random" numbers the effect filters use (tearSlices, rgbShift's random sign, vhs), the same ones the iOS
 * and Android editors use: the same step and salt always give the same number in 0..1, so preview, scrubbing, export and the
 * picker tiles all show the same glitch / VHS pattern at the same moment. Worked out on the CPU
 * (see {@link OpenGLEdit.FilterStep#random}) so every place that draws an effect agrees on them.
 */
public final class EffectRandoms {

    private EffectRandoms() {}

    /** Same pseudo-random as the iOS editor: the same step and salt always give the same number in 0..1. */
    static float random(long step, double salt) {
        double x = Math.sin(step * 12.9898 + salt * 78.233) * 43758.5453;
        return (float) (x - Math.floor(x));
    }
}
