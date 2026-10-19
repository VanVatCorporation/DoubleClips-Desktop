package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.TemplateStripMath;
import com.vanvatcorporation.doubleclips.TemplateTimelineInfo;
import com.vanvatcorporation.doubleclips.TemplateTimelineInfo.StripClip;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

/**
 * A template's timeline for the desktop preview: the whole duration across the full width (the desktop has the room the
 * phones do not, so no sliding strip), tracks as rows (lowest timelineIndex on top), clips coloured by role
 * (white = yours, numbered; gray = locked; blue = audio; green = effect; yellow = text; purple = other), a ruler on top
 * and a red playhead that follows the preview video. Click or drag anywhere to scrub; hovering a clip reports it.
 */
public final class TemplateTimelineStrip extends Region {

    public interface Listener {
        void onScrubStart();
        /** The strip wants the video at {@code seconds}. */
        void onScrub(double seconds);
        void onScrubEnd();
        /** The clip under the mouse (null when none). */
        void onHover(StripClip clip);
    }

    private static final double PAD = 14;
    private static final double RULER_H = 20;
    private static final double GAP = 4;
    private static final Color PLAYHEAD = Color.web("#ff3b30");
    private static final double[] STEPS = {0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600};

    private final Canvas canvas = new Canvas();
    private TemplateTimelineInfo info;
    private double time;
    private boolean scrubbing;
    private Listener listener;
    private StripClip hovered;

