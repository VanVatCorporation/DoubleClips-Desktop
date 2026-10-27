package com.vanvatcorporation.doubleclips;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

// ─────────────────────────────────────────────────────────────────────
//  Shared by the export worker and the live preview worker (extracted unchanged from
//  OpenGLExportWorker, so both render through exactly the same shaders and blending).
//  Must only be used on the thread that owns the current GL context.
//
//  GL33 core-profile compositor: one FBO sized to the output canvas, one
//  textured-quad shader, one texture per active clip (so a held frame or a
//  still image is uploaded once, not re-uploaded every output frame).
//  No depth buffer - clips are composited purely by track order + alpha
//  blending (painter's algorithm), like FFmpeg's overlay chain.
//  Two extra canvas-sized scratch FBOs (created on first use) serve transitions
//  (each side rendered to its own layer, then blended - see TRANSITION_FRAGMENT_SHADER)
//  and in-animation blur (a separable Gaussian, see BLUR_FRAGMENT_SHADER).
// ─────────────────────────────────────────────────────────────────────
final class GlCompositor implements OpenGLTimelineExporter.Compositor {

    private static final class GlLayer implements OpenGLTimelineExporter.Layer {
        final int texture;
        final int width, height;

        GlLayer(int texture, int width, int height) {
            this.texture = texture;
            this.width = width;
            this.height = height;
        }
    }

    // ---- clip masks (ClipMask / iOS EditingView+Mask.swift) --------------------------------------------
    // Drawn in the shader from signed distances, so there is no mask bitmap or blur pass: vQuadPos (-1..1) is the
    // clip's OWN scaled, still unrotated box, exactly where iOS applies the mask. Per mask: A = (centre x, y,
    // half width, half height) in box pixels, B = (cos, sin of the rotation, feather sigma px, corner radius px),
    // C = (shape, invert, mode). Shape ids follow ClipMask.Shape's order, mode ids ClipMask.Mode's.
    // A feather is a soft edge about 2 sigma each side of the outline (a Gaussian-blurred edge, as on iOS).
    // Masks combine in order: the first defines the area, later ones add (max), subtract or intersect.
    private static final int HEART_POINTS = 64;

