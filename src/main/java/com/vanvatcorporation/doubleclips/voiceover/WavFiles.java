package com.vanvatcorporation.doubleclips.voiceover;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** 16-bit mono PCM WAV writing and a plain resampler. No dependencies. */
public final class WavFiles {

    private WavFiles() {
    }

    public static void writeMono16(File file, float[] samples, int rate) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        int dataBytes = samples.length * 2;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file), 1 << 16))) {
            out.writeBytes("RIFF");
            out.writeInt(Integer.reverseBytes(36 + dataBytes));
            out.writeBytes("WAVE");
            out.writeBytes("fmt ");
            out.writeInt(Integer.reverseBytes(16));
            out.writeShort(Short.reverseBytes((short) 1));          // PCM
            out.writeShort(Short.reverseBytes((short) 1));          // mono
            out.writeInt(Integer.reverseBytes(rate));
            out.writeInt(Integer.reverseBytes(rate * 2));           // byte rate
            out.writeShort(Short.reverseBytes((short) 2));          // block align
            out.writeShort(Short.reverseBytes((short) 16));
            out.writeBytes("data");
            out.writeInt(Integer.reverseBytes(dataBytes));
            byte[] buf = new byte[1 << 15];
            int p = 0;
            for (float v : samples) {
                int s = Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
                buf[p++] = (byte) s;
                buf[p++] = (byte) (s >> 8);
                if (p == buf.length) { out.write(buf, 0, p); p = 0; }
            }
            if (p > 0) out.write(buf, 0, p);
        }
    }

    /** Linear-interpolation resampler; when going down, a short moving average first keeps the worst aliasing out. */
    public static float[] resample(float[] in, int from, int to) {
        if (from == to || in.length == 0) return in;
        float[] src = in;
        if (to < from) {
            int w = Math.max(1, Math.round(from / (float) to));
            if (w > 1) {
                src = new float[in.length];
                double acc = 0;
                for (int i = 0; i < in.length; i++) {
                    acc += in[i];
                    if (i >= w) acc -= in[i - w];
                    src[i] = (float) (acc / Math.min(w, i + 1));
                }
            }
        }
        int outLen = (int) ((long) in.length * to / from);
        float[] out = new float[outLen];
        double step = from / (double) to;
        for (int i = 0; i < outLen; i++) {
            double pos = i * step;
            int k = (int) pos;
            float f = (float) (pos - k);
            float a = src[Math.min(k, src.length - 1)], b = src[Math.min(k + 1, src.length - 1)];
            out[i] = a + (b - a) * f;
        }
        return out;
    }
}
