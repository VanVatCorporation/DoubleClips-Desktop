package com.vanvatcorporation.doubleclips.voiceover;

/**
 * Plain radix-2 complex FFT on separate real / imaginary arrays. No dependencies (so it ports to Swift and Android
 * as it is). {@code n} must be a power of two. The inverse is NOT scaled: divide by n yourself.
 */
final class Fft {

    private final int n;
    private final double[] cos, sin;
    private final int[] rev;

    Fft(int n) {
        if (n < 2 || (n & (n - 1)) != 0) throw new IllegalArgumentException("FFT size must be a power of two: " + n);
        this.n = n;
        cos = new double[n / 2];
        sin = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            double a = -2 * Math.PI * i / n;
            cos[i] = Math.cos(a);
            sin[i] = Math.sin(a);
        }
        rev = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
    }

    int size() {
        return n;
    }

    void forward(double[] re, double[] im) {
        transform(re, im, false);
    }

    /** Unscaled inverse. */
    void inverse(double[] re, double[] im) {
        transform(re, im, true);
    }

    private void transform(double[] re, double[] im, boolean inverse) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int halfLen = len >> 1;
            int step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int k = 0; k < halfLen; k++) {
                    double wr = cos[k * step];
                    double wi = inverse ? -sin[k * step] : sin[k * step];
                    int a = i + k, b = a + halfLen;
                    double xr = re[b] * wr - im[b] * wi;
                    double xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }

    static int nextPow2(int v) {
        int p = 1;
        while (p < v) p <<= 1;
        return p;
    }
}
