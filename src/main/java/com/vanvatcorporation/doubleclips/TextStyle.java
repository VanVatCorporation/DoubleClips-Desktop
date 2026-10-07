package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.TextStyleData;
import com.vanvatcorporation.doubleclips.helper.IOHelper;

import java.io.File;

/**
 * Everything the rendered text depends on, as one immutable value. Equal styles give the same bitmap,
 * so {@link #key()} is the cache key for layouts and GL textures, and a style change is detected just
 * by the key changing. New text settings are added here, in {@link #of}, and in the key - nowhere else
 * has to know about them.
 */
public final class TextStyle {

    /** Java's logical font name: maps to a real sans-serif on every OS. */
    public static final String DEFAULT_FONT_FAMILY = "SansSerif";
    public static final float DEFAULT_FONT_SIZE = 48f;
    /** Opaque black: what text has always been drawn in (FFmpeg drawtext's default and the old preview label). */
    public static final int DEFAULT_COLOR_ARGB = 0xFF000000;

    public static final int ALIGN_LEFT = 0, ALIGN_CENTER = 1, ALIGN_RIGHT = 2;

    public final String text;
    public final String fontFamily;
    /** Absolute path of an imported font file, or null to use {@link #fontFamily}. */
    public final String fontFile;
    public final float fontSize;
    public final boolean bold;
    public final boolean italic;
    public final int colorArgb;
    /** Outline thickness in canvas units, drawn outside the letters; 0 = none. */
    public final float outlineWidth;
    public final int outlineColorArgb;
    public final int align;
    /** Lines wrap at this width in canvas units; 0 = never wrap. */
    public final float maxWidth;
    /**
     * True when {@link #maxWidth} is the width of the TEXT (a style's "wrap at" fraction of the canvas, as iOS
     * does it); false when it is the whole box including its margin (old clips: wrap at the clip's width).
     */
    public final boolean wrapExcludesMargin;
    /** Extra space after each letter and between lines, in canvas units (can be negative). */
    public final float letterSpacing;
    public final float lineSpacing;
    /** Drop shadow: shown when blur > 0 or the offset is not zero. Blur radius and offset in canvas units. */
    public final float shadowBlur, shadowOffsetX, shadowOffsetY;
    public final int shadowArgb;
    /** A box behind the text, shown when its alpha is above 0. */
    public final int backgroundArgb;
    public final float backgroundPadding, backgroundRadius;

    public TextStyle(String text, String fontFamily, String fontFile, float fontSize, boolean bold, boolean italic,
                     int colorArgb, float outlineWidth, int outlineColorArgb, int align, float maxWidth) {
        this(text, fontFamily, fontFile, fontSize, bold, italic, colorArgb, outlineWidth, outlineColorArgb, align, maxWidth,
                false, 0f, 0f, 0f, 0f, 0f, 0x99000000, 0x00000000, 0f, 0f);
    }

    public TextStyle(String text, String fontFamily, String fontFile, float fontSize, boolean bold, boolean italic,
                     int colorArgb, float outlineWidth, int outlineColorArgb, int align, float maxWidth,
                     boolean wrapExcludesMargin, float letterSpacing, float lineSpacing,
                     float shadowBlur, float shadowOffsetX, float shadowOffsetY, int shadowArgb,
                     int backgroundArgb, float backgroundPadding, float backgroundRadius) {
        this.text = text == null ? "" : text;
        this.fontFamily = fontFamily == null || fontFamily.isEmpty() ? DEFAULT_FONT_FAMILY : fontFamily;
        this.fontFile = fontFile == null || fontFile.isEmpty() ? null : fontFile;
        this.fontSize = fontSize > 0f ? fontSize : DEFAULT_FONT_SIZE;
        this.bold = bold;
        this.italic = italic;
        this.colorArgb = colorArgb;
        this.outlineWidth = Math.max(0f, outlineWidth);
        this.outlineColorArgb = outlineColorArgb;
        this.align = align < ALIGN_LEFT || align > ALIGN_RIGHT ? ALIGN_LEFT : align;
        this.maxWidth = Math.max(0f, maxWidth);
        this.wrapExcludesMargin = wrapExcludesMargin;
        this.letterSpacing = letterSpacing;
        this.lineSpacing = lineSpacing;
        this.shadowBlur = Math.max(0f, shadowBlur);
        this.shadowOffsetX = shadowOffsetX;
        this.shadowOffsetY = shadowOffsetY;
        this.shadowArgb = shadowArgb;
        this.backgroundArgb = backgroundArgb;
        this.backgroundPadding = Math.max(0f, backgroundPadding);
        this.backgroundRadius = Math.max(0f, backgroundRadius);
    }

    public boolean hasShadow() {
        return shadowBlur > 0f || shadowOffsetX != 0f || shadowOffsetY != 0f;
    }

    /** A box is drawn behind the text (its colour is not fully transparent). */
    public boolean hasBackground() {
        return (backgroundArgb >>> 24) != 0;
    }

