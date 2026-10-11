package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.editing.TransitionClip;

import java.util.Locale;

/** What a transition is, in one line: the cube's tooltip and the shortcut on its clip A. No UI classes, so it is testable. */
public final class TransitionSummary {
    private TransitionSummary() {}

    /** "Glitch Blur · 2.3 s", "Wipe Left · 0.5 s", or "No transition". An unknown style shows under its stored key. */
    public static String of(TransitionClip transition) {
        if (transition == null || transition.effect == null || transition.effect.style == null) return "No transition";
        String key = FXCommandEmitter.FXRegistry.normalizeTransitionStyle(transition.effect.style);
        if (key.isEmpty() || "none".equals(key)) return "No transition";
        TransitionStyle dataDriven = TransitionStyleLoader.get(transition.effect.style);
        String name = dataDriven != null ? dataDriven.getName()
                : FXCommandEmitter.FXRegistry.transitionFXMap.getOrDefault(key, transition.effect.style);
        return String.format(Locale.ROOT, "%s \u00B7 %.1f s", name, transition.duration);
    }
}
