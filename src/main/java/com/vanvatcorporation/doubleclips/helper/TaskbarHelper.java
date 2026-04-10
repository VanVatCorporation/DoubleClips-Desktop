package com.vanvatcorporation.doubleclips.helper;

import java.awt.Taskbar;
import java.awt.HeadlessException;

public class TaskbarHelper {

    /**
     * Updates the application's taskbar/dock progress indicator.
     * @param progress value between 0.0 and 1.0. 
     */
    public static void updateProgress(double progress) {
        try {
            if (isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.PROGRESS_VALUE)) {
                int percent = (int) (Math.max(0, Math.min(1.0, progress)) * 100);
                Taskbar.getTaskbar().setProgressValue(percent);
            }
        } catch (Exception e) {
            // Ignore if taskbar interaction fails silently
        }
    }

    /**
     * Sets the taskbar/dock indicator to a specific state (e.g., ERROR, PAUSED, OFF).
     */
    public static void setState(Taskbar.State state) {
        try {
            if (isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.PROGRESS_STATE_WINDOW)) {
                // null window usually applies state to the main application taskbar entry
                Taskbar.getTaskbar().setWindowProgressState(null, state);
            }
        } catch (Exception e) {
            // Ignore if taskbar interaction fails silently
        }
    }

    /**
     * Clears all taskbar/dock progress indicators.
     */
    public static void stopProgress() {
        setState(Taskbar.State.OFF);
    }

    private static boolean isTaskbarSupported() {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless()) return false;
            return java.awt.Taskbar.getTaskbar() != null;
        } catch (UnsupportedOperationException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
