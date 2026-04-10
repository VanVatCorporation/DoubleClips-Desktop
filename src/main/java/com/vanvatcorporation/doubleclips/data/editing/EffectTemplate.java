package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.annotations.Expose;
import java.io.Serializable;

public class EffectTemplate implements Serializable {
    @Expose
    public String effectId;
    @Expose
    public float duration;
    @Expose
    public float startTime;

    public EffectTemplate(String effectId, float duration, float startTime) {
        this.effectId = effectId;
        this.duration = duration;
        this.startTime = startTime;
    }
}
