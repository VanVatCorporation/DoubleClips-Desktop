package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
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

        try {
            File f = new File(wavPath);
            if (!f.exists()) return null;

            AudioInputStream ais = AudioSystem.getAudioInputStream(f);
            javax.sound.sampled.AudioFormat fmt = ais.getFormat();
            
            int bytesPerFrame = fmt.getFrameSize();
            long startFrame = (long) (clip.startClipTrim * fmt.getSampleRate());
            long endFrame = startFrame + (long) (clip.duration * fmt.getSampleRate());
            
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
            int samplesPerBar = Math.max(1, samples.size() / barCount);
            for (int b = 0; b < barCount; b++) {
                int start = b * samplesPerBar;
                int end = Math.min(start + samplesPerBar, samples.size());
                long rms = 0;
                for (int s = start; s < end; s++) {
                    long v = samples.get(s);
                    rms += v * v;
                }
                if (end > start) {
                    amps[b] = (float) Math.sqrt((double) rms / (end - start)) / 32768f; // 0..1
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
            float boostedAmp = Math.min(1.0f, amp * 2.5f); 
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
}
