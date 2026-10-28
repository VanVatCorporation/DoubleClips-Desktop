package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.ui.renderer.ClipRenderer;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;

public class AudioUtils {

    /**
     * Decode audio samples from a .wav preview file and render a waveform Image sized to the clip.
     * Width  = clip.duration * pixelsPerSecond (clamped 64…4096)
     * Height = TRACK_HEIGHT - 4  (same as clip view height)
     *
     * Visual style: dark navy background, teal/blue symmetric bars centred on midline.
     */
    public static Image generateAudioWaveformImage(String wavPath, Clip clip,
                                                      float pixelsPerSecond, int trackHeight,
                                                      int barWidth, int barGap) {

        final int bmpW = (int) Math.max(64, Math.min(4096, clip.duration * pixelsPerSecond));
        final int bmpH = trackHeight - 4;

        ArrayList<Short> samples = new ArrayList<>(64000);
        // A keyframed volume is drawn per bar below; a plain one scales the samples as they are read.
        final boolean keyedVolume = com.vanvatcorporation.doubleclips.data.editing.VolumeCurve.isAnimated(clip);
        final com.vanvatcorporation.doubleclips.data.editing.ClipTimeMap speedMap = clip.timeMapSnapshot();
        final double[] sourceSeconds = {clip.duration};

        try {
            File f = new File(wavPath);
            if (!f.exists()) return null;

            AudioInputStream ais = AudioSystem.getAudioInputStream(f);
            javax.sound.sampled.AudioFormat fmt = ais.getFormat();
            
            int bytesPerFrame = fmt.getFrameSize();
            long startFrame = (long) (clip.startClipTrim * fmt.getSampleRate());
            // The clip shows clip.duration TIMELINE seconds, which consume more or less SOURCE (speed, or a speed curve).
            sourceSeconds[0] = speedMap.sourceOffset(clip.duration);
            long endFrame = startFrame + (long) (sourceSeconds[0] * fmt.getSampleRate());
            
            if (startFrame > 0) {
                long skipped = 0;
                long toSkip = startFrame * bytesPerFrame;
                while (skipped < toSkip) {
                    long res = ais.skip(toSkip - skipped);
                    if (res <= 0) break;
                    skipped += res;
                }
            }
            
            byte[] buf = new byte[4096];
            long framesRead = 0;
            long framesToRead = endFrame - startFrame;
            
            boolean isBigEndian = fmt.isBigEndian();
            
            while (framesRead < framesToRead) {
                int read = ais.read(buf);
                if (read < 0) break;
                ClipRenderer.applyVolume(buf, read, keyedVolume ? 1f : com.vanvatcorporation.doubleclips.data.editing.VolumeCurve.plain(clip));
                // Process 16-bit PCM
                for (int i = 0; i < read - 1; i += 2) {
                    short sample;
                    if (isBigEndian) {
                        sample = (short) (((buf[i] & 0xFF) << 8) | (buf[i + 1] & 0xFF));
                    } else {
                        sample = (short) ((buf[i] & 0xFF) | ((buf[i + 1] & 0xFF) << 8));
                    }
                    samples.add(sample);
                }
                framesRead += read / bytesPerFrame;
            }
            ais.close();
            
        } catch (Exception e) {
            e.printStackTrace();
            return null; // Handle fallback outside
        }

        // Downsample into bars
        final int STRIDE = barWidth + barGap;
        final int barCount = Math.max(1, bmpW / STRIDE);
        float[] amps = new float[barCount];

        if (samples.isEmpty()) {
            java.util.Arrays.fill(amps, 0f);
        } else {
            // Bar b covers timeline [b, b+1] * duration / barCount; the samples it shows are the source between those moments.
            final double total = Math.max(1e-6, sourceSeconds[0]);
            for (int b = 0; b < barCount; b++) {
                int start = (int) Math.min(samples.size() - 1, speedMap.sourceOffset(clip.duration * b / (double) barCount) / total * samples.size());
                int end = (int) Math.min(samples.size(), Math.max(start + 1, speedMap.sourceOffset(clip.duration * (b + 1) / (double) barCount) / total * samples.size()));
                long rms = 0;
                for (int s = start; s < end; s++) {
                    long v = samples.get(s);
                    rms += v * v;
                }
                if (end > start) {
                    amps[b] = (float) Math.sqrt((double) rms / (end - start)) / 32768f; // 0..1
                }
                if (keyedVolume) {
                    float at = clip.startTime + clip.duration * (b + 0.5f) / barCount;
                    amps[b] = Math.min(1f, amps[b] * com.vanvatcorporation.doubleclips.data.editing.VolumeCurve.gainAt(clip, at));
                }
            }
        }

        // Draw waveform using AWT
        BufferedImage bmp = new BufferedImage(bmpW, bmpH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bmp.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Background: dark navy
        g.setColor(new Color(0x0D, 0x1B, 0x2A));
        g.fillRect(0, 0, bmpW, bmpH);

        // Baseline
        g.setColor(new Color(0x1E, 0x90, 0xFF, 60)); // 60 alpha out of 255
        g.setStroke(new BasicStroke(1f));
        float midY = bmpH / 2f;
        g.drawLine(0, (int) midY, bmpW, (int) midY);

        // Bars
        final float MAX_HALF = midY - 2f;
        for (int b = 0; b < barCount; b++) {
            // Boost the amplitude for better visibility (common in editors)
            float amp = amps[b];
            float boostedAmp = Math.min(1.0f, amp * 1.125f); // TODO: Hardcoded 1.125f boosted, should be in settings
            float halfBar = Math.max(2f, boostedAmp * MAX_HALF);
            int left = b * STRIDE;
            int top = (int) (midY - halfBar);
            int height = (int) (halfBar * 2);

            int alpha = (int) (10 + 245 * amp);
            alpha = Math.max(0, Math.min(255, alpha));
            g.setColor(new Color(0x1E, 0x90, 0xFF, alpha));
            g.fillRoundRect(left, top, barWidth, height, 3, 3);
        }
        g.dispose();

        // Convert to JavaFX Image
        WritableImage fxImage = new WritableImage(bmpW, bmpH);
        int[] argb = new int[bmpW * bmpH];
        bmp.getRGB(0, 0, bmpW, bmpH, argb, 0, bmpW);
        fxImage.getPixelWriter().setPixels(0, 0, bmpW, bmpH, PixelFormat.getIntArgbInstance(), argb, 0, bmpW);
        return fxImage;
    }

    /**
     * The audio of a VIDEO clip as a thin band for the bottom of its tiles (the iOS app shows the same). A
     * translucent dark strip with white mirrored bars, drawn for the part of the source the clip shows (trim, speed
     * and speed curves included), scaled by the clip's volume. Unlike {@link #generateAudioWaveformImage} the samples are
     * never collected: the file is streamed once into per-bar sums, so a long video costs a few milliseconds and no
     * memory. Returns null when the preview WAV is missing or not 16-bit PCM.
     */
    public static Image generateVideoWaveformBand(String wavPath, Clip clip, float pixelsPerSecond, int bandHeight) {
        final int bmpW = (int) Math.max(64, Math.min(4096, clip.duration * pixelsPerSecond));
        final int bars = bmpW;                                    // one bar per pixel column
        final double[] sumSq = new double[bars];
        final int[] count = new int[bars];
        try {
            File f = new File(wavPath);
            if (!f.exists()) return null;
            final com.vanvatcorporation.doubleclips.data.editing.ClipTimeMap speedMap = clip.timeMapSnapshot();
            try (AudioInputStream ais = AudioSystem.getAudioInputStream(f)) {
                javax.sound.sampled.AudioFormat fmt = ais.getFormat();
                if (fmt.getSampleSizeInBits() != 16 || fmt.isBigEndian()) return null;
                int frameSize = fmt.getFrameSize();
                double rate = fmt.getSampleRate();
                long startFrame = (long) (clip.startClipTrim * rate);
                long toSkip = startFrame * frameSize, skipped = 0;
                while (skipped < toSkip) {
                    long r = ais.skip(toSkip - skipped);
                    if (r <= 0) break;
                    skipped += r;
                }
                // Source frame (relative to the trim) at which each bar ends.
                double[] edge = new double[bars];
                for (int b = 0; b < bars; b++) edge[b] = speedMap.sourceOffset(clip.duration * (b + 1) / (double) bars) * rate;
                float volume = com.vanvatcorporation.doubleclips.data.editing.VolumeCurve.plain(clip);
                // A keyframed volume: each bar is as loud as the volume at its own moment.
                final boolean keyedVolume = com.vanvatcorporation.doubleclips.data.editing.VolumeCurve.isAnimated(clip);
                final float[] barVolume = new float[bars];
                for (int b = 0; b < bars; b++) {
                    barVolume[b] = keyedVolume
                            ? com.vanvatcorporation.doubleclips.data.editing.VolumeCurve.gainAt(clip, clip.startTime + clip.duration * (b + 0.5f) / bars)
                            : volume;
                }
                byte[] buf = new byte[frameSize * 2048];
                long frame = 0;
                int bar = 0;
                int read;
                while (bar < bars && (read = ais.read(buf)) > 0) {
                    int frames = read / frameSize;
                    for (int i = 0; i < frames && bar < bars; i++, frame++) {
                        while (bar < bars - 1 && frame >= edge[bar]) bar++;
                        int o = i * frameSize;
                        short s = (short) ((buf[o] & 0xFF) | (buf[o + 1] << 8));   // the first channel is enough for a band
                        double v = s / 32768.0 * barVolume[bar];
                        sumSq[bar] += v * v;
                        count[bar]++;
                        if (frame >= edge[bars - 1]) { bar = bars; break; }
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }

        BufferedImage bmp = new BufferedImage(bmpW, bandHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bmp.createGraphics();
        g.setColor(new Color(0, 0, 0, 115));                      // a scrim, so the bars read over any picture
        g.fillRect(0, 0, bmpW, bandHeight);
        float mid = bandHeight / 2f;
        g.setColor(new Color(255, 255, 255, 215));
        for (int b = 0; b < bars; b++) {
            if (count[b] == 0) continue;
            double rms = Math.sqrt(sumSq[b] / count[b]);
            double h = Math.min(1.0, Math.pow(rms * 2.0, 0.7));    // lifted so quiet speech still shows
            int half = (int) Math.max(1, h * (mid - 1));
            g.fillRect(b, (int) (mid - half), 1, half * 2);
        }
        g.dispose();

        WritableImage fx = new WritableImage(bmpW, bandHeight);
        int[] argb = new int[bmpW * bandHeight];
        bmp.getRGB(0, 0, bmpW, bandHeight, argb, 0, bmpW);
        fx.getPixelWriter().setPixels(0, 0, bmpW, bandHeight, PixelFormat.getIntArgbInstance(), argb, 0, bmpW);
        return fx;
    }
}
