package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.TemplateData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The files a template brings along (iOS: TemplateRenderer.fetchResources): the media and audio its author LOCKED, the
 * extra resources it lists, and the fonts of its text. They are downloaded from the template's content link, kept in a
 * cache per template + timestamp (so a template that changed is fetched again), and copied into the working folder:
 * fonts into Fonts/, everything else into Clips/.
 */
public final class TemplateResources {
    private TemplateResources() {}

    private static final Set<String> FONT_EXTENSIONS = new LinkedHashSet<>(Arrays.asList("ttf", "otf", "ttc", "otc"));
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    // ---- which files -------------------------------------------------------------------------------------

    /** A bare, safe file name: the last path component, trimmed; null for an empty one, "." or "..". */
    static String sanitiseName(String raw) {
        if (raw == null) return null;
        String n = raw.trim();
        int cut = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (cut >= 0) n = n.substring(cut + 1);
        n = n.trim();
        return n.isEmpty() || n.equals(".") || n.equals("..") ? null : n;
    }

    /** The template's extra resources, the clips it keeps (locked media, audio) and the fonts of its texts: sanitised, no repeats. */
    public static List<String> neededNames(TemplateData template, Timeline timeline) {
        List<String> raw = new ArrayList<>();
        String[] extra = template.getTemplateAdditionalResourcesName();
        if (extra != null) raw.addAll(Arrays.asList(extra));
        for (Track track : timeline.tracks) {
            if (track.clips == null) continue;
            for (Clip clip : track.clips) {
                switch (TemplateTimelineInfo.roleOf(clip)) {
                    case LOCKED: case AUDIO:
                        raw.add(clip.getClipName());
                        break;
                    case TEXT:
                        if (clip.textStyle != null && clip.textStyle.fontFile != null && !clip.textStyle.fontFile.isEmpty()) raw.add(clip.textStyle.fontFile);
                        break;
                    default:
                        break;
                }
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String r : raw) {
            String n = sanitiseName(r);
            if (n != null) seen.add(n);
        }
        return new ArrayList<>(seen);
    }

    static boolean isFont(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && FONT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** The cache folder of one template version: {cacheRoot}/{safeId}-{timestamp}. */
    static File cacheFolder(File cacheRoot, TemplateData template) {
        String id = template.getTemplateId() == null ? "" : template.getTemplateId().replaceAll("[^\\p{L}\\p{N}-]", "_");
        return new File(cacheRoot, id + "-" + template.getTemplateTimestamp());
    }

    // ---- download + copy ---------------------------------------------------------------------------------

    /**
     * Downloads what is not cached yet, then copies every resource into the working folder. Any failure is collected and
     * reported at the end together: "The template's files couldn't be downloaded: a (404), b (reason)".
     */
    public static void fetch(List<String> names, TemplateData template, File cacheRoot, File clipsDir, File fontsDir,
                             Consumer<String> log) throws TemplateRenderer.RenderError {
        if (names.isEmpty()) return;
        File cache = cacheFolder(cacheRoot, template);
        if (!cache.isDirectory() && !cache.mkdirs()) {
            throw new TemplateRenderer.RenderError("The template's files couldn't be downloaded: the cache folder can't be created.");
        }
        String base = template.getResolvedContentLink();
        HttpClient http = null;
        List<String> failed = new ArrayList<>();

        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            File cached = new File(cache, name);
            if (!cached.isFile()) {
                log.accept("Downloading " + name + " (" + (i + 1) + "/" + names.size() + ")\u2026");
                if (base.isEmpty()) { failed.add(name); continue; }
                if (http == null) {
                    http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
                }
                String problem = download(http, base + encode(name), cached);
                if (problem != null) { failed.add(name + " (" + problem + ")"); continue; }
            }
            try {
                File folder = isFont(name) ? fontsDir : clipsDir;
                Files.copy(cached.toPath(), new File(folder, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                failed.add(name + " (" + e.getMessage() + ")");
            }
        }
        if (!failed.isEmpty()) {
            throw new TemplateRenderer.RenderError("The template's files couldn't be downloaded: " + String.join(", ", failed));
        }
    }

    /** A path segment: everything but unreserved characters percent-encoded (a space is %20, not +). */
    static String encode(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** @return null when the file is now at {@code target}; else the reason (an HTTP status, or the error). */
    private static String download(HttpClient http, String url, File target) {
        File part = new File(target.getPath() + ".part");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream in = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() > 299) return String.valueOf(response.statusCode());
                Files.copy(in, part.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return "interrupted";
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }
}
