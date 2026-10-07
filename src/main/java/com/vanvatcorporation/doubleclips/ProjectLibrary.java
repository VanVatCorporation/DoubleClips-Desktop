package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The project's media library: the files in {@code <project>/Clips}, and what can be done to them without
 * breaking the clips that use them. Same rules as the iOS editor's library (iOS is the source of truth).
 * <p>
 * Nothing here draws anything. The file operations (import, rename, replace, delete, solid colour) and the
 * timeline updates that must go with them (a rename or delete has to reach the clips in the same step) are
 * plain Java so they can be tested; the Media tab only calls them.
 * <p>
 * Proxies: the preview proxies of a clip live in {@code <project>/PreviewClips} and are named after the clip
 * file ({@code clip.mp4} -> {@code clip.mp4}, {@code clip.wav}, and the thumbnail {@code clip.mp4.jpg}), so
 * rename / delete / replace keep them in step.
 */
public final class ProjectLibrary {

    private ProjectLibrary() {}

    // ---- kinds -----------------------------------------------------------------------------

    public enum Kind {
        VIDEO("Video"), IMAGE("Image"), AUDIO("Audio"), OTHER("File");

        public final String title;

        Kind(String title) {
            this.title = title;
        }

        static final Set<String> VIDEO_EXT = setOf("mp4", "mov", "m4v", "3gp", "3g2", "mkv", "webm", "avi");
        static final Set<String> IMAGE_EXT = setOf("png", "jpg", "jpeg", "heic", "heif", "gif", "webp", "bmp", "tif", "tiff");
        static final Set<String> AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "aif", "aiff", "caf", "flac", "ogg", "opus");

        public static Kind ofName(String fileName) {
            String ext = extensionOf(fileName).toLowerCase(Locale.ROOT);
            if (VIDEO_EXT.contains(ext)) return VIDEO;
            if (IMAGE_EXT.contains(ext)) return IMAGE;
            if (AUDIO_EXT.contains(ext)) return AUDIO;
            return OTHER;
        }

