package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

/**
 * Moves one track to another position as ONE undo step. Track order is layer order (a later track is drawn on
 * top of an earlier one, in the preview and in both exports), so this also decides which clips cover which.
 * The tracks and their clips are renumbered, as after any change to the track list.
 */
public class MoveTrackCommand implements Command {
    private final Timeline timeline;
    private final int from;
    private final int to;
    private final Runnable onUpdate;

    public MoveTrackCommand(Timeline timeline, int from, int to, Runnable onUpdate) {
        this.timeline = timeline;
        this.from = from;
        this.to = to;
        this.onUpdate = onUpdate;
    }

    @Override
    public void execute() {
        move(from, to);
    }

    @Override
    public void undo() {
        move(to, from);
    }

    private void move(int source, int target) {
        int count = timeline.tracks.size();
        if (source < 0 || source >= count || target < 0 || target >= count || source == target) return;
        Track moved = timeline.tracks.remove(source);
        timeline.tracks.add(target, moved);
        timeline.reloadTrackIndex();
        timeline.recalculateDuration();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public String getName() {
        return "Move Track";
    }
}
