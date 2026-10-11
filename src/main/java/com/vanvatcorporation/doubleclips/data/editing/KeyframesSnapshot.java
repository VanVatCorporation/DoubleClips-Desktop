package com.vanvatcorporation.doubleclips.data.editing;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a keyframe edit can touch on one clip: its static properties and its keyframe list, with each
 * keyframe's value, easing, time and channels copied. Used as the "before" state of an undo step.
 * <p>
 * Keyframe objects keep their identity on restore (the timeline's knots hold references to them): a keyframe
 * that was in the list comes back as the same object, with its old state.
 */
public final class KeyframesSnapshot {
    private final VideoProperties statics;
    private final List<Keyframe> keys;
    private final List<Keyframe> states;

    private KeyframesSnapshot(VideoProperties statics, List<Keyframe> keys, List<Keyframe> states) {
        this.statics = statics;
        this.keys = keys;
        this.states = states;
    }

    public static KeyframesSnapshot of(Clip clip) {
        List<Keyframe> keys = new ArrayList<>(clip.keyframes.keyframes);
        List<Keyframe> states = new ArrayList<>();
        for (Keyframe k : keys) states.add(new Keyframe(k));
        return new KeyframesSnapshot(new VideoProperties(clip.videoProperties), keys, states);
    }

    public void restore(Clip clip) {
        clip.videoProperties = new VideoProperties(statics);
        clip.keyframes.keyframes.clear();
        for (int i = 0; i < keys.size(); i++) {
            Keyframe k = keys.get(i);
            Keyframe state = states.get(i);
            k.value = state.value == null ? null : new VideoProperties(state.value);
            k.easing = state.easing;
            k.channels = KeyframeChannels.copy(state.channels);
            k.setLocalTime(state.getLocalTime());
            k.setLocalFrame(state.getLocalFrame());
            clip.keyframes.keyframes.add(k);
        }
    }
}
