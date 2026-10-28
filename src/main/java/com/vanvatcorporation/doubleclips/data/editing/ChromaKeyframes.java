package com.vanvatcorporation.doubleclips.data.editing;

import com.vanvatcorporation.doubleclips.data.editing.VideoProperties.ValueType;

import java.util.ArrayList;

/**
 * Keeps a clip's chroma-key keyframes in step with its key list. The sliders are keyframed under the key's PLACE in
 * {@link Clip#chromaKeys} ({@link ValueType#chroma}), so taking a key out of the list moves every later key down one place:
 * its keyframes have to move with it, or they would animate the wrong colour. Plain Java on the data classes, like
 * {@link VolumeCurve}; the Background tab calls it inside the same undo step as the list change.
 */
public final class ChromaKeyframes {

    private ChromaKeyframes() {}

    /**
     * Call right after the key at {@code removed} was taken out of the clip's list: the keyframed sliders of the keys after
     * it move down one place (what they hold and the values they keyed), the last place is freed, and a keyframe that
     * held nothing but sliders of the removed key is deleted.
     */
    public static void keyRemoved(Clip clip, int removed) {
        if (clip.keyframes == null || clip.keyframes.keyframes == null) return;
        for (Keyframe k : new ArrayList<>(clip.keyframes.keyframes)) {
            for (int param = 0; param < ChromaKey.PARAM_COUNT; param++) {
                for (int place = removed; place < ChromaKey.MAX_KEYS; place++) {
                    ValueType to = ValueType.chroma(place, param);
                    if (place + 1 < ChromaKey.MAX_KEYS) {
                        ValueType from = ValueType.chroma(place + 1, param);
                        boolean holds = k.holds(from);
                        float value = k.value == null ? 0f : k.value.getValue(from);
                        k.setHolds(to, holds);
                        if (k.value != null) k.value.setValue(holds ? value : 0f, to);
                    } else {
                        k.setHolds(to, false);
                        if (k.value != null) k.value.setValue(0f, to);
                    }
                }
            }
            if (k.holdsNothing()) clip.keyframes.keyframes.remove(k);
        }
    }

    /** All keyframes of the key at {@code index} go (its sliders are plain values again); emptied keyframes are deleted. */
    public static void clearKey(Clip clip, int index) {
        if (clip.keyframes == null || clip.keyframes.keyframes == null) return;
        for (Keyframe k : new ArrayList<>(clip.keyframes.keyframes)) {
            for (int param = 0; param < ChromaKey.PARAM_COUNT; param++) {
                ValueType type = ValueType.chroma(index, param);
                if (k.holds(type)) k.setHolds(type, false);
                if (k.value != null) k.value.setValue(0f, type);
            }
            if (k.holdsNothing()) clip.keyframes.keyframes.remove(k);
        }
    }

    /** Does any keyframe key a slider of the key at {@code index}? */
    public static boolean isAnimated(Clip clip, int index) {
        if (clip.keyframes == null) return false;
        for (int param = 0; param < ChromaKey.PARAM_COUNT; param++) {
            if (clip.keyframes.isAnimated(ValueType.chroma(index, param))) return true;
        }
        return false;
    }

    /**
     * For the live preview in the worker: lets go of every chroma slider in the clip's keyframes, so the plain values given
     * alongside (the sliders as they are at the playhead) are what is drawn. Emptied keyframes are kept (it is a throwaway copy).
     */
    public static void flatten(Clip clip) {
        if (clip.keyframes == null || clip.keyframes.keyframes == null) return;
        for (Keyframe k : clip.keyframes.keyframes) {
            for (ValueType t : ValueType.CHANNELS) if (t.isChroma() && k.holds(t)) k.setHolds(t, false);
        }
    }
}
