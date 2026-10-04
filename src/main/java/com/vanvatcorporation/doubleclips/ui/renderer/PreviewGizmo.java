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
    }

    private static final double HANDLE_RADIUS = 5.5;
    private static final double HANDLE_HIT = 10;
    private static final double ROTATE_OFFSET = 30;
    private static final float MIN_SCALE_FACTOR = 0.02f;

    private enum Mode { NONE, MOVE, SCALE, ROTATE }

    private final Host host;
    private final TimelineRenderer renderer;
    private final Pane renderPane;
    private final VideoSettings settings;
    private final Pane overlay = new Pane();

    private final Polygon outline = new Polygon();
    private final Circle[] corners = new Circle[4];
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
    private int draggedCorner = -1;
    private double[] startQuad;
    private double startAngle;
    private double[] startCenter;
    private boolean pinching;
    private boolean changed;

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

        overlay.getChildren().addAll(outline, rotateStem, rotateHandle);
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

        boolean transformable = clip.type != ClipType.TEXT; // text is move-only, like iOS
        for (int i = 0; i < 4; i++) {
            corners[i].setCenterX(s[i * 2]);
            corners[i].setCenterY(s[i * 2 + 1]);
            corners[i].setVisible(transformable);
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
        info.setVisible(false);
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
                overlay.setCursor(Cursor.CROSSHAIR);
                return;
            }
        }
        List<Clip> hits = hitsAt(canvasPoint(e));
        overlay.setCursor(hits.isEmpty() ? Cursor.DEFAULT : Cursor.MOVE);
    }

    private static final int HANDLE_ROTATE = 100;

    private void onPressed(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || host.isPlaying() || mode != Mode.NONE) return;
        float time = host.currentTime();
        Point2D canvasPoint = canvasPoint(e);

        Clip sel = host.primarySelectedClip();
        Mode startMode = Mode.NONE;
        int corner = -1;
        Clip chosen = null;

        if (sel != null && isVisual(sel) && isActive(sel, time) && sel.type != ClipType.TEXT) {
            int handle = handleAt(sel, e.getX(), e.getY());
            if (handle == HANDLE_ROTATE) {
                startMode = Mode.ROTATE;
                chosen = sel;
            } else if (handle >= 0) {
                startMode = Mode.SCALE;
                corner = handle;
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
        beginGesture(chosen, startMode, canvasPoint, corner);
        e.consume();
    }

    private void onDragged(MouseEvent e) {
        if (mode == Mode.NONE || pinching) return;
        Point2D p = canvasPoint(e);
        switch (mode) {
            case MOVE: {
                double dx = p.getX() - pressCanvas.getX();
                double dy = p.getY() - pressCanvas.getY();
                if (e.isShiftDown()) {
                    if (Math.abs(dx) >= Math.abs(dy)) dy = 0; else dx = 0;
                }
                VideoProperties v = new VideoProperties(basis);
                v.valuePosX = basis.valuePosX + (float) dx;
                v.valuePosY = basis.valuePosY + (float) dy;
                show(v);
                break;
            }
            case SCALE:
                scaleTo(p);
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
        if (sel == null || sel.type == ClipType.TEXT || !isVisual(sel) || !isActive(sel, host.currentTime())) return;
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

    private void beginGesture(Clip clip, Mode startMode, Point2D canvasPoint, int corner) {
        this.target = clip;
        this.mode = startMode;
        this.pressCanvas = canvasPoint;
        this.draggedCorner = corner;
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
        draggedCorner = -1;

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
        start.restore(clip);
        host.clipChanged(clip);
        refresh();
    }

    // ── transform maths ──────────────────────────────────────────────────

    /** Uniform scale from a corner handle; the opposite corner stays put. */
    private void scaleTo(Point2D p) {
        if (startQuad == null || draggedCorner < 0) return;
        int opposite = (draggedCorner + 2) % 4;
        double ox = startQuad[opposite * 2], oy = startQuad[opposite * 2 + 1];
        double dx0 = startQuad[draggedCorner * 2] - ox, dy0 = startQuad[draggedCorner * 2 + 1] - oy;
        double len2 = dx0 * dx0 + dy0 * dy0;
        if (len2 < 1e-3) return;
        double f = ((p.getX() - ox) * dx0 + (p.getY() - oy) * dy0) / len2; // projection onto the start diagonal
        f = Math.max(MIN_SCALE_FACTOR, f);

        VideoProperties v = new VideoProperties(basis);
        v.valueScaleX = basis.valueScaleX * (float) f;
        v.valueScaleY = basis.valueScaleY * (float) f;
        anchorPoint(v, opposite, ox, oy);
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

    /** Shifts PosX/PosY so that corner {@code corner} of the new quad lands on (ax, ay). */
    private void anchorPoint(VideoProperties v, int corner, double ax, double ay) {
        double[] q = quadFor(target, v);
        if (q == null) return;
        v.valuePosX += (float) (ax - q[corner * 2]);
        v.valuePosY += (float) (ay - q[corner * 2 + 1]);
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
        if (clip.type == ClipType.TEXT) {
            // Only JavaFX knows how big the label is; its own bounds already include the preview's transform.
            Bounds b = renderer.getClipViewBounds(clip);
            if (b == null || b.getWidth() <= 0) return null;
            return new double[]{b.getMinX(), b.getMinY(), b.getMaxX(), b.getMinY(),
                    b.getMaxX(), b.getMaxY(), b.getMinX(), b.getMaxY()};
        }
        double baseW = settings.isStretchToFull() ? settings.videoWidth : (clip.width > 0 ? clip.width : settings.videoWidth);
        double baseH = settings.isStretchToFull() ? settings.videoHeight : (clip.height > 0 ? clip.height : settings.videoHeight);
        double scaledW = baseW * p.valueScaleX;
        double scaledH = baseH * p.valueScaleY;
        double px = p.valuePivotX, py = p.valuePivotY;
        double pivotX = p.valuePosX + px * baseW;
        double pivotY = p.valuePosY + py * baseH;
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

    /** Which handle of {@code clip} is under an overlay point: 0-3 a corner, HANDLE_ROTATE, or -1. */
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
        return -1;
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
