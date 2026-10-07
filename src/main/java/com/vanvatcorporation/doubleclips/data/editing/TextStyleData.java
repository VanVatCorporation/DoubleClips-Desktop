package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.annotations.Expose;

import java.util.ArrayList;
import java.util.List;

/**
 * What a text clip looks like, stored in ONE optional JSON object on the clip, {@code "textStyle"}. The same
 * class is a text style PRESET (see TextPresets): a preset is just a style that also carries an id and a name.
 * The first block of fields is exactly the iOS format (iOS is the source of truth): same names, same
 * meaning, same units (canvas pixels), colours as "#RRGGBB" or "#RRGGBBAA". Every field is optional on read.
 * <p>
 * The last block is desktop / Android only (imported font file, per-unit animation). iOS ignores keys it
 * doesn't know, but it only writes the keys it does know, so those extras are dropped if the clip is saved
 * by iOS.
 */
public class TextStyleData {

    public static final String DEFAULT_COLOR_HEX = "#FFFFFF";

    // ---- the iOS format --------------------------------------------------------------------

    /** PostScript name (iOS) or family name (desktop) of the font; "" = the system / default font. */
    @Expose public String fontName = "";
    @Expose public boolean bold = false;
    @Expose public boolean italic = false;
    @Expose public String colorHex = DEFAULT_COLOR_HEX;
    /** "left", "center" or "right". */
    @Expose public String alignment = "center";
    /** Extra space after each letter / between lines, in canvas pixels (can be negative). */
    @Expose public float letterSpacing = 0f;
    @Expose public float lineSpacing = 0f;
    /** Outline drawn outside the letters; 0 = none. */
    @Expose public float outlineWidth = 0f;
    @Expose public String outlineColorHex = "#000000";
    /** Drop shadow: shown when blur > 0 or the offset isn't zero. */
    @Expose public float shadowBlur = 0f;
    @Expose public float shadowOffsetX = 0f;
    @Expose public float shadowOffsetY = 0f;
    @Expose public String shadowColorHex = "#00000099";
    /** A box behind the text, shown when its alpha is above 0. */
    @Expose public String backgroundColorHex = "#00000000";
    @Expose public float backgroundPadding = 0f;
    @Expose public float backgroundRadius = 0f;
    /**
     * Wrap long lines at this fraction of the canvas width; 0 = never wrap. A NEGATIVE value is the desktop
     * marker for an old clip that has not been given a width yet: wrap at the clip's own width, as it always did.
     */
    @Expose public float wrapWidth = 0f;

    // ---- desktop / Android: where the values came from, and what they need --------------------
    // Same names as Android's TextStyle, so a preset (and its text-styles.json) means the same on both.

    /** Preset id this look was picked from ("classic", "user-1700000000000"), or null for hand-made values ("Custom"). */
    @Expose public String id = null;
    @Expose public String name = null;
    /** Community attribution such as "@username"; null for built-ins and local styles. */
    @Expose public String author = null;
    /** Engines this look renders on ("FFMPEG", "OPENGL"); null or empty = both. */
    @Expose public List<String> supportedEngines = null;
    /** Animation ids a preset puts on the clip's In / Out slots when picked; null = leave the clip's own. */
    @Expose public String inAnimationId = null;
    @Expose public String outAnimationId = null;

    // ---- desktop / Android only ------------------------------------------------------------

    /** File name of an imported font inside the project's Fonts folder; null = use {@link #fontName}. */
    @Expose public String fontFile = null;
    /** Per-unit animation: "CHARACTER", "WORD" or "LINE" (null = the whole text animates as one). */
    @Expose public String unitMode = null;
    /** Fraction (0..0.95) of the animation window the units are spread over; 0 = the default (0.6). */
    @Expose public float stagger = 0f;
    /** "REVERSE", "CENTER_OUT" or "RANDOM"; null = forward. */
    @Expose public String unitOrder = null;

    // ---- Android's spelling of the same values ---------------------------------------------------
    // Android stores its style under this same "textStyle" key but with these names. Desktop reads them
    // (and folds them into the iOS fields above, see normalized()) and writes both spellings, so a clip
    // looks right on Android as well. All null on a style that has only the iOS fields.

    /** Project-relative font file ("Fonts/Name.ttf") or an absolute path (ignored here); Android's fontFile. */
    @Expose public String fontPath = null;
    /** 0xAARRGGBB as a signed int; Android's colorHex. */
    @Expose public Integer colorArgb = null;
    @Expose public Integer outlineColorArgb = null;
    /** "FORWARD", "REVERSE", "CENTER_OUT" or "RANDOM"; Android's unitOrder. */
    @Expose public String order = null;

    public static final String ENGINE_FFMPEG = "FFMPEG", ENGINE_OPENGL = "OPENGL";

