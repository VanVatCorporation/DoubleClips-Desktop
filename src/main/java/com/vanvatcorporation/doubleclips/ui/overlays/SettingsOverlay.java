package com.vanvatcorporation.doubleclips.ui.overlays;

import atlantafx.base.controls.ToggleSwitch;
import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.data.AppSettings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.event.EventHandler;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.util.function.Consumer;

public class SettingsOverlay extends StackPane {

    private final Consumer<Void> onClose;
    private final AppSettings settings = AppSettings.getInstance();

    public SettingsOverlay(Consumer<Void> onClose) {
        this.onClose = onClose;

        // Background Dimming
        getStyleClass().add("modal-overlay");

        // Main Settings Card
        VBox settingsCard = new VBox(20);
        settingsCard.getStyleClass().add("creation-dialog");
        settingsCard.setMaxSize(500, 600);
        settingsCard.setPadding(new Insets(30));

        // Header
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        Label titleLabel = new Label("App Settings");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        Button closeBtn = new Button();
        closeBtn.setGraphic(new FontIcon(MaterialDesignC.CLOSE));
        closeBtn.getStyleClass().add("button-transparent");
        closeBtn.setOnAction(e -> onClose.accept(null));
        
        header.getChildren().addAll(titleLabel, spacer, closeBtn);

        // Scrollable content
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("edge-to-edge");
        scrollPane.setStyle("-fx-background-color: transparent;");

        VBox content = new VBox(25);
        content.setPadding(new Insets(10, 5, 10, 5));

        // --- GENERAL GROUP ---
        VBox generalGroup = createGroup("GENERAL");
        
        // Theme Mode
        HBox themeRow = createSettingRow("Appearance", "Choose your preferred theme", new FontIcon(MaterialDesignP.PALETTE));
        ComboBox<String> themeCombo = new ComboBox<>();
        themeCombo.getItems().addAll("Dark", "Light", "System");
        themeCombo.setValue(capitalize(settings.getThemeMode()));
        themeCombo.setOnAction(e -> settings.setThemeMode(themeCombo.getValue().toLowerCase()));
        themeRow.getChildren().add(themeCombo);

        // Ads Popup
        HBox adsRow = createSettingRow("Ads Popup", "Show occasional promotional popups", new FontIcon(MaterialDesignB.BULLHORN));
        ToggleSwitch adsSwitch = new ToggleSwitch();
        adsSwitch.selectedProperty().bindBidirectional(settings.adsPopupProperty());
        adsRow.getChildren().add(adsSwitch);

        // Notifications
        HBox notifyRow = createSettingRow("Early Access Notifications", "Get notified about new features and issues", new FontIcon(MaterialDesignB.BELL_RING));
        ToggleSwitch notifySwitch = new ToggleSwitch();
        notifySwitch.selectedProperty().bindBidirectional(settings.earlyAccessNotificationsProperty());
        notifyRow.getChildren().add(notifySwitch);

        generalGroup.getChildren().addAll(themeRow, new Separator(), adsRow, new Separator(), notifyRow);
        
        // --- KEYBOARD SHORTCUTS ---
        VBox shortcutsGroup = createGroup("KEYBOARD SHORTCUTS");
        HBox undoRow = createKeybindRow("Undo", "Shortcut to revert last action", settings.undoKeybindProperty());
        HBox redoRow = createKeybindRow("Redo", "Shortcut to re-apply reverted action", settings.redoKeybindProperty());
        HBox deleteRow = createKeybindRow("Delete Clip/Track", "Shortcut to delete selected items", settings.deleteKeybindProperty());
        HBox selectAllRow = createKeybindRow("Select All", "Shortcut to select all clips", settings.selectAllKeybindProperty());
        HBox togglePlayRow = createKeybindRow("Toggle Play/Pause", "Shortcut to play/pause preview", settings.togglePlayKeybindProperty());
        shortcutsGroup.getChildren().addAll(undoRow, new Separator(), redoRow, new Separator(), deleteRow, new Separator(), selectAllRow, new Separator(), togglePlayRow);

        content.getChildren().addAll(generalGroup, shortcutsGroup);
        scrollPane.setContent(content);

        settingsCard.getChildren().addAll(header, scrollPane);
        getChildren().add(settingsCard);

        // Background click to close
        this.setOnMouseClicked(e -> {
            if (e.getTarget() == this) {
                onClose.accept(null);
            }
        });
    }

