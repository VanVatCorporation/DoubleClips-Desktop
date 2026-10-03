package com.vanvatcorporation.doubleclips.helper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * "Share project": turns a project folder into one .zip you can hand to someone (or move to another
 * machine), the same thing Android's share action does. Before zipping it drops a ready-to-run FFmpeg
 * render command for the project into the folder as {@code ffmpegCmd.txt}, so the project can also be
 * rendered elsewhere without the app. The zip's top-level entry is the project folder itself, which is
 * exactly what "Import Project" expects (it unzips into the projects directory).
 * <p>
 * Plain java.io only - the UI (file picker, progress dialog) lives in HomePane, and the command text
 * comes in as a Supplier so this class doesn't depend on the editor.
 */
public final class ProjectShare {

    public static final String RENDER_COMMAND_FILE = "ffmpegCmd.txt";

    private ProjectShare() {}

    /** The suggested file name in the save dialog: export_<title>.zip, with characters file systems reject replaced. */
    public static String suggestedFileName(String projectTitle) {
        String t = projectTitle == null ? "" : projectTitle.trim();
        t = t.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (t.isEmpty()) t = "project";
        return "export_" + t + ".zip";
    }

    /**
     * Why {@code destZip} can't be used, or null if it's fine. The one dangerous case is a destination
     * inside the project folder: the zip would be written into the very folder it is zipping and keep
     * growing as it goes.
     */
    public static String checkDestination(File projectDir, File destZip) {
        if (projectDir == null || !projectDir.isDirectory()) return "The project folder no longer exists.";
        if (destZip == null) return "No destination was chosen.";
        try {
            File project = projectDir.getCanonicalFile();
            File dest = destZip.getCanonicalFile();
            for (File p = dest.getParentFile(); p != null; p = p.getParentFile()) {
                if (p.equals(project)) {
                    return "Choose a location outside the project folder - the zip can't be saved inside the project it contains.";
                }
            }
            File parent = dest.getParentFile();
            if (parent == null || !parent.isDirectory()) return "The destination folder doesn't exist.";
        } catch (IOException e) {
            return "Couldn't check the destination: " + e.getMessage();
        }
        return null;
    }

    /**
     * Writes the render command (if one can be produced) and zips the project to {@code destZip}.
     * Runs on the calling thread and reports progress through {@code listener} (which may be null).
     * Returns null on success, otherwise a message fit to show the user; a failed zip leaves no
     * half-written file behind. A failure to produce the render command never blocks the share - the
     * project is more useful without that file than not shared at all.
     */
    public static String share(File projectDir, File destZip, Supplier<String> renderCommand,
                               CompressionHelper.ZipProgressListener listener) {
        String problem = checkDestination(projectDir, destZip);
        if (problem != null) return problem;

        if (renderCommand != null) {
            try {
                String cmd = renderCommand.get();
                if (cmd != null && !cmd.isEmpty()) {
                    Files.write(new File(projectDir, RENDER_COMMAND_FILE).toPath(), cmd.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                e.printStackTrace(); // the share goes ahead without the command file
            }
        }

        AtomicReference<Exception> failure = new AtomicReference<>();
        CompressionHelper.zipFolder(projectDir, destZip, new CompressionHelper.ZipProgressListener() {
            @Override public void onProgress(long bytesWritten, long totalBytes, String name) {
                if (listener != null) listener.onProgress(bytesWritten, totalBytes, name);
            }
            @Override public void onCompleted() {
                if (listener != null) listener.onCompleted();
            }
            @Override public void onError(Exception e) {
                failure.set(e);
                if (listener != null) listener.onError(e);
            }
        });

        Exception e = failure.get();
        if (e != null) {
            //noinspection ResultOfMethodCallIgnored
            destZip.delete();
            String msg = e.getMessage();
            return "Couldn't create the zip" + (msg == null || msg.isEmpty() ? "." : ": " + msg);
        }
        return null;
    }
}
