#version 330 core
// The JSON filter ops of an effect (animations/effects/*.json "filters"), one fullscreen pass each over a canvas-sized picture.
// A port of Android's EffectLayerRenderer shaders (GLSL ES 1.00 -> 330) with the same maths, so an effect looks the same on
// every platform. GlCompositor.runEffect decides which pass runs when; this file only draws one.
//
// Orientation: vUV.u runs right and vUV.v runs UP (the canvas top is v = 1), like every offscreen layer of the compositor.
// Pictures are opaque (a clip's own layer is handled as the colour it shows over black, see GlCompositor.runEffect).
in vec2 vUV;
uniform sampler2D uTexture;     // the picture being filtered
uniform sampler2D uTexture2;    // OP_ADD: the softened highlights; OP_MIX: the picture before the chain; OP_OUTLINE: the picture before the effect
uniform int uOp;
uniform vec2 uRes;              // canvas size in pixels

uniform float uAlpha;           // OP_MIX: how much of uTexture shows over uTexture2
uniform float uBrightness;      // OP_COLOR
uniform float uContrast;        // OP_COLOR
uniform float uSaturation;      // OP_COLOR
uniform float uMode;            // OP_PHOTO: 0 noir, 1 chrome, 2 fade, 3 instant, 4 mono, 5 process, 6 tonal, 7 transfer
uniform float uIntensity;       // OP_SEPIA, OP_VIGNETTE
uniform float uRadius;          // OP_VIGNETTE (fraction), OP_TWIRL / OP_BUMP (pixels)
uniform vec2 uCell;             // OP_PIXEL: cell size in texture units
uniform vec2 uCenter;           // OP_DOT: in texture units
uniform float uAngle;           // OP_DOT, OP_TWIRL, OP_KALEIDO: radians
uniform float uWidth;           // OP_DOT: dot pitch in pixels
uniform float uSharpness;       // OP_DOT
uniform float uAmount;          // OP_ADD
uniform float uScale;           // OP_BUMP
uniform float uCount;           // OP_KALEIDO
uniform float uShift;           // OP_RGB: the red offset in texture units
uniform vec3 uBand;             // OP_SLICE: v from .x to .y moved sideways by .z texture units
uniform float uS;               // OP_VHS: amount 0..2
uniform float uLine;            // OP_VHS: scanline width in pixels
uniform vec2 uSeed;             // OP_VHS
uniform vec2 uGrid;             // OP_TILE: cols, rows
uniform vec2 uCrop;             // OP_TILE: how much of the picture one cell shows (u, v extents about the middle)
out vec4 fragColor;

const int OP_COPY = 0, OP_COLOR = 1, OP_PHOTO = 2, OP_SEPIA = 3, OP_VIGNETTE = 4, OP_PIXEL = 5, OP_DOT = 6, OP_BRIGHT = 7,
          OP_ADD = 8, OP_TWIRL = 9, OP_BUMP = 10, OP_KALEIDO = 11, OP_RGB = 12, OP_SLICE = 13, OP_CROSS = 14, OP_VHS = 15,
          OP_TILE = 16, OP_OUTLINE = 17, OP_MIX = 18;

float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

// CIPhotoEffect*: hand-tuned colour curves in the spirit of each Core Image preset (Core Image's exact tables are not public).
vec3 photo(vec3 c) {
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    vec3 rgb = c;
    if (uMode < 0.5) {
        float g = clamp((l - 0.5) * 1.55 + 0.46, 0.0, 1.0);
        g = g * g * (3.0 - 2.0 * g) * 0.35 + g * 0.65;
        rgb = vec3(g);
    } else if (uMode < 1.5) {
        rgb = mix(vec3(l), rgb, 1.2);
        rgb = (rgb - 0.5) * 1.12 + 0.5;
        rgb *= vec3(1.03, 1.0, 0.95);
    } else if (uMode < 2.5) {
        rgb = mix(vec3(l), rgb, 0.8);
        rgb = rgb * 0.82 + 0.1;
    } else if (uMode < 3.5) {
        rgb = mix(vec3(l), rgb, 1.1);
        rgb = rgb * vec3(1.06, 1.0, 0.86) + vec3(0.03, 0.02, 0.05);
        rgb = (rgb - 0.5) * 0.95 + 0.5;
    } else if (uMode < 4.5) {
        rgb = vec3(l * 0.92 + 0.04);
    } else if (uMode < 5.5) {
        rgb = mix(vec3(l), rgb, 1.15);
        rgb *= vec3(0.93, 1.0, 1.1);
        rgb = (rgb - 0.5) * 1.08 + 0.5;
    } else if (uMode < 6.5) {
        rgb = vec3(clamp((l - 0.5) * 0.9 + 0.5, 0.0, 1.0));
    } else {
        rgb = mix(vec3(l), rgb, 1.1);
        rgb *= vec3(1.1, 1.0, 0.88);
        rgb = (rgb - 0.5) * 1.05 + 0.5;
    }
    return clamp(rgb, 0.0, 1.0);
}

