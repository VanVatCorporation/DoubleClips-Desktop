package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.editing.AnimatedProperty.KeyState;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;

/**
 * The keyframe diamond beside a property (the iOS design):
 * <ul>
 * <li>hollow, grey - the property is not animated</li>
 * <li>left half filled - it is animated, but has no keyframe at the playhead</li>
 * <li>fully filled, purple - a keyframe of this property sits at the playhead</li>
 * </ul>
 * Drawn from shapes so it does not depend on an icon font having a half-filled diamond.
 */
public final class KeyDiamond extends StackPane {
    private static final double HALF = 7;   // half of the diamond's width/height
    private static final Color IDLE = Color.web("#888888");

    private final Polygon outline = new Polygon(HALF, 0, HALF * 2, HALF, HALF, HALF * 2, 0, HALF);
    private final Polygon leftHalf = new Polygon(HALF, 0, HALF, HALF * 2, 0, HALF);

    public KeyDiamond() {
        outline.setStrokeWidth(1.5);
        outline.setFill(Color.TRANSPARENT);
        setMinSize(HALF * 2 + 3, HALF * 2 + 3);
        setPrefSize(HALF * 2 + 3, HALF * 2 + 3);
        setMaxSize(HALF * 2 + 3, HALF * 2 + 3);
        getChildren().addAll(outline, leftHalf);
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
