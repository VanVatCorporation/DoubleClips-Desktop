package com.vanvatcorporation.doubleclips;

import atlantafx.base.theme.CupertinoDark;
import com.vanvatcorporation.doubleclips.ui.panes.*;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

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
        VBox sidebar = new VBox(8);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(280);

        // "Start Creating" button lives in the sidebar (top), as in the sketch
        Button startCreatingBtn = new Button("Start Creating\nTap to create a new project");
        startCreatingBtn.getStyleClass().add("start-creating-button");
        startCreatingBtn.setMaxWidth(Double.MAX_VALUE);
        startCreatingBtn.setGraphic(new FontIcon(MaterialDesignP.PLUS));
        startCreatingBtn.setGraphicTextGap(16);
        VBox.setMargin(startCreatingBtn, new Insets(0, 0, 16, 0));

        ToggleGroup navGroup = new ToggleGroup();

        // Nav buttons: text-only, bold, left-aligned (no icons) — matching the sketch
        ToggleButton homeBtn = createNavButton("Home", navGroup);
        ToggleButton templateBtn = createNavButton("Template", navGroup);
        ToggleButton searchBtn = createNavButton("Search", navGroup);
        ToggleButton storageBtn = createNavButton("Storage", navGroup);
        ToggleButton profileBtn = createNavButton("Profile", navGroup);

        homeBtn.setSelected(true);

        sidebar.getChildren().addAll(startCreatingBtn, homeBtn, templateBtn, searchBtn, storageBtn, profileBtn);

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

    private ToggleButton createNavButton(String text, ToggleGroup group) {
        ToggleButton btn = new ToggleButton(text);
        btn.setToggleGroup(group);
        btn.setAlignment(Pos.CENTER_LEFT);
        btn.getStyleClass().add("sidebar-button");
        btn.setMaxWidth(Double.MAX_VALUE);
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
