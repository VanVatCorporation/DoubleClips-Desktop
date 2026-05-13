package com.vanvatcorporation.doubleclips.ui.overlays;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.io.File;
import java.util.List;

public class PostTemplateOverlay extends StackPane {

    private final StackPane cardContainer;
    private VBox step1, step2, step3, step4;

    private final TextField titleInput;
    private final TextArea descriptionInput;
    private final ProgressBar progressBar;
    private final Label progressLabel;
    private final Label successTemplateIdText;

    private MediaPlayer mediaPlayer;
    private MediaView mediaView;
    
    private boolean isProcessing = false;

    private final String ffmpegCommand;
    private final int totalClip;
    private final List<String> videoFilePaths;
    private final List<String> previewFilePaths;
    private final String defaultTitle;

    public PostTemplateOverlay(String ffmpegCommand, int totalClip, List<String> videoFilePaths, List<String> previewFilePaths, String defaultTitle) {
        this.ffmpegCommand = ffmpegCommand;
        this.totalClip = totalClip;
        this.videoFilePaths = videoFilePaths;
        this.previewFilePaths = previewFilePaths;
        this.defaultTitle = defaultTitle;

        getStyleClass().add("modal-overlay");

        VBox dialogCard = new VBox();
        dialogCard.getStyleClass().add("creation-dialog");
        dialogCard.setMaxSize(480, 600);
        dialogCard.setClip(new javafx.scene.shape.Rectangle(480, 600));

        cardContainer = new StackPane();
        VBox.setVgrow(cardContainer, Priority.ALWAYS);

        // --- STEP 1: Video Preview ---
        step1 = new VBox(16);
        step1.setPadding(new Insets(24));
        step1.setAlignment(Pos.TOP_LEFT);

        Label step1Title = new Label("Preview Template");
        step1Title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        StackPane mediaContainer = new StackPane();
        mediaContainer.setMaxSize(432, 450);
        mediaContainer.setStyle("-fx-background-color: #000000; -fx-background-radius: 8;");
        
        setupPreview(mediaContainer);

        Button nextBtn = new Button("Next");
        nextBtn.getStyleClass().addAll("button-primary", "button-large");
        nextBtn.setMaxWidth(Double.MAX_VALUE);
        nextBtn.setOnAction(e -> {
            if (mediaPlayer != null) mediaPlayer.pause();
            goToStep(step1, step2);
        });

        Button cancelBtn1 = new Button("Cancel");
        cancelBtn1.getStyleClass().add("button-transparent");
        cancelBtn1.setMaxWidth(Double.MAX_VALUE);
        cancelBtn1.setOnAction(e -> close());

        step1.getChildren().addAll(step1Title, mediaContainer, nextBtn, cancelBtn1);

        // --- STEP 2: Project Form ---
        step2 = new VBox(16);
        step2.setTranslateX(480);
        step2.setPadding(new Insets(24));
        step2.setAlignment(Pos.TOP_LEFT);

        Label step2Title = new Label("Edit Details");
        step2Title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        titleInput = new TextField();
        titleInput.setPromptText("Template title...");
        titleInput.setText(defaultTitle != null ? defaultTitle : "");
        titleInput.setPrefHeight(45);
        titleInput.setStyle("-fx-background-radius: 8;");

        descriptionInput = new TextArea();
        descriptionInput.setPromptText("Add description...");
        descriptionInput.setPrefHeight(100);
        descriptionInput.setWrapText(true);
        descriptionInput.setStyle("-fx-background-radius: 8;");

        Button uploadBtn = new Button("Upload template");
        uploadBtn.getStyleClass().addAll("button-primary", "button-large");
        uploadBtn.setMaxWidth(Double.MAX_VALUE);
        uploadBtn.setOnAction(e -> handleUpload());

        Button backBtn2 = new Button("Back");
        backBtn2.getStyleClass().add("button-transparent");
        backBtn2.setMaxWidth(Double.MAX_VALUE);
        backBtn2.setOnAction(e -> {
            goToStep(step2, step1);
            if (mediaPlayer != null) mediaPlayer.play();
        });

        step2.getChildren().addAll(step2Title, titleInput, descriptionInput, uploadBtn, backBtn2);

        // --- STEP 3: Progress View ---
        step3 = new VBox(24);
        step3.setTranslateX(480);
        step3.setPadding(new Insets(32));
        step3.setAlignment(Pos.CENTER);

        Label step3Title = new Label("Uploading Template...");
        step3Title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(20);

        progressLabel = new Label("Uploading... 0%");
        progressLabel.getStyleClass().add("text-muted");

        step3.getChildren().addAll(step3Title, progressBar, progressLabel);

        // --- STEP 4: Success Flow ---
        step4 = new VBox(20);
        step4.setTranslateX(480);
        step4.setPadding(new Insets(32));
        step4.setAlignment(Pos.CENTER);

        FontIcon successIcon = new FontIcon(MaterialDesignC.CHECK_CIRCLE_OUTLINE);
        successIcon.setIconSize(80);
        successIcon.setIconColor(javafx.scene.paint.Color.web("#34C759"));

        Label step4Title = new Label("Upload Completed!");
        step4Title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        successTemplateIdText = new Label("Template ID: ...");
        successTemplateIdText.setAlignment(Pos.CENTER);
        successTemplateIdText.getStyleClass().add("text-muted");

        Button doneBtn = new Button("Done");
        doneBtn.getStyleClass().addAll("button-primary", "button-large");
        doneBtn.setMaxWidth(200);
        doneBtn.setOnAction(e -> close());

        step4.getChildren().addAll(successIcon, step4Title, successTemplateIdText, doneBtn);

        // Add all to container
        cardContainer.getChildren().addAll(step1, step2, step3, step4);
        dialogCard.getChildren().add(cardContainer);
        getChildren().add(dialogCard);

        this.setOnMouseClicked(e -> {
            if (e.getTarget() == this && !isProcessing) close();
        });
    }

