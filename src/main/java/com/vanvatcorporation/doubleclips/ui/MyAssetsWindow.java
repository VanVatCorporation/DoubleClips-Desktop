package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.AssetPreviewMaker;
import com.vanvatcorporation.doubleclips.drive.AssetsClient;
import com.vanvatcorporation.doubleclips.drive.DoubleClipsDrive;
import com.vanvatcorporation.doubleclips.drive.DriveError;
import com.vanvatcorporation.doubleclips.drive.MyAsset;
import com.vanvatcorporation.doubleclips.i18n.I18n;
import com.vanvatcorporation.doubleclips.ui.components.AppUi;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * My assets (iOS: MyAssetsView): everything I posted, with the state of the link behind it.
 * <ul>
 *   <li>Public: anyone with the link, listed in the Assets tab.</li>
 *   <li>Restricted: no active link, not listed (restrict a file in Drive or OpenEditz and it moves here by itself).</li>
 *   <li>In review: hidden after reports.</li>
 *   <li>File deleted: the file is in the Drive trash.</li>
 * </ul>
 * The checkbox flips the file's own link; "Remove" takes the entry off the tab and leaves the file in Drive.
 */
public final class MyAssetsWindow {

    private static MyAssetsWindow open;   // one at a time

    private final Stage stage = new Stage();
    private final VBox rows = new VBox(10);
    private final Label status = new Label();
    private final List<MyAsset> items = new ArrayList<>();
    /** Assets whose picture was replaced in this window: their link gets a version so the new picture shows (the server caches the old one for a day). */
    private final Map<String, Long> pictureVersion = new HashMap<>();
    private int generation;

    /** @param onClosed runs when the window closes (the tab reloads its list: link changes may have added or removed assets) */
    public static void show(Window owner, Runnable onClosed) {
        if (open != null && open.stage.isShowing()) {
            open.stage.toFront();
            return;
        }
        open = new MyAssetsWindow(owner, onClosed);
        open.stage.show();
        open.reload();
    }

    private MyAssetsWindow(Window owner, Runnable onClosed) {
        Label heading = new Label(I18n.t("My assets"));
        heading.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        Button refresh = new Button(I18n.t("Refresh"), new FontIcon(MaterialDesignR.REFRESH));
        refresh.getStyleClass().add("button-transparent");
        refresh.setOnAction(e -> reload());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, heading, spacer, refresh);
        top.setAlignment(Pos.CENTER_LEFT);

        status.setWrapText(true);
        status.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 14px;");

        Label footer = new Label(I18n.t("Restricting a file in Drive or OpenEditz takes it off the Assets tab too. "
                + "Sharing it with \"anyone with the link\" again brings it back."));
        footer.setWrapText(true);
        footer.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        VBox content = new VBox(10, rows, status, footer);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox root = new VBox(12, top, scroll);
        root.setPadding(new Insets(18));
        root.getStyleClass().add("storage-dialog");

