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

    private AppSettings() {
        prefs = Preferences.userNodeForPackage(AppSettings.class);
        
        // Load defaults or saved values
        themeMode.set(prefs.get("theme_mode", "dark")); // "dark", "light", "system"
        adsPopup.set(prefs.getBoolean("ads_popup", true));
        earlyAccessNotifications.set(prefs.getBoolean("early_access_notifications", true));

        // Save on change
        themeMode.addListener((obs, oldVal, newVal) -> prefs.put("theme_mode", newVal));
        adsPopup.addListener((obs, oldVal, newVal) -> prefs.putBoolean("ads_popup", newVal));
        earlyAccessNotifications.addListener((obs, oldVal, newVal) -> prefs.putBoolean("early_access_notifications", newVal));
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
}
