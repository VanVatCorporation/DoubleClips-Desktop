package com.vanvatcorporation.doubleclips.voiceover;

import java.util.Arrays;

/**
 * "Enhance voice": cleans a finished mono recording. Pure Java on {@code float[]} (samples in -1..1), no JavaFX, no
 * javax.sound, no Android / Apple types, so the same code can be copied to the Android app as it is and ported line by
 * line to Swift.
 * <p>
 * The chain, in order:
 * <ol>
 *   <li><b>High-pass</b> (80 Hz, 4th order): rumble, desk thumps, the low part of plosive pops.</li>
 *   <li><b>De-click</b>: short broadband spikes (mouth clicks, digital ticks) are replaced by a straight line.</li>
 *   <li><b>Noise reduction</b>: STFT spectral subtraction. The noise profile is the average spectrum of the quietest
 *       ~0.5 s of the take (so a take should contain a moment of silence), over-subtraction 1.5, spectral floor 0.1
 *       (-20 dB, keeps the result from sounding "underwater"), gains smoothed over frequency and time.</li>
 *   <li><b>Gentle gate</b>: what is still below the speech level for a while is lowered by about 10 dB (not muted).</li>
 *   <li><b>Normalise</b> to about -3 dBFS (peak, ignoring the very loudest 0.05 % so one click cannot decide), then a
 *       soft limiter so nothing clips.</li>
 * </ol>
 * Whole-take processing (not streaming): it is run once when the recording stops.
 */
public final class VoiceEnhancer {

    private VoiceEnhancer() {
    }

    /** What to run. The defaults are the "Enhance voice" switch. */
    public static final class Options {
        public boolean highPass = true;
        public double highPassHz = 80;
        public boolean declick = true;
        public boolean denoise = true;
        public double overSubtraction = 1.5;
        public double spectralFloor = 0.1;
        public boolean gate = true;
        public double gateReductionDb = -10;
        public boolean normalise = true;
        public double targetPeakDb = -3;
    }

    /** Progress callback, 0..1 (may be null). Called from the calling thread. */
    public interface Progress {
        void onProgress(double fraction);
    }

    public static float[] process(float[] in, int rate, Options o, Progress progress) {
        if (in == null || in.length == 0) return in == null ? new float[0] : in;
        float[] x = Arrays.copyOf(in, in.length);
        if (o.highPass) highPass(x, rate, o.highPassHz);
        report(progress, 0.05);
        if (o.declick) declick(x, rate);
        report(progress, 0.10);
        if (o.denoise) x = denoise(x, rate, o.overSubtraction, o.spectralFloor, p -> report(progress, 0.10 + 0.80 * p));
        report(progress, 0.92);
        if (o.gate) gate(x, rate, o.gateReductionDb);
        report(progress, 0.96);
        if (o.normalise) normalise(x, o.targetPeakDb);
        report(progress, 1.0);
        return x;
    }

    private static void report(Progress p, double f) {
        if (p != null) p.onProgress(Math.max(0, Math.min(1, f)));
    }

    // ---- 1. high-pass ------------------------------------------------------------------------------

    /** Two cascaded Butterworth 2nd-order high-pass sections (RBJ biquads), in place. */
    static void highPass(float[] x, int rate, double hz) {
        if (hz <= 0 || hz >= rate / 2.0) return;
        double w0 = 2 * Math.PI * hz / rate;
        double cosw = Math.cos(w0);
        double alpha = Math.sin(w0) / (2 * 0.70710678);
        double a0 = 1 + alpha;
        double b0 = (1 + cosw) / 2 / a0, b1 = -(1 + cosw) / a0, b2 = b0;
        double a1 = -2 * cosw / a0, a2 = (1 - alpha) / a0;
        for (int pass = 0; pass < 2; pass++) {
            double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
            for (int i = 0; i < x.length; i++) {
                double xi = x[i];
                double y = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
                x2 = x1; x1 = xi; y2 = y1; y1 = y;
                x[i] = (float) y;
            }
        }
    }

    // ---- 2. de-click -------------------------------------------------------------------------------

