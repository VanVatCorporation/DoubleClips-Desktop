package com.vanvatcorporation.doubleclips.drive;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Typed client for the Van Vat Drive API v2 (docs/vanvat-drive-internal-api.md), the part the editor needs. Same
 * contract as iOS's DriveClient: auth is {@code Authorization: Bearer <jwt>} (the account JWT the app already has),
 * cookies are never sent. Every call blocks: run it off the FX thread.
 */
public final class DriveClient {
    public static final String DEFAULT_DRIVE = "https://drive.vanvatcorp.com";

    private final String base;
    private final Supplier<String> tokenProvider;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** The server sends a fresh JWT (X-Refreshed-Token) when the current one is close to expiring: store it. */
    public volatile Consumer<String> onTokenRefreshed;
    /** Called on any 401, so the app can treat the session as gone. */
    public volatile Runnable onUnauthorized;

    public DriveClient(Supplier<String> tokenProvider) { this(DEFAULT_DRIVE, tokenProvider); }

    public DriveClient(String driveBaseUrl, Supplier<String> tokenProvider) {
        String b = driveBaseUrl.endsWith("/") ? driveBaseUrl.substring(0, driveBaseUrl.length() - 1) : driveBaseUrl;
        this.base = b + "/api/v2/drive";
        this.tokenProvider = tokenProvider;
    }

    public boolean hasSession() {
        String t = tokenProvider.get();
        return t != null && !t.isEmpty();
    }

