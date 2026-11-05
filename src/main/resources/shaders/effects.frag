#version 330 core
// The LOOK of an effect: the "channels" of animations/effects/*.json (transform, colour, motion blur, edge slide) evaluated on the CPU
// into one ClipAnimationFrame and drawn here as one fullscreen pass over a copy of the picture drawn so far. Nothing about an
// individual effect lives in this file: the filter ops (blur, vhs, tile_grid...) are effect_ops.frag, and which effect does what is
// its JSON. GlCompositor.runEffect runs this pass, then the ops.
//
// The picture is opaque (a clip's own layer is handled by the caller as the colour it shows over black). Positions are in pixels with
// y pointing DOWN (row 0 = the canvas top), a pixel's centre at +0.5; the textures themselves are v UP (canvas top = v 1), which
// tap() and main() convert, so offsets, rotation and slides mean the same as on iOS and Android (+ = right / down, clockwise).
in vec2 vUV;
uniform sampler2D uTexture;     // the picture so far
uniform vec2 uResolution;       // canvas size in pixels
uniform vec4 uFrameT;           // scale about the canvas centre, offset x px, offset y px (+ = right / down), rotation radians
uniform vec4 uFrameC;           // exposure (multiplier), saturation, contrast, redTint
uniform vec4 uFrameM;           // motion-blur LENGTH px, its angle radians, edge slide x px, edge slide y px
uniform vec2 uFrameK;           // whiteMix, edgeFill (1 = the picture's edge pixels fill the canvas)
uniform vec4 uFrameG;           // hue (radians), brightness (-10..10: 0.1 per unit added), temperature offset (Kelvin), opacity
uniform int uFrameTaps;         // motion-blur taps (odd; 1 = no blur)
out vec4 fragColor;

float luma(vec3 c) { return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b; }

// saturation about the luma, then brightness added, then contrast about 0.5
vec3 colorControls(vec3 c, float sat, float bright, float contrast) {
    float l = luma(c);
    vec3 v = vec3(l) + (c - vec3(l)) * sat + vec3(bright);
    return clamp((v - 0.5) * contrast + 0.5, 0.0, 1.0);
}

// colour of the picture at the pixel position p (y down)
vec3 tap(vec2 p) {
    return texture(uTexture, vec2(p.x, uResolution.y - p.y) / uResolution).rgb;
}

// the picture at q as (rgb, coverage). Outside the canvas it is nothing (black) unless edge fill is on, which stretches the edge
// pixels outward instead.
vec4 frameTap(vec2 q) {
    if (uFrameK.y > 0.5) q = clamp(q, vec2(0.5), uResolution - vec2(0.5));
    else if (q.x < 0.0 || q.y < 0.0 || q.x > uResolution.x || q.y > uResolution.y) return vec4(0.0);
    return vec4(tap(q), 1.0);
}

void main() {
    vec2 p = vec2(vUV.x, 1.0 - vUV.y) * uResolution;
    vec2 c = uResolution / 2.0;

    // Where this pixel reads from: undo the slide, then the offset, rotation and scale (about the canvas centre).
    // Forward transform: p' = c + offset + scale * R(theta) * (q - c); R turns clockwise on screen (y points down).
    float cs = cos(uFrameT.w), sn = sin(uFrameT.w);
    vec2 d = (p - uFrameM.zw - c - uFrameT.yz) / max(uFrameT.x, 0.0001);
    vec2 q = c + vec2(d.x * cs + d.y * sn, -d.x * sn + d.y * cs);
    vec4 acc = vec4(0.0);
    int taps = max(uFrameTaps, 1);
    if (taps > 1) {
        vec2 dir = vec2(cos(uFrameM.y), -sin(uFrameM.y));      // the angle counts counter-clockwise on screen
        for (int i = 0; i < taps && i < 129; i++) {
            float t = (float(i) / float(taps - 1) - 0.5) * uFrameM.x;
            acc += frameTap(q + dir * t);
        }
        acc /= float(taps);
    } else {
        acc = frameTap(q);
    }
    vec3 o = acc.rgb;

    // colour like iOS: saturation + contrast (CIColorControls); hue, brightness and temperature as the clip shader does them
    o = colorControls(o, uFrameC.y, 0.0, uFrameC.z);
    if (uFrameG.x != 0.0) {
        float y = dot(o, vec3(0.299, 0.587, 0.114));
        float u = dot(o, vec3(-0.14713, -0.28886, 0.43600));
        float v = dot(o, vec3(0.61500, -0.51499, -0.10001));
        float cosH = cos(uFrameG.x), sinH = sin(uFrameG.x);
        float u2 = u * cosH - v * sinH;
        float v2 = u * sinH + v * cosH;
        o = vec3(y + 1.13983 * v2, y - 0.39465 * u2 - 0.58060 * v2, y + 2.03211 * u2);
    }
    o = clamp(o + vec3(uFrameG.y * 0.1), 0.0, 1.0);
    float tempNorm = clamp(uFrameG.z / 6500.0, -1.0, 1.0);
    o.r *= 1.0 + tempNorm * 0.3;
    o.b *= 1.0 - tempNorm * 0.3;
    // then exposure with the red cast and the wash toward white
    float keep = 1.0 - uFrameK.x;
    o = o * vec3(uFrameC.x * (1.0 + 0.15 * uFrameC.w) * keep,
                 uFrameC.x * (1.0 - 0.6 * uFrameC.w) * keep,
                 uFrameC.x * (1.0 - 0.6 * uFrameC.w) * keep) + vec3(uFrameK.x);
    // opacity fades the picture toward black (the picture is drawn over black, like iOS)
    o *= uFrameG.w;
    fragColor = vec4(clamp(o, 0.0, 1.0), 1.0);
}
