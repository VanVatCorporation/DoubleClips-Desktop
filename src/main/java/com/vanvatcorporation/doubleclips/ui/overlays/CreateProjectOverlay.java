package com.vanvatcorporation.doubleclips.ui.overlays;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.data.ProjectRepository;
import com.vanvatcorporation.doubleclips.helper.CompressionHelper;
import com.vanvatcorporation.doubleclips.helper.FileHelper;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;

import java.io.File;
import java.io.IOException;

public class CreateProjectOverlay extends StackPane {

    private final StackPane cardContainer;
    private final VBox step1, step2, step3, step4;
    private final TextField titleInput;
    
    // Step 3 components
    private final ProgressBar progressBar;
    private final Label progressLabel;
    
    // State management
    private boolean isProcessing = false;
    private File lastExtractedFolder = null;

    public CreateProjectOverlay() {
        getStyleClass().add("modal-overlay");
        
        VBox dialogCard = new VBox();
        dialogCard.getStyleClass().add("creation-dialog");
        dialogCard.setMaxSize(480, 450);
        dialogCard.setClip(new javafx.scene.shape.Rectangle(480, 450));

        cardContainer = new StackPane();
        VBox.setVgrow(cardContainer, Priority.ALWAYS);

        // --- STEP 1: Option Picker ---
        step1 = new VBox(24);
        step1.setPadding(new Insets(32));
        step1.setAlignment(Pos.TOP_LEFT);

        Label step1Title = new Label("Create New Project");
        step1Title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");

        HBox options = new HBox(16);
        options.setAlignment(Pos.CENTER);
        
        VBox newProjectCard = createOptionCard(MaterialDesignP.PLUS, "New Project");
        VBox importProjectCard = createOptionCard(MaterialDesignF.FOLDER_UPLOAD, "Import Project");

        HBox.setHgrow(newProjectCard, Priority.ALWAYS);
        HBox.setHgrow(importProjectCard, Priority.ALWAYS);
        options.getChildren().addAll(newProjectCard, importProjectCard);
        step1.getChildren().addAll(step1Title, options);

        // --- STEP 2: Project Form ---
        step2 = new VBox(12);
        step2.setTranslateX(480);
        step2.setPadding(new Insets(32));
        step2.setAlignment(Pos.TOP_LEFT);

        Label step2Title = new Label("New Project");
        step2Title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        Label step2Sub = new Label("Give your project a name to get started.");
        step2Sub.getStyleClass().add("text-muted");

        titleInput = new TextField();
        titleInput.setPromptText("Project title...");
        titleInput.setPrefHeight(50);
        titleInput.setStyle("-fx-background-radius: 12;");

        Button createBtn = new Button("Create Project");
        createBtn.getStyleClass().addAll("button-primary", "button-large");
        createBtn.setMaxWidth(Double.MAX_VALUE);
        createBtn.setPrefHeight(50);
        createBtn.setOnAction(e -> handleCreate());

        Button backBtn = new Button("Back");
        backBtn.getStyleClass().add("button-transparent");
        backBtn.setMaxWidth(Double.MAX_VALUE);
        backBtn.setOnAction(e -> goToStep(step2, step1));

        step2.getChildren().addAll(step2Title, step2Sub, titleInput, createBtn, backBtn);

        // --- STEP 3: Progress View ---
        step3 = new VBox(24);
        step3.setTranslateX(480);
        step3.setPadding(new Insets(32));
        step3.setAlignment(Pos.CENTER);

        Label step3Title = new Label("Extracting Project...");
        step3Title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(20);

        progressLabel = new Label("Preparing...");
        progressLabel.getStyleClass().add("text-muted");

        step3.getChildren().addAll(step3Title, progressBar, progressLabel);

        // --- STEP 4: Recovery Flow ---
        step4 = new VBox(20);
        step4.setTranslateX(480);
        step4.setPadding(new Insets(32));
        step4.setAlignment(Pos.CENTER);

        FontIcon warningIcon = new FontIcon(MaterialDesignP.PROGRESS_ALERT);
        warningIcon.setIconSize(60);
        warningIcon.setIconColor(javafx.scene.paint.Color.web("#FF9500"));

        Label step4Title = new Label("Invalid Project File");
        step4Title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        Label step4Sub = new Label("This ZIP doesn't seem to be a DoubleClips project.");
        step4Sub.setWrapText(true);
        step4Sub.setAlignment(Pos.CENTER);
        step4Sub.getStyleClass().add("text-muted");

        Button recoverBtn = new Button("Re-initialize as New Project");
        recoverBtn.getStyleClass().addAll("button-primary", "button-large");
        recoverBtn.setMaxWidth(Double.MAX_VALUE);
        recoverBtn.setOnAction(e -> handleRecover());

        Button discardBtn = new Button("Discard & Delete");
        discardBtn.getStyleClass().add("button-transparent");
        discardBtn.setMaxWidth(Double.MAX_VALUE);
        discardBtn.setOnAction(e -> handleDiscard());

        step4.getChildren().addAll(warningIcon, step4Title, step4Sub, recoverBtn, discardBtn);

        // --- Event Handlers (after all steps are initialized) ---
        newProjectCard.setOnMouseClicked(e -> { if (!isProcessing) goToStep(step1, step2); });
        importProjectCard.setOnMouseClicked(e -> { if (!isProcessing) handleImport(); });

        cardContainer.getChildren().addAll(step1, step2, step3, step4);
        dialogCard.getChildren().add(cardContainer);
        getChildren().add(dialogCard);

        this.setOnMouseClicked(e -> {
            if (e.getTarget() == this && !isProcessing) close();
        });
    }

