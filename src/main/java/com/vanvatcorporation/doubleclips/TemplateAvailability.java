package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.TemplateData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Which engines can really render a template. The list the server sends is not enough to tell:
 * <ul>
 *   <li>it carries no FFmpeg command (the command is fetched on its own from fetch-ffmpeg-command/author/id, like
 *       Android does), so "no command in the list" does not mean "no FFmpeg version";</li>
 *   <li>a template can have a timeline address whose file is empty, "null" or not a timeline at all.</li>
 * </ul>
 * So both are checked for real: the command is fetched, the timeline is downloaded and parsed (the same cached copy the
 * preview and the renderer use). Blocking work runs on a background thread; the answer is remembered per template id +
 * timestamp. A check that failed only because of the network is NOT remembered, so the next try asks again.
 */
public final class TemplateAvailability {
    private TemplateAvailability() {}

    private static final String COMMAND_ENDPOINT = "https://app.vanvatcorp.com/doubleclips/api/fetch-ffmpeg-command/";
    private static final int TIMEOUT_MS = 20_000;
    private static final int MAX_COMMAND_BYTES = 1024 * 1024;

    public static final class Result {
        public final boolean timelineOk, ffmpegOk;
        /** Why OpenGL / FFmpeg cannot render it; "" when they can. Shown as the grayed-out choice's tooltip. */
        public final String timelineWhyNot, ffmpegWhyNot;
        /** False when a failure was only the network, so the answer must not be kept. */
        final boolean definitive;

        Result(boolean timelineOk, String timelineWhyNot, boolean ffmpegOk, String ffmpegWhyNot, boolean definitive) {
            this.timelineOk = timelineOk;
            this.timelineWhyNot = timelineWhyNot;
            this.ffmpegOk = ffmpegOk;
            this.ffmpegWhyNot = ffmpegWhyNot;
            this.definitive = definitive;
        }

        public boolean anyEngine() { return timelineOk || ffmpegOk; }
    }

    private static final ConcurrentHashMap<String, CompletableFuture<Result>> JOBS = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "TemplateAvailability");
        t.setDaemon(true);
        return t;
    });

    private static String key(TemplateData d) {
        return d.getTemplateId() + "|" + d.getTemplateTimestamp();
    }

    /** The remembered answer when this template was already checked and finished, else null. Never blocks. */
    public static Result peek(TemplateData d) {
        CompletableFuture<Result> f = JOBS.get(key(d));
        if (f == null || !f.isDone() || f.isCompletedExceptionally()) return null;
        Result r = f.getNow(null);
        if (r == null || !r.definitive) return null;
        applyTo(d, r);
        return r;
    }

    /** Starts the check (or joins the one already running / done). The future never fails; call from any thread. */
    public static CompletableFuture<Result> start(TemplateData d) {
        final String k = key(d);
        CompletableFuture<Result> f = JOBS.computeIfAbsent(k,
                x -> CompletableFuture.supplyAsync(() -> compute(d), POOL));
        f.whenComplete((r, err) -> {
            if (r == null || !r.definitive) JOBS.remove(k, f); // a network failure is asked again next time
            else applyTo(d, r);
        });
        return f.handle((r, err) -> r != null ? r : new Result(false, "The check failed: " + (err == null ? "unknown" : err),
                false, "The check failed: " + (err == null ? "unknown" : err), false));
    }

    private static void applyTo(TemplateData d, Result r) {
        d.setTimelineVerified(r.timelineOk);
        d.setFfmpegVerified(r.ffmpegOk);
    }

    private static Result compute(TemplateData d) {
        boolean definitive = true;

        // ---- the timeline (OpenGL) -------------------------------------------------------------------
        boolean tlOk = false;
        String tlWhy = "";
        if (d.getTemplateTimelineLink().isEmpty()) {
            tlWhy = "This template has no timeline (it is an older template), so OpenGL cannot render it.";
        } else {
            try {
                TemplateTimelineInfo info = TemplateTimelineLoader.load(d);
                if (info.clips.isEmpty()) tlWhy = "This template's timeline has no clips in it, so OpenGL has nothing to render.";
                else tlOk = true;
            } catch (TemplateTimelineLoader.LoadException e) {
                tlWhy = "This template's timeline is missing or unreadable (" + e.getMessage() + "), so OpenGL cannot render it.";
            } catch (IOException e) {
                tlWhy = "The template's timeline could not be downloaded (" + e.getMessage() + ").";
                definitive = false;
            } catch (RuntimeException e) {
                tlWhy = "This template's timeline is unreadable (" + e + "), so OpenGL cannot render it.";
            }
        }

        // ---- the FFmpeg command (turtle) ---------------------------------------------------------------
        boolean ffOk = false;
        String ffWhy = "";
        if (Boolean.FALSE.equals(d.getFfmpegAvailableFlag())) {
            ffWhy = "The server says FFmpeg cannot render this template (it needs the timeline).";
        } else {
            try {
                String command = fetchCommand(d);
                d.setFfmpegCommand(command);
                ffOk = true;
            } catch (NoCommandException e) {
                ffWhy = "The server has no usable FFmpeg command for this template (" + e.getMessage() + ").";
            } catch (IOException e) {
                ffWhy = "The FFmpeg command could not be fetched (" + e.getMessage() + ").";
                definitive = false;
            }
        }
        return new Result(tlOk, tlWhy, ffOk, ffWhy, definitive);
    }

    /** The server answered, and what it answered is not an FFmpeg command (or it said no). Not a network problem. */
    public static final class NoCommandException extends IOException {
        public NoCommandException(String message) { super(message); }
    }

    /** Blocking: the template's FFmpeg command from the server. Throws {@link NoCommandException} when there is none. */
    public static String fetchCommand(TemplateData d) throws IOException {
        String url = COMMAND_ENDPOINT + segment(d.getTemplateAuthor()) + "/" + segment(d.getTemplateId());
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code > 299) {
                if (code >= 400 && code < 500) throw new NoCommandException("HTTP " + code);
                throw new IOException("HTTP " + code);
            }
            String body = new String(readAll(conn.getInputStream()), StandardCharsets.UTF_8);
            // same trim as Android: one trailing newline
            if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
            if (!TemplateData.looksLikeCommand(body)) throw new NoCommandException("the answer is empty or not a command");
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private static String segment(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException e) { return s == null ? "" : s; }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_COMMAND_BYTES) throw new NoCommandException("the answer is too large to be a command");
            }
            return out.toByteArray();
        }
    }
}
