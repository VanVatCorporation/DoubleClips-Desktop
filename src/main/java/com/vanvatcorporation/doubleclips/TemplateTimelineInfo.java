package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.TemplateData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * A template as a timeline (iOS: TemplateTimeline.swift). Every clip has a ROLE for whoever uses the template:
 *   REPLACEABLE  a video/image the user replaces with their own (numbered 1, 2, 3 ... in time order)
 *   LOCKED       a video/image the template keeps ("Lock media for template" in the editor)
 *   AUDIO, EFFECT, TEXT, OTHER (transitions, 3D scenes)
 * The strip under the preview and the slot list of "Use template" both come from here, so they number the same.
 */
public final class TemplateTimelineInfo {

    public enum Role {
        REPLACEABLE(0xFFFFFFFF, "Yours"), LOCKED(0xFF808080, "Locked"), AUDIO(0xFF408CFF, "Audio"),
        EFFECT(0xFF33CC66, "Effect"), TEXT(0xFFFFD633, "Text"), OTHER(0xFFB373FF, "Other");
        public final int colorArgb;
        public final String title;
        Role(int colorArgb, String title) { this.colorArgb = colorArgb; this.title = title; }
    }

    /** One clip as the strip draws it. */
    public static final class StripClip {
        /** The clip in the parsed timeline (null for a legacy strip). */
        public final Clip clip;
        public final double start, duration;
        /** Row from the top (timeline order: track 0 first). */
        public final int row;
        public final Role role;
        /** 1-based number of a replaceable clip, 0 for every other role. */
        public final int slotNumber;
        StripClip(Clip clip, double start, double duration, int row, Role role, int slotNumber) {
            this.clip = clip; this.start = start; this.duration = duration; this.row = row; this.role = role; this.slotNumber = slotNumber;
        }
    }

    /** One replaceable clip: what "Use template" asks the user to fill. */
    public static final class Slot {
        /** The template's own clip in the parsed timeline (null for a legacy slot). */
        public final Clip clip;
        public final int number;            // 1-based, time order (top row first on a tie)
        public final double start, duration; // how long the template keeps this clip on screen
        public final boolean isImage;
        /** The id the author's template gave the clips that share one source ("g1", "g2" ...); null = the clip stands alone. */
        public final String group;
        /** Where the template's own clip starts inside its source (seconds): where a grouped clip starts by default. */
        public final double sourceTrim;
        Slot(Clip clip, int number, double start, double duration, boolean isImage, String group, double sourceTrim) {
            this.clip = clip; this.number = number; this.start = start; this.duration = duration; this.isImage = isImage;
            this.group = group; this.sourceTrim = sourceTrim;
        }
        /** A slot that stands alone (legacy templates). */
        Slot(Clip clip, int number, double start, double duration, boolean isImage) {
            this(clip, number, start, duration, isImage, null, 0);
        }
    }

    public final List<StripClip> clips;
    public final int rowCount;
    public final double duration;
    public final List<Slot> slots;
    /** The parsed timeline; null for the legacy strip drawn from the clip count. A FRESH parse is needed for rendering (it gets edited). */
    public final Timeline timeline;
    /** The canvas the template was made for, when the file carries it; null otherwise. */
    public TemplateTimelineJson.Canvas canvas;

    private TemplateTimelineInfo(List<StripClip> clips, int rowCount, double duration, List<Slot> slots, Timeline timeline) {
        this.clips = clips; this.rowCount = rowCount; this.duration = duration; this.slots = slots; this.timeline = timeline;
    }

    /** The roles that occur, in a fixed order (the legend under the strip). */
    public List<Role> roles() {
        EnumSet<Role> present = EnumSet.noneOf(Role.class);
        for (StripClip c : clips) present.add(c.role);
        return new ArrayList<>(present);
    }

    public static Role roleOf(Clip clip) {
        if (clip.type == null) return Role.OTHER;
        switch (clip.type) {
            case AUDIO: return Role.AUDIO;
            case TEXT: return Role.TEXT;
            case EFFECT: return Role.EFFECT;
            case VIDEO: case IMAGE: return clip.isLockedForTemplate() ? Role.LOCKED : Role.REPLACEABLE;
            default: return Role.OTHER;
        }
    }

