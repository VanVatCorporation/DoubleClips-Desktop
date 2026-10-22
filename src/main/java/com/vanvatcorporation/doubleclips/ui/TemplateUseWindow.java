package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.TemplateRenderer;
import com.vanvatcorporation.doubleclips.TemplateTimelineInfo;
import com.vanvatcorporation.doubleclips.TemplateTimelineLoader;
import com.vanvatcorporation.doubleclips.TemplateUseModel;
import com.vanvatcorporation.doubleclips.data.TemplateData;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;
import com.vanvatcorporation.doubleclips.helper.MediaHelper;
import com.vanvatcorporation.doubleclips.ui.components.AppUi;
import com.vanvatcorporation.doubleclips.ui.components.MediaThumbnails;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * "Use template" through OpenGL (iOS: TemplateExportView): choose a photo or video for each replaceable clip of the
 * template, then render it with the same engine as the editor. Two steps like CapCut: first one tile per GROUP of clips that
 * share a source (they take one media), then every clip (to give single ones their own media or trim them). The rules live
 * in {@link TemplateUseModel}; the render in {@link TemplateRenderer}, whose result goes to the normal export window.
 */
public final class TemplateUseWindow extends Stage {
    private static TemplateUseWindow current;

    /** Opens the window for a template (a window already open for another one is closed). */
    public static void showInstance(TemplateData data) {
        if (current != null) current.close();
        current = new TemplateUseWindow(data);
        current.show();
    }

