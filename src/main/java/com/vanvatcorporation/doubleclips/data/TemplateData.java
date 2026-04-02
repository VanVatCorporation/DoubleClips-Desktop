package com.vanvatcorporation.doubleclips.data;

import java.io.Serializable;
import java.util.ArrayList;

public class TemplateData implements Serializable {
    public String version;
    private String templateAuthor;
    private String templateId;
    private String templateTitle;
    private String templateDescription;
    private String ffmpegCommand;
    private String templateSnapshotLink;
    private String templateVideoLink;
    private long templateTimestamp;
    private long templateDuration;
    private int templateTotalClip;
    private String[] additionalResourceName;
    private int viewCount;
    private int useCount;
    private int heartCount;
    private int bookmarkCount;
    public boolean isLiked;
    public boolean isBookmarked;

    public TemplateData() {}

    // Getters and Setters matching the Android implementation
    public String getTemplateAuthor() { return templateAuthor; }
    public String getTemplateId() { return templateId; }
    public String getTemplateTitle() { return templateTitle; }
    public String getTemplateDescription() { return templateDescription; }
    public String getFfmpegCommand() { return ffmpegCommand; }
    public String getTemplateSnapshotLink() { return templateSnapshotLink; }
    public String getTemplateVideoLink() { return templateVideoLink; }
    public long getTemplateTimestamp() { return templateTimestamp; }
    public long getTemplateDuration() { return templateDuration; }
    public int getTemplateClipCount() { return templateTotalClip; }
    public String[] getTemplateAdditionalResourcesName() { return additionalResourceName; }
    public int getViewCount() { return viewCount; }
    public int getUseCount() { return useCount; }
    public int getHeartCount() { return heartCount; }
    public int getBookmarkCount() { return bookmarkCount; }

    // Setters for mocking
    public void setTemplateAuthor(String templateAuthor) { this.templateAuthor = templateAuthor; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public void setTemplateTitle(String templateTitle) { this.templateTitle = templateTitle; }
    public void setTemplateVideoLink(String templateVideoLink) { this.templateVideoLink = templateVideoLink; }
    public void setHeartCount(int heartCount) { this.heartCount = heartCount; }
    public void setBookmarkCount(int bookmarkCount) { this.bookmarkCount = bookmarkCount; }
}
