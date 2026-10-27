package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.auth.TemplateFeedSource;
import com.vanvatcorporation.doubleclips.auth.TemplatePager;
import com.vanvatcorporation.doubleclips.auth.TemplateRepository;
import com.vanvatcorporation.doubleclips.data.TemplateData;
import com.vanvatcorporation.doubleclips.ui.overlays.TemplatePreviewOverlay;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignH;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The grid of template cards, shared by the Templates tab and the Profile Liked / Saved lists.
 * Pages in as you scroll (and while the window is taller than what has loaded), shows the newest numbers from
 * {@link TemplateRepository} on every card, and opens a card in the preview overlay.
 */
public class TemplateGridView extends ScrollPane {

    public interface Keep {
        boolean keep(TemplateData data);
    }

    private final FlowPane grid = new FlowPane();
    private final Label statusLabel = new Label();
    private final VBox content = new VBox();
    private final TemplatePager pager;
    private final Keep keep;
    private final String emptyText;
    private Runnable onPreviewClosed = () -> { };

    private final List<TemplateData> items = new ArrayList<>();
    private final Map<String, Node> cards = new HashMap<>();
    private final Map<String, Runnable> refreshers = new HashMap<>();
    private boolean loading;
    private String errorText;

    private final Consumer<String> stateListener = id -> {
        Runnable r = refreshers.get(id.toLowerCase());
        if (r != null) r.run();
    };

    /**
     * @param keep      null = show everything; otherwise a card whose template no longer matches (unliked in the preview)
     *                  leaves the list when the preview closes
     * @param emptyText what to say when the list is empty (null = nothing)
     */
    public TemplateGridView(Keep keep, String emptyText) {
        this.keep = keep;
        this.emptyText = emptyText;

        grid.setHgap(20);
        grid.setVgap(20);
        grid.setPadding(new Insets(10, 0, 20, 0));

        statusLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 14px;");
        statusLabel.setManaged(false);
        statusLabel.setVisible(false);

        content.setSpacing(8);
        content.setPadding(new Insets(0, 0, 60, 0));
        content.getChildren().addAll(grid, statusLabel);
        content.setAlignment(Pos.TOP_CENTER);

        setContent(content);
        setFitToWidth(true);
        setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");

        pager = new TemplatePager(new TemplatePager.Listener() {
            @Override public void onReset() {
                items.clear();
                grid.getChildren().clear();
                cards.clear();
                refreshers.clear();
                errorText = null;
                updateStatus();
            }
            @Override public void onPage(List<TemplateData> fresh, boolean hasMore) {
                for (TemplateData d : fresh) {
                    items.add(d);
                    Node card = createCard(d);
                    cards.put(d.getTemplateId().toLowerCase(), card);
                    grid.getChildren().add(card);
                }
                updateStatus();
                Platform.runLater(TemplateGridView.this::maybeLoadMore);
            }
            @Override public void onLoading(boolean isLoading) {
                loading = isLoading;
                updateStatus();
            }
            @Override public void onError(String message) {
                errorText = message + " - scroll or press refresh to try again";
                updateStatus();
            }
        });

        // Infinite scroll: near the bottom, or the window is taller than what has loaded so far.
        vvalueProperty().addListener((o, a, b) -> maybeLoadMore());
        viewportBoundsProperty().addListener((o, a, b) -> Platform.runLater(this::maybeLoadMore));
        content.heightProperty().addListener((o, a, b) -> Platform.runLater(this::maybeLoadMore));

        // Listen for number changes only while on screen.
        sceneProperty().addListener((o, was, scene) -> {
            if (scene != null) {
                TemplateRepository.getInstance().addStateListener(stateListener);
                for (Runnable r : new ArrayList<>(refreshers.values())) r.run();   // a preview may have changed things while away
            } else {
                TemplateRepository.getInstance().removeStateListener(stateListener);
            }
        });
    }

    public TemplateFeedSource getSource() { return pager.getSource(); }

    /** Starts over: a new feed session, a new search, or the Liked / Saved list. */
    public void start(TemplateFeedSource source) {
        errorText = null;
        pager.start(source);
    }

    public void setOnPreviewClosed(Runnable r) { this.onPreviewClosed = r == null ? () -> { } : r; }

