package com.vanvatcorporation.doubleclips.ui.overlays;

import com.vanvatcorporation.doubleclips.auth.AuthRepository;
import com.vanvatcorporation.doubleclips.auth.TemplateFeedSource;
import com.vanvatcorporation.doubleclips.auth.TemplateRepository;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.ProjectRepository;
import com.vanvatcorporation.doubleclips.data.TemplateData;
import com.vanvatcorporation.doubleclips.helper.DateHelper;
import com.vanvatcorporation.doubleclips.helper.ProjectBackup;
import com.vanvatcorporation.doubleclips.i18n.I18n;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Profile -> Statistics. Two parts: what is on THIS computer (projects, footage, disk space, activity by month; works signed out),
 * and how the account's posted templates perform (views, uses, likes, saves, comments, and a top list by any of them; pulled
 * from the same "My templates" list the Profile shows, page by page).
 */
public class StatisticsOverlay extends StackPane {

    private enum Metric { VIEWS, USES, LIKES, SAVES }

    /** A safety stop for an account with a huge number of templates (20 a page). */
    private static final int MAX_PAGES = 100;

    private final Consumer<Void> onClose;
    private boolean closed;

    private final VBox templatesSection = new VBox(14);
    private int loadGeneration;
    private boolean templatesLoading;
    private String templatesError;
    private List<TemplateData> mine;
    private Metric metric = Metric.USES;

