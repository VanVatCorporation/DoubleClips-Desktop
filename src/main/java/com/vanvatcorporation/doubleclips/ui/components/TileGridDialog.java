package com.vanvatcorporation.doubleclips.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A picker made of square tiles - a picture, a title, "@author", and a small badge for each render engine the
 * item works with. Used for text styles; effects and animations can use it with their own pictures (a GIF
 * is just another {@link Image}). The dialog only shows things and reports clicks; what picking does is the
 * caller's.
 */
public final class TileGridDialog {

    public static final String ENGINE_FFMPEG = "FFMPEG", ENGINE_OPENGL = "OPENGL";

    public static final int TILE_WIDTH = 120, PICTURE_HEIGHT = 84;

    /** One thing to pick. {@code thumbnail} is called when the tile is first shown, on the FX thread. */
    public static final class Tile {
        public final String id;
        public final String title;
        /** "@name", or null / empty for none. */
        public final String author;
        /** Which of {@link #ENGINE_FFMPEG} / {@link #ENGINE_OPENGL} it renders on; both are shown when both are present. */
        public final Set<String> engines;
        public final Supplier<Image> thumbnail;
        /** The user's own: shows a delete button. */
        public final boolean deletable;
        /**
         * Optional: the frames of a looping preview, made off the FX thread after the dialog is up (the tile shows
         * {@link #thumbnail} until they are ready). Null = a still tile.
         */
        public final Supplier<Image[]> animation;

        public Tile(String id, String title, String author, Set<String> engines, Supplier<Image> thumbnail, boolean deletable) {
            this(id, title, author, engines, thumbnail, deletable, null);
        }

        public Tile(String id, String title, String author, Set<String> engines, Supplier<Image> thumbnail, boolean deletable,
                    Supplier<Image[]> animation) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.engines = engines;
            this.thumbnail = thumbnail;
            this.deletable = deletable;
            this.animation = animation;
        }
    }

    /** A titled group of tiles. */
    public static final class Section {
        public final String title;
        public final List<Tile> tiles;

        public Section(String title, List<Tile> tiles) {
            this.title = title;
            this.tiles = tiles;
        }
    }

    public interface Listener {
        /** The tile was clicked. The dialog closes. */
        void onPick(Tile tile);

        /** The tile's delete button was pressed; return true if it was deleted (its tile then disappears). */
        boolean onDelete(Tile tile);
    }

    private TileGridDialog() {}

    /**
     * Shows the dialog. {@code footerText} / {@code footerAction} add one button along the bottom (null for none).
     * {@code selectedId} gets a highlighted frame.
     */
    public static Stage show(Window owner, String heading, List<Section> sections, String selectedId, Listener listener,
                             String footerText, Runnable footerAction) {
        Stage stage = new Stage();
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        }
        stage.setTitle(heading);

        TilePreviewAnimator animator = new TilePreviewAnimator(12);
        stage.setOnHidden(e -> animator.stop());

        VBox content = new VBox(10);
        content.setPadding(new Insets(12));
        for (Section section : sections) {
            if (section.tiles.isEmpty()) continue;
            Label title = new Label(section.title);
            title.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
            FlowPane flow = new FlowPane(10, 10);
            for (Tile tile : section.tiles) {
                flow.getChildren().add(buildTile(tile, tile.id != null && tile.id.equals(selectedId), stage, flow, listener, animator));
            }
            content.getChildren().addAll(title, flow);
        }

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        BorderPane root = new BorderPane(scroll);
        if (footerText != null && footerAction != null) {
            Button footer = new Button(footerText);
            footer.setOnAction(e -> {
                stage.close();
                footerAction.run();
            });
            HBox bar = new HBox(footer);
            bar.setPadding(new Insets(8, 12, 8, 12));
            bar.setAlignment(Pos.CENTER_LEFT);
            root.setBottom(bar);
        }

        Scene scene = new Scene(root, 580, 520);
        if (owner != null && owner.getScene() != null) scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        stage.setScene(scene);
        stage.show();
        return stage;
    }

    private static Node buildTile(Tile tile, boolean selected, Stage stage, FlowPane flow, Listener listener, TilePreviewAnimator animator) {
        StackPane picture = new StackPane();
        picture.setMinSize(TILE_WIDTH, PICTURE_HEIGHT);
        picture.setMaxSize(TILE_WIDTH, PICTURE_HEIGHT);
        picture.setStyle("-fx-background-color: #262626; -fx-background-radius: 6;");
        Image image = tile.thumbnail != null ? tile.thumbnail.get() : null;
        if (image != null) {
            ImageView view = new ImageView(image);
            view.setPreserveRatio(true);
            view.setFitWidth(TILE_WIDTH);
            view.setFitHeight(PICTURE_HEIGHT);
            picture.getChildren().add(view);
            if (tile.animation != null) animator.add(view, tile.animation);
        }

        VBox box = new VBox(3);
        box.setPrefWidth(TILE_WIDTH + 8);
        box.setPadding(new Insets(4));
        box.setStyle(selected
                ? "-fx-border-color: #4C9AFF; -fx-border-width: 2; -fx-border-radius: 8; -fx-cursor: hand;"
                : "-fx-border-color: transparent; -fx-border-width: 2; -fx-border-radius: 8; -fx-cursor: hand;");

        Label title = new Label(tile.title == null ? "" : tile.title);
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        title.setMaxWidth(TILE_WIDTH);
        title.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        HBox footer = new HBox(4);
        footer.setAlignment(Pos.CENTER_LEFT);
        VBox names = new VBox(1, title);
        if (tile.author != null && !tile.author.trim().isEmpty()) {
            String a = tile.author.trim();
            Label author = new Label(a.startsWith("@") ? a : "@" + a);
            author.getStyleClass().add("text-muted");
            author.setStyle("-fx-font-size: 10px;");
            names.getChildren().add(author);
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        footer.getChildren().addAll(names, spacer);
        if (tile.engines != null) {
            if (tile.engines.contains(ENGINE_FFMPEG)) footer.getChildren().add(badge("FFmpeg", "#2E6BD8", "Renders with FFmpeg"));
            if (tile.engines.contains(ENGINE_OPENGL)) footer.getChildren().add(badge("OpenGL", "#2E9B5B", "Renders with OpenGL"));
        }
        box.getChildren().addAll(picture, footer);

        if (tile.deletable) {
            Button delete = new Button("\u00D7");
            delete.setStyle("-fx-background-color: #000000AA; -fx-text-fill: white; -fx-background-radius: 10; -fx-padding: 0 6 1 6; -fx-font-size: 12px;");
            delete.setTooltip(new Tooltip("Delete this style"));
            StackPane.setAlignment(delete, Pos.TOP_RIGHT);
            StackPane.setMargin(delete, new Insets(3));
            // A click on the button must not also count as picking the tile underneath it.
            delete.setOnMouseClicked(javafx.scene.input.MouseEvent::consume);
            delete.setOnAction(e -> {
                if (listener.onDelete(tile)) flow.getChildren().remove(box);
            });
            picture.getChildren().add(delete);
        }

        box.setOnMouseClicked(e -> {
            if (e.isConsumed()) return;
            stage.close();
            listener.onPick(tile);
        });
        return box;
    }

    private static Label badge(String text, String color, String tooltip) {
        Label l = new Label(text);
        l.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; -fx-font-size: 9px; -fx-background-radius: 8; -fx-padding: 1 5 1 5;");
        l.setTooltip(new Tooltip(tooltip));
        return l;
    }
}
