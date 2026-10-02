package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Adds one or many clips (e.g. a paste) as a single undo step, creating missing tracks. */
public class AddClipsCommand implements Command {

    public static final class Entry {
        public final Clip clip;
        public final int track;

        public Entry(Clip clip, int track) {
            this.clip = clip;
            this.track = track;
        }
    }

    private final Timeline timeline;
    private final List<Entry> entries;
    private final Runnable onUpdate;
    private int createdTracks = 0;

    public AddClipsCommand(Timeline timeline, List<Entry> entries, Runnable onUpdate) {
        this.timeline = timeline;
        this.entries = new ArrayList<>(entries);
        this.onUpdate = onUpdate;
    }

    @Override
    public void execute() {
        int needed = 0;
        for (Entry e : entries) needed = Math.max(needed, e.track + 1);
        createdTracks = TrackGrowth.ensureTracks(timeline, needed);
        Set<Integer> touched = new HashSet<>();
        for (Entry e : entries) {
            timeline.tracks.get(e.track).addClip(e.clip);
            touched.add(e.track);
        }
        for (int t : touched) timeline.tracks.get(t).sortClips();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public void undo() {
        for (Entry e : entries) {
            timeline.tracks.get(e.clip.trackIndex).removeClip(e.clip);
        }
        TrackGrowth.dropTrailingEmptyTracks(timeline, createdTracks);
        createdTracks = 0;
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public String getName() {
        return entries.size() == 1
                ? "Add Clip: " + entries.get(0).clip.getClipName()
                : "Add " + entries.size() + " Clips";
    }
}
