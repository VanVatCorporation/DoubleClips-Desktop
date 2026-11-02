package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.editing.EffectTemplate;
import com.vanvatcorporation.doubleclips.data.editing.TransitionClip;

/**
 * Giving a transition a style from outside its properties panel (a tile dropped on its knot), and taking that back for undo. The same
 * rule the panel's Type picker follows: the style is stored on the transition's effect, and a data-driven style (authored for a
 * length, e.g. Glitch Blur: 2.3 s) starts from its own default duration; the other styles keep the duration the transition has.
 */
public final class TransitionStyleApply {

    private TransitionStyleApply() {}

    /** What a style change touches, kept for undo. */
    public static final class Before {
        private final boolean hadEffect;
        private final String style;
        private final float effectDuration;
        private final float duration;

        private Before(boolean hadEffect, String style, float effectDuration, float duration) {
            this.hadEffect = hadEffect;
            this.style = style;
            this.effectDuration = effectDuration;
            this.duration = duration;
        }
    }

    public static Before capture(TransitionClip tc) {
        EffectTemplate effect = tc.effect;
        return new Before(effect != null, effect != null ? effect.style : null, effect != null ? effect.duration : 0f, tc.duration);
    }

    /** Sets the style of {@code tc} to {@code chosen}. */
    public static void apply(TransitionClip tc, TransitionStyleCatalog.Entry chosen) {
        if (tc.effect == null) tc.effect = new EffectTemplate(chosen.id, tc.duration, tc.startTime);
        else tc.effect.style = chosen.id;
        TransitionStyle dataStyle = chosen.dataDriven ? TransitionStyleLoader.get(chosen.id) : null;
        if (dataStyle != null) {
            tc.duration = (float) Math.round(dataStyle.getDefaultDuration() * 10f) / 10f;
            tc.effect.duration = tc.duration;
        }
    }

    /** Puts back what {@link #capture} saw. */
    public static void restore(TransitionClip tc, Before before) {
        tc.duration = before.duration;
        if (!before.hadEffect) {
            tc.effect = null;
            return;
        }
        if (tc.effect == null) tc.effect = new EffectTemplate(before.style, before.effectDuration, tc.startTime);
        tc.effect.style = before.style;
        tc.effect.duration = before.effectDuration;
    }
}
