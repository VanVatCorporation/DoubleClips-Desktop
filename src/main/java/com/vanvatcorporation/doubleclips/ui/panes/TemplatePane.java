package com.vanvatcorporation.doubleclips.ui.panes;

import com.vanvatcorporation.doubleclips.auth.AuthRepository;
import com.vanvatcorporation.doubleclips.auth.TemplateFeedSource;
import com.vanvatcorporation.doubleclips.ui.TemplateGridView;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

/**
 * The Templates tab: the signed-in account's suggestion feed (server side ranking), loaded a page at a time as you scroll.
 * Typing in the search box switches to a server-side search of the same shape; clearing it returns to the feed.
 * The refresh button starts a new feed session.
 */
public class TemplatePane extends VBox {

    private static final double SEARCH_DEBOUNCE_MS = 400;

    private TemplateGridView gridView = new TemplateGridView(null, "");
    private final TextField searchField = new TextField();
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(SEARCH_DEBOUNCE_MS));

    public TemplatePane() {
        setSpacing(24);
        setPadding(new Insets(24));
        getStyleClass().add("content-pane");

        // 1. Header
        Label titleLabel = new Label("Templates");
        titleLabel.setStyle("-fx-font-size: 32px; -fx-font-weight: bold;");

        Button refreshButton = new Button("Refresh");
        refreshButton.getStyleClass().add("button-transparent");
        refreshButton.setOnAction(e -> gridView.start(wantedSource()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, titleLabel, spacer, refreshButton);
        header.setAlignment(Pos.CENTER_LEFT);

        // 2. Search Bar
        HBox searchContainer = new HBox(15);
        searchContainer.setAlignment(Pos.CENTER_LEFT);
        searchContainer.setPadding(new Insets(12, 18, 12, 18));
        searchContainer.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 25; -fx-border-color: -color-border-subtle; -fx-border-radius: 25;");

        FontIcon searchIcon = new FontIcon(MaterialDesignM.MAGNIFY);
        searchField.setPromptText("Search templates...");
        searchField.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-text-fill: -color-fg-default;");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        searchContainer.getChildren().addAll(searchIcon, searchField);

        // 3. Template Grid (paged)
        gridView = new TemplateGridView(null, "No templates found");

        getChildren().addAll(header, searchContainer, gridView);
        VBox.setVgrow(gridView, Priority.ALWAYS);

        // Search runs on the server (the cards on screen are only a page of it): wait for the typing to settle.
        searchField.textProperty().addListener((obs, old, val) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(e -> {
            TemplateFeedSource wanted = wantedSource();
            if (!wanted.equals(gridView.getSource())) gridView.start(wanted);
        });
        searchField.setOnAction(e -> {
            searchDelay.stop();
            TemplateFeedSource wanted = wantedSource();
            if (!wanted.equals(gridView.getSource())) gridView.start(wanted);
        });

        // Signed in / out: the likes in the list belong to the previous account, so start over.
        AuthRepository.getInstance().userProperty().addListener((obs, old, val) -> gridView.start(wantedSource()));

        // Initial load: the feed
        gridView.start(TemplateFeedSource.feed());
    }

    private TemplateFeedSource wantedSource() {
        String query = searchField.getText() == null ? "" : searchField.getText().trim();
        return query.isEmpty() ? TemplateFeedSource.feed() : TemplateFeedSource.search(query);
    }
}
