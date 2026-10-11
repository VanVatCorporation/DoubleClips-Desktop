package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.editing.EasingType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The easing grid (the iOS picker): a big live preview of the selected curve with a dot riding it, then 4 columns
 * of square tiles, one per easing, each drawing its own curve. Picking a tile applies it straight away
 * ({@code onPick}); Done (or Esc) just closes the window.
 */
public final class EasingPicker {
    private static final String TILE_IDLE = "-fx-background-color: -color-bg-subtle; -fx-background-radius: 10;"
            + " -fx-border-color: -color-border-default; -fx-border-radius: 10; -fx-border-width: 1;";
    private static final String TILE_SELECTED = "-fx-background-color: rgba(74,95,255,0.18); -fx-background-radius: 10;"
            + " -fx-border-color: #4A5FFF; -fx-border-radius: 10; -fx-border-width: 2.5;";

    private static Stage openStage; // one picker at a time

    private final Consumer<EasingType> onPick;
    private final Map<EasingType, VBox> tiles = new EnumMap<>(EasingType.class);
    private final Map<EasingType, Label> tileLabels = new EnumMap<>(EasingType.class);
    private EasingType selected;
    private final EasingCurveGraph preview;
    private final Label previewName = new Label();
    private final Label holdNote = new Label("Holds the value until the next keyframe, then jumps.");

    private EasingPicker(EasingType current, Consumer<EasingType> onPick) {
        this.selected = current == null ? EasingType.NONE : current;
        this.onPick = onPick;
        this.preview = new EasingCurveGraph(selected, 150, 3, true, null);
    }

    /** Opens the picker next to {@code owner}; {@code onPick} is called each time the user chooses a different easing. */
    public static void show(Window owner, EasingType current, Consumer<EasingType> onPick) {
        if (openStage != null) openStage.close();
        EasingPicker picker = new EasingPicker(current, onPick);

        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("Easing");
        Scene scene = new Scene(picker.buildRoot(stage), 440, 640);
        if (owner != null && owner.getScene() != null) scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.setOnHidden(e -> { if (openStage == stage) openStage = null; });
        openStage = stage;
        stage.show();
    }

    private VBox buildRoot(Stage stage) {
        Label title = new Label("Easing");
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button done = new Button("Done");
        done.setOnAction(e -> stage.close());
        HBox header = new HBox(title, spacer, done);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 16, 10, 16));

        // Big preview
        StackPane previewBox = new StackPane(preview);
        previewBox.setPadding(new Insets(12));
        previewBox.setMaxWidth(Region.USE_PREF_SIZE);
        previewBox.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 12;");
        previewName.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        holdNote.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");
        VBox previewCol = new VBox(6, previewBox, previewName, holdNote);
        previewCol.setAlignment(Pos.CENTER);

        // The grid
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        for (int c = 0; c < 4; c++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(25);
            cc.setHalignment(javafx.geometry.HPos.CENTER);
            grid.getColumnConstraints().add(cc);
        }
        int i = 0;
        for (EasingType e : EasingType.values()) {
            grid.add(buildTile(e), i % 4, i / 4);
            i++;
        }

        VBox content = new VBox(14, previewCol, grid);
        content.setPadding(new Insets(0, 16, 24, 16));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        refreshSelection();
        return new VBox(header, scroll);
    }

    private VBox buildTile(EasingType e) {
        EasingCurveGraph graph = new EasingCurveGraph(e, 72, 2, false, null);
        StackPane box = new StackPane(graph);
        box.setPadding(new Insets(8));
        box.setMaxWidth(Region.USE_PREF_SIZE);
        tileBox.put(e, box);

        Label name = new Label(EasingCurveGraph.shortName(e));
        name.setStyle("-fx-font-size: 10px;");
        tileLabels.put(e, name);

        VBox tile = new VBox(4, box, name);
        tile.setAlignment(Pos.CENTER);
        tile.setStyle("-fx-cursor: hand;");
        tile.setOnMouseClicked(ev -> {
            if (e == selected) return;
            selected = e;
            refreshSelection();
            onPick.accept(e);
        });
        tiles.put(e, tile);
        return tile;
    }

    private final Map<EasingType, StackPane> tileBox = new EnumMap<>(EasingType.class);

    private void refreshSelection() {
        preview.setEasing(selected);
        previewName.setText(EasingCurveGraph.shortName(selected));
        holdNote.setVisible(selected == EasingType.NONE);
        holdNote.setManaged(selected == EasingType.NONE);
        for (EasingType e : EasingType.values()) {
            boolean isSel = e == selected;
            StackPane box = tileBox.get(e);
            if (box != null) box.setStyle(isSel ? TILE_SELECTED : TILE_IDLE);
            Label name = tileLabels.get(e);
            if (name != null) name.setStyle("-fx-font-size: 10px;" + (isSel ? " -fx-font-weight: bold;" : " -fx-opacity: 0.75;"));
            VBox tile = tiles.get(e);
            if (tile != null) {
                // The selected tile's curve is drawn in the accent colour.
                StackPane b = tileBox.get(e);
                if (b != null && b.getChildren().get(0) instanceof EasingCurveGraph g) g.setTint(isSel ? ClipNode.KNOT_ACTIVE : null);
            }
        }
    }
}
