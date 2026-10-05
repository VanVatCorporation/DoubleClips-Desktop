package com.vanvatcorporation.doubleclips;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;
import com.vanvatcorporation.doubleclips.helper.IOHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Desktop entry point ExportWindow calls for an OpenGL-engine export - the
 * counterpart of Android's OpenGLEditNative, kept to the same responsibilities:
 * video-only compositing, with the reversed-clip pre-pass and the audio mix + mux
 * orchestrated around it by the export screen.
 * <p>
 * Does NOT do any GL work itself - it launches {@link OpenGLExportWorker} as
 * its own separate JVM process and talks to it over stdout/a cancel-flag
 * file. See the comment above the LWJGL dependency block in build.gradle for
 * why this is a separate process rather than a direct method call: LWJGL/
 * GLFW needs the process's "first thread" on macOS, and JavaFX's own macOS
 * toolkit already claims that same first thread in this process.
 */
public class OpenGLEditNative {

    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

    /** How long the worker gets to stop by itself after a cancel before it is killed. */
    private static final long CANCEL_GRACE_MILLIS = 5000;

    public interface ExportListener {
        void onLog(String message);
        /** frameIndex counts from 0; totalFrames is the whole export. */
        void onProgress(int frameIndex, int totalFrames);
    }

    private volatile Process workerProcess;
    private volatile Path cancelFlagPath;
    private volatile boolean cancelRequested = false;
    private volatile boolean workerReportedCancel = false;

