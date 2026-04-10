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
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

public class EditorWindow extends Stage {

    private final ProjectData project;
    private final SplitPane mainSplit;

    public EditorWindow(ProjectData project) {
        this.project = project;
        this.setTitle("Editing: " + project.getProjectTitle());
        
        // Initialize with preferred floating window size
        this.setWidth(1440);
        this.setHeight(900);

        // Main layout container: SplitPane (Top: Preview, Bottom: Timeline)
        mainSplit = new SplitPane();
        mainSplit.setOrientation(Orientation.VERTICAL);
        mainSplit.setDividerPositions(0.6); // 60% for preview

        VBox previewArea = createPreviewArea();
        VBox timelineArea = createTimelineArea();

        mainSplit.getItems().addAll(previewArea, timelineArea);

        Scene scene = new Scene(mainSplit);
        // Reuse global styles
        String styleSheet = DoubleClipsDesktop.class.getResource("/style.css").toExternalForm();
        scene.getStylesheets().add(styleSheet);
        
        this.setScene(scene);
        
        // Handle window close
        this.setOnCloseRequest(e -> {
            DoubleClipsDesktop.getInstance().closeEditor(this);
        });
    }

    private VBox createPreviewArea() {
        VBox container = new VBox();
        container.getStyleClass().add("editor-preview-container");

        // 1. Top Bar
        HBox topBar = new HBox(12);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(10, 20, 10, 20));
        topBar.getStyleClass().add("editor-top-bar");

        Button backBtn = new Button();
        backBtn.setGraphic(new FontIcon(MaterialDesignK.KEYBOARD_RETURN));
        backBtn.getStyleClass().add("button-transparent");
        backBtn.setOnAction(e -> DoubleClipsDesktop.getInstance().closeEditor(this));

        Label titleLabel = new Label(project.getProjectTitle());
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button exportBtn = new Button("EXPORT");
        exportBtn.getStyleClass().addAll("button-primary", "export-button");
        exportBtn.setGraphic(new FontIcon(MaterialDesignU.UPLOAD));

        topBar.getChildren().addAll(backBtn, titleLabel, spacer, exportBtn);

        // 2. Preview Canvas Area
        StackPane canvasWrapper = new StackPane();
        VBox.setVgrow(canvasWrapper, Priority.ALWAYS);
        canvasWrapper.getStyleClass().add("canvas-wrapper");
        
        ImageView previewImage = new ImageView();
        previewImage.setPreserveRatio(true);
        // Sync with existing preview.png if available
        java.io.File previewFile = new java.io.File(project.getProjectPath(), "preview.png");
        if (previewFile.exists()) {
            previewImage.setImage(new Image(previewFile.toURI().toString()));
        }
        
        canvasWrapper.getChildren().add(previewImage);

        // 3. Playback Controls
        HBox controls = new HBox(20);
        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new Insets(10));
        controls.getStyleClass().add("playback-controls");

        Button undoBtn = new Button();
        undoBtn.setGraphic(new FontIcon(MaterialDesignU.UNDO));
        undoBtn.getStyleClass().add("button-transparent");

        Button playBtn = new Button();
        playBtn.setGraphic(new FontIcon(MaterialDesignP.PLAY_CIRCLE_OUTLINE));
        playBtn.getStyleClass().addAll("button-transparent", "play-button");
        playBtn.setStyle("-fx-font-size: 32px;");

        Button redoBtn = new Button();
        redoBtn.setGraphic(new FontIcon(MaterialDesignR.REDO));
        redoBtn.getStyleClass().add("button-transparent");

        controls.getChildren().addAll(undoBtn, playBtn, redoBtn);

        container.getChildren().addAll(topBar, canvasWrapper, controls);
        return container;
    }

    private VBox createTimelineArea() {
        VBox container = new VBox();
        container.getStyleClass().add("editor-timeline-container");

        // 1. Timestamp bar
        HBox timestampBar = new HBox();
        timestampBar.setPadding(new Insets(5, 20, 5, 20));
        Label currentPos = new Label("00:00");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label duration = new Label("00:00");
        timestampBar.getChildren().addAll(currentPos, spacer, duration);

        // 2. Main Timeline Content (Sidebar + Sync Scrolls)
        SplitPane timelineSplit = new SplitPane();
        VBox.setVgrow(timelineSplit, Priority.ALWAYS);
        
        // Left Sidebar: Track Headers
        VBox timelineSidebar = new VBox(2);
        timelineSidebar.setMinWidth(150);
        timelineSidebar.setMaxWidth(150);
        timelineSidebar.getStyleClass().add("timeline-sidebar");
        
        Button addTrackBtn = new Button();
        addTrackBtn.setGraphic(new FontIcon(MaterialDesignP.PLUS));
        addTrackBtn.setMaxWidth(Double.MAX_VALUE);
        addTrackBtn.getStyleClass().add("button-transparent");
        
        timelineSidebar.getChildren().add(addTrackBtn);

        // Right Content: Scrollable Tracks & Ruler
        VBox tracksWrapper = new VBox();
        
        // Ruler at top
        ScrollPane rulerScroll = new ScrollPane();
        rulerScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rulerScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rulerScroll.setFitToHeight(true);
        rulerScroll.getStyleClass().add("timeline-ruler-scroll");
        
        Pane rulerContent = new Pane();
        rulerContent.setPrefHeight(30);
        rulerContent.setPrefWidth(5000); // Artificial long timeline for now
        rulerContent.setStyle("-fx-background-color: #1A1A1A;");
        rulerScroll.setContent(rulerContent);

        // Tracks below ruler
        ScrollPane tracksScroll = new ScrollPane();
        VBox.setVgrow(tracksScroll, Priority.ALWAYS);
        tracksScroll.getStyleClass().add("timeline-tracks-scroll");
        
        Pane tracksContent = new Pane();
        tracksContent.setPrefSize(5000, 1000);
        tracksContent.setStyle("-fx-background-color: #111111;");
        tracksScroll.setContent(tracksContent);

        // RED PLAYHEAD (Static in center)
        StackPane timelineMainStack = new StackPane();
        VBox.setVgrow(timelineMainStack, Priority.ALWAYS);
        
        VBox scrollsContainer = new VBox(rulerScroll, tracksScroll);
        
        Line playhead = new Line(0, 0, 0, 1000);
        playhead.setStroke(Color.RED);
        playhead.setStrokeWidth(2);
        
        timelineMainStack.getChildren().addAll(scrollsContainer, playhead);
        StackPane.setAlignment(playhead, Pos.TOP_CENTER);

        // SYNC SCROLLING
        rulerScroll.hvalueProperty().bindBidirectional(tracksScroll.hvalueProperty());

        timelineSplit.getItems().addAll(timelineSidebar, timelineMainStack);
        
        container.getChildren().addAll(timestampBar, timelineSplit);
        return container;
    }
}
