package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.vanvatcorporation.doubleclips.FFmpegEdit.queue;

public class FFmpegEditNative {

    // These function are specifically for Native (This is Desktop) version. This is part of work for uniting the FFmpegEdit across all platform.


    public static String hardwareAcceleratedName = "videotoolbox";


    public static String getFfmpegPath() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();

        String binaryDir;
        if (os.contains("win")) {
            binaryDir = arch.contains("aarch64") || arch.contains("arm") ? "windows-arm" : "windows";
        } else if (os.contains("mac")) {
            binaryDir = "macos";
        } else {
            return "ffmpeg"; // Default to system PATH
        }

        try {
            // Find where the classes are loaded from
            java.net.URL url = FFmpegEdit.class.getProtectionDomain().getCodeSource().getLocation();
            File jarFile = new File(url.toURI());

            // Get the directory containing the JAR (this should be the 'app' directory inside the bundle)
            File appDir = jarFile.getParentFile();

            // The bundle structure we created is app/bin/[os]/ffmpeg
            File expectedBundlePath = new File(appDir, "bin" + File.separator + binaryDir + File.separator + (os.contains("win") ? "ffmpeg.exe" : "ffmpeg"));

            if (expectedBundlePath.exists()) {
                return expectedBundlePath.getAbsolutePath();
            }

        } catch (Exception e) {
            System.err.println("Failed to locate bundled execution path: " + e.getMessage());
        }

        // Handle development paths (when running via IDE or gradle run)
        String devPath = IOHelper.CombinePath(System.getProperty("user.dir"), "desktop", "bin", binaryDir, os.contains("win") ? "ffmpeg.exe" : "ffmpeg");
        if (new File(devPath).exists()) return devPath;

        String bundleFallbackPath = IOHelper.CombinePath(System.getProperty("user.dir"), "bin", binaryDir, os.contains("win") ? "ffmpeg.exe" : "ffmpeg");
        if (new File(bundleFallbackPath).exists()) return bundleFallbackPath;

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
                    Executors.newSingleThreadExecutor().execute(() -> {
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

                                    // Parse progress
                                    if (finalLine.contains("time=")) {
                                        Matcher timeMatcher = Pattern.compile("time=([0-9:.]+)").matcher(finalLine);
                                        if (timeMatcher.find()) {
                                            onStatisticsRunnable.accept(new FFmpegEditNative.FfmpegStatistics(timeMatcher.group(1)));
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
                        Executors.newSingleThreadExecutor().execute(() -> {
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


    public static class FfmpegStatistics {
        private String time;
        public FfmpegStatistics(String time) { this.time = time; }
        public String getTime() { return time; }
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
