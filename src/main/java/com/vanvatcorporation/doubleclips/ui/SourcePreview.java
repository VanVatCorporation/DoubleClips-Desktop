package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.AppSettings;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;
import com.vanvatcorporation.doubleclips.history.PlaceClipCommand;
import com.vanvatcorporation.doubleclips.ui.components.SourceSeekBar;
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
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

/**
 * "Source" view of the preview: clicking a file in the Media tab shows it here, on its own, with its own play button
 * and seek bar. The project's timeline, playhead and preview are not touched; the "Project" tab goes back to them.
 *
 * Part 2 (pre-trim): the seek bar has in / out arrows (and I / O keys), and "Add to Timeline" puts the chosen part of the
 * file on the timeline at the project's playhead (three-point editing: in + out in the source, the playhead is the third
 * point). The picture can also be dragged onto the timeline: it carries the chosen part.
 *
 * The picture comes from a second {@link TimelineRenderer} that holds a one-clip timeline (a copy of the file's clip,
 * untrimmed), so every format the project preview can play is shown the same way here, with its sound.
 * The renderer is created on the first click, not when the editor opens. Its canvas has the FILE's own aspect ratio (the
 * clip's width / height from the import probe, already swapped for a rotated video), not the project's.
 */
public class SourcePreview {

    /** What the editor gives the Source view. */
    public interface Host {
        /** Frames per second of the project (the seek bar and the in / out points snap to it). */
        int fps();

        /** True when the Source view came up, false when the project's own preview is back. */
        void modeChanged(boolean source);

        /** Puts {@code clip} (already cut to the chosen range) on the timeline at the project's playhead. */
        void addToTimeline(Clip clip, PlaceClipCommand.Mode mode);

        /** The range remembered for a file ({in, out} in seconds of the file), or null for the whole file. */
        float[] savedRange(String clipName);

        /** The user changed a file's range. {@code whole} = back to the whole file (forget the range). */
        void rangeChanged(String clipName, float in, float out, boolean whole);
    }

    private final ProjectData project;
    private final VideoSettings projectSettings;   // the project's canvas: only its size and frame rate are looked at
    private final VideoSettings settings;          // this view's own canvas: the file's aspect ratio, see fitCanvasTo
    private final Host host;

    private TimelineRenderer renderer;
    private Clip clip;            // the untrimmed copy on the one-clip timeline
    private float time = 0f;
    private float in = 0f;        // the chosen part of the file, in seconds of the file
    private float out = 0f;
    private boolean sourceMode = false;
    private boolean playing = false;
    private long lastNanos = 0;

    // The layer over the project's picture (and its gizmo) while the Source tab is on
    private final StackPane overlay = new StackPane();
    private final Group pictureGroup = new Group();
    private final Label audioLabel = new Label();

    // Under the picture: time, play, add; the seek bar; the chosen range
    private final VBox controls = new VBox(6);
    private final Label timeLabel = new Label("00:00:00:00");
    private final Label durationLabel = new Label("00:00:00:00");
    private final Button playBtn = new Button();
    private final SourceSeekBar seek = new SourceSeekBar();
    private final Label rangeLabel = new Label();
    private final Button resetBtn = new Button("Reset");
    private final SplitMenuButton addBtn = new SplitMenuButton();

    // Source | Project tabs, below the controls (like Shotcut)
    private final HBox tabs = new HBox(0);
    private final ToggleButton sourceTab = new ToggleButton("Source");
    private final ToggleButton projectTab = new ToggleButton("Project");

    private final AnimationTimer timer = new AnimationTimer() {
        @Override public void handle(long now) {
            if (lastNanos > 0 && clip != null) {
                time += (now - lastNanos) / 1_000_000_000f;
                float end = playEnd();
                if (time >= end) {
                    time = end;
                    pause();   // reached the out point: stop on its last frame
                    return;
                }
                show(time, false);
                updateBar();
            }
            lastNanos = now;
        }
    };

