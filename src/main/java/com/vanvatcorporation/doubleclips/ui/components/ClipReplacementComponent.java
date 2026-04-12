package com.vanvatcorporation.doubleclips.ui.components;

import com.vanvatcorporation.doubleclips.data.ClipReplacementData;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.util.function.Consumer;

public class ClipReplacementComponent extends VBox {

    private final ImageView preview;
    private final Label indexLabel;
    private final ClipReplacementData data;
    private final Consumer<ClipReplacementComponent> onClick;

    public ClipReplacementComponent(int index, ClipReplacementData data, Consumer<ClipReplacementComponent> onClick) {
        this.data = data;
        this.onClick = onClick;

        setSpacing(8);
        setAlignment(Pos.CENTER);
        setPadding(new Insets(10));
        getStyleClass().add("clip-replacement-card");
        setPrefWidth(120);

        StackPane previewContainer = new StackPane();
        previewContainer.setPrefSize(100, 160);
        previewContainer.getStyleClass().add("clip-preview-container");
        previewContainer.setStyle("-fx-background-color: #2A2A2E; -fx-background-radius: 12; -fx-overflow: hidden;");

        preview = new ImageView();
        preview.setFitWidth(100);
        preview.setFitHeight(160);
        preview.setPreserveRatio(true);
        if (data.getClipThumbnail() != null) {
            preview.setImage(data.getClipThumbnail());
        } else {
            // Placeholder icon if no thumbnail
            FontIcon plusIcon = new FontIcon(MaterialDesignP.PLUS);
            plusIcon.setIconSize(32);
            plusIcon.setIconColor(Color.web("#666666"));
            previewContainer.getChildren().add(plusIcon);
        }

        previewContainer.getChildren().add(preview);

        indexLabel = new Label(String.valueOf(index + 1));
        indexLabel.getStyleClass().add("clip-index-label");
        indexLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: -color-fg-default;");

        getChildren().addAll(previewContainer, indexLabel);

        setOnMouseClicked(e -> {
            if (onClick != null) onClick.accept(this);
        });
        
        setCursor(javafx.scene.Cursor.HAND);
    }

    public void updateThumbnail() {
        if (data.getClipThumbnail() != null) {
            preview.setImage(data.getClipThumbnail());
        }
    }

    public ClipReplacementData getData() {
        return data;
    }
}
