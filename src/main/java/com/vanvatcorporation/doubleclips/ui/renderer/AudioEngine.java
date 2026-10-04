package com.vanvatcorporation.doubleclips.ui.renderer;

import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * The preview's audio: ONE output line and ONE mixing thread for the whole timeline, replacing the
 * old per-clip line + thread in ClipRenderer (which could not mix two clips cleanly, drifted per
 * clip and spawned a thread burst on every scrub).
 * <p>
 * It mixes what the OpenGL export's audio graph produces: each clip's source audio, trimmed to
 * [startClipTrim, startClipTrim + duration), placed at startTime, scaled by the clip's audioVolume,
 * and summed (amix normalize=0). Video clips contribute only when they have audio and aren't muted;
 * audio clips always do. Reverse and clip speed aren't applied (the export doesn't change audio
 * speed either).
 * <p>
 * Source material is the mono 22.05 kHz preview WAVs written at import; they are resampled
 * (linear) and widened to the line's 44.1 kHz stereo here.
 * <p>
 * Sync: the editor's playhead is the clock. While playing, the engine writes ahead of it by
 * exactly what the line has buffered; if what is actually audible ever drifts more than
 * {@link #RESYNC_SECONDS} from the playhead (an underrun, a seek during playback, a stall), it
 * re-anchors at the playhead. While paused or scrubbing it plays a short burst at the new position.
 */
public final class AudioEngine {

    private static final int RATE = 44100;
    private static final int CHUNK_FRAMES = RATE / 50;   // 20 ms per write
    private static final int BURST_FRAMES = RATE / 20;   // 50 ms scrub burst
    private static final double RESYNC_SECONDS = 0.15;
    private static final int LINE_BUFFER_FRAMES = RATE / 10; // ~100 ms of latency

    private final Consumer<String> log;

    private final Object lock = new Object();
    private Thread thread;
    private boolean shutdown;
    private boolean wantPlaying;
    private boolean restart;
    private double restartTime;
    private boolean burstPending;
    private double burstTime;

    private volatile double playhead;
    private volatile SourceDataLine line;
    private volatile boolean unavailable;
    private volatile List<Src> sources = Collections.emptyList();
    private final ConcurrentLinkedQueue<Src> retired = new ConcurrentLinkedQueue<>();

    // FX-thread state
    private boolean uiPlaying;
    private double lastScrubTime = Double.NaN;

    public AudioEngine(Consumer<String> log) {
        this.log = log;
    }

    // ── FX-thread API ────────────────────────────────────────────────────

    /** The timeline's structure changed: rebuild the list of audible clips (open files are kept). */
    public void setTimeline(Timeline timeline, ProjectData project) {
        Map<Clip, Src> old = new HashMap<>();
        for (Src s : sources) old.put(s.clip, s);

        List<Src> next = new ArrayList<>();
        if (timeline != null && timeline.tracks != null) {
            for (Track track : timeline.tracks) {
                if (track == null || track.clips == null) continue;
                for (Clip clip : track.clips) {
                    if (clip == null) continue;
                    boolean audible = clip.type == ClipType.AUDIO
                            || (clip.type == ClipType.VIDEO && clip.isClipHasAudio());
                    if (!audible) continue;
                    Src existing = old.remove(clip);
                    next.add(existing != null ? existing : new Src(clip, clip.getAbsolutePreviewPath(project, ".wav")));
                }
            }
        }
        retired.addAll(old.values()); // the mixer thread closes them, it is the only one reading them
        sources = next;

        // The mix just changed under a running playback: re-anchor so the new clips are heard now.
        if (uiPlaying) {
            synchronized (lock) {
                restart = true;
                restartTime = playhead;
                lock.notifyAll();
            }
        }
    }

    /**
     * Called every time the preview time is set.
     *
     * @param time          the playhead
     * @param playingNormal true only for forward playback at 1x; reverse, other speeds and paused
     *                      seeks pass false and only get scrub bursts
     */
    public void update(float time, boolean playingNormal) {
        playhead = time;
        if (playingNormal) {
            lastScrubTime = Double.NaN;
            if (!uiPlaying) {
                uiPlaying = true;
                synchronized (lock) {
                    ensureThread();
                    wantPlaying = true;
                    restart = true;
                    restartTime = time;
                    lock.notifyAll();
                }
            }
            return;
        }

        if (uiPlaying) {           // playing -> paused/seeking: just stop, no blip at the pause position
            uiPlaying = false;
            stopPlaying();
            lastScrubTime = time;
            return;
        }
        if (Double.isNaN(lastScrubTime)) {
            lastScrubTime = time;  // first call: nothing moved, nothing to play
            return;
        }
        if (Math.abs(time - lastScrubTime) > 1e-4) {
            lastScrubTime = time;
            synchronized (lock) {
                ensureThread();
                burstPending = true;
                burstTime = time;
                lock.notifyAll();
            }
        }
    }

    /** The editor is closing. */
    public void shutdown() {
        synchronized (lock) {
            shutdown = true;
            wantPlaying = false;
            lock.notifyAll();
        }
        SourceDataLine l = line;
        if (l != null) l.flush();
    }

    private void stopPlaying() {
        synchronized (lock) {
            wantPlaying = false;
        }
        SourceDataLine l = line;
        if (l != null) l.flush(); // cuts what is buffered, and releases a write that is blocked on a full buffer
    }

    private void ensureThread() { // lock held
        if (thread != null || unavailable) return;
        thread = new Thread(this::run, "PreviewAudioMixer");
        thread.setDaemon(true);
        thread.start();
    }

    // ── mixer thread ─────────────────────────────────────────────────────

    private void run() {
        SourceDataLine out;
        try {
            AudioFormat format = new AudioFormat(RATE, 16, 2, true, false);
            out = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format));
            out.open(format, LINE_BUFFER_FRAMES * 4);
            out.start();
        } catch (Exception | Error e) {
            unavailable = true;
            log.accept("Preview audio unavailable: " + e);
            return;
        }
        line = out;

        float[] acc = new float[CHUNK_FRAMES * 2];
        float[] burstAcc = new float[BURST_FRAMES * 2];
        byte[] bytes = new byte[CHUNK_FRAMES * 4];
        byte[] burstBytes = new byte[BURST_FRAMES * 4];

        boolean running = false;
        double written = 0, baseTime = 0;
        long basePos = 0;

        try {
            while (true) {
                boolean doRestart, doBurst;
                double restartAt, burstAt;
                synchronized (lock) {
                    while (!shutdown && !wantPlaying && !burstPending) lock.wait();
                    if (shutdown) break;
                    doRestart = restart;
                    restartAt = restartTime;
                    doBurst = burstPending;
                    burstAt = burstTime;
                    restart = false;
                    burstPending = false;
                }
                closeRetired();

                if (!wantPlaying) {
                    running = false;
                    if (doBurst) {
                        out.flush();
                        mix(burstAt, BURST_FRAMES, burstAcc);
                        toBytes(burstAcc, BURST_FRAMES, burstBytes);
                        out.write(burstBytes, 0, BURST_FRAMES * 4);
                    }
                    continue;
                }

                if (doRestart || !running) {
                    out.flush();
                    written = doRestart ? restartAt : playhead;
                    baseTime = written;
                    basePos = out.getLongFramePosition();
                    running = true;
                }

                double audible = baseTime + (out.getLongFramePosition() - basePos) / (double) RATE;
                if (Math.abs(audible - playhead) > RESYNC_SECONDS && out.getLongFramePosition() - basePos > RATE / 4) {
                    // Drifted (underrun, stall, seek while playing): re-anchor at the playhead.
                    synchronized (lock) {
                        restart = true;
                        restartTime = playhead;
                    }
                    continue;
                }

                mix(written, CHUNK_FRAMES, acc);
                toBytes(acc, CHUNK_FRAMES, bytes);
                out.write(bytes, 0, CHUNK_FRAMES * 4);
                written += CHUNK_FRAMES / (double) RATE;
                if (!wantPlaying) out.flush(); // stopped while this write was in flight
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.accept("Preview audio stopped: " + e);
        } finally {
            line = null;
            try {
                out.stop();
                out.flush();
                out.close();
            } catch (RuntimeException ignored) {
            }
            for (Src s : sources) s.close();
            closeRetired();
        }
    }

    private void closeRetired() {
        Src s;
        while ((s = retired.poll()) != null) s.close();
    }

    /** Mixes [t0, t0 + frames/RATE) of the whole timeline into {@code acc} (interleaved stereo, float). */
    private void mix(double t0, int frames, float[] acc) {
        java.util.Arrays.fill(acc, 0, frames * 2, 0f);
        double t1 = t0 + frames / (double) RATE;

        for (Src src : sources) {
            Clip clip = src.clip;
            if (clip.type == ClipType.VIDEO && (clip.isMute() || !clip.isClipHasAudio())) continue;
            float gain = clip.getAudioVolume();
            if (gain <= 0f) continue;

            double c0 = clip.startTime, c1 = clip.startTime + clip.duration;
            if (t1 <= c0 || t0 >= c1) continue;
            int i0 = Math.max(0, (int) Math.ceil((c0 - t0) * RATE));
            int i1 = Math.min(frames, (int) Math.floor((c1 - t0) * RATE));
            if (i1 <= i0) continue;
            if (!src.ensureOpen(log)) continue;

            double srcSeconds = (t0 + i0 / (double) RATE) - c0 + clip.startClipTrim;
            double step = src.sampleRate / (double) RATE;
            double pos0 = srcSeconds * src.sampleRate;
            long first = (long) Math.floor(pos0);
            int count = (int) Math.ceil((i1 - i0) * step) + 3;
            short[] samples = src.read(first, count);
            int ch = src.channels;

            for (int i = i0; i < i1; i++) {
                double p = pos0 + (i - i0) * step - first;
                int k = (int) p;
                float f = (float) (p - k);
                int a = k * ch, b = (k + 1) * ch;
                float left = samples[a] * (1f - f) + samples[b] * f;
                float right = ch >= 2 ? samples[a + 1] * (1f - f) + samples[b + 1] * f : left;
                acc[i * 2] += left * gain;
                acc[i * 2 + 1] += right * gain;
            }
        }
    }

    private static void toBytes(float[] acc, int frames, byte[] out) {
        for (int i = 0; i < frames * 2; i++) {
            int v = Math.round(acc[i]);
            if (v > 32767) v = 32767;
            else if (v < -32768) v = -32768;
            out[i * 2] = (byte) v;
            out[i * 2 + 1] = (byte) (v >> 8);
        }
    }

    // ── one clip's WAV ───────────────────────────────────────────────────

    /** A preview WAV (16-bit PCM, mono or stereo), opened lazily and only ever touched by the mixer thread. */
    private static final class Src {
        final Clip clip;
        final String path;
        private RandomAccessFile raf;
        private boolean failed;
        int channels;
        int sampleRate;
        private long dataStart;
        private long dataFrames;
        private byte[] raw = new byte[0];
        private short[] pcm = new short[0];
        private boolean reported;

        Src(Clip clip, String path) {
            this.clip = clip;
            this.path = path;
        }

        boolean ensureOpen(Consumer<String> log) {
            if (raf != null) return true;
            if (failed) return false;
            try {
                File f = new File(path);
                if (!f.isFile()) {
                    failed = true; // no preview audio was extracted for this clip: silent, like before
                    return false;
                }
                RandomAccessFile file = new RandomAccessFile(f, "r");
                if (!parseHeader(file)) {
                    file.close();
                    failed = true;
                    if (!reported) {
                        reported = true;
                        log.accept("Preview audio skipped (not 16-bit PCM wav): " + path);
                    }
                    return false;
                }
                raf = file;
                return true;
            } catch (IOException e) {
                failed = true;
                return false;
            }
        }

        private boolean parseHeader(RandomAccessFile f) throws IOException {
            byte[] head = new byte[12];
            f.seek(0);
            f.readFully(head);
            if (head[0] != 'R' || head[1] != 'I' || head[2] != 'F' || head[3] != 'F') return false;
            long length = f.length();
            long pos = 12;
            boolean haveFmt = false;
            while (pos + 8 <= length) {
                f.seek(pos);
                byte[] chunk = new byte[8];
                f.readFully(chunk);
                String id = new String(chunk, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
                long size = le32(chunk, 4);
                if (id.equals("fmt ")) {
                    byte[] fmt = new byte[16];
                    f.readFully(fmt);
                    int tag = le16(fmt, 0);
                    channels = le16(fmt, 2);
                    sampleRate = (int) le32(fmt, 4);
                    int bits = le16(fmt, 14);
                    if ((tag != 1 && tag != 0xFFFE) || bits != 16 || channels < 1 || channels > 2 || sampleRate <= 0) return false;
                    haveFmt = true;
                } else if (id.equals("data")) {
                    if (!haveFmt) return false;
                    dataStart = pos + 8;
                    // A streamed/unfinished file can carry 0 or 0xFFFFFFFF here: trust the real length.
                    long available = length - dataStart;
                    long declared = (size == 0 || size > available) ? available : size;
                    dataFrames = declared / (2L * channels);
                    return true;
                }
                pos += 8 + size + (size & 1);
            }
            return false;
        }

        /** {@code count} frames starting at source frame {@code first}; anything outside the file is silence. */
        short[] read(long first, int count) {
            int samples = count * channels;
            if (pcm.length < samples) pcm = new short[samples];
            java.util.Arrays.fill(pcm, 0, samples, (short) 0);

            long from = Math.max(0, first);
            long to = Math.min(dataFrames, first + count);
            if (to <= from) return pcm;

            int frameBytes = 2 * channels;
            int bytes = (int) ((to - from) * frameBytes);
            if (raw.length < bytes) raw = new byte[bytes];
            try {
                raf.seek(dataStart + from * frameBytes);
                raf.readFully(raw, 0, bytes);
            } catch (IOException e) {
                return pcm;
            }
            int offset = (int) (from - first) * channels;
            for (int i = 0; i < bytes / 2; i++) {
                pcm[offset + i] = (short) ((raw[i * 2] & 0xFF) | (raw[i * 2 + 1] << 8));
            }
            return pcm;
        }

        void close() {
            try {
                if (raf != null) raf.close();
            } catch (IOException ignored) {
            }
            raf = null;
        }

        private static int le16(byte[] b, int o) {
            return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
        }

        private static long le32(byte[] b, int o) {
            return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
        }
    }
}
