package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.AppSettings;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;
import com.vanvatcorporation.doubleclips.ui.renderer.TimelineRenderer;
import javafx.animation.AnimationTimer;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

/**
 * "Source" view of the preview: clicking a file in the Media tab shows it here, on its own, with its own play button
 * and seek bar. The project's timeline, playhead and preview are not touched; the "Project" tab goes back to them.
 *
 * The picture comes from a second {@link TimelineRenderer} that holds a one-clip timeline (a copy of the file's clip,
 * untrimmed), so every format the project preview can play is shown the same way here, with its sound.
 * The renderer is created on the first click, not when the editor opens.
 */
public class SourcePreview {

    private final ProjectData project;
    private final VideoSettings settings;
    private final java.util.function.IntSupplier fps;
    private final java.util.function.Consumer<Boolean> onModeChanged;

    private TimelineRenderer renderer;
    private Clip clip;            // the untrimmed copy on the one-clip timeline
    private float time = 0f;
    private boolean sourceMode = false;
    private boolean playing = false;
    private boolean updatingSlider = false;
    private long lastNanos = 0;

    // The layer over the project's picture (and its gizmo) while the Source tab is on
    private final StackPane overlay = new StackPane();
    private final Group pictureGroup = new Group();
    private final Label audioLabel = new Label();

    // Under the picture: time, play button, seek bar
    private final HBox controls = new HBox(12);
    private final Label timeLabel = new Label("00:00:00:00");
    private final Label durationLabel = new Label("00:00:00:00");
    private final Button playBtn = new Button();
    private final Slider seek = new Slider(0, 1, 0);

    // Source | Project tabs, below the controls (like Shotcut)
    private final HBox tabs = new HBox(0);
    private final ToggleButton sourceTab = new ToggleButton("Source");
    private final ToggleButton projectTab = new ToggleButton("Project");

    private final AnimationTimer timer = new AnimationTimer() {
        @Override public void handle(long now) {
            if (lastNanos > 0 && clip != null) {
                time += (now - lastNanos) / 1_000_000_000f;
                float end = lastFrameTime();
                if (time >= end) {
                    time = end;
                    pause();   // reached the end: stop on the last frame
                    return;
                }
                show(time, false);
            }
            lastNanos = now;
        }
    };

    /**
     * @param onModeChanged called with true when the Source view comes up, false when the project is back
     *                      (the editor hides / shows its own playback controls)
     */
    public SourcePreview(ProjectData project, VideoSettings settings, java.util.function.IntSupplier fps,
                         java.util.function.Consumer<Boolean> onModeChanged) {
        this.project = project;
        this.settings = settings;
        this.fps = fps;
        this.onModeChanged = onModeChanged;
        buildUi();
    }

    // ── UI ──────────────────────────────────────────────────────────────

