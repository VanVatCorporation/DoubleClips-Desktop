package com.vanvatcorporation.doubleclips.ui.renderer;

import com.vanvatcorporation.doubleclips.FFmpegEdit;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.VideoProperties;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.effect.Blend;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.ColorInput;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import javax.sound.sampled.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Frame-accurate ClipRenderer for the Desktop.
 *
 * Uses ImageView and WritableImage for robust rendering.
 * Video frames are decoded via FFmpeg in a background thread to prevent UI freezing.
 * Audio is handled via SourceDataLine (javax.sound.sampled).
 */
public class ClipRenderer {

    public final Clip clip;
    private final ProjectData data;
    private final VideoSettings settings;
    private final Pane renderPane;

    // --- VIEW ---
    private Node viewNode;
    private WritableImage writableImage;
    private Image staticImage;

    // --- AUDIO ---
    private SourceDataLine audioLine;
    private RandomAccessFile wavFile;
    private long wavDataStart;
    private long wavBytesPerSecond;
    private Thread audioThread;
    private volatile boolean audioRunning = false;

    // --- DECODING ---
    private ExecutorService decoderService;
    private ExecutorService audioService;
    private final AtomicBoolean isDecoding = new AtomicBoolean(false);
    private final AtomicBoolean isAudioBursting = new AtomicBoolean(false);
    private float lastRequestedTime = -1f;
    private float lastRenderedTime = -1f;

    // --- Transforms ---
    private float posX = 0, posY = 0;
    private float scaleX = 1, scaleY = 1;
    private float rot = 0;
    private float opacity = 1;
    private float hue = 0;
    private float saturation = 1;
    private float brightness = 0;
    private float temperature = 6500;

    public boolean isPlaying = false;

