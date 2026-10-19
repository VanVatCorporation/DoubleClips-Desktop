package com.vanvatcorporation.doubleclips.ui.overlays;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.TemplateStripMath;
import com.vanvatcorporation.doubleclips.TemplateTimelineInfo;
import com.vanvatcorporation.doubleclips.TemplateTimelineInfo.Role;
import com.vanvatcorporation.doubleclips.TemplateTimelineInfo.Slot;
import com.vanvatcorporation.doubleclips.TemplateTimelineInfo.StripClip;
import com.vanvatcorporation.doubleclips.TemplateTimelineLoader;
import com.vanvatcorporation.doubleclips.auth.TemplateRepository;
import com.vanvatcorporation.doubleclips.data.TemplateData;
import com.vanvatcorporation.doubleclips.ui.TemplateExportWindow;
import com.vanvatcorporation.doubleclips.ui.TemplateTimelineStrip;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignH;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;

import java.text.DateFormat;
import java.util.Date;
import java.util.function.Consumer;

/**
 * The template preview, laid out for a wide window instead of a phone: the preview video (with its own controls) on the
 * left, what the template is on the right (its numbered clips, details, comments), and the template's whole timeline
 * across the full width underneath, with a red playhead that follows the video and scrubs it. Esc closes, Space plays or
 * pauses, the arrow keys step one second.
 * <p>
 * The strip first shows the simple clip-count version (every template has one) and is replaced by the real timeline as
 * soon as it has been downloaded; a template without a timeline link simply keeps the simple one.
 */
public class TemplatePreviewOverlay extends StackPane {

    private final TemplateData data;
    private final Consumer<Void> onClose;
    private boolean closed;

    // player
    private MediaPlayer mediaPlayer;
    private double videoSeconds;
    private final StackPane videoBox = new StackPane();
    private final ProgressIndicator loading = new ProgressIndicator();
    private final FontIcon statusIcon = new FontIcon(MaterialDesignP.PLAY);
    private final FontIcon playIcon = new FontIcon(MaterialDesignP.PAUSE);
    private final Slider seek = new Slider(0, 1, 0);
    private boolean sliderDragging, stripScrubbing, resumeAfterScrub;

    // timeline
    private final TemplateTimelineStrip strip = new TemplateTimelineStrip();
    private TemplateTimelineInfo info;
    private boolean realTimeline;
    private final Label clock = new Label("0:00 / 0:00");
    private final Label hover = new Label();
    private final HBox legend = new HBox(14);
    private final FlowPane slotsFlow = new FlowPane(10, 10);
    private final Label slotsHint = new Label();
    private final Label timelineNote = new Label();
    private final Label clipsChip = new Label();
    private final Label durationChip = new Label();
    private Tab clipsTab;
    private final VBox aboutBody = new VBox(12);

    // interactions
    private final Button likeBtn = new Button();
    private final Button bookmarkBtn = new Button();

