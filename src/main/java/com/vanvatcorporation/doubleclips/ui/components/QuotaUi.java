package com.vanvatcorporation.doubleclips.ui.components;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.helper.ProjectBackup;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Quota UI shared by the Storage tab and the backup window, with the same behaviour and wording as the web Drive:
 * bars turn orange below 50% remaining and red below 25%, and a transfer that probably won't fit today's allowance
 * gets a "Continue anyway" warning instead of being blocked.
 */
public final class QuotaUi {
    private QuotaUi() {}

    /** Tiers live on the account (one pack for every Van Vat service), so buying more happens in the Account Center. */
    public static final String PLANS_URL = "https://account.vanvatcorp.com/account-center/#plans";

    public static final int NORMAL = 0, WARN = 1, DANGER = 2;

    public static int tone(double used, double total) {
        if (total <= 0) return NORMAL;
        double remaining = Math.max(0, total - used) / total;
        if (remaining < 0.25) return DANGER;
        if (remaining < 0.50) return WARN;
        return NORMAL;
    }

    /** Sets the bar to used/total and colours it by how much is left (style.css: storage-bar-caution / storage-bar-warn). */
    public static void applyTone(ProgressBar bar, double used, double total) {
        bar.getStyleClass().removeAll("storage-bar-caution", "storage-bar-warn");
        int tone = tone(used, total);
        if (tone == WARN) bar.getStyleClass().add("storage-bar-caution");
        else if (tone == DANGER) bar.getStyleClass().add("storage-bar-warn");
        bar.setProgress(total <= 0 ? 0 : Math.max(0, Math.min(1.0, used / total)));
    }

    /** A caption over a thin bar, for the daily limits. */
    public static VBox usageRow(String text, double used, double total) {
        Label label = new Label(text);
        label.getStyleClass().add("storage-meta");
        ProgressBar bar = new ProgressBar(0);
        bar.getStyleClass().add("storage-bar");
        bar.setMaxWidth(Double.MAX_VALUE);
        applyTone(bar, used, total);
        return new VBox(4, label, bar);
    }

    public static Hyperlink moreResourcesLink() {
        Hyperlink link = new Hyperlink("Get more resources");
        link.setOnAction(e -> openPlans());
        return link;
    }

    public static void openPlans() {
        try { DoubleClipsDesktop.getInstance().getHostServices().showDocument(PLANS_URL); } catch (RuntimeException ignored) { /* no browser */ }
    }

    // ── dialogs ─────────────────────────────────────────────────────────────

    /** "Continue anyway" / Cancel. FX thread only. */
    public static boolean confirmContinue(Window owner, String title, String message) {
        ButtonType go = new ButtonType("Continue anyway", ButtonBar.ButtonData.OK_DONE);
        Alert a = new Alert(Alert.AlertType.WARNING, message, go, ButtonType.CANCEL);
        a.setTitle("Storage");
        a.setHeaderText(title);
        if (owner != null) a.initOwner(owner);
        return a.showAndWait().filter(b -> b == go).isPresent();
    }

    /** Same, callable from a background thread (blocks until the user answers). */
    public static boolean confirmContinueAnyThread(Window owner, String title, String message) {
        if (Platform.isFxApplicationThread()) return confirmContinue(owner, title, message);
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { answer.complete(confirmContinue(owner, title, message)); } catch (RuntimeException e) { answer.complete(false); }
        });
        try { return answer.get(); } catch (Exception e) { return false; }
    }

    /** Something that cannot work (no storage left, a limit reached): explain it and offer the upgrade. FX thread only. */
    public static void blocked(Window owner, String title, String message) {
        ButtonType more = new ButtonType("Get more resources", ButtonBar.ButtonData.LEFT);
        Alert a = new Alert(Alert.AlertType.WARNING, message, more, ButtonType.OK);
        a.setTitle("Storage");
        a.setHeaderText(title);
        if (owner != null) a.initOwner(owner);
        a.showAndWait().filter(b -> b == more).ifPresent(b -> openPlans());
    }

    // ── wording (same as the web Drive) ─────────────────────────────────────

    public static final String UPLOAD_TITLE = "Daily upload limit warning";
    public static final String DOWNLOAD_TITLE = "Daily download limit warning";

    public static String uploadMessage(long size, long free) {
        return "You might not have enough daily upload quota left to upload this file (" + ProjectBackup.formatBytes(size)
                + " selected, only " + ProjectBackup.formatBytes(free) + " left today). The upload may stop once the limit is reached.";
    }

    public static String downloadMessage(long size, long free) {
        return "You might not have enough daily download quota left for this file (" + ProjectBackup.formatBytes(size)
                + " requested, only " + ProjectBackup.formatBytes(free) + " left today). The download may be cut off once the limit is reached.";
    }

    /** Whole credits without decimals, otherwise one decimal. */
    public static String credits(double n) {
        return n == Math.rint(n) ? String.valueOf((long) n) : String.format(Locale.US, "%.1f", n);
    }
}
