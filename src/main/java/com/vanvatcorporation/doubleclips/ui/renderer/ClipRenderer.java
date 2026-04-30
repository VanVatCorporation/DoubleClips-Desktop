package com.vanvatcorporation.doubleclips.ui.renderer;

import com.vanvatcorporation.doubleclips.FFmpegEdit;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.VideoProperties;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;

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
    private ImageView viewNode;
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
    private final AtomicBoolean isDecoding = new AtomicBoolean(false);
    private float lastRequestedTime = -1f;
    private float lastRenderedTime = -1f;

    // --- Transforms ---
    private float posX = 0, posY = 0;
    private float scaleX = 1, scaleY = 1;
    private float rot = 0;
    private float opacity = 1;

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

        float clipTime = Math.max(0, playheadTime - clip.startTime + clip.startClipTrim);

        if (isSeekingOnly) {
            stopAudio();
            isPlaying = false;
            if (clip.type == ClipType.VIDEO) {
                requestVideoFrame(clipTime);
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

    private void startAudioAt(float clipTime) {
        if (audioLine == null || wavFile == null) return;
        stopAudio();
        audioRunning = true;
        audioThread = new Thread(() -> {
            try {
                long offset = wavDataStart + (long) (clipTime * wavBytesPerSecond);
                int frameSize = audioLine.getFormat().getFrameSize();
                offset = (offset / frameSize) * frameSize;
                wavFile.seek(offset);
                byte[] buf = new byte[4096];
                while (audioRunning) {
                    int read = wavFile.read(buf);
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
        float x = clip.keyframes.getValueAtTime(clip, time, VideoProperties.ValueType.PosX);
        float y = clip.keyframes.getValueAtTime(clip, time, VideoProperties.ValueType.PosY);
        float r = clip.keyframes.getValueAtTime(clip, time, VideoProperties.ValueType.Rot);
        float sx = clip.keyframes.getValueAtTime(clip, time, VideoProperties.ValueType.ScaleX);
        float sy = clip.keyframes.getValueAtTime(clip, time, VideoProperties.ValueType.ScaleY);
        float o = clip.keyframes.getValueAtTime(clip, time, VideoProperties.ValueType.Opacity);

        if (x != -1) posX = x;
        if (y != -1) posY = y;
        if (r != -1) rot = r;
        if (sx != -1) scaleX = sx;
        if (sy != -1) scaleY = sy;
        if (o >= 0) opacity = o;

        Platform.runLater(this::applyTransformation);
    }

    private void applyTransformation() {
        if (viewNode == null) return;
        viewNode.setLayoutX(posX);
        viewNode.setLayoutY(posY);
        viewNode.setScaleX(scaleX);
        viewNode.setScaleY(scaleY);
        viewNode.setRotate(rot);
        viewNode.setOpacity(opacity);
    }

    public void release() {
        stopAudio();
        decoderService.shutdownNow();
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
