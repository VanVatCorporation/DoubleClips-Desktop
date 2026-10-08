package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.editing.AnimationClip;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;
import com.vanvatcorporation.doubleclips.data.editing.TransitionClip;
import com.vanvatcorporation.doubleclips.data.editing.VideoProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Desktop port of Android's OpenGLEdit, synced with the Android version that is
 * already tested end to end. Kept a 1:1 port on purpose: the only differences
 * from the Android file are the type references (EditingActivity.Timeline/
 * Track/Clip/ClipType/VideoProperties -> data.editing.*); the matrix math, the
 * pivot model, speed/reverse time mapping and keyframe sampling are identical,
 * so a project renders the same on both platforms.
 * <p>
 * Platform-agnostic half of the OpenGL export pipeline - mirrors FFmpegEdit's
 * role: walks Timeline/Track/Clip and produces platform-neutral output
 * (transform matrices + per-clip draw info) that the GL side executes
 * (Android: OpenGLEditNative; desktop: OpenGLExportWorker, a separate process).
 * No GL, no android.*, no javafx.* imports here.
 * <p>
 * SCOPE (same as Android): position / scale / rotation / pivot / opacity /
 * colour grading (hue, saturation, brightness, temperature) / speed / reverse /
 * keyframes / in- and out-animations (ClipAnimation) / transitions between
 * adjacent clips (SUPPORTED_TRANSITION_STYLES, OVERLAP mode), for VIDEO and IMAGE
 * clips composited across tracks. Effects and 3D scenes are NOT rendered (see
 * getUnsupportedFeatures()). Text is rendered only when the platform supplies a
 * {@link TextMetrics} (desktop does: TextLayoutEngine); without one TEXT clips are skipped.
 */
public class OpenGLEdit {

    // ---- Capability flags -------------------------------------------------------
    // The single source of truth for what this renderer can't do yet. The export
    // screen asks getUnsupportedFeatures() and warns from that list, so when a
    // feature is implemented, flip its flag to true and the warning (and the
    // "use OpenGL anyway" choice) stops applying to it automatically - no UI
    // change needed. Anything unsupported is currently skipped/ignored by the
    // compositor rather than approximated.
    // Transitions supported so far - a real per-pixel crossfade/wipe/slide,
    // matching FFmpeg's xfade of the same name, via a two-pass FBO pipeline
    // (each clip rendered to its own full-canvas offscreen texture first, then
    // blended - see OpenGLEditNative.exportTimeline). Only EFFECT_TEMPLATE
    // styles in this set are honored; anything else (radial, circleopen,
    // pixelize, slices, fadeblack/white, distance, diag*, reveal*, custom_*)
    // and any transition using a mode other than OVERLAP are flagged as
    // unsupported by getUnsupportedFeatures below rather than silently
    // approximated or ignored. Keys must match FXCommandEmitter.FXRegistry.
    public static final Set<String> SUPPORTED_TRANSITION_STYLES = new java.util.HashSet<>(java.util.Arrays.asList(
            "fade", "dissolve", "wipeleft", "wiperight", "slideleft", "slideright", "slideup", "slidedown"
    ));
    public static final boolean SUPPORTS_TRANSITIONS = true;
    public static final boolean SUPPORTS_REVERSE = true;
    public static final boolean SUPPORTS_KEYFRAMES = true;
    public static final boolean SUPPORTS_IMAGES = true;

    /**
     * Whether the platform that will RUN this renderer can draw TEXT clips (it has a text rasteriser and
     * supplies a {@link TextMetrics}). A platform flag, not a constant, because this class is shared:
     * desktop's export screen sets it before asking getUnsupportedFeatures(); Android leaves it false
     * until it has a text path of its own, and keeps warning.
     */
    public static volatile boolean textRenderingAvailable = false;

    /**
     * Measures a TEXT clip: the size {width, height} of its rendered text box in CANVAS pixels, or null if
     * there is nothing to draw. The platform's rasteriser draws a bitmap of exactly this box; this class
     * only needs the size to place it (see buildClipMvp), so it stays free of any font or graphics API.
     */
    public interface TextMetrics {
        float[] measure(Clip clip, int canvasWidth, int canvasHeight);
    }

    private TextMetrics textMetrics;

    /** One animatable piece of a text clip (a character, word or line): its rectangle inside the text box, canvas units. */
    public static final class TextUnit {
        public final float x, y, w, h;

