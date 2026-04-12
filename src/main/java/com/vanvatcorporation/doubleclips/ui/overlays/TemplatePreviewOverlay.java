package com.vanvatcorporation.doubleclips.ui.overlays;

import com.vanvatcorporation.doubleclips.data.TemplateData;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.util.function.Consumer;

public class TemplatePreviewOverlay extends StackPane {

    private MediaPlayer mediaPlayer;
    private MediaView mediaView;
    private final Consumer<Void> onClose;
    private final StackPane mediaContainer;
    private final StackPane statusLayer;
    private final ProgressIndicator loadingIndicator;
    private final FontIcon playPauseIcon;
    private Label durationLabel;

    public TemplatePreviewOverlay(TemplateData data, Consumer<Void> onClose) {
        this.onClose = onClose;
        getStyleClass().add("preview-overlay");

        // 1. Main Portrait Card
        StackPane card = new StackPane();
        card.getStyleClass().add("preview-container");
        card.setMaxSize(420, 750);
        
        loadingIndicator = new ProgressIndicator();
        loadingIndicator.setMaxSize(60, 60);
        loadingIndicator.setVisible(true);

        playPauseIcon = new FontIcon(MaterialDesignP.PLAY);
        playPauseIcon.getStyleClass().add("status-icon");
        playPauseIcon.setOpacity(0);

        statusLayer = new StackPane(loadingIndicator, playPauseIcon);
        statusLayer.setMouseTransparent(true);

        // --- LAYER 1: Media Layer (Thumbnail + Video) ---
        mediaContainer = new StackPane();
        mediaContainer.setStyle("-fx-background-color: #121212; -fx-background-radius: 32; -fx-border-radius: 32;");
        
        ImageView placeholder = new ImageView();
        placeholder.setFitWidth(420);
        placeholder.setPreserveRatio(true);
        if (data.getTemplateSnapshotLink() != null) {
            placeholder.setImage(new Image(data.getTemplateSnapshotLink(), true));
        }

        mediaContainer.getChildren().add(placeholder);

        if (data.getTemplateVideoLink() != null && !data.getTemplateVideoLink().isEmpty()) {
            com.vanvatcorporation.doubleclips.helper.VideoCacheManager.getCachedVideoPath(data.getTemplateVideoLink(), cachedUrl -> {
                javafx.application.Platform.runLater(() -> {
                    Media media = new Media(cachedUrl);
                    mediaPlayer = new MediaPlayer(media);
                    mediaView = new MediaView(mediaPlayer);
                    mediaView.setFitWidth(420);
                    mediaView.setPreserveRatio(true);
                    
                    mediaContainer.getChildren().add(mediaView);
                    mediaPlayer.setAutoPlay(true);
                    mediaPlayer.setCycleCount(MediaPlayer.INDEFINITE);
                    
                    mediaPlayer.statusProperty().addListener((obs, old, status) -> {
                        loadingIndicator.setVisible(status == MediaPlayer.Status.STALLED || status == MediaPlayer.Status.UNKNOWN);
                        if (status == MediaPlayer.Status.PLAYING) {
                            placeholder.setVisible(false);
                            showStatusIcon(MaterialDesignP.PLAY);
                        } else if (status == MediaPlayer.Status.PAUSED) {
                            showStatusIcon(MaterialDesignP.PAUSE);
                        }
                        
                        // Update duration once metadata is ready
                        if (status == MediaPlayer.Status.READY && mediaPlayer.getTotalDuration() != null) {
                            // long totalMs = (long) mediaPlayer.getTotalDuration().toMillis();
                            long totalMs = (long) mediaPlayer.getMedia().getDuration().toMillis();
                            durationLabel.setText(formatDuration(totalMs));
                        }
                    });
                });
            });
        }

        // --- LAYER 2: Gradient for Readability ---
        Region gradient = new Region();
        gradient.getStyleClass().add("overlay-gradient");
        gradient.setMaxHeight(300);
        StackPane.setAlignment(gradient, Pos.BOTTOM_CENTER);

        // --- LAYER 3: UI Controls Layer ---
        VBox uiLayer = new VBox();
        uiLayer.setPadding(new Insets(20));
        uiLayer.setAlignment(Pos.BOTTOM_CENTER);
        uiLayer.setSpacing(20);

        // Right Action Column (Floating on the right side)
        HBox contentRow = new HBox();
        contentRow.setAlignment(Pos.BOTTOM_RIGHT);
        
        VBox leftInfo = new VBox(10);
        leftInfo.setAlignment(Pos.BOTTOM_LEFT);
        HBox.setHgrow(leftInfo, Priority.ALWAYS);

        Label authorLabel = new Label("@" + data.getTemplateAuthor());
        authorLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 18px;");
        
        HBox metaRow = new HBox(15);
        metaRow.setAlignment(Pos.CENTER_LEFT);
        
        HBox clipMeta = createMetaLabel(MaterialDesignM.MOVIE, data.getTemplateClipCount() + " clips");
        HBox durationMeta = createMetaLabel(MaterialDesignC.CLOCK_OUTLINE, formatDuration(data.getTemplateDuration()));
        durationLabel = (Label) durationMeta.getChildren().get(1); // The second child is the label

        metaRow.getChildren().addAll(clipMeta, durationMeta);

        Label titleLabel = new Label(data.getTemplateTitle());
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px; -fx-opacity: 0.9;");
        titleLabel.setWrapText(true);
        titleLabel.setMaxWidth(280);

        leftInfo.getChildren().addAll(authorLabel, metaRow, titleLabel);

        VBox rightActions = new VBox(22);
        rightActions.setAlignment(Pos.CENTER);
        rightActions.getChildren().addAll(
            createInteractionBtn(MaterialDesignH.HEART, String.valueOf(data.getHeartCount())),
            createInteractionBtn(MaterialDesignC.COMMENT, String.valueOf(data.getComments().size())),
            createInteractionBtn(MaterialDesignB.BOOKMARK, String.valueOf(data.getBookmarkCount())),
            createInteractionBtn(MaterialDesignM.MENU, "")
        );

        contentRow.getChildren().addAll(leftInfo, rightActions);

        useBtn.setOnAction(e -> {
            close();
            com.vanvatcorporation.doubleclips.ui.TemplateExportWindow.showInstance(data);
        });

        uiLayer.getChildren().addAll(contentRow, useBtn);

        card.getChildren().addAll(mediaContainer, gradient, uiLayer, statusLayer);

        // --- Close Button (Top Left) ---
        Button closeBtn = new Button();
        closeBtn.setGraphic(new FontIcon(MaterialDesignC.CLOSE));
        closeBtn.getStyleClass().add("button-transparent");
        closeBtn.setStyle("-fx-background-color: rgba(0,0,0,0.4); -fx-background-radius: 20; -fx-min-width: 40; -fx-min-height: 40; -fx-text-fill: white;");
        closeBtn.setCursor(javafx.scene.Cursor.HAND);
        closeBtn.setOnAction(e -> close());

        StackPane.setAlignment(closeBtn, Pos.TOP_LEFT);
        StackPane.setMargin(closeBtn, new Insets(30));

        getChildren().addAll(card, closeBtn);

        // Interactions Logic
        card.setOnMouseClicked(e -> {
            if (mediaPlayer != null) {
                if (mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING) mediaPlayer.pause();
                else mediaPlayer.play();
            }
        });

        this.setOnMouseClicked(e -> {
            if (e.getTarget() == this) close();
        });
    }

