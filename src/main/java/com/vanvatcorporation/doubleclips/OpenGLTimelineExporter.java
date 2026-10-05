package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.AnimationClip;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The per-frame export loop, with no OpenGL and no LWJGL in it. Desktop
 * counterpart of Android's OpenGLEditNative.exportTimeline(), and written to
 * follow its rules one for one:
 * <ul>
 *   <li>each output frame asks {@link OpenGLEdit} what to draw (transform, opacity,
 *       colour grading, in/out-animation warp and blur, source time), then draws
 *       those layers in track order - a plain clip straight onto the canvas, a clip
 *       with an animation blur via two blur passes, and a transition by rendering
 *       both sides into their own full-canvas layers and blending those;</li>
 *   <li>a clip's decoder is opened lazily on its first active frame and released
 *       as soon as the clip is no longer active;</li>
 *   <li>a clip that can't be opened, or never yields a frame, is reported ONCE and
 *       skipped (not once per frame);</li>
 *   <li>VIDEO and IMAGE clips are rendered; reversed clips decode from the
 *       pre-reversed intermediate supplied in {@code reversedClipPaths}.</li>
 * </ul>
 * The GPU work is behind {@link Compositor}. {@link OpenGLExportWorker} supplies the
 * real LWJGL one; a plain-Java compositor can drive this same loop on a machine
 * with no GPU, which is how the timing / seeking / speed / reverse / image logic is
 * tested.
 */
public final class OpenGLTimelineExporter {

    private OpenGLTimelineExporter() {}

    /** A GPU texture owned by the compositor; opaque to the loop. */
    public interface Layer {}

    /** The GPU half. All methods are called from the single render thread. */
    public interface Compositor {
        /** Allocates a texture for a clip of this size. */
        Layer createLayer(int width, int height);

        void destroyLayer(Layer layer);

        /** Clears the canvas to opaque black and sets blending up, once per output frame. */
        void beginFrame();

        /**
         * Draws one clip. {@code newPixels} is non-null only when the layer's
         * contents changed since it was last drawn (top row first, RGBA,
         * width*height*4 bytes); otherwise the layer already holds the right frame.
         */
        void draw(Layer layer, ByteBuffer newPixels, OpenGLEdit.DrawCommand command);

        /**
         * Redirects {@link #draw} into scratch canvas {@code slot} (0 or 1), cleared to
         * transparent. Each scratch canvas is the size of the output canvas. Used for
         * the two sides of a transition and for the clip a blur is applied to.
         */
        void beginOffscreen(int slot);

        /** Redirects {@link #draw} back onto the main canvas. */
        void endOffscreen();

        /**
         * Blends scratch canvases {@code slotA} (outgoing) and {@code slotB} (incoming)
         * onto the main canvas. {@code style} is one of
         * {@link OpenGLEdit#SUPPORTED_TRANSITION_STYLES}; progress runs 0..1.
         * Leaves drawing pointed at the main canvas.
         */
        void blendTransition(int slotA, int slotB, float progress, String style);

        /**
         * Gaussian-blurs scratch canvas {@code sourceSlot} (sigma in output pixels) and
         * composites the result onto the main canvas, using {@code tempSlot} for the
         * horizontal pass. Leaves drawing pointed at the main canvas.
         */
        void blurOntoMain(int sourceSlot, int tempSlot, float sigmaPixels);

        /** Reads the finished canvas back: RGBA, rows BOTTOM-first (glReadPixels order). */
        void readFrame(ByteBuffer out);
    }

    /** Draws one clip's DrawCommand into whatever the compositor is pointed at; true if something was drawn. */
    public interface ClipDrawer {
        boolean draw(OpenGLEdit.DrawCommand command, float outputTimeSeconds);
    }

