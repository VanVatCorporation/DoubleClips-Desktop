package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.ClipProcessingTracker;
import com.vanvatcorporation.doubleclips.data.editing.AnimatedProperty.KeyState;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipMotionBlur;
import com.vanvatcorporation.doubleclips.data.editing.ClipOpticalFlow;
import com.vanvatcorporation.doubleclips.data.editing.KeyframesSnapshot;
import com.vanvatcorporation.doubleclips.data.editing.VideoProperties.ValueType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;

/**
 * The "Motion" tab of a video clip (CapCut's Optical flow and Motion blur): both are switches with their own controls.
 *
 * <ul>
 * <li><b>Optical flow</b>: the frame rate the clip's in-between frames are made at, typed (any positive number, no upper limit).
 *     When it is above the project's frame rate a dialog says the extra frames can't be shown, with OK (keep) and Change (the
 *     project's frame rate becomes the highest optical flow rate among the clips, through the same path as the video settings).</li>
 * <li><b>Motion blur</b>: Blur and Blend (0 ... 100, each with the keyframe diamond like every other slider: the
 *     {@link ValueType#MotionBlur} / {@link ValueType#MotionBlend} channels), the streak direction, and how many times the
 *     blur is applied (typed, 1 or more).</li>
 * </ul>
 *
 * <p>Every change is ONE undo step that puts back the clip's switches, its plain slider values and its keyframes. While a
 * feature is being pre-processed (see {@link ClipProcessingTracker}) its header shows "Processing xx%".
 */
final class MotionSection {

    private final PropertyContext context;
    /** The "Processing xx%" labels of the section on screen, and the clip it was built for. */
    private Label opticalBusy, blurBusy;
    private Clip shown;
    /** A dialog about the project frame rate being lower is open or about to open. */
    private boolean asking;

    MotionSection(PropertyContext context) {
        this.context = context;
    }

    VBox build(Clip clip) {
        shown = clip;
        VBox box = new VBox(16);
        box.getChildren().add(opticalFlowBlock(clip));
        box.getChildren().add(new javafx.scene.control.Separator());
        box.getChildren().add(motionBlurBlock(clip));
        refreshProcessing();
        return box;
    }

    /** Updates the "Processing xx%" lines from the tracker (called on the FX thread whenever a job moves). */
    void refreshProcessing() {
        if (shown == null) return;
        setBusy(opticalBusy, ClipProcessingTracker.get().find(shown, ClipProcessingTracker.OPTICAL_FLOW));
        setBusy(blurBusy, ClipProcessingTracker.get().find(shown, ClipProcessingTracker.MOTION_BLUR));
    }

    private static void setBusy(Label label, ClipProcessingTracker.Job job) {
        if (label == null) return;
        boolean busy = job != null;
        label.setVisible(busy);
        label.setManaged(busy);
        if (busy) label.setText("Processing " + ClipProcessingTracker.percent(job.progress()));
    }

    // ---- optical flow ---------------------------------------------------------------------------

    private VBox opticalFlowBlock(Clip clip) {
        VBox b = new VBox(10);
        opticalBusy = busyLabel();
        b.getChildren().add(header("Optical flow", clip.hasOpticalFlow(), opticalBusy, now -> setOpticalFlow(clip, now)));
        if (!clip.hasOpticalFlow()) {
            b.getChildren().add(note("Makes smooth in-between frames when the clip is slowed down, by following how things move."));
            return b;
        }
        TextField fps = new TextField(ClipOpticalFlow.format(clip.opticalFlow.effectiveFps()));
        fps.setPrefWidth(90);
        // Enter and losing focus both apply the box, and the panel is rebuilt after the first: the second call must see the value
        // the first one stored (read live from the clip, not remembered when the box was made), or it would apply it twice.
        wire(fps, () -> {
            if (clip.opticalFlow == null) return;
            float live = clip.opticalFlow.effectiveFps();
            float typed = parseFloat(fps, Float.NaN);
            if (Float.isNaN(typed) || Float.isInfinite(typed) || typed <= 0f) {
                fps.setText(ClipOpticalFlow.format(live)); // not a usable frame rate: show what is kept
                return;
            }
            if (Math.abs(typed - live) < 1e-4f) return;
            Window owner = windowOf(fps);
            commitChange(clip, "Change Optical Flow Frame Rate", () -> clip.opticalFlow.fps = typed);
            askAboutProjectRate(clip, owner);
        });
        b.getChildren().add(row("Frame rate (fps)", fps));
        b.getChildren().add(note("The project plays at " + context.getVideoSettings().frameRate + " fps. The FFmpeg export makes these frames "
                + "with minterpolate; the preview and the OpenGL export follow."));
        return b;
    }

