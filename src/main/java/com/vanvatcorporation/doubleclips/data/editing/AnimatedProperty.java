package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.JsonObject;
import com.vanvatcorporation.doubleclips.UnknownKeysHolder;

import com.google.gson.annotations.Expose;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.helper.FrameHelper;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.vanvatcorporation.doubleclips.data.editing.VideoProperties.ValueType;

public class AnimatedProperty implements Serializable, UnknownKeysHolder {
    /** Keys of this object's JSON that no field maps to (another platform's), kept across a save. See {@link UnknownKeysHolder}. */
    private transient JsonObject unknownKeys;
    @Override public JsonObject getUnknownKeys() { return unknownKeys; }
    @Override public void setUnknownKeys(JsonObject keys) { unknownKeys = keys; }

    @Expose
    public List<Keyframe> keyframes = new ArrayList<>();

    public AnimatedProperty() {}

    public AnimatedProperty(AnimatedProperty other) {
        copyUnknownKeysFrom(other);
        if (other != null && other.keyframes != null) {
            // Deep copy: keyframes are edited in place now (per-property keys), a copy must not share them.
            for (Keyframe k : other.keyframes) this.keyframes.add(new Keyframe(k));
        }
    }
    /**
     * Get keyframe at global clip time
     * @param clip the clip that contains keyframe to get its global time
     * @param playheadTime the global time
     * @return keyframe that matched exactly the global time, null if there's no keyframe match the provided global time.
     */
    public Keyframe getKeyframeAtTime(Clip clip, float playheadTime)
    {
        for (Keyframe k : keyframes) {
            //if(k.getGlobalTime(clip) == playheadTime) return k;
            // Adjust tolerance (0.01s)
            // TODO:
            if(Math.abs(k.getGlobalTime(clip) - playheadTime) <= Constants.TRACK_CLIPS_MINIMUM_KEYFRAME_SPACE_SECONDS) return k;
        }
        return null;
    }

    /**
     * Reassign keyframe into the current timeline in respect of framePerSecond
     * @param framePerSecond
     */
    public void reassignKeyframes(int framePerSecond) {
        if (framePerSecond <= 0) return;

        for (Keyframe k : keyframes) {
            double currentTime = k.getLocalTime();

            // 1. Find which frame index we are closest to
            // Example: 3.14 * 30 = 94.2. Round(94.2) = 94.
            long closestFrameIndex = Math.round(currentTime * framePerSecond);

            k.setLocalTime(FrameHelper.calculateToNearestFrame(k.getLocalTime(), framePerSecond));
            k.setLocalFrame(closestFrameIndex);
        }
    }

    public void sortKeyframe() {
        keyframes.sort((o1, o2) -> (Float.compare(o1.getLocalTime(), o2.getLocalTime())));
    }

    /**
     * One property at a global time. Each property is interpolated on its own, through the keyframes that hold it
     * (see {@link KeyframeChannels}); a property no keyframe holds keeps the clip's static value.
     */
    public float getValueAtTime(Clip clip, float playheadTime, ValueType valueType) {
        float staticValue = clip.videoProperties.getValue(valueType);
        if (keyframes.isEmpty()) return staticValue;
        return KeyframeChannels.valueAt(new Reader(valueType), playheadTime - clip.startTime, staticValue);
    }

    /** The keyframes of this clip as one property sees them, for {@link KeyframeChannels#valueAt}. */
    private final class Reader implements KeyframeChannels.Keys {
        private final ValueType type;
        Reader(ValueType type) { this.type = type; }
        @Override public int size() { return keyframes.size(); }
        @Override public boolean holds(int i) { return keyframes.get(i).holds(type); }
        @Override public float time(int i) { return keyframes.get(i).getLocalTime(); }
        @Override public float value(int i) { return keyframes.get(i).value.getValue(type); }
        @Override public float ease(int i, float t) {
            EasingType easing = keyframes.get(i).easing;
            return AnimatedProperty.ease(t, easing == null ? EasingType.LINEAR : easing);
        }
    }

    // ---- per-property keyframes -------------------------------------------------------------

