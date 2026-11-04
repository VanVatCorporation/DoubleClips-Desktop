package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.drive.DoubleClipsDrive;
import com.vanvatcorporation.doubleclips.drive.DriveError;
import com.vanvatcorporation.doubleclips.drive.DriveNode;
import com.vanvatcorporation.doubleclips.i18n.I18n;
import com.vanvatcorporation.doubleclips.ui.components.AppUi;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Picks a FILE from the user's OpenEditz folder on Van Vat Drive (iOS: OpenEditzFilePicker): folders open on a double click, a file is
 * chosen with the Choose button or a double click. Used to post a file that is already in Drive.
 */
public final class OpenEditzPickerWindow {

    private final Stage stage = new Stage();
    private final Consumer<DriveNode> onPick;
    private final List<DriveNode> trail = new ArrayList<>();   // the folders entered, last = the one shown
    private final ListView<DriveNode> list = new ListView<>();
    private final Label title = new Label("OpenEditz");
    private final Label status = new Label();
    private final Button back = new Button();
    private final Button choose = new Button(I18n.t("Choose"));
    /** Bumped at every load: a late answer for a folder already left is dropped. */
    private int generation;

    public static void pick(Window owner, Consumer<DriveNode> onPick) {
        OpenEditzPickerWindow w = new OpenEditzPickerWindow(owner, onPick);
        w.stage.show();
        w.load();
    }

    private OpenEditzPickerWindow(Window owner, Consumer<DriveNode> onPick) {
        this.onPick = onPick;

        back.setGraphic(new FontIcon(MaterialDesignC.CHEVRON_LEFT));
        back.getStyleClass().add("button-transparent");
        back.setDisable(true);
        back.setOnAction(e -> {
            if (trail.isEmpty()) return;
            trail.remove(trail.size() - 1);
            load();
        });
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        HBox top = new HBox(8, back, title);
        top.setAlignment(Pos.CENTER_LEFT);

        list.setPlaceholder(new Label(""));
        list.setCellFactory(v -> new ListCell<DriveNode>() {
            @Override protected void updateItem(DriveNode node, boolean empty) {
                super.updateItem(node, empty);
                if (empty || node == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                FontIcon icon = new FontIcon(node.isFolder ? MaterialDesignF.FOLDER_OUTLINE : MaterialDesignF.FILE_OUTLINE);
                Label name = new Label(node.name);
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                HBox row = new HBox(10, icon, name, spacer);
                row.setAlignment(Pos.CENTER_LEFT);
                if (!node.isFolder) {
                    Label size = new Label(AssetUi.size(node.size));
                    size.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
                    row.getChildren().add(size);
                }
                setText(null);
                setGraphic(row);
            }
        });
        list.setOnMouseClicked(e -> { if (e.getClickCount() == 2) activate(); });
        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> updateButtons());
        VBox.setVgrow(list, Priority.ALWAYS);

        status.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        status.setWrapText(true);

        choose.getStyleClass().add("app-button-primary");
        choose.setDisable(true);
        choose.setOnAction(e -> activate());
        Button cancel = new Button(I18n.t("Cancel"));
        cancel.getStyleClass().add("app-button");
        cancel.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(10, status, spacer, cancel, choose);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, top, list, buttons);
        root.setPadding(new Insets(16));
        root.getStyleClass().add("storage-dialog");

        stage.setTitle(I18n.t("Choose from OpenEditz"));
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        }
        Scene scene = new Scene(root, 480, 520);
        AppUi.applyAppStyles(scene);
        stage.setScene(scene);
    }

    private DriveNode current() { return trail.isEmpty() ? null : trail.get(trail.size() - 1); }

    private void updateButtons() {
        DriveNode selected = list.getSelectionModel().getSelectedItem();
        choose.setDisable(selected == null);
        choose.setText(selected != null && selected.isFolder ? I18n.t("Open") : I18n.t("Choose"));
        back.setDisable(trail.isEmpty());
    }

    /** A folder opens, a file is picked. */
    private void activate() {
        DriveNode selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        if (selected.isFolder) {
            trail.add(selected);
            load();
        } else {
            stage.close();
            onPick.accept(selected);
        }
    }

    private void load() {
        final int gen = ++generation;
        final DriveNode folder = current();
        title.setText(folder == null ? "OpenEditz" : folder.name);
        status.setText(I18n.t("Loading\u2026"));
        list.getItems().clear();
        updateButtons();
        Thread t = new Thread(() -> {
            try {
                List<DriveNode> nodes = new ArrayList<>(DoubleClipsDrive.openEditz.list(folder));
                nodes.sort(Comparator.<DriveNode, Integer>comparing(n -> n.isFolder ? 0 : 1)
                        .thenComparing(n -> n.name == null ? "" : n.name.toLowerCase()));
                Platform.runLater(() -> {
                    if (gen != generation) return;
                    list.setItems(FXCollections.observableArrayList(nodes));
                    status.setText(nodes.isEmpty() ? I18n.t("Nothing here") : "");
                    updateButtons();
                });
            } catch (IOException | RuntimeException e) {
                String message = DriveError.describe(e);
                Platform.runLater(() -> {
                    if (gen != generation) return;
                    status.setText(message);
                });
            }
        }, "openeditz-list");
        t.setDaemon(true);
        t.start();
    }
}
