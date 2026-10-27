package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.EffectCatalog;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.EffectTemplate;
import com.vanvatcorporation.doubleclips.ui.components.TileGridDialog;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The "Effect" tab of the properties panel: an effect for THIS clip only ({@link Clip#clipEffect}), as opposed to an
 * effect clip, which filters every layer below it. The same styles and the same tile picker (with its live GPU previews)
 * as an effect clip, plus a "None" tile to take the effect off, a start / duration for when it shows inside the clip
 * (duration 0 = until the clip ends, so the window keeps following the clip when it is trimmed) and the strength.
 *
 * <p>Every change is ONE undo step that swaps the clip's whole effect (a copy, so a later edit never touches what undo holds).
 */
final class ClipEffectSection {

    private static final String NONE = "none";

    private final PropertyContext context;
    private Image noneTile;

    ClipEffectSection(PropertyContext context) {
        this.context = context;
    }

    VBox build(Clip clip) {
        VBox box = new VBox(10);
        EffectTemplate fx = clip.clipEffect;
        EffectCatalog.Style style = fx == null ? null : EffectCatalog.find(fx.style);

        Label label = new Label("Clip effect");
        label.getStyleClass().add("text-muted");
        label.setStyle("-fx-font-size: 11px;");
        String shown = fx == null ? "None" : (style != null ? style.title : fx.style + " (not installed)");
        Button open = new Button(shown + "   \u25B8");
        open.getStyleClass().add("tool-button");
        open.setMaxWidth(Double.MAX_VALUE);
        open.setOnAction(e -> showBrowser(clip, open));
        box.getChildren().add(new VBox(4, label, open));

        if (fx == null) {
            box.getChildren().add(note("Pick an effect that touches only this clip. The clips on the other tracks stay as they are."));
            return box;
        }
        if (style == null) {
            box.getChildren().add(note("This effect comes from a newer version of the app, so it isn't drawn here. "
                    + "It is kept in the project until you pick another."));
            return box;
        }

        // ---- when it shows inside the clip
        float start = fx.offset == null ? 0f : Math.max(0f, fx.offset.floatValue());
        if (start >= clip.duration) start = 0f;
        float room = Math.max(0f, clip.duration - start);
        float length = fx.duration > 0f ? Math.min(fx.duration, room) : room;
        final float shownStart = start, shownLength = length;
        TextField startField = field(shownStart);
        TextField lengthField = field(shownLength);
        Runnable applyTiming = () -> {
            if (!commitTiming(clip, parse(startField, shownStart), parse(lengthField, shownLength))) {
                startField.setText(trim(shownStart));   // nothing changed (or the typed value was out of range): show what is kept
                lengthField.setText(trim(shownLength));
            }
        };
        wire(startField, applyTiming);
        wire(lengthField, applyTiming);
        box.getChildren().addAll(row("Start (s)", startField), row("Duration (s)", lengthField));

        // ---- strength
        if (style.hasIntensity()) {
            float value = EffectCatalog.clampIntensity(style, fx.intensity());
            Label strengthLabel = new Label(style.intensityLabel);
            strengthLabel.getStyleClass().add("text-muted");
            strengthLabel.setStyle("-fx-font-size: 11px;");
            Label number = new Label(strengthText(value));
            number.setStyle("-fx-font-size: 11px;");
            Slider slider = new Slider(style.intensityMin, style.intensityMax, value);
            slider.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(slider, Priority.ALWAYS);
            // Dragging only moves the number; the change is made (one undo step) when the slider is let go.
            slider.valueProperty().addListener((o, was, now) -> {
                number.setText(strengthText(now.floatValue()));
                if (!slider.isValueChanging()) commitStrength(clip, now.floatValue());
            });
            slider.valueChangingProperty().addListener((o, was, changing) -> {
                if (!changing) commitStrength(clip, (float) slider.getValue());
            });
            HBox strengthRow = new HBox(8, slider, number);
            strengthRow.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(new VBox(4, strengthLabel, strengthRow));
        }

        box.getChildren().add(note("Filters this clip only, after its mask and before its blend mode. Drawn by the OpenGL engine "
                + "(the FFmpeg export skips it)."));
        return box;
    }

    // ---- edits ---------------------------------------------------------------------------------

    /** One undo step: the clip's effect becomes {@code after} (null = none). */
    private void commit(Clip clip, String undoName, EffectTemplate after) {
        final EffectTemplate before = clip.clipEffect == null ? null : new EffectTemplate(clip.clipEffect);
        final EffectTemplate next = after == null ? null : new EffectTemplate(after);
        context.executePropertyChange(undoName, () -> {
            clip.clipEffect = next == null ? null : new EffectTemplate(next);
            changed();
        }, () -> {
            clip.clipEffect = before == null ? null : new EffectTemplate(before);
            changed();
        });
    }

    private void changed() {
        context.refreshTimelineUI();
        context.saveProject();
        javafx.application.Platform.runLater(context::updatePropertiesPane);
    }

    private void commitStrength(Clip clip, float value) {
        if (clip.clipEffect == null) return;
        EffectCatalog.Style style = EffectCatalog.find(clip.clipEffect.style);
        if (style == null) return;
        float clamped = EffectCatalog.clampIntensity(style, value);
        if (Math.abs(clamped - clip.clipEffect.intensity()) < 1e-4f) return; // the slider reports a change twice on release
        EffectTemplate after = new EffectTemplate(clip.clipEffect);
        after.setIntensity(clamped);
        commit(clip, "Change Clip Effect Strength", after);
    }

    /**
     * The typed start / duration. A window that reaches the end of the clip is stored as duration 0 = "until the clip's
     * end", so it keeps following the clip when the clip is trimmed or stretched.
     */
    private boolean commitTiming(Clip clip, float start, float length) {
        if (clip.clipEffect == null) return false;
        if (Float.isNaN(start) || start < 0f) start = 0f;
        if (start >= clip.duration) start = 0f; // it would never show: back to the beginning
        float room = clip.duration - start;
        float storedLength = (length <= 0f || length >= room - 0.001f) ? 0f : length;
        Double storedStart = start <= 0f ? null : Double.valueOf(start);
        EffectTemplate current = clip.clipEffect;
        float currentStart = current.offset == null ? 0f : current.offset.floatValue();
        if (Math.abs(currentStart - start) < 1e-4f && Math.abs(current.duration - storedLength) < 1e-4f) return false;
        EffectTemplate after = new EffectTemplate(current);
        after.offset = storedStart;
        after.duration = storedLength;
        commit(clip, "Change Clip Effect Timing", after);
        return true;
    }

    // ---- the picker ----------------------------------------------------------------------------

    private void showBrowser(Clip clip, Button owner) {
        List<TileGridDialog.Tile> tiles = new ArrayList<>();
        tiles.add(new TileGridDialog.Tile(NONE, "None", null, Collections.<String>emptySet(), this::noneTile, false));
        for (EffectCatalog.Style st : EffectCatalog.styles()) {
            Set<String> engines = new LinkedHashSet<>();
            for (String engine : st.engines()) {
                engines.add(EffectCatalog.ENGINE_FFMPEG.equals(engine) ? TileGridDialog.ENGINE_FFMPEG : TileGridDialog.ENGINE_OPENGL);
            }
            tiles.add(new TileGridDialog.Tile(st.key, st.title, st.author, engines,
                    () -> context.tilePreviews().effectStill(st.key, 1f),
                    false,
                    () -> context.tilePreviews().effectLoop(st.key, 1f)));
        }
        List<TileGridDialog.Section> sections = new ArrayList<>();
        sections.add(new TileGridDialog.Section("Clip effect", tiles));
        Window window = owner.getScene() != null ? owner.getScene().getWindow() : null;
        String selected = clip.clipEffect == null ? NONE : clip.clipEffect.style;
        TileGridDialog.show(window, "Clip effect", sections, selected, new TileGridDialog.Listener() {
            @Override
            public void onPick(TileGridDialog.Tile tile) {
                if (NONE.equals(tile.id)) {
                    if (clip.clipEffect != null) commit(clip, "Remove Clip Effect", null);
                    return;
                }
                EffectCatalog.Style chosen = EffectCatalog.find(tile.id);
                if (chosen == null || (clip.clipEffect != null && chosen.key.equals(clip.clipEffect.style))) return;
                // A new effect starts at its own default strength; the window it shows in stays as it was.
                EffectTemplate after = clip.clipEffect == null ? new EffectTemplate(chosen.key, 0f, 0f) : new EffectTemplate(clip.clipEffect);
                after.style = chosen.key;
                after.params = null;
                commit(clip, "Change Clip Effect", after);
            }

            @Override
            public boolean onDelete(TileGridDialog.Tile tile) {
                return false;
            }
        }, null, null);
    }

    private Image noneTile() {
        if (noneTile == null) {
            int side = 96;
            WritableImage image = new WritableImage(side, side);
            PixelWriter out = image.getPixelWriter();
            for (int y = 0; y < side; y++) for (int x = 0; x < side; x++) out.setArgb(x, y, 0xFF26262B);
            noneTile = image;
        }
        return noneTile;
    }

    // ---- small pieces --------------------------------------------------------------------------

    private static Label note(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("text-muted");
        l.setStyle("-fx-font-size: 11px;");
        return l;
    }

    private static HBox row(String title, TextField field) {
        Label l = new Label(title);
        l.getStyleClass().add("text-muted");
        l.setStyle("-fx-font-size: 11px;");
        l.setMinWidth(90);
        field.setPrefWidth(80);
        HBox r = new HBox(8, l, field);
        r.setAlignment(Pos.CENTER_LEFT);
        return r;
    }

    private static TextField field(float seconds) {
        return new TextField(trim(seconds));
    }

    /** Applies when Enter is pressed or the field loses focus. */
    private static void wire(TextField field, Runnable apply) {
        field.setOnAction(e -> apply.run());
        field.focusedProperty().addListener((o, was, focused) -> {
            if (!focused) apply.run();
        });
    }

    private static float parse(TextField field, float fallback) {
        try {
            return Float.parseFloat(field.getText().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String trim(float v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.isEmpty() ? "0" : s;
    }

    private static String strengthText(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
