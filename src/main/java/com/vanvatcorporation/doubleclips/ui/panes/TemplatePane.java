package com.vanvatcorporation.doubleclips.ui.panes;

import com.vanvatcorporation.doubleclips.i18n.I18n;

import com.vanvatcorporation.doubleclips.auth.AuthRepository;
import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.auth.TemplateFeedSource;
import com.vanvatcorporation.doubleclips.auth.TemplateSearchHints;
import com.vanvatcorporation.doubleclips.auth.TrendingSearch;
import com.vanvatcorporation.doubleclips.ui.components.TemplateSearchBar;
import com.vanvatcorporation.doubleclips.ui.overlays.TemplateSearchOverlay;
import java.util.ArrayList;
import java.util.List;
import com.vanvatcorporation.doubleclips.ui.TemplateGridView;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;

/**
 * The Templates tab: the signed-in account's suggestion feed (server side ranking), loaded a page at a time as you scroll.
 * The search bar shows the hot searches in turn and opens the search window (suggestions, trending, results).
 * The refresh button starts a new feed session.
 */
public class TemplatePane extends VBox {

    private TemplateGridView gridView = new TemplateGridView(null, "");
    private TemplateSearchBar searchBar;
    private Runnable hotListener;

    public TemplatePane() {
        setSpacing(24);
        setPadding(new Insets(24));
        getStyleClass().add("content-pane");

        // 1. Header
        Label titleLabel = new Label(I18n.t("Templates"));
        titleLabel.setStyle("-fx-font-size: 32px; -fx-font-weight: bold;");

        Button refreshButton = new Button(I18n.t("Refresh"));
        refreshButton.getStyleClass().add("button-transparent");
        refreshButton.setOnAction(e -> gridView.start(TemplateFeedSource.feed()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, titleLabel, spacer, refreshButton);
        header.setAlignment(Pos.CENTER_LEFT);

        // 2. Search Bar: the hot searches take turns in it; a click opens the search window (suggestions, trending, results)
        searchBar = new TemplateSearchBar(this::openSearch);
        updateHotTerms();

        // 3. Template Grid (paged)
        gridView = new TemplateGridView(null, I18n.t("No templates found"));
        gridView.setOnPageLoaded(this::updateHotTerms);   // no hot searches from the server: the bar uses the first titles

        getChildren().addAll(header, searchBar, gridView);
        VBox.setVgrow(gridView, Priority.ALWAYS);

        // The server's hot searches arrive (or are refreshed) after the pane is built; listened to only while the pane is on screen.
        hotListener = this::updateHotTerms;
        sceneProperty().addListener((o, was, scene) -> {
            if (scene != null) {
                TemplateSearchHints.getInstance().addListener(hotListener);
                TemplateSearchHints.getInstance().refresh(false);
                updateHotTerms();
            } else {
                TemplateSearchHints.getInstance().removeListener(hotListener);
            }
        });

        // Signed in / out: the likes in the list belong to the previous account, so start over.
        AuthRepository.getInstance().userProperty().addListener((obs, old, val) -> gridView.start(TemplateFeedSource.feed()));

        // Initial load: the feed
        gridView.start(TemplateFeedSource.feed());
    }

    /** What the bar rotates through: the server's hot searches, else the titles of the first templates in the feed. */
    private void updateHotTerms() {
        List<String> terms = new ArrayList<>();
        for (TrendingSearch t : TemplateSearchHints.getInstance().trending()) terms.add(t.text);
        if (terms.isEmpty()) terms = gridView.titles(8);
        searchBar.setTerms(terms);
    }

    private void openSearch(String hotTerm) {
        final TemplateSearchOverlay[] ref = new TemplateSearchOverlay[1];
        ref[0] = new TemplateSearchOverlay(hotTerm, () -> gridView.titles(40),
                v -> DoubleClipsDesktop.getInstance().hideOverlay(ref[0]));
        DoubleClipsDesktop.getInstance().showOverlay(ref[0]);
    }
}
