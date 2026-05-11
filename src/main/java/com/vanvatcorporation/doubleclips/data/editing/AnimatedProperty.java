package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.annotations.Expose;
import com.vanvatcorporation.doubleclips.constants.Constants;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class AnimatedProperty implements Serializable {
    @Expose
    public List<Keyframe> keyframes = new ArrayList<>();

    public AnimatedProperty() {}

    public AnimatedProperty(AnimatedProperty other) {
        if (other != null && other.keyframes != null) {
            this.keyframes.addAll(other.keyframes);
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

            // 2. Convert that frame index back into seconds
            // Example: 94 / 30.0 = 3.1333...
            double snappedTime = (double) closestFrameIndex / framePerSecond;

            k.setLocalTime((float) snappedTime);
        }
    }

    public void sortKeyframe() {
        keyframes.sort((o1, o2) -> (Float.compare(o1.getLocalTime(), o2.getLocalTime())));
    }

    public float getValueAtTime(Clip clip, float playheadTime, VideoProperties.ValueType valueType) {
        if (keyframes.isEmpty()) return clip.videoProperties.getValue(valueType);

        // Map global time to local clip time
        float localTime = playheadTime - clip.startTime;

        if (localTime <= keyframes.get(0).getLocalTime()) {
            return keyframes.get(0).value.getValue(valueType);
        }
        if (localTime >= keyframes.get(keyframes.size() - 1).getLocalTime()) {
            return keyframes.get(keyframes.size() - 1).value.getValue(valueType);
        }

        Keyframe prev = keyframes.get(0);
        for (Keyframe next : keyframes) {
            if (localTime < next.getLocalTime()) {
                float t = (localTime - prev.getLocalTime()) / (next.getLocalTime() - prev.getLocalTime());
                t = Math.max(0f, Math.min(1f, t));
                
                float prevVal = prev.value.getValue(valueType);
                float nextVal = next.value.getValue(valueType);
                
                return lerp(prevVal, nextVal, ease(t, prev.easing));
            }
            prev = next;
        }
        return keyframes.get(keyframes.size() - 1).value.getValue(valueType);
    }

    private float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }


        private float ease(float t, EasingType type) {
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
