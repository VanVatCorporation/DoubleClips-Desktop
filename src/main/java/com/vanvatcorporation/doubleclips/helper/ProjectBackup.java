package com.vanvatcorporation.doubleclips.helper;

import com.vanvatcorporation.doubleclips.FFmpegEdit;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.ProjectRepository;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;
import com.vanvatcorporation.doubleclips.data.storage.StorageHelper;
import com.vanvatcorporation.doubleclips.drive.DoubleClipsDrive;
import com.vanvatcorporation.doubleclips.drive.DriveError;
import com.vanvatcorporation.doubleclips.drive.DriveMe;
import com.vanvatcorporation.doubleclips.drive.DriveNode;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Back up a project": zips it (the same ZIP as "Share", render command included) and uploads it to
 * OpenEditz/Projects in Van Vat Drive (iOS: StorageModel.startBackup). Blocking: run it on a background thread.
 */
public final class ProjectBackup {
    private ProjectBackup() {}

    public interface Listener {
        void onZipping(double fraction);
        void onUploading(double fraction);
    }

    public static String formatBytes(long n) {
        if (n < 1024) return n + " B";
        double v = n;
        String[] unit = {"KB", "MB", "GB", "TB"};
        int i = -1;
        while (v >= 1024 && i < unit.length - 1) { v /= 1024; i++; }
        return String.format(v >= 100 ? "%.0f %s" : "%.1f %s", v, unit[i]);
    }

    public static String backupName(String projectTitle) {
        String t = projectTitle == null ? "" : projectTitle.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (t.isEmpty()) t = "project";
        return t + " " + new SimpleDateFormat("yyyy-MM-dd HH.mm.ss").format(new Date()) + ".zip";
    }

    /** @return the uploaded zip's node. @throws CancellationException when {@code cancel} was set. */
    public static DriveNode run(ProjectData project, Listener listener, AtomicBoolean cancel) throws IOException {
        File projectDir = new File(project.getProjectPath());
        File dir = new File(StorageHelper.getCacheDirectory(), "Backups");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File zip = File.createTempFile("backup-", ".zip", dir);
        try {
            // 1. zip
            String problem = ProjectShare.share(projectDir, zip, () -> {
                // Same fixed profile as Share: 1080p30, CRF 18, software encoder (the command is for another machine).
                VideoSettings settings = new VideoSettings(1920, 1080, 30, 18, Integer.MAX_VALUE,
                        VideoSettings.FfmpegPreset.MEDIUM, VideoSettings.FfmpegTune.ZEROLATENCY);
                settings.useHardwareAccel = false;
                Timeline timeline = ProjectRepository.getInstance().loadTimeline(project);
                return FFmpegEdit.generateCmdFull(settings, timeline, project, false, false);
            }, new CompressionHelper.ZipProgressListener() {
                @Override public void onProgress(long written, long total, String name) {
                    if (cancel != null && cancel.get()) throw new CancellationException("Backup stopped."); // ends the zip
                    if (listener != null) listener.onZipping((double) written / Math.max(1L, total));
                }
                @Override public void onCompleted() {}
                @Override public void onError(Exception e) {}
            });
            if (cancel != null && cancel.get()) throw new CancellationException("Backup stopped.");
            if (problem != null) throw new IOException(problem);

            // 2. room?
            long size = zip.length();
            DriveMe me = DoubleClipsDrive.client.me();
            if (size > me.freeBytes()) {
                throw new DriveError(413, "quota_exceeded", "The backup is " + formatBytes(size) + " but only "
                        + formatBytes(me.freeBytes()) + " of storage is left.");
            }

            // 3. upload
            if (listener != null) listener.onUploading(0);
            DriveNode folder = DoubleClipsDrive.openEditz.ensureFolder("Projects");
            return DoubleClipsDrive.openEditz.upload(zip, folder, backupName(project.getProjectTitle()),
                    sent -> { if (listener != null) listener.onUploading((double) sent / Math.max(1L, size)); }, cancel);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
        }
    }
}
