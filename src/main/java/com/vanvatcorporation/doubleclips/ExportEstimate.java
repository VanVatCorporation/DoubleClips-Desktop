package com.vanvatcorporation.doubleclips;

import java.util.Locale;

/**
 * What the export window says about the file that is about to be made: its video bitrate and its estimated size (iOS's
 * "Estimated size": duration x (video + audio bitrate) / 8).
 *
 * <p>With a hardware encoder the video bitrate is the one in the video settings ({@code -b:v NM}), so the estimate is close.
 * With the software encoder (libx264) the settings give a quality (CRF), not a bitrate: the encoder spends what the picture
 * needs, so the size depends on the footage. That case uses a rule of thumb (see {@link #bitsPerPixel}) and is marked as a
 * rough figure ({@link #isRough()}).
 */
public final class ExportEstimate {

    /** The audio is AAC at ffmpeg's default for stereo, 128 kbit/s. */
    public static final int AUDIO_BITS_PER_SECOND = 128_000;

    /** How the video bitrate was found. */
    public enum Basis { HARDWARE_BITRATE, CRF }

    public final Basis basis;
    public final long videoBitsPerSecond;
    public final double durationSeconds;
    /** The estimated size of the file in bytes (video + audio). */
    public final long bytes;

    private ExportEstimate(Basis basis, long videoBitsPerSecond, double durationSeconds) {
        this.basis = basis;
        this.videoBitsPerSecond = videoBitsPerSecond;
        this.durationSeconds = durationSeconds;
        this.bytes = (long) ((videoBitsPerSecond + AUDIO_BITS_PER_SECOND) / 8.0 * Math.max(0.0, durationSeconds));
    }

    /** True when the size is a guess from the quality setting (software encoder) rather than from a set bitrate. */
    public boolean isRough() {
        return basis == Basis.CRF;
    }

    /**
     * Bits per pixel per frame that x264 spends at a CRF, for ordinary footage: about 0.07 at CRF 23 (a 1080p30 picture then
     * needs about 4.5 Mbit/s), halving every 6 CRF steps (the encoder's own rule: +6 CRF is about half the bitrate).
     * Screen recordings need far less, noisy or fast footage more.
     */
    static double bitsPerPixel(int crf) {
        int c = Math.max(0, Math.min(51, crf));
        return 0.07 * Math.pow(2.0, (23 - c) / 6.0);
    }

    /**
     * @param hardwareEncoder true when the export will use the hardware encoder (the settings' bitrate is then the video bitrate)
     * @param bitrateMbps     the settings' bitrate in Mbit/s (used with a hardware encoder)
     * @param crf             the settings' CRF (used with the software encoder)
     */
    public static ExportEstimate of(int width, int height, int frameRate, int crf, int bitrateMbps, boolean hardwareEncoder,
                                    double durationSeconds) {
        if (hardwareEncoder) {
            return new ExportEstimate(Basis.HARDWARE_BITRATE, Math.max(1, bitrateMbps) * 1_000_000L, durationSeconds);
        }
        double bits = (double) Math.max(1, width) * Math.max(1, height) * Math.max(1, frameRate) * bitsPerPixel(crf);
        return new ExportEstimate(Basis.CRF, (long) bits, durationSeconds);
    }

    /** "12.3 MB" / "1.4 GB" (1024-based like iOS's ByteCountFormatter .binary); "845 KB", "0 bytes". */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " bytes";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes / 1024.0;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return (value >= 100 || unit == 0 ? String.format(Locale.ROOT, "%.0f", value) : String.format(Locale.ROOT, "%.1f", value))
                + " " + units[unit];
    }

    /** "1:05" / "1:02:03": minutes and seconds, with hours when there are any. */
    public static String formatDuration(double seconds) {
        long total = Math.max(0, Math.round(seconds));
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }
}
