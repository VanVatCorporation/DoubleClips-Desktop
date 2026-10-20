package com.vanvatcorporation.doubleclips.ui.panes;

import com.vanvatcorporation.doubleclips.auth.AuthRepository;
import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.data.ProjectRepository;
import com.vanvatcorporation.doubleclips.drive.DoubleClipsDrive;
import com.vanvatcorporation.doubleclips.drive.DriveError;
import com.vanvatcorporation.doubleclips.drive.DriveMe;
import com.vanvatcorporation.doubleclips.drive.DriveNode;
import com.vanvatcorporation.doubleclips.drive.OpenEditzClient;
import com.vanvatcorporation.doubleclips.helper.ProjectBackup;
import com.vanvatcorporation.doubleclips.helper.ProjectRestore;
import com.vanvatcorporation.doubleclips.ui.BackupProjectWindow;
import com.vanvatcorporation.doubleclips.ui.components.FxCoalescer;
import com.vanvatcorporation.doubleclips.ui.components.TransferProgressWindow;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignU;

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The Storage tab: your OpenEditz folder in Van Vat Drive (iOS: StorageView). Back up projects as ZIPs, restore them,
 * upload and download files. Everything here stays inside OpenEditz (the full Drive - sharing, trash, other folders -
 * lives in the Van Vat Drive app). There is no second sign-in: the account login supplies the token.
 */
