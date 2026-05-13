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
    /**
     * Reassign keyframe into the current timeline in respect of framePerSecond
     * @param framePerSecond
     */
    public void reassignClips(int framePerSecond) {
        if (framePerSecond <= 0) return;

        for (Track track : tracks) {
            track.reassignClips(framePerSecond);
        }
    }

    public void moveTrackUp(Track track) {
        if(track != null)
        {
            if(track.timelineIndex > 0)
            {
                Track upperTrack = tracks.get(track.timelineIndex - 1);
                tracks.set(track.timelineIndex - 1, track);
                tracks.set(track.timelineIndex, upperTrack);

//                ViewParent vf = track.viewRef.getParent();
//                if(vf instanceof ViewGroup)
//                {
//                    ((ViewGroup) vf).removeView(track.viewRef);
//                    ((ViewGroup) vf).addView(track.viewRef, track.timelineIndex - 1);
//                }

                reloadTrackIndex();
            }
            else {
//                LoggingManager.LogToToast(context, "Track already on top");
            }
        }
    }
    public void moveTrackDown(Track track) {
        if(track != null)
        {
            if(track.timelineIndex < tracks.size() - 1)
            {
                Track lowerTrack = tracks.get(track.timelineIndex + 1);
                tracks.set(track.timelineIndex + 1, track);
                tracks.set(track.timelineIndex, lowerTrack);

//                ViewParent vf = track.viewRef.getParent();
//                if(vf instanceof ViewGroup)
//                {
//                    ((ViewGroup) vf).removeView(track.viewRef);
//                    ((ViewGroup) vf).addView(track.viewRef, track.timelineIndex + 1);
//                }

                reloadTrackIndex();
            }
            else {
//                LoggingManager.LogToToast(context, "Track already on bottom");
            }
        }
    }


    public List<Clip> getClipsAtCurrentTime(float playheadTime) {
        List<Clip> clips = new ArrayList<>();
        for (Track track : tracks) {
            for (Clip clip : track.clips) {
                if (playheadTime >= clip.startTime && playheadTime < clip.startTime + clip.duration) {
                    clips.add(clip);
                }
            }
        }
        return clips; // No clip at this time
    }

    public void recalculateDuration() {
        float max = 0f;
        for (Track track : tracks) {
            float endTime = track.getTrackEndTime();
            if (endTime > max) {
                max = endTime;
            }
            track.sortClips();
        }
        duration = max;
    }
    public Track getTrackFromClip(Clip selectedClip) {
        for (Track track : tracks) {
            if(track.clips.contains(selectedClip))
                return track;
        }
        return null;
    }

    public void prepareAfterLoad() {
        reloadTrackIndex();
        for (Track track : tracks) {
            if (track.clips == null) track.clips = new ArrayList<>();
            for (Clip clip : track.clips) {
                clip.filterNullAfterLoad();
            }
        }
    }

    public int getAllClipCount() {
        int clipCount = 0;
        for (Track track : tracks) {
            clipCount += track.clips.size();
        }
        return clipCount;
    }

    public List<Clip> getAllClips() {
        List<Clip> all = new ArrayList<>();
        for (Track track : tracks) {
            all.addAll(track.clips);
        }
        return all;
    }

    public Clip[] getStreamOfClip() {
        Clip[] clips = new Clip[getAllClipCount()];
        int i = 0;
        for (Track track : tracks) {
            for (Clip clip : track.clips) {
                clips[i] = clip;
                i++;
            }
        }
        return clips;
    }
    public int getAllReplacementClipCount() {
        return getAllClipCount() - getLockedForTemplateClip().length;
    }

    public Clip[] getLockedForTemplateClip() {
        List<Clip> clips = new ArrayList<>();
        for (Clip clip : getStreamOfClip()) {
            if(clip.isLockedForTemplate())
            {
                clips.add(clip);
            }
        }

        return clips.toArray(new Clip[0]);
    }
}

