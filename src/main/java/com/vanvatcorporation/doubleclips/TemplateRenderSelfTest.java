package com.vanvatcorporation.doubleclips;

import com.sun.net.httpserver.HttpServer;
import com.vanvatcorporation.doubleclips.data.TemplateData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.EasingType;
import com.vanvatcorporation.doubleclips.data.editing.Keyframe;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;
import com.vanvatcorporation.doubleclips.data.editing.VideoProperties;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Checks for "Use template" through OpenGL, no JavaFX / ffmpeg / network of the app needed:
 * {@code java com.vanvatcorporation.doubleclips.TemplateRenderSelfTest} (exit code 1 when a check fails).
 * Covers TemplateRetarget (cover + centre, keyframes, trims, speed), TemplateResources (names, cache, download) and the
 * TemplateRenderer.prepare core (with a fake probe and fetcher).
 */
public class TemplateRenderSelfTest {
    private static int failures = 0;

    private static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : "  " + detail));
        if (!ok) failures++;
    }

    private static boolean near(double a, double b, double eps) { return Math.abs(a - b) <= eps; }

    private static Clip clip(String name, float start, float dur, ClipType type, boolean locked, int w, int h) {
        Clip c = new Clip(name, start, dur, 0, type, false, 0, 0);
        c.isLockedForTemplate = locked;
        c.width = w;
        c.height = h;
        return c;
    }

    private static Track track(int index, Clip... clips) {
        Track t = new Track();
        t.timelineIndex = index;
        for (Clip c : clips) t.clips.add(c);
        return t;
    }

    private static VideoProperties look(float posX, float posY, float rotDeg, float sx, float sy, float pvx, float pvy) {
        VideoProperties p = new VideoProperties();
        p.valuePosX = posX; p.valuePosY = posY; p.valueRot = rotDeg;
        p.valueScaleX = sx; p.valueScaleY = sy; p.valuePivotX = pvx; p.valuePivotY = pvy;
        return p;
    }

    private static double[] centreOf(Clip c, VideoProperties p) {
        return TemplateRetarget.centre(TemplateRetarget.quad(c, p, 1920, 1080, false));
    }

    private static void set(Object target, String field, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static TemplateData template(String id, long timestamp, String contentLink, String... extra) throws Exception {
        TemplateData t = new TemplateData();
        set(t, "templateId", id);
        set(t, "templateTimestamp", timestamp);
        set(t, "templateContentLink", contentLink);
        set(t, "templateTitle", "My template");
        set(t, "additionalResourceName", extra);
        return t;
    }

    public static void main(String[] args) throws Exception {
        retargetChecks();
        trimChecks();
        resourceChecks();
        prepareChecks();
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ======================================================================================== retarget

    private static void retargetChecks() {
        // ---- cover: a portrait box (1080x1920) filled with a landscape video (1920x1080)
        Clip a = clip("a.mp4", 0, 4, ClipType.VIDEO, false, 1080, 1920);
        a.videoProperties = look(100, -50, 0, 1, 1, 0, 0);
        double[] before = centreOf(a, a.videoProperties);
        TemplateRetarget.retarget(a, 1920, 1080, 1080, 1920, false);
        check("cover: landscape media in a portrait box is scaled up to cover it", a.width == 3413 && a.height == 1920, a.width + "x" + a.height);
        double[] after = centreOf(a, a.videoProperties);
        check("cover: the box's centre stays where it was", near(before[0], after[0], 0.01) && near(before[1], after[1], 0.01),
                before[0] + "," + before[1] + " -> " + after[0] + "," + after[1]);

        // ---- a rotated, scaled box with its pivot off-centre
        Clip b = clip("b.mp4", 0, 4, ClipType.VIDEO, false, 800, 600);
        b.videoProperties = look(240, 90, 30, 1.5f, 0.8f, 0.3f, 0.7f);
        double[] b0 = centreOf(b, b.videoProperties);
        TemplateRetarget.retarget(b, 1000, 1000, 1920, 1080, false);
        double[] b1 = centreOf(b, b.videoProperties);
        check("cover: centre kept with rotation, scale and an off-centre pivot",
                b.width == 800 && b.height == 800 && near(b0[0], b1[0], 0.01) && near(b0[1], b1[1], 0.01), b.width + "x" + b.height + " " + b1[0] + "," + b1[1]);

        // ---- same shape: nothing moves
        Clip same = clip("s.mp4", 0, 4, ClipType.VIDEO, false, 1920, 1080);
        same.videoProperties = look(10, 20, 0, 1, 1, 0, 0);
        TemplateRetarget.retarget(same, 3840, 2160, 1920, 1080, false);
        check("same aspect ratio: the size follows, the position does not move",
                same.width == 1920 && same.height == 1080 && same.videoProperties.valuePosX == 10 && same.videoProperties.valuePosY == 20, same.width + "x" + same.height);

        // ---- keyframes holding everything
        Clip k = clip("k.mp4", 1, 4, ClipType.VIDEO, false, 1000, 500);
        k.videoProperties = look(0, 0, 0, 1, 1, 0.5f, 0.5f);
        VideoProperties v0 = look(50, 20, 0, 1, 1, 0.5f, 0.5f);
        VideoProperties v1 = look(300, 120, 45, 2, 1.5f, 0.2f, 0.9f);
        k.keyframes.keyframes.add(new Keyframe(0f, v0, EasingType.LINEAR));
        k.keyframes.keyframes.add(new Keyframe(2f, v1, EasingType.LINEAR));
        double[][] kb = new double[2][];
        for (int i = 0; i < 2; i++) kb[i] = centreOf(k, k.keyframes.resolve(k, k.keyframes.keyframes.get(i).getGlobalTime(k)));
        TemplateRetarget.retarget(k, 500, 1000, 1920, 1080, false);
        boolean keyOk = true;
        String keyDetail = "";
        for (int i = 0; i < 2; i++) {
            double[] now = centreOf(k, k.keyframes.resolve(k, k.keyframes.keyframes.get(i).getGlobalTime(k)));
            if (!near(kb[i][0], now[0], 0.02) || !near(kb[i][1], now[1], 0.02)) { keyOk = false; keyDetail += " key" + i + ":" + now[0] + "," + now[1] + " want " + kb[i][0] + "," + kb[i][1]; }
        }
        check("keyframes: the centre is kept at every keyframe's moment", keyOk && k.width == 1000 && k.height == 2000, k.width + "x" + k.height + keyDetail);

        // ---- per-property keyframes: Pos keyed, scale and rotation static
        Clip pk = clip("pk.mp4", 0, 4, ClipType.VIDEO, false, 600, 600);
        pk.videoProperties = look(0, 0, 20, 1.3f, 1.3f, 0.4f, 0.6f);
        Keyframe p0 = new Keyframe(0f, look(10, 10, 20, 1.3f, 1.3f, 0.4f, 0.6f), EasingType.LINEAR);
        Keyframe p1 = new Keyframe(2f, look(400, 200, 20, 1.3f, 1.3f, 0.4f, 0.6f), EasingType.LINEAR);
        for (Keyframe kf : new Keyframe[]{p0, p1}) {
            kf.channels = new ArrayList<>();
            kf.setHolds(VideoProperties.ValueType.PosX, true);
            kf.setHolds(VideoProperties.ValueType.PosY, true);
            pk.keyframes.keyframes.add(kf);
        }
        Keyframe scaleOnly = new Keyframe(3f, look(777, 888, 20, 2f, 2f, 0.4f, 0.6f), EasingType.LINEAR);
        scaleOnly.channels = new ArrayList<>();
        scaleOnly.setHolds(VideoProperties.ValueType.ScaleX, true);
        pk.keyframes.keyframes.add(scaleOnly);
        double[] at1 = centreOf(pk, pk.keyframes.resolve(pk, 1f));
        double[] at0 = centreOf(pk, pk.keyframes.resolve(pk, 0f));
        TemplateRetarget.retarget(pk, 1200, 600, 1920, 1080, false);
        double[] at1b = centreOf(pk, pk.keyframes.resolve(pk, 1f));
        double[] at0b = centreOf(pk, pk.keyframes.resolve(pk, 0f));
        check("per-property keyframes: the centre is kept in between keys too",
                near(at1[0], at1b[0], 0.05) && near(at1[1], at1b[1], 0.05) && near(at0[0], at0b[0], 0.05) && near(at0[1], at0b[1], 0.05),
                at1b[0] + "," + at1b[1] + " want " + at1[0] + "," + at1[1]);
        check("per-property keyframes: a key that does not hold Pos keeps its own Pos values",
                scaleOnly.value.valuePosX == 777 && scaleOnly.value.valuePosY == 888, scaleOnly.value.valuePosX + "," + scaleOnly.value.valuePosY);

        // ---- stretch-to-fit and unknown sizes
        Clip st = clip("st.mp4", 0, 4, ClipType.VIDEO, false, 1080, 1920);
        st.videoProperties = look(33, 44, 0, 1, 1, 0, 0);
        TemplateRetarget.retarget(st, 1920, 1080, 1080, 1920, true);
        check("stretch-to-fit: only the size changes", st.width == 1920 && st.height == 1080 && st.videoProperties.valuePosX == 33 && st.videoProperties.valuePosY == 44, "");
        Clip unk = clip("u.mp4", 0, 4, ClipType.VIDEO, false, 640, 360);
        unk.videoProperties = look(5, 6, 0, 1, 1, 0, 0);
        TemplateRetarget.retarget(unk, 0, 0, 1920, 1080, false);
        check("media of unknown size: nothing changes", unk.width == 640 && unk.height == 360 && unk.videoProperties.valuePosX == 5, "");
        Clip noBox = clip("n.mp4", 0, 4, ClipType.VIDEO, false, 0, 0);
        noBox.videoProperties = look(5, 6, 0, 1, 1, 0, 0);
        TemplateRetarget.retarget(noBox, 1280, 720, 1920, 1080, false);
        check("a template clip without a stored size: the size is taken, nothing is shifted", noBox.width == 1280 && noBox.height == 720 && noBox.videoProperties.valuePosX == 5, "");

        // ---- the box itself: identity, and a 90 degree turn about the pivot
        double[] q = TemplateRetarget.quad(100, 50, look(10, 20, 0, 1, 1, 0, 0));
        check("box: with no scale / rotation the corners are the unscaled rectangle at PosX, PosY",
                near(q[0], 10, 1e-9) && near(q[1], 20, 1e-9) && near(q[2], 110, 1e-9) && near(q[5], 70, 1e-9), java.util.Arrays.toString(q));
        double[] r = TemplateRetarget.quad(100, 100, look(0, 0, 90, 1, 1, 0.5f, 0.5f));
        check("box: 90 degrees about the middle maps TL to TR", near(r[0], 100, 1e-6) && near(r[1], 0, 1e-6), java.util.Arrays.toString(r));
        Clip text = clip("t", 0, 1, ClipType.TEXT, false, 10, 10);
        check("box: only video / image clips have one", TemplateRetarget.quad(text, new VideoProperties(), 1920, 1080, false) == null, "");
    }

    // ======================================================================================== trims

    private static Clip slotClip(ClipType type, float dur) {
        return clip("old.mp4", 0, dur, type, false, 1920, 1080);
    }

    private static void trimChecks() {
        Clip c = slotClip(ClipType.VIDEO, 4);
        TemplateRetarget.fitVideo(c, 10, true, 4, 0, 0);
        check("video longer than the slot: capped at the slot, the rest trimmed off the end",
                near(c.duration, 4, 1e-4) && c.startClipTrim == 0 && near(c.endClipTrim, 6, 1e-4) && c.originalDuration == 10, c.duration + " end " + c.endClipTrim);
        check("video with sound: volume 1", c.isClipHasAudio && c.videoProperties.valueVolume == 1f, "");

        c = slotClip(ClipType.VIDEO, 4);
        TemplateRetarget.fitVideo(c, 10, false, 4, 3, 0);
        check("start trim kept, then capped at the slot", near(c.duration, 4, 1e-4) && c.startClipTrim == 3 && near(c.endClipTrim, 3, 1e-4), c.duration + " " + c.startClipTrim + " " + c.endClipTrim);
        check("video without sound: muted", !c.isClipHasAudio && c.videoProperties.valueVolume == 0f, "");

        c = slotClip(ClipType.VIDEO, 4);
        TemplateRetarget.fitVideo(c, 10, true, 4, 2, 5);
        check("both trims chosen (3 s wanted): plays 3 s", near(c.duration, 3, 1e-4) && c.startClipTrim == 2 && near(c.endClipTrim, 5, 1e-4), c.duration + " " + c.endClipTrim);

        c = slotClip(ClipType.VIDEO, 4);
        TemplateRetarget.fitVideo(c, 2, true, 4, 0, 0);
        check("video shorter than the slot simply ends early", near(c.duration, 2, 1e-4) && c.endClipTrim == 0, String.valueOf(c.duration));

        c = slotClip(ClipType.VIDEO, 4);
        TemplateRetarget.fitVideo(c, 10, true, 4, 99, 0);
        check("a start trim past the end is pulled back so something plays", c.startClipTrim <= 9.95f + 1e-4 && c.duration >= TemplateRetarget.MIN_LENGTH - 1e-6, c.startClipTrim + " " + c.duration);

        c = slotClip(ClipType.VIDEO, 4);
        c.videoProperties.valueSpeed = 2f;
        TemplateRetarget.fitVideo(c, 10, true, 4, 0, 0);
        check("speed 2: a 4 s slot holds 8 s of the source", near(c.duration, 4, 1e-3) && near(c.endClipTrim, 2, 0.02), c.duration + " end " + c.endClipTrim);

        c = slotClip(ClipType.VIDEO, 4);
        c.videoProperties.valueSpeed = 2f;
        TemplateRetarget.fitVideo(c, 3, true, 4, 0, 0);
        check("speed 2, a 3 s video: plays 1.5 s", near(c.duration, 1.5, 1e-3) && c.endClipTrim == 0, String.valueOf(c.duration));

        c = slotClip(ClipType.IMAGE, 2.5f);
        TemplateRetarget.fitImage(c, 2.5);
        check("a still lasts the slot, no sound, no trims",
                c.duration == 2.5f && c.originalDuration == 2.5f && c.startClipTrim == 0 && c.endClipTrim == 0 && !c.isClipHasAudio && c.videoProperties.valueVolume == 0f, "");
    }

    // ======================================================================================== resources

    private static void resourceChecks() throws Exception {
        TemplateData t = template("tpl-1", 111L, "http://x/", "a.png", "sub/b.mp3", " ../c.ttf", "..", "", "a.png");
        Clip lockedVideo = clip("lock.mp4", 0, 5, ClipType.VIDEO, true, 100, 100);
        Clip audio = clip("song.mp3", 0, 5, ClipType.AUDIO, false, 0, 0);
        Clip yours = clip("mine.mp4", 0, 5, ClipType.VIDEO, false, 100, 100);
        Clip text = clip("t", 0, 5, ClipType.TEXT, false, 0, 0);
        text.textStyle = new com.vanvatcorporation.doubleclips.data.editing.TextStyleData();
        text.textStyle.fontFile = "Fonts/Pacifico.ttf";
        Timeline tl = new Timeline();
        tl.tracks.add(track(0, lockedVideo, yours));
        tl.tracks.add(track(1, audio, text));
        List<String> names = TemplateResources.neededNames(t, tl);
        check("resources: extras first, then locked / audio / font names; sanitised, no repeats, the replaceable clip left out",
                names.equals(Arrays.asList("a.png", "b.mp3", "c.ttf", "lock.mp4", "song.mp3", "Pacifico.ttf")), names.toString());
        check("resources: fonts go to Fonts/, the rest to Clips/", TemplateResources.isFont("X.OTF") && !TemplateResources.isFont("a.png") && !TemplateResources.isFont("ttf"), "");
        check("resources: cache folder per template + timestamp, safe name",
                TemplateResources.cacheFolder(new File("/c"), template("a/b c", 5L, "")).getName().equals("a_b_c-5"), TemplateResources.cacheFolder(new File("/c"), template("a/b c", 5L, "")).getName());
        check("resources: names are encoded as one path segment", TemplateResources.encode("my file (1).png").equals("my%20file%20%281%29.png"), TemplateResources.encode("my file (1).png"));

        // ---- download against a real local server
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/content/", ex -> {
            hits.incrementAndGet();
            String p = ex.getRequestURI().getPath();
            byte[] body = p.endsWith("a.png") ? "PNGDATA".getBytes() : p.endsWith("f.ttf") ? "FONTDATA".getBytes() : null;
            if (body == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/content/";
            File root = Files.createTempDirectory("res").toFile();
            File cache = new File(root, "cache"), clips = new File(root, "Clips"), fonts = new File(root, "Fonts");
            clips.mkdirs(); fonts.mkdirs();
            TemplateData td = template("tpl-9", 1L, base);
            List<String> log = new ArrayList<>();
            TemplateResources.fetch(Arrays.asList("a.png", "f.ttf"), td, cache, clips, fonts, log::add);
            check("download: files land in Clips/ and Fonts/ with their content",
                    new String(Files.readAllBytes(new File(clips, "a.png").toPath())).equals("PNGDATA")
                            && new String(Files.readAllBytes(new File(fonts, "f.ttf").toPath())).equals("FONTDATA"), "");
            check("download: progress is logged", log.size() == 2 && log.get(0).startsWith("Downloading a.png (1/2)"), log.toString());
            int firstHits = hits.get();
            new File(clips, "a.png").delete();
            TemplateResources.fetch(Arrays.asList("a.png", "f.ttf"), td, cache, clips, fonts, s -> { });
            check("download: the second time comes from the cache (no request) and is copied again", hits.get() == firstHits && new File(clips, "a.png").isFile(), "hits " + hits.get());
            TemplateResources.fetch(Arrays.asList("a.png"), template("tpl-9", 2L, base), cache, clips, fonts, s -> { });
            check("download: a newer template version is fetched again", hits.get() == firstHits + 1, "hits " + hits.get());
            try {
                TemplateResources.fetch(Arrays.asList("a.png", "missing.bin", "gone.bin"), td, cache, clips, fonts, s -> { });
                check("download: a missing file fails", false, "");
            } catch (TemplateRenderer.RenderError e) {
                check("download: every failure is named together, with its status",
                        e.getMessage().equals("The template's files couldn't be downloaded: missing.bin (404), gone.bin (404)"), e.getMessage());
            }
            check("download: no half-written file is left in the cache", new File(cache, "tpl-9-1").list((d, n) -> n.endsWith(".part")).length == 0, "");
            try {
                TemplateResources.fetch(Arrays.asList("a.png"), template("tpl-x", 1L, ""), cache, clips, fonts, s -> { });
                check("download: no content link fails", false, "");
            } catch (TemplateRenderer.RenderError e) {
                check("download: no content link = the file is reported as not downloadable", e.getMessage().contains("a.png"), e.getMessage());
            }
        } finally {
            server.stop(0);
        }
    }

    // ======================================================================================== prepare

    private static String[] workFolders(File root) {
        String[] n = root.list((d, name) -> name.startsWith(TemplateRenderer.WORK_FOLDER_PREFIX));
        return n == null ? new String[0] : n;
    }

    private static TemplateTimelineInfo sampleInfo() {
        Clip slot1 = clip("a.mp4", 0, 4, ClipType.VIDEO, false, 1080, 1920);
        Clip slot2 = clip("p.png", 4, 2, ClipType.IMAGE, false, 500, 500);
        Clip locked = clip("lock.png", 0, 6, ClipType.IMAGE, true, 100, 100);
        Clip audio = clip("song.mp3", 0, 6, ClipType.AUDIO, false, 0, 0);
        Timeline tl = new Timeline();
        tl.tracks.add(track(0, slot1, locked));
        tl.tracks.add(track(1, slot2));
        tl.tracks.add(track(2, audio));
        return TemplateTimelineInfo.make(tl);
    }

    private static TemplateRenderer.Probe fakeProbe() {
        return f -> f.getName().endsWith(".mp4") ? new TemplateRenderer.MediaFacts(1920, 1080, 10, true) : new TemplateRenderer.MediaFacts(800, 800, 0, false);
    }

    private static void prepareChecks() throws Exception {
        File root = Files.createTempDirectory("work").toFile();
        File in = Files.createTempDirectory("in").toFile();
        File userVideo = new File(in, "holiday.MP4"); Files.write(userVideo.toPath(), new byte[]{1, 2, 3});
        File userPhoto = new File(in, "me.png"); Files.write(userPhoto.toPath(), new byte[]{4, 5});
        VideoSettings settings = new VideoSettings(1080, 1920, 30, 18, Integer.MAX_VALUE, "medium", "zerolatency");
        List<String> fetched = new ArrayList<>();
        List<String> log = new ArrayList<>();
        TemplateRenderer.ResourceFetcher fetcher = (names, clips, fonts, l) -> {
            fetched.addAll(names);
            check("prepare: the fetcher gets the working folder's Clips/ and Fonts/", clips.getName().equals("Clips") && fonts.getName().equals("Fonts") && clips.isDirectory() && fonts.isDirectory(), "");
        };
        TemplateTimelineInfo info = sampleInfo();
        TemplateRenderer.Prepared p = TemplateRenderer.prepare(info, template("t1", 1L, "http://x/"), Arrays.asList(
                new TemplateRenderer.SlotPick(userVideo.getPath(), true, 0, 0),
                new TemplateRenderer.SlotPick(userPhoto.getPath(), false, 0, 0)), settings, fetcher, fakeProbe(), root, log::add);

        check("prepare: the resources asked for are the locked clip and the audio", fetched.equals(Arrays.asList("lock.png", "song.mp3")), fetched.toString());
        check("prepare: the working folder is under the given root and named with the prefix", p.directory.getParentFile().equals(root) && p.directory.getName().startsWith(TemplateRenderer.WORK_FOLDER_PREFIX), p.directory.getName());
        check("prepare: the user's files are copied in as template-slot-N.ext (extension lower-cased)",
                new File(p.directory, "Clips/template-slot-1.mp4").isFile() && new File(p.directory, "Clips/template-slot-2.png").isFile(), "");
        Clip c1 = info.slots.get(0).clip, c2 = info.slots.get(1).clip;
        check("prepare: slot 1 is now the video: name, type, cover size, length",
                c1.getClipName().equals("template-slot-1.mp4") && c1.type == ClipType.VIDEO && c1.width == 3413 && c1.height == 1920
                        && near(c1.duration, 4, 1e-4) && c1.originalDuration == 10 && c1.videoProperties.valueVolume == 1f, c1.getClipName() + " " + c1.width + "x" + c1.height + " " + c1.duration);
        check("prepare: slot 2 is the photo, lasting its slot", c2.getClipName().equals("template-slot-2.png") && c2.type == ClipType.IMAGE
                && c2.width == 500 && c2.height == 500 && c2.duration == 2f, c2.width + "x" + c2.height + " " + c2.duration);
        check("prepare: the timeline's duration is recomputed", near(p.timeline.duration, 6, 1e-4), String.valueOf(p.timeline.duration));
        check("prepare: the project record points at the folder, titled like the template", p.project.getProjectPath().equals(p.directory.getPath()) && p.project.getProjectTitle().equals("My template"), p.project.getProjectTitle());
        check("prepare: it logs each placed clip", log.stream().anyMatch(s -> s.startsWith("Clip 1 placed: video, 4.0s of 4.0s")) && log.stream().anyMatch(s -> s.startsWith("Clip 2 placed: image")), log.toString());

        // ---- cleanup only touches its own folders
        File other = new File(root, "keep-me"); other.mkdirs();
        TemplateRenderer.cleanup(other);
        check("cleanup: a folder that is not a render folder is left alone", other.isDirectory(), "");
        TemplateRenderer.cleanup(p.directory);
        check("cleanup: a render folder is removed with everything in it", !p.directory.exists(), "");

        // ---- errors leave nothing behind
        try {
            TemplateRenderer.prepare(sampleInfo(), template("t1", 1L, ""), Arrays.asList(new TemplateRenderer.SlotPick(userVideo.getPath(), true, 0, 0)), settings, fetcher, fakeProbe(), root, log::add);
            check("prepare: wrong number of picks is refused", false, "");
        } catch (TemplateRenderer.RenderError e) {
            check("prepare: wrong number of picks -> 'The template changed', no folder made", e.getMessage().startsWith("The template changed: it now has 2 replaceable clips, not 1.") && workFolders(root).length == 0, e.getMessage());
        }
        try {
            TemplateRenderer.prepare(sampleInfo(), template("t1", 1L, ""), Arrays.asList(
                    new TemplateRenderer.SlotPick(userVideo.getPath(), true, 0, 0),
                    new TemplateRenderer.SlotPick(new File(in, "gone.png").getPath(), false, 0, 0)), settings, fetcher, fakeProbe(), root, log::add);
            check("prepare: an empty slot is refused", false, "");
        } catch (TemplateRenderer.RenderError e) {
            check("prepare: a missing file -> 'Clip 2 is empty.' and the folder is removed", e.getMessage().equals("Clip 2 is empty.") && workFolders(root).length == 0, e.getMessage());
        }
        try {
            TemplateRenderer.prepare(sampleInfo(), template("t1", 1L, ""), Arrays.asList(
                    new TemplateRenderer.SlotPick(userVideo.getPath(), true, 0, 0),
                    new TemplateRenderer.SlotPick(userPhoto.getPath(), false, 0, 0)), settings,
                    (names, clips, fonts, l) -> { throw new TemplateRenderer.RenderError("The template's files couldn't be downloaded: lock.png (404)"); },
                    fakeProbe(), root, log::add);
            check("prepare: a download failure is passed on", false, "");
        } catch (TemplateRenderer.RenderError e) {
            check("prepare: a download failure is passed on and the folder is removed", e.getMessage().contains("lock.png (404)") && workFolders(root).length == 0, e.getMessage());
        }
        try {
            TemplateRenderer.prepare(TemplateTimelineInfo.legacy(0, 0, 0), template("t1", 1L, ""), new ArrayList<>(), settings, fetcher, fakeProbe(), root, log::add);
            check("prepare: no timeline is refused", false, "");
        } catch (TemplateRenderer.RenderError e) {
            check("prepare: a template without a timeline is refused", e.getMessage().equals("This template has no timeline."), e.getMessage());
        } catch (RuntimeException e) {
            check("prepare: a template without a timeline is refused", false, e.toString());
        }
    }
}
