package com.vanvatcorporation.doubleclips.manager;

import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.helper.android.Context;

import java.io.PrintWriter;
import java.io.StringWriter;

public class LoggingManager {

    public static void LogToPersistentDataPath(String message) {
        System.out.println("[LOG]: " + message);
        LogToExternalDisk("debug.txt", message);
    }

    public static void LogToExternalDisk(String fileName, String message) {
        try {
            String logPath = IOHelper.CombinePath(IOHelper.getPersistentDataPath(), "logs", fileName);
            IOHelper.appendToFileTrunc(logPath, message, 1024 * 1024); // 1MB limit for now
        } catch (Exception e) {
            System.err.println("Error logging to disk: " + e.getMessage());
        }
    }

    public static void LogToNoteOverlay(String message) {
        System.out.println("[NOTE]: " + message);
        LogToPersistentDataPath(message);
    }

    public static void LogExceptionToNoteOverlay(Context context, Exception ex) {
        String stackTrace = getStackTraceFromException(ex);
        System.err.println("[EXCEPTION]: " + stackTrace);
        LogToPersistentDataPath(stackTrace);
    }

    public static void LogToToast(Context context, String message) {
        System.out.println("[TOAST]: " + message);
    }

    public static String getStackTraceFromException(Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }
}
