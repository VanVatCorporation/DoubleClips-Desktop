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
    // Editor preview. proxy: play the low-res proxy clips instead of the originals (off = originals, like iOS).
    // gpuPreview: composite the preview with the OpenGL worker (the same engine as the OpenGL export).
    private final BooleanProperty previewUseProxy = new SimpleBooleanProperty();
    private final BooleanProperty gpuPreview = new SimpleBooleanProperty();
    // Hardware (e.g. VideoToolbox) decoding for the GPU preview. Off by default: it can corrupt frames after a seek.
    private final BooleanProperty previewHardwareDecode = new SimpleBooleanProperty();
    
    private final StringProperty deleteKeybind = new SimpleStringProperty();
    private final StringProperty selectAllKeybind = new SimpleStringProperty();
    private final StringProperty undoKeybind = new SimpleStringProperty();
    private final StringProperty redoKeybind = new SimpleStringProperty();
    private final StringProperty togglePlayKeybind = new SimpleStringProperty();
    private final StringProperty copyKeybind = new SimpleStringProperty();
    private final StringProperty cutKeybind = new SimpleStringProperty();
    private final StringProperty pasteKeybind = new SimpleStringProperty();

    private AppSettings() {
        prefs = Preferences.userNodeForPackage(AppSettings.class);
        
        // Load defaults or saved values
        themeMode.set(prefs.get("theme_mode", "dark")); // "dark", "light", "system"
        adsPopup.set(prefs.getBoolean("ads_popup", true));
        earlyAccessNotifications.set(prefs.getBoolean("early_access_notifications", true));
        previewUseProxy.set(prefs.getBoolean("preview_use_proxy", false));
        gpuPreview.set(prefs.getBoolean("gpu_preview", true));
        previewHardwareDecode.set(prefs.getBoolean("preview_hw_decode", false));
        deleteKeybind.set(prefs.get("delete_keybind", "DELETE"));
        selectAllKeybind.set(prefs.get("select_all_keybind", "Shortcut+A"));
        undoKeybind.set(prefs.get("undo_keybind", "Shortcut+Z"));
        redoKeybind.set(prefs.get("redo_keybind", "Shortcut+Shift+Z"));
        togglePlayKeybind.set(prefs.get("toggle_play_keybind", "SPACE"));
        copyKeybind.set(prefs.get("copy_keybind", "Shortcut+C"));
        cutKeybind.set(prefs.get("cut_keybind", "Shortcut+X"));
        pasteKeybind.set(prefs.get("paste_keybind", "Shortcut+V"));

        // Save on change
        themeMode.addListener((obs, oldVal, newVal) -> prefs.put("theme_mode", newVal));
        adsPopup.addListener((obs, oldVal, newVal) -> prefs.putBoolean("ads_popup", newVal));
        earlyAccessNotifications.addListener((obs, oldVal, newVal) -> prefs.putBoolean("early_access_notifications", newVal));
        previewUseProxy.addListener((obs, oldVal, newVal) -> prefs.putBoolean("preview_use_proxy", newVal));
        gpuPreview.addListener((obs, oldVal, newVal) -> prefs.putBoolean("gpu_preview", newVal));
        previewHardwareDecode.addListener((obs, oldVal, newVal) -> prefs.putBoolean("preview_hw_decode", newVal));
        deleteKeybind.addListener((obs, oldVal, newVal) -> prefs.put("delete_keybind", newVal));
        selectAllKeybind.addListener((obs, oldVal, newVal) -> prefs.put("select_all_keybind", newVal));
        undoKeybind.addListener((obs, oldVal, newVal) -> prefs.put("undo_keybind", newVal));
        redoKeybind.addListener((obs, oldVal, newVal) -> prefs.put("redo_keybind", newVal));
        togglePlayKeybind.addListener((obs, oldVal, newVal) -> prefs.put("toggle_play_keybind", newVal));
        copyKeybind.addListener((obs, oldVal, newVal) -> prefs.put("copy_keybind", newVal));
        cutKeybind.addListener((obs, oldVal, newVal) -> prefs.put("cut_keybind", newVal));
        pasteKeybind.addListener((obs, oldVal, newVal) -> prefs.put("paste_keybind", newVal));
    }

    public static AppSettings getInstance() {
        return instance;
    }

    // Not persisted: true while the Settings screen is waiting for the user to press a new shortcut, so the
    // editor's own key handling stays out of the way and the key press reaches the recorder instead.
    private volatile boolean recordingKeybind = false;
    public boolean isRecordingKeybind() { return recordingKeybind; }
    public void setRecordingKeybind(boolean value) { recordingKeybind = value; }

    public String getThemeMode() { return themeMode.get(); }
    public void setThemeMode(String value) { themeMode.set(value); }
    public StringProperty themeModeProperty() { return themeMode; }

    public boolean isAdsPopup() { return adsPopup.get(); }
    public void setAdsPopup(boolean value) { adsPopup.set(value); }
    public BooleanProperty adsPopupProperty() { return adsPopup; }

    public boolean isEarlyAccessNotifications() { return earlyAccessNotifications.get(); }
    public void setEarlyAccessNotifications(boolean value) { earlyAccessNotifications.set(value); }
    public BooleanProperty earlyAccessNotificationsProperty() { return earlyAccessNotifications; }
    
    public boolean isPreviewUseProxy() { return previewUseProxy.get(); }
    public void setPreviewUseProxy(boolean value) { previewUseProxy.set(value); }
    public BooleanProperty previewUseProxyProperty() { return previewUseProxy; }

    public boolean isPreviewHardwareDecode() { return previewHardwareDecode.get(); }
    public void setPreviewHardwareDecode(boolean value) { previewHardwareDecode.set(value); }
    public BooleanProperty previewHardwareDecodeProperty() { return previewHardwareDecode; }

    public boolean isGpuPreview() { return gpuPreview.get(); }
    public void setGpuPreview(boolean value) { gpuPreview.set(value); }
    public BooleanProperty gpuPreviewProperty() { return gpuPreview; }

    public String getDeleteKeybind() { return deleteKeybind.get(); }
    public void setDeleteKeybind(String value) { deleteKeybind.set(value); }
    public StringProperty deleteKeybindProperty() { return deleteKeybind; }
    
    public String getSelectAllKeybind() { return selectAllKeybind.get(); }
    public void setSelectAllKeybind(String value) { selectAllKeybind.set(value); }
    public StringProperty selectAllKeybindProperty() { return selectAllKeybind; }

    public String getUndoKeybind() { return undoKeybind.get(); }
    public void setUndoKeybind(String value) { undoKeybind.set(value); }
    public StringProperty undoKeybindProperty() { return undoKeybind; }

    public String getRedoKeybind() { return redoKeybind.get(); }
    public void setRedoKeybind(String value) { redoKeybind.set(value); }
    public StringProperty redoKeybindProperty() { return redoKeybind; }

    public String getTogglePlayKeybind() { return togglePlayKeybind.get(); }
    public void setTogglePlayKeybind(String value) { togglePlayKeybind.set(value); }
    public StringProperty togglePlayKeybindProperty() { return togglePlayKeybind; }

    public String getCopyKeybind() { return copyKeybind.get(); }
    public void setCopyKeybind(String value) { copyKeybind.set(value); }
    public StringProperty toggleCopyProperty() { return copyKeybind; }

    public String getCutKeybind() { return cutKeybind.get(); }
    public void setCutKeybind(String value) { cutKeybind.set(value); }
    public StringProperty toggleCutProperty() { return cutKeybind; }

    public String getPasteKeybind() { return pasteKeybind.get(); }
    public void setPasteKeybind(String value) { pasteKeybind.set(value); }
    public StringProperty togglePasteProperty() { return pasteKeybind; }
}
