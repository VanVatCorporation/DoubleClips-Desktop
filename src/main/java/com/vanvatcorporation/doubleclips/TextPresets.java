package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.editing.AnimationClip;
import com.vanvatcorporation.doubleclips.data.editing.TextStyleData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Text style presets: the ones that ship with the app, and the rules for putting one on a clip.
 * <p>
 * A preset is a {@link TextStyleData} that carries an id and a name. Picking one COPIES its values into the
 * clip (so a project never depends on a preset still being installed); the clip keeps the preset's id / name
 * only to say where the look came from, and loses them as soon as a value is changed by hand ("Custom").
 * The Android presets are here under the same ids and values, plus a few that use what only desktop and iOS
 * can draw (shadow, background box, letter spacing).
 */
public final class TextPresets {

    private TextPresets() {}

    public static final String USER_ID_PREFIX = "user-";

    private static TextStyleData look(String id, String name, String color, String outlineColor, float outlineWidth) {
        TextStyleData d = new TextStyleData();
        d.id = id;
        d.name = name;
        d.colorHex = color;
        d.outlineColorHex = outlineColor;
        d.outlineWidth = outlineWidth;
        return d;
    }

    private static TextStyleData animated(String id, String name, String color, String outlineColor, float outlineWidth,
                                          String unitMode, float stagger, String order, String in, String out) {
        TextStyleData d = look(id, name, color, outlineColor, outlineWidth);
        d.unitMode = unitMode;
        d.stagger = stagger;
        d.unitOrder = "FORWARD".equals(order) ? null : order;
        d.inAnimationId = in;
        d.outAnimationId = out;
        return d;
    }

    /** The presets that ship with the app, in display order. A fresh copy each call. */
    public static List<TextStyleData> builtIns() {
        List<TextStyleData> list = new ArrayList<>();
        // The same looks as Android's built-in styles (same ids).
        list.add(look("classic", "Classic", "#FFFFFF", "#000000", 0f));
        list.add(look("bold-outline", "Bold Outline", "#FFFFFF", "#000000", 4f));
        list.add(look("caption-yellow", "Caption Yellow", "#FFD60A", "#000000", 3f));
        list.add(look("neon-pink", "Neon Pink", "#FF4FD8", "#6A0057", 3f));
        list.add(look("sticker", "Sticker", "#111111", "#FFFFFF", 6f));

        // Shadow, box and spacing: desktop and iOS can draw these, so these presets are OpenGL-only here.
        TextStyleData shadow = look("soft-shadow", "Soft Shadow", "#FFFFFF", "#000000", 0f);
        shadow.shadowBlur = 8f;
        shadow.shadowOffsetY = 4f;
        shadow.shadowColorHex = "#000000B3";
        list.add(shadow);
        TextStyleData label = look("label-box", "Label Box", "#FFFFFF", "#000000", 0f);
        label.backgroundColorHex = "#000000B3";
        label.backgroundPadding = 12f;
        label.backgroundRadius = 10f;
        list.add(label);
        TextStyleData spaced = look("spaced-title", "Spaced Title", "#FFFFFF", "#000000", 0f);
        spaced.bold = true;
        spaced.letterSpacing = 8f;
        list.add(spaced);
        TextStyleData glow = look("neon-glow", "Neon Glow", "#FF4FD8", "#000000", 0f);
        glow.shadowBlur = 18f;
        glow.shadowColorHex = "#FF4FD8CC";
        list.add(glow);

        // Per-unit animation (OpenGL only). Each puts its animation on the clip's In / Out slots.
        list.add(animated("pop-letters", "Pop Letters", "#FFFFFF", "#000000", 3f, "CHARACTER", 0.7f, "FORWARD", "pop-in", "fade-out"));
        list.add(animated("typewriter", "Typewriter", "#E8F5E9", "#1B5E20", 2f, "CHARACTER", 0.9f, "FORWARD", "fade-in", null));
        list.add(animated("drop-words", "Drop Words", "#FFD60A", "#000000", 3f, "WORD", 0.6f, "FORWARD", "drop-in", "rise-out"));
        list.add(animated("rise-lines", "Rise Lines", "#FFFFFF", "#000000", 0f, "LINE", 0.6f, "FORWARD", "rise-in", "fade-out"));
        list.add(animated("spin-letters", "Spin Letters", "#FF4FD8", "#6A0057", 3f, "CHARACTER", 0.6f, "CENTER_OUT", "spin-in", "fade-out"));
        list.add(animated("shuffle", "Shuffle", "#7CE0FF", "#00334D", 3f, "CHARACTER", 0.8f, "RANDOM", "tilt-in", "fade-out"));

        for (TextStyleData d : list) d.withAndroidFields(); // both spellings + the engines each one needs
        return list;
    }

