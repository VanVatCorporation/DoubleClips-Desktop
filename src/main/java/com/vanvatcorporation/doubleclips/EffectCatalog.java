package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The effects an effect clip can have. iOS is the source of truth for the list, the titles, the author and the range of
 * the strength slider, and the list now comes from the bundled {@code animations/effects/*.json} files ({@link EffectStyleLoader}),
 * in iOS's picker order. Most of them are drawn by this class's built-in shader code (the list below, whose POSITIONS the shader
 * switches on); the ones with no built-in code ({@link Style#dataDriven}) are drawn from their own JSON (channels such as
 * offset / rotation / scale / colour / motion blur, applied to the whole picture).
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
        /**
         * true = no built-in shader code: the effect is drawn from {@link #data} (its channels). false = the shader's
         * built-in look for {@link #key}.
         */
        public final boolean dataDriven;
        /** The loaded JSON behind the style, or null for a style that exists only as built-in code. */
        public final EffectStyle data;

        Style(String key, String title, String intensityLabel, float min, float max) {
            this(key, title, BUILTIN_AUTHOR, intensityLabel, min, max, false, null);
        }

        Style(String key, String title, String author, String intensityLabel, float min, float max, boolean dataDriven, EffectStyle data) {
            this.key = key;
            this.title = title;
            this.author = author;
            this.intensityLabel = intensityLabel;
            this.intensityMin = min;
            this.intensityMax = max;
            this.dataDriven = dataDriven;
            this.data = data;
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

    private static final List<Style> BUILT_INS;

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
        BUILT_INS = Collections.unmodifiableList(list);
    }

    /**
     * The styles to offer, in iOS's picker order (see {@link EffectStyleCatalog}): everything the bundled JSON defines, each one
     * either drawn by the built-in shader code of the same key or - with none - from its own JSON. If the JSON can't be read at
     * all the built-in list stands in, so effects never disappear.
     */
    public static List<Style> styles() {
        ensureLoaded();
        List<EffectStyle> loaded = EffectStyleCatalog.ordered(EffectStyleLoader.list());
        if (loaded.isEmpty()) return BUILT_INS;
        List<Style> out = new ArrayList<>();
        for (EffectStyle es : loaded) out.add(fromData(es));
        return Collections.unmodifiableList(out);
    }

    private static volatile boolean loaded = false;

    /** Registers the bundled effect files once (the render threads ask for a style every frame, so this must stay cheap). */
    private static void ensureLoaded() {
        if (loaded) return;
        ClipAnimationAssets.loadBuiltIns(); // idempotent
        loaded = true;
    }

    /** A style as the list shows it: the JSON's text and slider range, and whether built-in code draws it. */
    private static Style fromData(EffectStyle es) {
        boolean builtIn = builtInIndex(es.getId()) >= 0;
        return new Style(es.getId(), es.getName(), es.getAuthor(), es.getIntensityLabel(),
                es.hasIntensity() ? es.getIntensityMin() : 1f, es.hasIntensity() ? es.getIntensityMax() : 1f,
                !builtIn, es);
    }

    /**
     * The style for a key, or null (a style from a newer version: the clip is left as it is and draws nothing). Older projects
     * store "shake" for what is now "camera-shake" (iOS keeps the same alias), so that key finds the camera shake.
     */
    public static Style find(String key) {
        if (key == null) return null;
        ensureLoaded();
        EffectStyle es = EffectStyleLoader.get(key);
        if (es != null) return fromData(es);
        String k = key.trim();
        for (Style st : BUILT_INS) if (st.key.equals(k)) return st; // the JSON failed to load: the built-in one still draws
        return null;
    }

    /**
     * Position of a BUILT-IN style in the shader's list, 0-based; -1 if the key has no built-in shader code (an unknown key, or a
     * {@link Style#dataDriven} style). The shader switches on this, so the built-in list never changes order.
     */
    public static int indexOf(String key) {
        if (key == null) return -1;
        return builtInIndex(key.trim());
    }

    private static int builtInIndex(String key) {
        for (int i = 0; i < BUILT_INS.size(); i++) if (BUILT_INS.get(i).key.equals(key)) return i;
        return -1;
    }

    /** A strength value inside the style's range (a value from another editor, or a hand-edited file, can be anything). */
    public static float clampIntensity(Style style, float value) {
        if (style == null || !style.hasIntensity()) return 1f;
        if (Float.isNaN(value) || Float.isInfinite(value)) return 1f;
        return Math.max(style.intensityMin, Math.min(style.intensityMax, value));
    }
}
