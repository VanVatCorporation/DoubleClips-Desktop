package com.vanvatcorporation.doubleclips.ui.components;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Makes a set of picture tiles loop, all from one clock, like the looping previews on iOS. Each tile gives a way
 * to make its frames; they are made one tile at a time off the FX thread (a tile shows its still picture until its
 * frames are ready) and then played at {@code fps}. {@link #stop()} ends the clock and any frames still being made.
 */
public final class TilePreviewAnimator {

    private static final class Entry {
        final ImageView view;
        volatile Image[] frames;

        Entry(ImageView view) {
            this.view = view;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Timeline clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tile-previews");
        t.setDaemon(true);
        return t;
    });
    private int tick = 0;
    private boolean stopped = false;

    public TilePreviewAnimator(int fps) {
        clock = new Timeline(new KeyFrame(Duration.millis(1000.0 / Math.max(1, fps)), e -> advance()));
        clock.setCycleCount(Animation.INDEFINITE);
    }

    /** Starts making the frames for {@code view}; call on the FX thread. */
    public void add(ImageView view, Supplier<Image[]> makeFrames) {
        if (stopped) return;
        Entry entry = new Entry(view);
        entries.add(entry);
        worker.submit(() -> {
            Image[] frames;
            try {
                frames = makeFrames.get();
            } catch (RuntimeException ex) {
                return; // a tile that can't animate just stays a still picture
            }
            if (frames == null || frames.length == 0) return;
            Platform.runLater(() -> {
                if (stopped) return;
                entry.frames = frames;
                if (clock.getStatus() != Animation.Status.RUNNING) clock.play();
            });
        });
    }

    private void advance() {
        tick++;
        for (Entry entry : entries) {
            Image[] frames = entry.frames;
            if (frames != null) entry.view.setImage(frames[tick % frames.length]);
        }
    }

    public void stop() {
        stopped = true;
        clock.stop();
        worker.shutdownNow();
        entries.clear();
    }
}
