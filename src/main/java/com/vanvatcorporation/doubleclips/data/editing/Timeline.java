package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.annotations.Expose;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Timeline implements Serializable {
    @Expose
    public List<Track> tracks = new ArrayList<>();
    @Expose
    public float duration;

    public void updateDuration() {
        float max = 0f;
        for (Track track : tracks) {
            float endTime = track.getTrackEndTime();
            if (endTime > max) max = endTime;
            track.sortClips();
        }
        this.duration = max;
    }

    public void sortAllTracks() {
        for (Track track : tracks) {
            track.sortClips();
        }
    }

    public void addTrack(Track track) {
        track.timelineIndex = tracks.size();
        tracks.add(track);
    }

    public void removeTrack(Track track) {
        tracks.remove(track);
        reloadTrackIndex();
    }

    public void reloadTrackIndex() {
        for (int i = 0; i < tracks.size(); i++) {
            tracks.get(i).timelineIndex = i;
            for (Clip clip : tracks.get(i).clips) {
                clip.trackIndex = i;
            }
        }
    }

    public List<Clip> getClipsAtTime(float playheadTime) {
        List<Clip> activeClips = new ArrayList<>();
        for (Track track : tracks) {
            for (Clip clip : track.clips) {
                if (playheadTime >= clip.startTime && playheadTime < clip.startTime + clip.duration) {
                    activeClips.add(clip);
                }
            }
        }
        return activeClips;
    }
}
