package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;

public class DeleteClipCommand implements Command {
    private final Timeline timeline;
    private final Clip clip;
    private final int trackIndex;
    private final Runnable onUpdate;

    public DeleteClipCommand(Timeline timeline, Clip clip, Runnable onUpdate) {
        this.timeline = timeline;
        this.clip = clip;
        this.trackIndex = clip.trackIndex;
        this.onUpdate = onUpdate;
    }

    @Override
    public void execute() {
        timeline.tracks.get(trackIndex).removeClip(clip);
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public void undo() {
        timeline.tracks.get(trackIndex).addClip(clip);
        timeline.tracks.get(trackIndex).sortClips();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public String getName() {
        return "Delete Clip: " + clip.getClipName();
    }
}
