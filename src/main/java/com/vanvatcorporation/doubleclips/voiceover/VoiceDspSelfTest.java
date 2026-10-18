package com.vanvatcorporation.doubleclips.voiceover;

import java.util.Random;

/**
 * Self-check for the voiceover DSP with synthetic signals. Run it with
 * {@code java -cp <classes> com.vanvatcorporation.doubleclips.voiceover.VoiceDspSelfTest}; it prints the numbers and
 * exits with 1 when one of them is out of range.
 */
public final class VoiceDspSelfTest {

    private static int failures = 0;

    private static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + "  " + detail);
        if (!ok) failures++;
    }

    private static double db(double a, double b) {
        return 20 * Math.log10(Math.max(a, 1e-12) / Math.max(b, 1e-12));
    }

    public static void main(String[] args) {
        enhancer();
        echo();
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void enhancer() {
        int rate = 44100, n = rate * 5;
        Random rnd = new Random(7);
        float[] x = new float[n];
        // 0..1 s: noise only. 1..5 s: a voice-like tone (300 Hz + 900 Hz) with a 50 Hz rumble; noise all the way.
        for (int i = 0; i < n; i++) {
            double t = i / (double) rate;
            double noise = rnd.nextGaussian() * 0.02;
            double voice = t >= 1 ? 0.25 * Math.sin(2 * Math.PI * 300 * t) + 0.10 * Math.sin(2 * Math.PI * 900 * t) : 0;
            double rumble = 0.08 * Math.sin(2 * Math.PI * 40 * t);
            x[i] = (float) (noise + voice + rumble);
        }
        float[] withClicks = x.clone();
        for (int c = 0; c < 6; c++) { int p = rate * (2 + c / 2) + 5000 * c + 1000; withClicks[p] += 0.6f; withClicks[p + 1] -= 0.5f; }

        VoiceEnhancer.Options o = new VoiceEnhancer.Options();
        o.normalise = false;
        float[] y = VoiceEnhancer.process(x, rate, o, null);

        double noiseBefore = VoiceEnhancer.rms(x, rate / 10, rate * 9 / 10), noiseAfter = VoiceEnhancer.rms(y, rate / 10, rate * 9 / 10);
        check("noise-only part gets quieter", db(noiseBefore, noiseAfter) >= 10,
                String.format("%.1f dB (want >= 10)", db(noiseBefore, noiseAfter)));

        // Voice level: compare the 300 Hz tone's RMS. Reference = the same tone alone (0.25/sqrt2 + 0.10/sqrt2 combined).
        double expected = Math.sqrt((0.25 * 0.25 + 0.10 * 0.10) / 2);
        double voiceAfter = VoiceEnhancer.rms(y, rate * 2, rate * 4);
        check("voice level preserved within 1 dB", Math.abs(db(voiceAfter, expected)) <= 1.0,
                String.format("%.2f dB off (rms %.4f, expected ~%.4f)", db(voiceAfter, expected), voiceAfter, expected));

        // The 40 Hz rumble in the noise-only part must be mostly gone.
        double rumbleAfter = VoiceEnhancer.rms(y, rate / 10, rate * 9 / 10);
        check("rumble + noise together below 0.01", rumbleAfter < 0.01, String.format("%.4f", rumbleAfter));

        float[] z = VoiceEnhancer.process(withClicks, rate, o, null);
        double maxClickAfter = 0, maxClickBefore = 0;
        for (int c = 0; c < 6; c++) {
            int p = rate * (2 + c / 2) + 5000 * c + 1000;
            for (int i = p - 3; i <= p + 4; i++) {
                maxClickBefore = Math.max(maxClickBefore, Math.abs(withClicks[i] - x[i]));
                maxClickAfter = Math.max(maxClickAfter, Math.abs(z[i] - y[i]));
            }
        }
        check("clicks removed", db(maxClickBefore, maxClickAfter) >= 10,
                String.format("%.1f dB lower (spike %.3f -> %.3f)", db(maxClickBefore, maxClickAfter), maxClickBefore, maxClickAfter));

        VoiceEnhancer.Options full = new VoiceEnhancer.Options();
        float[] f = VoiceEnhancer.process(x, rate, full, null);
        double peak = 0;
        for (float v : f) peak = Math.max(peak, Math.abs(v));
        check("normalised to about -3 dBFS", peak > 0.55 && peak < 0.95, String.format("peak %.3f (%.1f dBFS)", peak, 20 * Math.log10(peak)));
    }

    private static void echo() {
        int rate = 44100, n = rate * 8;
        Random rnd = new Random(11);
        // Reference: a "music" mix of sinusoids with slow amplitude changes.
        float[] ref = new float[n];
        double[] freq = new double[12], ph = new double[12];
        for (int k = 0; k < 12; k++) { freq[k] = 180 + rnd.nextDouble() * 2800; ph[k] = rnd.nextDouble() * 6.28; }
        for (int i = 0; i < n; i++) {
            double t = i / (double) rate, v = 0;
            for (int k = 0; k < 12; k++) v += Math.sin(2 * Math.PI * freq[k] * t + ph[k]) * (0.5 + 0.5 * Math.sin(2 * Math.PI * (0.3 + 0.1 * k) * t));
            ref[i] = (float) (v * 0.06);
        }
        int d1 = (int) (0.137 * rate), d2 = (int) (0.171 * rate);
        float[] echo = new float[n];
        for (int i = 0; i < n; i++) {
            double e = 0;
            if (i >= d1) e += 0.6 * ref[i - d1];
            if (i >= d2) e += 0.25 * ref[i - d2];
            echo[i] = (float) e;
        }
        float[] mic = new float[n];
        for (int i = 0; i < n; i++) mic[i] = echo[i] + (float) (rnd.nextGaussian() * 0.002);

        long t0 = System.currentTimeMillis();
        EchoCanceller.Result r = EchoCanceller.cancel(mic, ref, rate, null);
        long ms = System.currentTimeMillis() - t0;
        check("echo found", r.applied && Math.abs(r.delaySeconds - 0.137) < 0.02,
                String.format("applied=%s delay %.3f s corr %.2f (%d ms for %d s)", r.applied, r.delaySeconds, r.correlation, ms, n / rate));
        double before = VoiceEnhancer.rms(mic, rate * 4, rate * 8), after = VoiceEnhancer.rms(r.output, rate * 4, rate * 8);
        check("echo attenuated by 15 dB or more", db(before, after) >= 15, String.format("%.1f dB", db(before, after)));

        // Double talk: the speaker talks (1.3 kHz tone) from 3 s on; the tone must survive.
        float[] nearEnd = new float[n];
        float[] mic2 = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) rate;
            nearEnd[i] = t >= 3 ? (float) (0.2 * Math.sin(2 * Math.PI * 1300 * t)) : 0f;
            mic2[i] = mic[i] + nearEnd[i];
        }
        EchoCanceller.Result r2 = EchoCanceller.cancel(mic2, ref, rate, null);
        float[] resid = new float[n];
        for (int i = 0; i < n; i++) resid[i] = r2.output[i] - nearEnd[i];
        double residRms = VoiceEnhancer.rms(resid, rate * 5, rate * 8), nearRms = VoiceEnhancer.rms(nearEnd, rate * 5, rate * 8);
        check("voice kept during double talk (residual 12 dB under the voice)", db(nearRms, residRms) >= 12,
                String.format("residual %.4f vs voice %.4f = %.1f dB", residRms, nearRms, db(nearRms, residRms)));

        // No echo in the microphone: unchanged.
        float[] quiet = new float[n];
        for (int i = 0; i < n; i++) quiet[i] = (float) (rnd.nextGaussian() * 0.01);
        EchoCanceller.Result r3 = EchoCanceller.cancel(quiet, ref, rate, null);
        double qb = VoiceEnhancer.rms(quiet, 0, n), qa = VoiceEnhancer.rms(r3.output, 0, n);
        check("no echo -> signal not damaged", Math.abs(db(qb, qa)) < 1.0, String.format("%.2f dB change, applied=%s", db(qb, qa), r3.applied));
    }
}
