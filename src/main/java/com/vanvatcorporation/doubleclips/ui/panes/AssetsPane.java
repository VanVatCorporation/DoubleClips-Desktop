package com.vanvatcorporation.doubleclips.ui.panes;

import com.vanvatcorporation.doubleclips.i18n.I18n;
import com.vanvatcorporation.doubleclips.ui.AssetsBrowser;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The Assets tab (it replaces the old Search tab; iOS did the same): what the community posted, live from the server. Videos,
 * images, audio, fonts, effects and animation packs, on the same Van Vat Drive as OpenEditz: an asset is a file in the poster's Drive
 * shared as "anyone with the link", and it leaves this list by itself when the poster restricts or deletes the file.
 * Browsing needs no sign-in; getting an asset does.
 */
public class AssetsPane extends VBox {

    public AssetsPane() {
        setSpacing(18);
        setPadding(new Insets(24));
        getStyleClass().add("content-pane");

        Label title = new Label(I18n.t("Assets"));
        title.setStyle("-fx-font-size: 32px; -fx-font-weight: bold;");

        AssetsBrowser browser = new AssetsBrowser(null, 220);
        getChildren().addAll(title, browser);
        VBox.setVgrow(browser, Priority.ALWAYS);
    }
}
