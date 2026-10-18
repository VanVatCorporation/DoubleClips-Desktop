package com.vanvatcorporation.doubleclips.voiceover;

import java.util.Arrays;

/**
 * "Echo reduction": removes what the speakers played from a finished microphone recording. Pure Java on
 * {@code float[]}, no platform types (portable to Android as it is, to Swift line by line).
 * <p>
 * It works on the finished take because the speaker signal is fully known: it is the timeline's own audio mix at the
 * times the recording covered ({@code reference}, same sample rate and the same start as the recording). So there is no
 * live ring buffer and no clock-drift problem to solve while recording, and the filter can be tuned in two passes.
 * <ol>
 *   <li>Both signals are low-passed and decimated to about 11 kHz (speech and what speakers play are mostly below
 *       5 kHz; this keeps the filter cheap).</li>
 *   <li>The delay between the reference and what the microphone heard (output latency + the air) is found with an
 *       FFT cross-correlation over the first 30 s, searched up to 0.6 s.</li>
 *   <li>An NLMS adaptive filter (about 160 ms) learns the path from the delayed reference to the microphone, first
 *       over the start of the take to converge, then over the whole take. A Geigel double-talk detector (scaled by the echo gain found in step 2) freezes the
 *       adaptation while the speaker is talking, so the voice is not learned as "echo".</li>
 *   <li>The echo estimate is upsampled and subtracted from the full-rate microphone signal.</li>
 * </ol>
 * It is best-effort (the room, a laptop speaker and a non-linear amplifier all leave a residue); headphones are always
 * better. Nothing is changed when no echo is found.
 */
public final class EchoCanceller {

    private EchoCanceller() {
    }

    /** What happened, for the log / UI. */
    public static final class Result {
        public float[] output;
        public double delaySeconds;          // found delay of the echo
        public double correlation;           // 0..1, how clearly the echo was found
        public boolean applied;
        public double reductionDb;           // how much quieter the signal got where the reference was loud
    }

    private static final int TARGET_RATE = 11025;
    private static final double MAX_DELAY_S = 0.6;
    private static final double TAIL_S = 0.16;

    public static Result cancel(float[] mic, float[] reference, int rate, VoiceEnhancer.Progress progress) {
        Result r = new Result();
        r.output = mic;
        if (mic == null || reference == null || mic.length < rate / 2) return r;
        int n = mic.length;
        float[] ref = reference.length == n ? reference : Arrays.copyOf(reference, n);

        int d = Math.max(1, Math.round(rate / (float) TARGET_RATE));
        int sr = rate / d;
        float[] m = decimate(mic, d);
        float[] x = decimate(ref, d);
        int len = Math.min(m.length, x.length);

        // Nothing played: nothing to remove.
        if (VoiceEnhancer.rms(x, 0, len) < 1e-4) return r;

        // 1. delay
        int maxLag = (int) (MAX_DELAY_S * sr);
        int probe = Math.min(len, 30 * sr);
        double[] best = xcorrPeak(m, x, probe, maxLag);
        int lag = (int) best[0];
        r.correlation = best[1];
        r.delaySeconds = lag / (double) sr;
        double echoGain = best[2];
        report(progress, 0.15);
        if (r.correlation < 0.03) return r;                    // the microphone does not hear the speakers

        // 2. NLMS, two passes: converge on the start, then run on the whole take
        int taps = Math.max(64, (int) (TAIL_S * sr));
        float[] w = new float[taps];
        int warm = Math.min(len, 5 * sr);
        nlms(m, x, lag, echoGain, w, 0, warm, null, null);
        report(progress, 0.35);
        float[] est = new float[len];
        nlms(m, x, lag, echoGain, w, 0, len, est, p -> report(progress, 0.35 + 0.6 * p));

        // 3. upsample the estimate (linear) and subtract
        float[] out = Arrays.copyOf(mic, n);
        for (int i = 0; i < n; i++) {
            double pos = i / (double) d;
            int k = (int) pos;
            if (k >= len - 1) { k = len - 2; if (k < 0) break; }
            double f = pos - k;
            out[i] -= (float) (est[k] * (1 - f) + est[k + 1] * f);
        }
        // Where the reference was loud: how much quieter did the signal get?
        double before = 0, after = 0;
        for (int i = 0; i + 1 < len; i += 4) {
            if (Math.abs(x[i]) < 0.05) continue;
            before += m[i] * (double) m[i];
            double v = m[i] - est[i];
            after += v * v;
        }
        r.reductionDb = before > 0 && after > 0 ? 10 * Math.log10(before / after) : 0;
        r.output = out;
        r.applied = true;
        report(progress, 1.0);
        return r;
    }

    private static void report(VoiceEnhancer.Progress p, double f) {
        if (p != null) p.onProgress(Math.max(0, Math.min(1, f)));
    }

