package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.annotations.Expose;
import java.io.Serializable;

public class VideoProperties implements Serializable {
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
    // Synced from Android's VideoProperties. valueVolume is data-compat only (desktop keeps
    // its own Clip.audioVolume); the two pivot fields are used by OpenGLEdit/FFmpegEdit.
    /** Normalized pivot X within the clip [0.0 = left ... 1.0 = right]. PosX/PosY is the canvas
     *  position of the clip's UNSCALED top-left; scale and rotation happen around this pivot.
     *  Default 0.0 (top-left); Gson leaves it at the constructor default for older project JSON. */
    @Expose public float valuePivotX;
    @Expose public float valuePivotY;
    @Expose public float valueVolume;

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
    }

    public VideoProperties(VideoProperties other) {
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
            default: return 1;
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
        }
    }

    public enum ValueType {
        PosX, PosY, Rot, RotInRadians, ScaleX, ScaleY, PivotX, PivotY, Opacity, Speed, Volume, Hue, Saturation, Brightness, Temperature
    }
}
