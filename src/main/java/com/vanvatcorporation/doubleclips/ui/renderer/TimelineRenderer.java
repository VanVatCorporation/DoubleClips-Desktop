package com.vanvatcorporation.doubleclips.ui.renderer;

import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;
import com.vanvatcorporation.doubleclips.data.editing.VideoProperties;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * The editor's preview. Two engines:
 * <ul>
 *   <li><b>GPU preview</b> (default): a {@link PreviewClient} asks the preview worker process for a
 *       composited frame, drawn by the same OpenGL code as the export. VIDEO and IMAGE clips are
 *       shown through one ImageView; the ClipRenderers then only play audio (and draw TEXT).</li>
 *   <li><b>Legacy</b>: every clip has its own JavaFX node (ClipRenderer). Used when the GPU preview
 *       is switched off, and as the automatic fallback if the worker can't run.</li>
 * </ul>
 */
public class TimelineRenderer {

    private final Pane renderPane;
    private List<List<ClipRenderer>> trackLayers = new ArrayList<>();
    
    private final ProjectData data;
    private final VideoSettings settings;

    // One mixer for all the preview audio (the ClipRenderers no longer open audio lines)
    private final AudioEngine audio = new AudioEngine(
            msg -> com.vanvatcorporation.doubleclips.manager.LoggingManager.LogToPersistentDataPath(msg));

    // GPU preview
    private PreviewClient client;
    private ImageView gpuView;
    private Timeline timeline;
    private float lastTime = 0f;

    public TimelineRenderer(ProjectData data, VideoSettings settings) {
        this.data = data;
        this.settings = settings;
        this.renderPane = new Pane();
        
        // Define fixed size for rendering pane
        this.renderPane.setPrefSize(settings.videoWidth, settings.videoHeight);
        this.renderPane.setMinSize(Pane.USE_PREF_SIZE, Pane.USE_PREF_SIZE);
        this.renderPane.setMaxSize(Pane.USE_PREF_SIZE, Pane.USE_PREF_SIZE);
        
        // Create a black background box for blank video areas
        Rectangle blackBox = new Rectangle(settings.videoWidth, settings.videoHeight, Color.BLACK);
        this.renderPane.getChildren().add(blackBox);
        
        // Clip content to the video bounds
        Rectangle clipRect = new Rectangle(settings.videoWidth, settings.videoHeight);
        this.renderPane.setClip(clipRect);
    }

    public Pane getRenderPane() {
        return renderPane;
    }

    // ── GPU preview ──────────────────────────────────────────────────────

    /** True while the GPU preview is the active engine (starting up counts: the picture appears when it is ready). */
    public boolean isGpuPreviewActive() {
        return client != null && client.isUsable();
    }

    /**
     * Starts the GPU preview. Call before the first buildTimeline, or call buildTimeline again
     * afterwards (this does, if a timeline is already built) so the clips drop their own pictures.
     */
    public void enableGpuPreview(boolean useProxy) {
        if (client != null) return;
        gpuUseProxy = useProxy;
        client = new PreviewClient(new PreviewClient.Listener() {
            @Override public void onReady() { showGpuView(); }
            @Override public void onFrame() {
                // The client alternates between two images (see PreviewClient); show the finished one.
                if (gpuView != null && client != null && client.image() != null) gpuView.setImage(client.image());
            }
            @Override public void onFailed(String reason) { fallBackToLegacy(); }
        }, settings.videoWidth, settings.videoHeight, settings.frameRate);
        // Hardware decoding is its own preview setting (off by default), NOT the export's useHardwareAccel:
        // seeking into the middle of a clip with a hardware decoder is what produced noisy / corrupt frames.
        client.start(data.getProjectPath(), settings.isStretchToFull(),
                com.vanvatcorporation.doubleclips.data.AppSettings.getInstance().isPreviewHardwareDecode(), useProxy,
                settings.videoWidth, settings.videoHeight);
        if (timeline != null) buildTimeline(timeline);
    }

