package com.vanvatcorporation.doubleclips.ui.components;

import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.awt.image.BufferedImage;

/** BufferedImage (the CPU renderers' picture) to a JavaFX image. Safe off the FX thread while the image is not yet shown. */
public final class FxImages {

    private FxImages() {}

    public static WritableImage of(BufferedImage source) {
        int w = source.getWidth(), h = source.getHeight();
        int[] argb = source.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < argb.length; i++) argb[i] |= 0xFF000000; // opaque pictures; make sure of it
        WritableImage image = new WritableImage(w, h);
        image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        return image;
    }

    public static WritableImage[] of(BufferedImage[] frames) {
        WritableImage[] out = new WritableImage[frames.length];
        for (int i = 0; i < frames.length; i++) out[i] = of(frames[i]);
        return out;
    }
}
