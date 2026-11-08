package com.vanvatcorporation.doubleclips.ui.overlays;

import com.vanvatcorporation.doubleclips.auth.AuthRepository;
import com.vanvatcorporation.doubleclips.auth.AuthorStats;
import com.vanvatcorporation.doubleclips.auth.AuthorStatsRepository;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.ProjectRepository;
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
import javafx.scene.control.Tooltip;
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
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Profile -> Statistics. Two parts: what is on THIS computer (projects, footage, disk space, activity by month; works signed out),
 * and how the account's templates perform, which the SERVER counts (GET /templates/stats): all-time totals, the last 7 / 30 / 90
 * days compared with the same number of days before, a per-day chart of other people's activity, and the top templates.
 */
public class StatisticsOverlay extends StackPane {

    private enum Metric { VIEWS, USES, LIKES, SAVES }

    private final Consumer<Void> onClose;
    private boolean closed;

    private final VBox templatesSection = new VBox(14);
    private int loadGeneration;
    private boolean templatesLoading;
    private String templatesError;
    private AuthorStats remote;
    private int days = 30;
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

    // ── Your templates (counted by the server) ────────────────────────────────

    private void loadTemplates() {
        remote = null;
        templatesError = null;
        if (AuthRepository.getInstance().getCurrentUser() == null) {
            templatesLoading = false;
            renderTemplates();
            return;
        }
        final int gen = ++loadGeneration;
        templatesLoading = true;
        renderTemplates();
        AuthorStatsRepository.fetch(days,
                stats -> {
                    if (gen != loadGeneration || closed) return;
                    remote = stats;
                    templatesLoading = false;
                    renderTemplates();
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

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox titleRow = new HBox(12, sectionTitle(I18n.t("Your templates")), grow);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        boolean signedIn = AuthRepository.getInstance().getCurrentUser() != null;
        if (signedIn) titleRow.getChildren().add(rangeSwitch());
        templatesSection.getChildren().add(titleRow);

        if (!signedIn) {
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
        if (remote == null || remote.totalsOrEmpty().templates == 0) {
            templatesSection.getChildren().add(mutedLine(I18n.t("You haven't posted a template yet")));
            return;
        }

        // all time: the counters on the template cards
        AuthorStats.Totals t = remote.totalsOrEmpty();
        String useRate = t.views > 0 ? I18n.f("%s of views", String.format(Locale.ROOT, "%.1f%%", 100.0 * t.uses / t.views)) : null;
        FlowPane totals = new FlowPane(12, 12);
        totals.getChildren().add(statTile(I18n.t("Templates"), compact(t.templates), null, 28));
        totals.getChildren().add(statTile(I18n.t("Views"), compact(t.views), null, 28));
        totals.getChildren().add(statTile(I18n.t("Uses"), compact(t.uses), useRate, 28));
        totals.getChildren().add(statTile(I18n.t("Likes"), compact(t.likes), null, 28));
        totals.getChildren().add(statTile(I18n.t("Saves"), compact(t.saves), null, 28));
        totals.getChildren().add(statTile(I18n.t("Comments"), compact(t.comments), null, 28));
        totals.getChildren().add(statTile(I18n.t("Unique viewers"), compact(t.uniqueViewers), I18n.t("people who opened them"), 28));
        templatesSection.getChildren().addAll(smallTitle(I18n.t("All time")), totals);

        // this period against the one before it (other people's activity only)
        AuthorStats.Counts cur = remote.currentOrEmpty();
        AuthorStats.Counts prev = remote.previousOrEmpty();
        FlowPane period = new FlowPane(12, 12);
        period.getChildren().add(deltaTile(I18n.t("New viewers"), cur.newViewers, prev.newViewers));
        period.getChildren().add(deltaTile(I18n.t("Uses"), cur.uses, prev.uses));
        period.getChildren().add(deltaTile(I18n.t("Likes"), cur.likes, prev.likes));
        period.getChildren().add(deltaTile(I18n.t("Saves"), cur.saves, prev.saves));
        period.getChildren().add(deltaTile(I18n.t("Comments"), cur.comments, prev.comments));
        templatesSection.getChildren().addAll(
                smallTitle(I18n.f("Last %d days (change against the %d days before)", days, days)), period);

        // the day-by-day chart and the top list follow the metric picked in the switch
        templatesSection.getChildren().addAll(smallTitle(I18n.t("Activity per day")), metricSwitch(), dailyChart(remote.seriesOrEmpty()));

        List<AuthorStats.Item> sorted = new ArrayList<>(remote.templatesOrEmpty());
        sorted.sort((a, b) -> Long.compare(itemValue(b), itemValue(a)));
        long top = sorted.isEmpty() ? 0 : itemValue(sorted.get(0));
        VBox list = new VBox(14);
        for (int i = 0; i < Math.min(5, sorted.size()); i++) {
            list.getChildren().add(topRow(i + 1, sorted.get(i), itemValue(sorted.get(i)), top));
        }
        templatesSection.getChildren().addAll(smallTitle(I18n.t("Top templates")), list);
    }

    private long dayValue(AuthorStats.Day d) {
        switch (metric) {
            case VIEWS: return d.newViewers;
            case LIKES: return d.likes;
            case SAVES: return d.saves;
            case USES:
            default: return d.uses;
        }
    }

    private long itemValue(AuthorStats.Item d) {
        switch (metric) {
            case VIEWS: return d.views;
            case LIKES: return d.likes;
            case SAVES: return d.saves;
            case USES:
            default: return d.uses;
        }
    }

    private String metricUnit() {
        switch (metric) {
            case VIEWS: return I18n.t("new viewers");
            case LIKES: return I18n.t("likes");
            case SAVES: return I18n.t("saves");
            case USES:
            default: return I18n.t("uses");
        }
    }

    /** 7 / 30 / 90 days: asks the server again. */
    private Node rangeSwitch() {
        ToggleGroup group = new ToggleGroup();
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_RIGHT);
        for (int option : new int[]{7, 30, 90}) {
            ToggleButton tb = new ToggleButton(I18n.f("%d days", option));
            tb.setToggleGroup(group);
            tb.getStyleClass().add("stat-segment");
            tb.setSelected(option == days);
            tb.setOnAction(e -> {
                if (option != days) { days = option; loadTemplates(); }
                else tb.setSelected(true);                       // the picked one stays picked
            });
            box.getChildren().add(tb);
        }
        return box;
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

    /** One bar per local day of the current period; hover a bar for the day and the number. */
    private Node dailyChart(List<AuthorStats.Day> series) {
        long max = 1;
        for (AuthorStats.Day d : series) max = Math.max(max, dayValue(d));

        HBox bars = new HBox(series.size() > 45 ? 1 : 3);
        bars.setAlignment(Pos.BOTTOM_LEFT);
        bars.setMinHeight(130);
        bars.setPrefHeight(130);
        for (AuthorStats.Day d : series) {
            long v = dayValue(d);
            double h = v == 0 ? 3 : 8 + 112.0 * v / max;
            Region bar = new Region();
            bar.getStyleClass().add(v == 0 ? "stat-bar-zero" : "stat-bar");
            bar.setMinSize(2, h);
            bar.setPrefHeight(h);
            bar.setMaxSize(Double.MAX_VALUE, h);
            HBox.setHgrow(bar, Priority.ALWAYS);
            Tooltip.install(bar, new Tooltip(d.date + ": " + v + " " + metricUnit()));
            bars.getChildren().add(bar);
        }

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Label from = new Label(series.isEmpty() ? "" : series.get(0).date);
        from.getStyleClass().add("stat-sub");
        Label to = new Label(series.isEmpty() ? "" : series.get(series.size() - 1).date);
        to.getStyleClass().add("stat-sub");
        return new VBox(6, bars, new HBox(from, gap, to));
    }

    private Node topRow(int rank, AuthorStats.Item d, long value, long max) {
        Label rankLabel = new Label(String.valueOf(rank));
        rankLabel.getStyleClass().add("stat-rank");
        rankLabel.setMinWidth(22);

        String name = d.title;
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

    /** A tile with this period's number and a green / red change against the previous period. */
    private VBox deltaTile(String label, long current, long previous) {
        String text;
        String style;
        if (current == previous) { text = I18n.t("No change"); style = "stat-sub"; }
        else if (previous == 0) { text = "\u25b2 " + I18n.t("new"); style = "stat-up"; }
        else {
            double pct = 100.0 * (current - previous) / previous;
            boolean up = pct > 0;
            text = (up ? "\u25b2 " : "\u25bc ") + String.format(Locale.ROOT, "%.0f%%", Math.abs(pct));
            style = up ? "stat-up" : "stat-down";
        }
        return statTile(label, compact(current), text, 28, style);
    }

    // ── Small pieces ───────────────────────────────────────────────────────────

    private VBox statTile(String label, String value, String sub, double valueSize) {
        return statTile(label, value, sub, valueSize, "stat-sub");
    }

    private VBox statTile(String label, String value, String sub, double valueSize, String subClass) {
        Label v = new Label(value);
        v.getStyleClass().add("stat-value");
        v.setStyle("-fx-font-size: " + (int) valueSize + "px;");
        Label l = new Label(label);
        l.getStyleClass().add("stat-label");
        VBox tile = new VBox(3, v, l);
        if (sub != null && !sub.isEmpty()) {
            Label s = new Label(sub);
            s.getStyleClass().add(subClass);
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
