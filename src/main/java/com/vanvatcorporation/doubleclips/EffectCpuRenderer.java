package com.vanvatcorporation.doubleclips;

import java.awt.image.BufferedImage;

/**
 * The effects of {@link EffectCatalog} drawn on the CPU, one picture at a time. Same look as the GPU shaders in
 * GlCompositor (which are a port of these formulas): this is what the picker's looping tiles show and what the
 * tests check, so a tile is the real effect and not a stand-in.
 * <p>
 * Every effect is a pure function of (the picture so far, where in the effect we are): the same frame always
 * looks the same, so preview, scrubbing, export and tiles agree. The formulas follow the iOS editor's effect
 * renderer; Core Image's own filters (bloom, dot screen, vignette...) are replaced by plain definitions below,
 * so a look is close to iOS, not identical to the pixel.
 * <p>
 * Pictures are opaque RGB (the canvas is black behind everything), image coordinates with y pointing down.
 */
public final class EffectCpuRenderer {

    private EffectCpuRenderer() {}

    /** Opaque RGB, 0..1, row by row from the top. */
    public static final class Frame {
        public final int w, h;
        public final float[] rgb;

        public Frame(int w, int h) {
            this.w = w;
            this.h = h;
            this.rgb = new float[w * h * 3];
        }

        public static Frame of(BufferedImage image) {
            Frame f = new Frame(image.getWidth(), image.getHeight());
            for (int y = 0; y < f.h; y++) {
                for (int x = 0; x < f.w; x++) {
                    int p = image.getRGB(x, y);
                    int i = (y * f.w + x) * 3;
                    f.rgb[i] = ((p >> 16) & 0xFF) / 255f;
                    f.rgb[i + 1] = ((p >> 8) & 0xFF) / 255f;
                    f.rgb[i + 2] = (p & 0xFF) / 255f;
                }
            }
            return f;
        }

