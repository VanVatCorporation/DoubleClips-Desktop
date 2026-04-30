package com.vanvatcorporation.doubleclips.ui.renderer;

import com.vanvatcorporation.doubleclips.FFmpegEdit;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.VideoProperties;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Frame-accurate ClipRenderer for the Desktop.
 *
 * Video: FFmpeg is spawned with "-f rawvideo -pix_fmt bgra pipe:1" to output raw
 *        pixel bytes for a single frame at the requested timestamp. Since the preview
 *        clip was encoded with keyint=1 every frame is an I-frame so seeking is instant.
 *
 * Audio: javax.sound.sampled.SourceDataLine reads raw PCM chunks from the .wav file,
 *        matching Android's AudioTrack.write(chunk, …) behavior exactly.
 */
public class ClipRenderer {

    public final Clip clip;
    private final ProjectData data;
    private final VideoSettings settings;
    private final Pane renderPane;

    // --- VIDEO ---
    private Canvas canvas;
    private WritableImage writableImage;
    private Image imageAsset; // for ClipType.IMAGE

    // --- AUDIO ---
    private SourceDataLine audioLine;
    private RandomAccessFile wavFile;
    private long wavDataStart;      // byte offset where PCM data begins
    private long wavBytesPerSecond; // for converting clipTime → byte offset
    private Thread audioThread;
    private volatile boolean audioRunning = false;

    // --- Transforms ---
    private float posX = 0, posY = 0;
    private float scaleX = 1, scaleY = 1;
    private float rot   = 0;
    private float opacity = 1;

    public boolean isPlaying = false;

    // Track last rendered frame to skip redundant FFmpeg calls when scrubbing
    private float lastRenderedVideoTime = -1f;
    private static final float FRAME_EPSILON = 1f / 120f; // half of 60fps frame

