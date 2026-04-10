package com.vanvatcorporation.doubleclips.data;

import java.io.Serializable;

public class ProjectData implements Serializable {
    public String version;
    private String projectPath;
    private String projectTitle;
    private long projectTimestamp;
    private long projectSize;
    private long projectDuration;

    public ProjectData(String projectPath, String projectTitle, long projectTimestamp, long projectSize, long projectDuration) {
        this.projectPath = projectPath;
        this.projectTitle = projectTitle;
        this.projectTimestamp = projectTimestamp;
        this.projectSize = projectSize;
        this.projectDuration = projectDuration;
    }

    // Getters and Setters
    public String getProjectPath() { return projectPath; }
    public String getProjectTitle() { return projectTitle; }
    public long getProjectTimestamp() { return projectTimestamp; }
    public long getProjectSize() { return projectSize; }
    public long getProjectDuration() { return projectDuration; }

    public void setProjectPath(String projectPath) { this.projectPath = projectPath; }
    public void setProjectTitle(String projectTitle) { this.projectTitle = projectTitle; }
    public void setProjectTimestamp(long projectTimestamp) { this.projectTimestamp = projectTimestamp; }
    public void setProjectSize(long projectSize) { this.projectSize = projectSize; }
    public void setProjectDuration(long projectDuration) { this.projectDuration = projectDuration; }
}
