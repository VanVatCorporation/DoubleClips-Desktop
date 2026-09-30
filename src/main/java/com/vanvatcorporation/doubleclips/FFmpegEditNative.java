package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.vanvatcorporation.doubleclips.FFmpegEdit.queue;

public class FFmpegEditNative {

    // These function are specifically for Native (This is Desktop) version. This is part of work for uniting the FFmpegEdit across all platform.


    /**
     * Cross-platform replacement for the old hardcoded
     * {@code hardwareAcceleratedName = "videotoolbox"} field. VideoToolbox is
     * macOS-only (it happened to work there because that's the only platform
     * this app ran hardware-accelerated exports on so far); on Windows/Linux
     * it produced an invalid "-hwaccel videotoolbox"/"-c:v h264_videotoolbox"
     * ffmpeg command whenever a user enabled hardware acceleration there.
     * <p>
     * Detected once, lazily, by asking the bundled ffmpeg binary which
     * hardware encoders it actually has (`ffmpeg -encoders`) - this ffmpeg
     * build's actual vendor support, not just "is this OS X". Result is
     * {@code [hwaccelDecodeFlag, encoderSuffix]}, both null if nothing usable
     * was found (callers must then fall back to libx264/software encoding).
     */
    private static volatile String[] detectedHwAccel;

    private static synchronized void detectHardwareAccelIfNeeded() {
        if (detectedHwAccel != null) return;

        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("mac")) {
            // VideoToolbox is macOS's only hwaccel API and covers both Intel
            // and Apple silicon Macs identically - no per-arch branching
            // needed for this one.
            detectedHwAccel = new String[]{"videotoolbox", "videotoolbox"};
            return;
        }

        // Windows/Linux: no single universal API. Probe which hardware
        // encoder this ffmpeg binary actually has, in vendor-portability
        // order (NVENC/QSV are single-vendor but very common; VAAPI covers
        // most Linux drivers; AMF is AMD-on-Windows only).
        String[][] candidates = os.contains("win")
                ? new String[][]{ {"cuda", "h264_nvenc"}, {"qsv", "h264_qsv"}, {"d3d11va", "h264_amf"} }
                : new String[][]{ {"vaapi", "h264_vaapi"}, {"cuda", "h264_nvenc"} };

        try {
            List<String> availableEncoders = listFfmpegEncoders();
            for (String[] candidate : candidates) {
                if (availableEncoders.contains(candidate[1])) {
                    detectedHwAccel = new String[]{candidate[0], candidate[1].substring("h264_".length())};
                    return;
                }
            }
        } catch (Exception e) {
            System.err.println("Hardware encoder detection failed: " + e.getMessage());
        }

