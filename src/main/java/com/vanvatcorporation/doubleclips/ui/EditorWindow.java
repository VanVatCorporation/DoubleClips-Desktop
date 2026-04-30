package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.data.*;
import com.vanvatcorporation.doubleclips.data.editing.*;
import com.vanvatcorporation.doubleclips.ui.renderer.TimelineRenderer;
import com.vanvatcorporation.doubleclips.helper.MediaHelper;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.FFmpegEdit;
import javafx.stage.FileChooser;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import javafx.concurrent.Task;
import javafx.stage.Modality;
import java.util.concurrent.CountDownLatch;
import javafx.application.Platform;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

public class EditorWindow extends Stage {

    private final ProjectData project;
    private Timeline timeline;
    private VideoSettings videoSettings;
    
    private TimelineRenderer timelineRenderer;

    // Editor State
    private float currentTime = 0f;
    private boolean isPlaying = false;
    private float pixelsPerSecond = 100f; // 100px = 1s
    private final float TRACK_HEIGHT = 70f;
    private final float TRACK_SPACING = 5f;

    private Clip selectedClip;
    private Track selectedTrack;

    // Playback engine
    private AnimationTimer playbackTimer;
    private long lastTimerUpdate = 0;

    // UI Components for logic access
    private final Label currentTimeLabel = new Label("00:00:00:00");
    private final Label durationLabel = new Label("00:00:00:00");
    private final Pane tracksPane = new Pane();
    private final VBox trackHeadersContainer = new VBox(0);
    private final VBox propertiesContent = new VBox(15);
    private Line playheadLine;
    private Line ghostPlayheadLine;
    private float tempTime = -1;
    private Slider zoomSlider;

    // --- Scroll sync ---
    private final ScrollPane rulerScrollPane = new ScrollPane();
    private final ScrollPane tracksScrollPane = new ScrollPane();

    // --- Drag & Drop ---
    private static final double SNAP_THRESHOLD = 8.0;

    /** Mutable drag state — one active drag at a time. */
    private static class DragContext {
        Clip      clip;
        ClipNode  ghost;          // semi-transparent clone in tracksPane
        int       currentTrackIdx;
        double    dragOffsetX;    // mouse X offset from clip left edge
        boolean   dragging;       // becomes true once mouse moves > 0 px
        boolean   isNewClip;      // true if dragging from media browser
    }
    private final DragContext activeDrag = new DragContext();

    public EditorWindow(ProjectData project) {
        this.project = project;
        this.setTitle(project.getProjectTitle() + " — DoubleClips");
        this.setWidth(1440);
        this.setHeight(900);
        this.setMinWidth(1024);
        this.setMinHeight(640);

        // Load persistences early
        this.timeline = ProjectRepository.getInstance().loadTimeline(project);
        this.videoSettings = ProjectRepository.getInstance().loadVideoSettings(project);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("editor-root");

        // === Main 3-column middle section ===
        HBox centerRow = new HBox();
        HBox.setHgrow(centerRow, Priority.ALWAYS);

        VBox leftPanel  = buildLeftPanel();
        VBox previewPanel = buildPreviewPanel();
        VBox rightPanel = buildRightPanel();

        HBox.setHgrow(previewPanel, Priority.ALWAYS);
        centerRow.getChildren().addAll(leftPanel, previewPanel, rightPanel);

        // === Bottom Timeline ===
        VBox timelineArea = buildTimelineArea();

        root.setTop(buildTopBar());
        root.setCenter(centerRow);
        root.setBottom(timelineArea);

        Scene scene = new Scene(root);
        scene.getStylesheets().add(DoubleClipsDesktop.class.getResource("/style.css").toExternalForm());

        this.setScene(scene);
        this.setOnCloseRequest(e -> {
            stopPlayback();
            saveProject();
            DoubleClipsDesktop.getInstance().closeEditor(this);
        });

        initPlaybackTimer();
        
        if (this.timeline.tracks.isEmpty()) {
            addNewTrack("Video 1");
            addNewTrack("Audio 1");
        } else {
            // Rebuild sidebar headers
            for (Track t : timeline.tracks) {
                trackHeadersContainer.getChildren().add(buildTrackHeader("Track " + (t.timelineIndex + 1)));
            }
            refreshTimelineUI();
        }
    }

    private void saveProject() {
        ProjectRepository.getInstance().saveTimeline(project, timeline, videoSettings);
    }

