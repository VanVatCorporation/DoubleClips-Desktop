package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.ProjectRepository;
import com.vanvatcorporation.doubleclips.data.TemplateData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;
import com.vanvatcorporation.doubleclips.data.storage.StorageHelper;
import com.vanvatcorporation.doubleclips.helper.MediaHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * "Use template" through OpenGL (iOS: TemplateRenderer): the template's timeline with the user's media dropped into its
 * replaceable clips, built in a temporary project folder and handed to the normal export window (same engine as the editor).
 * <ol>
 *   <li>A FRESH copy of the template's timeline (each parse makes new clip objects: this one gets edited).</li>
 *   <li>The template's resources (locked media, audio, fonts) from its content link, cached per template + timestamp, copied
 *       into the folder's Clips/ and Fonts/ ({@link TemplateResources}).</li>
 *   <li>Slot i gets the i-th pick ({@link TemplateRetarget}): the clip keeps everything about it; the new media covers the old
 *       box, centred on it; a video keeps the trim the user chose, capped at the slot's length; a still lasts the slot.</li>
 *   <li>The export settings (which start as the template's own canvas) are saved as the folder's project.settings.</li>
 * </ol>
 * Blocking: call it off the FX thread. The working folder is deleted with {@link #cleanup}.
 */
public final class TemplateRenderer {
    private TemplateRenderer() {}

    public static final String WORK_FOLDER_PREFIX = "TemplateRender-";
    public static final String FONTS_DIRECTORY = "Fonts";
    private static final String RESOURCE_CACHE_FOLDER = "TemplateResources";

    /** Something that stops the render, in words for the user. */
    public static final class RenderError extends Exception {
        public RenderError(String message) { super(message); }
    }

    /** What the user picked for one slot. {@code endTrim <= startTrim} means "to the end of the video". Trims are source seconds. */
    public static final class SlotPick {
        public final String path;
        public final boolean isVideo;
        public final double startTrim, endTrim;
        public SlotPick(String path, boolean isVideo, double startTrim, double endTrim) {
            this.path = path; this.isVideo = isVideo; this.startTrim = startTrim; this.endTrim = endTrim;
        }
    }

    /** What is known about a media file. A size of 0 = unknown. */
    public static final class MediaFacts {
        public final int width, height;
        public final double duration;
        public final boolean hasAudio;
        public MediaFacts(int width, int height, double duration, boolean hasAudio) {
            this.width = width; this.height = height; this.duration = duration; this.hasAudio = hasAudio;
        }
    }

    /** Reads a media file's size, length and sound (ffmpeg in the app, a fake in tests). */
    public interface Probe { MediaFacts probe(File file); }

    /** Puts the named resources into the working folder's Clips/ and Fonts/ (the download in the app, a fake in tests). */
    public interface ResourceFetcher {
        void fetch(List<String> names, File clipsDir, File fontsDir, Consumer<String> log) throws RenderError;
    }

    /** A template ready to export. */
    public static final class Prepared {
        public final ProjectData project;
        public final Timeline timeline;
        public final File directory;
        Prepared(ProjectData project, Timeline timeline, File directory) {
            this.project = project; this.timeline = timeline; this.directory = directory;
        }
    }

    // ---- the app's entry ----------------------------------------------------------------------------------

    /**
     * @param picks    one per replaceable clip, in slot order
     * @param settings the export settings the screen chose (their canvas is what the media is fitted to)
     */
    public static Prepared prepare(TemplateData template, List<SlotPick> picks, VideoSettings settings,
                                   Consumer<String> log) throws RenderError {
        log.accept("Loading the template's timeline\u2026");
        TemplateTimelineInfo info;
        try {
            info = TemplateTimelineLoader.load(template);   // a fresh parse every time
        } catch (IOException e) {
            throw new RenderError("The template's timeline couldn't be loaded: " + e.getMessage());
        }

        final File cacheRoot = new File(StorageHelper.getCacheDirectory(), RESOURCE_CACHE_FOLDER);
        Prepared prepared = prepare(info, template, picks, settings,
                (names, clips, fonts, l) -> TemplateResources.fetch(names, template, cacheRoot, clips, fonts, l),
                file -> {
                    MediaHelper.MediaInfo m = MediaHelper.probeMediaInfo(file.getPath());
                    return new MediaFacts(m.width, m.height, m.duration, m.hasAudio);
                },
                new File(System.getProperty("java.io.tmpdir")), log);

        try {
            ProjectRepository.getInstance().saveVideoSettings(prepared.project, settings);
        } catch (RuntimeException e) {
            cleanup(prepared.directory);
            throw new RenderError("The render settings couldn't be saved: " + e.getMessage());
        }
        log.accept("Ready to render.");
        return prepared;
    }

    // ---- the core (no network, no ffmpeg, no UI: testable) -------------------------------------------------

    static Prepared prepare(TemplateTimelineInfo info, TemplateData template, List<SlotPick> picks, VideoSettings settings,
                            ResourceFetcher fetcher, Probe probe, File workRoot, Consumer<String> log) throws RenderError {
        if (info == null || info.timeline == null) throw new RenderError("This template has no timeline.");
        if (info.slots.size() != picks.size()) {
            throw new RenderError("The template changed: it now has " + info.slots.size()
                    + " replaceable clips, not " + picks.size() + ". Reopen it.");
        }

        File directory = new File(workRoot, WORK_FOLDER_PREFIX + UUID.randomUUID());
        File clipsDir = new File(directory, "Clips");
        File fontsDir = new File(directory, FONTS_DIRECTORY);
        if (!clipsDir.mkdirs() || !fontsDir.mkdirs()) {
            throw new RenderError("The working folder can't be created.");
        }

        try {
            Timeline timeline = info.timeline;

            // 2. resources
            fetcher.fetch(TemplateResources.neededNames(template, timeline), clipsDir, fontsDir, log);

            // 3. the user's media into the replaceable clips
            final int canvasW = settings.videoWidth, canvasH = settings.videoHeight;
            for (int i = 0; i < info.slots.size(); i++) {
                TemplateTimelineInfo.Slot slot = info.slots.get(i);
                SlotPick pick = picks.get(i);
                File source = pick == null || pick.path == null ? null : new File(pick.path);
                if (source == null || !source.isFile()) throw new RenderError("Clip " + (i + 1) + " is empty.");

                String ext = extensionOf(source.getName());
                if (ext.isEmpty()) ext = pick.isVideo ? "mov" : "png";
                String name = "template-slot-" + (i + 1) + "." + ext;
                File destination = new File(clipsDir, name);
                try {
                    Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RenderError("Clip " + (i + 1) + " couldn't be copied: " + e.getMessage());
                }

                MediaFacts media = probe.probe(destination);
                if (media.width <= 0 || media.height <= 0) {
                    log.accept("Clip " + (i + 1) + ": its size couldn't be read, the template's box is kept.");
                }
                Clip clip = slot.clip;
                clip.setType(pick.isVideo ? ClipType.VIDEO : ClipType.IMAGE);
                clip.setClipName(name);
                TemplateRetarget.retarget(clip, media.width, media.height, canvasW, canvasH, settings.isStretchToFull());

                if (pick.isVideo) {
                    TemplateRetarget.fitVideo(clip, media.duration, media.hasAudio, slot.duration, pick.startTrim, pick.endTrim);
                } else {
                    TemplateRetarget.fitImage(clip, slot.duration);
                }
                log.accept(String.format(Locale.ROOT, "Clip %d placed: %s, %.1fs of %.1fs", i + 1,
                        pick.isVideo ? "video" : "image", clip.duration, slot.duration));
            }

            timeline.recalculateDuration();
            String title = template.getTemplateTitle() == null || template.getTemplateTitle().isEmpty() ? "Template" : template.getTemplateTitle();
            ProjectData project = new ProjectData(directory.getPath(), title, System.currentTimeMillis(), 0,
                    (long) (timeline.duration * 1000));
            return new Prepared(project, timeline, directory);
        } catch (RenderError | RuntimeException e) {
            deleteTree(directory);
            throw e;
        }
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ---- cleanup ------------------------------------------------------------------------------------------

    /** Deletes a working folder made by {@link #prepare}. Anything else is left alone. */
    public static void cleanup(File directory) {
        if (directory != null && directory.getName().startsWith(WORK_FOLDER_PREFIX)) deleteTree(directory);
    }

    private static void deleteTree(File dir) {
        if (dir == null || !dir.exists()) return;
        try (Stream<java.nio.file.Path> walk = Files.walk(dir.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { /* best effort */ }
            });
        } catch (IOException ignored) {
            /* best effort */
        }
    }
}