    private void setupPreview(StackPane mediaContainer) {
        if (previewFilePaths != null && !previewFilePaths.isEmpty()) {
            for (String path : previewFilePaths) {
                if (path.endsWith(".mp4")) {
                    File file = new File(path);
                    if (file.exists()) {
                        Media media = new Media(file.toURI().toString());
                        mediaPlayer = new MediaPlayer(media);
                        mediaView = new MediaView(mediaPlayer);
                        mediaView.setFitWidth(432);
                        mediaView.setFitHeight(450);
                        mediaView.setPreserveRatio(true);

                        mediaContainer.getChildren().add(mediaView);
                        mediaPlayer.setAutoPlay(true);
                        mediaPlayer.setCycleCount(MediaPlayer.INDEFINITE);
                        break;
                    }
                } else if (path.endsWith(".png")) {
                    // Fallback to Image
                    File file = new File(path);
                    if (file.exists()) {
                        ImageView imageView = new ImageView(new Image(file.toURI().toString()));
                        imageView.setFitWidth(432);
                        imageView.setFitHeight(450);
                        imageView.setPreserveRatio(true);
                        mediaContainer.getChildren().add(imageView);
                    }
                }
            }
        }
    }

    private void goToStep(VBox from, VBox to) {
        double targetX = (cardContainer.getChildren().indexOf(to) > cardContainer.getChildren().indexOf(from)) ? -480 : 480;
        animateSwipe(from, to, targetX, 0);
    }

    private void animateSwipe(VBox out, VBox in, double outTargetX, double inTargetX) {
        TranslateTransition ttOut = new TranslateTransition(Duration.millis(250), out);
        ttOut.setToX(outTargetX);
        ttOut.setInterpolator(Interpolator.EASE_BOTH);
        TranslateTransition ttIn = new TranslateTransition(Duration.millis(250), in);
        ttIn.setToX(inTargetX);
        ttIn.setInterpolator(Interpolator.EASE_BOTH);
        ttOut.play();
        ttIn.play();
    }

    private void handleUpload() {
        setProcessing(true);
        goToStep(step2, step3);

        String title = titleInput.getText().trim();
        String desc = descriptionInput.getText().trim();
        if (title.isEmpty()) title = defaultTitle;
        if (desc.isEmpty()) desc = defaultTitle;

        uploadTemplateNecessityItems(title, desc);
    }

    // Dummy Upload Function (User requested to leave this for later connection)
    private void uploadTemplateNecessityItems(String title, String description) {
        new Thread(() -> {
            try {
                // Simulate multipart upload process
                for (int i = 0; i <= 100; i += 10) {
                    Thread.sleep(200);
                    final int progress = i;
                    Platform.runLater(() -> {
                        progressBar.setProgress(progress / 100.0);
                        progressLabel.setText("Uploading... " + progress + "%");
                    });
                }
                
                // Simulate success payload
                Platform.runLater(() -> {
                    setProcessing(false);
                    successTemplateIdText.setText("Template ID: 999-DUMMY-ID");
                    goToStep(step3, step4);
                });
            } catch (InterruptedException e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    setProcessing(false);
                    progressLabel.setText("Failed to upload template.");
                    progressLabel.setStyle("-fx-text-fill: red;");
                });
            }
        }).start();
    }

    private void setProcessing(boolean processing) {
        this.isProcessing = processing;
        DoubleClipsDesktop.setGlobalProcessing(processing);
    }

    private void close() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.dispose();
        }
        DoubleClipsDesktop.getInstance().hideOverlay(this);
    }
}
