package com.vanvatcorporation.doubleclips.ui.overlays;

import com.vanvatcorporation.doubleclips.data.TemplateData;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcTo;
import javafx.scene.shape.Circle;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Line;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.QuadCurveTo;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;

import com.vanvatcorporation.doubleclips.TemplateAvailability;

import java.util.function.Consumer;

/**
 * "How should this template be rendered?": two square choices like the New Project popup - the rabbit (OpenGL: renders
 * the template's timeline, fast) and the turtle (FFmpeg: runs the template's own command, slower). Desktop and Android
 * have both engines; a template can offer one or both:
 * <ul>
 *   <li>no (usable) timeline: the rabbit is grayed out and cannot be picked;</li>
 *   <li>no (usable) FFmpeg command: the turtle is grayed out.</li>
 * </ul>
 * What is usable comes from {@link TemplateAvailability}, which really fetches the command and parses the timeline; the
 * reason it gives is the grayed-out choice's tooltip.
 * The art is drawn with shapes; drop {@code engine_rabbit.png} / {@code engine_turtle.png} into {@code resources/icons}
 * to use pictures instead.
 */
public class TemplateEnginePickerOverlay extends StackPane {

    public enum Engine { OPENGL, FFMPEG }

    private final Consumer<Engine> onChoose;
    private final Runnable onCancel;

