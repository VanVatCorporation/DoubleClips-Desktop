package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Keyframe;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import java.util.ArrayList;
import java.util.List;

import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.input.MouseEvent;

/**
 * A timeline clip node with a CapCut-style repeating thumbnail strip.
 *
 * Layout (front → back):
 *   ┌────────────────────────────────────────────┐
 *   │  name  timecode            [selection rim] │  ← overlay (mouse-transparent)
 *   │  [thumb][thumb][thumb][thumb]...           │  ← thumbnail HBox (clipped)
 *   │  teal gradient background                  │
 *   └────────────────────────────────────────────┘
 *
 * Thumbnails expand / retract automatically when the node width changes
 * (i.e. when the zoom level changes).  Each tile is (height × 16/9) px wide.
 * Real frame images can be pushed in via {@link #setThumbnailImage(int, Image)}.
 */
public class ClipNode extends Pane {

    /** Aspect ratio used to compute each thumbnail tile's width. */
    private static final double THUMB_ASPECT = 16.0 / 9.0;

    private final Clip    clip;
    private final HBox    thumbnailRow;
    private final ImageView singleImageOverlay;
    private final Label   nameLabel;
    private final Label   timecodeLabel;
    private final Rectangle selBorder;
    
    private java.util.function.Consumer<Keyframe> onKeyframeClicked;
    private Runnable onKeyframesModified;
    public interface KeyframeMoveHandler { void onKeyframeMoved(Keyframe kf, float oldTime, float newTime); }
    private KeyframeMoveHandler onKeyframeMoved;

    public interface TrimHandler {
        void onTrimFinished(Clip clip,
                            float oldStart, float newStart,
                            float oldDur, float newDur,
                            float oldSTrim, float newSTrim,
                            float oldETrim, float newETrim);
    }
    private TrimHandler onTrimFinished;

    private final Rectangle leftHandle;
    private final Rectangle rightHandle;

    public ClipNode(Clip clip) {
        this.clip = clip;

        // ── Teal background ───────────────────────────────────────────────────
        Rectangle bg = new Rectangle();
        bg.widthProperty().bind(widthProperty());
        bg.heightProperty().bind(heightProperty());
        bg.setArcWidth(7);
        bg.setArcHeight(7);
        bg.getStyleClass().add("clip-bg");

        // ── Thumbnail strip ───────────────────────────────────────────────────
        thumbnailRow = new HBox(1);
        thumbnailRow.setLayoutX(0);
        thumbnailRow.setLayoutY(0);
        thumbnailRow.prefWidthProperty().bind(widthProperty());
        thumbnailRow.prefHeightProperty().bind(heightProperty());
        thumbnailRow.maxWidthProperty().bind(widthProperty());
        thumbnailRow.maxHeightProperty().bind(heightProperty());
        // Clip so thumbs never overflow the rounded corners
        Rectangle thumbMask = new Rectangle();
        thumbMask.widthProperty().bind(widthProperty());
        thumbMask.heightProperty().bind(heightProperty());
        thumbnailRow.setClip(thumbMask);

        // ── Dark gradient scrim so labels are readable ────────────────────────
        Region scrim = new Region();
        scrim.getStyleClass().add("clip-label-scrim");
        scrim.prefWidthProperty().bind(widthProperty());
        scrim.prefHeightProperty().bind(heightProperty());
        scrim.setMouseTransparent(true);

        // ── Selection border ──────────────────────────────────────────────────
        selBorder = new Rectangle();
        selBorder.widthProperty().bind(widthProperty());
        selBorder.heightProperty().bind(heightProperty());
        selBorder.setArcWidth(7);
        selBorder.setArcHeight(7);
        selBorder.setFill(Color.TRANSPARENT);
        selBorder.setStroke(Color.TRANSPARENT);
        selBorder.setStrokeWidth(2.5);
        selBorder.setMouseTransparent(true);

        // ── Overlay labels ────────────────────────────────────────────────────
        nameLabel = new Label(clip.getClipName());
        nameLabel.getStyleClass().add("clip-name-label");
        nameLabel.setMouseTransparent(true);
        nameLabel.setLayoutX(6);
        nameLabel.setLayoutY(3);

        timecodeLabel = new Label(formatDuration(clip.duration));
        timecodeLabel.getStyleClass().add("clip-timecode-label");
        timecodeLabel.setMouseTransparent(true);
        timecodeLabel.setLayoutX(6);
        timecodeLabel.setLayoutY(17);

        // ── Single Image Overlay ──────────────────────────────────────────────
        singleImageOverlay = new ImageView();
        singleImageOverlay.setMouseTransparent(true);
        singleImageOverlay.setPreserveRatio(false);
        singleImageOverlay.fitWidthProperty().bind(widthProperty());
        singleImageOverlay.fitHeightProperty().bind(heightProperty());
        singleImageOverlay.setVisible(false);

        // ── Trim Handles ──────────────────────────────────────────────────────
        double handleWidth = 12.0;
        leftHandle = new Rectangle(handleWidth, 0);
        leftHandle.heightProperty().bind(heightProperty());
        leftHandle.setFill(Color.web("#00D4FF"));
        leftHandle.setCursor(Cursor.H_RESIZE);
        leftHandle.setVisible(false);

        rightHandle = new Rectangle(handleWidth, 0);
        rightHandle.heightProperty().bind(heightProperty());
        rightHandle.translateXProperty().bind(widthProperty().subtract(handleWidth));
        rightHandle.setFill(Color.web("#00D4FF"));
        rightHandle.setCursor(Cursor.H_RESIZE);
        rightHandle.setVisible(false);

        getChildren().addAll(bg, thumbnailRow, singleImageOverlay, scrim, nameLabel, timecodeLabel, selBorder, leftHandle, rightHandle);

        // ── Refresh thumbnails when size changes (zoom) ───────────────────────
        widthProperty().addListener((o, ov, nv)  -> { if (nv.doubleValue() > 0) refreshThumbnails(); });
        heightProperty().addListener((o, ov, nv) -> { if (nv.doubleValue() > 0) refreshThumbnails(); });

        getStyleClass().add("clip-node");
        refreshLabels();
    }

