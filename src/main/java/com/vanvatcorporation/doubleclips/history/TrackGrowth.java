package com.vanvatcorporation.doubleclips.history;

import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

/**
 * Shared helper for the group commands: grow the timeline so a track index exists, and
 * take those tracks away again on undo. Only trailing tracks that are EMPTY are removed,
 * so an undo can never delete a clip.
 */
final class TrackGrowth {
    private TrackGrowth() {}

    /** Adds empty tracks until {@code requiredTracks} exist. Returns how many were added. */
    static int ensureTracks(Timeline timeline, int requiredTracks) {
        int added = 0;
        while (timeline.tracks.size() < requiredTracks) {
            timeline.addTrack(new Track());
            added++;
        }
        return added;
    }

    /** Removes up to {@code count} trailing tracks, stopping at the first one that still holds a clip. */
    static void dropTrailingEmptyTracks(Timeline timeline, int count) {
        for (int i = 0; i < count && !timeline.tracks.isEmpty(); i++) {
            Track last = timeline.tracks.get(timeline.tracks.size() - 1);
            if (!last.clips.isEmpty()) return;
            timeline.removeTrack(last);
        }
    }
}