    /** What a property's keyframe diamond shows at the playhead. */
    public enum KeyState {
        /** No keyframe holds the property: it is a plain value. */
        NONE,
        /** The property is animated, but has no keyframe at the playhead (the half-filled diamond). */
        ANIMATED,
        /** A keyframe of the property sits at the playhead (the filled, purple diamond). */
        AT_PLAYHEAD
    }

    /** Number of keyframes that hold the property. */
    public int holderCount(ValueType type) {
        int n = 0;
        for (Keyframe k : keyframes) if (k.holds(type)) n++;
        return n;
    }

    /** Is the property animated, i.e. does at least one keyframe hold it? */
    public boolean isAnimated(ValueType type) {
        return holderCount(type) > 0;
    }

    /** Does any property actually change over time (some property is held by two or more keyframes)? */
    public boolean hasAnimation() {
        for (ValueType t : ValueType.CHANNELS) if (holderCount(t) > 1) return true;
        return false;
    }

    /** The keyframes that hold the property, in time order. */
    public List<Keyframe> keyframesHolding(ValueType type) {
        List<Keyframe> list = new ArrayList<>();
        for (Keyframe k : keyframes) if (k.holds(type)) list.add(k);
        return list;
    }

    /** The keyframe at the playhead that holds the property, null when there is none. */
    public Keyframe getKeyframeAtTime(Clip clip, float playheadTime, ValueType type) {
        for (Keyframe k : keyframes) {
            if (k.holds(type) && Math.abs(k.getGlobalTime(clip) - playheadTime) <= Constants.TRACK_CLIPS_MINIMUM_KEYFRAME_SPACE_SECONDS) return k;
        }
        return null;
    }

    public KeyState stateAt(Clip clip, float playheadTime, ValueType type) {
        if (!isAnimated(type)) return KeyState.NONE;
        return getKeyframeAtTime(clip, playheadTime, type) != null ? KeyState.AT_PLAYHEAD : KeyState.ANIMATED;
    }

    /** The clip's complete look at a global time: every property interpolated on its own (static when not animated). */
    public VideoProperties resolve(Clip clip, float playheadTime) {
        VideoProperties p = new VideoProperties(clip.videoProperties);
        if (keyframes.isEmpty()) return p;
        for (ValueType t : ValueType.CHANNELS) p.setValue(getValueAtTime(clip, playheadTime, t), t);
        // The volume's plain value is the clip's audioVolume (valueVolume only mirrors it): a keyframe made from this look
        // must carry that, not an old "all properties" keyframe's volume, unless the volume really is keyframed.
        if (!VolumeCurve.isAnimated(clip)) p.setValue(clip.getAudioVolume(), ValueType.Volume);
        return p;
    }

    /**
     * Keys one property at the playhead with {@code value}. A keyframe already sitting there (whatever it holds)
     * takes the property over; otherwise a new keyframe is added that holds only this property, carrying the
     * current look and the easing of the keyframe it splits.
     *
     * @return the keyframe that now holds the property
     */
    public Keyframe setKey(Clip clip, float playheadTime, ValueType type, float value, int frameRate) {
        if (type == ValueType.RotInRadians) { type = ValueType.Rot; value = (float) Math.toDegrees(value); }
        Keyframe at = getKeyframeAtTime(clip, playheadTime);
        if (at == null) {
            float local = Math.max(0f, Math.min(playheadTime - clip.startTime, clip.duration));
            at = new Keyframe(local, resolve(clip, playheadTime), easingBefore(type, local));
            at.channels = KeyframeChannels.with(new ArrayList<>(), type.channelName(), true);
            keyframes.add(at);
            sortKeyframe();
            reassignKeyframes(frameRate);
        } else if (!at.holds(type)) {
            at.setHolds(type, true);
        }
        at.value.setValue(value, type);
        return at;
    }

    /**
     * Stops animating the property at the playhead: its keyframe there lets go of it (and is deleted when it
     * holds nothing else). When that was the property's last keyframe it becomes a plain value again, keeping the
     * value it had.
     */
    public void removeKey(Clip clip, float playheadTime, ValueType type) {
        if (type == ValueType.RotInRadians) type = ValueType.Rot;
        Keyframe at = getKeyframeAtTime(clip, playheadTime, type);
        if (at == null) return;
        if (holderCount(type) == 1) clip.videoProperties.setValue(at.value.getValue(type), type);
        at.setHolds(type, false);
        if (at.holdsNothing()) keyframes.remove(at);
    }

