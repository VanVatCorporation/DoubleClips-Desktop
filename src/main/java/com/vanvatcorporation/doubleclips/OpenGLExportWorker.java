package com.vanvatcorporation.doubleclips;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Runs as its OWN separate JVM process - see the comment in build.gradle for
 * why (LWJGL/GLFW's macOS main-thread requirement collides with JavaFX's own
 * main-thread requirement if they share a process). Launched by
 * OpenGLEditNative via ProcessBuilder; talks back over stdout with plain
 * "PROGRESS n total" / "LOG message" / "CANCELLED" lines, and reads a
 * cancel-flag file each frame (there is no shared memory across processes).
 * <p>
 * This class is deliberately thin. The algorithm - which clip is drawn where and
 * when, decoder lifetime, speed / trim / reverse / images, cancel, progress - is
 * {@link OpenGLTimelineExporter}, the desktop port of Android's
 * OpenGLEditNative.exportTimeline(); the media plumbing (ffmpeg decode/encode
 * subprocesses, since there is no MediaCodec on the JVM) is {@link OpenGLFrameIO}.
 * What is left here is only the GPU: a hidden GLFW window to get a GL 3.3 core
 * context, one FBO the size of the output canvas, and one textured-quad shader
 * carrying the same colour-grading maths as Android's fragment shaders.
 * <p>
 * Positional args (an internal contract with OpenGLEditNative, not a CLI):
 * <pre>
 *  0 timeline JSON path        1 project path           2 output (video-only mp4)
 *  3 width                     4 height                 5 bitrate (Mbps, informational)
 *  6 frame rate                7 ffmpeg binary path     8 encoder args
 *  9 cancel-flag file path    10 stretchToFull (true/false)
 * 11 reversed-clips manifest path, or "-" for none
 *    (lines: trackIndex TAB clipIndex TAB pre-reversed file path)
 * </pre>
 * The GL calls in this file have NOT been run from the environment this was
 * written in (no GPU/display, no LWJGL jars). Everything else in the pipeline is
 * exercised by a software compositor; test this class on a real machine.
 */
public class OpenGLExportWorker {

    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

    public static void main(String[] args) {
        try {
            run(args); // a cancelled export is reported by its CANCELLED line, not by the exit code
            System.exit(0);
        } catch (Throwable t) {
            System.err.println("ERROR " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length < 12) {
            throw new IllegalArgumentException("Expected 12 arguments, got " + args.length
                    + " (OpenGLEditNative and OpenGLExportWorker are out of sync)");
        }
        Path timelineJsonPath = Path.of(args[0]);
        String projectPath = args[1];
        String outputPath = args[2];
        int width = Integer.parseInt(args[3]);
        int height = Integer.parseInt(args[4]);
        int frameRate = Integer.parseInt(args[6]);
        String ffmpegPath = args[7];
        String encoderArgs = args[8];
        Path cancelFlagPath = Path.of(args[9]);
        boolean stretchToFull = Boolean.parseBoolean(args[10]);
        String reversedManifest = args[11];

        Timeline timeline = GSON.fromJson(Files.readString(timelineJsonPath), Timeline.class);
        ProjectData projectData = new ProjectData(projectPath, "", 0, 0, 0);
        Map<Clip, String> reversedClipPaths = readReversedManifest(reversedManifest, timeline);

        log("OpenGL worker starting - " + width + "x" + height + " @" + frameRate + "fps"
                + (stretchToFull ? ", stretch-to-full" : "")
                + (reversedClipPaths.isEmpty() ? "" : ", " + reversedClipPaths.size() + " reversed clip(s)"));

        org.lwjgl.glfw.GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialise GLFW - is a display/GPU available to this process?");
        }
        try {
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE); // required for core profile on macOS
            // 1x1: never shown or drawn to - GLFW just needs SOME window to create a
            // GL context. All real rendering goes into the FBO created below.
            long window = glfwCreateWindow(1, 1, "DoubleClips OpenGL export (hidden)", NULL, NULL);
            if (window == NULL) throw new RuntimeException("Failed to create hidden GLFW window/GL context");
            try {
                glfwMakeContextCurrent(window);
                org.lwjgl.opengl.GL.createCapabilities();
                log("GL " + glGetString(GL_VERSION) + " on " + glGetString(GL_RENDERER));

                GlCompositor compositor = new GlCompositor(width, height);
                try {
                    boolean completed = OpenGLTimelineExporter.export(timeline, new OpenGLEdit(), projectData, compositor,
                            width, height, frameRate, stretchToFull, reversedClipPaths,
                            ffmpegPath, encoderArgs, outputPath,
                            OpenGLTimelineExporter.CancelSignal.fileExists(cancelFlagPath),
                            new OpenGLTimelineExporter.Listener() {
                                @Override public void onLog(String message) { log(message); }
                                @Override public void onProgress(int frameIndex, int totalFrames) { progress(frameIndex, totalFrames); }
                            });
                    if (!completed) {
                        System.out.println("CANCELLED");
                        System.out.flush();
                    }
                } finally {
                    compositor.close();
                }
            } finally {
                glfwDestroyWindow(window);
            }
        } finally {
            glfwTerminate();
        }
    }

    /**
     * Clips cross the process boundary as JSON, so they are matched back up by
     * position (track index, clip index) - stable, because the launcher writes
     * the manifest from the same Timeline object it serialised.
     */
    private static Map<Clip, String> readReversedManifest(String manifestPath, Timeline timeline) throws IOException {
        Map<Clip, String> map = new IdentityHashMap<>();
        if (manifestPath == null || manifestPath.equals("-") || timeline == null || timeline.tracks == null) return map;
        for (String line : Files.readAllLines(Path.of(manifestPath), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t", 3);
            if (parts.length != 3) continue;
            int trackIndex = Integer.parseInt(parts[0].trim());
            int clipIndex = Integer.parseInt(parts[1].trim());
            if (trackIndex < 0 || trackIndex >= timeline.tracks.size()) continue;
            Track track = timeline.tracks.get(trackIndex);
            if (track == null || track.clips == null || clipIndex < 0 || clipIndex >= track.clips.size()) continue;
            map.put(track.clips.get(clipIndex), parts[2]);
        }
        return map;
    }

    private static void log(String message) {
        System.out.println("LOG " + message);
        System.out.flush();
    }

    private static void progress(int frameIndex, int totalFrames) {
        System.out.println("PROGRESS " + frameIndex + " " + totalFrames);
        System.out.flush();
    }
}
