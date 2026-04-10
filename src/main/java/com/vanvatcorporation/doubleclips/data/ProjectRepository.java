package com.vanvatcorporation.doubleclips.data;

import com.google.gson.Gson;
import com.vanvatcorporation.doubleclips.constants.Constants;
import javafx.beans.property.ListProperty;
import javafx.beans.property.SimpleListProperty;
import javafx.collections.FXCollections;
import javafx.application.Platform;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ProjectRepository {
    private static ProjectRepository instance;
    private final ListProperty<ProjectData> projects = new SimpleListProperty<>(FXCollections.observableArrayList());
    private final Gson gson = new Gson();

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
}