        /** The clip type a file of this kind becomes on the timeline. */
        public ClipType clipType() {
            return this == AUDIO ? ClipType.AUDIO : this == IMAGE ? ClipType.IMAGE : ClipType.VIDEO;
        }
    }

    private static Set<String> setOf(String... values) {
        Set<String> set = new HashSet<>();
        for (String v : values) set.add(v);
        return set;
    }

    // ---- items ---------------------------------------------------------------------------------

    public static final class Item {
        public final String name;
        public final File file;
        public final Kind kind;
        public final long bytes;
        public final long modified;
        /** Seconds, for video and audio; null until read (reading it opens the file). */
        public Double duration;

        public Item(String name, File file, Kind kind, long bytes, long modified) {
            this.name = name;
            this.file = file;
            this.kind = kind;
            this.bytes = bytes;
            this.modified = modified;
        }
    }

    /** A library operation failed in a way worth telling the user; the message is written for them. */
    public static final class LibraryException extends Exception {
        public LibraryException(String message) {
            super(message);
        }

        public LibraryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static File clipsDirectory(String projectPath) {
        return new File(projectPath, Constants.DEFAULT_CLIP_DIRECTORY);
    }

    public static File previewDirectory(String projectPath) {
        return new File(projectPath, Constants.DEFAULT_PREVIEW_CLIP_DIRECTORY);
    }

    /** Every media file in Clips; folders, hidden files and files that are not media are not part of the library. */
    public static List<Item> scan(String projectPath) {
        List<Item> items = new ArrayList<>();
        File[] files = clipsDirectory(projectPath).listFiles();
        if (files == null) return items;
        for (File f : files) {
            if (f.isDirectory() || f.getName().startsWith(".")) continue;
            Kind kind = Kind.ofName(f.getName());
            if (kind == Kind.OTHER) continue;
            items.add(new Item(f.getName(), f, kind, f.length(), f.lastModified()));
        }
        return items;
    }

    // ---- names -----------------------------------------------------------------------------------

    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && dot < fileName.length() - 1 ? fileName.substring(dot + 1) : "";
    }

    static String stemOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * The name for the rename field: trimmed, characters file systems reject replaced by "_", leading dots
     * dropped, and the file's own extension kept. {@code typed} is the name WITHOUT the extension, though a
     * typed extension that matches is tolerated.
     */
    public static String validatedName(String typed, String extension) throws LibraryException {
        String stem = typed == null ? "" : typed.trim();
        String ext = extension == null ? "" : extension;
        if (!ext.isEmpty() && stem.toLowerCase(Locale.ROOT).endsWith("." + ext.toLowerCase(Locale.ROOT))) {
            stem = stem.substring(0, stem.length() - ext.length() - 1);
        }
        StringBuilder clean = new StringBuilder();
        for (char c : stem.toCharArray()) {
            boolean forbidden = "/\\:*?\"<>|".indexOf(c) >= 0 || Character.isISOControl(c);
            clean.append(forbidden ? '_' : c);
        }
        stem = clean.toString().trim();
        while (stem.startsWith(".")) stem = stem.substring(1);
        if (stem.isEmpty()) throw new LibraryException("The name can't be empty.");
        return ext.isEmpty() ? stem : stem + "." + ext;
    }

    /** "name.ext" if free, otherwise "name (1).ext", "name (2).ext"... */
    public static String uniqueName(String name, File directory) {
        if (!new File(directory, name).exists()) return name;
        String ext = extensionOf(name), stem = stemOf(name);
        for (int i = 1; ; i++) {
            String candidate = ext.isEmpty() ? stem + " (" + i + ")" : stem + " (" + i + ")." + ext;
            if (!new File(directory, candidate).exists()) return candidate;
        }
    }

    // ---- import ---------------------------------------------------------------------------------------

    /**
     * Copies a file into Clips and returns the name it has there. The same file imported again (same name AND
     * same size) is reused; a DIFFERENT file whose name is taken becomes "name (1).ext" rather than silently
     * standing in for the old one (and so changing every clip that uses it).
     */
    public static String copyIn(File source, String projectPath) throws LibraryException {
        File directory = clipsDirectory(projectPath);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new LibraryException("Couldn't open the project's Clips folder.");
        String name = source.getName();
        File target = new File(directory, name);
        if (target.exists()) {
            if (sameLocation(source, target) || target.length() == source.length()) return name;
            String unique = uniqueName(name, directory);
            copy(source, new File(directory, unique));
            return unique;
        }
        copy(source, target);
        return name;
    }

    private static boolean sameLocation(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException e) {
            return false;
        }
    }

    private static void copy(File source, File destination) throws LibraryException {
        try {
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            throw new LibraryException("Couldn't copy " + source.getName() + ": " + e.getMessage(), e);
        }
    }

    // ---- proxies, rename, delete, replace ---------------------------------------------------------------

    /** The proxy files in PreviewClips that belong to a clip file: same base name, plus "<name>.jpg" (the thumbnail). */
    public static List<File> previewFiles(String name, String projectPath) {
        List<File> found = new ArrayList<>();
        File[] files = previewDirectory(projectPath).listFiles();
        if (files == null) return found;
        String stem = stemOf(name);
        for (File f : files) {
            if (f.isDirectory() || f.getName().startsWith(".")) continue;
            if (f.getName().equals(name + ".jpg") || stemOf(f.getName()).equals(stem)) found.add(f);
        }
        return found;
    }

    private static File renamedPreview(File preview, String oldName, String newName) {
        String fileName = preview.getName();
        String renamed = fileName.equals(oldName + ".jpg") ? newName + ".jpg"
                : stemOf(newName) + (extensionOf(fileName).isEmpty() ? "" : "." + extensionOf(fileName));
        return new File(preview.getParentFile(), renamed);
    }

    public static void rename(String oldName, String newName, String projectPath) throws LibraryException {
        File directory = clipsDirectory(projectPath);
        File source = new File(directory, oldName), target = new File(directory, newName);
        if (!source.exists()) throw new LibraryException("\"" + oldName + "\" is no longer in the project.");

        // On a case-insensitive disk "clip.MP4" -> "clip.mp4" looks like an existing file: go through a temporary
        // name. Any other existing target is a real clash.
        boolean caseOnly = oldName.equalsIgnoreCase(newName);
        if (!caseOnly && target.exists()) throw new LibraryException("There is already a file called \"" + newName + "\".");

        List<File> previews = previewFiles(oldName, projectPath);
        try {
            if (caseOnly) {
                File temporary = new File(directory, ".rename-" + System.nanoTime());
                Files.move(source.toPath(), temporary.toPath());
                Files.move(temporary.toPath(), target.toPath());
            } else {
                Files.move(source.toPath(), target.toPath());
            }
        } catch (IOException e) {
            throw new LibraryException("Couldn't rename the file: " + e.getMessage(), e);
        }
        for (File preview : previews) {
            File renamed = renamedPreview(preview, oldName, newName);
            try {
                if (!renamed.equals(preview)) {
                    Files.deleteIfExists(renamed.toPath()); // a stale proxy of an older file with that name
                    Files.move(preview.toPath(), renamed.toPath());
                }
            } catch (IOException ignored) {
                // A proxy that can't follow is only a missed shortcut: the preview falls back to the original.
            }
        }
    }

    public static void delete(String name, String projectPath) {
        new File(clipsDirectory(projectPath), name).delete();
        for (File preview : previewFiles(name, projectPath)) preview.delete();
    }

    /**
     * Puts {@code source} in the project in place of {@code oldName} and returns the name clips should use now.
     * A file with the SAME name overwrites the old one (its old proxies are dropped, they are stale); a different
     * name is imported like any file and the caller deletes the old one once the clips point at the new one.
     */
    public static String replace(String oldName, File source, String projectPath) throws LibraryException {
        File directory = clipsDirectory(projectPath);
        File oldFile = new File(directory, oldName);
        if (sameLocation(source, oldFile)) throw new LibraryException("That is the file already in the project.");

        if (source.getName().equalsIgnoreCase(oldName)) {
            File temporary = new File(directory, ".replace-" + System.nanoTime());
            copy(source, temporary);
            try {
                Files.move(temporary.toPath(), oldFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                temporary.delete();
                throw new LibraryException("Couldn't replace the file: " + e.getMessage(), e);
            }
            for (File preview : previewFiles(oldName, projectPath)) preview.delete();
            return oldName;
        }
        return copyIn(source, projectPath);
    }

    // ---- solid colour ---------------------------------------------------------------------------------

    public static final int SOLID_WIDTH = 1920, SOLID_HEIGHT = 1080;

    /**
     * A solid-colour PNG (transparency kept) named like Android's: solid_color_0.png, solid_color_1.png...
     * 1920x1080 so that, as an image clip, it fills a 16:9 canvas without being scaled up.
     */
    public static String createSolidColorImage(Color color, String projectPath) throws LibraryException {
        File directory = clipsDirectory(projectPath);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new LibraryException("Couldn't open the project's Clips folder.");
        int index = 0;
        String name;
        do {
            name = "solid_color_" + index++ + ".png";
        } while (new File(directory, name).exists());

        BufferedImage image = new BufferedImage(SOLID_WIDTH, SOLID_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setComposite(java.awt.AlphaComposite.Src); // write the colour AND its alpha, don't blend with nothing
            g.setColor(color);
            g.fillRect(0, 0, SOLID_WIDTH, SOLID_HEIGHT);
        } finally {
            g.dispose();
        }
        try {
            if (!ImageIO.write(image, "png", new File(directory, name))) throw new LibraryException("Couldn't save the image.");
        } catch (IOException e) {
            throw new LibraryException("Couldn't save the image: " + e.getMessage(), e);
        }
        return name;
    }

    // ---- usage on the timeline ------------------------------------------------------------------------------

    private static boolean isFileClip(Clip clip) {
        return clip.type == ClipType.VIDEO || clip.type == ClipType.IMAGE || clip.type == ClipType.AUDIO;
    }

    public static boolean usesFile(Clip clip, String name) {
        return isFileClip(clip) && name.equals(clip.getClipName());
    }

    /** How many clips use each file, by file name. */
    public static Map<String, Integer> usage(Timeline timeline) {
        Map<String, Integer> counts = new HashMap<>();
        for (Track track : timeline.tracks) {
            for (Clip clip : track.clips) {
                if (isFileClip(clip)) counts.merge(clip.getClipName(), 1, Integer::sum);
            }
        }
        return counts;
    }

    public enum Filter { ALL, VIDEO, IMAGE, AUDIO, UNUSED }

    public enum Sort { NEWEST, NAME, SIZE }

    public static List<Item> visible(List<Item> items, Filter filter, Sort sort, Map<String, Integer> usage) {
        List<Item> list = new ArrayList<>();
        for (Item item : items) {
            boolean show;
            switch (filter) {
                case VIDEO: show = item.kind == Kind.VIDEO; break;
                case IMAGE: show = item.kind == Kind.IMAGE; break;
                case AUDIO: show = item.kind == Kind.AUDIO; break;
                case UNUSED: show = usage.getOrDefault(item.name, 0) == 0; break;
                default: show = true;
            }
            if (show) list.add(item);
        }
        switch (sort) {
            case NAME: list.sort((a, b) -> naturalCompare(a.name, b.name)); break;
            case SIZE: list.sort(Comparator.comparingLong((Item i) -> i.bytes).reversed()); break;
            default: list.sort(Comparator.comparingLong((Item i) -> i.modified).reversed());
        }
        return list;
    }

    public static List<Item> unused(List<Item> items, Map<String, Integer> usage) {
        return visible(items, Filter.UNUSED, Sort.NAME, usage);
    }

    /** "clip2" before "clip10", ignoring case. */
    static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", ""), nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return Integer.compare(na.length(), nb.length());
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    // ---- timeline updates that go with file operations ---------------------------------------------------------

    /** Points every clip that used {@code oldName} at {@code newName}. Returns how many. */
    public static int renameInTimeline(Timeline timeline, String oldName, String newName) {
        int changed = 0;
        for (Track track : timeline.tracks) {
            for (Clip clip : track.clips) {
                if (usesFile(clip, oldName)) {
                    clip.setClipName(newName);
                    changed++;
                }
            }
        }
        return changed;
    }

    /** Removes every clip that uses any of {@code names} (their file is going away). Returns how many. */
    public static int removeClipsUsing(Timeline timeline, Collection<String> names) {
        int removed = 0;
        for (Track track : timeline.tracks) {
            List<Clip> doomed = new ArrayList<>();
            for (Clip clip : track.clips) {
                for (String name : names) {
                    if (usesFile(clip, name)) {
                        doomed.add(clip);
                        break;
                    }
                }
            }
            for (Clip clip : doomed) track.removeClip(clip);
            removed += doomed.size();
        }
        timeline.recalculateDuration();
        return removed;
    }

    /** What a replacement file turned out to be (read by the caller; this class never opens media). */
    public static final class Probe {
        public final Kind kind;
        public final float duration;
        public final int width, height;
        public final boolean hasAudio;

        public Probe(Kind kind, float duration, int width, int height, boolean hasAudio) {
            this.kind = kind;
            this.duration = duration;
            this.width = width;
            this.height = height;
            this.hasAudio = hasAudio;
        }
    }

    /**
     * Points a clip at a new file, keeping its place on the timeline. A new file shorter than the clip's trim
     * shortens the clip to fit. Returns true when the clip had to be shortened.
     */
    public static boolean repoint(Clip clip, String name, Probe info) {
        clip.setClipName(name);
        boolean shortened = false;
        if (info.kind == Kind.VIDEO || info.kind == Kind.AUDIO) {
            float source = info.duration;
            clip.originalDuration = source;
            if (clip.startClipTrim >= source - 0.1f) clip.startClipTrim = 0f;
            float room = Math.max(0.1f, source - clip.startClipTrim);
            if (clip.duration > room) {
                clip.duration = room;
                shortened = true;
            }
            clip.endClipTrim = Math.max(0f, source - clip.startClipTrim - clip.duration);
        }
        if (info.kind != Kind.AUDIO) {
            clip.width = info.width;
            clip.height = info.height;
        }
        if (info.kind == Kind.VIDEO) clip.isClipHasAudio = info.hasAudio;
        return shortened;
    }
}