    public TemplateTimelineStrip() {
        getChildren().add(canvas);
        setMinHeight(96);
        setCursor(Cursor.HAND);
        setOnMousePressed(e -> {
            if (info == null || info.duration <= 0) return;
            scrubbing = true;
            if (listener != null) listener.onScrubStart();
            scrubTo(e.getX());
        });
        setOnMouseDragged(e -> { if (scrubbing) scrubTo(e.getX()); });
        setOnMouseReleased(e -> {
            if (!scrubbing) return;
            scrubbing = false;
            if (listener != null) listener.onScrubEnd();
        });
        setOnMouseMoved(e -> {
            StripClip c = clipAt(e.getX(), e.getY());
            if (c != hovered) {
                hovered = c;
                if (listener != null) listener.onHover(c);
                redraw();
            }
        });
        setOnMouseExited(e -> {
            if (hovered != null) {
                hovered = null;
                if (listener != null) listener.onHover(null);
                redraw();
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setInfo(TemplateTimelineInfo info) {
        this.info = info;
        hovered = null;
        redraw();
    }

    /** Playhead position in seconds (from the video player). */
    public void setTime(double seconds) {
        if (seconds == time) return;
        time = seconds;
        redraw();
    }

    /** Height that shows every row comfortably (ruler + rows of up to 30 px). */
    public double idealHeight() {
        int rows = info == null ? 1 : Math.max(1, info.rowCount);
        return Math.max(96, RULER_H + 12 + rows * 30 + (rows - 1) * GAP);
    }

    private void scrubTo(double x) {
        double pps = pixelsPerSecond();
        if (pps <= 0) return;
        double t = Math.min(Math.max((x - PAD) / pps, 0), info.duration);
        time = t;
        redraw();
        if (listener != null) listener.onScrub(t);
    }

    private double pixelsPerSecond() {
        if (info == null || info.duration <= 0) return 0;
        return Math.max(1, getWidth() - 2 * PAD) / info.duration;
    }

    private double rowTop() {
        return RULER_H + 6;
    }

    private double rowHeight() {
        int rows = info == null ? 1 : Math.max(1, info.rowCount);
        return TemplateStripMath.rowHeight((float) (getHeight() - rowTop() + 8), rows, (float) GAP, 14f, 34f);
    }

    private StripClip clipAt(double x, double y) {
        if (info == null) return null;
        double pps = pixelsPerSecond();
        double rh = rowHeight();
        for (int i = info.clips.size() - 1; i >= 0; i--) {
            StripClip c = info.clips.get(i);
            double left = PAD + c.start * pps, w = Math.max(3, c.duration * pps - 1);
            double top = rowTop() + c.row * (rh + GAP);
            if (x >= left && x <= left + w && y >= top && y <= top + rh) return c;
        }
        return null;
    }

    @Override
    protected void layoutChildren() {
        canvas.setLayoutX(0);
        canvas.setLayoutY(0);
        canvas.setWidth(Math.max(1, getWidth()));
        canvas.setHeight(Math.max(1, getHeight()));
        redraw();
    }

    // ---- drawing ------------------------------------------------------------------------------

    private void redraw() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.setFill(Color.web("#808080", 0.10));
        g.fillRoundRect(0, 0, w, h, 12, 12);

        if (info == null || info.duration <= 0) {
            g.setFill(Color.web("#a0a0a0"));
            g.setFont(Font.font(12));
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText("The timeline appears when the preview has loaded", w / 2, h / 2 + 4);
            return;
        }
        double pps = pixelsPerSecond();
        drawRuler(g, pps, w);

        double rh = rowHeight();
        Font numberFont = Font.font("System", FontWeight.BOLD, Math.min(14, rh * 0.6));
        Font nameFont = Font.font("System", FontWeight.NORMAL, Math.min(11, rh * 0.45));
        for (StripClip c : info.clips) {
            double left = PAD + c.start * pps, cw = Math.max(3, c.duration * pps - 1);
            double top = rowTop() + c.row * (rh + GAP);
            boolean active = time >= c.start && time < c.start + c.duration;
            boolean hover = c == hovered;
            Color base = argb(c.role.colorArgb);
            g.setFill(active || hover ? base : base.deriveColor(0, 1, 1, 0.78));
            g.fillRoundRect(left, top, cw, rh, 7, 7);
            if (active) {
                g.setStroke(PLAYHEAD);
                g.setLineWidth(1.6);
                g.strokeRoundRect(left + 0.8, top + 0.8, cw - 1.6, rh - 1.6, 7, 7);
            }
            drawLabel(g, c, left, top, cw, rh, numberFont, nameFont);
        }

        // The playhead: a red line through every row with a small head on the ruler.
        double px = PAD + Math.min(Math.max(time, 0), info.duration) * pps;
        g.setStroke(PLAYHEAD);
        g.setLineWidth(2);
        g.setLineCap(StrokeLineCap.ROUND);
        g.strokeLine(px, 6, px, h - 6);
        g.setFill(PLAYHEAD);
        g.fillPolygon(new double[]{px - 5, px + 5, px}, new double[]{2, 2, 11}, 3);
    }

    private void drawRuler(GraphicsContext g, double pps, double w) {
        double step = STEPS[STEPS.length - 1];
        for (double s : STEPS) {
            if (s * pps >= 64) { step = s; break; }
        }
        g.setFont(Font.font(10));
        g.setTextAlign(TextAlignment.LEFT);
        g.setStroke(Color.web("#808080", 0.55));
        g.setFill(Color.web("#909090"));
        g.setLineWidth(1);
        for (double t = 0; t <= info.duration + 1e-6; t += step) {
            double x = PAD + t * pps;
            g.strokeLine(x + 0.5, RULER_H - 7, x + 0.5, RULER_H - 1);
            g.fillText(TemplateStripMath.clock(t), x + 3, RULER_H - 5);
        }
    }

    private void drawLabel(GraphicsContext g, StripClip c, double left, double top, double cw, double rh, Font numberFont, Font nameFont) {
        if (cw < 14) return;
        boolean darkText = c.role == TemplateTimelineInfo.Role.REPLACEABLE || c.role == TemplateTimelineInfo.Role.TEXT;
        g.setFill(darkText ? Color.web("#1b1b1f") : Color.WHITE);
        g.setTextAlign(TextAlignment.LEFT);
        String label;
        Font font;
        if (c.slotNumber > 0) {
            label = String.valueOf(c.slotNumber);
            font = numberFont;
        } else {
            label = nameOf(c.clip);
            font = nameFont;
        }
        if (label.isEmpty()) return;
        g.setFont(font);
        label = fit(label, font, cw - 10);
        g.fillText(label, left + 6, top + rh / 2 + font.getSize() * 0.35);
    }

    private static String nameOf(Clip clip) {
        if (clip == null) return "";
        String n;
        if (clip.type == ClipType.TEXT && clip.textContent != null && !clip.textContent.isBlank()) n = clip.textContent.replace('\n', ' ');
        else n = clip.getClipName() == null ? "" : clip.getClipName();
        int dot = n.lastIndexOf('.');
        return dot > 0 && clip.type != ClipType.TEXT ? n.substring(0, dot) : n;
    }

    private static String fit(String s, Font font, double maxWidth) {
        if (maxWidth <= 0) return "";
        Text t = new Text(s);
        t.setFont(font);
        if (t.getLayoutBounds().getWidth() <= maxWidth) return s;
        for (int len = s.length() - 1; len > 0; len--) {
            t.setText(s.substring(0, len) + "\u2026");
            if (t.getLayoutBounds().getWidth() <= maxWidth) return t.getText();
        }
        return "";
    }

    public static Color argb(int argb) {
        return Color.rgb((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, ((argb >>> 24) & 0xFF) / 255.0);
    }
}
