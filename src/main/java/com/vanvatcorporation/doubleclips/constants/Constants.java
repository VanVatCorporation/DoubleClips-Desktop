package com.vanvatcorporation.doubleclips.constants;

import com.vanvatcorporation.doubleclips.data.storage.StorageHelper;
import java.io.File;

public class Constants {
    // Filenames for project structure
    public static final String DEFAULT_PROJECT_PROPERTIES_FILENAME = "project.properties";
    public static final String DEFAULT_TIMELINE_FILENAME = "project.timeline";
    public static final String DEFAULT_VIDEO_SETTINGS_FILENAME = "project.settings";
    public static final String DEFAULT_PREVIEW_CLIP_FILENAME = "preview.mp4";
    public static final String DEFAULT_EXPORT_CLIP_FILENAME = "export.mp4";
    public static final String DEFAULT_PREVIEW_IMAGE_FILENAME = "preview.png";

    // Directory names
    public static final String DEFAULT_LOGGING_DIRECTORY = "Logging";
    public static final String DEFAULT_TEMPLATE_CLIP_TEMP_DIRECTORY = "TemplatesClipTemp";
    public static final String DEFAULT_CLIP_DIRECTORY = "Clips";
    public static final String DEFAULT_PREVIEW_CLIP_DIRECTORY = "PreviewClips";
    public static final String DEFAULT_CUTOUT_DIRECTORY = "Cutouts";
    public static final String DEFAULT_CLIP_TEMP_DIRECTORY = "Clips/Temp";

    // Project Root Directory
    public static File getProjectsDirectory() {
        File projectsDir = new File(StorageHelper.getAppDirectory(), "projects");
        if (!projectsDir.exists()) {
            projectsDir.mkdirs();
        }
        return projectsDir;
    }

    // Legacy/Sync regex
    public static final String DEFAULT_MULTI_FFMPEG_COMMAND_REGEX = "<Ffmpeg Command Splitter hehe lmao skibidi tung tung tung sahur>";

    public static String DEFAULT_TEMPLATE_CLIP_STATIC_MARK(String clipName) {
        return "template_static_" + clipName;
    }

    public static String DEFAULT_TEMPLATE_CLIP_MARK(int index) {
        return "template_clip_" + index;
    }

    public static String DEFAULT_TEMPLATE_TRIM_MARK(int index) {
        return "template_trim_" + index;
    }

    public static final String DEFAULT_TEMPLATE_CLIP_SCALE_WIDTH_MARK = "template_scale_width";
    public static final String DEFAULT_TEMPLATE_CLIP_SCALE_HEIGHT_MARK = "template_scale_height";
    public static final String DEFAULT_TEMPLATE_CLIP_EXPORT_MARK = "template_export_mark";
}
