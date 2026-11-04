package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.auth.TrendingSearch;
import com.vanvatcorporation.doubleclips.i18n.I18n;
import com.vanvatcorporation.doubleclips.ui.components.AppUi;
import com.vanvatcorporation.doubleclips.ui.components.TemplateSearchBar;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor's Assets window (iOS: the Assets sheet next to Files): the same live community list as the Assets tab, opened beside
 * the editor and left open while you work. What you get goes straight into the open project ({@link AssetTarget}). Its search bar
 * opens the same search window as the tab, as a layer inside this window.
 */
public final class AssetsWindow extends Stage {

    private static final double CARD_WIDTH = 150;

    private final AssetsBrowser browser;
    private final TemplateSearchBar searchBar;
    private final StackPane root = new StackPane();
    private final AssetTarget target;
    private final Runnable hotListener = this::updateHotTerms;

    public AssetsWindow(AssetTarget target) {
        this.target = target;
        if (target.window() != null) initOwner(target.window());
        setTitle(I18n.t("Assets") + " - " + target.projectTitle());

        browser = new AssetsBrowser(target, CARD_WIDTH);
        searchBar = new TemplateSearchBar(I18n.t("Search assets..."), this::openSearch);
        browser.setOnPageLoaded(this::updateHotTerms);

        VBox content = new VBox(12, searchBar, browser);
        content.setPadding(new Insets(16));
        VBox.setVgrow(browser, Priority.ALWAYS);
        root.getChildren().add(content);

        // the hot searches arrive (and refresh) after the window is built; listened to only while it is open
        AssetSearchHints.getInstance().addListener(hotListener);
        AssetSearchHints.getInstance().refresh(false);
        setOnHidden(e -> AssetSearchHints.getInstance().removeListener(hotListener));
        updateHotTerms();

        Scene scene = new Scene(root, 700, 680);
        AppUi.applyAppStyles(scene);
        setScene(scene);
    }

    private void updateHotTerms() {
        List<String> terms = new ArrayList<>();
        for (TrendingSearch t : AssetSearchHints.getInstance().trending()) terms.add(t.text);
        if (terms.isEmpty()) terms = browser.titles(8);
        searchBar.setTerms(terms);
    }

    private void openSearch(String hotTerm) {
        final AssetSearchOverlay[] ref = new AssetSearchOverlay[1];
        ref[0] = new AssetSearchOverlay(hotTerm, () -> browser.titles(40), target, CARD_WIDTH, v -> root.getChildren().remove(ref[0]));
        root.getChildren().add(ref[0]);
    }
}
