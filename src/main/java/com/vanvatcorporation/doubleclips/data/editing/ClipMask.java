package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.vanvatcorporation.doubleclips.constants.Constants;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A clip mask (CapCut style): limits where a clip (video / image / text) is visible. A clip can carry several
 * ({@link Clip#masks}); they are combined in order by each mask's {@link Mode}.
 *
 * <p>The field names and units are iOS's {@code ClipMask} (EditingView+Mask.swift), so a mask means the same thing
 * on every platform. Everything is a fraction, so a mask survives a change of clip or canvas size:
 * <ul>
 * <li>{@code centerX / centerY}: centre of the shape as a fraction of the clip box's width / height (0.5 = middle)</li>
 * <li>{@code width / height}: size as a fraction of the box's SHORTER side</li>
 * <li>{@code rotation}: degrees, clockwise</li>
 * <li>{@code feather}: soft edge, fraction of the shorter side (0 ... 0.5)</li>
 * <li>{@code roundness}: rectangle corners, 0 (square) ... 1 (pill)</li>
 * <li>{@code invert}: show everything outside the shape instead</li>
 * </ul>
 * The mask lives in the clip's OWN box, after the clip's scale and before its rotation / position: it moves, scales
 * and rotates with the clip, and a circle stays a circle however the clip is stretched.
 *
 * <p>Beyond iOS: {@link #mode} (how a later mask combines with the ones before it) and the mask's own
 * {@link #keys}. iOS keeps one mask as {@code mask} + {@code maskKeys} on the clip; those keys are still read (as the
 * first mask) when a project that has them is loaded, see {@link Clip#filterNullAfterLoad()}.
 *
 * <p>Plain Java, no JavaFX / GL: the maths here is shared by the renderer, the panel and (later) the canvas handles.
 */
public class ClipMask implements Serializable {

    public enum Shape {
        @SerializedName("linear") LINEAR("Linear", false, false),
        @SerializedName("mirror") MIRROR("Mirror", false, true),
        @SerializedName("circle") CIRCLE("Circle", true, true),
        @SerializedName("rectangle") RECTANGLE("Rectangle", true, true),
        @SerializedName("heart") HEART("Heart", true, true),
        @SerializedName("star") STAR("Star", true, true);

        public final String title;
        /** Linear has no extent; mirror only a thickness. */
        public final boolean hasWidth, hasHeight;

        Shape(String title, boolean hasWidth, boolean hasHeight) {
            this.title = title;
            this.hasWidth = hasWidth;
            this.hasHeight = hasHeight;
        }
    }

    /** How a mask combines with the result of the masks before it (the first mask always just defines the area). */
    public enum Mode {
        @SerializedName("add") ADD("Add"),
        @SerializedName("subtract") SUBTRACT("Subtract"),
        @SerializedName("intersect") INTERSECT("Intersect");

        public final String title;
        Mode(String title) { this.title = title; }
    }

    /** The animatable parameters, by iOS's {@code MaskParam} raw names; values are in the panel's units. */
    public enum Param {
        X("x", "Position X", -50f, 150f),
        Y("y", "Position Y", -50f, 150f),
        WIDTH("width", "Width", 1f, 200f),
        HEIGHT("height", "Height", 1f, 200f),
        ROTATION("rotation", "Rotation", -180f, 180f),
        FEATHER("feather", "Feather", 0f, 100f),
        ROUNDNESS("roundness", "Corner", 0f, 100f);

        public final String raw, title;
        public final float min, max;

        /** The parameter with this raw name (as keyframes store them), or null. */
        public static Param byRaw(String raw) {
            for (Param p : values()) if (p.raw.equals(raw)) return p;
            return null;
        }

        Param(String raw, String title, float min, float max) {
            this.raw = raw;
            this.title = title;
            this.min = min;
            this.max = max;
        }

        /** The row title for a shape: a mirror's height is its thickness. */
        public String titleFor(Shape shape) {
            return this == HEIGHT && shape == Shape.MIRROR ? "Thickness" : title;
        }

        /** Percent of the clip's box (position) / of its shorter side (size), degrees, 0...100 (feather, corner). */
        public float get(ClipMask m) {
            switch (this) {
                case X: return m.centerX * 100f;
                case Y: return m.centerY * 100f;
                case WIDTH: return m.width * 100f;
                case HEIGHT: return m.height * 100f;
                case ROTATION: return m.rotation;
                case FEATHER: return m.feather * 200f;
                default: return m.roundness * 100f;
            }
        }

        public void set(ClipMask m, float value) {
            float v = Math.max(min, Math.min(max, value));
            switch (this) {
                case X: m.centerX = v / 100f; break;
                case Y: m.centerY = v / 100f; break;
                case WIDTH: m.width = v / 100f; break;
                case HEIGHT: m.height = v / 100f; break;
                case ROTATION: m.rotation = v; break;
                case FEATHER: m.feather = v / 200f; break;
                default: m.roundness = v / 100f; break;
            }
        }
    }

    /**
     * One keyframe of a mask, at {@code time} seconds into the clip. {@code values} holds only the parameters this
     * keyframe animates (Param raw name -> value in the panel's units), so a keyframe on Position X leaves the rest
     * alone. Same shape as iOS's {@code MaskKeyframe}.
     */
    public static class MaskKeyframe implements Serializable {
        @Expose public float time;
        @Expose public EasingType easing = EasingType.LINEAR;
        @Expose public Map<String, Float> values = new HashMap<>();

        public MaskKeyframe() {}

        public MaskKeyframe(MaskKeyframe other) {
            this.time = other.time;
            this.easing = other.easing;
            this.values = other.values == null ? new HashMap<>() : new HashMap<>(other.values);
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MaskKeyframe)) return false;
            MaskKeyframe k = (MaskKeyframe) o;
            return Float.compare(time, k.time) == 0 && easing == k.easing && java.util.Objects.equals(values, k.values);
        }

        @Override public int hashCode() { return java.util.Objects.hash(time, easing, values); }
    }

    @Expose public Shape shape = Shape.CIRCLE;
    @Expose public Mode mode = Mode.ADD;
    @Expose public float centerX = 0.5f;
    @Expose public float centerY = 0.5f;
    @Expose public float width = 0.6f;
    @Expose public float height = 0.6f;
    @Expose public float rotation = 0f;
    @Expose public float feather = 0f;
    @Expose public float roundness = 0f;
    @Expose public boolean invert = false;
    /** This mask's keyframes (sorted by time); empty = every parameter is its static value above. */
    @Expose public List<MaskKeyframe> keys = new ArrayList<>();

    public ClipMask() {}

    /** A new mask of a shape with that shape's default size, centred. */
    public ClipMask(Shape shape) {
        applyDefaultSize(shape);
    }

    public ClipMask(ClipMask other) {
        this.shape = other.shape;
        this.mode = other.mode;
        this.centerX = other.centerX;
        this.centerY = other.centerY;
        this.width = other.width;
        this.height = other.height;
        this.rotation = other.rotation;
        this.feather = other.feather;
        this.roundness = other.roundness;
        this.invert = other.invert;
        this.keys = new ArrayList<>();
        if (other.keys != null) for (MaskKeyframe k : other.keys) this.keys.add(new MaskKeyframe(k));
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClipMask)) return false;
        ClipMask m = (ClipMask) o;
        return shape == m.shape && mode == m.mode && invert == m.invert
                && Float.compare(centerX, m.centerX) == 0 && Float.compare(centerY, m.centerY) == 0
                && Float.compare(width, m.width) == 0 && Float.compare(height, m.height) == 0
                && Float.compare(rotation, m.rotation) == 0 && Float.compare(feather, m.feather) == 0
                && Float.compare(roundness, m.roundness) == 0 && java.util.Objects.equals(keys, m.keys);
    }

    @Override public int hashCode() {
        return java.util.Objects.hash(shape, mode, invert, centerX, centerY, width, height, rotation, feather, roundness, keys);
    }

    private void applyDefaultSize(Shape s) {
        shape = s;
        switch (s) {
            case LINEAR: width = 0f; height = 0f; break;
            case MIRROR: width = 0f; height = 0.35f; break;
            case CIRCLE: width = 0.6f; height = 0.6f; break;
            case RECTANGLE: width = 0.8f; height = 0.5f; break;
            default: width = 0.7f; height = 0.7f; break; // heart, star
        }
    }

    /** The same placement as another shape (keeps position, rotation, feather, invert, mode and keys; sizes reset to the shape's default). */
    public void changeShape(Shape newShape) {
        if (newShape == shape) return;
        applyDefaultSize(newShape);
        if (!newShape.hasWidth) roundness = 0f;
    }

    /** Fills in anything a hand-edited / older file left out. */
    public void normalizeAfterLoad() {
        if (shape == null) shape = Shape.CIRCLE;
        if (mode == null) mode = Mode.ADD;
        if (keys == null) keys = new ArrayList<>();
        for (MaskKeyframe k : keys) {
            if (k.values == null) k.values = new HashMap<>();
            if (k.easing == null) k.easing = EasingType.LINEAR;
        }
    }

    // ---- keyframes ----------------------------------------------------------------------

    public boolean isAnimated(Param p) {
        for (MaskKeyframe k : keys) if (k.values.containsKey(p.raw)) return true;
        return false;
    }

    public boolean hasKeys() {
        return keys != null && !keys.isEmpty();
    }

    private MaskKeyframe keyAt(Param p, float local) {
        for (MaskKeyframe k : keys) {
            if (Math.abs(k.time - local) <= Constants.TRACK_CLIPS_MINIMUM_KEYFRAME_SPACE_SECONDS && k.values.containsKey(p.raw)) return k;
        }
        return null;
    }

    /** What a parameter's diamond shows at {@code local} seconds into the clip. */
    public AnimatedProperty.KeyState stateAt(Param p, float local) {
        if (!isAnimated(p)) return AnimatedProperty.KeyState.NONE;
        return keyAt(p, local) != null ? AnimatedProperty.KeyState.AT_PLAYHEAD : AnimatedProperty.KeyState.ANIMATED;
    }

    /** Value of one parameter at {@code local} seconds into the clip (its static value when no keyframe holds it). */
    public float valueAt(Param p, float local) {
        if (!isAnimated(p)) return p.get(this);
        final String raw = p.raw;
        final List<MaskKeyframe> list = keys;
        return KeyframeChannels.valueAt(new KeyframeChannels.Keys() {
            @Override public int size() { return list.size(); }
            @Override public boolean holds(int i) { return list.get(i).values.containsKey(raw); }
            @Override public float time(int i) { return list.get(i).time; }
            @Override public float value(int i) { return list.get(i).values.get(raw); }
            @Override public float ease(int i, float t) {
                EasingType e = list.get(i).easing;
                return AnimatedProperty.ease(t, e == null ? EasingType.LINEAR : e);
            }
        }, local, p.get(this));
    }

    /** A copy with every animated parameter replaced by its value {@code local} seconds into the clip. */
    public ClipMask resolved(float local) {
        ClipMask m = new ClipMask(this);
        m.keys = new ArrayList<>();
        if (hasKeys()) {
            for (Param p : Param.values()) if (isAnimated(p)) p.set(m, valueAt(p, local));
        }
        return m;
    }

    /**
     * Gives {@code p} the value {@code value} at {@code local}: changes its keyframe there, or adds the value to
     * another keyframe at the same time, or creates a keyframe (snapped to the project's frame grid).
     */
    public void setKey(Param p, float local, float value, int frameRate) {
        MaskKeyframe at = keyAt(p, local);
        if (at != null) { at.values.put(p.raw, value); return; }
        float t = local;
        if (frameRate > 0) t = Math.round(local * frameRate) / (float) frameRate;
        for (MaskKeyframe k : keys) {
            if (Math.abs(k.time - t) <= Constants.TRACK_CLIPS_MINIMUM_KEYFRAME_SPACE_SECONDS) { k.values.put(p.raw, value); return; }
        }
        MaskKeyframe created = new MaskKeyframe();
        created.time = t;
        created.easing = easingBefore(p, t);
        created.values.put(p.raw, value);
        keys.add(created);
        keys.sort((a, b) -> Float.compare(a.time, b.time));
    }

    /** A new keyframe splits a curve: it takes the easing of the keyframe before it that holds the parameter. */
    private EasingType easingBefore(Param p, float t) {
        EasingType e = EasingType.LINEAR;
        for (MaskKeyframe k : keys) if (k.time <= t && k.values.containsKey(p.raw) && k.easing != null) e = k.easing;
        return e;
    }

    /**
     * Takes {@code p}'s keyframe at {@code local} away (the keyframe goes too when it held nothing else). When that
     * was the parameter's last keyframe it stays at the value it had, as a plain value.
     */
    public void removeKey(Param p, float local) {
        MaskKeyframe at = keyAt(p, local);
        if (at == null) return;
        Float held = at.values.remove(p.raw);
        if (at.values.isEmpty()) keys.remove(at);
        if (held != null && !isAnimated(p)) p.set(this, held);
    }

    /**
     * Moves a whole keyframe (all the parameters it holds) to {@code newTime} (snapped to the frame grid, kept inside
     * 0...{@code clipDuration}). A keyframe already there is merged in: the moved one's values win.
     */
    public void moveKeyframe(MaskKeyframe k, float newTime, float clipDuration, int frameRate) {
        float t = Math.max(0f, Math.min(clipDuration, newTime));
        if (frameRate > 0) t = Math.round(t * frameRate) / (float) frameRate;
        for (java.util.Iterator<MaskKeyframe> it = keys.iterator(); it.hasNext(); ) {
            MaskKeyframe other = it.next();
            if (other == k || Math.abs(other.time - t) > Constants.TRACK_CLIPS_MINIMUM_KEYFRAME_SPACE_SECONDS) continue;
            for (Map.Entry<String, Float> e : other.values.entrySet()) k.values.putIfAbsent(e.getKey(), e.getValue());
            it.remove();
        }
        k.time = t;
        keys.sort((a, b) -> Float.compare(a.time, b.time));
    }

    /** Removes a whole keyframe; a parameter that had no other keyframe stays at the value it held there. */
    public void removeKeyframe(MaskKeyframe k) {
        if (!keys.remove(k)) return;
        for (Map.Entry<String, Float> e : k.values.entrySet()) {
            Param p = Param.byRaw(e.getKey());
            if (p != null && !isAnimated(p)) p.set(this, e.getValue());
        }
    }

    /** Deep copy of a mask list (null stays null). */
    public static List<ClipMask> copyAll(List<ClipMask> src) {
        if (src == null) return null;
        List<ClipMask> out = new ArrayList<>();
        for (ClipMask m : src) out.add(new ClipMask(m));
        return out;
    }

    /** The diamond: keys the parameter at {@code local} with its current value, or removes that key. */
    public void toggleKey(Param p, float local, int frameRate) {
        if (keyAt(p, local) != null) removeKey(p, local);
        else setKey(p, local, valueAt(p, local), frameRate);
    }

    // ---- what the GPU needs -------------------------------------------------------------

    /** Masks as the fragment shader takes them (see GlCompositor): in clip-box pixels, at most {@link #MAX_MASKS}. */
    public static final int MAX_MASKS = 8;

    public static final class Packed {
        public final int count;
        /** The clip box these pixels are in. */
        public final float boxW, boxH;
        /** Per mask (cx, cy, halfWidth, halfHeight), box pixels, y down. */
        public final float[] a;
        /** Per mask (cos, sin of the rotation, feather sigma in pixels, corner radius in pixels). */
        public final float[] b;
        /** Per mask (shape index, invert 0/1, mode index). */
        public final int[] c;

        Packed(int count, float boxW, float boxH) {
            this.count = count;
            this.boxW = boxW;
            this.boxH = boxH;
            this.a = new float[count * 4];
            this.b = new float[count * 4];
            this.c = new int[count * 3];
        }
    }

    /**
     * The masks already resolved at one moment ({@link #resolved}), packed for the shader for a clip box of
     * {@code boxW} x {@code boxH} pixels; null when there is nothing to draw (no masks, or an empty box).
     */
    public static Packed pack(List<ClipMask> resolved, float boxW, float boxH) {
        if (resolved == null || resolved.isEmpty() || !(boxW >= 1f) || !(boxH >= 1f)) return null;
        int n = Math.min(resolved.size(), MAX_MASKS);
        Packed out = new Packed(n, boxW, boxH);
        float m = Math.min(boxW, boxH);
        for (int i = 0; i < n; i++) {
            ClipMask k = resolved.get(i);
            float hw = Math.max(k.width * m / 2f, 0.5f);
            float hh = Math.max(k.height * m / 2f, 0.5f);
            double rad = Math.toRadians(k.rotation);
            out.a[i * 4] = k.centerX * boxW;
            out.a[i * 4 + 1] = k.centerY * boxH;
            out.a[i * 4 + 2] = hw;
            out.a[i * 4 + 3] = hh;
            out.b[i * 4] = (float) Math.cos(rad);
            out.b[i * 4 + 1] = (float) Math.sin(rad);
            out.b[i * 4 + 2] = Math.max(0f, k.feather) * m * 0.5f; // iOS: the Gaussian sigma
            out.b[i * 4 + 3] = Math.min(Math.max(k.roundness, 0f) * Math.min(hw, hh), Math.min(hw, hh));
            out.c[i * 3] = k.shape.ordinal();
            out.c[i * 3 + 1] = k.invert ? 1 : 0;
            out.c[i * 3 + 2] = k.mode.ordinal();
        }
        return out;
    }
}
