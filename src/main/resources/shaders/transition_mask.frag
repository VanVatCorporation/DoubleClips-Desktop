#version 330 core
// A data-driven mask / crop transition (animations/transitions/*.json with "mode": "mask" or "crop"): wipes, circles, slices, clock, rect,
// distance, dissolve (noise). B shows where the mask is white, A where it is black; mode crop draws ONE picture (A before uCut, B after)
// through the shape on black. Same maths as iOS ClipCompositor.transitionMask + masked and Android's TransitionMaskRenderer, computed per
// pixel here (Core Image builds a mask picture; the mask value is a formula).
//
// Both inputs are the compositor's offscreen layers: premultiplied RGBA, canvas-sized, v UP (canvas top = v 1), so all lengths below are
// pixels with y UP, like Core Image. A mix of two premultiplied layers is a correct premultiplied layer.
in vec2 vUV;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform vec2 uRes;              // canvas size in pixels
uniform int uShape;             // 0 wipe, 1 circle, 2 noise, 3 slices, 4 clock, 5 rect, 6 distance
uniform float uReveal;          // 0..1, how much of B shows
uniform float uAngle;           // radians (wipe, slices)
uniform float uSoft;
uniform vec2 uCenter;           // circle centre, 0..1 from the left / TOP
uniform float uGrain;
uniform float uCount;
uniform float uStart;           // clock, radians clockwise from 12 o'clock
uniform int uFlag;              // wipe: normalized, circle: invert
uniform int uCrop;              // 1 = mode crop
uniform float uCut;
uniform float uProgress;
out vec4 fragColor;

float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

float maskValue(vec2 p) {
    float r = uReveal;
    float w = uRes.x;
    float h = uRes.y;
    vec2 c = uRes * 0.5;
    float fallback = r > 0.5 ? 1.0 : 0.0;
    if (uShape == 0) {                                  // wipe
        vec2 d = vec2(cos(uAngle), sin(uAngle));
        vec2 g = uFlag == 1 ? d / uRes : d;
        float denom = uFlag == 1 ? abs(d.x) + abs(d.y) : abs(w * d.x) + abs(h * d.y);
        float g2 = dot(g, g);
        if (denom <= 0.0 || g2 <= 0.0) return fallback;
        float halfSoft = max(uSoft * 0.5, 0.5 * sqrt(g2) / denom);
        float edge = -0.5 + r;
        float s = dot(p - c, g) / denom;
        return clamp((edge + halfSoft - s) / (2.0 * halfSoft), 0.0, 1.0);
    } else if (uShape == 1) {                           // circle
        vec2 center = vec2(w * uCenter.x, h * (1.0 - uCenter.y));
        vec2 far2 = max(center, uRes - center);
        float far = length(far2);
        float radius = far * (uFlag == 1 ? 1.0 - r : r);
        float soft = max(2.0, far * uSoft);
        float d = length(p - center);
        return uFlag == 1 ? clamp((d - radius) / soft, 0.0, 1.0) : clamp(1.0 - (d - radius) / soft, 0.0, 1.0);
    } else if (uShape == 2) {                           // noise
        float grain = max(1.0, uGrain);
        float n = hash(floor(p / grain));
        float gain = 1.0 / max(uSoft, 0.001);
        return clamp(gain * (n - (1.0 - r)), 0.0, 1.0);
    } else if (uShape == 3) {                           // slices
        float len = abs(w * cos(uAngle)) + abs(h * sin(uAngle));
        float count = max(1.0, floor(uCount + 0.5));
        float band = len / count;
        vec2 q = p - c;
        float u = q.x * cos(uAngle) + q.y * sin(uAngle) + len * 0.5;
        if (u < 0.0 || u >= len) return 0.0;
        float within = u - floor(u / band) * band;
        return clamp(band * r - within + 0.5, 0.0, 1.0);
    } else if (uShape == 4) {                           // clock
        vec2 q = p - c;
        float startAngle = 1.5707963 - uStart;
        float delta = mod(startAngle - atan(q.y, q.x), 6.2831853);
        float aa = 1.0 / max(length(q), 1.0);
        return clamp((6.2831853 * r - delta) / aa + 0.5, 0.0, 1.0);
    } else {                                            // rect: a centred rectangle that is `reveal` of the canvas in both directions
        vec2 q = abs(p - c) - vec2(w * r, h * r) * 0.5;
        float aa = max(uSoft * min(w, h), 1.0);
        return clamp(0.5 - max(q.x, q.y) / aa, 0.0, 1.0);
    }
}

// distance: B shows where the two pictures are close in colour first (mean channel difference below reveal)
float distanceValue(vec4 a, vec4 b) {
    vec3 d = abs(a.rgb - b.rgb);
    float gray = (d.r + d.g + d.b) / 3.0;
    return clamp((uReveal - gray) / max(uSoft, 0.001) + 0.5, 0.0, 1.0);
}

void main() {
    vec4 a = texture(uTextureA, vUV);
    vec4 b = texture(uTextureB, vUV);
    float m = 0.0;
    if (uReveal >= 1.0) m = 1.0;
    else if (uReveal > 0.0) m = uShape == 6 ? distanceValue(a, b) : maskValue(vUV * uRes);
    if (uCrop == 1) {
        vec4 pic = uProgress < uCut ? a : b;
        fragColor = vec4(pic.rgb * m, 1.0);              // premultiplied colour over opaque black
        return;
    }
    fragColor = mix(a, b, m);
}
