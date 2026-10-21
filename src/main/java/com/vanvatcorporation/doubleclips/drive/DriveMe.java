package com.vanvatcorporation.doubleclips.drive;

import com.google.gson.JsonObject;

import java.time.Instant;

/** The signed-in account's Drive numbers (GET /me): storage, plan, today's transfer counters and compute quota. */
public final class DriveMe {
    public final String userId, username, email;
    public final long quotaBytes, usedBytes;
    public final int chunkSize;

    /** Largest single file the plan allows (0 from older servers). */
    public final long maxFileBytes;
    /** The account tier (free, personal, pro...), or null from older servers. */
    public final String planName;

    /** Today's counters; {@code hasUsage} is false from older servers. */
    public final boolean hasUsage;
    public final long uploadedBytes, downloadedBytes, uploadLimit, downloadLimit;
    public final Instant resetsAt;
    /** Account-wide compute credits shared with every Van Vat service; {@code hasCompute} false when absent. */
    public final boolean hasCompute;
    public final double computeUsed, computeLimit;

    DriveMe(JsonObject o) {
        userId = DriveJson.str(o, "userId");
        username = DriveJson.str(o, "username");
        email = DriveJson.str(o, "email");
        quotaBytes = DriveJson.lng(o, "quotaBytes", 0);
        usedBytes = DriveJson.lng(o, "usedBytes", 0);
        chunkSize = (int) Math.max(64 * 1024, DriveJson.lng(o, "chunkSize", 4L * 1024 * 1024));
        maxFileBytes = DriveJson.lng(o, "maxFileBytes", 0);

        JsonObject plan = DriveJson.obj(o, "plan");
        planName = plan == null ? null : DriveJson.str(plan, "name");

        JsonObject usage = DriveJson.obj(o, "usage");
        hasUsage = usage != null;
        uploadedBytes = DriveJson.lng(usage, "uploadedBytes", 0);
        downloadedBytes = DriveJson.lng(usage, "downloadedBytes", 0);
        uploadLimit = DriveJson.lng(usage, "uploadLimit", 0);
        downloadLimit = DriveJson.lng(usage, "downloadLimit", 0);
        resetsAt = DriveJson.date(usage, "resetsAt");
        hasCompute = usage != null && usage.has("computeLimit") && !usage.get("computeLimit").isJsonNull();
        computeUsed = DriveJson.dbl(usage, "computeUsed", 0);
        computeLimit = DriveJson.dbl(usage, "computeLimit", 0);
    }

    public long freeBytes() { return Math.max(0, quotaBytes - usedBytes); }

    /** Room left in today's upload allowance (unlimited when the server did not say). */
    public long uploadFree() { return hasUsage ? Math.max(0, uploadLimit - uploadedBytes) : Long.MAX_VALUE; }

    /** Room left in today's download allowance (unlimited when the server did not say). */
    public long downloadFree() { return hasUsage ? Math.max(0, downloadLimit - downloadedBytes) : Long.MAX_VALUE; }
}