    /**
     * A click is a spike in the second difference that stands far above the local average of it, lasting a couple of
     * milliseconds at most. Marked samples (plus a small margin) are replaced by a line between the good samples on
     * both sides. Conservative on purpose: speech plosives are louder and longer than what this touches.
     */
    static void declick(float[] x, int rate) {
        int n = x.length;
        if (n < 64) return;
        int maxSpan = Math.max(8, (int) (rate * 0.002));      // 2 ms
        int margin = Math.max(2, rate / 4000);                  // ~0.25 ms
        double ema = 0, k = 1.0 / (rate * 0.02);                // 20 ms average of the second difference
        boolean[] bad = new boolean[n];
        for (int i = 2; i < n; i++) {
            double e = Math.abs(x[i] - 2.0 * x[i - 1] + x[i - 2]);
            if (e > 10 * ema + 0.03) bad[i] = true;
            else ema += (e - ema) * k;                           // spikes do not feed the average
        }
        int i = 0;
        while (i < n) {
            if (!bad[i]) { i++; continue; }
            int s = i;
            while (i < n && (bad[i] || (i + 1 < n && bad[i + 1]) )) i++;
            int e = i;                                            // [s, e) is a run of marked samples
            int a = Math.max(0, s - margin), b = Math.min(n - 1, e + margin);
            if (b - a <= maxSpan && a > 0 && b < n - 1) {
                float ya = x[a - 1], yb = x[b + 1];
                int len = b - a + 2;
                for (int j = a; j <= b; j++) x[j] = ya + (yb - ya) * (j - a + 1) / (float) len;
            }
        }
    }

    // ---- 3. noise reduction ------------------------------------------------------------------------

