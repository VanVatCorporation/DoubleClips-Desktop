package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Decoders for the live preview, owned by the preview worker's GL thread (not thread-safe).
 * <p>
 * Unlike the export, which reads every clip strictly forward, the preview is jumped around by the
 * playhead. So decode streams are pooled per source FILE, not per clip:
 * <ul>
 *   <li>A request is served by an open stream that is at, or at most {@link #FORWARD_WINDOW_FRAMES}
 *       behind, the wanted frame (the extra frames are decoded and discarded - cheaper than a new
 *       process). That is what makes normal playback a single steady stream per clip.</li>
 *   <li>Otherwise (a seek, a scrub backwards) an idle stream of the same file is restarted at the new
 *       position, or a new one is opened. Restarting means a fresh ffmpeg with {@code -ss}.</li>
 *   <li>Two clips cut from the same file (a split) get separate streams automatically, because they
 *       ask for different source times.</li>
 * </ul>
 * Frames are decoded at no more than {@link #DECODE_MAX_EDGE} on the long edge (the clip's quad
 * stretches them anyway, see ClipFrameSource), which keeps the pipe and the texture uploads small
 * for 4K sources.
 */
final class PreviewFramePool {

    static final int DECODE_MAX_EDGE = 1280;
    static final long FORWARD_WINDOW_FRAMES = 45;
    static final int MAX_VIDEO_STREAMS = 10;
    static final int MAX_IMAGES = 16;
    static final int MAX_TEXTS = 96; // a per-character animation needs one texture per letter
    /** Decoded-but-not-yet-uploaded images kept ready (about 3.7 MB each at the preview size). */
    static final int MAX_PRELOADED_IMAGES = 24;
    /** A stream nobody asked for in this long is closed. */
    static final long IDLE_CLOSE_NANOS = 4_000_000_000L;

    private final OpenGLTimelineExporter.Compositor compositor;
    private final ProjectData project;
    private final String fontsDir;
    private final String ffmpegPath;
    private volatile boolean hwaccel;
    private final Consumer<String> log;

    private volatile boolean useProxy;

    private final List<VideoStream> streams = new ArrayList<>();
    private final Map<String, ImageEntry> images = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Double> fpsCache = new HashMap<>();
    private final Set<Object> reported = new HashSet<>();
    /** Bumped once per rendered frame; a stream stamped with the current value is in use right now. */
    private long frameCounter = 0;
    /** True while the playhead is running: nothing may stall the render loop then. */
    private boolean playing;

    /** Images are decoded off the GL thread (a PNG is an ffmpeg process, 100+ ms), keyed like {@link #images}. */
    private final Map<String, Future<ByteBuffer>> decodes = new LinkedHashMap<>();

    /** Rendered text, one GL texture per distinct style (and render scale). */
    private final Map<String, TextEntry> texts = new LinkedHashMap<>(16, 0.75f, true);
    private int canvasWidth = 1920;
    /** Bitmap pixels per canvas unit: text is rasterised at the preview's own size so it lands pixel-for-pixel. */
    private float renderScale = 1f;
    private final ExecutorService imageLoader = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "PreviewImageLoader");
        t.setDaemon(true);
        return t;
    });

    private static final class VideoStream {
        final String path;
        final int width, height;
        final double fps;
        final OpenGLTimelineExporter.Layer layer;
        OpenGLFrameIO.ClipFrameSource source;
        /** First frame index the current source was started at (it has not read a frame yet if currentFrameIndex() < 0). */
        long startFrame;
        long uploadedIndex = Long.MIN_VALUE;
        String lastLoggedErrors = "";
        long lastUsedFrame = -1;
        long lastUsedNanos;

        VideoStream(String path, int width, int height, double fps, OpenGLTimelineExporter.Layer layer) {
            this.path = path;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.layer = layer;
        }

        long positionIndex() {
            long cur = source.currentFrameIndex();
            return cur >= 0 ? cur : startFrame - 1;
        }
    }

    private static final class TextEntry {
        final OpenGLTimelineExporter.Layer layer;
        ByteBuffer pixelsUntilUploaded;

        TextEntry(OpenGLTimelineExporter.Layer layer, ByteBuffer pixels) {
            this.layer = layer;
            this.pixelsUntilUploaded = pixels;
        }
    }

    private static final class ImageEntry {
        final OpenGLTimelineExporter.Layer layer;
        ByteBuffer pixelsUntilUploaded;

        ImageEntry(OpenGLTimelineExporter.Layer layer, ByteBuffer pixels) {
            this.layer = layer;
            this.pixelsUntilUploaded = pixels;
        }
    }

    PreviewFramePool(OpenGLTimelineExporter.Compositor compositor, ProjectData project, String ffmpegPath,
                     boolean hwaccel, boolean useProxy, Consumer<String> log) {
        this.compositor = compositor;
        this.project = project;
        this.ffmpegPath = ffmpegPath;
        this.hwaccel = hwaccel;
        this.useProxy = useProxy;
        this.log = log;
        this.fontsDir = TextStyle.fontsDirOf(project);
    }

    /** The project canvas width (text wraps against it) and the preview's pixels per canvas unit. */
    void setCanvas(int canvasWidth, float renderScale) {
        this.canvasWidth = canvasWidth;
        this.renderScale = renderScale > 0f ? renderScale : 1f;
    }

    /** Switching between the proxy and the original files invalidates every open stream. */
    void setUseProxy(boolean useProxy) {
        if (this.useProxy == useProxy) return;
        this.useProxy = useProxy;
        closeAll();
    }

    /** Streams opened from now on use (or don't use) hardware decoding; open ones are left alone. */
    void setHardwareDecode(boolean on) {
        this.hwaccel = on;
    }

    /** Call once at the start of every rendered frame. */
    void beginFrame(boolean playing) {
        frameCounter++;
        this.playing = playing;
    }

    /** Draws one clip into whatever the compositor is pointed at. True if something was drawn. */
    boolean draw(OpenGLEdit.DrawCommand cmd, float outputTimeSeconds) {
        Clip clip = cmd.clip;
        if (clip.type == ClipType.IMAGE) return drawImage(clip, cmd);
        if (clip.type == ClipType.TEXT) return drawText(clip, cmd);
        if (clip.type != ClipType.VIDEO) return false;
        return drawVideo(clip, cmd, outputTimeSeconds);
    }

    /**
     * Opens (or repositions) the stream a clip will need shortly, WITHOUT reading from it, so the
     * ffmpeg start-up cost is paid before the playhead gets there instead of as a hitch.
     */
    void prefetch(OpenGLEdit.DrawCommand cmd) {
        Clip clip = cmd.clip;
        if (clip.type != ClipType.VIDEO) return;
        try {
            acquire(videoPath(clip), decodeSize(clip), sourceTimeFor(clip, cmd));
        } catch (IOException | RuntimeException ignored) {
            // reported properly if the clip is actually drawn
        }
    }

    /** Closes streams nobody has asked for lately. Call once per frame. */
    void trimIdle() {
        long now = System.nanoTime();
        Iterator<VideoStream> it = streams.iterator();
        while (it.hasNext()) {
            VideoStream s = it.next();
            if (s.lastUsedFrame != frameCounter && now - s.lastUsedNanos > IDLE_CLOSE_NANOS) {
                destroy(s);
                it.remove();
            }
        }
    }

    void closeAll() {
        for (VideoStream s : streams) destroy(s);
        streams.clear();
        for (ImageEntry e : images.values()) compositor.destroyLayer(e.layer);
        images.clear();
        for (TextEntry e : texts.values()) compositor.destroyLayer(e.layer);
        texts.clear();
        for (Future<ByteBuffer> f : decodes.values()) f.cancel(true);
        decodes.clear();
    }

    /** The worker is exiting. */
    void shutdown() {
        closeAll();
        imageLoader.shutdownNow();
    }

    // ── video ────────────────────────────────────────────────────────────

    private boolean drawVideo(Clip clip, OpenGLEdit.DrawCommand cmd, float outputTimeSeconds) {
        String path = videoPath(clip);
        double target = sourceTimeFor(clip, cmd);
        VideoStream stream;
        try {
            stream = acquire(path, decodeSize(clip), target);
        } catch (IOException | RuntimeException e) {
            if (reported.add(clip)) log.accept("Could not open a clip for preview: " + e.getMessage());
            return false;
        }

        stream.lastUsedFrame = frameCounter;
        stream.lastUsedNanos = System.nanoTime();
        if (!stream.source.advanceToTime(target)) {
            if (reported.add(clip)) {
                String detail = stream.source.errorTail();
                log.accept("A clip produced no frames in preview (t=" + outputTimeSeconds + "s)"
                        + (detail.isEmpty() ? "" : ": " + detail));
            }
            return false;
        }

        if ((frameCounter & 15) == 0) logDecodeErrors(stream, clip);

        long index = stream.source.currentFrameIndex();
        ByteBuffer upload = index != stream.uploadedIndex ? stream.source.frame() : null;
        stream.uploadedIndex = index;
        compositor.draw(stream.layer, upload, cmd);
        return true;
    }

    /**
     * The time in the ORIGINAL file for this draw. A reversed clip's DrawCommand time refers to the
     * pre-reversed intermediate the export builds, which the preview doesn't have, so reversed clips
     * are shown forward for now (reported once) rather than not at all.
     */
    private double sourceTimeFor(Clip clip, OpenGLEdit.DrawCommand cmd) {
        double t = cmd.localSourceTimeSeconds;
        if (clip.isReverse()) {
            String name = new File(clip.getAbsolutePath(project)).getName();
            if (reported.add("reverse:" + name)) {
                log.accept("Reverse isn't previewed yet - '" + name + "' plays forward in preview (export is correct)");
            }
            t += clip.startClipTrim;
        }
        return Math.max(0.0, t);
    }

    private String videoPath(Clip clip) {
        if (useProxy && !clip.removeBackground) {
            String proxy = clip.getAbsolutePreviewPath(project, ".mp4");
            if (new File(proxy).isFile()) return proxy;
        }
        return OpenGLTimelineExporter.sourcePathFor(clip, project);
    }

    private int[] decodeSize(Clip clip) {
        int cw = Math.max(1, clip.width);
        int ch = Math.max(1, clip.height);
        double k = Math.min(1.0, DECODE_MAX_EDGE / (double) Math.max(cw, ch));
        return new int[]{Math.max(1, (int) Math.round(cw * k)), Math.max(1, (int) Math.round(ch * k))};
    }

    private double fpsFor(String path) {
        Double cached = fpsCache.get(path);
        if (cached != null) return cached;
        double fps = OpenGLFrameIO.probeFrameRate(ffmpegPath, path, 30.0);
        fpsCache.put(path, fps);
        return fps;
    }

    private VideoStream acquire(String path, int[] size, double targetSec) throws IOException {
        double fps = fpsFor(path);
        long wanted = (long) Math.floor(targetSec * fps + 1e-4);

        // 1. A stream that is at the wanted frame, or a little behind it (closest wins).
        VideoStream best = null;
        long bestDistance = Long.MAX_VALUE;
        for (VideoStream s : streams) {
            if (!s.path.equals(path) || s.width != size[0] || s.height != size[1]) continue;
            long position = s.positionIndex();
            long distance = wanted - position;
            if (s.source.hasEnded() && s.source.currentFrameIndex() >= 0 && distance >= 0) {
                distance = 0; // ran off the end of the file: hold its last frame
            }
            if (distance < 0 || distance > FORWARD_WINDOW_FRAMES) continue;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = s;
            }
        }
        if (best != null) return best;

        // 2. Restart an idle stream of the same file (the scrub / seek case).
        VideoStream idle = null;
        for (VideoStream s : streams) {
            if (!s.path.equals(path) || s.width != size[0] || s.height != size[1]) continue;
            if (s.lastUsedFrame == frameCounter) continue;
            if (idle == null || s.lastUsedNanos < idle.lastUsedNanos) idle = s;
        }
        if (idle != null) {
            idle.source.close();
            idle.source = open(path, size, targetSec, fps);
            idle.startFrame = wanted;
            idle.uploadedIndex = Long.MIN_VALUE;
            idle.lastUsedNanos = System.nanoTime();
            return idle;
        }

        // 3. Open a new one, making room first if needed.
        while (streams.size() >= MAX_VIDEO_STREAMS) {
            VideoStream lru = null;
            for (VideoStream s : streams) {
                if (s.lastUsedFrame == frameCounter) continue;
                if (lru == null || s.lastUsedNanos < lru.lastUsedNanos) lru = s;
            }
            if (lru == null) break; // everything is in use this very frame: allow the overshoot
            destroy(lru);
            streams.remove(lru);
        }
        OpenGLFrameIO.ClipFrameSource source = open(path, size, targetSec, fps);
        VideoStream created = new VideoStream(path, size[0], size[1], fps,
                compositor.createLayer(source.getWidth(), source.getHeight()));
        created.source = source;
        created.startFrame = wanted;
        created.lastUsedNanos = System.nanoTime();
        streams.add(created);
        return created;
    }

    private OpenGLFrameIO.ClipFrameSource open(String path, int[] size, double targetSec, double fps) throws IOException {
        // Hardware decoding only for a stream that starts at the very beginning of the file, i.e. one
        // opened WITHOUT a seek. Seeking into the middle of a clip makes ffmpeg decode and discard the
        // frames before the target, and a hardware decoder (VideoToolbox) fed a stream that starts
        // mid-GOP can leave corrupted reference state - noisy, torn, triangular frames until the next
        // keyframe. Software decoding handles that correctly, so every seeked stream uses it.
        boolean hardware = hwaccel && Math.floor(Math.max(0.0, targetSec) * fps + 1e-4) <= 0;
        return new OpenGLFrameIO.ClipFrameSource(ffmpegPath, path, size[0], size[1], targetSec, fps, true, hardware);
    }

    /**
     * ffmpeg runs with -loglevel error, so anything on its stderr is a real decode problem (corrupt or
     * missing reference frames, bad slices...). Those are exactly what smeared / displaced bands in
     * the picture look like, so they are written to the log once per distinct message.
     */
    private void logDecodeErrors(VideoStream stream, Clip clip) {
        String tail = stream.source.errorTail();
        if (tail.isEmpty() || tail.equals(stream.lastLoggedErrors)) return;
        stream.lastLoggedErrors = tail;
        String shown = tail.length() > 700 ? tail.substring(tail.length() - 700) : tail;
        log.accept("Decoder reported problems for '" + new File(stream.path).getName() + "': " + shown.replace('\n', '|'));
    }

    private void destroy(VideoStream s) {
        String tail = s.source.errorTail();
        if (!tail.isEmpty() && !tail.equals(s.lastLoggedErrors)) {
            String shown = tail.length() > 700 ? tail.substring(tail.length() - 700) : tail;
            log.accept("Decoder reported problems for '" + new File(s.path).getName() + "': " + shown.replace('\n', '|'));
        }
        s.source.close();
        compositor.destroyLayer(s.layer);
    }

    // ── text ─────────────────────────────────────────────────────────────

    private boolean drawText(Clip clip, OpenGLEdit.DrawCommand cmd) {
        TextStyle style = TextStyle.of(clip, canvasWidth, fontsDir);
        boolean unit = cmd.textUnitMode != null && cmd.textUnitIndex >= 0;
        String key = style.key() + '\u0002' + renderScale + (unit ? '\u0003' + cmd.textUnitMode + '\u0003' + cmd.textUnitIndex : "");
        TextEntry entry = texts.get(key);
        if (entry == null) {
            try {
                TextLayoutEngine.Bitmap bitmap = unit
                        ? TextLayoutEngine.renderUnit(style, cmd.textUnitMode, cmd.textUnitIndex, renderScale)
                        : TextLayoutEngine.render(style, renderScale);
                entry = new TextEntry(compositor.createLayer(bitmap.width, bitmap.height), bitmap.rgba);
            } catch (RuntimeException e) {
                if (reported.add(clip)) log.accept("Could not draw a text clip in preview: " + e);
                return false;
            }
            texts.put(key, entry);
            // Typing in the text box makes one new style per keystroke: keep only the recent ones.
            while (texts.size() > MAX_TEXTS) {
                Iterator<Map.Entry<String, TextEntry>> it = texts.entrySet().iterator();
                Map.Entry<String, TextEntry> eldest = it.next();
                if (eldest.getValue() == entry) break;
                compositor.destroyLayer(eldest.getValue().layer);
                it.remove();
            }
        }
        ByteBuffer upload = entry.pixelsUntilUploaded;
        entry.pixelsUntilUploaded = null;
        compositor.draw(entry.layer, upload, cmd);
        return true;
    }

    // ── images ───────────────────────────────────────────────────────────

    private static String imageKey(String path, int[] size) {
        return path + "@" + size[0] + "x" + size[1];
    }

    /** Starts (once) the background decode of an image; returns its future. */
    private Future<ByteBuffer> startImageDecode(String path, int[] size, String key) {
        Future<ByteBuffer> existing = decodes.get(key);
        if (existing != null) return existing;
        // Make room by forgetting finished decodes nobody has used (oldest first).
        Iterator<Map.Entry<String, Future<ByteBuffer>>> it = decodes.entrySet().iterator();
        while (decodes.size() >= MAX_PRELOADED_IMAGES && it.hasNext()) {
            Map.Entry<String, Future<ByteBuffer>> eldest = it.next();
            if (!eldest.getValue().isDone()) break;
            it.remove();
        }
        final int w = size[0], h = size[1];
        Future<ByteBuffer> future = imageLoader.submit(() -> OpenGLFrameIO.decodeImageRgba(ffmpegPath, path, w, h));
        decodes.put(key, future);
        return future;
    }

    /**
     * Starts decoding every image clip that becomes visible within {@code horizonSeconds}, so a clip that
     * lasts only a few frames is ready when the playhead reaches it instead of being decoded (and
     * missed) at the moment it appears.
     */
    void prefetchImages(Timeline timeline, float fromSeconds, float horizonSeconds) {
        if (timeline == null || timeline.tracks == null) return;
        float to = fromSeconds + horizonSeconds;
        for (Track track : timeline.tracks) {
            if (track == null || track.clips == null) continue;
            for (Clip clip : track.clips) {
                if (clip == null || clip.type != ClipType.IMAGE) continue;
                if (clip.startTime >= to || clip.startTime + clip.duration <= fromSeconds) continue;
                int[] size = decodeSize(clip);
                String path = clip.getAbsolutePath(project);
                String key = imageKey(path, size);
                if (!images.containsKey(key)) startImageDecode(path, size, key);
            }
        }
    }

    private boolean drawImage(Clip clip, OpenGLEdit.DrawCommand cmd) {
        int[] size = decodeSize(clip);
        String path = clip.getAbsolutePath(project);
        String key = imageKey(path, size);
        ImageEntry image = images.get(key);
        if (image == null) {
            Future<ByteBuffer> future = startImageDecode(path, size, key);
            // While playing, never wait for a decode: skip the clip this frame and let a later frame
            // have it. When paused or scrubbing, wait: that frame is the one being looked at.
            if (playing && !future.isDone()) return false;
            ByteBuffer pixels;
            try {
                pixels = future.get();
            } catch (ExecutionException | java.util.concurrent.CancellationException e) {
                if (reported.add(clip)) log.accept("Could not open an image for preview: "
                        + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            decodes.remove(key);
            image = new ImageEntry(compositor.createLayer(size[0], size[1]), pixels);
            images.put(key, image);
            while (images.size() > MAX_IMAGES) {
                Iterator<Map.Entry<String, ImageEntry>> it = images.entrySet().iterator();
                Map.Entry<String, ImageEntry> eldest = it.next();
                if (eldest.getValue() == image) break;
                compositor.destroyLayer(eldest.getValue().layer);
                it.remove();
            }
        }
        ByteBuffer upload = image.pixelsUntilUploaded;
        image.pixelsUntilUploaded = null;
        compositor.draw(image.layer, upload, cmd);
        return true;
    }
}