    /** Drops the cards that no longer belong (a template unliked in the preview leaves the Liked list). */
    public void prune() {
        if (keep == null) return;
        TemplateRepository repo = TemplateRepository.getInstance();
        boolean changed = false;
        for (Iterator<TemplateData> it = items.iterator(); it.hasNext(); ) {
            TemplateData d = it.next();
            repo.applyCached(d);
            if (!keep.keep(d)) {
                String k = d.getTemplateId().toLowerCase();
                Node card = cards.remove(k);
                if (card != null) grid.getChildren().remove(card);
                refreshers.remove(k);
                it.remove();
                changed = true;
            }
        }
        if (changed) updateStatus();
    }

    private void maybeLoadMore() {
        if (!pager.hasMore() || pager.isLoading()) return;
        double view = getViewportBounds().getHeight();
        double total = content.getHeight();
        if (view <= 0) return;                                  // not laid out yet
        if (total <= view + 1 || getVvalue() >= 0.85) pager.loadNext();
    }

    private void updateStatus() {
        String text = null;
        if (errorText != null) text = errorText;
        else if (loading && items.isEmpty()) text = "Loading\u2026";
        else if (loading) text = "Loading more\u2026";
        else if (items.isEmpty() && !pager.hasMore() && emptyText != null) text = emptyText;
        statusLabel.setText(text == null ? "" : text);
        statusLabel.setManaged(text != null);
        statusLabel.setVisible(text != null);
    }

    // ---- one card ---------------------------------------------------------------------------------------------------

    private Node createCard(TemplateData data) {
        VBox card = new VBox(12);
        card.getStyleClass().add("project-card");
        card.setPrefWidth(240);
        card.setCursor(javafx.scene.Cursor.HAND);

        StackPane thumbnailStack = new StackPane();
        thumbnailStack.getStyleClass().add("project-thumbnail");
        thumbnailStack.setPrefHeight(340);

        ImageView thumbView = new ImageView();
        thumbView.setFitWidth(240);
        thumbView.setPreserveRatio(true);
        if (data.getTemplateSnapshotLink() != null) {
            // 240 wide is all the card shows; background loading keeps the grid responsive while a page arrives.
            thumbView.setImage(new Image(data.getTemplateSnapshotLink(), 240, 0, true, true, true));
        }
        thumbnailStack.getChildren().add(thumbView);

        Label titleLabel = new Label(data.getTemplateTitle());
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        Label authorLabel = new Label("@" + data.getTemplateAuthor());
        authorLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");

        // views / uses / likes, live
        FontIcon heart = new FontIcon(MaterialDesignH.HEART_OUTLINE);
        Label heartCount = new Label();
        heartCount.setStyle("-fx-font-size: 12px;");
        Label stats = new Label();
        stats.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        HBox statsRow = new HBox(10, heart, heartCount, stats);
        statsRow.setAlignment(Pos.CENTER_LEFT);

        VBox info = new VBox(4, titleLabel, authorLabel, statsRow);
        card.getChildren().addAll(thumbnailStack, info);

        Runnable refresh = () -> {
            TemplateRepository.getInstance().applyCached(data);
            heart.setIconCode(data.isLiked ? MaterialDesignH.HEART : MaterialDesignH.HEART_OUTLINE);
            if (data.isLiked) heart.setIconColor(javafx.scene.paint.Color.web("#ff3b30"));
            else heart.setIconColor(javafx.scene.paint.Color.GRAY);
            heartCount.setText(String.valueOf(data.getHeartCount()));
            stats.setText(data.getViewCount() + " views \u00b7 " + data.getUseCount() + " uses");
        };
        refresh.run();
        refreshers.put(data.getTemplateId().toLowerCase(), refresh);

        card.setOnMouseClicked(e -> open(data));
        return card;
    }

    private void open(TemplateData data) {
        final TemplatePreviewOverlay[] ref = new TemplatePreviewOverlay[1];
        ref[0] = new TemplatePreviewOverlay(data, v -> {
            DoubleClipsDesktop.getInstance().hideOverlay(ref[0]);
            prune();
            onPreviewClosed.run();
        });
        DoubleClipsDesktop.getInstance().showOverlay(ref[0]);
    }
}
