package com.vanvatcorporation.doubleclips.drive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One asset in the public catalog: {@code { id, kind, title, description, tags[], ownerName, fileName, size, mimeType, previewUrl, downloads, createdAt }}. */
public final class CommunityAsset {
    public final String id;
    public final AssetKind kind;
    public final String title;
    public final String description;
    public final List<String> tags;
    public final String ownerName;
    public final String fileName;
    public final long size;
    public final String mimeType;
    /** An absolute link to the poster's small JPEG, or null. */
    public final String previewUrl;
    public final long downloads;
    public final Instant createdAt;

    private CommunityAsset(DriveClient drive, JsonObject o) {
        id = DriveJson.str(o, "id");
        kind = AssetKind.ofApiName(DriveJson.str(o, "kind"));
        String t = DriveJson.str(o, "title");
        title = t == null || t.trim().isEmpty() ? "Untitled" : t.trim();
        description = DriveJson.str(o, "description");
        List<String> list = new ArrayList<>();
        JsonElement tagsElement = o.get("tags");
        if (tagsElement != null && tagsElement.isJsonArray()) {
            JsonArray array = tagsElement.getAsJsonArray();
            for (JsonElement e : array) {
                try {
                    String tag = e.getAsString();
                    if (tag != null && !tag.trim().isEmpty()) list.add(tag.trim());
                } catch (RuntimeException ignored) { /* not a string: skip */ }
            }
        }
        tags = Collections.unmodifiableList(list);
        ownerName = DriveJson.str(o, "ownerName");
        fileName = DriveJson.str(o, "fileName");
        size = DriveJson.lng(o, "size", 0);
        mimeType = DriveJson.str(o, "mimeType");
        previewUrl = drive.absoluteUrl(DriveJson.str(o, "previewUrl"));
        downloads = DriveJson.lng(o, "downloads", 0);
        Instant created = DriveJson.date(o, "createdAt");
        createdAt = created == null ? Instant.EPOCH : created;
    }

    static CommunityAsset fromJson(DriveClient drive, JsonObject o) throws DriveError {
        CommunityAsset a = new CommunityAsset(drive, o);
        if (a.id == null || a.id.isEmpty()) throw new DriveError(0, "bad_answer", "The server's answer has no asset id.");
        return a;
    }

    @Override public boolean equals(Object other) { return other instanceof CommunityAsset && ((CommunityAsset) other).id.equals(id); }
    @Override public int hashCode() { return id.hashCode(); }
    @Override public String toString() { return title; }
}
