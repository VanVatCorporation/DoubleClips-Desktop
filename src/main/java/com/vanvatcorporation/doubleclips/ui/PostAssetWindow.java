package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.AssetPreviewMaker;
import com.vanvatcorporation.doubleclips.drive.AssetKind;
import com.vanvatcorporation.doubleclips.drive.AssetsClient;
import com.vanvatcorporation.doubleclips.drive.DoubleClipsDrive;
import com.vanvatcorporation.doubleclips.drive.DriveError;
import com.vanvatcorporation.doubleclips.drive.DriveNode;
import com.vanvatcorporation.doubleclips.drive.MyAsset;
import com.vanvatcorporation.doubleclips.i18n.I18n;
import com.vanvatcorporation.doubleclips.ui.components.AppUi;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
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
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Post an asset (iOS: PostAssetView).
 * <ol>
 *   <li>Pick the file: one already in OpenEditz, or one from this computer (it is uploaded to OpenEditz/Assets first).</li>
 *   <li>Describe it: kind (guessed from the file), title, tags, description; a preview picture is made from the file when it can
 *       be (an image, a frame of a video, a font sample) or picked by hand.</li>
 *   <li>Confirm the rights. Posting shares the file as "anyone with the link" (view only) and lists it in the Assets tab for everyone;
 *       restricting it later, from My assets, Drive or OpenEditz, takes it off the tab again.</li>
 * </ol>
 */
public final class PostAssetWindow {

    private final Stage stage = new Stage();
    private final Runnable onPosted;

    private DriveNode node;          // a file already in OpenEditz
    private File local;              // a file from this computer (uploaded when posting)
    private byte[] preview;
    private boolean customPicture;   // the user picked the picture: a change of kind keeps it

    private final Label fileLabel = new Label(I18n.t("No file chosen"));
    private final ComboBox<AssetKind> kindBox = new ComboBox<>();
    private final TextField titleField = new TextField();
    private final TextField tagsField = new TextField();
    private final TextArea descriptionField = new TextArea();
    private final ImageView previewView = new ImageView();
    private final CheckBox rights = new CheckBox(I18n.t("I own this file or have the right to share it"));
    private final Label errorLabel = new Label();
    private final Label workingLabel = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Button postButton = new Button(I18n.t("Post"));
    private final VBox form = new VBox(14);
    private boolean working;

    /** @param onPosted runs on the FX thread once the asset is posted (the window closes itself) */
    public static void show(Window owner, Runnable onPosted) {
        new PostAssetWindow(owner, onPosted).stage.show();
    }

