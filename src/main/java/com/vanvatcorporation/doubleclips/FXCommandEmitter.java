package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.TransitionClip;
import com.vanvatcorporation.doubleclips.data.editing.EffectTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class FXCommandEmitter {

    public static String emit(Clip clip, FFmpegEdit.FfmpegFilterComplexTags.FilterComplexInfo affectedTags,
                              FFmpegEdit.FfmpegFilterComplexTags tags) {
        if (affectedTags == null) return "";
        String outputLabel = "[transition_" +
                affectedTags.tag.replace("[trans-video-", "").replace("]", "") + "]";

        EffectTemplate fx = clip.getEffect();

        if (fx == null || fx.style == null) return "";

        switch (fx.style) {
            case "glitch-pulse":
                tags.storeTag(outputLabel);
                return affectedTags.tag + "tblend=all_mode=addition,framestep=2,eq=brightness=0.2" + outputLabel + ";";

            case "warp-zoom":
                tags.storeTag(outputLabel);
                return affectedTags.tag + "zoompan=z='zoom+0.001':d=" + (int) (clip.getDuration() * 30) +
                        ":x='iw/2':y='ih/2'" + outputLabel + ";";

            case "lens-flare-surge":
                tags.storeTag(outputLabel);
                return affectedTags.tag + "curves=preset=cross_process,eq=contrast=1.5:saturation=1.2" + outputLabel + ";";

            case "spin-burst":
                tags.storeTag(outputLabel);
                return affectedTags.tag + "rotate='2*PI*t/" + clip.getDuration() + "'" + outputLabel + ";";
        }
        return ""; 
    }

    public static String emitTransition(Clip clipA, Clip clipB, TransitionClip transition,
                                        FFmpegEdit.FfmpegFilterComplexTags tags) {

        if (clipA == null || clipB == null) return "";

        Clip mergedClip = new Clip();
        mergedClip.setClipName("MERGED");
        mergedClip.setStartTime(clipA.getStartTime());
        mergedClip.setDuration(clipA.getDuration() + clipB.getDuration() -
                (transition.mode == TransitionClip.TransitionMode.OVERLAP ? transition.duration : 0));
        mergedClip.setTrackIndex(clipA.getTrackIndex());
        mergedClip.setType(clipA.getType());

        FFmpegEdit.FfmpegFilterComplexTags.FilterComplexInfo fromTag = tags.useTag(clipA, mergedClip);
        FFmpegEdit.FfmpegFilterComplexTags.FilterComplexInfo toTag = tags.useTag(clipB, mergedClip);

        if (fromTag == null || toTag == null) return "";

        String outputLabel = "[transition_" +
                fromTag.tag.replace("[transition_", "")
                        .replace("[trans-video-", "").replace("]", "") + "_" +
                toTag.tag.replace("[transition_", "")
                        .replace("[trans-video-", "").replace("]", "") + "]";

        float transitionOffset = 0;
        switch (transition.mode) {
            case END_FIRST:
                transitionOffset = (clipA.getDuration() - (transition.duration * 2));
                break;
            case OVERLAP:
                transitionOffset = (clipA.getDuration() - transition.duration);
                break;
            case BEGIN_SECOND:
                transitionOffset = clipA.getDuration();
                break;
        }

        if (transition.effect != null && transition.effect.style != null && transition.effect.style.contains("custom_")) {
            String customStyle = transition.effect.style.replace("custom_", "");
            String expression = "";
            switch (customStyle) {
                case "expose":
                    expression = "if(gt(Y, H*(1-P)), A, B)";
                    break;
                case "two-stage-slide":
                    expression = "if(P<0.5, B*(Y<H*(0.5+0.5*P)), B*(Y<H*(1.0))*(1-P) + A*(1-(Y<H*(1.0))*(1-P)))";
                    break;
                case "radial-shockwave":
                    expression = "B*sin(P*PI)*exp(-((X-W/2)^2+(Y-H/2)^2)/10000) + A*(1-sin(P*PI))";
                    break;
                case "massive-effect":
                    expression = "(B*(sin(P*PI)*exp(-((X-W/2)^2+(Y-H/2)^2)/5000) + cos(P*PI/2)*sin(sqrt((X-W/2)^2+(Y-H/2)^2)/50)*exp(-P*5)) + A*(1-sin(P*PI))*(1 - exp(-((X-W/2)^2+(Y-H/2)^2)/8000)) + (mod(X+Y+P*1000, 20)/20)*sin(P*PI*4)*cos(X/30)*cos(Y/30)) * (1 + 0.3*sin(P*PI*10)*cos(X/15)*cos(Y/15))";
                    break;
                case "fake-glass-shatter":
                    expression = "B*(exp(-((X-W/2)^2+(Y-H/2)^2)/(500+100*sin(P*PI*10))) * sin(P*PI)^2) + A*(1 - sin(P*PI)^2)";
                    break;
            }

            if (!expression.isEmpty()) {
                tags.storeTag(mergedClip, outputLabel, fromTag.index);
                return fromTag.tag + toTag.tag +
                        "xfade=transition=custom:duration=" + transition.duration + ":offset=" +
                        transitionOffset +
                        ":expr='" + expression + "'"
                        + outputLabel + ";\n";
            }
        } else if (transition.effect != null && transition.effect.style != null) {
            tags.storeTag(mergedClip, outputLabel, fromTag.index);
            return fromTag.tag + toTag.tag +
                    "xfade=transition=" + transition.effect.style + ":duration=" + transition.duration + ":offset=" +
                    transitionOffset
                    + outputLabel + ";\n";
        }
        return "";
    }

    public static class FXRegistry {
        public static final Map<String, String> effectsFXMap = Collections.unmodifiableMap(new HashMap<String, String>() {{
            put("glitch-pulse", "Glitch Pulse");
            put("warp-zoom", "Warp Zoom");
            put("lens-flare-surge", "Lens Flare Surge");
            put("spin-burst", "Spinning Burst");
        }});

        public static final Map<String, String> transitionFXMap = Collections.unmodifiableMap(new HashMap<String, String>() {{
            put("custom_expose", "Expose [Custom]");
            put("custom_two-stage-slide", "Two Stage Slide [Custom]");
            put("custom_radial-shockwave", "Radial Shockwave [Custom]");
            put("custom_massive-effect", "Massive Effect [Custom]");
            put("custom_fake-glass-shatter", "Fake Glass Shatter [Custom]");

            put("fade", "Cross Fade");
            put("dissolve", "Dissolve");
            put("pixelize", "Pixelize");
            put("hblur", "Horizontal Blur");
            put("fadegrays", "Fade Gray");
            put("fadeblack", "Fade Black");
            put("fadewhite", "Fade White");
            put("rectcrop", "Rect Crop");
            put("circlecrop", "Circle Crop");
            put("wipeleft", "Wipe Left");
            put("wiperight", "Wipe Right");
            put("slidedown", "Slide Down");
            put("slideup", "Slide Up");
            put("slideleft", "Slide Left");
            put("slideright", "Slide Right");
            put("distance", "Distance");
            put("diagtl", "Diagonal Top-Left Wipe");
            put("diagbl", "Diagonal Bottom-Left Wipe");
            put("revealup", "Reveal Up");
        }});
    }
}