        public TextUnit(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /** Splits a TEXT clip into units for a mode ("CHARACTER" / "WORD" / "LINE"), in reading order. Platform-supplied, like {@link TextMetrics}. */
    public interface TextUnitProvider {
        List<TextUnit> units(Clip clip, String mode, int canvasWidth, int canvasHeight);
    }

    private TextUnitProvider textUnitProvider;

    /** Needed (with TextMetrics) for per-unit text animation; without it text just animates as one block. */
    public void setTextUnitProvider(TextUnitProvider provider) {
        this.textUnitProvider = provider;
    }

    public static final String UNIT_CHARACTER = "CHARACTER", UNIT_WORD = "WORD", UNIT_LINE = "LINE";
    public static final float DEFAULT_TEXT_STAGGER = 0.6f;

    /** True when this text clip animates per unit (and so must be drawn as units while an animation window is open). */
    public static boolean animatesPerUnit(Clip clip) {
        if (clip == null || clip.type != ClipType.TEXT) return false;
        com.vanvatcorporation.doubleclips.data.editing.TextStyleData style = clip.effectiveTextStyle();
        String mode = style.unitMode;
        // A background box belongs to the whole text, so with one the text animates as a block.
        return !style.hasBackground()
                && (UNIT_CHARACTER.equals(mode) || UNIT_WORD.equals(mode) || UNIT_LINE.equals(mode));
    }

    /** Required to produce DrawCommands for TEXT clips; without it they are skipped. */
    public void setTextMetrics(TextMetrics textMetrics) {
        this.textMetrics = textMetrics;
    }

    /**
     * Human-readable list of timeline features this renderer will NOT reproduce
     * yet (empty = the OpenGL export will match the FFmpeg one for this project,
     * as far as this class covers). AUDIO clips are fine: audio is always mixed
     * by FFmpeg.
     */
    public static List<String> getUnsupportedFeatures(Timeline timeline) {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        if (timeline == null || timeline.tracks == null) return new ArrayList<>(found);

        for (Track track : timeline.tracks) {
            if (track == null || track.clips == null) continue;
            for (Clip clip : track.clips) {
                if (clip == null) continue;
                switch (clip.type) {
                    case IMAGE:
                        if (!SUPPORTS_IMAGES) found.add("Image clips");
                        break;
                    case TEXT:
                        if (!textRenderingAvailable) found.add("Text clips");
                        break;
                    case EFFECT:
                        // Effects are drawn (see buildEffectCommand); only one this version doesn't know is skipped.
                        if (clip.effect != null && EffectCatalog.find(clip.effect.style) == null) found.add("Effects this version doesn't know");
                        break;
                    case SCENE_3D:
                        found.add("3D scene clips");
                        break;
                    case TRANSITION:
                        found.add("Transition clips");
                        break;
                    default: // VIDEO, AUDIO
                        break;
                }
                if (clip.endTransitionEnabled && clip.endTransition != null
                        && clip.endTransition.effect != null && !"none".equals(clip.endTransition.effect.style)) {
                    if (clip.endTransition.mode != TransitionClip.TransitionMode.OVERLAP) {
                        found.add("Transitions using End First/Begin Second timing (only Overlap is supported)");
                    } else if (!SUPPORTED_TRANSITION_STYLES.contains(clip.endTransition.effect.style)) {
                        found.add("Transitions using the '" + clip.endTransition.effect.style + "' effect");
                    }
                }
                if (!SUPPORTS_REVERSE && clip.isReverse()) found.add("Reversed clips");
                if (!SUPPORTS_KEYFRAMES && clip.hasAnimatedProperties()) found.add("Keyframe animations");
            }
        }
        return new ArrayList<>(found);
    }

    /** One clip's contribution to a single output frame. */
    public static class DrawCommand {
        public final Clip clip;
        /** Where to seek/advance this clip's decoder to, in the clip's OWN source time. */
        public final float localSourceTimeSeconds;
        /** Column-major 4x4, ready for glUniformMatrix4fv(..., false, mvpMatrix, ...). */
        public final float[] mvpMatrix;
        public final float opacity;
        // Color grading, matching FFmpegEdit's hue=h=..:s=..:b=.. and
        // colortemperature=temperature=.. filters (FFmpegEdit.java:421-424).
        // Units match FFmpeg's own: hueDegrees is degrees, saturation/brightness
        // are the same multiplier/offset the hue filter takes, temperatureKelvin
        // is Kelvin (6500 = neutral/no change).
        public final float hueDegrees;
        public final float saturation;
        public final float brightness;
        public final float temperatureKelvin;
        /**
         * Gaussian blur SIGMA in output pixels for this frame, from an active
         * in-animation (see ClipAnimation / buildDrawCommand).
         * 0 means no blur — the caller should skip the extra blur passes
         * entirely rather than run a Gaussian blur shader with sigma 0.
         */
        public final float blurSigmaPixels;
        /**
         * "unfold" in-animation frame warp, relative to the clip's own box: top
         * edge width, bottom edge width, and overall height (anchored at the top
         * edge, widths about the vertical center line = top-center). All three 1 =
         * no warp (every frame outside an active unfold). Applied in the fragment shader.
         */
        public final float unfoldTopWidth;
        public final float unfoldBottomWidth;
        public final float unfoldHeight;
        /** Contrast multiplier about mid-grey from "unfold" (1 = none). Applied in the colour stage of the shader. */
        public final float contrast;
        /** TEXT only: when set, this command draws just one unit of the text (see TextUnit) instead of the whole box. */
        public final String textUnitMode;
        /** Index into the units of {@link #textUnitMode}; -1 = the whole text box. */
        public final int textUnitIndex;

        public DrawCommand(Clip clip, float localSourceTimeSeconds, float[] mvpMatrix, float opacity,
                            float hueDegrees, float saturation, float brightness, float temperatureKelvin, float blurSigmaPixels,
                            float unfoldTopWidth, float unfoldBottomWidth, float unfoldHeight, float contrast) {
            this(clip, localSourceTimeSeconds, mvpMatrix, opacity, hueDegrees, saturation, brightness, temperatureKelvin,
                    blurSigmaPixels, unfoldTopWidth, unfoldBottomWidth, unfoldHeight, contrast, null, -1);
        }

        public DrawCommand(Clip clip, float localSourceTimeSeconds, float[] mvpMatrix, float opacity,
                            float hueDegrees, float saturation, float brightness, float temperatureKelvin, float blurSigmaPixels,
                            float unfoldTopWidth, float unfoldBottomWidth, float unfoldHeight, float contrast,
                            String textUnitMode, int textUnitIndex) {
            this.textUnitMode = textUnitMode;
            this.textUnitIndex = textUnitIndex;
            this.clip = clip;
            this.localSourceTimeSeconds = localSourceTimeSeconds;
            this.mvpMatrix = mvpMatrix;
            this.opacity = opacity;
            this.hueDegrees = hueDegrees;
            this.saturation = saturation;
            this.brightness = brightness;
            this.temperatureKelvin = temperatureKelvin;
            this.blurSigmaPixels = blurSigmaPixels;
            this.unfoldTopWidth = unfoldTopWidth;
            this.unfoldBottomWidth = unfoldBottomWidth;
            this.unfoldHeight = unfoldHeight;
            this.contrast = contrast;
        }
    }

    /**
     * A transition window between two clips on the same track: clipA (ending)
     * and clipB (starting) each get their OWN complete DrawCommand — own
     * transform, own color grading, own local source time — because FFmpeg's
     * xfade blends two INDEPENDENTLY fully-rendered full-canvas layers, not two
     * raw textures at a shared position (see the Android port notes). style is
     * guaranteed to be a key in SUPPORTED_TRANSITION_STYLES (callers filter
     * unsupported ones out before this is created).
     */
    public static class TransitionCommand {
        public final DrawCommand clipACommand;
        public final DrawCommand clipBCommand;
        public final String style;
        /** 0 at the start of the transition window, 1 at the end. */
        public final float progress;

        public TransitionCommand(DrawCommand clipACommand, DrawCommand clipBCommand, String style, float progress) {
            this.clipACommand = clipACommand;
            this.clipBCommand = clipBCommand;
            this.style = style;
            this.progress = progress;
        }
    }

    /**
     * An effect clip at one moment: an ADJUSTMENT LAYER. It filters everything drawn before it in the layer
     * list (the tracks below it in the stack), so clips on later tracks are drawn over the result untouched.
     */
    public static final class EffectCommand {
        public final Clip clip;
        /** The effect's key in {@link EffectCatalog}. */
        public final String style;
        /** The strength setting, already inside the effect's range (1 = the default look). */
        public final float intensity;
        /** 0..1 through the effect clip. */
        public final float progress;
        /** Seconds since the effect clip began. */
        public final float elapsed;

        EffectCommand(Clip clip, String style, float intensity, float progress, float elapsed) {
            this.clip = clip;
            this.style = style;
            this.intensity = intensity;
            this.progress = progress;
            this.elapsed = elapsed;
        }
    }

    /** One track's single frame layer: exactly one of simpleDraw/transition/effect is non-null. Order in the list returned by computeFrameForTimestamp is the actual draw order. */
    public static class FrameLayer {
        public final DrawCommand simpleDraw;
        public final TransitionCommand transition;
        public final EffectCommand effect;
        private FrameLayer(DrawCommand simpleDraw, TransitionCommand transition, EffectCommand effect) {
            this.simpleDraw = simpleDraw;
            this.transition = transition;
            this.effect = effect;
        }
        static FrameLayer of(DrawCommand d) { return new FrameLayer(d, null, null); }
        static FrameLayer of(TransitionCommand t) { return new FrameLayer(null, t, null); }
        static FrameLayer of(EffectCommand e) { return new FrameLayer(null, null, e); }
    }

    /**
     * Computes what to draw for one output timestamp, one FrameLayer per track
     * that has anything active, in the EXACT order tracks were walked. This
     * matches FFmpegEdit's overlay chain (FFmpegEdit.java:107: tracks iterated
     * in list order, each overlaid on top of the accumulated base) — so the
     * caller (OpenGLEditNative) MUST draw this list front-to-back in order,
     * not group simple draws and transitions into separate batches, or track
     * stacking order breaks whenever a project mixes plain and transitioning
     * tracks.
     */
    public List<FrameLayer> computeFrameForTimestamp(Timeline timeline, float outputTimeSeconds,
                                                        int canvasWidth, int canvasHeight, boolean stretchToFull) {
        List<FrameLayer> layers = new ArrayList<>();
        if (timeline == null || timeline.tracks == null) return layers;

        float[] projection = new float[16];
        // left=0,right=W,bottom=H,top=0: deliberately flips Y so pixel-space
        // Y-down (top-left origin, matching FFmpeg's overlay=X:Y convention)
        // lands correctly in NDC — top of canvas -> NDC +1, not -1.
        orthoM(projection, 0, canvasWidth, canvasHeight, 0, -1, 1);

        for (Track track : timeline.tracks) {
            if (track == null || track.clips == null) continue;

            TransitionWindow window = findActiveTransition(track, outputTimeSeconds);
            if (window != null) {
                DrawCommand cmdA = buildDrawCommand(window.clipA, outputTimeSeconds, canvasWidth, canvasHeight, stretchToFull, projection);
                DrawCommand cmdB = buildDrawCommand(window.clipB, outputTimeSeconds, canvasWidth, canvasHeight, stretchToFull, projection);
                if (cmdA != null && cmdB != null) {
                    layers.add(FrameLayer.of(new TransitionCommand(cmdA, cmdB, window.style, window.progress)));
                    continue;
                }
                // One side has an unsupported clip type (e.g. text/3D) - fall
                // through to normal single-clip handling below rather than
                // dropping the track entirely.
            }

            Clip activeClip = findActiveClip(track, outputTimeSeconds);
            if (activeClip == null) continue;
            if (activeClip.type == ClipType.EFFECT) {
                // An effect clip draws nothing itself: it filters the layers listed before it.
                EffectCommand effect = buildEffectCommand(activeClip, outputTimeSeconds);
                if (effect != null) layers.add(FrameLayer.of(effect));
                continue;
            }
            // Per-character / word / line text animation: one layer per unit, in reading order.
            List<DrawCommand> unitCommands = buildTextUnitCommands(activeClip, outputTimeSeconds, canvasWidth, canvasHeight, projection);
            if (unitCommands != null) {
                for (DrawCommand unit : unitCommands) layers.add(FrameLayer.of(unit));
                continue;
            }
            DrawCommand cmd = buildDrawCommand(activeClip, outputTimeSeconds, canvasWidth, canvasHeight, stretchToFull, projection);
            if (cmd != null) layers.add(FrameLayer.of(cmd));
        }

        return layers;
    }

    /** Detects an active OVERLAP-mode, supported-style transition on this track at t, if any. */
    private static class TransitionWindow {
        final Clip clipA, clipB;
        final String style;
        final float progress;
        TransitionWindow(Clip clipA, Clip clipB, String style, float progress) {
            this.clipA = clipA; this.clipB = clipB; this.style = style; this.progress = progress;
        }
    }

    /**
     * Window = [clipA.startTime + clipA.duration - transitionDuration,
     * clipA.startTime + clipA.duration) — derived from FXCommandEmitter's own
     * OVERLAP-mode offset (clipA.duration - transitionDuration), NOT from
     * TransitionClip.startTime (that field appears to be a UI knot-display
     * position, not what the actual FFmpeg render uses).
     * END_FIRST/BEGIN_SECOND have different offsets and are not handled
     * here; they're reported by getUnsupportedFeatures instead.
     */
    private TransitionWindow findActiveTransition(Track track, float t) {
        for (int i = 0; i < track.clips.size() - 1; i++) {
            Clip clipA = track.clips.get(i);
            if (clipA == null || !clipA.endTransitionEnabled || clipA.endTransition == null) continue;
            TransitionClip transition = clipA.endTransition;
            if (transition.mode != TransitionClip.TransitionMode.OVERLAP) continue;
            if (transition.effect == null || "none".equals(transition.effect.style)) continue;
            if (!SUPPORTED_TRANSITION_STYLES.contains(transition.effect.style)) continue;

            float windowEnd = clipA.startTime + clipA.duration;
            float windowStart = windowEnd - transition.duration;
            if (t >= windowStart && t < windowEnd) {
                Clip clipB = track.clips.get(i + 1);
                float progress = transition.duration > 0f ? (t - windowStart) / transition.duration : 1f;
                return new TransitionWindow(clipA, clipB, transition.effect.style, progress);
            }
        }
        return null;
    }

    // ---- clip in / out animations ---------------------------------------------------
    // Animations are data, not code: clip.inAnimation / clip.outAnimation .type is an id looked
    // up in ClipAnimationLoader (bundled animations/in and animations/out, plus installed packs - see ClipAnimationAssets),
    // which gives back a ClipAnimation whose evaluate(p) returns every channel for this
    // frame. Plain Java, shared with the desktop port. This class only decides WHICH
    // progress p applies and how each channel combines with the clip's own properties
    // (see ClipAnimationFrame for the add / multiply / standalone rules).
    // The top-centre squish is applied in the fragment shader (OpenGLEditNative.UNFOLD_WARP_*)
    // as an inverse mapping with edge clamping, so the area the shrunken picture no
    // longer covers is filled with edge pixels rather than showing a gap.
    // An unknown type, or one of the wrong direction, animates nothing (the export reports
    // those up front - see OpenGLEditNative.prepareClipAnimations).

    /** The installed animation for one of a clip's two animation slots, or null (none / unknown / wrong direction). */
    private static ClipAnimation animationFor(AnimationClip slot, ClipAnimation.Direction direction) {
        if (slot == null) return null;
        ClipAnimation def = ClipAnimationLoader.get(slot.type);
        return (def != null && def.getDirection() == direction) ? def : null;
    }

    /**
     * The clip's animation channel values at this output time (NEUTRAL when none is active).
     * The in window starts at the clip's first frame, the out window ends at its last; if the two
     * don't fit in the clip together both shrink proportionally, so they never overlap and at
     * most one is active at any time. The out animation is held at its end state past the clip's
     * nominal end (the outgoing clip of a transition keeps drawing there).
     */
    private ClipAnimationFrame animationFrame(Clip clip, float outputTimeSeconds) {
        ClipAnimation inDef = animationFor(clip.inAnimation, ClipAnimation.Direction.IN);
        ClipAnimation outDef = animationFor(clip.outAnimation, ClipAnimation.Direction.OUT);
        if (inDef == null && outDef == null) return ClipAnimationFrame.NEUTRAL;

        float inRaw = inDef != null ? clip.inAnimation.duration : 0f;
        float outRaw = outDef != null ? clip.outAnimation.duration : 0f;
        if (inDef != null) {
            float inDur = ClipAnimation.fitDuration(inRaw, outRaw, clip.duration);
            // 0 at clip start -> 1 at animation end, -1 outside the window
            float p = ClipAnimation.progress(outputTimeSeconds - clip.startTime, inDur);
            if (p >= 0f) return inDef.evaluate(p);
        }
        if (outDef != null) {
            float outDur = ClipAnimation.fitDuration(outRaw, inRaw, clip.duration);
            float p = ClipAnimation.progressOut(clip.startTime + clip.duration, outputTimeSeconds, outDur);
            if (p >= 0f) return outDef.evaluate(p);
        }
        return ClipAnimationFrame.NEUTRAL;
    }

    /** The effect an EFFECT clip applies at {@code t}, or null when it has none or names one this version doesn't know. */
    private EffectCommand buildEffectCommand(Clip clip, float t) {
        if (clip.effect == null) return null;
        EffectCatalog.Style style = EffectCatalog.find(clip.effect.style);
        if (style == null) return null;
        float elapsed = Math.max(0f, t - clip.startTime);
        float progress = clip.duration > 1e-3f ? Math.max(0f, Math.min(1f, elapsed / clip.duration)) : 1f;
        return new EffectCommand(clip, style.key, EffectCatalog.clampIntensity(style, clip.effect.intensity()), progress, elapsed);
    }

    /**
     * Per-unit text animation. When a TEXT clip animates per unit and its in or out animation window is open at t,
     * returns one command per unit: the clip's own in / out animation evaluated per unit, each starting a little
     * after the previous one (stagger), applied about the unit's own centre on top of the clip's (keyframed)
     * position, scale, rotation and pivot. Returns null when the whole-box path should be used (not a text clip,
     * no unit mode, outside the animation windows - where every unit is neutral anyway - or nothing to split).
     * Same maths as buildClipMvp, so units sit exactly where the whole box would put them.
     * Per unit: delay = stagger * window * rank/(N-1), length = window * (1 - stagger).
     */
    private List<DrawCommand> buildTextUnitCommands(Clip clip, float t, int canvasWidth, int canvasHeight, float[] projection) {
        if (!animatesPerUnit(clip) || textUnitProvider == null || textMetrics == null) return null;

        ClipAnimation inDef = animationFor(clip.inAnimation, ClipAnimation.Direction.IN);
        ClipAnimation outDef = animationFor(clip.outAnimation, ClipAnimation.Direction.OUT);
        if (inDef == null && outDef == null) return null;
        float inRaw = inDef != null ? clip.inAnimation.duration : 0f;
        float outRaw = outDef != null ? clip.outAnimation.duration : 0f;
        float inDur = inDef != null ? ClipAnimation.fitDuration(inRaw, outRaw, clip.duration) : 0f;
        float outDur = outDef != null ? ClipAnimation.fitDuration(outRaw, inRaw, clip.duration) : 0f;
        float clipEnd = clip.startTime + clip.duration;
        boolean inOpen = inDef != null && inDur > 0f && t - clip.startTime >= 0f && t - clip.startTime < inDur;
        boolean outOpen = outDef != null && outDur > 0f && t >= clipEnd - outDur;
        if (!inOpen && !outOpen) return null;

        float[] box = textMetrics.measure(clip, canvasWidth, canvasHeight);
        if (box == null || box[0] <= 0f || box[1] <= 0f) return null;
        com.vanvatcorporation.doubleclips.data.editing.TextStyleData textStyle = clip.effectiveTextStyle();
        List<TextUnit> units = textUnitProvider.units(clip, textStyle.unitMode, canvasWidth, canvasHeight);
        if (units == null || units.isEmpty()) return null;
        int n = units.size();

        // Block transform: the same quantities buildClipMvp reads, with the clip-level animation left out.
        float textW = box[0], textH = box[1];
        float scaleX = readAtTime(clip, t, VideoProperties.ValueType.ScaleX);
        float scaleY = readAtTime(clip, t, VideoProperties.ValueType.ScaleY);
        float posX = readAtTime(clip, t, VideoProperties.ValueType.PosX) + (canvasWidth - textW) / 2f;
        float posY = readAtTime(clip, t, VideoProperties.ValueType.PosY) + (canvasHeight - textH) / 2f;
        float pivotX = readAtTime(clip, t, VideoProperties.ValueType.PivotX);
        float pivotY = readAtTime(clip, t, VideoProperties.ValueType.PivotY);
        float blockRot = readAtTime(clip, t, VideoProperties.ValueType.RotInRadians);
        float cos = (float) Math.cos(blockRot), sin = (float) Math.sin(blockRot);
        float pivotCanvasX = posX + pivotX * textW;
        float pivotCanvasY = posY + pivotY * textH;

        float opacityBase = readAtTime(clip, t, VideoProperties.ValueType.Opacity);
        float hueBase = readAtTime(clip, t, VideoProperties.ValueType.Hue);
        float satBase = readAtTime(clip, t, VideoProperties.ValueType.Saturation);
        float brightBase = readAtTime(clip, t, VideoProperties.ValueType.Brightness);
        float tempBase = readAtTime(clip, t, VideoProperties.ValueType.Temperature);

        float stagger = textStyle.stagger > 0f ? textStyle.stagger : DEFAULT_TEXT_STAGGER;
        stagger = Math.max(0f, Math.min(0.95f, stagger));
        int[] rank = unitRanks(n, textStyle.unitOrder, clip);

        List<DrawCommand> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            TextUnit u = units.get(i);
            float frac = n > 1 ? rank[i] / (float) (n - 1) : 0f;
            ClipAnimationFrame anim = unitAnimation(inOpen, inDef, outDef, inDur, outDur, clip.startTime, clipEnd, t, stagger, frac);

            float ux = u.x + u.w / 2f, uy = u.y + u.h / 2f;
            float dx = (ux - pivotX * textW) * scaleX;
            float dy = (uy - pivotY * textH) * scaleY;
            float centerX = pivotCanvasX + dx * cos - dy * sin + anim.offsetX() * canvasWidth;
            float centerY = pivotCanvasY + dx * sin + dy * cos + anim.offsetY() * canvasHeight;
            float rot = blockRot + (float) Math.toRadians(anim.rotationDegrees());
            float c = (float) Math.cos(rot), s = (float) Math.sin(rot);
            float halfW = u.w * scaleX * anim.scale() / 2f;
            float halfH = u.h * scaleY * anim.scale() / 2f;

            float[] model = new float[16];
            model[0] = halfW * c;   model[1] = halfW * s;   model[2] = 0; model[3] = 0;
            model[4] = -halfH * s;  model[5] = halfH * c;   model[6] = 0; model[7] = 0;
            model[8] = 0;           model[9] = 0;           model[10] = 1; model[11] = 0;
            model[12] = centerX;    model[13] = centerY;    model[14] = 0; model[15] = 1;
            float[] mvp = new float[16];
            multiplyMM(mvp, projection, model);

            out.add(new DrawCommand(clip, 0f, mvp,
                    opacityBase * anim.opacity(),
                    hueBase + anim.hueDegrees(),
                    satBase * anim.saturation(),
                    brightBase + anim.brightness(),
                    tempBase + anim.temperatureKelvin(),
                    0f, // blur is not applied per unit
                    anim.warpTopWidth(), anim.warpBottomWidth(), anim.warpHeight(), anim.contrast(),
                    textStyle.unitMode, i));
        }
        return out;
    }

