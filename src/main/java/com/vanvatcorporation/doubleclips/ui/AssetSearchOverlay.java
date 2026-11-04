package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.auth.TemplateSearchHistory;
import com.vanvatcorporation.doubleclips.auth.TemplateSearchParse;
import com.vanvatcorporation.doubleclips.auth.TrendingSearch;
import com.vanvatcorporation.doubleclips.drive.DoubleClipsDrive;
import com.vanvatcorporation.doubleclips.i18n.I18n;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Assets search window (iOS: AssetSearchView), the same flow as the Templates one:
 * <ul>
 *   <li>Empty field: your recent searches, "You may like" and the ranked "Trending" list from the server.</li>
 *   <li>Typing: suggestions from the server (instant matches from this computer first); the arrow copies one into the field.</li>
 *   <li>Search / Enter / a clicked row: the results, paged from the server by the same {@link AssetsBrowser} the tab uses (kind chips,
 *       sort, Get). Clicking the field again goes back to the suggestions.</li>
 * </ul>
 * An empty "Search" press searches the hot term that was showing in the bar (the field's prompt). A submitted search is remembered here
 * and, when signed in, told to the server, which ranks "Trending" from them. It is a layer inside whatever window shows it: the main
 * window's overlay layer for the tab, the Assets window's own root in the editor.
 */
public final class AssetSearchOverlay extends StackPane {

    private enum Phase { INPUT, RESULTS }

    private static final double SUGGEST_DEBOUNCE_MS = 250;

    private final Consumer<Void> onClose;
    private final String placeholder;
    private final Supplier<List<String>> fallbackTitles;
    private final AssetTarget target;
    private final double cardWidth;
    private final AssetSearchHints hints = AssetSearchHints.getInstance();
    private final TemplateSearchHistory history = TemplateSearchHistory.assets();

    private final TextField field = new TextField();
    private final Button clearButton = new Button();
    private final Button searchButton = new Button(I18n.t("Search"));
    private final VBox inputList = new VBox();
    private final ScrollPane inputScroll = new ScrollPane(inputList);
    private final StackPane body = new StackPane();
    private final PauseTransition suggestDelay = new PauseTransition(Duration.millis(SUGGEST_DEBOUNCE_MS));
    private final Runnable listListener = this::renderInput;

    private AssetsBrowser results;       // made at the first search
    private Phase phase = Phase.INPUT;
    private boolean showAllHistory;
    private List<String> serverSuggestions = new ArrayList<>();
    private String serverSuggestionsFor = "";
    private int suggestTicket;
    private boolean closed;

    /**
     * @param placeholder    the hot search showing in the bar when it was clicked ("" = none)
     * @param fallbackTitles titles of assets the screen already shows: suggestions when the server has none
     * @param target         where media and fonts go (the editor's project), or null in the main window
     * @param cardWidth      the results' card width (220 in the tab, about 150 in the editor's narrower window)
     */
    public AssetSearchOverlay(String placeholder, Supplier<List<String>> fallbackTitles, AssetTarget target, double cardWidth,
                              Consumer<Void> onClose) {
        this.onClose = onClose;
        this.placeholder = placeholder == null ? "" : placeholder;
        this.fallbackTitles = fallbackTitles == null ? () -> new ArrayList<>() : fallbackTitles;
        this.target = target;
        this.cardWidth = cardWidth;
        getStyleClass().add("preview-overlay");

        VBox card = new VBox(12);
        card.getStyleClass().add("template-dialog");
        card.setMaxSize(900, 780);
        card.setPrefSize(900, 780);
        card.setPadding(new Insets(20));
        StackPane.setMargin(card, new Insets(20));

        // ── top bar: back, the field with its clear button, Search ─────────────────────────────
        Button back = new Button();
        back.setGraphic(new FontIcon(MaterialDesignA.ARROW_LEFT));
        back.getStyleClass().add("button-transparent");
        back.setOnAction(e -> close());

        field.setPromptText(this.placeholder.isEmpty() ? I18n.t("Search assets...") : this.placeholder);
        field.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-text-fill: -color-fg-default;");
        HBox.setHgrow(field, Priority.ALWAYS);
        clearButton.setGraphic(new FontIcon(MaterialDesignC.CLOSE_CIRCLE));
        clearButton.getStyleClass().add("button-transparent");
        clearButton.setVisible(false);
        clearButton.setManaged(false);
        clearButton.setOnAction(e -> clearField());
        HBox pill = new HBox(10, new FontIcon(MaterialDesignM.MAGNIFY), field, clearButton);
        pill.setAlignment(Pos.CENTER_LEFT);
        pill.setPadding(new Insets(6, 14, 6, 16));
        pill.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 25; -fx-border-color: -color-border-subtle; -fx-border-radius: 25;");
        HBox.setHgrow(pill, Priority.ALWAYS);

        searchButton.getStyleClass().add("button-transparent");
        searchButton.setStyle("-fx-font-weight: bold;");
        searchButton.setOnAction(e -> submit(null));

        HBox topBar = new HBox(10, back, pill, searchButton);
        topBar.setAlignment(Pos.CENTER_LEFT);

        // ── body: the suggestions / start page, or the results ─────────────────────────────────
        inputList.setFillWidth(true);
        inputScroll.setFitToWidth(true);
        inputScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
        body.getChildren().add(inputScroll);
        VBox.setVgrow(body, Priority.ALWAYS);

        card.getChildren().addAll(topBar, body);
        getChildren().add(card);

        // ── behaviour ──────────────────────────────────────────────────────────────────────────
        field.textProperty().addListener((obs, was, now) -> {
            boolean has = now != null && !now.isEmpty();
            clearButton.setVisible(has);
            clearButton.setManaged(has);
            if (phase != Phase.INPUT) return;
            renderInput();                 // instant matches from this computer
            suggestDelay.playFromStart();  // the server's after the typing settles
        });
        suggestDelay.setOnFinished(e -> loadServerSuggestions());
        field.setOnAction(e -> submit(null));
        // touching the field in the results = back to the suggestions
        field.focusedProperty().addListener((obs, was, focused) -> {
            if (focused && phase == Phase.RESULTS) showInput();
        });
        field.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) { close(); e.consume(); } });

        setFocusTraversable(true);
        setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) { close(); e.consume(); } });
        setOnMouseClicked(e -> { if (e.getTarget() == this) close(); });   // a click on the dim area closes it

        sceneProperty().addListener((o, was, scene) -> {
            if (scene != null) {
                hints.addListener(listListener);
                history.addListener(listListener);
                hints.refresh(false);
                renderInput();
                Platform.runLater(field::requestFocus);
            } else {
                hints.removeListener(listListener);
                history.removeListener(listListener);
            }
        });
        renderInput();
    }

    // ── phases ───────────────────────────────────────────────────────────────────────────────────

    private void showInput() {
        phase = Phase.INPUT;
        body.getChildren().setAll(inputScroll);
        searchButton.setVisible(true);
        searchButton.setManaged(true);
        renderInput();
    }

    private void showResults() {
        phase = Phase.RESULTS;
        searchButton.setVisible(false);
        searchButton.setManaged(false);
    }

    private void clearField() {
        field.setText("");
        showInput();
        field.requestFocus();
    }

    private String typed() {
        return field.getText() == null ? "" : field.getText().trim();
    }

    /** Search: what was typed, a suggestion / history row that was clicked, or (nothing typed) the hot term from the bar. */
    private void submit(String chosen) {
        String query = (chosen != null ? chosen : typed()).trim();
        if (query.isEmpty()) query = placeholder;
        if (query.isEmpty()) return;
        suggestDelay.stop();
        suggestTicket++;
        showResults();               // before the text changes, so the field's listener doesn't ask for suggestions
        field.setText(query);
        requestFocus();              // out of the field: touching it again means "back to the suggestions"
        history.add(query);
        logSearch(query);
        if (results == null) {
            results = new AssetsBrowser(target, cardWidth, query);   // loads the first page for this query
        } else {
            results.setQuery(query);
        }
        body.getChildren().setAll(results);
    }

    /** Signed-in searches only (one person can't push a term up); best effort, never in the way of the results. */
    private void logSearch(String query) {
        if (!DoubleClipsDrive.hasSession()) return;
        Thread t = new Thread(() -> {
            try {
                DoubleClipsDrive.assets.logSearch(query);
            } catch (IOException | RuntimeException ignored) {
                // the ranking just misses one search
            }
        }, "asset-search-log");
        t.setDaemon(true);
        t.start();
    }

    // ── suggestions ──────────────────────────────────────────────────────────────────────────────

    private void loadServerSuggestions() {
        final String query = typed();
        if (phase != Phase.INPUT || query.isEmpty()) {
            serverSuggestions = new ArrayList<>();
            serverSuggestionsFor = "";
            return;
        }
        final int ticket = ++suggestTicket;
        Thread t = new Thread(() -> {
            try {
                List<String> found = DoubleClipsDrive.assets.suggest(query, 10);
                Platform.runLater(() -> {
                    if (closed || ticket != suggestTicket) return;   // typed on, or searched, meanwhile
                    serverSuggestions = found;
                    serverSuggestionsFor = query.toLowerCase();
                    renderInput();
                });
            } catch (IOException | RuntimeException ignored) {
                // the phone-side suggestions stay
            }
        }, "asset-suggest");
        t.setDaemon(true);
        t.start();
    }

    private List<String> shownSuggestions() {
        String typed = typed();
        if (serverSuggestionsFor.equals(typed.toLowerCase()) && !serverSuggestions.isEmpty()) return serverSuggestions;
        return TemplateSearchParse.localSuggestions(typed, history.items(), hints.trending(), fallbackTitles.get());
    }

    // ── drawing the start page / suggestions ─────────────────────────────────────────────────────

    private void renderInput() {
        if (phase != Phase.INPUT) return;
        inputList.getChildren().clear();
        if (typed().isEmpty()) {
            buildStartPage();
        } else {
            for (String suggestion : shownSuggestions()) inputList.getChildren().add(suggestionRow(suggestion));
        }
    }

    private void buildStartPage() {
        List<String> recent = history.items();
        List<String> rows = showAllHistory ? recent : new ArrayList<>(recent.subList(0, Math.min(2, recent.size())));
        for (String item : rows) inputList.getChildren().add(historyRow(item));
        if (recent.size() > 2) {
            Button toggle = new Button(I18n.t(showAllHistory ? "Show less" : "Show all searches"));
            toggle.getStyleClass().add("button-transparent");
            toggle.setMaxWidth(Double.MAX_VALUE);
            toggle.setOnAction(e -> { showAllHistory = !showAllHistory; renderInput(); });
            inputList.getChildren().add(toggle);
        }

        if (!hints.forYou().isEmpty()) {
            inputList.getChildren().add(sectionTitle(I18n.t("You may like")));
            for (String item : hints.forYou()) inputList.getChildren().add(forYouRow(item));
        }

        if (!hints.trending().isEmpty()) {
            inputList.getChildren().add(sectionTitle(I18n.t("Trending")));
            int rank = 0;
            for (TrendingSearch item : hints.trending()) inputList.getChildren().add(trendingRow(++rank, item));
        }
    }

    private static Label sectionTitle(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        l.setPadding(new Insets(20, 6, 8, 6));
        return l;
    }

    /** A clickable row of the list: highlights under the pointer, searches {@code query} on click. */
    private HBox row(String query, Node... parts) {
        HBox r = new HBox(12, parts);
        r.setAlignment(Pos.CENTER_LEFT);
        r.setPadding(new Insets(6, 8, 6, 12));
        r.setMinHeight(44);
        r.setCursor(javafx.scene.Cursor.HAND);
        r.setOnMouseEntered(e -> r.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 10;"));
        r.setOnMouseExited(e -> r.setStyle(""));
        r.setOnMouseClicked(e -> submit(query));
        return r;
    }

    private static Region grow() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private HBox historyRow(String item) {
        Button remove = new Button();
        remove.setGraphic(new FontIcon(MaterialDesignC.CLOSE));
        remove.getStyleClass().add("button-transparent");
        remove.setOnAction(e -> { history.remove(item); e.consume(); });
        Label text = new Label(item);
        return row(item, new FontIcon(MaterialDesignC.CLOCK_OUTLINE), text, grow(), remove);
    }

    private HBox forYouRow(String item) {
        Label dot = new Label("\u2022");
        dot.setStyle("-fx-text-fill: -color-fg-muted;");
        return row(item, dot, new Label(item), grow());
    }

    private HBox trendingRow(int rank, TrendingSearch item) {
        Label number = new Label(String.valueOf(rank));
        number.setMinWidth(28);
        number.setStyle("-fx-font-weight: bold; -fx-text-fill: " + (rank <= 3 ? "-color-accent-fg" : "-color-fg-muted") + ";");
        List<Node> parts = new ArrayList<>();
        parts.add(number);
        parts.add(new Label(item.text));
        if (item.hot) {
            FontIcon flame = new FontIcon(MaterialDesignF.FIRE);
            flame.setStyle("-fx-icon-color: orange;");
            parts.add(flame);
        }
        parts.add(grow());
        if (item.count != null) {
            Label count = new Label(TemplateSearchParse.compactCount(item.count));
            count.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
            parts.add(count);
        }
        return row(item.text, parts.toArray(new Node[0]));
    }

    /** The typed part muted, the rest of the suggestion in the normal colour (like CapCut); the arrow copies it into the field. */
    private HBox suggestionRow(String suggestion) {
        int n = TemplateSearchParse.typedPrefixLength(suggestion, typed());
        TextFlow flow = new TextFlow();
        if (n > 0) {
            Text typedPart = new Text(suggestion.substring(0, n));
            typedPart.setStyle("-fx-fill: -color-fg-muted;");
            Text rest = new Text(suggestion.substring(n));
            rest.setStyle("-fx-fill: -color-fg-default;");
            flow.getChildren().addAll(typedPart, rest);
        } else {
            Text whole = new Text(suggestion);
            whole.setStyle("-fx-fill: -color-fg-default;");
            flow.getChildren().add(whole);
        }
        Button insert = new Button();
        insert.setGraphic(new FontIcon(MaterialDesignA.ARROW_TOP_LEFT));
        insert.getStyleClass().add("button-transparent");
        insert.setOnAction(e -> {
            field.setText(suggestion);   // copy into the field and keep typing
            field.requestFocus();
            field.positionCaret(suggestion.length());
            e.consume();
        });
        return row(suggestion, new FontIcon(MaterialDesignM.MAGNIFY), flow, grow(), insert);
    }

    // ── closing ──────────────────────────────────────────────────────────────────────────────────

    private void close() {
        if (closed) return;
        closed = true;
        suggestDelay.stop();
        suggestTicket++;
        hints.removeListener(listListener);
        history.removeListener(listListener);
        onClose.accept(null);
    }
}
