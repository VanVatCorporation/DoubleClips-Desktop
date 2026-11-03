package com.vanvatcorporation.doubleclips.ui.components;

import javafx.scene.Cursor;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;

import java.util.function.DoubleConsumer;

/**
 * The seek bar of the Source view: a track with the playhead, and two trim arrows (in / out) that mark the part of the
 * file to use. Pressing on the track seeks (and dragging keeps seeking); dragging an arrow moves the in / out point.
 *
 * Times are in seconds of the file. Everything snaps to {@code frame} seconds. The setters here never call back, only the
 * user's gestures do (onSeek, onRange).
 */
public final class SourceSeekBar extends Pane {

    private static final double PAD = 10;      // room at both ends for the arrows
    private static final double ARROW_W = 10;
    private static final double ARROW_H = 20;
    private static final double HEIGHT = 34;
    private static final double TRACK_H = 6;

    private final Region track = new Region();
    private final Region range = new Region();
    private final Region playhead = new Region();
    private final Polygon inArrow = new Polygon(0, 0, 0, ARROW_H, ARROW_W, ARROW_H / 2);        // flat edge on the left, points right
    private final Polygon outArrow = new Polygon(ARROW_W, 0, ARROW_W, ARROW_H, 0, ARROW_H / 2); // flat edge on the right, points left

    private double max = 1;
    private double position = 0;
    private double in = 0;
    private double out = 1;
    private double frame = 1.0 / 30;
    private boolean trimEnabled = true;

    private DoubleConsumer onSeek = v -> {};
    private Runnable onRange = () -> {};

    private enum Drag { NONE, SEEK, IN, OUT }
    private Drag drag = Drag.NONE;

    public SourceSeekBar() {
        track.setStyle("-fx-background-color: -color-bg-inset; -fx-background-radius: 3;");
        range.setStyle("-fx-background-color: #007AFF; -fx-background-radius: 3;");
        playhead.setStyle("-fx-background-color: white;");
        playhead.setMouseTransparent(true);
        for (Polygon p : new Polygon[]{inArrow, outArrow}) {
            p.setFill(Color.web("#FFB020"));
            p.setStroke(Color.web("#222222"));
            p.setStrokeWidth(1);
            p.setCursor(Cursor.H_RESIZE);
        }
        getChildren().addAll(track, range, playhead, inArrow, outArrow);
        setMinHeight(HEIGHT);
        setPrefHeight(HEIGHT);
        setMaxHeight(HEIGHT);
        setMinWidth(0);

        setOnMousePressed(this::pressed);
        setOnMouseDragged(this::dragged);
        setOnMouseReleased(e -> drag = Drag.NONE);
    }

    // ── configuration (no callbacks) ────────────────────────────────────

    public void setOnSeek(DoubleConsumer c) { onSeek = c == null ? v -> {} : c; }

    /** Called while an arrow is dragged. */
    public void setOnRange(Runnable r) { onRange = r == null ? () -> {} : r; }

    public void setFrame(double seconds) { frame = Math.max(0.0001, seconds); }

    /** False for a file with no length of its own (a picture): no arrows. */
    public void setTrimEnabled(boolean on) {
        trimEnabled = on;
        inArrow.setVisible(on);
        outArrow.setVisible(on);
        requestLayout();
    }

    public void setMax(double seconds) { max = Math.max(0.0001, seconds); requestLayout(); }

    public void setPosition(double seconds) { position = clamp(seconds, 0, max); requestLayout(); }

    public void setRange(double in, double out) {
        this.in = clamp(in, 0, max);
        this.out = clamp(out, this.in, max);
        requestLayout();
    }

    public double getIn() { return in; }
    public double getOut() { return out; }

    // ── gestures ────────────────────────────────────────────────────────

    private void pressed(MouseEvent e) {
        double x = e.getX();
        drag = Drag.SEEK;
        if (trimEnabled) {
            double xi = xOf(in), xo = xOf(out);
            boolean onIn = x >= xi - 2 && x <= xi + ARROW_W;
            boolean onOut = x >= xo - ARROW_W && x <= xo + 2;
            if (onIn && onOut) {
                drag = Math.abs(x - xi) <= Math.abs(x - xo) ? Drag.IN : Drag.OUT;
                if (in == out) drag = x < xi ? Drag.IN : Drag.OUT;   // stacked arrows: the side of the press decides
            } else if (onIn) {
                drag = Drag.IN;
            } else if (onOut) {
                drag = Drag.OUT;
            }
        }
        apply(x);
        e.consume();
    }

    private void dragged(MouseEvent e) {
        apply(e.getX());
        e.consume();
    }

    private void apply(double x) {
        double v = snap(valueAt(x));
        switch (drag) {
            case IN -> {
                in = clamp(v, 0, Math.max(0, out - frame));
                requestLayout();
                onRange.run();
            }
            case OUT -> {
                out = clamp(v, Math.min(max, in + frame), max);
                requestLayout();
                onRange.run();
            }
            case SEEK -> {
                position = clamp(v, 0, max);
                requestLayout();
                onSeek.accept(position);
            }
            default -> { }
        }
    }

    // ── geometry ────────────────────────────────────────────────────────

    private double usable() { return Math.max(1, getWidth() - 2 * PAD); }

    private double xOf(double value) { return PAD + value / max * usable(); }

    private double valueAt(double x) { return (x - PAD) / usable() * max; }

    private double snap(double v) { return Math.round(v / frame) * frame; }

    private static double clamp(double v, double lo, double hi) { return Math.min(Math.max(v, lo), Math.max(lo, hi)); }

    @Override
    protected void layoutChildren() {
        double h = getHeight();
        double ty = (h - TRACK_H) / 2;
        track.resizeRelocate(PAD, ty, usable(), TRACK_H);

        double xi = xOf(in), xo = xOf(out);
        range.setVisible(trimEnabled);
        range.resizeRelocate(xi, ty, Math.max(0, xo - xi), TRACK_H);

        double px = xOf(position);
        playhead.resizeRelocate(px - 1, 2, 2, h - 4);

        inArrow.relocate(xi, (h - ARROW_H) / 2);
        outArrow.relocate(xo - ARROW_W, (h - ARROW_H) / 2);
    }
}
