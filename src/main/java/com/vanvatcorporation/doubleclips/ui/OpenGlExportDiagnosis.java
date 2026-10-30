package com.vanvatcorporation.doubleclips.ui;

import java.util.Locale;

/**
 * Turns a failed OpenGL export into something the user can act on. The raw error (often a native crash report or a GL error
 * code) always goes to the export log in full; this adds, when the evidence points at the project's own video settings (an
 * enormous canvas, an unusual frame rate), a plain sentence saying what probably happened and what to change, which the export
 * window shows in the log AND as a popup with a button into Video Settings. Failures that nothing points at the settings for
 * get no popup, only the log.
 *
 * <p>The video settings have no upper limit on purpose (see VideoSettingsDialog), so this is where a canvas the machine can't
 * render is explained instead of capped. Pure on its inputs (sizes and the error text), so it needs no UI to test.
 */
final class OpenGlExportDiagnosis {

    /** Most GPUs stop at this many pixels on one side of a framebuffer. */
    static final int COMMON_GPU_SIDE_LIMIT = 16384;
    /** Above this on one side a render worker that dies has most often run out of GPU memory. */
    static final int LARGE_SIDE = 8192;
    /** About this many megapixels and up, the same. */
    static final long LARGE_PIXELS = 60_000_000L;
    /** Above this frame rate the encoder or the player is the likelier problem than the picture. */
    static final int UNUSUAL_FRAME_RATE = 240;

    /** What probably went wrong and what to change; both are shown to the user as they are. */
    static final class Advice {
        final String summary;
        final String fix;

        Advice(String summary, String fix) {
            this.summary = summary;
            this.fix = fix;
        }
    }

    private OpenGlExportDiagnosis() {}

    /** "1600 MB", "12.8 GB", "3.4 TB". */
    static String describeBytes(double bytes) {
        if (bytes >= 1e12) return String.format(Locale.ROOT, "%.1f TB", bytes / 1e12);
        if (bytes >= 1e9) return String.format(Locale.ROOT, "%.1f GB", bytes / 1e9);
        return Math.round(bytes / 1e6) + " MB";
    }

    /**
     * @param evidence the error text: the exception's class names and messages and its stack trace (anything the worker
     *                 reported); may be null
     * @return null when nothing points at the video settings
     */
    static Advice diagnose(int width, int height, int frameRate, String evidence) {
        String e = evidence == null ? "" : evidence.toLowerCase(Locale.ROOT);
        int longest = Math.max(width, height);
        long pixels = (long) width * (long) height;
        String size = width + " x " + height;
        // double, not long: with no limit on the canvas, 4 bytes a pixel can pass what a long holds
        String frameSize = describeBytes((double) width * (double) height * 4.0);

        boolean memory = e.contains("outofmemory") || e.contains("out of memory") || e.contains("gl_out_of_memory")
                || e.contains("cannot allocate") || e.contains("unable to allocate") || e.contains("bad_alloc");
        boolean glRefused = e.contains("gl_invalid_value") || e.contains("gl_invalid_framebuffer")
                || (e.contains("framebuffer") && (e.contains("incomplete") || e.contains("not complete")))
                || e.contains("max texture") || e.contains("texture size") || e.contains("gl_max_texture_size");
        boolean workerDied = e.contains("exited") || e.contains("crash") || e.contains("sigsegv")
                || e.contains("native") || e.contains("worker died") || e.contains("worker stopped");
        boolean bigCanvas = longest > LARGE_SIDE || pixels > LARGE_PIXELS;

        String lowerIt = "Lower the width and height in Video Settings, then export again.";

        if (memory) {
            return new Advice("The computer ran out of memory rendering a " + size + " canvas (one frame is about "
                    + frameSize + ", and the renderer keeps several at once).", lowerIt);
        }
        if (glRefused) {
            String limit = longest > COMMON_GPU_SIDE_LIMIT
                    ? " Most graphics cards stop at " + COMMON_GPU_SIDE_LIMIT + " pixels on one side."
                    : "";
            return new Advice("The graphics card refused to create a " + size + " canvas." + limit, lowerIt);
        }
        if (workerDied && bigCanvas) {
            return new Advice("The render worker stopped unexpectedly. At " + size + " (" + (pixels / 1_000_000L)
                    + " megapixels) that is most often the graphics card running out of memory.", lowerIt);
        }
        if (frameRate > UNUSUAL_FRAME_RATE) {
            return new Advice("Nothing in the error names a cause, but a frame rate of " + frameRate
                    + " is far above what encoders and players handle, so it may be the problem.",
                    "Set the frame rate in Video Settings to a usual value (24 to 60, up to 120 for slow motion), then export again.");
        }
        if (bigCanvas) {
            return new Advice("Nothing in the error names a cause, but a " + size + " canvas is very large and may be more than "
                    + "this computer can render.", lowerIt);
        }
        return null;
    }
}
