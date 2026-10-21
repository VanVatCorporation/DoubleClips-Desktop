package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

import java.util.List;

/**
 * Self-check for the template-as-timeline model (roles, slot numbering, legacy strip, strip arithmetic) with real
 * Clip / Timeline objects. Run: {@code java -cp <classes> com.vanvatcorporation.doubleclips.TemplateTimelineSelfTest}.
 * Exits with 1 when a check fails. (The JSON parsing in TemplateTimelineJson needs Gson and is exercised by the app.)
 */
public final class TemplateTimelineSelfTest {

    private static int failures = 0;

    private static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + (detail.isEmpty() ? "" : "  " + detail));
        if (!ok) failures++;
    }

    private static Clip clip(String name, float start, float dur, ClipType type, boolean locked) {
        Clip c = new Clip(name, start, dur, 0, type, false, 0, 0);
        c.isLockedForTemplate = locked;
        return c;
    }

    private static Track track(int index, Clip... clips) {
        Track t = new Track();
        t.timelineIndex = index;
        for (Clip c : clips) t.clips.add(c);
        return t;
    }

    public static void main(String[] args) {
        // roles
        check("video unlocked = replaceable", TemplateTimelineInfo.roleOf(clip("a", 0, 1, ClipType.VIDEO, false)) == TemplateTimelineInfo.Role.REPLACEABLE, "");
        check("image unlocked = replaceable", TemplateTimelineInfo.roleOf(clip("a", 0, 1, ClipType.IMAGE, false)) == TemplateTimelineInfo.Role.REPLACEABLE, "");
        check("video locked = locked", TemplateTimelineInfo.roleOf(clip("a", 0, 1, ClipType.VIDEO, true)) == TemplateTimelineInfo.Role.LOCKED, "");
        check("audio / text / effect", TemplateTimelineInfo.roleOf(clip("a", 0, 1, ClipType.AUDIO, false)) == TemplateTimelineInfo.Role.AUDIO
                && TemplateTimelineInfo.roleOf(clip("a", 0, 1, ClipType.TEXT, false)) == TemplateTimelineInfo.Role.TEXT
                && TemplateTimelineInfo.roleOf(clip("a", 0, 1, ClipType.EFFECT, false)) == TemplateTimelineInfo.Role.EFFECT, "");
        check("transition / 3D = other", TemplateTimelineInfo.roleOf(clip("a", 0, 1, ClipType.TRANSITION, false)) == TemplateTimelineInfo.Role.OTHER
                && TemplateTimelineInfo.roleOf(clip("a", 0, 1, ClipType.SCENE_3D, false)) == TemplateTimelineInfo.Role.OTHER, "");

        // numbering: time order, ties by top row; empty tracks are skipped; locked clips get no number
        Clip a = clip("A", 4, 3, ClipType.VIDEO, false);        // row 0, 2nd in time
        Clip text = clip("T", 1, 2, ClipType.TEXT, false);      // row 0
        Clip b = clip("B", 0, 4, ClipType.VIDEO, false);        // row 1, starts first
        Clip c = clip("C", 8, 2, ClipType.VIDEO, true);         // row 1, locked
        Clip tie = clip("D", 4, 2, ClipType.IMAGE, false);      // row 2, same start as A: A (row 0) first
        Clip aud = clip("M", 0, 12, ClipType.AUDIO, false);     // row 3
        Timeline tl = new Timeline();
        tl.tracks.add(track(0, a, text));
        tl.tracks.add(track(1, b, c));
        tl.tracks.add(track(5));                                // empty: not a row
        tl.tracks.add(track(2, tie));
        tl.tracks.add(track(3, aud));
        tl.duration = 11f;
        TemplateTimelineInfo info = TemplateTimelineInfo.make(tl);
        check("empty track skipped", info.rowCount == 4, "rows " + info.rowCount);
        check("three slots", info.slots.size() == 3, "slots " + info.slots.size());
        check("numbered in time order", info.slots.get(0).clip == b && info.slots.get(1).clip == a && info.slots.get(2).clip == tie,
                "order " + info.slots.get(0).clip.getClipName() + info.slots.get(1).clip.getClipName() + info.slots.get(2).clip.getClipName());
        check("slot numbers 1,2,3", info.slots.get(0).number == 1 && info.slots.get(1).number == 2 && info.slots.get(2).number == 3, "");
        check("image slot flagged", info.slots.get(2).isImage && !info.slots.get(0).isImage, "");
        int locked = 0, nums = 0;
        for (TemplateTimelineInfo.StripClip s : info.clips) { if (s.role == TemplateTimelineInfo.Role.LOCKED) { locked++; if (s.slotNumber != 0) nums++; } }
        check("locked clip has no number", locked == 1 && nums == 0, "");
        check("duration = latest clip end", Math.abs(info.duration - 12.0) < 1e-6, "duration " + info.duration);
        List<TemplateTimelineInfo.Role> roles = info.roles();
        check("legend roles in fixed order", roles.size() == 4 && roles.get(0) == TemplateTimelineInfo.Role.REPLACEABLE
                && roles.get(1) == TemplateTimelineInfo.Role.LOCKED && roles.get(2) == TemplateTimelineInfo.Role.AUDIO
                && roles.get(3) == TemplateTimelineInfo.Role.TEXT, roles.toString());

        // legacy strip
        TemplateTimelineInfo legacy = TemplateTimelineInfo.legacy(3, 0, 12);
        check("legacy: 3 equal slots over the video length", legacy.slots.size() == 3 && Math.abs(legacy.slots.get(1).start - 4) < 1e-9
                && Math.abs(legacy.duration - 12) < 1e-9, "");
        check("legacy: stored duration wins", Math.abs(TemplateTimelineInfo.legacy(2, 10, 99).duration - 10) < 1e-9, "");
        check("legacy: nothing to draw", TemplateTimelineInfo.legacy(0, 0, 0).slots.isEmpty() && TemplateTimelineInfo.legacy(3, 0, 0).slots.isEmpty(), "");

        // strip arithmetic
        check("clock 65.9 -> 1:05", TemplateStripMath.clock(65.9).equals("1:05") && TemplateStripMath.clock(-3).equals("0:00"), TemplateStripMath.clock(65.9));
        check("scrub: drag left moves time forward, clamped", Math.abs(TemplateStripMath.scrubTime(10, -50, 25, 30) - 12) < 1e-9
                && TemplateStripMath.scrubTime(1, 500, 25, 30) == 0 && TemplateStripMath.scrubTime(29, -500, 25, 30) == 30, "");
        check("row height clamps", TemplateStripMath.rowHeight(100, 3, 4, 12, 28) == 28f && TemplateStripMath.rowHeight(40, 6, 4, 12, 28) == 12f, "");
        check("clip left: current time under the middle", Math.abs(TemplateStripMath.clipLeft(400, 5, 5, 20) - 200) < 1e-4, "");

        // template data: what counts as a timeline address / an ffmpeg command
        check("link: null, blank, \"null\", relative path = none",
                com.vanvatcorporation.doubleclips.data.TemplateData.cleanLink(null).isEmpty()
                        && com.vanvatcorporation.doubleclips.data.TemplateData.cleanLink("  ").isEmpty()
                        && com.vanvatcorporation.doubleclips.data.TemplateData.cleanLink("null").isEmpty()
                        && com.vanvatcorporation.doubleclips.data.TemplateData.cleanLink("/a/timeline.json").isEmpty(), "");
        check("link: http(s) address kept, trimmed",
                com.vanvatcorporation.doubleclips.data.TemplateData.cleanLink(" https://x.y/t.json ").equals("https://x.y/t.json"), "");
        check("command: blank / null / html / json answers are not commands",
                !com.vanvatcorporation.doubleclips.data.TemplateData.looksLikeCommand("") && !com.vanvatcorporation.doubleclips.data.TemplateData.looksLikeCommand("null")
                        && !com.vanvatcorporation.doubleclips.data.TemplateData.looksLikeCommand("<html>404</html>")
                        && !com.vanvatcorporation.doubleclips.data.TemplateData.looksLikeCommand("{\"error\":1}"), "");
        check("command: a real ffmpeg command is",
                com.vanvatcorporation.doubleclips.data.TemplateData.looksLikeCommand("-y -i \"in.mp4\" out.mp4"), "");
        com.vanvatcorporation.doubleclips.data.TemplateData td = new com.vanvatcorporation.doubleclips.data.TemplateData();
        check("unchecked, no command in the list: FFmpeg not available, no timeline", !td.isFfmpegAvailable() && !td.hasTimeline(), "");
        td.setFfmpegCommand("-i x y"); td.setFfmpegVerified(false);
        check("a failed check wins over a command in the data", !td.isFfmpegAvailable(), "");
        td.setFfmpegVerified(true);
        check("a passed check enables FFmpeg", td.isFfmpegAvailable() && td.hasVerifiedFfmpegCommand(), "");


        // ---- template groups (iOS TemplateGrouping): clips sharing ONE source get g1, g2 ... in slot order
        Clip g1a = clip("same.mp4", 6, 2, ClipType.VIDEO, false);      // 3rd in time
        Clip g1b = clip("same.mp4", 0, 2, ClipType.VIDEO, false);      // 1st in time -> group g1
        Clip solo = clip("alone.mp4", 2, 2, ClipType.VIDEO, false);
        Clip g2a = clip("pic.png", 3, 1, ClipType.IMAGE, false);       // a photo used twice -> g2
        Clip g2b = clip("pic.png", 5, 1, ClipType.IMAGE, false);
        Clip sameNameVideo = clip("pic.png", 4, 1, ClipType.VIDEO, false); // a VIDEO named like the photo: another source
        Clip lockedSame = clip("same.mp4", 7, 1, ClipType.VIDEO, true);    // locked: never counted, never grouped
        Clip stale = clip("one.mp4", 8, 1, ClipType.VIDEO, false);
        stale.templateGroup = "old";                                    // a stale id on a clip that is alone now
        Timeline gt = new Timeline();
        gt.tracks.add(track(0, g1a, g2a, g2b, lockedSame));
        gt.tracks.add(track(1, g1b, solo, sameNameVideo, stale));
        int made = TemplateGrouping.assign(gt);
        check("groups: two ids made", made == 2, "made " + made);
        check("groups: first appearance in slot order gets g1", "g1".equals(g1b.templateGroup) && "g1".equals(g1a.templateGroup), g1b.templateGroup + "," + g1a.templateGroup);
        check("groups: a photo used twice is g2", "g2".equals(g2a.templateGroup) && "g2".equals(g2b.templateGroup), g2a.templateGroup + "," + g2b.templateGroup);
        check("groups: a source used once stays alone", solo.templateGroup == null && sameNameVideo.templateGroup == null, "");
        check("groups: a stale id is cleared", stale.templateGroup == null, String.valueOf(stale.templateGroup));
        check("groups: a locked clip is not grouped", lockedSame.templateGroup == null, "");

        TemplateTimelineInfo gi = TemplateTimelineInfo.make(gt);
        TemplateTimelineInfo.Slot slotOfG1b = null;
        for (TemplateTimelineInfo.Slot sl : gi.slots) if (sl.clip == g1b) slotOfG1b = sl;
        g1b.startClipTrim = 2.5f;
        TemplateTimelineInfo gi2 = TemplateTimelineInfo.make(gt);
        TemplateTimelineInfo.Slot trimmed = null;
        for (TemplateTimelineInfo.Slot sl : gi2.slots) if (sl.clip == g1b) trimmed = sl;
        check("slot carries the group of its clip", slotOfG1b != null && "g1".equals(slotOfG1b.group), slotOfG1b == null ? "none" : slotOfG1b.group);
        check("slot carries sourceTrim = the clip's own start inside its source", trimmed != null && trimmed.sourceTrim == 2.5, trimmed == null ? "none" : String.valueOf(trimmed.sourceTrim));
        check("a standing-alone slot has no group and the clip's own trim", gi.slots.stream().anyMatch(sl -> sl.clip == solo && sl.group == null && sl.sourceTrim == 0), "");
        check("numbering is unchanged by grouping (time order)", gi.slots.get(0).clip == g1b && gi.slots.get(1).clip == solo, "");
        check("legacy slots have no group", TemplateTimelineInfo.legacy(3, 6, 0).slots.stream().allMatch(sl -> sl.group == null && sl.sourceTrim == 0), "");
        check("copy keeps templateGroup", new Clip(g2a).templateGroup.equals("g2"), "");
        System.exit(failures == 0 ? 0 : 1);
    }
}
