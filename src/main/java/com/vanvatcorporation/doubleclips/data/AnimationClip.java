package com.vanvatcorporation.doubleclips.data;

import com.google.gson.annotations.Expose;
import java.io.Serializable;

public class AnimationClip implements Serializable {
    @Expose
    public String type;
    @Expose
    public float duration;

    public AnimationClip(String type, float duration) {
        this.type = type;
        this.duration = duration;
    }

    public AnimationClip(AnimationClip other) {
        if (other != null) {
            this.type = other.type;
            this.duration = other.duration;
        } else {
            this.type = "none";
            this.duration = 0.5f;
        }
    }
}
