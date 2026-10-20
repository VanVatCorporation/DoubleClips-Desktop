package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.ProjectRepository;
import com.vanvatcorporation.doubleclips.drive.DoubleClipsDrive;
import com.vanvatcorporation.doubleclips.drive.DriveError;
import com.vanvatcorporation.doubleclips.drive.DriveNode;
import com.vanvatcorporation.doubleclips.helper.ProjectBackup;
import com.vanvatcorporation.doubleclips.helper.TaskbarHelper;
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
        Label empty = new Label("No projects yet. Create a project first, then come back to back it up.");
        empty.setWrapText(true);
        list.setPlaceholder(empty);
        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(ProjectData p, boolean isEmpty) {
                super.updateItem(p, isEmpty);
                if (isEmpty || p == null) { setGraphic(null); return; }
                Label title = new Label(p.getProjectTitle());
                title.setStyle("-fx-font-weight: bold;");
                Label meta = new Label("About " + ProjectBackup.formatBytes(p.getProjectSize()) + " \u00b7 "
                        + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(p.getProjectTimestamp())));
                meta.getStyleClass().add("text-muted");
                setGraphic(new VBox(2, title, meta));
            }
        });

        Stage stage = new Stage();
        stage.setTitle("Back up a project");
        if (owner != null) { stage.initOwner(owner); stage.initModality(Modality.WINDOW_MODAL); }

        Label header = new Label("Choose a project to back up to OpenEditz/Projects");
        Button cancel = new Button("Cancel");
        Button go = new Button("Back up");
        go.getStyleClass().add("button-primary");
        go.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancel, go);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox box = new VBox(12, header, list, buttons);
        box.setPadding(new Insets(18));

        Runnable choose = () -> {
            ProjectData p = list.getSelectionModel().getSelectedItem();
            if (p == null) return;
            stage.close();
            backup(owner, p, onDone);
        };
        go.setOnAction(e -> choose.run());
        list.setOnMouseClicked(e -> { if (e.getClickCount() == 2) choose.run(); });
        cancel.setOnAction(e -> stage.close());

        stage.setScene(new Scene(box, 460, 420));
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
                }, w.cancelFlag());
                Platform.runLater(() -> {
                    w.close();
                    TaskbarHelper.stopProgress();
                    alert(owner, Alert.AlertType.INFORMATION, "Backed up to OpenEditz/Projects", "Saved as:\n" + node.name);
                    if (onDone != null) onDone.run();
                });
            } catch (CancellationException e) {
                Platform.runLater(() -> { w.close(); TaskbarHelper.stopProgress(); if (onDone != null) onDone.run(); });
            } catch (Throwable e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    w.close();
                    TaskbarHelper.stopProgress();
                    alert(owner, Alert.AlertType.ERROR, "Couldn't back up the project", DriveError.describe(e));
                    if (onDone != null) onDone.run();
                });
            }
        }, "backup-project");
        t.setDaemon(true);
        t.start();
    }

    private static boolean signedIn(Window owner) {
        if (DoubleClipsDrive.hasSession()) return true;
        alert(owner, Alert.AlertType.INFORMATION, "Sign in to back up", "Sign in from the Profile tab to back up your projects to OpenEditz.");
        return false;
    }

    private static void alert(Window owner, Alert.AlertType type, String header, String text) {
        Alert a = new Alert(type, text, ButtonType.OK);
        a.setTitle("Storage");
        a.setHeaderText(header);
        if (owner != null) a.initOwner(owner);
        a.show();
    }
}