    /**
     * Safe from any thread. Asks the worker to stop at the next frame boundary (it
     * checks a flag file once per frame), and only kills it if it hasn't stopped
     * within a few seconds - killing it outright would cut the encoder off mid-write.
     * Also stops the reversed-clip pre-pass, which polls {@link #isCancelled()}.
     */
    public void cancel() {
        cancelRequested = true;
        Path flag = cancelFlagPath;
        if (flag != null) {
            try {
                Files.createFile(flag);
            } catch (IOException ignored) {
                // already exists - fine
            }
        }
        Process process = workerProcess;
        if (process != null) {
            Thread killer = new Thread(() -> {
                try {
                    if (!process.waitFor(CANCEL_GRACE_MILLIS, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "OpenGLEditNative-cancel");
            killer.setDaemon(true);
            killer.start();
        }
    }

    /** True once {@link #cancel()} was called, or the worker reported that it stopped on request. */
    public boolean isCancelled() {
        return cancelRequested || workerReportedCancel;
    }

    /**
     * Renders the timeline's VIDEO and IMAGE clips (see OpenGLEdit.getUnsupportedFeatures()
     * for what's excluded) to a video-only mp4 at outputPath. Blocks the calling
     * thread until the worker process exits - call this from a background
     * thread, not the JavaFX Application Thread. Check {@link #isCancelled()}
     * afterwards: a cancelled export returns normally with a partial/absent file.
     *
     * @param reversedClipPaths clip -> pre-reversed intermediate (see
     *        {@link #renderReversedIntermediates}); never null, empty if there are none
     */
    public void exportTimeline(Timeline timeline, VideoSettings settings, String projectPath,
                               int width, int height, int frameRate, String outputPath,
                               Map<Clip, String> reversedClipPaths,
                               ExportListener listener) throws IOException, InterruptedException {
        if (cancelRequested) return;

        Path tempDir = Files.createTempDirectory("doubleclips-opengl-export");
        Path timelineJsonPath = tempDir.resolve("timeline.json");
        Path manifestPath = tempDir.resolve("reversed.tsv");
        Path flag = tempDir.resolve("cancel.flag");

        try {
            Files.writeString(timelineJsonPath, GSON.toJson(timeline));
            Files.writeString(manifestPath, buildReversedManifest(timeline, reversedClipPaths), StandardCharsets.UTF_8);
            cancelFlagPath = flag;
            if (cancelRequested) return; // cancel() raced with the setup above

            String ffmpegPath = FFmpegEditNative.getFfmpegPath();
            String encoderArgs = buildEncoderArgs(settings);
            if (listener != null) listener.onLog("Encoder: " + encoderArgs);

            String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator
                    + (System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");

            List<String> command = new ArrayList<>();
            command.add(javaBin);
            if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                // GLFW window creation must happen on the process's first thread on
                // macOS - this flag is a no-op (and unrecognized) on Windows/Linux,
                // which is why it's added conditionally rather than always.
                command.add("-XstartOnFirstThread");
            }
            command.add("-Djava.awt.headless=true"); // text is drawn with Java2D in the worker, never with a window
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add("com.vanvatcorporation.doubleclips.OpenGLExportWorker");
            command.add(timelineJsonPath.toString());             // 0
            command.add(projectPath);                             // 1
            command.add(outputPath);                              // 2
            command.add(String.valueOf(width));                   // 3
            command.add(String.valueOf(height));                  // 4
            command.add(String.valueOf(settings.getBitrate()));   // 5
            command.add(String.valueOf(frameRate));               // 6
            command.add(ffmpegPath);                              // 7
            command.add(encoderArgs);                             // 8
            command.add(flag.toString());                         // 9
            command.add(String.valueOf(settings.isStretchToFull())); // 10
            command.add(manifestPath.toString());                 // 11

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();
            workerProcess = process;
            if (cancelRequested) { // cancel() landed between the check above and start()
                try { Files.createFile(flag); } catch (IOException ignored) { }
                cancel();
            }

            // Captured in full (capped) so a crash's detail rides in the thrown
            // exception's own message - not just forwarded live via onLog, which
            // is easy to miss right when it matters most. A worker exit code like
            // 134 (128+6 = SIGABRT) means the worker's JVM itself aborted natively
            // (an LWJGL/GLFW assertion, GPU driver fault, etc.) rather than
            // throwing a normal Java exception - for that, whatever the JVM printed
            // to stderr IS the only diagnostic there is, so it has to make it into view.
            StringBuilder stderrCapture = new StringBuilder();
            Thread stderrDrain = new Thread(() -> drainStream(process.getErrorStream(), line -> {
                synchronized (stderrCapture) {
                    if (stderrCapture.length() < 16_000) stderrCapture.append(line).append('\n');
                }
                // Always forwarded live, unlike the "LOG " lines below - this is
                // exactly the detail an OpenGL export failure needs visible.
                if (listener != null) listener.onLog("[stderr] " + line);
            }));
            stderrDrain.setDaemon(true);
            stderrDrain.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.equals("CANCELLED")) {
                        workerReportedCancel = true;
                        continue;
                    }
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

            int exitCode = process.waitFor();
            try { stderrDrain.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

            // A worker that was stopped because we asked it to is not a failure.
            if (exitCode != 0 && !isCancelled()) {
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
        } finally {
            workerProcess = null;
            deleteQuietly(flag);
            deleteQuietly(manifestPath);
            deleteQuietly(timelineJsonPath);
            deleteQuietly(tempDir);
        }
    }

    /**
     * Manifest lines "trackIndex TAB clipIndex TAB path". The worker gets its
     * clips back from JSON as fresh objects, so the reversed-file mapping is
     * carried across by position rather than object identity.
     */
    private static String buildReversedManifest(Timeline timeline, Map<Clip, String> reversedClipPaths) {
        StringBuilder sb = new StringBuilder();
        if (timeline == null || timeline.tracks == null || reversedClipPaths == null || reversedClipPaths.isEmpty()) return "";
        for (int t = 0; t < timeline.tracks.size(); t++) {
            Track track = timeline.tracks.get(t);
            if (track == null || track.clips == null) continue;
            for (int c = 0; c < track.clips.size(); c++) {
                String path = reversedClipPaths.get(track.clips.get(c));
                if (path != null) sb.append(t).append('\t').append(c).append('\t').append(path).append('\n');
            }
        }
        return sb.toString();
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

    // ─────────────────────────────────────────────────────────────────────
    //  Reversed clips
    // ─────────────────────────────────────────────────────────────────────

    /** True if this clip needs a pre-reversed intermediate for the GL path. */
    public static boolean needsReversedIntermediate(Clip clip) {
        return clip != null && clip.type == ClipType.VIDEO && clip.isReverse();
    }

    /**
     * Reversed-clip pre-pass, ported from Android's ExportActivity. Decoding
     * backward isn't possible with a forward-only pipe, so each reversed VIDEO
     * clip's USED range (startClipTrim .. originalDuration - endClipTrim) is cut out
     * and reversed by ffmpeg into a temp file BEFORE the worker ever opens it.
     * OpenGLEdit then addresses that file from local time 0, with no trim offset.
     * Video only - reversed AUDIO is handled by FFmpegEdit's own areverse in the
     * audio pass, against the original file.
     * <p>
     * Blocks; run it off the FX thread. Fails loudly (IOException with ffmpeg's own
     * message) instead of continuing: skipping a clip here would make the GL export
     * silently play it forward. Returns an identity-keyed map (Clip has no equals).
     * The caller owns cleanup - see {@link #deleteReversedIntermediates}.
     * <p>
     * Memory note: ffmpeg's {@code reverse} filter buffers every frame of the
     * trimmed range, so a very long reversed clip at high resolution needs a lot of RAM.
     */
    public static Map<Clip, String> renderReversedIntermediates(Timeline timeline, ProjectData project,
                                                                java.util.function.BooleanSupplier isCancelled,
                                                                ExportListener listener) throws IOException, InterruptedException {
        Map<Clip, String> result = Collections.synchronizedMap(new IdentityHashMap<>());
        if (timeline == null || timeline.tracks == null) return result;

        List<Clip> reversed = new ArrayList<>();
        for (Track track : timeline.tracks) {
            if (track == null || track.clips == null) continue;
            for (Clip clip : track.clips) {
                if (needsReversedIntermediate(clip)) reversed.add(clip);
            }
        }
        if (reversed.isEmpty()) return result;

        String ffmpegPath = FFmpegEditNative.getFfmpegPath();
        for (int i = 0; i < reversed.size(); i++) {
            if (isCancelled != null && isCancelled.getAsBoolean()) break;
            Clip clip = reversed.get(i);
            String outPath = IOHelper.CombinePath(project.getProjectPath(), "opengl_reversed_" + i + "_tmp.mp4");
            float trimEnd = clip.originalDuration - clip.endClipTrim;
            if (listener != null) listener.onLog("Reversing clip " + (i + 1) + "/" + reversed.size() + ": " + clip.getClipName());

            // trim+setpts resets the segment to local time 0 before reverse - reverse
            // needs a PTS-STARTPTS-clean input to buffer and flip correctly, and must
            // not see the rest of the source either side. Even-sized output because
            // yuv420p requires it (the GL side rescales to the clip's size anyway).
            // A high-quality intermediate, not the export codec: this file is decoded
            // once more, so its loss would be paid twice.
            String filter = String.format(Locale.US,
                    "trim=start=%.6f:end=%.6f,setpts=PTS-STARTPTS,reverse,scale=trunc(iw/2)*2:trunc(ih/2)*2",
                    clip.startClipTrim, trimEnd);
            List<String> command = List.of(ffmpegPath, "-y", "-hide_banner", "-loglevel", "error", "-nostdin",
                    "-i", clip.getAbsolutePath(project),
                    "-vf", filter, "-an",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "14", "-pix_fmt", "yuv420p",
                    outPath);

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            Thread drain = new Thread(() -> drainStream(process.getInputStream(), line -> {
                synchronized (output) {
                    if (output.length() < 4000) output.append(line).append('\n');
                }
            }));
            drain.setDaemon(true);
            drain.start();

            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (isCancelled != null && isCancelled.getAsBoolean()) {
                    process.destroyForcibly();
                    deleteQuietly(Path.of(outPath));
                    return result;
                }
            }
            drain.join(1000);
            if (process.exitValue() != 0 || !new File(outPath).isFile()) {
                deleteQuietly(Path.of(outPath));
                String detail;
                synchronized (output) { detail = output.toString().trim(); }
                throw new IOException("Could not reverse clip \"" + clip.getClipName() + "\" (ffmpeg exit "
                        + process.exitValue() + ")" + (detail.isEmpty() ? "" : ":\n" + detail));
            }
            result.put(clip, outPath);
        }
        return result;
    }

    public static void deleteReversedIntermediates(Map<Clip, String> reversedClipPaths) {
        if (reversedClipPaths == null) return;
        for (String path : reversedClipPaths.values()) deleteQuietly(Path.of(path));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Audio
    // ─────────────────────────────────────────────────────────────────────

    /**
     * The regular ffmpeg command with its filter graph sliced down to just the
     * audio statements (see FFmpegEdit.generateExportCmdPartially's audioOnly
     * handling) - used to mix this timeline's audio separately from the GL video
     * render. Returns a command with no "[aout]" in it if the timeline has no audio.
     * <p>
     * Deliberately ONE command over every clip, like Android's audio pass. The
     * regular export splits big timelines into chunks (Clip Cap) that chain through
     * intermediate video files; generateCmdFull would do that here too, but a video
     * intermediate is meaningless for audio - every chunk would target the same
     * audio file - and the joined multi-command string isn't runnable as one command.
     * <p>
     * Newlines are stripped, exactly as the regular export does before running its command
     * ({@code getText().replace("\n", "")}): FFmpegEditNative.runAnyCommand's tokenizer can't
     * keep a quoted filter graph together across line breaks and would hand ffmpeg a
     * shredded command. (The graph's statements are ';'-terminated, so nothing is lost.)
     */
    public static String buildAudioOnlyCommand(VideoSettings settings, Timeline timeline, ProjectData project) {
        FFmpegEdit.RenderSettings renderSettings = new FFmpegEdit.RenderSettings(
                settings, timeline, new Clip[0], project, 0, true, false, false);
        renderSettings.setClips(timeline.getStreamOfClip());
        renderSettings.setAudioOnly(true);
        String command = FFmpegEdit.generateExportCmdPartially(renderSettings).replace("\n", "");
        return sanitizeAudioCommand(command, timeline.duration);
    }

    /**
     * Makes the audio-only command safe to mux, whatever the ffmpeg build does inside its
     * filters. Two problems seen on a real export (ffmpeg 8.1, macOS):
     * <ul>
     *   <li><b>Garbage timestamps.</b> The AAC encoder received frames whose PTS was near
     *       INT64_MAX ("Non-monotonic DTS; previous: 9223372036854775709 ..."), so the file's
     *       start time was ~2e14 seconds and the {@code -c copy} mux silently dropped the whole
     *       track (audio:0KiB). The mixed output is contiguous samples from t=0, so its
     *       timestamps are simply rebuilt from the sample count: {@code asetpts=N/SR/TB} on the
     *       final label. That is correct for any cause upstream (atrim / adelay / apad / amix).</li>
     *   <li><b>Unbounded length.</b> Nothing limited the output, so audio could run past the
     *       video (30.7 s of audio for a 23 s timeline). It is now capped at the timeline
     *       duration with {@code -t}.</li>
     * </ul>
     * Package-visible so it can be tested on its own. A command without {@code [aout]} (no
     * audio at all) is returned unchanged.
     */
    static String sanitizeAudioCommand(String command, float timelineDurationSeconds) {
        if (command == null || !command.contains("[aout]")) return command;
        final String marker = "-filter_complex \"";
        int start = command.indexOf(marker);
        if (start < 0) return command;
        int graphStart = start + marker.length();
        int graphEnd = command.indexOf('"', graphStart);
        if (graphEnd < 0) return command;

        String graph = command.substring(graphStart, graphEnd).trim().replace("[aout]", "[aout_raw]");
        if (!graph.endsWith(";")) graph += ";";
        graph += "[aout_raw]asetpts=N/SR/TB[aout]";
        String rebuilt = command.substring(0, graphStart) + graph + command.substring(graphEnd);

        if (timelineDurationSeconds > 0) {
            int y = rebuilt.lastIndexOf(" -y ");
            if (y >= 0) {
                rebuilt = rebuilt.substring(0, y)
                        + String.format(Locale.US, " -t %.3f", timelineDurationSeconds)
                        + rebuilt.substring(y);
            }
        }
        return rebuilt;
    }

    /** The `-c copy` mux of the GL video-only file and the mixed audio (ported from Android). */
    public static String buildMuxCommand(String videoOnlyPath, String audioOnlyPath, String finalPath) {
        return "-y -i \"" + videoOnlyPath + "\" -i \"" + audioOnlyPath + "\" "
                + "-map 0:v:0 -map 1:a:0 -c:v copy -c:a copy -shortest \"" + finalPath + "\"";
    }

    private static void drainStream(java.io.InputStream stream, Consumer<String> onLine) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) onLine.accept(line);
        } catch (IOException ignored) {
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
