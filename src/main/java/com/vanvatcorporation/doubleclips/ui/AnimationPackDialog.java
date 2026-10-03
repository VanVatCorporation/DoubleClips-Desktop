package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.ClipAnimationAssets;
import com.vanvatcorporation.doubleclips.ClipAnimationPacks;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.List;

/**
 * The "Animation packs" dialog: lists the installed packs, lets the user import a new .zip pack or
 * remove an installed one. All the real work (validation, install, removal) is in
 * {@link ClipAnimationPacks} via {@link ClipAnimationAssets}; this only shows it. Same behaviour and
 * wording as Android's AnimationPackDialog.
 */
public final class AnimationPackDialog {

    private AnimationPackDialog() {}

    /**
     * @param owner     the window to sit on top of (may be null)
     * @param onChanged called after a pack was installed, updated or removed, so the animation
     *                  dropdowns can re-read the registry
     */
    public static void show(Window owner, Runnable onChanged) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Animation packs");
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ListView<ClipAnimationPacks.PackInfo> list = new ListView<>();
        list.setPrefSize(460, 220);
        list.setPlaceholder(new Label("No animation packs installed.\n\n"
                + "A pack is a .zip with a pack.json and animation .json files.\n"
                + "Import one to add more in / out animations to the dropdowns."));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ClipAnimationPacks.PackInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : describe(item));
            }
        });
        Runnable reload = () -> list.getItems().setAll(ClipAnimationAssets.installedPacks());
        reload.run();

        Button importButton = new Button("Import pack...");
        Button removeButton = new Button("Remove");
        removeButton.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());

        importButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select Animation Pack (.zip)");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Animation pack (.zip)", "*.zip"));
            File file = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (file == null) return;
            try {
                ClipAnimationPacks.InstallResult installed = ClipAnimationAssets.importPack(file);
                String what = installed.replacedVersion > 0
                        ? "Updated \"" + installed.pack.name + "\" (v" + installed.replacedVersion + " -> v" + installed.pack.version + ")."
                        : "Installed \"" + installed.pack.name + "\".";
                reload.run();
                onChanged.run();
                info(dialog, "Animation pack", what + "\n\n" + installed.pack.animationCount()
                        + " animation(s) are now in the in / out dropdowns.");
            } catch (ClipAnimationPacks.PackException ex) {
                error(dialog, "Couldn't install the pack", ex.getMessage());
            }
        });

        removeButton.setOnAction(e -> {
            ClipAnimationPacks.PackInfo pack = list.getSelectionModel().getSelectedItem();
            if (pack == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Clips that use its animations keep them selected but will export without them until the pack is installed again.",
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.setTitle("Remove pack");
            confirm.setHeaderText("Remove \"" + pack.name + "\"?");
            confirm.initOwner(dialog.getDialogPane().getScene().getWindow());
            confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
                try {
                    ClipAnimationAssets.removePack(pack.id);
                    reload.run();
                    onChanged.run();
                } catch (ClipAnimationPacks.PackException ex) {
                    error(dialog, "Couldn't remove the pack", ex.getMessage());
                }
            });
        });

        HBox buttons = new HBox(8, importButton, removeButton);
        VBox content = new VBox(10, list, buttons);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private static void info(Dialog<?> parent, String title, String message) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        a.initOwner(parent.getDialogPane().getScene().getWindow());
        a.showAndWait();
    }

    private static void error(Dialog<?> parent, String title, String message) {
        Alert a = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        a.initOwner(parent.getDialogPane().getScene().getWindow());
        a.showAndWait();
    }

    private static String describe(ClipAnimationPacks.PackInfo p) {
        StringBuilder sb = new StringBuilder(p.name);
        if (!p.damaged) sb.append("  v").append(p.version);
        if (!p.author.isEmpty()) sb.append("  \u00b7  ").append(p.author);
        sb.append("\n");
        if (p.damaged) {
            sb.append("Unreadable - select it and press Remove");
        } else {
            sb.append(p.animationCount()).append(p.animationCount() == 1 ? " animation" : " animations");
            if (!p.inIds.isEmpty()) sb.append("  \u00b7  in: ").append(String.join(", ", p.inIds));
            if (!p.outIds.isEmpty()) sb.append("  \u00b7  out: ").append(String.join(", ", p.outIds));
        }
        return sb.toString();
    }
}
