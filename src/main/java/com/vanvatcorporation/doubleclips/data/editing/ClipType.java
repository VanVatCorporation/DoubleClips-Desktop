package com.vanvatcorporation.doubleclips.data.editing;

public enum ClipType {
    VIDEO,
    AUDIO,
    IMAGE,
    EFFECT,
    TEXT,
    SCENE_3D,
    // Synced from Android. Appended last so nothing ordinal-based shifts (Gson stores enums by name).
    TRANSITION
}
