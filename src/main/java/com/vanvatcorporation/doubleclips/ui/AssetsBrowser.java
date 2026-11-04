package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.drive.AssetKind;
import com.vanvatcorporation.doubleclips.drive.AssetsClient;
import com.vanvatcorporation.doubleclips.drive.BlockedAuthors;
import com.vanvatcorporation.doubleclips.drive.CommunityAsset;
import com.vanvatcorporation.doubleclips.drive.DoubleClipsDrive;
import com.vanvatcorporation.doubleclips.drive.DriveError;
import com.vanvatcorporation.doubleclips.i18n.I18n;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The community assets, live from the server (iOS: AssetsView): kind chips, Newest / Most downloaded, a grid that pages in as
 * you scroll. Used by the main window's Assets tab and by the editor's Assets window (which passes a {@link AssetTarget}).
 * Browsing needs no sign-in. Assets of people the user hid are left out. A card opens {@link AssetDetailWindow}.
 */
public final class AssetsBrowser extends VBox {

    private final AssetTarget target;
    private final double cardWidth;

    private final FlowPane grid = new FlowPane();
    private final Label statusLabel = new Label();
    private final VBox content = new VBox();
    private final ScrollPane scroll = new ScrollPane();

    private final List<CommunityAsset> items = new ArrayList<>();
    private final Map<String, Node> cards = new HashMap<>();

    private AssetKind kind;                       // null = every kind
    private String sort = AssetsClient.SORT_NEW;
    private String query = "";
    private String cursor;
    private boolean hasMore = true, loading, failed;
    private String errorText;
    /** Bumped at every restart: the answer of an older request is dropped. */
    private int generation;

    private final Runnable blockedListener = () -> Platform.runLater(this::redraw);

    /**
     * @param target    where media and fonts go (the editor's project), or null in the main window
     * @param cardWidth 220 for the tab, about 150 for the editor's narrower window
     */
    public AssetsBrowser(AssetTarget target, double cardWidth) {
        this.target = target;
        this.cardWidth = cardWidth;
        setSpacing(12);

        getChildren().addAll(buildToolbar(), buildGrid());
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // People hidden in a detail window leave the list; listened to only while on screen.
        sceneProperty().addListener((o, was, scene) -> {
            if (scene != null) {
                BlockedAuthors.addListener(blockedListener);
                redraw();
            } else {
                BlockedAuthors.removeListener(blockedListener);
            }
        });

        start();
    }

    // ---- the controls ------------------------------------------------------------------------------------

    private Node buildToolbar() {
        FlowPane chips = new FlowPane(6, 6);
        ToggleGroup group = new ToggleGroup();
        chips.getChildren().add(chip(I18n.t("All"), null, group, true));
        for (AssetKind k : AssetKind.values()) chips.getChildren().add(chip(I18n.t(k.chipTitle), k, group, false));
        // one chip is always on: pressing the selected one again keeps it
        group.selectedToggleProperty().addListener((o, was, now) -> { if (now == null && was != null) group.selectToggle(was); });
        HBox.setHgrow(chips, Priority.ALWAYS);

        ComboBox<String> sortBox = new ComboBox<>();
        sortBox.getItems().addAll(I18n.t("Newest"), I18n.t("Most downloaded"));
        sortBox.getSelectionModel().select(0);
        sortBox.setOnAction(e -> {
            sort = sortBox.getSelectionModel().getSelectedIndex() == 1 ? AssetsClient.SORT_POPULAR : AssetsClient.SORT_NEW;
            start();
        });

        Button refresh = new Button(I18n.t("Refresh"), new FontIcon(MaterialDesignR.REFRESH));
        refresh.getStyleClass().add("button-transparent");
        refresh.setOnAction(e -> start());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(8, sortBox, spacer, refresh);
        controls.setAlignment(Pos.CENTER_LEFT);

        return new VBox(8, chips, controls);
    }

    private ToggleButton chip(String text, AssetKind chipKind, ToggleGroup group, boolean selected) {
        ToggleButton b = new ToggleButton(text);
        b.setToggleGroup(group);
        b.setSelected(selected);
        b.setOnAction(e -> {
            if (kind == chipKind) return;
            kind = chipKind;
            start();
        });
        return b;
    }