        detectedHwAccel = new String[]{null, null}; // none found - callers fall back to libx264
    }

    private static List<String> listFfmpegEncoders() throws IOException {
        List<String> encoders = new ArrayList<>();
        Process process = new ProcessBuilder(getFfmpegPath(), "-hide_banner", "-encoders").start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // ffmpeg -encoders lines look like " V..... h264_nvenc  NVIDIA NVENC H.264 encoder"
                Matcher m = Pattern.compile("^\\s*[VAS.]{6}\\s+(\\S+)").matcher(line);
                if (m.find()) encoders.add(m.group(1));
            }
        }
        try { process.waitFor(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        return encoders;
    }

    /** ffmpeg {@code -hwaccel} value for decode, or null if no working hardware accel was found. */
    public static String getHwAccelDecodeFlag() {
        detectHardwareAccelIfNeeded();
        return detectedHwAccel[0];
    }

    /**
     * Encoder-name suffix for {@code -c:v h264_<name>}. Replaces the old
     * hardcoded "videotoolbox" constant; resolved per-OS/per-GPU (see
     * detectHardwareAccelIfNeeded()). Null if hardware acceleration isn't
     * available - callers must fall back to libx264 in that case.
     */
    public static String getHardwareAcceleratedName() {
        detectHardwareAccelIfNeeded();
        return detectedHwAccel[1];
    }


    public static String getFfmpegPath() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        boolean isArm = arch.contains("aarch64") || arch.contains("arm");

        String binaryDir;
        if (os.contains("win")) {
            binaryDir = isArm ? "windows-arm" : "windows";
        } else if (os.contains("mac")) {
            // Was a single "macos" folder with no arch split, unlike Windows
            // above - fine while this only ran under Rosetta on Apple silicon,
            // but macOS 28 stops launching Intel-only binaries entirely (see
            // Apple's Rosetta phase-out, effective macOS 28), so an x86_64-only
            // ffmpeg silently breaks exports there rather than merely losing
            // performance. Prefer an arch-specific folder if bundled, but keep
            // falling back to the legacy "macos" folder for old bundles/builds
            // that only ship one (e.g. a lipo'd universal binary).
            binaryDir = isArm ? "macos-arm64" : "macos-x86_64";
        } else {
            return "ffmpeg"; // Default to system PATH
        }

        String exeName = os.contains("win") ? "ffmpeg.exe" : "ffmpeg";
        // On mac, an existing bundle may still only ship the old single
        // "macos" folder (e.g. a lipo'd universal binary covering both
        // arches, or simply not rebuilt yet) - try the arch-specific folder
        // first, then fall back to it, rather than failing to find ffmpeg at
        // all on those bundles.
        String[] binaryDirCandidates = os.contains("mac")
                ? new String[]{binaryDir, "macos"}
                : new String[]{binaryDir};

        try {
            // Find where the classes are loaded from
            java.net.URL url = FFmpegEdit.class.getProtectionDomain().getCodeSource().getLocation();
            File jarFile = new File(url.toURI());

            // Get the directory containing the JAR (this should be the 'app' directory inside the bundle)
            File appDir = jarFile.getParentFile();

            // The bundle structure we created is app/bin/[os]/ffmpeg
            for (String dirCandidate : binaryDirCandidates) {
                File expectedBundlePath = new File(appDir, "bin" + File.separator + dirCandidate + File.separator + exeName);
                if (expectedBundlePath.exists()) {
                    return expectedBundlePath.getAbsolutePath();
                }
            }

        } catch (Exception e) {
            System.err.println("Failed to locate bundled execution path: " + e.getMessage());
        }

        // Handle development paths (when running via IDE or gradle run)
        for (String dirCandidate : binaryDirCandidates) {
            String devPath = IOHelper.CombinePath(System.getProperty("user.dir"), "desktop", "bin", dirCandidate, exeName);
            if (new File(devPath).exists()) return devPath;

            String bundleFallbackPath = IOHelper.CombinePath(System.getProperty("user.dir"), "bin", dirCandidate, exeName);
            if (new File(bundleFallbackPath).exists()) return bundleFallbackPath;
        }

        return "ffmpeg"; // Fallback to system PATH
    }




    public static void runAnyCommand(String cmd, String taskName, String successMessage, String failMessage, boolean includeFullReport,
                                     Runnable onSuccessRunnable, Runnable onFailRunnable,
                                     java.util.function.Consumer<String> onLogRunnable, java.util.function.Consumer<FfmpegStatistics> onStatisticsRunnable) {
        // Since Desktop version doesn't support new line for command, we discard the \n
        cmd.replace('\n', ' ');


        LoggingManager.LogToPersistentDataPath(cmd);


        queue.enqueue(new FFmpegEdit.FfmpegRenderQueue.FfmpegRenderQueueInfo(
                taskName,
                () -> {
                    runOnDaemonThread(() -> {
                        try {
                            String ffmpegPath = getFfmpegPath();
                            List<String> fullCmd = new ArrayList<>();
                            fullCmd.add(ffmpegPath);

                            // Split command by space but respect quotes
                            Matcher m = Pattern.compile("([^ \"]\\S*|\".+?\")\\s*").matcher(cmd);
                            while (m.find()) {
                                String part = m.group(1);
                                if (part.startsWith("\"") && part.endsWith("\"")) {
                                    part = part.substring(1, part.length() - 1);
                                }
                                fullCmd.add(part);
                            }

                            ProcessBuilder pb = new ProcessBuilder(fullCmd);
                            pb.redirectErrorStream(true);
                            Process process = pb.start();

                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    String finalLine = line;
                                    onLogRunnable.accept(finalLine);

                                    // Parse progress (frame=, fps=, time= appear on the same FFmpeg progress line)
                                    if (finalLine.contains("time=")) {
                                        Matcher timeMatcher = Pattern.compile("time=([0-9:.]+)").matcher(finalLine);
                                        if (timeMatcher.find()) {
                                            Matcher frameMatcher = Pattern.compile("frame=\\s*(\\d+)").matcher(finalLine);
                                            int frame = frameMatcher.find() ? Integer.parseInt(frameMatcher.group(1)) : 0;
                                            Matcher fpsMatcher = Pattern.compile("fps=\\s*([0-9.]+)").matcher(finalLine);
                                            float fps = fpsMatcher.find() ? Float.parseFloat(fpsMatcher.group(1)) : 0f;
                                            onStatisticsRunnable.accept(new FFmpegEditNative.FfmpegStatistics(timeMatcher.group(1), frame, fps));
                                        }
                                    }
                                }
                            }

                            int exitCode = process.waitFor();
                            if (exitCode == 0) {
                                LoggingManager.LogToPersistentDataPath(successMessage);
                                onSuccessRunnable.run();
                            } else {
                                LoggingManager.LogToPersistentDataPath(failMessage + " Exit code: " + exitCode);
                                onFailRunnable.run();
                            }
                        } catch (Exception e) {
                            LoggingManager.LogToPersistentDataPath("Error executing FFmpeg: " + e.getMessage());
                            onFailRunnable.run();
                        }



                        // TODO: Add a slightly user friendly delay (Execute next ffmpeg rendering part in 3, 2, 1), dynamically into logText
                        runOnDaemonThread(() -> {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ignored) {

                            }
                            queue.taskCompleted(); // Move to next task
                        });
                    });
                }
        ));
    }


    /**
     * Runs a task on its own DAEMON thread. This replaces {@code Executors.newSingleThreadExecutor().execute(..)},
     * which created a new, never-shut-down, non-daemon executor per ffmpeg command: each left a
     * thread alive that kept the JVM from exiting after the last window closed (the app then
     * sat in the Dock as "Application Not Responding", depending on GC timing).
     */
    private static void runOnDaemonThread(Runnable task) {
        Thread thread = new Thread(task, "FFmpegEditNative-task");
        thread.setDaemon(true);
        thread.start();
    }

    public static class FfmpegStatistics {
        private final String time;
        private final int frame;
        private final float fps;

        public FfmpegStatistics(String time) { this(time, 0, 0f); }
        public FfmpegStatistics(String time, int frame, float fps) {
            this.time = time;
            this.frame = frame;
            this.fps = fps;
        }

        public String getTime() { return time; }
        /** Equivalent to Android Statistics.getVideoFrameNumber() */
        public int getVideoFrameNumber() { return frame; }
        /** Equivalent to Android Statistics.getVideoFps() */
        public float getVideoFps() { return fps; }

        public long getTimeInMs() {
            try {
                String[] parts = time.split(":");
                long hours = Long.parseLong(parts[0]);
                long minutes = Long.parseLong(parts[1]);
                String[] secParts = parts[2].split("\\.");
                long seconds = Long.parseLong(secParts[0]);
                long ms = secParts.length > 1 ? Long.parseLong(secParts[1]) : 0;
                return (hours * 3600 + minutes * 60 + seconds) * 1000 + ms;
            } catch (Exception e) { return 0; }
        }
    }


}
