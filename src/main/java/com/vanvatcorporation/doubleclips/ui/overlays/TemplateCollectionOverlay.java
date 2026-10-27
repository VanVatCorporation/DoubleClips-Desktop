package com.vanvatcorporation.doubleclips.ui.overlays;

import com.vanvatcorporation.doubleclips.auth.TemplateFeedSource;
import com.vanvatcorporation.doubleclips.ui.TemplateGridView;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.util.function.Consumer;

/**
 * Profile -> Liked Templates / Saved Templates: the signed-in account's own list, newest first, paged from the server.
 * A card opens in the same preview as the Templates tab; whatever is unliked / unsaved there leaves the list on return.
 */
public class TemplateCollectionOverlay extends StackPane {
    private final Consumer<Void> onClose;
    private boolean closed;

    public TemplateCollectionOverlay(TemplateFeedSource source, Consumer<Void> onClose) {
        this.onClose = onClose;
        getStyleClass().add("preview-overlay");

        final boolean saved = source.kind == TemplateFeedSource.Kind.BOOKMARKED;

        VBox card = new VBox(16);
        card.getStyleClass().add("template-dialog");
        card.setMaxSize(1100, 780);
        card.setPrefSize(1100, 780);
        card.setPadding(new Insets(24));
        StackPane.setMargin(card, new Insets(28));

        Label title = new Label(saved ? "Saved templates" : "Liked templates");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        HBox.setHgrow(title, Priority.ALWAYS);
        title.setMaxWidth(Double.MAX_VALUE);

        Button closeBtn = new Button();
        closeBtn.setGraphic(new FontIcon(MaterialDesignC.CLOSE));
        closeBtn.getStyleClass().add("button-transparent");
        closeBtn.setOnAction(e -> close());

        HBox header = new HBox(12, title, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        // what is no longer liked / saved leaves the list once the preview is closed, without a reload
        TemplateGridView grid = new TemplateGridView(saved ? d -> d.isBookmarked : d -> d.isLiked, "Nothing here yet");
        grid.setOnPreviewClosed(this::requestFocus);
        VBox.setVgrow(grid, Priority.ALWAYS);

        card.getChildren().addAll(header, grid);
        getChildren().add(card);

        grid.start(source);

        setFocusTraversable(true);
        sceneProperty().addListener((o, a, scene) -> { if (scene != null) Platform.runLater(this::requestFocus); });
        setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) { close(); e.consume(); } });
        setOnMouseClicked(e -> { if (e.getTarget() == this) close(); });   // a click on the dim area closes it
    }

    private void close() {
        if (closed) return;
        closed = true;
        onClose.accept(null);
    }
}
