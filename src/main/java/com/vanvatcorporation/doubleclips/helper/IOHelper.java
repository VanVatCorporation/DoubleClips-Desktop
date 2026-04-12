package com.vanvatcorporation.doubleclips.helper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;

public class IOHelper {

    public static String CombinePath(String... paths) {
        if (paths.length == 0) return "";
        File file = new File(paths[0]);
        for (int i = 1; i < paths.length; i++) {
            file = new File(file, paths[i]);
        }
        return file.getAbsolutePath();
    }

    public static String getNextIndexPathInFolder(String folderPath, String prefix, String extension, boolean createEmptyFile) {
        int nonexistentFileIndex = 0;
        while (isFileExist(CombinePath(folderPath, prefix + nonexistentFileIndex + extension))) {
            nonexistentFileIndex++;
        }
        String newFilePath = CombinePath(folderPath, prefix + nonexistentFileIndex + extension);
        if (createEmptyFile) {
            createEmptyFile(newFilePath);
        }
        return newFilePath;
    }

    public static void createEmptyFile(String filePath) {
        try {
            File file = new File(filePath);
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            if (file.createNewFile()) {
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                    writer.write("");
                }
            }
        } catch (IOException e) {
            System.err.println("Error creating empty file: " + e.getMessage());
        }
    }

    public static boolean isFileExist(String filePath) {
        return new File(filePath).exists();
    }

    public static void appendToFileTrunc(String filePath, String content, int truncByte) {
        try {
            File file = new File(filePath);
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }

            // Simple implementation for now
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))) {
                writer.write(content + "\n");
            }
        } catch (IOException e) {
            System.err.println("Error appending to file: " + e.getMessage());
        }
    }

    public static String getPersistentDataPath() {
        return System.getProperty("user.home") + File.separator + ".doubleclips";
    }
}
