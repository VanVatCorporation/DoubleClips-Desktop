package com.vanvatcorporation.doubleclips.ui;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.ImageCursor;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * An eyedropper over the WHOLE desktop (any window, any screen), not just the preview: the cursor turns into a dropper,
 * a small loupe next to it shows the pixels under the pointer magnified with the colour's hex, a click takes the colour
 * and Esc (or a right click) cancels.
 *
 * <p>How: every screen is captured once with {@link Robot} BEFORE anything is shown, then a near-transparent overlay covers
 * each screen to catch the pointer; the loupe and the picked colour read the captured picture, so the overlay never tints
 * what is picked. It is a still of that moment: a video playing underneath is picked as it was when the dropper started.
 *
 * <p>macOS only lets an app capture other apps' windows after it is allowed in System Settings > Privacy & Security >
 * Screen Recording (the app that launched this one, e.g. the IDE or the terminal, when run from source); without that
 * the capture shows just the wallpaper and this app's own windows. Windows and X11 need nothing.
 */
final class ScreenEyedropper {

    /** Pixels either side of the pointer that the loupe shows (so 11 x 11). */
    private static final int LOUPE_RADIUS = 5;
    /** Screen pixels per magnified pixel in the loupe. */
    private static final int LOUPE_ZOOM = 10;
    private static final int LOUPE_SIDE = (2 * LOUPE_RADIUS + 1) * LOUPE_ZOOM;
    private static final int LOUPE_LABEL = 22;

    private static boolean active;

    private ScreenEyedropper() {}

    /** One screen as it looked when the dropper started. */
    private static final class Frozen {
        final Rectangle2D bounds;
        final WritableImage image;
        final double scale; // captured pixels per screen unit (2 on a retina screen)

        Frozen(Rectangle2D bounds, WritableImage image) {
            this.bounds = bounds;
            this.image = image;
            this.scale = bounds.getWidth() > 0 ? image.getWidth() / bounds.getWidth() : 1.0;
        }

        Color at(double localX, double localY) {
            int x = (int) Math.floor(localX * scale);
            int y = (int) Math.floor(localY * scale);
            x = Math.max(0, Math.min((int) image.getWidth() - 1, x));
            y = Math.max(0, Math.min((int) image.getHeight() - 1, y));
            return image.getPixelReader().getColor(x, y);
        }
    }

    /**
     * Starts the dropper; {@code onPicked} gets the colour on the FX thread once the pointer clicks. Returns false when the
     * screen can't be captured on this platform (nothing is waiting then: the caller can fall back to picking from the preview).
     * Must be called on the FX thread.
     */
    static boolean pick(Consumer<Color> onPicked) {
        if (active) return true;
        List<Frozen> frozen = new ArrayList<>();
        try {
            Robot robot = new Robot();
            for (Screen screen : Screen.getScreens()) {
                Rectangle2D bounds = screen.getBounds();
                frozen.add(new Frozen(bounds, robot.getScreenCapture(null, bounds, false)));
            }
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
        if (frozen.isEmpty()) return false;

        active = true;
        List<Stage> overlays = new ArrayList<>();
        Runnable close = () -> {
            for (Stage s : overlays) s.close();
            overlays.clear();
            active = false;
        };
        ImageCursor dropper = new ImageCursor(cursorImage(), 3, 28);

        for (Frozen f : frozen) {
            Pane root = new Pane();
            root.setStyle("-fx-background-color: rgba(0,0,0,0.01);"); // not fully clear: a fully clear window lets the clicks through
            Canvas loupe = new Canvas(LOUPE_SIDE, LOUPE_SIDE + LOUPE_LABEL);
            loupe.setMouseTransparent(true);
            loupe.setVisible(false);
            root.getChildren().add(loupe);

            Scene scene = new Scene(root, f.bounds.getWidth(), f.bounds.getHeight(), Color.TRANSPARENT);
            scene.setCursor(dropper);

            javafx.event.EventHandler<MouseEvent> follow = e -> drawLoupe(loupe, f, e.getX(), e.getY(), root.getWidth(), root.getHeight());
            root.setOnMouseMoved(follow);
            root.setOnMouseDragged(follow);
            root.setOnMousePressed(e -> {
                if (e.getButton() != MouseButton.PRIMARY) {
                    close.run();
                    return;
                }
                final Color picked = f.at(e.getX(), e.getY());
                close.run();
                Platform.runLater(() -> onPicked.accept(picked));
            });
            scene.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) close.run();
            });