    static float[] denoise(float[] x, int rate, double over, double floor, Progress progress) {
        int n = x.length;
        int N = Fft.nextPow2((int) Math.round(rate * 0.023));   // ~23 ms frame: 1024 at 44.1 kHz
        N = Math.max(256, N);
        int H = N / 4;
        if (n < N * 4) return x;                                // too short to learn a noise profile

        Fft fft = new Fft(N);
        double[] win = new double[N];
        for (int i = 0; i < N; i++) win[i] = Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / N)); // sqrt-Hann

        // Frames fully inside the take: start s in [0, n - N], step H.
        int frames = (n - N) / H + 1;
        // Frame energies (time domain, windowed) to find the quietest ones.
        double[] energy = new double[frames];
        for (int f = 0; f < frames; f++) {
            int s = f * H;
            double e = 0;
            for (int i = 0; i < N; i += 2) {                    // every other sample is plenty for ranking
                double v = x[s + i] * win[i];
                e += v * v;
            }
            energy[f] = e;
        }
        Integer[] order = new Integer[frames];
        for (int i = 0; i < frames; i++) order[i] = i;
        Arrays.sort(order, (p, q) -> Double.compare(energy[p], energy[q]));
        int want = (int) Math.ceil(0.5 * rate / H);             // ~0.5 s of frames
        want = Math.max(8, Math.min(want, Math.max(8, frames / 5)));
        want = Math.min(want, frames);

        int bins = N / 2 + 1;
        double[] noise = new double[bins];                       // mean |X|^2 of the quiet frames
        double[] re = new double[N], im = new double[N];
        for (int q = 0; q < want; q++) {
            int s = order[q] * H;
            for (int i = 0; i < N; i++) { re[i] = x[s + i] * win[i]; im[i] = 0; }
            fft.forward(re, im);
            for (int k = 0; k < bins; k++) noise[k] += re[k] * re[k] + im[k] * im[k];
        }
        for (int k = 0; k < bins; k++) noise[k] /= want;
        double[] noiseMag = new double[bins];                    // smoothed over 5 bins, as a magnitude
        for (int k = 0; k < bins; k++) {
            double sum = 0; int c = 0;
            for (int d = -2; d <= 2; d++) { int j = k + d; if (j >= 0 && j < bins) { sum += noise[j]; c++; } }
            noiseMag[k] = Math.sqrt(sum / c);
        }

        // Pass 2: gain per bin, overlap-add. Frames start at -(N - H) so every sample is covered by four frames.
        float[] out = new float[n];
        double[] norm = new double[n];
        double[] prev = new double[bins];
        Arrays.fill(prev, 1.0);
        double[] g = new double[bins];
        int start = -(N - H);
        int total = (n - start + H - 1) / H;
        int done = 0;
        for (int s = start; s < n; s += H) {
            for (int i = 0; i < N; i++) {
                int idx = s + i;
                re[i] = (idx >= 0 && idx < n) ? x[idx] * win[i] : 0;
                im[i] = 0;
            }
            fft.forward(re, im);
            for (int k = 0; k < bins; k++) {
                double mag = Math.sqrt(re[k] * re[k] + im[k] * im[k]) + 1e-12;
                g[k] = Math.max(floor, 1 - over * noiseMag[k] / mag);
            }
            // smooth over frequency (3 taps), then over time (fast attack, slower release)
            double[] gs = new double[bins];
            for (int k = 0; k < bins; k++) {
                double a = g[Math.max(0, k - 1)], b = g[k], c = g[Math.min(bins - 1, k + 1)];
                gs[k] = 0.25 * a + 0.5 * b + 0.25 * c;
            }
            for (int k = 0; k < bins; k++) {
                double t = gs[k] >= prev[k] ? 0.7 * gs[k] + 0.3 * prev[k] : 0.2 * gs[k] + 0.8 * prev[k];
                prev[k] = t;
                re[k] *= t;
                im[k] *= t;
                if (k > 0 && k < N / 2) { re[N - k] = re[k]; im[N - k] = -im[k]; }   // keep it a real signal
            }
            fft.inverse(re, im);
            for (int i = 0; i < N; i++) {
                int idx = s + i;
                if (idx < 0 || idx >= n) continue;
                out[idx] += (float) (re[i] / N * win[i]);
                norm[idx] += win[i] * win[i];
            }
            if (progress != null && (++done & 255) == 0) progress.onProgress(done / (double) total);
        }
        for (int i = 0; i < n; i++) if (norm[i] > 1e-6) out[i] /= (float) norm[i];
        return out;
    }

    // ---- 4. gate -----------------------------------------------------------------------------------

    static void gate(float[] x, int rate, double reductionDb) {
        int block = Math.max(1, rate / 100);                     // 10 ms
        int blocks = x.length / block;
        if (blocks < 20) return;
        double[] rms = new double[blocks];
        for (int b = 0; b < blocks; b++) {
            double e = 0;
            for (int i = 0; i < block; i++) { double v = x[b * block + i]; e += v * v; }
            rms[b] = Math.sqrt(e / block);
        }
        double[] sorted = rms.clone();
        Arrays.sort(sorted);
        double floorRms = sorted[Math.max(0, blocks / 10)];       // the quietest tenth
        double threshold = Math.max(floorRms * 3, 0.0015);
        double closed = Math.pow(10, reductionDb / 20);
        int holdBlocks = 15;                                      // 150 ms
        int hold = 0;
        double gain = 1;
        double attack = 1 - Math.exp(-1.0 / (rate * 0.005));
        double release = 1 - Math.exp(-1.0 / (rate * 0.08));
        for (int b = 0; b < blocks; b++) {
            if (rms[b] >= threshold) hold = holdBlocks; else if (hold > 0) hold--;
            double target = hold > 0 ? 1.0 : closed;
            for (int i = 0; i < block; i++) {
                gain += (target - gain) * (target > gain ? attack : release);
                x[b * block + i] *= (float) gain;
            }
        }
        for (int i = blocks * block; i < x.length; i++) x[i] *= (float) gain;
    }

    // ---- 5. normalise ------------------------------------------------------------------------------

    static void normalise(float[] x, double targetPeakDb) {
        int[] hist = new int[4097];
        for (float v : x) {
            int b = (int) Math.min(4096, Math.abs(v) * 4096);
            hist[b]++;
        }
        long keep = (long) (x.length * 0.9995);
        long seen = 0;
        int bin = 0;
        for (; bin <= 4096; bin++) { seen += hist[bin]; if (seen >= keep) break; }
        double peak = Math.max(1e-9, (bin + 1) / 4096.0);
        if (peak < 1e-4) return;                                  // silence: do not blow up the noise floor
        double gain = Math.min(Math.pow(10, 24 / 20.0), Math.pow(10, targetPeakDb / 20) / peak);
        for (int i = 0; i < x.length; i++) x[i] = softLimit((float) (x[i] * gain));
    }

    /** Linear up to 0.9, then a tanh knee that never passes 1.0. */
    static float softLimit(float v) {
        float a = Math.abs(v);
        if (a <= 0.9f) return v;
        float y = 0.9f + 0.1f * (float) Math.tanh((a - 0.9f) / 0.1f);
        return v < 0 ? -y : y;
    }

    // ---- helpers (also used by the tests) ----------------------------------------------------------

    public static double rms(float[] x, int from, int to) {
        double e = 0;
        int c = 0;
        for (int i = Math.max(0, from); i < Math.min(x.length, to); i++) { e += x[i] * (double) x[i]; c++; }
        return c == 0 ? 0 : Math.sqrt(e / c);
    }
}
