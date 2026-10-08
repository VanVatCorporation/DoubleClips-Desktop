package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The effects an effect clip can have: the four Android defines first, then the iOS starter set (iOS is the
 * source of truth for the list, the titles, the author and the range of the strength slider).
 * <p>
 * An effect clip is an ADJUSTMENT LAYER: it filters everything drawn before it for as long as the clip lasts.
 * Tracks are composited in order (a later track is drawn on top), so clips on tracks after the effect's track
 * are drawn over the result and are not touched.
 */
public final class EffectCatalog {

    private EffectCatalog() {}

    public static final String ENGINE_FFMPEG = "FFMPEG", ENGINE_OPENGL = "OPENGL";
    public static final String BUILTIN_AUTHOR = "@doubleclips";

    public static final class Style {
        public final String key;
        public final String title;
        public final String author;
        /** What the strength slider means ("Strength", "Zoom speed", "Turns"...); null = nothing to scale, no slider. */
        public final String intensityLabel;
        public final float intensityMin, intensityMax;

        Style(String key, String title, String intensityLabel, float min, float max) {
            this.key = key;
            this.title = title;
            this.author = BUILTIN_AUTHOR;
            this.intensityLabel = intensityLabel;
            this.intensityMin = min;
            this.intensityMax = max;
        }

        public boolean hasIntensity() {
            return intensityLabel != null;
        }

        /**
         * The render engines this effect works with, as the Media picker's badges show them. Desktop's FFmpeg
         * export has no effect support (its filter graph never draws effect clips), so every effect is OpenGL only
         * for now; an engine gets added here when its export really draws the effect.
         */
        public List<String> engines() {
            return Collections.singletonList(ENGINE_OPENGL);
        }
    }

    private static Style s(String key, String title) {
        return new Style(key, title, "Strength", 0.2f, 3f);
    }

    private static final List<Style> STYLES;

    static {
        List<Style> list = new ArrayList<>();
        // Android's four
        list.add(s("glitch-pulse", "Glitch Pulse"));
        list.add(new Style("warp-zoom", "Warp Zoom", "Zoom speed", 0.2f, 5f));
        list.add(new Style("lens-flare-surge", "Lens Flare Surge", "Strength", 0.2f, 2f));
        list.add(new Style("spin-burst", "Spinning Burst", "Turns", 0.25f, 4f));
        // iOS starter set: motion, colour, optics, retro, fades
        list.add(s("shake", "Camera Shake"));
        list.add(s("beat-pulse", "Beat Pulse"));
        list.add(s("strobe", "Strobe"));
        list.add(s("flash", "Flash"));
        list.add(s("rgb-shift", "RGB Shift"));
        list.add(s("vhs", "VHS Tape"));
        list.add(s("blur", "Soft Blur"));
        list.add(s("glow", "Dream Glow"));
        list.add(new Style("vignette", "Vignette", "Strength", 0.2f, 2f));
        list.add(new Style("noir", "Noir", "Strength", 0.2f, 1f));
        list.add(new Style("vintage", "Vintage", "Strength", 0.2f, 1f));
        list.add(s("pixelate", "Pixelate"));
        list.add(s("halftone", "Halftone"));
        list.add(new Style("kaleidoscope", "Kaleidoscope", "Speed", 0.2f, 4f));
        list.add(s("twirl", "Twirl"));
        list.add(s("bulge", "Lens Bulge"));
        list.add(new Style("fade-in", "Fade In", null, 1f, 1f));
        list.add(new Style("fade-out", "Fade Out", null, 1f, 1f));
        STYLES = Collections.unmodifiableList(list);
    }

    public static List<Style> styles() {
        return STYLES;
    }

    /** The style for a key, or null (a style from a newer version: the clip is left as it is and draws nothing). */
    public static Style find(String key) {
        if (key == null) return null;
        String k = key.trim();
        for (Style st : STYLES) if (st.key.equals(k)) return st;
        return null;
    }

    /** Position in the catalog, 0-based; -1 if unknown. Shaders switch on this. */
    public static int indexOf(String key) {
        if (key == null) return -1;
        String k = key.trim();
        for (int i = 0; i < STYLES.size(); i++) if (STYLES.get(i).key.equals(k)) return i;
        return -1;
    }

    /** A strength value inside the style's range (a value from another editor, or a hand-edited file, can be anything). */
    public static float clampIntensity(Style style, float value) {
        if (style == null || !style.hasIntensity()) return 1f;
        if (Float.isNaN(value) || Float.isInfinite(value)) return 1f;
        return Math.max(style.intensityMin, Math.min(style.intensityMax, value));
    }
}
