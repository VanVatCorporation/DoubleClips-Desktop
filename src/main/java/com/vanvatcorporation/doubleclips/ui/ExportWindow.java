package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.FFmpegEdit;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignK;
import org.kordamp.ikonli.materialdesign2.MaterialDesignU;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.vanvatcorporation.doubleclips.constants.Constants;


/**
 * Desktop equivalent of Android's ExportActivity.
 * <p>
 * Sections:
 *  - Top bar   : back, settings (video properties), export-as-template, export
 *  - Advanced  : generate-command button + editable FFmpeg command text area
 *  - Log       : current-task progress bar, global progress bar, log controls, log text area
 */
public class ExportWindow extends Stage {

    // ── Data ────────────────────────────────────────────────────────────────
    private final ProjectData  project;
    private final Timeline     timeline;
    private final VideoSettings settings;

    // ── Advanced section ────────────────────────────────────────────────────
    private final TextArea commandTextArea = new TextArea();

    // ── Log / Progress section ───────────────────────────────────────────────
    private final Label      taskStatusLabel  = new Label("Current Task: 0%");
    private final ProgressBar taskProgressBar  = new ProgressBar(0);
    private final Label      globalStatusLabel = new Label("Remaining Tasks: 0 / 0");
    private final ProgressBar globalProgressBar = new ProgressBar(0);
    private final TextArea   logTextArea      = new TextArea();
    private final CheckBox   logCheckBox      = new CheckBox("Enable Log");
    private final CheckBox   truncateCheckBox = new CheckBox("Truncate Log");
    private final CheckBox   scrollLockCheckBox = new CheckBox("Scroll Lock");

    // ── Action buttons ────────────────────────────────────────────────────────
    private final Button exportButton           = new Button("Export");
    private final Button exportAsTemplateButton = new Button("Export as Template");

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean isExporting = false;

    // ─────────────────────────────────────────────────────────────────────────
    //  Factory / Show
    // ─────────────────────────────────────────────────────────────────────────

