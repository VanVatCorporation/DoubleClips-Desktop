package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.i18n.AppLanguage;
import com.vanvatcorporation.doubleclips.i18n.I18n;

import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import com.jthemedetecor.OsThemeDetector;
import com.vanvatcorporation.doubleclips.auth.AuthRepository;
import com.vanvatcorporation.doubleclips.auth.User;
import com.vanvatcorporation.doubleclips.data.AppSettings;
import com.vanvatcorporation.doubleclips.ui.panes.*;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.stage.Stage;
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



        // Detect register.
        final OsThemeDetector detector = OsThemeDetector.getDetector();
        detector.registerListener(isDark -> {
            if("system".equalsIgnoreCase(AppSettings.getInstance().getThemeMode()))
            {
                if (isDark) {
                    updateTheme("dark");
                } else {
                    updateTheme("light");
                }
            }
        });

        // The language (Settings > General > Language): set before any screen is built, and followed when the user changes it.
        I18n.setLanguage(AppLanguage.resolve(AppSettings.getInstance().getLanguage(), AppLanguage.systemLanguages()));
        AppSettings.getInstance().languageProperty().addListener((obs, oldVal, newVal) ->
                I18n.setLanguage(AppLanguage.resolve(newVal, AppLanguage.systemLanguages())));
        I18n.addChangeListener(this::rebuildMainLayout);

        // Use the AtlantaFX theme - set this early
        updateTheme(AppSettings.getInstance().getThemeMode());

        // Listen for theme changes
        AppSettings.getInstance().themeModeProperty().addListener((obs, oldVal, newVal) -> {
            updateTheme(newVal);
        });

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
                alert.setTitle(I18n.t("Processing in progress"));
                alert.setHeaderText(I18n.t("A task is currently running."));
                alert.setContentText(I18n.t("Closing the app now may cause data loss or corruption. Are you sure you want to exit?"));
                
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

            // Setup macOS preferences handler if supported
            try {
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_PREFERENCES)) {
                    java.awt.Desktop.getDesktop().setPreferencesHandler(e -> {
                        javafx.application.Platform.runLater(() -> {
                            final com.vanvatcorporation.doubleclips.ui.overlays.SettingsOverlay[] overlayRef = new com.vanvatcorporation.doubleclips.ui.overlays.SettingsOverlay[1];
                            overlayRef[0] = new com.vanvatcorporation.doubleclips.ui.overlays.SettingsOverlay(v -> {
                                hideOverlay(overlayRef[0]);
                            });
                            showOverlay(overlayRef[0]);
                        });
                    });
                }
            } catch (Exception ignored) {}
        });




        // Initial session check
        AuthRepository.getInstance().checkSession(new AuthRepository.AuthCallback<>() {
            @Override public void onSuccess(User data) {}
            @Override public void onError(String message) {}
        });
    }

    /**
     * Called by JavaFX on the FX thread once the last window has closed (or on Cmd+Q). Without
     * this the JVM only exits when every non-daemon thread has finished, and on macOS a process
     * that outlives its event loop sits in the Dock as "Application Not Responding" (see
     * {@link AppShutdown}). Kills ffmpeg / the OpenGL worker and exits explicitly.
     */
    @Override
    public void stop() {
        AppShutdown.shutdownAndExit(0);
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

    /**
     * The language changed: the main window is built again in the new one (its panes too). Overlays that are open (the
     * settings card, a dialog) stay on top. Windows opened from here (the editor, export...) read the language when they are
     * created, so an open one keeps the language it was opened in.
     */
    private void rebuildMainLayout() {
        if (rootLayer == null || rootLayer.getChildren().isEmpty()) return; // the window isn't built yet
        java.util.List<Node> overlays = new java.util.ArrayList<>(rootLayer.getChildren());
        overlays.remove(0); // the first child is the main layout
        paneCache.clear();
        java.util.List<Node> all = new java.util.ArrayList<>();
        all.add(createMainLayout());
        all.addAll(overlays);
        rootLayer.getChildren().setAll(all);
    }

    private Region createMainLayout() {
        BorderPane root = new BorderPane();
        
        javafx.scene.control.MenuBar menuBar = new javafx.scene.control.MenuBar();
        menuBar.setUseSystemMenuBar(true);
        javafx.scene.control.Menu appMenu = new javafx.scene.control.Menu(I18n.t("File"));
        javafx.scene.control.MenuItem settingsItem = new javafx.scene.control.MenuItem(I18n.t("Preferences..."));
        settingsItem.setOnAction(ev -> {
            final com.vanvatcorporation.doubleclips.ui.overlays.SettingsOverlay[] overlayRef = new com.vanvatcorporation.doubleclips.ui.overlays.SettingsOverlay[1];
            overlayRef[0] = new com.vanvatcorporation.doubleclips.ui.overlays.SettingsOverlay(v -> {
                hideOverlay(overlayRef[0]);
            });
            showOverlay(overlayRef[0]);
        });
        appMenu.getItems().add(settingsItem);
        menuBar.getMenus().add(appMenu);
        root.setTop(menuBar);

        // 1. Sidebar Construction
        VBox sidebar = new VBox(8);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(280);

        // "Start Creating" button lives in the sidebar (top), as in the sketch
        Button startCreatingBtn = new Button(I18n.t("Start Creating\nTap to create a new project"));
        startCreatingBtn.getStyleClass().add("start-creating-button");
        startCreatingBtn.setMaxWidth(Double.MAX_VALUE);
        startCreatingBtn.setGraphic(new FontIcon(MaterialDesignP.PLUS));
        startCreatingBtn.setGraphicTextGap(16);
        VBox.setMargin(startCreatingBtn, new Insets(0, 0, 16, 0));

        ToggleGroup navGroup = new ToggleGroup();

        // Nav buttons: text-only, bold, left-aligned (no icons) — matching the sketch
        ToggleButton homeBtn = createNavButton(I18n.t("Home"), navGroup);
        ToggleButton templateBtn = createNavButton(I18n.t("Template"), navGroup);
        ToggleButton assetsBtn = createNavButton(I18n.t("Assets"), navGroup);
        ToggleButton storageBtn = createNavButton(I18n.t("Storage"), navGroup);
        ToggleButton profileBtn = createNavButton(I18n.t("Profile"), navGroup);

        homeBtn.setSelected(true);

        sidebar.getChildren().addAll(startCreatingBtn, homeBtn, templateBtn, assetsBtn, storageBtn, profileBtn);

        // 2. Content Area Construction
        contentArea = new StackPane();
        contentArea.getChildren().setAll(getOrCreatePane("Home"));

        root.setLeft(sidebar);
        root.setCenter(contentArea);

        // 3. Navigation Switch Logic
        startCreatingBtn.setOnAction(e -> showOverlay(new com.vanvatcorporation.doubleclips.ui.overlays.CreateProjectOverlay()));
        
        homeBtn.setOnAction(e -> switchPane("Home"));
        templateBtn.setOnAction(e -> switchPane("Template"));
        assetsBtn.setOnAction(e -> switchPane("Assets"));
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
                case "Assets": return new AssetsPane();
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

    private void updateTheme(String themeMode) {
        if ("light".equalsIgnoreCase(themeMode)) {
            Application.setUserAgentStylesheet(new CupertinoLight().getUserAgentStylesheet());
        } else if ("dark".equalsIgnoreCase(themeMode)) {
            Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());
        }
        // One time change.
        else if ("system".equalsIgnoreCase(themeMode)){
            if(OsThemeDetector.getDetector().isDark())
                Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());
            else
                Application.setUserAgentStylesheet(new CupertinoLight().getUserAgentStylesheet());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
