package com.vanvatcorporation.doubleclips.drive;

import com.google.gson.JsonObject;

/** The signed-in account's Drive numbers (GET /me). */
public final class DriveMe {
    public final String userId, username, email;
    public final long quotaBytes, usedBytes;
    public final int chunkSize;

    DriveMe(JsonObject o) {
        userId = DriveJson.str(o, "userId");
        username = DriveJson.str(o, "username");
        email = DriveJson.str(o, "email");
        quotaBytes = DriveJson.lng(o, "quotaBytes", 0);
        usedBytes = DriveJson.lng(o, "usedBytes", 0);
        chunkSize = (int) Math.max(64 * 1024, DriveJson.lng(o, "chunkSize", 4L * 1024 * 1024));
    }

    public long freeBytes() { return Math.max(0, quotaBytes - usedBytes); }
}
