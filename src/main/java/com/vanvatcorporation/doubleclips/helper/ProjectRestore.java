package com.vanvatcorporation.doubleclips.helper;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * "Restore into DoubleClips": unpacks a project ZIP (the same zip "Share" and "Back up" make) into the projects folder.
 * Unlike a plain unzip it (1) refuses entries that would land outside the folder it extracts into, (2) never overwrites
 * a project that already exists: the restored one gets a free name, and (3) works in a temporary folder first, so a
 * failed or stopped restore leaves nothing half-written among the projects. Plain java.io: no UI, no editor types.
 */
public final class ProjectRestore {
    private ProjectRestore() {}

    public interface Progress { void onProgress(double fraction, String entryName); }

    public static final class Result {
        /** The new project folder inside the projects directory. */
        public final File folder;
        /** False when the zip held no project properties file: it unpacked, but the app may not list it as a project. */
        public final boolean hasProperties;
        Result(File folder, boolean hasProperties) { this.folder = folder; this.hasProperties = hasProperties; }
    }

    public static Result restore(File zip, File projectsDir, String propertiesFileName, Progress progress, AtomicBoolean cancel) throws IOException {
        if (!zip.isFile()) throw new IOException("The downloaded file is missing.");
        if (!projectsDir.isDirectory() && !projectsDir.mkdirs()) throw new IOException("The projects folder cannot be created.");

        // Same file system as the projects folder, so the final move is a rename.
        Path parent = projectsDir.toPath().toAbsolutePath().getParent();
        Path tmp = Files.createTempDirectory(parent != null ? parent : projectsDir.toPath(), ".restore-");
        boolean moved = false;
        try {
            long total = totalSize(zip);
            extract(zip, tmp, total, progress, cancel);

            File[] top = tmp.toFile().listFiles(f -> !f.getName().equals("__MACOSX"));
            if (top == null || top.length == 0) throw new IOException("The ZIP is empty.");

            File source;
            String baseName;
            if (top.length == 1 && top[0].isDirectory()) {
                source = top[0];                        // the normal case: the project folder is the zip's one top entry
                baseName = top[0].getName();
            } else {
                source = tmp.toFile();                  // loose files: the zip's own name becomes the project folder
                String n = zip.getName();
                baseName = n.toLowerCase().endsWith(".zip") ? n.substring(0, n.length() - 4) : n;
            }
            File target = new File(projectsDir, freeName(projectsDir, baseName));
            Files.move(source.toPath(), target.toPath());
            moved = source.equals(tmp.toFile());
            return new Result(target, propertiesFileName != null && new File(target, propertiesFileName).isFile());
        } finally {
            if (!moved) deleteTree(tmp);
        }
    }

    /** A folder name not used yet in {@code dir}: the name itself, else "name (restored)", "name (restored 2)" ... */
    static String freeName(File dir, String base) {
        String safe = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) safe = "Restored project";
        if (!new File(dir, safe).exists()) return safe;
        String candidate = safe + " (restored)";
        for (int i = 2; new File(dir, candidate).exists(); i++) candidate = safe + " (restored " + i + ")";
        return candidate;
    }

    private static boolean skipped(String name) {
        String n = name.replace('\\', '/');
        return n.startsWith("__MACOSX/") || n.equals("__MACOSX") || n.endsWith(".DS_Store") || n.substring(n.lastIndexOf('/') + 1).startsWith("._");
    }

    private static long totalSize(File zip) {
        long total = 0;
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                if (!entry.isDirectory() && entry.getSize() > 0) total += entry.getSize();
            }
        } catch (IOException ignored) { /* the extraction reports the real problem */ }
        return Math.max(total, 1);
    }

    private static void extract(File zip, Path into, long total, Progress progress, AtomicBoolean cancel) throws IOException {
        Path root = into.toRealPath();
        long done = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            byte[] buf = new byte[64 * 1024];
            while ((entry = zis.getNextEntry()) != null) {
                if (cancel != null && cancel.get()) throw new CancellationException("Restore stopped.");
                String name = entry.getName();
                if (skipped(name)) { zis.closeEntry(); continue; }
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root) || target.equals(root)) {
                    throw new IOException("The ZIP holds an unsafe path (\"" + name + "\") and was not restored.");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = Files.newOutputStream(target)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            if (cancel != null && cancel.get()) throw new CancellationException("Restore stopped.");
                            out.write(buf, 0, n);
                            done += n;
                            if (progress != null) progress.onProgress(Math.min(1.0, (double) done / total), name);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { /* best effort */ } });
        } catch (IOException ignored) { /* best effort */ }
    }
}
