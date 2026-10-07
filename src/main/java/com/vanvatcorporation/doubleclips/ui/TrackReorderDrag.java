package com.vanvatcorporation.doubleclips.ui;

/**
 * The arithmetic of dragging a track up or down the timeline (plain Java, no JavaFX, so it can be tested).
 * <p>
 * While a track is dragged only how things are DRAWN changes: the grabbed row follows the pointer and the rows
 * it passes slide one row out of its way. The track list itself is untouched until the pointer is released,
 * then the move is applied once as one undoable command. Same rules as the iOS editor.
 */
public final class TrackReorderDrag {
    public final int startIndex;
    public final int count;
    public final double rowHeight;
    private double translation;

    public TrackReorderDrag(int startIndex, int count, double rowHeight) {
        this.startIndex = startIndex;
        this.count = count;
        this.rowHeight = rowHeight;
    }

    /** Pointer travel since the grip was grabbed (+ = down). */
    public void setTranslation(double translation) {
        this.translation = translation;
    }

    public double getTranslation() {
        return translation;
    }

    /** Where the track would land if released now. */
    public int targetIndex() {
        if (count <= 0) return 0;
        // Half a row rounds AWAY from zero, as on iOS (Math.round would round -0.5 up to 0).
        double rows = translation / rowHeight;
        int steps = (int) (Math.signum(rows) * Math.floor(Math.abs(rows) + 0.5));
        return Math.min(Math.max(startIndex + steps, 0), count - 1);
    }

    /**
     * How far to move the row at {@code index} from its resting place: the dragged row follows the pointer
     * (kept inside the list), the rows it passes move one row out of the way, the rest stay.
     */
    public double offsetOfRow(int index) {
        if (index == startIndex) {
            double lowest = -startIndex * rowHeight;
            double highest = (count - 1 - startIndex) * rowHeight;
            return Math.min(Math.max(translation, lowest), highest);
        }
        int target = targetIndex();
        if (startIndex < target && index > startIndex && index <= target) return -rowHeight;
        if (startIndex > target && index >= target && index < startIndex) return rowHeight;
        return 0;
    }
}
