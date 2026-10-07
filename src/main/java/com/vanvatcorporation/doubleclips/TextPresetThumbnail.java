package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.TextStyleData;

import java.nio.ByteBuffer;

/**
 * A style tile's picture, drawn live with the same rasteriser as the preview and the export, so there are no
 * preview images to ship and a tile can never disagree with what the style really looks like.
 */
public final class TextPresetThumbnail {

    private TextPresetThumbnail() {}

    /** Straight-alpha ARGB pixels, ready for a JavaFX PixelFormat.getIntArgbInstance(). */
    public static final class Pixels {
        public final int width, height;
        public final int[] argb;

        Pixels(int width, int height, int[] argb) {
            this.width = width;
            this.height = height;
            this.argb = argb;
        }
    }

    public static final String SAMPLE = "Abc";

    /** Draws {@code sample} in {@code preset}'s look, centred and as large as fits in a w x h tile. */
    public static Pixels render(TextStyleData preset, String sample, String fontsDir, int w, int h) {
        Clip clip = new Clip("Sample", 0f, 1f, 0, ClipType.TEXT, false, 1280, 720);
        clip.textContent = sample == null || sample.isEmpty() ? SAMPLE : sample;
        clip.fontSize = 48;
        TextStyleData look = new TextStyleData(preset.normalized());
        look.alignment = "center";
        look.wrapWidth = 0f;
        look.lineSpacing = 0f;
        look.unitMode = null; // the tile is a still
        clip.textStyle = look;

        TextStyle style = TextStyle.of(clip, 1920, fontsDir);
        float[] box = TextLayoutEngine.measure(style);
        float margin = 6f;
        float scale = Math.min((w - 2 * margin) / box[0], (h - 2 * margin) / box[1]);
        scale = Math.max(0.05f, Math.min(scale, 2f));

        TextLayoutEngine.Bitmap bitmap = TextLayoutEngine.render(style, scale);
        int[] out = new int[w * h];
        int ox = (w - bitmap.width) / 2, oy = (h - bitmap.height) / 2;
        ByteBuffer rgba = bitmap.rgba;
        for (int y = 0; y < bitmap.height; y++) {
            int ty = y + oy;
            if (ty < 0 || ty >= h) continue;
            for (int x = 0; x < bitmap.width; x++) {
                int tx = x + ox;
                if (tx < 0 || tx >= w) continue;
                int i = (y * bitmap.width + x) * 4;
                int r = rgba.get(i) & 0xFF, g = rgba.get(i + 1) & 0xFF, b = rgba.get(i + 2) & 0xFF, a = rgba.get(i + 3) & 0xFF;
                out[ty * w + tx] = a << 24 | r << 16 | g << 8 | b;
            }
        }
        return new Pixels(w, h, out);
    }
}
