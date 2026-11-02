package com.vanvatcorporation.doubleclips;

/** Plain-Java checks of {@link ReverseMap}: {@code java ...ReverseMapSelfTest} prints "ok" or fails. */
public final class ReverseMapSelfTest {

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    public static void main(String[] args) {
        check("a.mp4.reversed.mp4".equals(ReverseMap.cacheName("a.mp4")), "cache name");
        check(ReverseMap.isCacheName("a.mp4.reversed.mp4") && !ReverseMap.isCacheName("a.mp4"), "is cache name");

        // whole clip, no trims: plays the copy from 0
        check(near(ReverseMap.copyTime(10, 10, 0, 0), 0), "whole clip start");
        check(near(ReverseMap.copyTime(10, 10, 0, 4), 4), "whole clip +4 s");
        // 2 s trimmed off the END of the original: the reversed clip starts 2 s into the copy
        check(near(ReverseMap.copyTime(10, 10, 2, 0), 2), "end trim");
        check(near(ReverseMap.copyTime(10, 10, 2, 1.5), 3.5), "end trim + elapsed");
        // the original's audio runs 0.1 s past its video: the copy's VIDEO is 0.1 s shorter than the container length used for originalLength
        check(near(ReverseMap.copyTime(9.9, 10, 2, 0), 1.9), "video shorter than container");
        // pre-roll before the clip (a transition) never goes negative
        check(near(ReverseMap.copyTime(10, 10, 0, -0.5), 0), "clamped at 0");

        check(ReverseMap.plausibleLength(10.02, 10) && ReverseMap.plausibleLength(10.9, 10), "plausible");
        check(!ReverseMap.plausibleLength(3, 10) && !ReverseMap.plausibleLength(0, 10) && !ReverseMap.plausibleLength(10, 0), "implausible");
        check(ReverseMap.plausibleLength(95, 100) && !ReverseMap.plausibleLength(80, 100), "5 percent tolerance on long files");

        double hd = ReverseMap.chunkSeconds(1920, 1080, 30);
        check(hd > 4 && hd < 5, "1080p30 chunk: " + hd);
        check(near(ReverseMap.chunkSeconds(3840, 2160, 60), 1.0), "4K60 hits the floor");
        check(near(ReverseMap.chunkSeconds(320, 240, 24), 20.0), "tiny video hits the cap");

        // every frame lands in exactly one chunk, whatever the rounding
        for (double fps : new double[] {24, 25, ReverseMap.exactFps(29.97), 30, ReverseMap.exactFps(59.94), 240}) {
            int per = 37;
            int total = 500;
            int chunks = (total + per - 1) / per;
            int[] hits = new int[total];
            for (int k = 0; k < chunks; k++) {
                double[] w = ReverseMap.chunkWindow(k, per, fps, k == chunks - 1);
                for (int i = 0; i < total; i++) {
                    double t = i / fps;
                    if (t >= w[0] && t < w[1]) hits[i]++;
                }
            }
            for (int i = 0; i < total; i++) check(hits[i] == 1, "frame " + i + " at " + fps + " fps landed " + hits[i] + " times");
        }

        check(near(ReverseMap.exactFps(29.97), 30000.0 / 1001.0) && near(ReverseMap.exactFps(25), 25), "exact fps");
        check("30000/1001".equals(ReverseMap.rationalFps(29.97)), "ntsc 29.97");
        check("24000/1001".equals(ReverseMap.rationalFps(23.976)), "ntsc 23.976");
        check("30".equals(ReverseMap.rationalFps(30)) && "25".equals(ReverseMap.rationalFps(25.0)), "integer rates");
        System.out.println("ok");
    }
}
