package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.annotations.Expose;
import java.io.Serializable;

public class VideoSettings implements Serializable {
    @Expose public int videoWidth;
    @Expose public int videoHeight;
    @Expose public int frameRate;
    @Expose public int crf;
    @Expose public int bitrate;
    @Expose public int clipCap;
    @Expose public String preset;
    @Expose public String tune;
    @Expose public boolean isStretchToFull;
    @Expose public boolean useHardwareAccel;

    public VideoSettings(int videoWidth, int videoHeight, int frameRate, int crf, int clipCap, String preset, String tune) {
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.frameRate = frameRate;
        this.crf = crf;
        this.bitrate = 15;
        this.clipCap = clipCap;
        this.preset = preset;
        this.tune = tune;
        this.isStretchToFull = false;
        this.useHardwareAccel = true;
    }

    public static VideoSettings createDefault() {
        return new VideoSettings(1920, 1080, 30, 23, 10, "medium", "film");
    }

    public int getFrameRate() { return frameRate; }
    public boolean isStretchToFull() { return isStretchToFull; }

    public int getRenderVideoWidth(boolean isTemplate) {
        return videoWidth;
    }
    public int getRenderVideoHeight(boolean isTemplate) {
        return videoHeight;
    }
}