    private static final double TILE = 124;
    /** The dot colours of the groups (the same sequence the first stage uses). */
    private static final Color[] PALETTE = {
            Color.web("#007AFF"), Color.web("#34C759"), Color.web("#FF9500"), Color.web("#AF52DE"),
            Color.web("#FF2D55"), Color.web("#5AC8FA"), Color.web("#FFCC00"), Color.web("#8E8E93")};
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "mov", "mkv", "avi", "webm", "m4v", "mpg", "mpeg", "wmv", "flv", "3gp", "ts", "mts");
    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "bmp", "gif", "webp", "tif", "tiff");

    private final TemplateData data;
    private final TextArea logArea = new TextArea();
    private final TemplateUseModel model;
    private final MediaThumbnails thumbs = new MediaThumbnails();

    private TemplateTimelineInfo info;
    private String timelineProblem;
    private boolean preparing;
    private boolean importing;
    private TemplateRenderer.Prepared lastPrepared;
    private ExportWindow activeExport;

    private final Label subtitle = new Label("Loading the template\u2026");
    private final Label banner = new Label();
    private final Label sectionTitle = new Label();
    private final Label sectionHint = new Label();
    private final FlowPane tiles = new FlowPane(16, 14);
    private final HBox importingRow = new HBox(8);
    private final Button backBtn = AppUi.button("Back to groups", MaterialDesignC.CHEVRON_LEFT, false);
    private final Button nextBtn = AppUi.button("Next: check every clip", null, true);
    private final Button exportBtn = AppUi.button("Export", null, true);

    private TemplateUseWindow(TemplateData data) {
        this.data = data;
        this.model = new TemplateUseModel(data.getTemplateClipCount(), this::appendLog);

        // ---- header
        Label title = new Label(data.getTemplateTitle() == null || data.getTemplateTitle().isEmpty() ? "Use template" : data.getTemplateTitle());
        title.getStyleClass().add("storage-dialog-title");
        subtitle.getStyleClass().add("storage-subtitle");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, backBtn, new VBox(3, title, subtitle), spacer, exportBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(18, 24, 14, 24));

        // ---- the tiles card
        banner.getStyleClass().addAll("storage-banner", "storage-banner-error");
        banner.setWrapText(true);
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setVisible(false);
        banner.setManaged(false);

        sectionTitle.getStyleClass().add("storage-section-title");
        sectionHint.getStyleClass().add("tpl-hint");
        sectionHint.setWrapText(true);
        tiles.setPrefWrapLength(800);
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(18, 18);
        Label importingLabel = new Label("Reading your files\u2026");
        importingLabel.getStyleClass().add("storage-meta");
        importingRow.getChildren().addAll(spinner, importingLabel);
        importingRow.setAlignment(Pos.CENTER_LEFT);
        importingRow.setVisible(false);
        importingRow.setManaged(false);

        Region nextSpacer = new Region();
        HBox.setHgrow(nextSpacer, Priority.ALWAYS);
        HBox nextRow = new HBox(nextSpacer, nextBtn);
        nextRow.managedProperty().bind(nextBtn.visibleProperty());
        nextRow.visibleProperty().bind(nextBtn.visibleProperty());

        VBox card = new VBox(12, sectionTitle, sectionHint, importingRow, tiles, nextRow);
        card.getStyleClass().add("storage-card");
        card.setPadding(new Insets(18));

        // ---- the log
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(7);
        TitledPane logPane = new TitledPane("Log", logArea);
        logPane.setExpanded(false);

        VBox content = new VBox(16, banner, card, logPane);
        content.setPadding(new Insets(0, 24, 24, 24));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox root = new VBox(header, scroll);
        root.getStyleClass().add("storage-dialog");

        setTitle("Use template");
        initOwner(DoubleClipsDesktop.getInstance().getPrimaryStage());
        initModality(Modality.NONE);
        Scene scene = new Scene(root, 940, 720);
        AppUi.applyAppStyles(scene);
        setScene(scene);
        setMinWidth(620);
        setMinHeight(480);

        backBtn.setOnAction(e -> { model.stage = TemplateUseModel.Stage.GROUPS; render(); });
        nextBtn.setOnAction(e -> { model.stage = TemplateUseModel.Stage.CLIPS; render(); });
        exportBtn.setOnAction(e -> startExport());
        setOnHidden(e -> {
            thumbs.shutdown();
            if (current == this) current = null;
        });

        render();
        loadTimeline();
    }

    // ================================================================================== timeline

    private void loadTimeline() {
        if (data.getTemplateTimelineLink().isEmpty()) {
            timelineProblem = "This template has no timeline (it is an older template), so it can only be rendered with FFmpeg.";
            render();
            return;
        }
        Thread t = new Thread(() -> {
            try {
                TemplateTimelineInfo loaded = TemplateTimelineLoader.load(data);
                Platform.runLater(() -> {
                    info = loaded;
                    model.configure(loaded);
                    render();
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> {
                    timelineProblem = "The template's timeline couldn't be loaded: " + e.getMessage();
                    appendLog(timelineProblem);
                    render();
                });
            }
        }, "TemplateUseLoad");
        t.setDaemon(true);
        t.start();
    }

    /** The settings the template was made for: its canvas, rendered with OpenGL. A fresh object each time (the export window edits it). */
    private VideoSettings buildSettings() {
        VideoSettings s = VideoSettings.createDefault();
        if (info != null && info.canvas != null) {
            if (info.canvas.videoWidth > 0 && info.canvas.videoHeight > 0) {
                s.videoWidth = info.canvas.videoWidth;
                s.videoHeight = info.canvas.videoHeight;
            }
            if (info.canvas.frameRate > 0) s.frameRate = info.canvas.frameRate;
            if (info.canvas.isStretchToFull != null) s.isStretchToFull = info.canvas.isStretchToFull;
        }
        s.setRenderEngine("opengl");
        return s;
    }

    // ================================================================================== drawing

    private void appendLog(String line) {
        String text = logArea.getText();
        if (text.length() > 20000) text = text.substring(text.length() - 10000);
        logArea.setText(text.isEmpty() ? line : text + "\n" + line);
        logArea.positionCaret(logArea.getText().length());
    }

    private void render() {
        boolean groupsStage = model.stage == TemplateUseModel.Stage.GROUPS && model.hasGroups();
        boolean busy = preparing || activeExport != null;

        if (timelineProblem != null) {
            banner.setText(timelineProblem);
            banner.setVisible(true);
            banner.setManaged(true);
        } else {
            banner.setVisible(false);
            banner.setManaged(false);
        }

        double seconds = info == null ? 0 : info.duration;
        subtitle.setText(info == null && timelineProblem == null ? "Loading the template\u2026"
                : String.format(Locale.ROOT, "%d replaceable clip%s \u00b7 %.1fs", model.slots.size(), model.slots.size() == 1 ? "" : "s", seconds));

        backBtn.setVisible(!groupsStage && model.hasGroups());
        backBtn.setManaged(backBtn.isVisible());
        exportBtn.setVisible(!groupsStage);
        exportBtn.setManaged(!groupsStage);
        exportBtn.setDisable(busy || info == null || timelineProblem != null);
        exportBtn.setText(preparing ? "Preparing\u2026" : "Export");
        nextBtn.setVisible(groupsStage);
        nextBtn.setDisable(!model.groupsFilled() || importing);
        importingRow.setVisible(importing);
        importingRow.setManaged(importing);

        tiles.getChildren().clear();
        if (model.slots.isEmpty()) {
            sectionTitle.setText(timelineProblem != null ? "Nothing to fill" : "Loading\u2026");
            sectionHint.setText(timelineProblem != null ? "" : "The clips of this template appear here in a moment.");
            return;
        }
        if (groupsStage) {
            sectionTitle.setText("Choose your media");
            sectionHint.setText("Clips with the same coloured dot use the same media: choose it once. You can give single clips their own media on the next step. "
                    + "Select several files at once to fill the next tiles in a row.");
            List<TemplateUseModel.Entry> entries = model.entries();
            for (int i = 0; i < entries.size(); i++) tiles.getChildren().add(entryTile(i, entries.get(i)));
        } else {
            sectionTitle.setText("Clips");
            sectionHint.setText("Click a clip to choose other media for it (select several files to fill the next clips). "
                    + "Right-click a clip for Properties and Remove. The pencil trims a video.");
            for (int i = 0; i < model.slots.size(); i++) tiles.getChildren().add(clipTile(i));
        }
    }

    private Node clipTile(int index) {
        TemplateUseModel.Slot slot = model.slots.get(index);
        StackPane face = tileFace(index + 1, slot, 0, true, index);
        face.setOnMouseClicked(e -> { if (e.getButton() == MouseButton.PRIMARY) choose(index, false); });
        ContextMenu menu = new ContextMenu();
        if (slot.isFilled()) {
            MenuItem properties = new MenuItem("Properties\u2026");
            properties.setOnAction(e -> openProperties(index));
            MenuItem remove = new MenuItem("Remove");
            remove.setOnAction(e -> { model.clear(index); render(); });
            menu.getItems().addAll(properties, remove);
        } else {
            MenuItem choose = new MenuItem("Choose media\u2026");
            choose.setOnAction(e -> choose(index, false));
            menu.getItems().add(choose);
        }
        face.setOnContextMenuRequested(e -> menu.show(face, e.getScreenX(), e.getScreenY()));
        return withDot(face, model.colorIndexOfSlot(index), slot.detached);
    }

    private Node entryTile(int index, TemplateUseModel.Entry entry) {
        // show a clip the group still sets (a detached one has its own media), else the first
        TemplateUseModel.Slot shown = model.slots.get(entry.members.get(0));
        for (int m : entry.members) if (!model.slots.get(m).detached) { shown = model.slots.get(m); break; }
        StackPane face = tileFace(index + 1, shown, entry.members.size() > 1 ? entry.members.size() : 0, false, -1);
        face.setOnMouseClicked(e -> { if (e.getButton() == MouseButton.PRIMARY) choose(index, true); });
        ContextMenu menu = new ContextMenu();
        MenuItem choose = new MenuItem("Choose media\u2026");
        choose.setOnAction(e -> choose(index, true));
        menu.getItems().add(choose);
        if (shown.isFilled()) {
            MenuItem remove = new MenuItem("Remove");
            remove.setOnAction(e -> { model.clearEntry(entry); render(); });
            menu.getItems().add(remove);
        }
        face.setOnContextMenuRequested(e -> menu.show(face, e.getScreenX(), e.getScreenY()));
        return withDot(face, entry.colorIndex, false);
    }

    /** A tile under a small dot: the group's colour (hollow when the clip has its own media), nothing for a clip on its own. */
    private Node withDot(StackPane face, Integer colorIndex, boolean hollow) {
        HBox dotRow = new HBox();
        dotRow.setAlignment(Pos.CENTER);
        dotRow.setMinHeight(12);
        if (colorIndex != null) {
            Color c = PALETTE[colorIndex % PALETTE.length];
            Circle dot = new Circle(4.5);
            dot.setFill(hollow ? Color.TRANSPARENT : c);
            dot.setStroke(c);
            dot.setStrokeWidth(1.5);
            dotRow.getChildren().add(dot);
        }
        VBox box = new VBox(6, face, dotRow);
        box.setAlignment(Pos.TOP_CENTER);
        return box;
    }

    /**
     * The square of one slot: its number, the picture of the media once chosen (or a plus), the length of what plays, a
     * count of the clips it fills, a warning when the video is shorter than the template's clip, and for a video clip a pencil
     * to trim it.
     */
    private StackPane tileFace(int number, TemplateUseModel.Slot slot, int badge, boolean editable, int slotIndex) {
        StackPane face = new StackPane();
        face.getStyleClass().addAll("tpl-tile", "tpl-tile-face");
        face.setMinSize(TILE, TILE);
        face.setPrefSize(TILE, TILE);
        face.setMaxSize(TILE, TILE);
        Rectangle round = new Rectangle(TILE, TILE);
        round.setArcWidth(28);
        round.setArcHeight(28);
        face.setClip(round);

        Label numberLabel = new Label(String.valueOf(number));
        numberLabel.getStyleClass().add("tpl-tile-number");

        ImageView picture = new ImageView();
        picture.setFitWidth(TILE);
        picture.setFitHeight(TILE);
        picture.setPreserveRatio(false);
        Region shade = new Region();
        shade.getStyleClass().add("tpl-tile-shade");
        shade.setVisible(false);
        shade.setMouseTransparent(true);

        face.getChildren().addAll(picture, shade);
        if (slot.isFilled()) {
            thumbs.load(slot.path, slot.kind == TemplateUseModel.Kind.VIDEO, img -> {
                if (img == null) return;
                picture.setImage(img);
                picture.setViewport(MediaThumbnails.coverSquare(img));
                shade.setVisible(true);
                if (!numberLabel.getStyleClass().contains("tpl-tile-number-on-photo")) numberLabel.getStyleClass().add("tpl-tile-number-on-photo");
            });
            face.getChildren().add(numberLabel);
        } else {
            VBox empty = new VBox(4, numberLabel, plusIcon());
            empty.setAlignment(Pos.CENTER);
            empty.setMouseTransparent(true);
            face.getChildren().add(empty);
        }

        if (slot.isFilled()) {
            Label length = new Label(slot.kind == TemplateUseModel.Kind.VIDEO
                    ? String.format(Locale.ROOT, "%.1fs", slot.trimmedLength())
                    : (slot.targetDuration > 0 ? String.format(Locale.ROOT, "%.1fs", slot.targetDuration) : "photo"));
            length.getStyleClass().add("tpl-pill");
            StackPane.setAlignment(length, Pos.BOTTOM_LEFT);
            StackPane.setMargin(length, new Insets(0, 0, 8, 8));
            face.getChildren().add(length);
            if (slot.endsEarly()) {
                Label warn = new Label("!");
                warn.getStyleClass().add("tpl-warn");
                javafx.scene.control.Tooltip.install(warn, new javafx.scene.control.Tooltip("Shorter than the template's clip: it will end early."));
                StackPane.setAlignment(warn, Pos.TOP_RIGHT);
                StackPane.setMargin(warn, new Insets(8, 8, 0, 0));
                face.getChildren().add(warn);
            }
        }
        if (badge > 1) {
            Label count = new Label("\u00d7" + badge);
            count.getStyleClass().add("tpl-pill");
            StackPane.setAlignment(count, Pos.TOP_LEFT);
            StackPane.setMargin(count, new Insets(8, 0, 0, 8));
            face.getChildren().add(count);
        }
        if (editable && slot.kind == TemplateUseModel.Kind.VIDEO) {
            Button edit = new Button();
            edit.setGraphic(new FontIcon(MaterialDesignP.PENCIL_OUTLINE));
            edit.getStyleClass().add("tpl-edit");
            edit.setFocusTraversable(false);
            edit.setOnAction(e -> openProperties(slotIndex));
            StackPane.setAlignment(edit, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(edit, new Insets(0, 8, 8, 0));
            face.getChildren().add(edit);
        }

        Region ring = new Region();
        ring.getStyleClass().add("tpl-tile-ring");
        ring.setMouseTransparent(true);
        face.getChildren().add(ring);
        return face;
    }

    private static Node plusIcon() {
        StackPane p = new StackPane(new FontIcon(MaterialDesignP.PLUS));
        p.getStyleClass().add("tpl-tile-plus");
        return p;
    }

    // ================================================================================== choosing

    private void openProperties(int index) {
        TemplateClipDialog.show(this, model, index, thumbs, this::render);
    }

    private static List<String> patterns(Set<String> extensions) {
        List<String> out = new ArrayList<>();
        for (String e : new TreeSet<>(extensions)) {
            out.add("*." + e);
            out.add("*." + e.toUpperCase(Locale.ROOT));
        }
        return out;
    }

    /** index = a slot (clips stage) or a tile (groups stage). Several files fill the next ones in a row. */
    private void choose(int index, boolean tileMode) {
        if (importing) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tileMode ? "Choose media for tile " + (index + 1) : "Choose media for clip " + (index + 1));
        Set<String> all = new TreeSet<>(VIDEO_EXT);
        all.addAll(IMAGE_EXT);
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Photos and videos", patterns(all)),
                new FileChooser.ExtensionFilter("Videos", patterns(VIDEO_EXT)),
                new FileChooser.ExtensionFilter("Photos", patterns(IMAGE_EXT)));
        List<File> picked = chooser.showOpenMultipleDialog(this);
        if (picked == null || picked.isEmpty()) return;

        final List<File> files = new ArrayList<>(picked);
        importing = true;
        render();
        Thread t = new Thread(() -> {
            // reading a video's length runs ffmpeg: do it here, then let the model apply the answers
            Map<File, Object> answers = new LinkedHashMap<>();
            for (File f : files) {
                try {
                    answers.put(f, readMedia(f));
                } catch (IOException | RuntimeException e) {
                    answers.put(f, new IOException(e.getMessage() == null ? "unreadable" : e.getMessage()));
                }
            }
            TemplateUseModel.MediaReader reader = f -> {
                Object a = answers.get(f);
                if (a instanceof IOException) throw (IOException) a;
                return (TemplateUseModel.Media) a;
            };
            Platform.runLater(() -> {
                if (tileMode) model.fillEntries(index, files, reader);
                else model.fill(index, files, reader);
                importing = false;
                render();
            });
        }, "TemplateUseRead");
        t.setDaemon(true);
        t.start();
    }

    private static String extensionOf(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** What a picked file is: a video (with its length), a photo, or a reason it can't be used. Blocking (ffmpeg for a video). */
    private static TemplateUseModel.Media readMedia(File file) throws IOException {
        String ext = extensionOf(file);
        if (!file.isFile()) throw new IOException("the file is missing");
        if (IMAGE_EXT.contains(ext)) return new TemplateUseModel.Media(false, 0, ext.toUpperCase(Locale.ROOT) + " image");
        if (VIDEO_EXT.contains(ext)) {
            MediaHelper.MediaInfo m = MediaHelper.probeMediaInfo(file.getPath());
            if (!m.hasVideo) throw new IOException("it has no video in it");
            return new TemplateUseModel.Media(true, m.duration, ext.toUpperCase(Locale.ROOT) + " video");
        }
        throw new IOException("a ." + ext + " file is not a photo or video DoubleClips can use");
    }

    // ================================================================================== export

    private void alert(Alert.AlertType type, String header, String text) {
        Alert a = new Alert(type, text, ButtonType.OK);
        a.setTitle("Use template");
        a.setHeaderText(header);
        a.initOwner(this);
        a.show();
    }

    private void startExport() {
        if (preparing || activeExport != null) return;
        if (timelineProblem != null || info == null) {
            alert(Alert.AlertType.INFORMATION, "The template isn't ready", timelineProblem != null ? timelineProblem : "Its timeline is still loading.");
            return;
        }
        List<Integer> missing = model.missing();
        if (!missing.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "Every clip needs media", "Still empty: clip " + missing.toString().replaceAll("[\\[\\]]", "") + ".");
            return;
        }

        preparing = true;
        render();
        final List<TemplateRenderer.SlotPick> picks = model.picks();
        final VideoSettings settings = buildSettings();
        Thread t = new Thread(() -> {
            try {
                TemplateRenderer.Prepared prepared = TemplateRenderer.prepare(data, picks, settings, line -> Platform.runLater(() -> appendLog(line)));
                Platform.runLater(() -> openExport(prepared, settings));
            } catch (TemplateRenderer.RenderError e) {
                Platform.runLater(() -> prepareFailed(e.getMessage()));
            } catch (RuntimeException e) {
                e.printStackTrace();
                Platform.runLater(() -> prepareFailed("Something went wrong while preparing the template: " + e));
            }
        }, "TemplateUsePrepare");
        t.setDaemon(true);
        t.start();
    }

    private void prepareFailed(String message) {
        preparing = false;
        appendLog(message);
        render();
        alert(Alert.AlertType.ERROR, "Couldn't prepare the template", message);
    }

    /** The export window is the normal one; the working folder goes when it closes. */
    private void openExport(TemplateRenderer.Prepared prepared, VideoSettings settings) {
        preparing = false;
        lastPrepared = prepared;
        ExportWindow window = ExportWindow.show(DoubleClipsDesktop.getInstance().getPrimaryStage(), prepared.project, prepared.timeline, settings);
        activeExport = window;
        window.setOnHidden(e -> {
            activeExport = null;
            TemplateRenderer.cleanup(prepared.directory);
            if (lastPrepared == prepared) lastPrepared = null;
            if (isShowing()) render();
        });
        render();
    }
}
