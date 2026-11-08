package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.helper.MediaHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The files a project keeps in {@code PreviewClips}, and which of them a project is MISSING (the same idea as Android's
 * PreviewMedia, but per media FILE of the library: the Media tab lists files, not clips).
 * <p>
 * They are all made from the originals in {@code Clips}, so a share / backup leaves the folder out (see {@link ProjectShare}) and
 * the editor makes them again whenever a project is opened, a file is imported or "Use proxy clips" is switched on:
 * <ul>
 *   <li><b>{@link Kind#THUMBNAIL}</b> - {@code <file>.jpg}: the 128x128 tile in the Media tab. Always, for a video.</li>
 *   <li><b>{@link Kind#AUDIO}</b> - {@code <name>.wav}: the sound the preview plays and the waveform is drawn from. Always: an audio
 *       file, and a video that has sound.</li>
 *   <li><b>{@link Kind#PROXY}</b> - {@code <name>.mp4}: the small video-only copy the preview plays. Only while "Use proxy clips" is
 *       on; with it off the preview, and the timeline thumbnails, use the originals, so nothing is made.</li>
 * </ul>
 */
public final class PreviewMedia {

    public enum Kind { THUMBNAIL, AUDIO, PROXY }

    /** One file to make. */
    public static final class Need {
        public final Kind kind;
        /** The original media file in {@code Clips} (its name is what clips refer to). */
        public final File source;
        public final File target;
        /** Seconds of the source that get processed (progress = processed time / this). */
        public final double sourceSeconds;
        public final boolean isVideoFile;
        public final boolean hasAudio;
        public final int width, height;
        /** The ffmpeg scale of a proxy ("1280:-2" / "-2:720"). */
        final String proxyScale;

        Need(Kind kind, File source, File target, MediaHelper.MediaInfo info, boolean isVideoFile, String proxyScale) {
            this.kind = kind;
            this.source = source;
            this.target = target;
            this.sourceSeconds = info == null ? 0 : info.duration;
            this.isVideoFile = isVideoFile;
            this.hasAudio = info != null && info.hasAudio;
            this.width = info == null ? 0 : info.width;
            this.height = info == null ? 0 : info.height;
            this.proxyScale = proxyScale;
        }

        public String fileName() { return source.getName(); }

        /** Where ffmpeg writes before the finished file is renamed into place: a half-written file is never picked up. */
        public File workFile() {
            String name = target.getName();
            int dot = name.lastIndexOf('.');
            return new File(target.getParentFile(), name.substring(0, dot) + ".tmp" + name.substring(dot));
        }

        /** The ffmpeg arguments (without the program), one element each so no quoting is needed. */
        public List<String> arguments() {
            String out = workFile().getAbsolutePath();
            List<String> a = new ArrayList<>();
            a.add("-i");
            a.add(source.getAbsolutePath());
            switch (kind) {
                case THUMBNAIL:
                    a.add("-vframes"); a.add("1"); a.add("-s"); a.add("128x128");
                    break;
                case AUDIO:
                    a.add("-vn"); a.add("-ac"); a.add("1"); a.add("-ar"); a.add("22050"); a.add("-c:a"); a.add("pcm_s16le");
                    break;
                default:
                    a.add("-vf"); a.add("scale=" + proxyScale);
                    a.add("-c:v"); a.add("libx264"); a.add("-preset"); a.add("ultrafast"); a.add("-crf"); a.add("32");
                    a.add("-g"); a.add("1"); a.add("-an");
                    break;
            }
            a.add("-y");
            a.add(out);
            return a;
        }

        /** Renames the finished work file into place. False when it didn't turn out to be a file. */
        public boolean commit() {
            File work = workFile();
            if (!work.isFile() || work.length() == 0) return false;
            if (target.exists() && !target.delete()) return false;
            return work.renameTo(target);
        }

        public void discard() {
            //noinspection ResultOfMethodCallIgnored
            workFile().delete();
        }
    }

    private PreviewMedia() { }

    // ------------------------------------------------------------------ paths

    private static String stemOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    public static File directory(String projectPath) {
        return ProjectLibrary.previewDirectory(projectPath);
    }

    public static File thumbnailFile(String projectPath, String fileName) { return new File(directory(projectPath), fileName + ".jpg"); }

    public static File audioFile(String projectPath, String fileName) { return new File(directory(projectPath), stemOf(fileName) + ".wav"); }

    public static File proxyFile(String projectPath, String fileName) { return new File(directory(projectPath), stemOf(fileName) + ".mp4"); }

    /** A made file: there, and not an empty leftover. */
    public static boolean isMade(File file) { return file.isFile() && file.length() > 0; }

    /** Whether a clip's preview sound exists (a clip with no file behind it, or no sound, never needs one). */
    public static boolean hasAudioFile(String projectPath, Clip clip) {
        return isMade(audioFile(projectPath, clip.getClipName()));
    }

    // ------------------------------------------------------------------ scanning

    /** The library's video and audio files (not still images, and not the reversed copies the editor keeps beside them). */
    private static List<ProjectLibrary.Item> mediaFiles(String projectPath) {
        List<ProjectLibrary.Item> files = new ArrayList<>();
        for (ProjectLibrary.Item item : ProjectLibrary.scan(projectPath)) {
            if (item.kind != ProjectLibrary.Kind.VIDEO && item.kind != ProjectLibrary.Kind.AUDIO) continue;
            if (ReverseMap.isCacheName(item.name)) continue;
            files.add(item);
        }
        return files;
    }

    /**
     * What is missing in the project. Probes each file that lacks something (to know if a video has sound, and its size), so call
     * it OFF the UI thread.
     *
     * @param useProxy whether "Use proxy clips" is on: only then are missing video proxies part of the result
     */
    public static List<Need> scan(String projectPath, boolean useProxy) {
        List<Need> needs = new ArrayList<>();
        for (ProjectLibrary.Item item : mediaFiles(projectPath)) {
            boolean video = item.kind == ProjectLibrary.Kind.VIDEO;
            File thumb = thumbnailFile(projectPath, item.name);
            File wav = audioFile(projectPath, item.name);
            File proxy = proxyFile(projectPath, item.name);
            boolean needThumb = video && !isMade(thumb);
            boolean needWav = !isMade(wav);
            boolean needProxy = video && useProxy && !isMade(proxy);
            if (!needThumb && !needWav && !needProxy) continue;

            MediaHelper.MediaInfo info = MediaHelper.probeMediaInfo(item.file.getAbsolutePath());
            if (needThumb) needs.add(new Need(Kind.THUMBNAIL, item.file, thumb, info, true, null));
            if (needWav && (!video || info.hasAudio)) needs.add(new Need(Kind.AUDIO, item.file, wav, info, video, null));
            if (needProxy) needs.add(new Need(Kind.PROXY, item.file, proxy, info, true, info.width < info.height ? "-2:720" : "1280:-2"));
        }
        return needs;
    }

    /** How many video FILES are missing their proxy: what "Use proxy clips" is about to make. Cheap: nothing is probed. */
    public static int countMissingProxies(String projectPath) {
        int n = 0;
        for (ProjectLibrary.Item item : mediaFiles(projectPath)) {
            if (item.kind == ProjectLibrary.Kind.VIDEO && !isMade(proxyFile(projectPath, item.name))) n++;
        }
        return n;
    }
}
