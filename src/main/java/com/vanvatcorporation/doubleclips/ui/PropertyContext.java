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
    /** Makes {@code fps} the project's frame rate, taking effect at once like a change in the video settings (ruler, keyframe grid, saved). */
    void changeProjectFrameRate(int fps);
    com.vanvatcorporation.doubleclips.data.ProjectData getProject();
    /** Which clip mask the canvas is editing (shared by the Mask panel and the preview gizmo). */
    MaskEditState maskEditState();
    /** Streams a mask edit that is still in flight (a slider drag) to the preview without committing it. */
    void showLiveMasks(com.vanvatcorporation.doubleclips.data.editing.Clip clip,
                       java.util.List<com.vanvatcorporation.doubleclips.data.editing.ClipMask> masks);
    /** Puts the preview back on the committed project after a live edit ended. */
    void syncPreview();
    /** A chroma-key slider is being dragged: shows {@code keys} (the clip's whole list as the drag would commit it) in the preview without committing. */
    void showLiveChromaKeys(com.vanvatcorporation.doubleclips.data.editing.Clip clip,
                            java.util.List<com.vanvatcorporation.doubleclips.data.editing.ChromaKey> keys);
    /**
     * The eyedropper: the next click on the preview hands {@code onPicked} the colour of the pixel clicked (the frame as shown,
     * every layer composited). Returns false when there is no preview picture to pick from. One shot: it ends with the click
     * or with {@link #cancelColorPick()}.
     */
    boolean pickColorFromPreview(java.util.function.Consumer<javafx.scene.paint.Color> onPicked);
    /** Ends an eyedropper that is still waiting for its click (nothing happens when none is). */
    void cancelColorPick();
    /** Picker tile pictures made by the preview worker's real renderer (transitions and effects). */
    com.vanvatcorporation.doubleclips.ui.components.GpuTilePreviews tilePreviews();
}
