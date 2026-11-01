package com.vanvatcorporation.doubleclips;

/** Plain-Java checks of {@link ExportEstimate}: prints "ok" or fails. */
public final class ExportEstimateSelfTest {

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        // hardware encoder: 15 Mbit/s video + 128 kbit/s audio for 60 s = 15.128e6 / 8 * 60 bytes
        ExportEstimate hw = ExportEstimate.of(1920, 1080, 30, 23, 15, true, 60);
        check(hw.bytes == 113_460_000L && !hw.isRough(), "hardware: " + hw.bytes);
        check(hw.videoBitsPerSecond == 15_000_000L, "hardware bitrate");

        // software encoder, CRF 23, 1080p30: about 4.5 Mbit/s, rough
        ExportEstimate sw = ExportEstimate.of(1920, 1080, 30, 23, 15, false, 60);
        check(sw.isRough(), "software is rough");
        check(sw.videoBitsPerSecond > 4_300_000L && sw.videoBitsPerSecond < 4_600_000L, "crf 23 1080p30: " + sw.videoBitsPerSecond);
        // +6 CRF halves it, -6 doubles it
        long at29 = ExportEstimate.of(1920, 1080, 30, 29, 15, false, 60).videoBitsPerSecond;
        long at17 = ExportEstimate.of(1920, 1080, 30, 17, 15, false, 60).videoBitsPerSecond;
        check(Math.abs(at29 * 2 - sw.videoBitsPerSecond) < 1000 && Math.abs(at17 - sw.videoBitsPerSecond * 2) < 1000, "6 crf steps double/halve");
        // more pixels or frames cost proportionally more
        check(ExportEstimate.of(3840, 2160, 30, 23, 15, false, 60).videoBitsPerSecond > sw.videoBitsPerSecond * 3.9, "4K is 4x the pixels");
        // empty timeline, bad values: no negative or crashing sizes
        check(ExportEstimate.of(1920, 1080, 30, 23, 15, true, 0).bytes == 0, "no duration, no bytes");
        check(ExportEstimate.of(0, 0, 0, 99, 0, false, 10).bytes >= 0, "zeros don't crash");

        check("0 bytes".equals(ExportEstimate.formatBytes(0)) && "845 KB".equals(ExportEstimate.formatBytes(845 * 1024L)), "bytes/KB");
        check("108 MB".equals(ExportEstimate.formatBytes(113_460_000L)), "108 MB: " + ExportEstimate.formatBytes(113_460_000L));
        check("1.5 GB".equals(ExportEstimate.formatBytes(1_610_612_736L)), "1.5 GB: " + ExportEstimate.formatBytes(1_610_612_736L));
        check("9.5 MB".equals(ExportEstimate.formatBytes(10_000_000L)), "9.5 MB: " + ExportEstimate.formatBytes(10_000_000L));
        check("1:05".equals(ExportEstimate.formatDuration(65)) && "1:02:03".equals(ExportEstimate.formatDuration(3723)) && "0:00".equals(ExportEstimate.formatDuration(-3)), "durations");
        System.out.println("ok");
    }
}
