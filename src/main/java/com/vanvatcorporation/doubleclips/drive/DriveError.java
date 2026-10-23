package com.vanvatcorporation.doubleclips.drive;

import java.io.IOException;

/** An answer from Van Vat Drive that is an error ({ success:false, message, code }), or a problem before asking. */
public class DriveError extends IOException {
    private final int status;
    private final String code;

    public DriveError(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() { return status; }
    /** The server's machine-readable code (quota_exceeded, name_conflict, daily_upload_limit ...), or null. */
    public String getCode() { return code; }
    public boolean isUnauthorized() { return status == 401; }

    /** New terms were published and the user has not accepted them yet. */
    public boolean isTosRequired() { return status == 403 && "tos_required".equals(code); }

    /**
     * The server refused because of a quota or plan limit (storage, file size, daily transfer, compute):
     * the useful next step is "Get more resources" (the account tier upgrade).
     */
    public boolean isQuotaLimit() {
        return "quota_exceeded".equals(code) || "file_too_large".equals(code) || "daily_upload_limit".equals(code)
                || "daily_download_limit".equals(code) || "daily_compute_limit".equals(code);
    }

    /** Words for the user for any failure of a Drive call (a server answer, no network, a timeout ...). */
    public static String describe(Throwable e) {
        if (e instanceof DriveError) {
            DriveError d = (DriveError) e;
            if ("no_session".equals(d.getCode())) return "You are not signed in. Sign in from the Profile tab to use Storage.";
            if (d.isUnauthorized()) return "Your session expired. Please sign in again from the Profile tab.";
            return d.getMessage();
        }
        if (e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException
                || e instanceof java.net.SocketException || e instanceof java.net.http.HttpTimeoutException
                || e instanceof javax.net.ssl.SSLException) {
            return "Can't reach Van Vat Drive. Check your internet connection and try again.";
        }
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
