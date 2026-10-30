package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.JsonObject;
import com.vanvatcorporation.doubleclips.UnknownKeysHolder;

import com.google.gson.annotations.Expose;
import java.io.Serializable;

public class VideoProperties implements Serializable, UnknownKeysHolder {
    /** Keys of this object's JSON that no field maps to (another platform's), kept across a save. See {@link UnknownKeysHolder}. */
    private transient JsonObject unknownKeys;
    @Override public JsonObject getUnknownKeys() { return unknownKeys; }
    @Override public void setUnknownKeys(JsonObject keys) { unknownKeys = keys; }

    @Expose public float valuePosX;
    @Expose public float valuePosY;
    @Expose public float valueRot;
    @Expose public float valueScaleX;
    @Expose public float valueScaleY;
    @Expose public float valueOpacity;
    @Expose public float valueSpeed;
    @Expose public float valueHue;
    @Expose public float valueSaturation;
    @Expose public float valueBrightness;
    @Expose public float valueTemperature;
    // Synced from Android's VideoProperties. valueVolume is the clip's plain volume, the same field iOS and Android use
    // (see VolumeCurve); the two pivot fields are used by OpenGLEdit/FFmpegEdit.
    /** Normalized pivot X within the clip [0.0 = left ... 1.0 = right]. PosX/PosY is the canvas
     *  position of the clip's UNSCALED top-left; scale and rotation happen around this pivot.
     *  Default 0.0 (top-left); Gson leaves it at the constructor default for older project JSON. */
    @Expose public float valuePivotX;
    @Expose public float valuePivotY;
    @Expose public float valueVolume;
    /**
     * The strength of the clip's effect (an effect clip's, or a clip's own {@link Clip#clipEffect}), only ever ANIMATED: the
     * static strength is the effect's {@code params["intensity"]}, so this is never read for a clip without a "strength"
     * keyframe. iOS's and Android's valueStrength: same key, default 1.
     */
    @Expose public float valueStrength = 1;
    /** The motion blur's Blur / Blend sliders (see {@link ClipMotionBlur}), plain or keyframed like every property: 0 ... 100. */
    @Expose public float valueMotionBlur = ClipMotionBlur.DEFAULT_BLUR;
    @Expose public float valueMotionBlend = ClipMotionBlur.DEFAULT_BLEND;
    /**
     * What the chroma-key sliders (see {@link ChromaKey}) were in a keyframe, per key per slider (ValueType.chromaIndex()).
     * Only keyframes ever carry them: the plain values live in the ChromaKey itself, so this stays null (and out of the
     * file) for a clip's own properties and for any keyframe that doesn't key a slider.
     */
    @Expose public float[] valueChroma;

    public VideoProperties() {
        this.valuePosX = 0;
        this.valuePosY = 0;
        this.valueRot = 0;
        this.valueScaleX = 1;
        this.valueScaleY = 1;
        this.valueOpacity = 1;
        this.valueSpeed = 1;
        this.valueHue = 0;
        this.valueSaturation = 1;
        this.valueBrightness = 0;
        this.valueTemperature = 6500;
        this.valuePivotX = 0;
        this.valuePivotY = 0;
        this.valueVolume = 1;
        this.valueStrength = 1;
        this.valueMotionBlur = ClipMotionBlur.DEFAULT_BLUR;
        this.valueMotionBlend = ClipMotionBlur.DEFAULT_BLEND;
    }

    public VideoProperties(VideoProperties other) {
        copyUnknownKeysFrom(other);
        this.valuePosX = other.valuePosX;
        this.valuePosY = other.valuePosY;
        this.valueRot = other.valueRot;
        this.valueScaleX = other.valueScaleX;
        this.valueScaleY = other.valueScaleY;
        this.valueOpacity = other.valueOpacity;
        this.valueSpeed = other.valueSpeed;
        this.valueHue = other.valueHue;
        this.valueSaturation = other.valueSaturation;
        this.valueBrightness = other.valueBrightness;
        this.valueTemperature = other.valueTemperature;
        this.valuePivotX = other.valuePivotX;
        this.valuePivotY = other.valuePivotY;
        this.valueVolume = other.valueVolume;
        this.valueStrength = other.valueStrength;
        this.valueMotionBlur = other.valueMotionBlur;
        this.valueMotionBlend = other.valueMotionBlend;
        this.valueChroma = other.valueChroma == null ? null : other.valueChroma.clone();
    }