    /** Open (or replace) the singleton export window. */
    public static void show(Stage owner, ProjectData project, Timeline timeline, VideoSettings settings) {
        ExportWindow win = new ExportWindow(owner, project, timeline, settings);
        win.show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Constructor
    // ─────────────────────────────────────────────────────────────────────────

    private ExportWindow(Stage owner, ProjectData project, Timeline timeline, VideoSettings settings) {
        this.project  = project;
        this.timeline = timeline;
        this.settings = settings;

        setTitle("Export — " + project.getProjectTitle());
        initOwner(owner);
        initModality(Modality.NONE);
        setWidth(860);
        setHeight(720);
        setMinWidth(640);
        setMinHeight(500);

        // ── Root ───────────────────────────────────────────────────────────
        VBox root = new VBox(0);
        root.getStyleClass().add("export-window-root");

        // ── Top bar ────────────────────────────────────────────────────────
        root.getChildren().add(buildTopBar());

        // ── Scrollable body ────────────────────────────────────────────────
        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox body = new VBox(20);
        body.setPadding(new Insets(20));

        body.getChildren().addAll(
                buildSection("Advanced", buildAdvancedPane()),
                buildSection("Log & Progress", buildLogPane())
        );

        scroll.setContent(body);
        root.getChildren().add(scroll);

        // ── Scene ──────────────────────────────────────────────────────────
        Scene scene = new Scene(root);
        scene.getStylesheets().add(
                DoubleClipsDesktop.class.getResource("/style.css").toExternalForm());
        setScene(scene);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Top bar
    // ─────────────────────────────────────────────────────────────────────────

    private HBox buildTopBar() {
        HBox bar = new HBox(10);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 16, 10, 16));
        bar.getStyleClass().add("export-topbar");

        // Back
        Button backBtn = new Button();
        backBtn.setGraphic(new FontIcon(MaterialDesignK.KEYBOARD_RETURN));
        backBtn.getStyleClass().add("button-transparent");
        backBtn.setOnAction(e -> close());

        // Settings (video properties)
        Button settingsBtn = new Button();
        settingsBtn.setGraphic(new FontIcon(MaterialDesignC.COG));
        settingsBtn.getStyleClass().add("button-transparent");
        settingsBtn.setOnAction(e -> openVideoSettings());

        Label title = new Label("Export");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Export as Template
        exportAsTemplateButton.setGraphic(new FontIcon(MaterialDesignU.UPLOAD_OUTLINE));
        exportAsTemplateButton.getStyleClass().add("export-template-button");
        exportAsTemplateButton.setOnAction(e -> exportClip(true));

        // Export
        exportButton.setGraphic(new FontIcon(MaterialDesignU.UPLOAD_OUTLINE));
        exportButton.getStyleClass().add("export-button");
        exportButton.setOnAction(e -> exportClip(false));

        bar.getChildren().addAll(backBtn, settingsBtn, title, spacer,
                exportAsTemplateButton, exportButton);
        return bar;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Section wrapper (mirrors Android SectionView)
    // ─────────────────────────────────────────────────────────────────────────

    private VBox buildSection(String titleStr, javafx.scene.Node content) {
        VBox section = new VBox(10);
        section.getStyleClass().add("export-section");
        section.setPadding(new Insets(16));

        Label lbl = new Label(titleStr);
        lbl.getStyleClass().add("export-section-title");

        section.getChildren().addAll(lbl, content);
        return section;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Advanced pane
    // ─────────────────────────────────────────────────────────────────────────

    private javafx.scene.Node buildAdvancedPane() {
        VBox pane = new VBox(10);

        HBox btns = new HBox(8);

        Button genCmdBtn = new Button("Generate Command");
        genCmdBtn.setGraphic(new FontIcon(MaterialDesignC.CODE_TAGS));
        genCmdBtn.getStyleClass().add("secondary-action-button");
        genCmdBtn.setOnAction(e -> commandTextArea.setText(generateCommand()));

        Button genTemplateCmdBtn = new Button("Generate Template Command");
        genTemplateCmdBtn.setGraphic(new FontIcon(MaterialDesignC.CODE_TAGS_CHECK));
        genTemplateCmdBtn.getStyleClass().add("secondary-action-button");
        genTemplateCmdBtn.setOnAction(e -> generateTemplateCommand());

        btns.getChildren().addAll(genCmdBtn, genTemplateCmdBtn);

        commandTextArea.setPromptText("FFmpeg command will appear here…");
        commandTextArea.setWrapText(true);
        commandTextArea.setPrefHeight(180);
        commandTextArea.getStyleClass().add("export-command-area");

        pane.getChildren().addAll(btns, commandTextArea);
        return pane;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Log & Progress pane
    // ─────────────────────────────────────────────────────────────────────────

    private javafx.scene.Node buildLogPane() {
        VBox pane = new VBox(12);

        // Current-task progress
        VBox taskBox = new VBox(4);
        taskStatusLabel.getStyleClass().add("export-status-label");
        taskProgressBar.setMaxWidth(Double.MAX_VALUE);
        taskProgressBar.getStyleClass().add("export-progress-bar");
        taskBox.getChildren().addAll(taskStatusLabel, taskProgressBar);

        // Global progress
        VBox globalBox = new VBox(4);
        globalStatusLabel.getStyleClass().add("export-status-label");
        globalProgressBar.setMaxWidth(Double.MAX_VALUE);
        globalProgressBar.getStyleClass().add("export-progress-bar");
        globalBox.getChildren().addAll(globalStatusLabel, globalProgressBar);

        // Options row
        HBox options = new HBox(16);
        options.setAlignment(Pos.CENTER_LEFT);
        logCheckBox.setSelected(true);
        truncateCheckBox.setSelected(true);
        scrollLockCheckBox.setSelected(true);
        options.getChildren().addAll(logCheckBox, truncateCheckBox, scrollLockCheckBox);

        // Log text area
        logTextArea.setEditable(false);
        logTextArea.setWrapText(true);
        logTextArea.setPrefHeight(220);
        logTextArea.getStyleClass().add("export-log-area");
        VBox.setVgrow(logTextArea, Priority.ALWAYS);

        pane.getChildren().addAll(taskBox, globalBox, options, logTextArea);
        return pane;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Video Settings dialog
    // ─────────────────────────────────────────────────────────────────────────

    private void openVideoSettings() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Video Settings");
        dialog.initOwner(this);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        String[] labels = { "Width", "Height", "Frame Rate", "CRF", "Bitrate (Mbps)", "Clip Cap" };
        TextField[] fields = {
                makeField(String.valueOf(settings.videoWidth)),
                makeField(String.valueOf(settings.videoHeight)),
                makeField(String.valueOf(settings.frameRate)),
                makeField(String.valueOf(settings.crf)),
                makeField(String.valueOf(settings.bitrate)),
                makeField(String.valueOf(settings.clipCap))
        };
        for (int i = 0; i < labels.length; i++) {
            grid.add(new Label(labels[i]), 0, i);
            grid.add(fields[i], 1, i);
        }

        // Preset
        ComboBox<String> presetBox = new ComboBox<>();
        presetBox.getItems().addAll("ultrafast","superfast","veryfast","faster","fast",
                "medium","slow","slower","veryslow","placebo");
        presetBox.setValue(settings.preset != null ? settings.preset : "medium");
        grid.add(new Label("Preset"), 0, labels.length);
        grid.add(presetBox, 1, labels.length);

        // Tune
        ComboBox<String> tuneBox = new ComboBox<>();
        tuneBox.getItems().addAll("film","animation","grain","stillimage","fastdecode","zerolatency");
        tuneBox.setValue(settings.tune != null ? settings.tune : "film");
        grid.add(new Label("Tune"), 0, labels.length + 1);
        grid.add(tuneBox, 1, labels.length + 1);

        // Checkboxes
        CheckBox stretchCB = new CheckBox("Stretch to Full");
        stretchCB.setSelected(settings.isStretchToFull);
        CheckBox hwAccelCB = new CheckBox("Hardware Acceleration");
        hwAccelCB.setSelected(settings.useHardwareAccel);
        grid.add(stretchCB, 0, labels.length + 2, 2, 1);
        grid.add(hwAccelCB, 0, labels.length + 3, 2, 1);

        dialog.getDialogPane().setContent(grid);

        dialog.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                try { settings.videoWidth  = Integer.parseInt(fields[0].getText()); } catch (Exception ignored) {}
                try { settings.videoHeight = Integer.parseInt(fields[1].getText()); } catch (Exception ignored) {}
                try { settings.frameRate   = Integer.parseInt(fields[2].getText()); } catch (Exception ignored) {}
                try { settings.crf         = Integer.parseInt(fields[3].getText()); } catch (Exception ignored) {}
                try { settings.bitrate     = Integer.parseInt(fields[4].getText()); } catch (Exception ignored) {}
                try { settings.clipCap     = Integer.parseInt(fields[5].getText()); } catch (Exception ignored) {}
                settings.preset           = presetBox.getValue();
                settings.tune             = tuneBox.getValue();
                settings.isStretchToFull  = stretchCB.isSelected();
                settings.useHardwareAccel = hwAccelCB.isSelected();
                appendLog("Video settings updated.");
            }
        });
    }

