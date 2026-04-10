package com.vanvatcorporation.doubleclips.helper;

import com.vanvatcorporation.doubleclips.data.storage.StorageHelper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class VideoCacheManager {

    private static final long MAX_CACHE_SIZE = 100 * 1024 * 1024; // 100 MB
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final OkHttpClient client = new OkHttpClient();

    public static void getCachedVideoPath(String url, Consumer<String> onResult) {
        String fileName = generateFileName(url);
        File cacheDir = new File(StorageHelper.getCacheDirectory(), "videos");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        File cachedFile = new File(cacheDir, fileName);

        if (cachedFile.exists()) {
            onResult.accept(cachedFile.toURI().toString());
            return;
        }

        // Fetch from network
        executor.execute(() -> {
            try {
                Request request = new Request.Builder().url(url).build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        try (BufferedSink sink = Okio.buffer(Okio.sink(cachedFile))) {
                            sink.writeAll(response.body().source());
                        }
                        
                        // Perform cleanup if over limit
                        cleanupCache(cacheDir);
                        
                        onResult.accept(cachedFile.toURI().toString());
                    } else {
                        onResult.accept(url); // Fallback to raw URL
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                onResult.accept(url); // Fallback to raw URL
            }
        });
    }

    private static String generateFileName(String url) {
        // Simple hash to create a unique file name
        return "vid_" + Math.abs(url.hashCode()) + ".mp4";
    }

    private static void cleanupCache(File cacheDir) {
        File[] files = cacheDir.listFiles();
        if (files == null) return;

        long currentSize = 0;
        for (File f : files) currentSize += f.length();

        if (currentSize > MAX_CACHE_SIZE) {
            // Sort by last modified (oldest first)
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));

            for (File f : files) {
                currentSize -= f.length();
                f.delete();
                if (currentSize <= MAX_CACHE_SIZE) break;
            }
        }
    }
}
