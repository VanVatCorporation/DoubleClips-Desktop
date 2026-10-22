package com.vanvatcorporation.doubleclips;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What the person has chosen for each replaceable clip of a template (iOS: TemplateExportModel). No UI, no files read
 * here (a {@link MediaReader} says what a file is): so the rules are testable.
 * <ul>
 *   <li>Two stages, like CapCut: {@link Stage#GROUPS} = one tile per group of clips that share a source (they take ONE
 *       media), {@link Stage#CLIPS} = every clip. A template without groups only has the second.</li>
 *   <li>A group's media goes to every clip of it that has not been given its own ("detached"); when every clip of a group
 *       has, filling the group takes them all back.</li>
 *   <li>A video starts at the template clip's own start inside its source when it is long enough (so the pieces of one
 *       split video stay different pieces), else as early as it can, and runs as long as the template keeps the clip.</li>
 *   <li>A media file is not copied here: clips of a group simply point at the same path.</li>
 * </ul>
 */
public final class TemplateUseModel {

    public enum Kind { EMPTY, VIDEO, IMAGE }

    /** First stage = one tile per group, second = every clip. */
    public enum Stage { GROUPS, CLIPS }

    public static final class Slot {
        public Kind kind = Kind.EMPTY;
        public String path = "";
        public String fileName = "";
        public String typeDescription = "";
        /** Length of the picked media in seconds (videos only; 0 = unknown). */
        public double mediaDuration;
        public double startTrim;
        public double endTrim;
        /** How long the template keeps this clip on screen (seconds; 0 = unknown, an old template). */
        public double targetDuration;
        /** Id of the group the template put this clip in (clips that share one source); null = it stands alone. */
        public String group;
        /** The person gave this clip its own media after the group was filled: the group no longer sets it. */
        public boolean detached;
        /** Where the template's own clip starts inside its source (seconds): where a grouped clip starts by default. */
        public double sourceTrim;

        public boolean isFilled() { return kind != Kind.EMPTY; }
        public double trimmedLength() { return Math.max(0, endTrim - startTrim); }

        /** A video that ends before the template clip does: the clip just ends early. */
        public boolean endsEarly() {
            return kind == Kind.VIDEO && targetDuration > 0 && trimmedLength() < targetDuration - 0.05;
        }

        /** An empty slot that keeps what the template says about the clip. */
        Slot emptied() {
            Slot s = new Slot();
            s.targetDuration = targetDuration;
            s.group = group;
            s.sourceTrim = sourceTrim;
            return s;
        }
    }

    /** One tile of the first stage: a group of clips that share a source, or a clip on its own. */
    public static final class Entry {
        public final String id;
        /** Slot indices, in time order. */
        public final List<Integer> members;
        /** Position of the group among the groups (picks the dot colour); null for a clip on its own. */
        public final Integer colorIndex;
        Entry(String id, List<Integer> members, Integer colorIndex) { this.id = id; this.members = members; this.colorIndex = colorIndex; }
    }

    /** What a picked file is. */
    public static final class Media {
        public final boolean isVideo;
        /** Seconds; 0 = unknown. */
        public final double duration;
        public final String typeDescription;
        public Media(boolean isVideo, double duration, String typeDescription) {
            this.isVideo = isVideo; this.duration = duration; this.typeDescription = typeDescription;
        }
    }

    /** Says what a file is, or throws when it can't be used. */
    public interface MediaReader { Media read(File file) throws IOException; }

    // ---------------------------------------------------------------------------------------------------

    public final List<Slot> slots = new ArrayList<>();
    public Stage stage = Stage.CLIPS;
    private final Consumer<String> log;

    public TemplateUseModel(int clipCount, Consumer<String> log) {
        this.log = log == null ? s -> { } : log;
        for (int i = 0; i < Math.max(0, clipCount); i++) slots.add(new Slot());
        this.log.accept("Template ready: " + slots.size() + " clip slot(s). Click a tile to choose a photo or video.");
    }

