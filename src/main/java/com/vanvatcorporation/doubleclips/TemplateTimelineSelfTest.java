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

        System.exit(failures == 0 ? 0 : 1);
    }
}
