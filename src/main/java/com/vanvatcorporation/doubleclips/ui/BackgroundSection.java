package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.i18n.I18n;

import com.vanvatcorporation.doubleclips.data.editing.AnimatedProperty.KeyState;
import com.vanvatcorporation.doubleclips.data.editing.ChromaKey;
import com.vanvatcorporation.doubleclips.data.editing.ChromaKeyframes;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Keyframe;
import com.vanvatcorporation.doubleclips.data.editing.KeyframesSnapshot;
import com.vanvatcorporation.doubleclips.data.editing.VideoProperties.ValueType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The "Background" tab of a video / image clip (CapCut's "Remove BG"): the existing Remove Background cut-out switch, and
 * the clip's chroma keys, up to {@link ChromaKey#MAX_KEYS} of them (CapCut allows one): each a colour that goes transparent,
 * with its own intensity / shadow / feather edge / clean up edge, 0 ... {@link ChromaKey#SCALE_MAX}. See {@link ChromaKey}
 * for what each control does; GlCompositor does the keying, so only the OpenGL preview and export show it.
 *
 * <p>Each slider has a keyframe diamond like the other properties: it keys that slider at the playhead (the
 * {@link ValueType#chroma} channels, so the keys also show as knots on the clip and in the Keyframes list with their easing).
 * Not animated, a slider edits the key's plain value; animated, it edits the keyframe at the playhead (adding one there when
 * none is) and follows the playhead. A colour can be picked with the picker or taken from anywhere on the screen with the
 * eyedropper (see {@link ScreenEyedropper}).
 *
 * <p>Every change is ONE undo step that puts back the clip's whole key list AND its keyframes (copies, so a later edit never
 * touches what undo holds). While a slider is dragged the preview follows live without committing.
 */
final class BackgroundSection {

    private final PropertyContext context;

    BackgroundSection(PropertyContext context) {
        this.context = context;
    }

    VBox build(Clip clip) {
        context.cancelColorPick(); // a pick left waiting by the panel that this one replaces
        VBox box = new VBox(12);

        // ---- the AI cut-out switch (moved here from Properties)
        HBox cutout = new HBox(8);
        cutout.setAlignment(Pos.CENTER_LEFT);
        Label cutoutLabel = new Label(I18n.t("Remove Background"));
        cutoutLabel.getStyleClass().add("text-muted");
        cutoutLabel.setStyle("-fx-font-size: 11px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        ToggleButton toggle = new ToggleButton();
        toggle.getStyleClass().add("pill-toggle");
        toggle.setSelected(clip.removeBackground);
        toggle.setOnAction(e -> {
            final boolean newValue = toggle.isSelected();
            final boolean oldVal = clip.removeBackground;
            if (newValue == oldVal) return;
            context.executePropertyChange("Toggle Remove Background", () -> {
                clip.removeBackground = newValue;
                context.refreshTimelineUI();
                context.saveProject();
            }, () -> {
                clip.removeBackground = oldVal;
                context.refreshTimelineUI();
                context.saveProject();
            });
        });
        cutout.getChildren().addAll(cutoutLabel, spacer, toggle);
        box.getChildren().add(cutout);

        // ---- the chroma keys
        List<ChromaKey> keys = clip.chromaKeys == null ? new ArrayList<>() : clip.chromaKeys;
        Label title = new Label(I18n.t("Chroma key"));
        title.setStyle("-fx-font-weight: bold;");
        Label count = new Label(keys.size() + " / " + ChromaKey.MAX_KEYS);
        count.getStyleClass().add("text-muted");
        count.setStyle("-fx-font-size: 11px;");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Button add = new Button(I18n.t("+ Add"));
        add.getStyleClass().add("tool-button");
        add.setDisable(keys.size() >= ChromaKey.MAX_KEYS);
        add.setOnAction(e -> commit(clip, "Add Chroma Key", list -> {
            if (list.size() < ChromaKey.MAX_KEYS) list.add(new ChromaKey());
        }));
        HBox header = new HBox(8, title, count, gap, add);
        header.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(header);

        if (keys.isEmpty()) {
            box.getChildren().add(note("Add a chroma key, then pick the colour to remove (use the eyedropper on the preview). "
                    + "Add more keys to remove more colours."));
        }
        for (int i = 0; i < keys.size(); i++) box.getChildren().add(card(clip, keys.get(i), i));

        box.getChildren().add(note("The keys filter the clip's own picture before its colour grade and masks. Drawn by the OpenGL "
                + "engine (the FFmpeg export skips them)."));
        return box;
    }

    // ---- one key -------------------------------------------------------------------------------

    private VBox card(Clip clip, ChromaKey key, int index) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(8));
        card.setStyle("-fx-background-color: rgba(255,255,255,0.04); -fx-background-radius: 6;");

        ToggleButton on = new ToggleButton();
        on.getStyleClass().add("pill-toggle");
        on.setSelected(key.enabled);
        on.setOnAction(e -> {
            final boolean value = on.isSelected();
            commit(clip, value ? "Enable Chroma Key" : "Disable Chroma Key", list -> list.get(index).enabled = value);
        });
        Label name = new Label("Key " + (index + 1));
        name.setStyle("-fx-font-weight: bold;");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Button reset = new Button(I18n.t("Reset"));
        reset.getStyleClass().add("tool-button");
        reset.setOnAction(e -> commitChange(clip, "Reset Chroma Key", () -> {
            ChromaKeyframes.clearKey(clip, index); // the sliders are plain values again ...
            List<ChromaKey> list = ChromaKey.copyAll(clip.chromaKeys);
            if (list == null || index >= list.size()) return;
            ChromaKey k = list.get(index);
            k.intensity = ChromaKey.DEFAULT_INTENSITY; // ... at their defaults; the colour stays: it is what the key is for
            k.shadow = 0f;
            k.feather = 0f;
            k.cleanUpEdge = 0f;
            clip.chromaKeys = list;
        }));
        Button delete = new Button(I18n.t("Remove"));
        delete.getStyleClass().add("tool-button");
        delete.setOnAction(e -> commitChange(clip, "Remove Chroma Key", () -> {
            List<ChromaKey> list = ChromaKey.copyAll(clip.chromaKeys);
            if (list == null || index >= list.size()) return;
            list.remove(index);
            clip.chromaKeys = list.isEmpty() ? null : list;
            ChromaKeyframes.keyRemoved(clip, index); // the keys after it move down one place, and so do their keyframes
        }));
        HBox top = new HBox(8, on, name, gap, reset, delete);
        top.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(top);

        // colour: the picker, and the eyedropper that takes it from the preview
        Label colorLabel = new Label(I18n.t("Color"));
        colorLabel.getStyleClass().add("text-muted");
        colorLabel.setStyle("-fx-font-size: 11px;");
        colorLabel.setMinWidth(50);
        float[] rgb = key.rgb();
        ColorPicker picker = new ColorPicker(new Color(rgb[0], rgb[1], rgb[2], 1.0));
        picker.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(picker, Priority.ALWAYS);
        picker.setOnAction(e -> {
            Color c = picker.getValue();
            if (c == null) return;
            final String hex = ChromaKey.toHex((float) c.getRed(), (float) c.getGreen(), (float) c.getBlue());
            if (hex.equalsIgnoreCase(key.color)) return;
            commit(clip, "Change Chroma Key Color", list -> list.get(index).color = hex);
        });
        Button eyedropper = new Button(I18n.t("Pick"));
        eyedropper.getStyleClass().add("tool-button");
        Label hint = note("");
        hint.setVisible(false);
        hint.setManaged(false);
        eyedropper.setOnAction(e -> {
            Consumer<Color> take = c -> {
                if (c == null) return;
                final String hex = ChromaKey.toHex((float) c.getRed(), (float) c.getGreen(), (float) c.getBlue());
                commit(clip, "Pick Chroma Key Color", list -> list.get(index).color = hex);
            };
            // Anywhere on the screen; the preview is the fallback where the screen can't be captured.
            String text;
            if (ScreenEyedropper.pick(take)) {
                text = "Click any colour on the screen (Esc cancels). On macOS the app needs Screen Recording permission "
                        + "to see other windows.";
            } else if (context.pickColorFromPreview(take)) {
                text = "The screen can't be read here: click the colour in the preview…";
            } else {
                text = "Nothing to pick from: the screen and the preview aren't available.";
            }
            hint.setText(text);
            hint.setVisible(true);
            hint.setManaged(true);
        });
        HBox colorRow = new HBox(8, colorLabel, picker, eyedropper);
        colorRow.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().addAll(colorRow, hint);

        card.getChildren().add(slider(clip, index, ChromaKey.INTENSITY, I18n.t("Intensity"), "Change Chroma Key Intensity"));
        card.getChildren().add(slider(clip, index, ChromaKey.SHADOW, I18n.t("Shadow"), "Change Chroma Key Shadow"));
        card.getChildren().add(slider(clip, index, ChromaKey.FEATHER, I18n.t("Feather edge"), "Change Chroma Key Feather"));
        card.getChildren().add(slider(clip, index, ChromaKey.CLEAN_UP, I18n.t("Clean up edge"), "Change Chroma Key Clean Up"));
        card.setOpacity(key.enabled ? 1.0 : 0.55);
        return card;
    }

    /**
     * A 0 ... 100 slider with its number and its keyframe diamond. Dragging moves the preview live (see {@link #showLive});
     * the change is made, as one undo step, when it is let go: the key's plain value, or, when the slider is animated, the
     * keyframe at the playhead.
     */
    private VBox slider(Clip clip, int index, int param, String title, String undoName) {
        final ValueType type = ValueType.chroma(index, param);
        float shownTime = shownTime();
        float value = valueAt(clip, index, param, shownTime);

        Label label = new Label(title);
        label.getStyleClass().add("text-muted");
        label.setStyle("-fx-font-size: 11px;");
        Label number = new Label(String.valueOf(Math.round(value)));
        number.setStyle("-fx-font-size: 11px;");
        number.setMinWidth(30);
        number.setAlignment(Pos.CENTER_RIGHT);
        Slider slider = new Slider(0, ChromaKey.SCALE_MAX, value);
        slider.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(slider, Priority.ALWAYS);

        Button keyButton = new Button();
        keyButton.getStyleClass().add("tool-button");
        keyButton.setPadding(new Insets(4));
        keyButton.setMaxHeight(Double.MAX_VALUE);
        KeyDiamond diamond = new KeyDiamond();
        diamond.setState(stateAt(clip, type, shownTime));
        keyButton.setGraphic(diamond);
        keyButton.setDisable(!playheadInside(clip, context.getCurrentTime()));
        keyButton.setOnAction(e -> toggleKey(clip, index, param));

        final boolean[] fromCode = {false};
        final float[] shown = {Math.round(value)}; // what the preview was last given while dragging
        Runnable commit = () -> {
            float v = Math.round((float) slider.getValue());
            final float time = context.getCurrentTime();
            if (Math.abs(v - valueAt(clip, index, param, time)) < 0.5f) {
                if (Math.abs(v - shown[0]) >= 0.5f) { // dragged back to where it was: the preview must follow
                    shown[0] = v;
                    showLive(clip, index, param, v);
                }
                return;
            }
            shown[0] = v;
            if (clip.keyframes != null && clip.keyframes.isAnimated(type)) {
                if (!playheadInside(clip, time)) return; // a keyframe can't be placed outside the clip
                final int fps = context.getVideoSettings().frameRate;
                commitChange(clip, undoName, () -> clip.keyframes.setKey(clip, time, type, v, fps));
            } else {
                commitChange(clip, undoName, () -> {
                    List<ChromaKey> list = ChromaKey.copyAll(clip.chromaKeys);
                    if (list == null || index >= list.size()) return;
                    list.get(index).set(param, v);
                    clip.chromaKeys = list;
                });
            }
        };
        slider.valueProperty().addListener((o, was, now) -> {
            float v = Math.round(now.floatValue());
            number.setText(String.valueOf((int) v));
            if (fromCode[0]) return;
            if (slider.isValueChanging()) {
                if (Math.abs(v - shown[0]) >= 0.5f) { // only when the number moves: the worker renders the newest anyway
                    shown[0] = v;
                    showLive(clip, index, param, v);
                }
            } else {
                commit.run();
            }
        });
        slider.valueChangingProperty().addListener((o, was, changing) -> {
            if (!changing) commit.run();
        });

        // Follows the playhead (scrubbing, playing): the slider's value there and the diamond's state.
        context.addPropertyUpdater(() -> {
            if (clip.chromaKeys == null || index >= clip.chromaKeys.size()) return;
            float time = shownTime();
            if (!slider.isValueChanging()) {
                float v = valueAt(clip, index, param, time);
                fromCode[0] = true;
                slider.setValue(v);
                number.setText(String.valueOf(Math.round(v)));
                fromCode[0] = false;
                shown[0] = Math.round(v);
            }
            diamond.setState(stateAt(clip, type, time));
            keyButton.setDisable(!playheadInside(clip, time));
        });

        HBox row = new HBox(8, slider, number, keyButton);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(2, label, row);
    }

    /**
     * The preview shows the clip's key list with one slider changed, without committing it. The worker is given the sliders
     * as they are at the playhead (keyframes resolved) and lets go of the keyframed ones in its copy for the drag.
     */
    private void showLive(Clip clip, int index, int param, float value) {
        List<ChromaKey> work = ChromaKey.copyAll(clip.chromaKeys);
        if (work == null || index >= work.size()) return;
        float time = context.getCurrentTime();
        for (int i = 0; i < work.size(); i++) {
            for (int p = 0; p < ChromaKey.PARAM_COUNT; p++) work.get(i).set(p, valueAt(clip, i, p, time));
        }
        work.get(index).set(param, value);
        context.showLiveChromaKeys(clip, work);
    }

    // ---- keyframes -----------------------------------------------------------------------------

    private float shownTime() {
        return context.getTempTime() >= 0 ? context.getTempTime() : context.getCurrentTime();
    }

    private static boolean playheadInside(Clip clip, float time) {
        return time >= clip.startTime - 0.001f && time <= clip.startTime + clip.duration + 0.001f;
    }

    /** A slider at a global time: the keyframed value when some keyframe holds it, else the key's plain value. */
    private static float valueAt(Clip clip, int index, int param, float time) {
        return ChromaKey.valueAt(clip, index, param, time);
    }

    private static KeyState stateAt(Clip clip, ValueType type, float time) {
        return clip.keyframes == null ? KeyState.NONE : clip.keyframes.stateAt(clip, time, type);
    }

    /** The diamond: animate this slider from here on (a key at the playhead), or take the key there off. */
    private void toggleKey(Clip clip, int index, int param) {
        if (clip.keyframes == null || clip.chromaKeys == null || index >= clip.chromaKeys.size()) return;
        final float time = context.getCurrentTime();
        if (!playheadInside(clip, time)) return;
        final int fps = context.getVideoSettings().frameRate;
        final ValueType type = ValueType.chroma(index, param);
        commitChange(clip, "Keyframe Chroma Key", () -> {
            if (clip.chromaKeys == null || index >= clip.chromaKeys.size()) return;
            Keyframe at = clip.keyframes.getKeyframeAtTime(clip, time, type);
            if (at != null) {
                float held = at.value == null ? clip.chromaKeys.get(index).get(param) : at.value.getValue(type);
                boolean last = clip.keyframes.holderCount(type) == 1;
                clip.keyframes.removeKey(clip, time, type);
                if (last) clip.chromaKeys.get(index).set(param, held); // no longer animated: the slider stays where it was
            } else {
                clip.keyframes.setKey(clip, time, type, valueAt(clip, index, param, time), fps);
            }
        });
    }

    // ---- edits ---------------------------------------------------------------------------------

    /** What one undo step puts back: the clip's key list and every keyframe (the chroma keys live in the clip's keyframes). */
    private static final class Snap {
        private final KeyframesSnapshot keys;
        private final List<ChromaKey> list;

        private Snap(KeyframesSnapshot keys, List<ChromaKey> list) {
            this.keys = keys;
            this.list = list;
        }

        static Snap of(Clip clip) {
            return new Snap(KeyframesSnapshot.of(clip), ChromaKey.copyAll(clip.chromaKeys));
        }

        void restore(Clip clip) {
            keys.restore(clip); // the keyframes with their channels and values
            clip.chromaKeys = list == null || list.isEmpty() ? null : ChromaKey.copyAll(list);
        }
    }

    /** One undo step: runs {@code change} on the clip (again on redo); undo puts the key list and the keyframes back as they were. */
    private void commitChange(Clip clip, String undoName, Runnable change) {
        final Snap before = Snap.of(clip);
        context.executePropertyChange(undoName, () -> {
            change.run();
            changed();
        }, () -> {
            before.restore(clip);
            changed();
        });
    }

    /** One undo step: {@code edit} changes a copy of the clip's key list, which then replaces it. */
    private void commit(Clip clip, String undoName, Consumer<List<ChromaKey>> edit) {
        commitChange(clip, undoName, () -> {
            List<ChromaKey> work = ChromaKey.copyAll(clip.chromaKeys);
            if (work == null) work = new ArrayList<>();
            try {
                edit.accept(work);
            } catch (IndexOutOfBoundsException stale) {
                return; // the panel showed a list that has changed since
            }
            clip.chromaKeys = work.isEmpty() ? null : work;
        });
    }

    private void changed() {
        context.refreshTimelineUI();
        context.saveProject();
        javafx.application.Platform.runLater(context::updatePropertiesPane);
    }

    private static Label note(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("text-muted");
        l.setStyle("-fx-font-size: 11px;");
        return l;
    }
}
