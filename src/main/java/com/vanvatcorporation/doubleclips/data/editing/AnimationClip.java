package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.JsonObject;
import com.vanvatcorporation.doubleclips.UnknownKeysHolder;

import com.google.gson.annotations.Expose;
import java.io.Serializable;

public class AnimationClip implements Serializable, UnknownKeysHolder {
    /** Keys of this object's JSON that no field maps to (another platform's), kept across a save. See {@link UnknownKeysHolder}. */
    private transient JsonObject unknownKeys;
    @Override public JsonObject getUnknownKeys() { return unknownKeys; }
    @Override public void setUnknownKeys(JsonObject keys) { unknownKeys = keys; }

    @Expose
    public String type;
    @Expose
    public float duration;

    public AnimationClip(String type, float duration) {
        this.type = type;
        this.duration = duration;
    }

    public AnimationClip(AnimationClip other) {
        copyUnknownKeysFrom(other);
        if (other != null) {
            this.type = other.type;
            this.duration = other.duration;
        } else {
            this.type = "none";
            this.duration = 0.5f;
        }
    }
}
