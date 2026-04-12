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
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

public class DoubleClipsDesktop extends Application {

    private static DoubleClipsDesktop instance;
    private static boolean isGlobalProcessing = false;
    private Stage primaryStage;
    private StackPane rootLayer;
    private StackPane contentArea;

    public static boolean isGlobalProcessing() {
        return isGlobalProcessing;
    }

    public static void setGlobalProcessing(boolean processing) {
        isGlobalProcessing = processing;
    }

    public static DoubleClipsDesktop getInstance() {
        return instance;
    }

    public Stage getPrimaryStage() {
        return primaryStage;
    }

    @Override
    public void start(Stage stage) {
        instance = this;
        this.primaryStage = stage;
        
        // Use the AtlantaFX theme - set this early
        Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());

        // Initialize with an empty root layer so we can show the window instantly
        rootLayer = new StackPane();
        Scene scene = new Scene(rootLayer, 1200, 800);
        
        // Add custom styles
        String styleSheet = getClass().getResource("/style.css").toExternalForm();
        scene.getStylesheets().add(styleSheet);

        stage.setScene(scene);
        stage.setTitle("DoubleClips Desktop");

        stage.setOnCloseRequest(event -> {
            if (isGlobalProcessing) {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Processing in progress");
                alert.setHeaderText("A task is currently running.");
                alert.setContentText("Closing the app now may cause data loss or corruption. Are you sure you want to exit?");
                
                if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                    event.consume();
                }
            }
        });
        
        // Critical: Show the stage BEFORE building the complex layout to prevent OS timeouts
        stage.show();

        // Defer heavy UI construction and asset loading to the next pulse
        javafx.application.Platform.runLater(() -> {
            // Build and show the real layout
            rootLayer.getChildren().setAll(createMainLayout());
            
            // Large assets like icons can be loaded last
            try {
                stage.getIcons().add(new Image(getClass().getResourceAsStream("/icons/app.png")));
            } catch (Exception ignored) {}
        });
    }

    public void openEditor(com.vanvatcorporation.doubleclips.data.ProjectData project) {
        primaryStage.hide();
        com.vanvatcorporation.doubleclips.ui.EditorWindow editor = new com.vanvatcorporation.doubleclips.ui.EditorWindow(project);
        editor.show();
    }

    public void closeEditor(Stage editorStage) {
        editorStage.close();
        primaryStage.show();
        // The ProjectRepository is already synchronized, HomePane uses ObservableList
    }

    private final java.util.Map<String, Node> paneCache = new java.util.HashMap<>();

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
        contentArea.getChildren().setAll(getOrCreatePane("Home"));

        root.setLeft(sidebar);
        root.setCenter(contentArea);

        // 3. Navigation Switch Logic
        startCreatingBtn.setOnAction(e -> showOverlay(new com.vanvatcorporation.doubleclips.ui.overlays.CreateProjectOverlay()));
        
        homeBtn.setOnAction(e -> switchPane("Home"));
        templateBtn.setOnAction(e -> switchPane("Template"));
        searchBtn.setOnAction(e -> switchPane("Search"));
        storageBtn.setOnAction(e -> switchPane("Storage"));
        profileBtn.setOnAction(e -> switchPane("Profile"));

        return root;
    }

    private void switchPane(String key) {
        contentArea.getChildren().setAll(getOrCreatePane(key));
    }

    private Node getOrCreatePane(String key) {
        return paneCache.computeIfAbsent(key, k -> {
            switch (k) {
                case "Home": return new HomePane();
                case "Template": return new TemplatePane();
                case "Search": return new SearchPane();
                case "Storage": return new StoragePane();
                case "Profile": return new ProfilePane();
                default: return new HomePane();
            }
        });
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
