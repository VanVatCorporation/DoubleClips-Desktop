package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * "Close gaps" (the mobile apps' auto snap): lines the clips of one track up back to back in start-time order. The
 * first clip stays where it is; every later clip starts where the one before it ends, so gaps (and overlaps) in
 * that track disappear. ONE undo step; a clip's end transition moves with it.
 */
public class CloseTrackGapsCommand implements Command {

    private final Timeline timeline;
    private final Runnable onUpdate;
    private final List<Clip> clips = new ArrayList<>();
    private final List<Float> oldStarts = new ArrayList<>();
    private final List<Float> newStarts = new ArrayList<>();

    public CloseTrackGapsCommand(Timeline timeline, Track track, Runnable onUpdate) {
        this.timeline = timeline;
        this.onUpdate = onUpdate;
        List<Clip> sorted = new ArrayList<>(track.clips);
        sorted.sort((a, b) -> Float.compare(a.startTime, b.startTime));
        float cursor = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Clip c = sorted.get(i);
            float start = i == 0 ? c.startTime : cursor;
            clips.add(c);
            oldStarts.add(c.startTime);
            newStarts.add(start);
            cursor = start + c.duration;
        }
    }

    /** True when at least one clip would move. */
    public boolean hasChanges() {
        for (int i = 0; i < clips.size(); i++) if (Math.abs(oldStarts.get(i) - newStarts.get(i)) > 0.0005f) return true;
        return false;
    }

    @Override
    public void execute() {
        apply(newStarts);
    }

    @Override
    public void undo() {
        apply(oldStarts);
    }

    private void apply(List<Float> starts) {
        for (int i = 0; i < clips.size(); i++) {
            Clip c = clips.get(i);
            float shift = starts.get(i) - c.startTime;
            c.startTime = starts.get(i);
            if (c.endTransition != null && shift != 0f) {       // the transition follows its clip
                c.endTransition.startTime += shift;
                if (c.endTransition.effect != null) c.endTransition.effect.startTime += shift;
            }
        }
        timeline.recalculateDuration();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public String getName() {
        return "Close Gaps";
    }
}