    private Node buildGrid() {
        grid.setHgap(16);
        grid.setVgap(16);
        grid.setPadding(new Insets(6, 0, 16, 0));

        statusLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 14px;");
        statusLabel.setWrapText(true);
        statusLabel.setManaged(false);
        statusLabel.setVisible(false);

        content.setSpacing(8);
        content.setPadding(new Insets(0, 0, 40, 0));
        content.setAlignment(Pos.TOP_CENTER);
        content.getChildren().addAll(grid, statusLabel);

        scroll.setContent(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");

        // Infinite scroll: near the bottom, or the window is taller than what has loaded so far.
        scroll.vvalueProperty().addListener((o, a, b) -> { failed = false; maybeLoadMore(); });
        scroll.viewportBoundsProperty().addListener((o, a, b) -> Platform.runLater(this::maybeLoadMore));
        content.heightProperty().addListener((o, a, b) -> Platform.runLater(this::maybeLoadMore));
        return scroll;
    }

    // ---- loading -----------------------------------------------------------------------------------------

    /** Search words (matched on title, tags and description); "" = everything. */
    public void setQuery(String text) {
        String next = text == null ? "" : text.trim();
        if (next.equals(query)) return;
        query = next;
        start();
    }

    /** Starts over from the first page (a new filter, sort or search, or the Refresh button). */
    public void start() {
        generation++;
        items.clear();
        cards.clear();
        grid.getChildren().clear();
        cursor = null;
        hasMore = true;
        loading = false;
        failed = false;
        errorText = null;
        updateStatus();
        loadNext();
    }

    private void loadNext() {
        if (loading || !hasMore) return;
        loading = true;
        failed = false;
        updateStatus();
        final int gen = generation;
        final AssetKind askedKind = kind;
        final String askedQuery = query, askedSort = sort, askedCursor = cursor;
        Thread t = new Thread(() -> {
            try {
                AssetsClient.Page page = DoubleClipsDrive.assets.list(askedKind, askedQuery, askedSort, askedCursor);
                Platform.runLater(() -> onPage(gen, page));
            } catch (IOException | RuntimeException e) {
                String message = DriveError.describe(e);
                Platform.runLater(() -> onError(gen, message));
            }
        }, "assets-list");
        t.setDaemon(true);
        t.start();
    }

    private void onPage(int gen, AssetsClient.Page page) {
        if (gen != generation) return;           // a newer list was started meanwhile
        loading = false;
        for (CommunityAsset a : page.items) {
            if (!cards.containsKey(a.id)) items.add(a);
            cards.putIfAbsent(a.id, null);        // placeholder: the card is built when first shown
        }
        cursor = page.next;
        hasMore = page.next != null && !page.items.isEmpty();   // an empty page never asks for another
        redraw();
        Platform.runLater(this::maybeLoadMore);
    }

    private void onError(int gen, String message) {
        if (gen != generation) return;
        loading = false;
        failed = true;
        errorText = I18n.f("%s - scroll or press refresh to try again", message);
        updateStatus();
    }

    private void maybeLoadMore() {
        if (!hasMore || loading || failed) return;
        double view = scroll.getViewportBounds().getHeight();
        double total = content.getHeight();
        if (view <= 0) return;                    // not laid out yet
        if (total <= view + 1 || scroll.getVvalue() >= 0.85) loadNext();
    }

    // ---- drawing -----------------------------------------------------------------------------------------

    /** The grid from the loaded items, minus those of hidden people. Cards are built once and reused. */
    private void redraw() {
        List<Node> shown = new ArrayList<>();
        for (CommunityAsset a : items) {
            if (BlockedAuthors.contains(a.ownerName)) continue;
            Node card = cards.get(a.id);
            if (card == null) {
                card = createCard(a);
                cards.put(a.id, card);
            }
            shown.add(card);
        }
        grid.getChildren().setAll(shown);
        updateStatus();
    }

    private void updateStatus() {
        String text = null;
        if (errorText != null) text = errorText;
        else if (loading && grid.getChildren().isEmpty()) text = I18n.t("Loading\u2026");
        else if (loading) text = I18n.t("Loading more\u2026");
        else if (grid.getChildren().isEmpty() && !hasMore) {
            text = query.isEmpty() ? I18n.t("No assets yet") : I18n.t("No assets found");
        }
        statusLabel.setText(text == null ? "" : text);
        statusLabel.setManaged(text != null);
        statusLabel.setVisible(text != null);
    }

    private Node createCard(CommunityAsset a) {
        VBox card = new VBox(8);
        card.getStyleClass().add("project-card");
        card.setPrefWidth(cardWidth);
        card.setMinWidth(cardWidth);
        card.setMaxWidth(cardWidth);
        card.setCursor(Cursor.HAND);

        double thumbHeight = Math.round(cardWidth * 0.62);
        StackPane thumb = new StackPane();
        thumb.getStyleClass().add("project-thumbnail");
        thumb.setPrefSize(cardWidth, thumbHeight);
        thumb.setMinSize(cardWidth, thumbHeight);
        thumb.setMaxSize(cardWidth, thumbHeight);
        FontIcon placeholder = new FontIcon(AssetUi.icon(a.kind));
        placeholder.setIconSize(30);
        thumb.getChildren().add(placeholder);
        if (a.previewUrl != null) {
            // The poster's small JPEG: browsing never touches the poster's file or allowance. Background loading keeps the grid responsive.
            Image image = new Image(a.previewUrl, cardWidth, 0, true, true, true);
            ImageView view = new ImageView(image);
            view.setFitWidth(cardWidth);
            view.setFitHeight(thumbHeight);
            view.setPreserveRatio(true);
            view.setVisible(false);
            Runnable decide = () -> view.setVisible(image.getProgress() >= 1.0 && !image.isError());
            image.progressProperty().addListener((o, was, now) -> decide.run());
            image.errorProperty().addListener((o, was, now) -> decide.run());
            decide.run();
            thumb.getChildren().add(view);
        }

        Label title = new Label(a.title);
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        title.setWrapText(true);
        title.setMaxWidth(cardWidth - 24);
        title.setMaxHeight(40);
        Label owner = new Label(a.ownerName == null ? "" : "@" + a.ownerName);
        owner.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        FontIcon kindIcon = new FontIcon(AssetUi.icon(a.kind));
        Label meta = new Label(I18n.t(a.kind.singular) + " \u00b7 " + AssetUi.size(a.size) + " \u00b7 \u2193 " + AssetUi.count(a.downloads));
        meta.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        HBox metaRow = new HBox(6, kindIcon, meta);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(thumb, new VBox(3, title, owner, metaRow));
        card.setOnMouseClicked(e -> {
            Window owningWindow = getScene() == null ? null : getScene().getWindow();
            AssetDetailWindow.show(a, target, owningWindow);
        });
        return card;
    }
}
