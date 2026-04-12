package com.vanvatcorporation.doubleclips.data;

import javafx.scene.image.Image;
import java.io.Serializable;

public class ClipReplacementData implements Serializable {
    public enum ClipType {
        VIDEO, IMAGE
    }

    private ClipType type;
    private String clipPath;
    private transient Image clipThumbnail; // Image is not serializable
    private float startClipTrim;
    private float endClipTrim;
    private float duration;

    public ClipReplacementData(ClipType type, String clipPath, Image clipThumbnail) {
        this.type = type;
        this.clipPath = clipPath;
        this.clipThumbnail = clipThumbnail;
    }

    public ClipType getType() { return type; }
    public void setType(ClipType type) { this.type = type; }

    public String getClipPath() { return clipPath; }
    public void setClipPath(String clipPath) { this.clipPath = clipPath; }

    public Image getClipThumbnail() { return clipThumbnail; }
    public void setClipThumbnail(Image clipThumbnail) { this.clipThumbnail = clipThumbnail; }

    public float getStartClipTrim() { return startClipTrim; }
    public void setStartClipTrim(float startClipTrim) { this.startClipTrim = startClipTrim; }

    public float getEndClipTrim() { return endClipTrim; }
    public void setEndClipTrim(float endClipTrim) { this.endClipTrim = endClipTrim; }

    public float getDuration() { return duration; }
    public void setDuration(float duration) { this.duration = duration; }

    public void destroy() {
        clipPath = "";
        startClipTrim = 0;
        endClipTrim = 0;
        duration = 0;
        clipThumbnail = null;
    }
}