            Stage stage = new Stage(StageStyle.TRANSPARENT);
            stage.setAlwaysOnTop(true);
            stage.setScene(scene);
            stage.setX(f.bounds.getMinX());
            stage.setY(f.bounds.getMinY());
            stage.setWidth(f.bounds.getWidth());
            stage.setHeight(f.bounds.getHeight());
            overlays.add(stage);
        }
        for (Stage s : overlays) s.show();
        if (!overlays.isEmpty()) overlays.get(0).requestFocus(); // Esc needs a focused window
        return true;
    }

    // ---- the loupe ---------------------------------------------------------------------------

    private static void drawLoupe(Canvas loupe, Frozen f, double x, double y, double paneWidth, double paneHeight) {
        loupe.setVisible(true);
        // beside the pointer, flipped to its other side near the screen's right / bottom edge
        double lx = x + 24, ly = y + 24;
        if (lx + loupe.getWidth() > paneWidth) lx = x - 24 - loupe.getWidth();
        if (ly + loupe.getHeight() > paneHeight) ly = y - 24 - loupe.getHeight();
        loupe.setLayoutX(lx);
        loupe.setLayoutY(ly);

        GraphicsContext gc = loupe.getGraphicsContext2D();
        PixelReader pr = f.image.getPixelReader();
        int cx = (int) Math.floor(x * f.scale), cy = (int) Math.floor(y * f.scale);
        int w = (int) f.image.getWidth(), h = (int) f.image.getHeight();
        for (int dy = -LOUPE_RADIUS; dy <= LOUPE_RADIUS; dy++) {
            for (int dx = -LOUPE_RADIUS; dx <= LOUPE_RADIUS; dx++) {
                int px = cx + dx, py = cy + dy;
                Color c = (px < 0 || py < 0 || px >= w || py >= h) ? Color.BLACK : pr.getColor(px, py);
                gc.setFill(c);
                gc.fillRect((dx + LOUPE_RADIUS) * LOUPE_ZOOM, (dy + LOUPE_RADIUS) * LOUPE_ZOOM, LOUPE_ZOOM, LOUPE_ZOOM);
            }
        }
        // the pixel that will be taken, outlined twice so it shows on light and dark
        double o = LOUPE_RADIUS * LOUPE_ZOOM;
        gc.setLineWidth(2);
        gc.setStroke(Color.BLACK);
        gc.strokeRect(o, o, LOUPE_ZOOM, LOUPE_ZOOM);
        gc.setLineWidth(1);
        gc.setStroke(Color.WHITE);
        gc.strokeRect(o + 1, o + 1, LOUPE_ZOOM - 2, LOUPE_ZOOM - 2);
        gc.setStroke(Color.web("#222222"));
        gc.strokeRect(0.5, 0.5, LOUPE_SIDE - 1, LOUPE_SIDE - 1);

        Color center = f.at(x, y);
        gc.setFill(Color.web("#222222"));
        gc.fillRect(0, LOUPE_SIDE, LOUPE_SIDE, LOUPE_LABEL);
        gc.setFill(Color.WHITE);
        gc.setFont(Font.font(12));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(String.format(Locale.ROOT, "#%02X%02X%02X", Math.round(center.getRed() * 255), Math.round(center.getGreen() * 255),
                Math.round(center.getBlue() * 255)), LOUPE_SIDE / 2.0, LOUPE_SIDE + 15);
    }

    // ---- the cursor --------------------------------------------------------------------------

    /** A small dropper, drawn here so no resource file is needed; its tip (the hotspot) is bottom left. */
    private static Image cursorImage() {
        Canvas canvas = new Canvas(32, 32);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setStroke(Color.BLACK);
        gc.setLineWidth(9);
        gc.strokeLine(27, 5, 12, 20);   // the body
        gc.setLineWidth(5);
        gc.strokeLine(12, 20, 4, 28);   // the tip
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(6);
        gc.strokeLine(27, 5, 12, 20);
        gc.setLineWidth(2);
        gc.strokeLine(12, 20, 4, 28);
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return canvas.snapshot(params, null);
    }
}