    /**
     * One unit's animation frame. In: a unit that has not started is held at the first frame, a finished one is
     * neutral. Out: a unit that has not started is neutral, a finished one is held at the last frame.
     * {@code frac} is the unit's rank as 0..1 (0 = first to start).
     */
    static ClipAnimationFrame unitAnimation(boolean inWindow, ClipAnimation inDef, ClipAnimation outDef,
                                            float inDur, float outDur, float clipStart, float clipEnd, float t,
                                            float stagger, float frac) {
        if (inWindow) {
            float delay = stagger * inDur * frac;
            float length = Math.max(inDur * (1f - stagger), 1e-3f);
            float e = (t - clipStart) - delay;
            if (e < 0f) return inDef.evaluate(0f);
            if (e < length) return inDef.evaluate(e / length);
            return ClipAnimationFrame.NEUTRAL;
        }
        float delay = stagger * outDur * frac;
        float length = Math.max(outDur * (1f - stagger), 1e-3f);
        float e = t - (clipEnd - outDur) - delay;
        if (e >= length) return outDef.evaluate(1f);
        if (e >= 0f) return outDef.evaluate(e / length);
        return ClipAnimationFrame.NEUTRAL;
    }

    /** rank[i] = when unit i starts, 0 = first. Deterministic: the same clip always shuffles the same way. */
    static int[] unitRanks(int n, String order, Clip clip) {
        int[] rank = new int[n];
        String o = order == null ? "FORWARD" : order;
        if (o.equals("REVERSE")) {
            for (int i = 0; i < n; i++) rank[i] = n - 1 - i;
        } else if (o.equals("CENTER_OUT")) {
            Integer[] idx = new Integer[n];
            for (int i = 0; i < n; i++) idx[i] = i;
            final float mid = (n - 1) / 2f;
            java.util.Arrays.sort(idx, (a, b) -> {
                int byDistance = Float.compare(Math.abs(a - mid), Math.abs(b - mid));
                return byDistance != 0 ? byDistance : Integer.compare(a, b);
            });
            for (int r = 0; r < n; r++) rank[idx[r]] = r;
        } else if (o.equals("RANDOM")) {
            Integer[] idx = new Integer[n];
            for (int i = 0; i < n; i++) idx[i] = i;
            java.util.Collections.shuffle(java.util.Arrays.asList(idx),
                    new java.util.Random((clip.textContent == null ? 0 : clip.textContent.hashCode()) * 31L + n));
            for (int r = 0; r < n; r++) rank[idx[r]] = r;
        } else {
            for (int i = 0; i < n; i++) rank[i] = i;
        }
        return rank;
    }

