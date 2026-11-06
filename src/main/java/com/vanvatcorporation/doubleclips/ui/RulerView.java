package com.vanvatcorporation.doubleclips.ui;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Line;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The timeline ruler. It is as wide as the timeline (so the ruler ScrollPane scrolls with the tracks), but instead
 * of one {@code Line}/{@code Text} node per tick for the WHOLE timeline it keeps a few canvases covering just the
 * visible part (plus a margin) and draws only those ticks ({@link RulerTicks}). Scrolling inside the margin costs
 * nothing; leaving it, zooming or changing the timeline length repaints a few hundred strokes.
 * <p>
 * Colours and the font come from the stylesheet (.ruler-tick-major / .ruler-tick-minor / .ruler-text) through invisible
 * probe nodes, so a theme change recolours the ruler like it did the node-based one.
 */
final class RulerView extends Pane {

    static final double HEIGHT = 30;

    /** A canvas stays well under the GPU's texture limit (retina doubles the backing size). */
    private static final double SEGMENT_WIDTH = 2048;
    /** At most this many canvases (6 * 2048 px covers any realistic viewport + margin). */
    private static final int MAX_SEGMENTS = 6;

    private final List<Canvas> segments = new ArrayList<>();

    // Probes: only here so the stylesheet resolves colours / font for us. The group is fully transparent,
    // unmanaged and mouse-transparent; it still takes part in CSS (which an invisible node might not).
    private final Group probeHost = new Group();
    private final Line probeMajor = new Line();
    private final Line probeMinor = new Line();
    private final Text probeLabel = new Text("0");
    private final Text probeSecondLabel = new Text("0");

    // What the canvases currently show
    private double windowStart = -1, windowEnd = -1;
    private double lastContentWidth = -1;
    private float lastPps = -1, lastDuration = -1;
    private int lastFps = -1;
    private double lastScrollPx = 0, lastViewportWidth = 0;
    private boolean dirty = true;
    private boolean repaintQueued = false;

    RulerView() {
        getStyleClass().add("timeline-ruler-pane");
        setPickOnBounds(true);
        setPrefHeight(HEIGHT);
        setMinHeight(HEIGHT);
        setMaxHeight(HEIGHT);

        probeMajor.getStyleClass().add("ruler-tick-major");
        probeMinor.getStyleClass().add("ruler-tick-minor");
        probeLabel.getStyleClass().add("ruler-text");
        probeSecondLabel.getStyleClass().add("ruler-text");
        probeSecondLabel.setStyle("-fx-fill: -color-fg-default;");     // what the old ruler gave the start of a second
        probeHost.getChildren().addAll(probeMajor, probeMinor, probeLabel, probeSecondLabel);
        probeHost.setOpacity(0);
        probeHost.setMouseTransparent(true);
        probeHost.setManaged(false);
        getChildren().add(probeHost);

        // The stylesheet is applied after the first paint (and again when the theme changes): when it changes what
        // the probes resolve to, repaint with the new colours.
        probeMajor.strokeProperty().addListener((o, a, b) -> paintSoon());
        probeMinor.strokeProperty().addListener((o, a, b) -> paintSoon());
        probeLabel.fillProperty().addListener((o, a, b) -> paintSoon());
        probeSecondLabel.fillProperty().addListener((o, a, b) -> paintSoon());
        probeLabel.fontProperty().addListener((o, a, b) -> paintSoon());
        probeMajor.opacityProperty().addListener((o, a, b) -> paintSoon());
    }

    /** Marks the ruler stale and repaints once, after the current pulse (several probe properties change together). */
    private void paintSoon() {
        dirty = true;
        if (repaintQueued || lastContentWidth < 0) return;      // nothing painted yet: the first update() will paint
        repaintQueued = true;
        Platform.runLater(() -> {
            repaintQueued = false;
            update(lastContentWidth, lastPps, lastDuration, lastFps, lastScrollPx, lastViewportWidth);
        });
    }

    /** Forces a repaint at the next {@link #update}. */
    void invalidatePaint() {
        dirty = true;
    }

