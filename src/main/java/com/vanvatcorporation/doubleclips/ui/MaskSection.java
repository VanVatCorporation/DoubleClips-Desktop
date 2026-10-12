package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.editing.AnimatedProperty.KeyState;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipMask;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The "Mask" part of the properties panel (CapCut style): a tab per mask (Mask1 Rectangle, Mask2 Circle ... and a
 * plus to add one), the shape tiles, then the selected mask's settings - position, size, rotation, feather, corner,
 * how it combines with the masks before it, invert - each animatable with the same keyframe diamond as the clip's own
 * properties.
 *
 * <p>Every change is ONE undo step that swaps the clip's whole {@link Clip#masks} list (copies, so a later edit never
 * touches what undo holds). A parameter that has keyframes is edited AT THE PLAYHEAD (it keys there); one that has
 * none is edited as a plain value, exactly like the clip's Transform rows.
 */
final class MaskSection {

    private static final Color ICON = Color.web("#d8d8dc");

    private final PropertyContext context;
    private Clip lastClip;
    /** Which of the clip's masks the shape tiles and rows edit. */
    private int selected;

    MaskSection(PropertyContext context) {
        this.context = context;
    }

    VBox build(Clip clip) {
        if (clip != lastClip) { lastClip = clip; selected = 0; }
        List<ClipMask> masks = clip.masks == null ? new ArrayList<>() : clip.masks;
        if (selected >= masks.size()) selected = Math.max(0, masks.size() - 1);

        VBox box = new VBox(10);
        if (!masks.isEmpty()) box.getChildren().add(buildTabs(clip, masks));
        box.getChildren().add(buildShapeTiles(clip, masks));
        if (!masks.isEmpty()) box.getChildren().add(buildSettings(clip, masks.get(selected)));
        return box;
    }

    // ---- tabs: Mask1 Rectangle | Mask2 Circle | + ----------------------------------------

    private Node buildTabs(Clip clip, List<ClipMask> masks) {
        FlowPane row = new FlowPane(6, 6);
        for (int i = 0; i < masks.size(); i++) {
            final int index = i;
            ToggleButton tab = new ToggleButton("Mask" + (i + 1) + " " + masks.get(i).shape.title);
            tab.setSelected(i == selected);
            styleChip(tab, i == selected);
            tab.setOnAction(e -> { selected = index; context.updatePropertiesPane(); });
            row.getChildren().add(tab);
        }
        Button add = new Button("+");
        add.getStyleClass().add("tool-button");
        add.setDisable(masks.size() >= ClipMask.MAX_MASKS);
        add.setOnAction(e -> {
            int newIndex = masks.size();
            edit(clip, "Add Mask", list -> {
                ClipMask m = new ClipMask(ClipMask.Shape.CIRCLE);
                m.mode = ClipMask.Mode.ADD;
                list.add(m);
            });
            selected = newIndex;
            context.updatePropertiesPane();
        });
        row.getChildren().add(add);
        return row;
    }

    private static void styleChip(ToggleButton b, boolean on) {
        b.setStyle("-fx-background-radius: 14; -fx-padding: 4 10 4 10; -fx-font-size: 11px; -fx-text-fill: white;"
                + "-fx-background-color: " + (on ? "#007AFF" : "#3a3a40") + ";");
    }

    // ---- the shape tiles --------------------------------------------------------------------

    private Node buildShapeTiles(Clip clip, List<ClipMask> masks) {
        FlowPane grid = new FlowPane(8, 8);
        ClipMask.Shape current = masks.isEmpty() ? null : masks.get(selected).shape;
        for (ClipMask.Shape shape : ClipMask.Shape.values()) {
            VBox tile = new VBox(4, new StackPane(icon(shape)), caption(shape.title));
            tile.setAlignment(Pos.CENTER);
            tile.setPadding(new Insets(8, 6, 6, 6));
            tile.setPrefSize(74, 70);
            boolean on = shape == current;
            tile.setStyle("-fx-background-color: #2a2a2e; -fx-background-radius: 8; -fx-border-radius: 8; -fx-border-width: 2;"
                    + "-fx-border-color: " + (on ? "#007AFF" : "transparent") + "; -fx-cursor: hand;");
            tile.setOnMouseClicked(e -> {
                if (masks.isEmpty()) {
                    edit(clip, "Add Mask", list -> list.add(new ClipMask(shape)));
                    selected = 0;
                } else {
                    final int index = selected;
                    edit(clip, "Change Mask Shape", list -> list.get(index).changeShape(shape));
                }
                context.updatePropertiesPane();
            });
            grid.getChildren().add(tile);
        }
        return grid;
    }

    private static Label caption(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 10px; -fx-text-fill: #b8b8c0;");
        return l;
    }

