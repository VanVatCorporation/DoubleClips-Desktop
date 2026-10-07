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
import java.util.Collections;
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
        final float pad;
        final TextStyle style;
        private final Map<String, List<Unit>> unitCache = new java.util.HashMap<>();

        Layout(float width, float height, List<Line> lines, int fillArgb, float outlineWidth, int outlineArgb, float pad,
               TextStyle style) {
            this.style = style;
            this.width = width;
            this.height = height;
            this.pad = pad;
            this.lines = lines;
            this.fillArgb = fillArgb;
            this.outlineWidth = outlineWidth;
            this.outlineArgb = outlineArgb;
        }

        /** Units for a mode, computed once per layout. Splitting needs only the layout, no bitmap and no GL. */
        List<Unit> units(String mode) {
            String key = mode == null ? "" : mode;
            synchronized (unitCache) {
                List<Unit> cached = unitCache.get(key);
                if (cached == null) {
                    cached = Collections.unmodifiableList(splitUnits(this, key));
                    unitCache.put(key, cached);
                }
                return cached;
            }
        }

        /** The colour of the outermost thing drawn: what the empty margin's texels should carry (see toStraightRgba). */
        int edgeArgb() {
            if (style.hasBackground()) return style.backgroundArgb;
            return outlineWidth > 0f ? outlineArgb : fillArgb;
        }
    }

    static final class Line {
        final TextLayout layout; // null for an empty line
        final float x;
        final float baselineY;
        /** The paragraph this line was cut from and where in it the line starts (for splitting into units). */
        final String paragraph;
        final int start;
        /** Distance from the line's top to its baseline, and the line's pitch (ascent + descent + leading). */
        final float ascent;
        final float pitch;

        Line(TextLayout layout, float x, float baselineY, String paragraph, int start, float ascent, float pitch) {
            this.layout = layout;
            this.x = x;
            this.baselineY = baselineY;
            this.paragraph = paragraph;
            this.start = start;
            this.ascent = ascent;
            this.pitch = pitch;
        }
    }

    /** One animatable piece of the text (a character, word or line): its rectangle in the text box, canvas units. */
    public static final class Unit {
        public final float x, y, w, h;

        Unit(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    public static final String UNIT_CHARACTER = "CHARACTER", UNIT_WORD = "WORD", UNIT_LINE = "LINE";

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
        float sc = scale > 0f ? scale : 1f;
        int w = Math.max(1, (int) Math.ceil(layout.width * sc));
        int h = Math.max(1, (int) Math.ceil(layout.height * sc));
        // The box is ceil()ed to whole pixels; scale by the real ratio so the text isn't stretched.
        BufferedImage image = rasterize(layout, w, h, w / (double) layout.width, h / (double) layout.height, null);
        return new Bitmap(w, h, toStraightRgba(image, layout.edgeArgb()));
    }

    /**
     * Rasterises ONE unit of the text (see {@link #units}): the same drawing as {@link #render} restricted to
     * the unit's rectangle, so the units laid back at their positions add up to exactly the whole block.
     */
    public static Bitmap renderUnit(TextStyle style, String mode, int index, float scale) {
        Layout layout = layout(style);
        List<Unit> units = layout.units(mode);
        if (index < 0 || index >= units.size()) throw new IndexOutOfBoundsException("no text unit " + index);
        Unit u = units.get(index);
        float s = scale > 0f ? scale : 1f;
        int w = Math.max(1, (int) Math.ceil(u.w * s));
        int h = Math.max(1, (int) Math.ceil(u.h * s));

        BufferedImage image = rasterize(layout, w, h, w / (double) u.w, h / (double) u.h, u);
        return new Bitmap(w, h, toStraightRgba(image, layout.edgeArgb()));
    }

    /** The draw-side view of {@link #units}: the same rectangles as the plain value type OpenGLEdit works with. */
    public static List<OpenGLEdit.TextUnit> toUnits(List<Unit> units) {
        List<OpenGLEdit.TextUnit> out = new ArrayList<>(units.size());
        for (Unit u : units) out.add(new OpenGLEdit.TextUnit(u.x, u.y, u.w, u.h));
        return out;
    }

    /** The units of the text for {@code mode} (CHARACTER / WORD / LINE), in reading order. Empty if there is no ink. */
    public static List<Unit> units(TextStyle style, String mode) {
        return layout(style).units(mode);
    }

    private static Graphics2D graphics(BufferedImage image, double sx, double sy, Unit tile) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(sx, sy);
        if (tile != null) {
            g.translate(-tile.x, -tile.y);
            g.clip(new java.awt.geom.Rectangle2D.Float(tile.x, tile.y, tile.w, tile.h));
        }
        return g;
    }

    /**
     * Draws the whole block - or, with {@code tile}, just that unit's rectangle of it - into a w x h bitmap.
     * Layers, bottom to top, as iOS draws them: the background box, the shadow (of the outline and the
     * letters together), the outline, the letters. A unit has no background box: it belongs to the whole text.
     */
    private static BufferedImage rasterize(Layout layout, int w, int h, double sx, double sy, Unit tile) {
        TextStyle style = layout.style;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);

        if (style.hasBackground() && tile == null) {
            Graphics2D g = graphics(image, sx, sy, null);
            try {
                float boxPad = style.backgroundPadding;
                float inset = layout.pad - boxPad;
                float bw = layout.width - 2 * inset, bh = layout.height - 2 * inset;
                float radius = Math.min(style.backgroundRadius, Math.min(bw, bh) / 2f) * 2f;
                g.setColor(new Color(style.backgroundArgb, true));
                g.fill(new java.awt.geom.RoundRectangle2D.Float(inset, inset, bw, bh, radius, radius));
            } finally {
                g.dispose();
            }
        }

        if (style.hasShadow()) {
            BufferedImage silhouette = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D g = graphics(silhouette, sx, sy, tile);
            try {
                drawLayout(g, layout, style.shadowArgb);
            } finally {
                g.dispose();
            }
            // NSShadow's blur radius is about two standard deviations; the reach we reserved is the radius.
            blur(silhouette, (float) (style.shadowBlur * 0.5 * Math.max(sx, sy)));
            Graphics2D out = image.createGraphics();
            try {
                out.drawImage(silhouette, Math.round((float) (style.shadowOffsetX * sx)), Math.round((float) (style.shadowOffsetY * sy)), null);
            } finally {
                out.dispose();
            }
        }

        Graphics2D g = graphics(image, sx, sy, tile);
        try {
            drawLayout(g, layout, null);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Draws the outline (if any) and then the letters; {@code override} paints both in one colour (the shadow). */
    private static void drawLayout(Graphics2D g, Layout layout, Integer override) {
        if (layout.outlineWidth > 0f) {
            // Stroke the glyph outlines first, then fill the letters on top: the stroke is centred on the
            // edge, so half of it (the inside) is covered, and a stroke of 2w leaves w visible outside.
            g.setStroke(new BasicStroke(layout.outlineWidth * 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(override != null ? override : layout.outlineArgb, true));
            for (Line line : layout.lines) {
                if (line.layout == null) continue;
                Shape outline = line.layout.getOutline(AffineTransform.getTranslateInstance(line.x, line.baselineY));
                g.draw(outline);
            }
        }
        g.setColor(new Color(override != null ? override : layout.fillArgb, true));
        for (Line line : layout.lines) {
            if (line.layout != null) line.layout.draw(g, line.x, line.baselineY);
        }
    }

    /**
     * Gaussian-like blur of a premultiplied ARGB image in place: three box blurs per axis, whose combined
     * spread matches a Gaussian of standard deviation {@code sigma} (in pixels). A no-op below a third of a pixel.
     */
    static void blur(BufferedImage image, float sigma) {
        if (sigma < 0.34f) return;
        int radius = Math.max(1, Math.round((float) ((Math.sqrt(4.0 * sigma * sigma + 1.0) - 1.0) / 2.0)));
        int w = image.getWidth(), h = image.getHeight();
        int[] px = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        int[] tmp = new int[Math.max(w, h)];
        for (int pass = 0; pass < 3; pass++) {
            for (int y = 0; y < h; y++) boxLine(px, y * w, 1, w, radius, tmp);
            for (int x = 0; x < w; x++) boxLine(px, x, w, h, radius, tmp);
        }
    }

    /** One box-blur pass along a line of {@code n} pixels starting at {@code start}, stepping by {@code stride}. */
    private static void boxLine(int[] px, int start, int stride, int n, int r, int[] tmp) {
        long a = 0, rr = 0, gg = 0, bb = 0;
        int window = 2 * r + 1;
        // Prime the window with pixels [-r .. r-1] (outside the image counts as transparent).
        for (int i = 0; i < r && i < n; i++) {
            int p = px[start + i * stride];
            a += p >>> 24; rr += p >> 16 & 0xFF; gg += p >> 8 & 0xFF; bb += p & 0xFF;
        }
        for (int i = 0; i < n; i++) {
            int add = i + r, drop = i - r - 1;
            if (add < n) {
                int p = px[start + add * stride];
                a += p >>> 24; rr += p >> 16 & 0xFF; gg += p >> 8 & 0xFF; bb += p & 0xFF;
            }
            if (drop >= 0) {
                int p = px[start + drop * stride];
                a -= p >>> 24; rr -= p >> 16 & 0xFF; gg -= p >> 8 & 0xFF; bb -= p & 0xFF;
            }
            long half = window / 2;
            tmp[i] = (int) ((a + half) / window) << 24 | (int) ((rr + half) / window) << 16 | (int) ((gg + half) / window) << 8 | (int) ((bb + half) / window);
        }
        for (int i = 0; i < n; i++) px[start + i * stride] = tmp[i];
    }

    // ── layout ───────────────────────────────────────────────────────────

    private static Layout build(TextStyle style) {
        Font font = fontFor(style);
        if (style.letterSpacing != 0f) {
            // Java tracks in fractions of the font size (the style's spacing is in canvas units), and only
            // honours it when it is part of the font itself, not as a separate text attribute.
            Map<TextAttribute, Object> tracking = new java.util.HashMap<>();
            tracking.put(TextAttribute.TRACKING, style.letterSpacing / Math.max(1f, style.fontSize));
            font = font.deriveFont(tracking);
        }

        String text = style.text == null ? "" : style.text.replace("\r\n", "\n").replace('\r', '\n');
        // The outline sits outside the letters, so the margin grows with it (and wrapping leaves room for it).
        // iOS: the margin also covers the shadow's reach and the box's padding.
        float shadowReach = style.hasShadow() ? style.shadowBlur + Math.max(Math.abs(style.shadowOffsetX), Math.abs(style.shadowOffsetY)) : 0f;
        float boxPad = style.hasBackground() ? style.backgroundPadding : 0f;
        float pad = (float) Math.ceil(PAD + style.outlineWidth + shadowReach + boxPad);
        float wrap = style.maxWidth <= 0 ? Float.MAX_VALUE
                : style.wrapExcludesMargin ? Math.max(1f, style.maxWidth) : Math.max(1f, style.maxWidth - 2 * pad);

        List<TextLayout> layouts = new ArrayList<>();
        List<String> paragraphOf = new ArrayList<>();
        List<Integer> startOf = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                layouts.add(null); // an empty line keeps the line height
                paragraphOf.add("");
                startOf.add(0);
                continue;
            }
            AttributedString attributed = new AttributedString(paragraph);
            attributed.addAttribute(TextAttribute.FONT, font);
            LineBreakMeasurer measurer = new LineBreakMeasurer(attributed.getIterator(), FRC);
            while (measurer.getPosition() < paragraph.length()) {
                startOf.add(measurer.getPosition());
                paragraphOf.add(paragraph);
                layouts.add(measurer.nextLayout(wrap));
            }
        }
        if (layouts.isEmpty()) {
            layouts.add(null);
            paragraphOf.add("");
            startOf.add(0);
        }

        float emptyHeight = Math.max(1f, font.getLineMetrics("Ag", FRC).getHeight() + style.lineSpacing);
        float maxAdvance = 0f;
        for (TextLayout l : layouts) {
            if (l != null) maxAdvance = Math.max(maxAdvance, l.getVisibleAdvance());
        }

        float shift = style.align == TextStyle.ALIGN_CENTER ? 0.5f : style.align == TextStyle.ALIGN_RIGHT ? 1f : 0f;
        List<Line> lines = new ArrayList<>();
        float y = pad;
        for (int li = 0; li < layouts.size(); li++) {
            TextLayout l = layouts.get(li);
            if (l == null) {
                y += emptyHeight;
                continue;
            }
            float baseline = y + l.getAscent();
            float pitch = Math.max(1f, l.getAscent() + l.getDescent() + l.getLeading() + style.lineSpacing);
            lines.add(new Line(l, pad + (maxAdvance - l.getVisibleAdvance()) * shift, baseline,
                    paragraphOf.get(li), startOf.get(li), l.getAscent(), pitch));
            y += pitch;
        }
        y -= style.lineSpacing; // spacing goes BETWEEN lines, not after the last one
        return new Layout(Math.max(1f, maxAdvance) + 2 * pad, Math.max(1f, y - pad) + 2 * pad, lines,
                style.colorArgb, style.outlineWidth, style.outlineColorArgb, pad, style);
    }

    // ── units ────────────────────────────────────────────────────────────

    /**
     * Cuts the text into characters (grapheme clusters: a letter and its accents stay together), words
     * (runs between whitespace) or lines. Each unit is a vertical slice of its line: neighbouring units
     * meet exactly (halfway across the gap, for words) and the first and last reach the box edge, so the
     * slices tile the whole block and drawing every unit in place reproduces the whole-block bitmap pixel
     * for pixel. Spaces are never units. Slices of consecutive lines meet halfway too.
     */
    private static List<Unit> splitUnits(Layout layout, String mode) {
        List<Unit> out = new ArrayList<>();
        boolean chars = UNIT_CHARACTER.equals(mode), words = UNIT_WORD.equals(mode), whole = UNIT_LINE.equals(mode);
        if (!chars && !words && !whole) return out;

        List<Line> lines = layout.lines;
        for (int li = 0; li < lines.size(); li++) {
            Line line = lines.get(li);
            if (line.layout == null) continue;
            float top = li == 0 ? 0f : line.baselineY - line.ascent;
            float bottom = li == lines.size() - 1 ? layout.height : lines.get(li + 1).baselineY - lines.get(li + 1).ascent;
            if (bottom <= top) bottom = top + line.pitch;

            // Logical ranges [from, to) inside this line (indices into the line's own text).
            int count = line.layout.getCharacterCount();
            String text = line.paragraph.substring(line.start, line.start + count);
            List<int[]> ranges = new ArrayList<>();
            if (whole) {
                if (!text.trim().isEmpty()) ranges.add(new int[]{0, count});
            } else {
                java.text.BreakIterator clusters = java.text.BreakIterator.getCharacterInstance();
                clusters.setText(text);
                int wordStart = -1, wordEnd = -1;
                for (int a = clusters.first(), b = clusters.next(); b != java.text.BreakIterator.DONE; a = b, b = clusters.next()) {
                    boolean space = text.substring(a, b).trim().isEmpty();
                    if (chars) {
                        if (!space) ranges.add(new int[]{a, b});
                    } else if (space) {
                        if (wordStart >= 0) ranges.add(new int[]{wordStart, wordEnd});
                        wordStart = -1;
                    } else {
                        if (wordStart < 0) wordStart = a;
                        wordEnd = b;
                    }
                }
                if (wordStart >= 0) ranges.add(new int[]{wordStart, wordEnd});
            }
            if (ranges.isEmpty()) continue;

            // Each range's horizontal extent (its advance box), in text-box coordinates.
            int n = ranges.size();
            float[] x0 = new float[n], x1 = new float[n];
            for (int i = 0; i < n; i++) {
                java.awt.geom.Rectangle2D r = line.layout.getLogicalHighlightShape(ranges.get(i)[0], ranges.get(i)[1]).getBounds2D();
                x0[i] = line.x + (float) r.getMinX();
                x1[i] = line.x + (float) r.getMaxX();
            }
            // Tile left to right (right-to-left text is not in logical order on screen): meet halfway.
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            java.util.Arrays.sort(order, (p, q) -> Float.compare(x0[p], x0[q]));
            float[] left = new float[n], right = new float[n];
            for (int k = 0; k < n; k++) {
                int i = order[k];
                left[i] = k == 0 ? 0f : (x1[order[k - 1]] + x0[i]) / 2f;
                right[i] = k == n - 1 ? layout.width : (x1[i] + x0[order[k + 1]]) / 2f;
            }
            for (int i = 0; i < n; i++) {
                out.add(new Unit(left[i], top, Math.max(1f, right[i] - left[i]), bottom - top));
            }
        }
        return out;
    }

    // ── fonts ────────────────────────────────────────────────────────────

    /** Imported font files, loaded once. A file that fails to load is remembered as null so it isn't retried every frame. */
    private static final Map<String, Font> FILE_FONTS = new java.util.HashMap<>();

    /** The font for a style: an imported file if one is set and loads, otherwise the named family (Java falls back to a default if it is unknown). */
    private static Font fontFor(TextStyle style) {
        int flags = (style.bold ? Font.BOLD : 0) | (style.italic ? Font.ITALIC : 0);
        Font base = style.fontFile != null ? fileFont(style.fontFile) : null;
        if (base != null) return base.deriveFont(flags, Math.max(1f, style.fontSize));
        Font byPostScript = postScriptFont(style.fontFamily);
        if (byPostScript != null) return byPostScript.deriveFont(flags, Math.max(1f, style.fontSize));
        return new Font(style.fontFamily, flags, 12).deriveFont(Math.max(1f, style.fontSize));
    }

    private static final java.util.Set<String> LOGICAL = new java.util.HashSet<>(
            java.util.Arrays.asList("Dialog", "DialogInput", "Monospaced", "SansSerif", "Serif"));
    private static volatile java.util.Set<String> installedFamilies;
    private static volatile Map<String, Font> postScriptNames;

    /**
     * iOS stores a font by its PostScript name ("HelveticaNeue-Bold"), desktop by family. A name that is not a
     * family but is an installed font's PostScript name resolves to that font; otherwise null (use as a family).
     */
    private static Font postScriptFont(String name) {
        if (name == null || LOGICAL.contains(name)) return null;
        java.util.Set<String> families = installedFamilies;
        if (families == null) {
            families = new java.util.HashSet<>(java.util.Arrays.asList(
                    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            installedFamilies = families;
        }
        if (families.contains(name)) return null;
        Map<String, Font> byName = postScriptNames;
        if (byName == null) {
            byName = new java.util.HashMap<>();
            for (Font f : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts()) {
                byName.putIfAbsent(f.getPSName(), f);
            }
            postScriptNames = byName;
        }
        return byName.get(name);
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
