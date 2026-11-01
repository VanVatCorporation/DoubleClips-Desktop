package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.i18n.I18n;

import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.ProjectRepository;
import com.vanvatcorporation.doubleclips.drive.DoubleClipsDrive;
import com.vanvatcorporation.doubleclips.drive.DriveError;
import com.vanvatcorporation.doubleclips.drive.DriveNode;
import com.vanvatcorporation.doubleclips.helper.ProjectBackup;
import com.vanvatcorporation.doubleclips.helper.TaskbarHelper;
import com.vanvatcorporation.doubleclips.ui.components.AppUi;
import com.vanvatcorporation.doubleclips.ui.components.TransferProgressWindow;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
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
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.CancellationException;

/**
 * "Back up a project": pick a local project, it is zipped (the same ZIP as Share) and uploaded to OpenEditz/Projects in
 * Van Vat Drive (iOS: BackupProjectView). {@link #backup} skips the picking (the project's context menu uses it).
 */
public final class BackupProjectWindow {
    private BackupProjectWindow() {}

    /** The picker. {@code onDone} runs on the FX thread after a backup finished or was stopped (may be null). */
    public static void show(Window owner, Runnable onDone) {
        if (!signedIn(owner)) return;

        ListView<ProjectData> list = new ListView<>();
        list.getItems().addAll(new ArrayList<>(ProjectRepository.getInstance().projectsProperty()));
        list.getStyleClass().add("storage-list");

        Label emptyTitle = new Label(I18n.t("No projects yet"));
        emptyTitle.getStyleClass().add("storage-empty-title");
        Label emptyHint = new Label(I18n.t("Create a project first, then come back to back it up."));
        emptyHint.getStyleClass().add("storage-meta");
        emptyHint.setWrapText(true);
        VBox empty = new VBox(8, AppUi.tile(MaterialDesignM.MOVIE, true, true), emptyTitle, emptyHint);
        empty.setAlignment(Pos.CENTER);
        empty.setPadding(new Insets(36, 20, 36, 20));
        list.setPlaceholder(empty);

        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(ProjectData p, boolean isEmpty) {
                super.updateItem(p, isEmpty);
                if (isEmpty || p == null) { setGraphic(null); setText(null); return; }
                Label title = new Label(p.getProjectTitle());
                title.getStyleClass().add("storage-row-name");
                Label meta = new Label("About " + ProjectBackup.formatBytes(p.getProjectSize()) + " \u00b7 "
                        + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(p.getProjectTimestamp())));
                meta.getStyleClass().add("storage-meta");
                VBox texts = new VBox(3, title, meta);
                HBox row = new HBox(12, AppUi.tile(MaterialDesignM.MOVIE, true, false), texts);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setMaxWidth(Double.MAX_VALUE);
                row.getStyleClass().add("storage-row");
                setGraphic(row);
                setText(null);
            }
        });

        Stage stage = new Stage();
        stage.setTitle(I18n.t("Back up a project"));
        if (owner != null) { stage.initOwner(owner); stage.initModality(Modality.WINDOW_MODAL); }

        Label header = new Label(I18n.t("Back up a project"));
        header.getStyleClass().add("storage-dialog-title");
        Label sub = new Label(I18n.t("Pick a project. It is packed into one ZIP and saved to OpenEditz/Projects in Van Vat Drive."));
        sub.getStyleClass().add("storage-subtitle");
        sub.setWrapText(true);

        Button cancel = AppUi.button("Cancel", null, false);
        Button go = AppUi.button("Back up", MaterialDesignF.FOLDER_UPLOAD, true);
        go.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(10, spacer, cancel, go);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox.setVgrow(list, Priority.ALWAYS);
        VBox listCard = new VBox(list);
        listCard.getStyleClass().add("storage-card");
        listCard.setPadding(new Insets(8));
        VBox.setVgrow(listCard, Priority.ALWAYS);

        VBox box = new VBox(16, new VBox(4, header, sub), listCard, buttons);
        box.getStyleClass().add("storage-dialog");
        box.setPadding(new Insets(24));

        Runnable choose = () -> {
            ProjectData p = list.getSelectionModel().getSelectedItem();
            if (p == null) return;
            stage.close();
            backup(owner, p, onDone);
        };
        go.setOnAction(e -> choose.run());
        list.setOnMouseClicked(e -> { if (e.getClickCount() == 2) choose.run(); });
        cancel.setOnAction(e -> stage.close());

        Scene scene = new Scene(box, 500, 540);
        AppUi.applyAppStyles(scene);
        stage.setScene(scene);
        stage.show();
    }

    /** Zips and uploads one project, with a progress window. {@code onDone} may be null. */
    public static void backup(Window owner, ProjectData project, Runnable onDone) {
        if (!signedIn(owner)) return;

        TransferProgressWindow w = new TransferProgressWindow(owner, "Back up \u201c" + project.getProjectTitle() + "\u201d");
        w.show();
        w.post("Packing \u201c" + project.getProjectTitle() + "\u201d\u2026", 0);

        Thread t = new Thread(() -> {
            try {
                DriveNode node = ProjectBackup.run(project, new ProjectBackup.Listener() {
                    @Override public void onZipping(double f) {
                        w.post(String.format("Packing \u201c%s\u201d\u2026 %.0f%%", project.getProjectTitle(), f * 100), f);
                        TaskbarHelper.updateProgress(f);
                    }
                    @Override public void onUploading(double f) {
                        w.post(String.format("Uploading \u201c%s\u201d\u2026 %.0f%%", project.getProjectTitle(), f * 100), f);
                        TaskbarHelper.updateProgress(f);
                    }
                    @Override public boolean confirmDailyUpload(long size, long freeToday) {
                        return com.vanvatcorporation.doubleclips.ui.components.QuotaUi.confirmContinueAnyThread(owner,
                                com.vanvatcorporation.doubleclips.ui.components.QuotaUi.UPLOAD_TITLE,
                                com.vanvatcorporation.doubleclips.ui.components.QuotaUi.uploadMessage(size, freeToday));
                    }
                }, w.cancelFlag());
                Platform.runLater(() -> {
                    w.close();
                    TaskbarHelper.stopProgress();
                    alert(owner, Alert.AlertType.INFORMATION, I18n.t("Backed up to OpenEditz/Projects"), "Saved as:\n" + node.name);
                    if (onDone != null) onDone.run();
                });
            } catch (CancellationException e) {
                Platform.runLater(() -> { w.close(); TaskbarHelper.stopProgress(); if (onDone != null) onDone.run(); });
            } catch (Throwable e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    w.close();
                    TaskbarHelper.stopProgress();
                    if (e instanceof DriveError && ((DriveError) e).isTosRequired()) {
                        // New terms were published: the popup (DoubleClipsDrive's tos_required hook) is on its way; say why this stopped.
                        alert(owner, Alert.AlertType.INFORMATION, I18n.t("Terms of Service"), "Please accept the updated Terms of Service and Privacy Policy, then back up again.");
                    } else if (e instanceof DriveError && ((DriveError) e).isQuotaLimit()) {
                        com.vanvatcorporation.doubleclips.ui.components.QuotaUi.blocked(owner, "Couldn't back up the project", DriveError.describe(e));
                    } else {
                        alert(owner, Alert.AlertType.ERROR, I18n.t("Couldn't back up the project"), DriveError.describe(e));
                    }
                    if (onDone != null) onDone.run();
                });
            }
        }, "backup-project");
        t.setDaemon(true);
        t.start();
    }

    private static boolean signedIn(Window owner) {
        if (DoubleClipsDrive.hasSession()) return true;
        alert(owner, Alert.AlertType.INFORMATION, I18n.t("Sign in to back up"), "Sign in from the Profile tab to back up your projects to OpenEditz.");
        return false;
    }

    private static void alert(Window owner, Alert.AlertType type, String header, String text) {
        Alert a = new Alert(type, text, ButtonType.OK);
        a.setTitle(I18n.t("Storage"));
        a.setHeaderText(header);
        if (owner != null) a.initOwner(owner);
        a.show();
    }
}
