package com.vanvatcorporation.doubleclips.data.storage;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class StorageHelper {

    private static final String APP_NAME = "DoubleClips";

    public static File getCacheDirectory() {
        String os = System.getProperty("os.name").toLowerCase();
        Path path;

        if (os.contains("win")) {
            // Windows: %LOCALAPPDATA%\DoubleClips\Cache
            path = Paths.get(System.getenv("LOCALAPPDATA"), APP_NAME, "Cache");
        } else if (os.contains("mac")) {
            // macOS: ~/Library/Caches/DoubleClips
            path = Paths.get(System.getProperty("user.home"), "Library", "Caches", APP_NAME);
        } else {
            // Linux/Other: ~/.cache/doubleclips
            path = Paths.get(System.getProperty("user.home"), ".cache", APP_NAME.toLowerCase());
        }

        File dir = path.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File getAppDirectory() {
        String os = System.getProperty("os.name").toLowerCase();
        Path path;

        if (os.contains("win")) {
            // Windows: %LOCALAPPDATA%\DoubleClips
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData == null) {
                localAppData = System.getProperty("user.home") + "\\AppData\\Local";
            }
            path = Paths.get(localAppData, APP_NAME);
        } else if (os.contains("mac")) {
            // macOS: ~/Library/Application Support/DoubleClips
            path = Paths.get(System.getProperty("user.home"), "Library", "Application Support", APP_NAME);
        } else {
            // Linux/Other: ~/.doubleclips
            path = Paths.get(System.getProperty("user.home"), "." + APP_NAME.toLowerCase());
        }

        File dir = path.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File getAuthFile() {
        return new File(getAppDirectory(), "auth_cookies.json");
    }
}
