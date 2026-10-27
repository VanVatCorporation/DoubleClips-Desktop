package com.vanvatcorporation.doubleclips;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;
import com.vanvatcorporation.doubleclips.data.editing.VideoProperties;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * The live-preview process: a long-lived JVM that owns one GL context and composites the timeline
 * with the SAME compositor and layering code the OpenGL export uses ({@link GlCompositor},
 * {@link OpenGLTimelineExporter#drawFrameLayers}), so the preview can't drift from the export.
 * <p>
 * It is a separate JVM for the same reason the export is (see OpenGLEditNative): GLFW wants the
 * process's first thread, which JavaFX already owns in the editor.
 * <p>
 * Protocol. stdin: one JSON object per line -
 * <pre>
 *   {"cmd":"init","project":..,"ffmpeg":..,"canvasW":..,"canvasH":..,"previewW":..,"previewH":..,
 *    "stretch":bool,"hwaccel":bool,"proxy":bool}                       (always the first line)
 *   {"cmd":"timeline","timeline":{...}}                                (replaces the whole timeline)
 *   {"cmd":"live","track":i,"clip":j,"key":k,"props":{VideoProperties}}  (gesture in flight: patches ONE clip's
 *                                                                         static properties, or keyframe k's value
 *                                                                         when k >= 0, in the worker's copy only)
 *   {"cmd":"livemasks","track":i,"clip":j,"masks":[ClipMask]}           (mask gesture in flight: replaces ONE clip's whole
 *                                                                         mask list, in the worker's copy only)
 *   {"cmd":"tile","req":n,"kind":"transition"|"effect","style":id,"frames":n,"side":px,"intensity":x}
 *                                                                        (picker tiles, see TileRenderer; answered with
 *                                                                         `frames` packets whose seq is tileSeq(req, i))
 *   {"cmd":"render","seq":n,"t":seconds,"playing":bool}                (only the newest is ever rendered)
 *   {"cmd":"proxy","on":bool}
 *   {"cmd":"hwaccel","on":bool}                                        (hardware decoding for streams opened from now on)
 *   {"cmd":"quit"}
 * </pre>
 * stdout: ONLY binary packets - int magic, int seq, int width, int height (big-endian), then for
 * seq >= 0 width*height*4 bytes of BGRA, top row first. seq -1 is the "ready" handshake (no
 * pixels). Everything human-readable goes to stderr ("LOG ..." lines); System.out is redirected there
 * too, so a stray print in shared code can't corrupt the frame stream.
 */
public final class PreviewWorker {

    static final int MAGIC = 0x44434652; // "DCFR"
    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

    private static final class Request {
        final int seq;
        final float t;
        final boolean playing;

        Request(int seq, float t, boolean playing) {
            this.seq = seq;
            this.t = t;
            this.playing = playing;
        }
    }

    private static final Object LOCK = new Object();
    private static Request pending;                 // guarded by LOCK; a newer request replaces an unserved older one
    private static boolean quit;                    // guarded by LOCK
    private static volatile Timeline timeline;      // replaced wholesale, never mutated
    /** Picker tile requests (see TileRenderer), served on the render thread between frames. */
    private static final java.util.concurrent.ConcurrentLinkedQueue<JsonObject> tileRequests = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.Map<Integer, TileRenderer> tileRenderers = new java.util.HashMap<>();
    private static final AtomicReference<Boolean> proxyChange = new AtomicReference<>();
    private static final AtomicReference<Boolean> hwChange = new AtomicReference<>();

    public static void main(String[] args) {
        // Text is rasterised with Java2D; headless keeps AWT off the main thread GLFW needs on macOS.
        System.setProperty("java.awt.headless", "true");
        OutputStream frames = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16);
        System.setOut(System.err);
        try {
            run(frames);
            System.exit(0);
        } catch (Throwable t) {
            System.err.println("ERROR " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(OutputStream frames) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8), 1 << 16);
        String first = in.readLine();
        if (first == null) return;
        JsonObject init = parse(first);
        if (init == null || !"init".equals(str(init, "cmd"))) throw new IllegalArgumentException("First line must be init");

        String projectPath = str(init, "project");
        String ffmpegPath = str(init, "ffmpeg");
        int canvasW = init.get("canvasW").getAsInt();
        int canvasH = init.get("canvasH").getAsInt();
        int previewW = Math.max(2, init.get("previewW").getAsInt());
        int previewH = Math.max(2, init.get("previewH").getAsInt());
        boolean stretch = init.get("stretch").getAsBoolean();
        boolean hwaccel = init.get("hwaccel").getAsBoolean();
        boolean proxy = init.get("proxy").getAsBoolean();

        ProjectData projectData = new ProjectData(projectPath, "", 0, 0, 0);

        Thread reader = new Thread(() -> readCommands(in), "preview-stdin");
        reader.setDaemon(true);
        reader.start();

        org.lwjgl.glfw.GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialise GLFW - is a display/GPU available to this process?");
        }
        try {
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            long window = glfwCreateWindow(1, 1, "DoubleClips preview (hidden)", NULL, NULL);
            if (window == NULL) throw new RuntimeException("Failed to create hidden GLFW window/GL context");
            try {
                glfwMakeContextCurrent(window);
                org.lwjgl.opengl.GL.createCapabilities();
                log("GL " + glGetString(GL_VERSION) + " on " + glGetString(GL_RENDERER)
                        + " - preview " + previewW + "x" + previewH + " for canvas " + canvasW + "x" + canvasH
                        + (proxy ? ", proxy clips" : ", original clips"));
                for (String problem : ClipAnimationAssets.loadAll()) log("Animation file problem - " + problem);

                GlCompositor compositor = new GlCompositor(previewW, previewH);
                PreviewFramePool pool = new PreviewFramePool(compositor, projectData, ffmpegPath, hwaccel, proxy,
                        PreviewWorker::log);
                pool.setCanvas(canvasW, previewW / (float) canvasW);
                try {
                    writePacket(frames, -1, previewW, previewH, null);
                    renderLoop(frames, compositor, pool, canvasW, canvasH, previewW, previewH, stretch,
                            TextStyle.fontsDirOf(projectData));
                } finally {
                    pool.shutdown();
                    for (TileRenderer renderer : tileRenderers.values()) renderer.close();
                    compositor.close();
                }
            } finally {
                glfwDestroyWindow(window);
            }
        } finally {
            glfwTerminate();
        }
    }

    // ── render loop (runs on the main thread, which owns the GL context) ─────────────────

    private static void renderLoop(OutputStream frames, GlCompositor compositor, PreviewFramePool pool,
                                   int canvasW, int canvasH, int previewW, int previewH, boolean stretch,
                                   String fontsDir) throws Exception {
        OpenGLEdit edit = new OpenGLEdit();
        edit.setTextMetrics((clip, cw, ch) -> TextLayoutEngine.measure(TextStyle.of(clip, cw, fontsDir)));
        edit.setTextUnitProvider((clip, mode, cw, ch) -> TextLayoutEngine.toUnits(TextLayoutEngine.units(TextStyle.of(clip, cw, fontsDir), mode)));
        ByteBuffer pixels = ByteBuffer.allocateDirect(previewW * previewH * 4).order(ByteOrder.nativeOrder());
        byte[] out = new byte[previewW * previewH * 4];
        byte[] row = new byte[previewW * 4];
        float blurScale = previewW / (float) canvasW;
        boolean failureReported = false;

        while (true) {
            Request request;
            synchronized (LOCK) {
                while (pending == null && tileRequests.isEmpty() && !quit) LOCK.wait();
                if (quit) return;
                request = pending;
                pending = null;
            }

            // Picker tiles are made between frames (one request per turn, so a playing preview is never starved).
            JsonObject tile = tileRequests.poll();
            if (tile != null) serveTile(frames, tile);
            if (request == null) continue;

            Boolean proxy = proxyChange.getAndSet(null);
            if (proxy != null) pool.setUseProxy(proxy);
            Boolean hw = hwChange.getAndSet(null);
            if (hw != null) pool.setHardwareDecode(hw);

            Timeline tl = timeline;
            List<OpenGLEdit.FrameLayer> layers = Collections.emptyList();
            try {
                if (tl != null) layers = edit.computeFrameForTimestamp(tl, request.t, canvasW, canvasH, stretch);
            } catch (RuntimeException e) {
                if (!failureReported) {
                    failureReported = true;
                    log("Could not compute frame at t=" + request.t + ": " + e);
                }
            }

            pool.beginFrame(request.playing);
            OpenGLTimelineExporter.drawFrameLayers(compositor, layers, request.t, blurScale, pool::draw);
            compositor.readFrame(pixels);
            toBgraTopFirst(pixels, out, row, previewW, previewH);
            writePacket(frames, request.seq, previewW, previewH, out);

            // After the frame is on its way: warm the streams the playhead is about to need, and
            // drop the ones it left behind.
            if (request.playing && tl != null) {
                pool.prefetchImages(tl, request.t, 3f);
                try {
                    for (OpenGLEdit.FrameLayer layer : edit.computeFrameForTimestamp(tl, request.t + 0.75f, canvasW, canvasH, stretch)) {
                        if (layer.simpleDraw != null) {
                            pool.prefetch(layer.simpleDraw);
                        } else if (layer.transition != null) {
                            pool.prefetch(layer.transition.clipACommand);
                            pool.prefetch(layer.transition.clipBCommand);
                        }
                    }
                } catch (RuntimeException ignored) {
                }
            }
            pool.trimIdle();
        }
    }

    /**
     * The packet number of frame {@code index} of tile request {@code req}: tile packets use seq <= -2 (-1 is the
     * "worker is ready" packet), so a reader tells them from preview frames by the number alone.
     * Index 255 with an empty picture means "this tile cannot be made".
     */
    static int tileSeq(int req, int index) {
        return -(2 + ((req << 8) | (index & 0xFF)));
    }

    private static void serveTile(OutputStream frames, JsonObject cmd) {
        int req = cmd.get("req").getAsInt();
        try {
            int side = Math.max(16, Math.min(256, cmd.get("side").getAsInt()));
            int count = Math.max(1, Math.min(200, cmd.get("frames").getAsInt()));
            String kind = str(cmd, "kind");
            String style = str(cmd, "style");
            float intensity = cmd.has("intensity") ? cmd.get("intensity").getAsFloat() : 1f;
            TileRenderer renderer = tileRenderers.computeIfAbsent(side, TileRenderer::new);
            byte[][] pictures = null;
            if (style != null) {
                if ("transition".equals(kind)) pictures = renderer.transition(style, count);
                else if ("effect".equals(kind)) pictures = renderer.effect(style, intensity, count);
            }
            if (pictures == null) {
                writePacket(frames, tileSeq(req, 255), 0, 0, null);
                return;
            }
            for (int i = 0; i < pictures.length; i++) writePacket(frames, tileSeq(req, i), side, side, pictures[i]);
        } catch (Exception | LinkageError e) {
            log("Could not make a tile: " + e);
            try {
                writePacket(frames, tileSeq(req, 255), 0, 0, null);
            } catch (java.io.IOException ignored) {
            }
        }
    }

    /** glReadPixels gives RGBA with the BOTTOM row first; JavaFX wants BGRA with the top row first. */
    static void toBgraTopFirst(ByteBuffer rgbaBottomFirst, byte[] bgraTopFirst, byte[] row, int w, int h) {
        int rowBytes = w * 4;
        for (int y = 0; y < h; y++) {
            rgbaBottomFirst.position((h - 1 - y) * rowBytes);
            rgbaBottomFirst.get(row, 0, rowBytes);
            int dst = y * rowBytes;
            for (int i = 0; i < rowBytes; i += 4) {
                bgraTopFirst[dst + i] = row[i + 2];
                bgraTopFirst[dst + i + 1] = row[i + 1];
                bgraTopFirst[dst + i + 2] = row[i];
                bgraTopFirst[dst + i + 3] = (byte) 0xFF; // the canvas is opaque; keep it so regardless of blending
            }
        }
        rgbaBottomFirst.clear();
    }

    private static void writePacket(OutputStream out, int seq, int w, int h, byte[] bgra) throws java.io.IOException {
        ByteBuffer header = ByteBuffer.allocate(16); // big-endian
        header.putInt(MAGIC).putInt(seq).putInt(w).putInt(h);
        out.write(header.array());
        if (bgra != null) out.write(bgra, 0, w * h * 4);
        out.flush();
    }

    // ── stdin (reader thread) ────────────────────────────────────────────

    private static void readCommands(BufferedReader in) {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonObject cmd = parse(line);
                if (cmd == null) continue;
                String name = str(cmd, "cmd");
                if (name == null) continue;
                switch (name) {
                    case "render":
                        synchronized (LOCK) {
                            pending = new Request(cmd.get("seq").getAsInt(), cmd.get("t").getAsFloat(),
                                    cmd.has("playing") && cmd.get("playing").getAsBoolean());
                            LOCK.notifyAll();
                        }
                        break;
                    case "timeline":
                        try {
                            timeline = GSON.fromJson(cmd.get("timeline"), Timeline.class);
                        } catch (RuntimeException e) {
                            log("Ignoring an unreadable timeline: " + e);
                        }
                        break;
                    case "live":
                        try {
                            applyLive(cmd);
                        } catch (RuntimeException e) {
                            log("Ignoring an unusable live edit: " + e);
                        }
                        break;
                    case "tile":
                        tileRequests.add(cmd);
                        synchronized (LOCK) {
                            LOCK.notifyAll();
                        }
                        break;
                    case "livemasks":
                        try {
                            applyLiveMasks(cmd);
                        } catch (RuntimeException e) {
                            log("Ignoring an unusable live mask edit: " + e);
                        }
                        break;
                    case "proxy":
                        proxyChange.set(cmd.get("on").getAsBoolean());
                        break;
                    case "hwaccel":
                        hwChange.set(cmd.get("on").getAsBoolean());
                        break;
                    case "quit":
                        requestQuit();
                        return;
                    default:
                        break;
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            log("stdin closed: " + e);
        }
        requestQuit(); // the editor went away
    }

    /**
     * A drag/scale/rotate gesture in the editor: the editor keeps the real clip untouched until the
     * gesture ends, and streams the in-flight values here instead of re-sending the whole timeline.
     * Only this worker's copy is patched; the next "timeline" command replaces it with the truth.
     * Runs on the reader thread while the render thread may be drawing, so it patches the clip's
     * fields in place - a frame that sees the new PosX with the old PosY for one draw is harmless.
     */
    private static void applyLive(JsonObject cmd) {
        Timeline tl = timeline;
        if (tl == null) return;
        int trackIndex = cmd.get("track").getAsInt();
        int clipIndex = cmd.get("clip").getAsInt();
        int keyIndex = cmd.has("key") ? cmd.get("key").getAsInt() : -1;
        VideoProperties props = GSON.fromJson(cmd.get("props"), VideoProperties.class);
        if (props == null || trackIndex < 0 || trackIndex >= tl.tracks.size()) return;
        Track track = tl.tracks.get(trackIndex);
        if (clipIndex < 0 || clipIndex >= track.clips.size()) return;
        Clip clip = track.clips.get(clipIndex);
        if (clip.keyframes != null) {
            // Each property goes into that keyframe when it holds it, else into the static values.
            clip.keyframes.writeLook(clip, props, keyIndex);
        } else {
            clip.videoProperties = props;
        }
    }

    /** Like {@link #applyLive}, for a mask gesture: the clip's whole mask list is replaced in the worker's copy. */
    private static void applyLiveMasks(JsonObject cmd) {
        Timeline tl = timeline;
        if (tl == null) return;
        int trackIndex = cmd.get("track").getAsInt();
        int clipIndex = cmd.get("clip").getAsInt();
        if (trackIndex < 0 || trackIndex >= tl.tracks.size()) return;
        Track track = tl.tracks.get(trackIndex);
        if (clipIndex < 0 || clipIndex >= track.clips.size()) return;
        com.vanvatcorporation.doubleclips.data.editing.ClipMask[] parsed =
                GSON.fromJson(cmd.get("masks"), com.vanvatcorporation.doubleclips.data.editing.ClipMask[].class);
        java.util.List<com.vanvatcorporation.doubleclips.data.editing.ClipMask> list = new java.util.ArrayList<>();
        if (parsed != null) for (com.vanvatcorporation.doubleclips.data.editing.ClipMask m : parsed) {
            if (m == null) continue;
            m.normalizeAfterLoad();
            list.add(m);
        }
        track.clips.get(clipIndex).masks = list.isEmpty() ? null : list;
    }

    private static void requestQuit() {
        synchronized (LOCK) {
            quit = true;
            LOCK.notifyAll();
        }
    }

    // ── small helpers ────────────────────────────────────────────────────

    private static JsonObject parse(String line) {
        try {
            JsonElement e = new JsonParser().parse(line);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            log("Ignoring an unreadable command: " + e);
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static void log(String message) {
        System.err.println("LOG " + message);
        System.err.flush();
    }
}
