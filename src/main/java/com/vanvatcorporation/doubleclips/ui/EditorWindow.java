package com.vanvatcorporation.doubleclips.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.vanvatcorporation.doubleclips.AudioUtils;
import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.FFmpegEditNative;
import com.vanvatcorporation.doubleclips.data.*;
import com.vanvatcorporation.doubleclips.data.editing.*;
import com.vanvatcorporation.doubleclips.history.*;
import com.vanvatcorporation.doubleclips.ui.renderer.TimelineRenderer;
import com.vanvatcorporation.doubleclips.helper.MediaHelper;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.FFmpegEdit;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseEvent;
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
    private com.vanvatcorporation.doubleclips.ui.renderer.PreviewGizmo previewGizmo;
    private final HistoryManager historyManager = new HistoryManager();

    // Editor State
    private float currentTime = 0f;
    private boolean isPlaying = false;
    private boolean isPlayingInReverse = false;
    private float pixelsPerSecond = 100f; // 100px = 1s

    // ── Zoom state (see requestZoom / applyPendingZoom) ───────────────────────
    private boolean zoomApplyScheduled = false;
    private double zoomTargetPps = -1;            // -1 = no zoom pending
    private double zoomAnchorTime = 0;            // timeline time that must stay put on screen ...
    private double zoomAnchorViewportX = 0;       // ... at this x, measured from the viewport's left edge
    private boolean suppressZoomSliderListener = false;
    private javafx.animation.PauseTransition thumbnailSettleTimer;
    // Direct references instead of tracksPane.lookup("#id"), which walks the ENTIRE node tree
    // (every clip, thumbnail tile, knot ...) once per lookup.
    private final java.util.Map<Integer, Rectangle> trackBands = new java.util.HashMap<>();
    private final java.util.Map<Clip, Rectangle> transitionCubes = new java.util.IdentityHashMap<>();
    private final float TRACK_HEIGHT = 70f;
    private final float TRACK_SPACING = 5f;

    // Preview Options (synced from Android)
    private float previewFpsRuntime = -1f;   // -1 = uncapped
    private float previewSpeed = 1.0f;
    private boolean keepPlayingWhenClipSelected = false;
    private int thumbnailAudioBarWidth = 1;
    private int thumbnailAudioBarGap = 0;

    /** The "primary" selected clip: the one the properties panel edits (the last one clicked). Always a member of selectedClips, or null. */
    private Clip selectedClip;
    /** Every selected clip (Ctrl/Cmd+click adds to it). Insertion-ordered. */
    private final java.util.Set<Clip> selectedClips = new java.util.LinkedHashSet<>();
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

        // ── Group drag (existing clips) ─────────────────────────────────────
        /** Every clip that moves with this drag (the selection), including {@link #clip}. Empty for media-browser drags. */
        final List<Clip> members = new ArrayList<>();
        /** One semi-transparent ghost per member. {@link #ghost} is the grabbed clip's. */
        final java.util.Map<Clip, ClipNode> ghosts = new java.util.IdentityHashMap<>();
        int anchorTrack;          // track of the grabbed clip when the drag started
        int minMemberTrack, maxMemberTrack;
        float minMemberStart;     // earliest start among members (seconds)
        double groupWidthPx;      // from the earliest start to the latest end, at the zoom the drag started with
        double deltaPx;           // current horizontal shift of the whole group
        int deltaTrack;           // current vertical shift of the whole group, in tracks
        /** Pressed (without Ctrl/Cmd) on a clip that was already part of a multi-selection: a plain click should collapse to it. */
        boolean collapseOnRelease;
    }

    // ── Cut: Ctrl/Cmd+X copies the selection and marks it; the clips only MOVE when pasted (like a file manager) ──
    private static final double CUT_OPACITY = 0.45;
    /** Clips waiting to be moved by the next paste. Empty when no cut is pending. */
    private final java.util.Set<Clip> pendingCut = new java.util.LinkedHashSet<>();
    /** The exact clipboard text the cut wrote: if the clipboard no longer holds it, the cut is stale. */
    private String pendingCutPayload;

    // ── Marquee: click-drag on empty timeline area draws a rectangle that selects every clip it touches ──
    private static final double MARQUEE_THRESHOLD = 4.0;  // px the pointer must travel before a press becomes a marquee
    private Rectangle marqueeRect;                        // null when no marquee is being drawn
    private boolean marqueeArmed;                         // pressed on empty space; may become a marquee
    private boolean marqueeActive;                        // the rectangle is being drawn
    private boolean marqueeAdditive;                      // Ctrl/Cmd held: add to the existing selection
    private boolean marqueeJustFinished;                  // swallow the click that follows the release
    private double marqueeStartX, marqueeStartY;          // press point, tracks-pane coordinates
    private Clip marqueePrimaryBefore;                    // primary clip when the marquee started (additive mode keeps it)
    private final java.util.Set<Clip> marqueeBaseline = new java.util.LinkedHashSet<>();
    private List<Clip> marqueeLastHits = new ArrayList<>();

    /** Dashed "this track will be created" rows shown below the last track while a group is dragged past it. */
    private final java.util.Map<Integer, Rectangle> phantomBands = new java.util.HashMap<>();

    private final DragContext activeDrag = new DragContext();
    private AnimationTimer edgeScrollTimer;
    private double edgeScrollVelocity = 0; // pixels per frame to scroll horizontally
    private double edgeScrollVelocityY = 0; // pixels per frame to scroll vertically
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


        for (Clip clip : timeline.getAllClips())
        {
            clip.keyframes.reassignKeyframes(videoSettings.frameRate);
        }

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
            // Settings is recording a new shortcut: it needs the raw key press, not the action bound to it.
            if (AppSettings.getInstance().isRecordingKeybind())
                return;

            AppSettings settings = AppSettings.getInstance();

            if (matchesKeybind(settings.getUndoKeybind(), event)) {
                historyManager.undo();
                event.consume();
            } else if (matchesKeybind(settings.getRedoKeybind(), event)) {
                historyManager.redo();
                event.consume();
            } else if (matchesKeybind(settings.getDeleteKeybind(), event)
                    || (event.getCode() == javafx.scene.input.KeyCode.BACK_SPACE
                    && sanitizeKeybind(settings.getDeleteKeybind()).equalsIgnoreCase("DELETE"))) {
                handleDelete();
                event.consume();
            } else if (matchesKeybind(settings.getSelectAllKeybind(), event)) {
                selectAllClips();
                event.consume();
            } else if (matchesKeybind(settings.getTogglePlayKeybind(), event)) {
                triggerPlayAction();
                event.consume();
            } else if (matchesKeybind(settings.getCopyKeybind(), event)) {
                handleCopy();
                event.consume();
            } else if (matchesKeybind(settings.getCutKeybind(), event)) {
                handleCut();
                event.consume();
            } else if (matchesKeybind(settings.getPasteKeybind(), event)) {
                handlePaste();
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
    @Override public VideoSettings getVideoSettings() { return videoSettings; }
    @Override public ProjectData getProject() { return project; }

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
                    float deltaSec = (deltaNs / 1_000_000_000f) * previewSpeed;
                    if (isPlayingInReverse) {
                        updateCurrentTime(currentTime - deltaSec);
                    } else {
                        updateCurrentTime(currentTime + deltaSec);
                    }
                }
                lastTimerUpdate = now;
            }
        };
    }

    private void initEdgeScrollTimer() {
        edgeScrollTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if ((edgeScrollVelocity == 0 && edgeScrollVelocityY == 0) || (activeDrag.ghost == null && !marqueeActive)) {
                    stop();
                    return;
                }

                if (edgeScrollVelocity != 0) {
                    double contentWidth = tracksScrollPane.getContent().getBoundsInLocal().getWidth();
                    double viewportWidth = tracksScrollPane.getViewportBounds().getWidth();
                    double maxScrollX = contentWidth - viewportWidth;
                    if (maxScrollX > 0) {
                        double currentScrollX = tracksScrollPane.getHvalue() * maxScrollX;
                        double newScrollX = Math.max(0, Math.min(maxScrollX, currentScrollX + edgeScrollVelocity));
                        tracksScrollPane.setHvalue(newScrollX / maxScrollX);
                    }
                }

                if (edgeScrollVelocityY != 0) {
                    double contentHeight = tracksScrollPane.getContent().getBoundsInLocal().getHeight();
                    double viewportHeight = tracksScrollPane.getViewportBounds().getHeight();
                    double maxScrollY = contentHeight - viewportHeight;
                    if (maxScrollY > 0) {
                        double currentScrollY = tracksScrollPane.getVvalue() * maxScrollY;
                        double newScrollY = Math.max(0, Math.min(maxScrollY, currentScrollY + edgeScrollVelocityY));
                        tracksScrollPane.setVvalue(newScrollY / maxScrollY);
                    }
                }

                // Keep the ghost / marquee in sync with the mouse scene position as we scroll
                if (marqueeActive) updateMarquee(lastDragSceneX, lastDragSceneY);
                else updateActiveDragGhost(lastDragSceneX, lastDragSceneY);
            }
        };
    }

    private void updateActiveDragGhost(double sceneX, double sceneY) {
        if (activeDrag.ghost == null) return;

        javafx.geometry.Point2D local = tracksPane.sceneToLocal(sceneX, sceneY);

        // Media-browser drag: a single new clip, always lands on an existing track.
        if (activeDrag.isNewClip || activeDrag.members.isEmpty()) {
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
            return;
        }

        // Existing clips: the whole selection moves as one group (see ClipGroupMath for the rules).
        double rowH = TRACK_HEIGHT + TRACK_SPACING;
        double groupLeftPx = activeDrag.minMemberStart * pixelsPerSecond;

        // Horizontal: where the grabbed clip wants to be, applied to the whole group, never before time 0.
        double wantedGrabbedX = local.getX() - activeDrag.dragOffsetX;
        double dxPx = wantedGrabbedX - activeDrag.clip.startTime * pixelsPerSecond;
        dxPx = ClipGroupMath.clampTimeDeltaPx(dxPx, groupLeftPx);

        // Vertical: relative to the track the grabbed clip started on. Stops at the top, may run past the bottom.
        int pointerTrack = ClipGroupMath.rawTrackFromY(local.getY(), rowH);
        int dTrack = ClipGroupMath.clampTrackDelta(pointerTrack - activeDrag.anchorTrack,
                activeDrag.minMemberTrack, activeDrag.maxMemberTrack, timeline.tracks.size());

        // Snap the group's outer edges (not just the grabbed clip) to the playhead / neighbouring clips.
        double snappedLeft = applySnap(groupLeftPx + dxPx, activeDrag.groupWidthPx, activeDrag.anchorTrack + dTrack);
        dxPx = ClipGroupMath.clampTimeDeltaPx(snappedLeft - groupLeftPx, groupLeftPx);

        activeDrag.deltaPx = dxPx;
        activeDrag.deltaTrack = dTrack;
        activeDrag.currentTrackIdx = activeDrag.anchorTrack + dTrack;

        for (Clip m : activeDrag.members) {
            ClipNode g = activeDrag.ghosts.get(m);
            if (g == null) continue;
            g.setLayoutX(m.startTime * pixelsPerSecond + dxPx);
            g.setLayoutY((m.trackIndex + dTrack) * rowH + 3);
        }

        updatePhantomTracks(ClipGroupMath.lowestTrackAfterMove(activeDrag.maxMemberTrack, dTrack));
    }

    /**
     * While a group is dragged past the last track, show the blank rows it would land on (and make the
     * scrollable area tall enough to see them). The real tracks are only created when the group is dropped.
     */
    private void updatePhantomTracks(int lowestTrack) {
        double rowH = TRACK_HEIGHT + TRACK_SPACING;
        int rows = Math.max(timeline.tracks.size(), lowestTrack + 1);
        tracksPane.setPrefHeight(rows * rowH);

        for (int i = timeline.tracks.size(); i < rows; i++) {
            Rectangle band = phantomBands.get(i);
            if (band == null || band.getParent() != tracksPane) {
                band = new Rectangle(0, i * rowH, tracksPane.getPrefWidth(), TRACK_HEIGHT);
                band.getStyleClass().add(i % 2 == 0 ? "track-band-even" : "track-band-odd");
                band.setOpacity(0.45);
                band.setMouseTransparent(true);
                tracksPane.getChildren().add(0, band);
                phantomBands.put(i, band);
            } else {
                band.setWidth(tracksPane.getPrefWidth());
            }
        }
        // Rows the group no longer reaches (pointer moved back up) disappear again.
        phantomBands.entrySet().removeIf(en -> {
            if (en.getKey() >= rows || en.getKey() < timeline.tracks.size()) {
                tracksPane.getChildren().remove(en.getValue());
                return true;
            }
            return false;
        });
    }

    private void clearPhantomTracks() {
        for (Rectangle band : phantomBands.values()) tracksPane.getChildren().remove(band);
        phantomBands.clear();
    }

    private void checkEdgeScroll(double sceneX, double sceneY) {
        lastDragSceneX = sceneX;
        lastDragSceneY = sceneY;

        if (activeDrag.ghost == null && !marqueeActive) {
            edgeScrollVelocity = 0;
            edgeScrollVelocityY = 0;
            edgeScrollTimer.stop();
            return;
        }

        javafx.geometry.Point2D viewportPoint = tracksScrollPane.sceneToLocal(sceneX, sceneY);
        double vx = viewportPoint.getX();
        double vy = viewportPoint.getY();
        double vw = tracksScrollPane.getViewportBounds().getWidth();
        double vh = tracksScrollPane.getViewportBounds().getHeight();

        double threshold = 60.0;
        double maxSpeed = 12.0; // pixels per frame

        if (vx < threshold && vx > -threshold) { // Mouse is near left edge
            double intensity = (threshold - Math.max(0, vx)) / threshold;
            edgeScrollVelocity = -maxSpeed * intensity;
        } else if (vx > vw - threshold && vx < vw + threshold) { // Mouse is near right edge
            double intensity = (threshold - Math.max(0, vw - vx)) / threshold;
            edgeScrollVelocity = maxSpeed * intensity;
        } else {
            edgeScrollVelocity = 0;
        }

        // Vertical: lets a group be dragged down to the blank rows below the last track
        // (or back up) when the track list is taller than the viewport.
        double thresholdY = 40.0;
        double maxSpeedY = 10.0;
        if (vy < thresholdY && vy > -thresholdY) {
            edgeScrollVelocityY = -maxSpeedY * (thresholdY - Math.max(0, vy)) / thresholdY;
        } else if (vy > vh - thresholdY && vy < vh + thresholdY) {
            edgeScrollVelocityY = maxSpeedY * (thresholdY - Math.max(0, vh - vy)) / thresholdY;
        } else {
            edgeScrollVelocityY = 0;
        }

        if (edgeScrollVelocity != 0 || edgeScrollVelocityY != 0) edgeScrollTimer.start();
        else edgeScrollTimer.stop();
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
            // Reverse need to be track back => seeking
            // non-playing, is in fact => seeking
            // previewSpeed != normal speed, indeed => seeking
            timelineRenderer.updateTime(currentTime, (isPlayingInReverse || !isPlaying || previewSpeed != 1.0f));
        }
        if (previewGizmo != null) previewGizmo.refresh();

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

    /** True when {@code event} is the shortcut stored in {@code binding} ("Shortcut+C", "DELETE", "Meta+Shift+Z" ...). */
    private boolean matchesKeybind(String binding, javafx.scene.input.KeyEvent event) {
        String b = sanitizeKeybind(binding);
        if (b.isEmpty()) return false;
        try {
            return javafx.scene.input.KeyCombination.valueOf(b).match(event);
        } catch (Exception e) {
            return event.getCode().name().equalsIgnoreCase(b);
        }
    }

    private String sanitizeKeybind(String keybind) {
        if (keybind == null) return "";
        return keybind.replace("Cmd+", "Meta+").replace("Ctrl+", "Control+");
    }

    private void closeWindow() {
        stopPlayback();
        saveProject();
        if (timelineRenderer != null) timelineRenderer.shutdown();
        DoubleClipsDesktop.getInstance().closeEditor(this);
    }

    // ====================================================================
    // PREVIEW OPTIONS POPUP
    // ====================================================================
    private javafx.stage.Popup buildPreviewOptionsPopup() {
        javafx.stage.Popup popup = new javafx.stage.Popup();
        popup.setAutoHide(true);

        VBox panel = new VBox(10);
        panel.setPadding(new Insets(14, 16, 14, 16));
        panel.setStyle(
            "-fx-background-color: -color-bg-subtle; " +
            "-fx-background-radius: 8; " +
            "-fx-border-color: -color-border-default; " +
            "-fx-border-radius: 8; " +
            "-fx-border-width: 1; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 12, 0, 0, 4);"
        );
        panel.setPrefWidth(260);

        Label title = new Label("Preview Options");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        // ── Separator helper ──────────────────────────────────────────────
        javafx.scene.control.Separator sep1 = new javafx.scene.control.Separator();
        javafx.scene.control.Separator sep2 = new javafx.scene.control.Separator();



        // ── Playback FPS - Speed Correlation ──────────────────────────────────────────────
        float activeFps = previewFpsRuntime == -1f ? videoSettings.frameRate : previewFpsRuntime;


        // ── Preview FPS ───────────────────────────────────────────────────
        HBox fpsRow = new HBox(8);
        fpsRow.setAlignment(Pos.CENTER_LEFT);
        Label fpsLabel = new Label("Playback FPS:");
        fpsLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        fpsLabel.setPrefWidth(130);
        javafx.scene.control.Spinner<Double> fpsSpinner = new javafx.scene.control.Spinner<>(
            new javafx.scene.control.SpinnerValueFactory.DoubleSpinnerValueFactory(-1, Double.MAX_VALUE, activeFps, 1)
        );
        fpsSpinner.setEditable(true);
        fpsSpinner.setPrefWidth(90);
        fpsSpinner.valueProperty().addListener((obs, o, n) -> previewFpsRuntime = n.floatValue());
        Label fpsHint = new Label("(-1 = original)");
        fpsHint.setStyle("-fx-text-fill: -color-fg-subtle; -fx-font-size: 10px;");
        fpsRow.getChildren().addAll(fpsLabel, fpsSpinner);

        // ── Playback Speed ────────────────────────────────────────────────
        HBox speedRow = new HBox(8);
        speedRow.setAlignment(Pos.CENTER_LEFT);
        Label speedLabel = new Label("Playback speed:");
        speedLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        speedLabel.setPrefWidth(130);
        javafx.scene.control.Spinner<Double> speedSpinner = new javafx.scene.control.Spinner<>(
            new javafx.scene.control.SpinnerValueFactory.DoubleSpinnerValueFactory(0.1, Double.MAX_VALUE, previewSpeed, 0.25)
        );
        speedSpinner.setEditable(true);
        speedSpinner.setPrefWidth(90);
//        speedSpinner.valueProperty().addListener((obs, o, n) -> previewSpeed = n.floatValue());
        speedRow.getChildren().addAll(speedLabel, speedSpinner);


        // ── Playback FPS - Speed Correlation ───────────────────────────────

        // Listener for FPS -> Updates Speed
        fpsSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (fpsSpinner.isFocused() && newVal != null) {
                double newSpeed = newVal / videoSettings.frameRate;
                speedSpinner.getValueFactory().setValue(newSpeed);
                previewFpsRuntime = newVal.floatValue();
                previewSpeed = (float) newSpeed;
            }
        });

        // Listener for Speed -> Updates FPS
        speedSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (speedSpinner.isFocused() && newVal != null) {
                double newFps = newVal * videoSettings.frameRate;
                fpsSpinner.getValueFactory().setValue(newFps);
                previewSpeed = newVal.floatValue();
                previewFpsRuntime = (float) newFps;
            }
        });

        // ── Reverse Playback toggle ────────────────────────────────────────
        HBox reverseRow = new HBox(8);
        reverseRow.setAlignment(Pos.CENTER_LEFT);
        Label reverseLabel = new Label("Reverse playback:");
        reverseLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        reverseLabel.setPrefWidth(160);
        CheckBox reverseCheck = new CheckBox();
        reverseCheck.setSelected(isPlayingInReverse);
        reverseCheck.selectedProperty().addListener((obs, o, n) -> isPlayingInReverse = n);
        reverseRow.getChildren().addAll(reverseLabel, reverseCheck);

        // ── Keep playing when clip selected ───────────────────────────────
        HBox keepRow = new HBox(8);
        keepRow.setAlignment(Pos.CENTER_LEFT);
        Label keepLabel = new Label("Keep playing on select:");
        keepLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        keepLabel.setPrefWidth(160);
        CheckBox keepCheck = new CheckBox();
        keepCheck.setSelected(keepPlayingWhenClipSelected);
        keepCheck.selectedProperty().addListener((obs, o, n) -> keepPlayingWhenClipSelected = n);
        keepRow.getChildren().addAll(keepLabel, keepCheck);

        // ── Audio bar width ───────────────────────────────────────────────
        HBox audioWidthRow = new HBox(8);
        audioWidthRow.setAlignment(Pos.CENTER_LEFT);
        Label audioWidthLabel = new Label("Audio bar width:");
        audioWidthLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        audioWidthLabel.setPrefWidth(130);
        javafx.scene.control.Spinner<Integer> audioWidthSpinner = new javafx.scene.control.Spinner<>(
            new javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory(1, 32, thumbnailAudioBarWidth, 1)
        );
        audioWidthSpinner.setEditable(true);
        audioWidthSpinner.setPrefWidth(90);
        audioWidthSpinner.valueProperty().addListener((obs, o, n) -> thumbnailAudioBarWidth = n);
        audioWidthRow.getChildren().addAll(audioWidthLabel, audioWidthSpinner);

        // ── Audio bar gap ─────────────────────────────────────────────────
        HBox audioGapRow = new HBox(8);
        audioGapRow.setAlignment(Pos.CENTER_LEFT);
        Label audioGapLabel = new Label("Audio bar gap:");
        audioGapLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        audioGapLabel.setPrefWidth(130);
        javafx.scene.control.Spinner<Integer> audioGapSpinner = new javafx.scene.control.Spinner<>(
            new javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory(0, 16, thumbnailAudioBarGap, 1)
        );
        audioGapSpinner.setEditable(true);
        audioGapSpinner.setPrefWidth(90);
        audioGapSpinner.valueProperty().addListener((obs, o, n) -> thumbnailAudioBarGap = n);
        audioGapRow.getChildren().addAll(audioGapLabel, audioGapSpinner);

        // ── GPU preview + proxy clips ─────────────────────────────────────
        AppSettings appSettings = AppSettings.getInstance();

        HBox gpuRow = new HBox(8);
        gpuRow.setAlignment(Pos.CENTER_LEFT);
        Label gpuLabel = new Label("GPU preview:");
        gpuLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        gpuLabel.setPrefWidth(160);
        CheckBox gpuCheck = new CheckBox();
        gpuCheck.setSelected(appSettings.isGpuPreview() && timelineRenderer.isGpuPreviewActive());
        gpuCheck.setTooltip(new Tooltip("Composite the preview with the same OpenGL engine as the OpenGL export"));
        gpuRow.getChildren().addAll(gpuLabel, gpuCheck);

        HBox proxyRow = new HBox(8);
        proxyRow.setAlignment(Pos.CENTER_LEFT);
        Label proxyLabel = new Label("Use proxy clips:");
        proxyLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        proxyLabel.setPrefWidth(160);
        CheckBox proxyCheck = new CheckBox();
        proxyCheck.setSelected(appSettings.isPreviewUseProxy());
        proxyCheck.setTooltip(new Tooltip("Off: preview plays the original clips. On: the lighter proxy copies made at import (GPU preview only)"));
        proxyCheck.disableProperty().bind(gpuCheck.selectedProperty().not());
        proxyCheck.selectedProperty().addListener((obs, o, n) -> {
            appSettings.setPreviewUseProxy(n);
            timelineRenderer.setUseProxy(n);
        });
        proxyRow.getChildren().addAll(proxyLabel, proxyCheck);

        HBox hwRow = new HBox(8);
        hwRow.setAlignment(Pos.CENTER_LEFT);
        Label hwLabel = new Label("Hardware decoding:");
        hwLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        hwLabel.setPrefWidth(160);
        CheckBox hwCheck = new CheckBox();
        hwCheck.setSelected(appSettings.isPreviewHardwareDecode());
        hwCheck.setTooltip(new Tooltip("Experimental. Only used when a clip starts from its beginning; seeks always decode in software, because hardware decoding after a seek can corrupt frames"));
        hwCheck.disableProperty().bind(gpuCheck.selectedProperty().not());
        hwCheck.selectedProperty().addListener((obs, o, n) -> {
            appSettings.setPreviewHardwareDecode(n);
            timelineRenderer.setHardwareDecode(n);
        });
        hwRow.getChildren().addAll(hwLabel, hwCheck);

        gpuCheck.selectedProperty().addListener((obs, o, n) -> {
            appSettings.setGpuPreview(n);
            if (n) {
                timelineRenderer.enableGpuPreview(appSettings.isPreviewUseProxy());
            } else {
                timelineRenderer.disableGpuPreview();
            }
        });

        panel.getChildren().addAll(
            title,
            sep1,
            gpuRow,
            proxyRow,
            hwRow,
            new javafx.scene.control.Separator(),
            fpsRow, fpsHint,
            speedRow,
            sep2,
            reverseRow,
            keepRow,
            new javafx.scene.control.Separator(),
            audioWidthRow,
            audioGapRow
        );

        popup.getContent().add(panel);
        return popup;
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

                        String scaleStr;
                        if(info.width < info.height) scaleStr = "-2:720";
                        else scaleStr = "1280:-2";

                        String cmd = "-i \"" + targetFile.getAbsolutePath()
                                + "\" -vf \"scale=" + scaleStr + "\" -c:v libx264 -preset ultrafast -crf 32 -g 1 -an -y \""
                                + previewClipPath.substring(0, previewClipPath.lastIndexOf('.')) + ".mp4" + "\"";
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
                ClipNode ghost = new ClipNode(activeDrag.clip, this);
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
            Platform.runLater(() -> activeDrag.dragging = false);
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
        if (AppSettings.getInstance().isGpuPreview()) {
            timelineRenderer.enableGpuPreview(AppSettings.getInstance().isPreviewUseProxy());
        }
        Pane renderPane = timelineRenderer.getRenderPane();

        // Wrap in a Group to detach bounds from StackPane's layout system
        javafx.scene.Group renderGroup = new javafx.scene.Group(renderPane);
        renderGroup.setManaged(false); // crucial for allowing canvas to shrink smaller than the video settings
        canvas.getChildren().add(renderGroup);

        // On-canvas selection box + move / scale / rotate, above the picture and outside its scaling.
        previewGizmo = new com.vanvatcorporation.doubleclips.ui.renderer.PreviewGizmo(new com.vanvatcorporation.doubleclips.ui.renderer.PreviewGizmo.Host() {
            @Override public Timeline timeline() { return timeline; }
            @Override public float currentTime() { return currentTime; }
            @Override public boolean isPlaying() { return isPlaying; }
            @Override public Clip primarySelectedClip() { return selectedClip; }
            @Override public void selectClip(Clip clip) { EditorWindow.this.selectClip(clip); }
            @Override public void commit(String name, Runnable redo, Runnable undo) { executePropertyChange(name, redo, undo); }
            @Override public String fontsDirectory() { return com.vanvatcorporation.doubleclips.TextStyle.fontsDirOf(project); }
            @Override public void clipChanged(Clip clip) {
                if (clip.viewRef instanceof ClipNode cn) {
                    cn.updateKeyframes(pixelsPerSecond);
                }
                saveProject();
                updatePropertiesPane();
                timelineRenderer.syncWorker(currentTime);
                if (previewGizmo != null) previewGizmo.refresh();
            }
        }, timelineRenderer, videoSettings);
        canvas.getChildren().add(previewGizmo.getNode());

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
            Platform.runLater(() -> { if (previewGizmo != null) previewGizmo.refresh(); });
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
        if (previewGizmo != null) previewGizmo.refresh();
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
        zoomSlider = new Slider(1, 2000, 100); // 1px to 1000px per second
        zoomSlider.setPrefWidth(100);
        zoomSlider.getStyleClass().add("timeline-zoom-slider");
        zoomSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressZoomSliderListener) return; // our own sync after a cursor zoom
            requestZoom(newVal.doubleValue());
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

        // Preview options button (left of zoom slider)
        Button previewOptionsBtn = new Button();
        previewOptionsBtn.setGraphic(new FontIcon(MaterialDesignT.TUNE));
        previewOptionsBtn.getStyleClass().add("button-transparent");
        previewOptionsBtn.setTooltip(new Tooltip("Preview Options"));
        javafx.stage.Popup previewOptionsPopup = buildPreviewOptionsPopup();
        previewOptionsBtn.setOnAction(e -> {
            if (previewOptionsPopup.isShowing()) {
                previewOptionsPopup.hide();
            } else {
                javafx.geometry.Bounds b = previewOptionsBtn.localToScreen(previewOptionsBtn.getBoundsInLocal());
                previewOptionsPopup.show(previewOptionsBtn, b.getMinX(), b.getMaxY() + 4);
            }
        });

        toolbar.getChildren().addAll(selectTool, sliceTool, trimLeft, trimRight, deleteTool, textTool, keyframeBtn, clearKfBtn,
                toolSpacer, previewOptionsBtn, zoomLabel, zoomSlider);

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

        // Press + drag on EMPTY space (clips and transition cubes handle their own presses, track bands are
        // mouse-transparent) draws a selection rectangle; a plain click on empty space deselects.
        tracksPane.setOnMousePressed(e -> {
            marqueeArmed = false;
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY || e.getTarget() != tracksPane || activeDrag.clip != null)
                return;
            marqueeArmed = true;
            marqueeActive = false;
            marqueeStartX = e.getX();
            marqueeStartY = e.getY();
            marqueeAdditive = e.isShortcutDown();
        });
        tracksPane.setOnMouseDragged(e -> {
            if (!marqueeArmed) return;
            if (!marqueeActive) {
                if (!MarqueeSelection.exceedsThreshold(marqueeStartX, marqueeStartY, e.getX(), e.getY(), MARQUEE_THRESHOLD))
                    return;
                beginMarquee();
            }
            updateMarquee(e.getSceneX(), e.getSceneY());
            checkEdgeScroll(e.getSceneX(), e.getSceneY());
            e.consume();
        });
        tracksPane.setOnMouseReleased(e -> {
            if (marqueeActive) endMarquee();
            marqueeArmed = false;
        });
        tracksPane.setOnMouseClicked(e -> {
            if (activeDrag.dragging || marqueeJustFinished) return;
            if (e.isShortcutDown()) return; // Ctrl/Cmd+click on empty space leaves the selection alone
            deselectAll();
        });

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
                    ClipNode ghost = new ClipNode(ghostClip, this);
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
        // The pane is only as tall as its tracks; make it at least as tall as the viewport so a press on the blank
        // area below the last track still reaches it (marquee start, click to deselect).
        tracksScrollPane.viewportBoundsProperty().addListener((obs, oldB, newB) -> {
            if (newB != null) tracksPane.setMinHeight(newB.getHeight());
        });

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
        tracksScrollPane.hvalueProperty().addListener((obs, old, newVal) -> {
            updatePlayheadPosition();
            scheduleVisibleThumbnailRefresh();
        });

        // Update position when resizing viewport
        tracksScrollPane.viewportBoundsProperty().addListener((obs, old, newVal) -> {
            updatePlayheadPosition();
            scheduleVisibleThumbnailRefresh();
        });

        // Update position when content width changes (e.g. zoom)
        tracksPane.widthProperty().addListener((obs, old, newVal) -> updatePlayheadPosition());

        // Bind playhead height to container
        playheadLine.endYProperty().bind(tracksWithPlayhead.heightProperty());
        ghostPlayheadLine.endYProperty().bind(tracksWithPlayhead.heightProperty());

        rulerAndTracks.getChildren().addAll(rulerScrollPane, tracksWithPlayhead);

        // Sync Ruler and Tracks scrolling
        rulerScrollPane.hvalueProperty().bindBidirectional(tracksScrollPane.hvalueProperty());

        // Zoom Gestures
        // Only handled while the cursor is over the ruler/tracks (the filter is on that node), and
        // they zoom around the playhead - see requestZoom.
        rulerAndTracks.addEventFilter(javafx.scene.input.ZoomEvent.ZOOM, e -> {
            requestZoomFactor(e.getZoomFactor());
            e.consume();
        });

        rulerAndTracks.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            // TODO: Move the keyboard to keybind
            if (e.isControlDown() || e.isShortcutDown()) {
                double delta = e.getDeltaY();
                // Map the delta dynamically (standard mouse notch is +/- 40 = 1.1 scale)
                double zoomFactor = 1.0 + (delta / 400.0);

                if (zoomFactor != 1.0 && zoomFactor > 0) {
                    requestZoomFactor(zoomFactor);
                }
                e.consume(); // prevent natural scrolling while zooming
            }
        });

        trackLayout.getChildren().addAll(trackSidebar, rulerAndTracks);
        timeline.getChildren().addAll(toolbar, trackLayout);
        return timeline;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Timeline zoom
    //
    //  Zooming used to call refreshTimelineUI() on every slider change, and ctrl+scroll / pinch
    //  fire that many times per second. Each call re-sorted the timeline, rebuilt the preview
    //  renderer, searched the whole node tree by id, and queued new ffmpeg thumbnail decodes
    //  (one process per tile), waveform renders and image loads for EVERY clip - including ones
    //  far off-screen - whose results then flooded the FX thread with Platform.runLater calls.
    //
    //  Now a zoom (always anchored on the playhead) only re-lays-out geometry (ruler, clip x/width,
    //  knots, transition cubes), at most once per frame. Thumbnails are regenerated once the zoom settles, only for clips near
    //  the viewport, and only when what they show actually changed (see ensureThumbnails).
    // ═════════════════════════════════════════════════════════════════════════

    /** Multiplies the zoom by {@code factor} (relative to any zoom still waiting to be applied). */
    private void requestZoomFactor(double factor) {
        requestZoom((zoomTargetPps > 0 ? zoomTargetPps : pixelsPerSecond) * factor);
    }

    /**
     * Zooms around the PLAYHEAD: zooming never changes the playhead's time, so it stays exactly where
     * it is on screen while the rest of the timeline stretches or shrinks around it. (The mouse
     * position is deliberately not used: whatever is under the cursor moves relative to the
     * timeline as the zoom changes.) If the playhead is scrolled out of view, the centre of the
     * view is held instead - see {@link TimelineZoomMath#captureAnchorAtPlayhead}.
     */
    private void requestZoom(double targetPps) {
        zoomTargetPps = TimelineZoomMath.clamp(targetPps, zoomSlider.getMin(), zoomSlider.getMax());

        // Captured from the logical state (pixelsPerSecond, hvalue, content width), which only
        // changes inside applyPendingZoom, so it stays valid even if layout hasn't caught up yet.
        double[] anchor = TimelineZoomMath.captureAnchorAtPlayhead(currentTime, pixelsPerSecond,
                currentScrollPx(), tracksScrollPane.getViewportBounds().getWidth());
        zoomAnchorTime = anchor[0];
        zoomAnchorViewportX = anchor[1];

        if (!zoomApplyScheduled) {
            zoomApplyScheduled = true;
            Platform.runLater(this::applyPendingZoom); // coalesces a burst of events into one relayout
        }
    }

    private void applyPendingZoom() {
        zoomApplyScheduled = false;
        double newPps = zoomTargetPps;
        zoomTargetPps = -1;
        if (newPps <= 0) return;

        // pixelsPerSecond is a float, so compare with a tolerance rather than exactly
        if (Math.abs(newPps - pixelsPerSecond) > 1e-3) {
            applyZoomLayout((float) newPps);

            // Put the anchor back where it was on screen. Set before layout runs: the ScrollPane
            // resolves hvalue against the new content width when it lays out.
            double viewportWidth = tracksScrollPane.getViewportBounds().getWidth();
            tracksScrollPane.setHvalue(TimelineZoomMath.hvalueForAnchor(
                    zoomAnchorTime, zoomAnchorViewportX, newPps, tracksPane.getPrefWidth(), viewportWidth));

            updatePlayheadPosition();
            scheduleVisibleThumbnailRefresh();
        }

        // Keep the slider in step with gesture zooms without re-triggering the listener.
        if (Math.abs(zoomSlider.getValue() - pixelsPerSecond) > 1e-3) {
            suppressZoomSliderListener = true;
            try {
                zoomSlider.setValue(pixelsPerSecond);
            } finally {
                suppressZoomSliderListener = false;
            }
        }
    }

    /** Geometry-only relayout for a new scale. Deliberately does NOT touch the timeline data, the preview, or thumbnails. */
    private void applyZoomLayout(float newPps) {
        pixelsPerSecond = newPps;
        double contentWidth = Math.max(1200, timeline.duration * newPps + 1000); // same padding as refreshTimelineUI

        buildRuler(contentWidth);
        tracksPane.setPrefWidth(contentWidth);

        for (Rectangle band : trackBands.values()) band.setWidth(contentWidth);

        for (Track track : timeline.tracks) {
            for (Clip clip : track.clips) {
                if (!(clip.viewRef instanceof ClipNode node) || node.getParent() != tracksPane) continue;
                double clipW = clip.duration * newPps;
                node.setLayoutX(clip.startTime * newPps);
                node.setPrefWidth(clipW);
                node.setMinWidth(clipW);
                node.setMaxWidth(clipW);
                node.relayoutKeyframeKnots(newPps);
                node.setupTrimInteractions(newPps); // trim drags convert pixels to time with this scale
                node.refreshThumbnails();           // tile count follows the width; images refill once zoom settles
            }
        }

        for (java.util.Map.Entry<Clip, Rectangle> entry : transitionCubes.entrySet()) {
            Clip clip = entry.getKey();
            entry.getValue().setX((clip.startTime + clip.duration) * newPps - TRANSITION_CUBE_SIZE / 2.0);
        }
    }

    /** Content x currently at the left edge of the tracks viewport. */
    private double currentScrollPx() {
        double contentWidth = tracksPane.getPrefWidth() > 0 ? tracksPane.getPrefWidth() : tracksPane.getWidth();
        return TimelineZoomMath.scrollPx(tracksScrollPane.getHvalue(), contentWidth,
                tracksScrollPane.getViewportBounds().getWidth());
    }

    // ── Thumbnails: only when needed, only near the viewport ─────────────────

    private boolean isNodeNearViewport(Clip clip) {
        double viewportWidth = tracksScrollPane.getViewportBounds().getWidth();
        return TimelineZoomMath.nearViewport(clip.startTime * pixelsPerSecond, clip.duration * pixelsPerSecond,
                currentScrollPx(), viewportWidth, viewportWidth);
    }

    /** What the clip's thumbnails/waveform depend on. Unchanged signature = nothing to regenerate. */
    private String thumbnailSignature(ClipNode node) {
        Clip clip = node.getContainerClip();
        switch (clip.type) {
            case VIDEO:
                return "V|" + node.computeTileCount() + "|" + clip.startClipTrim + "|" + clip.duration;
            case AUDIO:
                // The waveform bitmap is drawn for a specific scale.
                return "A|" + pixelsPerSecond + "|" + clip.startClipTrim + "|" + clip.duration;
            case IMAGE:
                return "I|" + clip.getClipName();
            default:
                return "O|" + clip.type;
        }
    }

    /** Generates thumbnails for a clip only if it is near the viewport and what it shows has changed. */
    private void ensureThumbnails(ClipNode node) {
        if (!isNodeNearViewport(node.getContainerClip())) return; // picked up when scrolled/zoomed near
        String signature = thumbnailSignature(node);
        if (signature.equals(node.getThumbSignature())) return;
        node.setThumbSignature(signature);
        generateThumbnailsForNode(node, signature);
    }

    /** Debounced: runs once scrolling/zooming has been quiet for a moment. */
    private void scheduleVisibleThumbnailRefresh() {
        if (thumbnailSettleTimer == null) {
            thumbnailSettleTimer = new javafx.animation.PauseTransition(javafx.util.Duration.millis(200));
            thumbnailSettleTimer.setOnFinished(e -> refreshVisibleThumbnails());
        }
        thumbnailSettleTimer.playFromStart();
    }

    private void refreshVisibleThumbnails() {
        if (timeline == null || timeline.tracks == null) return;
        for (Track track : timeline.tracks) {
            for (Clip clip : track.clips) {
                if (clip.viewRef instanceof ClipNode node && node.getParent() == tracksPane) ensureThumbnails(node);
            }
        }
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
        Pane ruler;
        if (rulerScrollPane.getContent() instanceof Pane) {
            ruler = (Pane) rulerScrollPane.getContent();
        } else {
            ruler = new Pane();
            ruler.setPickOnBounds(true);
            ruler.getStyleClass().add("timeline-ruler-pane");
            rulerScrollPane.setContent(ruler);
        }

        ruler.setPrefWidth(width);
        ruler.setPrefHeight(30);
        ruler.getChildren().clear(); // Clearing ruler ticks is okay, they are light

        float rulerInterval = getRulerInterval(pixelsPerSecond);
//        float visibleDuration = (float) (width / pixelsPerSecond);
        float visibleDuration = timeline.duration;
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
        //renderClipUI(track, clip);
        refreshTimelineUI();
    }

    /**
     * @param signature what this generation is for (see thumbnailSignature). If the node's current
     *                  signature changes, or the node leaves the timeline, the job is stale: it
     *                  stops decoding and drops its results instead of flooding the FX thread.
     */
    private void generateThumbnailsForNode(ClipNode node, String signature) {
        Clip clip = node.getContainerClip();
        java.util.function.BooleanSupplier stale =
                () -> node.getParent() == null || !signature.equals(node.getThumbSignature());

        if (clip.type == ClipType.VIDEO) {
            node.refreshThumbnails(); // make sure the tile slots exist before images arrive
            final int tileCount = node.computeTileCount();
            if (tileCount <= 0) {
                node.setThumbSignature(null); // not sized yet - try again on the next refresh
                return;
            }
            thumbnailExecutor.submit(() -> {
                float tileDuration = (float) (clip.duration / tileCount);
                int decoded = 0;
                for (int i = 0; i < tileCount; i++) {
                    if (stale.getAsBoolean())
                        return; // node removed, or re-zoomed/re-trimmed since this job was queued
                    float time = clip.startClipTrim + (i * tileDuration);
                    Image img = decodeVideoFrame(clip, time);
                    if (img != null) {
                        decoded++;
                        int finalI = i;
                        javafx.application.Platform.runLater(() -> {
                            if (!stale.getAsBoolean()) node.setThumbnailImage(finalI, img);
                        });
                    }
                }
                if (decoded == 0) {
                    // Nothing could be decoded (file not ready yet?): allow a later refresh to retry.
                    javafx.application.Platform.runLater(() -> {
                        if (signature.equals(node.getThumbSignature())) node.setThumbSignature(null);
                    });
                }
            });
        } else if (clip.type == ClipType.AUDIO) {
            final float scale = pixelsPerSecond;
            thumbnailExecutor.submit(() -> {
                if (stale.getAsBoolean()) return;
                Image img = AudioUtils.generateAudioWaveformImage(
                        clip.getAbsolutePreviewPath(project, ".wav"), clip, scale, (int) TRACK_HEIGHT, 1, 0); // 2
                if (img != null) {
                    javafx.application.Platform.runLater(() -> {
                        if (!stale.getAsBoolean()) node.setSingleThumbnail(img);
                    });
                }
            });
        } else if (clip.type == ClipType.IMAGE) {
            thumbnailExecutor.submit(() -> {
                if (stale.getAsBoolean()) return;
                try {
                    java.io.File file = new java.io.File(clip.getAbsolutePath(project));
                    if (file.exists()) {
                        Image img = new Image(file.toURI().toString(), -1, TRACK_HEIGHT, true, true);
                        javafx.application.Platform.runLater(() -> {
                            if (!stale.getAsBoolean()) node.setSingleThumbnail(img);
                        });
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
        cmd.add(FFmpegEditNative.getFfmpegPath());
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
        timeline.reassignClips(videoSettings.frameRate);
        timeline.recalculateDuration();
        project.setProjectDuration((long) (timeline.duration * 1000));

        double totalDuration = timeline.duration;
        double contentWidth = Math.max(1200, totalDuration * pixelsPerSecond + 1000); // Add 1000px padding at end

        // Refresh Ruler
        buildRuler(contentWidth);

        // Update Tracks Pane dimensions
        tracksPane.setPrefWidth(contentWidth);
        tracksPane.setPrefHeight(timeline.tracks.size() * (TRACK_HEIGHT + TRACK_SPACING));

        // Use a set to track nodes that should remain in the pane
        java.util.Set<javafx.scene.Node> activeNodes = new java.util.HashSet<>();
        // Clips that exist after this refresh: used to drop deleted clips from the selection
        java.util.Set<Clip> liveClips = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        for (Track track : timeline.tracks) {
            // Add track band
            double y = track.timelineIndex * (TRACK_HEIGHT + TRACK_SPACING);
            
            // 1. Manage Track Band (Background)
            String bandId = "track-band-" + track.timelineIndex;
            Rectangle band = trackBands.get(track.timelineIndex);
            if (band != null && band.getParent() != tracksPane) band = null; // was removed elsewhere
            if (band == null) {
                band = new Rectangle(0, y, contentWidth, TRACK_HEIGHT);
                band.setId(bandId);
                band.getStyleClass().add(track.timelineIndex % 2 == 0 ? "track-band-even" : "track-band-odd");
                band.setMouseTransparent(true); // Crucial: don't interrupt gestures
                tracksPane.getChildren().add(0, band); // Add at back
                trackBands.put(track.timelineIndex, band);
            } else {
                band.setY(y);
                band.setWidth(contentWidth);
                band.toBack();
            }
            activeNodes.add(band);

            // 2. Manage Clips
            for (Clip clip : track.clips) {
                ClipNode node;
                if (clip.viewRef instanceof ClipNode && ((ClipNode) clip.viewRef).getParent() == tracksPane) { // O(1); contains() scanned every child
                    node = (ClipNode) clip.viewRef;
                } else {
                    node = new ClipNode(clip, this);
                    setupClipInteraction(node);
                    node.setOnKeyframeClicked(kf -> {
                        updateCurrentTime(kf.getGlobalTime(clip));
                        refreshTimelineUI();
                    });
                    node.setOnKeyframeMoved((kf, oldT, newT) -> {
                        if (oldT == newT) return;
                        executePropertyChange("Move Keyframe", () -> {
                            kf.setLocalTime(newT);
                            clip.keyframes.sortKeyframe();
                            refreshTimelineUI();
                            updatePropertiesPane();
                            saveProject();
                        }, () -> {
                            kf.setLocalTime(oldT);
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
                    tracksPane.getChildren().add(node);
                    clip.viewRef = node;
                }

                // Update existing or new node properties
                double clipX = clip.startTime * pixelsPerSecond;
                double clipW = clip.duration * pixelsPerSecond;
                double clipH = TRACK_HEIGHT - 6;

                node.setLayoutX(clipX);
                node.setLayoutY(y + 3);
                node.setPrefWidth(clipW);
                node.setMinWidth(clipW);
                node.setMaxWidth(clipW);
                node.setPrefHeight(clipH);
                node.setMinHeight(clipH);
                node.setMaxHeight(clipH);

                node.setSelected(selectedClips.contains(clip), clip == selectedClip);
                node.setOpacity(pendingCut.contains(clip) ? CUT_OPACITY : 1.0);
                liveClips.add(clip);
                node.updateKeyframes(pixelsPerSecond);
                node.setupTrimInteractions(pixelsPerSecond);
                
                activeNodes.add(node);

                // 3. Manage Transitions
                renderTransitionCubeIfNeededStable(track, clip, activeNodes);
                
                // Regenerates only if this clip is near the viewport and what it shows changed
                ensureThumbnails(node);
            }
        }

        // Cleanup: remove any nodes that are no longer active (deleted clips/tracks)
        // We exclude playhead overlay components if they are in the same pane
        // (But in this app, playhead is in a separate Pane, so we're safe)
        if (marqueeRect != null) activeNodes.add(marqueeRect);
        tracksPane.getChildren().removeIf(n -> !activeNodes.contains(n));

        // A delete / undo can remove clips that were selected: forget them so nothing acts on a ghost.
        boolean selectionChanged = selectedClips.removeIf(c -> !liveClips.contains(c));
        if (selectedClip != null && !selectedClips.contains(selectedClip)) {
            selectedClip = null;
            for (Clip c : selectedClips) selectedClip = c;
            selectionChanged = true;
        }
        if (selectionChanged) updatePropertiesPane();
        if (pendingCut.removeIf(c -> !liveClips.contains(c)) && pendingCut.isEmpty()) pendingCutPayload = null;

        trackBands.values().removeIf(n -> !activeNodes.contains(n));
        transitionCubes.values().removeIf(n -> !activeNodes.contains(n));

        // Rebuild TimelineRenderer
        if (timelineRenderer != null) {
            timelineRenderer.buildTimeline(timeline);
        }

        // Update playhead
        updateCurrentTime(currentTime);
    }

    // =========================================================================
    // Transition cube rendering
    // =========================================================================

    private static final double TRANSITION_CUBE_SIZE = 16.0;
    private static final double SNAP_TOLERANCE = 0.05f; // seconds — clips this close are "touching"

    /** Optimized transition cube rendering that reuses nodes. */
    private void renderTransitionCubeIfNeededStable(Track track, Clip clip, java.util.Set<javafx.scene.Node> activeNodes) {
        Clip next = null;
        for (Clip c : track.clips) {
            if (c == clip) continue;
            float gap = c.startTime - (clip.startTime + clip.duration);
            if (gap >= -SNAP_TOLERANCE && gap <= SNAP_TOLERANCE) {
                if (next == null || c.startTime < next.startTime) next = c;
            }
        }
        if (next == null) return;

        if (clip.endTransition == null) {
            clip.endTransition = new TransitionClip(clip, next, 0.5f);
        }
        clip.endTransitionEnabled = true;

        double trackY = track.timelineIndex * (TRACK_HEIGHT + TRACK_SPACING);
        double cubeX = (clip.startTime + clip.duration) * pixelsPerSecond - TRANSITION_CUBE_SIZE / 2.0;
        double cubeY = trackY + (TRACK_HEIGHT / 2.0) - (TRANSITION_CUBE_SIZE / 2.0);

        String cubeId = "transition-cube-" + clip.hashCode();
        Rectangle cube = transitionCubes.get(clip);
        if (cube != null && cube.getParent() != tracksPane) cube = null;
        if (cube == null) {
            cube = new Rectangle(cubeX, cubeY, TRANSITION_CUBE_SIZE, TRANSITION_CUBE_SIZE);
            cube.setId(cubeId);
            cube.setArcWidth(3);
            cube.setArcHeight(3);
            cube.getStyleClass().add("transition-cube");
            cube.setFill(Color.web("#5C67FF"));
            cube.setStroke(Color.web("#9BA3FF"));
            cube.setStrokeWidth(1.5);
            cube.setUserData(clip);

            cube.setOnMouseEntered(e -> ((Rectangle)e.getSource()).setFill(Color.web("#7B85FF")));
            cube.setOnMouseExited(e -> ((Rectangle)e.getSource()).setFill(Color.web("#5C67FF")));

            Clip finalClip = clip;
            cube.setOnMouseClicked(e -> {
                selectedTransitionSourceClip = finalClip;
                clearClipSelection();
                updatePropertiesPane();
                e.consume();
            });
            tracksPane.getChildren().add(cube);
            transitionCubes.put(clip, cube);
        } else {
            cube.setX(cubeX);
            cube.setY(cubeY);
            cube.toFront();
        }
        activeNodes.add(cube);
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
            activeDrag.collapseOnRelease = false;
            activeDrag.members.clear();
            activeDrag.ghosts.clear();

            if (e.isShortcutDown()) {
                // Ctrl (Win/Linux) / Cmd (macOS) + click: add to / remove from the selection.
                toggleClipSelection(clip);
                if (!selectedClips.contains(clip)) {
                    // It was just removed from the selection: nothing to drag.
                    activeDrag.clip = null;
                    activeDrag.dragging = false;
                    activeDrag.ghost = null;
                    e.consume();
                    return;
                }
            } else if (!selectedClips.contains(clip)) {
                // Plain click on an unselected clip: it becomes the only selection.
                selectClip(clip);
            } else {
                // Plain press on a clip that is already selected: keep the whole selection so it can be dragged
                // as a group. If it turns out to be a click, not a drag, it collapses to this clip on release.
                makePrimary(clip);
                activeDrag.collapseOnRelease = selectedClips.size() > 1;
            }

            // Prepare drag context
            activeDrag.clip = clip;
            activeDrag.currentTrackIdx = clip.trackIndex;
            activeDrag.anchorTrack = clip.trackIndex;
            activeDrag.dragOffsetX = e.getX(); // offset within the node
            activeDrag.dragging = false;
            activeDrag.ghost = null;
            activeDrag.isNewClip = false;
            activeDrag.deltaPx = 0;
            activeDrag.deltaTrack = 0;
            activeDrag.members.addAll(selectedClips);
            e.consume();
        });

        node.setOnMouseClicked(MouseEvent::consume);

        node.setOnMouseDragged(e -> {
            if (activeDrag.clip != clip || activeDrag.isNewClip)
                return;

            // Create the ghosts on first drag pixel (CapCut style — no threshold): one per selected clip.
            if (!activeDrag.dragging) {
                activeDrag.dragging = true;

                int minTrack = Integer.MAX_VALUE, maxTrack = Integer.MIN_VALUE;
                float minStart = Float.MAX_VALUE, maxEnd = 0f;
                for (Clip m : activeDrag.members) {
                    minTrack = Math.min(minTrack, m.trackIndex);
                    maxTrack = Math.max(maxTrack, m.trackIndex);
                    minStart = Math.min(minStart, m.startTime);
                    maxEnd = Math.max(maxEnd, m.startTime + m.duration);
                }
                activeDrag.minMemberTrack = minTrack;
                activeDrag.maxMemberTrack = maxTrack;
                activeDrag.minMemberStart = minStart;
                activeDrag.groupWidthPx = (maxEnd - minStart) * pixelsPerSecond;

                for (Clip m : activeDrag.members) {
                    if (!(m.viewRef instanceof ClipNode orig)) continue;
                    ClipNode ghost = new ClipNode(m, this);
                    ghost.getStyleClass().add("clip-node-ghost");
                    ghost.setOpacity(0.55);
                    ghost.setPrefWidth(orig.getPrefWidth());
                    ghost.setPrefHeight(orig.getPrefHeight());
                    ghost.setMinWidth(orig.getPrefWidth());
                    ghost.setMinHeight(orig.getPrefHeight());
                    ghost.setMaxWidth(orig.getPrefWidth());
                    ghost.setMaxHeight(orig.getPrefHeight());
                    ghost.setLayoutX(orig.getLayoutX());
                    ghost.setLayoutY(orig.getLayoutY());
                    ghost.setMouseTransparent(true);
                    orig.setVisible(false); // hide original
                    tracksPane.getChildren().add(ghost);
                    activeDrag.ghosts.put(m, ghost);
                }
                activeDrag.ghost = activeDrag.ghosts.get(clip);
                if (activeDrag.ghost == null) { // should not happen; fall back to "no drag"
                    activeDrag.dragging = false;
                    return;
                }
            }

            updateActiveDragGhost(e.getSceneX(), e.getSceneY());
            checkEdgeScroll(e.getSceneX(), e.getSceneY());

            e.consume();
        });

        node.setOnMouseReleased(e -> {
            edgeScrollTimer.stop();
            edgeScrollVelocity = 0;
            edgeScrollVelocityY = 0;
            if (activeDrag.clip != clip || activeDrag.isNewClip)
                return;

            List<MoveClipsCommand.Entry> moves = null;
            if (activeDrag.dragging && activeDrag.ghost != null) {
                float dt = (float) (activeDrag.deltaPx / pixelsPerSecond);
                int dTrack = activeDrag.deltaTrack;
                if (Math.abs(dt) > 1e-6f || dTrack != 0) {
                    moves = new ArrayList<>();
                    for (Clip m : activeDrag.members) {
                        moves.add(new MoveClipsCommand.Entry(m,
                                m.startTime, Math.max(0f, m.startTime + dt),
                                m.trackIndex, m.trackIndex + dTrack));
                    }
                }
            } else if (activeDrag.collapseOnRelease && !e.isShortcutDown()) {
                // A plain click (no drag) on a member of a multi-selection: select just that clip.
                selectClip(clip);
            }

            // Tear the drag visuals down BEFORE committing so the refresh sees a clean pane.
            for (ClipNode g : activeDrag.ghosts.values()) tracksPane.getChildren().remove(g);
            clearPhantomTracks();
            for (Clip m : activeDrag.members) {
                if (m.viewRef instanceof ClipNode cn) cn.setVisible(true);
            }
            node.setVisible(true);

            if (moves != null) {
                historyManager.execute(new MoveClipsCommand(timeline, moves, () -> {
                    updateCurrentClipEnd();
                    refreshTrackHeaders();   // the group may have created tracks
                    refreshTimelineUI();
                    saveProject();
                }));
            }

            // Reset drag state
            activeDrag.clip = null;
            activeDrag.ghost = null;
            activeDrag.members.clear();
            activeDrag.ghosts.clear();
            activeDrag.collapseOnRelease = false;
            Platform.runLater(() -> activeDrag.dragging = false);

            // Refresh so clips redraw at their committed position
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
                if (other == activeDrag.clip || activeDrag.members.contains(other))
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

    private void beginMarquee() {
        marqueeActive = true;
        marqueeBaseline.clear();
        marqueePrimaryBefore = marqueeAdditive ? selectedClip : null;
        if (marqueeAdditive) marqueeBaseline.addAll(selectedClips);
        marqueeLastHits = new ArrayList<>();

        marqueeRect = new Rectangle();
        marqueeRect.setFill(Color.web("#00D4FF", 0.15));
        marqueeRect.setStroke(Color.web("#00D4FF"));
        marqueeRect.setStrokeWidth(1);
        marqueeRect.setMouseTransparent(true);
        tracksPane.getChildren().add(marqueeRect);

        if (!marqueeAdditive) {
            // Starting a fresh marquee drops the old selection right away, like a file manager.
            selectedClips.clear();
            selectedClip = null;
            refreshSelectionVisuals();
        }
    }

    /** Resize the rectangle to the pointer and select exactly (baseline + clips it touches). */
    private void updateMarquee(double sceneX, double sceneY) {
        if (!marqueeActive || marqueeRect == null) return;

        double rowH = TRACK_HEIGHT + TRACK_SPACING;
        javafx.geometry.Point2D local = tracksPane.sceneToLocal(sceneX, sceneY);
        double maxX = Math.max(tracksPane.getWidth(), tracksPane.getPrefWidth());
        double maxY = Math.max(tracksPane.getHeight(), timeline.tracks.size() * rowH);
        double x = Math.max(0, Math.min(maxX, local.getX()));
        double y = Math.max(0, Math.min(maxY, local.getY()));

        marqueeRect.setX(Math.min(marqueeStartX, x));
        marqueeRect.setY(Math.min(marqueeStartY, y));
        marqueeRect.setWidth(Math.abs(x - marqueeStartX));
        marqueeRect.setHeight(Math.abs(y - marqueeStartY));
        marqueeRect.toFront();

        List<Clip> hits = MarqueeSelection.clipsInRect(timeline, marqueeStartX, marqueeStartY, x, y,
                pixelsPerSecond, rowH, 3, TRACK_HEIGHT - 6);
        if (hits.equals(marqueeLastHits)) return; // nothing changed: skip the repaint
        marqueeLastHits = hits;

        selectedClips.clear();
        selectedClips.addAll(marqueeBaseline);
        selectedClips.addAll(hits);

        Clip primary = null;
        for (Clip c : hits) primary = c;                       // the last clip the rectangle touched
        if (primary == null) {
            if (marqueePrimaryBefore != null && selectedClips.contains(marqueePrimaryBefore)) primary = marqueePrimaryBefore;
            else for (Clip c : selectedClips) primary = c;
        }
        selectedClip = primary;
        refreshSelectionVisuals();
    }

    private void endMarquee() {
        marqueeActive = false;
        if (marqueeRect != null) {
            tracksPane.getChildren().remove(marqueeRect);
            marqueeRect = null;
        }
        marqueeLastHits = new ArrayList<>();
        marqueeBaseline.clear();
        edgeScrollTimer.stop();
        edgeScrollVelocity = 0;
        edgeScrollVelocityY = 0;

        // The click that follows the release must not deselect what the marquee just selected.
        marqueeJustFinished = true;
        Platform.runLater(() -> marqueeJustFinished = false);

        selectedTrack = selectedClip != null ? timeline.tracks.get(selectedClip.trackIndex) : null;
        updatePropertiesPane(); // once, at the end, not on every mouse move
    }

    /** Select ONLY this clip. */
    private void selectClip(Clip clip) {
        // Move playhead at the beginning of the clip
        if(currentTime < clip.startTime) {
            updateCurrentTime(clip.startTime);
        }

        selectedClips.clear();
        selectedClips.add(clip);
        selectedClip = clip;
        selectedTrack = timeline.tracks.get(clip.trackIndex);

        refreshSelectionVisuals();
        updatePropertiesPane();
    }

    /** Ctrl/Cmd+click: add the clip to the selection, or take it out if it is already in. */
    private void toggleClipSelection(Clip clip) {
        if (selectedClips.contains(clip)) {
            selectedClips.remove(clip);
            if (selectedClip == clip) {
                selectedClip = null;
                for (Clip c : selectedClips) selectedClip = c; // last one left becomes the primary
            }
        } else {
            selectedClips.add(clip);
            selectedClip = clip;
        }
        if (selectedClip != null) selectedTrack = timeline.tracks.get(selectedClip.trackIndex);

        refreshSelectionVisuals();
        updatePropertiesPane();
    }

    /** Make an already-selected clip the primary one (the properties panel follows it). */
    private void makePrimary(Clip clip) {
        if (selectedClip == clip) return;
        selectedClip = clip;
        selectedTrack = timeline.tracks.get(clip.trackIndex);
        refreshSelectionVisuals();
        updatePropertiesPane();
    }

    /** Select exactly these clips; the last one is the primary. */
    private void selectClips(List<Clip> clips) {
        selectedClips.clear();
        selectedClips.addAll(clips);
        selectedClip = null;
        for (Clip c : clips) selectedClip = c;
        if (selectedClip != null) selectedTrack = timeline.tracks.get(selectedClip.trackIndex);

        refreshSelectionVisuals();
        updatePropertiesPane();
    }

    private void selectAllClips() {
        List<Clip> all = timeline.getAllClips();
        if (all.isEmpty()) return;
        Clip keepPrimary = selectedClip != null && all.contains(selectedClip) ? selectedClip : all.get(all.size() - 1);
        selectedClips.clear();
        selectedClips.addAll(all);
        selectedClip = keepPrimary;
        selectedTrack = timeline.tracks.get(keepPrimary.trackIndex);

        refreshSelectionVisuals();
        updatePropertiesPane();
    }

    /** Clears the clip selection (leaves track / transition selection alone). */
    private void clearClipSelection() {
        selectedClips.clear();
        selectedClip = null;
        refreshSelectionVisuals();
    }

    private void deselectAll() {
        selectedClips.clear();
        selectedClip = null;
        selectedTrack = null;
        refreshSelectionVisuals();
        updatePropertiesPane();
    }

    /** Push the selection state onto every clip node. Only the primary clip shows trim handles. */
    private void refreshSelectionVisuals() {
        for (Track t : timeline.tracks) {
            for (Clip c : t.clips) {
                if (c.viewRef instanceof ClipNode cn) {
                    cn.setSelected(selectedClips.contains(c), c == selectedClip);
                }
            }
        }
        if (previewGizmo != null) previewGizmo.refresh();
    }

    /** Selected clips in timeline order (track, then start time): the order they are copied in. */
    private List<Clip> orderedSelection() {
        List<Clip> list = new ArrayList<>(selectedClips);
        list.sort((x, y) -> x.trackIndex != y.trackIndex
                ? Integer.compare(x.trackIndex, y.trackIndex)
                : Float.compare(x.startTime, y.startTime));
        return list;
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
            List<Clip> allClipsAtTime = timeline.getClipsAtCurrentTime(currentTime);
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
        textClip.textColor = "#FFFFFF"; // white: black text vanishes on dark footage
        
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
            textClip.textColor = "#FFFFFF";
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
        if (!selectedClips.isEmpty()) {
            // Everything selected goes in ONE undo step.
            historyManager.execute(new DeleteClipsCommand(timeline, orderedSelection(), () -> {
                refreshTimelineUI();
                saveProject();
            }));
            // refreshTimelineUI() has already dropped the deleted clips from the selection.
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
    private void handleClone()
    {
        if(selectedClip != null) {
            Clip clipToClone = new Clip(selectedClip);
            clipToClone.startTime = Math.max(0f, selectedClip.getDuration() + selectedClip.getStartTime());
            clipToClone.trackIndex = selectedClip.trackIndex;

            historyManager.execute(new AddClipCommand(timeline, clipToClone, selectedClip.trackIndex, () -> {
                updateCurrentClipEnd();
                refreshTimelineUI();
                saveProject();
            }));
        } else if (selectedTrack != null) {
            Track track = new Track(selectedTrack);
            executePropertyChange("Add Track", () -> {
                timeline.addTrack(track);
                timeline.reloadTrackIndex();
                refreshTrackHeaders();
                refreshTimelineUI();
                saveProject();
            }, () -> {
                timeline.removeTrack(track);
                timeline.reloadTrackIndex();
                refreshTrackHeaders();
                refreshTimelineUI();
                saveProject();
            });
        }
    }

    /** Same JSON layout as the project file (only @Expose fields), so what is copied is exactly what is saved. */
    private final Gson clipboardGson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

    /**
     * Copies the selected clips to the system clipboard as a JSON ARRAY of clips (one element when a single
     * clip is selected), in timeline order. With no clip selected, a selected track is copied instead.
     */
    private void handleCopy() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        if (!selectedClips.isEmpty() || selectedTrack != null) cancelPendingCut(); // a new copy replaces the cut
        if (!selectedClips.isEmpty()) {
            content.putString(clipboardGson.toJson(orderedSelection().toArray(new Clip[0])));
            clipboard.setContent(content);
        } else if (selectedTrack != null) {
            content.putString(new Gson().toJson(selectedTrack));
            clipboard.setContent(content);
        }
    }

    /**
     * Pastes from the clipboard. Understood formats: a JSON array of clips (what Copy writes), a single clip
     * object (older copies) and a track object. Anything else (plain text ...) is ignored.
     */
    private void handlePaste() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (!clipboard.hasString()) return;
        String pasteText = clipboard.getString();

        if (!pendingCut.isEmpty()) {
            if (pasteText.equals(pendingCutPayload) && cutSourcesStillInTimeline()) {
                pasteCut();   // moves the cut clips: "paste into the track and delete the old one"
                return;
            }
            cancelPendingCut(); // the clipboard moved on, or the cut clips are gone: treat this as a normal paste
        }

        JsonElement root;
        try {
            root = new JsonParser().parse(pasteText);
        } catch (RuntimeException e) {
            return; // not JSON
        }

        if (root.isJsonObject() && root.getAsJsonObject().has("clips") && !root.getAsJsonObject().has("type")) {
            pasteTrack(pasteText);
            return;
        }

        List<Clip> pasted = new ArrayList<>();
        try {
            if (root.isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray()) {
                    Clip c = parseClipFromJson(el);
                    if (c != null) pasted.add(c);
                }
            } else {
                Clip c = parseClipFromJson(root);
                if (c != null) pasted.add(c);
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
            return;
        }
        if (!pasted.isEmpty()) pasteClips(pasted);
    }

    /** One clip from clipboard JSON, or null when the element does not look like a clip. */
    private Clip parseClipFromJson(JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject obj = el.getAsJsonObject();
        if (!obj.has("clipName") && !obj.has("type")) return null;
        Clip c = clipboardGson.fromJson(obj, Clip.class);
        if (c == null) return null;
        c.filterNullAfterLoad();
        return c;
    }

    /**
     * Pastes a group so it keeps its internal layout (the same gaps in time, the same track spacing):
     *  - time: the earliest pasted clip starts at the playhead; if the playhead sits exactly on the start of
     *    the current selection, the group goes right after the selection instead so it doesn't land on top of it;
     *  - tracks: the topmost pasted clip goes on the topmost selected track, or on its original track when
     *    nothing is selected. Tracks that don't exist yet are created.
     * The pasted clips become the selection. One undo step.
     */
    private void pasteClips(List<Clip> pasted) {
        ClipGroupMath.PastePlan plan = computePastePlan(pasted, null);

        List<AddClipsCommand.Entry> entries = new ArrayList<>();
        for (Clip c : pasted) {
            int newTrack = Math.max(0, c.trackIndex + plan.trackShift);
            float newStart = Math.max(0f, c.startTime + plan.timeShift);
            float shift = newStart - c.startTime;
            if (c.endTransition != null) { // keep its settings, move it along with the clip
                c.endTransition.trackIndex = newTrack;
                c.endTransition.startTime += shift;
                if (c.endTransition.effect != null) c.endTransition.effect.startTime += shift;
            }
            c.startTime = newStart;
            c.trackIndex = newTrack;
            entries.add(new AddClipsCommand.Entry(c, newTrack));
        }

        historyManager.execute(new AddClipsCommand(timeline, entries, () -> {
            updateCurrentClipEnd();
            refreshTrackHeaders();   // the paste may have created tracks
            refreshTimelineUI();
            saveProject();
        }));
        selectClips(pasted);
    }

    /**
     * Where a pasted (or cut-and-pasted) group lands: its earliest clip at the playhead, its topmost clip on the topmost
     * selected track (or on its own track when nothing is selected). If the playhead sits exactly on the start of the
     * selection, the group goes right after the selection instead of on top of it.
     *
     * @param ignoreInSelection clips that must not count as "the selection" (the cut clips themselves, which are about to move)
     */
    private ClipGroupMath.PastePlan computePastePlan(List<Clip> group, java.util.Set<Clip> ignoreInSelection) {
        float groupMinStart = Float.MAX_VALUE;
        int groupMinTrack = Integer.MAX_VALUE;
        for (Clip c : group) {
            groupMinStart = Math.min(groupMinStart, c.startTime);
            groupMinTrack = Math.min(groupMinTrack, c.trackIndex);
        }
        groupMinTrack = Math.max(0, groupMinTrack);

        float anchorStart = Math.max(0f, currentTime);
        int baseTrack = groupMinTrack;

        float selMinStart = Float.MAX_VALUE, selMaxEnd = 0f;
        int selMinTrack = Integer.MAX_VALUE;
        boolean haveSelection = false;
        for (Clip c : selectedClips) {
            if (ignoreInSelection != null && ignoreInSelection.contains(c)) continue;
            haveSelection = true;
            selMinStart = Math.min(selMinStart, c.startTime);
            selMaxEnd = Math.max(selMaxEnd, c.startTime + c.duration);
            selMinTrack = Math.min(selMinTrack, c.trackIndex);
        }
        if (haveSelection) {
            baseTrack = selMinTrack;
            if (Math.abs(currentTime - selMinStart) < 0.001f) anchorStart = selMaxEnd;
        }
        return ClipGroupMath.planPaste(groupMinStart, groupMinTrack, anchorStart, baseTrack);
    }

    /** Ctrl/Cmd+X: copy the selected clips to the clipboard and dim them; they are only moved when pasted. */
    private void handleCut() {
        if (selectedClips.isEmpty()) return;
        List<Clip> ordered = orderedSelection();
        String json = clipboardGson.toJson(ordered.toArray(new Clip[0]));

        ClipboardContent content = new ClipboardContent();
        content.putString(json);
        Clipboard.getSystemClipboard().setContent(content);

        pendingCut.clear();
        pendingCut.addAll(ordered);
        pendingCutPayload = json;
        refreshCutVisuals();
    }

    private void cancelPendingCut() {
        if (pendingCut.isEmpty() && pendingCutPayload == null) return;
        pendingCut.clear();
        pendingCutPayload = null;
        refreshCutVisuals();
    }

    private void refreshCutVisuals() {
        for (Track t : timeline.tracks) {
            for (Clip c : t.clips) {
                if (c.viewRef instanceof ClipNode cn) cn.setOpacity(pendingCut.contains(c) ? CUT_OPACITY : 1.0);
            }
        }
    }

    private boolean cutSourcesStillInTimeline() {
        for (Clip c : pendingCut) {
            if (c.trackIndex < 0 || c.trackIndex >= timeline.tracks.size()) return false;
            if (!timeline.tracks.get(c.trackIndex).clips.contains(c)) return false;
        }
        return true;
    }

    /**
     * Paste after a cut: the SAME clips are moved to the paste position (so they keep their identity, thumbnails
     * and settings) instead of being copied and the old ones deleted. One undo step. The cut is then used up:
     * pasting again inserts copies from the clipboard as usual.
     */
    private void pasteCut() {
        List<Clip> group = new ArrayList<>(pendingCut);
        ClipGroupMath.PastePlan plan = computePastePlan(group, pendingCut);

        List<MoveClipsCommand.Entry> moves = new ArrayList<>();
        for (Clip c : group) {
            moves.add(new MoveClipsCommand.Entry(c,
                    c.startTime, Math.max(0f, c.startTime + plan.timeShift),
                    c.trackIndex, Math.max(0, c.trackIndex + plan.trackShift)));
        }

        boolean nothingChanges = true;
        for (MoveClipsCommand.Entry m : moves) {
            if (Math.abs(m.newStart - m.oldStart) > 1e-6f || m.newTrack != m.oldTrack) { nothingChanges = false; break; }
        }

        cancelPendingCut(); // before the refresh, so the clips come back at full opacity
        if (nothingChanges) return;

        historyManager.execute(new MoveClipsCommand(timeline, moves, () -> {
            updateCurrentClipEnd();
            refreshTrackHeaders();   // the move may have created tracks
            refreshTimelineUI();
            saveProject();
        }));
        selectClips(group);
    }

    private void pasteTrack(String json) {
        Track pasteTrack;
        try {
            pasteTrack = new Gson().fromJson(json, Track.class);
        } catch (JsonSyntaxException e) {
            return;
        }
        if (pasteTrack == null) return;
        executePropertyChange("Add Track", () -> {
            timeline.addTrack(pasteTrack);
            timeline.reloadTrackIndex();
            refreshTrackHeaders();
            refreshTimelineUI();
            saveProject();
        }, () -> {
            timeline.removeTrack(pasteTrack);
            timeline.reloadTrackIndex();
            refreshTrackHeaders();
            refreshTimelineUI();
            saveProject();
        });
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
