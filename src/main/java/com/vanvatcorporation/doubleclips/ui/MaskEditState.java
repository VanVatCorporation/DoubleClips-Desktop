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

    /** Called (on the FX thread) whenever the state actually changes; the gizmo redraws. */
    public void setOnChange(Runnable onChange) {
        this.onChange = onChange == null ? () -> {} : onChange;
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
