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
    private final PropertyContext context;
    private final HBox    thumbnailRow;
    private final ImageView singleImageOverlay;
    private final Label   nameLabel;
    private final Label   timecodeLabel;
    private final Rectangle selBorder;
    
    /**
     * Identifies what the current thumbnails / waveform were generated for (tile count, trim,
     * duration, zoom for waveforms ...). EditorWindow only regenerates when this changes, so a
     * zoom or an unrelated edit no longer re-decodes every clip. Volatile: background thumbnail
     * jobs read it to notice they have become stale.
     */
    private volatile String thumbSignature;
    /** Scale the keyframe knots were last laid out with; their drag maths must use the CURRENT one. */
    private float keyframePps = 100f;
    private double lastTileWidth = -1, lastTileHeight = -1;

    private java.util.function.Consumer<Keyframe> onKeyframeClicked;
    private Runnable onKeyframesModified;
    public interface KeyframeMoveHandler { void onKeyframeMoved(Keyframe kf, float oldTime, float newTime); }
    private KeyframeMoveHandler onKeyframeMoved;
    /** A mask keyframe edited on the timeline (moved or deleted): the clip's masks before and after, for one undo step. */
    public interface MaskKeysHandler { void edited(Clip clip, List<com.vanvatcorporation.doubleclips.data.editing.ClipMask> before,
                                                  List<com.vanvatcorporation.doubleclips.data.editing.ClipMask> after); }
    private MaskKeysHandler onMaskKeysEdited;
    private java.util.function.Consumer<Float> onMaskKeyframeClicked;   // the keyframe's time on the timeline

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

    public ClipNode(Clip clip, PropertyContext context) {
        this.clip = clip;
        this.context = context;

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
        setSelected(selected, selected);
    }

    /**
     * @param showTrimHandles false for the non-primary clips of a multi-selection: they get the border
     *                        but not the trim handles (trimming acts on one clip at a time).
     */
    public void setSelected(boolean selected, boolean showTrimHandles) {
        selBorder.setStroke(selected ? Color.web("#00D4FF") : Color.TRANSPARENT);
        leftHandle.setVisible(selected && showTrimHandles);
        rightHandle.setVisible(selected && showTrimHandles);
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

    /** Tile count implied by the assigned size (same formula as {@link #refreshThumbnails()}). */
    public int computeTileCount() {
        double w = getPrefWidth() > 0 ? getPrefWidth() : getWidth();
        double h = getPrefHeight() > 0 ? getPrefHeight() : getHeight();
        if (w <= 0 || h <= 0) return 0;
        return (int) Math.ceil(w / (h * THUMB_ASPECT)) + 1;
    }

    public String getThumbSignature() { return thumbSignature; }
    public void setThumbSignature(String signature) { this.thumbSignature = signature; }

    /** Number of currently rendered thumbnail tiles. */
    public int getTileCount() {
        if (thumbnailRow.getChildren().isEmpty()) refreshThumbnails();
        return thumbnailRow.getChildren().size();
    }

    public Clip getContainerClip() { return clip; }
    
    public void setOnKeyframeClicked(java.util.function.Consumer<Keyframe> handler) { this.onKeyframeClicked = handler; }
    public void setOnKeyframesModified(Runnable handler) { this.onKeyframesModified = handler; }
    public void setOnKeyframeMoved(KeyframeMoveHandler handler) { this.onKeyframeMoved = handler; }
    public void setOnMaskKeysEdited(MaskKeysHandler handler) { this.onMaskKeysEdited = handler; }
    public void setOnMaskKeyframeClicked(java.util.function.Consumer<Float> handler) { this.onMaskKeyframeClicked = handler; }
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
        keyframePps = pixelsPerSecond;
        // Remove existing knots first
        clearKeyframeKnots();
        buildMaskKnots(pixelsPerSecond);

        if (clip.keyframes == null || clip.keyframes.keyframes == null) return;

        double h = getHeight() > 0 ? getHeight() : getPrefHeight();
        double knotSize = 10.0;

        for (Keyframe kf : clip.keyframes.keyframes) {
            Rectangle knot = new Rectangle(knotSize, knotSize);
            styleKnot(knot, kf);
            knot.setStrokeWidth(1.5);
            knot.setArcWidth(0);
            knot.setArcHeight(0);
            knot.setRotate(45);
            knot.setMouseTransparent(false);
            knot.getStyleClass().add("keyframe-knot");
            // Which properties this keyframe belongs to ("All", or e.g. "Pos X, Scale Y").
            javafx.scene.control.Tooltip.install(knot, new javafx.scene.control.Tooltip(
                    String.format("Keyframe at %.2fs - %s", kf.getLocalTime(), kf.channelSummary())));

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
                double newLocalTime = newCx / keyframePps; // current scale, even after a zoom that only repositioned the knots
                kf.setLocalTime((float) newLocalTime);
                
                e.consume();
            });

            knot.setOnMouseReleased(e -> {
                if (!e.isStillSincePress()) {
                    if (clip.keyframes != null) {
                        if (context != null && context.getVideoSettings() != null) {
                            clip.keyframes.reassignKeyframes(context.getVideoSettings().getFrameRate());
                        }
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

    // ── Mask keyframe knots ──────────────────────────────────────────────────────

    /** Which keyframe of which mask a knot stands for. */
    private static final class MaskKnot {
        final int maskIndex;
        final com.vanvatcorporation.doubleclips.data.editing.ClipMask.MaskKeyframe key;

        MaskKnot(int maskIndex, com.vanvatcorporation.doubleclips.data.editing.ClipMask.MaskKeyframe key) {
            this.maskIndex = maskIndex;
            this.key = key;
        }
    }

    private static final Color MASK_KNOT = Color.web("#FFD23F");

    /**
     * One small amber diamond per mask keyframe, on a lower row than the clip's own (white / cyan) keyframes, so the
     * two kinds never sit on top of each other. Click = playhead there, drag = move it, right-click = delete.
     */
    private void buildMaskKnots(float pixelsPerSecond) {
        if (!clip.hasMasks()) return;
        double h = getHeight() > 0 ? getHeight() : getPrefHeight();
        final double size = 8.0;
        for (int mi = 0; mi < clip.masks.size(); mi++) {
            final int maskIndex = mi;
            com.vanvatcorporation.doubleclips.data.editing.ClipMask mask = clip.masks.get(mi);
            if (mask.keys == null) continue;
            for (com.vanvatcorporation.doubleclips.data.editing.ClipMask.MaskKeyframe key : mask.keys) {
                Rectangle knot = new Rectangle(size, size);
                knot.setRotate(45);
                knot.setStrokeWidth(1.5);
                knot.setArcWidth(0);
                knot.setArcHeight(0);
                knot.getStyleClass().add("keyframe-knot");
                knot.setUserData(new MaskKnot(maskIndex, key));
                styleMaskKnot(knot, key);
                StringBuilder what = new StringBuilder();
                for (String raw : key.values.keySet()) {
                    com.vanvatcorporation.doubleclips.data.editing.ClipMask.Param p =
                            com.vanvatcorporation.doubleclips.data.editing.ClipMask.Param.byRaw(raw);
                    if (what.length() > 0) what.append(", ");
                    what.append(p == null ? raw : p.titleFor(mask.shape));
                }
                javafx.scene.control.Tooltip.install(knot, new javafx.scene.control.Tooltip(
                        String.format("Mask %d keyframe at %.2fs - %s", maskIndex + 1, key.time, what)));
                knot.setLayoutX(key.time * pixelsPerSecond - size / 2.0);
                knot.setLayoutY(Math.max(0, h - 14) - size / 2.0);

                knot.setOnMouseClicked(e -> {
                    if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.isStillSincePress()
                            && onMaskKeyframeClicked != null) {
                        onMaskKeyframeClicked.accept(clip.startTime + key.time);
                        e.consume();
                    }
                });
                final double[] dragStartX = new double[1];
                final double[] originalX = new double[1];
                knot.setOnMousePressed(e -> {
                    dragStartX[0] = e.getSceneX();
                    originalX[0] = knot.getLayoutX();
                    e.consume();
                });
                knot.setOnMouseDragged(e -> {
                    if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
                    // Only the knot moves while dragging; the masks change once, on release (one undo step).
                    double x = originalX[0] + e.getSceneX() - dragStartX[0];
                    knot.setLayoutX(Math.max(-size / 2.0, Math.min(getWidth() - size / 2.0, x)));
                    e.consume();
                });
                knot.setOnMouseReleased(e -> {
                    if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && !e.isStillSincePress()) {
                        float newTime = (float) ((knot.getLayoutX() + size / 2.0) / keyframePps);
                        int fps = context != null && context.getVideoSettings() != null ? context.getVideoSettings().getFrameRate() : 0;
                        editMaskKeys(maskIndex, key, (m, k) -> m.moveKeyframe(k, newTime, clip.duration, fps));
                    }
                    e.consume();
                });
                javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
                javafx.scene.control.MenuItem delete = new javafx.scene.control.MenuItem("Delete mask keyframe");
                delete.setOnAction(e -> editMaskKeys(maskIndex, key, (m, k) -> m.removeKeyframe(k)));
                menu.getItems().add(delete);
                knot.setOnContextMenuRequested(e -> {
                    menu.show(knot, e.getScreenX(), e.getScreenY());
                    e.consume();
                });
                getChildren().add(knot);
            }
        }
    }

    /** Applies an edit of one mask keyframe to a copy of the masks and reports before / after as one undo step. */
    private void editMaskKeys(int maskIndex, com.vanvatcorporation.doubleclips.data.editing.ClipMask.MaskKeyframe key,
                              java.util.function.BiConsumer<com.vanvatcorporation.doubleclips.data.editing.ClipMask,
                                      com.vanvatcorporation.doubleclips.data.editing.ClipMask.MaskKeyframe> edit) {
        List<com.vanvatcorporation.doubleclips.data.editing.ClipMask> before =
                com.vanvatcorporation.doubleclips.data.editing.ClipMask.copyAll(clip.masks);
        List<com.vanvatcorporation.doubleclips.data.editing.ClipMask> after =
                com.vanvatcorporation.doubleclips.data.editing.ClipMask.copyAll(clip.masks);
        com.vanvatcorporation.doubleclips.data.editing.ClipMask mask = after.get(maskIndex);
        int keyIndex = clip.masks.get(maskIndex).keys.indexOf(key);
        if (keyIndex >= 0) edit.accept(mask, mask.keys.get(keyIndex));
        if (java.util.Objects.equals(before, after)) {
            updateKeyframes(keyframePps); // a drag that changed nothing: put the knot back
            return;
        }
        if (onMaskKeysEdited != null) onMaskKeysEdited.edited(clip, before, after);
    }

    /** A mask knot is solid amber while the playhead is on it, an amber outline otherwise. */
    private void styleMaskKnot(Rectangle knot, com.vanvatcorporation.doubleclips.data.editing.ClipMask.MaskKeyframe key) {
        boolean active = context != null && Math.abs(clip.startTime + key.time - context.getCurrentTime())
                <= com.vanvatcorporation.doubleclips.constants.Constants.TRACK_CLIPS_MINIMUM_KEYFRAME_SPACE_SECONDS;
        knot.setFill(active ? MASK_KNOT : Color.web("#1e1e22"));
        knot.setStroke(MASK_KNOT);
    }

    /** The knot colour of a keyframe the playhead is on ("chosen": the properties panel edits it) vs the others. */
    public static final Color KNOT_ACTIVE = Color.web("#4A5FFF");
    private static final Color KNOT_IDLE_STROKE = Color.web("#00D4FF");

    private void styleKnot(Rectangle knot, Keyframe kf) {
        boolean active = context != null && Math.abs(kf.getGlobalTime(clip) - context.getCurrentTime())
                <= com.vanvatcorporation.doubleclips.constants.Constants.TRACK_CLIPS_MINIMUM_KEYFRAME_SPACE_SECONDS;
        knot.setFill(active ? KNOT_ACTIVE : Color.WHITE);
        knot.setStroke(active ? KNOT_ACTIVE : KNOT_IDLE_STROKE);
    }

    /** Recolours the knots for the current playhead (cheap; EditorWindow calls it whenever the time moves). */
    public void refreshKeyframeHighlight() {
        boolean clipKeys = clip.keyframes != null && clip.keyframes.keyframes != null && !clip.keyframes.keyframes.isEmpty();
        if (!clipKeys && !clip.hasMasks()) return;
        for (javafx.scene.Node n : getChildren()) {
            if (n instanceof Rectangle r) {
                if (r.getUserData() instanceof Keyframe kf) styleKnot(r, kf);
                else if (r.getUserData() instanceof MaskKnot mk) styleMaskKnot(r, mk.key);
            }
        }
    }

    /**
     * Zoom-time update: moves the existing knots to the new scale instead of destroying and
     * recreating every knot (and its handlers) for every clip on every zoom step.
     */
    public void relayoutKeyframeKnots(float pixelsPerSecond) {
        keyframePps = pixelsPerSecond;
        double knotSize = 10.0;
        for (javafx.scene.Node n : getChildren()) {
            if (n instanceof Rectangle r && r.getUserData() instanceof Keyframe kf) {
                r.setLayoutX(kf.getLocalTime() * pixelsPerSecond - knotSize / 2.0);
            } else if (n instanceof Rectangle r && r.getUserData() instanceof MaskKnot mk) {
                r.setLayoutX(mk.key.time * pixelsPerSecond - 8.0 / 2.0);
            }
        }
    }

    /** Remove all keyframe diamond knots (Rectangle nodes tagged with a Keyframe). */
    public void clearKeyframeKnots() {
        List<javafx.scene.Node> toRemove = new ArrayList<>();
        for (javafx.scene.Node n : getChildren()) {
            if (n instanceof Rectangle r && (r.getUserData() instanceof Keyframe || r.getUserData() instanceof MaskKnot)) {
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
        // Prefer the size the timeline assigned (pref == min == max) over the laid-out size: it is
        // correct immediately, whereas getWidth() lags a layout pass behind after a zoom.
        double w = getPrefWidth() > 0 ? getPrefWidth() : getWidth();
        double h = getPrefHeight() > 0 ? getPrefHeight() : getHeight();
        if (w <= 0 || h <= 0) return;

        double tw      = h * THUMB_ASPECT;                    // tile pixel width
        int    needed  = (int) Math.ceil(w / tw) + 1;         // +1 for partial right tile
        int    current = thumbnailRow.getChildren().size();
        if (needed == current && tw == lastTileWidth && h == lastTileHeight) return; // nothing changed
        lastTileWidth = tw;
        lastTileHeight = h;

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
