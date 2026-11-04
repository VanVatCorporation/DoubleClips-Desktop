package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.auth.HiddenTemplates;
import com.vanvatcorporation.doubleclips.drive.BlockedAuthors;
import com.vanvatcorporation.doubleclips.i18n.I18n;
import com.vanvatcorporation.doubleclips.ui.components.AppUi;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Consumer;

/**
 * Settings > Hidden content: what the user hid, with a way back. Two lists, both kept on this computer only:
 * the asset authors hidden in the Assets tab, and the templates hidden in the template preview. "Show again" brings one back
 * (the Assets list redraws at once; the Templates feed reloads to bring a template back), "Show all" brings back the whole list.
 */
public final class HiddenContentWindow {

    private static HiddenContentWindow open;   // one at a time

    private final Stage stage = new Stage();
    private final VBox content = new VBox(18);
    private final Runnable authorsListener = () -> Platform.runLater(this::redraw);
    private final Consumer<Boolean> templatesListener = unhid -> Platform.runLater(this::redraw);

    /** @param onClosed runs when the window closes (Settings updates its counts) */
    public static void show(Window owner, Runnable onClosed) {
        if (open != null && open.stage.isShowing()) {
            open.stage.toFront();
            return;
        }
        open = new HiddenContentWindow(owner, onClosed);
        open.stage.show();
    }

    /** "2 asset authors and 1 template hidden", for the line in Settings. */
    public static String summary() {
        return I18n.f("Hidden: %1$d asset authors, %2$d templates", BlockedAuthors.names().size(), HiddenTemplates.all().size());
    }

    private HiddenContentWindow(Window owner, Runnable onClosed) {
        Label heading = new Label(I18n.t("Hidden content"));
        heading.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        Label note = new Label(I18n.t("What you hid stays hidden on this computer only. Show it again here."));
        note.setWrapText(true);
        note.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox root = new VBox(12, heading, note, scroll);
        root.setPadding(new Insets(20));
        root.getStyleClass().add("storage-dialog");

        stage.setTitle(I18n.t("Hidden content"));
        if (owner != null) stage.initOwner(owner);
        Scene scene = new Scene(root, 520, 560);
        AppUi.applyAppStyles(scene);
        stage.setScene(scene);

        BlockedAuthors.addListener(authorsListener);
        HiddenTemplates.addListener(templatesListener);
        stage.setOnHidden(e -> {
            BlockedAuthors.removeListener(authorsListener);
            HiddenTemplates.removeListener(templatesListener);
            if (open == this) open = null;
            if (onClosed != null) onClosed.run();
        });
        redraw();
    }

    private void redraw() {
        content.getChildren().clear();

        // ---- asset authors
        List<String> authors = BlockedAuthors.names();
        VBox authorBox = section(I18n.t("Asset authors"), authors.isEmpty() ? null : () -> BlockedAuthors.unblockAll());
        if (authors.isEmpty()) authorBox.getChildren().add(empty(I18n.t("No hidden asset authors.")));
        for (String name : authors) {
            authorBox.getChildren().add(row("@" + name, null, () -> BlockedAuthors.unblock(name)));
        }

        // ---- templates
        List<HiddenTemplates.Entry> templates = HiddenTemplates.all();
        VBox templateBox = section(I18n.t("Templates"), templates.isEmpty() ? null : () -> HiddenTemplates.unhideAll());
        if (templates.isEmpty()) templateBox.getChildren().add(empty(I18n.t("No hidden templates.")));
        for (HiddenTemplates.Entry e : templates) {
            String title = e.title == null || e.title.trim().isEmpty() ? e.id : e.title.trim();
            String by = e.author == null || e.author.trim().isEmpty() ? null : "@" + e.author.trim();
            templateBox.getChildren().add(row(title, by, () -> HiddenTemplates.unhide(e.id)));
        }

        content.getChildren().addAll(authorBox, templateBox);
    }

    /** A titled card; {@code showAll} null = no "Show all" button (nothing to show). */
    private VBox section(String title, Runnable showAll) {
        Label l = new Label(title);
        l.getStyleClass().add("storage-section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(8, l, spacer);
        head.setAlignment(Pos.CENTER_LEFT);
        if (showAll != null) {
            Button all = new Button(I18n.t("Show all"));
            all.getStyleClass().add("button-transparent");
            all.setOnAction(e -> showAll.run());
            head.getChildren().add(all);
        }
        VBox box = new VBox(8, head);
        box.getStyleClass().add("storage-card");
        box.setPadding(new Insets(14));
        return box;
    }

    private static Label empty(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 13px; -fx-text-fill: -color-fg-muted;");
        return l;
    }

    private Node row(String title, String subtitle, Runnable showAgain) {
        Label name = new Label(title);
        name.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        name.setWrapText(true);
        VBox text = new VBox(2, name);
        if (subtitle != null) {
            Label sub = new Label(subtitle);
            sub.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
            text.getChildren().add(sub);
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        Button again = new Button(I18n.t("Show again"));
        again.getStyleClass().add("app-button");
        again.setOnAction(e -> showAgain.run());
        HBox row = new HBox(10, text, again);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
