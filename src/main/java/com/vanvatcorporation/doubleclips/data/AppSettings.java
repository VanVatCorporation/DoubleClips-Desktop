package com.vanvatcorporation.doubleclips.data;

import java.util.prefs.Preferences;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class AppSettings {

    private static final AppSettings instance = new AppSettings();
    private final Preferences prefs;

    private final StringProperty themeMode = new SimpleStringProperty();
    private final BooleanProperty adsPopup = new SimpleBooleanProperty();
    private final BooleanProperty earlyAccessNotifications = new SimpleBooleanProperty();
    
    private final StringProperty deleteKeybind = new SimpleStringProperty();
    private final StringProperty selectAllKeybind = new SimpleStringProperty();

    private AppSettings() {
        prefs = Preferences.userNodeForPackage(AppSettings.class);
        
        // Load defaults or saved values
        themeMode.set(prefs.get("theme_mode", "dark")); // "dark", "light", "system"
        adsPopup.set(prefs.getBoolean("ads_popup", true));
        earlyAccessNotifications.set(prefs.getBoolean("early_access_notifications", true));
        deleteKeybind.set(prefs.get("delete_keybind", "DELETE"));
        selectAllKeybind.set(prefs.get("select_all_keybind", "Shortcut+A"));

        // Save on change
        themeMode.addListener((obs, oldVal, newVal) -> prefs.put("theme_mode", newVal));
        adsPopup.addListener((obs, oldVal, newVal) -> prefs.putBoolean("ads_popup", newVal));
        earlyAccessNotifications.addListener((obs, oldVal, newVal) -> prefs.putBoolean("early_access_notifications", newVal));
        deleteKeybind.addListener((obs, oldVal, newVal) -> prefs.put("delete_keybind", newVal));
        selectAllKeybind.addListener((obs, oldVal, newVal) -> prefs.put("select_all_keybind", newVal));
    }

    public static AppSettings getInstance() {
        return instance;
    }

    public String getThemeMode() { return themeMode.get(); }
    public void setThemeMode(String value) { themeMode.set(value); }
    public StringProperty themeModeProperty() { return themeMode; }

    public boolean isAdsPopup() { return adsPopup.get(); }
    public void setAdsPopup(boolean value) { adsPopup.set(value); }
    public BooleanProperty adsPopupProperty() { return adsPopup; }

    public boolean isEarlyAccessNotifications() { return earlyAccessNotifications.get(); }
    public void setEarlyAccessNotifications(boolean value) { earlyAccessNotifications.set(value); }
    public BooleanProperty earlyAccessNotificationsProperty() { return earlyAccessNotifications; }
    
    public String getDeleteKeybind() { return deleteKeybind.get(); }
    public void setDeleteKeybind(String value) { deleteKeybind.set(value); }
    public StringProperty deleteKeybindProperty() { return deleteKeybind; }
    
    public String getSelectAllKeybind() { return selectAllKeybind.get(); }
    public void setSelectAllKeybind(String value) { selectAllKeybind.set(value); }
    public StringProperty selectAllKeybindProperty() { return selectAllKeybind; }
}
