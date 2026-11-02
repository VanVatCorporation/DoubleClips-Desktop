package com.vanvatcorporation.doubleclips;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The VIDEO length of a reversed copy (frames / frame rate), which {@link ReverseMap#copyTime} needs. It is the video's, not the
 * container's: when the original's audio runs a little longer than its video the container is longer than the frames. Counting the
 * frames reads every packet of the file once, so the answer is remembered by file + modified time + size. Plain Java, thread-safe;
 * shared by the live preview and the export.
 */
public final class ReverseCopies {

    private ReverseCopies() {}

    private static final ConcurrentMap<String, Double> LENGTHS = new ConcurrentHashMap<>();

    /** Seconds of video in the copy, or -1 when it can't be measured (unreadable, or no frames). */
    public static double videoLength(String ffmpeg, String path) {
        File file = new File(path);
        if (!file.isFile()) return -1;
        String key = file.getPath() + "|" + file.lastModified() + "|" + file.length();
        Double known = LENGTHS.get(key);
        if (known != null) return known;
        double length = -1;
        long frames = ReverseProcessor.countVideoFrames(ffmpeg, path);
        if (frames > 0) {
            try {
                ReverseProcessor.Info info = ReverseProcessor.probe(ffmpeg, path);
                double fps = ReverseMap.exactFps(info.fps);
                if (info.hasVideo && fps > 0) length = frames / fps;
            } catch (IOException | RuntimeException e) {
                length = -1;
            }
        }
        LENGTHS.put(key, length);
        return length;
    }
}
