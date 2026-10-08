package com.vanvatcorporation.doubleclips;

import java.awt.image.BufferedImage;

/**
 * The looping tile behind each effect in the picker: the real effect (the same maths as the timeline) run over a
 * small test picture with shapes, colour and fine detail, so every effect has something to show. Same picture,
 * loop length and frame rate as the iOS editor's tiles.
 */
public final class EffectPreview {

    private EffectPreview() {}

    public static final int DEFAULT_SIDE = 120;
    public static final double LOOP_SECONDS = 2.4;
    public static final int FRAMES_PER_SECOND = 12;

    public static int frameCount() {
        return (int) Math.round(LOOP_SECONDS * FRAMES_PER_SECOND);
    }

    /** Sky gradient, a faint checkerboard, and three discs. {@code side} x {@code side}, opaque. */
    public static EffectCpuRenderer.Frame testCard(int side) {
        EffectCpuRenderer.Frame f = new EffectCpuRenderer.Frame(side, side);
        float s = side;
        float[][] discs = {
                {0.32f * s, 0.38f * s, 0.20f * s, 1.00f, 0.88f, 0.20f},   // centre x, centre y (from the top), radius, colour
                {0.70f * s, 0.66f * s, 0.16f * s, 0.20f, 0.85f, 0.75f},
                {0.62f * s, 0.24f * s, 0.07f * s, 0.95f, 0.25f, 0.35f},
        };
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                float t = ((x + 0.5f) / s + (y + 0.5f) / s) / 2f; // top-left blue -> bottom-right orange
                float r = 0.10f + (0.98f - 0.10f) * t, g = 0.18f + (0.52f - 0.18f) * t, b = 0.55f + (0.20f - 0.55f) * t;
                int cell = (int) (s / 10f);
                if (cell > 0 && ((x / cell) + (y / cell)) % 2 == 0) { // white at 22%
                    r += (1f - r) * 0.22f;
                    g += (1f - g) * 0.22f;
                    b += (1f - b) * 0.22f;
                }
                for (float[] d : discs) {
                    float dx = x + 0.5f - d[0], dy = y + 0.5f - d[1];
                    float edge = d[2] + 0.6f - (float) Math.sqrt(dx * dx + dy * dy); // soft 1.2 px rim
                    float cover = Math.max(0f, Math.min(1f, edge / 1.2f));
                    r += (d[3] - r) * cover;
                    g += (d[4] - g) * cover;
                    b += (d[5] - b) * cover;
                }
                int i = (y * side + x) * 3;
                f.rgb[i] = r;
                f.rgb[i + 1] = g;
                f.rgb[i + 2] = b;
            }
        }
        return f;
    }

    /** One frame of the loop; {@code phase} is seconds into it. */
    public static EffectCpuRenderer.Frame frame(String style, float intensity, EffectCpuRenderer.Frame card, double phase) {
        float p = (float) (phase / LOOP_SECONDS);
        return EffectCpuRenderer.apply(style, intensity, card, p, (float) phase, (float) phase);
    }

    /** The whole loop, ready to cycle through. */
    public static BufferedImage[] loop(String style, float intensity, int side) {
        EffectCpuRenderer.Frame card = testCard(side);
        int n = frameCount();
        BufferedImage[] frames = new BufferedImage[n];
        for (int i = 0; i < n; i++) frames[i] = frame(style, intensity, card, i / (double) FRAMES_PER_SECOND).toImage();
        return frames;
    }
}