    /** Low-pass (windowed sinc, 63 taps, cut at 0.45 of the new rate) then keep every d-th sample. */
    static float[] decimate(float[] in, int d) {
        if (d <= 1) return in;
        int taps = 63, half = taps / 2;
        double fc = 0.45 / d;                                    // cycles per input sample
        double[] h = new double[taps];
        double sum = 0;
        for (int i = 0; i < taps; i++) {
            int k = i - half;
            double s = k == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * k) / (Math.PI * k);
            double w = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (taps - 1));
            h[i] = s * w;
            sum += h[i];
        }
        for (int i = 0; i < taps; i++) h[i] /= sum;
        int outLen = in.length / d;
        float[] out = new float[outLen];
        for (int o = 0; o < outLen; o++) {
            int c = o * d;
            double acc = 0;
            for (int i = 0; i < taps; i++) {
                int idx = c + i - half;
                if (idx >= 0 && idx < in.length) acc += h[i] * in[idx];
            }
            out[o] = (float) acc;
        }
        return out;
    }

    /**
     * {lag in samples (0..maxLag) at which {@code m[n] ~ x[n - lag]} correlates most, the normalised peak (0..1), the
     * echo gain (how much louder or quieter the reference is in the microphone)}.
     */
    static double[] xcorrPeak(float[] m, float[] x, int count, int maxLag) {
        int size = Fft.nextPow2(count + maxLag + 1);
        Fft fft = new Fft(size);
        double[] ar = new double[size], ai = new double[size];
        double[] br = new double[size], bi = new double[size];
        double em = 0, ex = 0;
        for (int i = 0; i < count; i++) {
            ar[i] = m[i];
            br[i] = x[i];
            em += m[i] * (double) m[i];
            ex += x[i] * (double) x[i];
        }
        fft.forward(ar, ai);
        fft.forward(br, bi);
        // cross-correlation c[lag] = sum m[n] * x[n - lag]  ->  M * conj(X)
        for (int i = 0; i < size; i++) {
            double re = ar[i] * br[i] + ai[i] * bi[i];
            double im = ai[i] * br[i] - ar[i] * bi[i];
            ar[i] = re;
            ai[i] = im;
        }
        fft.inverse(ar, ai);
        double bestV = 0;
        int bestLag = 0;
        for (int lag = 0; lag <= maxLag; lag++) {
            double v = Math.abs(ar[lag] / size);
            if (v > bestV) { bestV = v; bestLag = lag; }
        }
        double denom = Math.sqrt(em * ex);
        double gain = ex > 0 ? bestV / ex : 0;                   // regression of the microphone on the reference
        return new double[]{bestLag, denom > 0 ? Math.min(1, bestV / denom) : 0, gain};
    }

    /**
     * One NLMS pass over [from, to): estimates the echo in {@code m} from {@code x} delayed by {@code lag}; the
     * estimate goes to {@code est} (when not null), the weights {@code wr} (stored newest-tap-last) are updated in
     * place. The reference is copied into a zero-padded array so the two inner loops need no bounds checks and run
     * over increasing indices (the JIT vectorises them).
     */
    private static void nlms(float[] m, float[] x, int lag, double echoGain, float[] wr, int from, int to,
                             float[] est, VoiceEnhancer.Progress progress) {
        int taps = wr.length;
        int pad = taps + lag;
        float[] xp = new float[pad + Math.min(x.length, to)];
        System.arraycopy(x, 0, xp, pad, Math.min(x.length, to));
        double mu = 0.3, eps = 1e-4;
        int hang = Math.max(8, TARGET_RATE / 100);                 // keep adaptation frozen 10 ms after double talk
        int frozen = 0;
        double power = 0;                                          // energy of the last `taps` reference samples
        int[] dq = new int[taps + 2];                              // sliding max of |x| over one tail (monotonic deque)
        int head = 0, tail = 0;
        int mask = dq.length;
        for (int i = from; i < to; i++) {
            int xi = i - lag;                                      // newest reference sample the filter sees
            int base = xi + pad;
            float xn = xp[base];
            power += xn * (double) xn;
            float oldv = xp[base - taps];
            power -= oldv * (double) oldv;
            if (power < 0) power = 0;

            float ax = Math.abs(xn);
            while (tail > head && Math.abs(xp[dq[(tail - 1) % mask] + pad]) <= ax) tail--;
            dq[tail % mask] = xi;
            tail++;
            while (tail > head && dq[head % mask] <= xi - taps) head++;
            float maxX = tail > head ? Math.abs(xp[dq[head % mask] + pad]) : 0f;

            int st = base - taps + 1;
            float a0 = 0, a1 = 0, a2 = 0, a3 = 0;
            int k = 0;
            for (; k + 3 < taps; k += 4) {
                a0 += wr[k] * xp[st + k];
                a1 += wr[k + 1] * xp[st + k + 1];
                a2 += wr[k + 2] * xp[st + k + 2];
                a3 += wr[k + 3] * xp[st + k + 3];
            }
            for (; k < taps; k++) a0 += wr[k] * xp[st + k];
            double y = (double) a0 + a1 + a2 + a3;
            if (est != null) est[i] = (float) y;
            double e = m[i] - y;

            // Geigel: the microphone is louder than any echo of the recent reference could be -> someone is talking.
            if (maxX > 1e-4 && Math.abs(m[i]) > echoGain * maxX) frozen = hang;
            if (frozen > 0) {
                frozen--;
            } else if (power > 1e-6) {
                float g = (float) (mu * e / (power + eps));
                for (int j = 0; j < taps; j++) wr[j] += g * xp[st + j];
            }
            if (progress != null && ((i - from) & 0x3FFF) == 0) progress.onProgress((i - from) / (double) Math.max(1, to - from));
        }
    }
}