    private VBox createGroup(String title) {
        VBox group = new VBox(15);
        group.getStyleClass().add("settings-group");
        group.setPadding(new Insets(20));
        
        Label header = new Label(title);
        header.getStyleClass().add("settings-header");
        header.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted; -fx-font-weight: bold;");
        
        group.getChildren().add(header);
        return group;
    }

    private HBox createSettingRow(String title, String description, FontIcon icon) {
        HBox row = new HBox(15);
        row.setAlignment(Pos.CENTER_LEFT);
        
        icon.setIconSize(24);
        icon.setStyle("-fx-icon-color: -color-accent-fg;");
        
        VBox textVBox = new VBox(2);
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label descLabel = new Label(description);
        descLabel.getStyleClass().add("text-muted");
        descLabel.setStyle("-fx-font-size: 12px;");
        textVBox.getChildren().addAll(titleLabel, descLabel);
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        row.getChildren().addAll(icon, textVBox, spacer);
        return row;
    }

    private HBox createKeybindRow(String title, String description, javafx.beans.property.StringProperty bindProperty) {
        HBox row = new HBox(15);
        row.setAlignment(Pos.CENTER_LEFT);
        
        FontIcon icon = new FontIcon(org.kordamp.ikonli.materialdesign2.MaterialDesignK.KEYBOARD);
        icon.setIconSize(24);
        icon.setStyle("-fx-icon-color: -color-accent-fg;");
        
        VBox textVBox = new VBox(2);
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label descLabel = new Label(description);
        descLabel.getStyleClass().add("text-muted");
        descLabel.setStyle("-fx-font-size: 12px;");
        textVBox.getChildren().addAll(titleLabel, descLabel);
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        Button keyBtn = new Button(formatForDisplay(bindProperty.get()));
        keyBtn.setMinWidth(100);
        keyBtn.getStyleClass().add("button-transparent");
        keyBtn.setStyle("-fx-border-color: -color-border-default; -fx-border-radius: 4;");
        
        keyBtn.setOnAction(e -> {
            keyBtn.setText("Press key...");
            keyBtn.addEventFilter(KeyEvent.ANY, new EventHandler<KeyEvent>() {
                @Override
                public void handle(KeyEvent ke) {
                    ke.consume();
                    if (ke.getEventType() != KeyEvent.KEY_PRESSED) return;

                    KeyCode code = ke.getCode();
                    if (code == KeyCode.ESCAPE) {
                        keyBtn.setText(bindProperty.get());
                        keyBtn.removeEventFilter(KeyEvent.ANY, this);
                        return;
                    }

                    String combo = "";
                    if (ke.isControlDown() && code != KeyCode.CONTROL) {
                        combo += "Control+";
                    }
                    if (ke.isAltDown() && code != KeyCode.ALT) {
                        combo += "Alt+";
                    }
                    if (ke.isShiftDown() && code != KeyCode.SHIFT) {
                        combo += "Shift+";
                    }
                    if (ke.isMetaDown() && code != KeyCode.META && code != KeyCode.COMMAND && code != KeyCode.WINDOWS) {
                        combo += "Meta+";
                    }

                    if (!code.isModifierKey()) {
                        combo += code.name();
                        bindProperty.set(combo);
                        keyBtn.setText(formatForDisplay(combo));
                        keyBtn.removeEventFilter(KeyEvent.ANY, this);
                    }
                }
            });
        });
        
        bindProperty.addListener((obs, oldVal, newVal) -> keyBtn.setText(formatForDisplay(newVal)));
        
        row.getChildren().addAll(icon, textVBox, spacer, keyBtn);
        return row;
    }

    private String formatForDisplay(String combo) {
        if (combo == null) return "";
        String display = combo;
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            display = display.replace("Meta+", "Cmd+").replace("Shortcut+", "Cmd+");
        }
        return display.replace("Control+", "Ctrl+").replace("Shortcut+", "Ctrl+");
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