        public BufferedImage toImage() {
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = (y * w + x) * 3;
                    image.setRGB(x, y, (to8(rgb[i]) << 16) | (to8(rgb[i + 1]) << 8) | to8(rgb[i + 2]));
                }
            }
            return image;
        }

        /** Bilinear read at a position in pixel units (a pixel's centre is at +0.5), clamped to the picture's edge. */
        void sample(float x, float y, float[] out) {
            float fx = Math.max(0f, Math.min(w - 1f, x - 0.5f)), fy = Math.max(0f, Math.min(h - 1f, y - 0.5f));
            int x0 = (int) fx, y0 = (int) fy, x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
            float tx = fx - x0, ty = fy - y0;
            for (int c = 0; c < 3; c++) {
                float a = rgb[(y0 * w + x0) * 3 + c], b = rgb[(y0 * w + x1) * 3 + c];
                float d = rgb[(y1 * w + x0) * 3 + c], e = rgb[(y1 * w + x1) * 3 + c];
                out[c] = (a + (b - a) * tx) * (1 - ty) + (d + (e - d) * tx) * ty;
            }
        }
    }

    private static int to8(float v) {
        return Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }

    // ---- shared building blocks (the shaders repeat these exactly) ----------------------------------------

    static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    static float luma(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    static float smoothstep(float a, float b, float x) {
        float t = clamp01((x - a) / (b - a));
        return t * t * (3f - 2f * t);
    }

    static float fract(float v) {
        return v - (float) Math.floor(v);
    }

    /** Saturation about the luma, then brightness added, then contrast about 0.5. */
    static void colorControls(float[] c, float sat, float bright, float contrast) {
        float l = luma(c[0], c[1], c[2]);
        for (int i = 0; i < 3; i++) {
            float v = l + (c[i] - l) * sat + bright;
            c[i] = clamp01((v - 0.5f) * contrast + 0.5f);
        }
    }

    /** Fades the picture towards "dark edges": 0 at the centre, 1 in the corners; radius = how far the clear middle reaches. */
    static float vignetteFactor(float px, float py, int w, int h, float intensity, float radius) {
        float aspect = w / (float) h;
        float dx = (px / w - 0.5f) * aspect, dy = py / h - 0.5f;
        float d = (float) Math.sqrt(dx * dx + dy * dy) / (0.5f * (float) Math.sqrt(aspect * aspect + 1f));
        float v = smoothstep(0.4f * radius, 1.0f, d);
        return clamp01(1f - 0.5f * intensity * v);
    }

    static void cross(float[] c) {
        float r = c[0], g = c[1], b = c[2];
        c[0] = clamp01(0.6f * r + 1.2f * r * r - 0.8f * r * r * r);
        c[1] = clamp01(0.9f * g + 0.3f * g * g - 0.2f * g * g * g);
        c[2] = clamp01(0.08f + 1.2f * b - 0.9f * b * b + 0.6f * b * b * b);
    }

    static void noir(float[] c) {
        float g = clamp01((luma(c[0], c[1], c[2]) - 0.5f) * 1.35f + 0.5f - 0.03f);
        c[0] = c[1] = c[2] = g;
    }

    static void sepia(float[] c, float k) {
        float r = c[0], g = c[1], b = c[2];
        float sr = 0.393f * r + 0.769f * g + 0.189f * b, sg = 0.349f * r + 0.686f * g + 0.168f * b, sb = 0.272f * r + 0.534f * g + 0.131f * b;
        c[0] = clamp01(r + (sr - r) * k);
        c[1] = clamp01(g + (sg - g) * k);
        c[2] = clamp01(b + (sb - b) * k);
    }

    /** Noise for film grain; not a stable random, only has to look like grain. */
    static float hash(float x, float y) {
        float v = (float) Math.sin(x * 12.9898f + y * 78.233f) * 43758.5453f;
        return fract(v);
    }

    /** Same pseudo-random as the iOS editor: the same step and salt always give the same number in 0..1. */
    static float random(long step, double salt) {
        double x = Math.sin(step * 12.9898 + salt * 78.233) * 43758.5453;
        return (float) (x - Math.floor(x));
    }

    /**
     * The random numbers an effect uses, by salt, for the step of the moment (the pattern of glitch and VHS jumps 15
     * times a second, shake 30). Worked out here and handed to the shader, so CPU and GPU always agree on them.
     */
    public static float[] randoms(String style, float time) {
        long step = (long) Math.floor(time * ("shake".equals(style) ? 30.0 : 15.0));
        float[] r = new float[32];
        for (int salt = 0; salt < r.length; salt++) r[salt] = random(step, salt);
        return r;
    }

    /** The step number the VHS tracking line wanders by. */
    public static float wander(float time) {
        return fract((float) (Math.floor(time * 15.0) * 0.07));
    }

    // ---- separable Gaussian (blur, glow) ----------------------------------------------------------------------

    static float[] gaussianBlur(float[] src, int w, int h, float sigma) {
        if (sigma < 0.1f) return src.clone();
        int radius = Math.max(1, (int) Math.ceil(sigma * 3f));
        float[] kernel = new float[radius * 2 + 1];
        float sum = 0f;
        for (int i = -radius; i <= radius; i++) {
            kernel[i + radius] = (float) Math.exp(-(i * i) / (2.0 * sigma * sigma));
            sum += kernel[i + radius];
        }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= sum;
        float[] tmp = new float[src.length], out = new float[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 3; c++) {
                    float acc = 0f;
                    for (int k = -radius; k <= radius; k++) {
                        int xx = Math.max(0, Math.min(w - 1, x + k));
                        acc += src[(y * w + xx) * 3 + c] * kernel[k + radius];
                    }
                    tmp[(y * w + x) * 3 + c] = acc;
                }
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 3; c++) {
                    float acc = 0f;
                    for (int k = -radius; k <= radius; k++) {
                        int yy = Math.max(0, Math.min(h - 1, y + k));
                        acc += tmp[(yy * w + x) * 3 + c] * kernel[k + radius];
                    }
                    out[(y * w + x) * 3 + c] = acc;
                }
            }
        }
        return out;
    }

    // ---- the effect ---------------------------------------------------------------------------------------------

    private interface PerPixel {
        void at(float px, float py, float[] out);
    }

    private static Frame render(Frame src, PerPixel fn) {
        Frame out = new Frame(src.w, src.h);
        float[] c = new float[3];
        for (int y = 0; y < src.h; y++) {
            for (int x = 0; x < src.w; x++) {
                fn.at(x + 0.5f, y + 0.5f, c);
                int i = (y * src.w + x) * 3;
                out.rgb[i] = clamp01(c[0]);
                out.rgb[i + 1] = clamp01(c[1]);
                out.rgb[i + 2] = clamp01(c[2]);
            }
        }
        return out;
    }

    /** {@code effected} over {@code frame} at {@code amount} (0..1; from 1 up it is the effect alone). */
    private static void mix(float[] frame, float[] effected, float amount) {
        float k = Math.max(0f, Math.min(1f, amount));
        for (int i = 0; i < 3; i++) effected[i] = frame[i] + (effected[i] - frame[i]) * k;
    }

    /**
     * One picture through one effect. {@code progress} is how far through the effect clip we are (0..1),
     * {@code elapsed} seconds since the clip began, {@code time} the timeline time (it drives the jumpy ones).
     * An unknown style, or no frame, gives the picture back unchanged.
     */
    public static Frame apply(String style, float intensity, Frame src, float progress, float time, float elapsed) {
        if (src == null) return null;
        EffectCatalog.Style known = EffectCatalog.find(style);
        if (known == null) return src;
        final float a = Math.max(intensity, 0f);
        final float e = Math.max(elapsed, 0f);
        final float p = clamp01(progress);
        final int w = src.w, h = src.h;
        final float cx = w / 2f, cy = h / 2f;
        final float[] R = randoms(known.key, time);
        final float[] s = new float[3], s2 = new float[3], s3 = new float[3];

        switch (known.key) {
            case "glitch-pulse": {
                float pulse = 0.5f + 0.5f * (float) Math.sin(2 * Math.PI * 3 * e);
                float amount = (0.4f + 0.6f * pulse) * Math.min(a, 3f);
                float bright = 0.2f * Math.min(a, 1.5f);
                float dx = w * 0.012f * amount * (R[1] > 0.5f ? 1f : -1f);
                float[] top = new float[2], band = new float[2], shift = new float[2];
                for (int i = 0; i < 2; i++) {
                    float height = h * (0.02f + 0.08f * R[5 + i]);
                    float yUp = R[3 + i] * h * 0.85f;           // measured from the bottom, as on iOS
                    top[i] = h - yUp - height;
                    band[i] = height;
                    shift[i] = (R[7 + i] - 0.5f) * w * 0.14f * amount;
                }
                return render(src, (px, py, out) -> {
                    float sx = px;
                    for (int i = 1; i >= 0; i--) if (py >= top[i] && py < top[i] + band[i]) sx -= shift[i];
                    src.sample(sx - dx, py, s);
                    src.sample(sx, py, s2);
                    src.sample(sx + dx, py, s3);
                    out[0] = s[0] + bright;
                    out[1] = s2[1] + bright;
                    out[2] = s3[2] + bright;
                });
            }
            case "warp-zoom":
                return zoomed(src, 1f + 0.03f * a * e);
            case "lens-flare-surge": {
                float sat = 1f + 0.2f * Math.min(a, 2f), contrast = 1f + 0.5f * Math.min(a, 2f);
                return render(src, (px, py, out) -> {
                    src.sample(px, py, s);
                    System.arraycopy(s, 0, s2, 0, 3);
                    cross(s2);
                    colorControls(s2, sat, 0f, contrast);
                    mix(s, s2, a);
                    System.arraycopy(s2, 0, out, 0, 3);
                });
            }
            case "spin-burst": {
                float theta = (float) (2 * Math.PI * a * p);
                float cos = (float) Math.cos(theta), sin = (float) Math.sin(theta);
                return render(src, (px, py, out) -> {
                    float dx = px - cx, dy = py - cy;
                    float sx = cx + dx * cos + dy * sin, sy = cy - dx * sin + dy * cos;
                    if (sx < 0 || sx > w || sy < 0 || sy > h) {
                        out[0] = out[1] = out[2] = 0f; // corners the turn leaves empty are black
                    } else {
                        src.sample(sx, sy, out);
                    }
                });
            }
            case "shake": {
                float dx = (R[11] - 0.5f) * 2f * w * 0.012f * a, dy = (R[13] - 0.5f) * 2f * h * 0.012f * a;
                float rot = (R[17] - 0.5f) * 2f * 0.012f * a;
                float z = 1f + 0.03f * Math.min(a, 3f);
                float cos = (float) Math.cos(rot), sin = (float) Math.sin(rot);
                return render(src, (px, py, out) -> {
                    float vx = px - (cx + dx), vy = py - (cy + dy);
                    float rx = vx * cos + vy * sin, ry = -vx * sin + vy * cos;
                    src.sample(cx + rx / z, cy + ry / z, out);
                });
            }
            case "beat-pulse": {
                float phase = fract(e * 2f);
                return zoomed(src, 1f + 0.08f * a * (float) Math.exp(-6f * phase));
            }
            case "strobe": {
                boolean on = ((int) Math.floor(e * 10f)) % 2 == 0;
                float bright = (on ? 0.35f : -0.12f) * Math.min(a, 2f), contrast = on ? 1.2f : 1f;
                return render(src, (px, py, out) -> {
                    src.sample(px, py, out);
                    colorControls(out, 1f, bright, contrast);
                });
            }
            case "flash": {
                float decay = (float) Math.pow(1f - p, 3);
                float gain = (float) Math.pow(2.0, 2.5f * a * decay), bright = 0.3f * Math.min(a, 2f) * decay;
                return render(src, (px, py, out) -> {
                    src.sample(px, py, out);
                    for (int i = 0; i < 3; i++) out[i] = clamp01(out[i] * gain) + bright;
                });
            }
            case "rgb-shift": {
                float dx = w * 0.01f * a * (1f + 0.5f * (float) Math.sin(2 * Math.PI * 1.5 * e));
                return render(src, (px, py, out) -> {
                    src.sample(px - dx, py, s);
                    src.sample(px, py, s2);
                    src.sample(px + dx, py, s3);
                    out[0] = s[0];
                    out[1] = s2[1];
                    out[2] = s3[2];
                });
            }
            case "noir":
                return render(src, (px, py, out) -> {
                    src.sample(px, py, s);
                    System.arraycopy(s, 0, s2, 0, 3);
                    noir(s2);
                    mix(s, s2, a);
                    System.arraycopy(s2, 0, out, 0, 3);
                });
            case "vintage":
                return render(src, (px, py, out) -> {
                    src.sample(px, py, s);
                    System.arraycopy(s, 0, s2, 0, 3);
                    sepia(s2, 0.85f);
                    colorControls(s2, 0.9f, 0f, 1.06f);
                    float v = vignetteFactor(px, py, w, h, 0.9f, 1.5f);
                    for (int i = 0; i < 3; i++) s2[i] *= v;
                    mix(s, s2, a);
                    System.arraycopy(s2, 0, out, 0, 3);
                });
            case "vhs":
                return vhs(src, a, R, time);
            case "blur": {
                float sigma = h * 0.014f * a;
                if (sigma <= 0.1f) return src;
                Frame out = new Frame(w, h);
                float[] blurred = gaussianBlur(src.rgb, w, h, sigma);
                System.arraycopy(blurred, 0, out.rgb, 0, blurred.length);
                return out;
            }
            case "glow": {
                float sigma = h * 0.025f * a, k = Math.min(1.2f, 0.6f + 0.4f * a);
                float[] bright = new float[src.rgb.length];
                for (int i = 0; i < src.rgb.length; i += 3) {
                    float gate = clamp01((luma(src.rgb[i], src.rgb[i + 1], src.rgb[i + 2]) - 0.55f) / 0.45f);
                    for (int c = 0; c < 3; c++) bright[i + c] = src.rgb[i + c] * gate;
                }
                float[] halo = gaussianBlur(bright, w, h, sigma);
                Frame out = new Frame(w, h);
                for (int i = 0; i < src.rgb.length; i++) {
                    out.rgb[i] = clamp01(1f - (1f - src.rgb[i]) * (1f - clamp01(k * halo[i]))); // screen
                }
                return out;
            }
            case "vignette": {
                float intensity2 = 1.2f * Math.min(a, 2f);
                return render(src, (px, py, out) -> {
                    src.sample(px, py, out);
                    float v = vignetteFactor(px, py, w, h, intensity2, 1.4f);
                    for (int i = 0; i < 3; i++) out[i] *= v;
                });
            }
            case "pixelate": {
                float block = Math.max(2f, h * 0.012f * a);
                return render(src, (px, py, out) ->
                        src.sample((float) (Math.floor(px / block) + 0.5) * block, (float) (Math.floor(py / block) + 0.5) * block, out));
            }
            case "halftone": {
                float cell = Math.max(3f, h * 0.006f * a), angle = 0.5f;
                float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
                return render(src, (px, py, out) -> {
                    src.sample(px, py, out);
                    float l = luma(out[0], out[1], out[2]);
                    float dx = px - cx, dy = py - cy;
                    float u = dx * cos + dy * sin, v = -dx * sin + dy * cos;
                    float dot = 0.5f + 0.24f * ((float) Math.cos(2 * Math.PI * u / cell) + (float) Math.cos(2 * Math.PI * v / cell));
                    float ink = smoothstep(l - 0.04f, l + 0.04f, dot);
                    for (int i = 0; i < 3; i++) out[i] *= 1f - ink;
                });
            }
            case "kaleidoscope": {
                float rot = e * 0.5f * a, segment = (float) (2 * Math.PI / 6);
                return render(src, (px, py, out) -> {
                    float dx = px - cx, dy = py - cy;
                    float r = (float) Math.sqrt(dx * dx + dy * dy);
                    float ang = (float) Math.atan2(dy, dx) - rot;
                    float t = ang - segment * (float) Math.floor(ang / segment);
                    t = Math.abs(t - segment / 2f);
                    float source = t + rot;
                    src.sample(cx + r * (float) Math.cos(source), cy + r * (float) Math.sin(source), out);
                });
            }
            case "twirl": {
                float radius = Math.min(w, h) * 0.45f;
                float angle = 2f * a * (float) Math.sin(2 * Math.PI * 0.5 * e);
                return render(src, (px, py, out) -> {
                    float dx = px - cx, dy = py - cy;
                    float r = (float) Math.sqrt(dx * dx + dy * dy);
                    if (r >= radius) {
                        src.sample(px, py, out);
                        return;
                    }
                    float f = 1f - r / radius, theta = angle * f * f;
                    float cos = (float) Math.cos(theta), sin = (float) Math.sin(theta);
                    src.sample(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos, out);
                });
            }
            case "bulge": {
                float radius = Math.min(w, h) * 0.45f;
                float swell = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * 0.7 * e);
                float strength = Math.min(0.7f * a * swell, 0.9f);
                return render(src, (px, py, out) -> {
                    float dx = px - cx, dy = py - cy;
                    float r = (float) Math.sqrt(dx * dx + dy * dy);
                    if (r >= radius) {
                        src.sample(px, py, out);
                        return;
                    }
                    float f = 1f - r / radius;
                    float scale = 1f - strength * f * f;
                    src.sample(cx + dx * scale, cy + dy * scale, out);
                });
            }
            case "fade-in":
                return faded(src, p);
            case "fade-out":
                return faded(src, 1f - p);
            default:
                return src;
        }
    }

    private static Frame zoomed(Frame src, float z) {
        float cx = src.w / 2f, cy = src.h / 2f;
        return render(src, (px, py, out) -> src.sample(cx + (px - cx) / z, cy + (py - cy) / z, out));
    }

    private static Frame faded(Frame src, float visible) {
        float k = clamp01(visible);
        Frame out = new Frame(src.w, src.h);
        for (int i = 0; i < src.rgb.length; i++) out.rgb[i] = src.rgb[i] * k;
        return out;
    }

    /** Worn tape: flat colour, soft picture, colour fringing, a torn bottom strip and a wandering line, scanlines, grain. */
    private static Frame vhs(Frame src, float a, float[] R, float time) {
        final int w = src.w, h = src.h;
        final float s = Math.min(a, 2f);
        final float br = Math.max(0.5f, h * 0.0009f * s);
        final float dx = w * 0.004f * s;
        final float stripTop = h - h * 0.05f, stripHeight = h * 0.05f, stripShift = (R[21] - 0.5f) * w * 0.06f * s;
        final float lineHeight = h * 0.012f, lineTop = h - wander(time) * h - lineHeight, lineShift = (R[23] - 0.5f) * w * 0.03f * s;
        final float lineWidth = Math.max(1f, h / 540f);
        final float ox = R[25] * 700f, oy = R[27] * 700f;
        final float[] t = new float[3], c = new float[3];
        PerPixel base = (px, py, out) -> {
            // a little cross-shaped soften, then flatter colour
            float[] acc = new float[3];
            src.sample(px, py, t);
            for (int i = 0; i < 3; i++) acc[i] = 0.36f * t[i];
            float[][] offsets = {{-br, 0}, {br, 0}, {0, -br}, {0, br}};
            for (float[] o : offsets) {
                src.sample(px + o[0], py + o[1], t);
                for (int i = 0; i < 3; i++) acc[i] += 0.16f * t[i];
            }
            colorControls(acc, 0.88f, 0f, 1.05f);
            System.arraycopy(acc, 0, out, 0, 3);
        };
        return render(src, (px, py, out) -> {
            float sx = px;
            if (py >= stripTop && py < stripTop + stripHeight) sx -= stripShift;
            if (py >= lineTop && py < lineTop + lineHeight) sx -= lineShift;
            base.at(sx - dx, py, c);
            out[0] = c[0];
            base.at(sx, py, c);
            out[1] = c[1];
            base.at(sx + dx, py, c);
            out[2] = c[2];
            if (((int) Math.floor(py / lineWidth)) % 2 == 0) for (int i = 0; i < 3; i++) out[i] *= 1f - 0.22f * s;
            float grain = hash(px + ox, py + oy);
            for (int i = 0; i < 3; i++) out[i] = out[i] * (1f - 0.12f * s) + grain * 0.12f * s;
        });
    }
}