public class StoragePane extends VBox {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZONE);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZONE);

    private final OpenEditzClient oe = DoubleClipsDrive.openEditz;

    // state (FX thread only)
    private final List<DriveNode> trail = new ArrayList<>();           // folders entered below OpenEditz
    private final ObservableList<DriveNode> items = FXCollections.observableArrayList();
    private final AtomicInteger loadSeq = new AtomicInteger();
    private DriveMe me;
    private final BooleanProperty signedIn = new SimpleBooleanProperty(false);

    // ui
    private final VBox contentBox = new VBox(12);
    private final VBox signInBox = new VBox(8);
    private final Label signInText = new Label();
    private final HBox breadcrumb = new HBox(6);
    private final ProgressBar quotaBar = new ProgressBar(0);
    private final Label quotaLabel = new Label();
    private final VBox uploadRows = new VBox(10);
    private final VBox uploadsBox = new VBox(8);
    private final Label messageLabel = new Label();
    private final Label listPlaceholder = new Label();
    private final ListView<DriveNode> list = new ListView<>(items);

    public StoragePane() {
        setSpacing(16);
        setPadding(new Insets(32));
        getStyleClass().add("content-pane");

        // ---- header
        Label title = new Label("Storage");
        title.setStyle("-fx-font-size: 26px; -fx-font-weight: bold;");
        Label subtitle = new Label("Your OpenEditz folder in Van Vat Drive");
        subtitle.getStyleClass().add("text-muted");
        VBox titles = new VBox(2, title, subtitle);

        Button backupBtn = new Button("Back up a project", new FontIcon(MaterialDesignF.FOLDER_UPLOAD));
        backupBtn.getStyleClass().add("button-primary");
        Button uploadBtn = new Button("Upload files", new FontIcon(MaterialDesignU.UPLOAD_OUTLINE));
        Button folderBtn = new Button("New folder", new FontIcon(MaterialDesignF.FOLDER_OUTLINE));
        Button refreshBtn = new Button("Refresh");
        for (Button b : new Button[]{backupBtn, uploadBtn, folderBtn, refreshBtn}) b.disableProperty().bind(signedIn.not());
        backupBtn.setOnAction(e -> BackupProjectWindow.show(owner(), this::reload));
        uploadBtn.setOnAction(e -> chooseFilesToUpload());
        folderBtn.setOnAction(e -> newFolder());
        refreshBtn.setOnAction(e -> reload());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, titles, spacer, backupBtn, uploadBtn, folderBtn, refreshBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 8, 0));

        Region divider = new Region();
        divider.setStyle("-fx-background-color: -color-border-subtle;");
        divider.setPrefHeight(1);
        divider.setMaxWidth(Double.MAX_VALUE);

        // ---- signed-out view
        Label signInTitle = new Label("Sign in to use Storage");
        signInTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        signInText.setText("Sign in from the Profile tab to back up your projects to OpenEditz.");
        signInText.getStyleClass().add("text-muted");
        signInText.setWrapText(true);
        signInBox.getChildren().addAll(signInTitle, signInText);
        signInBox.setAlignment(Pos.TOP_CENTER);
        signInBox.setPadding(new Insets(60, 0, 0, 0));

        // ---- signed-in view
        quotaBar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(quotaBar, Priority.ALWAYS);
        quotaLabel.getStyleClass().add("text-muted");
        HBox quotaRow = new HBox(12, quotaBar, quotaLabel);
        quotaRow.setAlignment(Pos.CENTER_LEFT);

        breadcrumb.setAlignment(Pos.CENTER_LEFT);

        Label uploadsTitle = new Label("Uploads");
        uploadsTitle.setStyle("-fx-font-weight: bold;");
        Button clearUploads = new Button("Clear finished");
        clearUploads.getStyleClass().add("button-transparent");
        clearUploads.setOnAction(e -> {
            uploadRows.getChildren().removeIf(n -> Boolean.TRUE.equals(n.getProperties().get("finished")));
            refreshUploadsVisibility();
        });
        Region upSpacer = new Region();
        HBox.setHgrow(upSpacer, Priority.ALWAYS);
        uploadsBox.getChildren().addAll(new HBox(8, uploadsTitle, upSpacer, clearUploads), uploadRows);
        uploadsBox.setVisible(false);
        uploadsBox.setManaged(false);

        messageLabel.setWrapText(true);
        messageLabel.setVisible(false);
        messageLabel.setManaged(false);

        listPlaceholder.setWrapText(true);
        listPlaceholder.getStyleClass().add("text-muted");
        list.setPlaceholder(listPlaceholder);
        list.setCellFactory(v -> new NodeCell());
        VBox.setVgrow(list, Priority.ALWAYS);

        contentBox.getChildren().addAll(quotaRow, breadcrumb, messageLabel, uploadsBox, list);

        StackPane body = new StackPane(contentBox, signInBox);
        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().addAll(header, divider, body);

        // ---- wiring
        AuthRepository.getInstance().userProperty().addListener((o, a, b) -> {
            oe.reset();                       // another account (or none): forget what was learned about the old one
            trail.clear();
            items.clear();
            me = null;
            clearMessage();
            reload();
        });
        // The pane is cached by the main window: refresh whenever it is shown again.
        parentProperty().addListener((o, a, parent) -> { if (parent != null) reload(); });
        updateView();
    }

    // ================================================================================== loading

    private DriveNode currentFolder() { return trail.isEmpty() ? null : trail.get(trail.size() - 1); }

    private Window owner() { return getScene() != null ? getScene().getWindow() : null; }

    private void updateView() {
        boolean has = DoubleClipsDrive.hasSession();
        signedIn.set(has);
        contentBox.setVisible(has);
        contentBox.setManaged(has);
        signInBox.setVisible(!has);
        signInBox.setManaged(!has);
    }

    private void reload() {
        updateView();
        renderBreadcrumb();
        if (!signedIn.get()) return;

        final int seq = loadSeq.incrementAndGet();
        final DriveNode folder = currentFolder();
        listPlaceholder.setText("Loading\u2026");
        background(() -> {
            List<DriveNode> children = oe.list(folder);
            DriveMe fresh = null;
            try { fresh = DoubleClipsDrive.client.me(); } catch (IOException ignored) { /* the list is what matters */ }
            return new Object[]{children, fresh};
        }, result -> {
            if (seq != loadSeq.get()) return;                // a newer load is on its way
            @SuppressWarnings("unchecked") List<DriveNode> children = (List<DriveNode>) result[0];
            List<DriveNode> sorted = new ArrayList<>(children);
            sorted.sort((x, y) -> {
                if (x.isFolder != y.isFolder) return x.isFolder ? -1 : 1;
                return x.name.compareToIgnoreCase(y.name);
            });
            items.setAll(sorted);
            if (result[1] != null) me = (DriveMe) result[1];
            listPlaceholder.setText("Nothing here yet. Back up a project or upload files.");
            renderQuota();
            clearMessage();
        }, err -> {
            if (seq != loadSeq.get()) return;
            listPlaceholder.setText("Nothing to show.");
            handle(err);
        });
    }

    private void renderQuota() {
        if (me == null || me.quotaBytes <= 0) {
            quotaBar.setProgress(0);
            quotaLabel.setText("");
            return;
        }
        quotaBar.setProgress(Math.min(1.0, (double) me.usedBytes / me.quotaBytes));
        quotaBar.setStyle(me.usedBytes * 10 > me.quotaBytes * 9 ? "-fx-accent: -color-danger-fg;" : "");
        quotaLabel.setText(ProjectBackup.formatBytes(me.usedBytes) + " of " + ProjectBackup.formatBytes(me.quotaBytes) + " used");
    }

    private void renderBreadcrumb() {
        breadcrumb.getChildren().clear();
        breadcrumb.setVisible(!trail.isEmpty());
        breadcrumb.setManaged(!trail.isEmpty());
        if (trail.isEmpty()) return;
        Hyperlink root = new Hyperlink("OpenEditz");
        root.setOnAction(e -> goToDepth(0));
        breadcrumb.getChildren().add(root);
        for (int i = 0; i < trail.size(); i++) {
            Label sep = new Label("\u203a");
            sep.getStyleClass().add("text-muted");
            breadcrumb.getChildren().add(sep);
            DriveNode f = trail.get(i);
            if (i == trail.size() - 1) {
                Label last = new Label(f.name);
                last.setStyle("-fx-font-weight: bold;");
                breadcrumb.getChildren().add(last);
            } else {
                final int depth = i + 1;
                Hyperlink link = new Hyperlink(f.name);
                link.setOnAction(e -> goToDepth(depth));
                breadcrumb.getChildren().add(link);
            }
        }
    }

    private void enter(DriveNode folder) {
        trail.add(folder);
        items.clear();
        reload();
    }

    /** depth 0 = the OpenEditz folder. */
    private void goToDepth(int depth) {
        while (trail.size() > Math.max(0, depth)) trail.remove(trail.size() - 1);
        items.clear();
        reload();
    }

    // ================================================================================== changes

    private void newFolder() {
        TextInputDialog d = new TextInputDialog("");
        d.setTitle("New folder");
        d.setHeaderText("Folder name");
        if (owner() != null) d.initOwner(owner());
        d.showAndWait().map(String::trim).filter(n -> !n.isEmpty()).ifPresent(name -> {
            final DriveNode in = currentFolder();
            background(() -> oe.createFolder(name, in), made -> reload(), this::handle);
        });
    }

    private void rename(DriveNode node) {
        TextInputDialog d = new TextInputDialog(node.name);
        d.setTitle("Rename");
        d.setHeaderText("Rename \u201c" + node.name + "\u201d");
        if (owner() != null) d.initOwner(owner());
        d.showAndWait().map(String::trim).filter(n -> !n.isEmpty() && !n.equals(node.name)).ifPresent(name ->
                background(() -> oe.rename(node, name), renamed -> reload(), this::handle));
    }

    private void delete(DriveNode node) {
        ButtonType del = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "This frees the space right away and cannot be undone.", del, ButtonType.CANCEL);
        a.setTitle("Delete");
        a.setHeaderText("Delete \u201c" + node.name + "\u201d for good?");
        if (owner() != null) a.initOwner(owner());
        a.showAndWait().filter(b -> b == del).ifPresent(b ->
                background(() -> { oe.delete(node); return Boolean.TRUE; }, ok -> reload(), this::handle));
    }

    // ================================================================================== upload

    private void chooseFilesToUpload() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Upload files to OpenEditz");
        List<File> files = chooser.showOpenMultipleDialog(owner());
        if (files == null) return;
        for (File f : files) startUpload(f);
    }

    private void startUpload(File file) {
        final long size = file.length();
        if (me != null && size > me.freeBytes()) {
            showMessage("Not enough space for \u201c" + file.getName() + "\u201d: " + ProjectBackup.formatBytes(me.freeBytes()) + " left.", true);
            return;
        }
        final DriveNode folder = currentFolder();
        final AtomicBoolean cancel = new AtomicBoolean();

        Label name = new Label(file.getName());
        name.setStyle("-fx-font-weight: bold;");
        Button stop = new Button("Stop");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        ProgressBar bar = new ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        Label status = new Label("Starting\u2026");
        status.getStyleClass().add("text-muted");
        VBox row = new VBox(4, new HBox(8, name, sp, stop), bar, status);
        stop.setOnAction(e -> { cancel.set(true); stop.setDisable(true); status.setText("Stopping\u2026"); });
        uploadRows.getChildren().add(row);
        refreshUploadsVisibility();

        FxCoalescer<Long> ui = new FxCoalescer<>(sent -> {
            if (cancel.get()) return;
            bar.setProgress(size == 0 ? 1 : Math.min(1.0, (double) sent / size));
            status.setText(ProjectBackup.formatBytes(sent) + " of " + ProjectBackup.formatBytes(size));
        });

        background(() -> oe.upload(file, folder, null, ui::post, cancel), node -> {
            finishRow(row, bar, stop, status, "Uploaded", 1.0, null);
            reload();
        }, err -> {
            if (err instanceof CancellationException) finishRow(row, bar, stop, status, "Stopped", bar.getProgress(), null);
            else finishRow(row, bar, stop, status, DriveError.describe(err), bar.getProgress(), "-fx-text-fill: -color-danger-fg;");
            reload();
        });
    }

    private void finishRow(VBox row, ProgressBar bar, Button stop, Label status, String text, double progress, String style) {
        bar.setProgress(progress);
        status.setText(text);
        status.setStyle(style == null ? "" : style);
        stop.setVisible(false);
        stop.setManaged(false);
        row.getProperties().put("finished", Boolean.TRUE);
    }

    private void refreshUploadsVisibility() {
        boolean any = !uploadRows.getChildren().isEmpty();
        uploadsBox.setVisible(any);
        uploadsBox.setManaged(any);
    }

    // ================================================================================== download / restore

    private static double fraction(long got, long total) { return total <= 0 ? -1 : Math.min(1.0, (double) got / total); }

    private static String percent(long got, long total) { return total <= 0 ? "" : String.format(" %.0f%%", Math.min(1.0, (double) got / total) * 100); }

    /** Downloads a project ZIP and unpacks it into the projects folder (a project that exists is never overwritten). */
    private void restore(DriveNode node) {
        TransferProgressWindow w = new TransferProgressWindow(owner(), "Restore project");
        w.show();
        w.post("Downloading \u201c" + node.name + "\u201d\u2026", 0);
        background(() -> {
            File zip = oe.download(node, got -> w.post("Downloading \u201c" + node.name + "\u201d\u2026" + percent(got, node.size), fraction(got, node.size)), w.cancelFlag());
            try {
                w.post("Restoring\u2026", -1);
                return ProjectRestore.restore(zip, Constants.getProjectsDirectory(), Constants.DEFAULT_PROJECT_PROPERTIES_FILENAME,
                        (f, entry) -> w.post(String.format("Restoring\u2026 %.0f%%", f * 100), f), w.cancelFlag());
            } finally {
                //noinspection ResultOfMethodCallIgnored
                zip.delete();
            }
        }, result -> {
            w.close();
            ProjectRepository.getInstance().refreshProjects();
            showMessage("Restored as \u201c" + result.folder.getName() + "\u201d; it is in your projects now."
                    + (result.hasProperties ? "" : " This ZIP has no project information in it, so it may not be listed as a project."), false);
        }, err -> {
            w.close();
            if (!(err instanceof CancellationException)) handle(err);
        });
    }

    private void saveAs(DriveNode node) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save a copy");
        chooser.setInitialFileName(OpenEditzClient.safeFileName(node.name));
        File dest = chooser.showSaveDialog(owner());
        if (dest == null) return;

        TransferProgressWindow w = new TransferProgressWindow(owner(), "Download");
        w.show();
        w.post("Downloading \u201c" + node.name + "\u201d\u2026", 0);
        background(() -> {
            oe.downloadTo(node, dest, got -> w.post("Downloading \u201c" + node.name + "\u201d\u2026" + percent(got, node.size), fraction(got, node.size)), w.cancelFlag());
            return dest;
        }, saved -> {
            w.close();
            showMessage("Saved to " + saved.getAbsolutePath(), false);
        }, err -> {
            w.close();
            if (!(err instanceof CancellationException)) handle(err);
        });
    }

    // ================================================================================== messages

    private void showMessage(String text, boolean error) {
        messageLabel.setText(text);
        messageLabel.setStyle(error ? "-fx-text-fill: -color-danger-fg;" : "-fx-text-fill: -color-success-fg;");
        messageLabel.setVisible(true);
        messageLabel.setManaged(true);
    }

    private void clearMessage() {
        messageLabel.setVisible(false);
        messageLabel.setManaged(false);
        messageLabel.setText("");
    }

    private void handle(Throwable err) {
        if (err instanceof CancellationException) return;
        err.printStackTrace();
        updateView();
        showMessage(DriveError.describe(err), true);
    }

    // ================================================================================== background work

    /** Runs {@code work} off the FX thread, then {@code ok} or {@code fail} back on it. */
    private static <T> void background(Callable<T> work, Consumer<T> ok, Consumer<Throwable> fail) {
        Thread t = new Thread(() -> {
            try {
                T value = work.call();
                Platform.runLater(() -> ok.accept(value));
            } catch (Throwable e) {
                Platform.runLater(() -> fail.accept(e));
            }
        }, "storage-pane");
        t.setDaemon(true);
        t.start();
    }

    // ================================================================================== one row

    private static boolean isProjectZip(DriveNode n) { return !n.isFolder && n.name.toLowerCase(Locale.ROOT).endsWith(".zip"); }

    private static FontIcon iconFor(DriveNode n) {
        if (n.isFolder) return new FontIcon(MaterialDesignF.FOLDER_OUTLINE);
        if (isProjectZip(n)) return new FontIcon(MaterialDesignF.FOLDER_UPLOAD);
        String name = n.name.toLowerCase(Locale.ROOT);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        switch (ext) {
            case "mp4": case "mov": case "mkv": case "avi": case "webm": case "m4v": return new FontIcon(MaterialDesignM.MOVIE);
            case "mp3": case "wav": case "m4a": case "aac": case "flac": case "ogg": return new FontIcon(MaterialDesignM.MUSIC_NOTE);
            case "png": case "jpg": case "jpeg": case "gif": case "webp": case "bmp": return new FontIcon(MaterialDesignI.IMAGE);
            default: return new FontIcon(MaterialDesignF.FILE_OUTLINE);
        }
    }

    private final class NodeCell extends ListCell<DriveNode> {
        @Override
        protected void updateItem(DriveNode n, boolean empty) {
            super.updateItem(n, empty);
            if (empty || n == null) {
                setGraphic(null);
                setText(null);
                setContextMenu(null);
                setOnMouseClicked(null);
                return;
            }
            FontIcon icon = iconFor(n);
            icon.setIconSize(22);
            icon.setStyle(n.isFolder ? "-fx-icon-color: -color-accent-fg;" : "-fx-icon-color: -color-fg-muted;");
            StackPane iconBox = new StackPane(icon);
            iconBox.setMinWidth(34);

            Label name = new Label(n.name);
            name.setStyle("-fx-font-weight: bold;");
            String when = n.updatedAt == null ? "" : (n.isFolder ? DATE.format(n.updatedAt) : DATE_TIME.format(n.updatedAt));
            Label meta = new Label(n.isFolder ? when : ProjectBackup.formatBytes(n.size) + (when.isEmpty() ? "" : " \u00b7 " + when));
            meta.getStyleClass().add("text-muted");
            VBox texts = new VBox(2, name, meta);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            ContextMenu menu = menuFor(n);
            Button more = new Button();
            more.setGraphic(new FontIcon(MaterialDesignD.DOTS_HORIZONTAL));
            more.getStyleClass().add("button-transparent");
            more.setOnAction(e -> menu.show(more, Side.BOTTOM, 0, 0));

            HBox row = new HBox(10, iconBox, texts, spacer, more);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
            setText(null);
            setContextMenu(menu);
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                    if (n.isFolder) enter(n);
                    else if (isProjectZip(n)) restore(n);
                    else saveAs(n);
                }
            });
        }
    }

    private ContextMenu menuFor(DriveNode n) {
        ContextMenu menu = new ContextMenu();
        if (n.isFolder) {
            MenuItem open = new MenuItem("Open");
            open.setOnAction(e -> enter(n));
            menu.getItems().add(open);
        } else {
            if (isProjectZip(n)) {
                MenuItem restore = new MenuItem("Restore into DoubleClips");
                restore.setOnAction(e -> restore(n));
                menu.getItems().add(restore);
            }
            MenuItem save = new MenuItem("Save a copy\u2026");
            save.setOnAction(e -> saveAs(n));
            menu.getItems().add(save);
        }
        MenuItem rename = new MenuItem("Rename\u2026");
        rename.setOnAction(e -> rename(n));
        MenuItem delete = new MenuItem("Delete\u2026");
        delete.setOnAction(e -> delete(n));
        menu.getItems().addAll(rename, delete);
        return menu;
    }
}
