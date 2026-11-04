package com.vanvatcorporation.doubleclips.ui.panes;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.auth.TrendingSearch;
import com.vanvatcorporation.doubleclips.drive.DoubleClipsDrive;
import com.vanvatcorporation.doubleclips.i18n.I18n;
import com.vanvatcorporation.doubleclips.ui.AssetSearchHints;
import com.vanvatcorporation.doubleclips.ui.AssetSearchOverlay;
import com.vanvatcorporation.doubleclips.ui.AssetsBrowser;
import com.vanvatcorporation.doubleclips.ui.MyAssetsWindow;
import com.vanvatcorporation.doubleclips.ui.PostAssetWindow;
import com.vanvatcorporation.doubleclips.ui.components.TemplateSearchBar;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignU;

import java.util.ArrayList;
import java.util.List;

/**
 * The Assets tab (it replaces the old Search tab; iOS did the same): what the community posted, live from the server. Videos,
 * images, audio, fonts, effects and animation packs, on the same Van Vat Drive as OpenEditz: an asset is a file in the poster's Drive
 * shared as "anyone with the link", and it leaves this list by itself when the poster restricts or deletes the file.
 * Browsing needs no sign-in; getting, posting and My assets do.
 */
public class AssetsPane extends VBox {

    private final AssetsBrowser browser = new AssetsBrowser(null, 220);
    private final TemplateSearchBar searchBar = new TemplateSearchBar(I18n.t("Search assets..."), this::openSearch);
    private final Runnable hotListener = this::updateHotTerms;

    public AssetsPane() {
        setSpacing(18);
        setPadding(new Insets(24));
        getStyleClass().add("content-pane");

        Label title = new Label(I18n.t("Assets"));
        title.setStyle("-fx-font-size: 32px; -fx-font-weight: bold;");

        Button mine = new Button(I18n.t("My assets"), new FontIcon(MaterialDesignF.FOLDER_OUTLINE));
        mine.getStyleClass().add("app-button");
        mine.setOnAction(e -> {
            if (signedIn()) MyAssetsWindow.show(window(), browser::start);   // link changes there may add or remove assets in the list
        });
        Button post = new Button(I18n.t("Post an asset"), new FontIcon(MaterialDesignU.UPLOAD_OUTLINE));
        post.getStyleClass().add("app-button-primary");
        post.setOnAction(e -> {
            if (!signedIn()) return;
            // posted: show it in My assets (its link state, edit, remove) and refresh the list behind
            PostAssetWindow.show(window(), () -> {
                browser.start();
                MyAssetsWindow.show(window(), browser::start);
            });
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, title, spacer, mine, post);
        header.setAlignment(Pos.CENTER_LEFT);

        // The hot searches take turns in the bar; a click opens the search window (suggestions, trending, results).
        browser.setOnPageLoaded(this::updateHotTerms);   // no hot searches from the server: the bar uses the first titles
        sceneProperty().addListener((o, was, scene) -> {
            if (scene != null) {
                AssetSearchHints.getInstance().addListener(hotListener);
                AssetSearchHints.getInstance().refresh(false);
                updateHotTerms();
            } else {
                AssetSearchHints.getInstance().removeListener(hotListener);
            }
        });
        updateHotTerms();

        getChildren().addAll(header, searchBar, browser);
        VBox.setVgrow(browser, Priority.ALWAYS);
    }

    /** What the bar rotates through: the server's hot searches, else the titles of the first assets in the list. */
    private void updateHotTerms() {
        List<String> terms = new ArrayList<>();
        for (TrendingSearch t : AssetSearchHints.getInstance().trending()) terms.add(t.text);
        if (terms.isEmpty()) terms = browser.titles(8);
        searchBar.setTerms(terms);
    }

    private void openSearch(String hotTerm) {
        final AssetSearchOverlay[] ref = new AssetSearchOverlay[1];
        ref[0] = new AssetSearchOverlay(hotTerm, () -> browser.titles(40), null, 220,
                v -> DoubleClipsDesktop.getInstance().hideOverlay(ref[0]));
        DoubleClipsDesktop.getInstance().showOverlay(ref[0]);
    }

    private Window window() { return getScene() == null ? null : getScene().getWindow(); }

    /** Posting and My assets belong to an account; says so when there is none. */
    private boolean signedIn() {
        if (DoubleClipsDrive.hasSession()) return true;
        Alert alert = new Alert(Alert.AlertType.INFORMATION, I18n.t("Sign in from the Profile tab to post and manage your assets."), ButtonType.OK);
        alert.initOwner(window());
        alert.setHeaderText(null);
        alert.showAndWait();
        return false;
    }
}
