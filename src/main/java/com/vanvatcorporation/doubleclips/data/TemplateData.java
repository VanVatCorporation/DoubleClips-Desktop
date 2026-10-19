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
    /** URL of the template's timeline JSON (same format as a project's timeline, possibly wrapped). Null/empty = the old, ffmpeg-only kind. */
    private String templateTimelineLink;
    /** Folder URL (ends with "/") the template's resource files live in: additionalResourceName[i] is at templateContentLink + name. */
    private String templateContentLink;
    /** The server says whether ffmpegCommand can render this template. Null = an older server: assume yes when there is a command. */
    private Boolean ffmpegAvailable;
    private int viewCount;
    private int useCount;
    private int heartCount;
    private int bookmarkCount;
    public boolean isLiked;
    public boolean isBookmarked;
    private ArrayList<TemplateComment> comments = new ArrayList<>();

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
    public String getTemplateTimelineLink() { return templateTimelineLink == null ? "" : templateTimelineLink.trim(); }
    /** The template is a timeline (the new kind); false = the old kind that only has an ffmpeg command. */
    public boolean hasTimeline() { return !getTemplateTimelineLink().isEmpty(); }
    /** FFmpeg can render it: the server says so, or (older server) there is a command. */
    public boolean isFfmpegAvailable() {
        if (ffmpegAvailable != null) return ffmpegAvailable;
        return ffmpegCommand != null && !ffmpegCommand.trim().isEmpty();
    }
    /**
     * Folder URL (ends with "/") of the template's resource files: the server's templateContentLink, else derived from the
     * preview video's address (.../{user}/{id}/preview.mp4 -> .../{user}/{id}/content/).
     */
    public String getResolvedContentLink() {
        String given = templateContentLink == null ? "" : templateContentLink.trim();
        if (!given.isEmpty()) return given.endsWith("/") ? given : given + "/";
        if (templateVideoLink == null || templateVideoLink.trim().isEmpty()) return "";
        String v = templateVideoLink.trim();
        int slash = v.lastIndexOf('/');
        return slash < 0 ? "" : v.substring(0, slash + 1) + "content/";
    }
    public String getTemplateLocation() {
        return "/" + templateAuthor + "/" + templateId;
    }

    public ArrayList<TemplateComment> getComments() { 
        if (comments == null) comments = new ArrayList<>();
        return comments; 
    }

    // Setters
    public void setTemplateAuthor(String templateAuthor) { this.templateAuthor = templateAuthor; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public void setTemplateTitle(String templateTitle) { this.templateTitle = templateTitle; }
    public void setTemplateVideoLink(String templateVideoLink) { this.templateVideoLink = templateVideoLink; }
    public void setHeartCount(int heartCount) { this.heartCount = heartCount; }
    public void setViewCount(int viewCount) { this.viewCount = viewCount; }
    public void setBookmarkCount(int bookmarkCount) { this.bookmarkCount = bookmarkCount; }

    public void setFfmpegCommand(String ffmpegCommand) {
        this.ffmpegCommand = ffmpegCommand;
    }

    public static class TemplateComment implements Serializable {
        public String username;
        public String content;
        public long commentTimestamp;
        public int heartCount;
    }
}
