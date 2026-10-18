package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.data.editing.Track;

/** Turns a track's preview sound off or on, as one undo step. Preview only: an export is not affected. */
public class MuteTrackCommand implements Command {

    private final Track track;
    private final boolean muted;
    private final Runnable onUpdate;
    private boolean before;

    public MuteTrackCommand(Track track, boolean muted, Runnable onUpdate) {
        this.track = track;
        this.muted = muted;
        this.onUpdate = onUpdate;
    }

    @Override
    public void execute() {
        before = track.muted;
        track.muted = muted;
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public void undo() {
        track.muted = before;
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public String getName() {
        return muted ? "Mute Track" : "Unmute Track";
    }
}
