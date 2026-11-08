package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes the preview files a project is missing (see {@link PreviewMedia}) in the background, one file at a time, and reports to
 * {@link ClipProcessingTracker} exactly like the reversed copies do: every clip that uses a file shows "Applying Proxy... 0.0%" at
 * once, then the real percentage, and the badge over the preview counts them. A file no clip uses yet (it is only in the Media tab)
 * is shown in the badge through a stand-in clip that is never on the timeline. Nothing blocks the editor.
 * <p>
 * The editor calls {@link #sync} when a project is opened, a file is imported or "Use proxy clips" is switched on. It scans OFF the UI
 * thread, then queues what is missing; calling it again while files are being made is harmless (a file already queued or running is
 * not queued twice).
 * <p>
 * {@link #sync} and everything the callbacks do run on the UI thread, through {@code runOnUi}.
 */
public final class PreviewMediaManager {

    /** One file being made, with the tracker job of every clip waiting for it. */
    private static final class Run {
        final PreviewMedia.Need need;
        final Map<Clip, ClipProcessingTracker.Job> jobs = new IdentityHashMap<>();

        Run(PreviewMedia.Need need) { this.need = need; }
    }

    private static final Pattern TIME = Pattern.compile("time=(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");

    private final String ffmpeg;
    private final Supplier<String> projectPath;
    private final Supplier<List<Clip>> allClips;
    private final Consumer<Runnable> runOnUi;
    private final Consumer<Boolean> onBatchDone;
    private final BiConsumer<PreviewMedia.Need, String> onFailed;
    private final ClipProcessingTracker tracker;

    private final ExecutorService scanner = daemonExecutor("preview-media-scan");
    private final ExecutorService worker = daemonExecutor("preview-media-make");

    private final Map<String, Run> inFlight = new HashMap<>(); // UI thread only
    private int pending;                                      // UI thread only
    private boolean anyMade;                                  // UI thread only
    private volatile boolean destroyed;
    private volatile Process current;

    public PreviewMediaManager(String ffmpeg, Supplier<String> projectPath, Supplier<List<Clip>> allClips, Consumer<Runnable> runOnUi,
                               Consumer<Boolean> onBatchDone, BiConsumer<PreviewMedia.Need, String> onFailed, ClipProcessingTracker tracker) {
        this.ffmpeg = ffmpeg;
        this.projectPath = projectPath;
        this.allClips = allClips;
        this.runOnUi = runOnUi;
        this.onBatchDone = onBatchDone == null ? made -> { } : onBatchDone;
        this.onFailed = onFailed == null ? (n, why) -> { } : onFailed;
        this.tracker = tracker == null ? ClipProcessingTracker.get() : tracker;
    }

    private static ExecutorService daemonExecutor(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /** The editor is closing: nothing more is queued and the running ffmpeg is stopped. */
    public void destroy() {
        destroyed = true;
        scanner.shutdownNow();
        worker.shutdownNow();
        Process p = current;
        if (p != null) p.destroy();
    }

    /** True while at least one file is queued or being made. */
    public boolean isBusy() { return pending > 0; }

    /** What a file kind reports under (what the user reads: "Applying Proxy... 52.6%"). */
    public static String featureOf(PreviewMedia.Kind kind) {
        switch (kind) {
            case PROXY: return ClipProcessingTracker.PROXY;
            case THUMBNAIL: return ClipProcessingTracker.PREVIEW_THUMBNAIL;
            default: return ClipProcessingTracker.PREVIEW_AUDIO;
        }
    }

    // ------------------------------------------------------------------ queueing

    /**
     * Makes whatever is missing in the project. {@code useProxy} decides whether missing video proxies count (the thumbnail and the
     * preview sound always do). Returns at once; the scan runs in the background.
     */
    public void sync(boolean useProxy) {
        if (destroyed) return;
        final String project = projectPath.get();
        try {
            scanner.execute(() -> {
                List<PreviewMedia.Need> needs;
                try {
                    needs = PreviewMedia.scan(project, useProxy);
                } catch (RuntimeException e) {
                    return; // an unreadable project folder: the next entry scans again
                }
                if (needs.isEmpty() || destroyed) return;
                runOnUi.accept(() -> enqueueAll(needs));
            });
        } catch (RejectedExecutionException ignored) {
            // destroyed meanwhile
        }
    }

    private void enqueueAll(List<PreviewMedia.Need> needs) {
        if (destroyed) return;
        java.io.File dir = PreviewMedia.directory(projectPath.get());
        if (!dir.isDirectory()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();

        List<Clip> clips = allClips.get();
        for (PreviewMedia.Need need : needs) {
            String key = need.kind + "|" + need.target.getPath();
            if (inFlight.containsKey(key)) continue; // already queued or running

            final Run run = new Run(need);
            for (Clip clip : clips) {
                if (clip != null && need.fileName().equals(clip.getClipName())) {
                    run.jobs.put(clip, tracker.begin(clip, featureOf(need.kind))); // shown at 0.0 % right away
                }
            }
            if (run.jobs.isEmpty()) {
                // Only in the Media tab so far: a stand-in clip keeps the badge honest.
                ClipType type = need.isVideoFile ? ClipType.VIDEO : ClipType.AUDIO;
                Clip standIn = new Clip(need.fileName(), 0, (float) need.sourceSeconds, 0, type, need.hasAudio, need.width, need.height);
                run.jobs.put(standIn, tracker.begin(standIn, featureOf(need.kind)));
            }
            inFlight.put(key, run);
            pending++;
            try {
                worker.execute(() -> make(run));
            } catch (RejectedExecutionException e) {
                inFlight.remove(key);
                pending--;
            }
        }
    }

    // ------------------------------------------------------------------ running

    /** On the worker thread. */
    private void make(Run run) {
        final PreviewMedia.Need need = run.need;
        boolean ok = false;
        String why = "";
        need.discard(); // a leftover of a run that was killed
        try {
            List<String> command = new ArrayList<>();
            command.add(ffmpeg);
            command.addAll(need.arguments());
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            current = process;
            String last = "";
            double lastReported = -1;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) last = line.trim();
                    Matcher m = TIME.matcher(line);
                    if (m.find() && need.sourceSeconds > 0) {
                        double seconds = Integer.parseInt(m.group(1)) * 3600.0 + Integer.parseInt(m.group(2)) * 60.0 + Double.parseDouble(m.group(3));
                        final double fraction = Math.min(0.99, seconds / need.sourceSeconds); // 100 % is the commit, not the last frame
                        if (fraction - lastReported >= 0.005) {
                            lastReported = fraction;
                            runOnUi.accept(() -> { for (ClipProcessingTracker.Job job : run.jobs.values()) job.progress(fraction); });
                        }
                    }
                }
            }
            int exit = process.waitFor();
            current = null;
            ok = exit == 0 && need.commit();
            if (!ok) why = exit == 0 ? "no output" : "ffmpeg exit " + exit + ": " + last;
        } catch (Exception e) {
            why = String.valueOf(e.getMessage());
        }
        if (!ok) need.discard();
        final boolean made = ok;
        final String reason = why;
        if (!destroyed) runOnUi.accept(() -> complete(run, made, reason));
    }

    /** On the UI thread. */
    private void complete(Run run, boolean ok, String why) {
        inFlight.remove(run.need.kind + "|" + run.need.target.getPath());
        for (ClipProcessingTracker.Job job : run.jobs.values()) {
            if (ok) job.finish(); else job.fail(why);
        }
        if (ok) anyMade = true;
        else onFailed.accept(run.need, why);

        pending = Math.max(0, pending - 1);
        if (pending == 0) {
            boolean made = anyMade;
            anyMade = false;
            onBatchDone.accept(made);
        }
    }
}
