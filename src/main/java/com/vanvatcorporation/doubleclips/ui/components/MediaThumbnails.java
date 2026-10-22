package com.vanvatcorporation.doubleclips.ui.components;

import com.vanvatcorporation.doubleclips.FFmpegEditNative;
import com.vanvatcorporation.doubleclips.data.storage.StorageHelper;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Small pictures of the media the person picked: a photo is read (downscaled), a video gives its first frame through
 * ffmpeg. Made off the FX thread and remembered for the window's life; the callback runs on the FX thread (at once when the
 * picture is already known). A picture that can't be made is reported as null.
 */
public final class MediaThumbnails {
    private static final int SIZE = 240;

    private final Map<String, Image> cache = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "MediaThumbnails");
        t.setDaemon(true);
        return t;
    });

    public void load(String path, boolean video, Consumer<Image> onImage) {
        final String key = (video ? "v|" : "i|") + path;
        Image known = cache.get(key);
        if (known != null) {
            onImage.accept(known);
            return;
        }
        pool.submit(() -> {
            Image image = null;
            try {
                image = video ? videoFrame(new File(path)) : photo(new File(path));
            } catch (Exception ignored) {
                /* no picture: the tile shows its number only */
            }
            if (image != null) cache.put(key, image);
            final Image result = image;
            Platform.runLater(() -> onImage.accept(result));
        });
    }

    public void shutdown() { pool.shutdownNow(); }

    /** The largest centred square of the picture: the part a square tile shows (the tile fills, not letterboxes). */
    public static Rectangle2D coverSquare(Image image) {
        double w = image.getWidth(), h = image.getHeight();
        double s = Math.min(w, h);
        return new Rectangle2D((w - s) / 2, (h - s) / 2, s, s);
    }

    private static Image photo(File file) {
        Image image = new Image(file.toURI().toString(), SIZE, SIZE, true, true, false);
        return image.isError() ? null : image;
    }

    private static Image videoFrame(File file) throws Exception {
        File dir = new File(StorageHelper.getCacheDirectory(), "TemplateThumbs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, Integer.toHexString((file.getAbsolutePath() + file.lastModified()).hashCode()) + ".jpg");
        if (!out.isFile()) {
            ProcessBuilder pb = new ProcessBuilder(FFmpegEditNative.getFfmpegPath(), "-hide_banner", "-loglevel", "error", "-y",
                    "-i", file.getAbsolutePath(), "-frames:v", "1", "-vf", "scale=" + SIZE + ":-2", out.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
        }
        if (!out.isFile()) return null;
        Image image = new Image(out.toURI().toString(), SIZE, SIZE, true, true, false);
        return image.isError() ? null : image;
    }
}
