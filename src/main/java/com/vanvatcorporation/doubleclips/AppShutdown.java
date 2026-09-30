package com.vanvatcorporation.doubleclips;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Makes quitting the app reliable.
 * <p>
 * Why this exists: when the last JavaFX window closes, JavaFX tears down its UI thread and (on
 * macOS) the native event loop, but the JVM itself only exits once every NON-daemon thread has
 * finished. If any background thread is still alive (an executor nobody shut down, an HTTP
 * client's idle worker threads, ...) the process lingers with no event loop at all, which macOS
 * shows as "Application Not Responding" with the Dock icon still there. Which threads happen to be
 * alive depends on what the session did and on GC timing, hence "sometimes it quits, sometimes it
 * hangs".
 * <p>
 * The fix is to not depend on every thread in the codebase being well behaved:
 * {@link #shutdownAndExit(int)} stops the child processes the app started (ffmpeg decoders, the
 * OpenGL export worker JVM - none of which die on their own when the parent exits) and then
 * exits explicitly. A watchdog guarantees the process ends even if a shutdown hook stalls.
 * Deliberately has no JavaFX dependency so it can be tested on its own.
 */
public final class AppShutdown {

    /** How long System.exit may take (shutdown hooks) before the process is halted instead. */
    private static final long WATCHDOG_MILLIS = 3000;
    /** How long child processes get to exit after a polite destroy() before they are killed. */
    private static final long CHILD_GRACE_MILLIS = 500;

    private AppShutdown() {}

    /** Stops child processes, then exits the JVM. Does not return normally. */
    public static void shutdownAndExit(int exitCode) {
        startWatchdog();
        try {
            killChildProcesses();
        } catch (Throwable ignored) {
            // Nothing here may prevent the exit below.
        }
        System.exit(exitCode);
    }

    /**
     * Destroys every process this JVM started that is still running (and their descendants).
     * Polite destroy first so ffmpeg can close its files, then a forced kill for any survivor.
     */
    public static void killChildProcesses() {
        List<ProcessHandle> children = ProcessHandle.current().descendants().collect(Collectors.toList());
        for (ProcessHandle child : children) child.destroy();

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CHILD_GRACE_MILLIS);
        for (ProcessHandle child : children) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) break;
            try {
                child.onExit().get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (Exception ignored) {
                // timed out or interrupted - forced below
            }
        }
        for (ProcessHandle child : children) {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    private static void startWatchdog() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(WATCHDOG_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            // System.exit() hasn't finished (a shutdown hook is stuck) - skip the hooks.
            Runtime.getRuntime().halt(0);
        }, "AppShutdown-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }
}