    /**
     * Keys every property at the playhead (the toolbar's Keyframe button): the keyframe there, if any, now holds
     * everything; otherwise a new every-property keyframe is added. Either way it carries the current look.
     *
     * @return the keyframe at the playhead
     */
    public Keyframe setAllKeys(Clip clip, float playheadTime, int frameRate) {
        VideoProperties look = resolve(clip, playheadTime);
        Keyframe at = getKeyframeAtTime(clip, playheadTime);
        if (at == null) {
            float local = Math.max(0f, Math.min(playheadTime - clip.startTime, clip.duration));
            at = new Keyframe(local, look, EasingType.LINEAR);
            keyframes.add(at);
            sortKeyframe();
            reassignKeyframes(frameRate);
        } else {
            at.value = look;
            at.channels = null;
        }
        return at;
    }

    /** The diamond: keys the property at the playhead with its current value, or removes that key. */
    public void toggleKey(Clip clip, float playheadTime, ValueType type, int frameRate) {
        if (getKeyframeAtTime(clip, playheadTime, type) != null) {
            removeKey(clip, playheadTime, type);
        } else {
            setKey(clip, playheadTime, type, getValueAtTime(clip, playheadTime, type), frameRate);
        }
    }

    /** A new keyframe splits a curve: it takes the easing of the keyframe before it that holds the property. */
    private EasingType easingBefore(ValueType type, float localTime) {
        EasingType easing = EasingType.LINEAR;
        for (Keyframe k : keyframes) {
            if (k.holds(type) && k.getLocalTime() <= localTime && k.easing != null) easing = k.easing;
        }
        return easing;
    }

    /**
     * Writes a complete look into the clip: each property goes to {@code keyIndex}'s keyframe when that keyframe
     * holds it, to the clip's static value when it is not animated, and is left alone when it is animated by other
     * keyframes (this write is not about it). Used by the preview gizmo's live values, commit and undo.
     */
    public void writeLook(Clip clip, VideoProperties props, int keyIndex) {
        Keyframe key = keyIndex >= 0 && keyIndex < keyframes.size() ? keyframes.get(keyIndex) : null;
        for (ValueType t : ValueType.CHANNELS) {
            if (key != null && key.holds(t)) key.value.setValue(props.getValue(t), t);
            else if (!isAnimated(t)) clip.videoProperties.setValue(props.getValue(t), t);
        }
    }