    private void initPlaybackTimer() {
        playbackTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (lastTimerUpdate > 0) {
                    long deltaNs = now - lastTimerUpdate;
                    float deltaSec = deltaNs / 1_000_000_000f;
                    updateCurrentTime(currentTime + deltaSec);
                }
                lastTimerUpdate = now;
            }
        };
    }

    private void startPlayback() {
        if (isPlaying) return;
        isPlaying = true;
        lastTimerUpdate = System.nanoTime();
        playbackTimer.start();
        // Update play/pause button icon if needed
    }

    private void stopPlayback() {
        if (!isPlaying) return;
        isPlaying = false;
        playbackTimer.stop();
        lastTimerUpdate = 0;
        
        // Notify the renderer that we have paused
        if (timelineRenderer != null) {
            timelineRenderer.updateTime(currentTime, true);
        }
    }

    private void updateCurrentTime(float newTime) {
        this.currentTime = Math.max(0, newTime);
        currentTimeLabel.setText(formatTimecode(currentTime));
        
        // Update playhead position
        updatePlayheadPosition();

        if (timelineRenderer != null) {
            timelineRenderer.updateTime(currentTime, !isPlaying);
        }

        // Auto-scroll if playing
        if (isPlaying) {
            double playheadX = currentTime * pixelsPerSecond;
            double scrollWidth = tracksScrollPane.getContent().getBoundsInLocal().getWidth();
            double viewportWidth = tracksScrollPane.getViewportBounds().getWidth();
            
            if (playheadX > viewportWidth * 0.8) {
                // Simple auto-scroll logic
                tracksScrollPane.setHvalue(playheadX / scrollWidth);
            }
        }
    }

    private void updatePlayheadPosition() {
        if (playheadLine == null || tracksScrollPane == null || tracksPane == null) return;
        
        double contentWidth = tracksPane.getBoundsInLocal().getWidth();
        double viewportWidth = tracksScrollPane.getViewportBounds().getWidth();
        double hValue = tracksScrollPane.getHvalue();
        
        // scrollX is the pixel offset of the left edge of the viewport
        double scrollX = hValue * (contentWidth - viewportWidth);
        
        // Position relative to viewport left edge
        playheadLine.setTranslateX(currentTime * pixelsPerSecond - scrollX);

        if (tempTime >= 0) {
            ghostPlayheadLine.setTranslateX(tempTime * pixelsPerSecond - scrollX);
        }
    }

    private String formatTimecode(float seconds) {
        int h = (int)(seconds / 3600);
        int m = (int)((seconds % 3600) / 60);
        int s = (int)(seconds % 60);
        int f = (int)((seconds % 1) * 30); // 30fps assumption for display
        return String.format("%02d:%02d:%02d:%02d", h, m, s, f);
    }

    // ====================================================================
    //  TOP BAR
    // ====================================================================
    private HBox buildTopBar() {
        HBox bar = new HBox(10);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 16, 8, 16));
        bar.getStyleClass().add("editor-topbar");

        Button backBtn = new Button();
        backBtn.setGraphic(new FontIcon(MaterialDesignK.KEYBOARD_RETURN));
        backBtn.getStyleClass().add("button-transparent");
        backBtn.setOnAction(e -> DoubleClipsDesktop.getInstance().closeEditor(this));

        Label title = new Label(project.getProjectTitle());
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Undo / Redo at top (like CapCut)
        Button undoBtn = new Button();
        undoBtn.setGraphic(new FontIcon(MaterialDesignU.UNDO));
        undoBtn.getStyleClass().add("button-transparent");

        Button redoBtn = new Button();
        redoBtn.setGraphic(new FontIcon(MaterialDesignR.REDO));
        redoBtn.getStyleClass().add("button-transparent");

        Region spacer2 = new Region();
        spacer2.setPrefWidth(32);

        Button exportBtn = new Button("Export");
        exportBtn.getStyleClass().add("export-button");
        exportBtn.setGraphic(new FontIcon(MaterialDesignU.UPLOAD_OUTLINE));
        exportBtn.setOnAction(e -> ExportWindow.show(this, project, timeline, videoSettings));

        bar.getChildren().addAll(backBtn, title, spacer, undoBtn, redoBtn, spacer2, exportBtn);
        return bar;
    }

    // ====================================================================
    //  LEFT PANEL — Media Browser
    // ====================================================================
    private VBox buildLeftPanel() {
        VBox panel = new VBox();
        panel.setPrefWidth(320);
        panel.setMinWidth(240);
        panel.setMaxWidth(400);
        panel.getStyleClass().add("editor-left-panel");

        // --- Tool tab strip
        String[] tabLabels = {"Media", "Audio", "Text", "Stickers", "Effects", "Transitions"};
        HBox tabStrip = new HBox(0);
        tabStrip.getStyleClass().add("editor-tab-strip");
        ToggleGroup tabGroup = new ToggleGroup();

        for (String tab : tabLabels) {
            ToggleButton tb = new ToggleButton(tab);
            tb.setToggleGroup(tabGroup);
            tb.getStyleClass().add("editor-tab");
            HBox.setHgrow(tb, Priority.ALWAYS);
            tb.setMaxWidth(Double.MAX_VALUE);
            tabStrip.getChildren().add(tb);
        }
        ((ToggleButton) tabStrip.getChildren().get(0)).setSelected(true);

        // --- Media import bar
        HBox importBar = new HBox(8);
        importBar.setPadding(new Insets(10, 10, 10, 10));
        importBar.setAlignment(Pos.CENTER_LEFT);
        importBar.getStyleClass().add("editor-import-bar");

        Button importBtn = new Button("Import");
        importBtn.setGraphic(new FontIcon(MaterialDesignP.PLUS));
        importBtn.getStyleClass().add("import-media-button");

        Region ibSpacer = new Region();
        HBox.setHgrow(ibSpacer, Priority.ALWAYS);

        Button searchBtn = new Button();
        searchBtn.setGraphic(new FontIcon(MaterialDesignM.MAGNIFY));
        searchBtn.getStyleClass().add("button-transparent");

        importBar.getChildren().addAll(importBtn, ibSpacer, searchBtn);

        // --- Media grid (placeholder)
        FlowPane mediaGrid = new FlowPane(8, 8);
        mediaGrid.setPadding(new Insets(8));
        VBox.setVgrow(mediaGrid, Priority.ALWAYS);
        mediaGrid.getStyleClass().add("media-grid");

        Label emptyLabel = new Label("No media yet.\nClick Import to add files.");
        emptyLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-text-alignment: center;");
        emptyLabel.setAlignment(Pos.CENTER);
        mediaGrid.getChildren().add(emptyLabel);

        importBtn.setOnAction(e -> handleImportMedia(mediaGrid));

        loadMediaGrid(mediaGrid);

        ScrollPane mediaGridScroll = new ScrollPane(mediaGrid);
        mediaGridScroll.setFitToWidth(true);
        mediaGridScroll.getStyleClass().add("edge-to-edge");
        mediaGridScroll.setStyle("-fx-background-color: transparent; -fx-control-inner-background: transparent; -fx-border-color: transparent;");
        VBox.setVgrow(mediaGridScroll, Priority.ALWAYS);

        panel.getChildren().addAll(tabStrip, importBar, mediaGridScroll);
        return panel;
    }

    private void loadMediaGrid(FlowPane mediaGrid) {
        String clipDir = IOHelper.CombinePath(project.getProjectPath(), Constants.DEFAULT_CLIP_DIRECTORY);
        File dir = new File(clipDir);
        if (!dir.exists() || !dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;

        Task<List<Clip>> task = new Task<>() {
            @Override
            protected List<Clip> call() throws Exception {
                List<Clip> loadedClips = new ArrayList<>();
                for (File f : files) {
                    if (f.isDirectory() || f.getName().startsWith(".")) continue;
                    
                    String filename = f.getName();
                    String mime = Files.probeContentType(f.toPath());
                    ClipType type = ClipType.VIDEO;
                    if (mime != null) {
                        if (mime.startsWith("audio")) type = ClipType.AUDIO;
                        else if (mime.startsWith("image")) type = ClipType.IMAGE;
                    } else {
                        if (filename.endsWith(".mp3") || filename.endsWith(".wav")) type = ClipType.AUDIO;
                        else if (filename.endsWith(".png") || filename.endsWith(".jpg")) type = ClipType.IMAGE;
                    }
                    
                    Clip clip = new Clip(filename, 0, 0, 0, type, false, 0, 0);
                    loadedClips.add(clip);
                }
                return loadedClips;
            }
        };

        task.setOnSucceeded(e -> {
            for (Clip c : task.getValue()) {
                addClipToMediaGrid(mediaGrid, c);
            }
        });
        
        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
    }

    private void handleImportMedia(FlowPane mediaGrid) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Media");
        List<File> files = chooser.showOpenMultipleDialog(this);
        if (files == null || files.isEmpty()) return;

        Dialog<ButtonType> progressDialog = new Dialog<>();
        progressDialog.setTitle("Processing Media");
        progressDialog.initOwner(this);
        progressDialog.initModality(Modality.WINDOW_MODAL);
        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        progressDialog.getDialogPane().lookupButton(ButtonType.CANCEL).setVisible(false);

        VBox content = new VBox(10);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(20));
        Label desc = new Label("Processing...");
        ProgressIndicator progress = new ProgressIndicator();
        content.getChildren().addAll(desc, progress);
        progressDialog.getDialogPane().setContent(content);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                for (int i = 0; i < files.size(); i++) {
                    File f = files.get(i);
                    updateMessage("Processing: " + f.getName());

                    String filename = f.getName();
                    String clipDir = IOHelper.CombinePath(project.getProjectPath(), Constants.DEFAULT_CLIP_DIRECTORY);
                    File clipDirFile = new File(clipDir);
                    if (!clipDirFile.exists()) clipDirFile.mkdirs();

                    File targetFile = new File(clipDir, filename);
                    Files.copy(f.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                    MediaHelper.MediaInfo info = MediaHelper.probeMediaInfo(targetFile.getAbsolutePath());

                    String mime = Files.probeContentType(targetFile.toPath());
                    ClipType type = ClipType.VIDEO;
                    if (mime != null) {
                        if (mime.startsWith("audio")) type = ClipType.AUDIO;
                        else if (mime.startsWith("image")) type = ClipType.IMAGE;
                    } else {
                        if (filename.endsWith(".mp3") || filename.endsWith(".wav")) type = ClipType.AUDIO;
                        else if (filename.endsWith(".png") || filename.endsWith(".jpg")) type = ClipType.IMAGE;
                    }

                    Clip clip = new Clip(filename, 0, info.duration, 0, type, info.hasAudio, info.width, info.height);

                    String previewDir = IOHelper.CombinePath(project.getProjectPath(), Constants.DEFAULT_PREVIEW_CLIP_DIRECTORY);
                    File previewDirFile = new File(previewDir);
                    if (!previewDirFile.exists()) previewDirFile.mkdirs();

                    String previewClipPath = IOHelper.CombinePath(previewDir, filename);

                    CountDownLatch latch = new CountDownLatch(1);

                    if (type == ClipType.VIDEO) {
                        CountDownLatch thumbLatch = new CountDownLatch(1);
                        String cmdThumb = "-i \"" + targetFile.getAbsolutePath() + "\" -vframes 1 -s 128x128 -y \"" + previewClipPath + ".jpg\"";
                        FFmpegEdit.runAnyCommand(cmdThumb, "Preview Thumb",
                            () -> thumbLatch.countDown(),
                            () -> thumbLatch.countDown(),
                            log -> {}, stats -> {});
                        thumbLatch.await();

                        String cmd = "-i \"" + targetFile.getAbsolutePath() + "\" -vf \"scale=1280:-2\" -c:v libx264 -preset ultrafast -crf 32 -x264-params keyint=1 -an -y \"" + previewClipPath + "\"";
                        FFmpegEdit.runAnyCommand(cmd, "Preview Video",
                            () -> latch.countDown(),
                            () -> latch.countDown(),
                            log -> {}, stats -> {
                                if (stats.getTimeInMs() > 0 && info.duration > 0) {
                                    updateProgress(stats.getTimeInMs() / 1000.0, info.duration);
                                }
                            });
                        latch.await();

                        if (info.hasAudio) {
                            CountDownLatch audioLatch = new CountDownLatch(1);
                            String audioPath = previewClipPath.substring(0, previewClipPath.lastIndexOf('.')) + ".wav";
                            String cmdAudio = "-i \"" + targetFile.getAbsolutePath() + "\" -vn -ac 1 -ar 22050 -c:a pcm_s16le -y \"" + audioPath + "\"";
                            FFmpegEdit.runAnyCommand(cmdAudio, "Preview Audio",
                                () -> audioLatch.countDown(),
                                () -> audioLatch.countDown(),
                                log -> {}, stats -> {});
                            audioLatch.await();
                        }
                    } else if (type == ClipType.AUDIO) {
                        String audioPath = previewClipPath.substring(0, previewClipPath.lastIndexOf('.')) + ".wav";
                        String cmdAudio = "-i \"" + targetFile.getAbsolutePath() + "\" -vn -ac 1 -ar 22050 -c:a pcm_s16le -y \"" + audioPath + "\"";
                        FFmpegEdit.runAnyCommand(cmdAudio, "Preview Audio",
                            () -> latch.countDown(),
                            () -> latch.countDown(),
                            log -> {}, stats -> {});
                        latch.await();
                    }

                    Platform.runLater(() -> addClipToMediaGrid(mediaGrid, clip));
                }
                return null;
            }
        };

        desc.textProperty().bind(task.messageProperty());
        progress.progressProperty().bind(task.progressProperty());

        task.setOnSucceeded(e -> progressDialog.setResult(ButtonType.OK));
        task.setOnFailed(e -> progressDialog.setResult(ButtonType.CANCEL));
        
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
        
        progressDialog.showAndWait();
    }

    private void addClipToMediaGrid(FlowPane mediaGrid, Clip clip) {
        if (!mediaGrid.getChildren().isEmpty() && mediaGrid.getChildren().get(0) instanceof Label) {
            mediaGrid.getChildren().clear();
        }

        VBox box = new VBox(4);
        box.setAlignment(Pos.CENTER);
        box.setPrefWidth(80);
        box.setPrefHeight(80);
        box.getStyleClass().add("media-grid-item");
        box.setStyle("-fx-border-color: #555; -fx-border-radius: 4px; -fx-background-color: #222; -fx-padding: 4px;");

        javafx.scene.Node graphicNode;

        if (clip.type == ClipType.VIDEO || clip.type == ClipType.IMAGE) {
            String imagePath;
            if (clip.type == ClipType.VIDEO) {
                imagePath = IOHelper.CombinePath(project.getProjectPath(), Constants.DEFAULT_PREVIEW_CLIP_DIRECTORY, clip.getClipName() + ".jpg");
            } else {
                imagePath = clip.getAbsolutePath(project);
            }
            
            File imgFile = new File(imagePath);
            if (imgFile.exists()) {
                Image img = new Image("file:" + imgFile.getAbsolutePath(), 60, 60, true, true);
                ImageView imageView = new ImageView(img);
                imageView.setFitWidth(60);
                imageView.setFitHeight(40);
                imageView.setPreserveRatio(true);
                graphicNode = imageView;
            } else {
                FontIcon icon = new FontIcon(clip.type == ClipType.VIDEO ? MaterialDesignM.MOVIE : MaterialDesignI.IMAGE);
                icon.setIconSize(32);
                icon.setIconColor(Color.WHITE);
                graphicNode = icon;
            }
        } else {
            FontIcon icon = new FontIcon(clip.type == ClipType.AUDIO ? MaterialDesignM.MUSIC_NOTE : MaterialDesignF.FILE_QUESTION);
            icon.setIconSize(32);
            icon.setIconColor(Color.WHITE);
            graphicNode = icon;
        }

        Label nameLbl = new Label(clip.getClipName());
        nameLbl.setStyle("-fx-text-fill: white; -fx-font-size: 10px;");
        nameLbl.setWrapText(true);
        nameLbl.setMaxWidth(70);
        nameLbl.setMaxHeight(20);
        nameLbl.setAlignment(Pos.CENTER);

        box.getChildren().addAll(graphicNode, nameLbl);
        mediaGrid.getChildren().add(box);

        box.setOnMousePressed(e -> {
            Clip copyClip = new Clip(clip);
            copyClip.trackIndex = 0;
            
            activeDrag.clip = copyClip;
            activeDrag.currentTrackIdx = 0;
            activeDrag.dragOffsetX = e.getX();
            activeDrag.dragging = false;
            activeDrag.ghost = null;
            activeDrag.isNewClip = true;
            e.consume();
        });

        box.setOnMouseDragged(e -> {
            if (activeDrag.clip == null || !activeDrag.isNewClip) return;

            if (!activeDrag.dragging) {
                activeDrag.dragging = true;
                ClipNode ghost = new ClipNode(activeDrag.clip);
                ghost.getStyleClass().add("clip-node-ghost");
                ghost.setOpacity(0.55);
                double targetWidth = Math.max(2, activeDrag.clip.duration * pixelsPerSecond);
                ghost.setPrefWidth(targetWidth);
                ghost.setPrefHeight(TRACK_HEIGHT);
                ghost.setMinWidth(targetWidth);
                ghost.setMinHeight(TRACK_HEIGHT);
                ghost.setMaxWidth(targetWidth);
                ghost.setMaxHeight(TRACK_HEIGHT);
                ghost.setMouseTransparent(true);
                tracksPane.getChildren().add(ghost);
                activeDrag.ghost = ghost;
            }

            javafx.geometry.Point2D local = tracksPane.sceneToLocal(e.getSceneX(), e.getSceneY());
            double rawX = local.getX() - activeDrag.dragOffsetX;
            double clampedX = Math.max(0, rawX);

            double ghostW = activeDrag.ghost.getPrefWidth();
            double snappedX = applySnap(clampedX, ghostW, activeDrag.currentTrackIdx);
            activeDrag.ghost.setLayoutX(snappedX);

            int newTrackIdx = trackIdxFromLocalY(local.getY());
            if (newTrackIdx != activeDrag.currentTrackIdx) {
                activeDrag.currentTrackIdx = newTrackIdx;
            }
            double newY = activeDrag.currentTrackIdx * (TRACK_HEIGHT + TRACK_SPACING) + 3;
            activeDrag.ghost.setLayoutY(newY);

            e.consume();
        });

        box.setOnMouseReleased(e -> {
            if (activeDrag.clip == null || !activeDrag.isNewClip) return;

            if (activeDrag.dragging && activeDrag.ghost != null) {
                double finalX   = activeDrag.ghost.getLayoutX();
                int    newTrackIdx = activeDrag.currentTrackIdx;

                tracksPane.getChildren().remove(activeDrag.ghost);

                javafx.geometry.Point2D spLocal = tracksScrollPane.sceneToLocal(e.getSceneX(), e.getSceneY());
                if (spLocal.getX() >= 0 && spLocal.getY() >= 0 && spLocal.getX() <= tracksScrollPane.getWidth() && spLocal.getY() <= tracksScrollPane.getHeight()) {
                    float newStartTime = (float)(finalX / pixelsPerSecond);
                    activeDrag.clip.startTime = Math.max(0f, newStartTime);
                    activeDrag.clip.trackIndex = newTrackIdx;

                    while (timeline.tracks.size() <= newTrackIdx) {
                        addNewTrack("Track " + (timeline.tracks.size() + 1));
                    }

                    timeline.tracks.get(newTrackIdx).addClip(activeDrag.clip);
                    timeline.tracks.get(newTrackIdx).sortClips();
                    
                    saveProject();
                    refreshTimelineUI();
                }
            }

            activeDrag.clip    = null;
            activeDrag.ghost   = null;
            activeDrag.dragging = false;
            activeDrag.isNewClip = false;
            e.consume();
        });
    }

    // ====================================================================
    //  CENTER — Preview Player
    // ====================================================================
    private VBox buildPreviewPanel() {
        VBox panel = new VBox();
        panel.getStyleClass().add("editor-preview-panel");

        // Canvas / Preview
        StackPane canvas = new StackPane();
        canvas.getStyleClass().add("canvas-wrapper");
        VBox.setVgrow(canvas, Priority.ALWAYS);

        // Initialize TimelineRenderer
        timelineRenderer = new TimelineRenderer(project, videoSettings);
        Pane renderPane = timelineRenderer.getRenderPane();

        // Wrap in a Group to detach bounds from StackPane's layout system
        javafx.scene.Group renderGroup = new javafx.scene.Group(renderPane);
        renderGroup.setManaged(false); // crucial for allowing canvas to shrink smaller than the video settings
        canvas.getChildren().add(renderGroup);

        canvas.layoutBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            double w = newBounds.getWidth() - 32;
            double h = newBounds.getHeight() - 32;
            if (w <= 0 || h <= 0) return;
            
            double scale = Math.min(w / videoSettings.videoWidth, h / videoSettings.videoHeight);
            
            renderPane.setScaleX(scale);
            renderPane.setScaleY(scale);
            
            // Center the group in the canvas based on the unscaled dimensions
            renderGroup.setLayoutX(newBounds.getWidth() / 2.0 - videoSettings.videoWidth / 2.0);
            renderGroup.setLayoutY(newBounds.getHeight() / 2.0 - videoSettings.videoHeight / 2.0);
        });

        // Playback Controls Row
        HBox controls = new HBox(16);
        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new Insets(10, 16, 10, 16));
        controls.getStyleClass().add("playback-controls");

        // Time display
        currentTimeLabel.getStyleClass().add("timecode-label");
        Label timeSep = new Label("/");
        timeSep.setStyle("-fx-text-fill: -color-fg-muted;");
        durationLabel.getStyleClass().add("timecode-label");
        durationLabel.setText("00:00:10:00"); // Mock duration

        Region pbLeft = new Region();
        HBox.setHgrow(pbLeft, Priority.ALWAYS);

        Button playBtn = new Button();
        FontIcon playIcon = new FontIcon(MaterialDesignP.PLAY_CIRCLE_OUTLINE);
        FontIcon pauseIcon = new FontIcon(MaterialDesignP.PAUSE_CIRCLE_OUTLINE);
        playBtn.setGraphic(playIcon);
        playBtn.getStyleClass().addAll("button-transparent", "play-button-main");
        playBtn.setOnAction(e -> {
            if (isPlaying) {
                stopPlayback();
                playBtn.setGraphic(playIcon);
            } else {
                startPlayback();
                playBtn.setGraphic(pauseIcon);
            }
        });

        Region pbRight = new Region();
        HBox.setHgrow(pbRight, Priority.ALWAYS);

        // Quality labels (like CapCut)
        Label fpsLabel = buildStatBadge("60");
        Label resLabel = buildStatBadge("1080p");
        Label hdrLabel = buildStatBadge("SDR");

        Button fullScreenBtn = new Button();
        fullScreenBtn.setGraphic(new FontIcon(MaterialDesignF.FULLSCREEN));
        fullScreenBtn.getStyleClass().add("button-transparent");

        controls.getChildren().addAll(
            currentTimeLabel, timeSep, durationLabel,
            pbLeft, playBtn, pbRight,
            fpsLabel, resLabel, hdrLabel, fullScreenBtn
        );

        panel.getChildren().addAll(canvas, controls);
        return panel;
    }

    private Label buildStatBadge(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("stat-badge");
        lbl.setPadding(new Insets(2, 6, 2, 6));
        return lbl;
    }

    // ====================================================================
    //  RIGHT PANEL — Properties / Smart Suggestions
    // ====================================================================
    private VBox buildRightPanel() {
        VBox panel = new VBox(0);
        panel.setPrefWidth(280);
        panel.setMinWidth(220);
        panel.setMaxWidth(360);
        panel.getStyleClass().add("editor-right-panel");

        // Header tabs
        HBox tabs = new HBox(0);
        tabs.getStyleClass().add("editor-tab-strip");
        ToggleGroup tg = new ToggleGroup();
        for (String t : new String[]{"Project", "Details"}) {
            ToggleButton tb = new ToggleButton(t);
            tb.setToggleGroup(tg);
            tb.getStyleClass().add("editor-tab");
            HBox.setHgrow(tb, Priority.ALWAYS);
            tb.setMaxWidth(Double.MAX_VALUE);
            tabs.getChildren().add(tb);
        }
        ((ToggleButton) tabs.getChildren().get(0)).setSelected(true);

        // Smart suggestions card
        VBox suggestionsCard = new VBox(10);
        suggestionsCard.setPadding(new Insets(16));
        suggestionsCard.getStyleClass().add("suggestions-card");

        Label suggTitle = new Label("Smart suggestions");
        suggTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Label suggSub = new Label("Find out how your video\ncan be improved");
        suggSub.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        Button analyzeBtn = new Button("+ Analyze");
        analyzeBtn.getStyleClass().add("analyze-button");

        suggestionsCard.getChildren().addAll(suggTitle, suggSub, analyzeBtn);

        // Global edits section
        VBox globalEdits = new VBox(4);
        globalEdits.setPadding(new Insets(16));
        Label geTitle = new Label("Global edits");
        geTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        globalEdits.getChildren().add(geTitle);

        String[] edits = {"Make colors better", "Make colors consistent", "Make volume consistent", "Make voice clearer"};
        for (String edit : edits) {
            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(8, 12, 8, 12));
            row.getStyleClass().add("global-edit-row");
            Label lbl = new Label(edit);
            lbl.setStyle("-fx-font-size: 12px;");
            Region rs = new Region();
            HBox.setHgrow(rs, Priority.ALWAYS);
            ToggleButton toggle = new ToggleButton();
            toggle.getStyleClass().add("pill-toggle");
            row.getChildren().addAll(lbl, rs, toggle);
            globalEdits.getChildren().add(row);
        }

        ScrollPane rightScroll = new ScrollPane(propertiesContent);
        rightScroll.setFitToWidth(true);
        VBox.setVgrow(rightScroll, Priority.ALWAYS);
        rightScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        panel.getChildren().addAll(tabs, rightScroll);
        
        // Setup initial properties view
        updatePropertiesPane();
        
        return panel;
    }

    private void updatePropertiesPane() {
        propertiesContent.getChildren().clear();
        propertiesContent.setPadding(new Insets(16));
        
        if (selectedClip == null) {
            Label placeholder = new Label("Select a clip to view properties");
            placeholder.getStyleClass().add("text-muted");
            propertiesContent.getChildren().add(placeholder);
            return;
        }

        Label sectionTitle = new Label("Clip Properties");
        sectionTitle.getStyleClass().add("text-bold");
        sectionTitle.setStyle("-fx-font-size: 16px;");

        VBox fields = new VBox(10);

        fields.getChildren().add(buildPropertyField("Name", selectedClip.getClipName(), newValue -> {
            selectedClip.setClipName(newValue);
            refreshTimelineUI();
            saveProject();
        }));

        fields.getChildren().add(buildPropertyField("Start Time", String.valueOf(selectedClip.startTime), newValue -> {
            try {
                selectedClip.startTime = Float.parseFloat(newValue);
                refreshTimelineUI();
                saveProject();
            } catch (Exception ignored) {}
        }));

        fields.getChildren().add(buildPropertyField("Duration", String.valueOf(selectedClip.duration), newValue -> {
            try {
                selectedClip.duration = Float.parseFloat(newValue);
                refreshTimelineUI();
                saveProject();
            } catch (Exception ignored) {}
        }));

        propertiesContent.getChildren().addAll(sectionTitle, fields);
    }

    private VBox buildPropertyField(String label, String value, java.util.function.Consumer<String> onUpdate) {
        VBox box = new VBox(4);
        Label lbl = new Label(label);
        lbl.getStyleClass().add("text-muted");
        lbl.setStyle("-fx-font-size: 11px;");

        TextField tf = new TextField(value);
        tf.getStyleClass().add("editor-textfield");
        tf.setOnAction(e -> onUpdate.accept(tf.getText()));
        // Also update on focus loss
        tf.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) onUpdate.accept(tf.getText());
        });

        box.getChildren().addAll(lbl, tf);
        return box;
    }

    // ====================================================================
    //  BOTTOM — Timeline
    // ====================================================================
    private VBox buildTimelineArea() {
        VBox timeline = new VBox(0);
        timeline.setPrefHeight(280);
        timeline.setMinHeight(160);
        timeline.getStyleClass().add("editor-timeline");

        // --- Editing toolbar ---
        HBox toolbar = new HBox(6);
        toolbar.setPadding(new Insets(6, 12, 6, 12));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("timeline-toolbar");

        // Selection / trim tools (left cluster)
        Button selectTool = buildToolBtn(MaterialDesignC.CURSOR_DEFAULT_OUTLINE);
        Button sliceTool  = buildToolBtn(MaterialDesignS.SCISSORS_CUTTING);
        sliceTool.setOnAction(e -> handleSplit());
        
        Button trimLeft   = buildToolBtn(MaterialDesignF.FORMAT_INDENT_DECREASE);
        Button trimRight  = buildToolBtn(MaterialDesignF.FORMAT_INDENT_INCREASE);
        Button deleteTool = buildToolBtn(MaterialDesignT.TRASH_CAN_OUTLINE);
        deleteTool.setOnAction(e -> handleDelete());

        Region toolSpacer = new Region();
        HBox.setHgrow(toolSpacer, Priority.ALWAYS);

        // Right cluster (zoom, waveform toggle, snap)
        Label zoomLabel = new Label("Zoom");
        zoomLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
        zoomSlider = new Slider(50, 500, 100); // 50px to 500px per second
        zoomSlider.setPrefWidth(100);
        zoomSlider.getStyleClass().add("timeline-zoom-slider");
        zoomSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            pixelsPerSecond = newVal.floatValue();
            refreshTimelineUI();
        });

        toolbar.getChildren().addAll(selectTool, sliceTool, trimLeft, trimRight, deleteTool, toolSpacer, zoomLabel, zoomSlider);

        // --- Ruler + Track content ---
        HBox trackLayout = new HBox(0);
        VBox.setVgrow(trackLayout, Priority.ALWAYS);

        // Left sidebar (track headers)
        VBox trackSidebar = new VBox(0);
        trackSidebar.setPrefWidth(110);
        trackSidebar.setMinWidth(110);
        trackSidebar.setMaxWidth(110);
        trackSidebar.getStyleClass().add("track-sidebar");

        // Tiny "Add Track" at top of sidebar
        HBox sidebarTop = new HBox();
        sidebarTop.setPrefHeight(30);
        sidebarTop.setAlignment(Pos.CENTER_RIGHT);
        sidebarTop.setPadding(new Insets(0, 6, 0, 6));
        sidebarTop.getStyleClass().add("sidebar-top-row");
        Button addTrackBtn = new Button();
        addTrackBtn.setGraphic(new FontIcon(MaterialDesignP.PLUS));
        addTrackBtn.getStyleClass().add("button-transparent");
        addTrackBtn.setStyle("-fx-padding: 2px;");
        addTrackBtn.setOnAction(e -> addNewTrack("New Track"));
        sidebarTop.getChildren().add(addTrackBtn);

        // Tracks sidebar container
        ScrollPane trackHeadersScrollPane = new ScrollPane(trackHeadersContainer);
        trackHeadersScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        trackHeadersScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); // Hide scrollbars, slaved to tracks
        trackHeadersScrollPane.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        trackHeadersScrollPane.setFitToWidth(true);

        VBox.setVgrow(trackHeadersScrollPane, Priority.ALWAYS);
        trackSidebar.getChildren().addAll(sidebarTop, trackHeadersScrollPane);

        // Right: ruler + scrollable tracks
        VBox rulerAndTracks = new VBox(0);
        HBox.setHgrow(rulerAndTracks, Priority.ALWAYS);

        // Ruler
        buildRuler();
        rulerScrollPane.getStyleClass().add("ruler-scroll");
        rulerScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rulerScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rulerScrollPane.setPrefHeight(30);
        rulerScrollPane.setMinHeight(30);
        rulerScrollPane.setMaxHeight(30);

        // Tracks area
        tracksScrollPane.getStyleClass().add("tracks-scroll");
        tracksScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        tracksScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(tracksScrollPane, Priority.ALWAYS);

        // Sync scrollers (horizontal for ruler, vertical for headers)
        rulerScrollPane.hvalueProperty().bindBidirectional(tracksScrollPane.hvalueProperty());
        trackHeadersScrollPane.vvalueProperty().bindBidirectional(tracksScrollPane.vvalueProperty());

        // Tracks content container
        tracksPane.getStyleClass().add("timeline-tracks-pane");
        tracksPane.setPrefWidth(8000);
        tracksPane.setPrefHeight(300);
        tracksPane.setOnMouseClicked(e -> deselectAll());
        tracksScrollPane.setContent(tracksPane);

        // Playhead overlay
        StackPane tracksWithPlayhead = new StackPane();
        VBox.setVgrow(tracksWithPlayhead, Priority.ALWAYS);
        tracksWithPlayhead.getChildren().add(tracksScrollPane);

        playheadLine = new Line(0, 0, 0, 1000);
        playheadLine.setStroke(Color.web("#FF3B30"));
        playheadLine.setStrokeWidth(2);
        playheadLine.setManaged(false);
        
        ghostPlayheadLine = new Line(0, 0, 0, 1000);
        ghostPlayheadLine.setStroke(Color.web("#FF3B30"));
        ghostPlayheadLine.setStrokeWidth(1.5);
        ghostPlayheadLine.setOpacity(0.5);
        ghostPlayheadLine.getStrokeDashArray().addAll(4d, 4d);
        ghostPlayheadLine.setVisible(false);
        ghostPlayheadLine.setManaged(false);
        
        Pane playheadOverlay = new Pane(playheadLine, ghostPlayheadLine);
        playheadOverlay.setMouseTransparent(true);
        tracksWithPlayhead.getChildren().add(playheadOverlay);

        // --- Playhead Sync Listeners ---
        // Update position when scrolling
        tracksScrollPane.hvalueProperty().addListener((obs, old, newVal) -> updatePlayheadPosition());
        
        // Update position when resizing viewport
        tracksScrollPane.viewportBoundsProperty().addListener((obs, old, newVal) -> updatePlayheadPosition());
        
        // Update position when content width changes (e.g. zoom)
        tracksPane.widthProperty().addListener((obs, old, newVal) -> updatePlayheadPosition());

        // Bind playhead height to container
        playheadLine.endYProperty().bind(tracksWithPlayhead.heightProperty());
        ghostPlayheadLine.endYProperty().bind(tracksWithPlayhead.heightProperty());

        rulerAndTracks.getChildren().addAll(rulerScrollPane, tracksWithPlayhead);

        // Zoom Gestures
        rulerAndTracks.addEventFilter(javafx.scene.input.ZoomEvent.ZOOM, e -> {
            double zoomFactor = e.getZoomFactor();
            double newZoom = zoomSlider.getValue() * zoomFactor;
            zoomSlider.setValue(Math.min(zoomSlider.getMax(), Math.max(zoomSlider.getMin(), newZoom)));
            e.consume();
        });

        rulerAndTracks.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (e.isControlDown() || e.isShortcutDown()) {
                double delta = e.getDeltaY();
                // Map the delta dynamically (standard mouse notch is +/- 40 = 1.1 scale)
                double zoomFactor = 1.0 + (delta / 400.0);
                
                if (zoomFactor != 1.0 && zoomFactor > 0) {
                    double newZoom = zoomSlider.getValue() * zoomFactor;
                    zoomSlider.setValue(Math.min(zoomSlider.getMax(), Math.max(zoomSlider.getMin(), newZoom)));
                }
                e.consume(); // prevent natural scrolling while zooming
            }
        });

        trackLayout.getChildren().addAll(trackSidebar, rulerAndTracks);
        timeline.getChildren().addAll(toolbar, trackLayout);
        return timeline;
    }

    private void buildRuler() {
        Pane ruler = new Pane();
        ruler.setPrefWidth(8000);
        ruler.setPrefHeight(30);
        ruler.setPickOnBounds(true);
        ruler.getStyleClass().add("timeline-ruler-pane");

        // Compute step interval based on zoom factor
        float rulerInterval = 1f;
        if (pixelsPerSecond >= 400) rulerInterval = 0.1f;
        else if (pixelsPerSecond >= 200) rulerInterval = 0.2f;
        else if (pixelsPerSecond >= 100) rulerInterval = 0.5f;

        float majorPixels = pixelsPerSecond * rulerInterval;
        float visibleDuration = 8000 / pixelsPerSecond;
        long steps = (long) Math.ceil((visibleDuration + 2f) / rulerInterval);
        
        int subCount = 0;
        if (majorPixels >= 60f) subCount = 4;
        else if (majorPixels >= 28f) subCount = 1;

        for (long step = 0; step <= steps; step++) {
            float t = step * rulerInterval;
            double x = t * pixelsPerSecond;

            // Check if it's near a whole second
            float frac = t - (float) Math.floor(t);
            float tol = rulerInterval * 0.02f;
            boolean isWholeSecond = (frac < tol || frac > (1f - tol));

            Line tick = new Line(x, isWholeSecond ? 10 : 18, x, 30);
            tick.getStyleClass().add(isWholeSecond ? "ruler-tick-major" : "ruler-tick-minor");
            ruler.getChildren().add(tick);

            if (isWholeSecond) {
                int sec = Math.round(t);
                String lbl = sec == 0 ? "0s"
                        : sec < 60 ? sec + "s"
                        : (sec / 60) + "m" + (sec % 60) + "s";
                
                javafx.scene.text.Text label = new javafx.scene.text.Text(x + 2, 9, lbl);
                label.getStyleClass().add("ruler-text");
                ruler.getChildren().add(label);
            } else {
                // Frame number within the current second (assume 30fps)
                int frameInSec = Math.max(1, Math.round(frac * 30));
                String lbl = frameInSec + "f";
                
                if (majorPixels >= 14f) {
                    javafx.scene.text.Text label = new javafx.scene.text.Text(x + 1.5, 9, lbl);
                    label.getStyleClass().add("ruler-text");
                    label.setStyle("-fx-opacity: 0.7;"); // Medium tick fade
                    ruler.getChildren().add(label);
                }
            }

            // Small subticks
            if (subCount > 0 && step < steps) {
                float subInterval = rulerInterval / (subCount + 1);
                for (int s = 1; s <= subCount; s++) {
                    float subT = t + subInterval * s;
                    double subX = subT * pixelsPerSecond;
                    Line subTick = new Line(subX, 22, subX, 30);
                    subTick.getStyleClass().add("ruler-tick-minor");
                    subTick.setStyle("-fx-opacity: 0.5;");
                    ruler.getChildren().add(subTick);
                }
            }
        }

        ruler.setOnMouseEntered(e -> ghostPlayheadLine.setVisible(true));
        ruler.setOnMouseMoved(e -> {
            double x = e.getX();
            tempTime = (float)(x / pixelsPerSecond);
            updatePlayheadPosition();
            if (timelineRenderer != null) {
                timelineRenderer.updateTime(tempTime, true);
            }
        });
        ruler.setOnMouseExited(e -> {
            ghostPlayheadLine.setVisible(false);
            tempTime = -1;
            updatePlayheadPosition();
            if (timelineRenderer != null) {
                timelineRenderer.updateTime(currentTime, !isPlaying);
            }
        });
        ruler.setOnMouseClicked(e -> {
            updateCurrentTime((float)(e.getX() / pixelsPerSecond));
        });

        rulerScrollPane.setContent(ruler);
    }

    private void addNewTrack(String name) {
        Track track = new Track();
        timeline.addTrack(track);

        // UI: Sidebar Header
        trackHeadersContainer.getChildren().add(buildTrackHeader(name));

        // UI: Track Band (alternating colors)
        double y = (timeline.tracks.size() - 1) * (TRACK_HEIGHT + TRACK_SPACING);
        Rectangle band = new Rectangle(0, y, 8000, TRACK_HEIGHT);
        band.getStyleClass().add((timeline.tracks.size() - 1) % 2 == 0 ? "track-band-even" : "track-band-odd");
        tracksPane.getChildren().add(band);
        
        track.viewRef = band; // Keep reference if needed

        // Add a sample clip to the first track for visualization
        if (timeline.tracks.size() == 1) {
            addClipToTrack(track, new Clip("Sample Video", 1.2f, 5.0f, 0, ClipType.VIDEO, true, 1920, 1080));
        }
        
        saveProject(); // Auto-save on track creation
    }

    private void addClipToTrack(Track track, Clip clip) {
        track.addClip(clip);
        renderClipUI(track, clip);
    }

    private void refreshTimelineUI() {
        // Refresh Ruler
        buildRuler();
        
        // Refresh Clips and Tracks
        tracksPane.getChildren().clear();
        for (Track track : timeline.tracks) {
            // Add track band
            double y = track.timelineIndex * (TRACK_HEIGHT + TRACK_SPACING);
            Rectangle band = new Rectangle(0, y, 8000, TRACK_HEIGHT);
            band.getStyleClass().add(track.timelineIndex % 2 == 0 ? "track-band-even" : "track-band-odd");
            tracksPane.getChildren().add(band);

            // Re-add clips (they will be reconstructed for simplicity in this refresh)
            // In a more optimized version, we'd just update their X/Width.
            for (Clip clip : track.clips) {
                renderClipUI(track, clip);
            }
        }
        
        // Rebuild TimelineRenderer
        if (timelineRenderer != null) {
            timelineRenderer.buildTimeline(timeline);
        }
        
        // Update playhead
        updateCurrentTime(currentTime);
    }

    private void renderClipUI(Track track, Clip clip) {
        double x = clip.startTime * pixelsPerSecond;
        double y = track.timelineIndex * (TRACK_HEIGHT + TRACK_SPACING);
        double w = clip.duration * pixelsPerSecond;
        double h = TRACK_HEIGHT - 6;

        ClipNode node = new ClipNode(clip);
        node.setLayoutX(x);
        node.setLayoutY(y + 3);
        node.setPrefWidth(w);
        node.setPrefHeight(h);
        node.setMinWidth(w);
        node.setMinHeight(h);
        node.setMaxWidth(w);
        node.setMaxHeight(h);

        if (selectedClip == clip) node.setSelected(true);

        setupClipInteraction(node);
        tracksPane.getChildren().add(node);
        clip.viewRef = node;
    }

    // =========================================================================
    //  Clip Interaction — CapCut-style: click = select, drag = immediate move
    // =========================================================================
    private void setupClipInteraction(ClipNode node) {
        Clip clip = node.getContainerClip();

        node.setOnMousePressed(e -> {
            // Select on press (feels snappier than waiting for click)
            selectClip(clip);

            // Prepare drag context
            activeDrag.clip          = clip;
            activeDrag.currentTrackIdx = clip.trackIndex;
            activeDrag.dragOffsetX   = e.getX();   // offset within the node
            activeDrag.dragging      = false;
            activeDrag.ghost         = null;
            activeDrag.isNewClip     = false;
            e.consume();
        });

        node.setOnMouseDragged(e -> {
            if (activeDrag.clip != clip || activeDrag.isNewClip) return;

            // Create ghost on first drag pixel (CapCut style — no threshold)
            if (!activeDrag.dragging) {
                activeDrag.dragging = true;
                node.setVisible(false);             // hide original
                ClipNode ghost = new ClipNode(clip);
                ghost.getStyleClass().add("clip-node-ghost");
                ghost.setOpacity(0.55);
                ghost.setPrefWidth(node.getPrefWidth());
                ghost.setPrefHeight(node.getPrefHeight());
                ghost.setMinWidth(node.getPrefWidth());
                ghost.setMinHeight(node.getPrefHeight());
                ghost.setMaxWidth(node.getPrefWidth());
                ghost.setMaxHeight(node.getPrefHeight());
                ghost.setLayoutX(node.getLayoutX());
                ghost.setLayoutY(node.getLayoutY());
                ghost.setMouseTransparent(true);
                tracksPane.getChildren().add(ghost);
                activeDrag.ghost = ghost;
            }

            // Convert scene coords → tracksPane local coords
            javafx.geometry.Point2D local = tracksPane.sceneToLocal(e.getSceneX(), e.getSceneY());
            double rawX = local.getX() - activeDrag.dragOffsetX;
            double clampedX = Math.max(0, rawX);

            double ghostW = activeDrag.ghost.getPrefWidth();

            // Snapping
            double snappedX = applySnap(clampedX, ghostW, activeDrag.currentTrackIdx);
            activeDrag.ghost.setLayoutX(snappedX);

            // Cross-track detection by Y
            int newTrackIdx = trackIdxFromLocalY(local.getY());
            if (newTrackIdx != activeDrag.currentTrackIdx) {
                activeDrag.currentTrackIdx = newTrackIdx;
                double newY = newTrackIdx * (TRACK_HEIGHT + TRACK_SPACING) + 3;
                activeDrag.ghost.setLayoutY(newY);
            }

            e.consume();
        });

        node.setOnMouseReleased(e -> {
            if (activeDrag.clip != clip || activeDrag.isNewClip) return;

            if (activeDrag.dragging && activeDrag.ghost != null) {
                double finalX   = activeDrag.ghost.getLayoutX();
                double finalY   = activeDrag.ghost.getLayoutY();
                int    newTrackIdx = activeDrag.currentTrackIdx;

                // Remove ghost
                tracksPane.getChildren().remove(activeDrag.ghost);

                // Update model
                float newStartTime = (float)(finalX / pixelsPerSecond);
                clip.startTime     = Math.max(0f, newStartTime);

                if (newTrackIdx != clip.trackIndex) {
                    timeline.tracks.get(clip.trackIndex).removeClip(clip);
                    clip.trackIndex = newTrackIdx;
                    timeline.tracks.get(clip.trackIndex).addClip(clip);
                }
                timeline.tracks.get(clip.trackIndex).sortClips();

                saveProject();
            }

            // Reset drag state
            activeDrag.clip    = null;
            activeDrag.ghost   = null;
            activeDrag.dragging = false;
            node.setVisible(true);

            // Refresh so clip redraws at committed position
            refreshTimelineUI();
            e.consume();
        });
    }

    /** Map a Y position in tracksPane-local coords to a track index. */
    private int trackIdxFromLocalY(double localY) {
        int idx = (int)(localY / (TRACK_HEIGHT + TRACK_SPACING));
        return Math.max(0, Math.min(timeline.tracks.size() - 1, idx));
    }

    /**
     * Apply playhead + clip-edge snapping to a proposed ghost X position.
     * Mirrors Android: snaps start-to-end and end-to-start on ±1 neighbour tracks.
     */
    private double applySnap(double ghostX, double ghostWidth, int currentTrackIdx) {
        double ghostEnd = ghostX + ghostWidth;

        // 1. Snap to playhead
        double playheadX = currentTime * pixelsPerSecond;
        if (Math.abs(ghostX   - playheadX) < SNAP_THRESHOLD) return playheadX;
        if (Math.abs(ghostEnd - playheadX) < SNAP_THRESHOLD) return playheadX - ghostWidth;

        // 2. Snap to clip edges on current + neighbour tracks
        for (int j = 0; j < timeline.tracks.size(); j++) {
            if (Math.abs(j - currentTrackIdx) > 1) continue;  // only neighbours
            for (Clip other : timeline.tracks.get(j).clips) {
                if (other == activeDrag.clip) continue;
                double otherStart = other.startTime * pixelsPerSecond;
                double otherEnd   = (other.startTime + other.duration) * pixelsPerSecond;

                if (Math.abs(ghostX   - otherEnd)   < SNAP_THRESHOLD) return otherEnd;
                if (Math.abs(ghostEnd - otherStart)  < SNAP_THRESHOLD) return otherStart - ghostWidth;
            }
        }
        return ghostX;
    }

    private void selectClip(Clip clip) {
        // Deselect previous
        if (selectedClip != null && selectedClip.viewRef instanceof ClipNode prev) {
            prev.setSelected(false);
        }

        selectedClip  = clip;
        selectedTrack = timeline.tracks.get(clip.trackIndex);

        if (clip.viewRef instanceof ClipNode cn) {
            cn.setSelected(true);
        }

        updatePropertiesPane();
    }

    private void deselectAll() {
        if (selectedClip != null && selectedClip.viewRef instanceof ClipNode cn) {
            cn.setSelected(false);
        }
        selectedClip  = null;
        selectedTrack = null;
        updatePropertiesPane();
    }

    private void handleSplit() {
        if (selectedTrack != null) {
            // Split only clips in the selected track
            List<Clip> clipsToSplit = new ArrayList<>();
            for (Clip c : selectedTrack.clips) {
                if (currentTime > c.startTime && currentTime < c.startTime + c.duration) {
                    clipsToSplit.add(c);
                }
            }
            for (Clip c : clipsToSplit) {
                splitClipProxy(c);
            }
        } else {
            // Split all clips at current time
            List<Clip> allClipsAtTime = timeline.getClipsAtTime(currentTime);
            for (Clip c : allClipsAtTime) {
                splitClipProxy(c);
            }
        }
        refreshTimelineUI();
        saveProject(); // Auto-save on split
    }

    private void splitClipProxy(Clip clip) {
        // Model logic
        Track track = timeline.tracks.get(clip.trackIndex);
        float localSplitTime = currentTime - clip.startTime;
        
        Clip secondPart = new Clip(clip);
        secondPart.startTime = currentTime;
        secondPart.duration = clip.duration - localSplitTime;
        clip.duration = localSplitTime;
        
        track.addClip(secondPart);
        track.sortClips();
    }

    private void handleDelete() {
        if (selectedClip != null) {
            Track t = timeline.tracks.get(selectedClip.trackIndex);
            t.removeClip(selectedClip);
            selectedClip = null;
        } else if (selectedTrack != null) {
            timeline.removeTrack(selectedTrack);
            selectedTrack = null;
            // update headers
            trackHeadersContainer.getChildren().clear();
            for (Track t : timeline.tracks) {
                trackHeadersContainer.getChildren().add(buildTrackHeader("Track " + (t.timelineIndex + 1)));
            }
        }
        refreshTimelineUI();
        saveProject(); // Auto-save on delete
    }


    private HBox buildTrackHeader(String name) {
        HBox header = new HBox(6);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 8, 0, 8));
        header.setPrefHeight(75);
        header.setMaxHeight(75);
        header.getStyleClass().add("track-header");

        Label lbl = new Label(name);
        lbl.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");

        Region hs = new Region();
        HBox.setHgrow(hs, Priority.ALWAYS);

        Button muteBtn = new Button();
        muteBtn.setGraphic(new FontIcon(MaterialDesignV.VOLUME_HIGH));
        muteBtn.getStyleClass().add("button-transparent");
        muteBtn.setStyle("-fx-padding: 2px;");

        header.getChildren().addAll(lbl, hs, muteBtn);
        return header;
    }

    private Button buildToolBtn(org.kordamp.ikonli.Ikon icon) {
        Button b = new Button();
        b.setGraphic(new FontIcon(icon));
        b.getStyleClass().add("tool-button");
        return b;
    }

    private String formatSeconds(int totalSeconds) {
        int m = totalSeconds / 60;
        int s = totalSeconds % 60;
        return String.format("%d:%02d", m, s);
    }
}
