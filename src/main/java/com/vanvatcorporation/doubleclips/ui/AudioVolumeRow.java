package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.editing.AnimatedProperty.KeyState;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Keyframe;
import com.vanvatcorporation.doubleclips.data.editing.KeyframesSnapshot;
import com.vanvatcorporation.doubleclips.data.editing.VideoProperties.ValueType;
import com.vanvatcorporation.doubleclips.data.editing.VolumeCurve;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Locale;

/**
 * The "Audio Volume" row of a video / audio clip's Properties tab, with the keyframe diamond beside it (the iOS design):
 * the loudness over the clip, 1 = as recorded, 0 = silent.
 *
 * <p>Not animated, the field edits the clip's plain volume ({@code videoProperties.valueVolume}, the same value iOS and Android use). The diamond keys the volume at the playhead
 * (the "volume" keyframe channel, the same one iOS and Android use, so the keys also show as knots on the clip and in the
 * Keyframes list with their easing); animated, the field edits the key at the playhead (adding one there when none is) and
 * follows the playhead. Taking the last key off leaves the plain volume at the value it had.
 *
 * <p>Every change is ONE undo step that puts back the plain volume and all keyframes.
 */
final class AudioVolumeRow {

    private final PropertyContext context;

    AudioVolumeRow(PropertyContext context) {
        this.context = context;
    }

    VBox build(Clip clip) {
        float shownTime = shownTime();
        Label label = new Label("Audio Volume");
        label.getStyleClass().add("text-muted");
        label.setStyle("-fx-font-size: 11px;");

        TextField field = new TextField(number(valueAt(clip, shownTime)));
        field.getStyleClass().add("editor-textfield");
        field.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(field, Priority.ALWAYS);
        field.setOnAction(e -> commitValue(clip, field));
        field.focusedProperty().addListener((o, was, focused) -> {
            if (!focused) commitValue(clip, field);
        });

        Button keyButton = new Button();
        keyButton.getStyleClass().add("tool-button");
        keyButton.setPadding(new Insets(4));
        keyButton.setMaxHeight(Double.MAX_VALUE);
        KeyDiamond diamond = new KeyDiamond();
        diamond.setState(stateAt(clip, shownTime));
        keyButton.setGraphic(diamond);
        keyButton.setDisable(!playheadInside(clip, context.getCurrentTime()));
        keyButton.setOnAction(e -> toggleKey(clip));

        // Follows the playhead (scrubbing, playing): the volume there and the diamond's state.
        context.addPropertyUpdater(() -> {
            float time = shownTime();
            if (!field.isFocused()) field.setText(number(valueAt(clip, time)));
            diamond.setState(stateAt(clip, time));
            keyButton.setDisable(!playheadInside(clip, time));
        });

        HBox row = new HBox(8, field, keyButton);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(4, label, row);
    }

    // ---- reading -------------------------------------------------------------------------------

    private float shownTime() {
        return context.getTempTime() >= 0 ? context.getTempTime() : context.getCurrentTime();
    }

    /** The volume at a global time as it is typed: the keyframed value when some keyframe holds it, else the plain one. */
    private static float valueAt(Clip clip, float time) {
        if (VolumeCurve.isAnimated(clip)) return clip.keyframes.getValueAtTime(clip, time, ValueType.Volume);
        return VolumeCurve.plain(clip);
    }

    private static KeyState stateAt(Clip clip, float time) {
        if (!VolumeCurve.isAnimated(clip)) return KeyState.NONE; // old "all" keyframes don't animate the volume
        return clip.keyframes.stateAt(clip, time, ValueType.Volume);
    }

    private static boolean playheadInside(Clip clip, float time) {
        return time >= clip.startTime - 0.001f && time <= clip.startTime + clip.duration + 0.001f;
    }

    // ---- editing -------------------------------------------------------------------------------

    /** What one undo step puts back: the plain volume and every keyframe (the volume keys live in the clip's keyframes). */
    private static final class Snap {
        private final KeyframesSnapshot keys;
        private final boolean volumeKeyed;