    public TemplateEnginePickerOverlay(TemplateAvailability.Result avail, Consumer<Engine> onChoose, Runnable onCancel) {
        this.onChoose = onChoose;
        this.onCancel = onCancel;
        getStyleClass().add("modal-overlay");

        VBox dialog = new VBox(22);
        dialog.getStyleClass().add("creation-dialog");
        dialog.setPadding(new Insets(30, 32, 28, 32));
        dialog.setMaxSize(560, Region_USE_PREF);
        dialog.setAlignment(Pos.TOP_LEFT);

        Label title = new Label("Choose how to render");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        Label sub = new Label("This template can be rendered in different ways. The result looks the same.");
        sub.getStyleClass().add("text-muted");
        sub.setWrapText(true);

        boolean glOk = avail.timelineOk;
        boolean ffOk = avail.ffmpegOk;
        Node rabbit = card(art("/icons/engine_rabbit.png", Engine.OPENGL), "OpenGL", "Fast",
                "Renders the template's timeline on the graphics card, like the editor's preview.",
                glOk, avail.timelineWhyNot,
                () -> choose(Engine.OPENGL));
        Node turtle = card(art("/icons/engine_turtle.png", Engine.FFMPEG), "FFmpeg", "Slower",
                "Runs the template's original FFmpeg command.",
                ffOk, avail.ffmpegWhyNot,
                () -> choose(Engine.FFMPEG));

        HBox cards = new HBox(16, rabbit, turtle);
        cards.setAlignment(Pos.CENTER);
        dialog.getChildren().addAll(title, sub, cards);
        getChildren().add(dialog);

        setFocusTraversable(true);
        sceneProperty().addListener((o, a, scene) -> { if (scene != null) javafx.application.Platform.runLater(this::requestFocus); });
        setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) { onCancel.run(); e.consume(); } });
        setOnMouseClicked(e -> { if (e.getTarget() == this) onCancel.run(); });
    }

    private static final double Region_USE_PREF = javafx.scene.layout.Region.USE_PREF_SIZE;

    private void choose(Engine engine) {
        onChoose.accept(engine);
    }

    // ---- one square choice ------------------------------------------------------------------------------

    private Node card(Node art, String name, String speed, String description, boolean enabled, String whyNot, Runnable pick) {
        Label nameLbl = new Label(name);
        nameLbl.getStyleClass().add("option-card-title");
        Label speedLbl = new Label(speed);
        speedLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + (enabled ? "-color-accent-fg" : "-color-fg-muted") + ";");
        Label desc = new Label(description);
        desc.setWrapText(true);
        desc.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        desc.setAlignment(Pos.TOP_CENTER);
        desc.getStyleClass().add("text-muted");
        desc.setStyle("-fx-font-size: 12px;");

        VBox card = new VBox(8, art, new VBox(1, nameLbl, speedLbl), desc);
        ((VBox) card.getChildren().get(1)).setAlignment(Pos.CENTER);
        card.setAlignment(Pos.TOP_CENTER);
        card.setMinSize(232, 262);
        card.setPrefSize(232, 262);
        card.setMaxSize(232, 262);
        card.getStyleClass().add(enabled ? "option-card" : "option-card-disabled");

        if (enabled) {
            card.setOnMouseClicked(e -> pick.run());
        } else {
            // Grayed out: no colour, faded, no hover, no click - and it says why.
            Label why = new Label("Not available for this template");
            why.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
            card.getChildren().add(why);
            ColorAdjust gray = new ColorAdjust();
            gray.setSaturation(-1);
            card.setEffect(gray);
            card.setOpacity(0.42);
            card.setPickOnBounds(true);                          // still hit-testable, so the tooltip shows; the click does nothing
            Tooltip.install(card, new Tooltip(whyNot));
        }
        return card;
    }

    // ---- the art --------------------------------------------------------------------------------------------

    private static Node art(String resource, Engine engine) {
        java.net.URL url = TemplateEnginePickerOverlay.class.getResource(resource);
        if (url != null) {
            ImageView iv = new ImageView(new Image(url.toExternalForm(), 120, 120, true, true));
            return new StackPane(iv);
        }
        Group g = engine == Engine.OPENGL ? rabbit() : turtle();
        StackPane box = new StackPane(g);
        box.setMinSize(120, 120);
        box.setPrefSize(120, 120);
        box.setMaxSize(120, 120);
        return box;
    }

    private static Ellipse ellipse(double cx, double cy, double rx, double ry, Color fill) {
        Ellipse e = new Ellipse(cx, cy, rx, ry);
        e.setFill(fill);
        return e;
    }

    private static Circle circle(double cx, double cy, double r, Color fill) {
        return new Circle(cx, cy, r, fill);
    }

    /** A front-facing sitting rabbit with speed lines on the left (the fast one). 120 x 120. */
    private static Group rabbit() {
        Color fur = Color.web("#f6f2ea"), shade = Color.web("#e4ddd0"), pink = Color.web("#ff9db1"), ink = Color.web("#2b2b33");
        Group g = new Group();
        // speed lines
        for (double[] l : new double[][]{{2, 52, 20}, {0, 64, 26}, {4, 76, 18}}) {
            Line s = new Line(l[0], l[1], l[0] + l[2], l[1]);
            s.setStroke(Color.web("#007AFF"));
            s.setStrokeWidth(3.4);
            s.setStrokeLineCap(StrokeLineCap.ROUND);
            g.getChildren().add(s);
        }
        Ellipse earL = ellipse(48, 30, 9, 27, fur), earR = ellipse(76, 30, 9, 27, fur);
        earL.setRotate(-10);
        earR.setRotate(10);
        Ellipse inL = ellipse(48, 32, 4.5, 19, pink), inR = ellipse(76, 32, 4.5, 19, pink);
        inL.setRotate(-10);
        inR.setRotate(10);
        Ellipse body = ellipse(64, 99, 28, 21, fur);
        Ellipse belly = ellipse(64, 104, 16, 13, shade);
        Ellipse head = ellipse(64, 64, 29, 26, fur);
        Circle cheekL = circle(46, 71, 5.5, Color.web("#ff9db1", 0.55)), cheekR = circle(82, 71, 5.5, Color.web("#ff9db1", 0.55));
        Circle eyeL = circle(53, 61, 3.6, ink), eyeR = circle(75, 61, 3.6, ink);
        Circle shineL = circle(54.2, 59.8, 1.1, Color.WHITE), shineR = circle(76.2, 59.8, 1.1, Color.WHITE);
        Ellipse nose = ellipse(64, 70, 4.2, 3.2, pink);
        Path mouth = new Path(new MoveTo(64, 73), new QuadCurveTo(60, 79, 55, 75), new MoveTo(64, 73), new QuadCurveTo(68, 79, 73, 75));
        mouth.setStroke(ink);
        mouth.setStrokeWidth(1.6);
        mouth.setStrokeLineCap(StrokeLineCap.ROUND);
        mouth.setFill(null);
        Ellipse pawL = ellipse(52, 116, 9, 4.5, fur), pawR = ellipse(76, 116, 9, 4.5, fur);
        g.getChildren().addAll(earL, earR, inL, inR, pawL, pawR, body, belly, head, cheekL, cheekR, eyeL, eyeR, shineL, shineR, nose, mouth);
        return g;
    }

    /** A side-view turtle with a patterned shell, walking right (the slow one). 120 x 120. */
    private static Group turtle() {
        Color skin = Color.web("#8fd694"), skinDark = Color.web("#6fbf78"), shell = Color.web("#3f9b63"), line = Color.web("#2d7a4b"), ink = Color.web("#2b2b33");
        Group g = new Group();
        Polygon tail = new Polygon(14, 88, 30, 80, 30, 92);
        tail.setFill(skinDark);
        Rectangle legBack = new Rectangle(34, 86, 15, 20), legFront = new Rectangle(72, 86, 15, 20);
        for (Rectangle r : new Rectangle[]{legBack, legFront}) { r.setArcWidth(10); r.setArcHeight(10); r.setFill(skinDark); }
        Ellipse head = ellipse(101, 76, 16, 14, skin);
        Circle eye = circle(106, 72, 3, ink);
        Circle shine = circle(107, 71, 0.9, Color.WHITE);
        Path smile = new Path(new MoveTo(103, 82), new QuadCurveTo(109, 86, 114, 81));
        smile.setStroke(ink);
        smile.setStrokeWidth(1.6);
        smile.setStrokeLineCap(StrokeLineCap.ROUND);
        smile.setFill(null);

        // shell: a dome from (20,90) over to (94,90)
        Path dome = new Path(new MoveTo(20, 90), new ArcTo(37, 40, 0, 94, 90, false, true), new ClosePath());
        dome.setFill(shell);
        dome.setStroke(line);
        dome.setStrokeWidth(2.4);
        Rectangle plastron = new Rectangle(20, 86, 74, 8);
        plastron.setArcWidth(8);
        plastron.setArcHeight(8);
        plastron.setFill(Color.web("#ead9a6"));
        Path pattern = new Path(
                new MoveTo(57, 52), new QuadCurveTo(57, 70, 57, 86),
                new MoveTo(38, 62), new QuadCurveTo(46, 74, 44, 86),
                new MoveTo(76, 62), new QuadCurveTo(68, 74, 70, 86),
                new MoveTo(30, 76), new QuadCurveTo(57, 66, 84, 76));
        pattern.setStroke(line);
        pattern.setStrokeWidth(2);
        pattern.setStrokeLineCap(StrokeLineCap.ROUND);
        pattern.setFill(null);
        g.getChildren().addAll(tail, legBack, legFront, head, eye, shine, smile, dome, pattern, plastron);
        return g;
    }
}
