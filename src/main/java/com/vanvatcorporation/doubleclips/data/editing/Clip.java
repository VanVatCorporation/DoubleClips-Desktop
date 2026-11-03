package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.JsonObject;
import com.vanvatcorporation.doubleclips.UnknownKeysHolder;

import com.google.gson.annotations.Expose;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.helper.FrameHelper;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import java.io.Serializable;
import java.util.ArrayList;

public class Clip implements Serializable, UnknownKeysHolder {
    /** Keys of this object's JSON that no field maps to (another platform's), kept across a save. See {@link UnknownKeysHolder}. */
    private transient JsonObject unknownKeys;
    @Override public JsonObject getUnknownKeys() { return unknownKeys; }
    @Override public void setUnknownKeys(JsonObject keys) { unknownKeys = keys; }

    @Expose public ClipType type;
    @Expose private String clipName;
    @Expose public float startTime; // seconds
    @Expose public float duration;  // seconds
    @Expose public float startClipTrim;
    @Expose public float endClipTrim;
    @Expose public float originalDuration;
    /**
     * Desktop only: the keyframes that hold every property ("all" keyframes, which is what older builds wrote for any
     * animation) are also treated as holding the volume only after the user keyed the volume on this clip. Without it every
     * clip with an old position keyframe would suddenly follow that keyframe's volume (always 1) instead of its
     * {@code videoProperties.valueVolume}. A keyframe that lists the volume channel by name (iOS, Android) always counts. See {@link VolumeCurve}.
     */
    @Expose public boolean volumeKeyed;
    @Expose public int trackIndex;
    @Expose public int width;
    @Expose public int height;
    @Expose public String additionalFFmpegCommand;
    @Expose public VideoProperties videoProperties;
    @Expose public AnimatedProperty keyframes = new AnimatedProperty();
    
    @Expose public EffectTemplate effect;

    /**
     * An effect for THIS clip only (the clip's "Effect" tab), as opposed to {@link #effect} of an EFFECT clip, which filters
     * everything below it. {@code offset} / {@code duration} are the window inside the clip (duration 0 = until the clip's
     * end); the strength is {@code params["intensity"]}. Same JSON key as Android. null = none.
     */
    @Expose public EffectTemplate clipEffect;

    /**
     * The clip's masks (see {@link ClipMask}), combined in order; null / empty = no mask. Only video, image and text
     * clips use them.
     */
    @Expose public java.util.List<ClipMask> masks;
    /** How the clip mixes with what is drawn below it (iOS's {@code blendMode} key); null = normal. */
    @Expose public ClipBlendMode blendMode;
    /** iOS's single mask and its keyframes. Only READ, from a project that has them: folded into {@link #masks} on load. */
    @Expose public ClipMask mask;
    @Expose public java.util.List<ClipMask.MaskKeyframe> maskKeys;

    /** The masks to draw at {@code localTime} seconds into the clip (animated parameters resolved); empty for a clip without masks. */
    public java.util.List<ClipMask> resolvedMasks(float localTime) {
        java.util.List<ClipMask> out = new ArrayList<>();
        if (masks != null) for (ClipMask m : masks) out.add(m.resolved(localTime));
        return out;
    }

    /** Does a chroma key that is switched on key this clip? */
    public boolean hasChromaKeys() {
        if (chromaKeys == null) return false;
        for (ChromaKey k : chromaKeys) if (k != null && k.enabled) return true;
        return false;
    }

    /** Is this clip's optical flow switched on? */
    public boolean hasOpticalFlow() {
        return opticalFlow != null && opticalFlow.enabled;
    }

    /** Is this clip's motion blur switched on? */
    public boolean hasMotionBlur() {
        return motionBlur != null && motionBlur.enabled;
    }

    public boolean hasMasks() {
        return masks != null && !masks.isEmpty();
    }