    public TextStyleData() {}

    public TextStyleData(TextStyleData o) {
        fontName = o.fontName;
        bold = o.bold;
        italic = o.italic;
        colorHex = o.colorHex;
        alignment = o.alignment;
        letterSpacing = o.letterSpacing;
        lineSpacing = o.lineSpacing;
        outlineWidth = o.outlineWidth;
        outlineColorHex = o.outlineColorHex;
        shadowBlur = o.shadowBlur;
        shadowOffsetX = o.shadowOffsetX;
        shadowOffsetY = o.shadowOffsetY;
        shadowColorHex = o.shadowColorHex;
        backgroundColorHex = o.backgroundColorHex;
        backgroundPadding = o.backgroundPadding;
        backgroundRadius = o.backgroundRadius;
        wrapWidth = o.wrapWidth;
        fontFile = o.fontFile;
        unitMode = o.unitMode;
        stagger = o.stagger;
        unitOrder = o.unitOrder;
        id = o.id;
        name = o.name;
        author = o.author;
        supportedEngines = o.supportedEngines == null ? null : new ArrayList<>(o.supportedEngines);
        inAnimationId = o.inAnimationId;
        outAnimationId = o.outAnimationId;
        fontPath = o.fontPath;
        colorArgb = o.colorArgb;
        outlineColorArgb = o.outlineColorArgb;
        order = o.order;
    }

    // ---- Android spelling <-> iOS spelling ---------------------------------------------------------

    /** True when any of Android's own spellings is present. */
    public boolean hasAndroidFields() {
        return colorArgb != null || outlineColorArgb != null || fontPath != null || order != null;
    }

    /**
     * This style in the iOS spelling only: Android's colours, font and order folded into colorHex,
     * outlineColorHex, fontFile and unitOrder. Returns {@code this} when there is nothing to fold.
     * (Android's stagger 0 means "all together"; desktop's 0 means "default", so it becomes a tiny value.)
     */
    public TextStyleData normalized() {
        if (!hasAndroidFields()) return this;
        TextStyleData d = new TextStyleData(this);
        if (colorArgb != null) d.colorHex = hexOf(colorArgb);
        if (outlineColorArgb != null) d.outlineColorHex = hexOf(outlineColorArgb);
        if (fontPath != null) {
            // "Fonts/Name.ttf" is a font of the project; an absolute path is a font of the phone and means nothing here.
            String p = fontPath.replace('\\', '/');
            d.fontFile = p.startsWith("Fonts/") && p.length() > 6 ? p.substring(6) : null;
        }
        if (order != null && unitOrder == null) d.unitOrder = "FORWARD".equals(order) ? null : order;
        if ("NONE".equals(d.unitMode)) d.unitMode = null;
        if (d.unitMode != null && d.stagger <= 0f) d.stagger = 0.001f;
        d.fontPath = null;
        d.colorArgb = null;
        d.outlineColorArgb = null;
        d.order = null;
        return d;
    }

    /**
     * Fills in Android's spelling (and the engines this look needs) from the iOS fields, so the clip reads
     * correctly on both. Call after every change. Does not touch id / name / author.
     */
    public TextStyleData withAndroidFields() {
        colorArgb = argbOf(colorHex, 0xFFFFFFFF);
        outlineColorArgb = argbOf(outlineColorHex, 0xFF000000);
        fontPath = fontFile != null && !fontFile.isEmpty() ? "Fonts/" + fontFile : null;
        order = unitOrder == null ? "FORWARD" : unitOrder;
        refreshEngines();
        return this;
    }

    /** Per-unit animation, shadows, boxes and letter / line spacing need the OpenGL engine; the rest renders on both. */
    public void refreshEngines() {
        if (needsOpenGl()) {
            supportedEngines = new ArrayList<>();
            supportedEngines.add(ENGINE_OPENGL);
        } else {
            supportedEngines = null;
        }
    }

    public boolean needsOpenGl() {
        return (unitMode != null && !"NONE".equals(unitMode)) || hasShadow() || hasBackground()
                || letterSpacing != 0f || lineSpacing != 0f;
    }

    /** Whether a render engine ("FFMPEG" / "OPENGL") can draw this look. */
    public boolean supportsEngine(String engine) {
        if (needsOpenGl() && !ENGINE_OPENGL.equals(engine)) return false;
        return supportedEngines == null || supportedEngines.isEmpty() || supportedEngines.contains(engine);
    }

    /** Forgets which preset this came from: the values are now "Custom". */
    public void clearPreset() {
        id = null;
        name = null;
        author = null;
    }

