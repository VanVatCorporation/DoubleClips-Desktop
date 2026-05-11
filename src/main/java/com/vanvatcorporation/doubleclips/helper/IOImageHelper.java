package com.vanvatcorporation.doubleclips.helper;

import com.vanvatcorporation.doubleclips.manager.LoggingManager;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

public class IOImageHelper extends IOHelper {
    
    public static void SaveFileAsPNGImage(String path, Image image) {
        SaveFileAsPNGImage(path, image, 100);
    }

    public static void SaveFileAsPNGImage(String path, Image image, int quality) {
        if (image == null) return;
        BufferedImage bufferedImage = SwingFXUtils.fromFXImage(image, null);
        SaveFileAsPNGImage(path, bufferedImage, quality);
    }

    public static void SaveFileAsPNGImage(String path, BufferedImage bm, int quality) {
        File file = new File(path);
        try {
            // Quality is mostly ignored for PNG in ImageIO, but we could use it for compression if needed
            // For now, simple write
            ImageIO.write(bm, "png", file);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Image LoadFileAsPNGImage(String path) {
        return LoadFileAsPNGImage(path, 1);
    }

    public static Image LoadFileAsPNGImage(String path, int sampleSize) {
        File file = new File(path);
        if (!file.exists()) return null;
        
        try (InputStream in = new FileInputStream(file)) {
            return new Image(in);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