    /**
     * Composites one output frame: clears the canvas, then draws every FrameLayer in EXACT order.
     * Shared by the export and the live preview worker, so both use the same layering, transition
     * and blur rules and can't drift apart. {@code blurScale} converts the blur sigma, which
     * OpenGLEdit computes in full-canvas pixels, to the target's pixels (1 for export; preview
     * renders into a smaller target than the project canvas).
     */
    public static void drawFrameLayers(Compositor compositor, List<OpenGLEdit.FrameLayer> layers,
                                       float outputTimeSeconds, float blurScale, ClipDrawer drawer) {
        compositor.beginFrame();

        // Draw in EXACT layer order: it is the track stacking order, and plain,
        // blurred and transitioning layers must interleave correctly rather than
        // be grouped into separate passes (see OpenGLEdit.computeFrameForTimestamp).
        for (OpenGLEdit.FrameLayer layer : layers) {
            if (layer.simpleDraw != null) {
                OpenGLEdit.DrawCommand cmd = layer.simpleDraw;
                if (cmd.blurSigmaPixels > 0f) {
                    // In-animation blur: the clip goes into a scratch canvas, which is
                    // blurred horizontally then vertically; the vertical pass composites
                    // straight onto the main canvas.
                    compositor.beginOffscreen(0);
                    boolean drew = drawer.draw(cmd, outputTimeSeconds);
                    compositor.endOffscreen();
                    if (drew) compositor.blurOntoMain(0, 1, cmd.blurSigmaPixels * blurScale);
                } else {
                    drawer.draw(cmd, outputTimeSeconds);
                }
                continue;
            }
            // Transition: each side is rendered to its own full-canvas transparent
            // layer, then those two layers - NOT the raw clip textures - are blended.
            // That is what makes it match FFmpeg's xfade. A side that fails to open
            // is simply left transparent (reported once by the drawer).
            OpenGLEdit.TransitionCommand transition = layer.transition;
            compositor.beginOffscreen(0);
            drawer.draw(transition.clipACommand, outputTimeSeconds);
            compositor.beginOffscreen(1);
            drawer.draw(transition.clipBCommand, outputTimeSeconds);
            compositor.endOffscreen();
            compositor.blendTransition(0, 1, transition.progress, transition.style);
        }
    }

    public interface Listener {
        void onLog(String message);

        /** frameIndex counts from 0; totalFrames is the whole export. */
        void onProgress(int frameIndex, int totalFrames);
    }

    /** True when the export should stop at the next frame boundary. */
    public interface CancelSignal {
        boolean isCancelled();

        CancelSignal NEVER = () -> false;

        static CancelSignal fileExists(Path flagFile) {
            return () -> Files.exists(flagFile);
        }
    }

    private static final class VideoLayer {
        final OpenGLFrameIO.ClipFrameSource source;
        final Layer layer;
        boolean uploadedOnce = false;

        VideoLayer(OpenGLFrameIO.ClipFrameSource source, Layer layer) {
            this.source = source;
            this.layer = layer;
        }
    }

    private static final class ImageLayer {
        final Layer layer;
        ByteBuffer pixelsUntilUploaded;

        ImageLayer(Layer layer, ByteBuffer pixels) {
            this.layer = layer;
            this.pixelsUntilUploaded = pixels;
        }
    }

    /**
     * Renders the whole timeline into {@code outputPath} (video only).
     *
     * @param reversedClipPaths clip -> pre-reversed intermediate to decode instead of
     *                          the clip's own file. Identity-keyed (Clip has no equals).
     *                          Never null; pass an empty map if there are none.
     * @return true if every frame was written, false if it stopped early because of
     *         {@code cancel}. Real failures throw.
     */
    public static boolean export(Timeline timeline, OpenGLEdit edit, ProjectData projectData,
                                 Compositor compositor, int width, int height, int frameRate,
                                 boolean stretchToFull, Map<Clip, String> reversedClipPaths,
                                 String ffmpegPath, String encoderArgs, String outputPath,
                                 CancelSignal cancel, Listener listener) throws IOException, InterruptedException {
        float timelineDuration = timeline != null ? timeline.duration : 0f;
        int totalFrames = (int) Math.max(1, Math.ceil(timelineDuration * frameRate - 1e-6));
        log(listener, "Compositing " + totalFrames + " frames");

        Map<Clip, VideoLayer> videoLayers = new IdentityHashMap<>();
        Map<Clip, ImageLayer> imageLayers = new IdentityHashMap<>();
        Set<Clip> reported = Collections.newSetFromMap(new IdentityHashMap<>());
        ClipRenderer renderer = new ClipRenderer(compositor, projectData, ffmpegPath, frameRate, width,
                reversedClipPaths, videoLayers, imageLayers, reported, listener);

        prepareClipAnimations(timeline, listener);

        OpenGLFrameIO.VideoEncoder encoder = new OpenGLFrameIO.VideoEncoder(
                ffmpegPath, width, height, frameRate, encoderArgs, outputPath);
        ByteBuffer pixelBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());

