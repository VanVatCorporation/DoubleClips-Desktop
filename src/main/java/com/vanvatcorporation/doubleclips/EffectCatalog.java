package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The effects an effect clip can have. iOS is the source of truth for the list, the titles, the author and the range of the
 * strength slider; the list comes from the bundled {@code animations/effects/*.json} files ({@link EffectStyleLoader}), in iOS's
 * picker order. Every effect is only its JSON: channels for the whole-picture look and filter ops for the rest, drawn by
 * GlCompositor's effect pipeline. No effect is written in code.
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
        /** The loaded JSON behind the style. */
        public final EffectStyle data;

        Style(String key, String title, String author, String intensityLabel, float min, float max, EffectStyle data) {
            this.key = key;
            this.title = title;
            this.author = author;
            this.intensityLabel = intensityLabel;
            this.intensityMin = min;
            this.intensityMax = max;
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

    /**
     * The styles to offer, in iOS's picker order (see {@link EffectStyleCatalog}): everything the bundled JSON defines.
     */
    public static List<Style> styles() {
        ensureLoaded();
        List<EffectStyle> loaded = EffectStyleCatalog.ordered(EffectStyleLoader.list());
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

    /** A style as the list shows it: the JSON's text and slider range. */
    private static Style fromData(EffectStyle es) {
        return new Style(es.getId(), es.getName(), es.getAuthor(), es.getIntensityLabel(),
                es.hasIntensity() ? es.getIntensityMin() : 1f, es.hasIntensity() ? es.getIntensityMax() : 1f, es);
    }

    /**
     * The style for a key, or null (a style from a newer version: the clip is left as it is and draws nothing). Older projects
     * store "shake" for what is now "camera-shake" (iOS keeps the same alias), so that key finds the camera shake.
     */
    public static Style find(String key) {
        if (key == null) return null;
        ensureLoaded();
        EffectStyle es = EffectStyleLoader.get(key);
        return es == null ? null : fromData(es);
    }

    /** A strength value inside the style's range (a value from another editor, or a hand-edited file, can be anything). */
    public static float clampIntensity(Style style, float value) {
        if (style == null || !style.hasIntensity()) return 1f;
        if (Float.isNaN(value) || Float.isInfinite(value)) return 1f;
        return Math.max(style.intensityMin, Math.min(style.intensityMax, value));
    }
}
