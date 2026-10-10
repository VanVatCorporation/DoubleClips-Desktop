package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.annotations.Expose;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.helper.FrameHelper;
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
    // Text look. Every default below (null / 0 / false) means "how text always looked", so projects saved
    // before these existed load unchanged: SansSerif, black, no outline, left-aligned.
    /** Font family name (as the system lists it); null = the default sans-serif. */
    @Expose public String textFontFamily;
    /** File name of an imported font inside the project's Fonts folder; when set it wins over the family. */
    @Expose public String textFontFile;
    @Expose public boolean textBold;
    @Expose public boolean textItalic;
    /** "#RRGGBB"; null = black. */
    @Expose public String textColor;
    /** Outline thickness in canvas pixels, drawn outside the letters; 0 = none. */
    @Expose public float textOutlineWidth;
    /** "#RRGGBB"; null = black. */
    @Expose public String textOutlineColor;
    /** Line alignment inside the text box: 0 left, 1 centre, 2 right. */
    @Expose public int textAlign;
    /**
     * Per-unit text animation (OpenGL only): "CHARACTER", "WORD" or "LINE" animates the clip's own in/out
     * animation separately on each unit, each starting a little after the previous one. null / empty = the
     * whole text animates as one block, as before.
     */
    @Expose public String textUnitMode;
    /** Fraction (0..0.95) of the animation window the units are spread over; 0 = the default, 0.6. */
    @Expose public float textStagger;
    /** "FORWARD" (null), "REVERSE", "CENTER_OUT" or "RANDOM": which unit starts first. */
    @Expose public String textUnitOrder;

    /**
     * The text style in the iOS format (see {@link TextStyleData}). When present it wins over the flat text
     * fields above, which only older projects use; null on a clip that has never been given one.
     */
    @Expose public TextStyleData textStyle;

    /**
     * The style to draw and show for this clip: its {@link #textStyle}, or - for a clip from before that
     * existed - one built from the flat fields, looking exactly as the clip always did (black unless it had
     * a colour, left aligned, wrapping at the clip's width). A new object each time for a legacy clip:
     * change it through {@link #editableTextStyle()}.
     */
    public TextStyleData effectiveTextStyle() {
        if (textStyle != null) return textStyle.normalized(); // may carry Android's spelling too
        return legacyTextStyle();
    }

    /** The clip's own style object to change, creating it from the flat fields the first time. */
    public TextStyleData editableTextStyle() {
        if (textStyle == null) textStyle = legacyTextStyle();
        else if (textStyle.hasAndroidFields()) textStyle = textStyle.normalized();
        return textStyle;
    }

    private TextStyleData legacyTextStyle() {
        TextStyleData d = new TextStyleData();
        d.fontName = textFontFamily == null ? "" : textFontFamily;
        d.fontFile = textFontFile == null || textFontFile.isEmpty() ? null : textFontFile;
        d.bold = textBold;
        d.italic = textItalic;
        d.colorHex = textColor != null ? textColor : "#000000"; // what text has always been drawn in
        d.setAlignmentIndex(textAlign);
        d.outlineWidth = textOutlineWidth;
        d.outlineColorHex = textOutlineColor != null ? textOutlineColor : "#000000";
        d.wrapWidth = -1f; // wrap at the clip's width, as before
        d.unitMode = textUnitMode == null || textUnitMode.isEmpty() ? null : textUnitMode;
        d.stagger = textStagger;
        d.unitOrder = textUnitOrder;
        return d;
    }
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
        this.startClipTrim = other.startClipTrim;
        this.endClipTrim = other.endClipTrim;
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
        this.textFontFamily = other.textFontFamily;
        this.textFontFile = other.textFontFile;
        this.textBold = other.textBold;
        this.textItalic = other.textItalic;
        this.textColor = other.textColor;
        this.textOutlineWidth = other.textOutlineWidth;
        this.textOutlineColor = other.textOutlineColor;
        this.textAlign = other.textAlign;
        this.textUnitMode = other.textUnitMode;
        this.textStagger = other.textStagger;
        this.textUnitOrder = other.textUnitOrder;
        this.textStyle = other.textStyle == null ? null : new TextStyleData(other.textStyle);
        this.effect = other.effect == null ? null : new EffectTemplate(other.effect); // an effect clip keeps its effect and strength when copied
        this.sceneConfig = other.sceneConfig;
        this.textureClipName = other.textureClipName;
        this.additionalFFmpegCommand = other.additionalFFmpegCommand;
        this.isMute = other.isMute;
        this.isReverse = other.isReverse;
        this.removeBackground = other.removeBackground;
        this.isLockedForTemplate = other.isLockedForTemplate;
    }

    public String getClipName() {
        return clipName;
    }

    public void setClipName(String clipName) {
        this.clipName = clipName;
    }

    public float getStartTime() { return startTime; }
    public void setStartTime(float startTime) { this.startTime = startTime; }
    public void setStartTime(float startTime, int frameRate) { this.startTime = FrameHelper.calculateToNearestFrame(startTime, frameRate); }

    public float getDuration() { return duration; }
    public void setDuration(float duration) { this.duration = duration; }

    public float getStartClipTrim() { return startClipTrim; }
    public void setStartClipTrim(float value) {
        this.startClipTrim = value;
        setDuration(originalDuration - endClipTrim - startClipTrim);
    }

    public float getEndClipTrim() { return endClipTrim; }
    public void setEndClipTrim(float value) {
        this.endClipTrim = value;
        setDuration(originalDuration - endClipTrim - startClipTrim);
    }

    public float getLocalClipTime(float playheadTime) {
        return playheadTime - startTime;
    }

    public float getTrimmedLocalTime(float localClipTime) {
        return localClipTime + startClipTrim;
    }

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
