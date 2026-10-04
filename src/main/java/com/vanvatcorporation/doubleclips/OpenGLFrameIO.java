package com.vanvatcorporation.doubleclips;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything the OpenGL export worker does that is NOT OpenGL: getting decoded
 * frames in (video and still images) and pushing composited frames out to an
 * encoder. All of it goes through ffmpeg subprocesses, because the JVM has no
 * MediaCodec.
 * <p>
 * Split out of OpenGLExportWorker so it has no LWJGL dependency: it can be
 * exercised on a machine with no GPU/display (only needs an ffmpeg binary).
 * <p>
 * This is the desktop counterpart of Android OpenGLEditNative's ClipFrameSource /
 * ImageFrameSource / ExportEncoder, and is written to reproduce the same frame
 * SELECTION rules as Android's ClipFrameSource.advanceToTime():
 * <ul>
 *   <li>the frame shown at a target source time is the one with the greatest
 *       presentation time &lt;= that time (so a 25 fps source inside a 30 fps
 *       export repeats frames instead of running fast);</li>
 *   <li>if the source runs out before the clip does, the LAST frame is held
 *       (FFmpeg's tpad stop_mode=clone equivalent) rather than the clip
 *       vanishing;</li>
 *   <li>targets are non-decreasing (export only moves forward) - speed changes
 *       and keyframed speed are expressed purely through the target times
 *       OpenGLEdit computes, so nothing here knows about speed.</li>
 * </ul>
 */
public final class OpenGLFrameIO {

    private OpenGLFrameIO() {}


    /**
     * Drains a subprocess's stderr on a daemon thread, keeping only the last few
     * KB. Nothing is printed while the process is healthy; the tail is available
     * afterwards for the caller to report when something actually went wrong
     * (a decoder that produced no frames, an encoder that exited non-zero).
     * Also stops an unread stderr pipe from ever filling and stalling ffmpeg.
     */
    static final class StderrTail {
        private final StringBuilder tail = new StringBuilder();
        private static final int MAX = 4000;

        StderrTail(Process process) {
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (tail) {
                            tail.append(line).append('\n');
                            if (tail.length() > MAX) tail.delete(0, tail.length() - MAX);
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "OpenGLFrameIO-stderr");
            t.setDaemon(true);
            t.start();
        }

        String get() {
            synchronized (tail) {
                return tail.toString().trim();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Probing
    // ─────────────────────────────────────────────────────────────────────

    private static final Pattern FPS_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*fps");
    private static final Pattern TBR_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*tbr");

    /**
     * Video frame rate of the first video stream, read from the banner of
     * {@code ffmpeg -i file} (the app only bundles ffmpeg, not ffprobe).
     * Returns {@code fallback} if it can't be determined.
     */
    public static double probeFrameRate(String ffmpegPath, String path, double fallback) {
        try {
            ProcessBuilder pb = new ProcessBuilder(ffmpegPath, "-hide_banner", "-nostdin", "-i", path);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            double result = fallback;
            boolean found = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (found || !line.contains("Video:") || line.contains("attached pic")) continue;
                    Matcher m = FPS_PATTERN.matcher(line);
                    boolean matched = m.find();
                    if (!matched) {
                        m = TBR_PATTERN.matcher(line);
                        matched = m.find();
                    }
                    if (matched) {
                        double v = Double.parseDouble(m.group(1));
                        if (v > 0.5 && v < 1000) {
                            result = v;
                            found = true;
                        }
                    }
                }
            }
            process.waitFor(10, TimeUnit.SECONDS);
            process.destroy();
            return result;
        } catch (IOException | RuntimeException e) {
            return fallback;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Video in
    // ─────────────────────────────────────────────────────────────────────

    /**
     * One clip's decode stream: a single ffmpeg process emitting raw RGBA frames
     * on a regular grid at the source's own frame rate, read on demand.
     * <p>
     * The clip is decoded at {@code width x height} regardless of the file's real
     * resolution - the same way Android stretches whatever SurfaceTexture holds
     * onto the clip's quad - so the frame buffer size is always exactly
     * width*height*4 and a stale/mismatched Clip.width/height can't skew rows.
     */
    public static final class ClipFrameSource {
        private final Process process;
        private final ReadableByteChannel channel;
        private final int width, height;
        private final double sourceFps;
        /** Absolute source frame index of the first frame in the decoded stream. */
        private final long baseFrame;

        // LWJGL hands a buffer's memory address straight to native OpenGL, which
        // REQUIRES a direct buffer (a heap buffer can be moved by the GC, giving
        // an unexplained native crash mid-export). Two are used so that a
        // trailing PARTIAL frame at EOF - which is read into 'next' - can never
        // corrupt 'current', the last complete frame that gets held.
        private ByteBuffer current;
        private ByteBuffer next;

        private final StderrTail stderr;
        private long framesRead = 0;
        private boolean ended = false;
        private boolean hasFrame = false;
        private boolean changed = false;

        /**
         * @param firstTargetSec  the first source time that will be requested; the
         *                        decode is seeked there (like Android's seek to the
         *                        nearest earlier sync frame) instead of decoding
         *                        everything before it
         * @param fallbackFps     frame rate to assume if the file's can't be probed
         */
        public ClipFrameSource(String ffmpegPath, String inputPath, int width, int height,
                               double firstTargetSec, double fallbackFps) throws IOException {
            this(ffmpegPath, inputPath, width, height, firstTargetSec, fallbackFps, false, false);
        }

        /**
         * Preview variant. {@code knownFps} true means {@code fallbackFps} is the source's real,
         * already-probed rate, so the ffmpeg probe (a whole extra process per open) is skipped;
         * {@code hwaccel} adds {@code -hwaccel auto} (ffmpeg falls back to software by itself).
         */
        public ClipFrameSource(String ffmpegPath, String inputPath, int width, int height,
                               double firstTargetSec, double fallbackFps,
                               boolean knownFps, boolean hwaccel) throws IOException {
            if (!new File(inputPath).isFile()) {
                throw new IOException("Input file not found: " + inputPath);
            }
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            this.sourceFps = knownFps && fallbackFps > 0
                    ? fallbackFps
                    : probeFrameRate(ffmpegPath, inputPath, fallbackFps > 0 ? fallbackFps : 30.0);
            // Start the stream on the source frame that is on screen at firstTargetSec
            // (the greatest frame with pts <= target, Android's rule), not on the first
            // frame after it: a plain "-ss target" drops that frame whenever the target
            // falls between two source frames, which would show every frame one late.
            // Seeking half a frame early keeps it despite timestamp rounding.
            this.baseFrame = Math.max(0L, (long) Math.floor(Math.max(0.0, firstTargetSec) * sourceFps + 1e-4));

            int frameBytes = this.width * this.height * 4;
            this.current = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.nativeOrder());
            this.next = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.nativeOrder());

            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            command.add("-hide_banner");
            command.add("-loglevel");
            command.add("error");
            command.add("-nostdin");
            if (hwaccel) {
                command.add("-hwaccel");
                command.add("auto");
            }
            if (this.baseFrame > 0) {
                command.add("-ss");
                command.add(String.format(Locale.US, "%.6f", (this.baseFrame - 0.5) / sourceFps));
            }
            command.add("-i");
            command.add(inputPath);
            command.add("-an");
            command.add("-sn");
            command.add("-vf");
            // fps on the SOURCE's own rate with round=down: each grid slot holds the
            // latest source frame at or before it, i.e. "greatest pts <= target".
            command.add(String.format(Locale.US, "scale=%d:%d,fps=fps=%.5f:round=down", this.width, this.height, sourceFps));
            command.add("-pix_fmt");
            command.add("rgba");
            command.add("-f");
            command.add("rawvideo");
            command.add("pipe:1");

            ProcessBuilder pb = new ProcessBuilder(command);
            this.process = pb.start();
            this.stderr = new StderrTail(process);
            InputStream stdout = process.getInputStream();
            this.channel = Channels.newChannel(stdout);
        }

        /**
         * Makes {@link #frame()} hold the frame that belongs on screen at
         * {@code targetSourceSec}. Returns false only if no frame has ever been
         * decoded for this clip. Targets must be non-decreasing.
         */
        public boolean advanceToTime(double targetSourceSec) {
            changed = false;
            long wantedIndex = (long) Math.floor(targetSourceSec * sourceFps + 1e-4) - baseFrame;
            if (wantedIndex < 0) wantedIndex = 0;
            // Frame index i is the (i+1)-th frame in the stream.
            while (!ended && framesRead < wantedIndex + 1) {
                readOneFrame();
            }
            return hasFrame;
        }

        private void readOneFrame() {
            try {
                next.clear();
                while (next.hasRemaining()) {
                    int n = channel.read(next);
                    if (n < 0) {
                        ended = true; // EOF - a partial trailing frame is discarded, 'current' is untouched
                        return;
                    }
                }
                ByteBuffer tmp = current;
                current = next;
                next = tmp;
                framesRead++;
                hasFrame = true;
                changed = true;
            } catch (IOException e) {
                ended = true;
            }
        }

        /** True if the last advanceToTime() decoded a new frame (so the GL texture needs re-uploading). */
        public boolean frameChanged() {
            return changed;
        }

        /** The frame to draw: width*height*4 bytes of RGBA, top row first. Valid until the next advanceToTime(). */
        public ByteBuffer frame() {
            current.clear();
            return current;
        }

        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public double getSourceFps() { return sourceFps; }

        /** Absolute source frame index of the frame {@link #frame()} holds, or -1 before the first frame. */
        public long currentFrameIndex() {
            return hasFrame ? baseFrame + framesRead - 1 : -1L;
        }

        /** True once the decode stream hit EOF (or died); no further frames will arrive. */
        public boolean hasEnded() { return ended; }

        /** ffmpeg's recent error output for this decode (empty when healthy). */
        public String errorTail() {
            return stderr.get();
        }

        public void close() {
            // Kill first, then close the pipe: closing first makes ffmpeg log a
            // spurious "Broken pipe" on its way out.
            process.destroy();
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Still image in
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Decodes an image to width*height RGBA (top row first) in a direct buffer.
     * Like Android's ImageFrameSource it is decoded once and reused every frame
     * the clip is active; it is scaled to the clip's own size so it stretches
     * onto the clip's quad the same way.
     */
    public static ByteBuffer decodeImageRgba(String ffmpegPath, String path, int width, int height) throws IOException {
        if (!new File(path).isFile()) {
            throw new IOException("Image file not found: " + path);
        }
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        List<String> command = List.of(
                ffmpegPath, "-hide_banner", "-loglevel", "error", "-nostdin",
                "-i", path,
                "-frames:v", "1",
                "-vf", "scale=" + w + ":" + h,
                "-pix_fmt", "rgba",
                "-f", "rawvideo",
                "pipe:1");
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        StderrTail err = new StderrTail(process);
        ByteBuffer buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        try (ReadableByteChannel channel = Channels.newChannel(process.getInputStream())) {
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    String detail = err.get();
                    throw new IOException("Could not decode image: " + path + (detail.isEmpty() ? "" : " (" + detail + ")"));
                }
            }
        } finally {
            process.destroy();
        }
        buffer.clear();
        return buffer;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Video out
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Streaming ffmpeg encoder fed raw RGBA frames on stdin. Video only - audio
     * is mixed separately and muxed on afterwards (same as Android).
     */
    public static final class VideoEncoder {
        private final Process process;
        private final WritableByteChannel channel;
        private final StderrTail stderr;

        public VideoEncoder(String ffmpegPath, int width, int height, int frameRate,
                            String encoderArgs, String outputPath) throws IOException {
            List<String> command = new ArrayList<>(List.of(
                    ffmpegPath,
                    "-hide_banner", "-loglevel", "error", "-nostats",
                    "-f", "rawvideo",
                    "-pix_fmt", "rgba",
                    "-video_size", width + "x" + height,
                    "-framerate", String.valueOf(frameRate),
                    "-i", "pipe:0",
                    "-an",
                    // glReadPixels returns rows bottom-first (OpenGL window-coordinate
                    // convention); every video/image pixel format expects top-first.
                    // Cheaper to let ffmpeg's own optimized filter flip it than to
                    // reverse rows in Java.
                    "-vf", "vflip",
                    "-pix_fmt", "yuv420p"
            ));
            for (String part : encoderArgs.trim().split("\\s+")) {
                if (!part.isEmpty()) command.add(part);
            }
            command.add("-y");
            command.add(outputPath);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            this.process = pb.start();
            this.stderr = new StderrTail(process);
            this.channel = Channels.newChannel(process.getOutputStream());
        }

        /** Writes one whole frame (the buffer's full capacity). Its position/limit are left untouched. */
        public void writeFrame(ByteBuffer frame) throws IOException {
            ByteBuffer view = frame.duplicate();
            view.clear();
            while (view.hasRemaining()) {
                channel.write(view);
            }
        }

        /** Closes the input, waits for ffmpeg to finish the file, and throws if it reported a failure. */
        public void finish() throws IOException, InterruptedException {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            int exit = process.waitFor();
            if (exit != 0) {
                String detail = stderr.get();
                throw new IOException("Video encoder (ffmpeg) exited with code " + exit
                        + (detail.isEmpty() ? "" : ":\n" + detail));
            }
        }

        /** ffmpeg's recent error output (empty when healthy). */
        public String errorTail() {
            return stderr.get();
        }

        /** Failure/cancel path: stop without waiting for a clean finish. */
        public void abort() {
            process.destroy();
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }
}