    private void setOpticalFlow(Clip clip, boolean on) {
        if (on == clip.hasOpticalFlow()) return;
        Window owner = windowOfPane();
        commitChange(clip, on ? "Switch On Optical Flow" : "Switch Off Optical Flow", () -> {
            if (clip.opticalFlow == null) clip.opticalFlow = new ClipOpticalFlow();
            clip.opticalFlow.enabled = on;
        });
        if (on) askAboutProjectRate(clip, owner);
    }

    /**
     * The clip's optical flow asks for more frames per second than the project plays: says so, with OK (leave the project) and
     * Change (the project takes the highest optical flow rate among all the clips, rounded up to a whole frame rate).
     */
    private void askAboutProjectRate(Clip clip, Window owner) {
        if (asking) return; // one dialog at a time, whatever fires twice
        final int project = context.getVideoSettings().frameRate;
        if (!clip.hasOpticalFlow() || !clip.opticalFlow.exceedsProject(project)) return;
        final float wanted = ClipOpticalFlow.highestFps(context.getTimeline().getAllClips());
        final int target = Math.max(project + 1, (int) Math.ceil(wanted - 0.001f));
        asking = true;
        javafx.application.Platform.runLater(() -> {
            try {
                ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                ButtonType change = new ButtonType("Change to " + target + " fps", ButtonBar.ButtonData.OK_DONE);
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                        "This clip's optical flow makes " + ClipOpticalFlow.format(clip.opticalFlow.effectiveFps())
                                + " frames per second, but the project plays at " + project + " fps, so the extra frames will not be shown.\n\n"
                                + "Change the project to " + target + " fps, the highest optical flow rate among your clips? "
                                + "Cancel keeps it at " + project + " fps.",
                        change, cancel);
                alert.setTitle("Optical flow");
                alert.setHeaderText("The project frame rate is lower");
                if (owner != null) alert.initOwner(owner);
                Optional<ButtonType> answer = alert.showAndWait();
                if (answer.isPresent() && answer.get() == change) context.changeProjectFrameRate(target);
            } finally {
                asking = false;
            }
        });
    }

    // ---- motion blur ----------------------------------------------------------------------------

    private VBox motionBlurBlock(Clip clip) {
        VBox b = new VBox(12);
        blurBusy = busyLabel();
        b.getChildren().add(header("Motion blur", clip.hasMotionBlur(), blurBusy, now -> setMotionBlur(clip, now)));
        if (!clip.hasMotionBlur()) {
            b.getChildren().add(note("Streaks moving things along their path, using the same flow between frames. "
                    + "It works on this clip only."));
            return b;
        }
        addKeyedSlider(b, clip, "Blur", ValueType.MotionBlur);
        addKeyedSlider(b, clip, "Blend", ValueType.MotionBlend);

        ComboBox<ClipMotionBlur.Direction> direction = new ComboBox<>();
        direction.getItems().addAll(ClipMotionBlur.Direction.values());
        direction.setValue(clip.motionBlur.direction());
        direction.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(direction, Priority.ALWAYS);
        direction.setOnAction(e -> {
            ClipMotionBlur.Direction now = direction.getValue();
            if (now == null || now == clip.motionBlur.direction()) return;
            commitChange(clip, "Change Motion Blur Direction", () -> clip.motionBlur.setDirection(now));
        });
        b.getChildren().add(row("Direction", direction));

        TextField passes = new TextField(String.valueOf(clip.motionBlur.effectivePasses()));
        passes.setPrefWidth(70);
        wire(passes, () -> {
            if (clip.motionBlur == null) return;
            int live = clip.motionBlur.effectivePasses(); // live, for the same reason as the frame rate box
            float typed = parseFloat(passes, Float.NaN);
            int times = Float.isNaN(typed) || Float.isInfinite(typed) ? -1 : Math.round(typed);
            if (times < 1) {
                passes.setText(String.valueOf(live)); // not a whole number of 1 or more: show what is kept
                return;
            }
            if (times == live) return;
            commitChange(clip, "Change Motion Blur Speed", () -> clip.motionBlur.passes = times);
        });
        b.getChildren().add(row("Speed (times)", passes));
        b.getChildren().add(note("Speed is how many times the blur is applied: more times, more obvious streaks. "
                + "Drawn by the OpenGL engine."));
        return b;
    }

    private void setMotionBlur(Clip clip, boolean on) {
        if (on == clip.hasMotionBlur()) return;
        commitChange(clip, on ? "Switch On Motion Blur" : "Switch Off Motion Blur", () -> {
            if (clip.motionBlur == null) clip.motionBlur = new ClipMotionBlur();
            clip.motionBlur.enabled = on;
        });
    }

    /** One 0 ... 100 slider with its number box and keyframe diamond (the same behaviour as the clip effect's strength). */
    private void addKeyedSlider(VBox box, Clip clip, String title, ValueType type) {
        final float shownTime = shownTime();
        final float value = clampScale(valueAt(clip, shownTime, type));
        Label label = new Label(title);
        label.getStyleClass().add("text-muted");
        label.setStyle("-fx-font-size: 11px;");

        Slider slider = new Slider(0, ClipMotionBlur.SCALE_MAX, value);
        slider.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(slider, Priority.ALWAYS);
        TextField number = new TextField(numberText(value));
        number.setPrefWidth(58);

        final boolean[] fromCode = {false};
        // Dragging only moves the number; the change is made (one undo step) when the slider is let go.
        slider.valueProperty().addListener((o, was, now) -> {
            number.setText(numberText(now.floatValue()));
            if (fromCode[0]) return;
            if (!slider.isValueChanging()) commitValue(clip, type, now.floatValue());
        });
        slider.valueChangingProperty().addListener((o, was, changing) -> {
            if (!changing) commitValue(clip, type, (float) slider.getValue());
        });
        wire(number, () -> {
            float typed = parseFloat(number, Float.NaN);
            if (Float.isNaN(typed)) {
                number.setText(numberText(clampScale(valueAt(clip, shownTime(), type))));
                return;
            }
            commitValue(clip, type, typed);
            number.setText(numberText(clampScale(valueAt(clip, shownTime(), type))));
        });

        Button keyButton = new Button();
        keyButton.getStyleClass().add("tool-button");
        keyButton.setPadding(new Insets(4));
        keyButton.setMaxHeight(Double.MAX_VALUE);
        KeyDiamond diamond = new KeyDiamond();
        diamond.setState(stateAt(clip, shownTime, type));
        keyButton.setGraphic(diamond);
        keyButton.setDisable(!playheadInside(clip, context.getCurrentTime()));
        keyButton.setOnAction(e -> toggleKey(clip, type, title));

        // Follows the playhead (scrubbing, playing): the value there and the diamond's state.
        context.addPropertyUpdater(() -> {
            if (!clip.hasMotionBlur()) return;
            float time = shownTime();
            float v = clampScale(valueAt(clip, time, type));
            if (!slider.isValueChanging()) {
                fromCode[0] = true;
                slider.setValue(v);
                fromCode[0] = false;
            }
            if (!number.isFocused()) number.setText(numberText(v));
            diamond.setState(stateAt(clip, time, type));
            keyButton.setDisable(!playheadInside(clip, time));
        });

        HBox sliderRow = new HBox(8, slider, number, keyButton);
        sliderRow.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(new VBox(4, label, sliderRow));
    }

    private float valueAt(Clip clip, float time, ValueType type) {
        if (clip.keyframes != null && clip.keyframes.isAnimated(type)) return clip.keyframes.getValueAtTime(clip, time, type);
        return clip.videoProperties.getValue(type);
    }

    private static KeyState stateAt(Clip clip, float time, ValueType type) {
        return clip.keyframes == null ? KeyState.NONE : clip.keyframes.stateAt(clip, time, type);
    }

    /**
     * The typed / slid value. Not animated: the plain value. Animated: the keyframe at the playhead (one is added there when
     * none is), because a value that no keyframe holds would be overruled by the curve at once.
     */
    private void commitValue(Clip clip, ValueType type, float value) {
        if (!clip.hasMotionBlur()) return;
        final float clamped = clampScale(value);
        final float time = context.getCurrentTime();
        if (Math.abs(clamped - clampScale(valueAt(clip, time, type))) < 1e-4f) return; // the slider reports a change twice on release
        if (clip.keyframes != null && clip.keyframes.isAnimated(type)) {
            if (!playheadInside(clip, time)) return; // a keyframe can't be placed outside the clip
            final int fps = context.getVideoSettings().frameRate;
            commitChange(clip, "Change " + channelTitle(type), () -> clip.keyframes.setKey(clip, time, type, clamped, fps));
        } else {
            commitChange(clip, "Change " + channelTitle(type), () -> clip.videoProperties.setValue(clamped, type));
        }
    }

    /** The diamond: animate the slider from here on (a key at the playhead), or take the key there off. */
    private void toggleKey(Clip clip, ValueType type, String title) {
        if (clip.keyframes == null) return;
        final float time = context.getCurrentTime();
        if (!playheadInside(clip, time)) return;
        final int fps = context.getVideoSettings().frameRate;
        commitChange(clip, "Keyframe " + channelTitle(type), () -> {
            if (clip.keyframes.getKeyframeAtTime(clip, time, type) != null) {
                clip.keyframes.removeKey(clip, time, type); // the last key leaves the value where it was, as a plain value
            } else {
                clip.keyframes.setKey(clip, time, type, valueAt(clip, time, type), fps);
            }
        });
    }

    private static String channelTitle(ValueType type) {
        return type == ValueType.MotionBlend ? "Motion Blend" : "Motion Blur";
    }

    // ---- undo ------------------------------------------------------------------------------------

    /** What one undo step puts back: the two switches, the plain slider values and every keyframe. */
    private static final class Snap {
        private final KeyframesSnapshot keys; // includes the clip's plain properties (the slider values)
        private final ClipOpticalFlow optical;
        private final ClipMotionBlur blur;

        private Snap(Clip clip) {
            this.keys = KeyframesSnapshot.of(clip);
            this.optical = clip.opticalFlow == null ? null : new ClipOpticalFlow(clip.opticalFlow);
            this.blur = clip.motionBlur == null ? null : new ClipMotionBlur(clip.motionBlur);
        }

        void restore(Clip clip) {
            keys.restore(clip);
            clip.opticalFlow = optical == null ? null : new ClipOpticalFlow(optical);
            clip.motionBlur = blur == null ? null : new ClipMotionBlur(blur);
        }
    }

    /** One undo step: runs {@code change} on the clip (again on redo); undo puts the switches, values and keyframes back as they were. */
    private void commitChange(Clip clip, String undoName, Runnable change) {
        final Snap before = new Snap(clip);
        context.executePropertyChange(undoName, () -> {
            change.run();
            changed();
        }, () -> {
            before.restore(clip);
            changed();
        });
    }

    private void changed() {
        context.refreshTimelineUI();
        context.saveProject();
        javafx.application.Platform.runLater(context::updatePropertiesPane);
    }

    // ---- small pieces ----------------------------------------------------------------------------

    private float shownTime() {
        return context.getTempTime() >= 0 ? context.getTempTime() : context.getCurrentTime();
    }

    private static boolean playheadInside(Clip clip, float time) {
        return time >= clip.startTime - 0.001f && time <= clip.startTime + clip.duration + 0.001f;
    }

    private static float clampScale(float v) {
        return Float.isNaN(v) ? 0f : Math.max(0f, Math.min(ClipMotionBlur.SCALE_MAX, v));
    }

    private static String numberText(float v) {
        return String.valueOf(Math.round(v));
    }

    private static Label busyLabel() {
        Label l = new Label();
        l.getStyleClass().add("text-muted");
        l.setStyle("-fx-font-size: 11px;");
        l.setVisible(false);
        l.setManaged(false);
        return l;
    }

    /** The section's title row: the name, "Processing xx%" while a job runs, and the switch. */
    private static HBox header(String title, boolean on, Label busy, java.util.function.Consumer<Boolean> onToggle) {
        Label name = new Label(title);
        name.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        ToggleButton toggle = new ToggleButton();
        toggle.getStyleClass().add("pill-toggle");
        toggle.setSelected(on);
        toggle.setOnAction(e -> onToggle.accept(toggle.isSelected()));
        HBox row = new HBox(8, name, spacer, busy, toggle);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label note(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("text-muted");
        l.setStyle("-fx-font-size: 11px;");
        return l;
    }

    private static HBox row(String title, Node control) {
        Label l = new Label(title);
        l.getStyleClass().add("text-muted");
        l.setStyle("-fx-font-size: 11px;");
        l.setMinWidth(100);
        HBox r = new HBox(8, l, control);
        r.setAlignment(Pos.CENTER_LEFT);
        return r;
    }

    /** Applies when Enter is pressed or the field loses focus. */
    private static void wire(TextField field, Runnable apply) {
        field.setOnAction(e -> apply.run());
        field.focusedProperty().addListener((o, was, focused) -> {
            if (!focused) apply.run();
        });
    }

    private static float parseFloat(TextField field, float fallback) {
        try {
            return Float.parseFloat(field.getText().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Window windowOf(Node node) {
        return node.getScene() == null ? null : node.getScene().getWindow();
    }

    /** The window the panel is in (the section has no node of its own to ask once it is rebuilt). */
    private Window windowOfPane() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w.isFocused()) return w;
        }
        return null;
    }
}