        stage.setTitle(I18n.t("My assets"));
        if (owner != null) stage.initOwner(owner);
        Scene scene = new Scene(root, 600, 640);
        AppUi.applyAppStyles(scene);
        stage.setScene(scene);
        stage.setOnHidden(e -> {
            if (open == this) open = null;
            if (onClosed != null) onClosed.run();
        });
    }

    // ---- loading -----------------------------------------------------------------------------------------

    private void reload() {
        final int gen = ++generation;
        status.setText(I18n.t("Loading\u2026"));
        Thread t = new Thread(() -> {
            try {
                List<MyAsset> mine = DoubleClipsDrive.assets.mine();
                Platform.runLater(() -> {
                    if (gen != generation) return;
                    items.clear();
                    items.addAll(mine);
                    redraw();
                });
            } catch (IOException | RuntimeException e) {
                String message = DriveError.describe(e);
                Platform.runLater(() -> {
                    if (gen != generation) return;
                    status.setText(message);
                });
            }
        }, "my-assets");
        t.setDaemon(true);
        t.start();
    }

    private void replace(MyAsset updated) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(updated.id)) {
                items.set(i, updated);
                break;
            }
        }
        redraw();
    }

    private void redraw() {
        rows.getChildren().clear();
        for (MyAsset a : items) rows.getChildren().add(row(a));
        status.setText(items.isEmpty() ? I18n.t("Nothing posted yet. Assets you post show up here with the state of their link.") : "");
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.initOwner(stage);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    // ---- one row -----------------------------------------------------------------------------------------

    private Node row(MyAsset asset) {
        StackPane thumb = new StackPane();
        thumb.getStyleClass().add("project-thumbnail");
        thumb.setPrefSize(80, 54);
        thumb.setMinSize(80, 54);
        thumb.setMaxSize(80, 54);
        thumb.getChildren().add(new FontIcon(AssetUi.icon(asset.kind)));
        if (asset.previewUrl != null) {
            Long version = pictureVersion.get(asset.id);
            String url = version == null ? asset.previewUrl : asset.previewUrl + (asset.previewUrl.contains("?") ? "&" : "?") + "v=" + version;
            Image image = new Image(url, 80, 0, true, true, true);
            ImageView view = new ImageView(image);
            view.setFitWidth(80);
            view.setFitHeight(54);
            view.setPreserveRatio(true);
            view.setVisible(false);
            Runnable decide = () -> view.setVisible(image.getProgress() >= 1.0 && !image.isError());
            image.progressProperty().addListener((o, was, now) -> decide.run());
            image.errorProperty().addListener((o, was, now) -> decide.run());
            decide.run();
            thumb.getChildren().add(view);
        }

        Label title = new Label(asset.title);
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label meta = new Label(I18n.t(asset.kind.singular) + "  \u00b7  \u2193 " + AssetUi.count(asset.downloads));
        meta.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        VBox text = new VBox(3, title, meta);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(12, thumb, text, spacer, stateBadge(asset));
        head.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, head);
        card.getStyleClass().add("storage-card");
        card.setPadding(new Insets(12));

        if (asset.canToggleLink()) {
            CheckBox toggle = new CheckBox(I18n.t("Anyone with the link"));
            toggle.setSelected(MyAsset.STATE_PUBLIC.equals(asset.state));
            toggle.setOnAction(e -> setPublic(asset, toggle));
            card.getChildren().add(toggle);
            if (MyAsset.STATE_PUBLIC.equals(asset.state) && "folder".equals(asset.via) && asset.folderName != null) {
                card.getChildren().add(note(I18n.f("Through the folder \u201c%s\u201d", asset.folderName), "-fx-text-fill: -color-fg-muted;"));
            }
        } else if (MyAsset.STATE_REVIEW.equals(asset.state)) {
            card.getChildren().add(note(I18n.t("Hidden after reports until it is reviewed."), "-fx-text-fill: #ff9500;"));
        } else {
            card.getChildren().add(note(I18n.t("The file is in the Drive trash. Restore it in Drive to bring it back, or remove it here."),
                    "-fx-text-fill: -color-fg-muted;"));
        }

        Button edit = new Button(I18n.t("Edit\u2026"));
        edit.getStyleClass().add("app-button");
        edit.setOnAction(e -> edit(asset));
        Button remove = new Button(I18n.t("Remove\u2026"));
        remove.getStyleClass().add("app-button");
        remove.setOnAction(e -> remove(asset));
        Button picture = new Button(I18n.t(asset.previewUrl == null ? "Add picture\u2026" : "Change picture\u2026"));
        picture.getStyleClass().add("app-button");
        picture.setOnAction(e -> changePicture(asset, picture));
        HBox buttons = new HBox(8, edit, picture, remove);
        card.getChildren().add(buttons);
        return card;
    }

    private static Label note(String text, String color) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 11px; " + color);
        return l;
    }

    private static Label stateBadge(MyAsset asset) {
        String text;
        String color;
        if (MyAsset.STATE_PUBLIC.equals(asset.state)) { text = I18n.t("Public"); color = "#34c759"; }
        else if (MyAsset.STATE_RESTRICTED.equals(asset.state)) { text = I18n.t("Restricted"); color = "#8e8e93"; }
        else if (MyAsset.STATE_REVIEW.equals(asset.state)) { text = I18n.t("In review"); color = "#ff9500"; }
        else { text = I18n.t("File deleted"); color = "#ff3b30"; }
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 4 8 4 8; -fx-background-radius: 10; -fx-text-fill: " + color
                + "; -fx-background-color: " + color + "26;");
        return l;
    }

    // ---- actions -----------------------------------------------------------------------------------------

    /** Flips "anyone with the link". A link that comes from a folder can't be removed from here: the server says which folder. */
    private void setPublic(MyAsset asset, CheckBox toggle) {
        final boolean on = toggle.isSelected();
        toggle.setDisable(true);
        Thread t = new Thread(() -> {
            try {
                MyAsset updated = on ? DoubleClipsDrive.assets.share(asset) : DoubleClipsDrive.assets.restrict(asset);
                Platform.runLater(() -> replace(updated));
            } catch (IOException | RuntimeException e) {
                String message = DriveError.describe(e);
                Platform.runLater(() -> {
                    showError(message);
                    reload();     // the real state, whatever the switch said
                });
            }
        }, "my-assets-link");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Picks a picture and sends it as the asset's preview (the small JPEG the tab shows), for an asset posted without one or
     * to replace the one it has. Any image this computer can read; it is shrunk to fit the 200 KB the server accepts.
     */
    private void changePicture(MyAsset asset, Button button) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("Choose a picture\u2026"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.t("Pictures"), "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp"));
        File picked = chooser.showOpenDialog(stage);
        if (picked == null) return;
        button.setDisable(true);
        Thread t = new Thread(() -> {
            byte[] jpeg = AssetPreviewMaker.jpegFromPicture(picked);
            if (jpeg == null) {
                Platform.runLater(() -> {
                    button.setDisable(false);
                    showError(I18n.t("That picture couldn't be read."));
                });
                return;
            }
            try {
                DoubleClipsDrive.assets.uploadPreview(asset.id, jpeg);
                Platform.runLater(() -> {
                    pictureVersion.put(asset.id, System.currentTimeMillis());
                    reload();     // the row is rebuilt with the new picture
                });
            } catch (IOException | RuntimeException e) {
                String message = DriveError.describe(e);
                Platform.runLater(() -> {
                    button.setDisable(false);
                    showError(message);
                });
            }
        }, "my-assets-picture");
        t.setDaemon(true);
        t.start();
    }

    private void remove(MyAsset asset) {
        ButtonType removeOnly = new ButtonType(I18n.t("Remove from Assets"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType removeAndRestrict = new ButtonType(I18n.t("Remove and restrict the file"), javafx.scene.control.ButtonBar.ButtonData.OTHER);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, I18n.t("The file stays in your Drive."), removeOnly, removeAndRestrict, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setHeaderText(I18n.f("Remove \u201c%s\u201d from Assets?", asset.title));
        Optional<ButtonType> answer = confirm.showAndWait();
        if (!answer.isPresent() || answer.get() == ButtonType.CANCEL) return;
        final boolean revoke = answer.get() == removeAndRestrict;
        Thread t = new Thread(() -> {
            try {
                DoubleClipsDrive.assets.remove(asset, revoke);
                Platform.runLater(() -> {
                    items.removeIf(a -> a.id.equals(asset.id));
                    redraw();
                });
            } catch (IOException | RuntimeException e) {
                String message = DriveError.describe(e);
                Platform.runLater(() -> showError(message));
            }
        }, "my-assets-remove");
        t.setDaemon(true);
        t.start();
    }

    private void edit(MyAsset asset) {
        TextField titleField = new TextField(asset.title);
        TextField tagsField = new TextField(String.join(", ", asset.tags));
        tagsField.setPromptText("beat, smooth, 4k");
        TextArea descriptionField = new TextArea(asset.description == null ? "" : asset.description);
        descriptionField.setPromptText(I18n.t("What is it, how to use it"));
        descriptionField.setPrefRowCount(5);
        descriptionField.setWrapText(true);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle(I18n.t("Edit asset"));
        VBox form = new VBox(8, new Label(I18n.t("Title")), titleField, new Label(I18n.t("Tags (comma separated)")), tagsField,
                new Label(I18n.t("Description")), descriptionField);
        form.setPrefWidth(420);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> titleField.getText().trim().length() < 2, titleField.textProperty()));

        Optional<ButtonType> answer = dialog.showAndWait();
        if (!answer.isPresent() || answer.get() != ButtonType.OK) return;
        final String title = titleField.getText().trim();
        final String description = descriptionField.getText().trim();
        final List<String> tags = AssetsClient.splitTags(tagsField.getText());
        Thread t = new Thread(() -> {
            try {
                MyAsset updated = DoubleClipsDrive.assets.update(asset, title, description, tags);
                Platform.runLater(() -> replace(updated));
            } catch (IOException | RuntimeException e) {
                String message = DriveError.describe(e);
                Platform.runLater(() -> showError(message));
            }
        }, "my-assets-edit");
        t.setDaemon(true);
        t.start();
    }
}
