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

    public Track(Track other) {
        timelineIndex = other.timelineIndex;
        clips.addAll(other.clips);
    }

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
            clip.setStartTime(clip.getStartTime(), framePerSecond);
        }
    }
    public List<Clip> getClipsAtCurrentTime(float playheadTime) {
        List<Clip> clipsSelected = new ArrayList<>();
        for (Clip clip : clips) {
            if (playheadTime >= clip.startTime && playheadTime < clip.startTime + clip.duration) {
                clipsSelected.add(clip);
            }
        }
        return clipsSelected; // No clip at this time
    }
}