    public float getValue(ValueType valueType) {
        switch (valueType) {
            case PosX: return valuePosX;
            case PosY: return valuePosY;
            case Rot: return valueRot;
            case RotInRadians: return (float) Math.toRadians(valueRot);
            case ScaleX: return valueScaleX;
            case ScaleY: return valueScaleY;
            case PivotX: return valuePivotX;
            case PivotY: return valuePivotY;
            case Volume: return valueVolume;
            case Opacity: return valueOpacity;
            case Speed: return valueSpeed;
            case Hue: return valueHue;
            case Saturation: return valueSaturation;
            case Brightness: return valueBrightness;
            case Temperature: return valueTemperature;
            case Strength: return valueStrength;
            case MotionBlur: return valueMotionBlur;
            case MotionBlend: return valueMotionBlend;
            default:
                if (valueType.isChroma()) return valueChroma == null ? 0f : valueChroma[valueType.chromaIndex()];
                return 1;
        }
    }

    public void setValue(float v, ValueType valueType) {
        switch (valueType) {
            case PosX: valuePosX = v; break;
            case PosY: valuePosY = v; break;
            case Rot: valueRot = v; break;
            case ScaleX: valueScaleX = v; break;
            case ScaleY: valueScaleY = v; break;
            case PivotX: valuePivotX = v; break;
            case PivotY: valuePivotY = v; break;
            case Volume: valueVolume = v; break;
            case Opacity: valueOpacity = v; break;
            case Speed: valueSpeed = v; break;
            case Hue: valueHue = v; break;
            case Saturation: valueSaturation = v; break;
            case Brightness: valueBrightness = v; break;
            case Temperature: valueTemperature = v; break;
            case Strength: valueStrength = v; break;
            case MotionBlur: valueMotionBlur = v; break;
            case MotionBlend: valueMotionBlend = v; break;
            default:
                if (valueType.isChroma()) {
                    if (valueChroma == null) {
                        if (v == 0f) break; // nothing to keep: stay null
                        valueChroma = new float[ChromaKey.MAX_KEYS * ChromaKey.PARAM_COUNT];
                    }
                    valueChroma[valueType.chromaIndex()] = v;
                }
                break;
        }
    }

    public enum ValueType {
        PosX, PosY, Rot, RotInRadians, ScaleX, ScaleY, PivotX, PivotY, Opacity, Speed, Volume, Hue, Saturation, Brightness, Temperature, Strength,
        // The motion blur sliders: before the chroma constants, which have to stay the LAST ones.
        MotionBlur, MotionBlend,
        // The chroma-key sliders, one per key per slider (a clip carries up to ChromaKey.MAX_KEYS keys): they are the LAST
        // constants, in this order, which isChroma() / chromaIndex() rely on. Raising MAX_KEYS needs a group more here.
        ChromaIntensity0, ChromaShadow0, ChromaFeather0, ChromaCleanUp0,
        ChromaIntensity1, ChromaShadow1, ChromaFeather1, ChromaCleanUp1,
        ChromaIntensity2, ChromaShadow2, ChromaFeather2, ChromaCleanUp2,
        ChromaIntensity3, ChromaShadow3, ChromaFeather3, ChromaCleanUp3,
        ChromaIntensity4, ChromaShadow4, ChromaFeather4, ChromaCleanUp4,
        ChromaIntensity5, ChromaShadow5, ChromaFeather5, ChromaCleanUp5;

        static {
            // Fails at once, not as a keyframe that silently can't be made, when MAX_KEYS and the constants above disagree.
            if (values().length - ChromaIntensity0.ordinal() != ChromaKey.MAX_KEYS * ChromaKey.PARAM_COUNT) {
                throw new IllegalStateException("ValueType has " + (values().length - ChromaIntensity0.ordinal())
                        + " chroma constants but ChromaKey.MAX_KEYS * PARAM_COUNT is " + ChromaKey.MAX_KEYS * ChromaKey.PARAM_COUNT);
            }
        }

