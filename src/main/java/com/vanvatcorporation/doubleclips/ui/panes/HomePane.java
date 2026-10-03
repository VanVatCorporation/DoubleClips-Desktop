package com.vanvatcorporation.doubleclips.ui.panes;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.FFmpegEdit;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.ProjectRepository;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;
import com.vanvatcorporation.doubleclips.helper.CompressionHelper;
import com.vanvatcorporation.doubleclips.helper.DateHelper;
import com.vanvatcorporation.doubleclips.helper.FileHelper;
import com.vanvatcorporation.doubleclips.helper.ProjectShare;
import com.vanvatcorporation.doubleclips.helper.TaskbarHelper;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import java.io.File;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignU;

import java.util.Optional;

public class HomePane extends VBox {

    private final GridPane projectGrid;
    private final VBox welcomePane;
    private final ScrollPane scrollPane;

    public HomePane() {
        setSpacing(16);
        setPadding(new Insets(32));
        getStyleClass().add("content-pane");

        // 1. Header
        Label titleLabel = new Label("Recent Projects");
        titleLabel.setStyle("-fx-font-size: 26px; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button sortButton = new Button();
        sortButton.setGraphic(new FontIcon(MaterialDesignS.SORT_VARIANT));
        sortButton.getStyleClass().addAll("button-transparent");

        HBox header = new HBox(titleLabel, spacer, sortButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setSpacing(10);
        header.setPadding(new Insets(0, 0, 8, 0));

        Region divider = new Region();
        divider.setStyle("-fx-background-color: -color-border-subtle;");
        divider.setPrefHeight(1);
        divider.setMaxWidth(Double.MAX_VALUE);

        // 2. Content Stack (List or Welcome)
        projectGrid = new GridPane();
        projectGrid.setHgap(20);
        projectGrid.setVgap(16);
        projectGrid.setPadding(new Insets(16, 0, 80, 0));

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        projectGrid.getColumnConstraints().addAll(col1, col2);

        scrollPane = new ScrollPane(projectGrid);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        welcomePane = createWelcomePane();
        VBox.setVgrow(welcomePane, Priority.ALWAYS);

        StackPane contentStack = new StackPane(scrollPane, welcomePane);
        VBox.setVgrow(contentStack, Priority.ALWAYS);

        getChildren().addAll(header, divider, contentStack);

        // 3. Data Binding
        ProjectRepository repository = ProjectRepository.getInstance();
        repository.projectsProperty().addListener((ListChangeListener<ProjectData>) c -> renderProjects());
        
        // Initial render
        renderProjects();
    }

    private void renderProjects() {
        projectGrid.getChildren().clear();
        var projects = ProjectRepository.getInstance().projectsProperty().get();

        if (projects.isEmpty()) {
            scrollPane.setVisible(false);
            welcomePane.setVisible(true);
        } else {
            scrollPane.setVisible(true);
            welcomePane.setVisible(false);

            int col = 0, row = 0;
            for (ProjectData project : projects) {
                projectGrid.add(createProjectCard(project), col, row);
                col++;
                if (col > 1) {
                    col = 0;
                    row++;
                }
            }
        }
    }

    private VBox createWelcomePane() {
        VBox pane = new VBox();
        pane.getStyleClass().add("welcome-pane");
        
        Label welcomeLabel = new Label("Welcome!");
        welcomeLabel.getStyleClass().add("welcome-title");
        
        Label subtitleLabel = new Label("Let's create something truly awesome.");
        subtitleLabel.getStyleClass().add("welcome-subtitle");
        
        Button startButton = new Button("Create Your First Project");
        startButton.getStyleClass().addAll("button-primary", "button-large");
        startButton.setStyle("-fx-padding: 12 24; -fx-font-size: 16px;");
        startButton.setOnAction(e -> com.vanvatcorporation.doubleclips.DoubleClipsDesktop.getInstance().showOverlay(new com.vanvatcorporation.doubleclips.ui.overlays.CreateProjectOverlay()));
        
        pane.getChildren().addAll(welcomeLabel, subtitleLabel, startButton);
        return pane;
    }

    private HBox createProjectCard(ProjectData project) {
        HBox card = new HBox(16);
        card.getStyleClass().add("project-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(Double.MAX_VALUE);

        // Thumbnail
        File previewFile = new File(project.getProjectPath(), "preview.png");
        if (!previewFile.exists()) {
            FileHelper.saveResourceToFile("/icons/app.png", previewFile);
        }

        ImageView thumbnail = new ImageView();
        try {
            Image img = new Image(previewFile.toURI().toString(), 80, 80, true, true);
            thumbnail.setImage(img);
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        thumbnail.setFitWidth(80);
        thumbnail.setFitHeight(80);
        
        // Rounded corners for the image
        Rectangle clip = new Rectangle(80, 80);
        clip.setArcWidth(20);
        clip.setArcHeight(20);
        thumbnail.setClip(clip);

        // Text block
        VBox textContainer = new VBox(3);
        HBox.setHgrow(textContainer, Priority.ALWAYS);

        Label titleLabel = new Label(project.getProjectTitle());
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        Label dateLabel = new Label(DateHelper.convertTimestampToDateTimeStringFormat(project.getProjectTimestamp()));
        dateLabel.getStyleClass().add("text-muted");

        String stats = DateHelper.convertTimestampToHHMMSSFormat(project.getProjectDuration()) + " • " + 
                       String.format("%.2f MB", project.getProjectSize() / (1024.0 * 1024.0));
        Label statsLabel = new Label(stats);
        statsLabel.getStyleClass().add("text-muted");

        textContainer.getChildren().addAll(titleLabel, dateLabel, statsLabel);
        textContainer.setAlignment(Pos.CENTER_LEFT);

        Button menuBtn = new Button();
        menuBtn.setGraphic(new FontIcon(MaterialDesignD.DOTS_HORIZONTAL));
        menuBtn.getStyleClass().addAll("button-transparent");
        
        // Context Menu Setup
        ContextMenu contextMenu = createContextMenu(project);
        menuBtn.setOnAction(e -> {
            contextMenu.show(menuBtn, javafx.geometry.Side.BOTTOM, 0, 0);
        });

        StackPane thumbnailContainer = new StackPane(thumbnail);
        thumbnailContainer.getStyleClass().add("project-thumbnail");
        thumbnailContainer.setPrefSize(80, 80);
        thumbnailContainer.setMinSize(80, 80);

        card.getChildren().addAll(thumbnailContainer, textContainer, menuBtn);

        // Enter Editor on click
        card.setOnMouseClicked(e -> {
            if (e.getClickCount() == 1 && e.getTarget() != menuBtn && !(e.getTarget() instanceof FontIcon)) {
                DoubleClipsDesktop.getInstance().openEditor(project);
            }
        });

        return card;
    }

    private ContextMenu createContextMenu(ProjectData project) {
        ContextMenu menu = new ContextMenu();
        
        MenuItem editItem = new MenuItem("Edit Title", new FontIcon(MaterialDesignP.PENCIL_OUTLINE));
        editItem.setOnAction(e -> showRenameDialog(project));
        
        MenuItem shareItem = new MenuItem("Share", new FontIcon(MaterialDesignS.SHARE_VARIANT));
        shareItem.setOnAction(e -> handleShare(project));
        
        MenuItem uploadItem = new MenuItem("Upload", new FontIcon(MaterialDesignU.UPLOAD_OUTLINE));
        uploadItem.setDisable(true); // Placeholder

        MenuItem revealItem = new MenuItem(getRevealLabel(), new FontIcon(MaterialDesignF.FOLDER_OUTLINE));
        revealItem.setOnAction(e -> FileHelper.revealInFileBrowser(java.nio.file.Paths.get(project.getProjectPath())));
        
        MenuItem cloneItem = new MenuItem("Clone", new FontIcon(MaterialDesignC.CONTENT_COPY));
        cloneItem.setOnAction(e -> ProjectRepository.getInstance().cloneProject(project));
        
        MenuItem deleteItem = new MenuItem("Delete", new FontIcon(MaterialDesignD.DELETE_OUTLINE));
        deleteItem.getStyleClass().add("danger"); // Assumes some CSS support or just visual distinction
        deleteItem.setOnAction(e -> showDeleteConfirmation(project));
        
        menu.getItems().addAll(editItem, shareItem, uploadItem, new SeparatorMenuItem(), revealItem, new SeparatorMenuItem(), cloneItem, deleteItem);
        return menu;
    }

    /**
     * Share = zip the project to a place the user picks (Android's share action). Next to the project's
     * files the zip also carries ffmpegCmd.txt, a ready-to-run FFmpeg render command for rendering
     * elsewhere. The zip is exactly what "Import Project" accepts. The work is in ProjectShare; this is
     * the file picker, the progress window and the result message.
     */
    private void handleShare(ProjectData project) {
        File projectDir = new File(project.getProjectPath());

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Share Project");
        chooser.setInitialFileName(ProjectShare.suggestedFileName(project.getProjectTitle()));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Project ZIP", "*.zip"));
        Window owner = getScene() != null ? getScene().getWindow() : null;
        File dest = chooser.showSaveDialog(owner);
        if (dest == null) return;
        if (!dest.getName().toLowerCase().endsWith(".zip")) dest = new File(dest.getParentFile(), dest.getName() + ".zip");
        final File destZip = dest;

        String problem = ProjectShare.checkDestination(projectDir, destZip);
        if (problem != null) {
            Alert alert = new Alert(Alert.AlertType.ERROR, problem, ButtonType.OK);
            alert.setTitle("Share Project");
            alert.setHeaderText("Can't share here");
            if (owner != null) alert.initOwner(owner);
            alert.show();
            return;
        }

        // A small non-closable progress window; it is closed from code when the work is done.
        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(360);
        Label status = new Label("Preparing...");
        VBox box = new VBox(10, status, bar);
        box.setPadding(new Insets(16));
        Dialog<Void> progress = new Dialog<>();
        progress.setTitle("Compressing project");
        if (owner != null) progress.initOwner(owner);
        progress.getDialogPane().setContent(box);
        progress.show();

        Task<String> task = new Task<>() {
            @Override
            protected String call() {
                return ProjectShare.share(projectDir, destZip, () -> {
                    // Same fixed profile as Android: 1080p30, CRF 18. Software encoder on purpose - this command is meant to
                    // be run on some other machine, so it must not name this machine's hardware encoder.
                    VideoSettings settings = new VideoSettings(1920, 1080, 30, 18, Integer.MAX_VALUE,
                            VideoSettings.FfmpegPreset.MEDIUM, VideoSettings.FfmpegTune.ZEROLATENCY);
                    settings.useHardwareAccel = false;
                    Timeline timeline = ProjectRepository.getInstance().loadTimeline(project);
                    return FFmpegEdit.generateCmdFull(settings, timeline, project, false, false);
                }, new CompressionHelper.ZipProgressListener() {
                    @Override
                    public void onProgress(long bytesWritten, long totalBytes, String name) {
                        double fraction = (double) bytesWritten / Math.max(1L, totalBytes);
                        Platform.runLater(() -> {
                            bar.setProgress(fraction);
                            status.setText(String.format("Compressing: %s (%.0f%%)", name, fraction * 100));
                        });
                        TaskbarHelper.updateProgress(fraction);
                    }

                    @Override public void onCompleted() {}
                    @Override public void onError(Exception e) {}
                });
            }
        };

        task.setOnSucceeded(ev -> {
            progress.close();
            TaskbarHelper.stopProgress();
            String error = task.getValue();
            if (error != null) {
                Alert alert = new Alert(Alert.AlertType.ERROR, error, ButtonType.OK);
                alert.setTitle("Share Project");
                alert.setHeaderText("Couldn't share the project");
                if (owner != null) alert.initOwner(owner);
                alert.show();
                return;
            }
            ButtonType reveal = new ButtonType(getRevealLabel(), ButtonBar.ButtonData.LEFT);
            Alert done = new Alert(Alert.AlertType.INFORMATION, "Saved to:\n" + destZip.getAbsolutePath(), ButtonType.OK, reveal);
            done.setTitle("Share Project");
            done.setHeaderText("Project shared");
            if (owner != null) done.initOwner(owner);
            done.showAndWait().filter(b -> b == reveal)
                    .ifPresent(b -> FileHelper.revealInFileBrowser(destZip.toPath()));
        });
        task.setOnFailed(ev -> {
            progress.close();
            TaskbarHelper.stopProgress();
            Throwable t = task.getException();
            Alert alert = new Alert(Alert.AlertType.ERROR, t == null ? "Unknown error." : String.valueOf(t.getMessage()), ButtonType.OK);
            alert.setTitle("Share Project");
            alert.setHeaderText("Couldn't share the project");
            if (owner != null) alert.initOwner(owner);
            alert.show();
        });

        Thread worker = new Thread(task, "share-project");
        worker.setDaemon(true);
        worker.start();
    }

    private void showRenameDialog(ProjectData project) {
        TextInputDialog dialog = new TextInputDialog(project.getProjectTitle());
        dialog.setTitle("Rename Project");
        dialog.setHeaderText("Change title for " + project.getProjectTitle());
        dialog.setContentText("New Title:");
        
        dialog.showAndWait().ifPresent(newName -> {
            if (!newName.trim().isEmpty()) {
                ProjectRepository.getInstance().renameProject(project, newName.trim());
            }
        });
    }

    private void showDeleteConfirmation(ProjectData project) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Delete Project");
        alert.setHeaderText("Delete '" + project.getProjectTitle() + "'?");
        alert.setContentText("This will permanently remove the project folder and all its contents.");
        
        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                ProjectRepository.getInstance().deleteProject(project);
            }
        });
    }

    private String getRevealLabel() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "Show in Explorer";
        if (os.contains("mac")) return "Reveal in Finder";
        return "Open in File Manager";
    }
}
