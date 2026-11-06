package com.vanvatcorporation.doubleclips.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.Track;
import com.vanvatcorporation.doubleclips.FFmpegEdit;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import javafx.beans.property.ListProperty;
import javafx.beans.property.SimpleListProperty;
import javafx.collections.FXCollections;
import javafx.application.Platform;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.vanvatcorporation.doubleclips.helper.FileHelper;

public class ProjectRepository {
    private static ProjectRepository instance;
    private final ListProperty<ProjectData> projects = new SimpleListProperty<>(FXCollections.observableArrayList());
    private final Gson gson = new Gson();
    /** Timeline and settings go through ProjectGson, which keeps the keys this build does not know (another platform's). */
    private final Gson exposeGson = com.vanvatcorporation.doubleclips.ProjectGson.forSave();
    private final Gson loadGson = com.vanvatcorporation.doubleclips.ProjectGson.forLoad();

    private ProjectRepository() {
        refreshProjects();
    }

    public static synchronized ProjectRepository getInstance() {
        if (instance == null) {
            instance = new ProjectRepository();
        }
        return instance;
    }

    public ListProperty<ProjectData> projectsProperty() {
        return projects;
    }

    public void refreshProjects() {
        List<ProjectData> loadedProjects = new ArrayList<>();
        File projectsDir = Constants.getProjectsDirectory();
        File[] folders = projectsDir.listFiles(File::isDirectory);

        if (folders != null) {
            for (File folder : folders) {
                ProjectData data = loadProjectProperties(folder);
                if (data != null) {
                    loadedProjects.add(data);
                }
            }
        }

        // Sort by timestamp descending (newest first)
        loadedProjects.sort((a, b) -> Long.compare(b.getProjectTimestamp(), a.getProjectTimestamp()));
        
        Platform.runLater(() -> projects.setAll(loadedProjects));
    }

    public void createNewProject(String title) {
        File projectsDir = Constants.getProjectsDirectory();
        File projectFolder = new File(projectsDir, title);
        
        // Ensure unique folder name if title exists
        int count = 1;
        while (projectFolder.exists()) {
            projectFolder = new File(projectsDir, title + " (" + count + ")");
            count++;
        }

        if (projectFolder.mkdirs()) {
            // Create subdirectories mirroring Android structure
            new File(projectFolder, Constants.DEFAULT_CLIP_DIRECTORY).mkdirs();
            new File(projectFolder, Constants.DEFAULT_CLIP_TEMP_DIRECTORY).mkdirs();
            new File(projectFolder, "Clips/Temp/frames").mkdirs();
            new File(projectFolder, Constants.DEFAULT_PREVIEW_CLIP_DIRECTORY).mkdirs();

            ProjectData data = new ProjectData(
                projectFolder.getAbsolutePath(),
                projectFolder.getName(),
                new Date().getTime(),
                0, // size
                0  // duration
            );

            saveProjectProperties(data);
            refreshProjects();
        }
    }