    private void buildUi() {
        overlay.setStyle("-fx-background-color: #aaaaaa;");
        overlay.setMinSize(0, 0);
        pictureGroup.setManaged(false);
        audioLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px; -fx-background-color: rgba(0,0,0,0.55);"
                + " -fx-padding: 6 12; -fx-background-radius: 6;");
        FontIcon note = new FontIcon(MaterialDesignM.MUSIC_NOTE);
        note.setIconSize(28);
        note.setIconColor(javafx.scene.paint.Color.WHITE);
        audioLabel.setGraphic(note);
        audioLabel.setMouseTransparent(true);
        audioLabel.setVisible(false);
        overlay.getChildren().addAll(pictureGroup, audioLabel);
        overlay.setVisible(false);
        // An opaque layer: clicks must not fall through to the project's gizmo underneath
        overlay.setPickOnBounds(true);
        ChangeListener<Bounds> relayout = (o, a, b) -> layoutPicture();
        overlay.layoutBoundsProperty().addListener(relayout);

        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new Insets(10, 16, 10, 16));
        controls.getStyleClass().add("playback-controls");
        timeLabel.getStyleClass().add("timecode-label");
        durationLabel.getStyleClass().add("timecode-label");
        Label sep = new Label("/");
        sep.setStyle("-fx-text-fill: -color-fg-muted;");
        playBtn.setGraphic(new FontIcon(MaterialDesignP.PLAY_CIRCLE_OUTLINE));
        playBtn.getStyleClass().addAll("button-transparent", "play-button-main");
        playBtn.setOnAction(e -> togglePlay());
        HBox.setHgrow(seek, Priority.ALWAYS);
        seek.setMaxWidth(Double.MAX_VALUE);
        seek.valueProperty().addListener((o, was, now) -> {
            if (updatingSlider || clip == null) return;
            time = Math.min(now.floatValue(), lastFrameTime());
            lastNanos = 0;                     // a drag while playing keeps playing from the new spot
            show(time, true);
            updateLabels();
        });
        controls.getChildren().addAll(timeLabel, sep, durationLabel, playBtn, seek);
        controls.setVisible(false);
        controls.setManaged(false);

        ToggleGroup group = new ToggleGroup();
        sourceTab.setToggleGroup(group);
        projectTab.setToggleGroup(group);
        sourceTab.setDisable(true);            // nothing to show until a file was clicked
        projectTab.setSelected(true);
        // A tab can never be left unselected: clicking the selected one keeps it
        sourceTab.setOnAction(e -> { showSource(); sourceTab.setSelected(sourceMode); projectTab.setSelected(!sourceMode); });
        projectTab.setOnAction(e -> { showProject(); projectTab.setSelected(true); });
        tabs.setAlignment(Pos.CENTER_LEFT);
        tabs.setPadding(new Insets(2, 8, 4, 8));
        tabs.getStyleClass().add("source-project-tabs");
        tabs.getChildren().addAll(sourceTab, projectTab);
    }

    /** The layer to put on top of the preview canvas. */
    public Node overlay() { return overlay; }

    /** The source's own playback row; the editor puts it right under its own one. */
    public Node controls() { return controls; }

    /** The Source | Project tab strip. */
    public Node tabs() { return tabs; }

    public boolean isActive() { return sourceMode; }

    // ── Showing a file ──────────────────────────────────────────────────

    /** Shows {@code mediaClip} (a video, audio or image tile of the Media tab) in the Source view, stopped at 0. */
    public void open(Clip mediaClip) {
        if (mediaClip == null) return;
        if (mediaClip.type != ClipType.VIDEO && mediaClip.type != ClipType.AUDIO && mediaClip.type != ClipType.IMAGE) return;
        pause();

        Clip c = new Clip(mediaClip);
        c.startTime = 0f;
        c.trackIndex = 0;
        c.startClipTrim = 0f;      // the whole file; trimming it here comes later
        c.endClipTrim = 0f;
        if (c.originalDuration > 0f) c.duration = c.originalDuration;
        if (c.duration <= 0f) c.duration = 5f;
        clip = c;

        ensureRenderer();
        Timeline tl = new Timeline();
        Track track = new Track();
        tl.addTrack(track);
        track.addClip(c);
        tl.duration = c.duration;
        renderer.buildTimeline(tl);

        time = 0f;
        audioLabel.setText(c.getClipName());
        audioLabel.setVisible(c.type == ClipType.AUDIO);
        durationLabel.setText(timecode(c.duration));
        updatingSlider = true;
        seek.setMax(Math.max(0.01, lastFrameTime()));
        seek.setValue(0);
        seek.setBlockIncrement(1.0 / Math.max(1, fps.getAsInt()));
        updatingSlider = false;
        updateLabels();
        sourceTab.setDisable(false);
        showSource();
        show(0f, true);
    }

    private void showSource() {
        if (clip == null) return;
        sourceMode = true;
        sourceTab.setSelected(true);
        overlay.setVisible(true);
        controls.setVisible(true);
        controls.setManaged(true);
        layoutPicture();
        onModeChanged.accept(true);
    }

    /** Back to the project's preview (the Source tab stays, with the file still loaded). */
    public void showProject() {
        if (!sourceMode) { projectTab.setSelected(true); return; }
        pause();
        sourceMode = false;
        projectTab.setSelected(true);
        overlay.setVisible(false);
        controls.setVisible(false);
        controls.setManaged(false);
        onModeChanged.accept(false);
    }

    // ── Playback ────────────────────────────────────────────────────────

    public void togglePlay() {
        if (clip == null) return;
        if (playing) pause(); else play();
    }

    private void play() {
        if (clip == null || playing) return;
        if (time >= lastFrameTime() - 0.0005f) { time = 0f; }   // at the end: play again from the start
        playing = true;
        lastNanos = 0;
        playBtn.setGraphic(new FontIcon(MaterialDesignP.PAUSE_CIRCLE_OUTLINE));
        timer.start();
    }

    public void pause() {
        if (!playing) return;
        playing = false;
        timer.stop();
        lastNanos = 0;
        playBtn.setGraphic(new FontIcon(MaterialDesignP.PLAY_CIRCLE_OUTLINE));
        if (renderer != null && clip != null) renderer.updateTime(time, true);   // a seek stops the sound
        updateLabels();
    }

    /** The last time worth showing: one frame before the end (the end itself is past the clip). */
    private float lastFrameTime() {
        if (clip == null) return 0f;
        float frame = 1f / Math.max(1, fps.getAsInt());
        return Math.max(0f, clip.duration - frame);
    }

    private void show(float t, boolean seekingOnly) {
        if (renderer == null) return;
        renderer.updateTime(t, seekingOnly);
        if (!seekingOnly) updateLabels();
    }

    private void updateLabels() {
        timeLabel.setText(timecode(time));
        updatingSlider = true;
        seek.setValue(time);
        updatingSlider = false;
    }

    // ── Picture ─────────────────────────────────────────────────────────

    private void ensureRenderer() {
        if (renderer != null) return;
        renderer = new TimelineRenderer(project, settings);
        if (AppSettings.getInstance().isGpuPreview()) {
            renderer.enableGpuPreview(AppSettings.getInstance().isPreviewUseProxy());
        }
        pictureGroup.getChildren().add(renderer.getRenderPane());
        layoutPicture();
    }

    /** Fits the picture into the layer, as the project's own canvas does. */
    private void layoutPicture() {
        if (renderer == null) return;
        Bounds b = overlay.getLayoutBounds();
        double w = b.getWidth() - 32;
        double h = b.getHeight() - 32;
        if (w <= 0 || h <= 0) return;
        double scale = Math.min(w / settings.videoWidth, h / settings.videoHeight);
        renderer.getRenderPane().setScaleX(scale);
        renderer.getRenderPane().setScaleY(scale);
        pictureGroup.setLayoutX(b.getWidth() / 2.0 - settings.videoWidth / 2.0);
        pictureGroup.setLayoutY(b.getHeight() / 2.0 - settings.videoHeight / 2.0);
    }

    /** The project's canvas size changed: the source picture follows. */
    public void applyVideoSettings() {
        if (renderer == null) return;
        renderer.applyVideoSettings();
        layoutPicture();
    }

    private String timecode(float seconds) {
        int f = Math.max(1, fps.getAsInt());
        int h = (int) (seconds / 3600);
        int m = (int) ((seconds % 3600) / 60);
        int s = (int) (seconds % 60);
        int fr = Math.min(f - 1, (int) Math.floor((seconds - Math.floor(seconds)) * f + 1e-4));
        return String.format("%02d:%02d:%02d:%02d", h, m, s, fr);
    }

    /** The editor is closing. */
    public void shutdown() {
        pause();
        timer.stop();
        if (renderer != null) {
            renderer.shutdown();
            renderer = null;
        }
    }
}
