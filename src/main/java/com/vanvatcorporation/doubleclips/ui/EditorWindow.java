package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.i18n.I18n;

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
import com.vanvatcorporation.doubleclips.ProjectLibrary;
import com.vanvatcorporation.doubleclips.helper.FrameHelper;
import com.vanvatcorporation.doubleclips.helper.MediaHelper;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.FFmpegEdit;
import javafx.scene.Node;
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
    private DockManager dock;

    private final ProjectData project;
    private Timeline timeline;
    private VideoSettings videoSettings;

    private TimelineRenderer timelineRenderer;
    /** The Source view of the preview: a file clicked in the Media tab, shown alone with its own seek bar. */
    private SourcePreview sourcePreview;
    private com.vanvatcorporation.doubleclips.ui.renderer.PreviewGizmo previewGizmo;
    private final HistoryManager historyManager = new HistoryManager();
    { historyManager.setBeforeChange(this::endTakeFromEditor); }   // an edit / undo / redo ends a running voiceover take
    private final MaskEditState maskEditState = new MaskEditState();
    private final com.vanvatcorporation.doubleclips.ui.components.GpuTilePreviews tilePreviews =
            new com.vanvatcorporation.doubleclips.ui.components.GpuTilePreviews(
                    () -> this.timelineRenderer == null ? null : this.timelineRenderer.tileClient());

    @Override
    public com.vanvatcorporation.doubleclips.ui.components.GpuTilePreviews tilePreviews() { return tilePreviews; }

    @Override
    public MaskEditState maskEditState() { return maskEditState; }

    @Override
    public void showLiveMasks(Clip clip, java.util.List<com.vanvatcorporation.doubleclips.data.editing.ClipMask> masks) {
        timelineRenderer.showLiveMasks(clip, masks, currentTime);
    }

    @Override
    public void syncPreview() { timelineRenderer.syncWorker(currentTime); }
    @Override
    public void showLiveChromaKeys(Clip clip, java.util.List<com.vanvatcorporation.doubleclips.data.editing.ChromaKey> keys) {
        timelineRenderer.showLiveChromaKeys(clip, keys, currentTime);
    }
    @Override
    public boolean pickColorFromPreview(java.util.function.Consumer<javafx.scene.paint.Color> onPicked) {
        return timelineRenderer != null && timelineRenderer.pickColor(onPicked);
    }
    @Override
    public void cancelColorPick() { if (timelineRenderer != null) timelineRenderer.cancelColorPick(); }

    // Editor State
    private float currentTime = 0f;
    private boolean isPlaying = false;
    private boolean isPlayingInReverse = false;

    // Voiceover (see VoiceoverController): while a take is recorded the clock must not stop at the end of the timeline.
    private VoiceoverController voiceover;
    private VoiceoverLiveClip voiceLive;      // the growing temporary clip, a view only
    private int voiceLiveTrack = -1;
    private float voiceLiveStart;
    private boolean recordingPlayback;
    private boolean clockTick;                // true only while the playback timer itself moves the playhead
    private float savedPreviewSpeed = 1.0f;
    private boolean savedReverse;
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
    /** The track the user selected on purpose (header click / menu): shown highlighted, the target of Delete / Copy / Paste. */
    private Track explicitTrack;
    private Track shownTrackSelection;          // what the headers currently show, to rebuild them only on a change
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
    /** Track reorder: grips are shown on the track headers while this is on; dragging a grip moves that track. */
    private boolean reorderingTracks = false;
    private Button reorderTracksButton;
    private com.vanvatcorporation.doubleclips.ui.TrackReorderDrag trackDrag;
    private double trackDragStartSceneY;
    private Line playheadLine;
    private Line ghostPlayheadLine;
    private float tempTime = -1;
    private Slider zoomSlider;
    private FlowPane mediaGrid;
    // Media tab (the project's files): what is on disk, how it is shown, and the badge that counts clips using each file
    private static final class MediaEntry {
        final com.vanvatcorporation.doubleclips.ProjectLibrary.Item item;
        final MediaHelper.MediaInfo info;

        MediaEntry(com.vanvatcorporation.doubleclips.ProjectLibrary.Item item, MediaHelper.MediaInfo info) {
            this.item = item;
            this.info = info;
        }
    }
    private final List<MediaEntry> mediaEntries = new ArrayList<>();
    private final java.util.Map<String, MediaHelper.MediaInfo> mediaInfoCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Label> usageBadges = new java.util.HashMap<>();
    private com.vanvatcorporation.doubleclips.ProjectLibrary.Filter mediaFilter = com.vanvatcorporation.doubleclips.ProjectLibrary.Filter.ALL;
    private com.vanvatcorporation.doubleclips.ProjectLibrary.Sort mediaSort = com.vanvatcorporation.doubleclips.ProjectLibrary.Sort.NEWEST;
    private HBox mediaToolbar;
    private com.vanvatcorporation.doubleclips.ui.components.TilePreviewAnimator effectAnimator;
    private final Label toastLabel = new Label();
    private javafx.animation.SequentialTransition toastAnimation;
    private boolean usageRefreshQueued = false;
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

    private final com.vanvatcorporation.doubleclips.ReverseManager reverseManager;

    @Override
    public void applyReverse(Clip clip) {
        if (clip == null) return;
        if (clip.isReverse) reverseManager.apply(clip);
        else reverseManager.release(clip);
    }

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

        // Reversed copies of the source files (a clip with Reverse on plays a whole-file reversed copy): made in the background, and
        // looked at once the window is up (a flagged clip that has no copy yet gets one, a copy nobody uses any more is removed).
        reverseManager = new com.vanvatcorporation.doubleclips.ReverseManager(
                FFmpegEditNative.getFfmpegPath(),
                () -> new java.io.File(project.getProjectPath(), com.vanvatcorporation.doubleclips.constants.Constants.DEFAULT_CLIP_DIRECTORY),
                () -> timeline.getAllClips(),
                Platform::runLater,
                () -> { refreshTimelineUI(); saveProject(); },
                message -> System.err.println("[Reverse] " + message),
                com.vanvatcorporation.doubleclips.ClipProcessingTracker.get());
        Platform.runLater(() -> { reverseManager.syncAll(); saveProject(); });

        BorderPane mainContent = new BorderPane();
        mainContent.getStyleClass().add("editor-root");

        // === Main 3-column middle section ===
        HBox centerRow = new HBox();
        HBox.setHgrow(centerRow, Priority.ALWAYS);

        VBox leftPanel = buildLeftPanel();
        VBox previewPanel = buildPreviewPanel();
        VBox rightPanel = buildRightPanel();

        // leftPanel / previewPanel / rightPanel are docked into centerRow by the DockManager below.

        // === Bottom Timeline ===
        VBox timelineArea = buildTimelineArea();

        StackPane root = new StackPane(mainContent);

        MenuBar menuBar = new MenuBar();
        menuBar.setUseSystemMenuBar(true);
        Menu appMenu = new Menu(I18n.t("File"));
        MenuItem settingsItem = new MenuItem(I18n.t("Preferences..."));
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
        // The timeline is docked into the bottom of mainContent by the DockManager below.

        Scene scene = new Scene(root);
        scene.getStylesheets().add(DoubleClipsDesktop.class.getResource("/style.css").toExternalForm());

        javafx.event.EventHandler<javafx.scene.input.KeyEvent> shortcutFilter = event -> {
            // While the user types into any text control, every key belongs to that control: Delete / Backspace must edit the
            // text, Cmd+A / C / V / X / Z must act on the text, and Space must type a space - not run the editor's shortcut.
            if ((event.getTarget() instanceof javafx.scene.Node && isTextEntry((javafx.scene.Node) event.getTarget()))
                    || isTextEntry(scene.getFocusOwner()))
                return;
            // Settings is recording a new shortcut: it needs the raw key press, not the action bound to it.
            if (AppSettings.getInstance().isRecordingKeybind())
                return;

            // The Source view is up: I / O set the in / out points, Esc goes back to the whole file
            if (sourcePreview != null && sourcePreview.handleKey(event)) {
                event.consume();
                return;
            }

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
                Track onlyTrack = selectedTrackOnly();
                if (onlyTrack != null) trackActions.selectAllClips(onlyTrack);   // a track is selected: its clips first
                else selectAllClips();
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
        };
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, shortcutFilter);

        // Media, preview and properties are dockable panels: drag a panel's grip to move it or pop it out into its
        // own window (floating windows get the same shortcuts). Hover a docked panel's edge to resize it.
        dock = new DockManager(this, mainContent, centerRow, root, shortcutFilter);
        dock.addPanel("Media", leftPanel, 320, 240, 720, false, 240);
        dock.addPanel("Preview", previewPanel, 640, 360, 4000, true, 360);
        dock.addPanel("Properties", rightPanel, 280, 220, 720, false, 240);
        dock.addBottomPanel("Timeline", timelineArea, 280, 160, 900);
        dock.start(); // applies the layout remembered from last time (floating windows included)

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
            refreshTrackHeaders();
            refreshTimelineUI();
        }
    }

    @Override public Clip getSelectedClip() { return selectedClip; }
    @Override public Track getSelectedTrack() { return selectedTrack; }
    @Override public Clip getSelectedTransitionSourceClip() {
        // One rule keeps the transition selection honest without hooking every way of selecting a clip: a transition and a
        // clip are never selected together, and a transition whose clips were deleted or moved apart is gone.
        if (selectedTransitionSourceClip != null
                && (selectedClip != null || getTransitionNextClip(selectedTransitionSourceClip) == null)) {
            selectedTransitionSourceClip = null;
        }
        return selectedTransitionSourceClip;
    }

    @Override public void selectTransition(Clip clipA) {
        if (clipA == null || getTransitionNextClip(clipA) == null) return;
        selectedClips.clear();
        selectedClip = null;
        selectedTransitionSourceClip = clipA;
        refreshSelectionVisuals(); // also restyles the cubes
        updatePropertiesPane();
    }

    @Override public Clip getTransitionNextClip(Clip clipA) {
        if (clipA == null || clipA.trackIndex < 0 || clipA.trackIndex >= timeline.tracks.size()) return null;
        Track track = timeline.tracks.get(clipA.trackIndex);
        return track.clips.contains(clipA) ? findTransitionNext(track, clipA) : null;
    }

    /** The clip that touches the end of {@code clip} on its track (within SNAP_TOLERANCE) - the B of its transition - or null. */
    private Clip findTransitionNext(Track track, Clip clip) {
        Clip next = null;
        for (Clip c : track.clips) {
            if (c == clip) continue;
            float gap = c.startTime - (clip.startTime + clip.duration);
            if (gap >= -SNAP_TOLERANCE && gap <= SNAP_TOLERANCE) {
                if (next == null || c.startTime < next.startTime) next = c;
            }
        }
        return next;
    }
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
                    clockTick = true;
                    try {
                        if (isPlayingInReverse) {
                            updateCurrentTime(currentTime - deltaSec);
                        } else {
                            updateCurrentTime(currentTime + deltaSec);
                        }
                    } finally {
                        clockTick = false;
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
        if (sourcePreview != null && sourcePreview.isActive()) {   // the Source view is up: Space plays / pauses the file
            sourcePreview.togglePlay();
            return;
        }
        if (voiceover != null && voiceover.isRecording()) {   // Space / the play button ends the take
            voiceover.stopFromEditor();
            return;
        }
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
        if (sourcePreview != null && sourcePreview.isActive()) sourcePreview.showProject();   // the project plays: back to it
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
        if (recordingPlayback) {              // any pause, from anywhere, ends the voiceover take; it calls back here
            endTakeFromEditor();
            if (recordingPlayback) recordingPlayback = false;   // no take was running: do not stay stuck
            if (!isPlaying) return;           // the take's end already stopped the clock
        }
        isPlaying = false;
        playbackTimer.stop();
        lastTimerUpdate = 0;

        // The clock runs between frames; a paused playhead sits on the frame that was showing.
        if (currentTime < timeline.duration) currentTime = floorToFrame(currentTime);
        currentTimeLabel.setText(formatTimecode(currentTime));
        updatePlayheadPosition();

        // Notify the renderer that we have paused
        if (timelineRenderer != null) {
            timelineRenderer.updateTime(currentTime, true);
        }
    }

    @Override
    public void updateCurrentTime(float newTime) {
        if (recordingPlayback && !clockTick) endTakeFromEditor();   // scrubbing / seeking during a take ends it
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
        refreshKeyframeKnotHighlight();

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

            if (!recordingPlayback && ((currentTime >= timeline.duration) || (currentTime <= 0f && isPlayingInReverse))) {
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
        // While playing the clock is between frames: draw the line on the frame being shown, like the preview.
        float shownTime = isPlaying ? floorToFrame(currentTime) : currentTime;
        playheadLine.setTranslateX(shownTime * pixelsPerSecond - scrollX);

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
        int fps = rulerFps();
        // floor to the frame the time is in (the epsilon keeps 2.4999999 s at 30 fps in frame 15, not 14)
        int f = Math.min(fps - 1, (int) Math.floor((seconds - Math.floor(seconds)) * fps + 1e-4));
        return String.format("%02d:%02d:%02d:%02d", h, m, s, f);
    }

    /**
     * True when {@code node} is a text control, or sits inside one: the editable Spinner or ComboBox that owns a text field
     * (the key event may be targeted at the Spinner itself rather than at its inner editor, which a plain
     * "target instanceof TextInputControl" test misses - that is how Delete in the Duration box used to delete the selected clip).
     */
    private static boolean isTextEntry(javafx.scene.Node node) {
        for (javafx.scene.Node n = node; n != null; n = n.getParent()) {
            if (n instanceof javafx.scene.control.TextInputControl) return true;
            if (n instanceof javafx.scene.control.Spinner && ((javafx.scene.control.Spinner<?>) n).isEditable()) return true;
            if (n instanceof javafx.scene.control.ComboBox && ((javafx.scene.control.ComboBox<?>) n).isEditable()) return true;
        }
        return false;
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

    private ProcessingBadge processingBadge;
    private Runnable processingListener;
    private final java.util.concurrent.atomic.AtomicBoolean processingRefreshQueued = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Follows the processing tracker (a clip feature being pre-processed): the preview badge, the clip labels on the timeline and
     * the Motion tab's "Processing xx%" line are redrawn. Jobs report from worker threads and often, so the redraws are
     * coalesced into one FX-thread run at a time.
     */
    private void startProcessingWatch() {
        if (processingListener != null) return;
        processingListener = () -> {
            if (!processingRefreshQueued.compareAndSet(false, true)) return;
            Platform.runLater(() -> {
                processingRefreshQueued.set(false);
                refreshProcessingViews();
            });
        };
        com.vanvatcorporation.doubleclips.ClipProcessingTracker.get().addListener(processingListener);
    }

    private void refreshProcessingViews() {
        if (processingBadge != null) processingBadge.refresh();
        for (Clip clip : timeline.getAllClips()) {
            if (clip.viewRef instanceof ClipNode cn) cn.refreshLabels();
        }
        if (propertyPanel != null) propertyPanel.refreshProcessing();
    }

    private void closeWindow() {
        if (reverseManager != null) reverseManager.shutdown();
        if (processingListener != null) {
            com.vanvatcorporation.doubleclips.ClipProcessingTracker.get().removeListener(processingListener);
            processingListener = null;
        }
        if (voiceover != null) voiceover.shutdown();   // before the dock goes: a running take is dropped, not turned into a clip
        if (dock != null) dock.shutdown();
        stopPlayback();
        saveProject();
        if (sourcePreview != null) sourcePreview.shutdown();
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

        Label title = new Label(I18n.t("Preview Options"));
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        // ── Separator helper ──────────────────────────────────────────────
        javafx.scene.control.Separator sep1 = new javafx.scene.control.Separator();
        javafx.scene.control.Separator sep2 = new javafx.scene.control.Separator();



        // ── Playback FPS - Speed Correlation ──────────────────────────────────────────────
        float activeFps = previewFpsRuntime == -1f ? videoSettings.frameRate : previewFpsRuntime;


        // ── Preview FPS ───────────────────────────────────────────────────
        HBox fpsRow = new HBox(8);
        fpsRow.setAlignment(Pos.CENTER_LEFT);
        Label fpsLabel = new Label(I18n.t("Playback FPS:"));
        fpsLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        fpsLabel.setPrefWidth(130);
        javafx.scene.control.Spinner<Double> fpsSpinner = new javafx.scene.control.Spinner<>(
            new javafx.scene.control.SpinnerValueFactory.DoubleSpinnerValueFactory(-1, Double.MAX_VALUE, activeFps, 1)
        );
        fpsSpinner.setEditable(true);
        fpsSpinner.setPrefWidth(90);
        fpsSpinner.valueProperty().addListener((obs, o, n) -> previewFpsRuntime = n.floatValue());
        Label fpsHint = new Label(I18n.t("(-1 = original)"));
        fpsHint.setStyle("-fx-text-fill: -color-fg-subtle; -fx-font-size: 10px;");
        fpsRow.getChildren().addAll(fpsLabel, fpsSpinner);

        // ── Playback Speed ────────────────────────────────────────────────
        HBox speedRow = new HBox(8);
        speedRow.setAlignment(Pos.CENTER_LEFT);
        Label speedLabel = new Label(I18n.t("Playback speed:"));
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
        Label reverseLabel = new Label(I18n.t("Reverse playback:"));
        reverseLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        reverseLabel.setPrefWidth(160);
        CheckBox reverseCheck = new CheckBox();
        reverseCheck.setSelected(isPlayingInReverse);
        reverseCheck.selectedProperty().addListener((obs, o, n) -> isPlayingInReverse = n);
        reverseRow.getChildren().addAll(reverseLabel, reverseCheck);

        // ── Keep playing when clip selected ───────────────────────────────
        HBox keepRow = new HBox(8);
        keepRow.setAlignment(Pos.CENTER_LEFT);
        Label keepLabel = new Label(I18n.t("Keep playing on select:"));
        keepLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        keepLabel.setPrefWidth(160);
        CheckBox keepCheck = new CheckBox();
        keepCheck.setSelected(keepPlayingWhenClipSelected);
        keepCheck.selectedProperty().addListener((obs, o, n) -> keepPlayingWhenClipSelected = n);
        keepRow.getChildren().addAll(keepLabel, keepCheck);

        // ── Audio bar width ───────────────────────────────────────────────
        HBox audioWidthRow = new HBox(8);
        audioWidthRow.setAlignment(Pos.CENTER_LEFT);
        Label audioWidthLabel = new Label(I18n.t("Audio bar width:"));
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
        Label audioGapLabel = new Label(I18n.t("Audio bar gap:"));
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
        Label gpuLabel = new Label(I18n.t("GPU preview:"));
        gpuLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        gpuLabel.setPrefWidth(160);
        CheckBox gpuCheck = new CheckBox();
        gpuCheck.setSelected(appSettings.isGpuPreview() && timelineRenderer.isGpuPreviewActive());
        gpuCheck.setTooltip(new Tooltip(I18n.t("Composite the preview with the same OpenGL engine as the OpenGL export")));
        gpuRow.getChildren().addAll(gpuLabel, gpuCheck);

        HBox proxyRow = new HBox(8);
        proxyRow.setAlignment(Pos.CENTER_LEFT);
        Label proxyLabel = new Label(I18n.t("Use proxy clips:"));
        proxyLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        proxyLabel.setPrefWidth(160);
        CheckBox proxyCheck = new CheckBox();
        proxyCheck.setSelected(appSettings.isPreviewUseProxy());
        proxyCheck.setTooltip(new Tooltip(I18n.t("Off: preview plays the original clips. On: the lighter proxy copies made at import (GPU preview only)")));
        proxyCheck.disableProperty().bind(gpuCheck.selectedProperty().not());
        proxyCheck.selectedProperty().addListener((obs, o, n) -> {
            appSettings.setPreviewUseProxy(n);
            timelineRenderer.setUseProxy(n);
        });
        proxyRow.getChildren().addAll(proxyLabel, proxyCheck);

        HBox hwRow = new HBox(8);
        hwRow.setAlignment(Pos.CENTER_LEFT);
        Label hwLabel = new Label(I18n.t("Hardware decoding:"));
        hwLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        hwLabel.setPrefWidth(160);
        CheckBox hwCheck = new CheckBox();
        hwCheck.setSelected(appSettings.isPreviewHardwareDecode());
        hwCheck.setTooltip(new Tooltip(I18n.t("Experimental. Only used when a clip starts from its beginning; seeks always decode in software, because hardware decoding after a seek can corrupt frames")));
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

        Button exportBtn = new Button(I18n.t("Export"));
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

        Button importBtn = new Button(I18n.t("Import"));
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

        Label emptyLabel = new Label(I18n.t("No media yet.\nClick Import to add files."));
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

        Label dropLabel = new Label(I18n.t("Drop here to import media"));
        dropLabel.setStyle("-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold;");

        mediaDropOverlay.getChildren().addAll(dropIcon, dropLabel);
        leftStack.getChildren().add(mediaDropOverlay);

        // Filter / sort / more, for the Media tab only
        mediaToolbar = new HBox(6);
        mediaToolbar.setPadding(new Insets(0, 10, 6, 10));
        mediaToolbar.setAlignment(Pos.CENTER_LEFT);
        ComboBox<String> filterBox = new ComboBox<>();
        filterBox.getItems().addAll("All", "Video", "Image", "Audio", "Unused");
        filterBox.getSelectionModel().select(0);
        filterBox.setOnAction(e -> {
            mediaFilter = com.vanvatcorporation.doubleclips.ProjectLibrary.Filter.values()[Math.max(0, filterBox.getSelectionModel().getSelectedIndex())];
            renderMediaGrid();
        });
        ComboBox<String> sortBox = new ComboBox<>();
        sortBox.getItems().addAll("Newest", "Name", "Size");
        sortBox.getSelectionModel().select(0);
        sortBox.setOnAction(e -> {
            mediaSort = com.vanvatcorporation.doubleclips.ProjectLibrary.Sort.values()[Math.max(0, sortBox.getSelectionModel().getSelectedIndex())];
            renderMediaGrid();
        });
        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);
        MenuButton moreBtn = new MenuButton();
        moreBtn.setGraphic(new FontIcon(org.kordamp.ikonli.materialdesign2.MaterialDesignD.DOTS_HORIZONTAL));
        moreBtn.getStyleClass().add("button-transparent");
        MenuItem solidItem = new MenuItem(I18n.t("Solid colour..."));
        solidItem.setOnAction(e -> mediaActions.solidColor());
        MenuItem unusedItem = new MenuItem(I18n.t("Delete unused files..."));
        unusedItem.setOnAction(e -> mediaActions.deleteUnused(com.vanvatcorporation.doubleclips.ProjectLibrary.unused(
                libraryItems(), com.vanvatcorporation.doubleclips.ProjectLibrary.usage(timeline))));
        moreBtn.getItems().addAll(solidItem, unusedItem);
        mediaToolbar.getChildren().addAll(filterBox, sortBox, toolbarSpacer, moreBtn);

        toastLabel.setStyle("-fx-background-color: rgba(0,0,0,0.82); -fx-text-fill: white; -fx-padding: 6 12 6 12; -fx-background-radius: 14; -fx-font-size: 11px;");
        toastLabel.setMouseTransparent(true);
        toastLabel.setVisible(false);
        StackPane.setAlignment(toastLabel, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toastLabel, new Insets(0, 0, 14, 0));
        leftStack.getChildren().add(toastLabel);

        panel.getChildren().addAll(tabStrip, importBar, mediaToolbar, leftStack);

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

    private List<com.vanvatcorporation.doubleclips.ProjectLibrary.Item> libraryItems() {
        List<com.vanvatcorporation.doubleclips.ProjectLibrary.Item> items = new ArrayList<>();
        for (MediaEntry entry : mediaEntries) items.add(entry.item);
        return items;
    }

    /** Reads the project's files from disk (their length and size are probed once and remembered), then shows them. */
    private void loadMediaGrid(FlowPane mediaGrid) {
        String projectPath = project.getProjectPath();
        Task<List<MediaEntry>> task = new Task<>() {
            @Override
            protected List<MediaEntry> call() {
                List<MediaEntry> loaded = new ArrayList<>();
                for (com.vanvatcorporation.doubleclips.ProjectLibrary.Item item : com.vanvatcorporation.doubleclips.ProjectLibrary.scan(projectPath)) {
                    String key = item.name + "|" + item.bytes + "|" + item.modified;
                    MediaHelper.MediaInfo info = mediaInfoCache.computeIfAbsent(key,
                            k -> MediaHelper.probeMediaInfo(item.file.getAbsolutePath()));
                    if (item.kind != com.vanvatcorporation.doubleclips.ProjectLibrary.Kind.IMAGE) item.duration = (double) info.duration;
                    loaded.add(new MediaEntry(item, info));
                }
                return loaded;
            }
        };
        task.setOnSucceeded(e -> {
            mediaEntries.clear();
            mediaEntries.addAll(task.getValue());
            // The user may have switched tab while this was reading; the grid then belongs to another tab.
            if (mediaTab != null && mediaTab.isSelected()) renderMediaGrid();
        });
        Thread t = new Thread(task, "media-scan");
        t.setDaemon(true);
        t.start();
    }

    /** Draws the Media tab from {@link #mediaEntries} with the current filter and sort. */
    private void renderMediaGrid() {
        if (mediaGrid == null || mediaTab == null || !mediaTab.isSelected()) return;
        mediaGrid.getChildren().clear();
        usageBadges.clear();

        java.util.Map<String, Integer> usage = com.vanvatcorporation.doubleclips.ProjectLibrary.usage(timeline);
        List<com.vanvatcorporation.doubleclips.ProjectLibrary.Item> shown = com.vanvatcorporation.doubleclips.ProjectLibrary.visible(
                libraryItems(), mediaFilter, mediaSort, usage);
        if (shown.isEmpty()) {
            Label empty = new Label(mediaEntries.isEmpty() ? "No media yet.\nClick Import to add files." : "No files match this filter.");
            empty.setStyle("-fx-text-fill: -color-fg-muted; -fx-text-alignment: center;");
            empty.setAlignment(Pos.CENTER);
            mediaGrid.getChildren().add(empty);
            return;
        }
        java.util.Map<String, MediaEntry> byName = new java.util.HashMap<>();
        for (MediaEntry entry : mediaEntries) byName.put(entry.item.name, entry);
        for (com.vanvatcorporation.doubleclips.ProjectLibrary.Item item : shown) {
            MediaHelper.MediaInfo info = byName.get(item.name).info;
            Clip clip = new Clip(item.name, 0, info.duration, 0, item.kind.clipType(), info.hasAudio, info.width, info.height);
            addClipToMediaGrid(mediaGrid, clip, item, usage.getOrDefault(item.name, 0));
        }
    }

    private static void setUsageBadge(Label badge, int used) {
        badge.setText(used > 0 ? "\u00D7" + used : "");
        badge.setVisible(used > 0);
        badge.setStyle("-fx-background-color: #2E6BD8; -fx-text-fill: white; -fx-font-size: 9px; -fx-background-radius: 8; -fx-padding: 0 4 0 4;");
    }

    /** Keeps the \u00D7N badges right as clips come and go. Cheap, and run once however many refreshes pile up. */
    private void scheduleUsageBadgeRefresh() {
        if (usageRefreshQueued) return;
        usageRefreshQueued = true;
        Platform.runLater(() -> {
            usageRefreshQueued = false;
            java.util.Map<String, Integer> usage = com.vanvatcorporation.doubleclips.ProjectLibrary.usage(timeline);
            usageBadges.forEach((name, badge) -> setUsageBadge(badge, usage.getOrDefault(name, 0)));
        });
    }

    private static String mediaCaption(com.vanvatcorporation.doubleclips.ProjectLibrary.Item item) {
        String size = com.vanvatcorporation.doubleclips.ui.MediaLibraryActions.readableSize(item.bytes);
        if (item.duration == null || item.duration <= 0) return size;
        int total = (int) Math.round(item.duration);
        return String.format("%d:%02d \u00B7 %s", total / 60, total % 60, size);
    }

    private void attachMediaMenu(VBox box, com.vanvatcorporation.doubleclips.ProjectLibrary.Item item) {
        ContextMenu menu = new ContextMenu();
        MenuItem add = new MenuItem(I18n.t("Add to timeline"));
        add.setOnAction(e -> mediaActions.addToTimeline(item));
        MenuItem rename = new MenuItem(I18n.t("Rename..."));
        rename.setOnAction(e -> mediaActions.rename(item));
        MenuItem replace = new MenuItem(I18n.t("Replace file..."));
        replace.setOnAction(e -> mediaActions.replace(item));
        MenuItem delete = new MenuItem(I18n.t("Delete"));
        delete.setOnAction(e -> mediaActions.delete(item));
        menu.getItems().addAll(add, new SeparatorMenuItem(), rename, replace, new SeparatorMenuItem(), delete);
        box.setOnContextMenuRequested(e -> {
            menu.show(box, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        box.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.getClickCount() == 2) mediaActions.addToTimeline(item);
        });
    }

    // ---- what the Media tab's actions need from the editor -----------------------------------------------------------

    private final MediaLibraryActions mediaActions = new MediaLibraryActions(new MediaLibraryActions.Host() {
        @Override public javafx.stage.Window window() { return EditorWindow.this; }
        @Override public String projectPath() { return project.getProjectPath(); }
        @Override public Timeline timeline() { return timeline; }
        @Override public float playhead() { return currentTime; }
        @Override public Track selectedTrack() { return selectedTrack; }

        @Override
        public void addClip(Clip clip, int trackIndex) {
            historyManager.execute(new AddClipCommand(timeline, clip, trackIndex, () -> {
                refreshTrackHeaders(); // the add may have made a track
                refreshTimelineUI();
                saveProject();
            }));
            selectClip(clip);
            updateCurrentTime(clip.startTime + clip.duration); // ready for the next one
        }

        @Override public void timelineChanged() { refreshTimelineUI(); saveProject(); }
        @Override public void reloadLibrary() { if (mediaTab != null && mediaTab.isSelected()) loadMediaGrid(mediaGrid); }
        @Override public void clearHistory() { historyManager.clear(); }

        @Override
        public void rebuildProxies(File file, ClipType type, MediaHelper.MediaInfo info, Runnable done) {
            runWithProgress(I18n.t("Processing Media"), () -> generateProxies(file, file.getName(), type, info, (d, t) -> { }), done);
        }

        @Override public void toast(String text) { showToast(text); }
    });

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Runs {@code work} off the FX thread behind a small modal "processing" dialog, then {@code onDone} on it. */
    private void runWithProgress(String title, ThrowingRunnable work, Runnable onDone) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.initOwner(this);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setVisible(false);
        VBox content = new VBox(10, new Label(I18n.t("Processing...")), new ProgressIndicator());
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(20));
        dialog.getDialogPane().setContent(content);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                work.run();
                return null;
            }
        };
        task.setOnSucceeded(e -> dialog.setResult(ButtonType.OK));
        task.setOnFailed(e -> dialog.setResult(ButtonType.CANCEL));
        Thread thread = new Thread(task, "media-work");
        thread.setDaemon(true);
        thread.start();
        dialog.showAndWait();
        if (onDone != null) onDone.run();
    }

    /** A short message that fades by itself, at the bottom of the media panel. */
    private void showToast(String text) {
        toastLabel.setText(text);
        toastLabel.setOpacity(1);
        toastLabel.setVisible(true);
        if (toastAnimation != null) toastAnimation.stop();
        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
        javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(400), toastLabel);
        fade.setFromValue(1);
        fade.setToValue(0);
        toastAnimation = new javafx.animation.SequentialTransition(pause, fade);
        toastAnimation.setOnFinished(e -> toastLabel.setVisible(false));
        toastAnimation.play();
    }

    private void handleImportMedia(FlowPane mediaGrid) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("Import Media"));
        List<File> files = chooser.showOpenMultipleDialog(this);
        if (files == null || files.isEmpty())
            return;
        handleImportFiles(files, mediaGrid, -1f, -1);
    }

    private void handleImportFiles(List<File> files, FlowPane mediaGrid, float startTime, int trackIdx) {
        Dialog<ButtonType> progressDialog = new Dialog<>();
        progressDialog.setTitle(I18n.t("Processing Media"));
        progressDialog.initOwner(this);
        progressDialog.initModality(Modality.WINDOW_MODAL);
        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        progressDialog.getDialogPane().lookupButton(ButtonType.CANCEL).setVisible(false);

        VBox content = new VBox(10);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(20));
        Label desc = new Label(I18n.t("Processing..."));
        ProgressIndicator progress = new ProgressIndicator();
        content.getChildren().addAll(desc, progress);
        progressDialog.getDialogPane().setContent(content);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                for (int i = 0; i < files.size(); i++) {
                    File f = files.get(i);
                    updateMessage("Processing: " + f.getName());

                    String clipDir = IOHelper.CombinePath(project.getProjectPath(), Constants.DEFAULT_CLIP_DIRECTORY);
                    File clipDirFile = new File(clipDir);
                    if (!clipDirFile.exists())
                        clipDirFile.mkdirs();

                    // Never overwrite a different file that has this name: the clips using the old one would silently
                    // change. The same file imported again is reused; another one becomes "name (1).ext".
                    String safeName = com.vanvatcorporation.doubleclips.ProjectLibrary.safeFileName(f.getName());   // what copyIn will call it
                    boolean existedBefore = new File(clipDir, safeName).exists();
                    String filename = ProjectLibrary.copyIn(f, project.getProjectPath());
                    File targetFile = new File(clipDir, filename);
                    boolean reused = existedBefore && filename.equals(safeName);

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
                    ProjectLibrary.Kind kind = ProjectLibrary.Kind.ofName(filename);
                    if (kind != ProjectLibrary.Kind.OTHER) type = kind.clipType(); // the extension is more reliable than the OS's guess

                    Clip clip = new Clip(filename, 0, info.duration, 0, type, info.hasAudio, info.width, info.height);

                    // A file that was already here keeps the proxies it has; anything else gets them made.
                    if (!reused || ProjectLibrary.previewFiles(filename, project.getProjectPath()).isEmpty()) {
                        generateProxies(targetFile, filename, type, info, (done, total) -> updateProgress(done, total));
                    }

                    Platform.runLater(() -> {
                        loadMediaGrid(mediaGrid); // redraw from disk: the tile gets its badges and menu, and a renamed duplicate shows up
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

    /**
     * Makes the preview proxies of a media file in PreviewClips: for a video the thumbnail, the 720p editing proxy and
     * the audio proxy, for an audio file the audio proxy. Blocks until ffmpeg is done, so call it off the FX thread.
     * {@code progress} gets (seconds done, seconds total) while the video proxy is made.
     */
    private void generateProxies(File targetFile, String filename, ClipType type, MediaHelper.MediaInfo info,
                                 java.util.function.BiConsumer<Double, Double> progress) throws InterruptedException {
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
                                        progress.accept(stats.getTimeInMs() / 1000.0, (double) info.duration);
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
    }

    private void addClipToMediaGrid(FlowPane mediaGrid, Clip clip) {
        addClipToMediaGrid(mediaGrid, clip, null, 0);
    }

    /** One tile. With a library {@code item} (the Media tab) it also shows length / size, how many clips use the file, and a right-click menu. */
    private void addClipToMediaGrid(FlowPane mediaGrid, Clip clip, com.vanvatcorporation.doubleclips.ProjectLibrary.Item item, int usedBy) {
        if (!mediaGrid.getChildren().isEmpty() && mediaGrid.getChildren().get(0) instanceof Label) {
            mediaGrid.getChildren().clear();
        }

        VBox box = new VBox(4);
        box.setAlignment(Pos.CENTER);
        box.setPrefWidth(80);
        box.setPrefHeight(80);
        box.getStyleClass().add("media-grid-item");
        box.getProperties().put("mediaClipName", clip.getClipName());
        markMediaTile(box, mediaRanges.containsKey(clip.getClipName()));   // an amber border when the file has a pre-trim range

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

        if (item == null) {
            box.getChildren().addAll(graphicNode, nameLbl);
        } else {
            StackPane thumb = new StackPane(graphicNode);
            Label badge = new Label();
            setUsageBadge(badge, usedBy);
            StackPane.setAlignment(badge, Pos.TOP_RIGHT);
            thumb.getChildren().add(badge);
            usageBadges.put(item.name, badge);
            Label details = new Label(mediaCaption(item));
            details.setStyle("-fx-text-fill: #999; -fx-font-size: 9px;");
            box.setPrefHeight(96);
            box.getChildren().addAll(thumb, nameLbl, details);
            attachMediaMenu(box, item);
        }
        mediaGrid.getChildren().add(box);

        attachNewClipDrag(box, () -> mediaDragClip(clip), true, () -> {
            // A click (no drag): show the file in the preview's Source view. A drag adds the file (with its pre-trim range, if any).
            if (clip.type == ClipType.VIDEO || clip.type == ClipType.AUDIO || clip.type == ClipType.IMAGE) openInSource(clip);
        });
    }

    /**
     * Makes {@code node} a source of NEW clips: pressing it and dragging over the timeline shows a ghost, dropping adds the clip
     * {@code source} gives (a Media tab file; the Source view's chosen part). A click without a drag runs {@code onClick} (may be null).
     * {@code grabOffset}: the ghost keeps the grab point inside the node (a tile) instead of starting at the pointer.
     */
    private void attachNewClipDrag(javafx.scene.Node node, java.util.function.Supplier<Clip> source, boolean grabOffset, Runnable onClick) {
        node.setOnMousePressed(e -> {
            Clip copyClip = source.get();
            if (copyClip == null) return;
            copyClip.trackIndex = 0;

            activeDrag.clip = copyClip;
            activeDrag.currentTrackIdx = 0;
            activeDrag.dragOffsetX = grabOffset ? e.getX() : 0;
            activeDrag.dragging = false;
            activeDrag.ghost = null;
            activeDrag.isNewClip = true;
            e.consume();
        });

        node.setOnMouseDragged(e -> {
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

        node.setOnMouseReleased(e -> {
            edgeScrollTimer.stop();
            if (activeDrag.clip == null || !activeDrag.isNewClip)
                return;

            // A click (no drag), e.g. on a Media tab file: its own action. A drag adds the clip to the timeline.
            if (!activeDrag.dragging && onClick != null && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                onClick.run();
            }

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

    // ---- Pre-trim (Source view) ----------------------------------------------------------------------------------

    /** The in / out range ({in, out} seconds of the file) chosen in the Source view for a Media tab file; kept while the editor is open. */
    private final java.util.Map<String, float[]> mediaRanges = new java.util.HashMap<>();
    private static final String MEDIA_TILE_STYLE = "-fx-border-color: %s; -fx-border-radius: 4px; -fx-background-color: #222; -fx-padding: 4px;";

    private void markMediaTile(javafx.scene.Node tile, boolean trimmed) {
        tile.setStyle(String.format(MEDIA_TILE_STYLE, trimmed ? "#FFB020" : "#555"));
    }

    private void rememberMediaRange(String clipName, float in, float out, boolean whole) {
        if (whole) mediaRanges.remove(clipName);
        else mediaRanges.put(clipName, new float[]{in, out});
        if (mediaGrid == null) return;
        for (javafx.scene.Node n : mediaGrid.getChildren()) {
            if (clipName.equals(n.getProperties().get("mediaClipName"))) markMediaTile(n, !whole);
        }
    }

    /** A new clip for a Media tab file being dragged out: the whole file, or the range chosen for it in the Source view. */
    private Clip mediaDragClip(Clip tileClip) {
        Clip c = new Clip(tileClip);
        c.trackIndex = 0;
        float[] r = mediaRanges.get(c.getClipName());
        if (r != null) SourcePreview.applyRange(c, r[0], r[1]);
        return c;
    }

    /** True when {@code clip} suits {@code track}: sound goes on tracks that hold only sound, pictures on tracks without any. */
    private static boolean trackFits(Track track, Clip clip) {
        boolean hasAudio = false, hasOther = false;
        for (Clip x : track.clips) {
            if (x.type == ClipType.AUDIO) hasAudio = true; else hasOther = true;
        }
        return clip.type == ClipType.AUDIO ? !hasOther : !hasAudio;
    }

    /** The track "Add to Timeline" uses: the selected track (or the selected clip's) when it suits the clip, else the first that does, else a new one. */
    private int sourceTargetTrack(Clip clip) {
        if (selectedTrack != null && timeline.tracks.contains(selectedTrack) && trackFits(selectedTrack, clip)) {
            return timeline.tracks.indexOf(selectedTrack);
        }
        for (int i = 0; i < timeline.tracks.size(); i++) {
            if (trackFits(timeline.tracks.get(i), clip)) return i;
        }
        return timeline.tracks.size();
    }

    /** Source view "Add to Timeline": the clip goes on the timeline at the playhead, and the playhead moves to its end so the next add follows. */
    private void addSourceClipToTimeline(Clip clip, PlaceClipCommand.Mode mode) {
        int trackIdx = sourceTargetTrack(clip);
        clip.startTime = currentTime;
        clip.trackIndex = trackIdx;
        historyManager.execute(new PlaceClipCommand(timeline, clip, trackIdx, mode, () -> {
            updateCurrentClipEnd();
            refreshTimelineUI();
            saveProject();
        }));
        updateCurrentTime(clip.startTime + clip.duration);
        Platform.runLater(this::revealPlayhead);
    }

    /** Scrolls the timeline so the playhead is in view (it is not moved by playing here, so nothing else would). */
    private void revealPlayhead() {
        double playheadX = currentTime * pixelsPerSecond;
        double contentWidth = tracksScrollPane.getContent().getBoundsInLocal().getWidth();
        double viewportWidth = tracksScrollPane.getViewportBounds().getWidth();
        double room = Math.max(1, contentWidth - viewportWidth);
        double scrollX = tracksScrollPane.getHvalue() * room;
        if (playheadX < scrollX || playheadX > scrollX + viewportWidth) {
            tracksScrollPane.setHvalue(Math.max(0, Math.min(1, (playheadX - viewportWidth * 0.5) / room)));
        }
    }

    /** Click on a Media tab file: the project pauses and the preview shows that file alone (Source view). */
    private void openInSource(Clip mediaClip) {
        if (sourcePreview == null) return;
        if (isPlaying) {
            stopPlayback();
            playBtn.setGraphic(playIcon);
        }
        sourcePreview.open(mediaClip);
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
        // The canvas is independent of the editing UI around it: it never asks for room (min 0) and nothing the
        // picture or the gizmo draws may spill over the panels.
        canvas.setMinSize(0, 0);
        javafx.scene.shape.Rectangle canvasClip = new javafx.scene.shape.Rectangle();
        canvasClip.widthProperty().bind(canvas.widthProperty());
        canvasClip.heightProperty().bind(canvas.heightProperty());
        canvas.setClip(canvasClip);

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
            @Override public MaskEditState maskEdit() { return maskEditState; }
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
        // Source view: a file clicked in the Media tab, shown over the project's picture (and gizmo) with its own controls.
        sourcePreview = new SourcePreview(project, videoSettings, new SourcePreview.Host() {
            @Override public int fps() { return rulerFps(); }
            @Override public void modeChanged(boolean source) { if (sourceControlsSwap != null) sourceControlsSwap.accept(source); }
            @Override public void addToTimeline(Clip clip, PlaceClipCommand.Mode mode) { addSourceClipToTimeline(clip, mode); }
            @Override public float[] savedRange(String clipName) { return mediaRanges.get(clipName); }
            @Override public void rangeChanged(String clipName, float in, float out, boolean whole) { rememberMediaRange(clipName, in, out, whole); }
        });
        // Dragging the source picture onto the timeline adds the chosen part (in / out), like dragging a Media tab file
        attachNewClipDrag(sourcePreview.overlay(), sourcePreview::chosenClip, false, null);
        // "Applying N feature..." while a clip feature is being pre-processed (see ClipProcessingTracker)
        processingBadge = new ProcessingBadge();
        StackPane.setAlignment(processingBadge, Pos.TOP_LEFT);
        StackPane.setMargin(processingBadge, new Insets(10));
        canvas.getChildren().add(processingBadge);
        canvas.getChildren().add(sourcePreview.overlay());   // on top of everything: the Source view covers the project picture
        startProcessingWatch();
        maskEditState.setOnChange(() -> { if (previewGizmo != null) previewGizmo.refresh(); });

        // Fits the picture into the canvas; runs when the canvas is resized AND when the project's canvas size changes.
        relayoutPreview = () -> {
            javafx.geometry.Bounds newBounds = canvas.getLayoutBounds();
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
        };
        canvas.layoutBoundsProperty().addListener((obs, oldBounds, newBounds) -> relayoutPreview.run());

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
        // They show the project's real settings and open them: a change takes effect here at once (see onVideoSettingsChanged).
        fpsBadge = buildStatBadge(String.valueOf(videoSettings.frameRate));
        resBadge = buildStatBadge(resolutionBadgeText());
        Label hdrLabel = buildStatBadge(I18n.t("SDR"));
        for (Label badge : new Label[]{fpsBadge, resBadge, hdrLabel}) {
            badge.setCursor(javafx.scene.Cursor.HAND);
            badge.setTooltip(new javafx.scene.control.Tooltip(I18n.t("Video settings")));
            badge.setOnMouseClicked(e -> openVideoSettings());
        }
        Label fpsLabel = fpsBadge;
        Label resLabel = resBadge;

        Button fullScreenBtn = new Button();
        fullScreenBtn.setGraphic(new FontIcon(MaterialDesignF.FULLSCREEN));
        fullScreenBtn.getStyleClass().add("button-transparent");

        controls.getChildren().addAll(
                currentTimeLabel, timeSep, durationLabel,
                pbLeft, playBtn, pbRight,
                fpsLabel, resLabel, hdrLabel, fullScreenBtn);

        // The project's playback row gives way to the source's own while the Source view is up.
        sourceControlsSwap = on -> {
            controls.setVisible(!on);
            controls.setManaged(!on);
        };
        panel.getChildren().addAll(canvas, controls, sourcePreview.controls(), sourcePreview.tabs());
        return panel;
    }

    private java.util.function.Consumer<Boolean> sourceControlsSwap;
    private Label fpsBadge, resBadge;
    /** Re-fits the preview picture into its canvas (set when the canvas is built). */
    private Runnable relayoutPreview;

    /** "1080p": the shorter side of the canvas, so a vertical 1080 x 1920 also reads 1080p. */
    private String resolutionBadgeText() {
        return Math.min(videoSettings.videoWidth, videoSettings.videoHeight) + "p";
    }

    private void openVideoSettings() {
        if (isPlaying) triggerPlayAction(); // a canvas that changes under a playing preview helps nobody
        VideoSettingsDialog.Change change = VideoSettingsDialog.show(this, videoSettings);
        if (change != null) onVideoSettingsChanged(change);
    }

    /**
     * The project's video settings were changed (from the badges under the player, or from the export window): what each
     * change touches is rebuilt now, so there is no need to leave the editor and come back.
     *  - canvas size: the preview picture is resized and a new preview worker started at that size, the picture is re-fitted
     *    into the canvas and the on-canvas gizmo follows;
     *  - frame rate: the keyframes snap to the new frame grid (as when a project is opened), the clips follow in
     *    refreshTimelineUI, and the ruler and the timecodes count in the new frames.
     */
    @Override
    public void changeProjectFrameRate(int fps) {
        if (fps < 1 || fps == videoSettings.frameRate) return;
        videoSettings.frameRate = fps;
        onVideoSettingsChanged(new VideoSettingsDialog.Change(false, true, false));
    }

    public void onVideoSettingsChanged(VideoSettingsDialog.Change change) {
        ProjectRepository.getInstance().saveVideoSettings(project, videoSettings);
        fpsBadge.setText(String.valueOf(videoSettings.frameRate));
        resBadge.setText(resolutionBadgeText());
        if (change.frameRate) {
            for (Clip clip : timeline.getAllClips()) clip.keyframes.reassignKeyframes(videoSettings.frameRate);
        }
        if (change.size || change.frameRate) {
            timelineRenderer.applyVideoSettings();
        }
        if (change.size && relayoutPreview != null) relayoutPreview.run();
        if (change.size && sourcePreview != null) sourcePreview.applyVideoSettings();
        if (change.frameRate && sourcePreview != null) sourcePreview.frameRateChanged();
        refreshTimelineUI(); // the ruler, the clips on the new frame grid, the duration
        currentTimeLabel.setText(formatTimecode(currentTime));
        durationLabel.setText(formatTimecode(timeline.duration));
        if (previewGizmo != null) previewGizmo.refresh();
        saveProject();
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

        // Smart suggestions card
        VBox suggestionsCard = new VBox(10);
        suggestionsCard.setPadding(new Insets(16));
        suggestionsCard.getStyleClass().add("suggestions-card");

        Label suggTitle = new Label(I18n.t("Smart suggestions"));
        suggTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Label suggSub = new Label(I18n.t("Find out how your video\ncan be improved"));
        suggSub.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        Button analyzeBtn = new Button(I18n.t("+ Analyze"));
        analyzeBtn.getStyleClass().add("analyze-button");

        suggestionsCard.getChildren().addAll(suggTitle, suggSub, analyzeBtn);

        // Global edits section
        VBox globalEdits = new VBox(4);
        globalEdits.setPadding(new Insets(16));
        Label geTitle = new Label(I18n.t("Global edits"));
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

        panel.getChildren().addAll(propertyPanel.getTabStrip(), rightScroll);

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
        Label zoomLabel = new Label(I18n.t("Zoom"));
        zoomLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
        zoomSlider = new Slider(1, 2000, 100); // 1px to 1000px per second
        zoomSlider.setPrefWidth(100);
        zoomSlider.getStyleClass().add("timeline-zoom-slider");
        zoomSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressZoomSliderListener) return; // our own sync after a cursor zoom
            requestZoom(newVal.doubleValue());
        });

        Button keyframeBtn = buildToolBtn(MaterialDesignD.DIAMOND);
        keyframeBtn.setTooltip(new Tooltip(I18n.t("Add Keyframe at Playhead")));
        keyframeBtn.setOnAction(e -> handleAddKeyframe());

        Button clearKfBtn = buildToolBtn(MaterialDesignD.DIAMOND_OUTLINE);
        clearKfBtn.setTooltip(new Tooltip(I18n.t("Clear All Keyframes")));
        clearKfBtn.setOnAction(e -> handleClearKeyframes());

        Button textTool = buildToolBtn(MaterialDesignF.FORMAT_TEXT);
        textTool.setTooltip(new Tooltip(I18n.t("Add Text Clip")));
        textTool.setOnAction(e -> handleAddText());

        Button micBtn = buildToolBtn(MaterialDesignM.MICROPHONE);
        micBtn.setTooltip(new Tooltip(I18n.t("Voiceover")));
        micBtn.setOnAction(e -> openVoiceover());

        // Preview options button (left of zoom slider)
        Button previewOptionsBtn = new Button();
        previewOptionsBtn.setGraphic(new FontIcon(MaterialDesignT.TUNE));
        previewOptionsBtn.getStyleClass().add("button-transparent");
        previewOptionsBtn.setTooltip(new Tooltip(I18n.t("Preview Options")));
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
                toolSpacer, micBtn, previewOptionsBtn, zoomLabel, zoomSlider);

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
        sidebarTop.setAlignment(Pos.CENTER_LEFT);
        sidebarTop.setPadding(new Insets(0, 6, 0, 6));
        sidebarTop.getStyleClass().add("sidebar-top-row");
        reorderTracksButton = new Button();
        reorderTracksButton.getStyleClass().add("button-transparent");
        reorderTracksButton.setStyle("-fx-padding: 2px;");
        reorderTracksButton.setOnAction(e -> toggleTrackReorder());
        updateReorderTracksButton();
        Region sidebarSpacer = new Region();
        HBox.setHgrow(sidebarSpacer, Priority.ALWAYS);
        Button addTrackBtn = new Button();
        addTrackBtn.setGraphic(new FontIcon(MaterialDesignP.PLUS));
        addTrackBtn.getStyleClass().add("button-transparent");
        addTrackBtn.setStyle("-fx-padding: 2px;");
        addTrackBtn.setOnAction(e -> addNewTrack("New Track"));
        sidebarTop.getChildren().addAll(reorderTracksButton, sidebarSpacer, addTrackBtn);

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
        // Right-click on an EMPTY part of a track row: select that track and open its menu.
        tracksPane.setOnContextMenuRequested(e -> {
            if (e.getTarget() != tracksPane) return;
            javafx.geometry.Point2D local = tracksPane.sceneToLocal(e.getSceneX(), e.getSceneY());
            int row = (int) (local.getY() / (TRACK_HEIGHT + TRACK_SPACING));
            if (row < 0 || row >= this.timeline.tracks.size()) return;
            Track track = this.timeline.tracks.get(row);
            selectTrack(track);
            trackActions.menuFor(track).show(tracksPane, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        tracksPane.setOnMouseClicked(e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;   // a right-click opens the track menu, it must not deselect
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

                tempTime = snapToFrame((float) (clampedX / pixelsPerSecond));
                updatePlayheadPosition();

                if (activeDrag.ghost == null) {
                    Clip ghostClip = new Clip("Importing...", 0, defaultClipDuration(), 0, ClipType.VIDEO, false, 1280, 720);
                    ClipNode ghost = new ClipNode(ghostClip, this);
                    ghost.getStyleClass().add("clip-node-ghost");
                    ghost.setOpacity(0.4);
                    double targetWidth = defaultClipDuration() * pixelsPerSecond;
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
                float dropTime = snapToFrame((float) (Math.max(0, local.getX()) / pixelsPerSecond));
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
                return "V|" + node.computeTileCount() + "|" + clip.startClipTrim + "|" + clip.duration + "|" + clip.speedSignature();
            case AUDIO:
                // The waveform bitmap is drawn for a specific scale.
                return "A|" + pixelsPerSecond + "|" + clip.startClipTrim + "|" + clip.duration + "|" + clip.speedSignature()
                        + "|" + com.vanvatcorporation.doubleclips.data.editing.VolumeCurve.signature(clip);
            case IMAGE:
                return "I|" + clip.getClipName();
            default:
                return "O|" + clip.type;
        }
    }

    /** Generates thumbnails for a clip only if it is near the viewport and what it shows has changed. */
    /**
     * A video clip's own audio as a thin band along the bottom of its tiles (the iOS app does the same). Generated
     * like the audio clips' waveform: only near the viewport, only when what it shows changed, off the FX thread.
     */
    /** The widest stretch of a clip that gets a waveform bitmap (one bar per pixel column). */
    private static final double WAVE_WINDOW_PX = 4096.0;

    private void ensureWaveBand(ClipNode node) {
        Clip clip = node.getContainerClip();
        if (clip.type != ClipType.VIDEO || !clip.isClipHasAudio()) return;
        final float scale = pixelsPerSecond;

        // A long clip at a high zoom is far wider than a bitmap can be, so only the stretch around the viewport is drawn (at one bar
        // per pixel, where the whole clip squeezed into 4096 columns and stretched back smeared each bar over dozens of pixels). The
        // stretch moves in quarter-window steps, so scrolling redraws it only now and then.
        final double clipPx = clip.duration * scale;
        final double windowPx = Math.min(clipPx, WAVE_WINDOW_PX);
        double startPx = 0;
        if (clipPx > WAVE_WINDOW_PX) {
            double centerPx = currentScrollPx() + tracksScrollPane.getViewportBounds().getWidth() / 2.0 - clip.startTime * scale;
            double step = WAVE_WINDOW_PX / 4.0;
            startPx = Math.floor((centerPx - WAVE_WINDOW_PX / 2.0) / step) * step;
            startPx = Math.max(0, Math.min(clipPx - windowPx, startPx));
        }
        final double x = startPx, w = windowPx;
        final double fromSeconds = x / scale, toSeconds = (x + w) / scale;

        final String sig = "W|" + scale + "|" + clip.startClipTrim + "|" + clip.duration + "|" + clip.speedSignature()
                + "|" + com.vanvatcorporation.doubleclips.data.editing.VolumeCurve.signature(clip)
                + "|" + Math.round(x) + "|" + Math.round(w);
        if (sig.equals(node.getWaveSignature())) return;
        node.setWaveSignature(sig);
        thumbnailExecutor.submit(() -> {
            if (node.getParent() == null || !sig.equals(node.getWaveSignature())) return;     // removed / re-zoomed meanwhile
            Image band = AudioUtils.generateVideoWaveformBand(clip.getAbsolutePreviewPath(project, ".wav"), clip, scale, 20,
                    fromSeconds, toSeconds);
            javafx.application.Platform.runLater(() -> {
                if (!sig.equals(node.getWaveSignature())) return;
                if (band != null) node.setWaveformBand(band, x, w);
                else node.setWaveSignature(null);          // no preview audio yet: a later refresh tries again
            });
        });
    }

    private void ensureThumbnails(ClipNode node) {
        if (!isNodeNearViewport(node.getContainerClip())) return; // picked up when scrolled/zoomed near
        ensureWaveBand(node);
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

    /** The project's frame rate, as the ruler and the timecodes count in it. */
    private int rulerFps() {
        return Math.max(1, videoSettings.frameRate);
    }

    /** The time of the NEAREST frame (what a click or hover on the ruler lands on). */
    private float snapToFrame(float seconds) {
        return FrameHelper.calculateToNearestFrame(Math.max(0f, seconds), rulerFps());
    }

    /** The time at the start of the frame {@code seconds} is inside (what playback is showing at that moment). */
    private float floorToFrame(float seconds) {
        int fps = rulerFps();
        double frame = Math.floor(Math.max(0f, seconds) * (double) fps + 1e-4);
        return (float) (frame / fps);
    }

    private float getRulerInterval(float pixelsPerSecond) {
        int fps = rulerFps();
        // Preferred intervals in seconds: 1, 2, 5, 10, 15 frames (the ones shorter than a second), then whole seconds, doubling
        java.util.List<Float> intervals = new java.util.ArrayList<>();
        for (int frames : new int[]{1, 2, 5, 10, 15}) {
            if (frames < fps) intervals.add(frames / (float) fps);
        }
        for (float seconds = 1f; seconds <= 1024f; seconds *= 2f) intervals.add(seconds);

        // Aim for at least 80 pixels between major ticks for readability
        float minSpacing = 80f;
        for (float interval : intervals) {
            if (interval * pixelsPerSecond >= minSpacing) {
                return interval;
            }
        }
        return intervals.get(intervals.size() - 1);
    }

    /**
     * The label of a tick at a frame of the timeline: the whole seconds as "0s", "1s", "1m5s", every other frame as its
     * number inside its second ("1f" ... "29f" at 30 fps), so the frames count 0, 1, 2, 3 ... up to the next second.
     */
    private String rulerLabel(long frame, int fps) {
        long sec = frame / fps;
        int f = (int) (frame % fps);
        if (f == 0) {
            long m = sec / 60;
            long s = sec % 60;
            if (m > 0) return s == 0 ? m + "m" : m + "m" + s + "s";
            return s + "s";
        }
        return f + "f";
    }

    /**
     * One tick of the ruler. The start of a second is drawn to stand out (taller, thicker, bold label, always labelled);
     * frames in between are plain, and minor ones fainter.
     */
    private void addRulerMark(Pane ruler, double x, long frame, int fps, boolean major, boolean labelled) {
        boolean second = frame % fps == 0;
        Line tick = new Line(x, second ? 2 : (major ? 10 : 18), x, 30);
        tick.getStyleClass().add(major || second ? "ruler-tick-major" : "ruler-tick-minor");
        if (second) tick.setStyle("-fx-stroke-width: 2;");
        else if (!major) tick.setStyle("-fx-opacity: 0.5;");
        ruler.getChildren().add(tick);

        if (labelled || second) {
            javafx.scene.text.Text label = new javafx.scene.text.Text(x + 3, 9, rulerLabel(frame, fps));
            label.getStyleClass().add("ruler-text");
            label.setStyle(second ? "-fx-font-weight: bold; -fx-fill: -color-fg-default;" : "-fx-opacity: 0.7;");
            ruler.getChildren().add(label);
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

        int fps = rulerFps();
        float rulerInterval = getRulerInterval(pixelsPerSecond);
        float visibleDuration = timeline.duration;
        long steps = (long) Math.ceil(visibleDuration / rulerInterval);

        // long and double: with a very high frame rate a second is more frames than an int holds
        long framesInInterval = Math.max(1L, Math.round((double) rulerInterval * fps));
        float pixelsPerFrame = pixelsPerSecond / fps;

        for (long step = 0; step <= steps; step++) {
            // counted in whole frames, so every tick and label is exact (no 1/30 s rounding)
            long frame = step * framesInInterval;
            double x = frame * (double) pixelsPerSecond / fps;

            addRulerMark(ruler, x, frame, fps, true, true);

            // Minor ticks (frames) between this major tick and the next
            if (step < steps) {
                if (pixelsPerFrame >= 4f) {
                    // Enough space for every frame (at least 4px between ticks); very zoomed in, each carries its own
                    // number inside its second. The loop only runs when the frames are this wide, so it stays small.
                    for (long f = 1; f < framesInInterval; f++) {
                        long subFrame = frame + f;
                        addRulerMark(ruler, subFrame * (double) pixelsPerSecond / fps, subFrame, fps, false,
                                pixelsPerFrame >= 40f && framesInInterval > 1);
                    }
                } else if (framesInInterval >= 10) {
                    // Too dense for every frame, but wide enough for a midpoint tick. One tick, not a pass over every frame:
                    // at a very high frame rate a single interval holds more frames than anyone could loop over.
                    long mid = frame + framesInInterval / 2;
                    addRulerMark(ruler, mid * (double) pixelsPerSecond / fps, mid, fps, false, false);
                }
            }
        }

        ruler.setOnMouseEntered(e -> ghostPlayheadLine.setVisible(true));
        ruler.setOnMouseMoved(e -> {
            double x = e.getX();
            tempTime = snapToFrame((float) (x / pixelsPerSecond));   // the ghost line sits on a whole frame
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
            updateCurrentTime(snapToFrame((float) (e.getX() / pixelsPerSecond)));   // the playhead lands on a whole frame
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
                // A tile shows the source frame the clip plays at that moment (speed-aware), not the frame at trim + time.
                final com.vanvatcorporation.doubleclips.data.editing.ClipTimeMap speedMap = clip.timeMapSnapshot();
                int decoded = 0;
                for (int i = 0; i < tileCount; i++) {
                    if (stale.getAsBoolean())
                        return; // node removed, or re-zoomed/re-trimmed since this job was queued
                    float time = clip.startClipTrim + (float) speedMap.sourceOffset(i * tileDuration);
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
        if (!usageBadges.isEmpty()) scheduleUsageBadgeRefresh();

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
                        selectClipForKeyframe(clip);   // picking a keyframe picks its clip too
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
                    node.setOnMaskKeyframeClicked(time -> {
                        selectClipForKeyframe(clip);
                        updateCurrentTime(time);
                        refreshTimelineUI();
                    });
                    node.setOnMaskKeysEdited((c, before, after) -> {
                        // One undo step for moving / deleting a mask keyframe on the timeline.
                        final java.util.List<com.vanvatcorporation.doubleclips.data.editing.ClipMask> redoState =
                                com.vanvatcorporation.doubleclips.data.editing.ClipMask.copyAll(after);
                        final java.util.List<com.vanvatcorporation.doubleclips.data.editing.ClipMask> undoState =
                                com.vanvatcorporation.doubleclips.data.editing.ClipMask.copyAll(before);
                        executePropertyChange("Edit Mask Keyframes", () -> {
                            c.masks = com.vanvatcorporation.doubleclips.data.editing.ClipMask.copyAll(redoState);
                            refreshTimelineUI();
                            updatePropertiesPane();
                            timelineRenderer.syncWorker(currentTime);
                            if (previewGizmo != null) previewGizmo.refresh();
                            saveProject();
                        }, () -> {
                            c.masks = com.vanvatcorporation.doubleclips.data.editing.ClipMask.copyAll(undoState);
                            refreshTimelineUI();
                            updatePropertiesPane();
                            timelineRenderer.syncWorker(currentTime);
                            if (previewGizmo != null) previewGizmo.refresh();
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
        if (voiceLive != null) {
            activeNodes.add(voiceLive);                       // the voiceover take in progress is not a clip of the project
            positionVoiceLive();
        }
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
        refreshTrackSelectionVisuals();                       // the selected track's band (bands may be new)
        applyTrackMuteVisuals();

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
        Clip next = findTransitionNext(track, clip);
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

            final Rectangle newCube = cube;
            final Clip finalClip = clip;
            newCube.setOnMouseEntered(e -> styleTransitionCube(newCube, finalClip == selectedTransitionSourceClip, true));
            newCube.setOnMouseExited(e -> styleTransitionCube(newCube, finalClip == selectedTransitionSourceClip, false));
            newCube.setOnMouseClicked(e -> {
                selectTransition(finalClip);
                e.consume();
            });
            // What it is, on hover. Read when it is shown, so a change made in the panel is always current.
            javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip();
            tip.setOnShowing(e -> tip.setText(com.vanvatcorporation.doubleclips.TransitionSummary.of(finalClip.endTransition) + "\nClick to edit"));
            javafx.scene.control.Tooltip.install(newCube, tip);
            tracksPane.getChildren().add(newCube);
            transitionCubes.put(clip, newCube);
        } else {
            cube.setX(cubeX);
            cube.setY(cubeY);
            cube.toFront();
        }
        styleTransitionCube(cube, clip == selectedTransitionSourceClip, cube.isHover());
        activeNodes.add(cube);
    }

    /** Normal / hover / selected look of a transition cube; the selected one is also a little bigger (a bigger target). */
    private static void styleTransitionCube(Rectangle cube, boolean selected, boolean hover) {
        if (selected) {
            cube.setFill(Color.web("#B4BBFF"));
            cube.setStroke(Color.WHITE);
            cube.setStrokeWidth(2.5);
            cube.setScaleX(1.3);
            cube.setScaleY(1.3);
        } else {
            cube.setFill(Color.web(hover ? "#7B85FF" : "#5C67FF"));
            cube.setStroke(Color.web("#9BA3FF"));
            cube.setStrokeWidth(1.5);
            cube.setScaleX(1.0);
            cube.setScaleY(1.0);
        }
    }

    /** Restyle every cube after the selection changed. */
    private void refreshTransitionCubeVisuals() {
        Clip selected = getSelectedTransitionSourceClip();
        for (java.util.Map.Entry<Clip, Rectangle> entry : transitionCubes.entrySet()) {
            Rectangle cube = entry.getValue();
            styleTransitionCube(cube, entry.getKey() == selected, cube.isHover());
        }
    }

    // =========================================================================
    // Keyframe helpers
    // =========================================================================

    /** Recolours every clip's keyframe knots for the playhead: the keyframe it sits on is the chosen one. */
    private void refreshKeyframeKnotHighlight() {
        if (timeline == null) return;
        for (Track track : timeline.tracks) {
            for (Clip clip : track.clips) {
                if (clip.viewRef instanceof ClipNode cn) cn.refreshKeyframeHighlight();
            }
        }
    }

    /** Toolbar "Keyframe" button: keys every property at the playhead (per-property keys come from each property's own diamond). */
    @Override
    public void handleAddKeyframe() {
        if (selectedClip == null)
            return;
        Clip clip = selectedClip;

        // localTime of playhead within this clip
        float time = currentTime;
        float localTime = time - clip.startTime;
        if (localTime < 0 || localTime > clip.duration)
            return;

        KeyframesSnapshot before = KeyframesSnapshot.of(clip);
        int frameRate = videoSettings.frameRate;

        executePropertyChange("Add Keyframe", () -> {
            clip.keyframes.setAllKeys(clip, time, frameRate);
            if (clip.viewRef instanceof ClipNode cn) {
                cn.updateKeyframes(pixelsPerSecond);
            }
            saveProject();
            updatePropertiesPane();
        }, () -> {
            before.restore(clip);
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
        KeyframesSnapshot before = KeyframesSnapshot.of(clip);
        // The clip keeps the look it has at the playhead instead of jumping back to its static values.
        VideoProperties lookNow = clip.keyframes.resolve(clip, currentTime);

        executePropertyChange("Clear Keyframes", () -> {
            clip.videoProperties = new VideoProperties(lookNow);
            clip.keyframes.keyframes.clear();
            if (clip.viewRef instanceof ClipNode cn) {
                cn.clearKeyframeKnots();
            }
            saveProject();
            updatePropertiesPane();
        }, () -> {
            before.restore(clip);
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
        fileChooser.setTitle(I18n.t("Import Keyframes"));
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
        File file = fileChooser.showOpenDialog(this);
        if (file != null) {
            try (Reader reader = new FileReader(file)) {
                Gson gson = com.vanvatcorporation.doubleclips.ProjectGson.forLoad();
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
        fileChooser.setTitle(I18n.t("Export Keyframes"));
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
        fileChooser.setInitialFileName(selectedClip.getClipName() + "_keyframes.json");
        File file = fileChooser.showSaveDialog(this);
        if (file != null) {
            try (Writer writer = new FileWriter(file)) {
                Gson gson = com.vanvatcorporation.doubleclips.ProjectGson.forSavePretty();
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
    /** Bumped on every clip press: a "drag is over" reset queued by an earlier release must not end a newer drag. */
    private int dragGeneration = 0;

    /**
     * Removes every drag ghost still in the timeline and shows every clip again. A fast second press can arrive before
     * the first drag was torn down (or the first clip's node was rebuilt mid-drag, so its release never came); the
     * ghosts of that drag were then forgotten by activeDrag and stayed on screen, on top of the real clips.
     */
    private void discardStaleDragGhosts() {
        tracksPane.getChildren().removeIf(n -> n instanceof ClipNode && n.getStyleClass().contains("clip-node-ghost"));
        clearPhantomTracks();
        for (Clip c : timeline.getAllClips()) {
            if (c.viewRef instanceof ClipNode cn && !cn.isVisible()) cn.setVisible(true);
        }
    }

    private void setupClipInteraction(ClipNode node) {
        Clip clip = node.getContainerClip();

        node.setOnMousePressed(e -> {
            dragGeneration++;
            discardStaleDragGhosts();   // a new gesture starts clean, whatever the last one left behind
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
            final int releasedGeneration = dragGeneration;
            Platform.runLater(() -> { if (dragGeneration == releasedGeneration) activeDrag.dragging = false; });

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
            selectedTransitionSourceClip = null;
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
        explicitTrack = null;
        refreshTrackSelectionVisuals();
        updatePropertiesPane(); // once, at the end, not on every mouse move
    }

    /**
     * A keyframe was clicked on the timeline: its clip becomes the selected one (so the properties panel shows the
     * keyframe's clip). A clip that is already part of a multi-selection stays in it and just becomes the primary.
     */
    private void selectClipForKeyframe(Clip clip) {
        if (selectedClips.contains(clip)) makePrimary(clip);
        else selectClip(clip);
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

    private void deselectAll() {
        selectedClips.clear();
        selectedClip = null;
        selectedTransitionSourceClip = null;
        selectedTrack = null;
        explicitTrack = null;
        refreshSelectionVisuals();
        updatePropertiesPane();
    }

    /** Push the selection state onto every clip node. Only the primary clip shows trim handles. */
    private void refreshSelectionVisuals() {
        if (!selectedClips.isEmpty()) explicitTrack = null;       // selecting a clip ends "a track is selected"
        refreshTrackSelectionVisuals();
        for (Track t : timeline.tracks) {
            for (Clip c : t.clips) {
                if (c.viewRef instanceof ClipNode cn) {
                    cn.setSelected(selectedClips.contains(c), c == selectedClip);
                }
            }
        }
        refreshTransitionCubeVisuals();
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

    /** Length of anything without a length of its own (text, effect, image...), from the preferences. */
    private static float defaultClipDuration() {
        return com.vanvatcorporation.doubleclips.data.AppSettings.getInstance().getDefaultClipDuration();
    }
    private void handleAddText() {
        Clip textClip = new Clip("Text", currentTime, defaultClipDuration(), 0, ClipType.TEXT, false, 1280, 720);
        textClip.textContent = "New Text";
        textClip.fontSize = 48;
        textClip.textStyle = com.vanvatcorporation.doubleclips.TextPresets.defaultStyle(); // iOS format; the Classic look: white, centred
        
        historyManager.execute(new AddClipCommand(timeline, textClip, 0, () -> {
            refreshTimelineUI();
            saveProject();
        }));
    }

    private void reloadLeftPanelContent(String tabName) {
        if (effectAnimator != null) { // the Effects tab's looping previews belong to that tab only
            effectAnimator.stop();
            effectAnimator = null;
        }
        mediaGrid.getChildren().clear();
        usageBadges.clear();
        if (mediaToolbar != null) {
            boolean media = "Media".equals(tabName);
            mediaToolbar.setVisible(media);
            mediaToolbar.setManaged(media);
        }
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
            case "Transitions":
                loadTransitionTiles();
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
            Clip textClip = new Clip(presets[i], 0, defaultClipDuration(), 0, ClipType.TEXT, false, 1280, 720);
            textClip.textContent = presets[i];
            textClip.fontSize = sizes[i];
            textClip.textStyle = com.vanvatcorporation.doubleclips.TextPresets.defaultStyle();
            addClipToMediaGrid(mediaGrid, textClip);
        }
    }

    /**
     * The Transitions tab: the picker's grid of tiles (every style playing between two pictures), shown in the pane. A tile is dragged
     * onto the knot between two clips to give that transition the style; a drop on the track, a clip or anywhere else does nothing.
     */
    private void loadTransitionTiles() {
        effectAnimator = new com.vanvatcorporation.doubleclips.ui.components.TilePreviewAnimator(12); // this tab's looping previews
        final java.util.List<com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Entry> entries =
                com.vanvatcorporation.doubleclips.ui.components.TransitionTiles.entries(null);
        java.util.List<com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Section> sections =
                com.vanvatcorporation.doubleclips.ui.components.TransitionTiles.sections(entries,
                        com.vanvatcorporation.doubleclips.ui.components.TransitionTiles::plainTitle, tilePreviews);

        Label hint = new Label(com.vanvatcorporation.doubleclips.i18n.I18n.t("Drag a transition onto the knot between two clips."));
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");

        VBox grid = com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.buildContent(sections, null, null, effectAnimator, null,
                (tile, node) -> attachTransitionDrag(tile, node, entries));
        VBox pane = new VBox(8, hint, grid);
        pane.prefWidthProperty().bind(javafx.beans.binding.Bindings.max(140.0, mediaGrid.widthProperty().subtract(24)));
        pane.setMaxWidth(Region.USE_PREF_SIZE);
        mediaGrid.getChildren().add(pane);
    }

    /** The picture that follows the cursor while a transition tile is dragged (null when none is). */
    private javafx.stage.Popup transitionGhost;

    /** Makes a tile of the Transitions tab draggable onto a transition knot (the same press-drag-release the media tiles use). */
    private void attachTransitionDrag(com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Tile tile, javafx.scene.Node node,
                                      java.util.List<com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Entry> entries) {
        node.setOnMouseDragged(e -> {
            if (transitionGhost == null) {
                transitionGhost = buildTransitionGhost(tile);
                transitionGhost.show(this, e.getScreenX() + 14, e.getScreenY() + 14);
            } else {
                transitionGhost.setX(e.getScreenX() + 14);
                transitionGhost.setY(e.getScreenY() + 14);
            }
            Clip over = transitionClipAt(e.getSceneX(), e.getSceneY());
            highlightTransitionDropTarget(over);
            if (!transitionGhost.getContent().isEmpty()) transitionGhost.getContent().get(0).setOpacity(over != null ? 1.0 : 0.55);
            e.consume();
        });
        node.setOnMouseReleased(e -> {
            boolean dragged = transitionGhost != null;
            if (dragged) {
                transitionGhost.hide();
                transitionGhost = null;
            }
            highlightTransitionDropTarget(null);
            if (dragged) {
                Clip target = transitionClipAt(e.getSceneX(), e.getSceneY());
                int index = com.vanvatcorporation.doubleclips.TransitionStyleCatalog.indexOf(entries, tile.id);
                if (target != null && index >= 0) applyTransitionStyle(target, entries.get(index));
                // anywhere else the drop is discarded
            }
            e.consume();
        });
    }

    private javafx.stage.Popup buildTransitionGhost(com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Tile tile) {
        ImageView picture = new ImageView(tile.thumbnail != null ? tile.thumbnail.get() : null);
        picture.setFitWidth(96);
        picture.setPreserveRatio(true);
        Label name = new Label(tile.title == null ? "" : tile.title);
        name.setStyle("-fx-text-fill: white; -fx-font-size: 11px;");
        VBox box = new VBox(2, picture, name);
        box.setPadding(new Insets(4));
        box.setStyle("-fx-background-color: #262626; -fx-background-radius: 6;");
        box.setMouseTransparent(true);
        box.setOpacity(0.55); // dim until it is over a knot
        javafx.stage.Popup popup = new javafx.stage.Popup();
        popup.setAutoFix(false);
        popup.setAutoHide(false);
        popup.getContent().add(box);
        return popup;
    }

    /** The clip whose end knot is under the point (scene coordinates), or null: a knot is the only place a transition can be dropped. */
    private Clip transitionClipAt(double sceneX, double sceneY) {
        javafx.geometry.Point2D inScroll = tracksScrollPane.sceneToLocal(sceneX, sceneY);
        if (inScroll == null || !tracksScrollPane.contains(inScroll)) return null; // a knot scrolled out of view can't be reached
        Clip best = null;
        double bestDistance = Double.MAX_VALUE;
        for (java.util.Map.Entry<Clip, Rectangle> entry : transitionCubes.entrySet()) {
            Rectangle cube = entry.getValue();
            if (cube.getParent() != tracksPane || !cube.isVisible()) continue;
            javafx.geometry.Bounds b = cube.localToScene(cube.getBoundsInLocal());
            double distance = Math.hypot(sceneX - (b.getMinX() + b.getMaxX()) / 2.0, sceneY - (b.getMinY() + b.getMaxY()) / 2.0);
            double reach = Math.max(b.getWidth(), b.getHeight()) / 2.0 + 8.0; // a little forgiving: the knot is only 16 px
            if (distance <= reach && distance < bestDistance) {
                best = entry.getKey();
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Lights up the knot a dragged tile would land on (null = none). */
    private void highlightTransitionDropTarget(Clip clip) {
        refreshTransitionCubeVisuals();
        if (clip == null) return;
        Rectangle cube = transitionCubes.get(clip);
        if (cube != null) styleTransitionCube(cube, true, true);
    }

    /** Gives the transition after {@code clipA} the dropped style, as one undoable step, and shows it in the properties panel. */
    private void applyTransitionStyle(Clip clipA, com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Entry chosen) {
        final TransitionClip tc = clipA.endTransition;
        if (tc == null || getTransitionNextClip(clipA) == null) return;
        if (tc.effect != null && chosen.id.equals(tc.effect.style)) {
            selectTransition(clipA); // already that style
            return;
        }
        final com.vanvatcorporation.doubleclips.TransitionStyleApply.Before before = com.vanvatcorporation.doubleclips.TransitionStyleApply.capture(tc);
        final String title = com.vanvatcorporation.doubleclips.ui.components.TransitionTiles.plainTitle(chosen);
        executePropertyChange("Transition " + title, () -> {
            com.vanvatcorporation.doubleclips.TransitionStyleApply.apply(tc, chosen);
            saveProject();
            selectTransition(clipA);
        }, () -> {
            com.vanvatcorporation.doubleclips.TransitionStyleApply.restore(tc, before);
            saveProject();
            updatePropertiesPane();
        });
    }

    /** Every effect as a tile with a looping preview of the real effect; drag one onto the timeline for an effect clip. */
    private void loadEffectSamples() {
        effectAnimator = new com.vanvatcorporation.doubleclips.ui.components.TilePreviewAnimator(12);
        for (com.vanvatcorporation.doubleclips.EffectCatalog.Style style : com.vanvatcorporation.doubleclips.EffectCatalog.styles()) {
            Clip effectClip = new Clip(style.title, 0, defaultClipDuration(), 0, ClipType.EFFECT, false, 1280, 720);
            effectClip.effect = new EffectTemplate(style.key, defaultClipDuration(), 0);
            addClipToMediaGrid(mediaGrid, effectClip);
            decorateEffectTile((VBox) mediaGrid.getChildren().get(mediaGrid.getChildren().size() - 1), style);
        }
    }

    /** Swaps a plain effect tile's star icon for the effect's own looping picture, and adds "@author" and engine badges. */
    private void decorateEffectTile(VBox box, com.vanvatcorporation.doubleclips.EffectCatalog.Style style) {
        final int side = 72;
        // The picture is made by the preview worker's real renderer: a neutral square until its frames are ready.
        ImageView picture = new ImageView(tilePreviews.effectStill(style.key, 1f));
        picture.setFitWidth(side);
        picture.setFitHeight(side);
        picture.setPreserveRatio(true);
        picture.setMouseTransparent(true); // the whole tile is the drag handle
        if (effectAnimator != null) {
            effectAnimator.add(picture, () -> tilePreviews.effectLoop(style.key, 1f));
        }
        box.getChildren().set(0, picture);

        Label author = new Label(style.author);
        author.setStyle("-fx-text-fill: #999; -fx-font-size: 9px;");
        HBox badges = new HBox(3);
        badges.setAlignment(Pos.CENTER);
        for (String engine : style.engines()) {
            boolean gl = com.vanvatcorporation.doubleclips.EffectCatalog.ENGINE_OPENGL.equals(engine);
            Label pill = new Label(gl ? "OpenGL" : "FFmpeg");
            pill.setStyle("-fx-background-color: " + (gl ? "#2E9B5B" : "#2E6BD8")
                    + "; -fx-text-fill: white; -fx-font-size: 8px; -fx-background-radius: 7; -fx-padding: 0 4 0 4;");
            badges.getChildren().add(pill);
        }
        box.getChildren().addAll(author, badges);
        box.setPrefWidth(84);
        box.setPrefHeight(124);
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
            trackHeadersContainer.getChildren().add(buildTrackHeader("Track " + (t.timelineIndex + 1), t.timelineIndex));
        }
        shownTrackSelection = selectedTrackOnly();
        updateReorderTracksButton();
    }

    // ---- track reorder ------------------------------------------------------------------------

    private void toggleTrackReorder() {
        reorderingTracks = !reorderingTracks;
        trackDrag = null;
        refreshTrackHeaders(); // grips appear / disappear
    }

    private void updateReorderTracksButton() {
        if (reorderTracksButton == null) return;
        reorderTracksButton.setGraphic(new FontIcon(reorderingTracks
                ? org.kordamp.ikonli.materialdesign2.MaterialDesignC.CHECK
                : org.kordamp.ikonli.materialdesign2.MaterialDesignS.SWAP_VERTICAL));
        reorderTracksButton.setTooltip(new Tooltip(reorderingTracks
                ? "Done reordering"
                : "Reorder tracks (a lower track is drawn on top of the ones above it)"));
        reorderTracksButton.setDisable(timeline.tracks.size() < 2 && !reorderingTracks);
    }

    private void trackGripPressed(int index, javafx.scene.input.MouseEvent e) {
        if (index < 0 || index >= timeline.tracks.size()) return;
        if (isPlaying) stopPlayback();
        trackDrag = new com.vanvatcorporation.doubleclips.ui.TrackReorderDrag(index, timeline.tracks.size(), TRACK_HEIGHT + TRACK_SPACING);
        trackDragStartSceneY = e.getSceneY();
        javafx.scene.Node grabbed = trackHeadersContainer.getChildren().get(index);
        grabbed.setViewOrder(-1); // above the rows it slides over
        e.consume();
    }

    private void trackGripDragged(javafx.scene.input.MouseEvent e) {
        if (trackDrag == null) return;
        trackDrag.setTranslation(e.getSceneY() - trackDragStartSceneY);
        applyTrackDragOffsets();
        e.consume();
    }

    private void trackGripReleased(javafx.scene.input.MouseEvent e) {
        if (trackDrag == null) return;
        int from = trackDrag.startIndex;
        int to = trackDrag.targetIndex();
        trackDrag = null;
        clearTrackDragOffsets();
        e.consume();
        if (from == to) return;
        // The tracks are only reordered now, in one undoable step.
        historyManager.execute(new com.vanvatcorporation.doubleclips.history.MoveTrackCommand(timeline, from, to, () -> {
            refreshTrackHeaders();
            refreshTimelineUI();
            saveProject();
        }));
    }

    /** Slides each track's header and its clips by the drag's offset for that row. */
    private void applyTrackDragOffsets() {
        for (int i = 0; i < timeline.tracks.size(); i++) {
            double offset = trackDrag.offsetOfRow(i);
            if (i < trackHeadersContainer.getChildren().size()) trackHeadersContainer.getChildren().get(i).setTranslateY(offset);
            for (Clip clip : timeline.tracks.get(i).clips) {
                if (clip.viewRef instanceof ClipNode cn) cn.setTranslateY(offset);
            }
        }
    }

    private void clearTrackDragOffsets() {
        for (javafx.scene.Node header : trackHeadersContainer.getChildren()) {
            header.setTranslateY(0);
            header.setViewOrder(0);
        }
        for (Track track : timeline.tracks) {
            for (Clip clip : track.clips) {
                if (clip.viewRef instanceof ClipNode cn) cn.setTranslateY(0);
            }
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
        } else if (selectedTrackOnly() != null) {
            trackActions.delete(selectedTrackOnly());           // one undo step; undo puts it back in place
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
        } else if (selectedTrackOnly() != null) {
            trackActions.duplicate(selectedTrackOnly());        // a real copy of every clip, right below the original
        }
    }

    /** Same JSON layout as the project file (only @Expose fields), so what is copied is exactly what is saved. */
    private final Gson clipboardGson = com.vanvatcorporation.doubleclips.ProjectGson.forSave();
    private final Gson clipboardLoadGson = com.vanvatcorporation.doubleclips.ProjectGson.forLoad();

    /**
     * Copies the selected clips to the system clipboard as a JSON ARRAY of clips (one element when a single
     * clip is selected), in timeline order. With no clip selected, a selected track is copied instead.
     */
    private void handleCopy() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        if (!selectedClips.isEmpty() || selectedTrackOnly() != null) cancelPendingCut(); // a new copy replaces the cut
        if (!selectedClips.isEmpty()) {
            content.putString(clipboardGson.toJson(orderedSelection().toArray(new Clip[0])));
            clipboard.setContent(content);
        } else if (selectedTrackOnly() != null) {
            content.putString(clipboardGson.toJson(selectedTrackOnly()));   // same layout as the project file
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
        Clip c = clipboardLoadGson.fromJson(obj, Clip.class);
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
        } else if (selectedTrackOnly() != null) {
            baseTrack = selectedTrackOnly().timelineIndex;      // a track is selected, no clip: paste onto that track
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

    /** A copied track goes right below the selected track (or at the end); one undo step. */
    private void pasteTrack(String json) {
        Track pasteTrack;
        try {
            pasteTrack = clipboardLoadGson.fromJson(json, Track.class);
        } catch (JsonSyntaxException e) {
            return;
        }
        if (pasteTrack == null) return;
        for (Clip c : pasteTrack.clips) c.filterNullAfterLoad();
        final Track after = selectedTrackOnly();
        executePropertyChange("Paste Track", () -> {
            int at = after == null ? timeline.tracks.size() : timeline.tracks.indexOf(after) + 1;
            if (at <= 0) at = timeline.tracks.size();
            timeline.tracks.add(Math.min(at, timeline.tracks.size()), pasteTrack);
            timeline.reloadTrackIndex();
            timeline.recalculateDuration();
            afterTrackEdit();
        }, () -> {
            timeline.tracks.remove(pasteTrack);
            timeline.reloadTrackIndex();
            timeline.recalculateDuration();
            afterTrackEdit();
        });
    }

    // =========================================================================
    // Track selection and actions
    // =========================================================================

    private boolean isTrackSelected(Track t) {
        return t != null && t == explicitTrack && selectedClips.isEmpty();
    }

    /** The track selected on purpose, when no clip is selected; else null. */
    private Track selectedTrackOnly() {
        return explicitTrack != null && selectedClips.isEmpty() && timeline.tracks.contains(explicitTrack) ? explicitTrack : null;
    }

    /** Selects a track (clears the clip selection); null clears the track selection. */
    private void selectTrack(Track t) {
        selectedClips.clear();
        selectedClip = null;
        selectedTransitionSourceClip = null;
        explicitTrack = t;
        selectedTrack = t;
        refreshSelectionVisuals();
        updatePropertiesPane();
    }

    /** Header highlight (rebuilt only when the selection changed) and the selected track's band. */
    private void refreshTrackSelectionVisuals() {
        if (explicitTrack != null && !timeline.tracks.contains(explicitTrack)) {   // deleted, or its creation undone
            if (selectedTrack == explicitTrack) selectedTrack = null;
            explicitTrack = null;
        }
        Track now = selectedTrackOnly();
        if (now != shownTrackSelection && trackHeadersContainer != null) refreshTrackHeaders();
        for (Track t : timeline.tracks) {
            Rectangle band = trackBands.get(t.timelineIndex);
            if (band == null) continue;
            boolean on = t == now;
            if (on && !band.getStyleClass().contains("track-band-selected")) band.getStyleClass().add("track-band-selected");
            else if (!on) band.getStyleClass().remove("track-band-selected");
        }
    }

    /** Clips of a muted track show their waveform faded. */
    private void applyTrackMuteVisuals() {
        for (Track t : timeline.tracks) {
            for (Clip c : t.clips) {
                if (c.viewRef instanceof ClipNode n) n.setAudioMuted(t.muted);
            }
        }
    }

    /** What every track action does after it edited the timeline. */
    private void afterTrackEdit() {
        updateCurrentClipEnd();
        refreshTrackHeaders();
        refreshTimelineUI();
        saveProject();
    }

    private final TrackActions trackActions = new TrackActions(new TrackActions.Host() {
        @Override public Timeline timeline() { return timeline; }
        @Override public HistoryManager history() { return historyManager; }
        @Override public float playhead() { return currentTime; }
        @Override public void selectTrack(Track track) { EditorWindow.this.selectTrack(track); }
        @Override public void selectClips(List<Clip> clips) { EditorWindow.this.selectClips(clips); }
        @Override public void afterEdit() { afterTrackEdit(); }
        @Override public void afterMute() {
            refreshTrackHeaders();
            applyTrackMuteVisuals();
            saveProject();
        }
        @Override public void copyTrack() { handleCopy(); }
        @Override public void pasteHere() { handlePaste(); }
        @Override public void splitHere() { handleSplit(); }
    });

    // =========================================================================
    // Voiceover
    // =========================================================================

    /**
     * The one rule for "the user interfered while a take is running": the take ends (and is kept). Called for a pause
     * from any path, a seek or scrub, and any edit / undo / redo. Zoom and scrolling do not touch the take.
     */
    private void endTakeFromEditor() {
        if (voiceover != null && voiceover.isRecording()) voiceover.stopFromEditor();
        else recordingPlayback = false;
    }

    private void openVoiceover() {
        if (voiceover == null) voiceover = new VoiceoverController(voiceoverHost);
        voiceover.open();
    }

    private void positionVoiceLive() {
        if (voiceLive == null) return;
        double rowH = TRACK_HEIGHT + TRACK_SPACING;
        voiceLive.setLayoutX(voiceLiveStart * pixelsPerSecond);
        voiceLive.setLayoutY(voiceLiveTrack * rowH + 3);
    }

    /** Puts the pane back to the size the project alone needs (after the live clip is gone). */
    private void restoreTracksPaneSize() {
        double rowH = TRACK_HEIGHT + TRACK_SPACING;
        double contentWidth = Math.max(1200, timeline.duration * pixelsPerSecond + 1000);
        tracksPane.setPrefWidth(contentWidth);
        tracksPane.setPrefHeight(timeline.tracks.size() * rowH);
        for (Rectangle b : trackBands.values()) b.setWidth(contentWidth);
    }

    private final VoiceoverController.Host voiceoverHost = new VoiceoverController.Host() {
        @Override public DockManager dock() { return dock; }

        @Override public double[] windowPosition() {
            return new double[]{EditorWindow.this.getX() + 70, EditorWindow.this.getY() + 90};
        }

        @Override public float playhead() { return currentTime; }

        @Override public int chooseTrack(float start) {
            // An existing audio track that is free from the playhead on; otherwise a new track at the end.
            for (int i = 0; i < timeline.tracks.size(); i++) {
                Track t = timeline.tracks.get(i);
                if (t.clips.isEmpty()) continue;
                boolean audioOnly = true;
                for (Clip c : t.clips) if (c.type != ClipType.AUDIO) { audioOnly = false; break; }
                if (audioOnly && t.getTrackEndTime() <= start + 0.001f) return i;
            }
            return timeline.tracks.size();
        }

        @Override public void beginPlayback() {
            recordingPlayback = true;
            savedPreviewSpeed = previewSpeed;
            savedReverse = isPlayingInReverse;
            previewSpeed = 1.0f;                // a take is recorded at normal speed, forward
            isPlayingInReverse = false;
            startPlayback();
            playBtn.setGraphic(pauseIcon);
        }

        @Override public void endPlayback() {
            recordingPlayback = false;
            stopPlayback();
            playBtn.setGraphic(playIcon);
            previewSpeed = savedPreviewSpeed;
            isPlayingInReverse = savedReverse;
        }

        @Override public void setProjectMuted(boolean muted) {
            if (timelineRenderer != null) timelineRenderer.setProjectMuted(muted);
        }

        @Override public void liveShow(int track, float start) {
            liveHide();
            voiceLiveTrack = track;
            voiceLiveStart = start;
            voiceLive = new VoiceoverLiveClip(TRACK_HEIGHT - 6);
            tracksPane.getChildren().add(voiceLive);
            positionVoiceLive();
            updatePhantomTracks(track);          // a blank row when the take goes on a new track
        }

        @Override public void liveUpdate(double seconds, float[] peaks, int blocks) {
            if (voiceLive == null) return;
            double contentWidth = tracksScrollPane.getContent().getBoundsInLocal().getWidth();
            double viewportWidth = tracksScrollPane.getViewportBounds().getWidth();
            double scrollX = tracksScrollPane.getHvalue() * Math.max(0, contentWidth - viewportWidth);
            double nodeX = voiceLiveStart * pixelsPerSecond;
            double needW = (voiceLiveStart + seconds) * pixelsPerSecond + 1000;
            if (tracksPane.getPrefWidth() < needW) {
                tracksPane.setPrefWidth(needW);
                for (Rectangle b : trackBands.values()) b.setWidth(needW);
            }
            positionVoiceLive();
            voiceLive.update(pixelsPerSecond, seconds, peaks, blocks, scrollX - nodeX, scrollX + viewportWidth - nodeX);
        }

        @Override public void liveHide() {
            if (voiceLive == null) return;
            tracksPane.getChildren().remove(voiceLive);
            voiceLive = null;
            voiceLiveTrack = -1;
            clearPhantomTracks();
            restoreTracksPaneSize();
        }

        @Override public float[] audioReference(double t0, double seconds, int rate) {
            return timelineRenderer == null ? new float[0] : timelineRenderer.renderAudioReference(t0, seconds, rate);
        }

        @Override public ProjectData project() { return project; }

        @Override public void addClip(Clip clip, int trackIndex) {
            trackIndex = Math.min(trackIndex, timeline.tracks.size());   // an edit may have removed tracks meanwhile
            clip.trackIndex = trackIndex;
            historyManager.execute(new AddClipCommand(timeline, clip, trackIndex, () -> {
                refreshTrackHeaders();           // the add may have made a track
                refreshTimelineUI();
                saveProject();
            }));
            selectClip(clip);
        }

        @Override public void showError(String title, String message) {
            Alert a = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
            a.setTitle(title);
            a.setHeaderText(null);
            a.initOwner(EditorWindow.this);
            a.show();
        }
    };

    private HBox buildTrackHeader(String name, int index) {
        final Track track = index >= 0 && index < timeline.tracks.size() ? timeline.tracks.get(index) : null;
        final boolean selected = isTrackSelected(track);
        HBox header = new HBox(6);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 8, 0, 8));
        header.setPrefHeight(75);
        header.setMaxHeight(75);
        header.getStyleClass().add("track-header");
        if (selected) header.getStyleClass().add("track-header-selected");

        final Node[] gripHolder = new Node[1];
        if (reorderingTracks) {
            Label grip = new Label();
            grip.setGraphic(new FontIcon(org.kordamp.ikonli.materialdesign2.MaterialDesignD.DRAG));
            grip.setStyle("-fx-cursor: move; -fx-padding: 6 2 6 0;");
            grip.setTooltip(new Tooltip(I18n.t("Drag to reorder - a lower track is drawn on top")));
            grip.setOnMousePressed(e -> trackGripPressed(index, e));
            grip.setOnMouseDragged(this::trackGripDragged);
            grip.setOnMouseReleased(this::trackGripReleased);
            gripHolder[0] = grip;
            header.getChildren().add(grip);
        }

        Label lbl = new Label(name);
        lbl.setStyle("-fx-text-fill: " + (selected ? "-color-accent-fg" : "-color-fg-muted") + "; -fx-font-size: 11px;"
                + (selected ? " -fx-font-weight: bold;" : ""));

        Region hs = new Region();
        HBox.setHgrow(hs, Priority.ALWAYS);

        final boolean muted = track != null && track.muted;
        Button muteBtn = new Button();
        muteBtn.setGraphic(new FontIcon(muted ? MaterialDesignV.VOLUME_OFF : MaterialDesignV.VOLUME_HIGH));
        muteBtn.getStyleClass().add("button-transparent");
        muteBtn.setStyle("-fx-padding: 2px;");
        muteBtn.setTooltip(new Tooltip(muted ? "Unmute track" : "Mute track"));
        if (track != null) muteBtn.setOnAction(e -> trackActions.toggleMute(track));
        if (muted) lbl.setOpacity(0.5);

        header.getChildren().addAll(lbl, hs);
        if (selected) {
            // The track's actions, for anyone who does not think of right-click.
            Button more = new Button();
            more.setGraphic(new FontIcon(org.kordamp.ikonli.materialdesign2.MaterialDesignD.DOTS_HORIZONTAL));
            more.getStyleClass().add("button-transparent");
            more.setStyle("-fx-padding: 2px;");
            more.setTooltip(new Tooltip(I18n.t("Track actions")));
            more.setOnAction(e -> trackActions.menuFor(track).show(more, javafx.geometry.Side.BOTTOM, 0, 0));
            header.getChildren().add(more);
        }
        header.getChildren().add(muteBtn);

        if (track != null) {
            // Click selects the track (click it again to let go); the buttons and the grip keep their own clicks.
            header.setOnMouseClicked(e -> {
                if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
                if (e.getTarget() instanceof Node n) {
                    for (Node p = n; p != null && p != header; p = p.getParent()) {
                        if (p instanceof Button || p == gripHolder[0]) return;
                    }
                }
                selectTrack(isTrackSelected(track) ? null : track);
            });
            header.setOnContextMenuRequested(e -> {
                if (!isTrackSelected(track)) selectTrack(track);
                trackActions.menuFor(track).show(header, e.getScreenX(), e.getScreenY());
                e.consume();
            });
        }
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
