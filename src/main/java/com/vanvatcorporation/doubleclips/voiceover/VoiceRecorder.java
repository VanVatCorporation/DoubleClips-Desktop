package com.vanvatcorporation.doubleclips.voiceover;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;

/**
 * Microphone capture with {@code javax.sound.sampled}: 16-bit mono, 44.1 kHz when the device offers it (else 48, 22.05
 * or 16 kHz), read in 20 ms blocks on its own thread into one growing {@code float[]} (so a take is just samples, ready
 * for {@link EchoCanceller} / {@link VoiceEnhancer}). While recording it also keeps a peak envelope (one min/max pair
 * per 20 ms) for the live waveform and reports each block's level for the meter.
 * <p>
 * On macOS the microphone permission belongs to the app that started the JVM (Terminal, the IDE, the packaged app):
 * when it was refused the line still opens but delivers only zeros - {@link #isSilentSoFar()} reports that.
 */
public final class VoiceRecorder {

    /** One capture device. {@code info == null} is the system default. */
    public static final class Device {
        public final Mixer.Info info;
        public final String name;

        Device(Mixer.Info info, String name) {
            this.info = info;
            this.name = name;
        }

        @Override public String toString() {
            return name;
        }
    }

    public interface Listener {
        /** From the capture thread, every ~20 ms: the block's RMS and peak (0..1, after the input gain). */
        void onLevel(double rms, double peak);
    }

    private static final int[] RATES = {44100, 48000, 22050, 16000};

    /** The input devices (mixers that can open a 16-bit mono {@code TargetDataLine}); the system default comes first. */
    public static List<Device> listInputs() {
        List<Device> out = new ArrayList<>();
        out.add(new Device(null, "System default"));
        try {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                try {
                    Mixer mixer = AudioSystem.getMixer(mi);
                    if (pickFormat(mixer) != null) out.add(new Device(mi, mi.getName()));
                } catch (RuntimeException ignored) {
                    // a mixer that cannot even be queried is not a microphone we can use
                }
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private static AudioFormat pickFormat(Mixer mixer) {
        for (int rate : RATES) {
            AudioFormat f = new AudioFormat(rate, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, f);
            boolean ok = mixer == null ? AudioSystem.isLineSupported(info) : mixer.isLineSupported(info);
            if (ok) return f;
        }
        return null;
    }

    // ---- state ----------------------------------------------------------------------------------

    private TargetDataLine line;
    private AudioFormat format;
    private Thread thread;
    private volatile boolean running;
    private volatile double gain = 1.0;
    private Listener listener;

    private final Object lock = new Object();
    private float[] samples = new float[44100 * 60];
    private int count;
    private float[] peaks = new float[2 * 3000];                 // min,max per 20 ms
    private int blocks;
    private volatile boolean anySignal;
    private volatile Throwable failure;

    /** Opens the device and starts capturing. Throws when no device can be opened (in use, no permission, none). */
    public void start(Device device, Listener listener) throws LineUnavailableException {
        if (running) throw new IllegalStateException("already recording");
        Mixer mixer = device == null || device.info == null ? null : AudioSystem.getMixer(device.info);
        AudioFormat f = pickFormat(mixer);
        if (f == null) throw new LineUnavailableException("No microphone input is available.");
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, f);
        TargetDataLine l = mixer == null ? (TargetDataLine) AudioSystem.getLine(info) : (TargetDataLine) mixer.getLine(info);
        int block = f.getFrameSize() * (int) (f.getSampleRate() / 50);
        l.open(f, block * 10);
        this.line = l;
        this.format = f;
        this.listener = listener;
        synchronized (lock) {
            count = 0;
            blocks = 0;
        }
        anySignal = false;
        failure = null;
        running = true;
        l.start();
        thread = new Thread(this::run, "VoiceRecorder");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        TargetDataLine l = line;
        int frames = (int) (format.getSampleRate() / 50);        // 20 ms
        byte[] buf = new byte[frames * 2];
        float[] block = new float[frames];
        try {
            while (running) {
                int read = l.read(buf, 0, buf.length);
                if (read <= 0) continue;
                int n = read / 2;
                double g = gain;
                double sum = 0, peak = 0, lo = 0, hi = 0;
                for (int i = 0; i < n; i++) {
                    short s = (short) ((buf[2 * i] & 0xFF) | (buf[2 * i + 1] << 8));
                    float v = (float) Math.max(-1.0, Math.min(1.0, s / 32768.0 * g));
                    block[i] = v;
                    sum += v * (double) v;
                    double a = Math.abs(v);
                    if (a > peak) peak = a;
                    if (v < lo) lo = v;
                    if (v > hi) hi = v;
                    if (s != 0) anySignal = true;
                }
                synchronized (lock) {
                    if (count + n > samples.length) samples = java.util.Arrays.copyOf(samples, samples.length * 2);
                    System.arraycopy(block, 0, samples, count, n);
                    count += n;
                    if (2 * (blocks + 1) > peaks.length) peaks = java.util.Arrays.copyOf(peaks, peaks.length * 2);
                    peaks[2 * blocks] = (float) lo;
                    peaks[2 * blocks + 1] = (float) hi;
                    blocks++;
                }
                Listener ls = listener;
                if (ls != null) ls.onLevel(Math.sqrt(sum / n), peak);
            }
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    /** The input volume as a linear factor (0..1), changeable while recording. */
    public void setGain(double linear) {
        gain = Math.max(0, linear);
    }

    public boolean isRunning() {
        return running;
    }

    public Throwable failure() {
        return failure;
    }

    public int sampleRate() {
        return format == null ? 44100 : (int) format.getSampleRate();
    }

    /** Seconds captured so far. */
    public double seconds() {
        synchronized (lock) {
            return count / (double) sampleRate();
        }
    }

    /** True while nothing but digital silence has come in (microphone permission refused, device muted). */
    public boolean isSilentSoFar() {
        return !anySignal;
    }

    /** Number of 20 ms peak blocks so far. */
    public int peakBlocks() {
        synchronized (lock) {
            return blocks;
        }
    }

    /** Copies the min/max pairs of blocks [from, now) into {@code out} (grown as needed); returns how many blocks. */
    public int copyPeaks(int from, float[][] out) {
        synchronized (lock) {
            int n = Math.max(0, blocks - from);
            if (out[0] == null || out[0].length < 2 * n) out[0] = new float[2 * n];
            if (n > 0) System.arraycopy(peaks, 2 * from, out[0], 0, 2 * n);
            return n;
        }
    }

    /** Stops capturing, closes the device and returns the take (mono, {@link #sampleRate()} Hz). */
    public float[] stop() {
        running = false;
        TargetDataLine l = line;
        if (l != null) {
            try {
                l.stop();
                l.flush();
            } catch (RuntimeException ignored) {
            }
        }
        Thread t = thread;
        if (t != null) {
            try {
                t.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (l != null) {
            try {
                l.close();
            } catch (RuntimeException ignored) {
            }
        }
        line = null;
        thread = null;
        synchronized (lock) {
            return java.util.Arrays.copyOf(samples, count);
        }
    }

    /** Throws the take away without a result (the editor is closing). */
    public void abort() {
        stop();
    }
}
