package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

public class AddClipCommand implements Command {
    private final Timeline timeline;
    private final Clip clip;
    private final int trackIndex;
    private final Runnable onUpdate;

    public AddClipCommand(Timeline timeline, Clip clip, int trackIndex, Runnable onUpdate) {
        this.timeline = timeline;
        this.clip = clip;
        this.trackIndex = trackIndex;
        this.onUpdate = onUpdate;
    }

    @Override
    public void execute() {
        while (timeline.tracks.size() <= trackIndex) {
            timeline.addTrack(new Track());
        }
        timeline.tracks.get(trackIndex).addClip(clip);
        timeline.tracks.get(trackIndex).sortClips();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public void undo() {
        timeline.tracks.get(trackIndex).removeClip(clip);
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public String getName() {
        return "Add Clip: " + clip.getClipName();
    }
}
