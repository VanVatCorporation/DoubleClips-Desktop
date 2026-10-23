package com.vanvatcorporation.doubleclips.drive;

import com.vanvatcorporation.doubleclips.auth.RetrofitClient;

/**
 * Van Vat Drive access for DoubleClips (iOS: DoubleClipsDrive). There is no second sign-in: the account login the app
 * already has keeps the JWT in the cookie "authToken" (auth/RetrofitClient's cookie jar), and the Drive API takes that
 * same token as {@code Authorization: Bearer}.
 */
public final class DoubleClipsDrive {
    private DoubleClipsDrive() {}

    public static final DriveClient client = new DriveClient(DoubleClipsDrive::sessionToken);

    /** The OpenEditz client: the part of the Drive API the editor needs, locked to the protected OpenEditz folder. */
    public static final OpenEditzClient openEditz = new OpenEditzClient(client);

    static {
        // The server hands out a fresh token when the current one is close to expiring: keep it, or the session would
        // end on a date the server already moved.
        client.onTokenRefreshed = token -> RetrofitClient.getInstance().replaceAuthToken(token);
        // New terms were published while the app was open: any Drive call answers tos_required, so ask /me and show the popup.
        client.onTosRequired = com.vanvatcorporation.doubleclips.ui.components.TermsGate::checkAndShow;
    }

    public static String sessionToken() {
        return RetrofitClient.getInstance().getAuthToken();
    }

    public static boolean hasSession() {
        String t = sessionToken();
        return t != null && !t.isEmpty();
    }
}