    /**
     * Makes the ruler show the right ticks for the current timeline length, zoom and scroll position. Cheap when
     * nothing it shows has changed (the usual case while scrolling or playing).
     *
     * @param fps           the project's frame rate: the frame ticks and their labels count in it
     * @param contentWidth  the ruler's (and the tracks') full width in pixels
     * @param scrollPx      content x at the left edge of the viewport
     * @param viewportWidth the visible width (0 before the first layout)
     */
    void update(double contentWidth, float pixelsPerSecond, float durationSeconds, int fps, double scrollPx, double viewportWidth) {
        if (viewportWidth <= 0) viewportWidth = 1600;            // not laid out yet: a normal window
        scrollPx = Math.max(0, scrollPx);
        lastScrollPx = scrollPx;
        lastViewportWidth = viewportWidth;

        boolean geometryChanged = contentWidth != lastContentWidth || pixelsPerSecond != lastPps || durationSeconds != lastDuration || fps != lastFps;
        double visibleFrom = scrollPx, visibleTo = scrollPx + viewportWidth;
        boolean covered = windowStart >= 0 && visibleFrom >= windowStart && visibleTo <= windowEnd;
        if (!dirty && !geometryChanged && covered) return;

        // The window to paint: what is visible plus half a viewport each side, in whole pixels.
        double margin = viewportWidth / 2.0;
        double start = Math.floor(Math.max(0, scrollPx - margin));
        double end = Math.ceil(Math.min(Math.max(contentWidth, visibleTo), visibleTo + margin));
        double maxSpan = SEGMENT_WIDTH * MAX_SEGMENTS;
        if (end - start > maxSpan) end = start + maxSpan;

        // Resolve the stylesheet's colours and font once per repaint.
        Paint maj = probeMajor.getStroke(), min = probeMinor.getStroke(), lab = probeLabel.getFill();
        final Color majorColor = asColor(maj, Color.GRAY, probeMajor.getOpacity());
        final Color minorColor = asColor(min, Color.GRAY, 1.0);
        final Color labelColor = asColor(lab, Color.GRAY, 1.0);
        final Color secondLabelColor = asColor(probeSecondLabel.getFill(), labelColor, 1.0);
        final Font font = probeLabel.getFont() != null ? probeLabel.getFont() : Font.font(9);
        final Font boldFont = Font.font(font.getFamily(), javafx.scene.text.FontWeight.BOLD, font.getSize());

        int needed = (int) Math.max(1, Math.ceil((end - start) / SEGMENT_WIDTH));
        while (segments.size() < needed) {
            Canvas c = new Canvas();
            c.setManaged(false);
            c.setMouseTransparent(true);   // hovering / clicking the ruler must reach this pane
            c.setHeight(HEIGHT);
            segments.add(c);
            getChildren().add(0, c);       // behind the (transparent) probes
        }

        for (int i = 0; i < segments.size(); i++) {
            Canvas canvas = segments.get(i);
            double segStart = start + i * SEGMENT_WIDTH;
            double segEnd = Math.min(end, segStart + SEGMENT_WIDTH);
            if (i >= needed || segEnd <= segStart) {
                canvas.setVisible(false);              // unused segment: keep it, just don't draw it
                continue;
            }
            canvas.setVisible(true);
            canvas.setLayoutX(segStart);
            canvas.setLayoutY(0);
            double w = segEnd - segStart;
            if (canvas.getWidth() != w) canvas.setWidth(w);
            paintSegment(canvas, segStart, segEnd, pixelsPerSecond, durationSeconds, fps,
                    majorColor, minorColor, labelColor, secondLabelColor, font, boldFont);
        }

        windowStart = start;
        windowEnd = end;
        lastContentWidth = contentWidth;
        lastPps = pixelsPerSecond;
        lastDuration = durationSeconds;
        lastFps = fps;
        dirty = false;
    }

    private static void paintSegment(Canvas canvas, double segStart, double segEnd, float pps, float duration, int fps,
                                     Color majorColor, Color minorColor, Color labelColor, Color secondLabelColor,
                                     Font font, Font boldFont) {
        final GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), HEIGHT);

        RulerTicks.generate(pps, duration, fps, segStart, segEnd, new RulerTicks.Sink() {
            @Override public void tick(double x, double topY, int kind) {
                double lx = x - segStart;
                // the old ruler's looks: a second is tall and thick, the interval's tick plain, a frame faint
                gc.setLineWidth(kind == RulerTicks.SECOND ? 2 : 1);
                gc.setStroke(kind == RulerTicks.MINOR ? withOpacity(minorColor, 0.5) : majorColor);
                gc.strokeLine(lx, topY, lx, HEIGHT);
            }

            @Override public void label(double x, String text, boolean second) {
                gc.setFont(second ? boldFont : font);
                gc.setFill(second ? secondLabelColor : withOpacity(labelColor, 0.7));
                gc.fillText(text, x - segStart, 9);
            }
        });
    }

    private static Color asColor(Paint paint, Color fallback, double opacity) {
        Color c = paint instanceof Color ? (Color) paint : fallback;
        return withOpacity(c, opacity);
    }

    private static Color withOpacity(Color c, double opacity) {
        return Color.color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(1, c.getOpacity() * opacity)));
    }
}
