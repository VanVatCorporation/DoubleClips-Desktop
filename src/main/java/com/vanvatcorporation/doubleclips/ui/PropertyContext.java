package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

public interface PropertyContext {
    Clip getSelectedClip();
    Track getSelectedTrack();
    /**
     * The clip A of the selected transition (the cube between two touching clips), or null. A transition and a clip are
     * never selected together, and a transition whose clips were deleted or moved apart is no longer selected.
     */
    Clip getSelectedTransitionSourceClip();
    /** Select the transition that ends this clip (clears the clip selection); no-op when no clip touches its end. */
    void selectTransition(Clip clipA);
    /** The clip B of the transition that ends {@code clipA} - the clip touching its end on the same track - or null. */
    Clip getTransitionNextClip(Clip clipA);
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
    com.vanvatcorporation.doubleclips.data.ProjectData getProject();
    /** Which clip mask the canvas is editing (shared by the Mask panel and the preview gizmo). */
    MaskEditState maskEditState();
}