    private void showStatusIcon(org.kordamp.ikonli.Ikon icon) {
        playPauseIcon.setIconCode(icon);
        javafx.animation.FadeTransition ft = new javafx.animation.FadeTransition(javafx.util.Duration.millis(300), playPauseIcon);
        ft.setFromValue(0);
        ft.setToValue(1);
        ft.setAutoReverse(true);
        ft.setCycleCount(2);
        ft.play();
    }

    private HBox createMetaLabel(org.kordamp.ikonli.Ikon icon, String text) {
        HBox box = new HBox(5);
        box.setAlignment(Pos.CENTER_LEFT);
        FontIcon fi = new FontIcon(icon);
        fi.setStyle("-fx-icon-color: white; -fx-icon-size: 14px;");
        Label lbl = new Label(text);
        lbl.setStyle("-fx-text-fill: white; -fx-font-size: 13px;");
        box.getChildren().addAll(fi, lbl);
        return box;
    }

    private VBox createInteractionBtn(org.kordamp.ikonli.Ikon icon, String count) {
        VBox box = new VBox(4);
        box.setAlignment(Pos.CENTER);
        
        Button btn = new Button();
        btn.getStyleClass().add("interaction-button");
        FontIcon fi = new FontIcon(icon);
        fi.getStyleClass().add("interaction-icon");
        btn.setGraphic(fi);
        
        if (icon == MaterialDesignC.COMMENT) {
            btn.setOnAction(e -> showCommentsPlaceholder());
        }

        Label lbl = new Label(count);
        lbl.getStyleClass().add("interaction-label");
        
        box.getChildren().addAll(btn, lbl);
        return box;
    }

    private void showCommentsPlaceholder() {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
        alert.setTitle("Comments");
        alert.setHeaderText("Feature Coming Soon");
        alert.setContentText("The comment section is currently being ported from Android. Stay tuned!");
        alert.show();
    }

    private String formatDuration(long ms) {
        long sec = (ms / 1000) % 60;
        long min = (ms / (1000 * 60)) % 60;
        return String.format("%02d:%02d", min, sec);
    }

    private void close() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.dispose();
        }
        onClose.accept(null);
    }
}