    public void deleteProject(ProjectData project) {
        try {
            FileHelper.deleteDirectory(Paths.get(project.getProjectPath()));
            refreshProjects();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void cloneProject(ProjectData project) {
        flushSaves(); // the copy must have the newest edit
        try {
            Path source = Paths.get(project.getProjectPath());
            String cloneTitle = project.getProjectTitle() + "_clone";
            Path target = Paths.get(Constants.getProjectsDirectory().getAbsolutePath(), cloneTitle);

            // Unique target path
            int count = 1;
            while (Files.exists(target)) {
                target = Paths.get(Constants.getProjectsDirectory().getAbsolutePath(), cloneTitle + " (" + count + ")");
                count++;
            }

            FileHelper.copyDirectory(source, target);

            // Update metadata for the clone
            ProjectData cloneData = loadProjectProperties(target.toFile());
            if (cloneData != null) {
                cloneData.setProjectTitle(target.getFileName().toString());
                cloneData.setProjectTimestamp(new Date().getTime());
                cloneData.setProjectPath(target.toAbsolutePath().toString());
                saveProjectProperties(cloneData);
            }

            refreshProjects();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void renameProject(ProjectData project, String newTitle) {
        try {
            File oldDir = new File(project.getProjectPath());
            File newDir = new File(oldDir.getParent(), newTitle);

            if (oldDir.renameTo(newDir)) {
                project.setProjectPath(newDir.getAbsolutePath());
                project.setProjectTitle(newTitle);
                saveProjectProperties(project);
                refreshProjects();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void recoverLegacyProject(File folder) {
        String baseName = folder.getName();
        String recoveredTitle = baseName + " [Recovered]";
        
        ProjectData data = new ProjectData(
            folder.getAbsolutePath(),
            recoveredTitle,
            new Date().getTime(),
            0, // size - will be updated later
            0  // duration - will be updated later
        );

        saveProjectProperties(data);
        refreshProjects();
    }

    private ProjectData loadProjectProperties(File projectFolder) {
        File propsFile = new File(projectFolder, Constants.DEFAULT_PROJECT_PROPERTIES_FILENAME);
        if (!propsFile.exists()) return null;

        try (FileReader reader = new FileReader(propsFile)) {
            ProjectData data = gson.fromJson(reader, ProjectData.class);
            // Ensure path is updated to current machine context
            if (data != null) {
                data.setProjectPath(projectFolder.getAbsolutePath());
            }
            return data;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void saveProjectProperties(ProjectData data) {
        File propsFile = new File(data.getProjectPath(), Constants.DEFAULT_PROJECT_PROPERTIES_FILENAME);
        try (FileWriter writer = new FileWriter(propsFile)) {
            gson.toJson(data, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ==========================================
    //  Timeline & Settings Persistence Hooks
    // ==========================================
    public void saveTimeline(ProjectData data, Timeline timeline, VideoSettings settings) {
        // Prepare timeline mathematically
        timeline.recalculateDuration();

        // Update Project Data matching Android tracking logic
        data.setProjectTimestamp(new Date().getTime());
        data.setProjectDuration((long) (timeline.duration * 1000));
        data.setProjectSize(IOHelper.getFileSize(data.getProjectPath()));
        saveProjectProperties(data);

        // Generate Preview Thumbnail
        Clip nearestClip = null;
        if (timeline != null && timeline.tracks != null) {
            for (Track track : timeline.tracks) {
                if (!track.clips.isEmpty()) {
                    nearestClip = track.clips.get(0);
                    break;
                }
            }
        }

        if (nearestClip != null) {
            String previewPath = IOHelper.CombinePath(data.getProjectPath(), "preview.png");
            if (nearestClip.type == ClipType.VIDEO || nearestClip.type == ClipType.IMAGE || nearestClip.type == ClipType.SCENE_3D) {
                FFmpegEdit.generateThumbnail(
                        nearestClip.getAbsolutePath(data),
                        previewPath,
                        nearestClip.type == ClipType.VIDEO ? nearestClip.startClipTrim : 0,
                        -1, -1
                );
            }
        }

        // Save Settings
        if (settings != null) {
            File settingsFile = new File(data.getProjectPath(), Constants.DEFAULT_VIDEO_SETTINGS_FILENAME);
            try (FileWriter writer = new FileWriter(settingsFile)) {
                exposeGson.toJson(settings, writer);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Save Timeline data
        if (timeline != null) {
            File timelineFile = new File(data.getProjectPath(), Constants.DEFAULT_TIMELINE_FILENAME);
            try (FileWriter writer = new FileWriter(timelineFile)) {
                exposeGson.toJson(timeline, writer);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        // Refresh project list visually so last edited jumps top
        refreshProjects();
    }

    // ── Background save ─────────────────────────────────────────────────────
    //  saveTimeline() does all of this on the calling (UI) thread: the project folder's size (a walk over every cached
    //  thumbnail and preview), an ffmpeg process for the project's preview picture, three JSON writes and a re-read of EVERY
    //  project's properties. The editor saves after every edit, so it all ran on every click and grew with the project.
    //  saveTimelineAsync() keeps only the part that must read the live timeline (the JSON text) on the caller and hands the
    //  rest to one background thread; a burst of saves is written once (the newest wins).

    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ProjectSave");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Runnable> pendingSave = new AtomicReference<>();
    /** What the preview picture was last made from, per project: it is only made again when that changes. */
    private final Map<String, String> lastPreviewKey = new ConcurrentHashMap<>();
    /** When a project's folder size was last counted: counting walks the whole folder, so not on every save. */
    private final Map<String, Long> lastSizeAt = new ConcurrentHashMap<>();
    private static final long SIZE_RECOUNT_MS = 10_000;

    public void saveTimelineAsync(ProjectData data, Timeline timeline, VideoSettings settings) {
        // On the caller: what reads the live model.
        if (timeline != null) timeline.recalculateDuration();
        data.setProjectTimestamp(new Date().getTime());
        if (timeline != null) data.setProjectDuration((long) (timeline.duration * 1000));
        final String timelineJson = timeline == null ? null : exposeGson.toJson(timeline);
        final String settingsJson = settings == null ? null : exposeGson.toJson(settings);

        Clip firstClip = null;
        if (timeline != null && timeline.tracks != null) {
            for (Track track : timeline.tracks) {
                if (!track.clips.isEmpty()) { firstClip = track.clips.get(0); break; }
            }
        }
        String previewSource = null;
        float previewTime = 0;
        if (firstClip != null && (firstClip.type == ClipType.VIDEO || firstClip.type == ClipType.IMAGE || firstClip.type == ClipType.SCENE_3D)) {
            previewSource = firstClip.getAbsolutePath(data);
            previewTime = firstClip.type == ClipType.VIDEO ? firstClip.startClipTrim : 0;
        }
        final String fPreviewSource = previewSource;
        final float fPreviewTime = previewTime;

        // In the background: everything that touches the disk.
        pendingSave.set(() -> {
            String path = data.getProjectPath();
            long now = System.currentTimeMillis();
            Long counted = lastSizeAt.get(path);
            if (counted == null || now - counted >= SIZE_RECOUNT_MS) {
                data.setProjectSize(IOHelper.getFileSize(path));
                lastSizeAt.put(path, now);
            }
            saveProjectProperties(data);

            if (fPreviewSource != null) {
                String key = fPreviewSource + "|" + fPreviewTime;
                if (!key.equals(lastPreviewKey.get(path))) {
                    lastPreviewKey.put(path, key);
                    FFmpegEdit.generateThumbnail(fPreviewSource, IOHelper.CombinePath(path, "preview.png"), fPreviewTime, -1, -1);
                }
            }
            if (settingsJson != null) writeAtomically(new File(path, Constants.DEFAULT_VIDEO_SETTINGS_FILENAME), settingsJson);
            if (timelineJson != null) writeAtomically(new File(path, Constants.DEFAULT_TIMELINE_FILENAME), timelineJson);
            refreshProjects();
        });
        saveExecutor.execute(() -> {
            Runnable job = pendingSave.getAndSet(null);     // null: a newer save already took (and wrote) this one's place
            if (job == null) return;
            try {
                job.run();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    /** Waits until every save handed over so far is on disk (closing the editor, copying or exporting the project). */
    public void flushSaves() {
        try {
            saveExecutor.submit(() -> { }).get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // a stuck disk must not freeze the caller for good
        }
    }

    /** The file is written whole under another name and then moved over the old one, so a crash mid-write never leaves half a project. */
    private static void writeAtomically(File target, String text) {
        File tmp = new File(target.getPath() + ".tmp");
        try {
            Files.writeString(tmp.toPath(), text);
            try {
                Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Settings-only save, mirroring Android's VideoSettings.saveSettings().
     * saveTimeline() also saves settings, but recalculates duration and
     * regenerates the preview thumbnail - unnecessary work for something like
     * just flipping the render-engine radio button.
     */
    public void saveVideoSettings(ProjectData data, VideoSettings settings) {
        File settingsFile = new File(data.getProjectPath(), Constants.DEFAULT_VIDEO_SETTINGS_FILENAME);
        try (FileWriter writer = new FileWriter(settingsFile)) {
            exposeGson.toJson(settings, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Timeline loadTimeline(ProjectData data) {
        flushSaves(); // an edit saved a moment ago may still be on its way to the disk
        File timelineFile = new File(data.getProjectPath(), Constants.DEFAULT_TIMELINE_FILENAME);
        if (!timelineFile.exists()) {
            return new Timeline();
        }

        try (FileReader reader = new FileReader(timelineFile)) {
            Timeline timeline = loadGson.fromJson(reader, Timeline.class);
            if (timeline != null) {
                timeline.prepareAfterLoad();
                return timeline;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new Timeline();
    }

    public VideoSettings loadVideoSettings(ProjectData data) {
        File settingsFile = new File(data.getProjectPath(), Constants.DEFAULT_VIDEO_SETTINGS_FILENAME);
        if (!settingsFile.exists()) {
            return VideoSettings.createDefault();
        }

        try (FileReader reader = new FileReader(settingsFile)) {
            VideoSettings settings = loadGson.fromJson(reader, VideoSettings.class);
            if (settings != null) {
                return settings;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return VideoSettings.createDefault();
    }
}
