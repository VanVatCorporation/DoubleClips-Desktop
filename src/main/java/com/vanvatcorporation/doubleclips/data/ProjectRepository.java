package com.vanvatcorporation.doubleclips.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;
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
import com.vanvatcorporation.doubleclips.helper.FileHelper;

public class ProjectRepository {
    private static ProjectRepository instance;
    private final ListProperty<ProjectData> projects = new SimpleListProperty<>(FXCollections.observableArrayList());
    private final Gson gson = new Gson();
    private final Gson exposeGson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

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
        // Simple file size calculation skip for now, we just save props.
        saveProjectProperties(data);

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

    public Timeline loadTimeline(ProjectData data) {
        File timelineFile = new File(data.getProjectPath(), Constants.DEFAULT_TIMELINE_FILENAME);
        if (!timelineFile.exists()) {
            return new Timeline();
        }

        try (FileReader reader = new FileReader(timelineFile)) {
            Timeline timeline = exposeGson.fromJson(reader, Timeline.class);
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
            VideoSettings settings = exposeGson.fromJson(reader, VideoSettings.class);
            if (settings != null) {
                return settings;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return VideoSettings.createDefault();
    }
}
