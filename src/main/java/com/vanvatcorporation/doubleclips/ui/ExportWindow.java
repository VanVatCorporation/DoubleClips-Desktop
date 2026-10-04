package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.FFmpegEdit;
import com.vanvatcorporation.doubleclips.FFmpegEditNative;
import com.vanvatcorporation.doubleclips.OpenGLEdit;
import com.vanvatcorporation.doubleclips.OpenGLEditNative;
import com.vanvatcorporation.doubleclips.auth.AuthRepository;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.ProjectRepository;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.helper.android.AlertDialog;
import com.vanvatcorporation.doubleclips.helper.android.Context;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import okhttp3.*;
import okio.*;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignK;
import org.kordamp.ikonli.materialdesign2.MaterialDesignU;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

    // ── Render engine row (mirrors Android ExportActivity's renderEngineRadioGroup) ─
    private final ToggleGroup renderEngineGroup = new ToggleGroup();
    private final RadioButton ffmpegEngineRadio = new RadioButton("FFmpeg (CPU)");
    private final RadioButton openGlEngineRadio = new RadioButton("OpenGL (GPU) — Recommended");

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

        // ── Render engine choice ──────────────────────────────────────────
        root.getChildren().add(buildRenderEngineRow());

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
        exportAsTemplateButton.setOnAction(e -> {
            if(AuthRepository.getInstance().getCurrentUser() == null) {
                new AlertDialog.Builder().setTitle("Login required").setMessage("Please login to post template").create().show();
                return;
            }
            exportClipViaChosenEngine(true);
        });

        // Export
        exportButton.setGraphic(new FontIcon(MaterialDesignU.UPLOAD_OUTLINE));
        exportButton.getStyleClass().add("export-button");
        exportButton.setOnAction(e -> exportClipViaChosenEngine(false));

        bar.getChildren().addAll(backBtn, settingsBtn, title, spacer,
                exportAsTemplateButton, exportButton);
        return bar;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Render engine row
    // ─────────────────────────────────────────────────────────────────────────

    private HBox buildRenderEngineRow() {
        HBox row = new HBox(16);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 16, 10, 16));
        row.getStyleClass().add("export-render-engine-row");

        ffmpegEngineRadio.setToggleGroup(renderEngineGroup);
        openGlEngineRadio.setToggleGroup(renderEngineGroup);
        ffmpegEngineRadio.setUserData("ffmpeg");
        openGlEngineRadio.setUserData("opengl");

        if (settings.isOpenGlRenderEngine()) {
            openGlEngineRadio.setSelected(true);
        } else {
            ffmpegEngineRadio.setSelected(true);
        }

        renderEngineGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) return;
            settings.setRenderEngine((String) newToggle.getUserData());
            ProjectRepository.getInstance().saveVideoSettings(project, settings);
        });

        row.getChildren().addAll(ffmpegEngineRadio, openGlEngineRadio);
        return row;
    }

    /**
     * Desktop equivalent of Android's exportClipViaChosenEngine(). What the
     * OpenGL renderer can't reproduce yet is defined in ONE place -
     * OpenGLEdit's capability flags / getUnsupportedFeatures() - same as
     * Android, so this screen doesn't need updating when a feature there
     * gets implemented.
     */
    private void exportClipViaChosenEngine(boolean exportAsTemplate) {
        if (!settings.isOpenGlRenderEngine()) {
            // FFmpeg plays every feature except a few animation channels (scale / rotation / temperature
            // animations, or animations that aren't installed) - tell the user before they wait for an export.
            List<String> animationGaps = FFmpegEdit.getUnsupportedAnimationFeatures(timeline);
            if (animationGaps.isEmpty()) {
                exportClip(exportAsTemplate);
                return;
            }
            StringBuilder gapMessage = new StringBuilder("FFmpeg export can't reproduce these animation effects:\n");
            for (String gap : animationGaps) gapMessage.append("\n  \u2022 ").append(gap);
            gapMessage.append("\n\nSwitch the render engine to OpenGL to get them, or continue and they will be left out of this export.");

            Alert gapAlert = new Alert(Alert.AlertType.CONFIRMATION);
            gapAlert.initOwner(this);
            gapAlert.setTitle("Some animation effects are not available");
            gapAlert.setHeaderText(null);
            gapAlert.setContentText(gapMessage.toString());
            ButtonType continueFfmpeg = new ButtonType("Continue with FFmpeg");
            gapAlert.getButtonTypes().setAll(continueFfmpeg, ButtonType.CANCEL);
            gapAlert.showAndWait().ifPresent(choice -> {
                if (choice == continueFfmpeg) exportClip(exportAsTemplate);
            });
            return;
        }

        List<String> unsupported = OpenGLEdit.getUnsupportedFeatures(timeline);
        if (unsupported.isEmpty()) {
            exportClipViaOpenGl(exportAsTemplate);
            return;
        }

        StringBuilder message = new StringBuilder("OpenGL export can't reproduce these yet:\n");
        for (String feature : unsupported) message.append("\n  \u2022 ").append(feature);
        message.append("\n\nUse FFmpeg to get them all, or continue with OpenGL and they will be left out of this export.");

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(this);
        alert.setTitle("OpenGL export not complete yet");
        alert.setHeaderText(null);
        alert.setContentText(message.toString());

        ButtonType useFfmpeg = new ButtonType("Use FFmpeg");
        ButtonType useOpenGlAnyway = new ButtonType("Use OpenGL anyway");
        ButtonType cancel = ButtonType.CANCEL;
        alert.getButtonTypes().setAll(useFfmpeg, useOpenGlAnyway, cancel);

        alert.showAndWait().ifPresent(choice -> {
            if (choice == useFfmpeg) {
                exportClip(exportAsTemplate);
            } else if (choice == useOpenGlAnyway) {
                exportClipViaOpenGl(exportAsTemplate);
            }
            // cancel: do nothing
        });
    }

    /** Kept alongside cancelButton (if present) so a cancel click can reach the running worker process. */
    private OpenGLEditNative activeOpenGlExport;

    /**
     * Real GL export: OpenGLEditNative (video-only, via a separate worker
     * process - see its own javadoc for why) + a separate audio-only FFmpeg
     * pass + a final `-c copy` mux, same overall shape Android's own OpenGL
     * export activity uses (its OpenGLEditNative is video-only too; FFmpeg
     * handles audio there as well).
     * <p>
     * Template export isn't supported through this path yet - a template's
     * output is a differently-shaped set of per-clip marker files, not a
     * single mp4, which this pipeline doesn't produce. Falls back to FFmpeg
     * for that case specifically rather than mishandling it silently.
     */
    private void exportClipViaOpenGl(boolean exportAsTemplate) {
        if (exportAsTemplate) {
            appendLog("OpenGL template export isn't supported yet — using FFmpeg for this template.");
            exportClip(true);
            return;
        }

        if (isExporting) return;
        startExportRendering();
        appendLog("\n>>> STARTING OPENGL EXPORT <<<");

        int width = settings.getRenderVideoWidth(false);
        int height = settings.getRenderVideoHeight(false);
        int frameRate = settings.getFrameRate();

        String videoOnlyPath = IOHelper.CombinePath(project.getProjectPath(), "opengl_video_only_tmp.mp4");
        String audioOnlyPath = IOHelper.CombinePath(project.getProjectPath(), "opengl_audio_only_tmp.m4a");
        String finalPath = IOHelper.CombinePath(project.getProjectPath(), Constants.DEFAULT_EXPORT_CLIP_FILENAME);

        // Closing the window is the only cancel this screen has: without this the
        // worker process would keep rendering (and holding the GPU) with nobody watching.
        setOnCloseRequest(e -> {
            OpenGLEditNative running = activeOpenGlExport;
            if (running != null) running.cancel();
        });

        Thread worker = new Thread(() -> {
            // Identity-keyed (Clip has no equals); filled by the reversed-clip pre-pass below.
            final java.util.Map<Clip, String> reversedClipPaths = new java.util.IdentityHashMap<>();
            final OpenGLEditNative gl = new OpenGLEditNative();
            activeOpenGlExport = gl;
            // FPS tracking state for onProgress
            final long[] lastProgressTimeMs = {System.currentTimeMillis()};
            final int[] lastFrameIndex = {0};
            try {
                OpenGLEditNative.ExportListener listener = new OpenGLEditNative.ExportListener() {
                    @Override
                    public void onLog(String message) {
                        Platform.runLater(() -> {
                            if (logCheckBox.isSelected()) appendLog("OpenGL: " + message);
                        });
                    }

                    @Override
                    public void onProgress(int frameIndex, int totalFrames) {
                        // FPS tracking state
                        double fraction = totalFrames == 0 ? 0 : (double) frameIndex / totalFrames;
                        float percent = (float) (fraction * 100);

                        long now = System.currentTimeMillis();
                        long elapsedMs = now - lastProgressTimeMs[0];
                        float fps = 0f;
                        if (elapsedMs > 0) {
                            fps = (frameIndex - lastFrameIndex[0]) * 1000f / elapsedMs;
                        }
                        lastProgressTimeMs[0] = now;
                        lastFrameIndex[0] = frameIndex;

                        final String fpsStr = String.format(java.util.Locale.US, "%.2f", fps);
                        final String percentStr = String.format(java.util.Locale.US, "%.1f", percent);
                        Platform.runLater(() -> {
                            taskProgressBar.setProgress(fraction * 0.85);
                            taskStatusLabel.setText(
                                    new StringBuilder()
                                            .append("Compositing Video (OpenGL)...")
                                            .append(" (")
                                            .append(frameIndex)
                                            .append("/")
                                            .append(totalFrames)
                                            .append(" frames - ")
                                            .append(fpsStr)
                                            .append(" frames per second)")
                                            .append(" (")
                                            .append(percentStr)
                                            .append("%)")                                            
                                            .toString());
                        });
                    }
                };

                // Reversed-clip pre-pass (Android does the same): a forward-only decode
                // pipe can't play backward, so each reversed VIDEO clip's used range is
                // reversed into a temp file first. Fails the export rather than letting
                // GL quietly play that clip forward.
                Platform.runLater(() -> taskStatusLabel.setText("Preparing clips…"));
                reversedClipPaths.putAll(OpenGLEditNative.renderReversedIntermediates(timeline, project, gl::isCancelled, listener));
                if (gl.isCancelled()) {
                    cleanupOpenGlTemp(videoOnlyPath, audioOnlyPath, reversedClipPaths);
                    Platform.runLater(this::cancelOpenGlExport);
                    return;
                }

                Platform.runLater(() -> taskStatusLabel.setText("Compositing video (OpenGL)…"));
                gl.exportTimeline(timeline, settings, project.getProjectPath(), width, height, frameRate, videoOnlyPath,
                        reversedClipPaths, listener);

                if (gl.isCancelled()) {
                    cleanupOpenGlTemp(videoOnlyPath, audioOnlyPath, reversedClipPaths);
                    Platform.runLater(this::cancelOpenGlExport);
                    return;
                }

                String audioCmd = OpenGLEditNative.buildAudioOnlyCommand(settings, timeline, project);
                if (!audioCmd.contains("[aout]")) {
                    // Nothing to mix - this timeline has no audio at all.
                    Files.move(Path.of(videoOnlyPath), Path.of(finalPath), StandardCopyOption.REPLACE_EXISTING);
                    cleanupOpenGlTemp(null, audioOnlyPath, reversedClipPaths);
                    Platform.runLater(() -> finishOpenGlExportSuccess(finalPath));
                    return;
                }

                Platform.runLater(() -> {
                    taskStatusLabel.setText("Mixing audio…");
                    appendLog("OpenGL: mixing audio...");
                });
                FFmpegEdit.runAnyCommand(audioCmd, "OpenGL export — audio pass",
                        () -> {
                            Platform.runLater(() -> {
                                taskProgressBar.setProgress(0.92);
                                taskStatusLabel.setText("Finalizing…");
                                appendLog("OpenGL: muxing...");
                            });
                            String muxCmd = OpenGLEditNative.buildMuxCommand(videoOnlyPath, audioOnlyPath, finalPath);
                            FFmpegEdit.runAnyCommand(muxCmd, "OpenGL export — mux",
                                    () -> {
                                        cleanupOpenGlTemp(videoOnlyPath, audioOnlyPath, reversedClipPaths);
                                        Platform.runLater(() -> finishOpenGlExportSuccess(finalPath));
                                    },
                                    () -> {
                                        cleanupOpenGlTemp(videoOnlyPath, audioOnlyPath, reversedClipPaths);
                                        Platform.runLater(this::failOpenGlExport);
                                    },
                                    log -> Platform.runLater(() -> { if (logCheckBox.isSelected()) appendLog(log); }),
                                    stats -> {});
                        },
                        () -> {
                            cleanupOpenGlTemp(videoOnlyPath, audioOnlyPath, reversedClipPaths);
                            Platform.runLater(this::failOpenGlExport);
                        },
                        log -> Platform.runLater(() -> { if (logCheckBox.isSelected()) appendLog(log); }),
                        stats -> {});
            } catch (Exception e) {
                cleanupOpenGlTemp(videoOnlyPath, audioOnlyPath, reversedClipPaths);
                // Always shown in full, unlike routine "LOG "/"[stderr] " lines
                // above which respect logCheckBox - a failure's detail (often
                // the worker's own crash report for a native abort) shouldn't
                // be hidden behind that preference right when it's needed most.
                java.io.StringWriter stackTrace = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(stackTrace));
                String detail = "\n>>> OPENGL EXPORT ERROR <<<\n" + e.getMessage()
                        + "\n----- exception stack trace -----\n" + stackTrace
                        + "----------------------------------";
                Platform.runLater(() -> {
                    appendLog(detail);
                    failOpenGlExport();
                });
            } finally {
                activeOpenGlExport = null;
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private void finishOpenGlExportSuccess(String finalPath) {
        finishExportRendering();
        taskStatusLabel.setText("Export Completed! ✓");
        taskProgressBar.setProgress(1.0);
        appendLog("\n>>> EXPORT FINISHED SUCCESSFULLY <<<");
        appendLog("Location: " + finalPath);

        FileChooser fc = new FileChooser();
        fc.setTitle("Save Exported Video");
        fc.setInitialFileName(project.getProjectTitle() + "_export.mp4");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("MP4 Video", "*.mp4"));
        File userDest = fc.showSaveDialog(this);

        String shownPath = finalPath;
        if (userDest != null) {
            try {
                Files.move(Path.of(finalPath), userDest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                shownPath = userDest.getAbsolutePath();
            } catch (IOException e) {
                appendLog("Error moving file to destination: " + e.getMessage());
            }
        }

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(this);
        alert.setTitle("Export Complete");
        alert.setHeaderText("Success!");
        alert.setContentText("Your video has been exported to:\n" + shownPath);
        alert.showAndWait();
    }

    private void failOpenGlExport() {
        finishExportRendering();
        taskStatusLabel.setText("Export Failed ✗");
        appendLog("\n>>> EXPORT FAILED <<<");
    }

    private void cancelOpenGlExport() {
        finishExportRendering();
        taskStatusLabel.setText("Export Cancelled");
        taskProgressBar.setProgress(0);
        appendLog("\n>>> EXPORT CANCELLED <<<");
    }

    /** Removes every temp file an OpenGL export can leave behind, on any exit path. Null paths are skipped. */
    private void cleanupOpenGlTemp(String videoOnlyPath, String audioOnlyPath, java.util.Map<Clip, String> reversedClipPaths) {
        if (videoOnlyPath != null) deleteQuietly(videoOnlyPath);
        if (audioOnlyPath != null) deleteQuietly(audioOnlyPath);
        OpenGLEditNative.deleteReversedIntermediates(reversedClipPaths);
    }

    private void deleteQuietly(String path) {
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (IOException ignored) {
        }
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
            appendLog("Binary: " + FFmpegEditNative.getFfmpegPath() + "\n");

            String cmdEach = cmdAfterSplit[i];
            FFmpegEdit.runAnyCommand(
                    cmdEach,
                    "Exporting — " + project.getProjectTitle(),
                    (i == cmdAfterSplit.length - 1 ?
                            // onSuccess
                            () -> Platform.runLater(() -> {

                                if(asTemplate)
                                {
                                    File intermediateFile = new File(project.getProjectPath(), Constants.DEFAULT_EXPORT_CLIP_FILENAME);

                                    List<File> videoFiles = new ArrayList<>();
                                    for(Clip clip : timeline.getLockedForTemplateClip()) {
                                        videoFiles.add(new File(clip.getAbsolutePath(project)));
                                    }

                                    List<File> previewFiles = Arrays.asList(new File(IOHelper.CombinePath(project.getProjectPath(), "preview.png")),
                                            new File(IOHelper.CombinePath(project.getProjectPath(), "preview.mp4")));

                                    int totalClips =  timeline.getAllReplacementClipCount();

                                    new File(IOHelper.CombinePath(project.getProjectPath(), Constants.DEFAULT_EXPORT_CLIP_FILENAME))
                                            .renameTo(new File(IOHelper.CombinePath(project.getProjectPath(), "preview.mp4")));

                                    FFmpegEdit.RenderSettings renderSettings = new FFmpegEdit.RenderSettings(settings, timeline, new Clip[0], project, 0, false, true, false);
                                    String ffmpegCommand = FFmpegEdit.generateCmdFull(renderSettings);

                                    ArrayList<String> strVideoFiles = new ArrayList<>();
                                    for (File file : videoFiles) strVideoFiles.add(file.getAbsolutePath());

                                    ArrayList<String> strPreviewFiles = new ArrayList<>();
                                    for (File file : previewFiles) strPreviewFiles.add(file.getAbsolutePath());

                                    com.vanvatcorporation.doubleclips.ui.PostTemplateWindow.show(
                                            (Stage) ExportWindow.this.getOwner(),
                                            ffmpegCommand,
                                            totalClips,
                                            strVideoFiles,
                                            strPreviewFiles,
                                            project.getProjectTitle()
                                    );



                                    finishExportRendering();
                                    taskStatusLabel.setText("Export Completed! ✓");
                                    taskProgressBar.setProgress(1.0);
                                    appendLog("\n>>> EXPORT FINISHED SUCCESSFULLY <<<");
                                    appendLog("\nPassing to the uploader...");
                                }
                                else {

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
                                }

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
                            float progress = (float) Math.min(100.0, (double) progressMs * 100 / durationMs);
                            double fraction = progress / 100.0;
                            taskProgressBar.setProgress(fraction);

                            long totalFrames = (long) (durationMs / 1000.0 * settings.getFrameRate());
                            int frameNumber = stats.getVideoFrameNumber();
                            float fps = stats.getVideoFps();
                            String fpsStr = String.format(java.util.Locale.US, "%.2f", fps);
                            String percentStr = String.format(java.util.Locale.US, "%.1f", progress);
                            String taskName = FFmpegEdit.queue.currentRenderQueue != null
                                    ? FFmpegEdit.queue.currentRenderQueue.taskName
                                    : "Exporting";
                            taskStatusLabel.setText(
                                    new StringBuilder()
                                            .append(taskName)
                                            .append("...")
                                            .append(" (")
                                            .append(frameNumber)
                                            .append("/")
                                            .append(totalFrames)
                                            .append(" frames - ")
                                            .append(fpsStr)
                                            .append(" frames per second)")
                                            .append(" (")
                                            .append(percentStr)
                                            .append("%)")                                            
                                            .toString());
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





    // ─────────────────────────────────────────────────────────────────────────
    //  Export Upload helpers
    // ─────────────────────────────────────────────────────────────────────────


    public interface UploadCallback {
        void onResult(boolean success, String result);
        void onProgress(int progress);
    }
    public static class CountingRequestBody extends RequestBody {
        protected RequestBody delegate;
        protected UploadCallback listener;
        protected CountingSink countingSink;

        public CountingRequestBody(RequestBody delegate, UploadCallback listener) {
            this.delegate = delegate;
            this.listener = listener;
        }

        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() {
            try {
                return delegate.contentLength();
            } catch (IOException e) {
                return -1;
            }
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            countingSink = new CountingSink(sink);
            BufferedSink bufferedSink = Okio.buffer(countingSink);
            delegate.writeTo(bufferedSink);
            bufferedSink.flush();
        }

        protected final class CountingSink extends ForwardingSink {
            private long bytesWritten = 0;
            private long contentLength = 0;

            public CountingSink(Sink delegate) {
                super(delegate);
            }

            @Override
            public void write(Buffer source, long byteCount) throws IOException {
                super.write(source, byteCount);
                bytesWritten += byteCount;
                if (listener != null) {
                    if (contentLength == 0) contentLength = contentLength();
                    if(contentLength > 0) {
                        int progress = (int) ((bytesWritten * 100) / contentLength);
                        listener.onProgress(progress);
                    }
                }
            }
        }
    }

    public static void uploadTemplateNecessityItems(Context context,
                                                    String serverUrl,
                                                    Map<String, String> fields,
                                                    List<File> videoFiles,
                                                    List<File> previewFiles,
                                                    UploadCallback callback) {
        OkHttpClient client = new OkHttpClient();
        try {
            // Build multipart body
            MultipartBody.Builder multipartBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM);

            // Add text fields
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                multipartBuilder.addFormDataPart(entry.getKey(), entry.getValue());
            }

            // Add video files
            if (videoFiles != null) {
                for (File file : videoFiles) {
                    RequestBody fileBody = RequestBody.create(file, MediaType.parse("video/mp4"));
                    multipartBuilder.addFormDataPart("videoFiles", file.getName(), fileBody);
                }
            }

            // Add preview files
            if (previewFiles != null) {
                for (File file : previewFiles) {
                    RequestBody fileBody = RequestBody.create(file, MediaType.parse("video/mp4"));
                    multipartBuilder.addFormDataPart("previewFiles", file.getName(), fileBody);
                }
            }

            RequestBody requestBody = multipartBuilder.build();
            CountingRequestBody countingBody = new CountingRequestBody(requestBody, callback);

            // Build request
            Request request = new Request.Builder()
                    .url(serverUrl)
                    .post(countingBody)
                    .build();

            // Execute asynchronously
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    LoggingManager.LogExceptionToNoteOverlay(context, e);
                    if (callback != null) {
                        callback.onResult(false, e.getMessage());
                    }
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        String responseBody = response.body().string();
                        LoggingManager.LogToToast(context, "Server returned OK status: " + responseBody);
                        System.err.println(responseBody);
                        if (callback != null) {
                            callback.onResult(true, responseBody);
                        }
                    } else {
                        LoggingManager.LogToToast(context, "Server returned non-OK status: " + response.code());
                        if (callback != null) {
                            callback.onResult(false, "Server returned non-OK status: " + response.code());
                        }
                    }
                }
            });

        } catch (Exception e) {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
            if (callback != null) {
                callback.onResult(false, e.getMessage());
            }
        }
    }
}