    // ---- plumbing ----------------------------------------------------------------------------------------

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private HttpRequest.Builder builder(String path, Map<String, String> query, Duration timeout) throws DriveError {
        String token = tokenProvider.get();
        if (token == null || token.isEmpty()) throw new DriveError(401, "no_session", "You are not signed in.");
        StringBuilder url = new StringBuilder(base).append(path);
        char sep = '?';
        if (query != null) {
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (e.getValue() == null) continue;
                url.append(sep).append(enc(e.getKey())).append('=').append(enc(e.getValue()));
                sep = '&';
            }
        }
        return HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token);
    }

    private <T> HttpResponse<T> exec(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(req, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted");
        }
    }

    private static String defaultMessage(int code) {
        switch (code) {
            case 401: return "Your session expired. Please sign in again.";
            case 403: return "You are not allowed to do that.";
            case 404: return "Not found.";
            case 409: return "That name is already taken.";
            case 413: return "Not enough storage space left.";
            case 429: return "The daily transfer limit is reached. Try again later.";
            default: return "Van Vat Drive answered HTTP " + code + ".";
        }
    }

    /** Stores a refreshed token, and turns a non-2xx answer into a {@link DriveError}. */
    private void check(int status, HttpHeaders headers, byte[] body) throws DriveError {
        String fresh = headers.firstValue("X-Refreshed-Token").orElse("");
        Consumer<String> onFresh = onTokenRefreshed;
        if (!fresh.isEmpty() && onFresh != null) onFresh.accept(fresh);
        if (status >= 200 && status < 300) return;

        String message = defaultMessage(status);
        String code = null;
        JsonObject o = tryObject(body);
        if (o != null) {
            String m = DriveJson.str(o, "message");
            if (m != null && !m.isEmpty()) message = m;
            code = DriveJson.str(o, "code");
        }
        if (status == 401) {
            Runnable r = onUnauthorized;
            if (r != null) r.run();
        }
        throw new DriveError(status, code, message);
    }

    private static JsonObject tryObject(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            JsonElement e = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** One JSON call. Returns the answer's object, or an empty object when the answer has no body (204). */
    private JsonObject call(String method, String path, Map<String, String> query, String jsonBody) throws IOException {
        HttpRequest.Builder b = builder(path, query, Duration.ofSeconds(60));
        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<byte[]> r = exec(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        check(r.statusCode(), r.headers(), r.body());
        JsonObject o = tryObject(r.body());
        return o != null ? o : new JsonObject();
    }

    private static JsonObject need(JsonObject o, String key) throws DriveError {
        if (o.has(key) && o.get(key).isJsonObject()) return o.getAsJsonObject(key);
        throw new DriveError(0, "bad_answer", "The server's answer is not what was expected.");
    }

    // ---- account -----------------------------------------------------------------------------------------

    public DriveMe me() throws IOException { return new DriveMe(call("GET", "/me", null, null)); }

    /** The protected OpenEditz folder at the top of the user's space (created on first call). */
    public DriveNode ensureOpenEditz() throws IOException {
        return DriveNode.fromJson(need(call("POST", "/system/openeditz", null, null), "node"));
    }

    // ---- browse ------------------------------------------------------------------------------------------

    /** Everything directly inside a folder (all pages). parentId null = my root. */
    public List<DriveNode> list(String parentId) throws IOException {
        List<DriveNode> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("parentId", parentId);
            q.put("limit", "500");
            q.put("offset", String.valueOf(offset));
            JsonObject o = call("GET", "/nodes", q, null);
            int before = all.size();
            if (o.has("items") && o.get("items").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("items")) {
                    if (e.isJsonObject()) all.add(DriveNode.fromJson(e.getAsJsonObject()));
                }
            }
            if (!DriveJson.bool(o, "hasMore") || all.size() == before || all.size() > 20_000) break;
            offset = all.size();
        }
        return all;
    }

    // ---- change ------------------------------------------------------------------------------------------

    public DriveNode createFolder(String parentId, String name) throws IOException {
        String body = "{" + (parentId == null ? "" : "\"parentId\":" + DriveJson.quote(parentId) + ",") + "\"name\":" + DriveJson.quote(name) + "}";
        return DriveNode.fromJson(need(call("POST", "/folders", null, body), "node"));
    }

    public DriveNode rename(DriveNode node, String name) throws IOException {
        return DriveNode.fromJson(need(call("PATCH", "/nodes/" + node.id, null, "{\"name\":" + DriveJson.quote(name) + "}"), "node"));
    }

    /** To the trash: the space is NOT freed until it is purged. */
    public void trash(DriveNode node) throws IOException { call("DELETE", "/nodes/" + node.id, null, null); }

    /** Deletes a trashed item for good and frees its space. */
    public void purge(DriveNode node) throws IOException { call("DELETE", "/trash/" + node.id, null, null); }

    // ---- download ----------------------------------------------------------------------------------------

    /**
     * Downloads a file to {@code dest} (written as dest.part first, so a failed download leaves no half file).
     * {@code progress} gets the bytes received so far; {@code cancel} stops it (CancellationException).
     */
    public void download(DriveNode node, File dest, LongConsumer progress, AtomicBoolean cancel) throws IOException {
        HttpRequest req = builder("/files/" + node.id + "/content", null, Duration.ofSeconds(60)).GET().build();
        HttpResponse<InputStream> r = exec(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) {
            if (r.statusCode() < 200 || r.statusCode() > 299) {
                check(r.statusCode(), r.headers(), in.readNBytes(64 * 1024));
                return;
            }
            check(r.statusCode(), r.headers(), null); // only to pick up a refreshed token
            File parent = dest.getAbsoluteFile().getParentFile();
            if (parent != null) parent.mkdirs();
            File part = new File(dest.getPath() + ".part");
            long total = 0;
            try (java.io.OutputStream out = Files.newOutputStream(part.toPath())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel != null && cancel.get()) throw new CancellationException("Download stopped.");
                    out.write(buf, 0, n);
                    total += n;
                    if (progress != null) progress.accept(total);
                }
            } catch (IOException | RuntimeException e) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw e;
            }
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---- resumable upload primitives (used by ResumableUpload) -----------------------------------------------

    public static final class UploadSession {
        public final String uploadId;
        public final int chunkSize;
        public final long received;
        UploadSession(String uploadId, int chunkSize, long received) { this.uploadId = uploadId; this.chunkSize = chunkSize; this.received = received; }
    }

    UploadSession createUpload(String parentId, String name, long size, String mimeType) throws IOException {
        StringBuilder b = new StringBuilder("{");
        if (parentId != null) b.append("\"parentId\":").append(DriveJson.quote(parentId)).append(',');
        b.append("\"name\":").append(DriveJson.quote(name)).append(",\"size\":").append(size);
        if (mimeType != null) b.append(",\"mimeType\":").append(DriveJson.quote(mimeType));
        b.append('}');
        JsonObject o = call("POST", "/uploads", null, b.toString());
        String id = DriveJson.str(o, "uploadId");
        if (id == null) throw new DriveError(0, "bad_answer", "The server did not start the upload.");
        return new UploadSession(id, (int) Math.max(64 * 1024, DriveJson.lng(o, "chunkSize", 4L * 1024 * 1024)), DriveJson.lng(o, "received", 0));
    }

    long uploadReceived(String uploadId) throws IOException {
        return DriveJson.lng(call("GET", "/uploads/" + uploadId, null, null), "received", 0);
    }

    DriveNode completeUpload(String uploadId) throws IOException {
        return DriveNode.fromJson(need(call("POST", "/uploads/" + uploadId + "/complete", null, null), "node"));
    }

    /** Best effort: a failed cancel only leaves a session that expires by itself. */
    void cancelUpload(String uploadId) {
        try { call("DELETE", "/uploads/" + uploadId, null, null); } catch (IOException | RuntimeException ignored) { /* expires */ }
    }

    /** The answer to one chunk: either the new offset, or "the server has {received}, continue from there". */
    static final class ChunkResult {
        final boolean mismatch;
        final long offset;
        ChunkResult(boolean mismatch, long offset) { this.mismatch = mismatch; this.offset = offset; }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private final LongConsumer onRead;
        private long total;
        CountingInputStream(InputStream in, LongConsumer onRead) { super(in); this.onRead = onRead; }
        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0) onRead.accept(++total);
            return b;
        }
        @Override public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) { total += n; onRead.accept(total); }
            return n;
        }
    }

    /** Sends {@code data[0..length)} starting at {@code offset}. {@code sent} gets the bytes of THIS chunk sent so far. */
    ChunkResult putChunk(String uploadId, long offset, byte[] data, int length, LongConsumer sent) throws IOException {
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.fromPublisher(
                HttpRequest.BodyPublishers.ofInputStream(() -> new CountingInputStream(new java.io.ByteArrayInputStream(data, 0, length), sent)),
                length);
        HttpRequest req = builder("/uploads/" + uploadId, null, Duration.ofMinutes(5))
                .header("Upload-Offset", String.valueOf(offset))
                .header("Content-Type", "application/octet-stream")
                .PUT(body)
                .build();
        HttpResponse<byte[]> r = exec(req, HttpResponse.BodyHandlers.ofByteArray());

        if (r.statusCode() == 409) {
            JsonObject o = tryObject(r.body());
            if (o != null && "offset_mismatch".equals(DriveJson.str(o, "code"))) {
                return new ChunkResult(true, DriveJson.lng(o, "received", 0));
            }
        }
        check(r.statusCode(), r.headers(), r.body());
        long next = offset + length;
        String header = r.headers().firstValue("Upload-Offset").orElse("");
        try { if (!header.isEmpty()) next = Long.parseLong(header.trim()); } catch (NumberFormatException ignored) { /* keep our own count */ }
        return new ChunkResult(false, next);
    }
}
