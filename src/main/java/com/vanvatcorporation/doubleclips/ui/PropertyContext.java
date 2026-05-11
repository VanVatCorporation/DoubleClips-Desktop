package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

public interface PropertyContext {
    Clip getSelectedClip();
    Track getSelectedTrack();
    Clip getSelectedTransitionSourceClip();
    float getCurrentTime();
    float getTempTime();
    
    void refreshTimelineUI();
    void saveProject();
    void executePropertyChange(String name, Runnable redo, Runnable undo);
    void addPropertyUpdater(Runnable updater);
    Timeline getTimeline();
    void refreshTrackHeaders();
    void updateCurrentTime(float time);
    void handleAddKeyframe();
    void handleClearKeyframes();
    void handleImportKeyframes();
    void handleExportKeyframes();
    void updatePropertiesPane();
    com.vanvatcorporation.doubleclips.data.editing.VideoSettings getVideoSettings();
}
