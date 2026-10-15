package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.editing.Clip;

/**
 * Which mask of which clip is being edited ON THE CANVAS right now. The properties panel ({@link MaskSection}) sets it,
 * the preview gizmo reads it: while a mask is being edited the gizmo shows that mask's shape and handles instead of
 * the clip's own move / scale / rotate box. Plain data, no JavaFX.
 */
public final class MaskEditState {

    private Clip clip;
    private int index = -1;
    private boolean active;
    private Runnable onChange = () -> {};
    private Runnable onLiveChange = () -> {};
    private Clip liveClip;
    private java.util.List<com.vanvatcorporation.doubleclips.data.editing.ClipMask> liveMasks;

    /** Called (on the FX thread) whenever the state actually changes; the gizmo redraws. */
    public void setOnChange(Runnable onChange) {
        this.onChange = onChange == null ? () -> {} : onChange;
    }

    /** The Mask panel's rows follow an in-progress edit through this (see {@link #setLive}). */
    public void setOnLiveChange(Runnable onLiveChange) {
        this.onLiveChange = onLiveChange == null ? () -> {} : onLiveChange;
    }

    /**
     * An edit that is still going on (a canvas drag, a slider drag): the clip's masks as they WOULD be when it ends,
     * or null when no edit is in flight. Nothing is committed to the clip; whoever shows masks (the canvas handles,
     * the panel rows) reads {@link #liveFor} first.
     */
    public void setLive(Clip clip, java.util.List<com.vanvatcorporation.doubleclips.data.editing.ClipMask> masks) {
        this.liveClip = masks == null ? null : clip;
        this.liveMasks = masks;
        onChange.run();
        onLiveChange.run();
    }

    /** The in-flight masks of {@code c}, or null (read the clip's own). */
    public java.util.List<com.vanvatcorporation.doubleclips.data.editing.ClipMask> liveFor(Clip c) {
        return c != null && c == liveClip ? liveMasks : null;
    }

    public void set(Clip clip, int index, boolean active) {
        if (this.clip == clip && this.index == index && this.active == active) return;
        this.clip = clip;
        this.index = index;
        this.active = active;
        onChange.run();
    }

    /** The index of the mask being edited on the canvas for {@code c}, or -1 when {@code c} is not in mask-edit mode. */
    public int editingIndex(Clip c) {
        if (!active || c == null || c != clip || c.masks == null) return -1;
        return index >= 0 && index < c.masks.size() ? index : -1;
    }
}
