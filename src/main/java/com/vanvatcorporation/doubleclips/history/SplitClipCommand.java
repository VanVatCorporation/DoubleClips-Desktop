package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
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
        // The split point in SOURCE seconds: the speed (a number or a curve) decides how much source the first part uses.
        double consumed = originalClip.timeMap().sourceOffset(localSplitTime);
        boolean reversedVideo = originalClip.type == ClipType.VIDEO && originalClip.isReverse;
        if (reversedVideo) {
            // A reversed video plays its used range backwards, so the part on the LEFT of the cut is the source's LATER half:
            // it keeps the end trim and starts (in source terms) at the split point; the right part plays what is before it.
            float splitSource = (originalClip.originalDuration - oldEndClipTrim) - (float) consumed;
            secondPart.setStartClipTrim(oldStartClipTrim);
            secondPart.setEndClipTrim(originalClip.originalDuration - splitSource);

            originalClip.setStartClipTrim(splitSource);
        } else {
            float splitSource = oldStartClipTrim + (float) consumed;
            secondPart.setStartClipTrim(splitSource);
            secondPart.setEndClipTrim(oldEndClipTrim);

            // Primary part
            originalClip.setEndClipTrim(originalClip.originalDuration - splitSource);
        }

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
