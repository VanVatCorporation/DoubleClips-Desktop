package com.vanvatcorporation.doubleclips.data.editing;

import com.google.gson.annotations.Expose;
import java.io.Serializable;
import java.util.List;

public class Keyframe implements Serializable {
    @Expose private float time; // seconds, in local clip time
    @Expose private long frame; // frames, in local clip frame
    @Expose public VideoProperties value;
    @Expose public EasingType easing;
    /**
     * The properties this keyframe belongs to, by iOS's raw names ("posX", "opacity"...). {@code null} = every
     * property (what older projects and the Android/desktop builds before per-property keyframes always wrote);
     * see {@link KeyframeChannels}. {@link #value} always carries a complete look either way.
     */
    @Expose public List<String> channels;

    public Keyframe(float time, VideoProperties value, EasingType easing) {
        this.time = time;
        this.value = value;
        this.easing = easing;
    }

    /** A copy that shares nothing with the original (value and channels are copied). */
    public Keyframe(Keyframe other) {
        this.time = other.time;
        this.frame = other.frame;
        this.value = other.value == null ? null : new VideoProperties(other.value);
        this.easing = other.easing;
        this.channels = KeyframeChannels.copy(other.channels);
    }

    /** Does this keyframe hold the property? */
    public boolean holds(VideoProperties.ValueType type) {
        return KeyframeChannels.holds(channels, type.channelName());
    }

    /** Makes the keyframe start (or stop) holding the property. */
    public void setHolds(VideoProperties.ValueType type, boolean on) {
        channels = KeyframeChannels.with(channels, type.channelName(), on);
    }

    /** True when the keyframe holds nothing any more and should be deleted. */
    public boolean holdsNothing() {
        return KeyframeChannels.holdsNothing(channels);
    }

    /** "All" for an every-property keyframe, otherwise the held properties ("Pos X, Scale Y"); names this build doesn't know are skipped. */
    public String channelSummary() {
        if (channels == null) return "All";
        StringBuilder sb = new StringBuilder();
        for (String c : channels) {
            VideoProperties.ValueType t = VideoProperties.ValueType.fromChannelName(c);
            String label = t == null ? null : (t.channelLabel().equals(t.name()) ? humanize(c) : t.channelLabel());
            if (label == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(label);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static String humanize(String raw) {
        return raw.isEmpty() ? raw : Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    public float getLocalTime() {
        return time;
    }
    public void setLocalTime(float time) {
        this.time = time;
    }

    public long getLocalFrame() {
        return frame;
    }
    public void setLocalFrame(long frame) {
        this.frame = frame;
    }

    public float getGlobalTime(Clip clip) {
        return time + clip.startTime;
    }
}
