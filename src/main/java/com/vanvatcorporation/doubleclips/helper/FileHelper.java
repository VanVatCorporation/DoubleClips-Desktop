package com.vanvatcorporation.doubleclips.helper;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;

public class FileHelper {

    /**
     * Recursively deletes a directory and all its contents.
     */
    public static void deleteDirectory(Path path) throws IOException {
        if (Files.notExists(path)) return;
        
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Recursively copies a directory and all its contents.
     */
    public static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = target.resolve(source.relativize(dir));
                if (!Files.exists(targetDir)) {
                    Files.createDirectories(targetDir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    /**
     * Reveals the specified file or directory in the system's file browser.
     */
    public static void revealInFileBrowser(Path path) {
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", "/select," + path.toAbsolutePath()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", "-R", path.toAbsolutePath().toString()).start();
            } else {
                // TODO: Generic fallback for Linux (opens directory, doesn't always select)
                java.awt.Desktop.getDesktop().open(path.getParent().toFile());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Saves a resource from the classpath to a physical file on disk.
     */
    public static void saveResourceToFile(String resourcePath, File destination) {
        try (java.io.InputStream in = FileHelper.class.getResourceAsStream(resourcePath);
             java.io.OutputStream out = new java.io.FileOutputStream(destination)) {
            if (in == null) throw new IOException("Resource not found: " + resourcePath);
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