        int frameIndex = 0;
        boolean completed = false;
        try {
            for (; frameIndex < totalFrames; frameIndex++) {
                if (cancel.isCancelled()) {
                    log(listener, "Export cancelled");
                    break;
                }

                float outputTimeSeconds = (float) (frameIndex / (double) frameRate);
                List<OpenGLEdit.FrameLayer> layers =
                        edit.computeFrameForTimestamp(timeline, outputTimeSeconds, width, height, stretchToFull);

                // Release layers whose clip is no longer active. Clips don't recur
                // within a track, so once inactive they are done for good. Both sides
                // of a transition count as active.
                Set<Clip> stillActive = Collections.newSetFromMap(new IdentityHashMap<>());
                for (OpenGLEdit.FrameLayer layer : layers) {
                    if (layer.simpleDraw != null) {
                        stillActive.add(layer.simpleDraw.clip);
                    } else {
                        stillActive.add(layer.transition.clipACommand.clip);
                        stillActive.add(layer.transition.clipBCommand.clip);
                    }
                }
                releaseInactive(videoLayers, stillActive, compositor);
                releaseInactiveImages(imageLayers, stillActive, compositor);

                drawFrameLayers(compositor, layers, outputTimeSeconds, 1f, renderer::render);

                compositor.readFrame(pixelBuffer);
                try {
                    encoder.writeFrame(pixelBuffer);
                } catch (IOException e) {
                    // The pipe closing means the encoder died; its own stderr says why.
                    String detail = encoder.errorTail();
                    throw new IOException("Encoder stopped accepting frames at frame " + frameIndex
                            + (detail.isEmpty() ? "" : ":\n" + detail), e);
                }

                if (listener != null) {
                    if (frameIndex % 5 == 0) listener.onProgress(frameIndex, totalFrames);
                    if (frameIndex % 30 == 0 && frameIndex > 0) {
                        log(listener, String.format(Locale.US, "frame %d/%d (t=%.2fs)", frameIndex, totalFrames, outputTimeSeconds));
                    }
                }
            }
            completed = frameIndex >= totalFrames;
            if (listener != null) listener.onProgress(frameIndex, totalFrames);
            log(listener, "Timeline export " + (completed ? "finished" : "stopped") + ", " + frameIndex + " frames -> " + outputPath);
        } catch (IOException | RuntimeException e) {
            encoder.abort();
            throw e;
        } finally {
            for (VideoLayer v : videoLayers.values()) {
                v.source.close();
                compositor.destroyLayer(v.layer);
            }
            for (ImageLayer i : imageLayers.values()) compositor.destroyLayer(i.layer);
        }

