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

    /** Puts values straight into the clip (the gizmo's commit, undo/redo and cancel use this). */
    public static void writeProperties(Clip clip, VideoProperties props, int keyIndex) {
        VideoProperties copy = new VideoProperties(props);
        if (keyIndex >= 0 && keyIndex < clip.keyframes.keyframes.size()) {
            clip.keyframes.keyframes.get(keyIndex).value = copy;
        } else {
            clip.videoProperties = copy;
        }
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