    public SourcePreview(ProjectData project, VideoSettings projectSettings, Host host) {
        this.project = project;
        this.projectSettings = projectSettings;
        this.host = host;
        // Starts as a copy of the project's canvas (kept for a file with no picture); each file then reshapes it
        this.settings = new VideoSettings(projectSettings.videoWidth, projectSettings.videoHeight, projectSettings.frameRate,
                projectSettings.crf, projectSettings.clipCap, projectSettings.preset, projectSettings.tune);
        this.settings.isStretchToFull = false;   // the file fits its own canvas exactly
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
        overlay.setCursor(javafx.scene.Cursor.OPEN_HAND);   // the picture can be dragged onto the timeline
        ChangeListener<Bounds> relayout = (o, a, b) -> layoutPicture();
        overlay.layoutBoundsProperty().addListener(relayout);

        // Row 1: time, play, reset / add
        timeLabel.getStyleClass().add("timecode-label");
        durationLabel.getStyleClass().add("timecode-label");
        Label sep = new Label("/");
        sep.setStyle("-fx-text-fill: -color-fg-muted;");
        playBtn.setGraphic(new FontIcon(MaterialDesignP.PLAY_CIRCLE_OUTLINE));
        playBtn.getStyleClass().addAll("button-transparent", "play-button-main");
        playBtn.setOnAction(e -> togglePlay());
        Region left = new Region();
        Region right = new Region();
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        resetBtn.setTooltip(new Tooltip("Use the whole file again (Esc)"));
        resetBtn.setOnAction(e -> resetRange());
        addBtn.setText("Add to Timeline");
        addBtn.setTooltip(new Tooltip("Puts the chosen part at the playhead and leaves what is there as it is"));
        addBtn.setOnAction(e -> add(PlaceClipCommand.Mode.OVERLAP));
        MenuItem overlap = new MenuItem("Overlap at playhead (default)");
        overlap.setOnAction(e -> add(PlaceClipCommand.Mode.OVERLAP));
        MenuItem overwrite = new MenuItem("Overwrite at playhead (replaces what is there)");
        overwrite.setOnAction(e -> add(PlaceClipCommand.Mode.OVERWRITE));
        MenuItem insert = new MenuItem("Insert at playhead (moves what follows)");
        insert.setOnAction(e -> add(PlaceClipCommand.Mode.INSERT));
        addBtn.getItems().addAll(overlap, overwrite, insert);
        addBtn.setDisable(true);
        HBox row1 = new HBox(12, timeLabel, sep, durationLabel, left, playBtn, right, resetBtn, addBtn);
        row1.setAlignment(Pos.CENTER);

        // Row 2: seek bar with the in / out arrows
        seek.setMaxWidth(Double.MAX_VALUE);
        seek.setOnSeek(v -> {
            if (clip == null) return;
            time = (float) v;
            lastNanos = 0;                     // a drag while playing keeps playing from the new spot
            show(time, true);
            timeLabel.setText(timecode(time));
        });
        seek.setOnRange(() -> {
            if (clip == null) return;
            in = (float) seek.getIn();
            out = (float) seek.getOut();
            rangeEdited();
        });

        // Row 3: what is chosen
        rangeLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
        rangeLabel.setMaxWidth(Double.MAX_VALUE);
        rangeLabel.setAlignment(Pos.CENTER);

        controls.getChildren().addAll(row1, seek, rangeLabel);
        controls.setPadding(new Insets(10, 16, 8, 16));
        controls.getStyleClass().add("playback-controls");
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

    /** The layer to put on top of the preview canvas. It is also what is dragged onto the timeline. */
    public Node overlay() { return overlay; }

    /** The source's own playback rows; the editor puts them right under its own one. */
    public Node controls() { return controls; }

    /** The Source | Project tab strip. */
    public Node tabs() { return tabs; }

    public boolean isActive() { return sourceMode; }

    // ── Showing a file ──────────────────────────────────────────────────

    /** Shows {@code mediaClip} (a video, audio or image tile of the Media tab) in the Source view, stopped at its in point. */
    public void open(Clip mediaClip) {
        if (mediaClip == null) return;
        if (mediaClip.type != ClipType.VIDEO && mediaClip.type != ClipType.AUDIO && mediaClip.type != ClipType.IMAGE) return;
        pause();

        Clip c = new Clip(mediaClip);
        c.startTime = 0f;
        c.trackIndex = 0;
        c.startClipTrim = 0f;      // the whole file; the range is applied when it is added
        c.endClipTrim = 0f;
        if (c.originalDuration > 0f) c.duration = c.originalDuration;
        if (c.duration <= 0f) c.duration = com.vanvatcorporation.doubleclips.data.AppSettings.getInstance().getDefaultClipDuration();
        clip = c;

        fitCanvasTo(c);
        ensureRenderer();
        Timeline tl = new Timeline();
        Track track = new Track();
        tl.addTrack(track);
        track.addClip(c);
        tl.duration = c.duration;
        renderer.buildTimeline(tl);

        // The range remembered for this file, else the whole file
        in = 0f;
        out = c.duration;
        float[] saved = trimmable() ? host.savedRange(c.getClipName()) : null;
        if (saved != null && saved.length == 2 && saved[0] >= 0f && saved[1] <= c.duration + 0.001f && saved[1] - saved[0] >= frame()) {
            in = saved[0];
            out = Math.min(saved[1], c.duration);
        }
        time = in;

        audioLabel.setText(c.getClipName());
        audioLabel.setVisible(c.type == ClipType.AUDIO);
        durationLabel.setText(timecode(c.duration));
        seek.setFrame(frame());
        seek.setMax(c.duration);
        seek.setTrimEnabled(trimmable());
        resetBtn.setVisible(trimmable());
        resetBtn.setManaged(trimmable());
        addBtn.setDisable(false);
        updateBar();
        sourceTab.setDisable(false);
        showSource();
        show(time, true);
    }

    private void showSource() {
        if (clip == null) return;
        sourceMode = true;
        sourceTab.setSelected(true);
        overlay.setVisible(true);
        controls.setVisible(true);
        controls.setManaged(true);
        layoutPicture();
        host.modeChanged(true);
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
        host.modeChanged(false);
    }

    // ── In / out ────────────────────────────────────────────────────────

    /** A video or an audio file can be cut; a picture has no length of its own. */
    private boolean trimmable() {
        return clip != null && (clip.type == ClipType.VIDEO || clip.type == ClipType.AUDIO) && clip.originalDuration > 0f;
    }

    private float frame() { return 1f / Math.max(1, host.fps()); }

    private float snap(float t) { return Math.round(t / frame()) * frame(); }

    /** I: the chosen part starts here. If that is at or past the out point, the out point goes back to the end. */
    public void markIn() {
        if (!trimmable()) return;
        in = Math.max(0f, Math.min(snap(time), clip.duration - frame()));
        if (out <= in + frame() / 2f) out = clip.duration;
        rangeEdited();
    }

    /** O: the chosen part ends with the frame the playhead is on. If that is at or before the in point, the in point goes back to 0. */
    public void markOut() {
        if (!trimmable()) return;
        out = Math.min(clip.duration, snap(time) + frame());   // the frame under the playhead is included
        if (in >= out - frame() / 2f) in = 0f;
        rangeEdited();
    }

    /** Esc / Reset: the whole file again. */
    public void resetRange() {
        if (!trimmable()) return;
        in = 0f;
        out = clip.duration;
        rangeEdited();
    }

    private boolean isWhole() {
        return !trimmable() || (in <= 0.0005f && out >= clip.duration - 0.0005f);
    }

    /** The range changed (arrows, keys, reset): redraw, and tell the editor so the Media tab remembers it. */
    private void rangeEdited() {
        updateBar();
        if (clip != null && trimmable()) host.rangeChanged(clip.getClipName(), in, out, isWhole());
    }

    /**
     * Key presses while the Source view is up: I and O set the in and out points, Esc clears them.
     * Returns true when the key was used.
     */
    public boolean handleKey(KeyEvent e) {
        if (!sourceMode || e.isShortcutDown() || e.isAltDown() || e.isControlDown() || e.isMetaDown()) return false;
        if (e.getCode() == KeyCode.I) { markIn(); return true; }
        if (e.getCode() == KeyCode.O) { markOut(); return true; }
        if (e.getCode() == KeyCode.ESCAPE && trimmable() && !isWhole()) { resetRange(); return true; }
        return false;
    }

    // ── Handing the chosen part to the timeline ─────────────────────────

    /**
     * A new clip that is the chosen part of the file (start 0, track 0), or null when nothing is loaded. A picture is
     * handed over whole.
     */
    public Clip chosenClip() {
        if (clip == null) return null;
        Clip c = new Clip(clip);
        c.startTime = 0f;
        c.trackIndex = 0;
        if (trimmable()) applyRange(c, in, out);
        return c;
    }

    /** Cuts {@code c} (a video or audio clip with its whole file) to [in, out] seconds of the file. */
    public static void applyRange(Clip c, float in, float out) {
        if ((c.type != ClipType.VIDEO && c.type != ClipType.AUDIO) || c.originalDuration <= 0f) return;
        c.setStartClipTrim(Math.max(0f, in));
        c.setEndClipTrim(Math.max(0f, c.originalDuration - out));
    }

    private void add(PlaceClipCommand.Mode mode) {
        Clip c = chosenClip();
        if (c == null) return;
        pause();
        host.addToTimeline(c, mode);
    }

    // ── Playback ────────────────────────────────────────────────────────

    public void togglePlay() {
        if (clip == null) return;
        if (playing) pause(); else play();
    }

    private void play() {
        if (clip == null || playing) return;
        // Play the chosen part: from the in point, unless the playhead is already inside it
        if (time < in - 0.0005f || time >= playEnd() - 0.0005f) time = in;
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
        updateBar();
    }

    /** Where playing stops: the last frame of the chosen part. */
    private float playEnd() {
        if (clip == null) return 0f;
        return Math.max(in, out - frame());
    }

    private void show(float t, boolean seekingOnly) {
        if (renderer == null) return;
        renderer.updateTime(t, seekingOnly);
    }

    private void updateBar() {
        timeLabel.setText(timecode(time));
        seek.setPosition(time);
        seek.setRange(in, out);
        if (clip == null || !trimmable()) { rangeLabel.setText(""); return; }
        rangeLabel.setText("In " + timecode(in) + "   Out " + timecode(out) + "   Length " + timecode(out - in)
                + (isWhole() ? "   (whole file)" : ""));
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

    /**
     * Gives this view's canvas the aspect ratio of {@code c}: the clip's width / height (from the import probe, so a video
     * recorded sideways is already the right way round). The long side is the project canvas's long side, but never more
     * than the file itself (a small file is not blown up); both sides are even. A file with no picture (sound) or no known
     * size gets the project's canvas. If the renderer exists and the size or frame rate changed, it is resized (the preview
     * worker restarts at the new size).
     */
    private void fitCanvasTo(Clip c) {
        int w = projectSettings.videoWidth, h = projectSettings.videoHeight;
        if (c.type != ClipType.AUDIO && c.width > 0 && c.height > 0) {
            double budget = Math.max(projectSettings.videoWidth, projectSettings.videoHeight);
            double k = Math.min(1.0, budget / Math.max(c.width, c.height));
            w = Math.max(2, ((int) Math.round(c.width * k)) & ~1);
            h = Math.max(2, ((int) Math.round(c.height * k)) & ~1);
        }
        boolean changed = w != settings.videoWidth || h != settings.videoHeight || settings.frameRate != projectSettings.frameRate;
        settings.videoWidth = w;
        settings.videoHeight = h;
        settings.frameRate = projectSettings.frameRate;
        if (changed && renderer != null) {
            renderer.applyVideoSettings();
            layoutPicture();
        }
    }

    /** The project's canvas size changed: the long side this view uses follows. */
    public void applyVideoSettings() {
        if (clip != null) fitCanvasTo(clip);
    }

    /** The project's frame rate changed: the seek bar's frame step and the preview's frame rate follow. */
    public void frameRateChanged() {
        seek.setFrame(frame());
        if (clip != null) fitCanvasTo(clip);
    }

    private String timecode(float seconds) {
        int f = Math.max(1, host.fps());
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
