package com.vanvatcorporation.doubleclips.data;

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
    }

    public float getValue(ValueType valueType) {
        switch (valueType) {
            case PosX: return valuePosX;
            case PosY: return valuePosY;
            case Rot: return valueRot;
            case RotInRadians: return (float) Math.toRadians(valueRot);
            case ScaleX: return valueScaleX;
            case ScaleY: return valueScaleY;
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
            case Opacity: valueOpacity = v; break;
            case Speed: valueSpeed = v; break;
            case Hue: valueHue = v; break;
            case Saturation: valueSaturation = v; break;
            case Brightness: valueBrightness = v; break;
            case Temperature: valueTemperature = v; break;
        }
    }

    public enum ValueType {
        PosX, PosY, Rot, RotInRadians, ScaleX, ScaleY, Opacity, Speed, Hue, Saturation, Brightness, Temperature
    }
}
