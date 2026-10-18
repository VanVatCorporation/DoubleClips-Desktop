package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

/**
 * Deletes a whole track (with its clips) as ONE undo step. Undo puts the track back at the position it had, so the
 * tracks below it (and the layer order) come back exactly as they were.
 */
public class DeleteTrackCommand implements Command {

    private final Timeline timeline;
    private final Track track;
    private final Runnable onUpdate;
    private int index = -1;

    public DeleteTrackCommand(Timeline timeline, Track track, Runnable onUpdate) {
        this.timeline = timeline;
        this.track = track;
        this.onUpdate = onUpdate;
    }

    @Override
    public void execute() {
        index = timeline.tracks.indexOf(track);
        if (index < 0) return;
        timeline.removeTrack(track);                            // renumbers the rest
        timeline.recalculateDuration();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public void undo() {
        if (index < 0 || timeline.tracks.contains(track)) return;
        timeline.tracks.add(Math.min(index, timeline.tracks.size()), track);
        timeline.reloadTrackIndex();
        timeline.recalculateDuration();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public String getName() {
        return "Delete Track";
    }
}