    /** What a new text clip starts as: the "Classic" look (white, no outline). */
    public static TextStyleData defaultStyle() {
        return new TextStyleData(find(builtIns(), "classic"));
    }

    /** The preset with this id among {@code presets}, or null. */
    public static TextStyleData find(List<TextStyleData> presets, String id) {
        if (id == null) return null;
        for (TextStyleData d : presets) if (id.equals(d.id)) return d;
        return null;
    }

    /**
     * The clip's style with {@code preset}'s look put on it. Everything a preset describes is replaced (colours,
     * bold / italic, letter spacing, outline, shadow, box, per-unit animation); the clip's own layout choices
     * (alignment, line spacing, wrap) stay, and so does its font when the preset names none. The result carries
     * the preset's id / name / author.
     */
    public static TextStyleData apply(TextStyleData clipStyle, TextStyleData preset) {
        TextStyleData p = preset.normalized();
        TextStyleData out = new TextStyleData(clipStyle.normalized());
        out.colorHex = p.colorHex;
        out.bold = p.bold;
        out.italic = p.italic;
        out.letterSpacing = p.letterSpacing;
        out.outlineWidth = p.outlineWidth;
        out.outlineColorHex = p.outlineColorHex;
        out.shadowBlur = p.shadowBlur;
        out.shadowOffsetX = p.shadowOffsetX;
        out.shadowOffsetY = p.shadowOffsetY;
        out.shadowColorHex = p.shadowColorHex;
        out.backgroundColorHex = p.backgroundColorHex;
        out.backgroundPadding = p.backgroundPadding;
        out.backgroundRadius = p.backgroundRadius;
        out.unitMode = p.unitMode;
        out.stagger = p.stagger;
        out.unitOrder = p.unitOrder;
        boolean presetHasFont = (p.fontName != null && !p.fontName.isEmpty()) || (p.fontFile != null && !p.fontFile.isEmpty());
        if (presetHasFont) {
            out.fontName = p.fontName;
            out.fontFile = p.fontFile;
        }
        out.inAnimationId = p.inAnimationId;
        out.outAnimationId = p.outAnimationId;
        out.id = p.id;
        out.name = p.name;
        out.author = p.author;
        return out.withAndroidFields();
    }

    /**
     * The animation to put on a clip's In (or Out) slot for a preset, or null to leave the slot alone: the
     * preset names none, it is already that one, or no such animation is installed. The slot keeps its own duration.
     */
    public static AnimationClip slotFor(AnimationClip current, String animationId, Predicate<String> installed) {
        if (animationId == null || animationId.isEmpty() || !installed.test(animationId)) return null;
        if (current != null && animationId.equals(current.type)) return null;
        float duration = current != null && current.duration > 0f ? current.duration : 0.5f;
        return new AnimationClip(animationId, duration);
    }

    /** What the style picker shows for a clip's style: the preset's name while the values still match it, else "Custom". */
    public static String labelOf(TextStyleData clipStyle) {
        return clipStyle.name != null && !clipStyle.name.isEmpty() ? clipStyle.name : "Custom";
    }
}
