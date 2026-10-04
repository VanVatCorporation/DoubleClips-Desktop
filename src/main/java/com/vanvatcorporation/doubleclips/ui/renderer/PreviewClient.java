package com.vanvatcorporation.doubleclips.ui.renderer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.vanvatcorporation.doubleclips.FFmpegEditNative;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import javafx.application.Platform;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Editor-side handle on the long-lived preview worker process (see PreviewWorker for the protocol).
 * <p>
 * Everything here except the constructor-free "request" methods is thread-safe; the request methods
 * and {@link #image()} are meant for the JavaFX thread. Frames arrive on a reader thread and are
 * handed to the FX thread newest-wins: if the UI is behind, older frames are simply dropped.
 */
public final class PreviewClient {

    private static final int MAGIC = 0x44434652; // "DCFR", must match PreviewWorker
    private static final int FRAME_BUFFERS = 3;
    private static final int MAX_PREVIEW_EDGE = 1280;
    private static final long READY_TIMEOUT_SECONDS = 25;
    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

    public interface Listener {
        /** FX thread. The worker is up; {@link #image()} is valid from now on. */
        void onReady();

        /** FX thread. A new frame was written into {@link #image()}. */
        void onFrame();

        /** FX thread. The worker could not start or died; the client is dead, fall back to the old preview. */
        void onFailed(String reason);
    }

    private final Listener listener;
    private final int previewWidth, previewHeight;
    private final long frameIntervalNanos;

    private Process process;
    private BufferedWriter commands;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PreviewClient-writer");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean closing = false;
    private volatile boolean failed = false;
    private volatile boolean ready = false;

    private final AtomicInteger seq = new AtomicInteger();
    private volatile WritableImage image;

    // FX-thread state
    private String lastTimelineJson;
    private long lastSerializeNanos = 0;
    private long lastRenderNanos = 0;
    private boolean timelineDirty = true;

    // reader thread -> FX thread, newest wins
    private final ArrayBlockingQueue<byte[]> freeBuffers = new ArrayBlockingQueue<>(FRAME_BUFFERS);
    private final Object deliverLock = new Object();
    private byte[] pendingFrame;      // guarded by deliverLock
    private boolean deliverScheduled; // guarded by deliverLock

    /**
     * @param canvasWidth,canvasHeight the project's output size (what the timeline's positions are in)
     * @param frameRate                the project frame rate; playback requests are throttled to it
     */
    public PreviewClient(Listener listener, int canvasWidth, int canvasHeight, int frameRate) {
        this.listener = listener;
        double k = Math.min(1.0, MAX_PREVIEW_EDGE / (double) Math.max(1, Math.max(canvasWidth, canvasHeight)));
        this.previewWidth = Math.max(2, (int) Math.round(canvasWidth * k));
        this.previewHeight = Math.max(2, (int) Math.round(canvasHeight * k));
        this.frameIntervalNanos = (long) (1_000_000_000.0 / Math.max(1, frameRate) * 0.8);
    }

    public int getPreviewWidth() { return previewWidth; }
    public int getPreviewHeight() { return previewHeight; }

    /** The image the frames are written into; null until {@link Listener#onReady()}. */
    public WritableImage image() { return image; }

    public boolean isUsable() { return !failed && !closing; }

    // ── lifecycle ────────────────────────────────────────────────────────

    public void start(String projectPath, boolean stretchToFull, boolean hwaccel, boolean useProxy,
                      int canvasWidth, int canvasHeight) {
        try {
            String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator
                    + (System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
            List<String> command = new ArrayList<>();
            command.add(javaBin);
            if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                command.add("-XstartOnFirstThread"); // GLFW needs the process's first thread on macOS
            }
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add("com.vanvatcorporation.doubleclips.PreviewWorker");

            process = new ProcessBuilder(command).start();
            commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

            JsonObject init = new JsonObject();
            init.addProperty("cmd", "init");
            init.addProperty("project", projectPath);
            init.addProperty("ffmpeg", FFmpegEditNative.getFfmpegPath());
            init.addProperty("canvasW", canvasWidth);
            init.addProperty("canvasH", canvasHeight);
            init.addProperty("previewW", previewWidth);
            init.addProperty("previewH", previewHeight);
            init.addProperty("stretch", stretchToFull);
            init.addProperty("hwaccel", hwaccel);
            init.addProperty("proxy", useProxy);
            send(init.toString());

            startThread("PreviewClient-frames", this::readFrames);
            startThread("PreviewClient-stderr", this::drainStderr);
            startThread("PreviewClient-watchdog", () -> {
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(READY_TIMEOUT_SECONDS));
                } catch (InterruptedException e) {
                    return;
                }
                if (!ready) fail("the preview worker did not start within " + READY_TIMEOUT_SECONDS + " s");
            });
        } catch (IOException | RuntimeException e) {
            fail("could not launch the preview worker: " + e);
        }
    }

    public void close() {
        if (closing) return;
        closing = true;
        try {
            send("{\"cmd\":\"quit\"}");
        } catch (RuntimeException ignored) {
        }
        writer.shutdown();
        Process p = process;
        if (p != null) {
            // The worker also exits by itself when its stdin closes; this is the backstop.
            startThread("PreviewClient-reaper", () -> {
                try {
                    if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly();
                } catch (InterruptedException ignored) {
                    p.destroyForcibly();
                }
            });
        }
    }

    // ── requests (FX thread) ─────────────────────────────────────────────

    /** The timeline's structure changed (clips added/removed/moved): the worker's copy is stale. */
    public void markTimelineDirty() {
        timelineDirty = true;
    }

    public void setUseProxy(boolean useProxy) {
        send("{\"cmd\":\"proxy\",\"on\":" + useProxy + "}");
    }

    /**
     * Asks for the frame at {@code timeSeconds}. While playing, requests are throttled to the project
     * frame rate and the timeline is only re-sent when it was marked dirty or every half second;
     * while paused every request goes through, with the timeline re-sent whenever it differs from
     * what the worker has (property edits don't rebuild the timeline, they just ask for a frame).
     */
    public void requestFrame(Timeline timeline, float timeSeconds, boolean playing) {
        if (failed || closing) return;
        long now = System.nanoTime();
        if (playing && now - lastRenderNanos < frameIntervalNanos) return;
        lastRenderNanos = now;

        boolean resend = timelineDirty || !playing || now - lastSerializeNanos > 500_000_000L;
        if (resend && timeline != null) {
            String json = "{\"cmd\":\"timeline\",\"timeline\":" + GSON.toJson(timeline) + "}";
            lastSerializeNanos = now;
            timelineDirty = false;
            if (!json.equals(lastTimelineJson)) {
                lastTimelineJson = json;
                send(json);
            }
        }
        send("{\"cmd\":\"render\",\"seq\":" + seq.incrementAndGet() + ",\"t\":" + timeSeconds
                + ",\"playing\":" + playing + "}");
    }

    /**
     * The worker's copy of the timeline no longer matches the editor's (a gesture patched it, then
     * was cancelled or committed): the next requestFrame re-sends the whole timeline.
     */
    public void invalidateTimeline() {
        lastTimelineJson = null;
        timelineDirty = true;
    }

    /**
     * Gesture in flight: streams one clip's in-progress properties (see PreviewWorker "live") and asks
     * for a frame, without serialising the whole timeline. {@code keyIndex} >= 0 patches that
     * keyframe's value instead of the clip's static properties.
     */
    public void requestLiveFrame(int trackIndex, int clipIndex, int keyIndex,
                                 com.vanvatcorporation.doubleclips.data.editing.VideoProperties props, float timeSeconds) {
        if (failed || closing) return;
        lastTimelineJson = null; // the worker's copy is about to differ from the editor's
        send("{\"cmd\":\"live\",\"track\":" + trackIndex + ",\"clip\":" + clipIndex
                + ",\"key\":" + keyIndex + ",\"props\":" + GSON.toJson(props) + "}");
        lastRenderNanos = System.nanoTime();
        send("{\"cmd\":\"render\",\"seq\":" + seq.incrementAndGet() + ",\"t\":" + timeSeconds
                + ",\"playing\":false}");
    }

    // ── plumbing ─────────────────────────────────────────────────────────

    /** Lines go out in order on one thread, so a big timeline never blocks the FX thread on the pipe. */
    private void send(String line) {
        if (writer.isShutdown()) return;
        try {
            writer.execute(() -> {
                try {
                    commands.write(line);
                    commands.write('\n');
                    commands.flush();
                } catch (IOException | RuntimeException e) {
                    if (!closing) fail("lost the connection to the preview worker: " + e.getMessage());
                }
            });
        } catch (RuntimeException ignored) {
            // executor already shut down
        }
    }

    private void readFrames() {
        try (InputStream raw = process.getInputStream(); DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw, 1 << 16))) {
            while (true) {
                int magic = in.readInt();
                int frameSeq = in.readInt();
                int w = in.readInt();
                int h = in.readInt();
                if (magic != MAGIC) throw new IOException("bad packet header (is something printing to the worker's stdout?)");
                if (frameSeq < 0) {
                    onWorkerReady(w, h);
                    continue;
                }
                int length = w * h * 4;
                byte[] buffer = freeBuffers.poll();
                if (buffer == null || buffer.length != length) buffer = new byte[length];
                in.readFully(buffer, 0, length);
                deliver(buffer, w, h);
            }
        } catch (IOException e) {
            if (!closing) {
                String exit = "";
                try {
                    if (process.waitFor(2, TimeUnit.SECONDS)) exit = " (exit code " + process.exitValue() + ")";
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                fail("the preview worker stopped" + exit + ": " + e.getMessage());
            }
        }
    }

    private void onWorkerReady(int w, int h) {
        Platform.runLater(() -> {
            if (failed || closing) return;
            image = new WritableImage(w, h);
            ready = true;
            listener.onReady();
        });
    }

    private void deliver(byte[] frame, int w, int h) {
        synchronized (deliverLock) {
            if (pendingFrame != null) freeBuffers.offer(pendingFrame); // the UI never got to it: drop it
            pendingFrame = frame;
            if (deliverScheduled) return;
            deliverScheduled = true;
        }
        Platform.runLater(() -> {
            byte[] toShow;
            synchronized (deliverLock) {
                toShow = pendingFrame;
                pendingFrame = null;
                deliverScheduled = false;
            }
            if (toShow == null) return;
            WritableImage target = image;
            if (target != null && !closing && (int) target.getWidth() == w && (int) target.getHeight() == h) {
                target.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getByteBgraInstance(), toShow, 0, w * 4);
                listener.onFrame();
            }
            freeBuffers.offer(toShow);
        });
    }

    private void drainStderr() {
        try (BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = err.readLine()) != null) {
                LoggingManager.LogToPersistentDataPath("[preview worker] " + line);
            }
        } catch (IOException ignored) {
        }
    }

    private void fail(String reason) {
        if (failed || closing) return;
        failed = true;
        LoggingManager.LogToPersistentDataPath("GPU preview unavailable, using the legacy preview: " + reason);
        Process p = process;
        if (p != null) p.destroyForcibly();
        writer.shutdownNow();
        Platform.runLater(() -> listener.onFailed(reason));
    }

    private static void startThread(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        t.start();
    }
}
