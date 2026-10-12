package com.vanvatcorporation.doubleclips.ui;

import javafx.scene.Cursor;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import java.util.function.DoubleSupplier;

/**
 * A thin grab strip between two parts of the editor: hover it and the cursor turns into a resize arrow and the
 * strip lights up; drag it and the panel next to it grows or shrinks. It sits in the layout between the panel and
 * its neighbour, so nothing overlaps.
 */
public final class PanelResizeHandle extends Region {
    /** Width (or height) of the strip. */
    public static final double THICKNESS = 5;

    private static final String IDLE = "-fx-background-color: transparent;";
    private static final String ACTIVE = "-fx-background-color: rgba(74,95,255,0.55);";

    private final Region panel;
    private final boolean horizontal;
    private final int sign;               // +1: dragging right/down grows the panel, -1: dragging left/up does
    private final double min;
    private final DoubleSupplier limit;   // the biggest the panel may get right now (the neighbours need room too)
    private double startPos, startSize;
    private boolean dragging;

    private PanelResizeHandle(Region panel, boolean horizontal, int sign, double min, double max, DoubleSupplier limit) {
        this.panel = panel;
        this.horizontal = horizontal;
        this.sign = sign;
        this.min = min;
        this.limit = limit;

        if (horizontal) {
            panel.setMinWidth(min);
            panel.setMaxWidth(max);
            setMinWidth(THICKNESS); setPrefWidth(THICKNESS); setMaxWidth(THICKNESS);
            setCursor(Cursor.H_RESIZE);
        } else {
            panel.setMinHeight(min);
            panel.setMaxHeight(max);
            setMinHeight(THICKNESS); setPrefHeight(THICKNESS); setMaxHeight(THICKNESS);
            setCursor(Cursor.V_RESIZE);
        }
        setStyle(IDLE);

        setOnMouseEntered(e -> { if (!dragging) setStyle(ACTIVE); });
        setOnMouseExited(e -> { if (!dragging) setStyle(IDLE); });
        setOnMousePressed(e -> {
            dragging = true;
            startPos = horizontal ? e.getScreenX() : e.getScreenY();
            startSize = horizontal ? panel.getWidth() : panel.getHeight();
            setStyle(ACTIVE);
            e.consume();
        });
        setOnMouseDragged(e -> {
            double delta = ((horizontal ? e.getScreenX() : e.getScreenY()) - startPos) * this.sign;
            double size = Math.max(this.min, Math.min(startSize + delta, Math.max(this.min, this.limit.getAsDouble())));
            if (horizontal) panel.setPrefWidth(size); else panel.setPrefHeight(size);
            e.consume();
        });
        setOnMouseReleased(e -> {
            dragging = false;
            if (!isHover()) setStyle(IDLE);
            e.consume();
        });
    }

    /** A vertical strip beside {@code panel}: {@code panelOnLeft} = the panel is to the strip's left. */
    public static PanelResizeHandle horizontal(Region panel, boolean panelOnLeft, double min, double max, DoubleSupplier limit) {
        return new PanelResizeHandle(panel, true, panelOnLeft ? 1 : -1, min, max, limit);
    }

    /** A horizontal strip on the top edge of {@code panel} (dragging up makes the panel taller). */
    public static PanelResizeHandle verticalAbove(Region panel, double min, double max, DoubleSupplier limit) {
        return new PanelResizeHandle(panel, false, -1, min, max, limit);
    }
}