        if (completed) {
            encoder.finish(); // throws with ffmpeg's own error text if the file wasn't written properly
        } else {
            encoder.abort();
        }
        return completed;
    }

    /**
     * Opens (if needed), advances (video only) and draws one clip's DrawCommand into
     * whatever the compositor is currently pointed at - the main canvas for a plain
     * draw, a scratch canvas for one side of a transition or a blur. The same rules as
     * Android's OpenGLEditNative.renderDrawCommand: a clip that can't be opened, or never
     * yields a frame, is reported once and skipped.
     */
    private static final class ClipRenderer {
        private final Compositor compositor;
        private final ProjectData projectData;
        private final String ffmpegPath;
        private final int frameRate;
        private final int canvasWidth;
        private final Map<Clip, String> reversedClipPaths;
        private final Map<Clip, VideoLayer> videoLayers;
        private final Map<Clip, ImageLayer> imageLayers;
        private final Set<Clip> reported;
        private final Listener listener;

        ClipRenderer(Compositor compositor, ProjectData projectData, String ffmpegPath, int frameRate, int canvasWidth,
                     Map<Clip, String> reversedClipPaths, Map<Clip, VideoLayer> videoLayers,
                     Map<Clip, ImageLayer> imageLayers, Set<Clip> reported, Listener listener) {
            this.compositor = compositor;
            this.projectData = projectData;
            this.ffmpegPath = ffmpegPath;
            this.frameRate = frameRate;
            this.canvasWidth = canvasWidth;
            this.reversedClipPaths = reversedClipPaths;
            this.videoLayers = videoLayers;
            this.imageLayers = imageLayers;
            this.reported = reported;
            this.listener = listener;
        }

        /** Returns true if something was actually drawn. */
        boolean render(OpenGLEdit.DrawCommand cmd, float outputTimeSeconds) {
            Clip clip = cmd.clip;

            if (clip.type == ClipType.IMAGE) {
                ImageLayer image = imageLayers.get(clip);
                if (image == null) {
                    try {
                        String path = clip.getAbsolutePath(projectData);
                        ByteBuffer pixels = OpenGLFrameIO.decodeImageRgba(ffmpegPath, path, clip.width, clip.height);
                        image = new ImageLayer(compositor.createLayer(Math.max(1, clip.width), Math.max(1, clip.height)), pixels);
                        imageLayers.put(clip, image);
                    } catch (IOException | RuntimeException e) {
                        if (reported.add(clip)) {
                            log(listener, "Could not open an image clip, it will be missing from this export: " + e.getMessage());
                        }
                        return false;
                    }
                }
                ByteBuffer upload = image.pixelsUntilUploaded;
                image.pixelsUntilUploaded = null;
                compositor.draw(image.layer, upload, cmd);
                return true;
            }

            if (clip.type == ClipType.TEXT) {
                // Rasterised once at full canvas resolution (the text doesn't change during an export).
                ImageLayer text = imageLayers.get(clip);
                if (text == null) {
                    try {
                        TextLayoutEngine.Bitmap bitmap = TextLayoutEngine.render(TextStyle.of(clip, canvasWidth, TextStyle.fontsDirOf(projectData)), 1f);
                        text = new ImageLayer(compositor.createLayer(bitmap.width, bitmap.height), bitmap.rgba);
                        imageLayers.put(clip, text);
                    } catch (RuntimeException e) {
                        if (reported.add(clip)) {
                            log(listener, "Could not draw a text clip, it will be missing from this export: " + e);
                        }
                        return false;
                    }
                }
                ByteBuffer upload = text.pixelsUntilUploaded;
                text.pixelsUntilUploaded = null;
                compositor.draw(text.layer, upload, cmd);
                return true;
            }

            if (clip.type != ClipType.VIDEO) return false; // see OpenGLEdit.getUnsupportedFeatures()

            VideoLayer video = videoLayers.get(clip);
            if (video == null) {
                String override = reversedClipPaths.get(clip);
                String path = override != null ? override : sourcePathFor(clip, projectData);
                try {
                    OpenGLFrameIO.ClipFrameSource source = new OpenGLFrameIO.ClipFrameSource(
                            ffmpegPath, path, clip.width, clip.height, cmd.localSourceTimeSeconds, frameRate);
                    video = new VideoLayer(source, compositor.createLayer(source.getWidth(), source.getHeight()));
                    videoLayers.put(clip, video);
                } catch (IOException | RuntimeException e) {
                    if (reported.add(clip)) {
                        log(listener, "Could not open a clip, it will be missing from this export: " + e.getMessage());
                    }
                    return false;
                }
            }

            if (!video.source.advanceToTime(cmd.localSourceTimeSeconds)) {
                // Only when the decoder never produced a single frame. Once per clip.
                if (reported.add(clip)) {
                    String detail = video.source.errorTail();
                    log(listener, "A clip produced no frames (from output t=" + outputTimeSeconds + "s)"
                            + (detail.isEmpty() ? "" : ": " + detail));
                }
                return false;
            }

            ByteBuffer upload = (video.source.frameChanged() || !video.uploadedOnce) ? video.source.frame() : null;
            video.uploadedOnce = true;
            compositor.draw(video.layer, upload, cmd);
            return true;
        }
    }

    /**
     * Loads the bundled clip animations (idempotent) and warns once per unusable animation
     * this timeline uses: an id that isn't installed, or one of the wrong direction (an "out"
     * animation in the in slot or the reverse). Such a clip exports WITHOUT that animation
     * (see OpenGLEdit.animationFor) - this makes that visible instead of silent.
     */
    private static void prepareClipAnimations(Timeline timeline, Listener listener) {
        for (String problem : ClipAnimationAssets.loadAll()) {
            log(listener, "Animation file problem - " + problem);
        }
        if (timeline == null || timeline.tracks == null) return;
        Set<String> warned = new HashSet<>();
        for (Track track : timeline.tracks) {
            if (track == null || track.clips == null) continue;
            for (Clip clip : track.clips) {
                if (clip == null) continue;
                warnIfUnusable(clip.inAnimation, ClipAnimation.Direction.IN, warned, listener);
                warnIfUnusable(clip.outAnimation, ClipAnimation.Direction.OUT, warned, listener);
            }
        }
    }

    private static void warnIfUnusable(AnimationClip slot, ClipAnimation.Direction wanted,
                                       Set<String> warned, Listener listener) {
        if (slot == null || slot.type == null || slot.type.isEmpty() || "none".equals(slot.type)) return;
        if (!warned.add(wanted.json + ":" + slot.type)) return;
        ClipAnimation def = ClipAnimationLoader.get(slot.type);
        if (def == null) {
            log(listener, wanted.json + "-animation '" + slot.type + "' is not installed - clips using it export without it");
        } else if (def.getDirection() != wanted) {
            log(listener, "Animation '" + slot.type + "' is an " + def.getDirection().json
                    + " animation - it can't be used as an " + wanted.json + "-animation");
        }
    }

    /** Cut-out (background removed) clips decode from their cut-out file, matching FFmpegEdit. */
    static String sourcePathFor(Clip clip, ProjectData projectData) {
        if (clip.removeBackground && clip.type == ClipType.VIDEO) {
            String cutout = clip.getCutoutPath(projectData.getProjectPath());
            if (new File(cutout).exists()) return cutout;
        }
        return clip.getAbsolutePath(projectData);
    }

    private static void releaseInactive(Map<Clip, VideoLayer> layers, Set<Clip> stillActive, Compositor compositor) {
        Iterator<Map.Entry<Clip, VideoLayer>> it = layers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Clip, VideoLayer> entry = it.next();
            if (!stillActive.contains(entry.getKey())) {
                entry.getValue().source.close();
                compositor.destroyLayer(entry.getValue().layer);
                it.remove();
            }
        }
    }

    private static void releaseInactiveImages(Map<Clip, ImageLayer> layers, Set<Clip> stillActive, Compositor compositor) {
        Iterator<Map.Entry<Clip, ImageLayer>> it = layers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Clip, ImageLayer> entry = it.next();
            if (!stillActive.contains(entry.getKey())) {
                compositor.destroyLayer(entry.getValue().layer);
                it.remove();
            }
        }
    }

    private static void log(Listener listener, String message) {
        if (listener != null) listener.onLog(message);
    }
}
