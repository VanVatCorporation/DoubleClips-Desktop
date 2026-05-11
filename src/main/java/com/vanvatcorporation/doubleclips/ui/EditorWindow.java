package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.AudioUtils;
import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.data.*;
import com.vanvatcorporation.doubleclips.data.editing.*;
import com.vanvatcorporation.doubleclips.history.*;
import com.vanvatcorporation.doubleclips.helper.DateHelper;
import com.vanvatcorporation.doubleclips.ui.renderer.TimelineRenderer;
import com.vanvatcorporation.doubleclips.helper.MediaHelper;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.FFmpegEdit;
import javafx.stage.FileChooser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.*;
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
import javafx.geometry.Point2D;
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
import com.vanvatcorporation.doubleclips.history.TrimClipCommand;
import com.vanvatcorporation.doubleclips.history.MoveClipCommand;
import com.vanvatcorporation.doubleclips.history.AddClipCommand;
import com.vanvatcorporation.doubleclips.history.DeleteClipCommand;
import com.vanvatcorporation.doubleclips.history.PropertyChangeCommand;
import com.vanvatcorporation.doubleclips.history.SplitClipCommand;

public class EditorWindow extends Stage implements PropertyContext {

    private final ProjectData project;
    private Timeline timeline;
    private VideoSettings videoSettings;

    private TimelineRenderer timelineRenderer;
    private final HistoryManager historyManager = new HistoryManager();

    // Editor State
    private float currentTime = 0f;
    private boolean isPlaying = false;
    private boolean isPlayingInReverse = false;
    private float pixelsPerSecond = 100f; // 100px = 1s
    private final float TRACK_HEIGHT = 70f;
    private final float TRACK_SPACING = 5f;

    private Clip selectedClip;
    private Track selectedTrack;
    private Clip selectedTransitionSourceClip; // clip whose endTransition cube was clicked

    // Playback engine
    private AnimationTimer playbackTimer;
    private long lastTimerUpdate = 0;

    // Background operations
    private final java.util.concurrent.ExecutorService thumbnailExecutor = java.util.concurrent.Executors
            .newFixedThreadPool(
                    Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                    r -> {
                        Thread t = new Thread(r, "ThumbnailGenerator");
                        t.setDaemon(true);
                        return t;
                    });

    // UI Components for logic access
    private final Label currentTimeLabel = new Label("00:00:00:00");
    private final Label durationLabel = new Label("00:00:00:00");
    private final Button playBtn = new Button();
    private final Pane tracksPane = new Pane();
    private final VBox trackHeadersContainer = new VBox(0);
    private Line playheadLine;
    private Line ghostPlayheadLine;
    private float tempTime = -1;
    private Slider zoomSlider;
    private FlowPane mediaGrid;
    private ToggleButton mediaTab;
    private VBox mediaDropOverlay;

    // Icons for UI
    FontIcon playIcon = new FontIcon(MaterialDesignP.PLAY_CIRCLE_OUTLINE);
    FontIcon pauseIcon = new FontIcon(MaterialDesignP.PAUSE_CIRCLE_OUTLINE);

    // --- Scroll sync & Updaters ---
    private final ScrollPane rulerScrollPane = new ScrollPane();
    private final ScrollPane tracksScrollPane = new ScrollPane();
    private final List<Runnable> propertyUpdaters = new ArrayList<>();
    private PropertyPanel propertyPanel;

    // --- Drag & Drop ---
    private static final double SNAP_THRESHOLD = 8.0;

    /** Mutable drag state — one active drag at a time. */
    private static class DragContext {
        Clip clip;
        ClipNode ghost; // semi-transparent clone in tracksPane
        int currentTrackIdx;
        double dragOffsetX; // mouse X offset from clip left edge
        boolean dragging; // becomes true once mouse moves > 0 px
        boolean isNewClip; // true if dragging from media browser
    }

    private final DragContext activeDrag = new DragContext();
    private AnimationTimer edgeScrollTimer;
    private double edgeScrollVelocity = 0; // pixels per frame to scroll
    private double lastDragSceneX;
    private double lastDragSceneY;

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

        BorderPane mainContent = new BorderPane();
        mainContent.getStyleClass().add("editor-root");

        // === Main 3-column middle section ===
        HBox centerRow = new HBox();
        HBox.setHgrow(centerRow, Priority.ALWAYS);

        VBox leftPanel = buildLeftPanel();
        VBox previewPanel = buildPreviewPanel();
        VBox rightPanel = buildRightPanel();

        HBox.setHgrow(previewPanel, Priority.ALWAYS);
        centerRow.getChildren().addAll(leftPanel, previewPanel, rightPanel);

        // === Bottom Timeline ===
        VBox timelineArea = buildTimelineArea();

        StackPane root = new StackPane(mainContent);

        MenuBar menuBar = new MenuBar();
        menuBar.setUseSystemMenuBar(true);
        Menu appMenu = new Menu("File");
        MenuItem settingsItem = new MenuItem("Preferences...");
        settingsItem.setOnAction(ev -> {
            final com.vanvatcorporation.doubleclips.ui.overlays.SettingsOverlay[] overlayRef = new com.vanvatcorporation.doubleclips.ui.overlays.SettingsOverlay[1];
            overlayRef[0] = new com.vanvatcorporation.doubleclips.ui.overlays.SettingsOverlay(v -> {
                root.getChildren().remove(overlayRef[0]);
            });
            root.getChildren().add(overlayRef[0]);
        });
        appMenu.getItems().add(settingsItem);
        menuBar.getMenus().add(appMenu);

        mainContent.setTop(new VBox(menuBar, buildTopBar()));
        mainContent.setCenter(centerRow);
        mainContent.setBottom(timelineArea);

