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
    /** What the real check found (see TemplateAvailability). Null = not checked yet. Not part of the server's JSON. */
    private transient volatile Boolean ffmpegVerified;
    private transient volatile Boolean timelineVerified;
    private int viewCount;
    private int useCount;
    private int heartCount;
    private int bookmarkCount;
    /** Comments on the template, as the server counts them (the list in `comments` is only filled when the tab is opened). */
    private int commentCount;
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
    public int getCommentCount() { return commentCount; }
    /** The timeline's address, or "" when the server has none: a null, "null", or anything that is not an http(s) address counts as none. */
    public String getTemplateTimelineLink() { return cleanLink(templateTimelineLink); }

    public static String cleanLink(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        return (lower.startsWith("http://") || lower.startsWith("https://")) ? t : "";
    }

    /**
     * The template is a timeline (the new kind); false = the old kind that only has an ffmpeg command, or its timeline
     * turned out to be missing / empty when it was checked (TemplateAvailability).
     */
    public boolean hasTimeline() {
        return !getTemplateTimelineLink().isEmpty() && !Boolean.FALSE.equals(timelineVerified);
    }

    /**
     * FFmpeg can render it. Once checked, the check decides. Before that: the server's flag when it sends one, else
     * whether there is a command in the list data (the list usually carries none: the command is fetched on its own).
     */
    public boolean isFfmpegAvailable() {
        if (ffmpegVerified != null) return ffmpegVerified;
        if (ffmpegAvailable != null) return ffmpegAvailable;
        return looksLikeCommand(ffmpegCommand);
    }

    /** The server's own word on whether FFmpeg can render this template; null when it sent none. */
    public Boolean getFfmpegAvailableFlag() { return ffmpegAvailable; }

    /** Something that can be an ffmpeg command: not blank, not "null", and not an error page / JSON the server answered with. */
    public static boolean looksLikeCommand(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("null") || t.equalsIgnoreCase("undefined")) return false;
        char c = t.charAt(0);
        return c != '<' && c != '{' && c != '[';
    }

    /** True when the command in this object came from the server's command endpoint and passed the check. */
    public boolean hasVerifiedFfmpegCommand() {
        return Boolean.TRUE.equals(ffmpegVerified) && looksLikeCommand(ffmpegCommand);
    }
    public void setFfmpegVerified(Boolean verified) { this.ffmpegVerified = verified; }
    public void setTimelineVerified(Boolean verified) { this.timelineVerified = verified; }
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
    public void setUseCount(int useCount) { this.useCount = useCount; }
    public void setCommentCount(int commentCount) { this.commentCount = commentCount; }

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