    /** Builds one clip's complete draw info at outputTimeSeconds, or null if its type isn't drawable (audio/text/effect/3D — see getUnsupportedFeatures). */
    private DrawCommand buildDrawCommand(Clip clip, float outputTimeSeconds,
                                          int canvasWidth, int canvasHeight, boolean stretchToFull, float[] projection) {
        boolean isText = clip.type == ClipType.TEXT;
        if (clip.type != ClipType.VIDEO && clip.type != ClipType.IMAGE && !isText) {
            return null; // audio has no picture; effects/3D: see getUnsupportedFeatures
        }
        // A text clip is a bitmap of its own text box, centred on the canvas (drawtext's x=(w-text_w)/2),
        // then moved by PosX/PosY. Its size comes from the platform's text measurer.
        float[] textBox = null;
        if (isText) {
            if (textMetrics == null) return null;
            textBox = textMetrics.measure(clip, canvasWidth, canvasHeight);
            if (textBox == null || textBox[0] <= 0f || textBox[1] <= 0f) return null;
        }

        // Speed: FFmpeg remaps clip-local time via
        // setpts='(PTS-STARTPTS)/Speed+...' (FFmpegEdit.java:426), i.e. the
        // clip plays Speed times faster than the output timeline. Elapsed
        // OUTPUT time must be scaled by Speed to get elapsed SOURCE time -
        // this was missing before (localSourceTime just used elapsed output
        // time directly), which made any clip with Speed != 1.0 drift out
        // of sync with FFmpeg's export and eventually its own audio.
        float speed = isText ? 1f : readAtTime(clip, outputTimeSeconds, VideoProperties.ValueType.Speed);
        if (speed <= 0f) speed = 1f; // guard against a bad/zero value stalling the decoder forever
        float elapsedOutput = outputTimeSeconds - clip.startTime;
        // Reversed clips are decoded from a pre-rendered, already-reversed
        // intermediate that OpenGLEditNative builds for just the used trim
        // range (see the Android port notes) - that file starts at local time
        // 0 with the trim-in point, so no startClipTrim offset applies here,
        // unlike the normal (forward, original-file) case. Note: elapsedOutput
        // is intentionally allowed to be negative (clipB pre-rolling into a
        // transition, before its own nominal start) or exceed the clip's own
        // duration (clipA continuing past its nominal end, during a
        // transition) - both are correct here, matching what FFmpeg's own
        // xfade does with the same underlying clip stream.
        float localSourceTime = isText ? 0f // text has no source stream
                : clip.isReverse()
                ? elapsedOutput * speed
                : clip.startClipTrim + elapsedOutput * speed;

        // Every channel is neutral outside the animation window (the shared NEUTRAL frame, no
        // allocation), so the overwhelming majority of frames pay nothing for this.
        ClipAnimationFrame anim = animationFrame(clip, outputTimeSeconds);

        float[] mvp = buildClipMvp(clip, outputTimeSeconds, projection, canvasWidth, canvasHeight, stretchToFull, anim, textBox);
        float opacity = readAtTime(clip, outputTimeSeconds, VideoProperties.ValueType.Opacity)
                * anim.opacity();
        float hue = readAtTime(clip, outputTimeSeconds, VideoProperties.ValueType.Hue)
                + anim.hueDegrees();
        float saturation = readAtTime(clip, outputTimeSeconds, VideoProperties.ValueType.Saturation)
                * anim.saturation();
        float brightness = readAtTime(clip, outputTimeSeconds, VideoProperties.ValueType.Brightness)
                + anim.brightness();
        float temperature = readAtTime(clip, outputTimeSeconds, VideoProperties.ValueType.Temperature)
                + anim.temperatureKelvin();
        float blurSigmaPixels = anim.blurWidthFraction() * canvasWidth;

        return new DrawCommand(clip, localSourceTime, mvp, opacity, hue, saturation, brightness, temperature, blurSigmaPixels,
                anim.warpTopWidth(), anim.warpBottomWidth(), anim.warpHeight(), anim.contrast());
    }