        Scene scene = new Scene(root);
        scene.getStylesheets().add(DoubleClipsDesktop.class.getResource("/style.css").toExternalForm());

        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getTarget() instanceof javafx.scene.control.TextInputControl)
                return;

            String deleteBindingStr = sanitizeKeybind(AppSettings.getInstance().getDeleteKeybind());
            String selectAllBindingStr = sanitizeKeybind(AppSettings.getInstance().getSelectAllKeybind());
            String undoBindingStr = sanitizeKeybind(AppSettings.getInstance().getUndoKeybind());
            String redoBindingStr = sanitizeKeybind(AppSettings.getInstance().getRedoKeybind());
            String togglePlayBindingStr = sanitizeKeybind(AppSettings.getInstance().getTogglePlayKeybind());

            boolean deleteMatched = false;
            try {
                if (javafx.scene.input.KeyCombination.valueOf(deleteBindingStr).match(event))
                    deleteMatched = true;
            } catch (Exception e) {
                if (event.getCode().name().equalsIgnoreCase(deleteBindingStr))
                    deleteMatched = true;
            }

            boolean selectAllMatched = false;
            try {
                if (javafx.scene.input.KeyCombination.valueOf(selectAllBindingStr).match(event))
                    selectAllMatched = true;
            } catch (Exception e) {
                if (event.getCode().name().equalsIgnoreCase(selectAllBindingStr))
                    selectAllMatched = true;
            }

            boolean undoMatched = false;
            try {
                if (javafx.scene.input.KeyCombination.valueOf(undoBindingStr).match(event))
                    undoMatched = true;
            } catch (Exception e) {
            }

            boolean redoMatched = false;
            try {
                if (javafx.scene.input.KeyCombination.valueOf(redoBindingStr).match(event))
                    redoMatched = true;
            } catch (Exception e) {
            }

            boolean togglePlayMatched = false;
            try {
                if (javafx.scene.input.KeyCombination.valueOf(togglePlayBindingStr).match(event))
                    togglePlayMatched = true;
            } catch (Exception e) {
                if (event.getCode().name().equalsIgnoreCase(togglePlayBindingStr))
                    togglePlayMatched = true;
            }

            if (undoMatched) {
                historyManager.undo();
                event.consume();
            } else if (redoMatched) {
                historyManager.redo();
                event.consume();
            } else if (deleteMatched || (event.getCode() == javafx.scene.input.KeyCode.BACK_SPACE
                    && deleteBindingStr.equalsIgnoreCase("DELETE"))) {
                handleDelete();
                event.consume();
            } else if (selectAllMatched) {
                System.out.println("Select All triggered - Multiple selection not yet supported by data model");
                event.consume();
            }
            else if (togglePlayMatched) {
                triggerPlayAction();
                event.consume();
            }
        });

        this.setScene(scene);
        this.setOnCloseRequest(e -> {
            closeWindow();
        });

        initPlaybackTimer();
        initEdgeScrollTimer();

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

    @Override public Clip getSelectedClip() { return selectedClip; }
    @Override public Track getSelectedTrack() { return selectedTrack; }
    @Override public Clip getSelectedTransitionSourceClip() { return selectedTransitionSourceClip; }
    @Override public float getCurrentTime() { return currentTime; }
    @Override public float getTempTime() { return tempTime; }
    @Override public void addPropertyUpdater(Runnable updater) { propertyUpdaters.add(updater); }
    @Override public Timeline getTimeline() { return timeline; }

    @Override
    public void saveProject() {
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

    private void initEdgeScrollTimer() {
        edgeScrollTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (edgeScrollVelocity == 0 || activeDrag.ghost == null) {
                    stop();
                    return;
                }

                double contentWidth = tracksScrollPane.getContent().getBoundsInLocal().getWidth();
                double viewportWidth = tracksScrollPane.getViewportBounds().getWidth();
                double maxScrollX = contentWidth - viewportWidth;
                if (maxScrollX <= 0) return;

                double currentScrollX = tracksScrollPane.getHvalue() * maxScrollX;
                double newScrollX = Math.max(0, Math.min(maxScrollX, currentScrollX + edgeScrollVelocity));

                tracksScrollPane.setHvalue(newScrollX / maxScrollX);

                // Keep ghost in sync with mouse scene position as we scroll
                updateActiveDragGhost(lastDragSceneX, lastDragSceneY);
            }
        };
    }

    private void updateActiveDragGhost(double sceneX, double sceneY) {
        if (activeDrag.ghost == null) return;

        javafx.geometry.Point2D local = tracksPane.sceneToLocal(sceneX, sceneY);
        double rawX = local.getX() - activeDrag.dragOffsetX;
        double clampedX = Math.max(0, rawX);

        double ghostW = activeDrag.ghost.getPrefWidth();
        double snappedX = applySnap(clampedX, ghostW, activeDrag.currentTrackIdx);
        activeDrag.ghost.setLayoutX(snappedX);

        int newTrackIdx = trackIdxFromLocalY(local.getY());
        if (newTrackIdx != activeDrag.currentTrackIdx) {
            activeDrag.currentTrackIdx = newTrackIdx;
            double newY = newTrackIdx * (TRACK_HEIGHT + TRACK_SPACING) + 3;
            activeDrag.ghost.setLayoutY(newY);
        }
    }

    private void checkEdgeScroll(double sceneX, double sceneY) {
        lastDragSceneX = sceneX;
        lastDragSceneY = sceneY;

        if (activeDrag.ghost == null) {
            edgeScrollVelocity = 0;
            edgeScrollTimer.stop();
            return;
        }

        javafx.geometry.Point2D viewportPoint = tracksScrollPane.sceneToLocal(sceneX, sceneY);
        double vx = viewportPoint.getX();
        double vw = tracksScrollPane.getViewportBounds().getWidth();

        double threshold = 60.0;
        double maxSpeed = 12.0; // pixels per frame

        if (vx < threshold && vx > -threshold) { // Mouse is near left edge
            double intensity = (threshold - Math.max(0, vx)) / threshold;
            edgeScrollVelocity = -maxSpeed * intensity;
            edgeScrollTimer.start();
        } else if (vx > vw - threshold && vx < vw + threshold) { // Mouse is near right edge
            double intensity = (threshold - Math.max(0, vw - vx)) / threshold;
            edgeScrollVelocity = maxSpeed * intensity;
            edgeScrollTimer.start();
        } else {
            edgeScrollVelocity = 0;
            edgeScrollTimer.stop();
        }
    }

    private void triggerPlayAction()
    {
        if (isPlaying) {
            stopPlayback();
            playBtn.setGraphic(playIcon);
        } else {
            startPlayback();
            playBtn.setGraphic(pauseIcon);
        }
    }
    private void startPlayback() {
        if (isPlaying)
            return;
        isPlaying = true;
        lastTimerUpdate = System.nanoTime();
        playbackTimer.start();
        // Update play/pause button icon if needed

        double playheadX = currentTime * pixelsPerSecond;
        double contentWidth = tracksScrollPane.getContent().getBoundsInLocal().getWidth();
        double viewportWidth = tracksScrollPane.getViewportBounds().getWidth();
        double hValue = tracksScrollPane.getHvalue();
        double scrollX = hValue * (contentWidth - viewportWidth);

        // If playhead is not visible or "away", snap to 0.25 position
        if (playheadX < scrollX || playheadX > scrollX + viewportWidth) {
            double targetScrollX = Math.max(0, playheadX - viewportWidth * 0.25);
            double maxScrollX = contentWidth - viewportWidth;
            if (maxScrollX > 0) {
                tracksScrollPane.setHvalue(Math.min(1.0, targetScrollX / maxScrollX));
            }
        }
    }

    private void stopPlayback() {
        if (!isPlaying)
            return;
        isPlaying = false;
        playbackTimer.stop();
        lastTimerUpdate = 0;

        // Notify the renderer that we have paused
        if (timelineRenderer != null) {
            timelineRenderer.updateTime(currentTime, true);
        }
    }

    @Override
    public void updateCurrentTime(float newTime) {
        this.currentTime = Math.max(0, newTime);
        currentTimeLabel.setText(formatTimecode(currentTime));

        // Update playhead position
        updatePlayheadPosition();

        if (timelineRenderer != null) {
            timelineRenderer.updateTime(currentTime, !isPlaying);
        }

//        // Dynamically update property panel fields
//        propertyUpdaters.forEach(Runnable::run);

        // Auto-scroll if playing
        if (isPlaying) {
            double playheadX = currentTime * pixelsPerSecond;
            double contentWidth = tracksScrollPane.getContent().getBoundsInLocal().getWidth();
            double viewportWidth = tracksScrollPane.getViewportBounds().getWidth();
            double hValue = tracksScrollPane.getHvalue();
            double scrollX = hValue * (contentWidth - viewportWidth);

            if (playheadX - scrollX > viewportWidth * 0.8) {
                // Scroll to keep playhead at 0.8 mark
                double targetScrollX = playheadX - viewportWidth * 0.8;
                double maxScrollX = contentWidth - viewportWidth;
                if (maxScrollX > 0) {
                    tracksScrollPane.setHvalue(Math.min(1.0, targetScrollX / maxScrollX));
                }
            }

            if ((currentTime >= timeline.duration) || (currentTime <= 0f && isPlayingInReverse)) {
                currentTime = isPlayingInReverse ? timeline.duration : 0f;
                stopPlayback();
            }
        }
    }

    private void updatePlayheadPosition() {
        if (playheadLine == null || tracksScrollPane == null || tracksPane == null)
            return;

        double contentWidth = tracksScrollPane.getContent().getBoundsInLocal().getWidth();
        double viewportWidth = tracksScrollPane.getViewportBounds().getWidth();
        double hValue = tracksScrollPane.getHvalue();

        // scrollX is the pixel offset of the left edge of the viewport
        double scrollX = hValue * (contentWidth - viewportWidth);


        // Dynamically update property panel fields
        propertyUpdaters.forEach(Runnable::run);

        // Position relative to viewport left edge
        playheadLine.setTranslateX(currentTime * pixelsPerSecond - scrollX);

        if (tempTime >= 0) {
            ghostPlayheadLine.setTranslateX(tempTime * pixelsPerSecond - scrollX);
        }
    }

    void updateCurrentClipEnd() {
        float totalSeconds = 0;
        // 🧠 Recalculate max right edge of all clips in all tracks
        for (Track trackCpn : timeline.tracks) {
            for (int i = 0; i < trackCpn.clips.size(); i++) {
                Clip child = trackCpn.clips.get(i);
                if (child != null) { // It's a clip
                    if (totalSeconds < child.getStartTime() + child.getDuration()) {
                        totalSeconds = child.getStartTime() + child.getDuration();
                    }
                }
            }
        }

        durationLabel.setText(formatTimecode(totalSeconds));
        timeline.duration = totalSeconds;
    }

    private String formatTimecode(float seconds) {
        int h = (int) (seconds / 3600);
        int m = (int) ((seconds % 3600) / 60);
        int s = (int) (seconds % 60);
        int f = (int) ((seconds % 1) * 30); // 30fps assumption for display
        return String.format("%02d:%02d:%02d:%02d", h, m, s, f);
    }

    private String sanitizeKeybind(String keybind) {
        if (keybind == null) return "";
        return keybind.replace("Cmd+", "Meta+").replace("Ctrl+", "Control+");
    }

    private void closeWindow() {
        stopPlayback();
        saveProject();
        DoubleClipsDesktop.getInstance().closeEditor(this);
    }

    // ====================================================================
    // TOP BAR
    // ====================================================================
    private HBox buildTopBar() {
        HBox bar = new HBox(10);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 16, 8, 16));
        bar.getStyleClass().add("editor-topbar");

        Button backBtn = new Button();
        backBtn.setGraphic(new FontIcon(MaterialDesignK.KEYBOARD_RETURN));
        backBtn.getStyleClass().add("button-transparent");
        backBtn.setOnAction(e -> closeWindow());

        Label title = new Label(project.getProjectTitle());
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Undo / Redo at top (like CapCut)
        Button undoBtn = new Button();
        undoBtn.setGraphic(new FontIcon(MaterialDesignU.UNDO));
        undoBtn.getStyleClass().add("button-transparent");
        undoBtn.disableProperty().bind(historyManager.canUndoProperty().not());
        undoBtn.setOnAction(e -> historyManager.undo());

        Button redoBtn = new Button();
        redoBtn.setGraphic(new FontIcon(MaterialDesignR.REDO));
        redoBtn.getStyleClass().add("button-transparent");
        redoBtn.disableProperty().bind(historyManager.canRedoProperty().not());
        redoBtn.setOnAction(e -> historyManager.redo());

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
    // LEFT PANEL — Media Browser
    // ====================================================================
    private VBox buildLeftPanel() {
        VBox panel = new VBox();
        panel.setPrefWidth(320);
        panel.setMinWidth(240);
        panel.setMaxWidth(400);
        panel.getStyleClass().add("editor-left-panel");

        // --- Tool tab strip
        String[] tabLabels = { "Media", "Audio", "Text", "Stickers", "Effects", "Transitions" };
        HBox tabStrip = new HBox(0);
        tabStrip.getStyleClass().add("editor-tab-strip");
        ToggleGroup tabGroup = new ToggleGroup();

        for (String tab : tabLabels) {
            ToggleButton tb = new ToggleButton(tab);
            tb.setToggleGroup(tabGroup);
            tb.setUserData(tab);
            tb.getStyleClass().add("editor-tab");
            HBox.setHgrow(tb, Priority.ALWAYS);
            tb.setMaxWidth(Double.MAX_VALUE);
            tabStrip.getChildren().add(tb);
        }

        tabGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                String tabName = (String) newVal.getUserData();
                reloadLeftPanelContent(tabName);
            }
        });
        mediaTab = (ToggleButton) tabStrip.getChildren().get(0);
        mediaTab.setSelected(true);

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
        mediaGrid = new FlowPane(8, 8);
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
        mediaGridScroll.setStyle(
                "-fx-background-color: transparent; -fx-control-inner-background: transparent; -fx-border-color: transparent;");
        VBox.setVgrow(mediaGridScroll, Priority.ALWAYS);

        StackPane leftStack = new StackPane(mediaGridScroll);
        VBox.setVgrow(leftStack, Priority.ALWAYS);

        // --- Drop Overlay
        mediaDropOverlay = new VBox(20);
        mediaDropOverlay.setAlignment(Pos.CENTER);
        mediaDropOverlay.setStyle("-fx-background-color: rgba(0,0,0,0.7);");
        mediaDropOverlay.setVisible(false);
        mediaDropOverlay.setMouseTransparent(true);

        ImageView dropIcon = new ImageView(new Image(getClass().getResourceAsStream("/icons/import_media_graphic.png")));
        dropIcon.setFitWidth(150);
        dropIcon.setPreserveRatio(true);

        Label dropLabel = new Label("Drop here to import media");
        dropLabel.setStyle("-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold;");

        mediaDropOverlay.getChildren().addAll(dropIcon, dropLabel);
        leftStack.getChildren().add(mediaDropOverlay);

        panel.getChildren().addAll(tabStrip, importBar, leftStack);

        leftStack.setOnDragEntered(event -> {
            if (event.getDragboard().hasFiles() && mediaTab.isSelected()) {
                mediaDropOverlay.setVisible(true);
            }
        });

        leftStack.setOnDragExited(event -> {
            mediaDropOverlay.setVisible(false);
        });

        panel.setOnDragOver(event -> {
            if (event.getGestureSource() != panel && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            }
            event.consume();
        });

        panel.setOnDragDropped(event -> {
            mediaDropOverlay.setVisible(false);
            javafx.scene.input.Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                handleImportFiles(db.getFiles(), mediaGrid, -1f, -1);
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });

        return panel;
    }

    private void loadMediaGrid(FlowPane mediaGrid) {
        String clipDir = IOHelper.CombinePath(project.getProjectPath(), Constants.DEFAULT_CLIP_DIRECTORY);
        File dir = new File(clipDir);
        if (!dir.exists() || !dir.isDirectory())
            return;

        File[] files = dir.listFiles();
        if (files == null || files.length == 0)
            return;

        Task<List<Clip>> task = new Task<>() {
            @Override
            protected List<Clip> call() throws Exception {
                List<Clip> loadedClips = new ArrayList<>();
                for (File f : files) {
                    if (f.isDirectory() || f.getName().startsWith("."))
                        continue;

                    String filename = f.getName();
                    MediaHelper.MediaInfo info = MediaHelper.probeMediaInfo(f.getAbsolutePath());

                    String mime = Files.probeContentType(f.toPath());
                    ClipType type = ClipType.VIDEO;
                    if (mime != null) {
                        if (mime.startsWith("audio"))
                            type = ClipType.AUDIO;
                        else if (mime.startsWith("image"))
                            type = ClipType.IMAGE;
                    } else {
                        if (filename.endsWith(".mp3") || filename.endsWith(".wav"))
                            type = ClipType.AUDIO;
                        else if (filename.endsWith(".png") || filename.endsWith(".jpg"))
                            type = ClipType.IMAGE;
                    }

                    Clip clip = new Clip(filename, 0, info.duration, 0, type, info.hasAudio, info.width, info.height);
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
        if (files == null || files.isEmpty())
            return;
        handleImportFiles(files, mediaGrid, -1f, -1);
    }

    private void handleImportFiles(List<File> files, FlowPane mediaGrid, float startTime, int trackIdx) {
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
                    if (!clipDirFile.exists())
                        clipDirFile.mkdirs();

                    File targetFile = new File(clipDir, filename);
                    Files.copy(f.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                    MediaHelper.MediaInfo info = MediaHelper.probeMediaInfo(targetFile.getAbsolutePath());

                    String mime = Files.probeContentType(targetFile.toPath());
                    ClipType type = ClipType.VIDEO;
                    if (mime != null) {
                        if (mime.startsWith("audio"))
                            type = ClipType.AUDIO;
                        else if (mime.startsWith("image"))
                            type = ClipType.IMAGE;
                    } else {
                        if (filename.endsWith(".mp3") || filename.endsWith(".wav"))
                            type = ClipType.AUDIO;
                        else if (filename.endsWith(".png") || filename.endsWith(".jpg"))
                            type = ClipType.IMAGE;
                    }

                    Clip clip = new Clip(filename, 0, info.duration, 0, type, info.hasAudio, info.width, info.height);

                    String previewDir = IOHelper.CombinePath(project.getProjectPath(),
                            Constants.DEFAULT_PREVIEW_CLIP_DIRECTORY);
                    File previewDirFile = new File(previewDir);
                    if (!previewDirFile.exists())
                        previewDirFile.mkdirs();

                    String previewClipPath = IOHelper.CombinePath(previewDir, filename);

                    CountDownLatch latch = new CountDownLatch(1);

                    if (type == ClipType.VIDEO) {
                        CountDownLatch thumbLatch = new CountDownLatch(1);
                        String cmdThumb = "-i \"" + targetFile.getAbsolutePath() + "\" -vframes 1 -s 128x128 -y \""
                                + previewClipPath + ".jpg\"";
                        FFmpegEdit.runAnyCommand(cmdThumb, "Preview Thumb",
                                () -> thumbLatch.countDown(),
                                () -> thumbLatch.countDown(),
                                log -> {
                                }, stats -> {
                                });
                        thumbLatch.await();

                        String cmd = "-i \"" + targetFile.getAbsolutePath()
                                + "\" -vf \"scale=1280:-2\" -c:v libx264 -preset ultrafast -crf 32 -x264-params keyint=1 -an -y \""
                                + previewClipPath + "\"";
                        FFmpegEdit.runAnyCommand(cmd, "Preview Video",
                                () -> latch.countDown(),
                                () -> latch.countDown(),
                                log -> {
                                }, stats -> {
                                    if (stats.getTimeInMs() > 0 && info.duration > 0) {
                                        updateProgress(stats.getTimeInMs() / 1000.0, info.duration);
                                    }
                                });
                        latch.await();

                        if (info.hasAudio) {
                            CountDownLatch audioLatch = new CountDownLatch(1);
                            String audioPath = previewClipPath.substring(0, previewClipPath.lastIndexOf('.')) + ".wav";
                            String cmdAudio = "-i \"" + targetFile.getAbsolutePath()
                                    + "\" -vn -ac 1 -ar 22050 -c:a pcm_s16le -y \"" + audioPath + "\"";
                            FFmpegEdit.runAnyCommand(cmdAudio, "Preview Audio",
                                    () -> audioLatch.countDown(),
                                    () -> audioLatch.countDown(),
                                    log -> {
                                    }, stats -> {
                                    });
                            audioLatch.await();
                        }
                    } else if (type == ClipType.AUDIO) {
                        String audioPath = previewClipPath.substring(0, previewClipPath.lastIndexOf('.')) + ".wav";
                        String cmdAudio = "-i \"" + targetFile.getAbsolutePath()
                                + "\" -vn -ac 1 -ar 22050 -c:a pcm_s16le -y \"" + audioPath + "\"";
                        FFmpegEdit.runAnyCommand(cmdAudio, "Preview Audio",
                                () -> latch.countDown(),
                                () -> latch.countDown(),
                                log -> {
                                }, stats -> {
                                });
                        latch.await();
                    }

                    Platform.runLater(() -> {
                        addClipToMediaGrid(mediaGrid, clip);
                        if (startTime >= 0 && trackIdx >= 0) {
                            clip.startTime = startTime;
                            clip.trackIndex = trackIdx;
                            while (timeline.tracks.size() <= trackIdx) {
                                addNewTrack("Track " + (timeline.tracks.size() + 1));
                            }
                            timeline.tracks.get(trackIdx).addClip(clip);
                            timeline.tracks.get(trackIdx).sortClips();
                            saveProject();
                            refreshTimelineUI();
                        }
                    });
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
                imagePath = IOHelper.CombinePath(project.getProjectPath(), Constants.DEFAULT_PREVIEW_CLIP_DIRECTORY,
                        clip.getClipName() + ".jpg");
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
                FontIcon icon = new FontIcon(
                        clip.type == ClipType.VIDEO ? MaterialDesignM.MOVIE : MaterialDesignI.IMAGE);
                icon.setIconSize(32);
                icon.setIconColor(Color.WHITE);
                graphicNode = icon;
            }
        } else if (clip.type == ClipType.AUDIO) {
            FontIcon icon = new FontIcon(MaterialDesignM.MUSIC_NOTE);
            icon.setIconSize(32);
            icon.setIconColor(Color.WHITE);
            graphicNode = icon;
        } else if (clip.type == ClipType.TEXT) {
            FontIcon icon = new FontIcon(MaterialDesignF.FORMAT_TEXT);
            icon.setIconSize(32);
            icon.setIconColor(Color.WHITE);
            graphicNode = icon;
        } else if (clip.type == ClipType.EFFECT) {
            FontIcon icon = new FontIcon(MaterialDesignS.STAR);
            icon.setIconSize(32);
            icon.setIconColor(Color.WHITE);
            graphicNode = icon;
        } else {
            FontIcon icon = new FontIcon(MaterialDesignF.FILE_QUESTION);
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
            if (activeDrag.clip == null || !activeDrag.isNewClip)
                return;

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

            updateActiveDragGhost(e.getSceneX(), e.getSceneY());
            checkEdgeScroll(e.getSceneX(), e.getSceneY());

            e.consume();
        });

        box.setOnMouseReleased(e -> {
            edgeScrollTimer.stop();
            if (activeDrag.clip == null || !activeDrag.isNewClip)
                return;

            if (activeDrag.dragging && activeDrag.ghost != null) {
                double finalX = activeDrag.ghost.getLayoutX();
                int newTrackIdx = activeDrag.currentTrackIdx;

                tracksPane.getChildren().remove(activeDrag.ghost);

                javafx.geometry.Point2D spLocal = tracksScrollPane.sceneToLocal(e.getSceneX(), e.getSceneY());
                if (spLocal.getX() >= 0 && spLocal.getY() >= 0 && spLocal.getX() <= tracksScrollPane.getWidth()
                        && spLocal.getY() <= tracksScrollPane.getHeight()) {
                    float newStartTime = (float) (finalX / pixelsPerSecond);
                    activeDrag.clip.startTime = Math.max(0f, newStartTime);
                    activeDrag.clip.trackIndex = newTrackIdx;

                    historyManager.execute(new AddClipCommand(timeline, activeDrag.clip, newTrackIdx, () -> {
                        updateCurrentClipEnd();
                        refreshTimelineUI();
                        saveProject();
                    }));
                }
            }

            activeDrag.clip = null;
            activeDrag.ghost = null;
            activeDrag.dragging = false;
            activeDrag.isNewClip = false;
            e.consume();
        });
    }

    // ====================================================================
    // CENTER — Preview Player
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
            if (w <= 0 || h <= 0)
                return;

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
        durationLabel.setText(formatTimecode(timeline.duration)); // Mock duration

        Region pbLeft = new Region();
        HBox.setHgrow(pbLeft, Priority.ALWAYS);

        playBtn.setGraphic(playIcon);
        playBtn.getStyleClass().addAll("button-transparent", "play-button-main");
        playBtn.setOnAction(e -> {
            triggerPlayAction();
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
                fpsLabel, resLabel, hdrLabel, fullScreenBtn);

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
    // RIGHT PANEL — Properties / Smart Suggestions
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
        for (String t : new String[] { "Project", "Details" }) {
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

        String[] edits = { "Make colors better", "Make colors consistent", "Make volume consistent",
                "Make voice clearer" };
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

        propertyPanel = new PropertyPanel(this);
        ScrollPane rightScroll = new ScrollPane(propertyPanel);
        rightScroll.setFitToWidth(true);
        VBox.setVgrow(rightScroll, Priority.ALWAYS);
        rightScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        panel.getChildren().addAll(tabs, rightScroll);

        // Setup initial properties view
        updatePropertiesPane();

        return panel;
    }

    @Override
    public void updatePropertiesPane() {
        if (propertyPanel != null) {
            propertyUpdaters.clear();
            propertyPanel.update();
        }
    }

    // ====================================================================
    // BOTTOM — Timeline
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
        Button sliceTool = buildToolBtn(MaterialDesignS.SCISSORS_CUTTING);
        sliceTool.setOnAction(e -> handleSplit());

        Button trimLeft = buildToolBtn(MaterialDesignF.FORMAT_INDENT_DECREASE);
        Button trimRight = buildToolBtn(MaterialDesignF.FORMAT_INDENT_INCREASE);
        Button deleteTool = buildToolBtn(MaterialDesignT.TRASH_CAN_OUTLINE);
        deleteTool.setOnAction(e -> handleDelete());

        Region toolSpacer = new Region();
        HBox.setHgrow(toolSpacer, Priority.ALWAYS);

        // Right cluster (zoom, waveform toggle, snap)
        Label zoomLabel = new Label("Zoom");
        zoomLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
        zoomSlider = new Slider(5, 2000, 100); // 5px to 1000px per second
        zoomSlider.setPrefWidth(100);
        zoomSlider.getStyleClass().add("timeline-zoom-slider");
        zoomSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            pixelsPerSecond = newVal.floatValue();
            refreshTimelineUI();
        });

        Button keyframeBtn = buildToolBtn(MaterialDesignD.DIAMOND);
        keyframeBtn.setTooltip(new Tooltip("Add Keyframe at Playhead"));
        keyframeBtn.setOnAction(e -> handleAddKeyframe());

        Button clearKfBtn = buildToolBtn(MaterialDesignD.DIAMOND_OUTLINE);
        clearKfBtn.setTooltip(new Tooltip("Clear All Keyframes"));
        clearKfBtn.setOnAction(e -> handleClearKeyframes());

        Button textTool = buildToolBtn(MaterialDesignF.FORMAT_TEXT);
        textTool.setTooltip(new Tooltip("Add Text Clip"));
        textTool.setOnAction(e -> handleAddText());

        toolbar.getChildren().addAll(selectTool, sliceTool, trimLeft, trimRight, deleteTool, textTool, keyframeBtn, clearKfBtn,
                toolSpacer, zoomLabel, zoomSlider);

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
        buildRuler(8000); // TODO: Hardcoded 8000?
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
        tracksPane.setPrefHeight(0); // Will be updated by refreshTimelineUI

        tracksPane.setOnMouseClicked(e -> deselectAll());

        tracksPane.setOnDragOver(event -> {
            if (event.getGestureSource() != tracksPane && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);

                ghostPlayheadLine.setVisible(true);

                javafx.geometry.Point2D local = tracksPane.sceneToLocal(event.getSceneX(), event.getSceneY());
                double rawX = local.getX();
                double clampedX = Math.max(0, rawX);
                int trackIdx = trackIdxFromLocalY(local.getY());

                tempTime = (float) (clampedX / pixelsPerSecond);
                updatePlayheadPosition();

                if (activeDrag.ghost == null) {
                    Clip ghostClip = new Clip("Importing...", 0, 5.0f, 0, ClipType.VIDEO, false, 1280, 720);
                    ClipNode ghost = new ClipNode(ghostClip);
                    ghost.getStyleClass().add("clip-node-ghost");
                    ghost.setOpacity(0.4);
                    double targetWidth = 5.0f * pixelsPerSecond;
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

                activeDrag.ghost.setLayoutX(clampedX);
                activeDrag.ghost.setLayoutY(trackIdx * (TRACK_HEIGHT + TRACK_SPACING) + 3);
            }
            event.consume();
        });

        tracksPane.setOnDragExited(event -> {
            if (event.getDragboard().hasFiles()) {
                ghostPlayheadLine.setVisible(false);
                tempTime = -1;
                updatePlayheadPosition();
                if (activeDrag.ghost != null) {
                    tracksPane.getChildren().remove(activeDrag.ghost);
                    activeDrag.ghost = null;
                }
            }
            event.consume();
        });

        tracksPane.setOnDragDropped(event -> {
            javafx.scene.input.Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                javafx.geometry.Point2D local = tracksPane.sceneToLocal(event.getSceneX(), event.getSceneY());
                float dropTime = (float) (Math.max(0, local.getX()) / pixelsPerSecond);
                int dropTrack = trackIdxFromLocalY(local.getY());

                handleImportFiles(db.getFiles(), this.mediaGrid, dropTime, dropTrack);
                success = true;
            }

            ghostPlayheadLine.setVisible(false);
            tempTime = -1;
            updatePlayheadPosition();
            if (activeDrag.ghost != null) {
                tracksPane.getChildren().remove(activeDrag.ghost);
                activeDrag.ghost = null;
            }

            event.setDropCompleted(success);
            event.consume();
        });

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

        // Sync Ruler and Tracks scrolling
        rulerScrollPane.hvalueProperty().bindBidirectional(tracksScrollPane.hvalueProperty());

        // Zoom Gestures
        rulerAndTracks.addEventFilter(javafx.scene.input.ZoomEvent.ZOOM, e -> {
            double zoomFactor = e.getZoomFactor();
            double newZoom = zoomSlider.getValue() * zoomFactor;
            zoomSlider.setValue(Math.min(zoomSlider.getMax(), Math.max(zoomSlider.getMin(), newZoom)));
            e.consume();
        });

        rulerAndTracks.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            // TODO: Move the keyboard to keybind
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

    private float getRulerInterval(float pixelsPerSecond) {
        // Preferred intervals in seconds
        float[] intervals = {
                1 / 30f, 2 / 30f, 5 / 30f, 10 / 30f, 15 / 30f, // Frames: 1, 2, 5, 10, 15
                1f, 2f, 4f, 8f, 16f, 32f, 64f, 128f, 256f, 512f, 1024f // Seconds: Doubling
        };

        // Aim for at least 80 pixels between major ticks for readability
        float minSpacing = 80f;
        for (float interval : intervals) {
            if (interval * pixelsPerSecond >= minSpacing) {
                return interval;
            }
        }
        return intervals[intervals.length - 1];
    }

    private String formatRulerLabel(float t, float interval) {
        int totalFrames = Math.round(t * 30f);
        int sec = totalFrames / 30;
        int f = totalFrames % 30;

        if (interval >= 1.0f) {
            if (sec == 0 && f == 0)
                return "0s";
            int m = sec / 60;
            int s = sec % 60;
            if (m > 0)
                return m + "m" + s + "s";
            return s + "s";
        } else {
            if (f == 0)
                return sec + "s";
            return f + "f";
        }
    }

    private void buildRuler(double width) {
        Pane ruler = new Pane();
        ruler.setPrefWidth(width);
        ruler.setPrefHeight(30);
        ruler.setPickOnBounds(true);
        ruler.getStyleClass().add("timeline-ruler-pane");

        float rulerInterval = getRulerInterval(pixelsPerSecond);
        float majorPixels = pixelsPerSecond * rulerInterval;
        float visibleDuration = (float) (width / pixelsPerSecond);
        long steps = (long) Math.ceil(visibleDuration / rulerInterval);

        int framesInInterval = Math.round(rulerInterval * 30f);
        float pixelsPerFrame = pixelsPerSecond / 30f;

        for (long step = 0; step <= steps; step++) {
            float t = step * rulerInterval;
            double x = t * pixelsPerSecond;

            // Major tick
            Line tick = new Line(x, 10, x, 30);
            tick.getStyleClass().add("ruler-tick-major");
            ruler.getChildren().add(tick);

            // Label
            String lbl = formatRulerLabel(t, rulerInterval);
            javafx.scene.text.Text label = new javafx.scene.text.Text(x + 2, 9, lbl);
            label.getStyleClass().add("ruler-text");
            ruler.getChildren().add(label);

            // Minor ticks (frames) between this major tick and the next
            if (step < steps) {
                for (int f = 1; f < framesInInterval; f++) {
                    float subT = t + (f / 30f);
                    double subX = subT * pixelsPerSecond;

                    // Only draw if there's enough space (at least 4px between minor ticks)
                    if (pixelsPerFrame >= 4f) {
                        Line subTick = new Line(subX, 18, subX, 30);
                        subTick.getStyleClass().add("ruler-tick-minor");
                        subTick.setStyle("-fx-opacity: 0.5;");
                        ruler.getChildren().add(subTick);

                        // Optional: Frame labels if very zoomed in
                        if (pixelsPerFrame >= 40f && framesInInterval > 1) {
                            javafx.scene.text.Text subLabel = new javafx.scene.text.Text(subX + 1.5, 9, f + "f");
                            subLabel.getStyleClass().add("ruler-text");
                            subLabel.setStyle("-fx-opacity: 0.6;");
                            ruler.getChildren().add(subLabel);
                        }
                    } else if (framesInInterval >= 10 && f % (framesInInterval / 2) == 0) {
                        // Midpoint tick if too dense for every frame but wide enough for one
                        Line subTick = new Line(subX, 22, subX, 30);
                        subTick.getStyleClass().add("ruler-tick-minor");
                        subTick.setStyle("-fx-opacity: 0.4;");
                        ruler.getChildren().add(subTick);
                    }
                }
            }
        }

        ruler.setOnMouseEntered(e -> ghostPlayheadLine.setVisible(true));
        ruler.setOnMouseMoved(e -> {
            double x = e.getX();
            tempTime = (float) (x / pixelsPerSecond);
            currentTimeLabel.setText(formatTimecode(tempTime));
            updatePlayheadPosition();
            if (timelineRenderer != null) {
                timelineRenderer.updateTime(tempTime, true);
            }
        });
        ruler.setOnMouseExited(e -> {
            ghostPlayheadLine.setVisible(false);
            tempTime = -1;
            currentTimeLabel.setText(formatTimecode(currentTime));
            updatePlayheadPosition();
            if (timelineRenderer != null) {
                timelineRenderer.updateTime(currentTime, !isPlaying);
            }
        });
        ruler.setOnMouseClicked(e -> {
            updateCurrentTime((float) (e.getX() / pixelsPerSecond));
        });

        rulerScrollPane.setContent(ruler);
    }

    private void addNewTrack(String name) {
        Track track = new Track();
        executePropertyChange("Add Track", () -> {
            timeline.addTrack(track);
            refreshTrackHeaders();
            refreshTimelineUI();
            saveProject();
        }, () -> {
            timeline.removeTrack(track);
            refreshTrackHeaders();
            refreshTimelineUI();
            saveProject();
        });
    }

    private void addClipToTrack(Track track, Clip clip) {
        track.addClip(clip);
        renderClipUI(track, clip);
    }

    private void generateThumbnailsForNode(ClipNode node) {
        Clip clip = node.getContainerClip();
        if (clip.type == ClipType.VIDEO) {
            thumbnailExecutor.submit(() -> {
                int tileCount = node.getTileCount();
                if (tileCount <= 0)
                    return;
                float tileDuration = (float) (clip.duration / tileCount);
                for (int i = 0; i < tileCount; i++) {
                    // Stop extracting if node was removed or hidden
                    if (node.getParent() == null)
                        return;

                    float time = clip.startClipTrim + (i * tileDuration);
                    Image img = decodeVideoFrame(clip, time);
                    if (img != null) {
                        int finalI = i;
                        javafx.application.Platform.runLater(() -> node.setThumbnailImage(finalI, img));
                    }
                }
            });
        } else if (clip.type == ClipType.AUDIO) {
            thumbnailExecutor.submit(() -> {
                Image img = AudioUtils.generateAudioWaveformImage(
                        clip.getAbsolutePreviewPath(project, ".wav"), clip, pixelsPerSecond, (int) TRACK_HEIGHT, 1, 0); // 2
                                                                                                                        // 1
                if (img != null) {
                    javafx.application.Platform.runLater(() -> node.setSingleThumbnail(img));
                }
            });
        } else if (clip.type == ClipType.IMAGE) {
            thumbnailExecutor.submit(() -> {
                try {
                    java.io.File file = new java.io.File(clip.getAbsolutePath(project));
                    if (file.exists()) {
                        Image img = new Image(file.toURI().toString(), -1, TRACK_HEIGHT, true, true);
                        javafx.application.Platform.runLater(() -> node.setSingleThumbnail(img));
                    }
                } catch (Exception ignored) {
                }
            });
        } else {
            thumbnailExecutor.submit(() -> {
                javafx.scene.image.WritableImage empty = new javafx.scene.image.WritableImage(1, (int) TRACK_HEIGHT);
                javafx.scene.paint.Color fill = clip.type == ClipType.TEXT ? javafx.scene.paint.Color.web("#AAFF0000")
                        : javafx.scene.paint.Color.web("#AAFFFF00");
                for (int y = 0; y < TRACK_HEIGHT; y++)
                    empty.getPixelWriter().setColor(0, y, fill);
                javafx.application.Platform.runLater(() -> node.setSingleThumbnail(empty));
            });
        }
    }

    private Image decodeVideoFrame(Clip clip, float clipTime) {
        String previewPath = clip.getAbsolutePreviewPath(project);
        java.io.File previewFile = new java.io.File(previewPath);
        if (!previewFile.exists()) {
            // Try with .mp4 extension
            String mp4Preview = clip.getAbsolutePreviewPath(project, ".mp4");
            java.io.File mp4File = new java.io.File(mp4Preview);
            if (mp4File.exists()) {
                previewFile = mp4File;
            } else {
                previewFile = new java.io.File(clip.getAbsolutePath(project));
                if (!previewFile.exists())
                    return null;
            }
        }

        // Standard low-res for thumbnails - Ensure EVEN dimensions for FFmpeg
        int sampleSize = com.vanvatcorporation.doubleclips.constants.Constants.SAMPLE_SIZE_PREVIEW_CLIP;
        int w = (1280 / sampleSize) & ~1; // Force even
        int h = (720 / sampleSize) & ~1; // Force even
        if (w <= 0)
            w = 80;
        if (h <= 0)
            h = 45; // Wait, 45 is odd. Let's use 44 or 46.
        if ((h % 2) != 0)
            h++;

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(com.vanvatcorporation.doubleclips.FFmpegEdit.getFfmpegPath());
        cmd.add("-accurate_seek");
        cmd.add("-ss");
        cmd.add(String.format(java.util.Locale.US, "%.6f", clipTime));
        cmd.add("-i");
        cmd.add(previewFile.getAbsolutePath());
        cmd.add("-vframes");
        cmd.add("1");
        cmd.add("-vf");
        cmd.add("scale=" + w + ":" + h);
        cmd.add("-f");
        cmd.add("rawvideo");
        cmd.add("-pix_fmt");
        cmd.add("bgra");
        cmd.add("pipe:1");

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();

            int expectedBytes = w * h * 4;
            byte[] buf;
            try (java.io.InputStream is = proc.getInputStream()) {
                buf = is.readNBytes(expectedBytes);
            }
            proc.destroy(); // Ensure process is killed

            if (buf.length == expectedBytes) {
                int[] pixels = new int[w * h];
                for (int i = 0; i < pixels.length; i++) {
                    int base = i * 4;
                    int b = buf[base] & 0xFF;
                    int g = buf[base + 1] & 0xFF;
                    int r = buf[base + 2] & 0xFF;
                    int a = buf[base + 3] & 0xFF;
                    pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
                }
                javafx.scene.image.WritableImage fxImage = new javafx.scene.image.WritableImage(w, h);
                fxImage.getPixelWriter().setPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(),
                        pixels, 0, w);
                return fxImage;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public void refreshTimelineUI() {
        timeline.recalculateDuration();
        project.setProjectDuration((long) (timeline.duration * 1000));

        double totalDuration = timeline.duration;
        double contentWidth = Math.max(1200, totalDuration * pixelsPerSecond + 1000); // Add 1000px padding at end

        // Refresh Ruler
        buildRuler(contentWidth);

        // Refresh Clips and Tracks
        tracksPane.getChildren().clear();
        tracksPane.setPrefWidth(contentWidth);
        tracksPane.setPrefHeight(timeline.tracks.size() * (TRACK_HEIGHT + TRACK_SPACING));

        for (Track track : timeline.tracks) {
            // Add track band
            double y = track.timelineIndex * (TRACK_HEIGHT + TRACK_SPACING);
            Rectangle band = new Rectangle(0, y, contentWidth, TRACK_HEIGHT);
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

        if (selectedClip == clip)
            node.setSelected(true);

        setupClipInteraction(node);
        node.setOnKeyframeClicked(kf -> {
            updateCurrentTime(kf.getGlobalTime(clip));
            refreshTimelineUI();
        });
        node.setOnKeyframeMoved((kf, oldTime, newTime) -> {
            if (oldTime == newTime) return;
            executePropertyChange("Move Keyframe", () -> {
                kf.setLocalTime(newTime);
                clip.keyframes.sortKeyframe();
                refreshTimelineUI();
                updatePropertiesPane();
                saveProject();
            }, () -> {
                kf.setLocalTime(oldTime);
                clip.keyframes.sortKeyframe();
                refreshTimelineUI();
                updatePropertiesPane();
                saveProject();
            });
        });
        node.setOnKeyframesModified(() -> {
            saveProject();
            refreshTimelineUI();
            updatePropertiesPane();
        });
        tracksPane.getChildren().add(node);
        clip.viewRef = node;

        // Render keyframe diamonds
        node.updateKeyframes(pixelsPerSecond);

        // Transition cube: show between this clip and the next if they are touching
        renderTransitionCubeIfNeeded(track, clip);

        // Start generating thumbnails for this node
        generateThumbnailsForNode(node);

        // --- Trim Handles Support ---
        node.setupTrimInteractions(pixelsPerSecond);
        node.setOnTrimFinished((c, os, ns, od, nd, ost, nst, oet, net) -> {
            if (os == ns && od == nd && ost == nst && oet == net) return;
            historyManager.execute(new TrimClipCommand(timeline, c,
                    os, ns, od, nd, ost, nst, oet, net,
                    () -> {
                        updateCurrentClipEnd();
                        refreshTimelineUI();
                        updatePropertiesPane();
                        saveProject();
                    }));
        });
    }

    // =========================================================================
    // Transition cube rendering
    // =========================================================================

    private static final double TRANSITION_CUBE_SIZE = 16.0;
    private static final double SNAP_TOLERANCE = 0.05f; // seconds — clips this close are "touching"

    /**
     * If {@code clip} is immediately followed by another clip in the same track
     * (gap ≤ SNAP_TOLERANCE seconds), draw a small cube at the join point.
     * Clicking the cube selects the transition and shows its properties.
     */
    private void renderTransitionCubeIfNeeded(Track track, Clip clip) {
        // Find the next clip in this track (sorted by startTime)
        Clip next = null;
        for (Clip c : track.clips) {
            if (c == clip)
                continue;
            float gap = c.startTime - (clip.startTime + clip.duration);
            if (gap >= -SNAP_TOLERANCE && gap <= SNAP_TOLERANCE) {
                if (next == null || c.startTime < next.startTime)
                    next = c;
            }
        }
        if (next == null)
            return;

        // Ensure the transition data object exists on the source clip
        if (clip.endTransition == null) {
            clip.endTransition = new TransitionClip(clip, next, 0.5f);
        }
        clip.endTransitionEnabled = true;

        // Position of the cube: horizontally at the right edge of clip, vertically
        // centred
        double trackY = track.timelineIndex * (TRACK_HEIGHT + TRACK_SPACING);
        double cubeX = (clip.startTime + clip.duration) * pixelsPerSecond - TRANSITION_CUBE_SIZE / 2.0;
        double cubeY = trackY + (TRACK_HEIGHT / 2.0) - (TRANSITION_CUBE_SIZE / 2.0);

        Rectangle cube = new Rectangle(cubeX, cubeY, TRANSITION_CUBE_SIZE, TRANSITION_CUBE_SIZE);
        cube.setArcWidth(3);
        cube.setArcHeight(3);
        cube.getStyleClass().add("transition-cube");
        cube.setFill(Color.web("#5C67FF"));
        cube.setStroke(Color.web("#9BA3FF"));
        cube.setStrokeWidth(1.5);
        cube.setUserData(clip); // tag so we know which clip owns it

        // Hover highlight
        cube.setOnMouseEntered(e -> cube.setFill(Color.web("#7B85FF")));
        cube.setOnMouseExited(e -> cube.setFill(Color.web("#5C67FF")));

        // Click → select transition and show in right panel
        Clip finalClip = clip;
        cube.setOnMouseClicked(e -> {
            selectedTransitionSourceClip = finalClip;
            selectedClip = null; // deselect any clip
            updatePropertiesPane();
            e.consume();
        });

        tracksPane.getChildren().add(cube);
    }

    // =========================================================================
    // Keyframe helpers
    // =========================================================================

    @Override
    public void handleAddKeyframe() {
        if (selectedClip == null)
            return;
        Clip clip = selectedClip;

        // localTime of playhead within this clip
        float localTime = currentTime - clip.startTime;
        if (localTime < 0 || localTime > clip.duration)
            return;

        // Build keyframe snapshot from current clip properties (copy constructor)
        Keyframe kf = new Keyframe(localTime,
                new VideoProperties(clip.videoProperties),
                EasingType.NONE);

        // Prevent duplicate at same time
        boolean exists = clip.keyframes.keyframes.stream()
                .anyMatch(k -> Math.abs(k.getLocalTime() - localTime) < 0.001f);
        if (exists)
            return;

        executePropertyChange("Add Keyframe", () -> {
            clip.keyframes.keyframes.add(kf);
            clip.keyframes.sortKeyframe();
            if (clip.viewRef instanceof ClipNode cn) {
                cn.updateKeyframes(pixelsPerSecond);
            }
            saveProject();
            updatePropertiesPane();
        }, () -> {
            clip.keyframes.keyframes.remove(kf);
            if (clip.viewRef instanceof ClipNode cn) {
                cn.updateKeyframes(pixelsPerSecond);
            }
            saveProject();
            updatePropertiesPane();
        });
    }

    @Override
    public void handleClearKeyframes() {
        if (selectedClip == null)
            return;
        Clip clip = selectedClip;
        java.util.List<Keyframe> oldKfs = new java.util.ArrayList<>(clip.keyframes.keyframes);

        executePropertyChange("Clear Keyframes", () -> {
            clip.keyframes.keyframes.clear();
            if (clip.viewRef instanceof ClipNode cn) {
                cn.clearKeyframeKnots();
            }
            saveProject();
            updatePropertiesPane();
        }, () -> {
            clip.keyframes.keyframes.addAll(oldKfs);
            clip.keyframes.sortKeyframe();
            if (clip.viewRef instanceof ClipNode cn) {
                cn.updateKeyframes(pixelsPerSecond);
            }
            saveProject();
            updatePropertiesPane();
        });
    }

    @Override
    public void handleImportKeyframes() {
        if (selectedClip == null) return;
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import Keyframes");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
        File file = fileChooser.showOpenDialog(this);
        if (file != null) {
            try (Reader reader = new FileReader(file)) {
                Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
                List<Keyframe> importedKfs = gson.fromJson(reader, new TypeToken<List<Keyframe>>(){}.getType());
                if (importedKfs != null) {
                    List<Keyframe> oldKfs = new ArrayList<>(selectedClip.keyframes.keyframes);
                    executePropertyChange("Import Keyframes", () -> {
                        selectedClip.keyframes.keyframes.clear();
                        selectedClip.keyframes.keyframes.addAll(importedKfs);
                        selectedClip.keyframes.sortKeyframe();
                        if (selectedClip.viewRef instanceof ClipNode cn) {
                            cn.updateKeyframes(pixelsPerSecond);
                        }
                        saveProject();
                        updatePropertiesPane();
                    }, () -> {
                        selectedClip.keyframes.keyframes.clear();
                        selectedClip.keyframes.keyframes.addAll(oldKfs);
                        selectedClip.keyframes.sortKeyframe();
                        if (selectedClip.viewRef instanceof ClipNode cn) {
                            cn.updateKeyframes(pixelsPerSecond);
                        }
                        saveProject();
                        updatePropertiesPane();
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
                Alert alert = new Alert(Alert.AlertType.ERROR, "Failed to import keyframes: " + e.getMessage());
                alert.show();
            }
        }
    }

    @Override
    public void handleExportKeyframes() {
        if (selectedClip == null) return;
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Keyframes");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
        fileChooser.setInitialFileName(selectedClip.getClipName() + "_keyframes.json");
        File file = fileChooser.showSaveDialog(this);
        if (file != null) {
            try (Writer writer = new FileWriter(file)) {
                Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().setPrettyPrinting().create();
                gson.toJson(selectedClip.keyframes.keyframes, writer);
            } catch (Exception e) {
                e.printStackTrace();
                Alert alert = new Alert(Alert.AlertType.ERROR, "Failed to export keyframes: " + e.getMessage());
                alert.show();
            }
        }
    }

    // =========================================================================
    // Clip Interaction — CapCut-style: click = select, drag = immediate move
    // =========================================================================
    private void setupClipInteraction(ClipNode node) {
        Clip clip = node.getContainerClip();

        node.setOnMousePressed(e -> {
            // Select on press (feels snappier than waiting for click)
            selectClip(clip);

            // Prepare drag context
            activeDrag.clip = clip;
            activeDrag.currentTrackIdx = clip.trackIndex;
            activeDrag.dragOffsetX = e.getX(); // offset within the node
            activeDrag.dragging = false;
            activeDrag.ghost = null;
            activeDrag.isNewClip = false;
            e.consume();
        });

        node.setOnMouseDragged(e -> {
            if (activeDrag.clip != clip || activeDrag.isNewClip)
                return;

            // Create ghost on first drag pixel (CapCut style — no threshold)
            if (!activeDrag.dragging) {
                activeDrag.dragging = true;
                node.setVisible(false); // hide original
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

            updateActiveDragGhost(e.getSceneX(), e.getSceneY());
            checkEdgeScroll(e.getSceneX(), e.getSceneY());

            e.consume();
        });

        node.setOnMouseReleased(e -> {
            edgeScrollTimer.stop();
            if (activeDrag.clip != clip || activeDrag.isNewClip)
                return;

            if (activeDrag.dragging && activeDrag.ghost != null) {
                float oldStartTime = clip.startTime;
                int oldTrackIndex = clip.trackIndex;
                float newStartTime = (float) (activeDrag.ghost.getLayoutX() / pixelsPerSecond);
                int newTrackIndex = activeDrag.currentTrackIdx;

                // Remove ghost
                tracksPane.getChildren().remove(activeDrag.ghost);

                historyManager.execute(new MoveClipCommand(timeline, clip, oldStartTime, newStartTime, oldTrackIndex, newTrackIndex, () -> {
                    updateCurrentClipEnd();
                    refreshTimelineUI();
                    saveProject();
                }));
            }

            // Reset drag state
            activeDrag.clip = null;
            activeDrag.ghost = null;
            activeDrag.dragging = false;
            node.setVisible(true);

            // Refresh so clip redraws at committed position
            refreshTimelineUI();
            e.consume();
        });
    }

    /** Map a Y position in tracksPane-local coords to a track index. */
    private int trackIdxFromLocalY(double localY) {
        int idx = (int) (localY / (TRACK_HEIGHT + TRACK_SPACING));
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
        if (Math.abs(ghostX - playheadX) < SNAP_THRESHOLD)
            return playheadX;
        if (Math.abs(ghostEnd - playheadX) < SNAP_THRESHOLD)
            return playheadX - ghostWidth;

        // 2. Snap to clip edges on current + neighbour tracks
        for (int j = 0; j < timeline.tracks.size(); j++) {
            if (Math.abs(j - currentTrackIdx) > 1)
                continue; // only neighbours
            for (Clip other : timeline.tracks.get(j).clips) {
                if (other == activeDrag.clip)
                    continue;
                double otherStart = other.startTime * pixelsPerSecond;
                double otherEnd = (other.startTime + other.duration) * pixelsPerSecond;

                if (Math.abs(ghostX - otherEnd) < SNAP_THRESHOLD)
                    return otherEnd;
                if (Math.abs(ghostEnd - otherStart) < SNAP_THRESHOLD)
                    return otherStart - ghostWidth;
            }
        }
        return ghostX;
    }

    private void selectClip(Clip clip) {
        // Deselect previous
        if (selectedClip != null && selectedClip.viewRef instanceof ClipNode prev) {
            prev.setSelected(false);
        }

        // Move playhead at the beginning of the clip
        if(currentTime < clip.startTime) {
            updateCurrentTime(clip.startTime);
        }

        selectedClip = clip;
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
        selectedClip = null;
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

    @Override
    public void executePropertyChange(String name, Runnable redo, Runnable undo) {
        historyManager.execute(new PropertyChangeCommand(name, redo, undo));
    }

    private void handleAddText() {
        Clip textClip = new Clip("Text", currentTime, 5.0f, 0, ClipType.TEXT, false, 1280, 720);
        textClip.textContent = "New Text";
        textClip.fontSize = 48;
        
        historyManager.execute(new AddClipCommand(timeline, textClip, 0, () -> {
            refreshTimelineUI();
            saveProject();
        }));
    }

    private void reloadLeftPanelContent(String tabName) {
        mediaGrid.getChildren().clear();
        switch (tabName) {
            case "Media":
                loadMediaGrid(mediaGrid);
                break;
            case "Text":
                loadTextPresets();
                break;
            case "Effects":
                loadEffectSamples();
                break;
            default:
                Label placeholder = new Label(tabName + " coming soon!");
                placeholder.setStyle("-fx-text-fill: -color-fg-muted;");
                mediaGrid.getChildren().add(placeholder);
                break;
        }
    }

    private void loadTextPresets() {
        String[] presets = {"Default Text", "Small Text", "Big Text"};
        float[] sizes = {48, 24, 96};
        
        for (int i = 0; i < presets.length; i++) {
            Clip textClip = new Clip(presets[i], 0, 5.0f, 0, ClipType.TEXT, false, 1280, 720);
            textClip.textContent = presets[i];
            textClip.fontSize = sizes[i];
            addClipToMediaGrid(mediaGrid, textClip);
        }
    }

    private void loadEffectSamples() {
        String[] effects = {"B&W", "Vintage", "Blur", "Glow"};
        for (String fx : effects) {
            Clip effectClip = new Clip(fx, 0, 5.0f, 0, ClipType.EFFECT, false, 1280, 720);
            effectClip.effect = new EffectTemplate(fx.toLowerCase(), 5.0f, 0);
            addClipToMediaGrid(mediaGrid, effectClip);
        }
    }

    private void splitClipProxy(Clip clip) {
        historyManager.execute(new SplitClipCommand(timeline, clip, currentTime, () -> {
            refreshTimelineUI();
            saveProject();
        }));
    }

    @Override
    public void refreshTrackHeaders() {
        trackHeadersContainer.getChildren().clear();
        for (Track t : timeline.tracks) {
            trackHeadersContainer.getChildren().add(buildTrackHeader("Track " + (t.timelineIndex + 1)));
        }
    }

    private void handleDelete() {
        if (selectedClip != null) {
            Clip clipToDelete = selectedClip;
            historyManager.execute(new DeleteClipCommand(timeline, clipToDelete, () -> {
                refreshTimelineUI();
                saveProject();
            }));
            selectedClip = null;
        } else if (selectedTrack != null) {
            Track trackToDelete = selectedTrack;
            historyManager.execute(new PropertyChangeCommand("Delete Track",
                    () -> {
                        timeline.removeTrack(trackToDelete);
                        selectedTrack = null;
                        refreshTrackHeaders();
                        refreshTimelineUI();
                        saveProject();
                    },
                    () -> {
                        timeline.addTrack(trackToDelete);
                        refreshTrackHeaders();
                        refreshTimelineUI();
                        saveProject();
                    }));
        }
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