    private float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }


        /** The curve of one easing: 0..1 in, 0..1 out (NONE gives 0 = hold until the next keyframe). Public so the easing graph draws exactly this. */
        public static float ease(float t, EasingType type) {
            switch (type) {
                // None
                case NONE: return 0;

                // Linear
                case LINEAR: return t;

                // Sine
                case EASE_IN_SINE: return (float)(1 - Math.cos((t * Math.PI) / 2));
                case EASE_OUT_SINE: return (float)(Math.sin((t * Math.PI) / 2));
                case EASE_IN_OUT_SINE: return (float)(-(Math.cos(Math.PI * t) - 1) / 2);

                // Quadratic
                case EASE_IN_QUAD: return t * t;
                case EASE_OUT_QUAD: return (float)(1 - (1 - t) * (1 - t));
                case EASE_IN_OUT_QUAD: return t < 0.5f ? 2 * t * t : (float)(1 - Math.pow(-2 * t + 2, 2) / 2);

                // Cubic
                case EASE_IN_CUBIC: return t * t * t;
                case EASE_OUT_CUBIC: return (float)(1 - Math.pow(1 - t, 3));
                case EASE_IN_OUT_CUBIC: return t < 0.5f ? 4 * t * t * t : (float)(1 - Math.pow(-2 * t + 2, 3) / 2);

                // Quartic
                case EASE_IN_QUART: return t * t * t * t;
                case EASE_OUT_QUART: return (float)(1 - Math.pow(1 - t, 4));
                case EASE_IN_OUT_QUART: return t < 0.5f ? 8 * t * t * t * t : (float)(1 - Math.pow(-2 * t + 2, 4) / 2);

                // Quintic
                case EASE_IN_QUINT: return t * t * t * t * t;
                case EASE_OUT_QUINT: return (float)(1 - Math.pow(1 - t, 5));
                case EASE_IN_OUT_QUINT: return t < 0.5f ? 16 * t * t * t * t * t : (float)(1 - Math.pow(-2 * t + 2, 5) / 2);

                // Exponential
                case EASE_IN_EXPO: return (t == 0) ? 0 : (float)Math.pow(2, 10 * t - 10);
                case EASE_OUT_EXPO: return (t == 1) ? 1 : (float)(1 - Math.pow(2, -10 * t));
                case EASE_IN_OUT_EXPO:
                    if (t == 0) return 0;
                    if (t == 1) return 1;
                    return t < 0.5f
                            ? (float)(Math.pow(2, 20 * t - 10) / 2)
                            : (float)((2 - Math.pow(2, -20 * t + 10)) / 2);

                // Circular
                case EASE_IN_CIRC: return (float)(1 - Math.sqrt(1 - t * t));
                case EASE_OUT_CIRC: return (float)(Math.sqrt(1 - Math.pow(t - 1, 2)));
                case EASE_IN_OUT_CIRC:
                    return t < 0.5f
                            ? (float)((1 - Math.sqrt(1 - Math.pow(2 * t, 2))) / 2)
                            : (float)((Math.sqrt(1 - Math.pow(-2 * t + 2, 2)) + 1) / 2);

                // Back
                case EASE_IN_BACK: {
                    float c1 = 1.70158f;
                    float c3 = c1 + 1;
                    return c3 * t * t * t - c1 * t * t;
                }
                case EASE_OUT_BACK: {
                    float c1 = 1.70158f;
                    float c3 = c1 + 1;
                    return (float)(1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2));
                }
                case EASE_IN_OUT_BACK: {
                    float c1 = 1.70158f;
                    float c2 = c1 * 1.525f;
                    return t < 0.5f
                            ? (float)(Math.pow(2 * t, 2) * ((c2 + 1) * 2 * t - c2)) / 2
                            : (float)((Math.pow(2 * t - 2, 2) * ((c2 + 1) * (t * 2 - 2) + c2) + 2) / 2);
                }

                // Elastic
                case EASE_IN_ELASTIC:
                    if (t == 0) return 0;
                    if (t == 1) return 1;
                    return (float)(-Math.pow(2, 10 * t - 10) * Math.sin((t * 10 - 10.75) * ((2 * Math.PI) / 3)));
                case EASE_OUT_ELASTIC:
                    if (t == 0) return 0;
                    if (t == 1) return 1;
                    return (float)(Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * ((2 * Math.PI) / 3)) + 1);
                case EASE_IN_OUT_ELASTIC:
                    if (t == 0) return 0;
                    if (t == 1) return 1;
                    return t < 0.5f
                            ? (float)(-(Math.pow(2, 20 * t - 10) * Math.sin((20 * t - 11.125) * ((2 * Math.PI) / 4.5))) / 2)
                            : (float)((Math.pow(2, -20 * t + 10) * Math.sin((20 * t - 11.125) * ((2 * Math.PI) / 4.5))) / 2 + 1);

                // Bounce
                case EASE_IN_BOUNCE: return 1 - ease(1 - t, EasingType.EASE_OUT_BOUNCE);
                case EASE_OUT_BOUNCE:
                    if (t < 1 / 2.75f) {
                        return 7.5625f * t * t;
                    } else if (t < 2 / 2.75f) {
                        t -= 1.5f / 2.75f;
                        return 7.5625f * t * t + 0.75f;
                    } else if (t < 2.5 / 2.75) {
                        t -= 2.25f / 2.75f;
                        return 7.5625f * t * t + 0.9375f;
                    } else {
                        t -= 2.625f / 2.75f;
                        return 7.5625f * t * t + 0.984375f;
                    }
                case EASE_IN_OUT_BOUNCE:
                    return t < 0.5f
                            ? (1 - ease(1 - 2 * t, EasingType.EASE_OUT_BOUNCE)) / 2
                            : (1 + ease(2 * t - 1, EasingType.EASE_OUT_BOUNCE)) / 2;

                default: return t;

        }
    }
}
