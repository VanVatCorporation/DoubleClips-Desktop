package com.vanvatcorporation.doubleclips.ui.components;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Cursor;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

/**
 * A two-thumb range slider (JavaFX has none in the core): {@link #lowerProperty()} and {@link #upperProperty()} move
 * between 0 and the maximum, at least {@code minGap} apart. Looks come from .trim-track / .trim-range / .trim-thumb.
 */
public final class TrimRangeSlider extends Pane {
    private static final double THUMB = 20;
    private static final double TRACK = 6;

    private final DoubleProperty lower = new SimpleDoubleProperty();
    private final DoubleProperty upper = new SimpleDoubleProperty();
    private final double max;
    private final double minGap;

    private final Region track = new Region();
    private final Region range = new Region();
    private final Region lowerThumb = new Region();
    private final Region upperThumb = new Region();

    public TrimRangeSlider(double max, double lower, double upper, double minGap) {
        this.max = Math.max(0.0001, max);
        this.minGap = Math.min(minGap, this.max);
        this.lower.set(clamp(lower, 0, this.max));
        this.upper.set(clamp(upper, this.lower.get(), this.max));

        track.getStyleClass().add("trim-track");
        range.getStyleClass().add("trim-range");
        lowerThumb.getStyleClass().add("trim-thumb");
        upperThumb.getStyleClass().add("trim-thumb");
        lowerThumb.setCursor(Cursor.HAND);
        upperThumb.setCursor(Cursor.HAND);
        getChildren().addAll(track, range, lowerThumb, upperThumb);
        setMinHeight(34);
        setPrefHeight(34);

        this.lower.addListener((o, a, b) -> requestLayout());
        this.upper.addListener((o, a, b) -> requestLayout());

        lowerThumb.setOnMouseDragged(e -> {
            double v = valueAt(e.getSceneX());
            this.lower.set(clamp(v, 0, Math.max(0, this.upper.get() - this.minGap)));
        });
        upperThumb.setOnMouseDragged(e -> {
            double v = valueAt(e.getSceneX());
            this.upper.set(clamp(v, Math.min(this.max, this.lower.get() + this.minGap), this.max));
        });
    }

    public DoubleProperty lowerProperty() { return lower; }
    public DoubleProperty upperProperty() { return upper; }

    private static double clamp(double v, double lo, double hi) { return Math.min(Math.max(v, lo), Math.max(lo, hi)); }

    private double usableWidth() { return Math.max(1, getWidth() - THUMB); }

    private double xOf(double value) { return value / max * usableWidth(); }

    /** The slider value under a scene x position (the thumb's centre follows the pointer). */
    private double valueAt(double sceneX) {
        double localX = sceneToLocal(sceneX, 0).getX();
        return (localX - THUMB / 2) / usableWidth() * max;
    }

    @Override
    protected void layoutChildren() {
        double h = getHeight();
        double lx = xOf(lower.get()), ux = xOf(upper.get());
        track.resizeRelocate(THUMB / 2, (h - TRACK) / 2, usableWidth(), TRACK);
        range.resizeRelocate(THUMB / 2 + lx, (h - TRACK) / 2, Math.max(0, ux - lx), TRACK);
        lowerThumb.resizeRelocate(lx, (h - THUMB) / 2, THUMB, THUMB);
        upperThumb.resizeRelocate(ux, (h - THUMB) / 2, THUMB, THUMB);
    }
}
