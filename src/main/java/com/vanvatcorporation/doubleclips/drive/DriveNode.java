package com.vanvatcorporation.doubleclips.drive;

import com.google.gson.JsonObject;

import java.time.Instant;

/** One file or folder in Van Vat Drive. */
public final class DriveNode {
    public final String id, parentId, ownerId, createdBy, name, mimeType, sha256;
    public final boolean isFolder;
    public final long size;
    public final Instant createdAt, updatedAt, deletedAt;
    /** "openeditz" for the protected OpenEditz folder, else null. */
    public final String systemKey;
    /** owner | manager | editor | viewer */
    public final String myRole;

    private DriveNode(JsonObject o) {
        id = DriveJson.str(o, "id");
        parentId = DriveJson.str(o, "parentId");
        ownerId = DriveJson.str(o, "ownerId");
        createdBy = DriveJson.str(o, "createdBy");
        String n = DriveJson.str(o, "name");
        name = n == null ? "" : n;
        isFolder = DriveJson.bool(o, "isFolder");
        size = DriveJson.lng(o, "size", 0);
        mimeType = DriveJson.str(o, "mimeType");
        sha256 = DriveJson.str(o, "sha256");
        createdAt = DriveJson.date(o, "createdAt");
        updatedAt = DriveJson.date(o, "updatedAt");
        deletedAt = DriveJson.date(o, "deletedAt");
        systemKey = DriveJson.str(o, "systemKey");
        myRole = DriveJson.str(o, "myRole");
    }

    static DriveNode fromJson(JsonObject o) throws DriveError {
        DriveNode n = new DriveNode(o);
        if (n.id == null || n.id.isEmpty()) throw new DriveError(0, "bad_answer", "The server's answer has no item id.");
        return n;
    }

    public boolean isSystemFolder() { return systemKey != null; }

    @Override public boolean equals(Object other) { return other instanceof DriveNode && ((DriveNode) other).id.equals(id); }
    @Override public int hashCode() { return id.hashCode(); }
    @Override public String toString() { return name; }
}
