package com.vanvatcorporation.doubleclips.helper;

import com.vanvatcorporation.doubleclips.FFmpegEditNative;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MediaHelper {

    public static class MediaInfo {
        public float duration = 3.0f;
        public int width = 0;
        public int height = 0;
        public boolean hasAudio = false;
        public boolean hasVideo = false;
        public float rotation = 0.0f; // Track rotation to fix dimensions
    }

    public static MediaInfo probeMediaInfo(String filePath) {
        MediaInfo info = new MediaInfo();

        try {
            String ffmpegPath = FFmpegEditNative.getFfmpegPath();
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegPath);
            cmd.add("-hide_banner"); // Keeps output cleaner
            cmd.add("-i");
            cmd.add(filePath);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Patterns
            Pattern durationPattern = Pattern.compile("Duration: (\\d{2}):(\\d{2}):(\\d+\\.\\d+)");
            // Improved video pattern to avoid catching SAR/DAR values
            Pattern videoPattern = Pattern.compile("Video:.*\\s(\\d{2,5})x(\\d{2,5})");
            Pattern audioPattern = Pattern.compile("Audio:");
            // Captures "rotation of -90.00" or "rotation of 90"
            Pattern rotatePattern = Pattern.compile("(?:rotate|rotation of|rotate\\s*[:=])\\s*(-?\\d+\\.?\\d*)");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 1. Check Duration
                    Matcher durMatcher = durationPattern.matcher(line);
                    if (durMatcher.find()) {
                        int hours = Integer.parseInt(durMatcher.group(1));
                        int minutes = Integer.parseInt(durMatcher.group(2));
                        float seconds = Float.parseFloat(durMatcher.group(3));
                        info.duration = hours * 3600 + minutes * 60 + seconds;
                    }

                    // 2. Check Video Dimensions
                    Matcher vidMatcher = videoPattern.matcher(line);
                    if (vidMatcher.find()) {
                        info.hasVideo = true;
                        info.width = Integer.parseInt(vidMatcher.group(1));
                        info.height = Integer.parseInt(vidMatcher.group(2));
                    }

                    // 3. Check Rotation (The "Fix")
                    Matcher rotMatcher = rotatePattern.matcher(line);
                    if (rotMatcher.find()) {
                        info.rotation = Float.parseFloat(rotMatcher.group(1));
                    }

                    // 4. Check Audio
                    if (audioPattern.matcher(line).find()) {
                        info.hasAudio = true;
                    }
                }
            }

            process.waitFor();

            // Final Correction: If the video is rotated sideways, swap width and height
            // We check for 90 or 270 (and their negatives)
            float absRotation = Math.abs(info.rotation);
            if (Math.round(absRotation) == 90 || Math.round(absRotation) == 270) {
                int temp = info.width;
                info.width = info.height;
                info.height = temp;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return info;
    }
}