    private boolean gpuUseProxy;

    /**
     * The project's canvas size or frame rate changed (the editor's video settings): resizes the picture area (black box,
     * clip, GPU picture) and, since the preview worker's canvas and timing are fixed when it starts, starts a new one at the
     * new size. The timeline is rebuilt either way.
     */
    public void applyVideoSettings() {
        renderPane.setPrefSize(settings.videoWidth, settings.videoHeight);
        if (!renderPane.getChildren().isEmpty() && renderPane.getChildren().get(0) instanceof Rectangle black) {
            black.setWidth(settings.videoWidth);
            black.setHeight(settings.videoHeight);
        }
        if (renderPane.getClip() instanceof Rectangle clipRect) {
            clipRect.setWidth(settings.videoWidth);
            clipRect.setHeight(settings.videoHeight);
        }
        if (client != null) {
            PreviewClient old = client;
            client = null;
            old.close();
            removeGpuView();
            enableGpuPreview(gpuUseProxy); // a new worker at the new size; it rebuilds the timeline and shows the frame when ready
        } else if (timeline != null) {
            buildTimeline(timeline);
            updateTime(lastTime, true);
        }
    }

    /** Switches back to the per-clip JavaFX preview (user turned the GPU preview off). */
    public void disableGpuPreview() {
        if (client == null) return;
        PreviewClient old = client;
        client = null;
        old.close();
        removeGpuView();
        if (timeline != null) {
            buildTimeline(timeline);
            updateTime(lastTime, true);
        }
    }

    /** Proxy clips on/off for the GPU preview (the legacy preview is unaffected). */
    public void setUseProxy(boolean useProxy) {
        gpuUseProxy = useProxy;
        if (client == null || !client.isUsable()) return;
        client.setUseProxy(useProxy);
        client.requestFrame(timeline, lastTime, false);
    }

    /** Hardware decoding on/off for the GPU preview (takes effect on the next stream that is opened). */
    public void setHardwareDecode(boolean on) {
        if (client == null || !client.isUsable()) return;
        client.setHardwareDecode(on);
        client.requestFrame(timeline, lastTime, false);
    }

    private javafx.event.EventHandler<javafx.scene.input.MouseEvent> colorPickFilter;

    /**
     * The eyedropper: the next press on the preview calls {@code onPicked} with the colour of the pixel under it (the frame
     * the preview shows, every layer composited) and ends the pick; the click does nothing else. False when the preview has
     * no picture to read (the legacy renderer), nothing is waiting then.
     */
    public boolean pickColor(java.util.function.Consumer<Color> onPicked) {
        cancelColorPick();
        if (client == null || client.image() == null) return false;
        renderPane.setCursor(javafx.scene.Cursor.CROSSHAIR);
        colorPickFilter = ev -> {
            ev.consume();
            if (ev.getEventType() != javafx.scene.input.MouseEvent.MOUSE_PRESSED) return;
            javafx.scene.image.WritableImage frame = client == null ? null : client.image();
            cancelColorPick();
            if (frame == null || settings.videoWidth <= 0 || settings.videoHeight <= 0) return;
            // the pane is in canvas pixels; the picture may be smaller (the preview can render at a lower size)
            int x = (int) Math.floor(ev.getX() * frame.getWidth() / settings.videoWidth);
            int y = (int) Math.floor(ev.getY() * frame.getHeight() / settings.videoHeight);
            if (x < 0 || y < 0 || x >= frame.getWidth() || y >= frame.getHeight()) return;
            onPicked.accept(frame.getPixelReader().getColor(x, y));
        };
        renderPane.addEventFilter(javafx.scene.input.MouseEvent.ANY, colorPickFilter);
        return true;
    }

    public void cancelColorPick() {
        if (colorPickFilter != null) {
            renderPane.removeEventFilter(javafx.scene.input.MouseEvent.ANY, colorPickFilter);
            colorPickFilter = null;
        }
        renderPane.setCursor(null);
    }

