package com.vanvatcorporation.doubleclips.ui.renderer;

import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.VideoProperties;
import com.vanvatcorporation.doubleclips.data.editing.VideoSettings;
import java.io.File;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;

public class ClipRenderer {
    public final Clip clip;
    private final ProjectData data;
    private final VideoSettings settings;
    private final Pane renderPane;

    private MediaPlayer mediaPlayer;
    private MediaView mediaView;
    private ImageView imageView;
    private Node viewNode;

    public boolean isPlaying;

    private float posX = 0, posY = 0;
    private float scaleX = 1, scaleY = 1;
    private float rot = 0;
    private float opacity = 1;

    public ClipRenderer(Clip clip, ProjectData data, VideoSettings settings, Pane renderPane) {
        this.clip = clip;
        this.data = data;
        this.settings = settings;
        this.renderPane = renderPane;

        try {
            File clipFile = new File(clip.getAbsolutePath(data));
            if (!clipFile.exists()) {
                System.err.println("Clip file does not exist: " + clipFile.getAbsolutePath());
                return;
            }

            switch (clip.type) {
                case VIDEO:
                case AUDIO:
                    Media media = new Media(clipFile.toURI().toString());
                    mediaPlayer = new MediaPlayer(media);
                    mediaPlayer.setMute(clip.isMute());

                    if (clip.type == com.vanvatcorporation.doubleclips.data.editing.ClipType.VIDEO) {
                        mediaView = new MediaView(mediaPlayer);
                        mediaView.setFitWidth(clip.width > 0 ? clip.width : settings.videoWidth);
                        mediaView.setFitHeight(clip.height > 0 ? clip.height : settings.videoHeight);
                        viewNode = mediaView;
                    }
                    break;
                case IMAGE:
                    Image img = new Image(clipFile.toURI().toString());
                    imageView = new ImageView(img);
                    imageView.setFitWidth(clip.width > 0 ? clip.width : img.getWidth());
                    imageView.setFitHeight(clip.height > 0 ? clip.height : img.getHeight());
                    viewNode = imageView;
                    break;
                default:
                    // Unsupported in preview right now
                    break;
            }

            if (viewNode != null) {
                renderPane.getChildren().add(viewNode);
                
                // Set baseline properties
                posX = clip.videoProperties.getValue(VideoProperties.ValueType.PosX);
                posY = clip.videoProperties.getValue(VideoProperties.ValueType.PosY);
                scaleX = clip.videoProperties.getValue(VideoProperties.ValueType.ScaleX);
                scaleY = clip.videoProperties.getValue(VideoProperties.ValueType.ScaleY);
                rot = clip.videoProperties.getValue(VideoProperties.ValueType.Rot);
                opacity = clip.videoProperties.getValue(VideoProperties.ValueType.Opacity);

                applyTransformation();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean isVisible(float playheadTime) {
        return playheadTime >= clip.startTime && playheadTime < clip.startTime + clip.duration;
    }

    public void renderFrame(float playheadTime, boolean isSeekingOnly) {
        if (!isVisible(playheadTime)) {
            if (viewNode != null) {
                viewNode.setVisible(false);
            }
            if (mediaPlayer != null) {
                mediaPlayer.pause();
                isPlaying = false;
            }
            return;
        }

        if (viewNode != null) {
            viewNode.setVisible(true);
        }

        startPlayingAt(playheadTime, isSeekingOnly);
    }

    public void startPlayingAt(float playheadTime, boolean isSeekingOnly) {
        try {
            if (viewNode != null) {
                float x = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.PosX);
                float y = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.PosY);
                float rotation = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.Rot);
                float sx = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.ScaleX);
                float sy = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.ScaleY);
                float op = clip.keyframes.getValueAtTime(clip, playheadTime, VideoProperties.ValueType.Opacity);

                posX = x == -1 ? posX : x;
                posY = y == -1 ? posY : y;
                rot = rotation == -1 ? rot : rotation;
                scaleX = sx == -1 ? scaleX : sx;
                scaleY = sy == -1 ? scaleY : sy;
                opacity = op < 0 ? opacity : op;

                applyTransformation();
            }

            if (mediaPlayer != null) {
                float clipTime = playheadTime - clip.startTime + clip.startClipTrim;
                if (clipTime >= 0 && clipTime <= clip.originalDuration) {
                    if (isSeekingOnly) {
                        mediaPlayer.pause();
                        isPlaying = false;
                        mediaPlayer.seek(Duration.seconds(clipTime));
                    } else {
                        if (!isPlaying) {
                            // Only seek if we are desynced by more than 100ms
                            double currentMediaTime = mediaPlayer.getCurrentTime().toSeconds();
                            if (Math.abs(currentMediaTime - clipTime) > 0.1) {
                                mediaPlayer.seek(Duration.seconds(clipTime));
                            }
                            mediaPlayer.play();
                            isPlaying = true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void applyTransformation() {
        if (viewNode != null) {
            viewNode.setTranslateX(posX);
            viewNode.setTranslateY(posY);
            viewNode.setScaleX(scaleX);
            viewNode.setScaleY(scaleY);
            viewNode.setRotate(rot);
            viewNode.setOpacity(opacity);
        }
    }

    public void release() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.dispose();
        }
        if (viewNode != null) {
            renderPane.getChildren().remove(viewNode);
        }
    }
}
