package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

public class SplitClipCommand implements Command {
    private final Timeline timeline;
    private final Clip originalClip;
    private final float splitTime;
    private final float originalDuration;
    private Clip secondPart;
    private final Runnable onUpdate;

    public SplitClipCommand(Timeline timeline, Clip clip, float splitTime, Runnable onUpdate) {
        this.timeline = timeline;
        this.originalClip = clip;
        this.splitTime = splitTime;
        this.originalDuration = clip.duration;
        this.onUpdate = onUpdate;
    }

    @Override
    public void execute() {
        Track track = timeline.tracks.get(originalClip.trackIndex);
        float localSplitTime = splitTime - originalClip.startTime;

        if (secondPart == null) {
            secondPart = new Clip(originalClip);
        }
        secondPart.startTime = splitTime;
        secondPart.duration = originalDuration - localSplitTime;
        originalClip.duration = localSplitTime;

        track.addClip(secondPart);
        track.sortClips();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public void undo() {
        Track track = timeline.tracks.get(originalClip.trackIndex);
        track.removeClip(secondPart);
        originalClip.duration = originalDuration;
        track.sortClips();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public String getName() {
        return "Split Clip: " + originalClip.getClipName();
    }
}
