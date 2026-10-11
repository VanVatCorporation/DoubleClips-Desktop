package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.editing.EasingType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

/** The small tappable graph in a keyframe row: the curve, its name and a chevron. Opens {@link EasingPicker}. */
public final class EasingChip extends Button {
    private final EasingCurveGraph graph;
    private final Label name = new Label();

    public EasingChip(EasingType easing) {
        graph = new EasingCurveGraph(easing, 30, 1.6, false, ClipNode.KNOT_ACTIVE);
        StackPane box = new StackPane(graph);
        box.setPadding(new Insets(4));
        box.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 7;");

        name.setStyle("-fx-font-size: 11px;");
        name.setMaxWidth(74);
        name.setMinWidth(46);
        Label chevron = new Label("\u203A");
        chevron.setStyle("-fx-font-size: 14px; -fx-opacity: 0.5;");

        HBox row = new HBox(6, box, name, chevron);
        row.setAlignment(Pos.CENTER_LEFT);
        setGraphic(row);
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        setStyle("-fx-background-color: transparent; -fx-padding: 0; -fx-cursor: hand;");
        setEasing(easing);
    }

    public void setEasing(EasingType easing) {
        graph.setEasing(easing);
        name.setText(EasingCurveGraph.shortName(easing));
    }
}
