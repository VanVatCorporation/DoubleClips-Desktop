package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.i18n.I18n;
import com.vanvatcorporation.doubleclips.ui.components.AppUi;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * The editor's Assets window (iOS: the Assets sheet next to Files): the same live community list as the Assets tab, opened beside
 * the editor and left open while you work. What you get goes straight into the open project ({@link AssetTarget}).
 */
public final class AssetsWindow extends Stage {

    public AssetsWindow(AssetTarget target) {
        if (target.window() != null) initOwner(target.window());
        setTitle(I18n.t("Assets") + " - " + target.projectTitle());

        AssetsBrowser browser = new AssetsBrowser(target, 150);
        VBox root = new VBox(browser);
        root.setPadding(new Insets(16));
        VBox.setVgrow(browser, Priority.ALWAYS);

        Scene scene = new Scene(root, 700, 680);
        AppUi.applyAppStyles(scene);
        setScene(scene);
    }
}
