package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.TemplateData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Checks for the "Use template" slot rules (TemplateUseModel), and for sharing one source file between the clips of a
 * group in TemplateRenderer: {@code java com.vanvatcorporation.doubleclips.TemplateUseSelfTest}.
 */
public class TemplateUseSelfTest {
    private static int failures = 0;

    private static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : "  " + detail));
        if (!ok) failures++;
    }

    private static boolean near(double a, double b) { return Math.abs(a - b) < 1e-6; }

    private static Clip clip(String name, float start, float dur, float sourceTrim) {
        Clip c = new Clip(name, start, dur, 0, ClipType.VIDEO, false, 0, 0);
        c.startClipTrim = sourceTrim;
        c.width = 1080;
        c.height = 1920;
        return c;
    }

    private static Track track(int index, Clip... clips) {
        Track t = new Track();
        t.timelineIndex = index;
        for (Clip c : clips) t.clips.add(c);
        return t;
    }

    /** 6 slots in time order: v.mp4 x3 (g1), p.png x2 (g2), x.mp4 alone. */
    private static TemplateTimelineInfo groupedInfo() {
        Timeline tl = new Timeline();
        tl.tracks.add(track(0,
                clip("v.mp4", 0, 2, 0), clip("p.png", 2, 2, 0), clip("v.mp4", 4, 2, 3),
                clip("x.mp4", 6, 2, 0), clip("p.png", 8, 1, 0), clip("v.mp4", 10, 2, 6)));
        TemplateGrouping.assign(tl);
        return TemplateTimelineInfo.make(tl);
    }

    private static TemplateTimelineInfo plainInfo() {
        Timeline tl = new Timeline();
        tl.tracks.add(track(0, clip("a.mp4", 0, 2, 0), clip("b.mp4", 2, 3, 0)));
        TemplateGrouping.assign(tl);
        return TemplateTimelineInfo.make(tl);
    }

    /** Videos are 20 s long unless the name says "short" (5 s); png / jpg are images; "bad" can't be read. */
    private static final TemplateUseModel.MediaReader READER = f -> {
        String n = f.getName().toLowerCase();
        if (n.startsWith("bad")) throw new IOException("unreadable");
        if (n.endsWith(".png") || n.endsWith(".jpg")) return new TemplateUseModel.Media(false, 0, "PNG image");
        return new TemplateUseModel.Media(true, n.contains("short") ? 5 : 20, "MPEG-4 movie");
    };

    private static List<File> files(String... names) {
        List<File> out = new ArrayList<>();
        for (String n : names) out.add(new File("/media/" + n));
        return out;
    }

    public static void main(String[] args) throws Exception {
        modelChecks();
        rendererSharingChecks();
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void modelChecks() {
        List<String> log = new ArrayList<>();
        TemplateUseModel m = new TemplateUseModel(3, log::add);
        check("starts with the count it was given, all empty, second stage", m.slots.size() == 3 && m.missing().equals(Arrays.asList(1, 2, 3)) && m.stage == TemplateUseModel.Stage.CLIPS, "");

        m.configure(groupedInfo());
        check("configure: one slot per replaceable clip with its length and group",
                m.slots.size() == 6 && near(m.slots.get(0).targetDuration, 2) && "g1".equals(m.slots.get(0).group)
                        && "g2".equals(m.slots.get(1).group) && m.slots.get(3).group == null && near(m.slots.get(5).sourceTrim, 6), "");
        check("configure: a grouped template starts on the groups stage", m.stage == TemplateUseModel.Stage.GROUPS && m.hasGroups(), "");

        List<TemplateUseModel.Entry> entries = m.entries();
        check("entries: three tiles in order of first appearance", entries.size() == 3
                && entries.get(0).members.equals(Arrays.asList(0, 2, 5)) && entries.get(1).members.equals(Arrays.asList(1, 4))
                && entries.get(2).members.equals(Arrays.asList(3)), entries.toString());
        check("entries: group dot colours count the groups, a clip alone has none",
                entries.get(0).colorIndex == 0 && entries.get(1).colorIndex == 1 && entries.get(2).colorIndex == null, "");
        check("colorIndexOfSlot follows the same numbering", m.colorIndexOfSlot(4) == 1 && m.colorIndexOfSlot(3) == null && m.colorIndexOfSlot(99) == null, "");

        // ---- first stage: one media for the whole group
        m.fillEntries(0, files("big.mp4"), READER);
        TemplateUseModel.Slot s0 = m.slots.get(0), s2 = m.slots.get(2), s5 = m.slots.get(5);
        check("group fill: every clip of the group gets the media (same path)", s0.isFilled() && s2.path.equals(s0.path) && s5.path.equals(s0.path), "");
        check("group fill: each clip starts where the template's own clip starts in its source (pieces stay different)",
                near(s0.startTrim, 0) && near(s2.startTrim, 3) && near(s5.startTrim, 6), s0.startTrim + "," + s2.startTrim + "," + s5.startTrim);
        check("group fill: each runs as long as the template keeps its clip", near(s0.endTrim, 2) && near(s2.endTrim, 5) && near(s5.endTrim, 8), "");
        check("group fill: no clip of it is detached", !s0.detached && !s2.detached && !s5.detached, "");
        check("groups are not complete until every tile has media", !m.groupsFilled() && m.missing().equals(Arrays.asList(2, 4, 5)), m.missing().toString());

        // a short video: the source start is pulled back so the clip still fits
        m.fillEntries(1, files("pic.png", "short.mp4"), READER);   // tile 1 = the photo group, tile 2 = the single clip
        check("photo group: images fill both clips", m.slots.get(1).kind == TemplateUseModel.Kind.IMAGE && m.slots.get(4).kind == TemplateUseModel.Kind.IMAGE, "");
        TemplateUseModel.Slot single = m.slots.get(3);
        check("a clip on its own starts at 0 and runs its length (a 5 s video, 2 s wanted)", single.kind == TemplateUseModel.Kind.VIDEO && near(single.startTrim, 0) && near(single.endTrim, 2), single.startTrim + "-" + single.endTrim);
        check("all tiles filled: first stage complete", m.groupsFilled() && m.missing().isEmpty(), "");

        // ---- second stage: one clip with its own media -> detached
        m.fill(2, files("own.mp4"), READER);
        check("second-stage fill: the clip leaves its group (detached) and takes the media", m.slots.get(2).detached && m.slots.get(2).path.endsWith("own.mp4"), "");
        check("second-stage fill: a grouped clip still starts at its template position when long enough", near(m.slots.get(2).startTrim, 3) && near(m.slots.get(2).endTrim, 5), "");
        m.fillEntries(0, files("again.mp4"), READER);
        check("filling the group again skips the detached clip", m.slots.get(0).path.endsWith("again.mp4") && m.slots.get(5).path.endsWith("again.mp4") && m.slots.get(2).path.endsWith("own.mp4"), "");
        m.slots.get(0).detached = true; m.slots.get(5).detached = true;
        m.fillEntries(0, files("third.mp4"), READER);
        check("when every clip of a group has its own media, filling the group takes them all back",
                m.slots.get(0).path.endsWith("third.mp4") && m.slots.get(2).path.endsWith("third.mp4") && m.slots.get(5).path.endsWith("third.mp4")
                        && !m.slots.get(0).detached && !m.slots.get(2).detached, "");

        // ---- clearing
        m.fill(2, files("own2.mp4"), READER);
        m.clearEntry(m.entries().get(0));
        check("clearing a group tile clears the clips the group sets, not the detached one",
                !m.slots.get(0).isFilled() && !m.slots.get(5).isFilled() && m.slots.get(2).isFilled(), "");
        check("a cleared clip keeps what the template says about it", "g1".equals(m.slots.get(0).group) && near(m.slots.get(0).targetDuration, 2) && near(m.slots.get(5).sourceTrim, 6), "");
        m.clear(2);
        check("clear(i) empties one clip", !m.slots.get(2).isFilled() && "g1".equals(m.slots.get(2).group), "");
        m.clearEntry(m.entries().get(2));
        check("clearing a tile of a clip on its own empties it", !m.slots.get(3).isFilled(), "");

        // ---- trims, warnings
        m.fill(3, files("long.mp4"), READER);
        m.setTrim(3, 4, 9);
        check("setTrim changes a video's trim", near(m.slots.get(3).startTrim, 4) && near(m.slots.get(3).endTrim, 9) && near(m.slots.get(3).trimmedLength(), 5), "");
        check("a video that fills the slot has no warning, a shorter one does",
                !m.slots.get(3).endsEarly() && endsEarlyAfter(m, 3, 4, 1), "");
        m.fill(1, files("p2.png"), READER);
        m.setTrim(1, 1, 2);
        check("setTrim is ignored for an image", near(m.slots.get(1).startTrim, 0) && near(m.slots.get(1).endTrim, 0), "");

        // ---- errors and extras
        TemplateUseModel e = new TemplateUseModel(0, log::add);
        e.configure(plainInfo());
        check("a template without groups goes straight to the clips stage", e.stage == TemplateUseModel.Stage.CLIPS && !e.hasGroups() && e.entries().size() == 2, "");
        log.clear();
        e.fill(0, files("bad1.mp4", "ok.mp4", "ok2.mp4", "ok3.mp4"), READER);
        check("an unreadable file is reported and does not use up a slot", e.slots.get(0).path.endsWith("ok.mp4") && e.slots.get(1).path.endsWith("ok2.mp4")
                && log.stream().anyMatch(l -> l.startsWith("Clip 1: couldn't load that item (unreadable)")), log.toString());
        check("extra files are discarded and said so", log.stream().anyMatch(l -> l.equals("Discarded 1 extra item(s): no more slots.")), log.toString());
        log.clear();
        e.fillEntries(9, files("a.mp4"), READER);
        check("a start beyond the last tile does nothing", log.isEmpty(), log.toString());
        check("picks: one per slot in order with the trims", e.picks().size() == 2 && e.picks().get(0).isVideo && e.picks().get(0).path.endsWith("ok.mp4")
                && near(e.picks().get(1).startTrim, 0), "");

        // ---- reconfigure keeps media when the count is the same
        TemplateUseModel k = new TemplateUseModel(2, log::add);
        k.configure(plainInfo());
        k.fill(0, files("keep.mp4"), READER);
        k.configure(plainInfo());
        check("configure again keeps chosen media when the count did not change", k.slots.get(0).path.endsWith("keep.mp4") && near(k.slots.get(1).targetDuration, 3), "");
    }

    private static boolean endsEarlyAfter(TemplateUseModel m, int index, double start, double length) {
        TemplateUseModel.Slot s = m.slots.get(index);
        double os = s.startTrim, oe = s.endTrim;
        s.startTrim = start; s.endTrim = start + length;
        boolean r = s.endsEarly();
        s.startTrim = os; s.endTrim = oe;
        return r;
    }

    // ======================================================================================== renderer

    private static void rendererSharingChecks() throws Exception {
        File root = Files.createTempDirectory("share").toFile();
        File in = Files.createTempDirectory("in").toFile();
        File shared = new File(in, "big.mp4"); Files.write(shared.toPath(), new byte[]{1, 2, 3, 4});
        File other = new File(in, "other.mp4"); Files.write(other.toPath(), new byte[]{9});

        TemplateTimelineInfo info = groupedInfo();   // 6 slots
        List<TemplateRenderer.SlotPick> picks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            boolean image = i == 1 || i == 4;
            File f = i == 3 ? other : shared;
            // the two image slots point at the same (video-named) file here: only the sharing matters
            picks.add(new TemplateRenderer.SlotPick(f.getPath(), true, i, i + 1.5));
        }
        AtomicInteger probes = new AtomicInteger();
        TemplateRenderer.Probe probe = f -> { probes.incrementAndGet(); return new TemplateRenderer.MediaFacts(1920, 1080, 30, true); };
        TemplateData t = new TemplateData();
        VideoSettings settings = new VideoSettings(1080, 1920, 30, 18, Integer.MAX_VALUE, "medium", "zerolatency");
        TemplateRenderer.Prepared p = TemplateRenderer.prepare(info, t, picks, settings, (n, c, f, l) -> { }, probe, root, s -> { });

        String[] made = new File(p.directory, "Clips").list();
        check("renderer: clips that point at one file get one file in Clips/ (plus one for the other)", made != null && made.length == 2, Arrays.toString(made));
        check("renderer: the shared file is probed once", probes.get() == 2, "probes " + probes.get());
        String n0 = info.slots.get(0).clip.getClipName();
        check("renderer: every clip of the group points at the same file name",
                n0.equals(info.slots.get(1).clip.getClipName()) && n0.equals(info.slots.get(5).clip.getClipName()) && !n0.equals(info.slots.get(3).clip.getClipName()), n0);
        check("renderer: the shared file's bytes are intact", Files.readAllBytes(new File(p.directory, "Clips/" + n0).toPath()).length == 4, "");
        check("renderer: each clip keeps its OWN trim of the shared file",
                near(info.slots.get(0).clip.startClipTrim, 0) && near(info.slots.get(5).clip.startClipTrim, 5) && near(info.slots.get(5).clip.duration, 1.5), info.slots.get(5).clip.startClipTrim + " " + info.slots.get(5).clip.duration);
        TemplateRenderer.cleanup(p.directory);
        check("renderer: cleanup removes the folder and leaves the user's files", !p.directory.exists() && shared.isFile() && shared.length() == 4, "");
    }
}
