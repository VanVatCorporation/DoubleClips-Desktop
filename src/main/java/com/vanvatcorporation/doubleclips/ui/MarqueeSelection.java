package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * Hit-testing for the click-drag selection rectangle ("marquee"), kept free of JavaFX so it can be tested alone.
 *
 * The rectangle is in tracks-pane coordinates. A clip is hit when the rectangle TOUCHES it (like an OS file
 * manager), it does not have to be fully inside. Clips are tested from the timeline data, not from their nodes,
 * so clips scrolled out of view are still found.
 */
public final class MarqueeSelection {
    private MarqueeSelection() {}

    /**
     * @param pixelsPerSecond  current timeline zoom
     * @param rowHeight        TRACK_HEIGHT + TRACK_SPACING
     * @param clipTopInset     gap between the top of a track row and the clip (the clip nodes sit at row*rowHeight + 3)
     * @param clipHeight       height of a clip node
     * @return the hit clips in timeline order (track, then start time)
     */
    public static List<Clip> clipsInRect(Timeline timeline,
                                         double x0, double y0, double x1, double y1,
                                         double pixelsPerSecond, double rowHeight,
                                         double clipTopInset, double clipHeight) {
        double left = Math.min(x0, x1), right = Math.max(x0, x1);
        double top = Math.min(y0, y1), bottom = Math.max(y0, y1);

        List<Clip> hits = new ArrayList<>();
        for (Track track : timeline.tracks) {
            double clipTop = track.timelineIndex * rowHeight + clipTopInset;
            double clipBottom = clipTop + clipHeight;
            if (clipBottom < top || clipTop > bottom) continue;     // this track row is not touched at all
            for (Clip clip : track.clips) {
                double clipLeft = clip.startTime * pixelsPerSecond;
                double clipRight = (clip.startTime + clip.duration) * pixelsPerSecond;
                if (clipRight >= left && clipLeft <= right) hits.add(clip);
            }
        }
        return hits;
    }

    /** True once the pointer has moved far enough from the press to count as a drag rather than a click. */
    public static boolean exceedsThreshold(double startX, double startY, double x, double y, double threshold) {
        double dx = x - startX, dy = y - startY;
        return dx * dx + dy * dy >= threshold * threshold;
    }
}