    /** A 30 x 30 picture of a shape, drawn from JavaFX shapes (no icon font needed). */
    private static Node icon(ClipMask.Shape shape) {
        javafx.scene.layout.Pane pane = new javafx.scene.layout.Pane();
        pane.setMinSize(30, 30);
        pane.setPrefSize(30, 30);
        pane.setMaxSize(30, 30);
        Shape s;
        switch (shape) {
            case LINEAR: {
                // a half-filled square cut by a straight edge
                Rectangle half = new Rectangle(3, 3, 24, 12);
                half.setFill(ICON);
                Line edge = new Line(0, 15, 30, 15);
                edge.setStroke(ICON);
                edge.setStrokeWidth(1.5);
                pane.getChildren().addAll(half, edge);
                return pane;
            }
            case MIRROR: {
                Rectangle band = new Rectangle(3, 9, 24, 12);
                band.setFill(ICON);
                Line a = new Line(0, 9, 30, 9), b = new Line(0, 21, 30, 21);
                a.setStroke(ICON); b.setStroke(ICON);
                pane.getChildren().addAll(band, a, b);
                return pane;
            }
            case CIRCLE:
                s = new Circle(15, 15, 12);
                break;
            case RECTANGLE:
                s = new Rectangle(3, 6, 24, 18);
                break;
            case HEART: {
                Polygon p = new Polygon();
                for (int i = 0; i < 40; i++) {
                    double t = i / 40.0 * 2 * Math.PI;
                    double x = 16 * Math.pow(Math.sin(t), 3);
                    double y = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t);
                    p.getPoints().addAll(15 + x / 16 * 13, 15 - (y + 2.6) / 14.4 * 13);
                }
                s = p;
                break;
            }
            default: {
                Polygon p = new Polygon();
                for (int k = 0; k < 10; k++) {
                    double angle = -Math.PI / 2 + k * Math.PI / 5;
                    double r = (k % 2 == 0 ? 1.0 : 0.42) * 14;
                    p.getPoints().addAll(15 + Math.cos(angle) * r, 15 + Math.sin(angle) * r);
                }
                s = p;
                break;
            }
        }
        s.setFill(ICON);
        pane.getChildren().add(s);
        return pane;
    }

    // ---- the selected mask's settings ----------------------------------------------------------

    private Node buildSettings(Clip clip, ClipMask mask) {
        final int index = selected;
        VBox rows = new VBox(10);

        List<ClipMask.Param> params = new ArrayList<>();
        params.add(ClipMask.Param.X);
        params.add(ClipMask.Param.Y);
        if (mask.shape.hasWidth) params.add(ClipMask.Param.WIDTH);
        if (mask.shape.hasHeight) params.add(ClipMask.Param.HEIGHT);
        params.add(ClipMask.Param.ROTATION);
        params.add(ClipMask.Param.FEATHER);
        if (mask.shape == ClipMask.Shape.RECTANGLE) params.add(ClipMask.Param.ROUNDNESS);
        for (ClipMask.Param p : params) rows.getChildren().add(buildParamRow(clip, index, p, mask.shape));

        if (index > 0) {
            ComboBox<ClipMask.Mode> modeBox = new ComboBox<>();
            modeBox.getItems().addAll(ClipMask.Mode.values());
            modeBox.setValue(mask.mode);
            modeBox.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(ClipMask.Mode m) { return m == null ? "" : m.title; }
                @Override public ClipMask.Mode fromString(String s) { return null; }
            });
            modeBox.setOnAction(e -> {
                ClipMask.Mode chosen = modeBox.getValue();
                if (chosen == null || chosen == mask.mode) return;
                edit(clip, "Change Mask Mode", list -> list.get(index).mode = chosen);
                context.updatePropertiesPane();
            });
            Label lbl = new Label("Combine with previous");
            lbl.getStyleClass().add("text-muted");
            lbl.setStyle("-fx-font-size: 11px;");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox modeRow = new HBox(8, lbl, spacer, modeBox);
            modeRow.setAlignment(Pos.CENTER_LEFT);
            rows.getChildren().add(modeRow);
        }

        Label invertLbl = new Label("Invert");
        invertLbl.getStyleClass().add("text-muted");
        invertLbl.setStyle("-fx-font-size: 11px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        ToggleButton invert = new ToggleButton();
        invert.getStyleClass().add("pill-toggle");
        invert.setSelected(mask.invert);
        invert.setOnAction(e -> {
            boolean on = invert.isSelected();
            edit(clip, "Invert Mask", list -> list.get(index).invert = on);
            context.updatePropertiesPane();
        });
        HBox invertRow = new HBox(8, invertLbl, spacer, invert);
        invertRow.setAlignment(Pos.CENTER_LEFT);
        rows.getChildren().add(invertRow);

        Button delete = new Button("Delete mask");
        delete.getStyleClass().add("tool-button");
        delete.setOnAction(e -> {
            edit(clip, "Delete Mask", list -> list.remove(index));
            selected = Math.max(0, index - 1);
            context.updatePropertiesPane();
        });
        rows.getChildren().add(delete);
        return rows;
    }

    private float localTime(Clip clip, float globalTime) {
        return Math.max(0f, Math.min(globalTime - clip.startTime, clip.duration));
    }

    private boolean playheadInside(Clip clip, float time) {
        return time >= clip.startTime - 0.001f && time <= clip.startTime + clip.duration + 0.001f;
    }

    private static String number(float v) {
        return v == (long) v ? String.format("%d", (long) v)
                : String.format("%.2f", v).replaceAll("0*$", "").replaceAll("\\.$", "");
    }

    /** One parameter: a value field (the value AT THE PLAYHEAD) and its keyframe diamond. */
    private HBox buildParamRow(Clip clip, int index, ClipMask.Param p, ClipMask.Shape shape) {
        String label = p.titleFor(shape);
        ClipMask mask = clip.masks.get(index);

        Label lbl = new Label(label);
        lbl.getStyleClass().add("text-muted");
        lbl.setStyle("-fx-font-size: 11px;");
        TextField tf = new TextField(number(mask.valueAt(p, localTime(clip, context.getCurrentTime()))));
        tf.getStyleClass().add("editor-textfield");
        VBox field = new VBox(4, lbl, tf);
        HBox.setHgrow(field, Priority.ALWAYS);

        Consumer<String> commit = text -> {
            try {
                float value = Float.parseFloat(text.trim());
                float time = context.getCurrentTime();
                float local = localTime(clip, time);
                ClipMask now = clip.masks.get(index);
                float old = now.valueAt(p, local);
                float clamped = Math.max(p.min, Math.min(p.max, value));
                if (clamped == old) return;
                if (now.isAnimated(p) && !playheadInside(clip, time)) return; // nowhere to put a keyframe
                int fps = context.getVideoSettings().frameRate;
                edit(clip, "Change Mask " + label, list -> {
                    ClipMask m = list.get(index);
                    if (m.isAnimated(p)) m.setKey(p, local, clamped, fps);
                    else p.set(m, clamped);
                });
                context.updatePropertiesPane();
            } catch (Exception ignored) {
                // not a number: the field is put back on the next refresh
            }
        };
        tf.setOnAction(e -> commit.accept(tf.getText()));
        tf.focusedProperty().addListener((obs, was, now) -> { if (!now) commit.accept(tf.getText()); });

        Button keyButton = new Button();
        keyButton.getStyleClass().add("tool-button");
        keyButton.setPadding(new Insets(4));
        keyButton.setMaxHeight(Double.MAX_VALUE);
        KeyDiamond diamond = new KeyDiamond();
        diamond.setState(mask.stateAt(p, localTime(clip, context.getCurrentTime())));
        keyButton.setGraphic(diamond);
        keyButton.setDisable(!playheadInside(clip, context.getCurrentTime()));
        keyButton.setOnAction(e -> {
            float time = context.getCurrentTime();
            if (!playheadInside(clip, time)) return;
            float local = localTime(clip, time);
            int fps = context.getVideoSettings().frameRate;
            edit(clip, "Keyframe Mask " + label, list -> list.get(index).toggleKey(p, local, fps));
            context.updatePropertiesPane();
        });

        // Follows the playhead while it moves (scrubbing, playing): the number and the diamond of the moment.
        context.addPropertyUpdater(() -> {
            if (clip.masks == null || index >= clip.masks.size()) return;
            float time = context.getTempTime() >= 0 ? context.getTempTime() : context.getCurrentTime();
            float local = localTime(clip, time);
            ClipMask m = clip.masks.get(index);
            if (!tf.isFocused()) tf.setText(number(m.valueAt(p, local)));
            KeyState state = m.stateAt(p, local);
            diamond.setState(state);
            keyButton.setDisable(!playheadInside(clip, time));
        });

        return new HBox(8, field, keyButton);
    }

    // ---- editing: one undo step per change ------------------------------------------------------

    /** Runs {@code change} on a copy of the clip's masks and records the swap as one undo step (nothing when nothing changed). */
    private void edit(Clip clip, String name, Consumer<List<ClipMask>> change) {
        List<ClipMask> before = clip.copyOfMasks();
        List<ClipMask> after = clip.copyOfMasks();
        if (after == null) after = new ArrayList<>();
        change.accept(after);
        if (after.isEmpty()) after = null;
        if (java.util.Objects.equals(before, after)) return;
        List<ClipMask> redoState = copy(after);
        List<ClipMask> undoState = copy(before);
        context.executePropertyChange(name, () -> {
            clip.masks = copy(redoState);
            changed();
        }, () -> {
            clip.masks = copy(undoState);
            changed();
        });
    }

    private void changed() {
        context.refreshTimelineUI();
        context.saveProject();
        context.updatePropertiesPane();
    }

    private static List<ClipMask> copy(List<ClipMask> src) {
        if (src == null) return null;
        List<ClipMask> out = new ArrayList<>();
        for (ClipMask m : src) out.add(new ClipMask(m));
        return out;
    }
}
