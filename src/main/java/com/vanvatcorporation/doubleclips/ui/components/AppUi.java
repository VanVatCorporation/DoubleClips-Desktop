package com.vanvatcorporation.doubleclips.ui.components;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Small builders for the app's own look (the classes live at the end of style.css). Icons are coloured by those
 * classes: never with an inline setStyle("-fx-icon-color: ..."), which makes the icon lose its font (a striped box).
 */
public final class AppUi {
    private AppUi() {}

    /** A rounded button; {@code primary} = the blue call-to-action. {@code icon} may be null. */
    public static Button button(String text, Ikon icon, boolean primary) {
        Button b = icon == null ? new Button(text) : new Button(text, new FontIcon(icon));
        b.getStyleClass().add(primary ? "app-button-primary" : "app-button");
        return b;
    }

    /** A rounded square holding an icon (file rows, empty states). */
    public static StackPane tile(Ikon icon, boolean accent, boolean large) {
        StackPane t = new StackPane(new FontIcon(icon));
        t.getStyleClass().add("storage-tile");
        if (accent) t.getStyleClass().add("storage-tile-accent");
        if (large) t.getStyleClass().add("storage-tile-large");
        return t;
    }

    /** A new window's scene does not inherit the main window's style.css: give it the same sheets. */
    public static void applyAppStyles(Scene scene) {
        Stage primary = DoubleClipsDesktop.getInstance().getPrimaryStage();
        if (primary != null && primary.getScene() != null) scene.getStylesheets().addAll(primary.getScene().getStylesheets());
    }
}
