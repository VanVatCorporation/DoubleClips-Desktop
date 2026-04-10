package com.vanvatcorporation.doubleclips.helper;

import java.io.*;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class CompressionHelper {

    public static void zipFolder(File srcFolder, File destZipFile, ZipProgressListener listener) {
        try (FileOutputStream fos = new FileOutputStream(destZipFile); 
             ZipOutputStream zip = new ZipOutputStream(fos)) {
            
            long totalBytes = calculateTotalSize(srcFolder);
            long[] written = {0};

            addFolderToZip("", srcFolder, zip, listener, written, totalBytes);
            zip.flush();
            if (listener != null) listener.onCompleted();
        } catch (Exception e) {
            if (listener != null) listener.onError(e);
            e.printStackTrace();
        }
    }

    public static void unzipFolder(File zipFile, File destDir, UnzipProgressListener listener) {
        try {
            if (!destDir.exists()) destDir.mkdirs();

            long totalBytes = calculateTotalZipSize(zipFile);
            long[] extracted = {0};

            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    File newFile = new File(destDir, entry.getName());
                    if (entry.isDirectory()) {
                        newFile.mkdirs();
                    } else {
                        File parent = newFile.getParentFile();
                        if (parent != null) parent.mkdirs();
                        
                        try (FileOutputStream fos = new FileOutputStream(newFile)) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                fos.write(buffer, 0, len);
                                extracted[0] += len;
                                if (listener != null) listener.onProgress(extracted[0], totalBytes, entry.getName());
                            }
                        }
                    }
                    zis.closeEntry();
                }
            }

            if (listener != null) listener.onCompleted();
        } catch (Exception e) {
            if (listener != null) listener.onError(e);
            e.printStackTrace();
        }
    }

    private static void addFolderToZip(String path, File srcFolder, ZipOutputStream zip, ZipProgressListener listener, long[] written, long totalBytes) throws IOException {
        for (File file : Objects.requireNonNull(srcFolder.listFiles())) {
            String newPath = path.isEmpty() ? srcFolder.getName() : path + "/" + srcFolder.getName();
            if (file.isDirectory()) {
                addFolderToZip(newPath, file, zip, listener, written, totalBytes);
            } else {
                byte[] buf = new byte[8192];
                int len;
                try (FileInputStream in = new FileInputStream(file)) {
                    zip.putNextEntry(new ZipEntry(newPath + "/" + file.getName()));
                    while ((len = in.read(buf)) > 0) {
                        zip.write(buf, 0, len);
                        written[0] += len;
                        if (listener != null) listener.onProgress(written[0], totalBytes, file.getName());
                    }
                }
            }
        }
    }

    private static long calculateTotalSize(File folder) {
        if (folder.isFile()) return folder.length();
        long size = 0;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                size += calculateTotalSize(f);
            }
        }
        return size;
    }

    private static long calculateTotalZipSize(File file) throws IOException {
        long total = 0;
        try (ZipFile zipfile = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> zipEnum = zipfile.entries();
            while (zipEnum.hasMoreElements()) {
                ZipEntry entry = zipEnum.nextElement();
                if (!entry.isDirectory()) {
                    total += entry.getSize();
                }
            }
        }
        return total > 0 ? total : 1;
    }

    public interface ZipProgressListener {
        void onProgress(long bytesWritten, long totalBytes, String name);
        void onCompleted();
        void onError(Exception e);
    }

    public interface UnzipProgressListener {
        void onProgress(long bytesExtracted, long totalBytes, String name);
        void onCompleted();
        void onError(Exception e);
    }
}