void main() {
    vec4 c = texture(uTexture, vUV);

    if (uOp == OP_COPY) {                       // the picture as it is, made opaque
        fragColor = vec4(c.rgb, 1.0);
    } else if (uOp == OP_MIX) {                 // the chain's result over the picture before it
        fragColor = vec4(mix(texture(uTexture2, vUV).rgb, c.rgb, clamp(uAlpha, 0.0, 1.0)), 1.0);
    } else if (uOp == OP_OUTLINE) {             // a clip's own layer: the effect's colour inside the clip's own outline, premultiplied
        float a = texture(uTexture2, vUV).a;
        fragColor = vec4(min(c.rgb, vec3(a)), a);
    } else if (uOp == OP_COLOR) {               // CIColorControls: saturation about the luma, contrast about mid grey, brightness added
        float l = dot(c.rgb, vec3(0.299, 0.587, 0.114));
        vec3 rgb = mix(vec3(l), c.rgb, uSaturation);
        rgb = (rgb - 0.5) * uContrast + 0.5 + uBrightness;
        fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
    } else if (uOp == OP_PHOTO) {
        fragColor = vec4(photo(c.rgb), c.a);
    } else if (uOp == OP_SEPIA) {
        vec3 s = vec3(dot(c.rgb, vec3(0.393, 0.769, 0.189)),
                      dot(c.rgb, vec3(0.349, 0.686, 0.168)),
                      dot(c.rgb, vec3(0.272, 0.534, 0.131)));
        fragColor = vec4(mix(c.rgb, clamp(s, 0.0, 1.0), clamp(uIntensity, 0.0, 1.0)), c.a);
    } else if (uOp == OP_VIGNETTE) {            // darkens toward the corners (an ellipse on a non-square canvas, like CIVignette)
        float d = length((vUV - 0.5) * 2.0) * 0.7071;
        float k = d * (1.4 / max(uRadius, 0.05));
        float dark = smoothstep(0.35, 1.05, k);
        float f = 1.0 - clamp(uIntensity * dark * 0.8, 0.0, 1.0);
        fragColor = vec4(c.rgb * f, c.a);
    } else if (uOp == OP_PIXEL) {
        vec2 uv = (floor(vUV / uCell) + 0.5) * uCell;
        fragColor = texture(uTexture, min(uv, vec2(1.0)));
    } else if (uOp == OP_DOT) {                 // CIDotScreen: a rotated sine grid thresholded against the picture's brightness
        vec2 q = (vUV - uCenter) * uRes;
        float s = sin(uAngle);
        float co = cos(uAngle);
        vec2 r = vec2(co * q.x + s * q.y, -s * q.x + co * q.y);
        float k = 3.14159265 / max(uWidth, 1.0);
        float pattern = sin(r.x * k) * sin(r.y * k) * 4.0;
        float avg = (c.r + c.g + c.b) / 3.0;
        float g = clamp((avg * 10.0 - 5.0 + pattern) * (0.3 + uSharpness * 2.0) + 0.5, 0.0, 1.0);
        fragColor = vec4(vec3(g), c.a);
    } else if (uOp == OP_BRIGHT) {              // bloom, step 1: the highlights (what glows)
        fragColor = vec4(max(c.rgb - 0.55, 0.0) * 2.2, 1.0);
    } else if (uOp == OP_ADD) {                 // bloom, step 3: the picture plus its blurred highlights
        vec3 g = texture(uTexture2, vUV).rgb;
        fragColor = vec4(clamp(c.rgb + g * uAmount, 0.0, 1.0), c.a);
    } else if (uOp == OP_TWIRL) {               // CITwirlDistortion: rotated about the centre, most at the centre, nothing at the radius
        vec2 p = vUV * uRes;
        vec2 q = p - uRes * 0.5;
        float t = clamp(1.0 - length(q) / max(uRadius, 1.0), 0.0, 1.0);
        float a = uAngle * t * t;
        float s = sin(a);
        float co = cos(a);
        vec2 r = vec2(co * q.x - s * q.y, s * q.x + co * q.y);
        fragColor = texture(uTexture, clamp((r + uRes * 0.5) / uRes, 0.0, 1.0));
    } else if (uOp == OP_BUMP) {                // CIBumpDistortion: a bulge (scale > 0) or pinch (scale < 0) around the centre
        vec2 p = vUV * uRes;
        vec2 q = p - uRes * 0.5;
        float t = clamp(1.0 - length(q) / max(uRadius, 1.0), 0.0, 1.0);
        float f = clamp(1.0 - uScale * 0.5 * t * t, 0.05, 4.0);
        fragColor = texture(uTexture, clamp((q * f + uRes * 0.5) / uRes, 0.0, 1.0));
    } else if (uOp == OP_KALEIDO) {             // CIKaleidoscope: the picture folded into mirrored wedges around the centre
        vec2 q = vUV * uRes - uRes * 0.5;
        float len = length(q);
        float seg = 6.2831853 / max(uCount, 1.0);
        float a = atan(q.y, q.x) - uAngle;
        a = mod(a, seg);
        a = abs(a - seg * 0.5);
        a += uAngle;
        vec2 r = vec2(cos(a), sin(a)) * len;
        fragColor = texture(uTexture, clamp((r + uRes * 0.5) / uRes, 0.0, 1.0));
    } else if (uOp == OP_RGB) {                 // red one way, blue the other, green stays
        float r = texture(uTexture, vec2(clamp(vUV.x - uShift, 0.0, 1.0), vUV.y)).r;
        float b = texture(uTexture, vec2(clamp(vUV.x + uShift, 0.0, 1.0), vUV.y)).b;
        fragColor = vec4(r, c.g, b, c.a);
    } else if (uOp == OP_SLICE) {               // a horizontal band moved sideways: a torn scanline strip
        vec2 uv = vUV;
        if (uv.y >= uBand.x && uv.y <= uBand.y) uv.x = clamp(uv.x - uBand.z, 0.0, 1.0);
        fragColor = texture(uTexture, uv);
    } else if (uOp == OP_CROSS) {               // CIColorPolynomial, as the iOS cross-process look
        vec3 x = c.rgb;
        vec3 x2 = x * x;
        vec3 x3 = x2 * x;
        float r = 0.0 + 0.6 * x.r + 1.2 * x2.r - 0.8 * x3.r;
        float g = 0.0 + 0.9 * x.g + 0.3 * x2.g - 0.2 * x3.g;
        float b = 0.08 + 1.2 * x.b - 0.9 * x2.b + 0.6 * x3.b;
        fragColor = vec4(clamp(vec3(r, g, b), 0.0, 1.0), c.a);
    } else if (uOp == OP_VHS) {                 // the last two steps of the VHS look: dark scanlines, then grain that changes every step
        vec2 px = vUV * uRes;
        float stripe = mod(floor(px.y / uLine), 2.0);
        vec3 rgb = mix(c.rgb, vec3(0.0), (1.0 - stripe) * 0.22 * uS);
        float n = hash(floor(px) + uSeed);
        rgb = mix(rgb, vec3(n), 0.12 * uS);
        fragColor = vec4(rgb, c.a);
    } else if (uOp == OP_TILE) {                // tile_grid: the picture repeated cols x rows times, each copy cropped (not stretched)
        vec2 cell = fract(vUV * uGrid);
        fragColor = texture(uTexture, vec2(0.5) + (cell - vec2(0.5)) * uCrop);
    } else {
        fragColor = c;
    }
}
