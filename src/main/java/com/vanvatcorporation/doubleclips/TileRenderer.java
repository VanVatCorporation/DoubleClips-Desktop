package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.EffectTemplate;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;
import com.vanvatcorporation.doubleclips.data.editing.TransitionClip;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The pictures behind the picker tiles of transitions and effects, drawn by the REAL renderer: a tiny synthetic
 * timeline (two pictures A and B with the transition between them, or a test card with an effect clip over it) goes
 * through {@link OpenGLEdit#computeFrameForTimestamp} and {@link OpenGLTimelineExporter#drawFrameLayers} on a small
 * {@link GlCompositor}, exactly like a preview or export frame. A tile is therefore the style as it really plays, for
 * every style the renderer draws now and every one it learns later - there is no second copy of the maths to keep in
 * step. The pictures are made here, in memory (no project, no files): a clip's layer is just the picture it stands for.
 *
 * <p>Runs on the preview worker's GL thread (it owns the context); one instance per tile size.
 */
final class TileRenderer {

    static {
        System.setProperty("java.awt.headless", "true"); // the pictures are drawn with Java2D, no window involved
    }

    /** How long an effect's looping preview is (the same as the iOS editor's tiles). */
    static final float EFFECT_LOOP_SECONDS = 2.4f;

    private final int side;
    private final GlCompositor compositor;
    private final OpenGLEdit edit = new OpenGLEdit();
    private final OpenGLTimelineExporter.Layer layerA, layerB, layerCard;
    private final ByteBuffer pictureA, pictureB, pictureCard;
    private final Set<OpenGLTimelineExporter.Layer> uploaded = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ByteBuffer pixels;
    private final byte[] bgra, row;

    // the clips of the timeline being rendered (the drawer tells them apart by identity)
    private Clip clipA, clipB, clipCard;

    TileRenderer(int side) {
        this.side = side;
        compositor = new GlCompositor(side, side);
        layerA = compositor.createLayer(side, side);
        layerB = compositor.createLayer(side, side);
        layerCard = compositor.createLayer(side, side);
        // A: blue / teal, B: orange / pink - the same stand-ins as the iOS tiles.
        pictureA = letterPicture("A", new Color(46, 107, 255), new Color(0, 194, 168));
        pictureB = letterPicture("B", new Color(255, 140, 26), new Color(242, 51, 140));
        pictureCard = testCard();
        pixels = ByteBuffer.allocateDirect(side * side * 4).order(ByteOrder.nativeOrder());
        bgra = new byte[side * side * 4];
        row = new byte[side * 4];
    }

    int side() {
        return side;
    }

    void close() {
        compositor.destroyLayer(layerA);
        compositor.destroyLayer(layerB);
        compositor.destroyLayer(layerCard);
        compositor.close();
    }

    // ---- transitions ------------------------------------------------------------------------------

    /**
     * {@code frames} pictures of a transition from progress 0 to 1 (BGRA, top row first), or null when the renderer
     * does not draw this style (an FFmpeg-only one).
     */
    byte[][] transition(String style, int frames) {
        boolean builtIn = OpenGLEdit.SUPPORTED_TRANSITION_STYLES.contains(style);
        TransitionStyle data = TransitionStyleLoader.get(style);
        if (!builtIn && data == null) return null;

        float d = Math.max(0.2f, data != null ? data.getDefaultDuration() : 1f);
        float aDuration = Math.max(2f, d + 0.5f), bDuration = aDuration;

        Timeline timeline = new Timeline();
        Track track = new Track();
        timeline.addTrack(track);
        clipA = new Clip("A", 0f, aDuration, 0, ClipType.IMAGE, false, side, side);
        clipB = new Clip("B", aDuration, bDuration, 0, ClipType.IMAGE, false, side, side);
        clipCard = null;
        track.addClip(clipA);
        track.addClip(clipB);
        TransitionClip transition = new TransitionClip(clipA, clipB, d);
        transition.effect = new EffectTemplate(style, d, transition.startTime);
        transition.mode = TransitionClip.TransitionMode.OVERLAP;
        clipA.endTransition = transition;
        clipA.endTransitionEnabled = true;
        timeline.duration = aDuration + bDuration;

        float windowStart = aDuration - d;
        byte[][] out = new byte[frames][];
        for (int i = 0; i < frames; i++) {
            float p = frames > 1 ? i / (float) (frames - 1) : 1f;
            out[i] = render(timeline, windowStart + Math.min(p, 0.995f) * d);
        }
        return out;
    }

    // ---- effects ----------------------------------------------------------------------------------

    /** {@code frames} pictures spread over the effect's loop (BGRA, top row first), or null for an unknown effect. */
    byte[][] effect(String style, float intensity, int frames) {
        if (EffectCatalog.find(style) == null) return null;

        Timeline timeline = new Timeline();
        Track base = new Track(), top = new Track();
        timeline.addTrack(base);
        timeline.addTrack(top);
        clipA = clipB = null;
        clipCard = new Clip("card", 0f, EFFECT_LOOP_SECONDS, 0, ClipType.IMAGE, false, side, side);
        base.addClip(clipCard);
        Clip fx = new Clip("fx", 0f, EFFECT_LOOP_SECONDS, 1, ClipType.EFFECT, false, side, side);
        fx.effect = new EffectTemplate(style, EFFECT_LOOP_SECONDS, 0f);
        fx.effect.setIntensity(intensity);
        top.addClip(fx);
        timeline.duration = EFFECT_LOOP_SECONDS;

        byte[][] out = new byte[frames][];
        for (int i = 0; i < frames; i++) out[i] = render(timeline, EFFECT_LOOP_SECONDS * i / frames);
        return out;
    }

    // ---- one frame --------------------------------------------------------------------------------

    private byte[] render(Timeline timeline, float t) {
        List<OpenGLEdit.FrameLayer> layers = edit.computeFrameForTimestamp(timeline, t, side, side, true);
        OpenGLTimelineExporter.drawFrameLayers(compositor, layers, t, 1f, this::draw);
        compositor.readFrame(pixels);
        PreviewWorker.toBgraTopFirst(pixels, bgra, row, side, side);
        return bgra.clone();
    }

    /** A clip's layer is the picture it stands for; uploaded once, the first time it is drawn. */
    private boolean draw(OpenGLEdit.DrawCommand command, float t) {
        OpenGLTimelineExporter.Layer layer;
        ByteBuffer picture;
        if (command.clip == clipA) { layer = layerA; picture = pictureA; }
        else if (command.clip == clipB) { layer = layerB; picture = pictureB; }
        else { layer = layerCard; picture = pictureCard; }
        boolean first = uploaded.add(layer);
        compositor.draw(layer, first ? picture.duplicate() : null, command);
        return true;
    }

    // ---- the stand-in pictures (RGBA, top row first) ----------------------------------------------

    private ByteBuffer letterPicture(String letter, Color from, Color to) {
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, from, side, side, to));
            g.fillRect(0, 0, side, side);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(side * 0.55f)));
            g.setColor(new Color(255, 255, 255, 235));
            FontMetrics m = g.getFontMetrics();
            g.drawString(letter, (side - m.stringWidth(letter)) / 2f, (side - m.getHeight()) / 2f + m.getAscent());
        } finally {
            g.dispose();
        }
        int[] argb = image.getRGB(0, 0, side, side, null, 0, side);
        ByteBuffer rgba = ByteBuffer.allocateDirect(side * side * 4);
        for (int c : argb) {
            rgba.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) 0xFF);
        }
        rgba.flip();
        return rgba;
    }

    /** Sky gradient, a faint checkerboard and three discs: something to show every kind of effect on. */
    private ByteBuffer testCard() {
        float s = side;
        float[][] discs = {
                {0.32f * s, 0.38f * s, 0.20f * s, 1.00f, 0.88f, 0.20f},   // centre x, centre y (from the top), radius, colour
                {0.70f * s, 0.66f * s, 0.16f * s, 0.20f, 0.85f, 0.75f},
                {0.62f * s, 0.24f * s, 0.07f * s, 0.95f, 0.25f, 0.35f},
        };
        ByteBuffer rgba = ByteBuffer.allocateDirect(side * side * 4);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                float t = ((x + 0.5f) / s + (y + 0.5f) / s) / 2f; // top-left blue -> bottom-right orange
                float r = 0.10f + (0.98f - 0.10f) * t, g = 0.18f + (0.52f - 0.18f) * t, b = 0.55f + (0.20f - 0.55f) * t;
                int cell = (int) (s / 10f);
                if (cell > 0 && ((x / cell) + (y / cell)) % 2 == 0) { // white at 22%
                    r += (1f - r) * 0.22f;
                    g += (1f - g) * 0.22f;
                    b += (1f - b) * 0.22f;
                }
                for (float[] d : discs) {
                    float dx = x + 0.5f - d[0], dy = y + 0.5f - d[1];
                    float edge = d[2] + 0.6f - (float) Math.sqrt(dx * dx + dy * dy); // soft 1.2 px rim
                    float cover = Math.max(0f, Math.min(1f, edge / 1.2f));
                    r += (d[3] - r) * cover;
                    g += (d[4] - g) * cover;
                    b += (d[5] - b) * cover;
                }
                rgba.put(to8(r)).put(to8(g)).put(to8(b)).put((byte) 0xFF);
            }
        }
        rgba.flip();
        return rgba;
    }

    private static byte to8(float v) {
        return (byte) Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }
}