    private VBox createOptionCard(org.kordamp.ikonli.Ikon icon, String title) {
        VBox card = new VBox(12);
        card.getStyleClass().add("option-card");
        FontIcon fi = new FontIcon(icon);
        fi.getStyleClass().add("option-card-icon");
        Label lbl = new Label(title);
        lbl.getStyleClass().add("option-card-title");
        card.getChildren().addAll(fi, lbl);
        return card;
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

    private void handleCreate() {
        String name = titleInput.getText().trim();
        if (!name.isEmpty()) {
            ProjectRepository.getInstance().createNewProject(name);
            close();
        }
    }

    private void handleImport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Project ZIP");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Project ZIP", "*.zip"));
        File file = chooser.showOpenDialog(getScene().getWindow());
        
        if (file != null) {
            startExtraction(file);
        }
    }

    private void startExtraction(File zipFile) {
        setProcessing(true);
        goToStep(step1, step3);

        File projectsDir = Constants.getProjectsDirectory();
        final java.util.Set<String> folderNamesBefore = new java.util.HashSet<>();
        File[] exitingFolders = projectsDir.listFiles(File::isDirectory);
        if (exitingFolders != null) {
            for (File f : exitingFolders) folderNamesBefore.add(f.getName());
        }

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                CompressionHelper.unzipFolder(zipFile, projectsDir, new CompressionHelper.UnzipProgressListener() {
                    @Override
                    public void onProgress(long bytesExtracted, long totalBytes, String name) {
                        Platform.runLater(() -> {
                            double progress = (double) bytesExtracted / totalBytes;
                            progressBar.setProgress(progress);
                            progressLabel.setText(String.format("Extracting: %s (%.0f%%)", name, progress * 100));
                        });
                    }

                    @Override
                    public void onCompleted() {}

                    @Override
                    public void onError(Exception e) {
                        e.printStackTrace();
                    }
                });
                return true;
            }
        };

        task.setOnSucceeded(e -> {
            setProcessing(false);
            ProjectRepository.getInstance().refreshProjects();
            
            // Find the new folder to check for properties
            File[] foldersAfter = projectsDir.listFiles(File::isDirectory);
            File newFolder = null;
            if (foldersAfter != null) {
                for (File f : foldersAfter) {
                    if (!folderNamesBefore.contains(f.getName())) {
                        newFolder = f;
                        break;
                    }
                }
            }

            if (newFolder != null) {
                lastExtractedFolder = newFolder;
                File props = new File(newFolder, Constants.DEFAULT_PROJECT_PROPERTIES_FILENAME);
                if (props.exists()) {
                    close();
                } else {
                    goToStep(step3, step4);
                }
            } else {
                // If no folder was created (e.g. all files at root), or something went wrong
                close();
            }
        });

        task.setOnFailed(e -> {
            setProcessing(false);
            progressLabel.setText("Failed to extract project.");
            progressLabel.setStyle("-fx-text-fill: -color-danger-fg;");
        });

        new Thread(task).start();
    }

    private void handleRecover() {
        if (lastExtractedFolder != null) {
            ProjectRepository.getInstance().recoverLegacyProject(lastExtractedFolder);
            close();
        }
    }

    private void handleDiscard() {
        if (lastExtractedFolder != null) {
            try {
                FileHelper.deleteDirectory(lastExtractedFolder.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
            close();
        }
    }

    private void setProcessing(boolean processing) {
        this.isProcessing = processing;
        DoubleClipsDesktop.setGlobalProcessing(processing);
    }

    private void close() {
        DoubleClipsDesktop.getInstance().hideOverlay(this);
    }
}
