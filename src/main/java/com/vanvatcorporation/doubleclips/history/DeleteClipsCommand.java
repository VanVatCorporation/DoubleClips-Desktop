package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deletes one or many clips as a single undo step; undo puts each back on the track it came from. */
public class DeleteClipsCommand implements Command {

    private final Timeline timeline;
    private final List<Clip> clips;
    private final int[] trackIndexes;
    private final Runnable onUpdate;

    public DeleteClipsCommand(Timeline timeline, List<Clip> clips, Runnable onUpdate) {
        this.timeline = timeline;
        this.clips = new ArrayList<>(clips);
        this.trackIndexes = new int[this.clips.size()];
        for (int i = 0; i < this.clips.size(); i++) trackIndexes[i] = this.clips.get(i).trackIndex;
        this.onUpdate = onUpdate;
    }

    @Override
    public void execute() {
        for (int i = 0; i < clips.size(); i++) {
            timeline.tracks.get(trackIndexes[i]).removeClip(clips.get(i));
        }
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public void undo() {
        Set<Integer> touched = new HashSet<>();
        for (int i = 0; i < clips.size(); i++) {
            timeline.tracks.get(trackIndexes[i]).addClip(clips.get(i));
            touched.add(trackIndexes[i]);
        }
        for (int t : touched) timeline.tracks.get(t).sortClips();
        if (onUpdate != null) onUpdate.run();
    }

    @Override
    public String getName() {
        return clips.size() == 1 ? "Delete Clip: " + clips.get(0).getClipName() : "Delete " + clips.size() + " Clips";
    }
}