    /** Refresh name and duration labels. */
    public void refreshLabels() {
        nameLabel.setText(clip.getClipName());
        timecodeLabel.setText(formatDuration(clip.duration));
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Select / deselect visual highlight. */
    public void setSelected(boolean selected) {
        selBorder.setStroke(selected ? Color.web("#00D4FF") : Color.TRANSPARENT);
        leftHandle.setVisible(selected);
        rightHandle.setVisible(selected);
        if (selected) toFront();
    }

    /**
     * Feed a decoded video-frame image into the given tile slot.
     * Call this from a background thread result once frames are decoded.
     */
    public void setThumbnailImage(int tileIndex, Image image) {
        if (tileIndex < 0 || tileIndex >= thumbnailRow.getChildren().size()) return;
        var node = thumbnailRow.getChildren().get(tileIndex);
        if (node instanceof StackPane sp && !sp.getChildren().isEmpty()
                && sp.getChildren().get(0) instanceof ImageView iv) {
            iv.setImage(image);
        }
    }

    /**
     * Sets a single image spanning the entire clip width.
     * Used for AUDIO, TEXT, EFFECT, and IMAGE clips instead of tiled thumbnails.
     */
    public void setSingleThumbnail(Image image) {
        thumbnailRow.setVisible(false);
        singleImageOverlay.setImage(image);
        singleImageOverlay.setVisible(true);
    }

    /** Number of currently rendered thumbnail tiles. */
    public int getTileCount() {
        if (thumbnailRow.getChildren().isEmpty()) refreshThumbnails();
        return thumbnailRow.getChildren().size();
    }

    public Clip getContainerClip() { return clip; }
    
    public void setOnKeyframeClicked(java.util.function.Consumer<Keyframe> handler) { this.onKeyframeClicked = handler; }
    public void setOnKeyframesModified(Runnable handler) { this.onKeyframesModified = handler; }
    public void setOnKeyframeMoved(KeyframeMoveHandler handler) { this.onKeyframeMoved = handler; }
    public void setOnTrimFinished(TrimHandler handler) { this.onTrimFinished = handler; }

    public void setupTrimInteractions(double pixelsPerSecond) {
        final double[] dragStartX = new double[1];
        final float[] initialStartTime = new float[1];
        final float[] initialDuration = new float[1];
        final float[] initialStartTrim = new float[1];
        final float[] initialEndTrim = new float[1];

        // --- Left Handle Drag ---
        leftHandle.setOnMousePressed(e -> {
            dragStartX[0] = e.getSceneX();
            initialStartTime[0] = clip.startTime;
            initialDuration[0] = clip.duration;
            initialStartTrim[0] = clip.startClipTrim;
            initialEndTrim[0] = clip.endClipTrim;
            e.consume();
        });

        leftHandle.setOnMouseDragged(e -> {
            double deltaX = e.getSceneX() - dragStartX[0];
            float deltaTime = (float) (deltaX / pixelsPerSecond);

            // Clamp delta so we don't trim before 0 or after originalDuration
            // and don't make clip too small
            float minDuration = 0.1f;
            float maxDelta = initialStartTrim[0]; // can only reduce trim to 0
            float minDelta = -(initialDuration[0] - minDuration); // can only increase trim up to minDuration left

            // Limit by minimum duration
            float proposedDelta = deltaTime;
            proposedDelta = Math.min(initialDuration[0] - minDuration, proposedDelta);
            
            if (clip.type == ClipType.VIDEO || clip.type == ClipType.AUDIO) {
                // Limit by source start (cannot trim before 0 of file)
                proposedDelta = Math.max(-initialStartTrim[0], proposedDelta);
                
                clip.startTime = initialStartTime[0] + proposedDelta;
                clip.startClipTrim = initialStartTrim[0] + proposedDelta;
                clip.duration = initialDuration[0] - proposedDelta;
            } else {
                // Unlimited resources: IMAGE, TEXT, EFFECT, etc.
                // Just move start time and adjust duration, no trim needed
                clip.startTime = initialStartTime[0] + proposedDelta;
                clip.duration = initialDuration[0] - proposedDelta;
            }

            // Visual update
            updateVisuals(pixelsPerSecond);
            e.consume();
        });

        leftHandle.setOnMouseReleased(e -> {
            if (onTrimFinished != null) {
                onTrimFinished.onTrimFinished(clip,
                        initialStartTime[0], clip.startTime,
                        initialDuration[0], clip.duration,
                        initialStartTrim[0], clip.startClipTrim,
                        initialEndTrim[0], clip.endClipTrim);
            }
            e.consume();
        });

        // --- Right Handle Drag ---
        rightHandle.setOnMousePressed(e -> {
            dragStartX[0] = e.getSceneX();
            initialStartTime[0] = clip.startTime;
            initialDuration[0] = clip.duration;
            initialStartTrim[0] = clip.startClipTrim;
            initialEndTrim[0] = clip.endClipTrim;
            e.consume();
        });

        rightHandle.setOnMouseDragged(e -> {
            double deltaX = e.getSceneX() - dragStartX[0];
            float deltaTime = (float) (deltaX / pixelsPerSecond);

            float minDuration = 0.1f;
            float proposedDelta = deltaTime;
            
            // Limit by minimum duration
            proposedDelta = Math.max(-(initialDuration[0] - minDuration), proposedDelta);

            if (clip.type == ClipType.VIDEO || clip.type == ClipType.AUDIO) {
                // Limit by source end (cannot trim after originalDuration)
                proposedDelta = Math.min(initialEndTrim[0], proposedDelta);
                
                clip.duration = initialDuration[0] + proposedDelta;
                clip.endClipTrim = initialEndTrim[0] - proposedDelta;
            } else {
                // Unlimited resources: IMAGE, TEXT, EFFECT, etc.
                // Just adjust duration, no trim needed
                clip.duration = initialDuration[0] + proposedDelta;
            }

            // Visual update
            updateVisuals(pixelsPerSecond);
            e.consume();
        });

        rightHandle.setOnMouseReleased(e -> {
            if (onTrimFinished != null) {
                onTrimFinished.onTrimFinished(clip,
                        initialStartTime[0], clip.startTime,
                        initialDuration[0], clip.duration,
                        initialStartTrim[0], clip.startClipTrim,
                        initialEndTrim[0], clip.endClipTrim);
            }
            e.consume();
        });
    }

    private void updateVisuals(double pixelsPerSecond) {
        double w = clip.duration * pixelsPerSecond;
        setPrefWidth(w);
        setMinWidth(w);
        setMaxWidth(w);
        setLayoutX(clip.startTime * pixelsPerSecond);
        refreshLabels();
        updateKeyframes((float) pixelsPerSecond);
    }

    // ── Keyframe diamonds ──────────────────────────────────────────────────────

    /**
     * Rebuild keyframe diamond knots from the clip's keyframe list.
     * Called by EditorWindow after any keyframe add/remove and on zoom changes.
     */
    public void updateKeyframes(float pixelsPerSecond) {
        // Remove existing knots first
        clearKeyframeKnots();

        if (clip.keyframes == null || clip.keyframes.keyframes == null) return;

        double h = getHeight() > 0 ? getHeight() : getPrefHeight();
        double knotSize = 10.0;

        for (Keyframe kf : clip.keyframes.keyframes) {
            Rectangle knot = new Rectangle(knotSize, knotSize);
            knot.setFill(Color.WHITE);
            knot.setStroke(Color.web("#00D4FF"));
            knot.setStrokeWidth(1.5);
            knot.setArcWidth(0);
            knot.setArcHeight(0);
            knot.setRotate(45);
            knot.setMouseTransparent(false);
            knot.getStyleClass().add("keyframe-knot");

            // Tag so we can identify and remove these later
            knot.setUserData(kf);

            // Position: X = localTime * pps centred, Y = vertical centre
            double cx = kf.getLocalTime() * pixelsPerSecond;
            double cy = h / 2.0;

            // A rotated 10×10 square has a visual diagonal of √2 * 10 ≈ 14
            // LayoutX/Y refer to the top-left of the un-rotated bounding box
            knot.setLayoutX(cx - knotSize / 2.0);
            knot.setLayoutY(cy - knotSize / 2.0);

            // Interaction
            knot.setOnMouseClicked(e -> {
                if (e.isStillSincePress() && onKeyframeClicked != null) {
                    onKeyframeClicked.accept(kf);
                    e.consume();
                }
            });

            final double[] dragStartX = new double[1];
            final double[] originalKnotX = new double[1];
            final float[] oldTime = new float[1];

            knot.setOnMousePressed(e -> {
                dragStartX[0] = e.getSceneX();
                originalKnotX[0] = knot.getLayoutX();
                oldTime[0] = kf.getLocalTime();
                e.consume();
            });

            knot.setOnMouseDragged(e -> {
                double deltaX = e.getSceneX() - dragStartX[0];
                double newLayoutX = originalKnotX[0] + deltaX;
                
                // Clamp to clip bounds (0 to width)
                double minX = -knotSize / 2.0;
                double maxX = getWidth() - knotSize / 2.0;
                newLayoutX = Math.max(minX, Math.min(maxX, newLayoutX));
                
                knot.setLayoutX(newLayoutX);
                
                // Update keyframe time temporarily
                double newCx = newLayoutX + knotSize / 2.0;
                double newLocalTime = newCx / pixelsPerSecond;
                kf.setLocalTime((float) newLocalTime);
                
                e.consume();
            });

            knot.setOnMouseReleased(e -> {
                if (!e.isStillSincePress()) {
                    if (clip.keyframes != null) {
                        clip.keyframes.sortKeyframe();
                    }
                    if (onKeyframeMoved != null) {
                        onKeyframeMoved.onKeyframeMoved(kf, oldTime[0], kf.getLocalTime());
                    }
                    if (onKeyframesModified != null) {
                        onKeyframesModified.run();
                    }
                }
                e.consume();
            });

            getChildren().add(knot);
        }
    }

    /** Remove all keyframe diamond knots (Rectangle nodes tagged with a Keyframe). */
    public void clearKeyframeKnots() {
        List<javafx.scene.Node> toRemove = new ArrayList<>();
        for (javafx.scene.Node n : getChildren()) {
            if (n instanceof Rectangle r && r.getUserData() instanceof Keyframe) {
                toRemove.add(n);
            }
        }
        getChildren().removeAll(toRemove);
    }

    // ── Thumbnail management ───────────────────────────────────────────────────

    /**
     * Recalculate tile count and widths to fill the current node width.
     * Called automatically on zoom (width change).
     */
    public void refreshThumbnails() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0) w = getPrefWidth();
        if (h <= 0) h = getPrefHeight();
        if (w <= 0 || h <= 0) return;

