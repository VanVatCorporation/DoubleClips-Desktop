package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts a clip on a track at its own start time, as one undo step, the way a source monitor does:
 *
 *  - OVERLAP: the clip is just added; whatever is already there stays untouched (clips may overlap on a track, as when a
 *    file is dragged onto the timeline).
 *  - OVERWRITE: the span the new clip covers is cleared first. A clip inside it goes; a clip that sticks out of one side
 *    is cut back; a clip that spans the whole thing is cut in two (a hole is left where the new clip lands).
 *  - INSERT: the track opens up. A clip that starts at or after the new start moves later by the new clip's length; a clip
 *    that spans the start is cut there and its second half moves later. Only the target track moves.
 *
 * Cutting uses the clip's source time (trims + speed), like {@link SplitClipCommand}. Missing tracks are created.
 */
public class PlaceClipCommand implements Command {

    public enum Mode { OVERLAP, OVERWRITE, INSERT }

    private static final float EPS = 0.0005f;

    private static final class Saved {
        final Clip clip;
        final float startTime, duration, startClipTrim, endClipTrim;

        Saved(Clip c) {
            clip = c;
            startTime = c.startTime;
            duration = c.duration;
            startClipTrim = c.startClipTrim;
            endClipTrim = c.endClipTrim;
        }

        void restore() {
            clip.startTime = startTime;
            clip.duration = duration;
            clip.startClipTrim = startClipTrim;
            clip.endClipTrim = endClipTrim;
        }
    }

    private final Timeline timeline;
    private final Clip clip;
    private final int trackIndex;
    private final Mode mode;
    private final Runnable onUpdate;

    private final List<Saved> saved = new ArrayList<>();
    private final List<Clip> removed = new ArrayList<>();
    private final List<Clip> created = new ArrayList<>();
    private int createdTracks = 0;

    public PlaceClipCommand(Timeline timeline, Clip clip, int trackIndex, Mode mode, Runnable onUpdate) {
        this.timeline = timeline;
        this.clip = clip;
        this.trackIndex = trackIndex;
        this.mode = mode;
        this.onUpdate = onUpdate;
    }

    @Override
    public void execute() {
        saved.clear();
        removed.clear();
        created.clear();
        createdTracks = TrackGrowth.ensureTracks(timeline, trackIndex + 1);
        Track track = timeline.tracks.get(trackIndex);

        final float s = clip.startTime;
        final float len = clip.duration;
        final float e = s + len;
        List<Clip> existing = new ArrayList<>(track.clips);

        if (mode == Mode.OVERLAP) {
            // nothing else is touched
        } else if (mode == Mode.OVERWRITE) {
            for (Clip c : existing) {
                float cs = c.startTime, ce = cs + c.duration;
                if (ce <= s + EPS || cs >= e - EPS) continue;          // not touched
                boolean startsBefore = cs < s - EPS;
                boolean endsAfter = ce > e + EPS;
                if (!startsBefore && !endsAfter) {                     // inside the new span: gone
                    track.removeClip(c);
                    removed.add(c);
                } else if (startsBefore && endsAfter) {                // spans it: keep both sides
                    saved.add(new Saved(c));
                    Clip right = rightPart(c, e);
                    cutEnd(c, s);
                    track.addClip(right);
                    created.add(right);
                } else if (startsBefore) {                             // sticks out on the left: cut its end back
                    saved.add(new Saved(c));
                    cutEnd(c, s);
                } else {                                               // sticks out on the right: cut its start forward
                    saved.add(new Saved(c));
                    cutStart(c, e);
                }
            }
        } else {
            for (Clip c : existing) {
                float cs = c.startTime, ce = cs + c.duration;
                if (cs >= s - EPS) {                                   // after the insert point: later
                    saved.add(new Saved(c));
                    c.startTime = cs + len;
                } else if (ce > s + EPS) {                             // spans the insert point: cut, second half later
                    saved.add(new Saved(c));
                    Clip right = rightPart(c, s);
                    cutEnd(c, s);
                    right.startTime = s + len;
                    track.addClip(right);
                    created.add(right);
                }
            }
        }

        track.addClip(clip);
        track.sortClips();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public void undo() {
        Track track = timeline.tracks.get(trackIndex);
        track.removeClip(clip);
        for (Clip c : created) track.removeClip(c);
        for (Saved sv : saved) sv.restore();
        for (Clip c : removed) track.addClip(c);
        track.sortClips();
        TrackGrowth.dropTrailingEmptyTracks(timeline, createdTracks);
        createdTracks = 0;
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public String getName() {
        String verb = mode == Mode.INSERT ? "Insert Clip: " : mode == Mode.OVERWRITE ? "Overwrite Clip: " : "Add Clip: ";
        return verb + clip.getClipName();
    }

    // ── cutting ─────────────────────────────────────────────────────────

    /** The source second that plays at timeline time {@code t} (trims + speed). */
    private static float sourceAt(Clip c, float t) {
        return c.startClipTrim + (float) c.timeMap().sourceOffset(t - c.startTime);
    }

    /** A copy of {@code c} that starts at timeline time {@code t} and plays to the old end. {@code c} is not changed. */
    private static Clip rightPart(Clip c, float t) {
        Clip right = new Clip(c);
        float oldEnd = c.startTime + c.duration;
        if (c.originalDuration > 0) {
            float src = sourceAt(c, t);
            right.startTime = t;
            right.setStartClipTrim(src);
        } else {
            right.startTime = t;
            right.duration = oldEnd - t;
        }
        return right;
    }

    /** Ends {@code c} at timeline time {@code t}. */
    private static void cutEnd(Clip c, float t) {
        if (c.originalDuration > 0) {
            float src = sourceAt(c, t);
            c.setEndClipTrim(c.originalDuration - src);
        } else {
            c.duration = t - c.startTime;
        }
    }

    /** Starts {@code c} at timeline time {@code t} (its end stays). */
    private static void cutStart(Clip c, float t) {
        if (c.originalDuration > 0) {
            float src = sourceAt(c, t);
            c.setStartClipTrim(src);
            c.startTime = t;
        } else {
            c.duration = c.startTime + c.duration - t;
            c.startTime = t;
        }
    }
}
