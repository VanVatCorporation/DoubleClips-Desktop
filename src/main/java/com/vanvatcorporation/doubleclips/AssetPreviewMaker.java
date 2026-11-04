package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.drive.AssetKind;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The small picture sent with a post (iOS: AssetPreviewMaker): a JPEG of at most 200 KB that stands for the asset in the Assets tab.
 * The image itself, a frame of a video, or a sample line in a font. Audio, effects and packs have no picture (the tab then shows
 * the kind's icon). Every method blocks (a video frame runs ffmpeg): call them off the FX thread. They return null instead of throwing.
 */
public final class AssetPreviewMaker {
    private AssetPreviewMaker() {}

    /** The server refuses a larger preview. */
    public static final int MAX_BYTES = 200_000;
    private static final int EDGE = 640;

    /** The picture for a file of this kind, or null when the kind has none or the file can't give one. */
    public static byte[] jpegFor(File file, AssetKind kind) {
        try {
            switch (kind) {
                case IMAGE: return encode(ImageIO.read(file));     // webp / heic have no reader here: null, the user picks a picture
                case VIDEO: return encode(videoFrame(file));
                case FONT: return encode(fontSample(file));
                default: return null;
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** A picture the user chose for it (any image file this computer can read), shrunk to fit. */
    public static byte[] jpegFromPicture(File picture) {
        try {
            return encode(ImageIO.read(picture));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---- sources -----------------------------------------------------------------------------------------

    /** A frame about a second in (the very first frame is often black), or the first frame of a clip shorter than that. */
    private static BufferedImage videoFrame(File video) {
        BufferedImage frame = grabFrame(video, "1");
        return frame != null ? frame : grabFrame(video, "0");
    }

    private static BufferedImage grabFrame(File video, String seekSeconds) {
        File out = null;
        try {
            out = File.createTempFile("asset-preview-", ".jpg");
            final File target = out;
            CountDownLatch done = new CountDownLatch(1);
            FFmpegEdit.runAnyCommand(
                    "-ss " + seekSeconds + " -i \"" + video.getAbsolutePath() + "\" -vframes 1 -vf scale=" + EDGE + ":-2 -q:v 3 -y \""
                            + target.getAbsolutePath() + "\"",
                    "Asset preview", done::countDown, done::countDown, log -> { }, stats -> { });
            if (!done.await(60, TimeUnit.SECONDS)) return null;
            return target.length() > 0 ? ImageIO.read(target) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (out != null) //noinspection ResultOfMethodCallIgnored
                out.delete();
        }
    }

    /** "Aa Bb / 0123456789 ABC xyz" in the font, on a plain card. */
    private static BufferedImage fontSample(File fontFile) {
        try {
            Font base = Font.createFont(Font.TRUETYPE_FONT, fontFile);
            BufferedImage img = new BufferedImage(EDGE, 360, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(new Color(247, 247, 247));
                g.fillRect(0, 0, EDGE, 360);
                g.setColor(Color.BLACK);
                g.setFont(base.deriveFont(Font.PLAIN, 120f));
                g.drawString("Aa Bb", 36, 170);
                g.setColor(Color.DARK_GRAY);
                g.setFont(base.deriveFont(Font.PLAIN, 44f));
                g.drawString("0123456789 ABC xyz", 36, 290);
            } finally {
                g.dispose();
            }
            return img;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    // ---- JPEG under the cap ------------------------------------------------------------------------------

    /** Shrunk to 640 px on the long side, then JPEG: the quality steps down, then the picture gets smaller, until it fits 200 KB. */
    private static byte[] encode(BufferedImage source) {
        if (source == null) return null;
        BufferedImage img = scaled(source, Math.min(1.0, (double) EDGE / Math.max(source.getWidth(), source.getHeight())));
        for (int round = 0; round < 4; round++) {
            for (float quality : new float[] { 0.82f, 0.65f, 0.5f, 0.35f }) {
                byte[] out = jpeg(img, quality);
                if (out != null && out.length <= MAX_BYTES) return out;
            }
            img = scaled(img, 0.75);
        }
        return null;
    }

    /** An opaque RGB copy at this scale (JPEG has no transparency: a transparent PNG gets a white background). */
    private static BufferedImage scaled(BufferedImage src, double scale) {
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] jpeg(BufferedImage img, float quality) {
        ImageWriter writer = null;
        try {
            writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ImageOutputStream stream = ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(img, null, null), param);
            }
            return bytes.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (writer != null) writer.dispose();
        }
    }
}
