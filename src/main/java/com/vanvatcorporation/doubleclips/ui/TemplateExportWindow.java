package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.data.ClipReplacementData;
import com.vanvatcorporation.doubleclips.data.TemplateData;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.ui.components.ClipReplacementComponent;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class TemplateExportWindow extends Stage {

    private static TemplateExportWindow currentInstance = null;

    private final TemplateData data;
    private final VideoSettings settings;
    private final List<ClipReplacementData> clipReplacementList = new ArrayList<>();
    private final HBox clipsHBox = new HBox(15);
    
    private final TextArea commandTextArea = new TextArea();
    private final TextArea logTextArea = new TextArea();
    private final ProgressBar taskProgressBar = new ProgressBar(0);
    private final ProgressBar globalProgressBar = new ProgressBar(0);
    private final Label taskStatusLabel = new Label("Ready");
    private final Label globalStatusLabel = new Label("No tasks running");
    
    private final Button exportButton = new Button("Export");

    public static void showInstance(TemplateData data) {
        if (currentInstance != null) {
            currentInstance.close();
        }
        currentInstance = new TemplateExportWindow(data);
        currentInstance.show();
    }

    private TemplateExportWindow(TemplateData data) {
        this.data = data;
        this.settings = VideoSettings.createDefault();
        
        setTitle("Export Template: " + data.getTemplateTitle());
        initOwner(DoubleClipsDesktop.getInstance().getPrimaryStage());
        initModality(Modality.NONE);

        VBox root = new VBox(0);
        root.getStyleClass().add("export-window-root");
        root.setStyle("-fx-background-color: -color-bg-default;");

        // 1. Header
        HBox header = createHeader();
        
        // 2. Content (Scrollable)
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("edge-to-edge");
        
        VBox content = new VBox(25);
        content.setPadding(new Insets(20));
        
        // Section: Clip Replacement
        VBox clipsSection = createSection("Clip Replacement", createClipsPane());
        
        // Section: Advanced (FFmpeg Command)
        VBox advancedSection = createSection("Advanced Settings", createAdvancedPane());
        
        // Section: Log & Progress
        VBox logSection = createSection("Export Progress & Logs", createLogPane());
        
        content.getChildren().addAll(clipsSection, advancedSection, logSection);
        scrollPane.setContent(content);

        root.getChildren().addAll(header, scrollPane);

        Scene scene = new Scene(root, 900, 800);
        // Reuse theme
        scene.getStylesheets().add(DoubleClipsDesktop.getInstance().getPrimaryStage().getScene().getStylesheets().get(0));
        setScene(scene);

        // Initialize clips
        for (int i = 0; i < data.getTemplateClipCount(); i++) {
            clipReplacementList.add(new ClipReplacementData(ClipReplacementData.ClipType.VIDEO, "", null));
            clipsHBox.getChildren().add(new ClipReplacementComponent(i, clipReplacementList.get(i), this::handleClipClick));
        }

        // Auto-fetch command template & resources
        fetchFFmpegCommand();
        downloadResources();
    }

    private HBox createHeader() {
        HBox header = new HBox(20);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(15, 20, 15, 20));
        header.setStyle("-fx-background-color: -color-bg-subtle; -fx-border-color: transparent transparent -color-border-subtle transparent; -fx-border-width: 0 0 1 0;");

        Button backBtn = new Button();
        backBtn.setGraphic(new FontIcon(MaterialDesignC.CHEVRON_LEFT));
        backBtn.getStyleClass().addAll("button-transparent", "button-icon-only");
        backBtn.setOnAction(e -> close());

        Label title = new Label("Export Template");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        HBox.setHgrow(title, Priority.ALWAYS);

        Button settingsBtn = new Button();
        settingsBtn.setGraphic(new FontIcon(MaterialDesignC.COG));
        settingsBtn.getStyleClass().addAll("button-transparent", "button-icon-only");

        exportButton.getStyleClass().addAll("button-primary");
        exportButton.setPrefWidth(120);
        exportButton.setOnAction(e -> exportClip());

        header.getChildren().addAll(backBtn, title, settingsBtn, exportButton);
        return header;
    }

    private VBox createSection(String titleStr, Node content) {
        VBox section = new VBox(12);
        Label title = new Label(titleStr);
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -color-fg-muted;");
        section.getChildren().addAll(title, content);
        return section;
    }

    private Node createClipsPane() {
        ScrollPane scroll = new ScrollPane(clipsHBox);
        scroll.setFitToHeight(true);
        scroll.setPrefHeight(220);
        scroll.getStyleClass().add("edge-to-edge");
        clipsHBox.setPadding(new Insets(5));
        return scroll;
    }

    private Node createAdvancedPane() {
        VBox pane = new VBox(15);
        Button genCmdBtn = new Button("Generate FFmpeg Command");
        genCmdBtn.setOnAction(e -> generateCommand());
        
        commandTextArea.setEditable(true);
        commandTextArea.setWrapText(true);
        commandTextArea.setPrefHeight(150);
        commandTextArea.setStyle("-fx-font-family: 'Monaco', 'Courier New', monospace; -fx-font-size: 12px;");
        
        pane.getChildren().addAll(genCmdBtn, commandTextArea);
        return pane;
    }

    private Node createLogPane() {
        VBox pane = new VBox(15);
        
        VBox progressBars = new VBox(10);
        
        VBox taskBox = new VBox(5);
        taskBox.getChildren().addAll(taskStatusLabel, taskProgressBar);
        taskProgressBar.setMaxWidth(Double.MAX_VALUE);
        
        VBox globalBox = new VBox(5);
        globalBox.getChildren().addAll(globalStatusLabel, globalProgressBar);
        globalProgressBar.setMaxWidth(Double.MAX_VALUE);
        
        progressBars.getChildren().addAll(taskBox, globalBox);
        
        HBox options = new HBox(15);
        CheckBox logCheck = new CheckBox("Enable Log");
        CheckBox scrollCheck = new CheckBox("Scroll Lock");
        CheckBox truncateCheck = new CheckBox("Truncate");
        logCheck.setSelected(true);
        scrollCheck.setSelected(true);
        options.getChildren().addAll(logCheck, scrollCheck, truncateCheck);
        
        logTextArea.setEditable(false);
        logTextArea.setPrefHeight(200);
        logTextArea.setStyle("-fx-font-family: 'Monaco', 'Courier New', monospace; -fx-font-size: 11px;");
        
        pane.getChildren().addAll(progressBars, options, logTextArea);
        return pane;
    }

    private void handleClipClick(ClipReplacementComponent component) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Media for Clip #" + (clipReplacementList.indexOf(component.getData()) + 1));
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Media Files", "*.mp4", "*.mov", "*.mkv", "*.png", "*.jpg", "*.jpeg")
        );
        File file = fileChooser.showOpenDialog(this);
        if (file != null) {
            ClipReplacementData data = component.getData();
            data.setClipPath(file.getAbsolutePath());
            data.setType(file.getName().toLowerCase().endsWith(".png") || file.getName().toLowerCase().endsWith(".jpg") ? ClipReplacementData.ClipType.IMAGE : ClipReplacementData.ClipType.VIDEO);
            
            // For now, no thumbnail extraction until FFmpegEdit is ready
            // We could use a generic icon or try to load as image
            if (data.getType() == ClipReplacementData.ClipType.IMAGE) {
                data.setClipThumbnail(new Image("file:" + file.getAbsolutePath(), 100, 160, true, true));
            }
            component.updateThumbnail();
        }
    }

    private void fetchFFmpegCommand() {
        taskStatusLabel.setText("Fetching FFmpeg template...");
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://app.vanvatcorp.com/doubleclips/api/fetch-ffmpeg-command/" + data.getTemplateAuthor() + "/" + data.getTemplateId()))
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(body -> {
                    Platform.runLater(() -> {
                        data.setFfmpegCommand(body);
                        taskStatusLabel.setText("Template ready.");
                        logTextArea.appendText("Successfully fetched FFmpeg command template.\n");
                    });
                })
                .exceptionally(e -> {
                    Platform.runLater(() -> {
                        taskStatusLabel.setText("Failed to fetch template.");
                        logTextArea.appendText("Error fetching command: " + e.getMessage() + "\n");
                    });
                    return null;
                });
    }

    private void downloadResources() {
        if (data.getTemplateAdditionalResourcesName() == null || data.getTemplateAdditionalResourcesName().length == 0) return;

        logTextArea.appendText("Initializing resource downloads...\n");
        HttpClient client = HttpClient.newHttpClient();
        String tempDir = IOHelper.CombinePath(IOHelper.getPersistentDataPath(), Constants.DEFAULT_TEMPLATE_CLIP_TEMP_DIRECTORY);
        
        // Ensure temp dir exists
        new File(tempDir).mkdirs();

        for (String name : data.getTemplateAdditionalResourcesName()) {
            String url = "https://app.vanvatcorp.com/doubleclips/templates/" + data.getTemplateLocation() + "/content/" + name;
            String destPath = IOHelper.CombinePath(tempDir, name);
            Platform.runLater(() -> { logTextArea.appendText("Downloading: " + url + " at " + destPath + "\n"); });
            
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            client.sendAsync(request, HttpResponse.BodyHandlers.ofFile(new File(destPath).toPath()))
                    .thenAccept(res -> {
                        Platform.runLater(() -> logTextArea.appendText("Downloaded: " + name + " at " + destPath + "\n"));
                    })
                    .exceptionally(e -> {
                        Platform.runLater(() -> logTextArea.appendText("Failed to download " + name + ": " + e.getMessage() + "\n"));
                        return null;
                    });
        }
    }

    private void generateCommand() {
        if (data.getFfmpegCommand() == null || data.getFfmpegCommand().isEmpty()) {
            logTextArea.appendText("Warning: FFmpeg template not yet loaded.\n");
            return;
        }

        String cmd = data.getFfmpegCommand();
        for (int i = 0; i < clipReplacementList.size(); i++) {
            ClipReplacementData clipData = clipReplacementList.get(i);
            String path = clipData.getClipPath();
            if (path == null || path.isEmpty()) {
                path = "MISSING_CLIP_" + i;
            }

            String frameFilter = (clipData.getType() == ClipReplacementData.ClipType.IMAGE) ?
                    "-loop 1 -t " + data.getTemplateDuration() + " -framerate " + settings.getFrameRate() + " " : "";

            cmd = cmd.replace("-i \"" + Constants.DEFAULT_TEMPLATE_CLIP_MARK(i), frameFilter + "-i \"" + path);
            
            String trimFilter = (clipData.getType() == ClipReplacementData.ClipType.VIDEO) ?
                    "trim=start=" + clipData.getStartClipTrim() + ":end=" + (clipData.getStartClipTrim() + clipData.getDuration()) :
                    "trim=duration=" + clipData.getDuration();
            
            cmd = cmd.replace(Constants.DEFAULT_TEMPLATE_TRIM_MARK(i), trimFilter);
        }

        // Replace global constants
        cmd = cmd.replace(Constants.DEFAULT_TEMPLATE_CLIP_SCALE_WIDTH_MARK, String.valueOf(settings.videoWidth));
        cmd = cmd.replace(Constants.DEFAULT_TEMPLATE_CLIP_SCALE_HEIGHT_MARK, String.valueOf(settings.videoHeight));
        
        // Mock output path
        String outputPath = IOHelper.CombinePath(System.getProperty("user.home"), "Downloads", "DoubleClips_Export.mp4");
        cmd = cmd.replace(Constants.DEFAULT_TEMPLATE_CLIP_EXPORT_MARK, outputPath);

        commandTextArea.setText(cmd);
        logTextArea.appendText("Command generated.\n");
    }

    private void exportClip() {
        if(commandTextArea.getText().isEmpty()) {
            generateCommand();
        }
        String cmd = commandTextArea.getText();
        
        logTextArea.appendText("\n>>> STARTING NATIVE EXPORT <<<\n");
        logTextArea.appendText("Binary: " + com.vanvatcorporation.doubleclips.FFmpegEdit.getFfmpegPath() + "\n");
        
        exportButton.setDisable(true);
        taskProgressBar.setProgress(0);
        
        com.vanvatcorporation.doubleclips.FFmpegEdit.runAnyCommand(
            cmd, 
            "Template Export: " + data.getTemplateTitle(),
            () -> Platform.runLater(() -> {
                exportButton.setDisable(false);
                taskStatusLabel.setText("Export Completed!");
                logTextArea.appendText("\n>>> EXPORT FINISHED SUCCESS <<<\n");
                
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.initOwner(this);
                alert.setTitle("Export Complete");
                alert.setHeaderText("Success!");
                alert.setContentText("Your video has been exported successfully.");
                alert.showAndWait();
            }),
            () -> Platform.runLater(() -> {
                exportButton.setDisable(false);
                taskStatusLabel.setText("Export Failed");
                logTextArea.appendText("\n>>> EXPORT FAILED <<<\n");
            }),
            log -> Platform.runLater(() -> logTextArea.appendText(log + "\n")),
            stats -> Platform.runLater(() -> {
                double duration = data.getTemplateDuration(); // seconds
                if (duration > 0) {
                    double progress = (stats.getTimeInMs() / 1000.0) / duration;
                    taskProgressBar.setProgress(Math.min(1.0, progress));
                    taskStatusLabel.setText("Exporting: " + (int)(Math.min(1.0, progress) * 100) + "% (" + stats.getTime() + ")");
                }
            })
        );
    }

    @Override
    public void close() {
        if (currentInstance == this) {
            currentInstance = null;
        }
        super.close();
    }
}