        double tw      = h * THUMB_ASPECT;                    // tile pixel width
        int    needed  = (int) Math.ceil(w / tw) + 1;         // +1 for partial right tile
        int    current = thumbnailRow.getChildren().size();

        if (needed > current) {
            for (int i = current; i < needed; i++) {
                thumbnailRow.getChildren().add(buildThumbTile(tw, h));
            }
        } else if (needed < current) {
            thumbnailRow.getChildren().remove(needed, current);
        }

        // Keep all tiles sized correctly (h drives width through aspect ratio)
        for (var node : thumbnailRow.getChildren()) {
            if (node instanceof StackPane sp) {
                sp.setPrefWidth(tw);  sp.setMinWidth(tw);  sp.setMaxWidth(tw);
                sp.setPrefHeight(h);  sp.setMinHeight(h);  sp.setMaxHeight(h);
                if (!sp.getChildren().isEmpty() && sp.getChildren().get(0) instanceof ImageView iv) {
                    iv.setFitWidth(tw);
                    iv.setFitHeight(h);
                }
            }
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private StackPane buildThumbTile(double w, double h) {
        ImageView iv = new ImageView();
        iv.setFitWidth(w);
        iv.setFitHeight(h);
        iv.setPreserveRatio(false);
        iv.setSmooth(true);

        StackPane sp = new StackPane(iv);
        sp.getStyleClass().add("clip-thumb-tile");
        sp.setPrefWidth(w);  sp.setMinWidth(w);  sp.setMaxWidth(w);
        sp.setPrefHeight(h); sp.setMinHeight(h); sp.setMaxHeight(h);
        return sp;
    }

    private static String formatDuration(float secs) {
        int total = (int) secs;
        int mm = total / 60, ss = total % 60;
        int ff = (int) ((secs - total) * 30);
        return String.format("%02d:%02d:%02d", mm, ss, ff);
    }
}
