package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.EffectTemplate;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;
import com.vanvatcorporation.doubleclips.data.editing.TransitionClip;

/**
 * Duplicates a track as ONE undo step: the copy goes right below the original (a later track is drawn on top, so the
 * copy covers the original), with a real copy of every clip - not the same clip objects - and of each clip's end
 * transition. The copy is made on the first execute and kept, so redo puts the very same track back.
 */
public class DuplicateTrackCommand implements Command {

    private final Timeline timeline;
    private final Track source;
    private final Runnable onUpdate;
    private Track copy;

    public DuplicateTrackCommand(Timeline timeline, Track source, Runnable onUpdate) {
        this.timeline = timeline;
        this.source = source;
        this.onUpdate = onUpdate;
    }

    /** The new track (after the first execute), so the editor can select it. */
    public Track getCopy() {
        return copy;
    }

    @Override
    public void execute() {
        if (copy == null) copy = deepCopy(source);
        int at = timeline.tracks.indexOf(source) + 1;
        if (at <= 0) at = timeline.tracks.size();
        timeline.tracks.add(Math.min(at, timeline.tracks.size()), copy);
        timeline.reloadTrackIndex();
        timeline.recalculateDuration();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public void undo() {
        timeline.tracks.remove(copy);
        timeline.reloadTrackIndex();
        timeline.recalculateDuration();
        if (onUpdate != null) onUpdate.run();
    }

    static Track deepCopy(Track other) {
        Track t = new Track();
        t.copyUnknownKeysFrom(other);
        t.muted = other.muted;
        for (Clip original : other.clips) {
            Clip c = new Clip(original);
            c.viewRef = null;                                   // the copy gets its own node
            c.endTransition = original.endTransition == null ? null : copyTransition(original.endTransition, c);
            t.clips.add(c);
        }
        return t;                                               // timelineIndex / clip.trackIndex: set by reloadTrackIndex
    }

    private static TransitionClip copyTransition(TransitionClip o, Clip owner) {
        TransitionClip t = new TransitionClip(owner);
        t.copyUnknownKeysFrom(o);
        t.trackIndex = o.trackIndex;
        t.startTime = o.startTime;
        t.duration = o.duration;
        t.mode = o.mode;
        t.effect = o.effect == null ? null : new EffectTemplate(o.effect);
        return t;
    }

    @Override
    public String getName() {
        return "Duplicate Track";
    }
}
