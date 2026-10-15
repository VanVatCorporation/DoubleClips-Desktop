package com.vanvatcorporation.doubleclips.ui.components;

import com.vanvatcorporation.doubleclips.ui.renderer.PreviewClient;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The pictures of the transition and effect pickers' tiles, made by the REAL renderer in the preview worker (see
 * TileRenderer): a tile is the style exactly as it plays in the preview and the export. Frames are made on request
 * and kept for the session, so a picker opens at once the second time.
 *
 * <p>The {@code ...Still} methods are instant and safe on the FX thread (a neutral square until the frames exist);
 * the {@code ...Loop} methods wait for the worker and belong off the FX thread (TilePreviewAnimator / TileGridDialog
 * call them there). Both return nothing for a style the renderer does not draw, or while there is no GPU worker.
 */
public final class GpuTilePreviews {

    /** Tile pictures are made this many pixels square and scaled down by the tile. */
    public static final int SIDE = 96;
    /** Transition: frames from progress 0 to 1, plus a short rest on A and on B at the ends of the loop, like the iOS tiles. */
    private static final int TRANSITION_RAMP = 20, TRANSITION_REST = 3;
    /** Effect: frames over its 2.4 s loop (12 per second, the tiles' clock). */
    private static final int EFFECT_FRAMES = 29, EFFECT_STILL_FRAME = 11;

    private final Supplier<PreviewClient> clientSource;
    private final ConcurrentHashMap<String, Image[]> made = new ConcurrentHashMap<>();
    private volatile Image placeholder;

    public GpuTilePreviews(Supplier<PreviewClient> clientSource) {
        this.clientSource = clientSource;
    }

    // ---- effects ----------------------------------------------------------------------------------

    public Image effectStill(String style, float intensity) {
        Image[] frames = made.get(effectKey(style, intensity));
        return frames == null ? placeholder() : frames[Math.min(EFFECT_STILL_FRAME, frames.length - 1)];
    }

    /** The loop of an effect (its frames in order), or null. Blocks until the worker has made it. */
    public Image[] effectLoop(String style, float intensity) {
        return frames("effect", style, intensity, EFFECT_FRAMES, effectKey(style, intensity));
    }

    private static String effectKey(String style, float intensity) {
        return "effect|" + style + "|" + Math.round(intensity * 100f);
    }

    // ---- transitions ------------------------------------------------------------------------------

    /** Halfway through the transition, or a neutral square until the frames exist. */
    public Image transitionStill(String style) {
        Image[] frames = made.get(transitionKey(style));
        return frames == null ? placeholder() : frames[frames.length / 2];
    }

    /** The loop of a transition: rest on A, A into B, rest on B. Null for a style the renderer does not draw. Blocks. */
    public Image[] transitionLoop(String style) {
        Image[] ramp = frames("transition", style, 1f, TRANSITION_RAMP, transitionKey(style));
        if (ramp == null) return null;
        Image[] loop = new Image[ramp.length + 2 * TRANSITION_REST];
        for (int i = 0; i < loop.length; i++) {
            loop[i] = ramp[Math.max(0, Math.min(ramp.length - 1, i - TRANSITION_REST))];
        }
        return loop;
    }

    private static String transitionKey(String style) {
        return "transition|" + style;
    }

    // ---- the worker -------------------------------------------------------------------------------

    private Image[] frames(String kind, String style, float intensity, int count, String key) {
        Image[] cached = made.get(key);
        if (cached != null) return cached;
        PreviewClient client = clientSource.get();
        if (client == null) return null;
        byte[][] raw;
        try {
            raw = client.requestTile(kind, style, intensity, SIDE, count).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
        if (raw == null) return null;
        Image[] images = new Image[raw.length];
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == null) return null;
            WritableImage image = new WritableImage(SIDE, SIDE);
            image.getPixelWriter().setPixels(0, 0, SIDE, SIDE, PixelFormat.getByteBgraInstance(), raw[i], 0, SIDE * 4);
            images[i] = image;
        }
        made.put(key, images);
        return images;
    }

    private Image placeholder() {
        Image p = placeholder;
        if (p == null) {
            WritableImage image = new WritableImage(SIDE, SIDE);
            int[] argb = new int[SIDE * SIDE];
            java.util.Arrays.fill(argb, 0xFF2A2A2E);
            image.getPixelWriter().setPixels(0, 0, SIDE, SIDE, PixelFormat.getIntArgbInstance(), argb, 0, SIDE);
            placeholder = p = image;
        }
        return p;
    }
}
