package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.editing.AnimatedProperty.KeyState;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.StrokeLineJoin;

/**
 * The keyframe diamond beside a property (the iOS design):
 * <ul>
 * <li>hollow, grey - the property is not animated</li>
 * <li>left half filled - it is animated, but has no keyframe at the playhead</li>
 * <li>fully filled, purple - a keyframe of this property sits at the playhead</li>
 * </ul>
 * Drawn from shapes so it does not depend on an icon font having a half-filled diamond. Both shapes are
 * placed in one coordinate space (a plain Pane never re-centres its children - a StackPane did, and slid the
 * narrower half-shape to the middle of the diamond).
 */
public final class KeyDiamond extends Pane {
    private static final double HALF = 7;                 // half of the diamond's width/height
    private static final double STROKE = 1.5;
    private static final double PAD = STROKE;             // keeps the stroke inside the node, never clipped
    private static final double C = PAD + HALF;           // centre
    private static final double SIZE = 2 * HALF + 2 * PAD;
    private static final Color IDLE = Color.web("#888888");

    private final Polygon outline = new Polygon(C, PAD, C + HALF, C, C, C + HALF, C - HALF, C);
    // The left half of the same diamond: top point, bottom point, left point.
    private final Polygon leftHalf = new Polygon(C, PAD, C, C + HALF, C - HALF, C);

    public KeyDiamond() {
        outline.setStrokeWidth(STROKE);
        outline.setStrokeLineJoin(StrokeLineJoin.MITER);
        outline.setFill(Color.TRANSPARENT);
        leftHalf.setStroke(null);
        setMinSize(SIZE, SIZE);
        setPrefSize(SIZE, SIZE);
        setMaxSize(SIZE, SIZE);
        getChildren().addAll(leftHalf, outline); // the outline on top keeps the edge crisp
        setState(KeyState.NONE);
        setMouseTransparent(true); // the button around it takes the clicks
    }

    public void setState(KeyState state) {
        Color active = ClipNode.KNOT_ACTIVE;
        switch (state) {
            case AT_PLAYHEAD:
                outline.setStroke(active);
                outline.setFill(active);
                leftHalf.setVisible(false);
                break;
            case ANIMATED:
                outline.setStroke(active);
                outline.setFill(Color.TRANSPARENT);
                leftHalf.setFill(active);
                leftHalf.setVisible(true);
                break;
            default:
                outline.setStroke(IDLE);
                outline.setFill(Color.TRANSPARENT);
                leftHalf.setVisible(false);
                break;
        }
    }
}
