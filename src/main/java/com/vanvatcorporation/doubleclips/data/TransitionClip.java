package com.vanvatcorporation.doubleclips.data;

import com.google.gson.annotations.Expose;
import java.io.Serializable;

public class TransitionClip implements Serializable {
    @Expose
    public int trackIndex;
    @Expose
    public float startTime;
    @Expose
    public float duration;

    @Expose
    public EffectTemplate effect;

    @Expose
    public TransitionMode mode;

    public TransitionClip(Clip clipA, Clip clipB, float transitionDuration) {
        trackIndex = clipA.trackIndex;
        startTime = clipB.startTime - transitionDuration / 2;
        duration = transitionDuration;
        effect = new EffectTemplate("none", transitionDuration, startTime);
        mode = TransitionMode.OVERLAP;
    }

    public TransitionClip(Clip clipA) {
        trackIndex = clipA.trackIndex;
        startTime = 0;
        duration = 0;
        effect = new EffectTemplate("none", 0, startTime);
        mode = TransitionMode.OVERLAP;
    }

    public enum TransitionMode {
        END_FIRST,
        OVERLAP,
        BEGIN_SECOND
    }
}