    private void showGpuView() {
        if (client == null || client.image() == null) return;
        removeGpuView();
        gpuView = new ImageView(client.image());
        gpuView.setFitWidth(settings.videoWidth);
        gpuView.setFitHeight(settings.videoHeight);
        gpuView.setPreserveRatio(false);
        gpuView.setSmooth(true);
        gpuView.setMouseTransparent(true);
        renderPane.getChildren().add(1, gpuView); // above the black box, below the clips' own nodes (text)
        client.requestFrame(timeline, lastTime, false);
    }

    private void removeGpuView() {
        if (gpuView != null) {
            renderPane.getChildren().remove(gpuView);
            gpuView = null;
        }
    }

    private void fallBackToLegacy() {
        if (client == null) return;
        client = null; // the failed client has already torn itself down
        removeGpuView();
        if (timeline != null) {
            buildTimeline(timeline);
            updateTime(lastTime, true);
        }
    }

    // ── gesture support (used by PreviewGizmo) ───────────────────────────

    /** The clip's own picture bounds in canvas pixels (TEXT only, see ClipRenderer), or null. */
    public javafx.geometry.Bounds getClipViewBounds(Clip clip) {
        for (List<ClipRenderer> track : trackLayers) {
            for (ClipRenderer cr : track) {
                if (cr.clip == clip) return cr.getViewBoundsInPane();
            }
        }
        return null;
    }

    /**
     * Shows in-progress values for a clip WITHOUT committing them: {@code keyIndex} >= 0 means they
     * belong to that keyframe, otherwise to the clip's static properties. Under the GPU preview they
     * are streamed to the worker (the real clip is untouched until the gesture ends); the legacy
     * preview has no other way to show them, so the values are written into the clip right away and
     * the gizmo restores them if the gesture is cancelled.
     */
    public void showLiveProperties(Clip clip, VideoProperties props, int keyIndex, float time) {
        lastTime = time;
        if (streamsLiveToWorker(clip)) {
            int clipIndex = timeline.tracks.get(clip.trackIndex).clips.indexOf(clip);
            client.requestLiveFrame(clip.trackIndex, clipIndex, keyIndex, props, time);
        } else {
            writeProperties(clip, props, keyIndex);
            updateTime(time, true);
        }
    }

    /** The GPU preview worker for the picker tiles (they are drawn by the real renderer), or null while there is none. */
    public PreviewClient tileClient() {
        PreviewClient c = client;
        return c != null && c.isUsable() ? c : null;
    }

    /**
     * Shows an in-progress mask edit for a clip WITHOUT committing it: {@code masks} is the clip's whole mask list as
     * the gesture would commit it. Under the GPU preview it is streamed to the worker (the real clip is untouched
     * until the gesture ends); the legacy preview cannot draw masks at all, so there it is only stored in the clip.
     */
    public void showLiveMasks(Clip clip, java.util.List<com.vanvatcorporation.doubleclips.data.editing.ClipMask> masks, float time) {
        lastTime = time;
        if (streamsLiveToWorker(clip)) {
            int clipIndex = timeline.tracks.get(clip.trackIndex).clips.indexOf(clip);
            client.requestLiveMasks(clip.trackIndex, clipIndex, masks, time);
        } else {
            clip.masks = masks == null || masks.isEmpty() ? null : masks;
            updateTime(time, true);
        }
    }

    /**
     * Shows an in-progress chroma-key slider drag for a clip WITHOUT committing it: {@code keys} is the clip's whole key
     * list as the drag would commit it, streamed to the preview worker (the real clip is untouched until the slider is let
     * go). The legacy preview can't draw chroma keys, so there it does nothing.
     */
    public void showLiveChromaKeys(Clip clip, java.util.List<com.vanvatcorporation.doubleclips.data.editing.ChromaKey> keys, float time) {
        if (!streamsLiveToWorker(clip)) return;
        lastTime = time;
        int clipIndex = timeline.tracks.get(clip.trackIndex).clips.indexOf(clip);
        if (clipIndex < 0) return;
        client.requestLiveChroma(clip.trackIndex, clipIndex, keys, time);
    }