    /** Deep copy of the masks list (null stays null) for undo snapshots. */
    public java.util.List<ClipMask> copyOfMasks() {
        if (masks == null) return null;
        java.util.List<ClipMask> copy = new ArrayList<>();
        for (ClipMask m : masks) copy.add(new ClipMask(m));
        return copy;
    }

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
    /**
     * Set only in a PUBLISHED template (iOS: TemplateGrouping): replaceable clips that share one source carry the same id
     * ("g1", "g2", ... in order of first appearance); null = the clip stands alone. "Use template" fills every member of a
     * group from one pick. Part of the iOS clip JSON; Android ignores it (and keeps it as an unknown key).
     */
    @Expose public String templateGroup;
    @Expose public boolean isReverse;
    /**
     * File name (in the project's Clips folder) of this clip's source reversed as a whole, {@code <clipName>.reversed.mp4},
     * once it has been made: a reversed clip plays that copy instead of the original (see {@link com.vanvatcorporation.doubleclips.ReverseMap}).
     * Clips sharing a source share the copy. null = not made yet. The same JSON key as on iOS ("reversedCopy").
     */
    @Expose public String reversedCopy;
    @Expose public boolean removeBackground;
    /**
     * Colours removed from this clip's picture (the "Background" tab), see {@link ChromaKey}: up to
     * {@link ChromaKey#MAX_KEYS}, a pixel matching any of them goes transparent. null / empty = none. Desktop only.
     */
    @Expose public java.util.List<ChromaKey> chromaKeys;
    /** Frame interpolation of this clip (the Motion tab), see {@link ClipOpticalFlow}; null = off. Desktop only. */
    @Expose public ClipOpticalFlow opticalFlow;
    /** Streaks along the clip's motion (the Motion tab), see {@link ClipMotionBlur}; null = off. Desktop only. */
    @Expose public ClipMotionBlur motionBlur;
    
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
        copyUnknownKeysFrom(other);
        this.clipName = other.clipName;
        this.startTime = other.startTime;
        this.duration = other.duration;
        this.originalDuration = other.originalDuration;
        this.volumeKeyed = other.volumeKeyed;
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
        this.masks = other.copyOfMasks();
        this.blendMode = other.blendMode;
        this.effect = other.effect == null ? null : new EffectTemplate(other.effect); // an effect clip keeps its effect and strength when copied
        this.clipEffect = other.clipEffect == null ? null : new EffectTemplate(other.clipEffect);
        this.sceneConfig = other.sceneConfig;
        this.textureClipName = other.textureClipName;
        this.additionalFFmpegCommand = other.additionalFFmpegCommand;
        this.isMute = other.isMute;
        this.isReverse = other.isReverse;
        this.reversedCopy = other.reversedCopy;
        this.removeBackground = other.removeBackground;
        this.chromaKeys = ChromaKey.copyAll(other.chromaKeys);
        this.opticalFlow = other.opticalFlow == null ? null : new ClipOpticalFlow(other.opticalFlow);
        this.motionBlur = other.motionBlur == null ? null : new ClipMotionBlur(other.motionBlur);
        this.isLockedForTemplate = other.isLockedForTemplate;
        this.templateGroup = other.templateGroup;
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

    /**
     * Moves the END of the clip (start + duration) to the nearest frame, keeping the start; a video / audio clip's end trim follows in
     * source seconds and never past the end of the source (see {@link ClipEndSnap}).
     */
    public void snapEndToFrame(int frameRate) {
        boolean trims = (type == ClipType.VIDEO || type == ClipType.AUDIO) && originalDuration > 0;
        ClipEndSnap.Result r = ClipEndSnap.snap(startTime, duration, endClipTrim, frameRate, trims ? timeMapSnapshot() : null, trims);
        if (!r.changed) return;
        duration = r.duration;
        endClipTrim = r.endClipTrim;
    }

    public float getDuration() { return duration; }
    public void setDuration(float duration) { this.duration = duration; }


    public float getStartClipTrim() { return startClipTrim; }
    public void setStartClipTrim(float value) {
        this.startClipTrim = value;
        refitFromTrims();
    }

    public float getEndClipTrim() { return endClipTrim; }
    public void setEndClipTrim(float value) {
        this.endClipTrim = value;
        refitFromTrims();
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
        normalizeMasks();
    }

    /**
     * iOS reads one mask only ({@code mask} + {@code maskKeys}) and ignores {@code masks}. A clip with exactly one
     * mask that simply adds is written in that layout as well, so an iOS project that went through desktop keeps its mask.
     * Several masks, or a subtract / intersect mask, have no iOS form and are written as {@code masks} alone.
     */
    @Override
    public void onSaving(JsonObject tree) {
        tree.remove("mask");
        tree.remove("maskKeys");
        if (masks == null || masks.size() != 1 || masks.get(0) == null || masks.get(0).mode != ClipMask.Mode.ADD) return;
        JsonObject m = com.vanvatcorporation.doubleclips.ProjectGson.forSave().toJsonTree(masks.get(0)).getAsJsonObject();
        com.google.gson.JsonElement keys = m.remove("keys");
        m.remove("mode");
        tree.add("mask", m);
        if (keys != null && keys.isJsonArray() && keys.getAsJsonArray().size() > 0) tree.add("maskKeys", keys);
    }

    // ---- speed: timeline time <-> source time ----------------------------------------------------

    private transient ClipTimeMap timeMapCache;
    private transient String timeMapSignature;

