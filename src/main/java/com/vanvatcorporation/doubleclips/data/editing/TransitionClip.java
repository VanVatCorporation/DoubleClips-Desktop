package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.JsonObject;
import com.vanvatcorporation.doubleclips.UnknownKeysHolder;

import com.google.gson.annotations.Expose;
import java.io.Serializable;

public class TransitionClip implements Serializable, UnknownKeysHolder {
    /** Keys of this object's JSON that no field maps to (another platform's), kept across a save. See {@link UnknownKeysHolder}. */
    private transient JsonObject unknownKeys;
    @Override public JsonObject getUnknownKeys() { return unknownKeys; }
    @Override public void setUnknownKeys(JsonObject keys) { unknownKeys = keys; }

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
