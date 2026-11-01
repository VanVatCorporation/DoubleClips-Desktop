package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.i18n.I18n;

import com.vanvatcorporation.doubleclips.ClipProcessingTracker;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;

/**
 * The badge over the top left of the preview canvas (CapCut's "Applying 1 feature..."): shows while any clip feature is being
 * pre-processed (see {@link ClipProcessingTracker}), click it to list which clip and feature are at what percent, and says
 * "... applied" with a green tick for a moment when the last one finishes. Hidden (and out of the layout) when idle.
 *
 * <p>{@link #refresh()} runs on the FX thread; the editor calls it, coalesced, whenever the tracker reports a change.
 */
final class ProcessingBadge extends VBox {

    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label tick = new Label("\u2713");
    private final Label title = new Label();
    private final Label chevron = new Label(I18n.t("\u25BE"));
    private final VBox list = new VBox(3);
    private final PauseTransition hideDone = new PauseTransition(Duration.millis(ClipProcessingTracker.DONE_VISIBLE_MS + 100));
    private boolean expanded;

    ProcessingBadge() {
        super(6);
        setPickOnBounds(false); // only the badge itself takes clicks, never the canvas around it
        setPadding(new Insets(7, 12, 7, 10));
        setStyle("-fx-background-color: rgba(30,30,34,0.92); -fx-background-radius: 10; -fx-border-radius: 10; "
                + "-fx-border-color: rgba(255,255,255,0.10);");
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);

        spinner.setPrefSize(16, 16);
        spinner.setMaxSize(16, 16);
        tick.setStyle("-fx-text-fill: #3CCB7F; -fx-font-weight: bold; -fx-font-size: 14px;");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold;");
        chevron.setStyle("-fx-text-fill: #B9BCC4; -fx-font-size: 11px;");
        HBox head = new HBox(8, spinner, tick, title, chevron);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setCursor(javafx.scene.Cursor.HAND);
        head.setOnMouseClicked(e -> {
            expanded = !expanded;
            refresh();
        });

        list.setPadding(new Insets(0, 0, 0, 24));
        getChildren().addAll(head, list);

        hideDone.setOnFinished(e -> refresh());
        refresh();
    }

    /** Re-reads the tracker and redraws. FX thread only. */
    void refresh() {
        ClipProcessingTracker tracker = ClipProcessingTracker.get();
        List<ClipProcessingTracker.Job> running = tracker.running();
        ClipProcessingTracker.Job done = running.isEmpty() ? tracker.lastFinished() : null;

        boolean busy = !running.isEmpty();
        boolean show = busy || done != null;
        setVisible(show);
        setManaged(show);
        spinner.setVisible(busy);
        spinner.setManaged(busy);
        tick.setVisible(!busy);
        tick.setManaged(!busy);
        chevron.setVisible(busy);
        chevron.setManaged(busy);
        list.getChildren().clear();

        if (busy) {
            hideDone.stop();
            title.setText(tracker.badgeTitle());
            chevron.setText(expanded ? "\u25B4" : "\u25BE");
            if (expanded) {
                for (ClipProcessingTracker.Job job : running) {
                    Label row = new Label(clipName(job) + " \u00B7 " + job.feature + "  " + ClipProcessingTracker.percent(job.progress()));
                    row.setStyle("-fx-text-fill: #D5D7DD; -fx-font-size: 11px;");
                    list.getChildren().add(row);
                }
            }
        } else if (done != null) {
            title.setText(done.feature + " applied");
            expanded = false;
            hideDone.playFromStart(); // looks again when the message has been shown long enough
        } else {
            expanded = false;
        }
        list.setManaged(!list.getChildren().isEmpty());
        list.setVisible(!list.getChildren().isEmpty());
    }

    private static String clipName(ClipProcessingTracker.Job job) {
        return job.clip instanceof Clip c && c.getClipName() != null ? c.getClipName() : "Clip";
    }
}
