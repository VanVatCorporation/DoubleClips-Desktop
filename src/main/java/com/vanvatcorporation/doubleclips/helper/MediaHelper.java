package com.vanvatcorporation.doubleclips.helper;

import com.vanvatcorporation.doubleclips.FFmpegEdit;
import java.io.BufferedReader;
import java.io.File;
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
    }

    public static MediaInfo probeMediaInfo(String filePath) {
        MediaInfo info = new MediaInfo();
        
        try {
            String ffmpegPath = FFmpegEdit.getFfmpegPath();
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegPath);
            cmd.add("-i");
            cmd.add(filePath);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            Pattern durationPattern = Pattern.compile("Duration: (\\d{2}):(\\d{2}):(\\d+\\.\\d+)");
            Pattern videoPattern = Pattern.compile("Video:.*, (\\d+)x(\\d+)");
            Pattern audioPattern = Pattern.compile("Audio:");

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher durMatcher = durationPattern.matcher(line);
                    if (durMatcher.find()) {
                        int hours = Integer.parseInt(durMatcher.group(1));
                        int minutes = Integer.parseInt(durMatcher.group(2));
                        float seconds = Float.parseFloat(durMatcher.group(3));
                        info.duration = hours * 3600 + minutes * 60 + seconds;
                    }

                    Matcher vidMatcher = videoPattern.matcher(line);
                    if (vidMatcher.find()) {
                        info.hasVideo = true;
                        info.width = Integer.parseInt(vidMatcher.group(1));
                        info.height = Integer.parseInt(vidMatcher.group(2));
                    }

                    if (audioPattern.matcher(line).find()) {
                        info.hasAudio = true;
                    }
                }
            }

            process.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return info;
    }
}
