package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.i18n.I18n;
import com.vanvatcorporation.doubleclips.ExportEstimate;
import javafx.scene.layout.GridPane;

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
    private final RadioButton ffmpegEngineRadio = new RadioButton(I18n.t("FFmpeg (CPU)"));
    private final RadioButton openGlEngineRadio = new RadioButton(I18n.t("OpenGL (GPU) — Recommended"));

    // ── Advanced section ────────────────────────────────────────────────────
    private final TextArea commandTextArea = new TextArea();

    // ── Log / Progress section ───────────────────────────────────────────────
    private final Label      taskStatusLabel  = new Label(I18n.t("Current Task: 0%"));
    private final ProgressBar taskProgressBar  = new ProgressBar(0);
    private final Label      globalStatusLabel = new Label(I18n.t("Remaining Tasks: 0 / 0"));
    private final ProgressBar globalProgressBar = new ProgressBar(0);
    private final TextArea   logTextArea      = new TextArea();
    private final CheckBox   logCheckBox      = new CheckBox(I18n.t("Enable Log"));
    private final CheckBox   truncateCheckBox = new CheckBox(I18n.t("Truncate Log"));
    private final CheckBox   scrollLockCheckBox = new CheckBox(I18n.t("Scroll Lock"));

    // ── Action buttons ────────────────────────────────────────────────────────
    private final Button exportButton           = new Button(I18n.t("Export"));
    private final Button exportAsTemplateButton = new Button(I18n.t("Export as Template"));

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean isExporting = false;

    // ─────────────────────────────────────────────────────────────────────────
    //  Factory / Show
    // ─────────────────────────────────────────────────────────────────────────

    /** Open (or replace) the singleton export window. */
    public static ExportWindow show(Stage owner, ProjectData project, Timeline timeline, VideoSettings settings) {
        ExportWindow win = new ExportWindow(owner, project, timeline, settings);
        win.show();
        return win;   // callers that need to know when it closes (template export) hook setOnHidden
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
                buildSection(I18n.t("Output"), buildOutputPane()),
                buildSection(I18n.t("Advanced"), buildAdvancedPane()),
                buildSection(I18n.t("Log & Progress"), buildLogPane())
        );

        scroll.setContent(body);
        root.getChildren().add(scroll);

        // ── Scene ──────────────────────────────────────────────────────────
        refreshOutputSummary();
        focusedProperty().addListener((obs, was, focused) -> { if (focused) refreshOutputSummary(); });

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

        Label title = new Label(I18n.t("Export"));
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Export as Template
        exportAsTemplateButton.setGraphic(new FontIcon(MaterialDesignU.UPLOAD_OUTLINE));
        exportAsTemplateButton.getStyleClass().add("export-template-button");
        exportAsTemplateButton.setOnAction(e -> {
            if(AuthRepository.getInstance().getCurrentUser() == null) {
                new AlertDialog.Builder().setTitle(I18n.t("Login required")).setMessage(I18n.t("Please login to post template")).create().show();
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
            gapAlert.setTitle(I18n.t("Some animation effects are not available"));
            gapAlert.setHeaderText(null);
            gapAlert.setContentText(gapMessage.toString());
            ButtonType continueFfmpeg = new ButtonType("Continue with FFmpeg");
            gapAlert.getButtonTypes().setAll(continueFfmpeg, ButtonType.CANCEL);
            gapAlert.showAndWait().ifPresent(choice -> {
                if (choice == continueFfmpeg) exportClip(exportAsTemplate);
            });
            return;
        }

        // Desktop's OpenGL workers draw text (TextLayoutEngine), so text clips are not a gap here.
        OpenGLEdit.textRenderingAvailable = true;
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
        alert.setTitle(I18n.t("OpenGL export not complete yet"));
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
     * "Export as Template" runs through here too: the video it renders is the
     * template's preview (the template itself is the timeline file plus a
     * placeholder FFmpeg command, see {@link #finishTemplateExport()}).
     */
    private void exportClipViaOpenGl(boolean exportAsTemplate) {

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
                Platform.runLater(() -> taskStatusLabel.setText(I18n.t("Preparing clips…")));
                reversedClipPaths.putAll(OpenGLEditNative.renderReversedIntermediates(timeline, project, gl::isCancelled, listener));
                if (gl.isCancelled()) {
                    cleanupOpenGlTemp(videoOnlyPath, audioOnlyPath, reversedClipPaths);
                    Platform.runLater(this::cancelOpenGlExport);
                    return;
                }

                Platform.runLater(() -> taskStatusLabel.setText(I18n.t("Compositing video (OpenGL)…")));
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
                    Platform.runLater(() -> finishOpenGlExportSuccess(finalPath, exportAsTemplate));
                    return;
                }

                Platform.runLater(() -> {
                    taskStatusLabel.setText(I18n.t("Mixing audio…"));
                    appendLog("OpenGL: mixing audio...");
                });
                FFmpegEdit.runAnyCommand(audioCmd, "OpenGL export — audio pass",
                        () -> {
                            Platform.runLater(() -> {
                                taskProgressBar.setProgress(0.92);
                                taskStatusLabel.setText(I18n.t("Finalizing…"));
                                appendLog("OpenGL: muxing...");
                            });
                            String muxCmd = OpenGLEditNative.buildMuxCommand(videoOnlyPath, audioOnlyPath, finalPath);
                            FFmpegEdit.runAnyCommand(muxCmd, "OpenGL export — mux",
                                    () -> {
                                        cleanupOpenGlTemp(videoOnlyPath, audioOnlyPath, reversedClipPaths);
                                        Platform.runLater(() -> finishOpenGlExportSuccess(finalPath, exportAsTemplate));
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
                // The settings have no upper limit, so a canvas / frame rate the machine can't handle ends up here: say so in
                // plain words (the stack trace stays for the rest).
                final OpenGlExportDiagnosis.Advice advice = OpenGlExportDiagnosis.diagnose(width, height, frameRate,
                        e.getClass().getName() + " " + e.getMessage() + "\n" + stackTrace);
                String detail = "\n>>> OPENGL EXPORT ERROR <<<\n" + e.getMessage()
                        + (advice == null ? "" : "\n\n>>> LIKELY CAUSE: " + advice.summary + "\n>>> WHAT TO DO: " + advice.fix)
                        + "\n----- exception stack trace -----\n" + stackTrace
                        + "----------------------------------";
                Platform.runLater(() -> {
                    appendLog(detail);
                    failOpenGlExport();
                    if (advice != null) showOpenGlFailureAdvice(advice);
                });
            } finally {
                activeOpenGlExport = null;
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * The second half of "Export as Template", for BOTH engines (the FFmpeg and the OpenGL export call it once the preview
     * video has been rendered to the project's export file): the preview becomes preview.mp4, the template's own FFmpeg
     * command (placeholder marks instead of real files; the phones render a template with it, the desktop renders it from
     * the timeline file) is generated, and the uploader opens with the replaceable clips, the previews and the timeline.
     * Runs on the FX thread. Stops with a message, instead of uploading something half made, when the preview can't be put
     * in place or the command can't be generated.
     */
    private void finishTemplateExport() {
        File exported = new File(project.getProjectPath(), Constants.DEFAULT_EXPORT_CLIP_FILENAME);
        File preview = new File(project.getProjectPath(), Constants.DEFAULT_PREVIEW_CLIP_FILENAME);
        try {
            // File.renameTo gives up silently when preview.mp4 is already there (Windows), leaving the last upload's preview.
            Files.move(exported.toPath(), preview.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            appendLog("Couldn't prepare the preview video: " + e.getMessage());
            failTemplateExport();
            return;
        }

        String ffmpegCommand;
        try {
            FFmpegEdit.RenderSettings renderSettings = new FFmpegEdit.RenderSettings(settings, timeline, new Clip[0], project, 0, false, true, false);
            ffmpegCommand = FFmpegEdit.generateCmdFull(renderSettings);
        } catch (Exception e) {
            appendLog("Couldn't generate the template command: " + e.getMessage());
            failTemplateExport();
            return;
        }

        ArrayList<String> strVideoFiles = new ArrayList<>();
        for (Clip clip : timeline.getLockedForTemplateClip()) strVideoFiles.add(new File(clip.getAbsolutePath(project)).getAbsolutePath());

        ArrayList<String> strPreviewFiles = new ArrayList<>();
        strPreviewFiles.add(new File(IOHelper.CombinePath(project.getProjectPath(), "preview.png")).getAbsolutePath());
        strPreviewFiles.add(preview.getAbsolutePath());

        String timelineFilePath = IOHelper.CombinePath(project.getProjectPath(), Constants.DEFAULT_TIMELINE_FILENAME);

        finishExportRendering();
        taskStatusLabel.setText(I18n.t("Export Completed! ✓"));
        taskProgressBar.setProgress(1.0);
        appendLog("\n>>> EXPORT FINISHED SUCCESSFULLY <<<");
        appendLog("\nPassing to the uploader...");

        com.vanvatcorporation.doubleclips.ui.PostTemplateWindow.show(
                (Stage) ExportWindow.this.getOwner(),
                ffmpegCommand,
                timeline.getAllReplacementClipCount(),
                strVideoFiles,
                strPreviewFiles,
                timelineFilePath,
                project.getProjectTitle()
        );
    }

    private void failTemplateExport() {
        finishExportRendering();
        taskStatusLabel.setText(I18n.t("Export Failed ✗"));
        appendLog("\n>>> TEMPLATE EXPORT FAILED <<<");
    }

    private void finishOpenGlExportSuccess(String finalPath, boolean asTemplate) {

        if (asTemplate) {
            finishTemplateExport();
        }
        else {
            finishExportRendering();
            notifyExportSucceeded();
            taskStatusLabel.setText(I18n.t("Export Completed! ✓"));
            taskProgressBar.setProgress(1.0);
            appendLog("\n>>> EXPORT FINISHED SUCCESSFULLY <<<");
            appendLog("Location: " + finalPath);

            FileChooser fc = new FileChooser();
            fc.setTitle(I18n.t("Save Exported Video"));
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
            alert.setTitle(I18n.t("Export Complete"));
            alert.setHeaderText(I18n.t("Success!"));
            alert.setContentText("Your video has been exported to:\n" + shownPath);
            alert.showAndWait();
        }


    }

    /**
     * The popup for a failure the user can fix in the video settings (the full reason is already in the log): what probably
     * happened, what to change, and a button straight into Video Settings.
     */
    private void showOpenGlFailureAdvice(OpenGlExportDiagnosis.Advice advice) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(this);
        alert.setTitle(I18n.t("OpenGL export failed"));
        alert.setHeaderText(I18n.t("The video could not be exported"));
        alert.setContentText(advice.summary + "\n\n" + advice.fix + "\n\nThe full details are in the log.");
        javafx.scene.control.ButtonType settingsButton = new javafx.scene.control.ButtonType("Video Settings…",
                javafx.scene.control.ButtonBar.ButtonData.LEFT);
        alert.getButtonTypes().setAll(settingsButton, javafx.scene.control.ButtonType.CLOSE);
        java.util.Optional<javafx.scene.control.ButtonType> answer = alert.showAndWait();
        if (answer.isPresent() && answer.get() == settingsButton) openVideoSettings();
    }

    private void failOpenGlExport() {
        finishExportRendering();
        taskStatusLabel.setText(I18n.t("Export Failed ✗"));
        appendLog("\n>>> EXPORT FAILED <<<");
    }

    private void cancelOpenGlExport() {
        finishExportRendering();
        taskStatusLabel.setText(I18n.t("Export Cancelled"));
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
    //  Output summary (what will be made, and how big)
    // ─────────────────────────────────────────────────────────────────────────

    private final Label outputResolution = new Label();
    private final Label outputFrameRate = new Label();
    private final Label outputQualityName = new Label();
    private final Label outputQuality = new Label();
    private final Label outputDuration = new Label();
    private final Label outputEstimate = new Label();
    private final Label outputEstimateNote = new Label();

    /** The numbers the export will use and the estimated size of the file (like the iOS export screen). */
    private javafx.scene.Node buildOutputPane() {
        GridPane grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(8);
        int row = 0;
        row = addOutputRow(grid, row, new Label(I18n.t("Resolution")), outputResolution);
        row = addOutputRow(grid, row, new Label(I18n.t("Frame rate")), outputFrameRate);
        row = addOutputRow(grid, row, outputQualityName, outputQuality);
        row = addOutputRow(grid, row, new Label(I18n.t("Duration")), outputDuration);
        row = addOutputRow(grid, row, new Label(I18n.t("Format")), new Label("MP4 \u00B7 H.264 + AAC"));
        Label estimateName = new Label(I18n.t("Estimated size"));
        outputEstimate.setStyle("-fx-font-weight: bold;");
        addOutputRow(grid, row, estimateName, outputEstimate);

        outputEstimateNote.setWrapText(true);
        outputEstimateNote.getStyleClass().add("text-muted");
        outputEstimateNote.setStyle("-fx-font-size: 11px;");
        return new VBox(8, grid, outputEstimateNote);
    }

    private static int addOutputRow(GridPane grid, int row, Label name, Label value) {
        name.getStyleClass().add("text-muted");
        grid.add(name, 0, row);
        grid.add(value, 1, row);
        return row + 1;
    }

    /** Re-reads the video settings and the timeline length: called when the window opens, regains focus or the settings change. */
    private void refreshOutputSummary() {
        boolean hardware = false;
        try {
            hardware = settings.isUseHardwareAccel() && FFmpegEditNative.getHardwareAcceleratedName() != null;
        } catch (RuntimeException ignored) {
            // no way to probe the encoders: assume the software one
        }
        ExportEstimate estimate = ExportEstimate.of(settings.videoWidth, settings.videoHeight, settings.frameRate,
                settings.getCRF(), settings.getBitrate(), hardware, timeline.duration);
        outputResolution.setText(settings.videoWidth + " \u00D7 " + settings.videoHeight);
        outputFrameRate.setText(settings.frameRate + " fps");
        outputQualityName.setText(I18n.t(hardware ? "Bitrate (HW)" : "Quality (CRF)"));
        outputQuality.setText(hardware ? settings.getBitrate() + " Mbps" : String.valueOf(settings.getCRF()));
        outputDuration.setText(ExportEstimate.formatDuration(timeline.duration));
        outputEstimate.setText("\u2248 " + ExportEstimate.formatBytes(estimate.bytes));
        outputEstimateNote.setText(estimate.isRough()
                ? I18n.t("The encoder chooses the bitrate, so this depends on the content.") : "");
        outputEstimateNote.setManaged(estimate.isRough());
        outputEstimateNote.setVisible(estimate.isRough());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Advanced pane
    // ─────────────────────────────────────────────────────────────────────────

    private javafx.scene.Node buildAdvancedPane() {
        VBox pane = new VBox(10);

        HBox btns = new HBox(8);

        Button genCmdBtn = new Button(I18n.t("Generate Command"));
        genCmdBtn.setGraphic(new FontIcon(MaterialDesignC.CODE_TAGS));
        genCmdBtn.getStyleClass().add("secondary-action-button");
        genCmdBtn.setOnAction(e -> commandTextArea.setText(generateCommand()));

        Button genTemplateCmdBtn = new Button(I18n.t("Generate Template Command"));
        genTemplateCmdBtn.setGraphic(new FontIcon(MaterialDesignC.CODE_TAGS_CHECK));
        genTemplateCmdBtn.getStyleClass().add("secondary-action-button");
        genTemplateCmdBtn.setOnAction(e -> generateTemplateCommand());

        btns.getChildren().addAll(genCmdBtn, genTemplateCmdBtn);

        commandTextArea.setPromptText(I18n.t("FFmpeg command will appear here…"));
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
        VideoSettingsDialog.Change change = VideoSettingsDialog.show(this, settings);
        if (change == null) return;
        appendLog("Video settings updated.");
        ProjectRepository.getInstance().saveVideoSettings(project, settings);
        refreshOutputSummary();
        // The editor under this window holds the same settings: it rebuilds its canvas / ruler now, not at the next visit.
        if (getOwner() instanceof EditorWindow editor) editor.onVideoSettingsChanged(change);
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

                                if (asTemplate) {
                                    finishTemplateExport();
                                }
                                else {

                                    // Intermediate file in project folder
                                    File intermediateFile = new File(project.getProjectPath(), Constants.DEFAULT_EXPORT_CLIP_FILENAME);

                                    // Ask for output file location
                                    FileChooser fc = new FileChooser();
                                    fc.setTitle(I18n.t("Save Exported Video"));
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
                                    notifyExportSucceeded();
                                    taskStatusLabel.setText(I18n.t("Export Completed! ✓"));
                                    taskProgressBar.setProgress(1.0);
                                    appendLog("\n>>> EXPORT FINISHED SUCCESSFULLY <<<");
                                    appendLog("Location: " + finalPath);

                                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                                    alert.initOwner(this);
                                    alert.setTitle(I18n.t("Export Complete"));
                                    alert.setHeaderText(I18n.t("Success!"));
                                    alert.setContentText("Your video has been exported to:\n" + finalPath);
                                    alert.showAndWait();
                                }

                            }) : () -> {}),

                    // onFail
                    () -> Platform.runLater(() -> {
                        finishExportRendering();
                        taskStatusLabel.setText(I18n.t("Export Failed ✗"));
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
        taskStatusLabel.setText(I18n.t("Preparing export…"));
        globalStatusLabel.setText(I18n.t("Running…"));
    }

    /** Set by a caller that wants to know when a render finished successfully (e.g. "Use template" counts a use). */
    private Runnable onExportSucceeded;

    public void setOnExportSucceeded(Runnable r) { this.onExportSucceeded = r; }

    private void notifyExportSucceeded() {
        if (onExportSucceeded != null) onExportSucceeded.run();
    }

    private void finishExportRendering() {
        isExporting = false;
        exportButton.setDisable(false);
        exportAsTemplateButton.setDisable(false);
        globalStatusLabel.setText(I18n.t("Done"));
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
