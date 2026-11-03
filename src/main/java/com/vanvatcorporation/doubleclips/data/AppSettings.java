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
    // The app's language: "system" (follow the computer) or a code from AppLanguage.SUPPORTED ("en", "vi"...).
    private final StringProperty language = new SimpleStringProperty();
    private final BooleanProperty adsPopup = new SimpleBooleanProperty();
    private final BooleanProperty earlyAccessNotifications = new SimpleBooleanProperty();
    // Editor preview. proxy: play the low-res proxy clips instead of the originals (off = originals, like iOS).
    // gpuPreview: composite the preview with the OpenGL worker (the same engine as the OpenGL export).
    private final BooleanProperty previewUseProxy = new SimpleBooleanProperty();
    private final BooleanProperty gpuPreview = new SimpleBooleanProperty();
    // Hardware (e.g. VideoToolbox) decoding for the GPU preview. Off by default: it can corrupt frames after a seek.
    private final BooleanProperty previewHardwareDecode = new SimpleBooleanProperty();
    
    // Length (seconds) given to anything that has no length of its own: text, images, effects, stickers...
    public static final float DEFAULT_CLIP_DURATION = 3.0f;
    public static final float MIN_CLIP_DURATION = 0.1f;
    public static final float MAX_CLIP_DURATION = 3600f;
    private final javafx.beans.property.FloatProperty defaultClipDuration = new javafx.beans.property.SimpleFloatProperty();

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
        language.set(prefs.get("app_language", "system"));
        adsPopup.set(prefs.getBoolean("ads_popup", true));
        earlyAccessNotifications.set(prefs.getBoolean("early_access_notifications", true));
        previewUseProxy.set(prefs.getBoolean("preview_use_proxy", false));
        gpuPreview.set(prefs.getBoolean("gpu_preview", true));
        previewHardwareDecode.set(prefs.getBoolean("preview_hw_decode", false));
        defaultClipDuration.set(clampDuration(prefs.getFloat("default_clip_duration", DEFAULT_CLIP_DURATION)));
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
        language.addListener((obs, oldVal, newVal) -> prefs.put("app_language", newVal));
        adsPopup.addListener((obs, oldVal, newVal) -> prefs.putBoolean("ads_popup", newVal));
        earlyAccessNotifications.addListener((obs, oldVal, newVal) -> prefs.putBoolean("early_access_notifications", newVal));
        previewUseProxy.addListener((obs, oldVal, newVal) -> prefs.putBoolean("preview_use_proxy", newVal));
        gpuPreview.addListener((obs, oldVal, newVal) -> prefs.putBoolean("gpu_preview", newVal));
        previewHardwareDecode.addListener((obs, oldVal, newVal) -> prefs.putBoolean("preview_hw_decode", newVal));
        defaultClipDuration.addListener((obs, oldVal, newVal) -> prefs.putFloat("default_clip_duration", newVal.floatValue()));
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

    // The editor's panel layout (docked order, sizes, floating windows) as JSON, written by DockManager.
    // Empty = the default layout. A java.util.prefs value is limited to 8192 characters; the layout is far smaller.
    public String getEditorLayout() { return prefs.get("editor_layout", ""); }
    public void setEditorLayout(String json) {
        if (json == null || json.isEmpty() || json.length() > Preferences.MAX_VALUE_LENGTH) prefs.remove("editor_layout");
        else prefs.put("editor_layout", json);
    }

    public String getThemeMode() { return themeMode.get(); }
    public void setThemeMode(String value) { themeMode.set(value); }
    public StringProperty themeModeProperty() { return themeMode; }

    /** "system" or a language code (see AppLanguage). */
    public String getLanguage() { return language.get(); }
    public void setLanguage(String value) { language.set(value); }
    public StringProperty languageProperty() { return language; }

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

    private static float clampDuration(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return DEFAULT_CLIP_DURATION;
        return Math.max(MIN_CLIP_DURATION, Math.min(MAX_CLIP_DURATION, v));
    }

    /** Seconds a clip without a length of its own (text, image, effect, sticker...) gets when it is added. */
    public float getDefaultClipDuration() { return defaultClipDuration.get(); }
    public void setDefaultClipDuration(float value) { defaultClipDuration.set(clampDuration(value)); }
    public javafx.beans.property.FloatProperty defaultClipDurationProperty() { return defaultClipDuration; }

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
