package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

public class SplitClipCommand implements Command {
    private final Timeline timeline;
    private final Clip originalClip;
    private final float splitTime;
    private final float oldDuration;
    private final float oldStartClipTrim;
    private final float oldEndClipTrim;
    private Clip secondPart;
    private final Runnable onUpdate;

    public SplitClipCommand(Timeline timeline, Clip clip, float splitTime, Runnable onUpdate) {
        this.timeline = timeline;
        this.originalClip = clip;
        this.splitTime = splitTime;
        this.oldDuration = clip.duration;
        this.oldStartClipTrim = clip.startClipTrim;
        this.oldEndClipTrim = clip.endClipTrim;
        this.onUpdate = onUpdate;
    }

    @Override
    public void execute() {
        Track track = timeline.tracks.get(originalClip.trackIndex);
        float localSplitTime = originalClip.getLocalClipTime(splitTime);

        if (secondPart == null) {
            secondPart = new Clip(originalClip);
        }

        // Secondary part
        secondPart.startTime = splitTime;
        secondPart.setStartClipTrim(localSplitTime + oldStartClipTrim);
        secondPart.setEndClipTrim(oldEndClipTrim);

        // Primary part
        originalClip.setEndClipTrim(originalClip.originalDuration - (localSplitTime + oldStartClipTrim));

        track.addClip(secondPart);
        track.sortClips();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public void undo() {
        Track track = timeline.tracks.get(originalClip.trackIndex);
        track.removeClip(secondPart);
        
        originalClip.startClipTrim = oldStartClipTrim;
        originalClip.endClipTrim = oldEndClipTrim;
        originalClip.duration = oldDuration;
        
        track.sortClips();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public String getName() {
        return "Split Clip: " + originalClip.getClipName();
    }
}
