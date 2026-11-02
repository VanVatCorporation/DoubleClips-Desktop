package com.vanvatcorporation.doubleclips;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the reversed copy of a whole video file (see {@link ReverseMap}), with ffmpeg only (the app bundles no ffprobe).
 * <p>
 * ffmpeg's {@code reverse} filter keeps every frame it is given in memory, so a long file can't be reversed in one pass.
 * The file is cut into short chunks (sized from a memory budget), each chunk is reversed on its own, and the chunks are
 * fed BACK TO FRONT into one encoder through a pipe: one continuous encode at an exact constant frame rate, no joins to
 * line up, and memory that stays flat however long the file is. The audio goes the same way (30 s chunks reversed with
 * {@code areverse}, written back to front into one PCM file), and the two are muxed at the end. Progress is reported
 * while it runs and the work can be cancelled.
 * <p>
 * Plain Java on purpose (no JavaFX / GL): it runs on a background thread of the editor and can be tested on its own.
 */
public final class ReverseProcessor {

    private ReverseProcessor() {}

    /** What the banner of {@code ffmpeg -i file} says about a file. */
    public static final class Info {
        public double duration;          // container length, seconds
        public double fps = 30.0;
        public boolean hasVideo;
        public boolean hasAudio;
        public int width;                // as displayed (rotation applied), even numbers
        public int height;
        public int audioRate = 44100;
        public int audioChannels = 2;
    }

    private static final Pattern DURATION = Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
    private static final Pattern SIZE = Pattern.compile("[ ,](\\d{2,5})x(\\d{2,5})[ ,\\[]");
    private static final Pattern ROTATION = Pattern.compile("rotation of\\s*(-?\\d+(?:\\.\\d+)?)\\s*degrees");
    private static final Pattern ROTATE_TAG = Pattern.compile("rotate\\s*:\\s*(-?\\d+)");
    private static final Pattern HZ = Pattern.compile("(\\d+)\\s*Hz,\\s*([^,]+)");

    private static final Pattern FPS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*fps");
    private static final Pattern TBR = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*tbr");

    /** The frame rate a video stream line states ("30 fps", else "30 tbr"), or {@code fallback}. */
    static double frameRateOf(String streamLine, double fallback) {
        Matcher m = FPS.matcher(streamLine);
        if (!m.find()) {
            m = TBR.matcher(streamLine);
            if (!m.find()) return fallback;
        }
        double v = Double.parseDouble(m.group(1));
        return v > 0.5 && v < 1000 ? v : fallback;
    }

