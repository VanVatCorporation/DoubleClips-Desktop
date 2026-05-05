package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;

public class MoveClipCommand implements Command {
    private final Timeline timeline;
    private final Clip clip;
    private final float oldStartTime;
    private final float newStartTime;
    private final int oldTrackIndex;
    private final int newTrackIndex;
    private final Runnable onUpdate;

    public MoveClipCommand(Timeline timeline, Clip clip, float oldStartTime, float newStartTime, int oldTrackIndex, int newTrackIndex, Runnable onUpdate) {
        this.timeline = timeline;
        this.clip = clip;
        this.oldStartTime = oldStartTime;
        this.newStartTime = newStartTime;
        this.oldTrackIndex = oldTrackIndex;
        this.newTrackIndex = newTrackIndex;
        this.onUpdate = onUpdate;
    }

    @Override
    public void execute() {
        move(newStartTime, newTrackIndex);
    }

    @Override
    public void undo() {
        move(oldStartTime, oldTrackIndex);
    }

    private void move(float startTime, int trackIdx) {
        if (clip.trackIndex != trackIdx) {
            timeline.tracks.get(clip.trackIndex).removeClip(clip);
            clip.trackIndex = trackIdx;
            clip.startTime = startTime;
            timeline.tracks.get(trackIdx).addClip(clip);
            timeline.tracks.get(trackIdx).sortClips();
        } else {
            clip.startTime = startTime;
            timeline.tracks.get(trackIdx).sortClips();
        }
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public String getName() {
        return "Move Clip: " + clip.getClipName();
    }
}