    /**
     * The timeline arrived: one slot per replaceable clip, each knowing how long its clip is and which group it is in.
     * Media already chosen is kept when the count did not change.
     */
    public void configure(TemplateTimelineInfo info) {
        boolean anyFilled = slots.stream().anyMatch(Slot::isFilled);
        if (slots.size() != info.slots.size() || !anyFilled) {
            slots.clear();
            for (TemplateTimelineInfo.Slot si : info.slots) {
                Slot s = new Slot();
                s.targetDuration = si.duration;
                slots.add(s);
            }
        } else {
            for (int i = 0; i < slots.size(); i++) slots.get(i).targetDuration = info.slots.get(i).duration;
        }
        for (int i = 0; i < slots.size() && i < info.slots.size(); i++) {
            slots.get(i).group = info.slots.get(i).group;
            slots.get(i).sourceTrim = info.slots.get(i).sourceTrim;
        }
        if (slots.stream().noneMatch(Slot::isFilled)) stage = hasGroups() ? Stage.GROUPS : Stage.CLIPS;
        log.accept(String.format(Locale.ROOT, "Template timeline loaded: %d clip slot(s), %.1fs.", info.slots.size(), info.duration));
    }

    // ---- groups ------------------------------------------------------------------------------------------

    public boolean hasGroups() { return slots.stream().anyMatch(s -> s.group != null); }