    public ClipRenderer(Clip clip, ProjectData data, VideoSettings settings, Pane renderPane) {
        this.clip = clip;
        this.data = data;
        this.settings = settings;
        this.renderPane = renderPane;

        this.decoderService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Decoder-" + clip.getClipName());
            t.setDaemon(true);
            return t;
        });

        this.audioService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AudioService-" + clip.getClipName());
            t.setDaemon(true);
            return t;
        });

        try {
            int w = Math.max(1, clip.width > 0 ? clip.width : settings.videoWidth);
            int h = Math.max(1, clip.height > 0 ? clip.height : settings.videoHeight);

            switch (clip.type) {
                case VIDEO:
                    writableImage = new WritableImage(w, h);
                    viewNode = new ImageView(writableImage);
                    if (clip.isClipHasAudio() && !clip.isMute()) {
                        openAudioLine(clip.getAbsolutePreviewPath(data, ".wav"));
                    }
                    break;

                case IMAGE:
                    File imgFile = new File(clip.getAbsolutePath(data));
                    if (imgFile.exists()) {
                        staticImage = new Image(imgFile.toURI().toString(), w, h, true, true);
                        viewNode = new ImageView(staticImage);
                    } else {
                        viewNode = new ImageView();
                    }
                    break;

                case AUDIO:
                    openAudioLine(clip.getAbsolutePreviewPath(data, ".wav"));
                    break;
                
                case TEXT:
                    Label label = new Label(clip.textContent != null ? clip.textContent : "");
                    label.setTextFill(Color.BLACK);
                    float fSize = clip.fontSize > 0 ? clip.fontSize : 48;
                    label.setFont(new Font(fSize));
                    label.setWrapText(true);
                    // Set a default width if none provided
                    label.setMaxWidth(clip.width > 0 ? clip.width : settings.videoWidth);
                    viewNode = label;
                    break;

                default:
                    break;
            }

            if (viewNode != null) {
                viewNode.setMouseTransparent(true);
                viewNode.setVisible(false);
                Platform.runLater(() -> {
                    if (!renderPane.getChildren().contains(viewNode)) {
                        renderPane.getChildren().add(viewNode);
                    }
                });
            }

            applyTransformation();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean isVisible(float playheadTime) {
        return playheadTime >= clip.startTime && playheadTime < clip.startTime + clip.duration;
    }

    public void renderFrame(float playheadTime, boolean isSeekingOnly) {
        boolean visible = isVisible(playheadTime);
        
        if (viewNode != null) {
            if (viewNode.isVisible() != visible) {
                viewNode.setVisible(visible);
            }
        }

        if (!visible) {
            stopAudio();
            isPlaying = false;
            return;
        }

        updateTransforms(playheadTime);
        
        if (clip.type == ClipType.TEXT && viewNode instanceof Label label) {
            String currentText = clip.textContent != null ? clip.textContent : "";
            if (!label.getText().equals(currentText)) {
                label.setText(currentText);
            }
            float currentSize = clip.fontSize > 0 ? clip.fontSize : 48;
            if (label.getFont().getSize() != currentSize) {
                label.setFont(new Font(currentSize));
            }
        }

        float clipTime = Math.max(0, playheadTime - clip.startTime + clip.startClipTrim);

        if (isSeekingOnly) {
            stopAudio();
            isPlaying = false;
            if (clip.type == ClipType.VIDEO || clip.type == ClipType.AUDIO) {
                if (clip.type == ClipType.VIDEO) requestVideoFrame(clipTime);
                requestAudioBurst(clipTime);
            }
        } else {
            if (clip.type == ClipType.VIDEO) {
                requestVideoFrame(clipTime);
            }
            if (!isPlaying) {
                isPlaying = true;
                startAudioAt(clipTime);
            }
        }
    }

    private void requestVideoFrame(float clipTime) {
        if (writableImage == null) return;
        
        // Avoid redundant requests
        if (Math.abs(clipTime - lastRequestedTime) < 0.001f) return;
        lastRequestedTime = clipTime;

        if (isDecoding.compareAndSet(false, true)) {
            decoderService.submit(() -> {
                try {
                    decodeFrame(clipTime);
                } finally {
                    isDecoding.set(false);
                    // If a newer time was requested while we were decoding, handle it
                    if (Math.abs(lastRequestedTime - clipTime) > 0.01f) {
                        requestVideoFrame(lastRequestedTime);
                    }
                }
            });
        }
    }

    private void decodeFrame(float clipTime) {
        String previewPath = clip.getAbsolutePreviewPath(data);
        File previewFile = new File(previewPath);
        if (!previewFile.exists()) {
            previewFile = new File(clip.getAbsolutePath(data));
            if (!previewFile.exists()) return;
        }

        int w = (int) writableImage.getWidth();
        int h = (int) writableImage.getHeight();

        List<String> cmd = new ArrayList<>();
        cmd.add(FFmpegEdit.getFfmpegPath());
        cmd.add("-accurate_seek");
        cmd.add("-ss"); cmd.add(String.format(java.util.Locale.US, "%.6f", clipTime));
        cmd.add("-i"); cmd.add(previewFile.getAbsolutePath());
        cmd.add("-vframes"); cmd.add("1");
        cmd.add("-vf"); cmd.add("scale=" + w + ":" + h);
        cmd.add("-f");        cmd.add("rawvideo");
        cmd.add("-pix_fmt");  cmd.add("bgra");
        cmd.add("pipe:1");

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();

            int expectedBytes = w * h * 4;
            byte[] buf = proc.getInputStream().readNBytes(expectedBytes);
            int exitCode = proc.waitFor();

            if (buf.length == expectedBytes) {
                int[] pixels = new int[w * h];
                for (int i = 0; i < pixels.length; i++) {
                    int base = i * 4;
                    int b = buf[base] & 0xFF;
                    int g = buf[base + 1] & 0xFF;
                    int r = buf[base + 2] & 0xFF;
                    int a = buf[base + 3] & 0xFF;
                    pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
                }

                Platform.runLater(() -> {
                    if (writableImage != null) {
                        writableImage.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
                        lastRenderedTime = clipTime;
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void openAudioLine(String wavPath) {
        try {
            File f = new File(wavPath);
            if (!f.exists()) return;

            wavFile = new RandomAccessFile(f, "r");
            WavHeader header = WavHeader.parse(wavFile);
            if (header == null) return;

            wavDataStart = header.dataOffset;
            wavBytesPerSecond = (long) header.sampleRate * header.channels * (header.bitsPerSample / 8);

            AudioFormat fmt = new AudioFormat(header.sampleRate, header.bitsPerSample, header.channels, true, false);
            audioLine = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
            audioLine.open(fmt, 16384);
            audioLine.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void requestAudioBurst(float clipTime) {
        if (audioLine == null || wavFile == null) return;

        if (isAudioBursting.compareAndSet(false, true)) {
            audioService.submit(() -> {
                try {
                    long offset = wavDataStart + (long) (clipTime * wavBytesPerSecond);
                    int frameSize = audioLine.getFormat().getFrameSize();
                    offset = (offset / frameSize) * frameSize;

                    int burstSize = (int) (wavBytesPerSecond * 0.05); // 50ms burst
                    burstSize = (burstSize / frameSize) * frameSize;
                    if (burstSize <= 0) return;

                    byte[] buf = new byte[burstSize];
                    int read;
                    synchronized (wavFile) {
                        wavFile.seek(offset);
                        read = wavFile.read(buf);
                    }

                    if (read > 0) {
                        audioLine.flush();
                        audioLine.write(buf, 0, read);
                    }
                } catch (Exception ignored) {
                } finally {
                    isAudioBursting.set(false);
                }
            });
        }
    }

    private void startAudioAt(float clipTime) {
        if (audioLine == null || wavFile == null) return;
        stopAudio();
        audioRunning = true;
        audioThread = new Thread(() -> {
            try {
                long offset = wavDataStart + (long) (clipTime * wavBytesPerSecond);
                int frameSize = audioLine.getFormat().getFrameSize();
                offset = (offset / frameSize) * frameSize;

                synchronized (wavFile) {
                    wavFile.seek(offset);
                }

                byte[] buf = new byte[4096];
                while (audioRunning) {
                    int read;
                    synchronized (wavFile) {
                        read = wavFile.read(buf);
                    }
                    if (read < 0) break;
                    audioLine.write(buf, 0, read);
                }
            } catch (Exception ignored) {}
        }, "Audio-" + clip.getClipName());
        audioThread.setDaemon(true);
        audioThread.start();
    }

    private void stopAudio() {
        audioRunning = false;
        if (audioThread != null) {
            audioThread.interrupt();
            audioThread = null;
        }
        if (audioLine != null) {
            audioLine.flush();
        }
    }

    private void updateTransforms(float time) {
        posX = clip.keyframes.getValueAtTime(clip, time, VideoProperties.ValueType.PosX);
        posY = clip.keyframes.getValueAtTime(clip, time, VideoProperties.ValueType.PosY);
        rot = clip.keyframes.getValueAtTime(clip, time, VideoProperties.ValueType.Rot);
        scaleX = clip.keyframes.getValueAtTime(clip, time, VideoProperties.ValueType.ScaleX);
        scaleY = clip.keyframes.getValueAtTime(clip, time, VideoProperties.ValueType.ScaleY);
        opacity = clip.keyframes.getValueAtTime(clip, time, VideoProperties.ValueType.Opacity);
        hue = clip.keyframes.getValueAtTime(clip, time, VideoProperties.ValueType.Hue);
        saturation = clip.keyframes.getValueAtTime(clip, time, VideoProperties.ValueType.Saturation);
        brightness = clip.keyframes.getValueAtTime(clip, time, VideoProperties.ValueType.Brightness);
        temperature = clip.keyframes.getValueAtTime(clip, time, VideoProperties.ValueType.Temperature);

        Platform.runLater(this::applyTransformation);
    }

    private void applyTransformation() {
        if (viewNode == null) return;

        double nodeW = viewNode.getLayoutBounds().getWidth();
        double nodeH = viewNode.getLayoutBounds().getHeight();
        if (nodeW == 0 || nodeH == 0) {
            // Might not be laid out yet
            if (viewNode instanceof Label label) {
                nodeW = label.prefWidth(-1);
                nodeH = label.prefHeight(-1);
            } else if (viewNode instanceof ImageView iv && iv.getImage() != null) {
                nodeW = iv.getImage().getWidth();
                nodeH = iv.getImage().getHeight();
            }
        }

        double baseW = nodeW;
        double baseH = nodeH;
        double extraScaleX = 1.0;
        double extraScaleY = 1.0;

        if (settings.isStretchToFull() && clip.type != ClipType.TEXT && clip.type != ClipType.AUDIO) {
            extraScaleX = (double) settings.videoWidth / Math.max(1, baseW);
            extraScaleY = (double) settings.videoHeight / Math.max(1, baseH);
            baseW = settings.videoWidth;
            baseH = settings.videoHeight;
        }

        double rad = Math.toRadians(rot);
        double sin = Math.abs(Math.sin(rad));
        double cos = Math.abs(Math.cos(rad));

        // FFmpeg's "expanded" dimensions during rotation
        double expandedW = baseW * cos + baseH * sin;
        double expandedH = baseW * sin + baseH * cos;

        float finalPosX = posX;
        float finalPosY = posY;

        if (clip.type == ClipType.TEXT) {
            // Match FFmpeg's x=(w-tw)/2 + posX
            finalPosX += (settings.videoWidth - nodeW) / 2;
            finalPosY += (settings.videoHeight - nodeH) / 2;
        }

        // Match FFmpeg's overlay center: layoutX + nodeW/2 = finalPosX + expandedW/2
        viewNode.setLayoutX(finalPosX + (expandedW - nodeW) / 2);
        viewNode.setLayoutY(finalPosY + (expandedH - nodeH) / 2);

        viewNode.setScaleX(scaleX * extraScaleX);
        viewNode.setScaleY(scaleY * extraScaleY);
        viewNode.setRotate(rot);
        viewNode.setOpacity(opacity);

        // --- Color Adjustments ---
        ColorAdjust colorAdjust = new ColorAdjust();
        colorAdjust.setHue(hue);
        
        // Mapping -10..10 to -1..1
        // Saturation: model 1.0 is neutral (0.0 in JFX). model 10.0 is 1.0 in JFX. model -10.0 is -1.0 in JFX.
        float jfxSaturation = (saturation - 1.0f) / 9.0f;
        if (saturation < 1.0f) {
            jfxSaturation = (saturation - 1.0f) / 11.0f; // Map 1.0..-10.0 to 0.0..-1.0
        }
        colorAdjust.setSaturation(Math.max(-1.0, Math.min(1.0, jfxSaturation)));
        
        // Brightness: model 0.0 is neutral. model 10.0 is 1.0 in JFX.
        colorAdjust.setBrightness(Math.max(-1.0, Math.min(1.0, brightness / 10.0f)));

        // --- Temperature Tint ---
        if (Math.abs(temperature - 6500) > 10) {
            Color tintColor = getTemperatureColor(temperature);
            
            // We need a source image to get dimensions
            Image img = (viewNode instanceof ImageView iv) ? iv.getImage() : null;
            if (img != null) {
                ColorInput colorInput = new ColorInput(0, 0, img.getWidth(), img.getHeight(), tintColor);
                Blend blend = new Blend(BlendMode.SOFT_LIGHT);
                blend.setBottomInput(colorAdjust);
                blend.setTopInput(colorInput);
                viewNode.setEffect(blend);
            } else {
                viewNode.setEffect(colorAdjust);
            }
        } else {
            viewNode.setEffect(colorAdjust);
        }
    }

    private Color getTemperatureColor(float kelvin) {
        if (kelvin < 6500) {
            // Warm: Yellow/Orange tint
            double t = (6500 - kelvin) / 5000.0;
            t = Math.max(0, Math.min(1, t));
            return Color.color(1.0, 0.6, 0.0, 0.4 * t);
        } else {
            // Cool: Blue tint
            double t = (kelvin - 6500) / 5000.0;
            t = Math.max(0, Math.min(1, t));
            return Color.color(0.0, 0.4, 1.0, 0.4 * t);
        }
    }

    public void release() {
        stopAudio();
        decoderService.shutdownNow();
        audioService.shutdownNow();
        if (viewNode != null) {
            Platform.runLater(() -> renderPane.getChildren().remove(viewNode));
        }
        try { if (wavFile != null) wavFile.close(); } catch (IOException ignored) {}
    }

    private static class WavHeader {
        int sampleRate, channels, bitsPerSample;
        long dataOffset;
        static WavHeader parse(RandomAccessFile raf) {
            try {
                raf.seek(0);
                byte[] b = new byte[44];
                if (raf.read(b) < 44) return null;
                if (b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') return null;
                WavHeader w = new WavHeader();
                w.channels = (b[22] & 0xFF) | ((b[23] & 0xFF) << 8);
                w.sampleRate = (b[24] & 0xFF) | ((b[25] & 0xFF) << 8) | ((b[26] & 0xFF) << 16) | ((b[27] & 0xFF) << 24);
                w.bitsPerSample = (b[34] & 0xFF) | ((b[35] & 0xFF) << 8);
                raf.seek(12);
                while (raf.getFilePointer() < raf.length() - 8) {
                    byte[] tag = new byte[4]; raf.readFully(tag);
                    int size = Integer.reverseBytes(raf.readInt());
                    if (tag[0] == 'd' && tag[1] == 'a' && tag[2] == 't' && tag[3] == 'a') {
                        w.dataOffset = raf.getFilePointer();
                        return w;
                    }
                    raf.skipBytes(size);
                }
            } catch (Exception ignored) {}
            return null;
        }
    }
}