    private PostAssetWindow(Window owner, Runnable onPosted) {
        this.onPosted = onPosted;

        // ---- file
        Button fromDrive = new Button(I18n.t("Choose from OpenEditz\u2026"), new FontIcon(MaterialDesignF.FOLDER_OUTLINE));
        fromDrive.getStyleClass().add("app-button");
        fromDrive.setOnAction(e -> OpenEditzPickerWindow.pick(stage, picked -> {
            node = picked;
            local = null;
            adopt(picked.name, null);
        }));
        Button fromComputer = new Button(I18n.t("Choose from this computer\u2026"), new FontIcon(MaterialDesignF.FILE_OUTLINE));
        fromComputer.getStyleClass().add("app-button");
        fromComputer.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("Choose the file to post"));
            File picked = chooser.showOpenDialog(stage);
            if (picked == null) return;
            local = picked;
            node = null;
            adopt(picked.getName(), picked);
        });
        fileLabel.setStyle("-fx-font-weight: bold;");
        fileLabel.setWrapText(true);
        VBox fileBox = new VBox(8, sectionTitle(I18n.t("File")), fileLabel, new HBox(8, fromDrive, fromComputer));

        // ---- details
        kindBox.getItems().addAll(AssetKind.values());
        kindBox.getSelectionModel().select(AssetKind.IMAGE);
        kindBox.setConverter(new StringConverter<AssetKind>() {
            @Override public String toString(AssetKind k) { return k == null ? "" : I18n.t(k.singular); }
            @Override public AssetKind fromString(String s) { return null; }
        });
        kindBox.setOnAction(e -> {
            // a new kind: the picture made from the file may be a different one (an image vs a video frame); one picked by hand stays
            if (local != null && !customPicture) makePreviewFromFile();
            refresh();
        });
        titleField.setPromptText(I18n.t("Title"));
        tagsField.setPromptText(I18n.t("Tags (comma separated)"));
        descriptionField.setPromptText(I18n.t("Description (optional)"));
        descriptionField.setPrefRowCount(4);
        descriptionField.setWrapText(true);
        titleField.textProperty().addListener((o, a, b) -> refresh());
        VBox detailsBox = new VBox(8, sectionTitle(I18n.t("Details")), kindBox, titleField, tagsField, descriptionField);

        // ---- picture
        previewView.setFitWidth(220);
        previewView.setFitHeight(130);
        previewView.setPreserveRatio(true);
        StackPane previewBox = new StackPane(previewView);
        previewBox.getStyleClass().add("project-thumbnail");
        previewBox.setPrefSize(240, 140);
        previewBox.setMinSize(240, 140);
        previewBox.setMaxSize(240, 140);
        Button choosePicture = new Button(I18n.t("Choose a picture\u2026"));
        choosePicture.getStyleClass().add("app-button");
        choosePicture.setOnAction(e -> choosePicture());
        VBox pictureBox = new VBox(8, sectionTitle(I18n.t("Preview picture")), previewBox, choosePicture);
        pictureBox.setAlignment(Pos.TOP_LEFT);

        // ---- rights
        rights.setWrapText(true);
        rights.selectedProperty().addListener((o, a, b) -> refresh());
        Label footer = new Label(I18n.t("Posting shares the file with \"anyone with the link\" (view only) and lists it in the Assets tab for everyone. "
                + "You can restrict it any time from My assets, Drive or OpenEditz."));
        footer.setWrapText(true);
        footer.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        VBox rightsBox = new VBox(8, rights, footer);

        errorLabel.setWrapText(true);
        errorLabel.setStyle("-fx-text-fill: #ff3b30; -fx-font-size: 13px;");
        errorLabel.setManaged(false);
        errorLabel.setVisible(false);

        form.getChildren().addAll(fileBox, detailsBox, pictureBox, rightsBox, errorLabel);
        form.setPadding(new Insets(20));
        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // ---- bottom bar
        spinner.setPrefSize(22, 22);
        spinner.setVisible(false);
        spinner.setManaged(false);
        workingLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        Button cancel = new Button(I18n.t("Cancel"));
        cancel.getStyleClass().add("app-button");
        cancel.setOnAction(e -> { if (!working) stage.close(); });
        postButton.getStyleClass().add("app-button-primary");
        postButton.setOnAction(e -> post());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, spinner, workingLabel, spacer, cancel, postButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(12, 20, 16, 20));

        VBox root = new VBox(scroll, bar);
        root.getStyleClass().add("storage-dialog");

        stage.setTitle(I18n.t("Post an asset"));
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        }
        Scene scene = new Scene(root, 520, 720);
        AppUi.applyAppStyles(scene);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> { if (working) e.consume(); });
        refresh();
    }

    private static Label sectionTitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("storage-section-title");
        return l;
    }

    // ---- state -------------------------------------------------------------------------------------------

    private String fileName() { return node != null ? node.name : local != null ? local.getName() : null; }

    private boolean canPost() {
        return fileName() != null && rights.isSelected() && titleField.getText().trim().length() >= 2 && !working;
    }

    private void refresh() {
        postButton.setDisable(!canPost());
    }

    private void showError(String text) {
        errorLabel.setText(text == null ? "" : text);
        errorLabel.setManaged(text != null);
        errorLabel.setVisible(text != null);
    }

    private void setWorking(boolean on, String text) {
        working = on;
        form.setDisable(on);
        spinner.setVisible(on);
        spinner.setManaged(on);
        workingLabel.setText(on && text != null ? text : "");
        refresh();
    }

    /** A newly chosen file: guess the kind, suggest a title, make the picture when the file is on this computer. */
    private void adopt(String name, File file) {
        showError(null);
        fileLabel.setText(name);
        AssetKind detected = AssetKind.detect(name);
        if (detected != null) kindBox.getSelectionModel().select(detected);
        if (titleField.getText().trim().isEmpty()) {
            int dot = name.lastIndexOf('.');
            titleField.setText(dot > 0 ? name.substring(0, dot) : name);
        }
        customPicture = false;
        setPreview(null);
        if (file != null) makePreviewFromFile();
        refresh();
    }

    private void makePreviewFromFile() {
        final File file = local;
        final AssetKind kind = kindBox.getValue();
        if (file == null || kind == null) return;
        Thread t = new Thread(() -> {
            byte[] jpeg = AssetPreviewMaker.jpegFor(file, kind);
            Platform.runLater(() -> {
                // the choice may have moved on while this ran
                if (file.equals(local) && !customPicture && kind == kindBox.getValue()) setPreview(jpeg);
            });
        }, "asset-preview");
        t.setDaemon(true);
        t.start();
    }

    private void choosePicture() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("Choose a picture\u2026"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.t("Pictures"), "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp"));
        File picked = chooser.showOpenDialog(stage);
        if (picked == null) return;
        Thread t = new Thread(() -> {
            byte[] jpeg = AssetPreviewMaker.jpegFromPicture(picked);
            Platform.runLater(() -> {
                if (jpeg == null) {
                    showError(I18n.t("That picture couldn't be read."));
                } else {
                    showError(null);
                    customPicture = true;
                    setPreview(jpeg);
                }
            });
        }, "asset-picture");
        t.setDaemon(true);
        t.start();
    }

    private void setPreview(byte[] jpeg) {
        preview = jpeg;
        previewView.setImage(jpeg == null ? null : new Image(new ByteArrayInputStream(jpeg)));
    }

    // ---- post --------------------------------------------------------------------------------------------

    private void post() {
        if (!canPost()) return;
        final String name = fileName();
        final AssetKind kind = kindBox.getValue();
        if (kind == null || !kind.accepts(name)) {
            showError(I18n.f("This file doesn't fit the kind \u201c%s\u201d. Pick the kind that matches it.", kind == null ? "" : I18n.t(kind.singular)));
            return;
        }
        showError(null);
        final DriveNode pickedNode = node;
        final File pickedLocal = local;
        final String title = titleField.getText().trim();
        final String description = descriptionField.getText().trim();
        final List<String> tags = AssetsClient.splitTags(tagsField.getText());
        final byte[] picture = preview;
        setWorking(true, I18n.t("Posting\u2026"));

        Thread t = new Thread(() -> {
            try {
                DriveNode target = pickedNode;
                if (target == null && pickedLocal != null) {
                    status(I18n.t("Uploading to OpenEditz\u2026"));
                    DriveNode folder = DoubleClipsDrive.openEditz.ensureFolder("Assets");
                    String uploadName = uniqueName(folder, pickedLocal.getName());
                    int[] lastPercent = { -1 };
                    target = DoubleClipsDrive.openEditz.upload(pickedLocal, folder, uploadName, got -> {
                        int percent = pickedLocal.length() > 0 ? (int) (got * 100 / pickedLocal.length()) : 0;
                        if (percent != lastPercent[0]) {
                            lastPercent[0] = percent;
                            status(I18n.f("Uploading to OpenEditz\u2026 %d%%", percent));
                        }
                    }, new AtomicBoolean());
                    // uploaded: if posting then fails, the next try reuses this file instead of uploading a second copy
                    final DriveNode uploaded = target;
                    Platform.runLater(() -> {
                        node = uploaded;
                        local = null;
                    });
                }
                if (target == null) return;
                status(I18n.t("Posting\u2026"));
                MyAsset asset = DoubleClipsDrive.assets.publish(target, kind, title, description, tags);
                if (picture != null) {
                    try {
                        DoubleClipsDrive.assets.uploadPreview(asset.id, picture);
                    } catch (IOException | RuntimeException ignored) {
                        // posted without a picture: the tab shows the kind's icon, My assets can't fix it yet but nothing is lost
                    }
                }
                Platform.runLater(() -> {
                    setWorking(false, null);
                    stage.close();
                    onPosted.run();
                });
            } catch (CancellationException stopped) {
                Platform.runLater(() -> setWorking(false, null));
            } catch (IOException | RuntimeException e) {
                String message = DriveError.describe(e);
                Platform.runLater(() -> {
                    setWorking(false, null);
                    showError(message);
                });
            }
        }, "asset-post");
        t.setDaemon(true);
        t.start();
    }

    private void status(String text) {
        Platform.runLater(() -> workingLabel.setText(text));
    }

    /** A name not yet used in the folder ("beat.mp3" -> "beat (1).mp3"): an upload under a taken name is refused by the server. */
    private static String uniqueName(DriveNode folder, String name) throws IOException {
        Set<String> taken = new HashSet<>();
        for (DriveNode n : DoubleClipsDrive.openEditz.list(folder)) {
            if (n.name != null) taken.add(n.name.toLowerCase(Locale.ROOT));
        }
        if (!taken.contains(name.toLowerCase(Locale.ROOT))) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
        return name;
    }
}
