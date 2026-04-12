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

    public EffectTemplate(String style, float duration, float startTime) {
        this.style = style;
        this.duration = duration;
        this.startTime = startTime;
    }
}
