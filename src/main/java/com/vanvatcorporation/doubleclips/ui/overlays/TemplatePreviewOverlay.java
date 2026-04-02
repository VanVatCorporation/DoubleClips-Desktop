package com.vanvatcorporation.doubleclips.ui.overlays;

import com.vanvatcorporation.doubleclips.data.TemplateData;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignH;

import java.util.function.Consumer;

public class TemplatePreviewOverlay extends StackPane {

    private MediaPlayer mediaPlayer;
    private MediaView mediaView;
    private final Consumer<Void> onClose;

    public TemplatePreviewOverlay(TemplateData data, Consumer<Void> onClose) {
        this.onClose = onClose;
        getStyleClass().add("preview-overlay");

        // 1. Black Container for Video (Portrait Phone Aspect Ratio)
        VBox container = new VBox();
        container.getStyleClass().add("preview-container");
        container.setMaxSize(420, 750);
        container.setAlignment(Pos.CENTER);

        // 2. Video Player Layer
        StackPane videoWrapper = new StackPane();
        videoWrapper.setStyle("-fx-background-color: black;");
        VBox.setVgrow(videoWrapper, Priority.ALWAYS);

        if (data.getTemplateVideoLink() != null && !data.getTemplateVideoLink().isEmpty()) {
            try {
                Media media = new Media(data.getTemplateVideoLink());
                mediaPlayer = new MediaPlayer(media);
                mediaView = new MediaView(mediaPlayer);
                
                // Binding width to container while preserving ratio
                mediaView.setFitWidth(420);
                mediaView.setPreserveRatio(true);

                videoWrapper.getChildren().add(mediaView);
                mediaPlayer.setAutoPlay(true);
                mediaPlayer.setCycleCount(MediaPlayer.INDEFINITE);
                
                // Play/Pause on click
                videoWrapper.setOnMouseClicked(e -> {
                    if (mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING) mediaPlayer.pause();
                    else mediaPlayer.play();
                });
            } catch (Exception e) {
                Label errorLabel = new Label("Preview Unavailable");
                errorLabel.setStyle("-fx-text-fill: white;");
                videoWrapper.getChildren().add(errorLabel);
            }
        }

        // 3. Overlay Content (Author, Title, Icons)
        VBox overlays = new VBox(20);
        overlays.getStyleClass().add("overlay-controls");
        
        HBox bottomRow = new HBox(10);
        bottomRow.setAlignment(Pos.BOTTOM_LEFT);
        
        VBox infoText = new VBox(5);
        Label authorLabel = new Label("@" + data.getTemplateAuthor());
        authorLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 16px;");
        Label titleLabel = new Label(data.getTemplateTitle());
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px; -fx-opacity: 0.8;");
        infoText.getChildren().addAll(authorLabel, titleLabel);
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        VBox interactionColumn = new VBox(18);
        interactionColumn.setAlignment(Pos.CENTER);
        interactionColumn.getChildren().addAll(
                createIconButton(MaterialDesignH.HEART, String.valueOf(data.getHeartCount())),
                createIconButton(MaterialDesignC.COMMENT, "12"),
                createIconButton(MaterialDesignB.BOOKMARK, String.valueOf(data.getBookmarkCount()))
        );

        bottomRow.getChildren().addAll(infoText, spacer, interactionColumn);

        Button useButton = new Button("Use Template");
        useButton.getStyleClass().addAll("accent", "large");
        useButton.setMaxWidth(Double.MAX_VALUE);
        useButton.setPrefHeight(54);
        useButton.setStyle("-fx-background-color: #007AFF; -fx-background-radius: 14; -fx-font-weight: bold; -fx-font-size: 16px; -fx-text-fill: white;");

        overlays.getChildren().addAll(bottomRow, useButton);

        // 4. Close Button (Top Left)
        Button closeBtn = new Button();
        closeBtn.setGraphic(new FontIcon(MaterialDesignC.CLOSE));
        closeBtn.getStyleClass().add("button-transparent");
        closeBtn.setStyle("-fx-background-color: rgba(0,0,0,0.4); -fx-background-radius: 20; -fx-min-width: 40; -fx-min-height: 40;");
        closeBtn.setCursor(javafx.scene.Cursor.HAND);
        closeBtn.setOnAction(e -> {
            if (mediaPlayer != null) mediaPlayer.stop();
            onClose.accept(null);
        });

        StackPane.setAlignment(closeBtn, Pos.TOP_LEFT);
        StackPane.setMargin(closeBtn, new Insets(30));

        container.getChildren().addAll(videoWrapper, overlays);
        getChildren().addAll(container, closeBtn);
        
        // Background click to close
        this.setOnMouseClicked(e -> {
            if (e.getTarget() == this) {
                if (mediaPlayer != null) mediaPlayer.stop();
                onClose.accept(null);
            }
        });
    }

    private VBox createIconButton(org.kordamp.ikonli.Ikon icon, String count) {
        VBox box = new VBox(4);
        box.setAlignment(Pos.CENTER);
        FontIcon fi = new FontIcon(icon);
        fi.getStyleClass().add("control-icon");
        Label lbl = new Label(count);
        lbl.getStyleClass().add("control-label");
        box.getChildren().addAll(fi, lbl);
        return box;
    }
}
