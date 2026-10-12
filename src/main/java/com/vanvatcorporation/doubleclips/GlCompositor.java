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
            STYLE_SLIDE_LEFT = 3, STYLE_SLIDE_RIGHT = 4, STYLE_SLIDE_UP = 5, STYLE_SLIDE_DOWN = 6;

    static int styleToId(String style) {
        switch (style) {
            case "fade": case "dissolve": return STYLE_FADE; // dissolve approximated as a fade
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

    private static final String TRANSITION_FRAGMENT_SHADER =
            "#version 330 core\n" +
            "in vec2 vUV;\n" +
            "uniform sampler2D uTextureA;\n" +
            "uniform sampler2D uTextureB;\n" +
            "uniform float uProgress;\n" +
            "uniform int uStyle;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    vec4 result;\n" +
            "    if (uStyle == 1) {\n" + // wipeleft: B revealed from the right edge moving left
            "        result = (vUV.x > 1.0 - uProgress) ? texture(uTextureB, vUV) : texture(uTextureA, vUV);\n" +
            "    } else if (uStyle == 2) {\n" + // wiperight: B revealed from the left edge moving right
            "        result = (vUV.x < uProgress) ? texture(uTextureB, vUV) : texture(uTextureA, vUV);\n" +
            "    } else if (uStyle >= 3 && uStyle <= 6) {\n" +
            // Push-slide: both layers move together as one strip; whichever offset lands in
            // [0,1] at this pixel is the one shown, the other is skipped entirely (not
            // clamped), so there is no smeared edge.
            "        vec2 axis = (uStyle == 3) ? vec2(1.0, 0.0) : (uStyle == 4) ? vec2(-1.0, 0.0) : (uStyle == 5) ? vec2(0.0, 1.0) : vec2(0.0, -1.0);\n" +
            "        vec2 uvA = vUV + axis * uProgress;\n" +
            "        vec2 uvB = vUV - axis * (1.0 - uProgress);\n" +
            "        bool inA = uvA.x >= 0.0 && uvA.x <= 1.0 && uvA.y >= 0.0 && uvA.y <= 1.0;\n" +
            "        result = inA ? texture(uTextureA, uvA) : texture(uTextureB, uvB);\n" +
            "    } else {\n" + // fade / dissolve
            "        result = mix(texture(uTextureA, vUV), texture(uTextureB, vUV), uProgress);\n" +
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
    private final GlTarget[] scratch = new GlTarget[3]; // 0 and 1: transitions and blur; effects use all three
    private int transitionProgram = 0, blurProgram = 0, streakProgram = 0;
    private int uTransALoc, uTransBLoc, uTransProgressLoc, uTransStyleLoc;
    private int uBlurTexLoc, uBlurDirLoc, uBlurTexelLoc, uBlurSigmaLoc;
    private int uStrTexLoc, uStrBoxLoc, uStrExtentLoc, uStrSlideLoc, uStrSqueezeLoc, uStrAnchorLoc, uStrHalfLoc, uStrTapsLoc;
    // Effect clips: one program for the whole catalog (resources/shaders/effects.frag), built on first use.
    private int effectProgram = 0;
    private boolean effectsUnavailable = false;
    private int uFxTexLoc, uFxTex2Loc, uFxStyleLoc, uFxPassLoc, uFxIntensityLoc, uFxProgressLoc, uFxTimeLoc, uFxElapsedLoc,
            uFxWanderLoc, uFxResolutionLoc, uFxRandLoc;

    GlCompositor(int width, int height) {
        this.width = width;
        this.height = height;

        colorTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        fbo = glGenFramebuffers();
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
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
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
        if (transitionProgram == 0) {
            transitionProgram = buildProgram(FULLSCREEN_VERTEX_SHADER, TRANSITION_FRAGMENT_SHADER);
            uTransALoc = glGetUniformLocation(transitionProgram, "uTextureA");
            uTransBLoc = glGetUniformLocation(transitionProgram, "uTextureB");
            uTransProgressLoc = glGetUniformLocation(transitionProgram, "uProgress");
            uTransStyleLoc = glGetUniformLocation(transitionProgram, "uStyle");
        }
        bindMain();
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
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glActiveTexture(GL_TEXTURE0); // leave unit 0 active: draw() assumes it
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
     * softens them with two Gaussian passes and adds them back; every other effect is one pass.
     * (tests/glcheck runs this exact pass order against the CPU renderer on a software GL.)
     */
    @Override
    public void applyEffect(String style, float intensity, float progress, float time, float elapsed) {
        int index = EffectCatalog.indexOf(style);
        if (index < 0 || !prepareEffects()) return;
        float a = Math.max(intensity, 0f);

        glDisable(GL_BLEND);
        try {
            glBindVertexArray(vao);

            // 1. a copy of everything drawn so far (the effect reads it while it writes the canvas)
            GlTarget copy = scratchTarget(0);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, copy.fbo);
            glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);

            if (index == EFFECT_BLUR) {
                float sigma = height * 0.014f * a;
                if (sigma <= 0.1f) return; // nothing to soften: the canvas is already what it should be
                ensureBlurProgram();
                glBindFramebuffer(GL_FRAMEBUFFER, scratchTarget(1).fbo);
                glViewport(0, 0, width, height);
                gaussianPass(0, 1f, 0f, sigma);
                bindMain();
                gaussianPass(1, 0f, 1f, sigma);
                return;
            }

            // the effect program's uniforms are the same for every pass
            glUseProgram(effectProgram);
            glUniform1i(uFxTexLoc, 0);
            glUniform1i(uFxTex2Loc, 1);
            glUniform1i(uFxStyleLoc, index);
            glUniform1f(uFxIntensityLoc, a);
            glUniform1f(uFxProgressLoc, progress);
            glUniform1f(uFxTimeLoc, time);
            glUniform1f(uFxElapsedLoc, elapsed);
            glUniform1f(uFxWanderLoc, EffectCpuRenderer.wander(time));
            glUniform2f(uFxResolutionLoc, width, height);
            glUniform1fv(uFxRandLoc, EffectCpuRenderer.randoms(style, time));

            if (index == EFFECT_GLOW) {
                float sigma = height * 0.025f * a;
                ensureBlurProgram();
                glBindFramebuffer(GL_FRAMEBUFFER, scratchTarget(1).fbo);
                glViewport(0, 0, width, height);
                effectPass(1, 0, -1);                            // bright parts -> slot 1
                glBindFramebuffer(GL_FRAMEBUFFER, scratchTarget(2).fbo);
                glViewport(0, 0, width, height);
                gaussianPass(1, 1f, 0f, sigma);                  // soften sideways -> slot 2
                glBindFramebuffer(GL_FRAMEBUFFER, scratchTarget(1).fbo);
                glViewport(0, 0, width, height);
                gaussianPass(2, 0f, 1f, sigma);                  // soften up and down -> slot 1
                bindMain();
                glUseProgram(effectProgram);
                effectPass(2, 0, 1);                             // picture + glow -> canvas
                return;
            }

            bindMain();
            effectPass(0, 0, -1);
        } finally {
            glEnable(GL_BLEND); // draw() assumes it; beginFrame set it up once
            glUseProgram(program);
            glActiveTexture(GL_TEXTURE0);
        }
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