    /**
     * Reads a property at outputTimeSeconds (the ABSOLUTE output-timeline time -
     * AnimatedProperty.getValueAtTime subtracts clip.startTime itself). Works
     * for both keyframed and static clips: getValueAtTime already falls back to
     * clip.videoProperties.getValue(valueType) when there are no keyframes, so
     * no branching is needed here - this always matches whichever the clip has.
     */
    private float readAtTime(Clip clip, float outputTimeSeconds, VideoProperties.ValueType valueType) {
        if (clip.keyframes != null) return clip.keyframes.getValueAtTime(clip, outputTimeSeconds, valueType);
        return clip.videoProperties != null ? clip.videoProperties.getValue(valueType) : 0f;
    }

    private Clip findActiveClip(Track track, float t) {
        for (Clip clip : track.clips) {
            if (clip == null) continue;
            if (t >= clip.startTime && t < clip.startTime + clip.duration) {
                return clip;
            }
        }
        return null;
    }

    /**
     * Builds the MVP matrix for one clip. FFmpegEdit's scale -> rotate(auto-expand bbox)
     * -> overlay chain compensates for the pivot with the same math, so OpenGL and FFmpeg
     * output land the clip in the same place:
     *
     * - baseW/H: the clip's own size (or the canvas size when stretch-to-full);
     *   scaledW/H = baseW/H times ScaleX/ScaleY.
     * - PosX/PosY is the clip's UNSCALED, unrotated top-left corner, independent of pivot.
     * - Scale and rotation both happen around the pivot (normalized 0..1 of the clip).
     *   FFmpeg's rotate filter expands to the rotated bounding box, so FFmpegEdit overlays
     *   that box by its center: pivotPoint + R * (center - pivot), minus overlay_w/2, h/2.
     *   GL needs no expanded canvas; alpha blending handles the transparent margins.
     */
    private float[] buildClipMvp(Clip clip, float outputTimeSeconds, float[] projection,
                                  int canvasWidth, int canvasHeight, boolean stretchToFull, ClipAnimationFrame anim,
                                  float[] textBox) {
        // The in-animation's scale multiplies the clip's own (about its pivot), its offset is a
        // fraction of the canvas size added to PosX/PosY, its rotation is added to RotInRadians.
        float scaleX = readAtTime(clip, outputTimeSeconds, VideoProperties.ValueType.ScaleX) * anim.scale();
        float scaleY = readAtTime(clip, outputTimeSeconds, VideoProperties.ValueType.ScaleY) * anim.scale();
        float posX = readAtTime(clip, outputTimeSeconds, VideoProperties.ValueType.PosX) + anim.offsetX() * canvasWidth;
        float posY = readAtTime(clip, outputTimeSeconds, VideoProperties.ValueType.PosY) + anim.offsetY() * canvasHeight;
        float pivotX = readAtTime(clip, outputTimeSeconds, VideoProperties.ValueType.PivotX);
        float pivotY = readAtTime(clip, outputTimeSeconds, VideoProperties.ValueType.PivotY);
        float rotRadians = readAtTime(clip, outputTimeSeconds, VideoProperties.ValueType.RotInRadians)
                + (float) Math.toRadians(anim.rotationDegrees());

        // Stretch-to-fit: matches FFmpegEdit's scale=w=(stretchToFull ? renderWidth
        // : iw)*ScaleX:h=(stretchToFull ? renderHeight : ih)*ScaleY (FFmpegEdit.java,
        // scaleXStretchExpr/scaleYStretchExpr) - the OUTPUT canvas size replaces the
        // clip's own intrinsic size as the base ScaleX/ScaleY multiplies against.
        float baseW = stretchToFull ? canvasWidth : clip.width;
        float baseH = stretchToFull ? canvasHeight : clip.height;
        if (textBox != null) {
            // TEXT: the base is the text's own box (stretch-to-full never applies), and PosX/PosY are
            // relative to the box centred on the canvas, exactly like drawtext's x=(w-text_w)/2 + PosX.
            baseW = textBox[0];
            baseH = textBox[1];
            posX += (canvasWidth - baseW) / 2f;
            posY += (canvasHeight - baseH) / 2f;
        }
        float scaledW = baseW * scaleX;
        float scaledH = baseH * scaleY;

        float cos = (float) Math.cos(rotRadians);
        float sin = (float) Math.sin(rotRadians);

        // Pivot is ONLY the transform origin for scale and rotation (like CSS
        // transform-origin / Android View.setPivotX). It must NOT move the clip:
        // PosX/PosY is always the canvas position of the clip's UNSCALED, unrotated
        // top-left corner, regardless of pivot. Normalized pivot: [0, 1],
        // 0 = left/top, 1 = right/bottom.
        float halfW = scaledW / 2f;
        float halfH = scaledH / 2f;

        // Pivot point in canvas pixel space. It is located on the UNSCALED clip, so it
        // stays fixed while scale and rotation are applied around it.
        float pivotCanvasX = posX + pivotX * baseW;
        float pivotCanvasY = posY + pivotY * baseH;

        // Vector from pivot to quad center after scaling about the pivot, then rotated
        // about the pivot by rotRadians to get the final center.
        float toCenterX = (0.5f - pivotX) * scaledW;
        float toCenterY = (0.5f - pivotY) * scaledH;
        float rotatedOffsetX = toCenterX * cos - toCenterY * sin;
        float rotatedOffsetY = toCenterX * sin + toCenterY * cos;
        float centerX = pivotCanvasX + rotatedOffsetX;
        float centerY = pivotCanvasY + rotatedOffsetY;

        // Model matrix for a unit quad spanning (-1,-1)..(1,1): rotate + scale
        // by the clip's own half-extents, then translate to its center.
        // Column-major (index = column*4 + row), same layout glUniformMatrix4fv expects.
        float[] model = new float[16];
        model[0] = halfW * cos;   model[1] = halfW * sin;   model[2] = 0; model[3] = 0;
        model[4] = -halfH * sin;  model[5] = halfH * cos;   model[6] = 0; model[7] = 0;
        model[8] = 0;             model[9] = 0;             model[10] = 1; model[11] = 0;
        model[12] = centerX;      model[13] = centerY;      model[14] = 0; model[15] = 1;

        float[] mvp = new float[16];
        multiplyMM(mvp, projection, model);
        return mvp;
    }


    // ---- Minimal platform-neutral 4x4 matrix math (column-major, OpenGL layout) -
    // Deliberately not android.opengl.Matrix: that class doesn't exist on desktop,
    // and this class needs to stay usable from a future desktop OpenGLEditNative.

    public static void orthoM(float[] m, float left, float right, float bottom, float top, float near, float far) {
        float rWidth = 1.0f / (right - left);
        float rHeight = 1.0f / (top - bottom);
        float rDepth = 1.0f / (far - near);
        java.util.Arrays.fill(m, 0f);
        m[0] = 2.0f * rWidth;
        m[5] = 2.0f * rHeight;
        m[10] = -2.0f * rDepth;
        m[12] = -(right + left) * rWidth;
        m[13] = -(top + bottom) * rHeight;
        m[14] = -(far + near) * rDepth;
        m[15] = 1.0f;
    }

    /** result = lhs * rhs. result must not alias lhs or rhs. */
    public static void multiplyMM(float[] result, float[] lhs, float[] rhs) {
        float[] tmp = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += lhs[k * 4 + row] * rhs[col * 4 + k];
                }
                tmp[col * 4 + row] = sum;
            }
        }
        System.arraycopy(tmp, 0, result, 0, 16);
    }
}
