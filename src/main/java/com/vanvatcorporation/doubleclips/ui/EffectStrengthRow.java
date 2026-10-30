package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.EffectCatalog;
import com.vanvatcorporation.doubleclips.data.editing.AnimatedProperty.KeyState;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.EffectTemplate;
import com.vanvatcorporation.doubleclips.data.editing.Keyframe;
import com.vanvatcorporation.doubleclips.data.editing.KeyframesSnapshot;
import com.vanvatcorporation.doubleclips.data.editing.VideoProperties.ValueType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Locale;

/**
 * The strength slider of an EFFECT CLIP (an adjustment layer, {@link Clip#effect}) with its keyframe diamond: the same
 * "strength" keyframe channel the clip's own effect uses ({@link ClipEffectSection}) and Android / iOS key, so the keys also
 * show as knots on the clip in the timeline and in the Keyframes list with their easing.
 *
 * <p>Not animated, the slider edits the effect's plain strength ({@code params["intensity"]}); animated, it edits the keyframe
 * at the playhead (adding one there when none is) and follows the playhead. Taking the last key off leaves the plain strength
 * at the value it had. Every change is ONE undo step that puts back the effect AND the keyframes.
 *
 * <p>The renderer already read an animated strength for effect clips (OpenGLEdit.strengthAt); this is its panel.
 */
final class EffectStrengthRow {

    private final PropertyContext context;

    EffectStrengthRow(PropertyContext context) {
        this.context = context;
    }

    VBox build(Clip clip, EffectCatalog.Style style) {
        float shownTime = shownTime();
        float value = EffectCatalog.clampIntensity(style, strengthAt(clip, shownTime));

        Label strengthLabel = new Label(style.intensityLabel);
        strengthLabel.getStyleClass().add("text-muted");
        strengthLabel.setStyle("-fx-font-size: 11px;");
        Label number = new Label(text(value));
        number.setStyle("-fx-font-size: 11px;");
        Slider slider = new Slider(style.intensityMin, style.intensityMax, value);
        slider.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(slider, Priority.ALWAYS);

        final boolean[] fromCode = {false};
        // Dragging only moves the number; the change is made (one undo step) when the slider is let go.
        slider.valueProperty().addListener((o, was, now) -> {
            number.setText(text(now.floatValue()));
            if (fromCode[0]) return;
            if (!slider.isValueChanging()) commitStrength(clip, style, now.floatValue());
        });
        slider.valueChangingProperty().addListener((o, was, changing) -> {
            if (!changing) commitStrength(clip, style, (float) slider.getValue());
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

        // Follows the playhead (scrubbing, playing): the strength there and the diamond's state.
        context.addPropertyUpdater(() -> {
            if (clip.effect == null) return;
            float time = shownTime();
            float v = EffectCatalog.clampIntensity(style, strengthAt(clip, time));
            if (!slider.isValueChanging()) {
                fromCode[0] = true;
                slider.setValue(v);
                fromCode[0] = false;
            }
            number.setText(text(v));
            diamond.setState(stateAt(clip, time));
            keyButton.setDisable(!playheadInside(clip, time));
        });

        HBox row = new HBox(8, slider, number, keyButton);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(4, strengthLabel, row);
    }

    // ---- reading -------------------------------------------------------------------------------

    private float shownTime() {
        return context.getTempTime() >= 0 ? context.getTempTime() : context.getCurrentTime();
    }

    private static boolean playheadInside(Clip clip, float time) {
        return time >= clip.startTime - 0.001f && time <= clip.startTime + clip.duration + 0.001f;
    }

    private static boolean animated(Clip clip) {
        return clip.keyframes != null && clip.keyframes.isAnimated(ValueType.Strength);
    }

    /** The strength at a global time: the keyframed value when some keyframe holds it, else the effect's own. */
    private static float strengthAt(Clip clip, float time) {
        if (animated(clip)) return clip.keyframes.getValueAtTime(clip, time, ValueType.Strength);
        return clip.effect == null ? 1f : clip.effect.intensity();
    }

    private static KeyState stateAt(Clip clip, float time) {
        return clip.keyframes == null ? KeyState.NONE : clip.keyframes.stateAt(clip, time, ValueType.Strength);
    }

    private static String text(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ---- editing -------------------------------------------------------------------------------

    /** What one undo step puts back: the clip's effect and every keyframe (the strength keys live in the clip's keyframes). */
    private static final class Snap {
        private final KeyframesSnapshot keys;
        private final EffectTemplate effect;

        private Snap(KeyframesSnapshot keys, EffectTemplate effect) {
            this.keys = keys;
            this.effect = effect;
        }

        static Snap of(Clip clip) {
            return new Snap(KeyframesSnapshot.of(clip), clip.effect == null ? null : new EffectTemplate(clip.effect));
        }

        void restore(Clip clip) {
            keys.restore(clip);
            clip.effect = effect == null ? null : new EffectTemplate(effect);
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

    /**
     * The slider's value. Not animated: the effect's plain strength. Animated: the keyframe at the playhead (one is added
     * there when none is), because a value that no keyframe holds would be overruled by the curve at once.
     */
    private void commitStrength(Clip clip, EffectCatalog.Style style, float value) {
        if (clip.effect == null) return;
        final float clamped = EffectCatalog.clampIntensity(style, value);
        final float time = context.getCurrentTime();
        if (Math.abs(clamped - EffectCatalog.clampIntensity(style, strengthAt(clip, time))) < 1e-4f) return; // the slider reports a change twice on release
        if (animated(clip)) {
            if (!playheadInside(clip, time)) return; // a keyframe can't be placed outside the clip
            final int fps = context.getVideoSettings().frameRate;
            commitChange(clip, "Change Effect Strength", () -> clip.keyframes.setKey(clip, time, ValueType.Strength, clamped, fps));
        } else {
            commitChange(clip, "Change Effect Strength", () -> clip.effect.setIntensity(clamped));
        }
    }

    /** The diamond: animate the strength from here on (a key at the playhead), or take the key there off. */
    private void toggleKey(Clip clip) {
        if (clip.effect == null || clip.keyframes == null) return;
        final float time = context.getCurrentTime();
        if (!playheadInside(clip, time)) return;
        final int fps = context.getVideoSettings().frameRate;
        commitChange(clip, "Keyframe Effect Strength", () -> {
            Keyframe at = clip.keyframes.getKeyframeAtTime(clip, time, ValueType.Strength);
            if (at != null) {
                float held = at.value == null ? clip.effect.intensity() : at.value.getValue(ValueType.Strength);
                boolean last = clip.keyframes.holderCount(ValueType.Strength) == 1;
                clip.keyframes.removeKey(clip, time, ValueType.Strength);
                if (last) clip.effect.setIntensity(held); // no longer animated: the strength stays where it was
            } else {
                clip.keyframes.setKey(clip, time, ValueType.Strength, strengthAt(clip, time), fps);
            }
        });
    }
}
