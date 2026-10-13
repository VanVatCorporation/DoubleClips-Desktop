package com.vanvatcorporation.doubleclips.ui.renderer;

import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipMask;
import com.vanvatcorporation.doubleclips.data.editing.ClipMask.Param;
import com.vanvatcorporation.doubleclips.data.editing.ClipMask.Shape;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Line;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * On-canvas editing of one clip mask (the canvas half of the Mask panel; PreviewGizmo hands it the mouse while
 * {@link com.vanvatcorporation.doubleclips.ui.MaskEditState} says a mask is being edited):
 * <ul>
 *   <li>drag inside the clip moves the mask (Shift locks an axis)</li>
 *   <li>edge handles change the width / height (a mirror's thickness), corner handles scale both - all about the
 *       mask's centre</li>
 *   <li>the round handle above the shape rotates it, snapping to 90 degrees</li>
 *   <li>the handle below the shape sets the feather</li>
 * </ul>
 * Everything is worked out in the clip's own box (the frame the shader masks in), so it is right for a scaled,
 * rotated or mirrored clip. As in {@link PreviewGizmo}, a gesture leaves the real clip alone: the in-progress mask list
 * is streamed to the preview worker and the gesture commits as ONE undo step. A parameter that has keyframes is edited
 * in a keyframe at the playhead (inserted when there is none), the others as plain values - the same rule as the
 * panel's rows.
 */
final class MaskGizmo {

    private static final double HANDLE_RADIUS = 5.5;
    private static final double HANDLE_HIT = 10;
    private static final double ROTATE_OFFSET = 30;
    private static final double FEATHER_OFFSET = 22;
    private static final double MIN_HALF_EXTENT = 1;

    private static final int H_TOP = 4, H_RIGHT = 5, H_BOTTOM = 6, H_LEFT = 7;
    private static final int H_ROTATE = 100, H_FEATHER = 101, H_BODY = 102;

    private enum Gesture { NONE, MOVE, RESIZE, ROTATE, FEATHER }

    private final PreviewGizmo.Host host;
    private final TimelineRenderer renderer;
    private final VideoSettings settings;
    private final Function<Clip, double[]> quadOf;                 // the clip's canvas-pixel corners TL, TR, BR, BL at the playhead
    private final BiFunction<Double, Double, Point2D> toOverlay;
    private final Function<MouseEvent, Point2D> canvasPoint;
    private final DoubleSupplier screenScale;

    private final Polygon clipBox = new Polygon();
    private final Path shadow = new Path();
    private final Path outline = new Path();
    private final Circle[] corners = new Circle[4];
    private final Rectangle[] edges = new Rectangle[4];
    private final Circle rotateHandle = new Circle(HANDLE_RADIUS + 1);
    private final Line rotateStem = new Line();
    private final Circle featherHandle = new Circle(HANDLE_RADIUS + 1);
    private final Line featherStem = new Line();
    private final Label info = new Label();
    private final Pane overlay;

    // ── gesture state ────────────────────────────────────────────────────
    private Gesture gesture = Gesture.NONE;
    private Clip target;
    private int maskIndex;
    private int handle;
    private float local;                   // playhead, seconds into the clip
    private List<ClipMask> before;         // the clip's masks at press (deep copy)
    private List<ClipMask> live;           // the masks as the gesture would commit them right now
    private ClipMask start;                // the mask's look at the playhead at press
    private Frame startFrame;
    private Point2D startBox;              // press point in box pixels
    private double startAngle;
    private double[] startHandleLocal;
    private boolean changed;

    MaskGizmo(Pane overlay, PreviewGizmo.Host host, TimelineRenderer renderer, VideoSettings settings,
              Function<Clip, double[]> quadOf, BiFunction<Double, Double, Point2D> toOverlay,
              Function<MouseEvent, Point2D> canvasPoint, DoubleSupplier screenScale) {
        this.overlay = overlay;
        this.host = host;
        this.renderer = renderer;
        this.settings = settings;
        this.quadOf = quadOf;
        this.toOverlay = toOverlay;
        this.canvasPoint = canvasPoint;
        this.screenScale = screenScale;

        clipBox.setFill(Color.TRANSPARENT);
        clipBox.setStroke(Color.web("#ffffff66"));
        clipBox.setStrokeWidth(1);
        clipBox.getStrokeDashArray().setAll(4.0, 4.0);
        clipBox.setMouseTransparent(true);

        shadow.setStroke(Color.web("#000000b0"));
        shadow.setStrokeWidth(3.5);
        shadow.setFill(Color.TRANSPARENT);
        shadow.setMouseTransparent(true);
        outline.setStroke(Color.web("#ffd23f"));
        outline.setStrokeWidth(1.6);
        outline.setFill(Color.TRANSPARENT);
        outline.setMouseTransparent(true);

        for (int i = 0; i < 4; i++) {
            Circle c = new Circle(HANDLE_RADIUS, Color.WHITE);
            c.setStroke(Color.web("#00000066"));
            c.setMouseTransparent(true);
            corners[i] = c;
            Rectangle r = new Rectangle(11, 11, Color.WHITE);
            r.setArcWidth(3);
            r.setArcHeight(3);
            r.setStroke(Color.web("#00000066"));
            r.setMouseTransparent(true);
            edges[i] = r;
        }
        for (Circle c : new Circle[]{rotateHandle, featherHandle}) {
            c.setStroke(Color.web("#00000066"));
            c.setMouseTransparent(true);
        }
        rotateHandle.setFill(Color.WHITE);
        featherHandle.setFill(Color.web("#7ab7ff"));
        for (Line l : new Line[]{rotateStem, featherStem}) {
            l.setStroke(Color.web("#ffffffcc"));
            l.setStrokeWidth(1.2);
            l.setMouseTransparent(true);
        }
        info.setStyle("-fx-background-color: #000000a0; -fx-text-fill: white; -fx-padding: 4 8; "
                + "-fx-background-radius: 6; -fx-font-family: monospace; -fx-font-size: 11px; -fx-text-alignment: center;");
        info.setMouseTransparent(true);

        overlay.getChildren().addAll(clipBox, shadow, outline, rotateStem, featherStem, rotateHandle, featherHandle);
        overlay.getChildren().addAll(edges);
        overlay.getChildren().addAll(corners);
        overlay.getChildren().add(info);
        setVisible(false);
    }

    // ── the clip's box: canvas <-> box pixels <-> the mask's own frame ───────────────────────

    /** The clip's scaled, still unrotated box as the shader masks it: box pixel (x, y), y down, origin at the clip's TL corner. */
    private static final class Frame {
        final double[] q;
        final double bw, bh, ux, uy, vx, vy;

        Frame(double[] q, double bw, double bh) {
            this.q = q;
            this.bw = bw;
            this.bh = bh;
            ux = (q[2] - q[0]) / bw;
            uy = (q[3] - q[1]) / bw;
            vx = (q[6] - q[0]) / bh;
            vy = (q[7] - q[1]) / bh;
        }

        double m() { return Math.min(bw, bh); }

        Point2D boxToCanvas(double px, double py) {
            return new Point2D(q[0] + ux * px + vx * py, q[1] + uy * px + vy * py);
        }

        Point2D canvasToBox(Point2D c) {
            double dx = c.getX() - q[0], dy = c.getY() - q[1];
            return new Point2D(dx * ux + dy * uy, dx * vx + dy * vy);
        }
    }

    private Frame frameOf(Clip clip) {
        double[] q = quadOf.apply(clip);
        if (q == null) return null;
        double bw = Math.hypot(q[2] - q[0], q[3] - q[1]);
        double bh = Math.hypot(q[6] - q[0], q[7] - q[1]);
        if (bw < 1 || bh < 1) return null;
        return new Frame(q, bw, bh);
    }

    /** A point in the mask's own frame (origin at its centre, rotated with it, box pixels) -> box pixels. */
    private static Point2D localToBox(ClipMask m, Frame f, double lx, double ly) {
        double r = Math.toRadians(m.rotation), c = Math.cos(r), s = Math.sin(r);
        return new Point2D(m.centerX * f.bw + lx * c - ly * s, m.centerY * f.bh + lx * s + ly * c);
    }

    private static Point2D boxToLocal(ClipMask m, Frame f, Point2D box) {
        double r = Math.toRadians(m.rotation), c = Math.cos(r), s = Math.sin(r);
        double dx = box.getX() - m.centerX * f.bw, dy = box.getY() - m.centerY * f.bh;
        return new Point2D(dx * c + dy * s, -dx * s + dy * c);
    }

    private Point2D localToOverlay(ClipMask m, Frame f, double lx, double ly) {
        Point2D box = localToBox(m, f, lx, ly);
        Point2D canvas = f.boxToCanvas(box.getX(), box.getY());
        return toOverlay.apply(canvas.getX(), canvas.getY());
    }

    private static double halfWidth(ClipMask m, Frame f) { return m.width * f.m() / 2.0; }
    private static double halfHeight(ClipMask m, Frame f) { return m.height * f.m() / 2.0; }

    /** Local position of a resize handle. */
    private static double[] handleLocal(int id, double hw, double hh) {
        switch (id) {
            case 0: return new double[]{-hw, -hh};
            case 1: return new double[]{hw, -hh};
            case 2: return new double[]{hw, hh};
            case 3: return new double[]{-hw, hh};
            case H_TOP: return new double[]{0, -hh};
            case H_RIGHT: return new double[]{hw, 0};
            case H_BOTTOM: return new double[]{0, hh};
            default: return new double[]{-hw, 0};
        }
    }

    private static boolean hasHandle(Shape shape, int id) {
        switch (shape) {
            case LINEAR: return false;
            case MIRROR: return id == H_TOP || id == H_BOTTOM;
            default: return true;
        }
    }

    /** How far the shape reaches above / below its centre line (a linear mask is only a line). */
    private static double reach(ClipMask m, Frame f) {
        return m.shape == Shape.LINEAR ? 0 : halfHeight(m, f);
    }

    private double[] rotateHandleLocal(ClipMask m, Frame f) {
        return new double[]{0, -reach(m, f) - ROTATE_OFFSET / screenScale.getAsDouble()};
    }

    private double[] featherHandleLocal(ClipMask m, Frame f) {
        return new double[]{0, reach(m, f) + m.feather * f.m() + FEATHER_OFFSET / screenScale.getAsDouble()};
    }

    // ── drawing ──────────────────────────────────────────────────────────

    boolean gestureActive() {
        return gesture != Gesture.NONE;
    }

    /** Is {@code clip} in mask-edit mode (so PreviewGizmo gives the canvas to this)? */
    boolean editing(Clip clip) {
        return maskIndexFor(clip) >= 0 && !host.isPlaying() && frameOf(clip) != null;
    }

    private int maskIndexFor(Clip clip) {
        if (gesture != Gesture.NONE && clip == target) return maskIndex;
        return host.maskEdit().editingIndex(clip);
    }

    private ClipMask currentMask(Clip clip, int index) {
        List<ClipMask> list = gesture != Gesture.NONE && clip == target && live != null ? live : clip.masks;
        if (list == null || index < 0 || index >= list.size()) return null;
        float t = clamp(host.currentTime() - clip.startTime, 0f, clip.duration);
        return list.get(index).resolved(t);
    }

    void hide() {
        setVisible(false);
    }

    private void setVisible(boolean v) {
        clipBox.setVisible(v);
        shadow.setVisible(v);
        outline.setVisible(v);
        rotateStem.setVisible(v);
        featherStem.setVisible(v);
        rotateHandle.setVisible(v);
        featherHandle.setVisible(v);
        for (Circle c : corners) c.setVisible(v);
        for (Rectangle r : edges) r.setVisible(v);
        info.setVisible(false);
    }

    /** Redraws the mask being edited on {@code clip}; hides everything when there is none. */
    void refresh(Clip clip) {
        int index = clip == null ? -1 : maskIndexFor(clip);
        Frame f = clip == null ? null : frameOf(clip);
        ClipMask m = index < 0 || f == null ? null : currentMask(clip, index);
        if (m == null) {
            setVisible(false);
            return;
        }
        setVisible(true);

        Point2D[] box = new Point2D[4];
        for (int i = 0; i < 4; i++) box[i] = toOverlay.apply(f.q[i * 2], f.q[i * 2 + 1]);
        clipBox.getPoints().setAll(box[0].getX(), box[0].getY(), box[1].getX(), box[1].getY(),
                box[2].getX(), box[2].getY(), box[3].getX(), box[3].getY());

        drawShape(m, f);

        double hw = halfWidth(m, f), hh = halfHeight(m, f);
        for (int i = 0; i < 4; i++) {
            boolean show = m.shape != Shape.MIRROR && m.shape != Shape.LINEAR;
            corners[i].setVisible(show);
            if (show) {
                double[] l = handleLocal(i, hw, hh);
                Point2D o = localToOverlay(m, f, l[0], l[1]);
                corners[i].setCenterX(o.getX());
                corners[i].setCenterY(o.getY());
            }
            int id = H_TOP + i;
            boolean showEdge = hasHandle(m.shape, id);
            edges[i].setVisible(showEdge);
            if (showEdge) {
                double[] l = handleLocal(id, hw, hh);
                Point2D o = localToOverlay(m, f, l[0], l[1]);
                edges[i].setX(o.getX() - edges[i].getWidth() / 2);
                edges[i].setY(o.getY() - edges[i].getHeight() / 2);
            }
        }

        // rotate: a stem from the shape's top edge to the handle
        double[] rl = rotateHandleLocal(m, f);
        Point2D top = localToOverlay(m, f, 0, -reach(m, f));
        Point2D rot = localToOverlay(m, f, rl[0], rl[1]);
        rotateStem.setStartX(top.getX());
        rotateStem.setStartY(top.getY());
        rotateStem.setEndX(rot.getX());
        rotateStem.setEndY(rot.getY());
        rotateHandle.setCenterX(rot.getX());
        rotateHandle.setCenterY(rot.getY());

        // feather: a stem from the shape's bottom edge to the handle
        double[] fl = featherHandleLocal(m, f);
        Point2D bottom = localToOverlay(m, f, 0, reach(m, f));
        Point2D feather = localToOverlay(m, f, fl[0], fl[1]);
        featherStem.setStartX(bottom.getX());
        featherStem.setStartY(bottom.getY());
        featherStem.setEndX(feather.getX());
        featherStem.setEndY(feather.getY());
        featherHandle.setCenterX(feather.getX());
        featherHandle.setCenterY(feather.getY());

        if (gesture != Gesture.NONE) {
            float t = clamp(host.currentTime() - clip.startTime, 0f, clip.duration);
            ClipMask lm = live == null ? m : live.get(maskIndex).resolved(t);
            StringBuilder sb = new StringBuilder("Mask").append(maskIndex + 1).append(": ")
                    .append(String.format("X %.0f  Y %.0f", Param.X.get(lm), Param.Y.get(lm)));
            if (lm.shape.hasWidth) sb.append(String.format("  W %.0f", Param.WIDTH.get(lm)));
            if (lm.shape.hasHeight) sb.append(String.format("  %s %.0f", lm.shape == Shape.MIRROR ? "T" : "H", Param.HEIGHT.get(lm)));
            sb.append(String.format("%nRot %.1f  Feather %.0f", Param.ROTATION.get(lm), Param.FEATHER.get(lm)));
            info.setText(sb.toString());
            info.applyCss();
            info.autosize();
            info.setLayoutX(Math.max(8, overlay.getWidth() / 2 - info.prefWidth(-1) / 2));
            info.setLayoutY(Math.max(8, overlay.getHeight() - info.prefHeight(-1) - 12));
            info.setVisible(true);
        }
    }

    private void drawShape(ClipMask m, Frame f) {
        List<PathElement> a = new ArrayList<>(), b = new ArrayList<>();
        double hw = halfWidth(m, f), hh = halfHeight(m, f);
        switch (m.shape) {
            case LINEAR: {
                double len = Math.hypot(f.bw, f.bh);
                line(a, b, m, f, -len, 0, len, 0);
                break;
            }
            case MIRROR: {
                double len = Math.hypot(f.bw, f.bh);
                line(a, b, m, f, -len, -hh, len, -hh);
                line(a, b, m, f, -len, hh, len, hh);
                break;
            }
            default:
                closed(a, b, m, f, polygon(m, hw, hh));
                break;
        }
        shadow.getElements().setAll(a);
        outline.getElements().setAll(b);
    }

    private void line(List<PathElement> a, List<PathElement> b, ClipMask m, Frame f, double x0, double y0, double x1, double y1) {
        Point2D p0 = localToOverlay(m, f, x0, y0), p1 = localToOverlay(m, f, x1, y1);
        a.add(new MoveTo(p0.getX(), p0.getY()));
        a.add(new LineTo(p1.getX(), p1.getY()));
        b.add(new MoveTo(p0.getX(), p0.getY()));
        b.add(new LineTo(p1.getX(), p1.getY()));
    }

    private void closed(List<PathElement> a, List<PathElement> b, ClipMask m, Frame f, List<double[]> pts) {
        for (int i = 0; i < pts.size(); i++) {
            Point2D p = localToOverlay(m, f, pts.get(i)[0], pts.get(i)[1]);
            if (i == 0) { a.add(new MoveTo(p.getX(), p.getY())); b.add(new MoveTo(p.getX(), p.getY())); }
            else { a.add(new LineTo(p.getX(), p.getY())); b.add(new LineTo(p.getX(), p.getY())); }
        }
        a.add(new ClosePath());
        b.add(new ClosePath());
    }

    /** The shape's outline in the mask's own frame (the same figures the shader and iOS fill). */
    private static List<double[]> polygon(ClipMask m, double hw, double hh) {
        List<double[]> pts = new ArrayList<>();
        switch (m.shape) {
            case CIRCLE:
                for (int i = 0; i < 72; i++) {
                    double t = i / 72.0 * 2 * Math.PI;
                    pts.add(new double[]{Math.cos(t) * hw, Math.sin(t) * hh});
                }
                break;
            case RECTANGLE: {
                double r = Math.min(Math.max(m.roundness, 0f) * Math.min(hw, hh), Math.min(hw, hh));
                double[][] centres = {{hw - r, -hh + r}, {hw - r, hh - r}, {-hw + r, hh - r}, {-hw + r, -hh + r}};
                for (int c = 0; c < 4; c++) {
                    double from = -Math.PI / 2 + c * Math.PI / 2;
                    int steps = r < 0.5 ? 1 : 10;
                    for (int s = 0; s <= steps; s++) {
                        double a = from + (Math.PI / 2) * s / steps;
                        pts.add(new double[]{centres[c][0] + Math.cos(a) * r, centres[c][1] + Math.sin(a) * r});
                    }
                }
                break;
            }
            case HEART:
                for (int i = 0; i < 96; i++) {
                    double t = i / 96.0 * 2 * Math.PI;
                    double x = 16 * Math.pow(Math.sin(t), 3);
                    double y = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t);
                    pts.add(new double[]{x / 16.0 * hw, -(y + 2.6) / 14.4 * hh});
                }
                break;
            default:
                for (int k = 0; k < 10; k++) {
                    double angle = -Math.PI / 2 + k * Math.PI / 5;
                    double r = k % 2 == 0 ? 1.0 : 0.42;
                    pts.add(new double[]{Math.cos(angle) * r * hw, Math.sin(angle) * r * hh});
                }
                break;
        }
        return pts;
    }

    // ── hit testing / cursor ────────────────────────────────────────────────

    /** The handle under an overlay point (a corner / edge id, H_ROTATE, H_FEATHER, H_BODY inside the clip) or -1. */
    private int handleAt(Clip clip, MouseEvent e) {
        Frame f = frameOf(clip);
        ClipMask m = currentMask(clip, maskIndexFor(clip));
        if (f == null || m == null) return -1;
        double ox = e.getX(), oy = e.getY();
        double[] rl = rotateHandleLocal(m, f);
        Point2D r = localToOverlay(m, f, rl[0], rl[1]);
        if (Math.hypot(ox - r.getX(), oy - r.getY()) <= HANDLE_HIT) return H_ROTATE;
        double[] fl = featherHandleLocal(m, f);
        Point2D fe = localToOverlay(m, f, fl[0], fl[1]);
        if (Math.hypot(ox - fe.getX(), oy - fe.getY()) <= HANDLE_HIT) return H_FEATHER;
        double hw = halfWidth(m, f), hh = halfHeight(m, f);
        if (m.shape != Shape.MIRROR && m.shape != Shape.LINEAR) {
            for (int i = 0; i < 4; i++) {
                double[] l = handleLocal(i, hw, hh);
                Point2D o = localToOverlay(m, f, l[0], l[1]);
                if (Math.hypot(ox - o.getX(), oy - o.getY()) <= HANDLE_HIT) return i;
            }
        }
        for (int id = H_TOP; id <= H_LEFT; id++) {
            if (!hasHandle(m.shape, id)) continue;
            double[] l = handleLocal(id, hw, hh);
            Point2D o = localToOverlay(m, f, l[0], l[1]);
            if (Math.hypot(ox - o.getX(), oy - o.getY()) <= HANDLE_HIT) return id;
        }
        return insideClip(f, canvasPoint.apply(e)) ? H_BODY : -1;
    }

    private static boolean insideClip(Frame f, Point2D canvas) {
        Point2D b = f.canvasToBox(canvas);
        return b.getX() >= 0 && b.getX() <= f.bw && b.getY() >= 0 && b.getY() <= f.bh;
    }

    Cursor cursorAt(Clip clip, MouseEvent e) {
        int h = handleAt(clip, e);
        if (h == H_ROTATE) return Cursor.HAND;
        if (h == H_FEATHER) return Cursor.V_RESIZE;
        if (h == H_BODY) return Cursor.MOVE;
        if (h >= 0) return Cursor.CROSSHAIR;
        return Cursor.DEFAULT;
    }

    // ── mouse ────────────────────────────────────────────────────────────

    /** Returns true when the press started a mask gesture (the event is then ours). */
    boolean onPressed(Clip clip, MouseEvent e) {
        int index = maskIndexFor(clip);
        if (index < 0 || gesture != Gesture.NONE) return false;
        int h = handleAt(clip, e);
        if (h < 0) return false;
        Frame f = frameOf(clip);
        if (f == null || clip.masks == null) return false;

        target = clip;
        maskIndex = index;
        handle = h;
        local = clamp(host.currentTime() - clip.startTime, 0f, clip.duration);
        before = copy(clip.masks);
        live = copy(clip.masks);
        start = clip.masks.get(index).resolved(local);
        startFrame = f;
        startBox = f.canvasToBox(canvasPoint.apply(e));
        changed = false;
        if (h == H_ROTATE) {
            gesture = Gesture.ROTATE;
            startAngle = Math.atan2(startBox.getY() - start.centerY * f.bh, startBox.getX() - start.centerX * f.bw);
        } else if (h == H_FEATHER) {
            gesture = Gesture.FEATHER;
        } else if (h == H_BODY) {
            gesture = Gesture.MOVE;
        } else {
            gesture = Gesture.RESIZE;
            startHandleLocal = handleLocal(h, halfWidth(start, f), halfHeight(start, f));
        }
        return true;
    }

    void onDragged(MouseEvent e) {
        if (gesture == Gesture.NONE || target == null) return;
        Frame f = startFrame;
        Point2D box = f.canvasToBox(canvasPoint.apply(e));
        Map<Param, Float> values = new LinkedHashMap<>();
        double m = f.m();
        switch (gesture) {
            case MOVE: {
                double dx = box.getX() - startBox.getX(), dy = box.getY() - startBox.getY();
                if (e.isShiftDown()) { if (Math.abs(dx) >= Math.abs(dy)) dy = 0; else dx = 0; }
                values.put(Param.X, (float) ((start.centerX + dx / f.bw) * 100.0));
                values.put(Param.Y, (float) ((start.centerY + dy / f.bh) * 100.0));
                break;
            }
            case RESIZE: {
                Point2D p = boxToLocal(start, f, box);
                if (handle == H_TOP || handle == H_BOTTOM) {
                    values.put(Param.HEIGHT, (float) (2 * Math.max(Math.abs(p.getY()), MIN_HALF_EXTENT) / m * 100.0));
                } else if (handle == H_LEFT || handle == H_RIGHT) {
                    values.put(Param.WIDTH, (float) (2 * Math.max(Math.abs(p.getX()), MIN_HALF_EXTENT) / m * 100.0));
                } else {
                    double len2 = startHandleLocal[0] * startHandleLocal[0] + startHandleLocal[1] * startHandleLocal[1];
                    if (len2 < 1e-3) break;
                    double k = Math.max(0.02, (p.getX() * startHandleLocal[0] + p.getY() * startHandleLocal[1]) / len2);
                    values.put(Param.WIDTH, (float) (start.width * k * 100.0));
                    values.put(Param.HEIGHT, (float) (start.height * k * 100.0));
                }
                break;
            }
            case ROTATE: {
                double angle = Math.atan2(box.getY() - start.centerY * f.bh, box.getX() - start.centerX * f.bw);
                float rot = start.rotation + (float) Math.toDegrees(angle - startAngle);
                rot = ((rot + 540f) % 360f + 360f) % 360f - 180f;
                float snap = Constants.CANVAS_ROTATE_SNAP_DEGREE;
                float nearest = Math.round(rot / snap) * snap;
                if (Math.abs(rot - nearest) <= Constants.CANVAS_ROTATE_SNAP_THRESHOLD_DEGREE) rot = nearest;
                values.put(Param.ROTATION, rot);
                break;
            }
            default: { // FEATHER
                Point2D p = boxToLocal(start, f, box);
                double beyond = p.getY() - reach(start, f) - FEATHER_OFFSET / screenScale.getAsDouble();
                double feather = Math.max(0, Math.min(0.5, beyond / m));
                values.put(Param.FEATHER, (float) (feather * 200.0));
                break;
            }
        }
        apply(values);
    }

    /** Applies values (panel units) to the live copy the way the panel's rows would, and shows them. */
    private void apply(Map<Param, Float> values) {
        List<ClipMask> next = copy(before);
        ClipMask m = next.get(maskIndex);
        int fps = settings.frameRate;
        for (Map.Entry<Param, Float> en : values.entrySet()) {
            Param p = en.getKey();
            float v = Math.max(p.min, Math.min(p.max, en.getValue()));
            if (m.isAnimated(p)) m.setKey(p, local, v, fps);
            else p.set(m, v);
        }
        if (!changed && next.equals(before)) {
            refresh(target);
            return;
        }
        changed = true;
        live = next;
        renderer.showLiveMasks(target, copy(next), host.currentTime());
        refresh(target);
    }

    /** Ends the gesture: ONE undo step when anything changed. */
    void onReleased() {
        if (gesture == Gesture.NONE || target == null) {
            gesture = Gesture.NONE;
            return;
        }
        Clip clip = target;
        List<ClipMask> start = before;
        List<ClipMask> end = live;
        boolean wasChanged = changed;
        reset();
        if (!wasChanged || end == null) {
            // the worker may hold a patched copy of a gesture that ended where it began
            host.clipChanged(clip);
            return;
        }
        clip.masks = copy(start); // commit() runs redo, which puts "end" in
        List<ClipMask> redoState = copy(end);
        List<ClipMask> undoState = copy(start);
        host.commit("Mask: " + clip.getClipName(), () -> {
            clip.masks = copy(redoState);
            host.clipChanged(clip);
        }, () -> {
            clip.masks = copy(undoState);
            host.clipChanged(clip);
        });
    }

    /** Escape: drop the gesture and put everything back. */
    void cancel() {
        if (gesture == Gesture.NONE || target == null) return;
        Clip clip = target;
        List<ClipMask> start = before;
        reset();
        clip.masks = copy(start);
        host.clipChanged(clip);
    }

    private void reset() {
        gesture = Gesture.NONE;
        target = null;
        live = null;
        before = null;
        startFrame = null;
    }

    // ── small helpers ────────────────────────────────────────────────────

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static List<ClipMask> copy(List<ClipMask> src) {
        List<ClipMask> out = new ArrayList<>();
        if (src != null) for (ClipMask m : src) out.add(new ClipMask(m));
        return out;
    }
}