    public ClipRenderer(Clip clip, ProjectData data, VideoSettings settings, Pane renderPane) {
        this.clip = clip;
        this.data = data;
        this.settings = settings;
        this.renderPane = renderPane;

        try {
            switch (clip.type) {

                // ─── VIDEO ────────────────────────────────────────────────────────────────
                case VIDEO: {
                    int w = clip.width  > 0 ? clip.width  : settings.videoWidth;
                    int h = clip.height > 0 ? clip.height : settings.videoHeight;

                    canvas = new Canvas(w, h);
                    canvas.setMouseTransparent(true);
                    renderPane.getChildren().add(canvas);

                    writableImage = new WritableImage(w, h);

                    // Audio channel
                    if (clip.isClipHasAudio() && !clip.isMute()) {
                        openAudioLine(clip.getAbsolutePreviewPath(data, ".wav"));
                    }
                    break;
                }

                // ─── IMAGE ────────────────────────────────────────────────────────────────
                case IMAGE: {
                    File imgFile = new File(clip.getAbsolutePath(data));
                    if (imgFile.exists()) {
                        imageAsset = new Image(imgFile.toURI().toString());
                    }

                    int w = clip.width  > 0 ? clip.width  : (imageAsset != null ? (int) imageAsset.getWidth()  : settings.videoWidth);
                    int h = clip.height > 0 ? clip.height : (imageAsset != null ? (int) imageAsset.getHeight() : settings.videoHeight);

                    canvas = new Canvas(w, h);
                    canvas.setMouseTransparent(true);
                    renderPane.getChildren().add(canvas);

                    // Draw once – images don't change
                    if (imageAsset != null) {
                        GraphicsContext gc = canvas.getGraphicsContext2D();
                        gc.drawImage(imageAsset, 0, 0, w, h);
                    }
                    break;
                }

                // ─── AUDIO ONLY ───────────────────────────────────────────────────────────
                case AUDIO: {
                    openAudioLine(clip.getAbsolutePreviewPath(data, ".wav"));
                    break;
                }

                default:
                    break;
            }

            applyTransformation();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────────

    public boolean isVisible(float playheadTime) {
        return playheadTime >= clip.startTime && playheadTime < clip.startTime + clip.duration;
    }

    /**
     * Called every animation tick.
     * @param isSeekingOnly true when the user is scrubbing (no continuous audio pump needed).
     */
    public void renderFrame(float playheadTime, boolean isSeekingOnly) {
        if (!isVisible(playheadTime)) {
            if (canvas != null) canvas.setVisible(false);
            stopAudio();
            isPlaying = false;
            return;
        }

        if (canvas != null) canvas.setVisible(true);

        // Always update keyframe-interpolated transforms
        updateTransforms(playheadTime);

        float clipTime = playheadTime - clip.startTime + clip.startClipTrim;
        clipTime = Math.max(0, clipTime);

        if (isSeekingOnly) {
            stopAudio();
            isPlaying = false;

            // Seek: decode one frame
            if (clip.type == ClipType.VIDEO) {
                seekVideoFrame(clipTime);
            }
        } else {
            // Playback: video frame + continuous audio pump
            if (clip.type == ClipType.VIDEO) {
                seekVideoFrame(clipTime);
            }

            if (!isPlaying) {
                isPlaying = true;
                startAudioAt(clipTime);
            }
        }
    }

    public void release() {
        stopAudio();

        if (canvas != null) {
            renderPane.getChildren().remove(canvas);
            canvas = null;
        }

        try {
            if (wavFile != null) wavFile.close();
        } catch (IOException ignored) {}

        isPlaying = false;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Frame Decode — FFmpeg single-frame pipe
    // ─────────────────────────────────────────────────────────────────────────────

    private void seekVideoFrame(float clipTime) {
        if (writableImage == null || canvas == null) return;

        // Skip if we are within half a frame of the last rendered time (avoids double-pumping)
        if (Math.abs(clipTime - lastRenderedVideoTime) < FRAME_EPSILON) return;
        lastRenderedVideoTime = clipTime;

        String previewPath = clip.getAbsolutePreviewPath(data);
        File previewFile = new File(previewPath);
        if (!previewFile.exists()) {
            // Fall back to original if preview isn't generated yet
            previewFile = new File(clip.getAbsolutePath(data));
            if (!previewFile.exists()) return;
        }

        int w = (int) writableImage.getWidth();
        int h = (int) writableImage.getHeight();

        List<String> cmd = new ArrayList<>();
        cmd.add(FFmpegEdit.getFfmpegPath());
        cmd.add("-accurate_seek");
        cmd.add("-ss");       cmd.add(String.format("%.6f", clipTime));
        cmd.add("-i");        cmd.add(previewFile.getAbsolutePath());
        cmd.add("-vframes");  cmd.add("1");
        cmd.add("-vf");       cmd.add("scale=" + w + ":" + h);
        cmd.add("-f");        cmd.add("rawvideo");
        cmd.add("-pix_fmt");  cmd.add("bgra");
        cmd.add("pipe:1");

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false); // suppress stderr separately
            Process proc = pb.start();

            // Drain stderr to avoid blocking
            proc.getErrorStream().close();

            int expectedBytes = w * h * 4; // BGRA
            byte[] buf = proc.getInputStream().readNBytes(expectedBytes);
            proc.waitFor();

            if (buf.length < expectedBytes) return;

            // Convert BGRA bytes → int[] ARGB for JavaFX PixelFormat
            int[] pixels = new int[w * h];
            for (int i = 0; i < pixels.length; i++) {
                int base = i * 4;
                int b = buf[base]     & 0xFF;
                int g = buf[base + 1] & 0xFF;
                int r = buf[base + 2] & 0xFF;
                int a = buf[base + 3] & 0xFF;
                pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }

            writableImage.getPixelWriter().setPixels(
                    0, 0, w, h,
                    PixelFormat.getIntArgbInstance(),
                    pixels, 0, w
            );

            Platform.runLater(() -> {
                if (canvas != null) {
                    GraphicsContext gc = canvas.getGraphicsContext2D();
                    gc.clearRect(0, 0, w, h);
                    gc.drawImage(writableImage, 0, 0);
                    applyTransformation();
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Audio — javax.sound.sampled (mirrors Android AudioTrack)
    // ─────────────────────────────────────────────────────────────────────────────

    private void openAudioLine(String wavPath) {
        try {
            File wavFileRef = new File(wavPath);
            if (!wavFileRef.exists()) return;

            wavFile = new RandomAccessFile(wavFileRef, "r");

            // Parse WAV header to find PCM parameters and data start offset
            WavHeader header = WavHeader.parse(wavFile);
            if (header == null) return;

            wavDataStart     = header.dataOffset;
            wavBytesPerSecond = (long) header.sampleRate * header.channels * (header.bitsPerSample / 8);

            AudioFormat fmt = new AudioFormat(
                    header.sampleRate,
                    header.bitsPerSample,
                    header.channels,
                    true,   // signed
                    false   // little-endian (standard WAV)
            );

            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            audioLine = (SourceDataLine) AudioSystem.getLine(info);
            audioLine.open(fmt, 8192);
            audioLine.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startAudioAt(float clipTime) {
        if (audioLine == null || wavFile == null) return;

        stopAudio(); // Kill prior pump thread

        audioRunning = true;
        audioThread = new Thread(() -> {
            try {
                long byteOffset = wavDataStart + (long)(clipTime * wavBytesPerSecond);
                // Align to a sample boundary (4 bytes for stereo 16-bit, 2 for mono)
                int frameSize = audioLine.getFormat().getFrameSize();
                byteOffset = (byteOffset / frameSize) * frameSize;

                wavFile.seek(byteOffset);

                byte[] chunk = new byte[4096];
                while (audioRunning) {
                    int read = wavFile.read(chunk);
                    if (read < 0) break; // end of file
                    audioLine.write(chunk, 0, read);
                }
            } catch (IOException e) {
                // Normal when we stop mid-stream
            }
        }, "AudioPump-" + clip.getClipName());

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

    // ─────────────────────────────────────────────────────────────────────────────
    //  Transforms
    // ─────────────────────────────────────────────────────────────────────────────

    private void updateTransforms(float playheadTime) {
        float x   = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.PosX);
        float y   = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.PosY);
        float rot = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.Rot);
        float sx  = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.ScaleX);
        float sy  = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.ScaleY);
        float op  = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.Opacity);

        if (x  != -1) posX    = x;
        if (y  != -1) posY    = y;
        if (rot != -1) this.rot = rot;
        if (sx != -1) scaleX  = sx;
        if (sy != -1) scaleY  = sy;
        if (op >= 0)  opacity = op;

        applyTransformation();
    }

    private void applyTransformation() {
        if (canvas == null) return;
        canvas.setTranslateX(posX);
        canvas.setTranslateY(posY);
        canvas.setScaleX(scaleX);
        canvas.setScaleY(scaleY);
        canvas.setRotate(rot);
        canvas.setOpacity(opacity);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Minimal WAV header parser
    // ─────────────────────────────────────────────────────────────────────────────

    private static class WavHeader {
        int sampleRate;
        int channels;
        int bitsPerSample;
        long dataOffset;

        static WavHeader parse(RandomAccessFile raf) {
            try {
                raf.seek(0);
                byte[] hdr = new byte[44];
                if (raf.read(hdr) < 44) return null;

                // Quick sanity checks
                if (hdr[0] != 'R' || hdr[1] != 'I' || hdr[2] != 'F' || hdr[3] != 'F') return null;
                if (hdr[8] != 'W' || hdr[9] != 'A' || hdr[10] != 'V' || hdr[11] != 'E') return null;

                WavHeader w = new WavHeader();
                w.channels     = le16(hdr, 22);
                w.sampleRate   = le32(hdr, 24);
                w.bitsPerSample = le16(hdr, 34);

                // Scan for 'data' chunk (may not be exactly at offset 36)
                raf.seek(12);
                while (raf.getFilePointer() < raf.length() - 8) {
                    byte[] tag = new byte[4];
                    raf.readFully(tag);
                    int chunkSize = Integer.reverseBytes(raf.readInt());
                    if (tag[0] == 'd' && tag[1] == 'a' && tag[2] == 't' && tag[3] == 'a') {
                        w.dataOffset = raf.getFilePointer();
                        return w;
                    }
                    raf.skipBytes(chunkSize);
                }
                return null;
            } catch (IOException e) {
                return null;
            }
        }

        private static int le16(byte[] b, int off) {
            return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
        }

        private static int le32(byte[] b, int off) {
            return (b[off] & 0xFF) | ((b[off+1] & 0xFF) << 8)
                 | ((b[off+2] & 0xFF) << 16) | ((b[off+3] & 0xFF) << 24);
        }
    }
}
