package com.vanvatcorporation.doubleclips.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * The temporary clip shown on the timeline while a voiceover is being recorded: a box that grows with the take and
 * draws its live waveform. It is a view only - nothing of it is in the project until the recording ends and the real
 * audio clip replaces it. The waveform is drawn only for the part that is on screen, so a long take at a high zoom
 * does not need a huge canvas.
 */
final class VoiceoverLiveClip extends Pane {

    private static final double BLOCK_SECONDS = 0.02;       // one min/max pair per 20 ms (VoiceRecorder)

    private final Rectangle box = new Rectangle();
    private final Canvas wave = new Canvas();
    private final Label name = new Label("Voiceover");

    VoiceoverLiveClip(double height) {
        box.setHeight(height);
        box.setArcWidth(6);
        box.setArcHeight(6);
        box.setFill(Color.web("#7a3cc8", 0.38));
        box.setStroke(Color.web("#b38cff"));
        box.setStrokeWidth(1.5);
        name.setStyle("-fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: bold;");
        name.setLayoutX(6);
        name.setLayoutY(2);
        wave.setHeight(height);
        wave.setMouseTransparent(true);
        setMouseTransparent(true);                            // never steals a click from the timeline
        setPrefHeight(height);
        getChildren().addAll(box, wave, name);
    }

    /**
     * @param pps     pixels per second of the timeline
     * @param seconds length of the take so far
     * @param peaks   min/max pairs, one per 20 ms, from the start of the take
     * @param visFrom first visible x inside this node (0..width), or negative when unknown
     * @param visTo   last visible x inside this node
     */
    void update(double pps, double seconds, float[] peaks, int blocks, double visFrom, double visTo) {
        double width = Math.max(2, seconds * pps);
        box.setWidth(width);
        double h = box.getHeight();
        double x0 = Math.max(0, Math.min(visFrom, width));
        double x1 = Math.min(width, Math.max(visTo, x0));
        int px = (int) Math.max(1, Math.min(4096, Math.ceil(x1 - x0)));
        wave.setLayoutX(x0);
        wave.setWidth(px);
        GraphicsContext g = wave.getGraphicsContext2D();
        g.clearRect(0, 0, px, h);
        g.setStroke(Color.web("#ffffff", 0.9));
        g.setLineWidth(1);
        double mid = h / 2, amp = h * 0.42;
        for (int c = 0; c < px; c++) {
            double t0 = (x0 + c) / pps, t1 = (x0 + c + 1) / pps;
            int b0 = (int) (t0 / BLOCK_SECONDS), b1 = Math.max(b0 + 1, (int) Math.ceil(t1 / BLOCK_SECONDS));
            float lo = 0, hi = 0;
            for (int b = b0; b < b1 && b < blocks; b++) {
                lo = Math.min(lo, peaks[2 * b]);
                hi = Math.max(hi, peaks[2 * b + 1]);
            }
            double yTop = mid - hi * amp, yBot = mid - lo * amp;
            if (yBot - yTop < 1) { yTop = mid - 0.5; yBot = mid + 0.5; }
            g.strokeLine(c + 0.5, yTop, c + 0.5, yBot);
        }
    }
}
