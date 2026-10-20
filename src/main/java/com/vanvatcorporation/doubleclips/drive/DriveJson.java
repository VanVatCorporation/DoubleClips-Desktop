package com.vanvatcorporation.doubleclips.drive;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Small helpers for the Drive JSON: null-safe reads, dates (.NET writes up to 7 fractional digits), request bodies. */
final class DriveJson {
    private DriveJson() {}

    /** A JSON string literal (with quotes). Request bodies are tiny, so they are written by hand instead of through Gson. */
    static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static JsonElement get(JsonObject o, String key) {
        if (o == null || !o.has(key)) return null;
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e;
    }

    static String str(JsonObject o, String key) {
        JsonElement e = get(o, key);
        try { return e == null ? null : e.getAsString(); } catch (RuntimeException ex) { return null; }
    }

    static long lng(JsonObject o, String key, long def) {
        JsonElement e = get(o, key);
        try { return e == null ? def : e.getAsLong(); } catch (RuntimeException ex) { return def; }
    }

    static boolean bool(JsonObject o, String key) {
        JsonElement e = get(o, key);
        try { return e != null && e.getAsBoolean(); } catch (RuntimeException ex) { return false; }
    }

    static Instant date(JsonObject o, String key) {
        String raw = str(o, key);
        return raw == null ? null : parseDate(raw);
    }

    /** ISO-8601 in UTC, with or without the zone, up to 9 fractional digits. Unreadable text gives the epoch. */
    static Instant parseDate(String raw) {
        String s = raw.trim();
        try { return Instant.parse(s); } catch (RuntimeException ignored) { /* try the zone-less form */ }
        try { return java.time.OffsetDateTime.parse(s).toInstant(); } catch (RuntimeException ignored) { /* next */ }
        try { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC); } catch (RuntimeException ignored) { /* next */ }
        return Instant.EPOCH;
    }
}