    /**
     * Same look as {@code preset}? Compares what a preset sets: colours, bold / italic, letter spacing, outline,
     * shadow, box, per-unit animation and the font (only if the preset names one). Layout choices (alignment,
     * line spacing, wrap) and the preset's own id / name are ignored.
     */
    public boolean sameLookAs(TextStyleData preset) {
        if (preset == null) return false;
        TextStyleData a = normalized(), b = preset.normalized();
        return java.util.Objects.equals(a.colorHex.toUpperCase(), b.colorHex.toUpperCase())
                && a.bold == b.bold && a.italic == b.italic
                && near(a.letterSpacing, b.letterSpacing) && near(a.outlineWidth, b.outlineWidth)
                && a.outlineColorHex.equalsIgnoreCase(b.outlineColorHex)
                && near(a.shadowBlur, b.shadowBlur) && near(a.shadowOffsetX, b.shadowOffsetX) && near(a.shadowOffsetY, b.shadowOffsetY)
                && a.shadowColorHex.equalsIgnoreCase(b.shadowColorHex)
                && a.backgroundColorHex.equalsIgnoreCase(b.backgroundColorHex)
                && near(a.backgroundPadding, b.backgroundPadding) && near(a.backgroundRadius, b.backgroundRadius)
                && java.util.Objects.equals(a.unitMode, b.unitMode) && near(effectiveStagger(a), effectiveStagger(b))
                && java.util.Objects.equals(a.unitOrder, b.unitOrder)
                && (!b.hasFont() || (java.util.Objects.equals(emptyToNull(a.fontName), emptyToNull(b.fontName))
                        && java.util.Objects.equals(a.fontFile, b.fontFile)));
    }

    private boolean hasFont() {
        return (fontName != null && !fontName.isEmpty()) || (fontFile != null && !fontFile.isEmpty());
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-3f;
    }

    private static float effectiveStagger(TextStyleData d) {
        return d.unitMode == null ? 0f : d.stagger > 0f ? d.stagger : 0.6f;
    }

    /** 0xAARRGGBB to "#RRGGBB" (opaque) or "#RRGGBBAA", the iOS spelling. */
    public static String hexOf(int argb) {
        String rgb = String.format("#%06X", argb & 0xFFFFFF);
        int a = argb >>> 24;
        return a == 0xFF ? rgb : rgb + String.format("%02X", a);
    }

    /** "#RRGGBB" / "#RRGGBBAA" to 0xAARRGGBB; {@code fallback} if it is not a colour. */
    public static int argbOf(String hex, int fallback) {
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

    /** True when every field is the same; used to skip an edit that changed nothing. */
    public boolean sameAs(TextStyleData o) {
        return o != null && key().equals(o.key());
    }

    private String key() {
        return String.join("\u0001", String.valueOf(fontName), String.valueOf(bold), String.valueOf(italic), String.valueOf(colorHex),
                String.valueOf(alignment), String.valueOf(letterSpacing), String.valueOf(lineSpacing), String.valueOf(outlineWidth),
                String.valueOf(outlineColorHex), String.valueOf(shadowBlur), String.valueOf(shadowOffsetX), String.valueOf(shadowOffsetY),
                String.valueOf(shadowColorHex), String.valueOf(backgroundColorHex), String.valueOf(backgroundPadding),
                String.valueOf(backgroundRadius), String.valueOf(wrapWidth), String.valueOf(fontFile), String.valueOf(unitMode),
                String.valueOf(stagger), String.valueOf(unitOrder), String.valueOf(id), String.valueOf(name), String.valueOf(author),
                String.valueOf(supportedEngines), String.valueOf(inAnimationId), String.valueOf(outAnimationId),
                String.valueOf(fontPath), String.valueOf(colorArgb), String.valueOf(outlineColorArgb), String.valueOf(order));
    }

    /** 0 = left, 1 = center, 2 = right (anything else reads as center, iOS's default). */
    public int alignmentIndex() {
        return "left".equals(alignment) ? 0 : "right".equals(alignment) ? 2 : 1;
    }

    public void setAlignmentIndex(int index) {
        alignment = index == 0 ? "left" : index == 2 ? "right" : "center";
    }

    public boolean hasShadow() {
        return shadowBlur > 0f || shadowOffsetX != 0f || shadowOffsetY != 0f;
    }

    /** True when a background box is drawn (its colour is not fully transparent). */
    public boolean hasBackground() {
        return colorAlpha(backgroundColorHex) > 0.001f;
    }

    /** The alpha (0..1) of "#RRGGBB" (1) or "#RRGGBBAA"; 1 for anything unreadable. */
    public static float colorAlpha(String hex) {
        if (hex == null) return 1f;
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() != 8) return 1f;
        try {
            return Integer.parseInt(h.substring(6, 8), 16) / 255f;
        } catch (NumberFormatException e) {
            return 1f;
        }
    }
}
