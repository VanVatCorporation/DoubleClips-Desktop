package com.vanvatcorporation.doubleclips;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Desktop entry point ExportWindow calls for an OpenGL-engine export.
 * <p>
 * Does NOT do any GL work itself - it launches {@link OpenGLExportWorker} as
 * its own separate JVM process and talks to it over stdout/a cancel-flag
 * file. See the comment above the LWJGL dependency block in build.gradle for
 * why this is a separate process rather than a direct method call: LWJGL/
 * GLFW needs the process's "first thread" on macOS, and JavaFX's own macOS
 * toolkit already claims that same first thread in this process.
 * <p>
 * Video-only output, same as Android's OpenGLEditNative - audio is mixed
 * separately (see {@link #buildAudioOnlyCommand}) and muxed onto the result
 * afterward; ExportWindow.exportClipViaOpenGl orchestrates the two plus the
 * final mux.
 */
public class OpenGLEditNative {

    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

    public interface ExportListener {
        void onLog(String message);
        /** frameIndex counts from 0; totalFrames is the whole export. */
        void onProgress(int frameIndex, int totalFrames);
    }

    private Process workerProcess;
    private Path cancelFlagPath;

    /** Safe from any thread. The worker checks this once per frame and stops (still finalizing output) if present. */
    public void cancel() {
        if (cancelFlagPath != null) {
            try {
                Files.createFile(cancelFlagPath);
            } catch (IOException ignored) {
                // already exists, or couldn't create it - either way, also try killing the process directly
            }
        }
        if (workerProcess != null) {
            workerProcess.destroy();
        }
    }

    /**
     * Renders the timeline's VIDEO clips (see OpenGLEdit.getUnsupportedFeatures()
     * for what's excluded) to a video-only mp4 at outputPath. Blocks the calling
     * thread until the worker process exits - call this from a background
     * thread, not the JavaFX Application Thread.
     */
    public void exportTimeline(Timeline timeline, VideoSettings settings, String projectPath,
                                int width, int height, int frameRate, String outputPath,
                                ExportListener listener) throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("doubleclips-opengl-export");
        Path timelineJsonPath = tempDir.resolve("timeline.json");
        Files.writeString(timelineJsonPath, GSON.toJson(timeline));
        cancelFlagPath = tempDir.resolve("cancel.flag");

        String ffmpegPath = FFmpegEditNative.getFfmpegPath();
        String encoderArgs = buildEncoderArgs(settings);

        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator
                + (System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");

        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(javaBin);
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            // GLFW window creation must happen on the process's first thread on
            // macOS - this flag is a no-op (and unrecognized) on Windows/Linux,
            // which is why it's added conditionally rather than always.
            command.add("-XstartOnFirstThread");
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("com.vanvatcorporation.doubleclips.OpenGLExportWorker");
        command.add(timelineJsonPath.toString());
        command.add(projectPath);
        command.add(outputPath);
        command.add(String.valueOf(width));
        command.add(String.valueOf(height));
        command.add(String.valueOf(settings.getBitrate()));
        command.add(String.valueOf(frameRate));
        command.add(ffmpegPath);
        command.add(encoderArgs);
        command.add(cancelFlagPath.toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        workerProcess = pb.start();

        // Captured in full (capped) so a crash's detail rides in the thrown
        // exception's own message - not just forwarded live via onLog, which
        // is easy to miss (e.g. the log checkbox off) right when it matters
        // most. A worker exit code like 134 (128+6 = SIGABRT) means the
        // worker's JVM itself aborted natively (an LWJGL/GLFW assertion, GPU
        // driver fault, etc.) rather than throwing a normal Java exception -
        // for that, whatever the JVM printed to stderr (often a "A fatal
        // error has been detected by the Java Runtime Environment" crash
        // report, sometimes also pointing at an hs_err_pid*.log file) IS the
        // only diagnostic there is, so it has to make it into view.
        StringBuilder stderrCapture = new StringBuilder();
        Thread stderrDrain = new Thread(() -> drainStream(workerProcess.getErrorStream(), line -> {
            synchronized (stderrCapture) {
                if (stderrCapture.length() < 16_000) stderrCapture.append(line).append('\n');
            }
            // Always forwarded live, unlike the "LOG " lines below - this is
            // exactly the detail an OpenGL export failure needs visible, and
            // shouldn't be silently dropped by a routine-logging preference.
            if (listener != null) listener.onLog("[stderr] " + line);
        }));
        stderrDrain.setDaemon(true);
        stderrDrain.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(workerProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (listener == null) continue;
                if (line.startsWith("PROGRESS ")) {
                    String[] parts = line.substring("PROGRESS ".length()).trim().split(" ");
                    if (parts.length == 2) {
                        try {
                            listener.onProgress(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                } else if (line.startsWith("LOG ")) {
                    listener.onLog(line.substring("LOG ".length()));
                }
            }
        }

        int exitCode = workerProcess.waitFor();
        try { stderrDrain.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        deleteQuietly(cancelFlagPath);
        deleteQuietly(timelineJsonPath);
        deleteQuietly(tempDir);

        if (exitCode != 0) {
            String detail;
            synchronized (stderrCapture) {
                detail = stderrCapture.length() > 0 ? stderrCapture.toString().trim() : "(worker printed nothing to stderr)";
            }
            String signalNote = exitCode > 128
                    ? " — likely killed by signal " + (exitCode - 128) + (exitCode - 128 == 6 ? " (SIGABRT: the worker JVM crashed natively, not a normal Java exception)" : "")
                    : "";
            throw new IOException("OpenGL export worker exited with code " + exitCode + signalNote
                    + "\n----- worker stderr -----\n" + detail + "\n--------------------------");
        }
    }

    /**
     * Same encoder choice FFmpegEdit's regular path already makes (see its
     * "Encoder selection" comment) - kept here as its own small method rather
     * than shared, since the two call sites build the flags into differently-
     * shaped command lines (FFmpegEdit appends onto its own filter-graph
     * command; the worker takes a single opaque args string).
     */
    private static String buildEncoderArgs(VideoSettings settings) {
        String hwEncoder = FFmpegEditNative.getHardwareAcceleratedName();
        if (settings.isUseHardwareAccel() && hwEncoder != null) {
            return "-c:v h264_" + hwEncoder + " -b:v " + settings.getBitrate() + "M";
        }
        return "-c:v libx264 -preset " + settings.getPreset() + " -crf " + settings.getCRF();
    }

    private static void drainStream(java.io.InputStream stream, Consumer<String> onLine) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) onLine.accept(line);
        } catch (IOException ignored) {
        }
    }

    /**
     * The regular ffmpeg command with its filter graph sliced down to just the
     * audio statements (see FFmpegEdit.generateCmdFull's audioOnly handling) -
     * used to mix this timeline's audio separately from the GL video render.
     * Returns a command that produces no output (harmless to run, but pointless)
     * if the timeline has no audio at all; check for "[aout]" in the result, or
     * just check the timeline yourself, before running it.
     */
    public static String buildAudioOnlyCommand(VideoSettings settings, Timeline timeline, ProjectData project) {
        FFmpegEdit.RenderSettings renderSettings = new FFmpegEdit.RenderSettings(
                settings, timeline, new Clip[0], project, 0, false, false, false);
        renderSettings.setAudioOnly(true);
        return FFmpegEdit.generateCmdFull(renderSettings);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
