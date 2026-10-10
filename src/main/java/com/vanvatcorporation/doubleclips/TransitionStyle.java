package com.vanvatcorporation.doubleclips;

/**
 * One data-driven transition style (see {@link TransitionStyleLoader}): the look of the outgoing clip A before
 * the cut and of the incoming clip B after it, each a {@link ClipAnimation} over the WHOLE transition window
 * (p = 0 .. 1). Immutable and thread-safe. Plain Java, same as iOS's {@code TransitionStyle}.
 */
public final class TransitionStyle {

    private final String id;
    private final String name;
    private final String description;
    private final float defaultDuration;
    private final double cut;
    private final ClipAnimation from;
    private final ClipAnimation to;

    TransitionStyle(String id, String name, String description, float defaultDuration, double cut,
                    ClipAnimation from, ClipAnimation to) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.defaultDuration = defaultDuration;
        this.cut = cut;
        this.from = from;
        this.to = to;
    }

    /** The key a transition stores as its style (what {@code TransitionClip.effect.style} holds). */
    public String getId() { return id; }
    public String getName() { return name; }
    /** The file's description, or null. */
    public String getDescription() { return description; }
    /** Suggested duration in seconds (the editor's duration default for this style). */
    public float getDefaultDuration() { return defaultDuration; }
    /** Window progress at which the picture switches from A to B (0.05 .. 0.95). */
    public double getCut() { return cut; }
    /** The look of A, evaluated over the whole window. */
    public ClipAnimation getFrom() { return from; }
    /** The look of B, evaluated over the whole window. */
    public ClipAnimation getTo() { return to; }

    /** True while A is the picture drawn, false once B has taken over. */
    public boolean showsFrom(float progress) { return progress < cut; }

    /** The channel values to apply to whichever clip is drawn at this window progress (0..1). */
    public ClipAnimationFrame frameAt(float progress) {
        return (showsFrom(progress) ? from : to).evaluate(progress);
    }
}