    /** Plain text with no outline: handy for tests and callers that only care about size and colour. */
    public TextStyle(String text, String fontFamily, float fontSize, boolean bold, boolean italic,
                     int colorArgb, float maxWidth) {
        this(text, fontFamily, null, fontSize, bold, italic, colorArgb, 0f, DEFAULT_COLOR_ARGB, ALIGN_LEFT, maxWidth);
    }

    /** The clip's style with no imported fonts available (a font file setting is ignored). */
    public static TextStyle of(Clip clip, int canvasWidth) {
        return of(clip, canvasWidth, null);
    }

    /**
     * The clip's style. Text wraps at the clip's own width but never wider than the canvas (the old
     * preview wrapped at the clip width; a 1280-wide clip on a narrower portrait canvas must not spill).
     *
     * @param fontsDir the project's Fonts folder, where imported font files live; null if there is none
     */
    public static TextStyle of(Clip clip, int canvasWidth, String fontsDir) {
        TextStyleData d = clip.effectiveTextStyle();
        String file = null;
        if (d.fontFile != null && !d.fontFile.isEmpty() && fontsDir != null) {
            // Only the file NAME is stored; never let a hand-edited project point outside the Fonts folder.
            file = new File(fontsDir, new File(d.fontFile).getName()).getPath();
        }
        // wrapWidth > 0: a fraction of the canvas, as iOS does it (it limits the text itself, not the margin).
        // wrapWidth < 0: an old clip, wraps at its own width but never wider than the canvas (the old preview
        // wrapped at the clip width; a 1280-wide clip on a narrower portrait canvas must not spill).
        // wrapWidth == 0: never wrap.
        float wrap;
        boolean excludesMargin = false;
        if (d.wrapWidth > 0.01f) {
            wrap = Math.max(20f, Math.min(d.wrapWidth, 1f) * canvasWidth);
            excludesMargin = true;
        } else if (d.wrapWidth < 0f) {
            wrap = clip.width > 0 ? Math.min(clip.width, canvasWidth) : canvasWidth;
        } else {
            wrap = 0f;
        }
        return new TextStyle(clip.textContent, d.fontName, file, clip.fontSize, d.bold, d.italic,
                parseRgba(d.colorHex, 0xFFFFFFFF), d.outlineWidth,
                parseRgba(d.outlineColorHex, DEFAULT_COLOR_ARGB), d.alignmentIndex(), wrap,
                excludesMargin, d.letterSpacing, d.lineSpacing,
                d.shadowBlur, d.shadowOffsetX, d.shadowOffsetY, parseRgba(d.shadowColorHex, 0x99000000),
                parseRgba(d.backgroundColorHex, 0x00000000), d.backgroundPadding, d.backgroundRadius);
    }

    /** "#RRGGBB" or "#RRGGBBAA" (the iOS format) to ARGB; anything unreadable gives {@code fallback}. */
    public static int parseRgba(String hex, int fallback) {
        if (hex == null) return fallback;
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        try {
            if (h.length() == 6) return 0xFF000000 | (int) Long.parseLong(h, 16);
            if (h.length() == 8) {
                long v = Long.parseLong(h, 16);
                return (int) (((v & 0xFF) << 24) | (v >>> 8));
            }
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    /** The project's Fonts folder (where imported font files are copied to). */
    public static String fontsDirOf(ProjectData project) {
        return project == null ? null : IOHelper.CombinePath(project.getProjectPath(), "Fonts");
    }

    /** "#RRGGBB" (or "RRGGBB", or "#AARRGGBB") to opaque-by-default ARGB; anything unreadable gives {@code fallback}. */
    public static int parseColor(String hex, int fallback) {
        if (hex == null) return fallback;
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        try {
            if (h.length() == 6) return 0xFF000000 | (int) Long.parseLong(h, 16);
            if (h.length() == 8) return (int) Long.parseLong(h, 16);
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    /** ARGB to "#RRGGBB" (the alpha is dropped: text opacity is the clip's own opacity). */
    public static String toHex(int argb) {
        return String.format("#%06X", argb & 0xFFFFFF);
    }

    /** Cache key: every field that changes the bitmap. */
    public String key() {
        return fontFamily + '\u0001' + fontFile + '\u0001' + fontSize + '\u0001' + bold + '\u0001' + italic + '\u0001'
                + colorArgb + '\u0001' + outlineWidth + '\u0001' + outlineColorArgb + '\u0001' + align + '\u0001'
                + maxWidth + '\u0001' + wrapExcludesMargin + '\u0001' + letterSpacing + '\u0001' + lineSpacing + '\u0001'
                + shadowBlur + '\u0001' + shadowOffsetX + '\u0001' + shadowOffsetY + '\u0001' + shadowArgb + '\u0001'
                + backgroundArgb + '\u0001' + backgroundPadding + '\u0001' + backgroundRadius + '\u0001' + text;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TextStyle && ((TextStyle) o).key().equals(key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }
}
