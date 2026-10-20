package com.vanvatcorporation.doubleclips.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A small window for one transfer (back up, restore, save a file): a line of text, a bar and a Stop button. Closing it
 * means Stop. {@link #post} can be called from any thread (coalesced); {@link #close} must be called on the FX thread.
 */
public final class TransferProgressWindow {
    private static final class Update {
        final String text; final double fraction;
        Update(String text, double fraction) { this.text = text; this.fraction = fraction; }
    }

    private final Stage stage = new Stage();
    private final Label status = new Label("Starting\u2026");
    private final ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private final Button stop = new Button("Stop");
    private final AtomicBoolean cancel = new AtomicBoolean();
    private final FxCoalescer<Update> updates = new FxCoalescer<>(u -> {
        status.setText(u.text);
        bar.setProgress(u.fraction);
    });

    public TransferProgressWindow(Window owner, String title) {
        status.setWrapText(true);
        status.setMaxWidth(380);
        bar.setPrefWidth(380);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(spacer, stop);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(12, status, bar, buttons);
        box.setPadding(new Insets(18));

        stage.setTitle(title);
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        }
        stage.setResizable(false);
        stage.setScene(new Scene(box));

        stop.setOnAction(e -> requestStop());
        stage.setOnCloseRequest(e -> { e.consume(); requestStop(); });   // never closes by itself mid-transfer
    }

    private void requestStop() {
        cancel.set(true);
        stop.setDisable(true);
        status.setText("Stopping\u2026");
    }

    /** Set to true when the user pressed Stop (or closed the window); the transfer checks it. */
    public AtomicBoolean cancelFlag() { return cancel; }

    public void show() { stage.show(); }

    public void close() {
        stage.setOnCloseRequest(null);
        stage.close();
    }

    /** fraction 0..1, or a negative number for "working, no percentage". Any thread. */
    public void post(String text, double fraction) {
        if (cancel.get()) return;            // keep showing "Stopping\u2026"
        updates.post(new Update(text, fraction < 0 ? ProgressBar.INDETERMINATE_PROGRESS : Math.min(1.0, fraction)));
    }
}
