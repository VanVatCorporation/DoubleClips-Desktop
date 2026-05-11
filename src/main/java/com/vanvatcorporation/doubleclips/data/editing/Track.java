package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.annotations.Expose;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Track implements Serializable {
    @Expose
    public int timelineIndex;
    @Expose
    public List<Clip> clips = new ArrayList<>();

    public transient Object viewRef;

    public Track() {}

    public void addClip(Clip clip) {
        clip.trackIndex = timelineIndex;
        clips.add(clip);
    }

    public void removeClip(Clip clip) {
        clips.remove(clip);
    }

    public void sortClips() {
        clips.sort((o1, o2) -> (Float.compare(o1.startTime, o2.startTime)));
    }

    public float getTrackEndTime() {
        float max = 0f;
        for (Clip clip : clips) {
            float end = clip.startTime + clip.duration;
            if (end > max) max = end;
        }
        return max;
    }

    public void reassignClips(int framePerSecond) {
        if (framePerSecond <= 0) return;

        for (Clip clip : clips) {
            double currentTime = clip.getStartTime();

            // 1. Find which frame index we are closest to
            // Example: 3.14 * 30 = 94.2. Round(94.2) = 94.
            long closestFrameIndex = Math.round(currentTime * framePerSecond);

            // 2. Convert that frame index back into seconds
            // Example: 94 / 30.0 = 3.1333...
            double snappedTime = (double) closestFrameIndex / framePerSecond;

            clip.setStartTime((float) snappedTime);
        }
    }
}
