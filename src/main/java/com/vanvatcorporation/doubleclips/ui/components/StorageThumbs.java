package com.vanvatcorporation.doubleclips.ui.components;

import com.vanvatcorporation.doubleclips.drive.DriveClient;
import com.vanvatcorporation.doubleclips.drive.DriveNode;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * Image thumbnails for the Storage list, like the web Drive. The server has no thumbnail endpoint, so a thumbnail is the
 * image itself loaded inline: the first load spends a little of the daily download allowance, so the file is kept on disk
 * and every later load (including the next run) is free. The user can turn them off (Settings, VanVatDrive).
 */
public final class StorageThumbs {
    private StorageThumbs() {}

    /** Bigger originals keep their icon: loading them just for a thumbnail would eat the allowance. */
    public static final long MAX_SOURCE_BYTES = 8L * 1024 * 1024;
    private static final double EDGE = 40;
    private static final Set<String> EXT = new HashSet<>(java.util.Arrays.asList("jpg", "jpeg", "png", "gif", "bmp"));

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "storage-thumbs");
        t.setDaemon(true);
        return t;
    });
    private static final Set<String> FAILED = Collections.synchronizedSet(new HashSet<>());
    private static final Map<String, Image> MEMORY = Collections.synchronizedMap(new LinkedHashMap<String, Image>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Image> e) { return size() > 300; }
    });

    public static boolean isThumbnailable(DriveNode n) {
        if (n == null || n.isFolder || n.size <= 0 || n.size > MAX_SOURCE_BYTES || n.name == null) return false;
        int dot = n.name.lastIndexOf('.');
        return dot >= 0 && EXT.contains(n.name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * Puts the thumbnail in {@code tile} when it is ready; the type icon already in the tile stays until then (and for good
     * when loading fails, e.g. today's allowance is used up). {@code stillWanted} tells whether the list cell still shows
     * this node (cells are recycled while the image loads).
     */
    public static void load(DriveClient client, DriveNode n, StackPane tile, BooleanSupplier stillWanted) {
        if (FAILED.contains(n.id)) return;
        Image cached = MEMORY.get(n.id);
        if (cached != null) { show(tile, cached); return; }

        POOL.execute(() -> {
            try {
                File disk = new File(dir(), n.id);
                if (!disk.isFile()) {
                    byte[] data = client.inlineContent(n);
                    if (data.length > MAX_SOURCE_BYTES) { FAILED.add(n.id); return; }
                    try { Files.write(disk.toPath(), data); } catch (Exception ignored) { /* the disk cache is best effort */ }
                }
                byte[] bytes = Files.readAllBytes(disk.toPath());
                // Decoded at twice the size, so it stays sharp on high-DPI screens.
                Image img = new Image(new ByteArrayInputStream(bytes), EDGE * 2, EDGE * 2, false, true);
                if (img.isError()) { FAILED.add(n.id); disk.delete(); return; }
                MEMORY.put(n.id, img);
                Platform.runLater(() -> { if (stillWanted.getAsBoolean()) show(tile, img); });
            } catch (Exception e) {
                FAILED.add(n.id);                     // keep the icon; try again next run
            }
        });
    }

    /** Forget everything (sign-out). */
    public static void clear() {
        MEMORY.clear();
        FAILED.clear();
        File[] files = dir().listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    private static void show(StackPane tile, Image img) {
        ImageView view = new ImageView(img);
        view.setFitWidth(EDGE);
        view.setFitHeight(EDGE);
        view.setPreserveRatio(false);
        Rectangle clip = new Rectangle(EDGE, EDGE);
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        view.setClip(clip);
        tile.getChildren().setAll(view);
    }

    private static File dir() {
        File d = new File(System.getProperty("user.home"), ".vanvatcorp" + File.separator + "openeditz" + File.separator + "thumbs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }
}