    public TemplatePreviewOverlay(TemplateData data, Consumer<Void> onClose) {
        this.data = data;
        this.onClose = onClose;
        getStyleClass().add("preview-overlay");

        VBox card = new VBox();
        card.getStyleClass().add("template-dialog");
        card.setMaxSize(1260, 800);
        card.setPrefSize(1260, 800);
        StackPane.setMargin(card, new Insets(28));

        card.getChildren().addAll(buildHeader(), buildMiddle(), buildTimelineSection());
        getChildren().add(card);

        // the simple strip first, the real timeline when it arrives
        applyInfo(TemplateTimelineInfo.legacy(data, 0), false);
        loadTimeline();
        loadVideo();
        refreshInteractions();
        TemplateRepository.getInstance().markSeen(data);

        setFocusTraversable(true);
        sceneProperty().addListener((o, a, scene) -> { if (scene != null) Platform.runLater(this::requestFocus); });
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) { close(); e.consume(); }
            else if (e.getCode() == KeyCode.SPACE && !(e.getTarget() instanceof Button)) { togglePlay(); e.consume(); }
            else if (e.getCode() == KeyCode.LEFT) { seekTo(currentSeconds() - 1); e.consume(); }
            else if (e.getCode() == KeyCode.RIGHT) { seekTo(currentSeconds() + 1); e.consume(); }
        });
        setOnMouseClicked(e -> { if (e.getTarget() == this) close(); });   // a click on the dim area closes it
    }

    // ------------------------------------------------------------------------------------------------ layout

    private Node buildHeader() {
        Label title = new Label(data.getTemplateTitle() == null ? "Template" : data.getTemplateTitle());
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        title.setMaxWidth(Double.MAX_VALUE);

        Label author = new Label("@" + data.getTemplateAuthor());
        author.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 13px;");
        clipsChip.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 13px;");
        durationChip.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 13px;");
        HBox sub = new HBox(8, author, dot(), clipsChip, dot(), durationChip);
        sub.setAlignment(Pos.CENTER_LEFT);

        VBox titles = new VBox(3, title, sub);
        HBox.setHgrow(titles, Priority.ALWAYS);

        likeBtn.getStyleClass().add("template-chip");
        likeBtn.setOnAction(e -> toggleLike());
        bookmarkBtn.getStyleClass().add("template-chip");
        bookmarkBtn.setOnAction(e -> toggleBookmark());

        Button use = new Button("Use template");
        use.getStyleClass().add("button-primary");
        use.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 9 22; -fx-background-radius: 10;");
        use.setOnAction(e -> useTemplate());

        Button closeBtn = new Button();
        closeBtn.setGraphic(new FontIcon(MaterialDesignC.CLOSE));
        closeBtn.getStyleClass().add("button-transparent");
        closeBtn.setTooltip(new javafx.scene.control.Tooltip("Close (Esc)"));
        closeBtn.setOnAction(e -> close());

        HBox header = new HBox(12, titles, likeBtn, bookmarkBtn, use, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 20, 14, 24));
        return header;
    }

    private Node buildMiddle() {
        HBox middle = new HBox(22, buildVideoColumn(), buildInfoColumn());
        middle.setPadding(new Insets(0, 24, 6, 24));
        VBox.setVgrow(middle, Priority.ALWAYS);
        return middle;
    }

    private Node buildVideoColumn() {
        loading.setMaxSize(54, 54);
        statusIcon.setIconSize(64);
        statusIcon.setIconColor(Color.WHITE);
        statusIcon.setOpacity(0);
        StackPane status = new StackPane(loading, statusIcon);
        status.setMouseTransparent(true);

        videoBox.setStyle("-fx-background-color: #101010; -fx-background-radius: 16;");
        videoBox.setMinWidth(300);
        videoBox.setPrefWidth(340);
        videoBox.setMaxWidth(420);
        Rectangle round = new Rectangle();
        round.setArcWidth(32);
        round.setArcHeight(32);
        round.widthProperty().bind(videoBox.widthProperty());
        round.heightProperty().bind(videoBox.heightProperty());
        videoBox.setClip(round);
        VBox.setVgrow(videoBox, Priority.ALWAYS);

        ImageView placeholder = new ImageView();
        placeholder.setPreserveRatio(true);
        placeholder.fitWidthProperty().bind(videoBox.widthProperty());
        placeholder.fitHeightProperty().bind(videoBox.heightProperty());
        placeholder.setId("placeholder");
        if (data.getTemplateSnapshotLink() != null && !data.getTemplateSnapshotLink().isEmpty()) {
            placeholder.setImage(new Image(data.getTemplateSnapshotLink(), true));
        }
        videoBox.getChildren().addAll(placeholder, status);
        videoBox.setOnMouseClicked(e -> togglePlay());

        // controls under the video
        Button play = new Button();
        play.setGraphic(playIcon);
        play.getStyleClass().add("button-transparent");
        play.setOnAction(e -> togglePlay());

        seek.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(seek, Priority.ALWAYS);
        seek.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> sliderDragging = true);
        seek.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> { sliderDragging = false; seekTo(seek.getValue()); });
        seek.valueProperty().addListener((o, a, b) -> { if (sliderDragging) seekTo(b.doubleValue()); });

        Button mute = new Button();
        FontIcon vol = new FontIcon(MaterialDesignV.VOLUME_HIGH);
        mute.setGraphic(vol);
        mute.getStyleClass().add("button-transparent");
        mute.setOnAction(e -> {
            if (mediaPlayer == null) return;
            mediaPlayer.setMute(!mediaPlayer.isMute());
            vol.setIconCode(mediaPlayer.isMute() ? MaterialDesignV.VOLUME_OFF : MaterialDesignV.VOLUME_HIGH);
        });

        HBox controls = new HBox(6, play, seek, mute);
        controls.setAlignment(Pos.CENTER_LEFT);

        VBox col = new VBox(8, videoBox, controls);
        col.setMinWidth(300);
        col.setPrefWidth(340);
        col.setMaxWidth(420);
        return col;
    }

    private Node buildInfoColumn() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        HBox.setHgrow(tabs, Priority.ALWAYS);

        // Clips: what the user fills in when using the template
        slotsHint.setWrapText(true);
        slotsHint.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        timelineNote.setWrapText(true);
        timelineNote.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        VBox clipsBody = new VBox(12, slotsHint, slotsFlow, timelineNote);
        clipsBody.setPadding(new Insets(16, 4, 10, 4));
        clipsTab = new Tab("Your clips", scroll(clipsBody));

        Tab aboutTab = new Tab("About", scroll(aboutBody));
        aboutBody.setPadding(new Insets(16, 4, 10, 4));
        rebuildAbout();

        int n = data.getComments().size();
        Tab commentsTab = new Tab("Comments" + (n > 0 ? " (" + n + ")" : ""), scroll(buildComments()));

        tabs.getTabs().addAll(clipsTab, aboutTab, commentsTab);
        return tabs;
    }

    private static ScrollPane scroll(Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setStyle("-fx-background-color: transparent;");
        return sp;
    }

    private Node buildComments() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(16, 4, 10, 4));
        if (data.getComments().isEmpty()) {
            Label none = new Label("No comments yet.");
            none.setStyle("-fx-text-fill: -color-fg-muted;");
            box.getChildren().add(none);
            return box;
        }
        DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
        for (TemplateData.TemplateComment c : data.getComments()) {
            Label who = new Label("@" + c.username);
            who.setStyle("-fx-font-weight: bold;");
            Label when = new Label(df.format(new Date(c.commentTimestamp)));
            when.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
            Label text = new Label(c.content);
            text.setWrapText(true);
            box.getChildren().add(new VBox(3, new HBox(8, who, when), text));
        }
        return box;
    }

    private Node buildTimelineSection() {
        clock.setStyle("-fx-font-family: 'Menlo', 'Consolas', monospace; -fx-font-size: 13px;");
        hover.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        legend.setAlignment(Pos.CENTER_LEFT);
        HBox top = new HBox(16, legend, spacer, hover, clock);
        top.setAlignment(Pos.CENTER_LEFT);

        strip.setListener(new TemplateTimelineStrip.Listener() {
            @Override public void onScrubStart() {
                stripScrubbing = true;
                resumeAfterScrub = mediaPlayer != null && mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING;
                if (mediaPlayer != null) mediaPlayer.pause();
            }
            @Override public void onScrub(double seconds) {
                seekTo(seconds);
            }
            @Override public void onScrubEnd() {
                stripScrubbing = false;
                if (resumeAfterScrub && mediaPlayer != null) mediaPlayer.play();
            }
            @Override public void onHover(StripClip c) {
                hover.setText(c == null ? "" : describe(c));
            }
        });

        VBox box = new VBox(10, top, strip);
        box.setPadding(new Insets(10, 24, 20, 24));
        return box;
    }

    private static Node dot() {
        Label d = new Label("\u00B7");
        d.setStyle("-fx-text-fill: -color-fg-muted;");
        return d;
    }

    // ------------------------------------------------------------------------------------------------ timeline

    private void loadTimeline() {
        if (!data.hasTimeline()) return;
        Thread t = new Thread(() -> {
            try {
                TemplateTimelineInfo real = TemplateTimelineLoader.load(data);
                Platform.runLater(() -> { if (!closed) applyInfo(real, true); });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (!closed) timelineNote.setText("The template's timeline could not be loaded (" + e.getMessage() + "); the simple strip is shown instead.");
                });
            }
        }, "TemplateTimelineLoad");
        t.setDaemon(true);
        t.start();
    }

    private void applyInfo(TemplateTimelineInfo next, boolean real) {
        info = next;
        realTimeline = real;
        strip.setInfo(next);
        strip.setPrefHeight(strip.idealHeight());
        strip.setMinHeight(strip.idealHeight());

        legend.getChildren().clear();
        for (Role r : next.roles()) {
            Circle swatch = new Circle(5, TemplateTimelineStrip.argb(r.colorArgb));
            if (r == Role.REPLACEABLE) swatch.setStroke(Color.web("#808080", 0.6));
            Label name = new Label(r.title);
            name.setStyle("-fx-font-size: 12px;");
            HBox item = new HBox(6, swatch, name);
            item.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(item);
        }

        int slots = next.slots.size();
        clipsChip.setText(slots + (slots == 1 ? " clip to fill" : " clips to fill"));
        clipsTab.setText("Your clips" + (slots > 0 ? " (" + slots + ")" : ""));
        double total = totalSeconds();
        durationChip.setText(total > 0 ? TemplateStripMath.clock(total) : "");

        slotsFlow.getChildren().clear();
        slotsHint.setText(slots == 0
                ? "This template has no clips to replace."
                : "Using this template, you pick your own photo or video for each numbered clip. Click one to jump to it.");
        for (Slot s : next.slots) slotsFlow.getChildren().add(slotCard(s));

        rebuildAbout();
        updateClock(currentSeconds());
    }

    private Node slotCard(Slot s) {
        Label num = new Label(String.valueOf(s.number));
        num.setMinSize(28, 28);
        num.setAlignment(Pos.CENTER);
        num.setStyle("-fx-background-color: white; -fx-background-radius: 14; -fx-text-fill: #1b1b1f; -fx-font-weight: bold;");
        Label kind = new Label((s.isImage ? "Photo" : "Video or photo") + " \u00B7 " + String.format("%.1fs", s.duration));
        kind.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        Label at = new Label("at " + TemplateStripMath.clock(s.start));
        at.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
        HBox card = new HBox(10, num, new VBox(2, kind, at));
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("template-slot");
        card.setPrefWidth(200);
        card.setOnMouseClicked(e -> { seekTo(s.start); e.consume(); });
        return card;
    }

    private void rebuildAbout() {
        aboutBody.getChildren().clear();
        String desc = data.getTemplateDescription();
        Label description = new Label(desc == null || desc.isBlank() ? "No description." : desc);
        description.setWrapText(true);
        aboutBody.getChildren().add(description);

        VBox facts = new VBox(6);
        facts.getChildren().add(fact("Made by", "@" + data.getTemplateAuthor()));
        if (data.getTemplateTimestamp() > 0) {
            facts.getChildren().add(fact("Updated", DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(data.getTemplateTimestamp()))));
        }
        double total = totalSeconds();
        if (total > 0) facts.getChildren().add(fact("Length", TemplateStripMath.clock(total)));
        if (info != null) facts.getChildren().add(fact("Clips to replace", String.valueOf(info.slots.size())));
        if (info != null && info.canvas != null && info.canvas.videoWidth > 0) {
            String fps = info.canvas.frameRate > 0 ? " \u00B7 " + info.canvas.frameRate + " fps" : "";
            facts.getChildren().add(fact("Made for", info.canvas.videoWidth + " \u00D7 " + info.canvas.videoHeight + fps));
        }
        facts.getChildren().add(fact("Format", data.hasTimeline()
                ? (data.isFfmpegAvailable() ? "Timeline template (also has an FFmpeg version)" : "Timeline template")
                : "Classic template (FFmpeg)"));
        String[] res = data.getTemplateAdditionalResourcesName();
        int files = 0;
        if (res != null) for (String r : res) if (r != null && !r.isEmpty()) files++;
        if (files > 0) facts.getChildren().add(fact("Included files", String.valueOf(files)));
        facts.getChildren().add(fact("Views / uses", data.getViewCount() + " / " + data.getUseCount()));
        aboutBody.getChildren().add(facts);
    }

    private static Node fact(String name, String value) {
        Label n = new Label(name);
        n.setMinWidth(120);
        n.setStyle("-fx-text-fill: -color-fg-muted;");
        Label v = new Label(value);
        v.setWrapText(true);
        HBox row = new HBox(10, n, v);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private static String describe(StripClip c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.role.title);
        if (c.slotNumber > 0) sb.append(' ').append(c.slotNumber);
        sb.append("  \u00B7  ").append(TemplateStripMath.clock(c.start)).append(" \u2013 ").append(TemplateStripMath.clock(c.start + c.duration));
        sb.append(String.format("  (%.1fs)", c.duration));
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------ player

    private void loadVideo() {
        String link = data.getTemplateVideoLink();
        if (link == null || link.isEmpty()) { loading.setVisible(false); return; }
        com.vanvatcorporation.doubleclips.helper.VideoCacheManager.getCachedVideoPath(link, cachedUrl -> Platform.runLater(() -> {
            if (closed) return;
            mediaPlayer = new MediaPlayer(new Media(cachedUrl));
            MediaView view = new MediaView(mediaPlayer);
            view.setPreserveRatio(true);
            view.fitWidthProperty().bind(videoBox.widthProperty());
            view.fitHeightProperty().bind(videoBox.heightProperty());
            videoBox.getChildren().add(1, view);
            mediaPlayer.setAutoPlay(true);
            mediaPlayer.setCycleCount(MediaPlayer.INDEFINITE);

            mediaPlayer.statusProperty().addListener((o, was, status) -> {
                loading.setVisible(status == MediaPlayer.Status.STALLED || status == MediaPlayer.Status.UNKNOWN);
                if (status == MediaPlayer.Status.PLAYING) {
                    videoBox.lookup("#placeholder").setVisible(false);
                    playIcon.setIconCode(MaterialDesignP.PAUSE);
                } else if (status == MediaPlayer.Status.PAUSED) {
                    playIcon.setIconCode(MaterialDesignP.PLAY);
                }
            });
            mediaPlayer.setOnReady(() -> {
                videoSeconds = mediaPlayer.getMedia().getDuration().toSeconds();
                seek.setMax(Math.max(1, videoSeconds));
                if (!realTimeline) applyInfo(TemplateTimelineInfo.legacy(data, videoSeconds), false);   // a legacy strip gets the video's length
                else updateClock(0);
            });
            mediaPlayer.currentTimeProperty().addListener((o, a, t) -> {
                if (stripScrubbing || sliderDragging) return;
                updateClock(t.toSeconds());
            });
        }));
    }

    private double currentSeconds() {
        return mediaPlayer == null ? 0 : mediaPlayer.getCurrentTime().toSeconds();
    }

    /** The template's own length when it has a timeline, else the preview video's. */
    private double totalSeconds() {
        if (realTimeline && info != null && info.duration > 0) return info.duration;
        if (videoSeconds > 0) return videoSeconds;
        return info == null ? 0 : info.duration;
    }

    private void updateClock(double seconds) {
        strip.setTime(seconds);
        if (!sliderDragging) seek.setValue(Math.min(seconds, seek.getMax()));
        clock.setText(TemplateStripMath.clock(seconds) + " / " + TemplateStripMath.clock(totalSeconds()));
    }

    private void seekTo(double seconds) {
        double limit = videoSeconds > 0 ? videoSeconds : totalSeconds();
        double t = Math.max(0, limit > 0 ? Math.min(seconds, limit) : seconds);
        if (mediaPlayer != null) mediaPlayer.seek(Duration.seconds(t));
        updateClock(t);
    }

    private void togglePlay() {
        if (mediaPlayer == null) return;
        boolean playing = mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING;
        if (playing) mediaPlayer.pause(); else mediaPlayer.play();
        statusIcon.setIconCode(playing ? MaterialDesignP.PAUSE : MaterialDesignP.PLAY);
        FadeTransition ft = new FadeTransition(Duration.millis(320), statusIcon);
        ft.setFromValue(0.9);
        ft.setToValue(0);
        ft.play();
    }

    // ------------------------------------------------------------------------------------------------ interactions

    private void refreshInteractions() {
        style(likeBtn, data.isLiked ? MaterialDesignH.HEART : MaterialDesignH.HEART_OUTLINE, data.isLiked, data.getHeartCount(), "#ff3b30");
        style(bookmarkBtn, data.isBookmarked ? MaterialDesignB.BOOKMARK : MaterialDesignB.BOOKMARK_OUTLINE, data.isBookmarked, data.getBookmarkCount(), "#ffb300");
    }

    private static void style(Button b, org.kordamp.ikonli.Ikon icon, boolean on, int count, String onColor) {
        FontIcon fi = new FontIcon(icon);
        if (on) fi.setIconColor(Color.web(onColor));
        b.setGraphic(fi);
        b.setText(String.valueOf(count));
    }

    private boolean requireLogin(String what) {
        if (TemplateRepository.getInstance().isLoggedIn()) return true;
        Alert a = new Alert(Alert.AlertType.INFORMATION, "Please log in to " + what + ".");
        a.setTitle("Login required");
        a.setHeaderText(null);
        a.show();
        return false;
    }

    private void toggleLike() {
        if (!requireLogin("like this template")) return;
        data.isLiked = !data.isLiked;                                    // optimistic, rolled back when the server says no
        data.setHeartCount(Math.max(0, data.getHeartCount() + (data.isLiked ? 1 : -1)));
        refreshInteractions();
        TemplateRepository.getInstance().toggleLike(data, ok -> {
            if (ok) return;
            data.isLiked = !data.isLiked;
            data.setHeartCount(Math.max(0, data.getHeartCount() + (data.isLiked ? 1 : -1)));
            refreshInteractions();
        });
    }

    private void toggleBookmark() {
        if (!requireLogin("bookmark this template")) return;
        data.isBookmarked = !data.isBookmarked;
        data.setBookmarkCount(Math.max(0, data.getBookmarkCount() + (data.isBookmarked ? 1 : -1)));
        refreshInteractions();
        TemplateRepository.getInstance().toggleBookmark(data, ok -> {
            if (ok) return;
            data.isBookmarked = !data.isBookmarked;
            data.setBookmarkCount(Math.max(0, data.getBookmarkCount() + (data.isBookmarked ? 1 : -1)));
            refreshInteractions();
        });
    }

    /**
     * "Use template": first asks which engine renders it (a rabbit for OpenGL = the timeline, a turtle for FFmpeg = the
     * template's command), with the one the template cannot use grayed out. The FFmpeg path is the existing export
     * window; the OpenGL path renders the template's timeline.
     */
    private void useTemplate() {
        if (!data.hasTimeline() && !data.isFfmpegAvailable()) {
            Alert a = new Alert(Alert.AlertType.INFORMATION, "This template cannot be rendered: it has neither a timeline nor an FFmpeg command.");
            a.setTitle("Use template");
            a.setHeaderText(null);
            a.show();
            return;
        }
        final TemplateEnginePickerOverlay[] picker = new TemplateEnginePickerOverlay[1];
        picker[0] = new TemplateEnginePickerOverlay(data,
                engine -> {
                    DoubleClipsDesktop.getInstance().hideOverlay(picker[0]);
                    if (engine == TemplateEnginePickerOverlay.Engine.FFMPEG) {
                        close();
                        TemplateExportWindow.showInstance(data);
                    } else {
                        renderWithOpenGl();
                    }
                },
                () -> DoubleClipsDesktop.getInstance().hideOverlay(picker[0]));
        DoubleClipsDesktop.getInstance().showOverlay(picker[0]);
    }

    /** The OpenGL path: the template's timeline with the user's own media. (Next step: not built yet.) */
    private void renderWithOpenGl() {
        Alert a = new Alert(Alert.AlertType.INFORMATION,
                "Rendering a template from its timeline with OpenGL is the next step on desktop and is not available yet. "
                        + "Pick the turtle (FFmpeg) for templates that have an FFmpeg version.");
        a.setTitle("Use template");
        a.setHeaderText(null);
        a.show();
    }

    private void close() {
        if (closed) return;
        closed = true;
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.dispose();
            mediaPlayer = null;
        }
        onClose.accept(null);
    }
}
