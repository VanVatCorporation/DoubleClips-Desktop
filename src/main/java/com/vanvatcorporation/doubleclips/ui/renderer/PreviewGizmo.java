package com.vanvatcorporation.doubleclips.ui.renderer;

import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.EasingType;
import com.vanvatcorporation.doubleclips.data.editing.Keyframe;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;
import com.vanvatcorporation.doubleclips.data.editing.VideoProperties;
import com.vanvatcorporation.doubleclips.data.editing.VideoProperties.ValueType;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * On-canvas editing for the preview (the desktop counterpart of iOS PreviewInteractionLayer):
 * <ul>
 *   <li>press on a clip selects it; dragging inside the selected clip moves it (Shift locks an axis)</li>
 *   <li>the four corner handles scale it uniformly, keeping the opposite corner where it is</li>
 *   <li>the round handle above the top edge rotates it around its visual centre, snapping to 90 degrees</li>
 *   <li>a trackpad pinch scales it about its centre</li>
 * </ul>
 * Every gesture is ONE undo step. While a gesture is in flight the real clip is left alone and the
 * in-progress values are streamed to the preview worker ({@link TimelineRenderer#showLiveProperties}),
 * which is what keeps dragging live without re-sending the timeline. If the clip has keyframes the
 * gesture edits the keyframe at the playhead, inserting one first (from the current interpolated look)
 * when there is none - otherwise interpolation would overwrite the change.
 * <p>
 * The overlay lives OUTSIDE the scaled render pane so handles keep a constant on-screen size and
 * aren't clipped to the canvas; canvas pixels are converted with the pane's own scene transform.
 */
public final class PreviewGizmo {

    /** What the editor has to provide; EditorWindow implements it. */
    public interface Host {
        Timeline timeline();
        float currentTime();
        boolean isPlaying();
        /** The clip the properties panel edits, or null. */
        Clip primarySelectedClip();
        /** Select exactly this clip (updates the timeline and the properties panel). */
        void selectClip(Clip clip);
        /** Runs redo now and records one undo step. */
        void commit(String name, Runnable redo, Runnable undo);
        /** A clip's data changed outside a gesture (commit, undo, redo, cancel): refresh timeline knots, save, redraw. */
        void clipChanged(Clip clip);
        /** The project's Fonts folder (imported fonts), so a text clip's box is measured with the font the preview draws. */
        String fontsDirectory();
    }

    private static final double HANDLE_RADIUS = 5.5;
    private static final double HANDLE_HIT = 10;
    private static final double ROTATE_OFFSET = 30;
    private static final float MIN_SCALE_FACTOR = 0.02f;
    /** Edge handles only appear when the edge is at least this long on screen (they'd sit on the corners otherwise). */
    private static final double EDGE_MIN_SCREEN_LENGTH = 40;
    private static final double EDGE_HIT = 9;
    /** Snapping pulls within this many SCREEN pixels, so it feels the same at any preview zoom. */
    private static final double SNAP_SCREEN_PX = 7;

    // Handle ids: 0-3 are the corners (TL, TR, BR, BL), 4-7 the edge midpoints (top, right, bottom, left).
    private static final int H_TOP = 4, H_RIGHT = 5, H_BOTTOM = 6, H_LEFT = 7;
    private static final int HANDLE_ROTATE = 100;

    private enum Mode { NONE, MOVE, SCALE, ROTATE }

    private final Host host;
    private final TimelineRenderer renderer;
    private final Pane renderPane;
    private final VideoSettings settings;
    private final Pane overlay = new Pane();

    private final Polygon outline = new Polygon();
    private final Circle[] corners = new Circle[4];
    private final Rectangle[] edges = new Rectangle[4];
    private final Line guideV = new Line();
    private final Line guideH = new Line();
    private final Circle rotateHandle = new Circle(HANDLE_RADIUS + 1);
    private final Line rotateStem = new Line();
    private final Label info = new Label();

    // ── gesture state ────────────────────────────────────────────────────
    private Mode mode = Mode.NONE;
    private Clip target;
    private VideoProperties basis;           // the values the gesture started from (static or keyframe)
    private VideoProperties live;            // the values currently shown
    private int keyIndex = -1;               // keyframe being edited, or -1
    private boolean keyframeInserted = false;
    private Snapshot before;                 // everything the gesture can touch, as it was at press
    private Point2D pressCanvas;
    private int draggedHandle = -1;
    private double[] startQuad;
    private double startAngle;
    private double[] startCenter;
    private boolean pinching;
    private boolean changed;
    // Snapping: the lines a gesture can snap to (canvas edges/centre + other visible clips), fixed at press,
    // and the guide currently being shown (NaN = none).
    private final List<Double> snapXs = new ArrayList<>();
    private final List<Double> snapYs = new ArrayList<>();
    private double guideX = Double.NaN, guideY = Double.NaN;

    public PreviewGizmo(Host host, TimelineRenderer renderer, VideoSettings settings) {
        this.host = host;
        this.renderer = renderer;
        this.renderPane = renderer.getRenderPane();
        this.settings = settings;

        overlay.setPickOnBounds(true);
        overlay.setStyle("-fx-background-color: transparent;");

        outline.setFill(Color.TRANSPARENT);
        outline.setStroke(Color.WHITE);
        outline.setStrokeWidth(1.5);
        outline.getStrokeDashArray().setAll(6.0, 4.0);
        outline.setMouseTransparent(true);

        for (int i = 0; i < 4; i++) {
            Circle c = new Circle(HANDLE_RADIUS, Color.WHITE);
            c.setStroke(Color.web("#00000066"));
            c.setMouseTransparent(true);
            corners[i] = c;
        }
        for (int i = 0; i < 4; i++) {
            Rectangle r = new Rectangle(14, 6);
            r.setArcWidth(6);
            r.setArcHeight(6);
            r.setFill(Color.WHITE);
            r.setStroke(Color.web("#00000066"));
            r.setMouseTransparent(true);
            edges[i] = r;
        }
        for (Line g : new Line[]{guideV, guideH}) {
            g.setStroke(Color.web("#ff3d9a"));
            g.setStrokeWidth(1);
            g.setMouseTransparent(true);
            g.setVisible(false);
        }
        rotateHandle.setFill(Color.WHITE);
        rotateHandle.setStroke(Color.web("#00000066"));
        rotateHandle.setMouseTransparent(true);
        rotateStem.setStroke(Color.WHITE);
        rotateStem.setStrokeWidth(1.2);
        rotateStem.setStrokeLineCap(StrokeLineCap.ROUND);
        rotateStem.setMouseTransparent(true);

        info.setStyle("-fx-background-color: #000000a0; -fx-text-fill: white; -fx-padding: 4 8; "
                + "-fx-background-radius: 6; -fx-font-family: monospace; -fx-font-size: 11px; -fx-text-alignment: center;");
        info.setMouseTransparent(true);

        overlay.getChildren().addAll(guideV, guideH, outline, rotateStem, rotateHandle);
        overlay.getChildren().addAll(edges);
        overlay.getChildren().addAll(corners);
        overlay.getChildren().add(info);
        setShapesVisible(false);

        overlay.addEventHandler(MouseEvent.MOUSE_PRESSED, this::onPressed);
        overlay.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::onDragged);
        overlay.addEventHandler(MouseEvent.MOUSE_RELEASED, this::onReleased);
        overlay.addEventHandler(MouseEvent.MOUSE_MOVED, this::onMoved);
        overlay.addEventHandler(ZoomEvent.ZOOM_STARTED, this::onZoomStarted);
        overlay.addEventHandler(ZoomEvent.ZOOM, this::onZoom);
        overlay.addEventHandler(ZoomEvent.ZOOM_FINISHED, e -> {
            if (pinching) {
                pinching = false;
                finishGesture();
            }
        });
        overlay.setFocusTraversable(true);
        overlay.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && mode != Mode.NONE) {
                pinching = false;
                cancel();
                e.consume();
            }
        });
    }

    public Pane getNode() {
        return overlay;
    }

    // ── drawing ──────────────────────────────────────────────────────────

    /** Redraws the box for the selected clip at the playhead. Cheap; call whenever time, selection or layout changes. */
    public void refresh() {
        Clip clip = mode != Mode.NONE ? target : host.primarySelectedClip();
        if (clip == null || !isVisual(clip) || !isActive(clip, host.currentTime())) {
            setShapesVisible(false);
            return;
        }
        VideoProperties p = (mode != Mode.NONE && live != null) ? live : resolve(clip, host.currentTime());
        double[] quad = quadFor(clip, p);
        if (quad == null) {
            setShapesVisible(false);
            return;
        }

        double[] s = new double[8];
        for (int i = 0; i < 4; i++) {
            Point2D o = toOverlay(quad[i * 2], quad[i * 2 + 1]);
            s[i * 2] = o.getX();
            s[i * 2 + 1] = o.getY();
        }
        outline.getPoints().setAll(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7]);

        boolean transformable = clip.type != ClipType.TEXT || gpuText(clip); // legacy text is move-only
        for (int i = 0; i < 4; i++) {
            corners[i].setCenterX(s[i * 2]);
            corners[i].setCenterY(s[i * 2 + 1]);
            corners[i].setVisible(transformable);
        }
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            double ex = s[j * 2] - s[i * 2], ey = s[j * 2 + 1] - s[i * 2 + 1];
            boolean show = transformable && Math.hypot(ex, ey) >= EDGE_MIN_SCREEN_LENGTH;
            edges[i].setVisible(show);
            if (show) {
                double mx = (s[i * 2] + s[j * 2]) / 2, my = (s[i * 2 + 1] + s[j * 2 + 1]) / 2;
                edges[i].setX(mx - edges[i].getWidth() / 2);
                edges[i].setY(my - edges[i].getHeight() / 2);
                edges[i].setRotate(Math.toDegrees(Math.atan2(ey, ex))); // the pill lies along its edge
            }
        }
        if (transformable) {
            double[] h = rotateHandleScreen(s);
            double mx = (s[0] + s[2]) / 2, my = (s[1] + s[3]) / 2;
            rotateStem.setStartX(mx);
            rotateStem.setStartY(my);
            rotateStem.setEndX(h[0]);
            rotateStem.setEndY(h[1]);
            rotateHandle.setCenterX(h[0]);
            rotateHandle.setCenterY(h[1]);
        }
        rotateStem.setVisible(transformable);
        rotateHandle.setVisible(transformable);
        outline.setVisible(true);

        refreshGuides();

        if (mode != Mode.NONE && live != null) {
            info.setText(String.format("Pos X: %.0f | Pos Y: %.0f%nScale X: %.2f | Scale Y: %.2f | Rot: %.1f",
                    live.valuePosX, live.valuePosY, live.valueScaleX, live.valueScaleY, live.valueRot));
            info.applyCss();
            info.autosize();
            info.setLayoutX(Math.max(8, overlay.getWidth() / 2 - info.prefWidth(-1) / 2));
            info.setLayoutY(Math.max(8, overlay.getHeight() - info.prefHeight(-1) - 12));
            info.setVisible(true);
        } else {
            info.setVisible(false);
        }
    }

    private void setShapesVisible(boolean v) {
        outline.setVisible(v);
        rotateStem.setVisible(v);
        rotateHandle.setVisible(v);
        for (Circle c : corners) c.setVisible(v);
        for (Rectangle r : edges) r.setVisible(v);
        guideV.setVisible(false);
        guideH.setVisible(false);
        info.setVisible(false);
    }

    /** Snap guides: a line across the whole canvas through the coordinate the gesture is snapped to. */
    private void refreshGuides() {
        boolean showX = mode != Mode.NONE && !Double.isNaN(guideX);
        boolean showY = mode != Mode.NONE && !Double.isNaN(guideY);
        if (showX) {
            Point2D a = toOverlay(guideX, 0), b = toOverlay(guideX, settings.videoHeight);
            guideV.setStartX(a.getX());
            guideV.setStartY(a.getY());
            guideV.setEndX(b.getX());
            guideV.setEndY(b.getY());
        }
        if (showY) {
            Point2D a = toOverlay(0, guideY), b = toOverlay(settings.videoWidth, guideY);
            guideH.setStartX(a.getX());
            guideH.setStartY(a.getY());
            guideH.setEndX(b.getX());
            guideH.setEndY(b.getY());
        }
        guideV.setVisible(showX);
        guideH.setVisible(showY);
    }

    /** The rotate handle sits ROTATE_OFFSET px beyond the middle of the top edge, away from the clip's centre. */
    private double[] rotateHandleScreen(double[] s) {
        double mx = (s[0] + s[2]) / 2, my = (s[1] + s[3]) / 2;
        double cx = (s[0] + s[4]) / 2, cy = (s[1] + s[5]) / 2;
        double dx = mx - cx, dy = my - cy;
        double len = Math.hypot(dx, dy);
        if (len < 1e-3) return new double[]{mx, my - ROTATE_OFFSET};
        return new double[]{mx + dx / len * ROTATE_OFFSET, my + dy / len * ROTATE_OFFSET};
    }

    // ── mouse ────────────────────────────────────────────────────────────

    private void onMoved(MouseEvent e) {
        if (mode != Mode.NONE || host.isPlaying()) return;
        Clip sel = host.primarySelectedClip();
        if (sel != null && isVisual(sel) && isActive(sel, host.currentTime())) {
            int handle = handleAt(sel, e.getX(), e.getY());
            if (handle == HANDLE_ROTATE) {
                overlay.setCursor(Cursor.HAND);
                return;
            }
            if (handle >= 0) {
                overlay.setCursor(cursorForHandle(sel, handle));
                return;
            }
        }
        List<Clip> hits = hitsAt(canvasPoint(e));
        overlay.setCursor(hits.isEmpty() ? Cursor.DEFAULT : Cursor.MOVE);
    }

    private void onPressed(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || host.isPlaying() || mode != Mode.NONE) return;
        float time = host.currentTime();
        Point2D canvasPoint = canvasPoint(e);

        Clip sel = host.primarySelectedClip();
        Mode startMode = Mode.NONE;
        int handleHit = -1;
        Clip chosen = null;

        if (sel != null && isVisual(sel) && isActive(sel, time) && (sel.type != ClipType.TEXT || gpuText(sel))) {
            int handle = handleAt(sel, e.getX(), e.getY());
            if (handle == HANDLE_ROTATE) {
                startMode = Mode.ROTATE;
                chosen = sel;
            } else if (handle >= 0) {
                startMode = Mode.SCALE;
                handleHit = handle;
                chosen = sel;
            }
        }
        if (chosen == null) {
            List<Clip> hits = hitsAt(canvasPoint);
            if (hits.isEmpty()) return;
            // Dragging inside the selected clip never grabs one stacked above it.
            chosen = (sel != null && hits.contains(sel)) ? sel : hits.get(0);
            startMode = Mode.MOVE;
            if (chosen != sel) host.selectClip(chosen);
        }

        overlay.requestFocus();
        beginGesture(chosen, startMode, canvasPoint, handleHit);
        e.consume();
    }

    private void onDragged(MouseEvent e) {
        if (mode == Mode.NONE || pinching) return;
        Point2D p = canvasPoint(e);
        switch (mode) {
            case MOVE: {
                double dx = p.getX() - pressCanvas.getX();
                double dy = p.getY() - pressCanvas.getY();
                boolean lockedX = false, lockedY = false;
                if (e.isShiftDown()) {
                    if (Math.abs(dx) >= Math.abs(dy)) { dy = 0; lockedY = true; } else { dx = 0; lockedX = true; }
                }
                VideoProperties v = new VideoProperties(basis);
                v.valuePosX = basis.valuePosX + (float) dx;
                v.valuePosY = basis.valuePosY + (float) dy;
                clearGuides();
                if (snapEnabled(e)) snapMove(v, !lockedX, !lockedY);
                show(v);
                break;
            }
            case SCALE:
                clearGuides();
                scaleTo(p, e.isAltDown(), snapEnabled(e));
                break;
            case ROTATE:
                rotateTo(p);
                break;
            default:
                break;
        }
        e.consume();
    }

    private void onReleased(MouseEvent e) {
        if (mode == Mode.NONE) return;
        finishGesture();
        e.consume();
    }

    // ── trackpad pinch ───────────────────────────────────────────────────

    private void onZoomStarted(ZoomEvent e) {
        if (host.isPlaying() || mode != Mode.NONE) return;
        Clip sel = host.primarySelectedClip();
        if (sel == null || (sel.type == ClipType.TEXT && !gpuText(sel)) || !isVisual(sel) || !isActive(sel, host.currentTime())) return;
        beginGesture(sel, Mode.SCALE, new Point2D(0, 0), -1);
        pinching = true;
        e.consume();
    }

    private void onZoom(ZoomEvent e) {
        if (!pinching || mode != Mode.SCALE) return;
        double f = Math.max(MIN_SCALE_FACTOR, e.getTotalZoomFactor());
        scaleAboutCenter((float) f);
        e.consume();
    }

    // ── gesture lifecycle ────────────────────────────────────────────────

    private void beginGesture(Clip clip, Mode startMode, Point2D canvasPoint, int handle) {
        this.target = clip;
        this.mode = startMode;
        this.pressCanvas = canvasPoint;
        this.draggedHandle = handle;
        clearGuides();
        collectSnapLines(clip);
        this.changed = false;
        this.keyframeInserted = false;
        this.before = Snapshot.of(clip);

        float time = host.currentTime();
        VideoProperties resolved = resolve(clip, time);
        keyIndex = -1;
        if (!clip.keyframes.keyframes.isEmpty()) {
            Keyframe at = clip.keyframes.getKeyframeAtTime(clip, time);
            if (at != null) {
                keyIndex = clip.keyframes.keyframes.indexOf(at);
                basis = new VideoProperties(at.value);
            } else {
                basis = resolved; // a keyframe is inserted from this look on the first real change
            }
        } else {
            basis = new VideoProperties(clip.videoProperties);
        }
        live = new VideoProperties(basis);

        startQuad = quadFor(clip, resolved);
        if (startQuad != null) {
            startCenter = new double[]{(startQuad[0] + startQuad[4]) / 2, (startQuad[1] + startQuad[5]) / 2};
            startAngle = Math.atan2(pressCanvas.getY() - startCenter[1], pressCanvas.getX() - startCenter[0]);
        }
    }

    /** Pushes in-progress values to the preview; the first real change also inserts the keyframe if one is needed. */
    private void show(VideoProperties v) {
        if (target == null) return;
        if (!changed && sameTransform(v, basis)) {
            live = v;
            refresh();
            return;
        }
        if (!changed) {
            changed = true;
            if (!target.keyframes.keyframes.isEmpty() && keyIndex < 0) insertKeyframe();
        }
        live = v;
        renderer.showLiveProperties(target, v, keyIndex, host.currentTime());
        refresh();
    }

    /** Adds a keyframe at the playhead from the current interpolated look (gesture-owned; undo removes it). */
    private void insertKeyframe() {
        Clip clip = target;
        float localTime = Math.max(0f, Math.min(host.currentTime() - clip.startTime, clip.duration));
        List<Keyframe> list = clip.keyframes.keyframes;
        // Keep the curve the new keyframe splits: it takes the easing of the keyframe before it.
        EasingType easing = EasingType.LINEAR;
        for (Keyframe k : list) {
            if (k.getLocalTime() <= localTime) easing = k.easing;
        }
        Keyframe kf = new Keyframe(localTime, new VideoProperties(basis), easing);
        list.add(kf);
        clip.keyframes.sortKeyframe();
        clip.keyframes.reassignKeyframes(settings.frameRate);
        keyIndex = list.indexOf(kf);
        keyframeInserted = true;
        // The worker has to see the new keyframe before any live patch addresses it by index.
        renderer.syncWorker(host.currentTime());
        host.clipChanged(clip);
    }

    private void finishGesture() {
        if (mode == Mode.NONE || target == null) {
            mode = Mode.NONE;
            return;
        }
        Clip clip = target;
        Snapshot start = before;
        VideoProperties finalValues = live;
        boolean wasChanged = changed;
        int finalKey = keyIndex;
        mode = Mode.NONE;
        target = null;
        live = null;
        draggedHandle = -1;
        clearGuides();

        if (!wasChanged || finalValues == null) {
            refresh();
            return;
        }

        // Make the clip hold the final values, then capture them as the "after" state.
        TimelineRenderer.writeProperties(clip, finalValues, finalKey);
        Snapshot end = Snapshot.of(clip);
        start.restore(clip);                       // commit() runs redo, which puts "end" back
        host.commit("Move/Transform: " + clip.getClipName(), () -> {
            end.restore(clip);
            host.clipChanged(clip);
        }, () -> {
            start.restore(clip);
            host.clipChanged(clip);
        });
        refresh();
    }

    /** Escape: drop the gesture and put everything back. */
    public void cancel() {
        if (mode == Mode.NONE || target == null) return;
        Clip clip = target;
        Snapshot start = before;
        mode = Mode.NONE;
        target = null;
        live = null;
        clearGuides();
        start.restore(clip);
        host.clipChanged(clip);
        refresh();
    }

    // ── transform maths ──────────────────────────────────────────────────

    /**
     * Scale from a handle. A corner scales both axes uniformly; an edge handle stretches just that edge's
     * axis (in the clip's own rotated frame). The opposite handle stays where it is - or, with Alt held,
     * the clip's centre does.
     */
    private void scaleTo(Point2D p, boolean fromCenter, boolean snapping) {
        if (startQuad == null || draggedHandle < 0) return;
        double[] grabbed = handlePoint(startQuad, draggedHandle);
        double[] anchor = fromCenter ? startCenter : handlePoint(startQuad, oppositeHandle(draggedHandle));
        double vx = grabbed[0] - anchor[0], vy = grabbed[1] - anchor[1];
        double len2 = vx * vx + vy * vy;
        if (len2 < 1e-3) return;
        // How far along anchor -> grabbed handle the pointer is: 1 = unchanged, 2 = twice as big.
        double f = ((p.getX() - anchor[0]) * vx + (p.getY() - anchor[1]) * vy) / len2;
        f = Math.max(MIN_SCALE_FACTOR, f);
        if (snapping) f = Math.max(MIN_SCALE_FACTOR, snapAlong(anchor[0], anchor[1], vx, vy, f));

        VideoProperties v = new VideoProperties(basis);
        if (draggedHandle < 4) {
            v.valueScaleX = basis.valueScaleX * (float) f;
            v.valueScaleY = basis.valueScaleY * (float) f;
        } else if (draggedHandle == H_TOP || draggedHandle == H_BOTTOM) {
            v.valueScaleY = basis.valueScaleY * (float) f;
        } else {
            v.valueScaleX = basis.valueScaleX * (float) f;
        }
        anchorPoint(v, fromCenter ? -1 : oppositeHandle(draggedHandle), anchor[0], anchor[1]);
        show(v);
    }

    /** Pinch: uniform scale that keeps the clip's centre where it is. */
    private void scaleAboutCenter(float factor) {
        if (startQuad == null) return;
        VideoProperties v = new VideoProperties(basis);
        v.valueScaleX = basis.valueScaleX * factor;
        v.valueScaleY = basis.valueScaleY * factor;
        double[] q = quadFor(target, v);
        if (q == null) return;
        double cx = (q[0] + q[4]) / 2, cy = (q[1] + q[5]) / 2;
        v.valuePosX += (float) (startCenter[0] - cx);
        v.valuePosY += (float) (startCenter[1] - cy);
        show(v);
    }

    /** Shifts PosX/PosY so that {@code handle} of the new quad (-1 = its centre) lands on (ax, ay). */
    private void anchorPoint(VideoProperties v, int handle, double ax, double ay) {
        double[] q = quadFor(target, v);
        if (q == null) return;
        double[] pt = handle < 0 ? centerOf(q) : handlePoint(q, handle);
        v.valuePosX += (float) (ax - pt[0]);
        v.valuePosY += (float) (ay - pt[1]);
    }

    /** Rotation about the clip's visual centre (the pivot is left alone), with 90-degree snapping. */
    private void rotateTo(Point2D p) {
        if (startQuad == null) return;
        double angle = Math.atan2(p.getY() - startCenter[1], p.getX() - startCenter[0]);
        double deltaDeg = Math.toDegrees(angle - startAngle);

        float rot = basis.valueRot + (float) deltaDeg;
        rot = ((rot + 360f) % 720f + 720f) % 720f - 360f;     // Android's [-360, 360) wrap
        float snap = Constants.CANVAS_ROTATE_SNAP_DEGREE;
        float nearest = Math.round(rot / snap) * snap;
        if (Math.abs(rot - nearest) <= Constants.CANVAS_ROTATE_SNAP_THRESHOLD_DEGREE) rot = nearest;

        VideoProperties v = new VideoProperties(basis);
        v.valueRot = rot;
        double[] q = quadFor(target, v);
        if (q != null) {
            double cx = (q[0] + q[4]) / 2, cy = (q[1] + q[5]) / 2;
            v.valuePosX += (float) (startCenter[0] - cx);
            v.valuePosY += (float) (startCenter[1] - cy);
        }
        show(v);
    }

    private static boolean sameTransform(VideoProperties a, VideoProperties b) {
        return a.valuePosX == b.valuePosX && a.valuePosY == b.valuePosY && a.valueRot == b.valueRot
                && a.valueScaleX == b.valueScaleX && a.valueScaleY == b.valueScaleY;
    }

    // ── geometry (same maths as OpenGLEdit.buildClipMvp / iOS ClipGeometry) ─

    /** A TEXT clip the preview worker draws (so its box can be computed and it can be scaled and rotated). */
    private boolean gpuText(Clip c) {
        return c.type == ClipType.TEXT && renderer.isGpuPreviewActive();
    }

    private static boolean isVisual(Clip c) {
        return c.type == ClipType.VIDEO || c.type == ClipType.IMAGE || c.type == ClipType.TEXT;
    }

    private static boolean isActive(Clip c, float t) {
        return t >= c.startTime && t < c.startTime + c.duration;
    }

    /**
     * The clip's full look at {@code t}: keyframe-interpolated when it has keyframes, static otherwise.
     * Every property is resolved (not just the transform) because a keyframe inserted by a gesture is
     * copied from this, and must not reset opacity, colour and the rest to their static values.
     */
    private static VideoProperties resolve(Clip clip, float t) {
        VideoProperties p = new VideoProperties(clip.videoProperties);
        if (clip.keyframes != null && !clip.keyframes.keyframes.isEmpty()) {
            for (ValueType type : ValueType.values()) {
                if (type == ValueType.RotInRadians) continue; // derived from Rot
                p.setValue(clip.keyframes.getValueAtTime(clip, t, type), type);
            }
        }
        return p;
    }

    /** Canvas-pixel corners TL, TR, BR, BL as x0,y0,x1,y1,..., or null when the clip has no box. */
    private double[] quadFor(Clip clip, VideoProperties p) {
        double baseW, baseH, posX = p.valuePosX, posY = p.valuePosY;
        if (clip.type == ClipType.TEXT) {
            if (gpuText(clip)) {
                // Same box the preview worker draws (TextLayoutEngine), centred on the canvas, then offset by
                // PosX/PosY - exactly OpenGLEdit.buildClipMvp's text rule.
                float[] box = com.vanvatcorporation.doubleclips.TextLayoutEngine.measure(
                        com.vanvatcorporation.doubleclips.TextStyle.of(clip, settings.videoWidth, host.fontsDirectory()));
                baseW = box[0];
                baseH = box[1];
                posX += (settings.videoWidth - baseW) / 2.0;
                posY += (settings.videoHeight - baseH) / 2.0;
            } else {
                // Legacy preview: only JavaFX knows how big the label is; its own bounds already include its transform.
                Bounds b = renderer.getClipViewBounds(clip);
                if (b == null || b.getWidth() <= 0) return null;
                return new double[]{b.getMinX(), b.getMinY(), b.getMaxX(), b.getMinY(),
                        b.getMaxX(), b.getMaxY(), b.getMinX(), b.getMaxY()};
            }
        } else {
            baseW = settings.isStretchToFull() ? settings.videoWidth : (clip.width > 0 ? clip.width : settings.videoWidth);
            baseH = settings.isStretchToFull() ? settings.videoHeight : (clip.height > 0 ? clip.height : settings.videoHeight);
        }
        double scaledW = baseW * p.valueScaleX;
        double scaledH = baseH * p.valueScaleY;
        double px = p.valuePivotX, py = p.valuePivotY;
        double pivotX = posX + px * baseW;
        double pivotY = posY + py * baseH;
        double theta = Math.toRadians(p.valueRot);
        double c = Math.cos(theta), s = Math.sin(theta);
        double[] out = new double[8];
        double[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int i = 0; i < 4; i++) {
            double x = (uv[i][0] - px) * scaledW;
            double y = (uv[i][1] - py) * scaledH;
            out[i * 2] = pivotX + x * c - y * s;
            out[i * 2 + 1] = pivotY + x * s + y * c;
        }
        return out;
    }

    /** Convex-quad hit test (works for mirrored and negative scales too). */
    private static boolean contains(Point2D p, double[] q) {
        double area = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            area += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1];
        }
        if (Math.abs(area) < 1) return false; // collapsed (scale 0)
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            double cross = (q[j * 2] - q[i * 2]) * (p.getY() - q[i * 2 + 1]) - (q[j * 2 + 1] - q[i * 2 + 1]) * (p.getX() - q[i * 2]);
            int s = cross > 0 ? 1 : (cross < 0 ? -1 : 0);
            if (s == 0) continue;
            if (sign == 0) sign = s; else if (sign != s) return false;
        }
        return true;
    }

    /** Visual clips under a canvas point, topmost first (later tracks are drawn over earlier ones). */
    private List<Clip> hitsAt(Point2D canvasPoint) {
        List<Clip> hits = new ArrayList<>();
        Timeline tl = host.timeline();
        float t = host.currentTime();
        if (tl == null) return hits;
        for (int ti = tl.tracks.size() - 1; ti >= 0; ti--) {
            Track track = tl.tracks.get(ti);
            if (track == null) continue;
            for (int ci = track.clips.size() - 1; ci >= 0; ci--) {
                Clip clip = track.clips.get(ci);
                if (clip == null || !isVisual(clip) || !isActive(clip, t)) continue;
                double[] q = quadFor(clip, resolve(clip, t));
                if (q != null && contains(canvasPoint, q)) hits.add(clip);
            }
        }
        return hits;
    }

    /** Which handle of {@code clip} is under an overlay point: 0-3 a corner, 4-7 an edge, HANDLE_ROTATE, or -1. */
    private int handleAt(Clip clip, double ox, double oy) {
        double[] quad = quadFor(clip, resolve(clip, host.currentTime()));
        if (quad == null) return -1;
        double[] s = new double[8];
        for (int i = 0; i < 4; i++) {
            Point2D o = toOverlay(quad[i * 2], quad[i * 2 + 1]);
            s[i * 2] = o.getX();
            s[i * 2 + 1] = o.getY();
        }
        double[] h = rotateHandleScreen(s);
        if (Math.hypot(ox - h[0], oy - h[1]) <= HANDLE_HIT) return HANDLE_ROTATE;
        for (int i = 0; i < 4; i++) {
            if (Math.hypot(ox - s[i * 2], oy - s[i * 2 + 1]) <= HANDLE_HIT) return i;
        }
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            if (Math.hypot(s[j * 2] - s[i * 2], s[j * 2 + 1] - s[i * 2 + 1]) < EDGE_MIN_SCREEN_LENGTH) continue;
            double mx = (s[i * 2] + s[j * 2]) / 2, my = (s[i * 2 + 1] + s[j * 2 + 1]) / 2;
            if (Math.hypot(ox - mx, oy - my) <= EDGE_HIT) return H_TOP + i;
        }
        return -1;
    }

    // ── handles ──────────────────────────────────────────────────────────

    /** Canvas position of handle {@code h} (corner 0-3, edge midpoint 4-7) on a quad. */
    private static double[] handlePoint(double[] q, int h) {
        if (h < 4) return new double[]{q[h * 2], q[h * 2 + 1]};
        int a = h - H_TOP, b = (a + 1) % 4; // edge a runs from corner a to corner a+1
        return new double[]{(q[a * 2] + q[b * 2]) / 2, (q[a * 2 + 1] + q[b * 2 + 1]) / 2};
    }

    private static int oppositeHandle(int h) {
        return h < 4 ? (h + 2) % 4 : H_TOP + ((h - H_TOP + 2) % 4);
    }

    private static double[] centerOf(double[] q) {
        return new double[]{(q[0] + q[4]) / 2, (q[1] + q[5]) / 2};
    }

    /** A resize cursor along the direction from the clip's centre to the handle (works for rotated clips too). */
    private Cursor cursorForHandle(Clip clip, int handle) {
        double[] q = quadFor(clip, resolve(clip, host.currentTime()));
        if (q == null) return Cursor.CROSSHAIR;
        double[] c = centerOf(q), h = handlePoint(q, handle);
        double deg = Math.toDegrees(Math.atan2(h[1] - c[1], h[0] - c[0]));
        double a = ((deg % 180) + 180) % 180;
        if (a < 22.5 || a >= 157.5) return Cursor.E_RESIZE;
        if (a < 67.5) return Cursor.SE_RESIZE;
        if (a < 112.5) return Cursor.S_RESIZE;
        return Cursor.SW_RESIZE;
    }

    // ── snapping ─────────────────────────────────────────────────────────

    /** Snapping is on unless Ctrl or Cmd is held. */
    private static boolean snapEnabled(MouseEvent e) {
        return !(e.isControlDown() || e.isMetaDown());
    }

    private void clearGuides() {
        guideX = Double.NaN;
        guideY = Double.NaN;
    }

    /** On-screen pixels per canvas pixel (the preview is scaled to fit its pane). */
    private double screenScale() {
        Point2D a = toOverlay(0, 0), b = toOverlay(1, 0);
        double d = Math.hypot(b.getX() - a.getX(), b.getY() - a.getY());
        return d > 1e-6 ? d : 1;
    }

    /** The lines a gesture can snap to: the canvas edges and centre, plus every other visible clip's edges and centre. */
    private void collectSnapLines(Clip exclude) {
        snapXs.clear();
        snapYs.clear();
        snapXs.add(0.0);
        snapXs.add(settings.videoWidth / 2.0);
        snapXs.add((double) settings.videoWidth);
        snapYs.add(0.0);
        snapYs.add(settings.videoHeight / 2.0);
        snapYs.add((double) settings.videoHeight);

        Timeline tl = host.timeline();
        float t = host.currentTime();
        if (tl == null) return;
        for (Track track : tl.tracks) {
            if (track == null) continue;
            for (Clip clip : track.clips) {
                if (clip == null || clip == exclude || !isVisual(clip) || !isActive(clip, t)) continue;
                double[] q = quadFor(clip, resolve(clip, t));
                if (q == null) continue;
                double minX = Math.min(Math.min(q[0], q[2]), Math.min(q[4], q[6]));
                double maxX = Math.max(Math.max(q[0], q[2]), Math.max(q[4], q[6]));
                double minY = Math.min(Math.min(q[1], q[3]), Math.min(q[5], q[7]));
                double maxY = Math.max(Math.max(q[1], q[3]), Math.max(q[5], q[7]));
                snapXs.add(minX);
                snapXs.add((minX + maxX) / 2);
                snapXs.add(maxX);
                snapYs.add(minY);
                snapYs.add((minY + maxY) / 2);
                snapYs.add(maxY);
            }
        }
    }

    /**
     * Moving: nudges PosX/PosY so the clip's left/centre/right (and top/centre/bottom) land on a snap line
     * when within a few screen pixels. An axis locked by Shift is left alone.
     */
    private void snapMove(VideoProperties v, boolean allowX, boolean allowY) {
        double[] q = quadFor(target, v);
        if (target.type == ClipType.TEXT && !gpuText(target) && startQuad != null) {
            // Legacy preview only: a text clip's box is the label's own bounds, which lag the live values
            // by a frame, so use the start box shifted by how far the position has moved.
            double dx = v.valuePosX - basis.valuePosX, dy = v.valuePosY - basis.valuePosY;
            q = new double[8];
            for (int i = 0; i < 4; i++) {
                q[i * 2] = startQuad[i * 2] + dx;
                q[i * 2 + 1] = startQuad[i * 2 + 1] + dy;
            }
        }
        if (q == null) return;
        double threshold = SNAP_SCREEN_PX / screenScale();

        double minX = Math.min(Math.min(q[0], q[2]), Math.min(q[4], q[6]));
        double maxX = Math.max(Math.max(q[0], q[2]), Math.max(q[4], q[6]));
        double minY = Math.min(Math.min(q[1], q[3]), Math.min(q[5], q[7]));
        double maxY = Math.max(Math.max(q[1], q[3]), Math.max(q[5], q[7]));
        double[] movingX = {minX, (minX + maxX) / 2, maxX};
        double[] movingY = {minY, (minY + maxY) / 2, maxY};

        double bestX = threshold + 1, shiftX = 0, lineX = Double.NaN;
        double bestY = threshold + 1, shiftY = 0, lineY = Double.NaN;
        if (allowX) {
            for (double m : movingX) for (double line : snapXs) {
                double d = line - m;
                if (Math.abs(d) < bestX) { bestX = Math.abs(d); shiftX = d; lineX = line; }
            }
        }
        if (allowY) {
            for (double m : movingY) for (double line : snapYs) {
                double d = line - m;
                if (Math.abs(d) < bestY) { bestY = Math.abs(d); shiftY = d; lineY = line; }
            }
        }
        if (bestX <= threshold) {
            v.valuePosX += (float) shiftX;
            guideX = lineX;
        }
        if (bestY <= threshold) {
            v.valuePosY += (float) shiftY;
            guideY = lineY;
        }
    }

    /**
     * Scaling: the grabbed handle moves along anchor + f * (vx, vy). Returns the f (near {@code f}) that puts
     * it exactly on a snap line, if one is within a few screen pixels, and shows that guide.
     */
    private double snapAlong(double ox, double oy, double vx, double vy, double f) {
        double threshold = SNAP_SCREEN_PX / screenScale();
        double best = Double.MAX_VALUE, snapped = f, lineX = Double.NaN, lineY = Double.NaN;
        if (Math.abs(vx) > 1e-6) {
            for (double line : snapXs) {
                double fl = (line - ox) / vx;
                double dist = Math.abs(fl - f) * Math.abs(vx);   // how far the handle is from the line, in canvas px
                if (fl > MIN_SCALE_FACTOR && dist < best && dist <= threshold) {
                    best = dist; snapped = fl; lineX = line; lineY = Double.NaN;
                }
            }
        }
        if (Math.abs(vy) > 1e-6) {
            for (double line : snapYs) {
                double fl = (line - oy) / vy;
                double dist = Math.abs(fl - f) * Math.abs(vy);
                if (fl > MIN_SCALE_FACTOR && dist < best && dist <= threshold) {
                    best = dist; snapped = fl; lineY = line; lineX = Double.NaN;
                }
            }
        }
        guideX = lineX;
        guideY = lineY;
        return snapped;
    }

    // ── coordinate conversion ────────────────────────────────────────────

    private Point2D canvasPoint(MouseEvent e) {
        return renderPane.sceneToLocal(e.getSceneX(), e.getSceneY());
    }

    private Point2D toOverlay(double canvasX, double canvasY) {
        return overlay.sceneToLocal(renderPane.localToScene(canvasX, canvasY));
    }

    // ── undo snapshot ────────────────────────────────────────────────────

    /**
     * Everything a gesture can touch on one clip: its static properties and its keyframe list with
     * each keyframe's value (copied, so later edits can't leak in). Keyframe objects keep their
     * identity - the timeline's knot UI holds references to them.
     */
    private static final class Snapshot {
        final VideoProperties statics;
        final List<Keyframe> keys;
        final List<VideoProperties> values;

        private Snapshot(VideoProperties statics, List<Keyframe> keys, List<VideoProperties> values) {
            this.statics = statics;
            this.keys = keys;
            this.values = values;
        }

        static Snapshot of(Clip clip) {
            List<Keyframe> keys = new ArrayList<>(clip.keyframes.keyframes);
            List<VideoProperties> values = new ArrayList<>();
            for (Keyframe k : keys) values.add(new VideoProperties(k.value));
            return new Snapshot(new VideoProperties(clip.videoProperties), keys, values);
        }

        void restore(Clip clip) {
            clip.videoProperties = new VideoProperties(statics);
            clip.keyframes.keyframes.clear();
            clip.keyframes.keyframes.addAll(keys);
            for (int i = 0; i < keys.size(); i++) keys.get(i).value = new VideoProperties(values.get(i));
        }
    }
}
