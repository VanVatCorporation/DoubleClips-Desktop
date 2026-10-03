package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.List;

/**
 * The logic behind the clip editor's in / out animation pickers, kept free of any UI toolkit
 * (Android's equivalent is the model half of AnimationPicker): what the dropdown offers, how a
 * saved id that isn't installed is shown, and what duration a freshly picked animation gets.
 * <p>
 * The choices come from the {@link ClipAnimationLoader} registry - "none" plus every installed
 * animation of the requested direction - instead of a hardcoded list; each is shown by its display
 * name while its id is what gets stored in {@code AnimationClip.type}.
 */
public final class AnimationChoices {

    public static final String NONE = "none";

    /** One dropdown entry: stored id + shown label. Equality is by id. */
    public static final class Choice {
        public final String id;
        public final String label;

        Choice(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override public String toString() { return label; }
        @Override public int hashCode() { return id.hashCode(); }
        @Override public boolean equals(Object o) { return o instanceof Choice && ((Choice) o).id.equals(id); }
    }

    private AnimationChoices() {}

    /** A clip's saved type, with "nothing saved" (null / empty) meaning "none". */
    public static String normalize(String type) {
        return (type == null || type.isEmpty()) ? NONE : type;
    }

    /**
     * Everything the dropdown offers for one direction. If {@code currentId} is a saved animation that
     * isn't installed (a pack that was removed, a project from another machine), it is kept as an extra
     * last entry labelled "(not installed)" so it stays selected and is not silently dropped on save.
     */
    public static List<Choice> choices(ClipAnimation.Direction direction, String currentId) {
        ClipAnimationAssets.loadAll(); // built-ins + installed packs; no-op after the first call
        List<Choice> out = new ArrayList<>();
        out.add(new Choice(NONE, "None"));
        for (ClipAnimation a : ClipAnimationLoader.list(direction)) out.add(new Choice(a.getId(), a.getName()));
        String id = normalize(currentId);
        if (indexOf(out, id) < 0) out.add(new Choice(id, id + " (not installed)"));
        return out;
    }

    public static int indexOf(List<Choice> choices, String id) {
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    /** The animation's own default duration in seconds (e.g. 1.5 for unfold), or 0 for "none" / an unknown id. */
    public static float defaultDurationOf(String id) {
        ClipAnimation a = ClipAnimationLoader.get(id);
        return a == null ? 0f : a.getDefaultDuration();
    }
}
