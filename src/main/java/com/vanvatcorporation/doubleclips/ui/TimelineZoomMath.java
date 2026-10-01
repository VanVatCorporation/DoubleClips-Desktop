package com.vanvatcorporation.doubleclips.ui;

/**
 * The arithmetic behind cursor-anchored timeline zoom, kept free of JavaFX so it can be tested
 * on its own. Everything is in pixels of the timeline CONTENT (time * pixelsPerSecond) unless
 * a name says "viewport".
 * <p>
 * A ScrollPane places its content so that
 * {@code scrollPx = hvalue * (contentWidth - viewportWidth)} is the content x shown at the
 * viewport's left edge (hvalue in [0,1]); when the content is narrower than the viewport there
 * is nothing to scroll and the offset is 0.
 */
public final class TimelineZoomMath {

    private TimelineZoomMath() {}

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Content x currently shown at the viewport's left edge. */
    public static double scrollPx(double hvalue, double contentWidth, double viewportWidth) {
        double range = contentWidth - viewportWidth;
        return range > 0 ? clamp(hvalue, 0, 1) * range : 0;
    }

    /**
     * The timeline time under the cursor (or at time 0, see below) plus where on screen it is,
     * captured BEFORE the zoom so that it can be put back at the same screen position after.
     *
     * @param cursorViewportX cursor x relative to the viewport's left edge, or NaN when the
     *                        cursor is not over the timeline (zoom slider, keyboard ...)
     * @return {anchorTimeSeconds, anchorViewportX}. With no cursor the anchor is time 0 held
     *         wherever it currently is on screen (viewport x = -scrollPx), which means the
     *         scroll offset stays exactly as it is and the content grows/shrinks from 0s.
     */
    public static double[] captureAnchor(double cursorViewportX, double pixelsPerSecond, double scrollPx) {
        if (Double.isNaN(cursorViewportX)) {
            return new double[]{0.0, -scrollPx};
        }
        double contentX = cursorViewportX + scrollPx;
        return new double[]{contentX / pixelsPerSecond, cursorViewportX};
    }

    /**
     * hvalue that, at the NEW zoom and content width, puts the anchor time back at the same
     * viewport x it had before the zoom. Clamped to the scrollable range.
     */
    public static double hvalueForAnchor(double anchorTimeSeconds, double anchorViewportX,
                                         double newPixelsPerSecond, double newContentWidth,
                                         double viewportWidth) {
        double range = newContentWidth - viewportWidth;
        if (range <= 0) return 0;
        double desiredScroll = anchorTimeSeconds * newPixelsPerSecond - anchorViewportX;
        return clamp(desiredScroll / range, 0, 1);
    }

    /**
     * Whether a clip (content x / width) is within one viewport-width of the visible area on
     * either side. Used to skip background work (thumbnails, waveforms) for clips that are far
     * off-screen; it is done lazily when they scroll near.
     */
    public static boolean nearViewport(double clipX, double clipWidth, double scrollPx,
                                       double viewportWidth, double margin) {
        if (viewportWidth <= 0) return true; // not laid out yet - don't starve the first build
        double left = scrollPx - margin;
        double right = scrollPx + viewportWidth + margin;
        return clipX + clipWidth >= left && clipX <= right;
    }
}
