package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Grouping the same clip (CapCut's "same media" dots; iOS: TemplateGrouping). Gives the replaceable clips of a timeline
 * that share ONE source (the same file: the pieces of one split video, or one photo used many times) a common
 * {@code templateGroup} id: "g1", "g2" ... in order of first appearance. A clip whose source is used once stays alone.
 * Run it on a COPY of the timeline (the project is left untouched), when a template is published.
 */
public final class TemplateGrouping {
    private TemplateGrouping() {}

    /** @return how many groups were made. */
    public static int assign(Timeline timeline) {
        // The same order as the slots, so the ids follow the numbering people see.
        List<Track> tracks = TemplateTimelineInfo.visibleTracks(timeline);
        List<Clip> ordered = TemplateTimelineInfo.replaceableInOrder(tracks);

        Map<String, Integer> counts = new HashMap<>();
        for (Clip c : ordered) counts.merge(key(c), 1, Integer::sum);

        Map<String, String> ids = new HashMap<>();
        int next = 1;
        for (Clip c : ordered) {
            String k = key(c);
            if (counts.get(k) <= 1) { c.templateGroup = null; continue; }
            String id = ids.get(k);
            if (id == null) { id = "g" + next++; ids.put(k, id); }
            c.templateGroup = id;
        }
        return ids.size();
    }

    /** A video and a photo with the same file name are different sources. */
    private static String key(Clip c) {
        return (c.type == ClipType.IMAGE ? "image|" : "video|") + (c.getClipName() == null ? "" : c.getClipName());
    }
}