    /** Puts values straight into the clip (the gizmo's commit, undo/redo and cancel use this). */
    public static void writeProperties(Clip clip, VideoProperties props, int keyIndex) {
        // Each property goes where it lives: into that keyframe when it holds it, into the static
        // properties when it is not animated (see AnimatedProperty#writeLook).
        clip.keyframes.writeLook(clip, new VideoProperties(props), keyIndex);
    }

    /**
     * The clip data changed under the worker's feet (a gesture committed, was cancelled, or inserted
     * a keyframe): re-sends the real timeline and redraws.
     */
    public void syncWorker(float time) {
        if (client != null) client.invalidateTimeline();
        updateTime(time, true);
    }

    private boolean streamsLiveToWorker(Clip clip) {
        return isGpuPreviewActive() && client.image() != null
                && (clip.type == com.vanvatcorporation.doubleclips.data.editing.ClipType.VIDEO
                || clip.type == com.vanvatcorporation.doubleclips.data.editing.ClipType.IMAGE
                || clip.type == com.vanvatcorporation.doubleclips.data.editing.ClipType.TEXT);
    }

    // ── timeline ─────────────────────────────────────────────────────────

    public void buildTimeline(Timeline timeline) {
        this.timeline = timeline;
        release();
        audio.setTimeline(timeline, data);

        // Clear everything except the black box (and the GPU picture, which sits right above it)
        if (gpuView != null) {
            renderPane.getChildren().retainAll(renderPane.getChildren().get(0), gpuView);
        } else {
            renderPane.getChildren().retainAll(renderPane.getChildren().get(0));
        }
        trackLayers.clear();

        boolean gpu = isGpuPreviewActive();
        if (gpu) client.markTimelineDirty();

        for (Track track : timeline.tracks) {
            List<ClipRenderer> renderers = new ArrayList<>();
            for (Clip clip : track.clips) {
                switch (clip.type) {
                    case VIDEO:
                    case AUDIO:
                    case IMAGE:
                    case TEXT:
                    case EFFECT:
                        ClipRenderer clipRenderer = new ClipRenderer(clip, data, settings, renderPane, gpu, false);
                        renderers.add(clipRenderer);
                        break;
                    default:
                        break;
                }
            }
            trackLayers.add(renderers);
        }
    }

    /** "Mute project": every clip's sound off while the preview clock keeps running (voiceover recording). */
    public void setProjectMuted(boolean muted) {
        audio.setProjectMuted(muted);
    }

    /** The speaker mix of [t0, t0 + seconds) as mono samples at {@code outRate}; background thread only. */
    public float[] renderAudioReference(double t0, double seconds, int outRate) {
        return AudioEngine.renderReference(timeline, data, t0, seconds, outRate);
    }

    public void updateTime(float time, boolean isSeekingOnly) {
        lastTime = time;
        audio.update(time, !isSeekingOnly);
        if (isGpuPreviewActive()) {
            client.requestFrame(timeline, time, !isSeekingOnly);
        }
        for (List<ClipRenderer> trackRenderer : trackLayers) {
            for (ClipRenderer clipRenderer : trackRenderer) {
                if (clipRenderer != null) {
                    clipRenderer.renderFrame(time, isSeekingOnly);
                }
            }
        }
    }

    public void release() {
        for (List<ClipRenderer> track : trackLayers) {
            for (ClipRenderer cr : track) {
                cr.release();
            }
        }
        trackLayers.clear();
    }

    /** Editor is closing: stop the clips and the preview worker process. */
    public void shutdown() {
        audio.shutdown();
        release();
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
