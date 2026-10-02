package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Moves one or MANY clips as a single undo step (a group drag).
 *
 * Every clip carries its own old/new start time and track. If a target track does not exist
 * yet (the group was dropped below the last track) the missing tracks are created on execute
 * and removed again on undo.
 */
public class MoveClipsCommand implements Command {

    public static final class Entry {
        public final Clip clip;
        public final float oldStart, newStart;
        public final int oldTrack, newTrack;

        public Entry(Clip clip, float oldStart, float newStart, int oldTrack, int newTrack) {
            this.clip = clip;
            this.oldStart = oldStart;
            this.newStart = newStart;
            this.oldTrack = oldTrack;
            this.newTrack = newTrack;
        }
    }

    private final Timeline timeline;
    private final List<Entry> entries;
    private final Runnable onUpdate;
    private int createdTracks = 0;

    public MoveClipsCommand(Timeline timeline, List<Entry> entries, Runnable onUpdate) {
        this.timeline = timeline;
        this.entries = new ArrayList<>(entries);
        this.onUpdate = onUpdate;
    }

    @Override
    public void execute() {
        int needed = 0;
        for (Entry e : entries) needed = Math.max(needed, e.newTrack + 1);
        createdTracks = TrackGrowth.ensureTracks(timeline, needed);
        apply(true);
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public void undo() {
        apply(false);
        TrackGrowth.dropTrailingEmptyTracks(timeline, createdTracks);
        createdTracks = 0;
        if (onUpdate != null) onUpdate.run();
    }

    /** Two phases (take everything out, then put everything in) so one clip's move never disturbs another's. */
    private void apply(boolean forward) {
        for (Entry e : entries) {
            timeline.tracks.get(e.clip.trackIndex).removeClip(e.clip);
        }
        Set<Integer> touched = new HashSet<>();
        for (Entry e : entries) {
            e.clip.startTime = forward ? e.newStart : e.oldStart;
            int target = forward ? e.newTrack : e.oldTrack;
            timeline.tracks.get(target).addClip(e.clip); // also sets clip.trackIndex
            touched.add(target);
        }
        for (int t : touched) timeline.tracks.get(t).sortClips();
    }

    @Override
    public String getName() {
        return entries.size() == 1
                ? "Move Clip: " + entries.get(0).clip.getClipName()
                : "Move " + entries.size() + " Clips";
    }
}
