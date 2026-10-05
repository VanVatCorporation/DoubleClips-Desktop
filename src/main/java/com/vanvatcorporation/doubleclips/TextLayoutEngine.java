package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.editing.Clip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a TEXT clip into pixels, for the OpenGL preview and the OpenGL export alike (plain Java2D, no
 * GL and no JavaFX). One implementation for both means the preview shows exactly what will be exported,
 * and the editor can measure text with the same code the workers draw it with.
 * <p>
 * Everything is laid out in CANVAS units (one unit = one pixel of the project's output size), with
 * fractional metrics and no hinting, so the layout is the same at any {@code scale}. {@link #render}
 * only decides how many bitmap pixels each unit gets: the export uses 1, the preview uses its smaller
 * render size so the texture lands on screen pixel-for-pixel instead of being shrunk by the GPU.
 * <p>
 * The text box is positioned by the caller exactly like drawtext did: centred on the canvas, then offset
 * by PosX/PosY (see OpenGLEdit.buildClipMvp).
 */
public final class TextLayoutEngine {

    private TextLayoutEngine() {}

    /** Empty margin around the text so antialiased edges and italic overhang aren't clipped. In canvas units. */
    static final float PAD = 2f;
    private static final int MAX_CACHED_LAYOUTS = 64;

    /** A laid-out text block. Immutable; the box size is in canvas units and includes the padding. */
    public static final class Layout {
        public final float width;
        public final float height;
        final List<Line> lines;
        final int fillArgb;
        final float outlineWidth;
        final int outlineArgb;

        Layout(float width, float height, List<Line> lines, int fillArgb, float outlineWidth, int outlineArgb) {
            this.width = width;
            this.height = height;
            this.lines = lines;
            this.fillArgb = fillArgb;
            this.outlineWidth = outlineWidth;
            this.outlineArgb = outlineArgb;
        }

        /** The colour of the outermost thing drawn: what the empty margin's texels should carry (see toStraightRgba). */
        int edgeArgb() {
            return outlineWidth > 0f ? outlineArgb : fillArgb;
        }
    }

    static final class Line {
        final TextLayout layout; // null for an empty line
        final float x;
        final float baselineY;

        Line(TextLayout layout, float x, float baselineY) {
            this.layout = layout;
            this.x = x;
            this.baselineY = baselineY;
        }
    }

    /** A rendered bitmap: straight-alpha RGBA, top row first, in a direct buffer ready for glTexSubImage2D. */
    public static final class Bitmap {
        public final int width;
        public final int height;
        public final ByteBuffer rgba;

        Bitmap(int width, int height, ByteBuffer rgba) {
            this.width = width;
            this.height = height;
            this.rgba = rgba;
        }
    }

    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    private static final Map<String, Layout> LAYOUTS = new LinkedHashMap<String, Layout>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Layout> eldest) {
            return size() > MAX_CACHED_LAYOUTS;
        }
    };

    /** The size of the text box in canvas units, {width, height}. Cheap (cached); needs no bitmap and no GL. */
    public static float[] measure(TextStyle style) {
        Layout layout = layout(style);
        return new float[]{layout.width, layout.height};
    }

    public static Layout layout(TextStyle style) {
        String key = style.key();
        synchronized (LAYOUTS) {
            Layout cached = LAYOUTS.get(key);
            if (cached != null) return cached;
        }
        Layout built = build(style);
        synchronized (LAYOUTS) {
            LAYOUTS.put(key, built);
        }
        return built;
    }

    /**
     * Rasterises the text. {@code scale} is bitmap pixels per canvas unit; the bitmap is
     * ceil(box * scale) pixels, so the caller maps it onto the box (width x height canvas units).
     */
    public static Bitmap render(TextStyle style, float scale) {
        Layout layout = layout(style);
        float s = scale > 0f ? scale : 1f;
        int w = Math.max(1, (int) Math.ceil(layout.width * s));
        int h = Math.max(1, (int) Math.ceil(layout.height * s));

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            // The box is ceil()ed to whole pixels; scale by the real ratio so the text isn't stretched.
            g.scale(w / (double) layout.width, h / (double) layout.height);
            if (layout.outlineWidth > 0f) {
                // Stroke the glyph outlines first, then fill the letters on top: the stroke is centred on the
                // edge, so half of it (the inside) is covered, and a stroke of 2w leaves w visible outside.
                g.setStroke(new BasicStroke(layout.outlineWidth * 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(layout.outlineArgb, true));
                for (Line line : layout.lines) {
                    if (line.layout == null) continue;
                    Shape outline = line.layout.getOutline(AffineTransform.getTranslateInstance(line.x, line.baselineY));
                    g.draw(outline);
                }
            }
            g.setColor(new Color(layout.fillArgb, true));
            for (Line line : layout.lines) {
                if (line.layout != null) line.layout.draw(g, line.x, line.baselineY);
            }
        } finally {
            g.dispose();
        }
        return new Bitmap(w, h, toStraightRgba(image, layout.edgeArgb()));
    }

    // ── layout ───────────────────────────────────────────────────────────

    private static Layout build(TextStyle style) {
        Font font = fontFor(style);

        String text = style.text == null ? "" : style.text.replace("\r\n", "\n").replace('\r', '\n');
        // The outline sits outside the letters, so the margin grows with it (and wrapping leaves room for it).
        float pad = PAD + style.outlineWidth;
        float wrap = style.maxWidth > 0 ? Math.max(1f, style.maxWidth - 2 * pad) : Float.MAX_VALUE;

        List<TextLayout> layouts = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                layouts.add(null); // an empty line keeps the line height
                continue;
            }
            AttributedString attributed = new AttributedString(paragraph);
            attributed.addAttribute(TextAttribute.FONT, font);
            LineBreakMeasurer measurer = new LineBreakMeasurer(attributed.getIterator(), FRC);
            while (measurer.getPosition() < paragraph.length()) {
                layouts.add(measurer.nextLayout(wrap));
            }
        }
        if (layouts.isEmpty()) layouts.add(null);

        float emptyHeight = font.getLineMetrics("Ag", FRC).getHeight();
        float maxAdvance = 0f;
        for (TextLayout l : layouts) {
            if (l != null) maxAdvance = Math.max(maxAdvance, l.getVisibleAdvance());
        }

        float shift = style.align == TextStyle.ALIGN_CENTER ? 0.5f : style.align == TextStyle.ALIGN_RIGHT ? 1f : 0f;
        List<Line> lines = new ArrayList<>();
        float y = pad;
        for (TextLayout l : layouts) {
            if (l == null) {
                y += emptyHeight;
                continue;
            }
            float baseline = y + l.getAscent();
            lines.add(new Line(l, pad + (maxAdvance - l.getVisibleAdvance()) * shift, baseline));
            y += l.getAscent() + l.getDescent() + l.getLeading();
        }
        return new Layout(Math.max(1f, maxAdvance) + 2 * pad, Math.max(1f, y - pad) + 2 * pad, lines,
                style.colorArgb, style.outlineWidth, style.outlineColorArgb);
    }

    // ── fonts ────────────────────────────────────────────────────────────

    /** Imported font files, loaded once. A file that fails to load is remembered as null so it isn't retried every frame. */
    private static final Map<String, Font> FILE_FONTS = new java.util.HashMap<>();

    /** The font for a style: an imported file if one is set and loads, otherwise the named family (Java falls back to a default if it is unknown). */
    private static Font fontFor(TextStyle style) {
        int flags = (style.bold ? Font.BOLD : 0) | (style.italic ? Font.ITALIC : 0);
        Font base = style.fontFile != null ? fileFont(style.fontFile) : null;
        if (base != null) return base.deriveFont(flags, Math.max(1f, style.fontSize));
        return new Font(style.fontFamily, flags, 12).deriveFont(Math.max(1f, style.fontSize));
    }

    private static Font fileFont(String path) {
        synchronized (FILE_FONTS) {
            if (FILE_FONTS.containsKey(path)) return FILE_FONTS.get(path);
        }
        Font loaded = null;
        try {
            File file = new File(path);
            if (file.isFile()) loaded = Font.createFont(Font.TRUETYPE_FONT, file);
        } catch (Exception | Error e) {
            loaded = null; // corrupt or unsupported: the style falls back to its family
        }
        synchronized (FILE_FONTS) {
            FILE_FONTS.put(path, loaded);
        }
        return loaded;
    }

    // ── pixels ───────────────────────────────────────────────────────────

    /**
     * Premultiplied ARGB ints -> straight-alpha RGBA bytes (the compositor blends with SRC_ALPHA).
     * Fully transparent pixels get the text colour as their RGB: the GPU's bilinear filter blends
     * neighbouring texels' colours, and (0,0,0) there would put a dark fringe around light text.
     */
    private static ByteBuffer toStraightRgba(BufferedImage image, int fillArgb) {
        int w = image.getWidth(), h = image.getHeight();
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        ByteBuffer out = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        byte fr = (byte) (fillArgb >> 16), fg = (byte) (fillArgb >> 8), fb = (byte) fillArgb;
        for (int i = 0; i < w * h; i++) {
            int p = pixels[i];
            int a = p >>> 24;
            if (a == 0) {
                out.put(fr).put(fg).put(fb).put((byte) 0);
            } else if (a == 255) {
                out.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) 255);
            } else {
                int r = Math.min(255, ((p >> 16 & 0xFF) * 255 + a / 2) / a);
                int g = Math.min(255, ((p >> 8 & 0xFF) * 255 + a / 2) / a);
                int b = Math.min(255, ((p & 0xFF) * 255 + a / 2) / a);
                out.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
            }
        }
        out.flip();
        return out;
    }

    /** What the clip looks like in this engine's terms. Everything the text bitmap depends on is in here. */
    public static TextStyle styleOf(Clip clip, int canvasWidth) {
        return TextStyle.of(clip, canvasWidth);
    }

    /** True if the file is a font Java can load (used when importing, to refuse a bad file up front). */
    public static boolean isLoadableFont(File file) {
        try {
            Font.createFont(Font.TRUETYPE_FONT, file);
            return true;
        } catch (Exception | Error e) {
            return false;
        }
    }

    /** The family name a font file declares (e.g. "Arsenal SC"), or null if it can't be read. */
    public static String familyOfFile(File file) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, file).getFamily();
        } catch (Exception | Error e) {
            return null;
        }
    }
}