    public StatisticsOverlay(Consumer<Void> onClose) {
        this.onClose = onClose;
        getStyleClass().add("preview-overlay");

        VBox card = new VBox(16);
        card.getStyleClass().add("template-dialog");
        card.setMaxSize(980, 780);
        card.setPrefSize(980, 780);
        card.setPadding(new Insets(24));
        StackPane.setMargin(card, new Insets(28));

        Label title = new Label(I18n.t("Statistics"));
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        HBox.setHgrow(title, Priority.ALWAYS);
        title.setMaxWidth(Double.MAX_VALUE);

        Button closeBtn = new Button();
        closeBtn.setGraphic(new FontIcon(MaterialDesignC.CLOSE));
        closeBtn.getStyleClass().add("button-transparent");
        closeBtn.setOnAction(e -> close());

        HBox header = new HBox(12, title, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(30, deviceSection(), templatesSection);
        content.setPadding(new Insets(4, 8, 12, 2));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("profile-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        card.getChildren().addAll(header, scroll);
        getChildren().add(card);

        loadTemplates();

        setFocusTraversable(true);
        sceneProperty().addListener((o, a, scene) -> { if (scene != null) Platform.runLater(this::requestFocus); });
        setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) { close(); e.consume(); } });
        setOnMouseClicked(e -> { if (e.getTarget() == this) close(); });   // a click on the dim area closes it
    }

    private void close() {
        if (closed) return;
        closed = true;
        loadGeneration++;               // an answer still on its way is dropped
        onClose.accept(null);
    }

    // ── On this device ─────────────────────────────────────────────────────────

    private Node deviceSection() {
        List<ProjectData> projects = new ArrayList<>(ProjectRepository.getInstance().projectsProperty());

        long totalMs = 0, totalBytes = 0;
        ProjectData longest = null, biggest = null, latest = null;
        for (ProjectData p : projects) {
            totalMs += Math.max(0, p.getProjectDuration());
            totalBytes += Math.max(0, p.getProjectSize());
            if (longest == null || p.getProjectDuration() > longest.getProjectDuration()) longest = p;
            if (biggest == null || p.getProjectSize() > biggest.getProjectSize()) biggest = p;
            if (latest == null || p.getProjectTimestamp() > latest.getProjectTimestamp()) latest = p;
        }

        FlowPane tiles = new FlowPane(12, 12);
        tiles.getChildren().add(statTile(I18n.t("Projects"), String.valueOf(projects.size()), null, 28));
        tiles.getChildren().add(statTile(I18n.t("Total footage"), DateHelper.convertTimestampToHHMMSSFormat(totalMs), null, 28));
        tiles.getChildren().add(statTile(I18n.t("Disk space"), ProjectBackup.formatBytes(totalBytes), null, 28));
        tiles.getChildren().add(statTile(I18n.t("Last edited"),
                latest == null ? "\u2014" : java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(latest.getProjectTimestamp())),
                latest == null ? null : latest.getProjectTitle(), 20));
        if (longest != null && longest.getProjectDuration() > 0) {
            tiles.getChildren().add(statTile(I18n.t("Longest project"), DateHelper.convertTimestampToHHMMSSFormat(longest.getProjectDuration()), longest.getProjectTitle(), 28));
        }
        if (biggest != null && biggest.getProjectSize() > 0) {
            tiles.getChildren().add(statTile(I18n.t("Biggest project"), ProjectBackup.formatBytes(biggest.getProjectSize()), biggest.getProjectTitle(), 28));
        }

        VBox box = new VBox(14, sectionTitle(I18n.t("On this computer")), tiles);
        if (!projects.isEmpty()) {
            box.getChildren().addAll(smallTitle(I18n.t("Projects last edited, by month")), monthBars(projects));
        }
        return box;
    }

    /** The last six months, one bar each: how many projects were last edited in that month. */
    private Node monthBars(List<ProjectData> projects) {
        YearMonth now = YearMonth.now();
        int[] counts = new int[6];
        for (ProjectData p : projects) {
            YearMonth ym = YearMonth.from(Instant.ofEpochMilli(p.getProjectTimestamp()).atZone(ZoneId.systemDefault()));
            long diff = ChronoUnit.MONTHS.between(ym, now);
            if (diff >= 0 && diff < 6) counts[5 - (int) diff]++;
        }
        int max = 1;
        for (int c : counts) max = Math.max(max, c);

        HBox row = new HBox(16);
        row.setAlignment(Pos.BOTTOM_LEFT);
        for (int i = 0; i < 6; i++) {
            Label count = new Label(String.valueOf(counts[i]));
            count.getStyleClass().add("stat-sub");

            double h = counts[i] == 0 ? 4 : 10 + 100.0 * counts[i] / max;
            Region bar = new Region();
            bar.getStyleClass().add("stat-bar");
            bar.setMinSize(38, h);
            bar.setPrefSize(38, h);
            bar.setMaxSize(38, h);

            Label month = new Label(now.minusMonths(5 - i).getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault()));
            month.getStyleClass().add("stat-label");

            VBox col = new VBox(6, count, bar, month);
            col.setAlignment(Pos.BOTTOM_CENTER);
            row.getChildren().add(col);
        }
        return row;
    }

    // ── Your templates ─────────────────────────────────────────────────────────

    private void loadTemplates() {
        if (AuthRepository.getInstance().getCurrentUser() == null) {
            renderTemplates();
            return;
        }
        final int gen = ++loadGeneration;
        templatesLoading = true;
        templatesError = null;
        mine = null;
        renderTemplates();
        fetchMore(gen, null, 0, new ArrayList<>(), new HashSet<>());
    }

    private void fetchMore(int gen, String cursor, int pages, List<TemplateData> acc, Set<String> seen) {
        TemplateRepository.getInstance().fetchPage(TemplateFeedSource.mine(), cursor, TemplateRepository.PAGE_SIZE,
                page -> {
                    if (gen != loadGeneration || closed) return;
                    for (TemplateData d : page.items) {
                        if (seen.add(d.getTemplateId().toLowerCase(Locale.ROOT))) acc.add(d);
                    }
                    String next = page.nextCursor;
                    boolean more = page.hasMore && next != null && !next.isEmpty() && pages + 1 < MAX_PAGES;
                    if (more) {
                        fetchMore(gen, next, pages + 1, acc, seen);
                    } else {
                        mine = acc;
                        templatesLoading = false;
                        renderTemplates();
                    }
                },
                message -> {
                    if (gen != loadGeneration || closed) return;
                    templatesLoading = false;
                    templatesError = message;
                    renderTemplates();
                });
    }

    private void renderTemplates() {
        templatesSection.getChildren().clear();
        templatesSection.getChildren().add(sectionTitle(I18n.t("Your templates")));

        if (AuthRepository.getInstance().getCurrentUser() == null) {
            templatesSection.getChildren().add(mutedLine(I18n.t("Sign in to see how your templates perform.")));
            return;
        }
        if (templatesLoading) {
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.setPrefSize(22, 22);
            spinner.setMaxSize(22, 22);
            HBox loading = new HBox(10, spinner, mutedLine(I18n.t("Loading your templates...")));
            loading.setAlignment(Pos.CENTER_LEFT);
            templatesSection.getChildren().add(loading);
            return;
        }
        if (templatesError != null) {
            Button retry = new Button(I18n.t("Retry"));
            retry.getStyleClass().add("app-button");
            retry.setOnAction(e -> loadTemplates());
            templatesSection.getChildren().addAll(mutedLine(I18n.t("Couldn't load your templates") + ": " + templatesError), retry);
            return;
        }
        if (mine == null || mine.isEmpty()) {
            templatesSection.getChildren().add(mutedLine(I18n.t("You haven't posted a template yet")));
            return;
        }

        long views = 0, uses = 0, likes = 0, saves = 0, comments = 0;
        for (TemplateData d : mine) {
            views += d.getViewCount();
            uses += d.getUseCount();
            likes += d.getHeartCount();
            saves += d.getBookmarkCount();
            comments += d.getCommentCount();
        }
        String useRate = views > 0 ? I18n.f("%s of views", String.format(Locale.ROOT, "%.1f%%", 100.0 * uses / views)) : null;

        FlowPane tiles = new FlowPane(12, 12);
        tiles.getChildren().add(statTile(I18n.t("Templates"), compact(mine.size()), null, 28));
        tiles.getChildren().add(statTile(I18n.t("Views"), compact(views), null, 28));
        tiles.getChildren().add(statTile(I18n.t("Uses"), compact(uses), useRate, 28));
        tiles.getChildren().add(statTile(I18n.t("Likes"), compact(likes), null, 28));
        tiles.getChildren().add(statTile(I18n.t("Saves"), compact(saves), null, 28));
        tiles.getChildren().add(statTile(I18n.t("Comments"), compact(comments), null, 28));
        templatesSection.getChildren().add(tiles);

        // the top list, by the metric picked in the switch
        templatesSection.getChildren().add(smallTitle(I18n.t("Top templates")));
        templatesSection.getChildren().add(metricSwitch());

        List<TemplateData> sorted = new ArrayList<>(mine);
        sorted.sort((a, b) -> Long.compare(valueOf(b), valueOf(a)));
        long top = sorted.isEmpty() ? 0 : valueOf(sorted.get(0));
        VBox list = new VBox(14);
        for (int i = 0; i < Math.min(5, sorted.size()); i++) {
            list.getChildren().add(topRow(i + 1, sorted.get(i), valueOf(sorted.get(i)), top));
        }
        templatesSection.getChildren().add(list);
    }

    private long valueOf(TemplateData d) {
        switch (metric) {
            case VIEWS: return d.getViewCount();
            case LIKES: return d.getHeartCount();
            case SAVES: return d.getBookmarkCount();
            case USES:
            default: return d.getUseCount();
        }
    }

    private Node metricSwitch() {
        ToggleGroup group = new ToggleGroup();
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);
        Object[][] items = {
                {Metric.USES, I18n.t("Uses")}, {Metric.VIEWS, I18n.t("Views")},
                {Metric.LIKES, I18n.t("Likes")}, {Metric.SAVES, I18n.t("Saves")}};
        for (Object[] item : items) {
            final Metric m = (Metric) item[0];
            ToggleButton tb = new ToggleButton((String) item[1]);
            tb.setToggleGroup(group);
            tb.getStyleClass().add("stat-segment");
            tb.setSelected(m == metric);
            tb.setOnAction(e -> { metric = m; renderTemplates(); });   // re-renders with the pick (also when the same one was clicked)
            box.getChildren().add(tb);
        }
        return box;
    }

    private Node topRow(int rank, TemplateData d, long value, long max) {
        Label rankLabel = new Label(String.valueOf(rank));
        rankLabel.getStyleClass().add("stat-rank");
        rankLabel.setMinWidth(22);

        String name = d.getTemplateTitle();
        Label title = new Label(name == null || name.trim().isEmpty() ? I18n.t("Untitled") : name);
        title.getStyleClass().add("stat-row-title");
        title.setMaxWidth(Double.MAX_VALUE);

        StackPane track = new StackPane();
        track.getStyleClass().add("stat-bar-track");
        track.setAlignment(Pos.CENTER_LEFT);
        track.setMinHeight(8);
        track.setPrefHeight(8);
        track.setMaxHeight(8);
        Region fill = new Region();
        fill.getStyleClass().add("stat-bar-fill");
        final double fraction = max <= 0 ? 0 : Math.min(1.0, (double) value / max);
        fill.maxWidthProperty().bind(track.widthProperty().multiply(fraction));
        track.getChildren().add(fill);

        VBox middle = new VBox(6, title, track);
        HBox.setHgrow(middle, Priority.ALWAYS);

        Label count = new Label(compact(value));
        count.getStyleClass().add("stat-row-value");
        count.setMinWidth(60);
        count.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(12, rankLabel, middle, count);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ── Small pieces ───────────────────────────────────────────────────────────

    private VBox statTile(String label, String value, String sub, double valueSize) {
        Label v = new Label(value);
        v.getStyleClass().add("stat-value");
        v.setStyle("-fx-font-size: " + (int) valueSize + "px;");
        Label l = new Label(label);
        l.getStyleClass().add("stat-label");
        VBox tile = new VBox(3, v, l);
        if (sub != null && !sub.isEmpty()) {
            Label s = new Label(sub);
            s.getStyleClass().add("stat-sub");
            s.setMaxWidth(176);
            tile.getChildren().add(s);
        }
        tile.getStyleClass().add("stat-tile");
        tile.setPrefWidth(206);
        tile.setMinWidth(160);
        return tile;
    }

    private Label sectionTitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("stat-section-title");
        return l;
    }

    private Label smallTitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("stat-small-title");
        return l;
    }

    private Label mutedLine(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("text-muted");
        l.setWrapText(true);
        return l;
    }

    /** 950 -> "950", 1234 -> "1.2K", 3_400_000 -> "3.4M". */
    static String compact(long n) {
        if (n < 1000) return Long.toString(n);
        double v;
        String suffix;
        if (n < 1_000_000) { v = n / 1000.0; suffix = "K"; }
        else if (n < 1_000_000_000L) { v = n / 1_000_000.0; suffix = "M"; }
        else { v = n / 1_000_000_000.0; suffix = "B"; }
        String s = String.format(Locale.ROOT, "%.1f", v);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + suffix;
    }
}