    /** Reads the banner of {@code ffmpeg -i file}. */
    public static Info probe(String ffmpeg, String path) throws IOException {
        Info info = new Info();
        double rotation = 0;
        boolean inVideo = false;
        ProcessBuilder pb = new ProcessBuilder(ffmpeg, "-hide_banner", "-nostdin", "-i", path);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                Matcher m = DURATION.matcher(line);
                if (m.find() && info.duration <= 0) {
                    info.duration = Integer.parseInt(m.group(1)) * 3600.0 + Integer.parseInt(m.group(2)) * 60.0
                            + Double.parseDouble(m.group(3));
                }
                if (line.contains("Video:") && !line.contains("attached pic") && !info.hasVideo) {
                    info.hasVideo = true;
                    inVideo = true;
                    Matcher s = SIZE.matcher(line);
                    if (s.find()) {
                        info.width = Integer.parseInt(s.group(1));
                        info.height = Integer.parseInt(s.group(2));
                    }
                    info.fps = frameRateOf(line, info.fps);
                } else if (line.contains("Audio:") && !info.hasAudio) {
                    info.hasAudio = true;
                    inVideo = false;
                    Matcher h = HZ.matcher(line);
                    if (h.find()) {
                        info.audioRate = Integer.parseInt(h.group(1));
                        String layout = h.group(2).trim();
                        info.audioChannels = layout.startsWith("mono") ? 1 : 2; // 5.1 and the like are mixed down to stereo
                    }
                } else if (line.contains("Stream #")) {
                    inVideo = false;
                } else if (inVideo) {
                    Matcher rot = ROTATION.matcher(line);
                    if (rot.find()) rotation = Double.parseDouble(rot.group(1));
                    Matcher tag = ROTATE_TAG.matcher(line);
                    if (tag.find()) rotation = Double.parseDouble(tag.group(1));
                }
            }
        }
        try {
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        p.destroy();
        long turns = Math.abs(Math.round(rotation / 90.0));
        if (turns % 2 == 1) {
            int w = info.width;
            info.width = info.height;
            info.height = w;
        }
        info.width &= ~1;
        info.height &= ~1;
        return info;
    }

    /**
     * How many frames the first video stream of a file has, counted by copying its packets to a framecrc listing (one line
     * a packet, no decoding, so it is quick). -1 when it can't be counted.
     */
    public static long countVideoFrames(String ffmpeg, String path) {
        try {
            ProcessBuilder pb = new ProcessBuilder(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error",
                    "-i", path, "-map", "0:v:0", "-c", "copy", "-f", "framecrc", "-");
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            long frames = 0;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isEmpty() && line.charAt(0) != '#') frames++;
                }
            }
            p.waitFor(60, TimeUnit.SECONDS);
            int code = p.isAlive() ? -1 : p.exitValue();
            p.destroy();
            return code == 0 && frames > 0 ? frames : -1;
        } catch (IOException | RuntimeException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** Thrown when {@link #build} was cancelled; nothing is left behind. */
    public static final class Cancelled extends IOException {
        public Cancelled() { super("cancelled"); }
    }

    private static final double AUDIO_CHUNK_SECONDS = 30.0;

    /**
     * Writes the reversed copy of {@code source} to {@code output} (video, and audio when the source has it).
     * Blocks; run it off the FX thread. {@code output} is only created when everything worked.
     *
     * @param progress 0..1, called often from this thread
     * @throws Cancelled if {@code cancelled} turned true
     * @throws IOException with ffmpeg's own message when a step failed
     */
    public static void build(String ffmpeg, String source, Info info, File output, DoubleConsumer progress,
                             BooleanSupplier cancelled) throws IOException {
        if (!info.hasVideo || info.width < 2 || info.height < 2) throw new IOException("the file has no video to reverse");
        Path work = Files.createTempDirectory("reverse-");
        File partial = new File(output.getParentFile(), output.getName() + ".partial");
        List<Process> live = new ArrayList<>();
        try {
            double audioShare = info.hasAudio ? 0.08 : 0.0;
            File pcm = new File(work.toFile(), "audio.pcm");
            if (info.hasAudio) {
                reverseAudio(ffmpeg, source, info, pcm, p -> progress.accept(p * audioShare), cancelled, live);
            }
            File videoOnly = new File(work.toFile(), "video.mp4");
            reverseVideo(ffmpeg, source, info, videoOnly, p -> progress.accept(audioShare + p * (0.97 - audioShare)),
                    cancelled, live);

            // mux: copy the video, encode the reversed audio once (no per-chunk codec priming to click at the joins)
            List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-y", "-hide_banner", "-nostdin", "-loglevel", "error",
                    "-i", videoOnly.getPath()));
            if (info.hasAudio && pcm.length() > 0) {
                cmd.addAll(List.of("-f", "s16le", "-ar", Integer.toString(info.audioRate), "-ac",
                        Integer.toString(info.audioChannels), "-i", pcm.getPath()));
            }
            cmd.addAll(List.of("-map", "0:v:0"));
            if (info.hasAudio && pcm.length() > 0) cmd.addAll(List.of("-map", "1:a:0", "-c:a", "aac", "-b:a", "192k"));
            cmd.addAll(List.of("-c:v", "copy", "-movflags", "+faststart", "-f", "mp4", partial.getPath()));
            run(cmd, cancelled, live);
            progress.accept(0.99);
            if (cancelled.getAsBoolean()) throw new Cancelled();
            Files.move(partial.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
            progress.accept(1.0);
        } finally {
            for (Process p : live) p.destroyForcibly();
            deleteQuietly(partial.toPath());
            deleteTree(work);
        }
    }

    // ── audio ─────────────────────────────────────────────────────────────

    private static void reverseAudio(String ffmpeg, String source, Info info, File pcm, DoubleConsumer progress,
                                     BooleanSupplier cancelled, List<Process> live) throws IOException {
        int chunks = (int) Math.max(1, Math.ceil(info.duration / AUDIO_CHUNK_SECONDS));
        try (OutputStream out = Files.newOutputStream(pcm.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int k = chunks - 1; k >= 0; k--) {
                if (cancelled.getAsBoolean()) throw new Cancelled();
                List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error",
                        "-ss", seconds(k * AUDIO_CHUNK_SECONDS)));
                if (k < chunks - 1) cmd.addAll(List.of("-t", seconds(AUDIO_CHUNK_SECONDS)));
                cmd.addAll(List.of("-i", source, "-map", "0:a:0", "-vn", "-af", "areverse", "-ac",
                        Integer.toString(info.audioChannels), "-ar", Integer.toString(info.audioRate), "-f", "s16le", "-"));
                Process p = start(cmd, live);
                StringBuilder err = drain(p.getErrorStream());
                try (InputStream in = p.getInputStream()) {
                    in.transferTo(out);
                }
                finish(p, err, live, cancelled, "audio");
                progress.accept((chunks - k) / (double) chunks);
            }
        }
    }

    // ── video ─────────────────────────────────────────────────────────────

    private static void reverseVideo(String ffmpeg, String source, Info info, File out, DoubleConsumer progress,
                                     BooleanSupplier cancelled, List<Process> live) throws IOException {
        final int w = info.width, h = info.height;
        final long frameBytes = (long) w * h * 3 / 2;
        final double fps = ReverseMap.exactFps(info.fps);
        final int perChunk = ReverseMap.chunkFrames(w, h, fps);
        final long expected = Math.max(1, Math.round(info.duration * fps));
        final int chunks = (int) Math.max(1, (expected + perChunk - 1) / perChunk);

        List<String> enc = List.of(ffmpeg, "-y", "-hide_banner", "-nostdin", "-loglevel", "error",
                "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", w + "x" + h, "-framerate", ReverseMap.rationalFps(info.fps), "-i", "-",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "16", "-bf", "0", "-g", "24", "-pix_fmt", "yuv420p",
                "-movflags", "+faststart", "-f", "mp4", out.getPath());
        Process encoder = start(enc, live);
        StringBuilder encErr = drain(encoder.getErrorStream());
        long bytes = 0;
        byte[] buffer = new byte[1 << 20];
        try (OutputStream toEncoder = encoder.getOutputStream()) {
            for (int k = chunks - 1; k >= 0; k--) {
                if (cancelled.getAsBoolean()) throw new Cancelled();
                double[] window = ReverseMap.chunkWindow(k, perChunk, fps, k == chunks - 1);
                List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error"));
                if (window[0] > 0) cmd.addAll(List.of("-ss", seconds(window[0])));
                // -t BEFORE -i: it limits what is read from the file. As an output option it would cut the reversed frames
                // instead, after the reverse filter had already swallowed the whole rest of the file (and shown its tail).
                if (!Double.isInfinite(window[1])) cmd.addAll(List.of("-t", seconds(window[1] - window[0])));
                cmd.addAll(List.of("-i", source));
                cmd.addAll(List.of("-map", "0:v:0", "-an", "-sn", "-dn",
                        "-vf", "reverse,scale=" + w + ":" + h + ",format=yuv420p", "-f", "rawvideo", "-"));
                Process dec = start(cmd, live);
                StringBuilder decErr = drain(dec.getErrorStream());
                try (InputStream in = dec.getInputStream()) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        toEncoder.write(buffer, 0, n);
                        bytes += n;
                        if (cancelled.getAsBoolean()) throw new Cancelled();
                        progress.accept(Math.min(0.999, bytes / (double) (frameBytes * expected)));
                    }
                }
                finish(dec, decErr, live, cancelled, "video");
            }
        } catch (IOException e) {
            if (e instanceof Cancelled) throw e;
            throw new IOException("the encoder stopped: " + tail(encErr) + " (" + e.getMessage() + ")", e);
        }
        if (bytes < frameBytes) throw new IOException("no frames were read from the file");
        finish(encoder, encErr, live, cancelled, "encoder");
    }

    // ── process helpers ───────────────────────────────────────────────────

    private static Process start(List<String> cmd, List<Process> live) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process p = pb.start();
        live.add(p);
        return p;
    }

    /** Collects a process's stderr on its own thread so a full pipe can never stall it. */
    private static StringBuilder drain(InputStream stream) {
        StringBuilder sb = new StringBuilder();
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sb) {
                        if (sb.length() < 4000) sb.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // the process ended
            }
        }, "reverse-stderr");
        t.setDaemon(true);
        t.start();
        return sb;
    }

    private static String tail(StringBuilder sb) {
        synchronized (sb) {
            String s = sb.toString().trim();
            return s.length() > 600 ? s.substring(s.length() - 600) : s;
        }
    }

    private static void finish(Process p, StringBuilder err, List<Process> live, BooleanSupplier cancelled, String what)
            throws IOException {
        try {
            int code = p.waitFor();
            live.remove(p);
            if (cancelled.getAsBoolean()) throw new Cancelled();
            if (code != 0) throw new IOException("ffmpeg (" + what + ") failed: " + tail(err));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new Cancelled();
        }
    }

    private static void run(List<String> cmd, BooleanSupplier cancelled, List<Process> live) throws IOException {
        Process p = start(cmd, live);
        StringBuilder err = drain(p.getErrorStream());
        try {
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
            // the exit code below tells
        }
        finish(p, err, live, cancelled, "mux");
    }

    private static String seconds(double s) {
        return String.format(java.util.Locale.ROOT, "%.6f", s);
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(ReverseProcessor::deleteQuietly);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
