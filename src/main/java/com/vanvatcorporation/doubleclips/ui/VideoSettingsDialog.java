package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

import java.util.Objects;

/**
 * The project's video settings (canvas size, frame rate, export quality...), shared by the editor (the 60 / 1080p badges
 * under the player) and the export window, so both edit the same things the same way. On OK the values are written into the
 * given {@link VideoSettings} (the object the editor and the preview are already holding) and the caller is told WHAT changed,
 * because a new canvas size and a new frame rate each need the editor to rebuild something (see
 * EditorWindow#onVideoSettingsChanged); the caller saves them.
 *
 * <p>Typed numbers are kept inside what makes sense: the canvas at least {@link #MIN_SIDE} px and an even number of pixels
 * (H.264's 4:2:0 needs even sides), the frame rate at least 1, the CRF 0 ... 51; an entry that isn't a number keeps the old
 * value. The upper limits ({@link #MAX_SIDE}, {@link #MAX_FRAME_RATE}) are left open on purpose.
 */
final class VideoSettingsDialog {

    static final int MIN_SIDE = 16;
    static final int MAX_SIDE = Integer.MAX_VALUE; // Before: 16384px
    static final int MAX_FRAME_RATE = Integer.MAX_VALUE; // Before: 240fps

    private static final String CUSTOM = "Custom";
    /** Common canvases, as width x height. */
    private static final int[][] RESOLUTIONS = {
            {1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}, {720, 1280}, {1080, 1920}, {1080, 1080}
    };

    /** What the dialog changed. */
    static final class Change {
        /** The canvas width or height. */
        final boolean size;
        final boolean frameRate;
        /** Anything else (quality, preset, hardware acceleration...): only matters for the export. */
        final boolean other;

        Change(boolean size, boolean frameRate, boolean other) {
            this.size = size;
            this.frameRate = frameRate;
            this.other = other;
        }

        boolean any() {
            return size || frameRate || other;
        }
    }

    private VideoSettingsDialog() {}

    /** Shows the dialog; null when cancelled or nothing was changed. */
    static Change show(Window owner, VideoSettings settings) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Video Settings");
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        // a canvas picked from the list fills the two fields; typing in them makes it "Custom"
        TextField width = field(String.valueOf(settings.videoWidth));
        TextField height = field(String.valueOf(settings.videoHeight));
        ComboBox<String> sizeBox = new ComboBox<>();
        for (int[] r : RESOLUTIONS) sizeBox.getItems().add(r[0] + " x " + r[1]);
        sizeBox.getItems().add(CUSTOM);
        sizeBox.setValue(CUSTOM);
        syncSizeBox(sizeBox, settings.videoWidth, settings.videoHeight);
        sizeBox.setOnAction(e -> {
            String v = sizeBox.getValue();
            if (v == null || CUSTOM.equals(v)) return;
            String[] parts = v.split(" x ");
            width.setText(parts[0]);
            height.setText(parts[1]);
        });
        javafx.beans.value.ChangeListener<String> typed = (o, was, now) ->
                syncSizeBox(sizeBox, parse(width, -1), parse(height, -1));
        width.textProperty().addListener(typed);
        height.textProperty().addListener(typed);

        TextField frameRate = field(String.valueOf(settings.frameRate));
        TextField crf = field(String.valueOf(settings.crf));
        TextField bitrate = field(String.valueOf(settings.bitrate));
        TextField clipCap = field(String.valueOf(settings.clipCap));

        int row = 0;
        grid.add(new Label("Canvas"), 0, row);
        grid.add(sizeBox, 1, row++);
        grid.add(new Label("Width"), 0, row);
        grid.add(width, 1, row++);
        grid.add(new Label("Height"), 0, row);
        grid.add(height, 1, row++);
        grid.add(new Label("Frame Rate"), 0, row);
        grid.add(frameRate, 1, row++);
        grid.add(new Label("CRF"), 0, row);
        grid.add(crf, 1, row++);
        grid.add(new Label("Bitrate (Mbps)"), 0, row);
        grid.add(bitrate, 1, row++);
        grid.add(new Label("Clip Cap"), 0, row);
        grid.add(clipCap, 1, row++);

