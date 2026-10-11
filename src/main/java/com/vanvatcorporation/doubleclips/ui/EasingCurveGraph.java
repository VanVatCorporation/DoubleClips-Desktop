package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.editing.AnimatedProperty;
import com.vanvatcorporation.doubleclips.data.editing.EasingType;
import javafx.animation.AnimationTimer;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;

/**
 * A square graph of one easing curve (the iOS easing graph): time runs left to right, value bottom to top, linear
 * is the straight diagonal. Faint dashed guides mark value 0 and 1 and a dot marks each end. The curve is sampled
 * from {@link AnimatedProperty#ease}, so it is always the curve the renderer uses; the value range grows to fit
 * curves that overshoot (back, elastic). "Hold" ({@link EasingType#NONE}) is drawn as a step, because it holds
 * the value until the next keyframe and then jumps. With {@code animated} a dot rides the curve in a loop.
 */
public final class EasingCurveGraph extends Pane {
    private static final int STEPS = 120;
    private static final double CYCLE_SECONDS = 2.2, RIDE_SECONDS = 1.6; // 1.6 s ride, short rest at each end

    private final double size, inset, lineWidth;
    private final boolean animated;
    private Color tint;                       // null = the theme's text colour

    private EasingType easing;
    private double lo = 0, span = 1;          // value range shown

    private final Line guide0 = new Line(), guide1 = new Line();
    private final Polyline curve = new Polyline();
    private final Circle startDot = new Circle(), endDot = new Circle(), runner = new Circle(7);
    private AnimationTimer timer;

    public EasingCurveGraph(EasingType easing, double size, double lineWidth, boolean animated, Color tint) {
        this.easing = easing;
        this.size = size;
        this.lineWidth = lineWidth;
        this.animated = animated;
        this.tint = tint;
        this.inset = Math.max(lineWidth * 1.4, 3);

        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        setMouseTransparent(true); // the tile / button around it takes the clicks

        for (Line g : new Line[]{guide0, guide1}) {
            g.setStroke(Color.gray(0.5, 0.5));
            g.setStrokeWidth(1);
            g.getStrokeDashArray().setAll(3.0, 3.0);
        }
        curve.setStrokeWidth(lineWidth);
        curve.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        curve.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        curve.setFill(null);
        startDot.setRadius(lineWidth * 1.2);
        endDot.setRadius(lineWidth * 1.2);
        runner.setFill(ClipNode.KNOT_ACTIVE);
        runner.setStroke(Color.WHITE);
        runner.setStrokeWidth(2);
        runner.setVisible(animated);

        getChildren().addAll(guide0, guide1, curve, startDot, endDot, runner);
        applyTint();
        redraw();

        if (animated) {
            sceneProperty().addListener((o, was, now) -> { if (now != null) startTimer(); else stopTimer(); });
        }
    }

    public void setEasing(EasingType easing) {
        if (this.easing == easing) return;
        this.easing = easing;
        redraw();
    }

    public void setTint(Color tint) {
        this.tint = tint;
        applyTint();
    }

    private void applyTint() {
        if (tint != null) {
            curve.setStyle("");
            curve.setStroke(tint);
            startDot.setFill(tint.deriveColor(0, 1, 1, 0.55));
            endDot.setFill(tint.deriveColor(0, 1, 1, 0.55));
        } else {
            // Theme text colour; the plain colour is the fallback if the theme has no such variable.
            curve.setStroke(Color.gray(0.4));
            curve.setStyle("-fx-stroke: -color-fg-default;");
            startDot.setFill(Color.gray(0.5, 0.7));
            endDot.setFill(Color.gray(0.5, 0.7));
        }
    }

    // ---- drawing -------------------------------------------------------------------------

    private double x(double t) { return inset + t * (size - 2 * inset); }
    private double y(double v) { return size - inset - ((v - lo) / span) * (size - 2 * inset); }

    private double valueAt(double t) {
        return easing == EasingType.NONE ? (t >= 1 ? 1 : 0) : AnimatedProperty.ease((float) t, easing);
    }

    private void redraw() {
        lo = 0;
        double hi = 1;
        if (easing != EasingType.NONE) {
            for (int i = 0; i <= STEPS; i++) {
                double v = AnimatedProperty.ease(i / (float) STEPS, easing);
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        span = Math.max(hi - lo, 0.0001);

        double y0 = y(0), y1 = y(1);
        guide0.setStartX(0); guide0.setEndX(size); guide0.setStartY(y0); guide0.setEndY(y0);
        guide1.setStartX(0); guide1.setEndX(size); guide1.setStartY(y1); guide1.setEndY(y1);

        curve.getPoints().clear();
        if (easing == EasingType.NONE) {
            curve.getPoints().addAll(x(0), y(0), x(1), y(0), x(1), y(1));
        } else {
            for (int i = 0; i <= STEPS; i++) {
                double t = i / (double) STEPS;
                curve.getPoints().addAll(x(t), y(AnimatedProperty.ease((float) t, easing)));
            }
        }
        startDot.setCenterX(x(0)); startDot.setCenterY(y0);
        endDot.setCenterX(x(1));   endDot.setCenterY(y1);
        if (animated) moveRunner(0);
    }

    // ---- the riding dot --------------------------------------------------------------------

    private void moveRunner(double t) {
        runner.setCenterX(x(t));
        runner.setCenterY(y(valueAt(t)));
    }

    private void startTimer() {
        if (timer != null) return;
        timer = new AnimationTimer() {
            @Override public void handle(long now) {
                double phase = (now / 1e9) % CYCLE_SECONDS;
                moveRunner(Math.min(phase / RIDE_SECONDS, 1));
            }
        };
        timer.start();
    }

    private void stopTimer() {
        if (timer != null) { timer.stop(); timer = null; }
    }

    // ---- names -----------------------------------------------------------------------------

    /** "EASE_IN_OUT_SINE" -> "In Out Sine"; the two non-curves read "Hold" / "Linear". */
    public static String shortName(EasingType e) {
        if (e == null) return "Hold";
        switch (e) {
            case NONE: return "Hold";
            case LINEAR: return "Linear";
            default:
                StringBuilder sb = new StringBuilder();
                for (String word : e.name().replace("EASE_", "").split("_")) {
                    if (word.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(word.charAt(0)).append(word.substring(1).toLowerCase());
                }
                return sb.toString();
        }
    }
}
