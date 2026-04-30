package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.annotations.Expose;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import java.io.Serializable;
import java.util.ArrayList;

public class Clip implements Serializable {
    @Expose public ClipType type;
    @Expose private String clipName;
    @Expose public float startTime; // seconds
    @Expose public float duration;  // seconds
    @Expose public float startClipTrim;
    @Expose public float endClipTrim;
    @Expose public float originalDuration;
    @Expose public int trackIndex;
    @Expose public int width;
    @Expose public int height;
    @Expose public String additionalFFmpegCommand;
    @Expose public VideoProperties videoProperties;
    @Expose public AnimatedProperty keyframes = new AnimatedProperty();
    
    @Expose public EffectTemplate effect;
    @Expose public String textContent;
    @Expose public float fontSize;
    @Expose public String sceneConfig;
    @Expose public String textureClipName;
    
    @Expose public TransitionClip endTransition = null;
    @Expose public boolean endTransitionEnabled = false;
    @Expose public boolean isClipHasAudio;
    @Expose public boolean isMute;
    @Expose public boolean isLockedForTemplate;
    @Expose public boolean isReverse;
    @Expose public boolean removeBackground;
    
    @Expose public AnimationClip inAnimation = null;
    @Expose public AnimationClip outAnimation = null;
    @Expose public AnimationClip comboAnimation = null;

    // UI Handle (Optional for desktop, can be used for the JavaFX Node)
    public transient Object viewRef;

    public Clip() {
        this.videoProperties = new VideoProperties();
        this.keyframes = new AnimatedProperty();
        this.inAnimation = new AnimationClip("none", 0.5f);
        this.outAnimation = new AnimationClip("none", 0.5f);
        this.comboAnimation = new AnimationClip("none", 0.5f);
    }

    public Clip(String clipName, float startTime, float duration, int trackIndex, ClipType type, boolean isClipHasAudio, int width, int height) {
        this.clipName = clipName;
        this.startTime = startTime;
        this.duration = duration;
        this.originalDuration = duration;
        this.trackIndex = trackIndex;
        this.type = type;
        this.isClipHasAudio = isClipHasAudio;
        this.width = width;
        this.height = height;
        this.videoProperties = new VideoProperties();
        this.inAnimation = new AnimationClip("none", 0.5f);
        this.outAnimation = new AnimationClip("none", 0.5f);
        this.comboAnimation = new AnimationClip("none", 0.5f);
        this.keyframes = new AnimatedProperty();
    }

    public Clip(Clip other) {
        this.clipName = other.clipName;
        this.startTime = other.startTime;
        this.duration = other.duration;
        this.originalDuration = other.originalDuration;
        this.trackIndex = other.trackIndex;
        this.type = other.type;
        this.isClipHasAudio = other.isClipHasAudio;
        this.width = other.width;
        this.height = other.height;
        this.videoProperties = new VideoProperties(other.videoProperties);
        this.keyframes = new AnimatedProperty(other.keyframes);
        this.inAnimation = new AnimationClip(other.inAnimation);
        this.outAnimation = new AnimationClip(other.outAnimation);
        this.comboAnimation = new AnimationClip(other.comboAnimation);
        this.endTransition = other.endTransition;
        this.endTransitionEnabled = other.endTransitionEnabled;
        this.textContent = other.textContent;
        this.fontSize = other.fontSize;
        this.sceneConfig = other.sceneConfig;
        this.textureClipName = other.textureClipName;
    }

    public String getClipName() {
        return clipName;
    }

    public void setClipName(String clipName) {
        this.clipName = clipName;
    }

    public float getStartTime() { return startTime; }
    public void setStartTime(float startTime) { this.startTime = startTime; }

    public float getDuration() { return duration; }
    public void setDuration(float duration) { this.duration = duration; }

    public int getTrackIndex() { return trackIndex; }
    public void setTrackIndex(int trackIndex) { this.trackIndex = trackIndex; }

    public ClipType getType() { return type; }
    public void setType(ClipType type) { this.type = type; }

    public EffectTemplate getEffect() { return effect; }
    public void setEffect(EffectTemplate effect) { this.effect = effect; }

    public void filterNullAfterLoad() {
        if (type == null) type = ClipType.VIDEO;
        if (videoProperties == null) videoProperties = new VideoProperties();
        if (keyframes == null) keyframes = new AnimatedProperty();
        if (keyframes.keyframes == null) keyframes.keyframes = new ArrayList<>();
        if (inAnimation == null) inAnimation = new AnimationClip("none", 0.5f);
        if (outAnimation == null) outAnimation = new AnimationClip("none", 0.5f);
        if (comboAnimation == null) comboAnimation = new AnimationClip("none", 0.5f);
    }

    public String getAbsolutePath(ProjectData data) {
        if (data == null) return clipName;
        return IOHelper.CombinePath(data.getProjectPath(), "Clips", clipName);
    }

    public String getAbsolutePreviewPath(ProjectData data) {
        if (data == null) return clipName;
        return IOHelper.CombinePath(data.getProjectPath(), "PreviewClips", clipName);
    }

    public String getAbsolutePreviewPath(ProjectData data, String extension) {
        if (data == null) return clipName;
        String baseName = clipName.contains(".") ? clipName.substring(0, clipName.lastIndexOf('.')) : clipName;
        return IOHelper.CombinePath(data.getProjectPath(), "PreviewClips", baseName + extension);
    }

    public String getCutoutPath(String projectPath) {
        return IOHelper.CombinePath(projectPath, "Cutouts", clipName);
    }

    public boolean isClipTransitionAvailable() {
        return endTransitionEnabled && endTransition != null;
    }

    public boolean isClipHasAudio() {
        return isClipHasAudio;
    }

    public boolean isMute() {
        return isMute;
    }

    public boolean isLockedForTemplate() {
        return isLockedForTemplate;
    }

    public boolean isReverse() {
        return isReverse;
    }

    public boolean hasAnimatedProperties() {
        return keyframes != null && keyframes.keyframes != null && keyframes.keyframes.size() > 1;
    }

    public void mergingVideoPropertiesFromSingleKeyframe() {
        if (keyframes != null && keyframes.keyframes != null && keyframes.keyframes.size() == 1) {
            Keyframe singleKey = keyframes.keyframes.get(0);
            if (singleKey.value != null) {
                // Simplistic merge: overwrite base properties with keyframe values
                this.videoProperties = new VideoProperties(singleKey.value);
            }
        }
    }
}
