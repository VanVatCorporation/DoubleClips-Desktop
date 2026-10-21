package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.Keyframe;
import com.vanvatcorporation.doubleclips.data.editing.VideoProperties;

/**
 * Putting the user's media into a template clip (iOS: TemplateRenderer.retarget and the trim rules). Pure maths on
 * {@link Clip}: no files, no UI.
 * <ul>
 *   <li>{@link #retarget}: the new media is scaled to COVER the template clip's old box and centred on it; everything
 *       else (scale, rotation, pivot, keyframes, animations, effects) is kept. PosX / PosY, in the clip and in each of its
 *       keyframes, move by however much the box's centre moved, so the new picture sits where the old one did.</li>
 *   <li>{@link #fitVideo} / {@link #fitImage}: which part of the media plays, capped at the slot's length.</li>
 * </ul>
 */
public final class TemplateRetarget {
    private TemplateRetarget() {}

    // ---- the box ------------------------------------------------------------------------------------------

    /**
     * The corners TL, TR, BR, BL of a clip's box in canvas pixels (y down) as x0,y0,x1,y1,..., for the box size
     * {@code baseW x baseH} and the look {@code p}. The same maths as OpenGLEdit.buildClipMvp (and the preview gizmo,
     * and iOS ClipGeometry): PosX / PosY is the UNSCALED, unrotated top-left corner; scale and rotation turn around
     * the pivot, which is a 0..1 point of the box.
     */
    static double[] quad(double baseW, double baseH, VideoProperties p) {
        double scaledW = baseW * p.valueScaleX;
        double scaledH = baseH * p.valueScaleY;
        double px = p.valuePivotX, py = p.valuePivotY;
        double pivotX = p.valuePosX + px * baseW;
        double pivotY = p.valuePosY + py * baseH;
        double theta = Math.toRadians(p.valueRot);   // OpenGLEdit reads RotInRadians = toRadians(valueRot)
        double c = Math.cos(theta), s = Math.sin(theta);
        double[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        double[] out = new double[8];
        for (int i = 0; i < 4; i++) {
            double x = (uv[i][0] - px) * scaledW;
            double y = (uv[i][1] - py) * scaledH;
            out[i * 2] = pivotX + x * c - y * s;
            out[i * 2 + 1] = pivotY + x * s + y * c;
        }
        return out;
    }

    /** The box of a video / image clip with the look {@code p}; null for any other clip type. */
    public static double[] quad(Clip clip, VideoProperties p, int canvasW, int canvasH, boolean stretchToFull) {
        if (clip.type != ClipType.VIDEO && clip.type != ClipType.IMAGE) return null;
        // Stretch-to-fit draws every clip across the canvas; a clip without a stored size uses the canvas too.
        double baseW = stretchToFull ? canvasW : (clip.width > 0 ? clip.width : canvasW);
        double baseH = stretchToFull ? canvasH : (clip.height > 0 ? clip.height : canvasH);
        return quad(baseW, baseH, p);
    }

    /** The middle of the box: the midpoint of the TL and BR corners. */
    static double[] centre(double[] q) {
        return new double[]{(q[0] + q[4]) / 2.0, (q[1] + q[5]) / 2.0};
    }

    // ---- retarget -----------------------------------------------------------------------------------------

    /**
     * Gives the clip the new media's shape. Nothing happens for a media of unknown size. With stretch-to-fit (or an
     * old size that is unknown) only the size changes.
     */
    public static void retarget(Clip clip, int mediaW, int mediaH, int canvasW, int canvasH, boolean stretchToFull) {
        if (mediaW <= 0 || mediaH <= 0) return;
        final int oldW = clip.width, oldH = clip.height;
        if (stretchToFull || oldW <= 0 || oldH <= 0) {
            clip.width = mediaW;
            clip.height = mediaH;
            return;
        }
        double cover = Math.max((double) oldW / mediaW, (double) oldH / mediaH);
        final int newW = Math.max(1, (int) Math.round(mediaW * cover));
        final int newH = Math.max(1, (int) Math.round(mediaH * cover));

        // How far the box's centre moves when only its size changes, for one look. It depends on the pivot, the scale and
        // the rotation of THAT look (not on the position).
        // The looks to correct: the static one, and each keyframe's own moment (resolved per property, so a keyframe
        // that holds only some properties still gets the scale / rotation really in force at its time).
        double[] staticShift = centreShift(clip.videoProperties, oldW, oldH, newW, newH);
        java.util.List<Keyframe> keys = clip.keyframes == null || clip.keyframes.keyframes == null
                ? java.util.Collections.<Keyframe>emptyList() : clip.keyframes.keyframes;
        double[][] keyShifts = new double[keys.size()][];
        for (int i = 0; i < keys.size(); i++) {
            VideoProperties look = clip.keyframes.resolve(clip, keys.get(i).getGlobalTime(clip));
            keyShifts[i] = centreShift(look, oldW, oldH, newW, newH);
        }

        clip.width = newW;
        clip.height = newH;

        clip.videoProperties.valuePosX += (float) staticShift[0];
        clip.videoProperties.valuePosY += (float) staticShift[1];
        for (int i = 0; i < keys.size(); i++) {
            Keyframe k = keys.get(i);
            if (k.value == null) continue;
            // a keyframe only matters for the properties it holds; the others read the static value (shifted above)
            if (k.holds(VideoProperties.ValueType.PosX)) k.value.valuePosX += (float) keyShifts[i][0];
            if (k.holds(VideoProperties.ValueType.PosY)) k.value.valuePosY += (float) keyShifts[i][1];
        }
    }

    /** centre(old box) - centre(new box) for the same look: what PosX / PosY must gain to keep the centre where it was. */
    private static double[] centreShift(VideoProperties look, int oldW, int oldH, int newW, int newH) {
        double[] before = centre(quad(oldW, oldH, look));
        double[] after = centre(quad(newW, newH, look));
        return new double[]{before[0] - after[0], before[1] - after[1]};
    }

    // ---- what plays ---------------------------------------------------------------------------------------

    /** The shortest a clip may be (seconds). */
    public static final float MIN_LENGTH = 0.05f;

    /**
     * A video in a slot. {@code slotDuration} is the slot's TIMELINE length; {@code startTrim} / {@code endTrim} are the
     * user's choice in SOURCE seconds (endTrim <= startTrim = "to the end"). The clip keeps its own speed: at 2x a 4 s slot
     * holds 8 s of the source, so the source part is cut to what fits. A video shorter than the slot simply ends early
     * (nothing after it moves). Set {@code clip.type} first: the length follows the speed only for video.
     */
    public static void fitVideo(Clip clip, double mediaDuration, boolean hasAudio, double slotDuration, double startTrim, double endTrim) {
        final float total = (float) Math.max(MIN_LENGTH, mediaDuration);
        final float start = Math.min(Math.max(0f, (float) startTrim), Math.max(0f, total - MIN_LENGTH));
        final float available = total - start;
        final float wanted = endTrim > startTrim ? (float) (endTrim - startTrim) : available;
        float span = Math.max(MIN_LENGTH, Math.min(wanted, available));

        clip.originalDuration = total;
        clip.startClipTrim = start;
        clip.endClipTrim = Math.max(0f, total - start - span);

        // The timeline length of that part at the clip's speed. Too long for the slot: keep only the source the slot holds.
        final float slot = (float) slotDuration;
        if (clip.fittedDuration() > slot) {
            double source = clip.timeMap().sourceOffset(slot);
            span = (float) Math.max(MIN_LENGTH, Math.min(span, source));
            clip.endClipTrim = Math.max(0f, total - start - span);
        }
        clip.setDuration(Math.max(MIN_LENGTH, Math.min(clip.fittedDuration(), slot)));
        clip.isClipHasAudio = hasAudio;
        clip.audioVolume = hasAudio ? 1f : 0f;
    }

    /** A still lasts the slot's whole length, and has no sound. */
    public static void fitImage(Clip clip, double slotDuration) {
        final float slot = (float) slotDuration;
        clip.originalDuration = slot;
        clip.startClipTrim = 0f;
        clip.endClipTrim = 0f;
        clip.setDuration(slot);
        clip.isClipHasAudio = false;
        clip.audioVolume = 0f;
    }
}
