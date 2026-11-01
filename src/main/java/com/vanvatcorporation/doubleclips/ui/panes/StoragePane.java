package com.vanvatcorporation.doubleclips.ui.panes;

import com.vanvatcorporation.doubleclips.i18n.I18n;

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
import com.vanvatcorporation.doubleclips.ui.components.AppUi;
import com.vanvatcorporation.doubleclips.ui.components.FxCoalescer;
import com.vanvatcorporation.doubleclips.ui.components.QuotaUi;
import com.vanvatcorporation.doubleclips.ui.components.StorageThumbs;
import com.vanvatcorporation.doubleclips.ui.components.TermsGate;
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
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
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
 * Looks come from the "storage-*" / "app-*" classes at the end of style.css.
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
    private final VBox contentBox = new VBox(14);
    private final VBox signInBox = new VBox(10);
    private final HBox breadcrumb = new HBox(2);
    private final ProgressBar quotaBar = new ProgressBar(0);
    private final Label quotaLabel = new Label();
    private final VBox usageBox = new VBox(10);
    private final Label planLabel = new Label();
    private final VBox uploadRows = new VBox(8);
    private final VBox uploadsBox = new VBox(8);
    private final Label messageLabel = new Label();
    private final Label emptyTitle = new Label();
    private final Label emptyHint = new Label();
    private final ListView<DriveNode> list = new ListView<>(items);

    public StoragePane() {
        TermsGate.addAcceptedListener(this::reload);       // terms accepted (here or after a tos_required answer): load the list
        setSpacing(20);
        setPadding(new Insets(32, 36, 28, 36));
        getStyleClass().addAll("content-pane", "storage-page");

        // ---- header
        Label title = new Label(I18n.t("Storage"));
        title.getStyleClass().add("storage-title");
        Label subtitle = new Label(I18n.t("Your OpenEditz folder in Van Vat Drive"));
        subtitle.getStyleClass().add("storage-subtitle");
        VBox titles = new VBox(4, title, subtitle);

        Button backupBtn = AppUi.button("Back up a project", MaterialDesignF.FOLDER_UPLOAD, true);
        Button uploadBtn = AppUi.button("Upload files", MaterialDesignU.UPLOAD_OUTLINE, false);
        Button folderBtn = AppUi.button("New folder", MaterialDesignF.FOLDER_OUTLINE, false);
        Button refreshBtn = AppUi.button("Refresh", MaterialDesignR.REFRESH, false);
        for (Button b : new Button[]{backupBtn, uploadBtn, folderBtn, refreshBtn}) b.disableProperty().bind(signedIn.not());
        backupBtn.setOnAction(e -> BackupProjectWindow.show(owner(), this::reload));
        uploadBtn.setOnAction(e -> chooseFilesToUpload());
        folderBtn.setOnAction(e -> newFolder());
        refreshBtn.setOnAction(e -> reload());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, titles, spacer, backupBtn, uploadBtn, folderBtn, refreshBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        // ---- signed-out view
        Label signInTitle = new Label(I18n.t("Sign in to use Storage"));
        signInTitle.getStyleClass().add("storage-empty-title");
        Label signInText = new Label(I18n.t("Sign in from the Profile tab to back up your projects to OpenEditz and restore them on any device."));
        signInText.getStyleClass().add("storage-meta");
        signInText.setWrapText(true);
        signInText.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        signInBox.getChildren().addAll(AppUi.tile(MaterialDesignF.FOLDER_UPLOAD, true, true), signInTitle, signInText);
        signInBox.getStyleClass().add("storage-card");
        signInBox.setAlignment(Pos.CENTER);
        signInBox.setPadding(new Insets(36, 40, 36, 40));
        signInBox.setMaxSize(440, Region.USE_PREF_SIZE);

        // ---- signed-in view: quota card
        Label quotaTitle = new Label(I18n.t("Storage used"));
        quotaTitle.getStyleClass().add("storage-section-title");
        quotaLabel.getStyleClass().add("storage-meta");
        Region quotaSpacer = new Region();
        HBox.setHgrow(quotaSpacer, Priority.ALWAYS);
        quotaBar.getStyleClass().add("storage-bar");
        quotaBar.setMaxWidth(Double.MAX_VALUE);
        // Minimalist: only the storage bar shows; the arrow slides the plan, daily limits and upgrade link open.
        planLabel.getStyleClass().add("storage-meta");
        VBox quotaDetails = new VBox(10, planLabel, usageBox, QuotaUi.moreResourcesLink());
        javafx.scene.control.Button quotaArrow = QuotaUi.collapseArrow(quotaDetails);
        VBox quotaCard = new VBox(10, new HBox(8, quotaTitle, quotaSpacer, quotaLabel, quotaArrow), quotaBar, quotaDetails);
        quotaCard.getStyleClass().add("storage-card");
        quotaCard.setPadding(new Insets(16, 18, 16, 18));

        breadcrumb.setAlignment(Pos.CENTER_LEFT);

        // ---- uploads
        Label uploadsTitle = new Label(I18n.t("Uploads"));
        uploadsTitle.getStyleClass().add("storage-section-title");
        Button clearUploads = AppUi.button("Clear finished", null, false);
        clearUploads.setOnAction(e -> {
            uploadRows.getChildren().removeIf(n -> Boolean.TRUE.equals(n.getProperties().get("finished")));
            refreshUploadsVisibility();
        });
        Region upSpacer = new Region();
        HBox.setHgrow(upSpacer, Priority.ALWAYS);
        HBox uploadsHeader = new HBox(8, uploadsTitle, upSpacer, clearUploads);
        uploadsHeader.setAlignment(Pos.CENTER_LEFT);
        uploadsBox.getChildren().addAll(uploadsHeader, uploadRows);
        uploadsBox.setVisible(false);
        uploadsBox.setManaged(false);

        // ---- messages
        messageLabel.getStyleClass().add("storage-banner");
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(Double.MAX_VALUE);
        messageLabel.setVisible(false);
        messageLabel.setManaged(false);

        // ---- the list (inside one rounded card)
        emptyTitle.getStyleClass().add("storage-empty-title");
        emptyHint.getStyleClass().add("storage-meta");
        emptyHint.setWrapText(true);
        emptyHint.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        emptyHint.managedProperty().bind(emptyHint.textProperty().isNotEmpty());
        emptyHint.visibleProperty().bind(emptyHint.textProperty().isNotEmpty());
        VBox emptyBox = new VBox(8, AppUi.tile(MaterialDesignF.FOLDER_OUTLINE, true, true), emptyTitle, emptyHint);
        emptyBox.setAlignment(Pos.CENTER);
        emptyBox.setPadding(new Insets(48, 24, 48, 24));
        setEmptyText(I18n.t("Loading\u2026"), "");

        list.getStyleClass().add("storage-list");
        list.setPlaceholder(emptyBox);
        list.setCellFactory(v -> new NodeCell());
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox listCard = new VBox(list);
        listCard.getStyleClass().add("storage-card");
        listCard.setPadding(new Insets(8));
        VBox.setVgrow(listCard, Priority.ALWAYS);

        contentBox.getChildren().addAll(quotaCard, breadcrumb, messageLabel, uploadsBox, listCard);

        StackPane body = new StackPane(contentBox, signInBox);
        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().addAll(header, body);

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

    private void setEmptyText(String title, String hint) {
        emptyTitle.setText(title);
        emptyHint.setText(hint);
    }

    private void updateView() {
        boolean has = DoubleClipsDrive.hasSession();
        signedIn.set(has);
        contentBox.setVisible(has);
        contentBox.setManaged(has);
        signInBox.setVisible(!has);
        signInBox.setManaged(!has);
    }

    /** The user still has to accept the current Terms of Service / Privacy Policy (from /me). */
    private boolean termsPending() { return me != null && me.legalRequired(); }

    /** The blocking popup; once accepted the list loads. */
    private void showTerms() {
        if (termsPending()) TermsGate.show(owner(), DoubleClipsDrive.client, me.legal, null);
    }

    private void reload() {
        updateView();
        renderBreadcrumb();
        if (!signedIn.get()) return;

        final int seq = loadSeq.incrementAndGet();
        final DriveNode folder = currentFolder();
        setEmptyText(I18n.t("Loading\u2026"), "");
        background(() -> {
            // /me first: until the Terms of Service are accepted the listing is refused, and /me says what to show.
            DriveMe fresh = null;
            try { fresh = DoubleClipsDrive.client.me(); } catch (IOException ignored) { /* the list is what matters */ }
            if (fresh != null && fresh.legalRequired()) return new Object[]{null, fresh};
            List<DriveNode> children = oe.list(folder);
            return new Object[]{children, fresh};
        }, result -> {
            if (seq != loadSeq.get()) return;                // a newer load is on its way
            if (result[0] == null) {                          // terms not accepted yet: nothing to list, show the popup
                me = (DriveMe) result[1];
                setEmptyText(I18n.t("Accept the Terms of Service to continue"), "");
                renderQuota();
                showTerms();
                return;
            }
            @SuppressWarnings("unchecked") List<DriveNode> children = (List<DriveNode>) result[0];
            List<DriveNode> sorted = new ArrayList<>(children);
            sorted.sort((x, y) -> {
                if (x.isFolder != y.isFolder) return x.isFolder ? -1 : 1;
                return x.name.compareToIgnoreCase(y.name);
            });
            items.setAll(sorted);
            if (result[1] != null) me = (DriveMe) result[1];
            setEmptyText(I18n.t("Nothing here yet"), I18n.t("Back up a project or upload files and they show up here."));
            renderQuota();
            clearMessage();
        }, err -> {
            if (seq != loadSeq.get()) return;
            setEmptyText(I18n.t("Couldn't load this folder"), "");
            handle(err);
        });
    }

    private void renderQuota() {
        usageBox.getChildren().clear();
        if (me == null || me.quotaBytes <= 0) {
            quotaBar.setProgress(0);
            quotaLabel.setText("");
            return;
        }
        QuotaUi.applyTone(quotaBar, me.usedBytes, me.quotaBytes);
        quotaLabel.setText(ProjectBackup.formatBytes(me.usedBytes) + " of " + ProjectBackup.formatBytes(me.quotaBytes));
        planLabel.setText(me.planName == null ? "" : "Plan: " + me.planName);
        planLabel.setManaged(me.planName != null);
        planLabel.setVisible(me.planName != null);

        if (me.hasUsage) {
            usageBox.getChildren().add(QuotaUi.usageRow("Today: \u2191 " + ProjectBackup.formatBytes(me.uploadedBytes) + " / "
                    + ProjectBackup.formatBytes(me.uploadLimit), me.uploadedBytes, me.uploadLimit));
            usageBox.getChildren().add(QuotaUi.usageRow("Today: \u2193 " + ProjectBackup.formatBytes(me.downloadedBytes) + " / "
                    + ProjectBackup.formatBytes(me.downloadLimit), me.downloadedBytes, me.downloadLimit));
            if (me.hasCompute) {
                usageBox.getChildren().add(QuotaUi.usageRow("Today: compute " + QuotaUi.credits(me.computeUsed) + " / "
                        + QuotaUi.credits(me.computeLimit) + " credits", me.computeUsed, me.computeLimit));
            }
        }
    }

    private void renderBreadcrumb() {
        breadcrumb.getChildren().clear();
        breadcrumb.setVisible(!trail.isEmpty());
        breadcrumb.setManaged(!trail.isEmpty());
        if (trail.isEmpty()) return;
        breadcrumb.getChildren().add(crumb(I18n.t("OpenEditz"), 0));
        for (int i = 0; i < trail.size(); i++) {
            Label sep = new Label("\u203a");
            sep.getStyleClass().add("storage-meta");
            breadcrumb.getChildren().add(sep);
            DriveNode f = trail.get(i);
            if (i == trail.size() - 1) {
                Label last = new Label(f.name);
                last.getStyleClass().add("storage-crumb-current");
                breadcrumb.getChildren().add(last);
            } else {
                breadcrumb.getChildren().add(crumb(f.name, i + 1));
            }
        }
    }

    private Hyperlink crumb(String text, int depth) {
        Hyperlink link = new Hyperlink(text);
        link.getStyleClass().add("storage-crumb");
        link.setOnAction(e -> goToDepth(depth));
        return link;
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
        d.setTitle(I18n.t("New folder"));
        d.setHeaderText(I18n.t("Folder name"));
        if (owner() != null) d.initOwner(owner());
        d.showAndWait().map(String::trim).filter(n -> !n.isEmpty()).ifPresent(name -> {
            final DriveNode in = currentFolder();
            background(() -> oe.createFolder(name, in), made -> reload(), this::handle);
        });
    }

    private void rename(DriveNode node) {
        TextInputDialog d = new TextInputDialog(node.name);
        d.setTitle(I18n.t("Rename"));
        d.setHeaderText("Rename \u201c" + node.name + "\u201d");
        if (owner() != null) d.initOwner(owner());
        d.showAndWait().map(String::trim).filter(n -> !n.isEmpty() && !n.equals(node.name)).ifPresent(name ->
                background(() -> oe.rename(node, name), renamed -> reload(), this::handle));
    }

    private void delete(DriveNode node) {
        ButtonType del = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "This frees the space right away and cannot be undone.", del, ButtonType.CANCEL);
        a.setTitle(I18n.t("Delete"));
        a.setHeaderText("Delete \u201c" + node.name + "\u201d for good?");
        if (owner() != null) a.initOwner(owner());
        a.showAndWait().filter(b -> b == del).ifPresent(b ->
                background(() -> { oe.delete(node); return Boolean.TRUE; }, ok -> reload(), this::handle));
    }

    // ================================================================================== upload

    private void chooseFilesToUpload() {
        if (termsPending()) { showTerms(); return; }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("Upload files to OpenEditz"));
        List<File> files = chooser.showOpenMultipleDialog(owner());
        if (files == null) return;
        // The daily cap only warns (like the web Drive): one question for the whole selection.
        long total = 0;
        for (File f : files) total += f.length();
        long freeToday = me == null ? Long.MAX_VALUE : me.uploadFree();
        if (total > freeToday && !QuotaUi.confirmContinue(owner(), QuotaUi.UPLOAD_TITLE, QuotaUi.uploadMessage(total, freeToday))) return;
        for (File f : files) startUpload(f);
    }

    private void startUpload(File file) {
        if (termsPending()) { showTerms(); return; }
        final long size = file.length();
        if (me != null && size > me.freeBytes()) {
            QuotaUi.blocked(owner(), "Not enough space", "Not enough space for \u201c" + file.getName() + "\u201d: "
                    + ProjectBackup.formatBytes(me.freeBytes()) + " left of " + ProjectBackup.formatBytes(me.quotaBytes) + ".");
            return;
        }
        final DriveNode folder = currentFolder();
        final AtomicBoolean cancel = new AtomicBoolean();

        StackPane tile = AppUi.tile(MaterialDesignU.UPLOAD_OUTLINE, true, false);
        Label name = new Label(file.getName());
        name.getStyleClass().add("storage-row-name");
        name.setMaxWidth(Double.MAX_VALUE);
        Label status = new Label(I18n.t("Starting\u2026"));
        status.getStyleClass().add("storage-meta");
        ProgressBar bar = new ProgressBar(0);
        bar.getStyleClass().add("storage-bar");
        bar.setMaxWidth(Double.MAX_VALUE);
        Button stop = AppUi.button("Stop", MaterialDesignC.CLOSE, false);
        stop.setOnAction(e -> { cancel.set(true); stop.setDisable(true); status.setText(I18n.t("Stopping\u2026")); });

        VBox texts = new VBox(6, name, bar, status);
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox row = new HBox(12, tile, texts, stop);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("storage-upload");
        uploadRows.getChildren().add(row);
        refreshUploadsVisibility();

        FxCoalescer<Long> ui = new FxCoalescer<>(sent -> {
            if (cancel.get()) return;
            bar.setProgress(size == 0 ? 1 : Math.min(1.0, (double) sent / size));
            status.setText(ProjectBackup.formatBytes(sent) + " of " + ProjectBackup.formatBytes(size));
        });

        background(() -> oe.upload(file, folder, null, ui::post, cancel), node -> {
            finishRow(row, bar, stop, status, I18n.t("Uploaded"), 1.0, false);
            reload();
        }, err -> {
            if (err instanceof CancellationException) finishRow(row, bar, stop, status, I18n.t("Stopped"), bar.getProgress(), false);
            else {
                finishRow(row, bar, stop, status, DriveError.describe(err), bar.getProgress(), true);
                if (err instanceof DriveError && ((DriveError) err).isQuotaLimit()) texts.getChildren().add(QuotaUi.moreResourcesLink());
            }
            reload();
        });
    }

    private void finishRow(HBox row, ProgressBar bar, Button stop, Label status, String text, double progress, boolean error) {
        bar.setProgress(progress);
        status.setText(text);
        if (error) status.getStyleClass().add("storage-meta-error");
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

    /** Downloading spends today's download allowance: warn first when the file probably won't fit (the user may continue). */
    private boolean confirmDownload(DriveNode node) {
        long free = me == null ? Long.MAX_VALUE : me.downloadFree();
        return node.size <= free || QuotaUi.confirmContinue(owner(), QuotaUi.DOWNLOAD_TITLE, QuotaUi.downloadMessage(node.size, free));
    }

    private static double fraction(long got, long total) { return total <= 0 ? -1 : Math.min(1.0, (double) got / total); }

    private static String percent(long got, long total) { return total <= 0 ? "" : String.format(" %.0f%%", Math.min(1.0, (double) got / total) * 100); }

    /** Downloads a project ZIP and unpacks it into the projects folder (a project that exists is never overwritten). */
    private void restore(DriveNode node) {
        if (termsPending()) { showTerms(); return; }
        if (!confirmDownload(node)) return;
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
            reload();                                     // the download spent part of today's allowance
            showMessage("Restored as \u201c" + result.folder.getName() + "\u201d; it is in your projects now."
                    + (result.hasProperties ? "" : " This ZIP has no project information in it, so it may not be listed as a project."), false);
        }, err -> {
            w.close();
            if (!(err instanceof CancellationException)) handle(err);
        });
    }

    private void saveAs(DriveNode node) {
        if (termsPending()) { showTerms(); return; }
        if (!confirmDownload(node)) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("Save a copy"));
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
            reload();
            showMessage("Saved to " + saved.getAbsolutePath(), false);
        }, err -> {
            w.close();
            if (!(err instanceof CancellationException)) handle(err);
        });
    }

    // ================================================================================== messages

    private void showMessage(String text, boolean error) {
        messageLabel.setText(text);
        messageLabel.getStyleClass().removeAll("storage-banner-ok", "storage-banner-error");
        messageLabel.getStyleClass().add(error ? "storage-banner-error" : "storage-banner-ok");
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
        if (err instanceof DriveError && ((DriveError) err).isQuotaLimit()) {
            QuotaUi.blocked(owner(), "Limit reached", DriveError.describe(err));   // explains it and offers the upgrade
            return;
        }
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
            StackPane tile = new StackPane(iconFor(n));
            tile.getStyleClass().add("storage-tile");
            if (n.isFolder || isProjectZip(n)) tile.getStyleClass().add("storage-tile-accent");
            // Image thumbnails follow the account's "Preview thumbnails" setting (Settings > VanVatDrive).
            if ((me == null || me.previewThumbnails) && StorageThumbs.isThumbnailable(n)) {
                StorageThumbs.load(DoubleClipsDrive.client, n, tile, () -> getItem() == n);
            }

            Label name = new Label(n.name);
            name.getStyleClass().add("storage-row-name");
            String when = n.updatedAt == null ? "" : (n.isFolder ? DATE.format(n.updatedAt) : DATE_TIME.format(n.updatedAt));
            Label meta = new Label(n.isFolder ? when : ProjectBackup.formatBytes(n.size) + (when.isEmpty() ? "" : " \u00b7 " + when));
            meta.getStyleClass().add("storage-meta");
            VBox texts = new VBox(3, name, meta);
            HBox.setHgrow(texts, Priority.ALWAYS);

            ContextMenu menu = menuFor(n);
            Button more = new Button();
            more.setGraphic(new FontIcon(MaterialDesignD.DOTS_HORIZONTAL));
            more.getStyleClass().add("storage-more");
            more.setFocusTraversable(false);
            more.setOnAction(e -> menu.show(more, Side.BOTTOM, 0, 0));

            HBox row = new HBox(12, tile, texts, more);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMaxWidth(Double.MAX_VALUE);
            row.getStyleClass().add("storage-row");
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
            MenuItem open = new MenuItem(I18n.t("Open"), new FontIcon(MaterialDesignF.FOLDER_OUTLINE));
            open.setOnAction(e -> enter(n));
            menu.getItems().add(open);
        } else {
            if (isProjectZip(n)) {
                MenuItem restore = new MenuItem(I18n.t("Restore into DoubleClips"), new FontIcon(MaterialDesignR.RESTORE));
                restore.setOnAction(e -> restore(n));
                menu.getItems().add(restore);
            }
            MenuItem save = new MenuItem(I18n.t("Save a copy\u2026"), new FontIcon(MaterialDesignC.CONTENT_COPY));
            save.setOnAction(e -> saveAs(n));
            menu.getItems().add(save);
        }
        MenuItem rename = new MenuItem(I18n.t("Rename\u2026"), new FontIcon(MaterialDesignP.PENCIL_OUTLINE));
        rename.setOnAction(e -> rename(n));
        FontIcon trash = new FontIcon(MaterialDesignD.DELETE_OUTLINE);
        trash.getStyleClass().add("danger-icon");
        MenuItem delete = new MenuItem(I18n.t("Delete\u2026"), trash);
        delete.setOnAction(e -> delete(n));
        menu.getItems().addAll(rename, delete);
        return menu;
    }
}