    private static String heartPointsGlsl() {
        // The same curve iOS fills (x = 16 sin^3 t, y = 13cos t - 5cos 2t - 2cos 3t - cos 4t), in -1..1 units of the half extents.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < HEART_POINTS; i++) {
            double t = (double) i / HEART_POINTS * 2 * Math.PI;
            double x = 16 * Math.pow(Math.sin(t), 3);
            double y = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t);
            if (i > 0) sb.append(", ");
            sb.append(String.format(java.util.Locale.ROOT, "vec2(%.5f, %.5f)", x / 16.0, -(y + 2.6) / 14.4));
        }
        return sb.toString();
    }

    private static String starPointsGlsl() {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 10; k++) {
            double angle = -Math.PI / 2 + k * Math.PI / 5;
            double r = k % 2 == 0 ? 1.0 : 0.42;
            if (k > 0) sb.append(", ");
            sb.append(String.format(java.util.Locale.ROOT, "vec2(%.5f, %.5f)", Math.cos(angle) * r, Math.sin(angle) * r));
        }
        return sb.toString();
    }

    private static final String MASK_DECLS =
            "#define MAX_MASKS " + com.vanvatcorporation.doubleclips.data.editing.ClipMask.MAX_MASKS + "\n" +
            "uniform int uMaskCount;\n" +
            "uniform vec2 uBoxSize;\n" +
            "uniform vec4 uMaskA[MAX_MASKS];\n" +
            "uniform vec4 uMaskB[MAX_MASKS];\n" +
            "uniform ivec3 uMaskC[MAX_MASKS];\n" +
            "const vec2 HEART[" + HEART_POINTS + "] = vec2[" + HEART_POINTS + "](" + heartPointsGlsl() + ");\n" +
            "const vec2 STAR[10] = vec2[10](" + starPointsGlsl() + ");\n" +
            // signed distance (negative inside) to a closed polygon scaled by s
            "float sdHeart(vec2 p, vec2 s) {\n" +
            "    vec2 v0 = HEART[0] * s;\n" +
            "    float d = dot(p - v0, p - v0);\n" +
            "    float sgn = 1.0;\n" +
            "    for (int i = 0, j = " + (HEART_POINTS - 1) + "; i < " + HEART_POINTS + "; j = i, i++) {\n" +
            "        vec2 vi = HEART[i] * s, vj = HEART[j] * s;\n" +
            "        vec2 e = vj - vi, w = p - vi;\n" +
            "        vec2 b = w - e * clamp(dot(w, e) / dot(e, e), 0.0, 1.0);\n" +
            "        d = min(d, dot(b, b));\n" +
            "        bvec3 c = bvec3(p.y >= vi.y, p.y < vj.y, e.x * w.y > e.y * w.x);\n" +
            "        if (all(c) || all(not(c))) sgn = -sgn;\n" +
            "    }\n" +
            "    return sgn * sqrt(d);\n" +
            "}\n" +
            "float sdStar(vec2 p, vec2 s) {\n" +
            "    vec2 v0 = STAR[0] * s;\n" +
            "    float d = dot(p - v0, p - v0);\n" +
            "    float sgn = 1.0;\n" +
            "    for (int i = 0, j = 9; i < 10; j = i, i++) {\n" +
            "        vec2 vi = STAR[i] * s, vj = STAR[j] * s;\n" +
            "        vec2 e = vj - vi, w = p - vi;\n" +
            "        vec2 b = w - e * clamp(dot(w, e) / dot(e, e), 0.0, 1.0);\n" +
            "        d = min(d, dot(b, b));\n" +
            "        bvec3 c = bvec3(p.y >= vi.y, p.y < vj.y, e.x * w.y > e.y * w.x);\n" +
            "        if (all(c) || all(not(c))) sgn = -sgn;\n" +
            "    }\n" +
            "    return sgn * sqrt(d);\n" +
            "}\n" +
            "float maskCoverage(vec2 p) {\n" +
            "    float aa = max(0.75 * length(fwidth(p)), 0.25);\n" +
            "    float result = 1.0;\n" +
            "    for (int i = 0; i < uMaskCount; i++) {\n" +
            "        vec4 a = uMaskA[i];\n" +
            "        vec4 b = uMaskB[i];\n" +
            "        ivec3 c = uMaskC[i];\n" +
            "        vec2 d = p - a.xy;\n" +
            // into the mask's own frame (the shape's rotation undone; y is down, + is clockwise)
            "        vec2 l = vec2(d.x * b.x + d.y * b.y, -d.x * b.y + d.y * b.x);\n" +
            "        vec2 h = a.zw;\n" +
            "        float sd;\n" +
            "        if (c.x == 0) {\n" +                       // linear: the side the shape's \"up\" points to is kept
            "            sd = l.y;\n" +
            "        } else if (c.x == 1) {\n" +                // mirror: a band through the centre
            "            sd = abs(l.y) - h.y;\n" +
            "        } else if (c.x == 2) {\n" +                // circle / ellipse (first-order distance estimate)
            "            float k0 = length(l / h);\n" +
            "            float k1 = max(length(l / (h * h)), 1e-4);\n" +
            "            sd = k0 * (k0 - 1.0) / k1;\n" +
            "        } else if (c.x == 3) {\n" +                // rectangle with rounded corners
            "            vec2 q = abs(l) - (h - vec2(b.w));\n" +
            "            sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - b.w;\n" +
            "        } else if (c.x == 4) {\n" +
            "            sd = sdHeart(l, h);\n" +
            "        } else {\n" +
            "            sd = sdStar(l, h);\n" +
            "        }\n" +
            "        float s = max(2.0 * b.z, aa);\n" +
            "        float cov = 1.0 - smoothstep(-s, s, sd);\n" +
            "        if (c.y == 1) cov = 1.0 - cov;\n" +
            "        if (i == 0) result = cov;\n" +
            "        else if (c.z == 0) result = max(result, cov);\n" +
            "        else if (c.z == 1) result = result * (1.0 - cov);\n" +
            "        else result = result * cov;\n" +
            "    }\n" +
            "    return result;\n" +
            "}\n";

    private static final String VERTEX_SHADER =
            "#version 330 core\n" +
            "layout(location=0) in vec2 aPos;\n" +
            "layout(location=1) in vec2 aUV;\n" +
            "uniform mat4 uMvp;\n" +
            "out vec2 vUV;\n" +
            "out vec2 vQuadPos;\n" +
            "void main() {\n" +
            "    vQuadPos = aPos;\n" +
            "    gl_Position = uMvp * vec4(aPos, 0.0, 1.0);\n" +
            "    vUV = aUV;\n" +
            "}\n";

    // Same colour maths as Android OpenGLEditNative's fragment shaders (the
    // ImageTransformShader variant - a plain sampler2D, which is what a decoded
    // RGBA frame is here): hue rotates the chroma (U,V) plane, saturation scales
    // chroma magnitude, brightness offsets luma (uBrightness arrives in the app's
    // own -10..10 range, hence * 0.1), temperature is a red/blue gain around
    // 6500K. Kept line-for-line identical so the two renderers agree.
    // In-animation frame warp ("warp.*" channels of ClipAnimation) and contrast are
    // the same shader snippets as Android's UNFOLD_WARP_FRAGMENT_DECLS: an inverse
    // mapping with edge clamping, squeezing the clip's box toward its top-centre.
    // uUnfoldActive is 0 for every normal clip, which then samples vUV untouched.
    private static final String FRAGMENT_SHADER =
            "#version 330 core\n" +
            "in vec2 vUV;\n" +
            "in vec2 vQuadPos;\n" +
            MASK_DECLS +
            "uniform sampler2D uTex;\n" +
            "uniform float uOpacity;\n" +
            "uniform float uHueDegrees;\n" +
            "uniform float uSaturation;\n" +
            "uniform float uBrightness;\n" +
            "uniform float uTemperatureKelvin;\n" +
            "uniform float uUnfoldActive;\n" +
            "uniform float uUnfoldTopX;\n" +
            "uniform float uUnfoldBottomX;\n" +
            "uniform float uUnfoldHeight;\n" +
            "uniform float uContrast;\n" +
            "uniform float uExposure;\n" + // iOS "exposure" channel: colour multiplier, 1 = none
            "out vec4 fragColor;\n" +
            "vec2 unfoldSourceQuadPos(vec2 q) {\n" +
            "    float srcY = clamp((q.y + 1.0) / uUnfoldHeight - 1.0, -1.0, 1.0);\n" +
            // Edge width follows the OUTPUT row (q.y), not the source row - how the squish was measured.
            "    float edgeW = mix(uUnfoldTopX, uUnfoldBottomX, (q.y + 1.0) * 0.5);\n" +
            "    float srcX = clamp(q.x / edgeW, -1.0, 1.0);\n" +
            "    return vec2(srcX, srcY);\n" +
            "}\n" +
            "void main() {\n" +
            "    vec2 sampleUv = vUV;\n" +
            "    if (uUnfoldActive > 0.5) {\n" +
            "        vec2 s = unfoldSourceQuadPos(vQuadPos);\n" +
            // quad y=-1 (top) samples v=0, y=+1 (bottom) samples v=1 - see QUAD_VERTICES
            "        sampleUv = vec2(s.x * 0.5 + 0.5, s.y * 0.5 + 0.5);\n" +
            "    }\n" +
            "    vec4 color = texture(uTex, sampleUv);\n" +
            "    vec3 rgb = color.rgb;\n" +
            "    float y = dot(rgb, vec3(0.299, 0.587, 0.114));\n" +
            "    float u = dot(rgb, vec3(-0.14713, -0.28886, 0.43600));\n" +
            "    float v = dot(rgb, vec3(0.61500, -0.51499, -0.10001));\n" +
            "    float hueRad = radians(uHueDegrees);\n" +
            "    float cosH = cos(hueRad);\n" +
            "    float sinH = sin(hueRad);\n" +
            "    float u2 = (u * cosH - v * sinH) * uSaturation;\n" +
            "    float v2 = (u * sinH + v * cosH) * uSaturation;\n" +
            "    float y2 = clamp((y - 0.5) * uContrast + 0.5 + uBrightness * 0.1, 0.0, 1.0);\n" +
            "    rgb = vec3(\n" +
            "        y2 + 1.13983 * v2,\n" +
            "        y2 - 0.39465 * u2 - 0.58060 * v2,\n" +
            "        y2 + 2.03211 * u2\n" +
            "    );\n" +
            "    float tempNorm = clamp((uTemperatureKelvin - 6500.0) / 6500.0, -1.0, 1.0);\n" +
            "    rgb.r *= (1.0 + tempNorm * 0.3);\n" +
            "    rgb.b *= (1.0 - tempNorm * 0.3);\n" +
            "    rgb *= uExposure;\n" +
            "    float maskAlpha = uMaskCount > 0 ? maskCoverage((vQuadPos * 0.5 + 0.5) * uBoxSize) : 1.0;\n" +
            "    fragColor = vec4(clamp(rgb, 0.0, 1.0), color.a * uOpacity * maskAlpha);\n" +
            "}\n";

    // ---- transition blend: combines two full-canvas layers (no MVP - both inputs are
    // already canvas-sized and pre-positioned). Style ids match styleToId() below, which
    // must stay in sync with OpenGLEdit.SUPPORTED_TRANSITION_STYLES. Same maths as Android's
    // TransitionBlendShader (a best-effort match to FFmpeg's xfade of the same name).
    private static final int STYLE_FADE = 0, STYLE_WIPE_LEFT = 1, STYLE_WIPE_RIGHT = 2,
            STYLE_SLIDE_LEFT = 3, STYLE_SLIDE_RIGHT = 4, STYLE_SLIDE_UP = 5, STYLE_SLIDE_DOWN = 6,
            STYLE_CIRCLE_OPEN = 7, STYLE_CIRCLE_CLOSE = 8, STYLE_DIAG_TL = 9, STYLE_DIAG_BL = 10, STYLE_RADIAL = 11,
            STYLE_HL_SLICE = 12, STYLE_HR_SLICE = 13, STYLE_VU_SLICE = 14, STYLE_VD_SLICE = 15, STYLE_REVEAL_UP = 16,
            STYLE_FADE_BLACK = 17, STYLE_FADE_WHITE = 18, STYLE_FADE_GRAYS = 19, STYLE_DISSOLVE = 20,
            STYLE_CIRCLE_CROP = 21, STYLE_RECT_CROP = 22, STYLE_PIXELIZE = 23, STYLE_HBLUR = 24, STYLE_DISTANCE = 25;

    static int styleToId(String style) {
        switch (style) {
            case "fade": return STYLE_FADE;
            case "dissolve": return STYLE_DISSOLVE;
            case "circlecrop": return STYLE_CIRCLE_CROP;
            case "rectcrop": return STYLE_RECT_CROP;
            case "pixelize": return STYLE_PIXELIZE;
            case "hblur": return STYLE_HBLUR;
            case "distance": return STYLE_DISTANCE;
            case "circleopen": return STYLE_CIRCLE_OPEN;
            case "circleclose": return STYLE_CIRCLE_CLOSE;
            case "diagtl": return STYLE_DIAG_TL;
            case "diagbl": return STYLE_DIAG_BL;
            case "radial": return STYLE_RADIAL;
            case "hlslice": return STYLE_HL_SLICE;
            case "hrslice": return STYLE_HR_SLICE;
            case "vuslice": return STYLE_VU_SLICE;
            case "vdslice": return STYLE_VD_SLICE;
            case "revealup": return STYLE_REVEAL_UP;
            case "fadeblack": return STYLE_FADE_BLACK;
            case "fadewhite": return STYLE_FADE_WHITE;
            case "fadegrays": return STYLE_FADE_GRAYS;
            case "wipeleft": return STYLE_WIPE_LEFT;
            case "wiperight": return STYLE_WIPE_RIGHT;
            case "slideleft": return STYLE_SLIDE_LEFT;
            case "slideright": return STYLE_SLIDE_RIGHT;
            case "slideup": return STYLE_SLIDE_UP;
            case "slidedown": return STYLE_SLIDE_DOWN;
            default: return STYLE_FADE; // OpenGLEdit only produces styles from SUPPORTED_TRANSITION_STYLES
        }
    }

    private static final String FULLSCREEN_VERTEX_SHADER =
            "#version 330 core\n" +
            "layout(location=0) in vec2 aPos;\n" +
            "layout(location=1) in vec2 aUV;\n" +
            "out vec2 vUV;\n" +
            "void main() {\n" +
            "    gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "    vUV = aUV;\n" +
            "}\n";

    // ---- clip blend modes (ClipBlendMode / iOS EditingView+Blend.swift) -----------------------------------------
    // One fullscreen pass: the finished clip (a premultiplied scratch layer) is mixed with a copy of the main canvas.
    // The maths is ClipBlendMode.blend (the W3C compositing formulas); ids are ClipBlendMode's order:
    // 0 normal, 1 multiply, 2 screen, 3 overlay, 4 darken, 5 lighten, 6 colorDodge, 7 colorBurn, 8 softLight,
    // 9 hardLight, 10 difference, 11 exclusion, 12 add, 13 hue, 14 saturation, 15 color, 16 luminosity.
    // The canvas is opaque, so the result is too: (1 - a) * backdrop + a * blend(backdrop, clip colour).
    private static final String BLEND_FRAGMENT_SHADER =
            "#version 330 core\n" +
            "in vec2 vUV;\n" +
            "uniform sampler2D uLayer;\n" +
            "uniform sampler2D uBackdrop;\n" +
            "uniform int uMode;\n" +
            "out vec4 fragColor;\n" +
            "float lum(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }\n" +
            "float sat(vec3 c) { return max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b); }\n" +
            "vec3 clipColor(vec3 c) {\n" +
            "    float l = lum(c);\n" +
            "    float n = min(min(c.r, c.g), c.b);\n" +
            "    float x = max(max(c.r, c.g), c.b);\n" +
            "    if (n < 0.0) c = l + (c - l) * l / (l - n);\n" +
            "    if (x > 1.0) c = l + (c - l) * (1.0 - l) / (x - l);\n" +
            "    return c;\n" +
            "}\n" +
            "vec3 setLum(vec3 c, float l) { return clipColor(c + (l - lum(c))); }\n" +
            "vec3 setSat(vec3 c, float s) {\n" +
            "    float mx = max(max(c.r, c.g), c.b);\n" +
            "    float mn = min(min(c.r, c.g), c.b);\n" +
            "    return mx > mn ? (c - mn) * s / (mx - mn) : vec3(0.0);\n" +
            "}\n" +
            "float hardLight(float cb, float cs) {\n" +
            "    return cs <= 0.5 ? cb * 2.0 * cs : cb + (2.0 * cs - 1.0) - cb * (2.0 * cs - 1.0);\n" +
            "}\n" +
            "float channel(int mode, float cb, float cs) {\n" +
            "    if (mode == 1) return cb * cs;\n" +
            "    if (mode == 2) return cb + cs - cb * cs;\n" +
            "    if (mode == 3) return hardLight(cs, cb);\n" +
            "    if (mode == 4) return min(cb, cs);\n" +
            "    if (mode == 5) return max(cb, cs);\n" +
            "    if (mode == 6) return cb == 0.0 ? 0.0 : (cs >= 1.0 ? 1.0 : min(1.0, cb / (1.0 - cs)));\n" +
            "    if (mode == 7) return cb >= 1.0 ? 1.0 : (cs <= 0.0 ? 0.0 : 1.0 - min(1.0, (1.0 - cb) / cs));\n" +
            "    if (mode == 8) {\n" +
            "        if (cs <= 0.5) return cb - (1.0 - 2.0 * cs) * cb * (1.0 - cb);\n" +
            "        float d = cb <= 0.25 ? ((16.0 * cb - 12.0) * cb + 4.0) * cb : sqrt(cb);\n" +
            "        return cb + (2.0 * cs - 1.0) * (d - cb);\n" +
            "    }\n" +
            "    if (mode == 9) return hardLight(cb, cs);\n" +
            "    if (mode == 10) return abs(cb - cs);\n" +
            "    if (mode == 11) return cb + cs - 2.0 * cb * cs;\n" +
            "    return cs;\n" +
            "}\n" +
            "void main() {\n" +
            "    vec4 layer = texture(uLayer, vUV);\n" +      // premultiplied
            "    vec3 cb = texture(uBackdrop, vUV).rgb;\n" +
            "    float a = layer.a;\n" +
            "    if (a <= 0.0) { fragColor = vec4(cb, 1.0); return; }\n" +
            "    vec3 cs = clamp(layer.rgb / a, 0.0, 1.0);\n" +
            "    vec3 result;\n" +
            "    if (uMode == 12) {\n" +
            "        result = min(cb + layer.rgb, vec3(1.0));\n" +
            "    } else {\n" +
            "        vec3 b;\n" +
            "        if (uMode == 13) b = setLum(setSat(cs, sat(cb)), lum(cb));\n" +
            "        else if (uMode == 14) b = setLum(setSat(cb, sat(cs)), lum(cb));\n" +
            "        else if (uMode == 15) b = setLum(cs, lum(cb));\n" +
            "        else if (uMode == 16) b = setLum(cb, lum(cs));\n" +
            "        else if (uMode == 0) b = cs;\n" +
            "        else b = vec3(channel(uMode, cb.r, cs.r), channel(uMode, cb.g, cs.g), channel(uMode, cb.b, cs.b));\n" +
            "        result = (1.0 - a) * cb + a * b;\n" +
            "    }\n" +
            "    fragColor = vec4(result, 1.0);\n" +
            "}\n";

    // Styles 7..25 are the xfade / iOS ones (circleopen ... distance), worked out in the picture's own proportions.
    // Orientation: vUV.y = 0 is the BOTTOM row of the canvas and 1 the top (see QUAD_VERTICES), so "up" is +y.
    // Edges of the shaped reveals are anti-aliased with fwidth(); slices, radial and dissolve are hard-edged, like xfade.
    private static final String TRANSITION_FRAGMENT_SHADER =
            "#version 330 core\n" +
            "in vec2 vUV;\n" +
            "uniform sampler2D uTextureA;\n" +
            "uniform sampler2D uTextureB;\n" +
            "uniform float uProgress;\n" +
            "uniform int uStyle;\n" +
            "uniform float uAspect;\n" +       // canvas width / height
            "uniform vec2 uCanvas;\n" +        // canvas size in pixels
            "out vec4 fragColor;\n" +
            "float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }\n" +
            "vec3 gray(vec3 c, float s) { return mix(vec3(dot(c, vec3(0.299, 0.587, 0.114))), c, s); }\n" +
            // B shows where the signed value s is below the edge: 1 inside, 0 outside, soft over one pixel
            "float edge(float s, float limit) { float w = max(fwidth(s), 1e-5); return 1.0 - smoothstep(limit - w, limit + w, s); }\n" +
            "void main() {\n" +
            "    float p = uProgress;\n" +
            "    vec4 a = texture(uTextureA, vUV);\n" +
            "    vec4 b = texture(uTextureB, vUV);\n" +
            "    vec4 result;\n" +
            "    vec2 c = (vUV - 0.5) * vec2(uAspect, 1.0);\n" +          // centred, height 1: a true circle
            "    float reach = length(vec2(uAspect, 1.0) * 0.5) * 1.02;\n" + // centre to corner
            "    if (uStyle == 1) {\n" + // wipeleft: B revealed from the right edge moving left
            "        result = (vUV.x > 1.0 - p) ? b : a;\n" +
            "    } else if (uStyle == 2) {\n" + // wiperight: B revealed from the left edge moving right
            "        result = (vUV.x < p) ? b : a;\n" +
            "    } else if (uStyle >= 3 && uStyle <= 6) {\n" +
            // Push-slide: both layers move together as one strip; whichever offset lands in
            // [0,1] at this pixel is the one shown, the other is skipped entirely (not
            // clamped), so there is no smeared edge. axis = the side B comes from (+y is up).
            "        vec2 axis = (uStyle == 3) ? vec2(1.0, 0.0) : (uStyle == 4) ? vec2(-1.0, 0.0) : (uStyle == 5) ? vec2(0.0, -1.0) : vec2(0.0, 1.0);\n" +
            "        vec2 uvA = vUV + axis * p;\n" +
            "        vec2 uvB = vUV - axis * (1.0 - p);\n" +
            "        bool inA = uvA.x >= 0.0 && uvA.x <= 1.0 && uvA.y >= 0.0 && uvA.y <= 1.0;\n" +
            "        result = inA ? texture(uTextureA, uvA) : texture(uTextureB, uvB);\n" +
            "    } else if (uStyle == 7) {\n" + // circleopen: B grows from the centre
            "        result = mix(a, b, p <= 0.0 ? 0.0 : edge(length(c), p * reach));\n" +
            "    } else if (uStyle == 8) {\n" + // circleclose: A closes toward the centre, B outside
            "        result = mix(a, b, p >= 1.0 ? 1.0 : 1.0 - edge(length(c), (1.0 - p) * reach));\n" +
            "    } else if (uStyle == 9) {\n" + // diagtl: B from the top-left corner along the diagonal
            "        result = mix(a, b, edge(vUV.x + (1.0 - vUV.y), 2.0 * p));\n" +
            "    } else if (uStyle == 10) {\n" + // diagbl: B from the bottom-left corner
            "        result = mix(a, b, edge(vUV.x + vUV.y, 2.0 * p));\n" +
            "    } else if (uStyle == 11) {\n" + // radial: B swept in clockwise from 12 o'clock
            "        float t = fract(atan(c.x, c.y) / 6.2831853);\n" +
            "        result = (t < p) ? b : a;\n" +
            "    } else if (uStyle == 12) {\n" + // hlslice: ten vertical strips, B grows from each strip's left edge
            "        result = (fract(vUV.x * 10.0) < p) ? b : a;\n" +
            "    } else if (uStyle == 13) {\n" + // hrslice: ... from each strip's right edge
            "        result = (1.0 - fract(vUV.x * 10.0) < p) ? b : a;\n" +
            "    } else if (uStyle == 14) {\n" + // vuslice: ten horizontal strips, B grows upward from each strip's bottom
            "        result = (fract(vUV.y * 10.0) < p) ? b : a;\n" +
            "    } else if (uStyle == 15) {\n" + // vdslice: ... downward from each strip's top
            "        result = (fract((1.0 - vUV.y) * 10.0) < p) ? b : a;\n" +
            "    } else if (uStyle == 16) {\n" + // revealup: A slides up and away, B stays underneath
            "        vec2 uvA = vec2(vUV.x, vUV.y - p);\n" +
            "        result = uvA.y >= 0.0 ? texture(uTextureA, uvA) : b;\n" +
            "    } else if (uStyle == 17) {\n" + // fadeblack: A to black, hard cut at the middle, B up from black
            "        result = p < 0.5 ? vec4(a.rgb * (1.0 - 2.0 * p), a.a) : vec4(b.rgb * (2.0 * p - 1.0), b.a);\n" +
            "    } else if (uStyle == 18) {\n" + // fadewhite: the same through white
            "        result = p < 0.5 ? vec4(mix(a.rgb, vec3(1.0), 2.0 * p), a.a) : vec4(mix(vec3(1.0), b.rgb, 2.0 * p - 1.0), b.a);\n" +
            "    } else if (uStyle == 19) {\n" + // fadegrays: both lose colour toward the middle while B cross fades in
            "        vec3 ga = gray(a.rgb, 1.0 - min(1.0, 2.0 * p));\n" +
            "        vec3 gb = gray(b.rgb, max(0.0, 2.0 * p - 1.0));\n" +
            "        result = vec4(mix(ga, gb, p), mix(a.a, b.a, p));\n" +
            "    } else if (uStyle == 20) {\n" + // dissolve: every pixel flips from A to B at its own moment
            "        result = (hash(floor(gl_FragCoord.xy)) < p) ? b : a;\n" +
            "    } else if (uStyle == 21) {\n" + // circlecrop: A in a circle shrinking to nothing on black, then B in a growing one
            "        float r = abs(2.0 * p - 1.0);\n" +
            "        vec4 pic = (p < 0.5) ? a : b;\n" +
            "        float k = (r <= 0.0) ? 0.0 : (r >= 1.0) ? 1.0 : edge(length(c), r * reach / 1.02);\n" +
            "        result = mix(vec4(0.0, 0.0, 0.0, 1.0), pic, k);\n" +
            "    } else if (uStyle == 22) {\n" + // rectcrop: the same through a centred rectangle, r of the canvas in both directions
            "        float r = abs(2.0 * p - 1.0);\n" +
            "        vec4 pic = (p < 0.5) ? a : b;\n" +
            "        vec2 d = abs(vUV - 0.5) * 2.0;\n" +
            "        float k = (r <= 0.0) ? 0.0 : (r >= 1.0) ? 1.0 : edge(max(d.x, d.y), r);\n" +
            "        result = mix(vec4(0.0, 0.0, 0.0, 1.0), pic, k);\n" +
            "    } else if (uStyle == 23) {\n" + // pixelize: A breaks into growing square blocks (0 -> 7% of the width), hard cut at 0.5, B's blocks shrink back
            "        float frac = (p < 0.5) ? 0.07 * 2.0 * p : 0.07 * (2.0 - 2.0 * p);\n" +
            "        float block = frac * uCanvas.x;\n" +
            "        vec2 uv = vUV;\n" +
            "        if (block > 1.5) uv = min((floor(vUV * uCanvas / block) + 0.5) * block / uCanvas, vec2(1.0));\n" +
            "        result = (p < 0.5) ? texture(uTextureA, uv) : texture(uTextureB, uv);\n" +
            "    } else if (uStyle == 24) {\n" + // hblur: A smears sideways (streak 0 -> 30% of the width, smooth), cut at 0.5, B comes out of the same blur
            "        float s = (p < 0.5) ? smoothstep(0.0, 1.0, 2.0 * p) : 1.0 - smoothstep(0.0, 1.0, 2.0 * p - 1.0);\n" +
            "        float halfLen = 0.3 * s * 0.5;\n" +   // half the streak, as a fraction of the width
            "        const int TAPS = 32;\n" +
            "        float jitter = (fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5) * 2.0 / float(TAPS - 1);\n" +
            "        vec4 sum = vec4(0.0);\n" +
            "        for (int i = 0; i < TAPS; i++) {\n" +
            "            float t = float(i) / float(TAPS - 1) * 2.0 - 1.0 + jitter;\n" +
            "            vec2 uv = vec2(clamp(vUV.x + halfLen * t, 0.0, 1.0), vUV.y);\n" +
            "            sum += (p < 0.5) ? texture(uTextureA, uv) : texture(uTextureB, uv);\n" +
            "        }\n" +
            "        result = sum / float(TAPS);\n" +
            "    } else if (uStyle == 25) {\n" + // distance: B takes over first where the two pictures are close in colour (iOS distance mask, softness 0.04)
            "        float diff = (abs(a.r - b.r) + abs(a.g - b.g) + abs(a.b - b.b)) / 3.0;\n" +
            "        float m = (p <= 0.0) ? 0.0 : (p >= 1.0) ? 1.0 : clamp((p - diff) / 0.04 + 0.5, 0.0, 1.0);\n" +
            "        result = mix(a, b, m);\n" +
            "    } else {\n" + // fade
            "        result = mix(a, b, p);\n" +
            "    }\n" +
            "    fragColor = result;\n" +
            "}\n";

    // ---- "streak" look of one clip (iOS streakStack): edge fill, edge slide, squeeze, motion blur ----
    // Runs as one fullscreen pass over the canvas the clip was drawn into (premultiplied colour, transparent
    // outside the clip). For every output pixel it walks the chain BACKWARDS: motion-blur taps along the streak
    // (each clamped into the output extent, so the picture does not fade at its border), then the squeeze about its
    // anchor, then the slide, then a clamp into the picture's own box - that last clamp IS the edge pixels being
    // stretched into whatever gap the squeeze or slide leaves. uExtent is the whole canvas with edge fill, else the
    // picture's box, and nothing is output outside it. All coordinates are canvas uv (v = 0 at the bottom row of
    // the target, so "up" is +v). Input and output are PREMULTIPLIED, like every scratch layer (see composite blending below).
    private static final String STREAK_FRAGMENT_SHADER =
            "#version 330 core\n" +
            "in vec2 vUV;\n" +
            "uniform sampler2D uTexture;\n" +
            "uniform vec4 uBox;\n" +        // picture box to sample inside (u0, v0, u1, v1), half a texel inset
            "uniform vec4 uExtent;\n" +     // where output exists (u0, v0, u1, v1)
            "uniform vec2 uSlide;\n" +      // picture slide in uv
            "uniform float uSqueeze;\n" +   // horizontal squeeze, 1 = none
            "uniform float uAnchorU;\n" +   // u the squeeze holds still
            "uniform vec2 uHalfStreak;\n" + // half of the motion-blur streak in uv
            "uniform int uTaps;\n" +        // motion-blur taps (1 = none)
            "out vec4 fragColor;\n" +
            "vec2 clampTo(vec2 p, vec4 r) { return clamp(p, r.xy, r.zw); }\n" +
            "vec4 lookAt(vec2 q) {\n" +
            "    vec2 p = q;\n" +
            "    if (uSqueeze != 1.0) {\n" +
            "        p.x = uAnchorU + (p.x - uAnchorU) / uSqueeze;\n" +
            "        p = clampTo(p, uExtent);\n" +
            "    }\n" +
            "    if (uSlide != vec2(0.0)) {\n" +
            "        p = clampTo(p - uSlide, uExtent);\n" +
            "    }\n" +
            "    return texture(uTexture, clampTo(p, uBox));\n" +
            "}\n" +
            "void main() {\n" +
            "    vec2 q = vUV;\n" +
            "    if (q.x < uExtent.x || q.y < uExtent.y || q.x > uExtent.z || q.y > uExtent.w) {\n" +
            "        fragColor = vec4(0.0);\n" +
            "        return;\n" +
            "    }\n" +
            "    vec4 sum = vec4(0.0);\n" +
            // A long streak (Shake Slide reaches most of the canvas width) has taps many pixels apart, which would show as
            // ghost copies of sharp detail. Shifting every pixel's taps by up to half a spacing (interleaved gradient
            // noise) trades those steps for fine grain, which video compression then smooths away.
            "    float jitter = (uTaps > 1)\n" +
            "        ? (fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5) * 2.0 / float(uTaps - 1)\n" +
            "        : 0.0;\n" +
            "    for (int i = 0; i < uTaps; i++) {\n" +
            "        float t = (uTaps > 1) ? float(i) / float(uTaps - 1) * 2.0 - 1.0 + jitter : 0.0;\n" +
            "        sum += lookAt(clampTo(q + uHalfStreak * t, uExtent));\n" +
            "    }\n" +
            "    fragColor = sum / float(uTaps);\n" + // still premultiplied, like the layer it read
            "}\n";

    // ---- in-animation blur. Separable Gaussian, applied along uDirection only: call once
    // with (1,0), then again on its output with (0,1). 17 taps (centre + 8 per side) spaced
    // sigma/3 apart, so the kernel spans +-2.67 sigma; the weights are exp(-i^2/18) normalised
    // and give a true Gaussian of standard deviation uSigmaPixels for ANY sigma, from one
    // compiled shader. Identical weights to Android's GaussianBlurShader.
    private static final String BLUR_FRAGMENT_SHADER =
            "#version 330 core\n" +
            "in vec2 vUV;\n" +
            "uniform sampler2D uTexture;\n" +
            "uniform vec2 uDirection;\n" + // (1,0) horizontal pass, (0,1) vertical pass
            "uniform vec2 uTexelSize;\n" + // 1/width, 1/height
            "uniform float uSigmaPixels;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    vec2 step = uDirection * uTexelSize * (uSigmaPixels / 3.0);\n" +
            "    vec4 sum = texture(uTexture, vUV) * 0.133571;\n" +
            "    sum += (texture(uTexture, vUV + step * 1.0) + texture(uTexture, vUV - step * 1.0)) * 0.126353;\n" +
            "    sum += (texture(uTexture, vUV + step * 2.0) + texture(uTexture, vUV - step * 2.0)) * 0.106955;\n" +
            "    sum += (texture(uTexture, vUV + step * 3.0) + texture(uTexture, vUV - step * 3.0)) * 0.081015;\n" +
            "    sum += (texture(uTexture, vUV + step * 4.0) + texture(uTexture, vUV - step * 4.0)) * 0.054913;\n" +
            "    sum += (texture(uTexture, vUV + step * 5.0) + texture(uTexture, vUV - step * 5.0)) * 0.033306;\n" +
            "    sum += (texture(uTexture, vUV + step * 6.0) + texture(uTexture, vUV - step * 6.0)) * 0.018077;\n" +
            "    sum += (texture(uTexture, vUV + step * 7.0) + texture(uTexture, vUV - step * 7.0)) * 0.008779;\n" +
            "    sum += (texture(uTexture, vUV + step * 8.0) + texture(uTexture, vUV - step * 8.0)) * 0.003816;\n" +
            "    fragColor = sum;\n" +
            "}\n";

    // Unit quad (-1,-1)..(1,1); OpenGLEdit's model matrix scales/rotates/
    // translates it into the clip's on-canvas position and size. UV is NOT
    // flipped: uploads are top-row-first RGBA buffers, and OpenGLEdit's projection
    // already places canvas Y=0 at NDC +1, so local y=-1 (the quad's top after the
    // model matrix) samples V=0, the buffer's first/top row.
    private static final float[] QUAD_VERTICES = {
            // x, y,     u, v
            -1f, -1f,    0f, 0f,
             1f, -1f,    1f, 0f,
            -1f,  1f,    0f, 1f,
             1f,  1f,    1f, 1f,
    };

    private final int width, height;
    private final int fbo, colorTex;
    private final int program;
    private final int uMvpLoc, uOpacityLoc, uTexLoc, uHueLoc, uSaturationLoc, uBrightnessLoc, uTemperatureLoc;
    private final int uUnfoldActiveLoc, uUnfoldTopXLoc, uUnfoldBottomXLoc, uUnfoldHeightLoc, uContrastLoc, uExposureLoc,
            uMaskCountLoc, uBoxSizeLoc, uMaskALoc, uMaskBLoc, uMaskCLoc;
    private final int vao, vbo;

    /** A canvas-sized offscreen colour target (FBO + texture). */
    private static final class GlTarget {
        final int fbo, texture;
        GlTarget(int fbo, int texture) { this.fbo = fbo; this.texture = texture; }
    }

    // Created lazily on first use, so a project with no transitions or blur pays nothing for them.
    // 0, 1, 2: transitions, blur, blend and the effect clips' passes. 3: the layer of a clip that has its own effect
    // (a "clip group"). 4, 5, 6: that effect's own working copies, so they never touch the group while it is being filtered.
    private final GlTarget[] scratch = new GlTarget[7];
    private static final int GROUP_SLOT = 3, GROUP_FX_BASE = 4;
    /** Where "the main canvas" draws go: null = the real canvas; the clip group's layer while one is being built. */
    private GlTarget baseTarget = null;
    private GlTarget mainTarget;
    private int copyProgram = 0, uCopyTexLoc;
    private int transitionProgram = 0, blurProgram = 0, streakProgram = 0, blendProgram = 0;
    private int uBlendLayerLoc, uBlendBackdropLoc, uBlendModeLoc;
    private int uTransALoc, uTransBLoc, uTransProgressLoc, uTransStyleLoc, uTransAspectLoc, uTransCanvasLoc;
    private int uBlurTexLoc, uBlurDirLoc, uBlurTexelLoc, uBlurSigmaLoc;
    private int uStrTexLoc, uStrBoxLoc, uStrExtentLoc, uStrSlideLoc, uStrSqueezeLoc, uStrAnchorLoc, uStrHalfLoc, uStrTapsLoc;
    // Effect clips: one program for the whole catalog (resources/shaders/effects.frag), built on first use.
    private int effectProgram = 0;
    private boolean effectsUnavailable = false;
    private int uFxTexLoc, uFxTex2Loc, uFxStyleLoc, uFxPassLoc, uFxIntensityLoc, uFxProgressLoc, uFxTimeLoc, uFxElapsedLoc,
            uFxWanderLoc, uFxResolutionLoc, uFxRandLoc, uFxLayerLoc;

    GlCompositor(int width, int height) {
        this.width = width;
        this.height = height;

        colorTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        fbo = glGenFramebuffers();
        mainTarget = new GlTarget(fbo, colorTex);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex, 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("FBO incomplete: 0x" + Integer.toHexString(status)
                    + " (canvas " + width + "x" + height + " may exceed this GPU's limits)");
        }

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        uMvpLoc = glGetUniformLocation(program, "uMvp");
        uOpacityLoc = glGetUniformLocation(program, "uOpacity");
        uTexLoc = glGetUniformLocation(program, "uTex");
        uHueLoc = glGetUniformLocation(program, "uHueDegrees");
        uSaturationLoc = glGetUniformLocation(program, "uSaturation");
        uBrightnessLoc = glGetUniformLocation(program, "uBrightness");
        uTemperatureLoc = glGetUniformLocation(program, "uTemperatureKelvin");
        uUnfoldActiveLoc = glGetUniformLocation(program, "uUnfoldActive");
        uUnfoldTopXLoc = glGetUniformLocation(program, "uUnfoldTopX");
        uUnfoldBottomXLoc = glGetUniformLocation(program, "uUnfoldBottomX");
        uUnfoldHeightLoc = glGetUniformLocation(program, "uUnfoldHeight");
        uContrastLoc = glGetUniformLocation(program, "uContrast");
        uExposureLoc = glGetUniformLocation(program, "uExposure");
        uMaskCountLoc = glGetUniformLocation(program, "uMaskCount");
        uBoxSizeLoc = glGetUniformLocation(program, "uBoxSize");
        uMaskALoc = glGetUniformLocation(program, "uMaskA");
        uMaskBLoc = glGetUniformLocation(program, "uMaskB");
        uMaskCLoc = glGetUniformLocation(program, "uMaskC");

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexData = ByteBuffer.allocateDirect(QUAD_VERTICES.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(QUAD_VERTICES);
        vertexData.flip();
        glBufferData(GL_ARRAY_BUFFER, vertexData, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8);
        glEnableVertexAttribArray(1);

        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    }

    @Override
    public OpenGLTimelineExporter.Layer createLayer(int w, int h) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return new GlLayer(tex, w, h);
    }

    @Override
    public void destroyLayer(OpenGLTimelineExporter.Layer layer) {
        glDeleteTextures(((GlLayer) layer).texture);
    }

    @Override
    public void beginFrame() {
        baseTarget = null;
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, width, height);
        glClearColor(0f, 0f, 0f, 1f); // opaque black canvas base - the encoded output has no alpha anyway
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_BLEND);
        // Colour blends normally; alpha is kept opaque so the canvas never goes translucent.
        useStraightBlend();
    }

    @Override
    public void draw(OpenGLTimelineExporter.Layer layer, ByteBuffer newPixels, OpenGLEdit.DrawCommand cmd) {
        GlLayer gl = (GlLayer) layer;
        glUseProgram(program);
        glBindVertexArray(vao);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, gl.texture);
        if (newPixels != null) {
            ByteBuffer view = newPixels.duplicate();
            view.clear();
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, gl.width, gl.height, GL_RGBA, GL_UNSIGNED_BYTE, view);
        }
        glUniform1i(uTexLoc, 0);

        glUniformMatrix4fv(uMvpLoc, false, cmd.mvpMatrix);
        glUniform1f(uOpacityLoc, cmd.opacity);
        glUniform1f(uHueLoc, cmd.hueDegrees);
        glUniform1f(uSaturationLoc, cmd.saturation);
        glUniform1f(uBrightnessLoc, cmd.brightness);
        glUniform1f(uTemperatureLoc, cmd.temperatureKelvin);
        // Every draw MUST set these (GL defaults them to 0): 1,1,1 / contrast 1 = no warp.
        boolean unfoldActive = cmd.unfoldTopWidth != 1f || cmd.unfoldBottomWidth != 1f || cmd.unfoldHeight != 1f;
        glUniform1f(uUnfoldActiveLoc, unfoldActive ? 1f : 0f);
        glUniform1f(uUnfoldTopXLoc, cmd.unfoldTopWidth);
        glUniform1f(uUnfoldBottomXLoc, cmd.unfoldBottomWidth);
        glUniform1f(uUnfoldHeightLoc, cmd.unfoldHeight);
        glUniform1f(uContrastLoc, cmd.contrast);
        glUniform1f(uExposureLoc, cmd.exposure); // like the warp uniforms: every draw sets it (GL defaults it to 0 = black)
        // Clip masks: count 0 = the shader skips them (and every draw must say so, the program is shared by all clips).
        if (cmd.masks != null && cmd.masks.count > 0) {
            glUniform1i(uMaskCountLoc, cmd.masks.count);
            glUniform2f(uBoxSizeLoc, cmd.masks.boxW, cmd.masks.boxH);
            glUniform4fv(uMaskALoc, cmd.masks.a);
            glUniform4fv(uMaskBLoc, cmd.masks.b);
            glUniform3iv(uMaskCLoc, cmd.masks.c);
        } else {
            glUniform1i(uMaskCountLoc, 0);
        }

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    // ---- offscreen layers, transition blend, blur --------------------------------------

    private GlTarget scratchTarget(int slot) {
        if (scratch[slot] == null) {
            int tex = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, tex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            int f = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, f);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                throw new RuntimeException("Offscreen FBO incomplete: 0x" + Integer.toHexString(status));
            }
            scratch[slot] = new GlTarget(f, tex);
        }
        return scratch[slot];
    }

    /** Binds a scratch target and clears it to transparent (a layer to be blended, not a final frame). */
    private void bindScratchCleared(int slot) {
        GlTarget t = scratchTarget(slot);
        glBindFramebuffer(GL_FRAMEBUFFER, t.fbo);
        glViewport(0, 0, width, height);
        glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    private void bindMain() {
        bindTarget(baseTarget != null ? baseTarget : mainTarget);
    }

    private void bindTarget(GlTarget t) {
        glBindFramebuffer(GL_FRAMEBUFFER, t.fbo);
        glViewport(0, 0, width, height);
    }

    @Override
    public void beginOffscreen(int slot) {
        bindScratchCleared(slot);
    }

    @Override
    public void endOffscreen() {
        bindMain();
    }

    @Override
    public void blendTransition(int slotA, int slotB, float progress, String style) {
        transitionPass(slotA, slotB, progress, style, -1);
    }

    @Override
    public void blendTransitionToSlot(int slotA, int slotB, float progress, String style, int destSlot) {
        transitionPass(slotA, slotB, progress, style, destSlot);
    }

    /** destSlot < 0 = onto the main canvas (blended over it), else into that scratch canvas as it is (premultiplied). */
    private void transitionPass(int slotA, int slotB, float progress, String style, int destSlot) {
        if (transitionProgram == 0) {
            transitionProgram = buildProgram(FULLSCREEN_VERTEX_SHADER, TRANSITION_FRAGMENT_SHADER);
            uTransALoc = glGetUniformLocation(transitionProgram, "uTextureA");
            uTransBLoc = glGetUniformLocation(transitionProgram, "uTextureB");
            uTransProgressLoc = glGetUniformLocation(transitionProgram, "uProgress");
            uTransStyleLoc = glGetUniformLocation(transitionProgram, "uStyle");
            uTransAspectLoc = glGetUniformLocation(transitionProgram, "uAspect");
            uTransCanvasLoc = glGetUniformLocation(transitionProgram, "uCanvas");
        }
        if (destSlot < 0) {
            bindMain();
        } else {
            bindScratchCleared(destSlot);
            glDisable(GL_BLEND); // the layer is written as it is, not blended over its own clear colour
        }
        glUseProgram(transitionProgram);
        glBindVertexArray(vao);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, scratchTarget(slotA).texture);
        glUniform1i(uTransALoc, 0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, scratchTarget(slotB).texture);
        glUniform1i(uTransBLoc, 1);
        glUniform1f(uTransProgressLoc, progress);
        glUniform1i(uTransStyleLoc, styleToId(style));
        glUniform1f(uTransAspectLoc, (float) width / height);
        glUniform2f(uTransCanvasLoc, width, height);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glActiveTexture(GL_TEXTURE0); // leave unit 0 active: draw() assumes it
        if (destSlot >= 0) {
            glEnable(GL_BLEND);
            bindMain();
        }
    }

    @Override
    public void blendOntoMain(int sourceSlot, int backdropSlot, int mode) {
        if (blendProgram == 0) {
            blendProgram = buildProgram(FULLSCREEN_VERTEX_SHADER, BLEND_FRAGMENT_SHADER);
            uBlendLayerLoc = glGetUniformLocation(blendProgram, "uLayer");
            uBlendBackdropLoc = glGetUniformLocation(blendProgram, "uBackdrop");
            uBlendModeLoc = glGetUniformLocation(blendProgram, "uMode");
        }
        // Copy what the main canvas holds into the backdrop scratch canvas (the pass below reads it while writing the main one).
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, scratchTarget(backdropSlot).fbo);
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        bindMain();
        glDisable(GL_BLEND); // the shader writes the finished, opaque pixel
        glUseProgram(blendProgram);
        glBindVertexArray(vao);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, scratchTarget(sourceSlot).texture);
        glUniform1i(uBlendLayerLoc, 0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, scratchTarget(backdropSlot).texture);
        glUniform1i(uBlendBackdropLoc, 1);
        glUniform1i(uBlendModeLoc, mode);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glActiveTexture(GL_TEXTURE0); // leave unit 0 active: draw() assumes it
        glEnable(GL_BLEND);
        useStraightBlend();
    }

    @Override
    public void blurToSlot(int sourceSlot, int tempSlot, int destSlot, float sigmaPixels) {
        ensureBlurProgram();
        glUseProgram(blurProgram);
        glBindVertexArray(vao);
        glActiveTexture(GL_TEXTURE0);
        glUniform1i(uBlurTexLoc, 0);
        glUniform2f(uBlurTexelLoc, 1f / width, 1f / height);
        glUniform1f(uBlurSigmaLoc, sigmaPixels);
        glDisable(GL_BLEND); // both passes write the layer as it is (premultiplied)
        bindScratchCleared(tempSlot);
        glBindTexture(GL_TEXTURE_2D, scratchTarget(sourceSlot).texture);
        glUniform2f(uBlurDirLoc, 1f, 0f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        bindScratchCleared(destSlot);
        glBindTexture(GL_TEXTURE_2D, scratchTarget(tempSlot).texture);
        glUniform2f(uBlurDirLoc, 0f, 1f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glEnable(GL_BLEND);
        bindMain();
    }

    private void ensureBlurProgram() {
        if (blurProgram == 0) {
            blurProgram = buildProgram(FULLSCREEN_VERTEX_SHADER, BLUR_FRAGMENT_SHADER);
            uBlurTexLoc = glGetUniformLocation(blurProgram, "uTexture");
            uBlurDirLoc = glGetUniformLocation(blurProgram, "uDirection");
            uBlurTexelLoc = glGetUniformLocation(blurProgram, "uTexelSize");
            uBlurSigmaLoc = glGetUniformLocation(blurProgram, "uSigmaPixels");
        }
    }

    private void ensureStreakProgram() {
        if (streakProgram == 0) {
            streakProgram = buildProgram(FULLSCREEN_VERTEX_SHADER, STREAK_FRAGMENT_SHADER);
            uStrTexLoc = glGetUniformLocation(streakProgram, "uTexture");
            uStrBoxLoc = glGetUniformLocation(streakProgram, "uBox");
            uStrExtentLoc = glGetUniformLocation(streakProgram, "uExtent");
            uStrSlideLoc = glGetUniformLocation(streakProgram, "uSlide");
            uStrSqueezeLoc = glGetUniformLocation(streakProgram, "uSqueeze");
            uStrAnchorLoc = glGetUniformLocation(streakProgram, "uAnchorU");
            uStrHalfLoc = glGetUniformLocation(streakProgram, "uHalfStreak");
            uStrTapsLoc = glGetUniformLocation(streakProgram, "uTaps");
        }
    }

    // ---- compositing convention -------------------------------------------------------------
    // A clip drawn into a cleared scratch layer with normal alpha blending leaves PREMULTIPLIED colour there (rgb * a, a).
    // Every pass that reads such a layer (blur, streak) averages premultiplied texels, which is right; the last pass
    // must then put it on the canvas as premultiplied too (src factor ONE). Compositing it with SRC_ALPHA would apply the
    // alpha a second time and darken everything that is not fully opaque (a faded clip, a soft edge).

    private static void useStraightBlend() {
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void usePremultipliedBlend() {
        glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    }

    /** One motion-blur tap per this many pixels of streak (jitter hides what is left between taps)... */
    private static final float STREAK_TAP_SPACING_PIXELS = 3f;
    /** ...but never more than this many taps: a 1080p Shake Slide streak is ~1700 px long. */
    private static final int STREAK_MAX_TAPS = 63;

    @Override
    public void streak(int sourceSlot, int destSlot, OpenGLEdit.PostFx fx) {
        ensureStreakProgram();
        // Output extent: the whole canvas with edge fill, else the picture's own box.
        float[] extent = fx.edgeFill ? new float[]{0f, 0f, 1f, 1f}
                : new float[]{fx.boxMinU, fx.boxMinV, fx.boxMaxU, fx.boxMaxV};
        // Sample clamp: the picture's box, half a texel inside, so a clamped sample never mixes in the transparent texel outside it.
        float hu = 0.5f / width, hv = 0.5f / height;
        float[] inner = {fx.boxMinU + hu, fx.boxMinV + hv, fx.boxMaxU - hu, fx.boxMaxV - hv};
        if (inner[2] <= inner[0]) { inner[0] = fx.boxMinU; inner[2] = fx.boxMaxU; }
        if (inner[3] <= inner[1]) { inner[1] = fx.boxMinV; inner[3] = fx.boxMaxV; }
        float anchorU = extent[0] + fx.squeezeAnchor * (extent[2] - extent[0]);

        // Motion blur taps: odd, so one lands on the centre.
        float streakPx = fx.streakLengthFraction * width;
        int taps = 1;
        if (streakPx > 0.5f) {
            taps = Math.min(STREAK_MAX_TAPS, Math.max(9, (int) Math.ceil(streakPx / STREAK_TAP_SPACING_PIXELS))) | 1;
        }

        boolean toMain = destSlot < 0;
        if (toMain) {
            bindMain();
            usePremultipliedBlend();
        } else {
            bindScratchCleared(destSlot);
            glDisable(GL_BLEND); // write the layer as it is, not blended over its own clear colour
        }
        glUseProgram(streakProgram);
        glBindVertexArray(vao);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, scratchTarget(sourceSlot).texture);
        glUniform1i(uStrTexLoc, 0);
        glUniform4f(uStrBoxLoc, inner[0], inner[1], inner[2], inner[3]);
        glUniform4f(uStrExtentLoc, extent[0], extent[1], extent[2], extent[3]);
        glUniform2f(uStrSlideLoc, fx.slideU, fx.slideV);
        glUniform1f(uStrSqueezeLoc, fx.squeezeX);
        glUniform1f(uStrAnchorLoc, anchorU);
        glUniform2f(uStrHalfLoc, fx.streakU, fx.streakV);
        glUniform1i(uStrTapsLoc, taps);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        if (toMain) useStraightBlend(); else glEnable(GL_BLEND);
        bindMain();
    }

    // ---- effect clips ------------------------------------------------------------------------------

    private static final int EFFECT_BLUR = 10, EFFECT_GLOW = 11; // positions in EffectCatalog

    /** Builds the effect program once. A shader that won't compile here turns effects off for the run instead of ending it. */
    private boolean prepareEffects() {
        if (effectProgram != 0) return true;
        if (effectsUnavailable) return false;
        try {
            String source;
            try (java.io.InputStream in = GlCompositor.class.getResourceAsStream("/shaders/effects.frag")) {
                if (in == null) throw new IllegalStateException("shaders/effects.frag is missing from the app");
                source = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            effectProgram = buildProgram(FULLSCREEN_VERTEX_SHADER, source);
            uFxTexLoc = glGetUniformLocation(effectProgram, "uTexture");
            uFxTex2Loc = glGetUniformLocation(effectProgram, "uTexture2");
            uFxStyleLoc = glGetUniformLocation(effectProgram, "uStyle");
            uFxPassLoc = glGetUniformLocation(effectProgram, "uPass");
            uFxIntensityLoc = glGetUniformLocation(effectProgram, "uIntensity");
            uFxProgressLoc = glGetUniformLocation(effectProgram, "uProgress");
            uFxTimeLoc = glGetUniformLocation(effectProgram, "uTime");
            uFxElapsedLoc = glGetUniformLocation(effectProgram, "uElapsed");
            uFxWanderLoc = glGetUniformLocation(effectProgram, "uWander");
            uFxResolutionLoc = glGetUniformLocation(effectProgram, "uResolution");
            uFxRandLoc = glGetUniformLocation(effectProgram, "uRand");
            uFxLayerLoc = glGetUniformLocation(effectProgram, "uLayer");
            return true;
        } catch (RuntimeException | java.io.IOException e) {
            effectsUnavailable = true;
            System.err.println("LOG Effects are unavailable on this GPU, effect clips will be skipped: " + e.getMessage());
            return false;
        }
    }

    /** Runs a fullscreen pass of the effect program, reading scratch {@code sourceSlot} (and {@code secondSlot} if >= 0). */
    private void effectPass(int pass, int sourceSlot, int secondSlot) {
        glUseProgram(effectProgram);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, scratchTarget(sourceSlot).texture);
        if (secondSlot >= 0) {
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, scratchTarget(secondSlot).texture);
            glActiveTexture(GL_TEXTURE0);
        }
        glUniform1i(uFxPassLoc, pass);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    /** One separable Gaussian pass of the blur program: {@code from} scratch slot to the currently bound target. */
    private void gaussianPass(int fromSlot, float dx, float dy, float sigma) {
        glUseProgram(blurProgram);
        glActiveTexture(GL_TEXTURE0);
        glUniform1i(uBlurTexLoc, 0);
        glUniform2f(uBlurTexelLoc, 1f / width, 1f / height);
        glUniform1f(uBlurSigmaLoc, sigma);
        glUniform2f(uBlurDirLoc, dx, dy);
        glBindTexture(GL_TEXTURE_2D, scratchTarget(fromSlot).texture);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    /**
     * An effect clip: copy the picture so far, then redraw it through the effect over the main canvas, which it
     * REPLACES (blending is off for these passes). "blur" is two Gaussian passes; "glow" keeps the bright parts,
     * softens them with two Gaussian passes and adds them back with a screen blend; every other effect is one pass.
     * (tests/glcheck runs this exact pass order against the CPU renderer on a software GL.)
     */
    @Override
    public void applyEffect(String style, float intensity, float progress, float time, float elapsed) {
        applyEffectCore(mainTarget, 0, false, style, intensity, progress, time, elapsed);
    }

    /** The same effect on ONE clip's transparent layer in scratch canvas {@code slot} (premultiplied), in place. */
    @Override
    public void applyEffectToSlot(int slot, String style, float intensity, float progress, float time, float elapsed) {
        applyEffectCore(scratchTarget(slot), GROUP_FX_BASE, true, style, intensity, progress, time, elapsed);
        bindMain();
    }

    /**
     * The effect passes, reading and writing {@code dst}: {@code base}, {@code base + 1} and {@code base + 2} are the
     * scratch canvases the passes use as working copies. {@code layer}: dst holds one clip's transparent layer rather
     * than the opaque canvas (the shader then keeps the alpha and writes premultiplied colour).
     */
    private void applyEffectCore(GlTarget dst, int base, boolean layer, String style, float intensity, float progress,
                                 float time, float elapsed) {
        int index = EffectCatalog.indexOf(style);
        if (index < 0 || !prepareEffects()) return;
        float a = Math.max(intensity, 0f);

        glDisable(GL_BLEND);
        try {
            glBindVertexArray(vao);

            // 1. a copy of everything drawn so far (the effect reads it while it writes dst)
            GlTarget copy = scratchTarget(base);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, dst.fbo);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, copy.fbo);
            glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);

            if (index == EFFECT_BLUR) {
                float sigma = height * 0.014f * a;
                if (sigma <= 0.1f) return; // nothing to soften: dst is already what it should be
                ensureBlurProgram();
                glBindFramebuffer(GL_FRAMEBUFFER, scratchTarget(base + 1).fbo);
                glViewport(0, 0, width, height);
                gaussianPass(base, 1f, 0f, sigma);
                bindTarget(dst);
                gaussianPass(base + 1, 0f, 1f, sigma);
                return;
            }

            // the effect program's uniforms are the same for every pass
            glUseProgram(effectProgram);
            glUniform1i(uFxTexLoc, 0);
            glUniform1i(uFxTex2Loc, 1);
            glUniform1i(uFxStyleLoc, index);
            glUniform1i(uFxLayerLoc, layer ? 1 : 0);
            glUniform1f(uFxIntensityLoc, a);
            glUniform1f(uFxProgressLoc, progress);
            glUniform1f(uFxTimeLoc, time);
            glUniform1f(uFxElapsedLoc, elapsed);
            glUniform1f(uFxWanderLoc, EffectRandoms.wander(time));
            glUniform2f(uFxResolutionLoc, width, height);
            glUniform1fv(uFxRandLoc, EffectRandoms.randoms(style, time));

            if (index == EFFECT_GLOW) {
                float sigma = height * 0.025f * a;
                ensureBlurProgram();
                glBindFramebuffer(GL_FRAMEBUFFER, scratchTarget(base + 1).fbo);
                glViewport(0, 0, width, height);
                effectPass(1, base, -1);                         // bright parts -> base + 1
                glBindFramebuffer(GL_FRAMEBUFFER, scratchTarget(base + 2).fbo);
                glViewport(0, 0, width, height);
                gaussianPass(base + 1, 1f, 0f, sigma);           // soften sideways -> base + 2
                glBindFramebuffer(GL_FRAMEBUFFER, scratchTarget(base + 1).fbo);
                glViewport(0, 0, width, height);
                gaussianPass(base + 2, 0f, 1f, sigma);           // soften up and down -> base + 1
                bindTarget(dst);
                glUseProgram(effectProgram);
                effectPass(2, base, base + 1);                   // picture + glow -> dst
                return;
            }

            bindTarget(dst);
            effectPass(0, base, -1);
        } finally {
            glEnable(GL_BLEND); // draw() assumes it; beginFrame set it up once
            glUseProgram(program);
            glActiveTexture(GL_TEXTURE0);
        }
    }

    // ---- a clip with an effect of its own (a "clip group") -------------------------------------

    @Override
    public void beginClipGroup() {
        // Everything drawn to "the main canvas" from here goes into a transparent layer of its own (bindMain follows baseTarget).
        baseTarget = scratchTarget(GROUP_SLOT);
        bindScratchCleared(GROUP_SLOT);
        glEnable(GL_BLEND);
        useStraightBlend();
    }

    @Override
    public void endClipGroup(OpenGLEdit.EffectCommand fx, float time, int blendMode, boolean backdrop) {
        baseTarget = null;
        if (fx != null) applyEffectToSlot(GROUP_SLOT, fx.style, fx.intensity, fx.progress, time, fx.elapsed);
        bindMain();
        glEnable(GL_BLEND);
        if (blendMode != 0 && backdrop) {
            blendOntoMain(GROUP_SLOT, 0, blendMode);
            return;
        }
        // The layer holds premultiplied colour: onto the canvas with src factor ONE (see "compositing convention").
        if (copyProgram == 0) {
            copyProgram = buildProgram(FULLSCREEN_VERTEX_SHADER,
                    "#version 330 core\nin vec2 vUV;\nuniform sampler2D uTex;\nout vec4 fragColor;\n"
                  + "void main() { fragColor = texture(uTex, vUV); }\n");
            uCopyTexLoc = glGetUniformLocation(copyProgram, "uTex");
        }
        usePremultipliedBlend();
        glUseProgram(copyProgram);
        glBindVertexArray(vao);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, scratchTarget(GROUP_SLOT).texture);
        glUniform1i(uCopyTexLoc, 0);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        useStraightBlend();
    }

    @Override
    public void blurOntoMain(int sourceSlot, int tempSlot, float sigmaPixels) {
        ensureBlurProgram();
        glUseProgram(blurProgram);
        glBindVertexArray(vao);
        glActiveTexture(GL_TEXTURE0);
        glUniform1i(uBlurTexLoc, 0);
        glUniform2f(uBlurTexelLoc, 1f / width, 1f / height);
        glUniform1f(uBlurSigmaLoc, sigmaPixels);

        // Horizontal pass: source -> temp scratch, written as it is (premultiplied, see "compositing convention").
        bindScratchCleared(tempSlot);
        glDisable(GL_BLEND);
        glBindTexture(GL_TEXTURE_2D, scratchTarget(sourceSlot).texture);
        glUniform2f(uBlurDirLoc, 1f, 0f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glEnable(GL_BLEND);

        // Vertical pass: temp -> main canvas, blended over what is already there.
        bindMain();
        usePremultipliedBlend();
        glBindTexture(GL_TEXTURE_2D, scratchTarget(tempSlot).texture);
        glUniform2f(uBlurDirLoc, 0f, 1f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        useStraightBlend();
    }

    @Override
    public void readFrame(ByteBuffer out) {
        out.clear();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, out);
    }

    void close() {
        for (GlTarget t : scratch) {
            if (t != null) {
                glDeleteTextures(t.texture);
                glDeleteFramebuffers(t.fbo);
            }
        }
        if (transitionProgram != 0) glDeleteProgram(transitionProgram);
        if (blurProgram != 0) glDeleteProgram(blurProgram);
        if (streakProgram != 0) glDeleteProgram(streakProgram);
        if (blendProgram != 0) glDeleteProgram(blendProgram);
        if (effectProgram != 0) glDeleteProgram(effectProgram);
        glDeleteTextures(colorTex);
        glDeleteFramebuffers(fbo);
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteProgram(program);
    }

    private static int buildProgram(String vertexSrc, String fragmentSrc) {
        int vs = compileShader(GL_VERTEX_SHADER, vertexSrc);
        int fs = compileShader(GL_FRAGMENT_SHADER, fragmentSrc);
        int prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glLinkProgram(prog);
        if (glGetProgrami(prog, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(prog);
            glDeleteProgram(prog);
            throw new RuntimeException("Shader program link failed: " + log);
        }
        glDeleteShader(vs);
        glDeleteShader(fs);
        return prog;
    }

    private static int compileShader(int type, String src) {
        int shader = glCreateShader(type);
        glShaderSource(shader, src);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return shader;
    }
}
