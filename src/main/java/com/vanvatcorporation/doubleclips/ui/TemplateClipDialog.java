package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.TemplateUseModel;
import com.vanvatcorporation.doubleclips.ui.components.AppUi;
import com.vanvatcorporation.doubleclips.ui.components.MediaThumbnails;
import com.vanvatcorporation.doubleclips.ui.components.TrimRangeSlider;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.Locale;

/**
 * The properties of one clip in "Use template" (iOS: the "Export Clip" sheet): a picture of the media, what it is, and for a
 * video a trim: the part of it that plays. Changes are applied with Done.
 */
final class TemplateClipDialog {
    private TemplateClipDialog() {}

    private static String seconds(double v) { return String.format(Locale.ROOT, "%.1fs", v); }

    static void show(Window owner, TemplateUseModel model, int index, MediaThumbnails thumbs, Runnable onApplied) {
        final TemplateUseModel.Slot slot = model.slots.get(index);
        if (!slot.isFilled()) return;

        Stage stage = new Stage();
        stage.setTitle("Clip " + (index + 1));
        if (owner != null) { stage.initOwner(owner); stage.initModality(Modality.WINDOW_MODAL); }

        // ---- what it is
        ImageView preview = new ImageView();
        preview.setFitWidth(88);
        preview.setFitHeight(88);
        Rectangle clip = new Rectangle(88, 88);
        clip.setArcWidth(20);
        clip.setArcHeight(20);
        preview.setClip(clip);
        thumbs.load(slot.path, slot.kind == TemplateUseModel.Kind.VIDEO, img -> {
            if (img != null) { preview.setImage(img); preview.setViewport(MediaThumbnails.coverSquare(img)); }
        });

        Label title = new Label("Clip " + (index + 1));
        title.getStyleClass().add("storage-dialog-title");
        Label file = new Label(slot.fileName);
        file.getStyleClass().add("storage-row-name");
        file.setMaxWidth(300);
        Label type = new Label(slot.typeDescription);
        type.getStyleClass().add("storage-meta");
        VBox texts = new VBox(4, title, file, type);
        HBox top = new HBox(16, preview, texts);
        top.setAlignment(Pos.CENTER_LEFT);

        // ---- trim
        VBox trimCard = new VBox(10);
        trimCard.getStyleClass().add("storage-card");
        trimCard.setPadding(new Insets(16, 18, 16, 18));
        Label trimTitle = new Label("Trim");
        trimTitle.getStyleClass().add("storage-section-title");

        final boolean trimmable = slot.kind == TemplateUseModel.Kind.VIDEO && slot.mediaDuration > 0;
        final TrimRangeSlider[] slider = new TrimRangeSlider[1];
        if (trimmable) {
            double end = slot.endTrim > slot.startTrim ? slot.endTrim : slot.mediaDuration;
            slider[0] = new TrimRangeSlider(slot.mediaDuration, slot.startTrim, end, 0.1);
            Label range = new Label();
            range.getStyleClass().add("storage-row-name");
            Label length = new Label();
            length.getStyleClass().add("storage-meta");
            Label keeps = new Label(slot.targetDuration > 0
                    ? "The template keeps this clip on screen for " + seconds(slot.targetDuration) + "."
                    : "The template does not say how long this clip lasts.");
            keeps.getStyleClass().add("tpl-hint");
            keeps.setWrapText(true);
            Label warn = new Label("This is shorter than the template's clip: it will end early.");
            warn.getStyleClass().add("storage-meta-error");
            warn.setWrapText(true);

            Runnable refresh = () -> {
                double lo = slider[0].lowerProperty().get(), hi = slider[0].upperProperty().get();
                range.setText(seconds(lo) + " \u2013 " + seconds(hi));
                length.setText(seconds(hi - lo) + " of " + seconds(slot.mediaDuration));
                boolean early = slot.targetDuration > 0 && (hi - lo) < slot.targetDuration - 0.05;
                warn.setVisible(early);
                warn.setManaged(early);
            };
            slider[0].lowerProperty().addListener((o, a, b) -> refresh.run());
            slider[0].upperProperty().addListener((o, a, b) -> refresh.run());
            refresh.run();

            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            trimCard.getChildren().addAll(new HBox(8, trimTitle, sp, range), slider[0], length, keeps, warn);
        } else if (slot.kind == TemplateUseModel.Kind.VIDEO) {
            Label none = new Label("This video's length could not be read, so it plays from its start for as long as the template needs.");
            none.getStyleClass().add("storage-meta");
            none.setWrapText(true);
            trimCard.getChildren().addAll(trimTitle, none);
        } else {
            Label none = new Label("A photo has nothing to trim: it stays on screen as long as the template's clip does"
                    + (slot.targetDuration > 0 ? " (" + seconds(slot.targetDuration) + ")." : "."));
            none.getStyleClass().add("storage-meta");
            none.setWrapText(true);
            trimCard.getChildren().addAll(trimTitle, none);
        }

        // ---- buttons
        Button cancel = AppUi.button("Cancel", null, false);
        Button done = AppUi.button("Done", null, true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(10, spacer, cancel, done);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        cancel.setOnAction(e -> stage.close());
        done.setOnAction(e -> {
            if (trimmable) {
                double lo = slider[0].lowerProperty().get(), hi = slider[0].upperProperty().get();
                if (Math.abs(lo - slot.startTrim) > 1e-6 || Math.abs(hi - slot.endTrim) > 1e-6) model.setTrim(index, lo, hi);
            }
            stage.close();
            if (onApplied != null) onApplied.run();
        });

        VBox root = new VBox(18, top, trimCard, buttons);
        root.getStyleClass().add("storage-dialog");
        root.setPadding(new Insets(24));
        Scene scene = new Scene(root, 480, trimmable ? 380 : 300);
        AppUi.applyAppStyles(scene);
        stage.setScene(scene);
        stage.show();
    }
}
