package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipBlendMode;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.Map;

/**
 * The "Blend" part of the properties panel (CapCut / iOS BlendPanel): a grid of tiles, one per {@link ClipBlendMode}.
 * Every tile shows what its mode does: a warm-to-cool backdrop with two bars, and a circle mixed into it by the mode
 * (drawn with the same maths the renderer uses). One click = one undo step.
 */
final class BlendSection {

    private static final int TILE_W = 72, TILE_H = 52;
    private static final Map<ClipBlendMode, WritableImage> PICTURES = new EnumMap<>(ClipBlendMode.class);

    private final PropertyContext context;

    BlendSection(PropertyContext context) {
        this.context = context;
    }

    VBox build(Clip clip) {
        FlowPane grid = new FlowPane(8, 10);
        ClipBlendMode current = clip.blendMode == null ? ClipBlendMode.NORMAL : clip.blendMode;
        for (ClipBlendMode mode : ClipBlendMode.values()) {
            boolean on = mode == current;
            ImageView picture = new ImageView(pictureOf(mode));
            picture.setFitWidth(TILE_W);
            picture.setFitHeight(TILE_H);
            Label caption = new Label(mode.title);
            caption.setStyle("-fx-font-size: 10px; -fx-text-fill: " + (on ? "white" : "#b8b8c0") + ";"
                    + (on ? " -fx-font-weight: bold;" : ""));
            VBox tile = new VBox(4, picture, caption);
            tile.setAlignment(Pos.CENTER);
            tile.setPrefWidth(TILE_W + 8);
            tile.setStyle("-fx-padding: 3; -fx-background-radius: 8; -fx-border-radius: 8; -fx-border-width: 2; -fx-cursor: hand;"
                    + "-fx-border-color: " + (on ? "#007AFF" : "transparent") + ";");
            tile.setOnMouseClicked(e -> choose(clip, mode));
            grid.getChildren().add(tile);
        }
        Label note = new Label("A blend mode mixes the clip with whatever is drawn below it, on lower tracks. "
                + "A clip with nothing below it is shown normally. Opacity still applies.");
        note.setWrapText(true);
        note.setStyle("-fx-font-size: 10px; -fx-text-fill: #8a8a92;");
        return new VBox(8, grid, note);
    }

    private void choose(Clip clip, ClipBlendMode mode) {
        ClipBlendMode before = clip.blendMode;
        ClipBlendMode after = mode == ClipBlendMode.NORMAL ? null : mode; // no key = normal, like iOS
        if (before == after) return;
        context.executePropertyChange("Blend: " + mode.title, () -> {
            clip.blendMode = after;
            changed();
        }, () -> {
            clip.blendMode = before;
            changed();
        });
    }

    private void changed() {
        context.refreshTimelineUI();
        context.saveProject();
        context.updatePropertiesPane();
    }

    // ---- the tile picture --------------------------------------------------------------------

    private static WritableImage pictureOf(ClipBlendMode mode) {
        return PICTURES.computeIfAbsent(mode, BlendSection::draw);
    }

    /** iOS's BlendTile: gradient backdrop with a white and a black translucent bar, and a gradient circle mixed in by the mode. */
    private static WritableImage draw(ClipBlendMode mode) {
        WritableImage image = new WritableImage(TILE_W, TILE_H);
        PixelWriter out = image.getPixelWriter();
        float cx = TILE_W / 2f, cy = TILE_H / 2f, radius = 17f;
        for (int y = 0; y < TILE_H; y++) {
            for (int x = 0; x < TILE_W; x++) {
                float t = ((x + 0.5f) / TILE_W + (y + 0.5f) / TILE_H) / 2f;
                float[] c = {0.95f + (0.20f - 0.95f) * t, 0.55f + (0.45f - 0.55f) * t, 0.25f + (0.90f - 0.25f) * t};
                if (x < 18) mix(c, 1f, 1f, 1f, 0.55f);        // white bar
                else if (x < 36) mix(c, 0f, 0f, 0f, 0.35f);   // black bar
                float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
                float cover = Math.max(0f, Math.min(1f, radius + 0.5f - (float) Math.sqrt(dx * dx + dy * dy)));
                if (cover > 0f) {
                    float v = Math.max(0f, Math.min(1f, (y + 0.5f - (cy - radius)) / (2 * radius)));
                    float[] top = {0.95f + (0.85f - 0.95f) * v, 0.85f + (0.25f - 0.85f) * v, 0.25f + (0.55f - 0.25f) * v};
                    c = mode.composite(c, top, cover);
                }
                out.setArgb(x, y, 0xFF000000 | (to8(c[0]) << 16) | (to8(c[1]) << 8) | to8(c[2]));
            }
        }
        return image;
    }

    private static void mix(float[] c, float r, float g, float b, float a) {
        c[0] += (r - c[0]) * a;
        c[1] += (g - c[1]) * a;
        c[2] += (b - c[2]) * a;
    }

    private static int to8(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255f)));
    }
}
