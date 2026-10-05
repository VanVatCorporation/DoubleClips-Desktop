package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.editing.Clip;

import java.util.Objects;

/**
 * Everything the rendered text depends on, as one immutable value. Equal styles give the same bitmap,
 * so {@link #key()} is the cache key for layouts and GL textures, and a style change is detected just
 * by the key changing. Today it is built from the clip's text and font size only (the clip has no
 * colour, font or outline fields yet); those are the next additions, and they go in here and in
 * {@link #of(Clip, int)}, with the key picking them up automatically.
 */
public final class TextStyle {

    /** Java's logical font name: maps to a real sans-serif on every OS. */
    public static final String DEFAULT_FONT_FAMILY = "SansSerif";
    public static final float DEFAULT_FONT_SIZE = 48f;
    /** Opaque black: what text has always been drawn in (FFmpeg drawtext's default and the old preview label). */
    public static final int DEFAULT_COLOR_ARGB = 0xFF000000;

    public final String text;
    public final String fontFamily;
    public final float fontSize;
    public final boolean bold;
    public final boolean italic;
    public final int colorArgb;
    /** Lines wrap at this width in canvas units; 0 = never wrap. */
    public final float maxWidth;

    public TextStyle(String text, String fontFamily, float fontSize, boolean bold, boolean italic,
                     int colorArgb, float maxWidth) {
        this.text = text == null ? "" : text;
        this.fontFamily = fontFamily == null || fontFamily.isEmpty() ? DEFAULT_FONT_FAMILY : fontFamily;
        this.fontSize = fontSize > 0f ? fontSize : DEFAULT_FONT_SIZE;
        this.bold = bold;
        this.italic = italic;
        this.colorArgb = colorArgb;
        this.maxWidth = Math.max(0f, maxWidth);
    }

    /**
     * The clip's style. Text wraps at the clip's own width but never wider than the canvas (the old
     * preview wrapped at the clip width; a 1280-wide clip on a narrower portrait canvas must not spill).
     */
    public static TextStyle of(Clip clip, int canvasWidth) {
        float wrap = clip.width > 0 ? Math.min(clip.width, canvasWidth) : canvasWidth;
        return new TextStyle(clip.textContent, DEFAULT_FONT_FAMILY, clip.fontSize, false, false,
                DEFAULT_COLOR_ARGB, wrap);
    }

    /** Cache key: every field that changes the bitmap. */
    public String key() {
        return fontFamily + '\u0001' + fontSize + '\u0001' + bold + '\u0001' + italic + '\u0001'
                + colorArgb + '\u0001' + maxWidth + '\u0001' + text;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TextStyle && ((TextStyle) o).key().equals(key());
    }

    @Override
    public int hashCode() {
        return Objects.hash(key());
    }
}