    /**
     * The first stage's tiles: clips that share a group are one tile (placed where the group's first clip is), every clip
     * on its own is a tile.
     */
    public List<Entry> entries() {
        List<String> ids = new ArrayList<>();
        List<List<Integer>> members = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        Map<String, Integer> positions = new HashMap<>();
        int groupCount = 0;
        for (int i = 0; i < slots.size(); i++) {
            String group = slots.get(i).group;
            if (group == null) {
                ids.add("slot-" + i);
                members.add(new ArrayList<>(List.of(i)));
                colors.add(null);
                continue;
            }
            Integer at = positions.get(group);
            if (at != null) {
                members.get(at).add(i);
            } else {
                positions.put(group, ids.size());
                ids.add("group-" + group);
                members.add(new ArrayList<>(List.of(i)));
                colors.add(groupCount++);
            }
        }
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) result.add(new Entry(ids.get(i), members.get(i), colors.get(i)));
        return result;
    }

    /** The dot colour index of a clip's group (the same numbering the first stage uses), null for a clip on its own. */
    public Integer colorIndexOfSlot(int index) {
        if (index < 0 || index >= slots.size() || slots.get(index).group == null) return null;
        for (Entry e : entries()) if (e.members.contains(index)) return e.colorIndex;
        return null;
    }

    /** The first stage is complete when every clip has media. */
    public boolean groupsFilled() { return !slots.isEmpty() && slots.stream().allMatch(Slot::isFilled); }

    /** 1-based numbers of the clips that have no media yet. */
    public List<Integer> missing() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) if (!slots.get(i).isFilled()) out.add(i + 1);
        return out;
    }

    // ---- picking -----------------------------------------------------------------------------------------

    /**
     * Second stage: fills slot {@code start}, {@code start + 1}, ... with the picked files; leftovers are discarded. A
     * clip of a group that is filled this way leaves its group (the group no longer sets it). A file that can't be read is
     * reported and does not use up a slot.
     */
    public void fill(int start, List<File> files, MediaReader reader) {
        if (files.isEmpty()) return;
        int index = start;
        for (int k = 0; k < files.size(); k++) {
            File file = files.get(k);
            if (index >= slots.size()) {
                log.accept("Discarded " + (files.size() - k) + " extra item(s): no more slots.");   // only those not tried yet
                break;
            }
            try {
                load(file, reader, index, true);
                index++;
            } catch (IOException e) {
                log.accept("Clip " + (index + 1) + ": couldn't load that item (" + e.getMessage() + ").");
            }
        }
    }

    /**
     * First stage: file k goes to tile {@code start + k}. A group's media goes to every clip of the group that has not been
     * given its own (when every clip of it has, the group takes them all back).
     */
    public void fillEntries(int start, List<File> files, MediaReader reader) {
        List<Entry> list = entries();
        if (files.isEmpty() || start < 0 || start >= list.size()) return;
        int tile = start;
        for (int k = 0; k < files.size(); k++) {
            File file = files.get(k);
            if (tile >= list.size()) {
                log.accept("Discarded " + (files.size() - k) + " extra item(s): no more tiles.");
                break;
            }
            List<Integer> members = new ArrayList<>(list.get(tile).members);
            if (members.stream().allMatch(m -> slots.get(m).detached)) {
                for (int m : members) slots.get(m).detached = false;
            }
            members.removeIf(m -> slots.get(m).detached);
            try {
                load(file, reader, members.get(0), false);
                spread(members.get(0), members);
                if (members.size() > 1) log.accept("Tile " + (tile + 1) + " <- the same media for " + members.size() + " clips.");
                tile++;
            } catch (IOException e) {
                log.accept("Tile " + (tile + 1) + ": couldn't load that item (" + e.getMessage() + ").");
            }
        }
    }

    /** Gives the other clips of a group the media {@code source} holds (the path is shared), each with its own length. */
    private void spread(int source, List<Integer> members) {
        Slot origin = slots.get(source);
        for (int m : members) {
            if (m == source) continue;
            Slot slot = new Slot();
            slot.kind = origin.kind;
            slot.path = origin.path;
            slot.fileName = origin.fileName;
            slot.typeDescription = origin.typeDescription;
            slot.mediaDuration = origin.mediaDuration;
            slot.targetDuration = slots.get(m).targetDuration;
            slot.group = slots.get(m).group;
            slot.sourceTrim = slots.get(m).sourceTrim;
            applyDefaultTrim(slot);
            slots.set(m, slot);
        }
    }

    /** See the class comment: where a video starts, and how much of it plays. */
    private static void applyDefaultTrim(Slot slot) {
        if (slot.kind != Kind.VIDEO) return;
        double media = slot.mediaDuration;
        double want = slot.targetDuration > 0 ? Math.min(media, slot.targetDuration) : media;
        double start = slot.group != null ? Math.min(Math.max(0, slot.sourceTrim), Math.max(0, media - want)) : 0;
        slot.startTrim = start;
        slot.endTrim = Math.min(media, start + want);
    }

    private void load(File file, MediaReader reader, int index, boolean detach) throws IOException {
        Media media = reader.read(file);
        Slot old = slots.get(index);
        Slot slot = new Slot();
        slot.targetDuration = old.targetDuration;
        slot.group = old.group;
        slot.sourceTrim = old.sourceTrim;
        slot.detached = detach && slot.group != null;
        slot.path = file.getPath();
        slot.fileName = file.getName();
        slot.typeDescription = media.typeDescription;
        if (media.isVideo) {
            slot.kind = Kind.VIDEO;
            slot.mediaDuration = Double.isFinite(media.duration) ? Math.max(0, media.duration) : 0;
            applyDefaultTrim(slot);   // the template keeps this clip on screen for targetDuration: start with that much
        } else {
            slot.kind = Kind.IMAGE;
        }
        slots.set(index, slot);
        if (slot.kind == Kind.VIDEO) {
            log.accept(String.format(Locale.ROOT, "Clip %d <- video, %.1fs (%s)", index + 1, slot.mediaDuration, slot.typeDescription));
        } else {
            log.accept("Clip " + (index + 1) + " <- image (" + slot.typeDescription + ")");
        }
    }

    // ---- change ------------------------------------------------------------------------------------------

    public void clear(int index) {
        if (index < 0 || index >= slots.size()) return;
        slots.set(index, slots.get(index).emptied());
        log.accept("Clip " + (index + 1) + " cleared.");
    }

    /** Clears a first-stage tile: every clip of the group that the group sets. */
    public void clearEntry(Entry entry) {
        for (int m : entry.members) {
            if (!slots.get(m).detached || entry.members.size() == 1) slots.set(m, slots.get(m).emptied());
        }
        log.accept("Tile cleared.");
    }

    public void setTrim(int index, double start, double end) {
        if (index < 0 || index >= slots.size() || slots.get(index).kind != Kind.VIDEO) return;
        slots.get(index).startTrim = start;
        slots.get(index).endTrim = end;
        log.accept(String.format(Locale.ROOT, "Clip %d trim: %.1fs - %.1fs", index + 1, start, end));
    }

    // ---- to the renderer ---------------------------------------------------------------------------------

    /** One pick per slot, in slot order (call when {@link #missing()} is empty). */
    public List<TemplateRenderer.SlotPick> picks() {
        List<TemplateRenderer.SlotPick> out = new ArrayList<>();
        for (Slot s : slots) out.add(new TemplateRenderer.SlotPick(s.path, s.kind == Kind.VIDEO, s.startTrim, s.endTrim));
        return out;
    }
}
