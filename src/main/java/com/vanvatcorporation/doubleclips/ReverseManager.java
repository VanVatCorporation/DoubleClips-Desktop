package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns the reversed copies of the project's source files (see {@link ReverseMap}): makes one when a clip is reversed, shares it
 * between the clips that use the same file, and removes it when the last of them is switched back. Ported from iOS (ReverseManager in
 * EditingView+Reverse.swift): the clip keeps its original file and trims, {@link Clip#isReverse} is the mark, and
 * {@link Clip#reversedCopy} remembers the cache's name once it exists.
 * <p>
 * The work runs on a background thread and reports to {@link ClipProcessingTracker} (the preview badge, the clip's "Applying Reverse... 52.6%"
 * and its "Processing xx%" line). Every change to a {@link Clip} is made on the UI thread through {@code runOnUi}, so the model is only ever
 * touched from one thread. When a copy becomes ready {@code onChanged} is called (on the UI thread) so the editor can save and redraw.
 */
public final class ReverseManager {

    private final Supplier<File> clipsDir;
    private final Supplier<List<Clip>> allClips;
    private final Consumer<Runnable> runOnUi;
    private final Runnable onChanged;
    private final Consumer<String> log;
    private final String ffmpeg;
    private final ClipProcessingTracker tracker;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "reverse-copy");
        t.setDaemon(true);
        return t;
    });

    /** One source file being reversed: the clips waiting for it and the job each shows. Touched from the UI thread only, except {@code cancelled}. */
    private static final class Build {
        final String source;
        volatile boolean cancelled;
        final Map<Clip, ClipProcessingTracker.Job> waiting = new HashMap<>();

        Build(String source) { this.source = source; }
    }

    private final Map<String, Build> builds = new HashMap<>(); // UI thread only

    public ReverseManager(String ffmpeg, Supplier<File> clipsDir, Supplier<List<Clip>> allClips, Consumer<Runnable> runOnUi,
                          Runnable onChanged, Consumer<String> log, ClipProcessingTracker tracker) {
        this.ffmpeg = ffmpeg;
        this.clipsDir = clipsDir;
        this.allClips = allClips;
        this.runOnUi = runOnUi;
        this.onChanged = onChanged == null ? () -> {} : onChanged;
        this.log = log == null ? s -> {} : log;
        this.tracker = tracker == null ? ClipProcessingTracker.get() : tracker;
    }

    /** True for a clip that is meant to play a reversed copy (a video clip with the flag on, that isn't a copy itself). */
    public static boolean wantsCopy(Clip clip) {
        return clip != null && clip.type == ClipType.VIDEO && clip.isReverse && !ReverseMap.isCacheName(clip.getClipName());
    }

    /** The copy's file when the clip has one that is there, else null. */
    public static File copyFileOf(Clip clip, File clipsDir) {
        if (clip == null || clip.reversedCopy == null || clip.reversedCopy.isEmpty() || clipsDir == null) return null;
        File f = new File(clipsDir, clip.reversedCopy);
        return f.isFile() && f.length() > 0 ? f : null;
    }

    /** The clip was flagged (or arrived flagged): make sure its reversed copy exists, making it in the background if not. UI thread. */
    public void apply(Clip clip) {
        if (!wantsCopy(clip)) return;
        if (copyFileOf(clip, clipsDir.get()) != null) return; // already has one
        String source = clip.getClipName();
        Build build = builds.get(source);
        boolean fresh = build == null;
        if (fresh) {
            build = new Build(source);
            builds.put(source, build);
        }
        if (build.waiting.containsKey(clip)) return;
        build.waiting.put(clip, tracker.begin(clip, ClipProcessingTracker.REVERSE));
        if (fresh) {
            final Build b = build;
            worker.submit(() -> run(b));
        }
    }

    /**
     * The clip is no longer reversed (switched off, or deleted): it stops waiting for a copy, and the copy is deleted unless another
     * clip still uses it. UI thread.
     */
    public void release(Clip clip) {
        if (clip == null) return;
        tracker.drop(clip);
        Build build = builds.get(clip.getClipName());
        if (build != null && build.waiting.remove(clip) != null && build.waiting.isEmpty()) {
            build.cancelled = true;
            builds.remove(clip.getClipName());
        }
        String copy = clip.reversedCopy;
        clip.reversedCopy = null;
        if (copy == null || copy.isEmpty()) return;
        for (Clip other : allClips.get()) {
            if (other != clip && wantsCopy(other) && copy.equals(other.reversedCopy)) return; // still needed
        }
        deleteCopy(copy);
    }

    /**
     * The project was opened: flagged clips that have no copy get one made, and copies nobody uses any more (a clip that was deleted, a
     * switch-off whose delete was refused because the file was open) are removed. UI thread.
     */
    public void syncAll() {
        List<Clip> clips = allClips.get();
        for (Clip clip : clips) {
            if (wantsCopy(clip)) apply(clip);
            else if (clip != null && clip.reversedCopy != null && !clip.isReverse) clip.reversedCopy = null; // switched off elsewhere
        }
        File dir = clipsDir.get();
        File[] files = dir == null ? null : dir.listFiles((d, name) -> ReverseMap.isCacheName(name));
        if (files == null) return;
        for (File f : files) {
            boolean used = false;
            for (Clip clip : clips) {
                if (wantsCopy(clip) && (f.getName().equals(clip.reversedCopy) || f.getName().equals(ReverseMap.cacheName(clip.getClipName())))) {
                    used = true;
                    break;
                }
            }
            if (!used && !builds.containsKey(f.getName().substring(0, f.getName().length() - ReverseMap.SUFFIX.length()))) {
                deleteCopy(f.getName());
            }
        }
    }

    /** The editor is closing: unfinished copies are abandoned (nothing is left half written). */
    public void shutdown() {
        for (Build b : builds.values()) b.cancelled = true;
        worker.shutdownNow();
    }

    // ── the background work ───────────────────────────────────────────────

    private void run(Build build) {
        File dir = clipsDir.get();
        File source = new File(dir, build.source);
        File copy = new File(dir, ReverseMap.cacheName(build.source));
        try {
            if (!source.isFile()) throw new IOException("the source file is missing: " + build.source);
            ReverseProcessor.Info info = ReverseProcessor.probe(ffmpeg, source.getPath());
            if (copy.isFile() && copy.length() > 0 && isUsable(copy, info)) {
                ready(build, copy.getName()); // an earlier run (or iOS / Android) already made it
                return;
            }
            log.accept("Reversing '" + build.source + "' (a whole-file copy, made once)...");
            final double[] lastReported = {-1};
            ReverseProcessor.build(ffmpeg, source.getPath(), info, copy, p -> {
                if (p - lastReported[0] < 0.002 && p < 1.0) return; // the pipe reports about a hundred times a second: the UI needs far fewer
                lastReported[0] = p;
                report(build, p);
            }, () -> build.cancelled);
            ready(build, copy.getName());
        } catch (ReverseProcessor.Cancelled e) {
            // switched off while it was being made: nothing to report
        } catch (IOException | RuntimeException e) {
            log.accept("Could not reverse '" + build.source + "': " + e.getMessage());
            runOnUi.accept(() -> {
                Build current = builds.get(build.source);
                if (current == build) builds.remove(build.source);
                for (ClipProcessingTracker.Job job : build.waiting.values()) job.fail(String.valueOf(e.getMessage()));
                build.waiting.clear();
            });
        }
    }

    /** A copy found on disk is used only when its length is close to the original's (not cut short by an interrupted run). */
    private boolean isUsable(File copy, ReverseProcessor.Info original) {
        try {
            ReverseProcessor.Info c = ReverseProcessor.probe(ffmpeg, copy.getPath());
            return c.hasVideo && ReverseMap.plausibleLength(c.duration, original.duration);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private void report(Build build, double fraction) {
        // the jobs are only read here (their progress() is thread-safe); the map is changed on the UI thread, so take a snapshot there
        runOnUi.accept(() -> {
            for (ClipProcessingTracker.Job job : build.waiting.values()) job.progress(fraction);
        });
    }

    private void ready(Build build, String copyName) {
        runOnUi.accept(() -> {
            if (builds.get(build.source) == build) builds.remove(build.source);
            boolean any = false;
            for (Map.Entry<Clip, ClipProcessingTracker.Job> e : build.waiting.entrySet()) {
                Clip clip = e.getKey();
                if (wantsCopy(clip)) {
                    clip.reversedCopy = copyName;
                    any = true;
                }
                e.getValue().finish();
            }
            build.waiting.clear();
            if (any) onChanged.run();
        });
    }

    private void deleteCopy(String name) {
        File f = new File(clipsDir.get(), name);
        if (f.isFile() && !f.delete()) {
            log.accept("Could not delete '" + name + "' now (still open); it is removed the next time the project opens.");
        }
    }

    /** Names of the copy files a project uses, for callers that package a project. */
    public static List<String> copyNamesOf(List<Clip> clips) {
        List<String> out = new ArrayList<>();
        for (Clip clip : clips) {
            if (wantsCopy(clip) && clip.reversedCopy != null && !out.contains(clip.reversedCopy)) out.add(clip.reversedCopy);
        }
        return out;
    }
}
