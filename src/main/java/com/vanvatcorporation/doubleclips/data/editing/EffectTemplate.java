package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.annotations.Expose;
import java.io.Serializable;

public class EffectTemplate implements Serializable {
    @Expose
    public String style;
    @Expose
    public float duration;
    @Expose
    public float startTime;
    /**
     * Optional settings of the effect. "intensity" (a number, 1 = the effect's default look) is the strength
     * slider the iOS editor writes; Android ignores it and keeps it when it saves. Null / missing = defaults.
     */
    @Expose
    public java.util.Map<String, Object> params;

    public static final String INTENSITY = "intensity";

    /** A copy that shares nothing with {@code other} (clip copies must not share an effect). */
    public EffectTemplate(EffectTemplate other) {
        this.style = other.style;
        this.duration = other.duration;
        this.startTime = other.startTime;
        this.params = other.params == null ? null : new java.util.LinkedHashMap<>(other.params);
    }

    /** The strength slider's value: 1 (the default look) when none is stored. */
    public float intensity() {
        if (params != null && params.get(INTENSITY) instanceof Number) {
            float v = ((Number) params.get(INTENSITY)).floatValue();
            if (!Float.isNaN(v) && !Float.isInfinite(v)) return v;
        }
        return 1f;
    }

    public void setIntensity(float value) {
        if (params == null) params = new java.util.LinkedHashMap<>();
        params.put(INTENSITY, (double) value);
    }

    public EffectTemplate(String style, float duration, float startTime) {
        this.style = style;
        this.duration = duration;
        this.startTime = startTime;
    }
}