    private TextField makeField(String value) {
        TextField tf = new TextField(value);
        tf.setPrefWidth(120);
        return tf;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Command generation (mirrors Android generateCommand / generateTemplateCommand)
    // ─────────────────────────────────────────────────────────────────────────

    private String generateCommand() {
        try {
            FFmpegEdit.RenderSettings rs = new FFmpegEdit.RenderSettings(
                    settings, timeline, new com.vanvatcorporation.doubleclips.data.editing.Clip[0],
                    project, 0, false, false, false);
            appendLog("FFmpeg command generated.");
            return FFmpegEdit.generateCmdFull(rs);
        } catch (Exception e) {
            appendLog("Error generating command: " + e.getMessage());
            return "";
        }
    }

    private String generateTemplateCommand() {
        try {
            FFmpegEdit.RenderSettings rs = new FFmpegEdit.RenderSettings(
                    settings, timeline, new com.vanvatcorporation.doubleclips.data.editing.Clip[0],
                    project, 0, false, true, true);
            appendLog("Template FFmpeg command generated.");
            return FFmpegEdit.generateCmdFull(rs);
        } catch (Exception e) {
            appendLog("Error generating template command: " + e.getMessage());
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Export
    // ─────────────────────────────────────────────────────────────────────────

    private void exportClip(boolean asTemplate) {
        if (isExporting) return;

        if (commandTextArea.getText().isBlank())
            commandTextArea.setText(generateCommand());

        String cmd = commandTextArea.getText().replace("\n", "");
        if (cmd.isBlank()) {
            appendLog("No command to run. Generate a command first.");
            return;
        }

        // Rendering is done to the project folder by default (export.mp4)


        startExportRendering();

        String[] cmdAfterSplit = cmd.split(Constants.DEFAULT_MULTI_FFMPEG_COMMAND_REGEX);
        for (int i = 0; i < cmdAfterSplit.length; i++) {

            appendLog("\n>>> STARTING EXPORT " + i + "/" + cmdAfterSplit.length + " <<<");
            appendLog("Binary: " + FFmpegEdit.getFfmpegPath() + "\n");

            String cmdEach = cmdAfterSplit[i];
            FFmpegEdit.runAnyCommand(
                    cmdEach,
                    "Exporting — " + project.getProjectTitle(),
                    (i == cmdAfterSplit.length - 1 ?
                            // onSuccess
                            () -> Platform.runLater(() -> {

                                // Intermediate file in project folder
                                File intermediateFile = new File(project.getProjectPath(), Constants.DEFAULT_EXPORT_CLIP_FILENAME);

                                // Ask for output file location
                                FileChooser fc = new FileChooser();
                                fc.setTitle("Save Exported Video");
                                fc.setInitialFileName(project.getProjectTitle() + "_export.mp4");
                                fc.getExtensionFilters().add(
                                        new FileChooser.ExtensionFilter("MP4 Video", "*.mp4"));
                                File userDest = fc.showSaveDialog(this);

                                String finalPath = intermediateFile.getAbsolutePath();

                                if (userDest != null) {
                                    try {
                                        Files.move(intermediateFile.toPath(), userDest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                        finalPath = userDest.getAbsolutePath();
                                    } catch (IOException e) {
                                        appendLog("Error moving file to destination: " + e.getMessage());
                                        // Fallback to intermediate path if move fails
                                    }
                                }

                                finishExportRendering();
                                taskStatusLabel.setText("Export Completed! ✓");
                                taskProgressBar.setProgress(1.0);
                                appendLog("\n>>> EXPORT FINISHED SUCCESSFULLY <<<");
                                appendLog("Location: " + finalPath);

                                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                                alert.initOwner(this);
                                alert.setTitle("Export Complete");
                                alert.setHeaderText("Success!");
                                alert.setContentText("Your video has been exported to:\n" + finalPath);
                                alert.showAndWait();
                            }) : () -> {}),

                    // onFail
                    () -> Platform.runLater(() -> {
                        finishExportRendering();
                        taskStatusLabel.setText("Export Failed ✗");
                        appendLog("\n>>> EXPORT FAILED <<<");
                    }),
                    // onLog
                    log -> Platform.runLater(() -> {
                        if (logCheckBox.isSelected()) {
                            appendLog(log);
                        }
                    }),
                    // onStatistics
                    stats -> Platform.runLater(() -> {
                        long progressMs = stats.getTimeInMs();
                        long durationMs = project.getProjectDuration();
                        if (durationMs > 0 && progressMs > 0) {
                            double progress = Math.min(1.0, (double) progressMs / durationMs);
                            taskProgressBar.setProgress(progress);
                            taskStatusLabel.setText(String.format(
                                    "Exporting: %d%%  (%s)", (int)(progress * 100), stats.getTime()));
                        }

                        globalProgressBar.setProgress((double) FFmpegEdit.queue.queueDone / FFmpegEdit.queue.totalQueue);
                        globalStatusLabel.setText(String.format(
                                "Remaining Task: %d/%d", FFmpegEdit.queue.queueDone, FFmpegEdit.queue.totalQueue));
                    }));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Export state helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void startExportRendering() {
        isExporting = true;
        exportButton.setDisable(true);
        exportAsTemplateButton.setDisable(true);
        taskProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        taskStatusLabel.setText("Preparing export…");
        globalStatusLabel.setText("Running…");
    }

    private void finishExportRendering() {
        isExporting = false;
        exportButton.setDisable(false);
        exportAsTemplateButton.setDisable(false);
        globalStatusLabel.setText("Done");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Log helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static final int MAX_LOG_CHARS = 50_000;

    private void appendLog(String message) {
        // Already on FX thread when called from onLog / onStatistics via Platform.runLater
        String current = logTextArea.getText();
        String next = current + "\n" + message;
        if (truncateCheckBox.isSelected() && next.length() > MAX_LOG_CHARS) {
            next = next.substring(next.length() - MAX_LOG_CHARS);
        }
        logTextArea.setText(next);
        if (scrollLockCheckBox.isSelected()) {
            logTextArea.positionCaret(logTextArea.getText().length());
        }
    }
}
