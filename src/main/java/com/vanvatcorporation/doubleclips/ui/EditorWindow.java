package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.data.*;
import com.vanvatcorporation.doubleclips.data.editing.*;
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
    private Slider zoomSlider;

    // --- Scroll sync ---
    private final ScrollPane rulerScrollPane = new ScrollPane();
    private final ScrollPane tracksScrollPane = new ScrollPane();

    public EditorWindow(ProjectData project) {
        this.project = project;
        this.setTitle(project.getProjectTitle() + " — DoubleClips");
        this.setWidth(1440);
        this.setHeight(900);
        this.setMinWidth(1024);
        this.setMinHeight(640);

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
        
        // Load persistences
        this.timeline = ProjectRepository.getInstance().loadTimeline(project);
        this.videoSettings = ProjectRepository.getInstance().loadVideoSettings(project);

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
    }

    private void updateCurrentTime(float newTime) {
        this.currentTime = Math.max(0, newTime);
        currentTimeLabel.setText(formatTimecode(currentTime));
        
        // Update playhead position
        if (playheadLine != null) {
            playheadLine.setTranslateX(currentTime * pixelsPerSecond);
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

        panel.getChildren().addAll(tabStrip, importBar, mediaGrid);
        return panel;
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

        java.io.File previewFile = new java.io.File(project.getProjectPath(), "preview.png");
        if (previewFile.exists()) {
            ImageView iv = new ImageView(new Image(previewFile.toURI().toString()));
            iv.setPreserveRatio(true);
            iv.fitWidthProperty().bind(canvas.widthProperty().subtract(32));
            iv.fitHeightProperty().bind(canvas.heightProperty().subtract(32));
            canvas.getChildren().add(iv);
        }

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
        VBox.setVgrow(trackHeadersContainer, Priority.ALWAYS);
        trackSidebar.getChildren().addAll(sidebarTop, trackHeadersContainer);

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

        // Sync horizontal scroll
        rulerScrollPane.hvalueProperty().bindBidirectional(tracksScrollPane.hvalueProperty());

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
        playheadLine.setManaged(false); // We handle position via setTranslateX
        
        Pane playheadOverlay = new Pane(playheadLine);
        playheadOverlay.setMouseTransparent(true);
        tracksWithPlayhead.getChildren().add(playheadOverlay);

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
                double zoomFactor = 1.0;
                if (delta > 0) zoomFactor = 1.1;
                else if (delta < 0) zoomFactor = 0.9;
                
                if (zoomFactor != 1.0) {
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
        
        double x = clip.startTime * pixelsPerSecond;
        double y = track.timelineIndex * (TRACK_HEIGHT + TRACK_SPACING);
        double w = clip.duration * pixelsPerSecond;
        double h = TRACK_HEIGHT - 10; // slightly smaller than track height
        
        Rectangle clipRect = new Rectangle(x, y + 5, w, h);
        clipRect.getStyleClass().add("timeline-clip");
        clipRect.setArcWidth(8);
        clipRect.setArcHeight(8);
        
        Label label = new Label(clip.getClipName());
        label.getStyleClass().add("ruler-text"); // reusing font style
        label.setLayoutX(x + 5);
        label.setLayoutY(y + 8);
        
        tracksPane.getChildren().addAll(clipRect, label);
        clip.viewRef = clipRect;
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
        
        // Update playhead
        updateCurrentTime(currentTime);
    }

    private void renderClipUI(Track track, Clip clip) {
        double x = clip.startTime * pixelsPerSecond;
        double y = track.timelineIndex * (TRACK_HEIGHT + TRACK_SPACING);
        double w = clip.duration * pixelsPerSecond;
        double h = TRACK_HEIGHT - 10;
        
        Rectangle clipRect = new Rectangle(x, y + 5, w, h);
        clipRect.getStyleClass().add("timeline-clip");
        if (selectedClip == clip) {
            clipRect.getStyleClass().add("timeline-clip-selected");
        }
        clipRect.setArcWidth(8);
        clipRect.setArcHeight(8);
        
        clipRect.setOnMouseClicked(e -> {
            selectClip(clip);
            e.consume(); // prevent deselectAll from container
        });
        
        Label label = new Label(clip.getClipName());
        label.getStyleClass().add("ruler-text");
        label.setLayoutX(x + 5);
        label.setLayoutY(y + 8);
        label.setMouseTransparent(true);
        
        tracksPane.getChildren().addAll(clipRect, label);
        clip.viewRef = clipRect;
    }

    private void selectClip(Clip clip) {
        if (selectedClip != null && selectedClip.viewRef instanceof Rectangle) {
            ((Rectangle) selectedClip.viewRef).getStyleClass().remove("timeline-clip-selected");
        }
        
        selectedClip = clip;
        selectedTrack = timeline.tracks.get(clip.trackIndex);
        
        if (clip.viewRef instanceof Rectangle) {
            ((Rectangle) clip.viewRef).getStyleClass().add("timeline-clip-selected");
        }
        
        System.out.println("Selected clip: " + clip.getClipName());
        updatePropertiesPane();
    }

    private void deselectAll() {
        if (selectedClip != null && selectedClip.viewRef instanceof Rectangle) {
            ((Rectangle) selectedClip.viewRef).getStyleClass().remove("timeline-clip-selected");
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
