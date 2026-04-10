package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.data.ProjectData;
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
        VBox timeline = buildTimelineArea();

        root.setTop(buildTopBar());
        root.setCenter(centerRow);
        root.setBottom(timeline);

        Scene scene = new Scene(root);
        scene.getStylesheets().add(DoubleClipsDesktop.class.getResource("/style.css").toExternalForm());

        this.setScene(scene);
        this.setOnCloseRequest(e -> DoubleClipsDesktop.getInstance().closeEditor(this));
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
        Label timeCurrent = new Label("00:00:00:00");
        timeCurrent.getStyleClass().add("timecode-label");
        Label timeSep = new Label("/");
        timeSep.setStyle("-fx-text-fill: -color-fg-muted;");
        Label timeDuration = new Label("00:00:04:22");
        timeDuration.getStyleClass().add("timecode-label");

        Region pbLeft = new Region();
        HBox.setHgrow(pbLeft, Priority.ALWAYS);

        Button playBtn = new Button();
        playBtn.setGraphic(new FontIcon(MaterialDesignP.PLAY_CIRCLE_OUTLINE));
        playBtn.getStyleClass().addAll("button-transparent", "play-button-main");

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
            timeCurrent, timeSep, timeDuration,
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

        ScrollPane rightScroll = new ScrollPane(new VBox(suggestionsCard, globalEdits));
        rightScroll.setFitToWidth(true);
        VBox.setVgrow(rightScroll, Priority.ALWAYS);
        rightScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        panel.getChildren().addAll(tabs, rightScroll);
        return panel;
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
        Button trimLeft   = buildToolBtn(MaterialDesignF.FORMAT_INDENT_DECREASE);
        Button trimRight  = buildToolBtn(MaterialDesignF.FORMAT_INDENT_INCREASE);
        Button deleteClip = buildToolBtn(MaterialDesignT.TRASH_CAN_OUTLINE);

        Region toolSpacer = new Region();
        HBox.setHgrow(toolSpacer, Priority.ALWAYS);

        // Right cluster (zoom, waveform toggle, snap)
        Label zoomLabel = new Label("Zoom");
        zoomLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
        Slider zoomSlider = new Slider(0.5, 4.0, 1.0);
        zoomSlider.setPrefWidth(100);
        zoomSlider.getStyleClass().add("timeline-zoom-slider");

        toolbar.getChildren().addAll(selectTool, sliceTool, trimLeft, trimRight, deleteClip, toolSpacer, zoomLabel, zoomSlider);

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
        sidebarTop.getChildren().add(addTrackBtn);

        // Sample track header
        VBox trackHeadersContainer = new VBox(0);
        VBox.setVgrow(trackHeadersContainer, Priority.ALWAYS);
        trackHeadersContainer.getChildren().add(buildTrackHeader("Video 1"));
        trackHeadersContainer.getChildren().add(buildTrackHeader("Audio 1"));

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
        buildTracksArea();
        tracksScrollPane.getStyleClass().add("tracks-scroll");
        tracksScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        tracksScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(tracksScrollPane, Priority.ALWAYS);

        // Sync horizontal scroll
        rulerScrollPane.hvalueProperty().bindBidirectional(tracksScrollPane.hvalueProperty());

        // Playhead overlay on top of rulerAndTracks
        StackPane tracksWithPlayhead = new StackPane();
        VBox.setVgrow(tracksWithPlayhead, Priority.ALWAYS);
        tracksWithPlayhead.getChildren().add(tracksScrollPane);

        Line playhead = new Line(0, 0, 0, 600);
        playhead.setStroke(Color.web("#FF3B30"));
        playhead.setStrokeWidth(2);
        StackPane.setAlignment(playhead, Pos.TOP_CENTER);
        tracksWithPlayhead.getChildren().add(playhead);

        rulerAndTracks.getChildren().addAll(rulerScrollPane, tracksWithPlayhead);

        trackLayout.getChildren().addAll(trackSidebar, rulerAndTracks);
        timeline.getChildren().addAll(toolbar, trackLayout);
        return timeline;
    }

    private void buildRuler() {
        Pane ruler = new Pane();
        ruler.setPrefWidth(8000);
        ruler.setPrefHeight(30);
        ruler.setStyle("-fx-background-color: #1A1A2E;");

        // Draw tick marks
        for (int i = 0; i <= 240; i++) {
            double x = i * 33.0;
            boolean isMajor = (i % 5 == 0);
            Line tick = new Line(x, isMajor ? 10 : 18, x, 30);
            tick.setStroke(Color.web(isMajor ? "#888" : "#444"));
            tick.setStrokeWidth(isMajor ? 1.5 : 0.8);
            ruler.getChildren().add(tick);

            if (isMajor) {
                javafx.scene.text.Text label = new javafx.scene.text.Text(x + 2, 9, formatSeconds(i * 5));
                label.setFill(Color.web("#888"));
                label.setStyle("-fx-font-size: 9px;");
                ruler.getChildren().add(label);
            }
        }

        rulerScrollPane.setContent(ruler);
    }

    private void buildTracksArea() {
        Pane tracks = new Pane();
        tracks.setPrefWidth(8000);
        tracks.setPrefHeight(300);
        tracks.setStyle("-fx-background-color: #0D0D1A;");

        // Draw alternating row bands (2 tracks by default)
        int[] trackYs = {0, 80};
        String[] trackColors = {"#141422", "#0F0F1E"};
        for (int i = 0; i < trackYs.length; i++) {
            Rectangle band = new Rectangle(0, trackYs[i], 8000, 75);
            band.setFill(Color.web(trackColors[i]));
            tracks.getChildren().add(band);
        }

        // Add a sample placeholder clip on the first track
        Rectangle sampleClip = new Rectangle(120, 5, 500, 65);
        sampleClip.setArcWidth(8);
        sampleClip.setArcHeight(8);
        sampleClip.setFill(Color.web("#006D77"));
        sampleClip.setStroke(Color.web("#00A5B5"));
        sampleClip.setStrokeWidth(1);

        Label clipLabel = new Label("Clip");
        clipLabel.setStyle("-fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold;");
        clipLabel.setLayoutX(128);
        clipLabel.setLayoutY(12);

        tracks.getChildren().addAll(sampleClip, clipLabel);
        tracksScrollPane.setContent(tracks);
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