        private Snap(KeyframesSnapshot keys, boolean volumeKeyed) {
            this.keys = keys;
            this.volumeKeyed = volumeKeyed;
        }

        static Snap of(Clip clip) {
            return new Snap(KeyframesSnapshot.of(clip), clip.volumeKeyed);
        }

        void restore(Clip clip) {
            keys.restore(clip); // also puts videoProperties (the plain volume) and every keyframe's channels back
            clip.volumeKeyed = volumeKeyed;
        }
    }

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

    private void changed() {
        context.refreshTimelineUI();
        context.saveProject();
        javafx.application.Platform.runLater(context::updatePropertiesPane);
    }

    private static void setPlain(Clip clip, float value) {
        clip.videoProperties.valueVolume = value;
    }

    /** The typed volume. Not animated: the plain volume. Animated: the key at the playhead (one is added when there is none). */
    private void commitValue(Clip clip, TextField field) {
        final float time = context.getCurrentTime();
        float typed;
        try {
            typed = Float.parseFloat(field.getText().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            field.setText(number(valueAt(clip, shownTime()))); // not a number: show what is kept
            return;
        }
        if (Float.isNaN(typed) || Float.isInfinite(typed)) {
            field.setText(number(valueAt(clip, shownTime())));
            return;
        }
        final float value = Math.max(0f, typed);
        if (Math.abs(value - valueAt(clip, time)) < 1e-4f) {
            field.setText(number(valueAt(clip, shownTime()))); // unchanged (or a negative typed: put back)
            return;
        }
        if (VolumeCurve.isAnimated(clip)) {
            if (!playheadInside(clip, time)) {
                field.setText(number(valueAt(clip, shownTime()))); // a key can't be placed outside the clip
                return;
            }
            final int fps = context.getVideoSettings().frameRate;
            commitChange(clip, "Change Audio Volume", () -> clip.keyframes.setKey(clip, time, ValueType.Volume, value, fps));
        } else {
            commitChange(clip, "Change Audio Volume", () -> setPlain(clip, value));
        }
    }

    /** The diamond: animate the volume from here on (a key at the playhead), or take the key there off. */
    private void toggleKey(Clip clip) {
        if (clip.keyframes == null) return;
        final float time = context.getCurrentTime();
        if (!playheadInside(clip, time)) return;
        final int fps = context.getVideoSettings().frameRate;
        commitChange(clip, "Keyframe Audio Volume", () -> {
            if (!VolumeCurve.isAnimated(clip)) {
                // Start animating the volume from the plain value. Old "all properties" keyframes never meant a volume, so they
                // let go of it first; the clip then follows only the keys made here.
                float plain = VolumeCurve.plain(clip);
                for (Keyframe k : new java.util.ArrayList<>(clip.keyframes.keyframes)) {
                    if (!k.holds(ValueType.Volume)) continue;
                    k.setHolds(ValueType.Volume, false);
                    if (k.holdsNothing()) clip.keyframes.keyframes.remove(k);
                }
                clip.volumeKeyed = true;
                clip.keyframes.setKey(clip, time, ValueType.Volume, plain, fps);
                return;
            }
            Keyframe at = clip.keyframes.getKeyframeAtTime(clip, time, ValueType.Volume);
            if (at != null) {
                float held = at.value == null ? VolumeCurve.plain(clip) : at.value.getValue(ValueType.Volume);
                boolean last = clip.keyframes.holderCount(ValueType.Volume) == 1;
                clip.keyframes.removeKey(clip, time, ValueType.Volume);
                if (last) {                      // no longer animated: the volume stays where it was
                    setPlain(clip, held);
                    clip.volumeKeyed = false;
                }
            } else {
                clip.keyframes.setKey(clip, time, ValueType.Volume, valueAt(clip, time), fps);
            }
        });
    }

    private static String number(float v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.isEmpty() ? "0" : s;
    }
}