        ComboBox<String> presetBox = new ComboBox<>();
        presetBox.getItems().addAll("ultrafast", "superfast", "veryfast", "faster", "fast",
                "medium", "slow", "slower", "veryslow", "placebo");
        presetBox.setValue(settings.preset != null ? settings.preset : "medium");
        grid.add(new Label("Preset"), 0, row);
        grid.add(presetBox, 1, row++);

        ComboBox<String> tuneBox = new ComboBox<>();
        tuneBox.getItems().addAll("film", "animation", "grain", "stillimage", "fastdecode", "zerolatency");
        tuneBox.setValue(settings.tune != null ? settings.tune : "film");
        grid.add(new Label("Tune"), 0, row);
        grid.add(tuneBox, 1, row++);

        CheckBox stretchCB = new CheckBox("Stretch to Full");
        stretchCB.setSelected(settings.isStretchToFull);
        CheckBox hwAccelCB = new CheckBox("Hardware Acceleration");
        hwAccelCB.setSelected(settings.useHardwareAccel);
        grid.add(stretchCB, 0, row++, 2, 1);
        grid.add(hwAccelCB, 0, row, 2, 1);

        dialog.getDialogPane().setContent(grid);

        final Change[] result = {null};
        dialog.showAndWait().ifPresent(button -> {
            if (button != ButtonType.OK) return;
            int newWidth = evenSide(parse(width, settings.videoWidth), settings.videoWidth);
            int newHeight = evenSide(parse(height, settings.videoHeight), settings.videoHeight);
            int newRate = clamp(parse(frameRate, settings.frameRate), 1, MAX_FRAME_RATE);
            int newCrf = clamp(parse(crf, settings.crf), 0, 51);
            int newBitrate = Math.max(1, parse(bitrate, settings.bitrate));
            int newClipCap = Math.max(0, parse(clipCap, settings.clipCap));
            String newPreset = presetBox.getValue();
            String newTune = tuneBox.getValue();

            boolean size = newWidth != settings.videoWidth || newHeight != settings.videoHeight;
            boolean rate = newRate != settings.frameRate;
            boolean other = newCrf != settings.crf || newBitrate != settings.bitrate || newClipCap != settings.clipCap
                    || !Objects.equals(newPreset, settings.preset) || !Objects.equals(newTune, settings.tune)
                    || stretchCB.isSelected() != settings.isStretchToFull || hwAccelCB.isSelected() != settings.useHardwareAccel;

            settings.videoWidth = newWidth;
            settings.videoHeight = newHeight;
            settings.frameRate = newRate;
            settings.crf = newCrf;
            settings.bitrate = newBitrate;
            settings.clipCap = newClipCap;
            settings.preset = newPreset;
            settings.tune = newTune;
            settings.isStretchToFull = stretchCB.isSelected();
            settings.useHardwareAccel = hwAccelCB.isSelected();

            Change change = new Change(size, rate, other);
            if (change.any()) result[0] = change;
        });
        return result[0];
    }

    private static void syncSizeBox(ComboBox<String> box, int w, int h) {
        String match = w + " x " + h;
        box.setValue(box.getItems().contains(match) ? match : CUSTOM);
    }

    /** A positive even number of pixels, at least {@link #MIN_SIDE} and at most {@link #MAX_SIDE}; the old one when it can't be. */
    static int evenSide(int typed, int fallback) {
        if (typed < MIN_SIDE) return fallback;
        int v = Math.min(typed, MAX_SIDE);
        return v - (v % 2);
    }

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int parse(TextField f, int fallback) {
        try {
            return Integer.parseInt(f.getText().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static TextField field(String value) {
        TextField tf = new TextField(value);
        tf.setPrefWidth(120);
        return tf;
    }
}