        /** One of the chroma-key sliders. */
        public boolean isChroma() { return ordinal() >= ChromaIntensity0.ordinal(); }
        /** 0 ... MAX_KEYS * PARAM_COUNT - 1: the slot in {@link VideoProperties#valueChroma}. */
        public int chromaIndex() { return ordinal() - ChromaIntensity0.ordinal(); }
        /** Which key of the clip's list (0-based) this chroma slider belongs to. */
        public int chromaKey() { return chromaIndex() / ChromaKey.PARAM_COUNT; }
        /** Which slider: ChromaKey.INTENSITY ... CLEAN_UP. */
        public int chromaParam() { return chromaIndex() % ChromaKey.PARAM_COUNT; }

        /** The channel of slider {@code param} (ChromaKey.INTENSITY ...) of key number {@code key} (0 ... MAX_KEYS - 1). */
        public static ValueType chroma(int key, int param) {
            if (key < 0 || key >= ChromaKey.MAX_KEYS || param < 0 || param >= ChromaKey.PARAM_COUNT) {
                throw new IndexOutOfBoundsException("chroma key " + key + ", slider " + param);
            }
            return values()[ChromaIntensity0.ordinal() + key * ChromaKey.PARAM_COUNT + param];
        }

        /** The properties a keyframe can hold one by one, in the order of {@link KeyframeChannels#ALL}
         *  (RotInRadians is only Rot expressed in radians, so it is not a property of its own). */
        public static final ValueType[] CHANNELS = channels();

        private static ValueType[] channels() {
            java.util.List<ValueType> list = new java.util.ArrayList<>(java.util.Arrays.asList(
                    PosX, PosY, Rot, ScaleX, ScaleY, PivotX, PivotY, Opacity, Speed, Volume, Hue, Saturation, Brightness, Temperature, Strength, MotionBlur, MotionBlend));
            for (ValueType t : values()) if (t.isChroma()) list.add(t);
            return list.toArray(new ValueType[0]);
        }

        /** The name this property goes by in a keyframe's {@code channels} list - iOS's raw value ("posX", "scaleY"...). */
        public String channelName() {
            switch (this) {
                case PosX: return "posX";
                case PosY: return "posY";
                case Rot:
                case RotInRadians: return "rot";
                case ScaleX: return "scaleX";
                case ScaleY: return "scaleY";
                case PivotX: return "pivotX";
                case PivotY: return "pivotY";
                case Opacity: return "opacity";
                case Speed: return "speed";
                case Volume: return "volume";
                case Hue: return "hue";
                case Saturation: return "saturation";
                case Brightness: return "brightness";
                case Temperature: return "temperature";
                case Strength: return "strength";
                case MotionBlur: return "motionBlur";
                case MotionBlend: return "motionBlend";
                default:
                    if (isChroma()) {
                        String[] params = {"Intensity", "Shadow", "Feather", "CleanUp"};
                        return KeyframeChannels.CHROMA_PREFIX + params[chromaParam()] + chromaKey();
                    }
                    return name();
            }
        }

        /** The property for a channel name, or null for a name this build doesn't know (e.g. an iOS-only one). */
        public static ValueType fromChannelName(String channel) {
            for (ValueType t : CHANNELS) if (t.channelName().equals(channel)) return t;
            return null;
        }

        /** Short label for lists ("Pos X", "Scale Y"...). */
        public String channelLabel() {
            switch (this) {
                case PosX: return "Pos X";
                case PosY: return "Pos Y";
                case Rot:
                case RotInRadians: return "Rotation";
                case ScaleX: return "Scale X";
                case ScaleY: return "Scale Y";
                case PivotX: return "Pivot X";
                case PivotY: return "Pivot Y";
                case MotionBlur: return "Motion blur";
                case MotionBlend: return "Motion blend";
                default:
                    if (isChroma()) {
                        String[] params = {"intensity", "shadow", "feather edge", "clean up edge"};
                        return "Chroma " + (chromaKey() + 1) + " " + params[chromaParam()];
                    }
                    return name();
            }
        }
    }
}
