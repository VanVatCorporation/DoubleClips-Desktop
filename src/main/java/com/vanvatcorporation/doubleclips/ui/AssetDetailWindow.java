package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.AssetLibrary;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.ProjectRepository;
import com.vanvatcorporation.doubleclips.drive.AssetKind;
import com.vanvatcorporation.doubleclips.drive.AssetsClient;
import com.vanvatcorporation.doubleclips.drive.BlockedAuthors;
import com.vanvatcorporation.doubleclips.drive.CommunityAsset;
import com.vanvatcorporation.doubleclips.drive.DoubleClipsDrive;
import com.vanvatcorporation.doubleclips.drive.DriveError;
import com.vanvatcorporation.doubleclips.i18n.I18n;
import com.vanvatcorporation.doubleclips.ui.components.AppUi;
import com.vanvatcorporation.doubleclips.ui.components.TransferProgressWindow;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One community asset (iOS: AssetDetailView): preview, details, and what to do with it.
 * <p>
 * "Get" downloads the file (sign-in needed; it counts against YOUR daily download allowance, never the poster's) and then, by kind:
 * an effect or an animation pack is installed in the app; video / image / audio / font go into the open project when this was opened
 * from the editor. From the main window's tab a font goes into a project you pick, and the other media are saved as a file (a project
 * that is not open has no preview proxies yet: the editor's own import makes them). "Save a copy..." always saves the file.
 * Report and "Hide assets from ..." are in the menu.
 */
public final class AssetDetailWindow {

    private final Stage stage = new Stage();
    private final CommunityAsset asset;
    private final AssetTarget target;

    private final Label notice = new Label();
    private final Button getButton = new Button();
    private final Button saveButton = new Button(I18n.t("Save a copy..."));
    private boolean busy;

    public static void show(CommunityAsset asset, AssetTarget target, Window owner) {
        new AssetDetailWindow(asset, target, owner).stage.show();
    }

    private AssetDetailWindow(CommunityAsset asset, AssetTarget target, Window owner) {
        this.asset = asset;
        this.target = target;

        VBox root = new VBox(12);
        root.setPadding(new Insets(20));
        root.getChildren().addAll(buildPreview(), buildDetails(), buildActions(), notice, buildDisclaimer());

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");

        notice.setWrapText(true);
        notice.setManaged(false);
        notice.setVisible(false);

        stage.setTitle(I18n.t(asset.kind.singular) + " - " + asset.title);
        if (owner != null) stage.initOwner(owner);
        Scene scene = new Scene(scroll, 540, 660);
        AppUi.applyAppStyles(scene);
        stage.setScene(scene);
    }

    // ---- layout ------------------------------------------------------------------------------------------

    private Region buildPreview() {
        StackPane box = new StackPane();
        box.getStyleClass().add("project-thumbnail");
        box.setPrefHeight(280);
        box.setMinHeight(280);
        box.setMaxHeight(280);
        FontIcon placeholder = new FontIcon(AssetUi.icon(asset.kind));
        placeholder.setIconSize(48);
        box.getChildren().add(placeholder);
        if (asset.previewUrl != null) {
            Image image = new Image(asset.previewUrl, 500, 0, true, true, true);
            ImageView view = new ImageView(image);
            view.setFitWidth(500);
            view.setFitHeight(280);
            view.setPreserveRatio(true);
            view.setVisible(false);
            Runnable decide = () -> view.setVisible(image.getProgress() >= 1.0 && !image.isError());
            image.progressProperty().addListener((o, was, now) -> decide.run());
            image.errorProperty().addListener((o, was, now) -> decide.run());
            decide.run();
            box.getChildren().add(view);
        }
        return box;
    }

    private Region buildDetails() {
        Label title = new Label(asset.title);
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        title.setWrapText(true);

        Label meta = new Label(I18n.t(asset.kind.singular) + "  \u00b7  " + AssetUi.size(asset.size)
                + "  \u00b7  \u2193 " + AssetUi.count(asset.downloads));
        meta.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");

        VBox box = new VBox(8, title, meta);
        if (asset.ownerName != null && !asset.ownerName.isEmpty()) {
            Label owner = new Label("@" + asset.ownerName);
            owner.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
            box.getChildren().add(owner);
        }
        if (!asset.tags.isEmpty()) {
            FlowPane tags = new FlowPane(6, 6);
            for (String tag : asset.tags) {
                Label l = new Label("#" + tag);
                l.setStyle("-fx-font-size: 12px; -fx-padding: 3 10 3 10; -fx-background-color: -color-bg-subtle; -fx-background-radius: 12;");
                tags.getChildren().add(l);
            }
            box.getChildren().add(tags);
        }
        if (asset.description != null && !asset.description.trim().isEmpty()) {
            Label description = new Label(asset.description.trim());
            description.setWrapText(true);
            description.setStyle("-fx-font-size: 14px; -fx-text-fill: -color-fg-muted;");
            box.getChildren().add(description);
        }
        return box;
    }

    private Region buildActions() {
        getButton.getStyleClass().add("app-button-primary");
        getButton.setText(getLabel());
        getButton.setOnAction(e -> get(false));

        saveButton.getStyleClass().add("app-button");
        saveButton.setOnAction(e -> get(true));

        MenuButton more = new MenuButton();
        more.setGraphic(new FontIcon(MaterialDesignD.DOTS_HORIZONTAL));
        more.getStyleClass().add("button-transparent");
        MenuItem report = new MenuItem(I18n.t("Report..."));
        report.setOnAction(e -> report());
        more.getItems().add(report);
        if (asset.ownerName != null && !asset.ownerName.isEmpty()) {
            MenuItem hide = new MenuItem(I18n.f("Hide assets from %s", asset.ownerName));
            hide.setOnAction(e -> hideOwner());
            more.getItems().add(hide);
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(10, getButton, saveButton, spacer, more);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Region buildDisclaimer() {
        Label l = new Label(I18n.t("Posted by a member of the community. Only use what you have the right to use; report anything that shouldn't be here."));
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        return l;
    }

    private String getLabel() {
        if (asset.kind.isInstalledInApp()) return I18n.t("Install");
        if (target != null) return I18n.f("Add to %s", target.projectTitle());
        return I18n.t("Get");
    }

    private void showNotice(String text, boolean error) {
        notice.setText(text);
        notice.setStyle(error ? "-fx-text-fill: #ff3b30; -fx-font-size: 13px;" : "-fx-text-fill: #34c759; -fx-font-size: 13px;");
        notice.setManaged(true);
        notice.setVisible(true);
    }

    private void setBusy(boolean b) {
        busy = b;
        getButton.setDisable(b);
        saveButton.setDisable(b);
    }

    private boolean requireSignIn() {
        if (DoubleClipsDrive.hasSession()) return true;
        showNotice(I18n.t("Sign in from the Profile tab to do this."), true);
        return false;
    }

    // ---- get ---------------------------------------------------------------------------------------------

    /** Downloads the file, then installs / adds it ({@code saveCopy} false) or saves it as a file ({@code saveCopy} true). */
    private void get(boolean saveCopy) {
        if (busy || !requireSignIn()) return;
        setBusy(true);
        notice.setManaged(false);
        notice.setVisible(false);

        TransferProgressWindow progress = new TransferProgressWindow(stage, I18n.t("Downloading") + " - " + asset.title);
        progress.show();
        AtomicBoolean cancel = progress.cancelFlag();
        final String line = I18n.f("Downloading %s", asset.title);
        Thread t = new Thread(() -> {
            try {
                File file = DoubleClipsDrive.assets.download(asset,
                        got -> progress.post(line, asset.size > 0 ? (double) got / asset.size : -1), cancel);
                Platform.runLater(() -> {
                    progress.close();
                    setBusy(false);
                    finish(file, saveCopy);
                });
            } catch (CancellationException stopped) {
                Platform.runLater(() -> {
                    progress.close();
                    setBusy(false);
                });
            } catch (IOException | RuntimeException e) {
                String message = DriveError.describe(e);
                Platform.runLater(() -> {
                    progress.close();
                    setBusy(false);
                    showNotice(message, true);
                });
            }
        }, "asset-download");
        t.setDaemon(true);
        t.start();
    }

    private void finish(File file, boolean saveCopy) {
        if (saveCopy) {
            saveAs(file);
            return;
        }
        try {
            switch (asset.kind) {
                case EFFECT:
                    showNotice(I18n.f("Effect \u201c%s\u201d installed. Find it in the Effects list.", AssetLibrary.installEffect(file)), false);
                    break;
                case PACK:
                    showNotice(I18n.f("Animation pack \u201c%s\u201d installed. Its animations are in the animation lists.", AssetLibrary.installPack(file)), false);
                    break;
                default:
                    if (target != null) {
                        showNotice(target.add(file, asset.kind), false);
                    } else if (asset.kind == AssetKind.FONT) {
                        ProjectData project = pickProject();
                        if (project != null) {
                            showNotice(I18n.f("Font \u201c%1$s\u201d added to %2$s.", AssetLibrary.addFont(file, project), project.getProjectTitle()), false);
                        }
                    } else {
                        showNotice(I18n.t("To add this to a project, open the project and use Assets in the editor. Saving it as a file instead."), false);
                        saveAs(file);
                    }
                    break;
            }
        } catch (AssetLibrary.InstallException e) {
            showNotice(e.getMessage(), true);
        }
    }

    private void saveAs(File file) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("Save a copy..."));
        chooser.setInitialFileName(file.getName());
        File dest = chooser.showSaveDialog(stage);
        if (dest == null) return;
        try {
            Files.copy(file.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            showNotice(I18n.f("Saved to %s", dest.getAbsolutePath()), false);
        } catch (IOException e) {
            showNotice(I18n.f("Couldn't save the file: %s", e.getMessage()), true);
        }
    }

    /** The project a font goes into (the main window has no open project). Null = cancelled or there are none. */
    private ProjectData pickProject() {
        List<ProjectData> projects = new ArrayList<>(ProjectRepository.getInstance().projectsProperty());
        if (projects.isEmpty()) {
            showNotice(I18n.t("There are no projects yet. Create one first."), true);
            return null;
        }
        ComboBox<ProjectData> box = new ComboBox<>(FXCollections.observableArrayList(projects));
        box.setConverter(new StringConverter<ProjectData>() {
            @Override public String toString(ProjectData p) { return p == null ? "" : p.getProjectTitle(); }
            @Override public ProjectData fromString(String s) { return null; }
        });
        box.getSelectionModel().selectFirst();
        box.setMaxWidth(Double.MAX_VALUE);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle(I18n.t("Add to project"));
        dialog.getDialogPane().setContent(new VBox(10, new Label(I18n.t("Which project should get this font?")), box));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Optional<ButtonType> answer = dialog.showAndWait();
        return answer.isPresent() && answer.get() == ButtonType.OK ? box.getValue() : null;
    }

    // ---- report / hide -----------------------------------------------------------------------------------

    private static String reasonLabel(String reason) {
        switch (reason) {
            case "spam": return I18n.t("Spam");
            case "copyright": return I18n.t("Copyright");
            case "inappropriate": return I18n.t("Inappropriate");
            case "malware": return I18n.t("Malware");
            default: return I18n.t("Other");
        }
    }

    private void report() {
        if (!requireSignIn()) return;
        ComboBox<String> reasons = new ComboBox<>(FXCollections.observableArrayList(AssetsClient.REPORT_REASONS));
        reasons.setConverter(new StringConverter<String>() {
            @Override public String toString(String r) { return r == null ? "" : reasonLabel(r); }
            @Override public String fromString(String s) { return null; }
        });
        reasons.getSelectionModel().selectFirst();
        reasons.setMaxWidth(Double.MAX_VALUE);
        TextField note = new TextField();
        note.setPromptText(I18n.t("Tell us more (optional)"));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle(I18n.t("Report"));
        dialog.getDialogPane().setContent(new VBox(10, new Label(I18n.t("What is wrong with this asset?")), reasons, note));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Optional<ButtonType> answer = dialog.showAndWait();
        if (!answer.isPresent() || answer.get() != ButtonType.OK) return;

        final String reason = reasons.getValue();
        final String text = note.getText();
        Thread t = new Thread(() -> {
            try {
                DoubleClipsDrive.assets.report(asset, reason, text);
                Platform.runLater(() -> showNotice(I18n.t("Thank you. We will take a look."), false));
            } catch (IOException | RuntimeException e) {
                String message = DriveError.describe(e);
                Platform.runLater(() -> showNotice(message, true));
            }
        }, "asset-report");
        t.setDaemon(true);
        t.start();
    }

    private void hideOwner() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, I18n.f("Hide every asset from %s on this computer?", asset.ownerName),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (!answer.isPresent() || answer.get() != ButtonType.OK) return;
        BlockedAuthors.block(asset.ownerName);
        stage.close();
    }
}
