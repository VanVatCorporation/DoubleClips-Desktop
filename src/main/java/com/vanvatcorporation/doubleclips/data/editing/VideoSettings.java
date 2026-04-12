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

    public int outputWidth;
    public int outputHeight;

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

    public static class FfmpegPreset {
        public static final String PLACEBO = "placebo";
        public static final String VERYSLOW = "veryslow";
        public static final String SLOWER = "slower";
        public static final String SLOW = "slow";
        public static final String MEDIUM = "medium";
        public static final String FAST = "fast";
        public static final String FASTER = "faster";
        public static final String VERYFAST = "veryfast";
        public static final String SUPERFAST = "superfast";
        public static final String ULTRAFAST = "ultrafast";
    }

    public static class FfmpegTune {
        public static final String FILM = "film";
        public static final String ANIMATION = "animation";
        public static final String GRAIN = "grain";
        public static final String STILLIMAGE = "stillimage";
        public static final String FASTDECODE = "fastdecode";
        public static final String ZEROLATENCY = "zerolatency";
    }

    public int getFrameRate() { return frameRate; }
    public boolean isStretchToFull() { return isStretchToFull; }
    public boolean isUseHardwareAccel() { return useHardwareAccel; }

    public int getClipCap() { return clipCap; }

    public int getCRF() { return crf; }
    public int getBitrate() { return bitrate; }
    public String getPreset() { return preset; }
    public String getTune() { return tune; }

    public int getRenderVideoWidth(boolean isTemplate) {
        return videoWidth;
    }
    public int getRenderVideoHeight(boolean isTemplate) {
        return videoHeight;
    }
}
