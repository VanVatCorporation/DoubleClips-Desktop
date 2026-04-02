package com.vanvatcorporation.doubleclips;

import atlantafx.base.theme.CupertinoDark;
import com.vanvatcorporation.doubleclips.ui.panes.*;
import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignH;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;

public class DoubleClipsDesktop extends Application {

    private static DoubleClipsDesktop instance;
    private StackPane rootLayer;
    private StackPane contentArea;

    public static DoubleClipsDesktop getInstance() {
        return instance;
    }

    @Override
    public void start(Stage stage) {
        instance = this;
        Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());

        // The root layer allows us to show overlays (like Template Preview) on top of everything
        rootLayer = new StackPane(createMainLayout());
        Scene scene = new Scene(rootLayer, 1200, 800);
        
        // Add custom styles
        String styleSheet = getClass().getResource("/style.css").toExternalForm();
        scene.getStylesheets().add(styleSheet);

        stage.setScene(scene);
        stage.setTitle("DoubleClips Desktop");
        stage.show();
    }

    private Region createMainLayout() {
        BorderPane root = new BorderPane();

        // 1. Sidebar Construction
        VBox sidebar = new VBox(15);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(260);

        ToggleGroup navGroup = new ToggleGroup();

        // Main Navigation Buttons (Icon + Text as in the sketch)
        ToggleButton homeBtn = createNavButton("Home", new FontIcon(MaterialDesignH.HOME), navGroup);
        ToggleButton templateBtn = createNavButton("Template", new FontIcon(MaterialDesignT.TABLE), navGroup);
        ToggleButton searchBtn = createNavButton("Search", new FontIcon(MaterialDesignM.MAGNIFY), navGroup);
        ToggleButton storageBtn = createNavButton("Storage", new FontIcon(MaterialDesignD.DATABASE), navGroup);
        ToggleButton profileBtn = createNavButton("Profile", new FontIcon(MaterialDesignA.ACCOUNT_CIRCLE), navGroup);

        homeBtn.setSelected(true);

        sidebar.getChildren().addAll(homeBtn, templateBtn, searchBtn, storageBtn, profileBtn);

        // 2. Content Area Construction
        contentArea = new StackPane();
        contentArea.getChildren().setAll(new HomePane());

        root.setLeft(sidebar);
        root.setCenter(contentArea);

        // 3. Navigation Switch Logic
        homeBtn.setOnAction(e -> switchPane(new HomePane()));
        templateBtn.setOnAction(e -> switchPane(new TemplatePane()));
        searchBtn.setOnAction(e -> switchPane(new SearchPane()));
        storageBtn.setOnAction(e -> switchPane(new StoragePane()));
        profileBtn.setOnAction(e -> switchPane(new ProfilePane()));

        return root;
    }

    private void switchPane(Node pane) {
        contentArea.getChildren().setAll(pane);
    }

    private ToggleButton createNavButton(String text, FontIcon icon, ToggleGroup group) {
        ToggleButton btn = new ToggleButton(text);
        btn.setToggleGroup(group);
        btn.setGraphic(icon);
        btn.setAlignment(Pos.CENTER_LEFT);
        btn.getStyleClass().add("sidebar-button");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setGraphicTextGap(15);
        return btn;
    }

    public void showOverlay(Node overlay) {
        rootLayer.getChildren().add(overlay);
    }

    public void hideOverlay(Node overlay) {
        rootLayer.getChildren().remove(overlay);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
