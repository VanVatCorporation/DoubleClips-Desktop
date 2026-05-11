package com.vanvatcorporation.doubleclips.ui.renderer;

import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

public class TimelineRenderer {

    private final Pane renderPane;
    private List<List<ClipRenderer>> trackLayers = new ArrayList<>();
    
    private final ProjectData data;
    private final VideoSettings settings;

    public TimelineRenderer(ProjectData data, VideoSettings settings) {
        this.data = data;
        this.settings = settings;
        this.renderPane = new Pane();
        
        // Define fixed size for rendering pane
        this.renderPane.setPrefSize(settings.videoWidth, settings.videoHeight);
        this.renderPane.setMinSize(Pane.USE_PREF_SIZE, Pane.USE_PREF_SIZE);
        this.renderPane.setMaxSize(Pane.USE_PREF_SIZE, Pane.USE_PREF_SIZE);
        
        // Create a black background box for blank video areas
        Rectangle blackBox = new Rectangle(settings.videoWidth, settings.videoHeight, Color.BLACK);
        this.renderPane.getChildren().add(blackBox);
        
        // Clip content to the video bounds
        Rectangle clipRect = new Rectangle(settings.videoWidth, settings.videoHeight);
        this.renderPane.setClip(clipRect);
    }

    public Pane getRenderPane() {
        return renderPane;
    }

    public void buildTimeline(Timeline timeline) {
        release();

        // Clear everything except the black box
        renderPane.getChildren().retainAll(renderPane.getChildren().get(0));
        trackLayers.clear();

        for (Track track : timeline.tracks) {
            List<ClipRenderer> renderers = new ArrayList<>();
            for (Clip clip : track.clips) {
                switch (clip.type) {
                    case VIDEO:
                    case AUDIO:
                    case IMAGE:
                    case TEXT:
                    case EFFECT:
                        ClipRenderer clipRenderer = new ClipRenderer(clip, data, settings, renderPane);
                        renderers.add(clipRenderer);
                        break;
                    default:
                        break;
                }
            }
            trackLayers.add(renderers);
        }
    }

    public void updateTime(float time, boolean isSeekingOnly) {
        for (List<ClipRenderer> trackRenderer : trackLayers) {
            for (ClipRenderer clipRenderer : trackRenderer) {
                if (clipRenderer != null) {
                    clipRenderer.renderFrame(time, isSeekingOnly);
                }
            }
        }
    }

    public void release() {
        for (List<ClipRenderer> track : trackLayers) {
            for (ClipRenderer cr : track) {
                cr.release();
            }
        }
        trackLayers.clear();
    }
}