    /**
     * The old kind of template: no timeline, only a clip count and a duration. {@code durationOverrideSeconds}: the preview
     * video's own length, for templates whose templateDuration is 0.
     */
    public static TemplateTimelineInfo legacy(TemplateData template, double durationOverrideSeconds) {
        return legacy(template.getTemplateClipCount(), template.getTemplateDuration() / 1000.0, durationOverrideSeconds);
    }

    /** Plain-number form of {@link #legacy(TemplateData, double)}: {@code storedSeconds} is templateDuration / 1000. */
    public static TemplateTimelineInfo legacy(int totalClips, double storedSeconds, double durationOverrideSeconds) {
        int total = Math.max(0, totalClips);
        double duration = storedSeconds > 0 ? storedSeconds : Math.max(0, durationOverrideSeconds);
        if (total <= 0 || duration <= 0) {
            return new TemplateTimelineInfo(new ArrayList<>(), 1, duration, new ArrayList<>(), null);
        }
        double each = duration / total;
        List<StripClip> clips = new ArrayList<>();
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            clips.add(new StripClip(null, i * each, each, 0, Role.REPLACEABLE, i + 1));
            slots.add(new Slot(null, i + 1, i * each, each, false));
        }
        return new TemplateTimelineInfo(clips, 1, duration, slots, null);
    }

    /** The tracks that have clips, top row first (timeline order): the rows of the strip. */
    static List<Track> visibleTracks(Timeline timeline) {
        List<Track> tracks = new ArrayList<>();
        for (Track t : timeline.tracks) if (t.clips != null && !t.clips.isEmpty()) tracks.add(t);
        Collections.sort(tracks, Comparator.comparingInt(t -> t.timelineIndex));
        return tracks;
    }

    /** The replaceable clips in slot order: time order, a tie goes to the top row. ONE ordering for the numbers and the groups. */
    static List<Clip> replaceableInOrder(List<Track> tracks) {
        final class Candidate { final Clip clip; final int row; Candidate(Clip c, int r) { clip = c; row = r; } }
        List<Candidate> candidates = new ArrayList<>();
        for (int row = 0; row < tracks.size(); row++) {
            for (Clip clip : tracks.get(row).clips) {
                if (roleOf(clip) == Role.REPLACEABLE) candidates.add(new Candidate(clip, row));
            }
        }
        Collections.sort(candidates, (a, b) -> a.clip.startTime != b.clip.startTime
                ? Float.compare(a.clip.startTime, b.clip.startTime) : Integer.compare(a.row, b.row));
        List<Clip> ordered = new ArrayList<>();
        for (Candidate c : candidates) ordered.add(c.clip);
        return ordered;
    }

    /** Builds the strip and the slot list from a template timeline. */
    public static TemplateTimelineInfo make(Timeline timeline) {
        List<Track> tracks = visibleTracks(timeline);

        // Slots first (time order, then top row first) so the strip can number them.
        List<Clip> ordered = replaceableInOrder(tracks);
        java.util.Map<Clip, Integer> numbers = new java.util.IdentityHashMap<>();
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            Clip c = ordered.get(i);
            numbers.put(c, i + 1);
            slots.add(new Slot(c, i + 1, c.startTime, c.duration, c.type == ClipType.IMAGE, c.templateGroup, c.startClipTrim));
        }

        List<StripClip> clips = new ArrayList<>();
        double end = 0;
        for (int row = 0; row < tracks.size(); row++) {
            for (Clip c : tracks.get(row).clips) {
                Integer n = numbers.get(c);
                clips.add(new StripClip(c, c.startTime, c.duration, row, roleOf(c), n == null ? 0 : n));
                end = Math.max(end, c.startTime + c.duration);
            }
        }
        return new TemplateTimelineInfo(clips, Math.max(1, tracks.size()), Math.max(end, timeline.duration), slots, timeline);
    }
}