    /**
     * The clip's speed over its local time: the static {@code valueSpeed}, or a curve when speed has keyframes. Cached until
     * the speed, the clip's start or the speed keyframes change, so it is cheap to ask for on every frame.
     */
    public ClipTimeMap timeMap() {
        float base = videoProperties == null ? 1f : videoProperties.valueSpeed;
        boolean animated = keyframes != null && keyframes.keyframes != null && keyframes.isAnimated(VideoProperties.ValueType.Speed);
        StringBuilder sig = new StringBuilder();
        sig.append(animated ? 'c' : 'k').append(base);
        if (animated) {
            sig.append('|').append(startTime);
            for (Keyframe k : keyframes.keyframesHolding(VideoProperties.ValueType.Speed)) {
                sig.append(';').append(k.getLocalTime()).append(',').append(k.value.getValue(VideoProperties.ValueType.Speed)).append(',').append(k.easing);
            }
        }
        String signature = sig.toString();
        ClipTimeMap cached = timeMapCache;
        if (cached != null && signature.equals(timeMapSignature)) return cached;
        ClipTimeMap map = animated
                ? ClipTimeMap.curve(t -> keyframes.getValueAtTime(this, startTime + (float) t, VideoProperties.ValueType.Speed))
                : ClipTimeMap.constant(base);
        timeMapCache = map;
        timeMapSignature = signature;
        return map;
    }

    /** Video and audio: the timeline length follows the trims AND the speed (iOS). Other types keep trims = plain lengths. */
    private void refitFromTrims() {
        if ((type == ClipType.VIDEO || type == ClipType.AUDIO) && originalDuration > 0) setDuration(fittedDuration());
        else setDuration(originalDuration - endClipTrim - startClipTrim);
    }

    /** Sets the duration to what the trims and the speed need (video / audio). Call after a speed or speed-key edit. */
    public void refitDuration() {
        if ((type == ClipType.VIDEO || type == ClipType.AUDIO) && originalDuration > 0) setDuration(fittedDuration());
    }

    /** Changes whenever the speed map would: use it in a cache key (thumbnails, waveform). */
    public String speedSignature() {
        timeMap();
        return timeMapSignature;
    }

    /**
     * A map frozen at the clip's CURRENT start time, not cached: for a drag that moves {@code startTime} while the map is
     * still needed for the pre-drag clip.
     */
    public ClipTimeMap timeMapSnapshot() {
        float base = videoProperties == null ? 1f : videoProperties.valueSpeed;
        boolean animated = keyframes != null && keyframes.keyframes != null && keyframes.isAnimated(VideoProperties.ValueType.Speed);
        if (!animated) return ClipTimeMap.constant(base);
        final float frozenStart = startTime;
        return ClipTimeMap.curve(t -> keyframes.getValueAtTime(this, frozenStart + (float) t, VideoProperties.ValueType.Speed));
    }

    /** The timeline length that plays the source between the trims at the clip's speed (what iOS stores as the duration). */
    public float fittedDuration() {
        if (originalDuration <= 0) return duration;
        double sourceLength = Math.max(0, originalDuration - startClipTrim - endClipTrim);
        return (float) timeMap().localTime(sourceLength);
    }

    /** Folds iOS's single {@code mask} + {@code maskKeys} into {@link #masks} (as the first mask) and repairs what a file left out. */
    private void normalizeMasks() {
        if (mask != null) {
            if (masks == null) masks = new ArrayList<>();
            if (masks.isEmpty()) {
                if (maskKeys != null) mask.keys = new ArrayList<>(maskKeys);
                masks.add(mask);
            }
            mask = null;
            maskKeys = null;
        }
        if (masks == null) return;
        masks.removeIf(m -> m == null);
        for (ClipMask m : masks) m.normalizeAfterLoad();
        if (masks.isEmpty()) masks = null;
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

    /** True when some property changes over time, i.e. is held by two or more keyframes (see {@link KeyframeChannels}). */
    public boolean hasAnimatedProperties() {
        return keyframes != null && keyframes.keyframes != null && keyframes.hasAnimation();
    }

    /**
     * Folds keyframes that can't animate anything into the static properties: a property held by exactly one
     * keyframe is just that keyframe's value everywhere. Properties no keyframe holds, or that two or more hold,
     * are left alone.
     */
    public void mergingVideoPropertiesFromSingleKeyframe() {
        if (keyframes == null || keyframes.keyframes == null || keyframes.keyframes.isEmpty()) return;
        for (VideoProperties.ValueType type : VideoProperties.ValueType.CHANNELS) {
            // The volume is read through VolumeCurve: an old "all properties" keyframe's volume (always 1) must not become
            // the clip's plain volume, and an animated one is decided by its keys.
            if (type == VideoProperties.ValueType.Volume) continue;
            if (keyframes.holderCount(type) != 1) continue;
            Keyframe only = keyframes.keyframesHolding(type).get(0);
            if (only.value != null) this.videoProperties.setValue(only.value.getValue(type), type);
        }
    }
}
