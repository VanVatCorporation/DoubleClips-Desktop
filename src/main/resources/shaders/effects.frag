#version 330 core
// The timeline effects (EffectCatalog), one fullscreen pass over a copy of the picture drawn so far.
// A port of EffectCpuRenderer: same formulas, same constants, so a tile in the picker is the real effect.
// Pictures are opaque; positions are in pixels with y pointing DOWN (row 0 = top), a pixel's centre at +0.5.
//
// uStyle is the effect's position in EffectCatalog. "blur" (10) is not drawn here: it is two passes of the
// compositor's Gaussian shader. "glow" (11) is four: uPass 1 keeps the bright parts, two Gaussian passes
// soften them, uPass 2 (this shader again) adds them back with a screen blend.
in vec2 vUV;
uniform sampler2D uTexture;     // the picture so far
uniform sampler2D uTexture2;    // glow: the softened bright parts
uniform int uStyle;
uniform int uPass;              // 0 = the effect; 1 = glow's bright-part pass; 2 = glow's combine pass
uniform float uIntensity;       // the strength setting
uniform float uProgress;        // 0..1 through the effect clip
uniform float uTime;            // timeline time, seconds
uniform float uElapsed;         // seconds since the effect clip began
uniform float uWander;          // vhs: where the tracking line is
uniform vec2 uResolution;       // canvas size in pixels
uniform float uRand[32];        // the step's random numbers, by salt (made on the CPU so CPU and GPU agree)
out vec4 fragColor;

const float PI = 3.14159265358979;

vec3 tap(vec2 p) { return texture(uTexture, p / uResolution).rgb; }

float luma(vec3 c) { return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b; }

float clamp01(float v) { return clamp(v, 0.0, 1.0); }

// saturation about the luma, then brightness added, then contrast about 0.5
vec3 colorControls(vec3 c, float sat, float bright, float contrast) {
    float l = luma(c);
    vec3 v = vec3(l) + (c - vec3(l)) * sat + vec3(bright);
    return clamp((v - 0.5) * contrast + 0.5, 0.0, 1.0);
}

float vignetteFactor(vec2 p, float intensity, float radius) {
    float aspect = uResolution.x / uResolution.y;
    vec2 d = vec2((p.x / uResolution.x - 0.5) * aspect, p.y / uResolution.y - 0.5);
    float dist = length(d) / (0.5 * sqrt(aspect * aspect + 1.0));
    float v = smoothstep(0.4 * radius, 1.0, dist);
    return clamp(1.0 - 0.5 * intensity * v, 0.0, 1.0);
}

vec3 crossProcess(vec3 c) {
    return clamp(vec3(0.6 * c.r + 1.2 * c.r * c.r - 0.8 * c.r * c.r * c.r,
                      0.9 * c.g + 0.3 * c.g * c.g - 0.2 * c.g * c.g * c.g,
                      0.08 + 1.2 * c.b - 0.9 * c.b * c.b + 0.6 * c.b * c.b * c.b), 0.0, 1.0);
}

vec3 noirOf(vec3 c) {
    float g = clamp01((luma(c) - 0.5) * 1.35 + 0.5 - 0.03);
    return vec3(g);
}

vec3 sepiaOf(vec3 c, float k) {
    vec3 s = vec3(0.393 * c.r + 0.769 * c.g + 0.189 * c.b,
                  0.349 * c.r + 0.686 * c.g + 0.168 * c.b,
                  0.272 * c.r + 0.534 * c.g + 0.131 * c.b);
    return clamp(c + (s - c) * k, 0.0, 1.0);
}

float grain(vec2 p) { return fract(sin(p.x * 12.9898 + p.y * 78.233) * 43758.5453); }

// vhs: a little cross-shaped soften, then flatter colour
vec3 vhsBase(vec2 p, float br) {
    vec3 acc = 0.36 * tap(p);
    acc += 0.16 * (tap(p + vec2(-br, 0.0)) + tap(p + vec2(br, 0.0)) + tap(p + vec2(0.0, -br)) + tap(p + vec2(0.0, br)));
    return colorControls(acc, 0.88, 0.0, 1.05);
}

vec3 zoomed(vec2 p, float z) {
    vec2 c = uResolution / 2.0;
    return tap(c + (p - c) / z);
}

void main() {
    vec2 p = vUV * uResolution;
    vec2 res = uResolution;
    vec2 c = res / 2.0;
    float a = max(uIntensity, 0.0);
    float e = max(uElapsed, 0.0);
    float pr = clamp01(uProgress);
    vec3 frame = tap(p);
    vec3 o = frame;

    if (uPass == 1) {                       // glow: keep the bright parts
        float gate = clamp01((luma(frame) - 0.55) / 0.45);
        fragColor = vec4(frame * gate, 1.0);
        return;
    }
    if (uPass == 2) {                       // glow: add the softened bright parts back (screen)
        float k = min(1.2, 0.6 + 0.4 * a);
        vec3 halo = texture(uTexture2, vUV).rgb;
        o = clamp(vec3(1.0) - (vec3(1.0) - frame) * (vec3(1.0) - clamp(k * halo, 0.0, 1.0)), 0.0, 1.0);
        fragColor = vec4(o, 1.0);
        return;
    }

    if (uStyle == 0) {                      // glitch-pulse
        float pulse = 0.5 + 0.5 * sin(2.0 * PI * 3.0 * e);
        float amount = (0.4 + 0.6 * pulse) * min(a, 3.0);
        float bright = 0.2 * min(a, 1.5);
        float dx = res.x * 0.012 * amount * (uRand[1] > 0.5 ? 1.0 : -1.0);
        float sx = p.x;
        for (int i = 1; i >= 0; i--) {
            float height = res.y * (0.02 + 0.08 * uRand[5 + i]);
            float yUp = uRand[3 + i] * res.y * 0.85;
            float top = res.y - yUp - height;
            float shift = (uRand[7 + i] - 0.5) * res.x * 0.14 * amount;
            if (p.y >= top && p.y < top + height) sx -= shift;
        }
        o = vec3(tap(vec2(sx - dx, p.y)).r + bright, tap(vec2(sx, p.y)).g + bright, tap(vec2(sx + dx, p.y)).b + bright);
    } else if (uStyle == 1) {               // warp-zoom
        o = zoomed(p, 1.0 + 0.03 * a * e);
    } else if (uStyle == 2) {               // lens-flare-surge
        float sat = 1.0 + 0.2 * min(a, 2.0);
        float contrast = 1.0 + 0.5 * min(a, 2.0);
        vec3 graded = colorControls(crossProcess(frame), sat, 0.0, contrast);
        o = frame + (graded - frame) * clamp01(a);
    } else if (uStyle == 3) {               // spin-burst
        float theta = 2.0 * PI * a * pr;
        float cs = cos(theta), sn = sin(theta);
        vec2 d = p - c;
        vec2 s = vec2(c.x + d.x * cs + d.y * sn, c.y - d.x * sn + d.y * cs);
        o = (s.x < 0.0 || s.x > res.x || s.y < 0.0 || s.y > res.y) ? vec3(0.0) : tap(s);
    } else if (uStyle == 4) {               // shake
        float dx = (uRand[11] - 0.5) * 2.0 * res.x * 0.012 * a;
        float dy = (uRand[13] - 0.5) * 2.0 * res.y * 0.012 * a;
        float rot = (uRand[17] - 0.5) * 2.0 * 0.012 * a;
        float z = 1.0 + 0.03 * min(a, 3.0);
        float cs = cos(rot), sn = sin(rot);
        vec2 v = p - (c + vec2(dx, dy));
        vec2 r = vec2(v.x * cs + v.y * sn, -v.x * sn + v.y * cs);
        o = tap(c + r / z);
    } else if (uStyle == 5) {               // beat-pulse
        float phase = fract(e * 2.0);
        o = zoomed(p, 1.0 + 0.08 * a * exp(-6.0 * phase));
    } else if (uStyle == 6) {               // strobe
        bool on = (int(floor(e * 10.0)) % 2) == 0;
        o = colorControls(frame, 1.0, (on ? 0.35 : -0.12) * min(a, 2.0), on ? 1.2 : 1.0);
    } else if (uStyle == 7) {               // flash
        float decay = pow(1.0 - pr, 3.0);
        float gain = pow(2.0, 2.5 * a * decay);
        o = clamp(frame * gain, 0.0, 1.0) + vec3(0.3 * min(a, 2.0) * decay);
    } else if (uStyle == 8) {               // rgb-shift
        float dx = res.x * 0.01 * a * (1.0 + 0.5 * sin(2.0 * PI * 1.5 * e));
        o = vec3(tap(vec2(p.x - dx, p.y)).r, frame.g, tap(vec2(p.x + dx, p.y)).b);
    } else if (uStyle == 9) {               // vhs
        float s = min(a, 2.0);
        float br = max(0.5, res.y * 0.0009 * s);
        float dx = res.x * 0.004 * s;
        float stripHeight = res.y * 0.05, stripTop = res.y - stripHeight;
        float stripShift = (uRand[21] - 0.5) * res.x * 0.06 * s;
        float lineHeight = res.y * 0.012, lineTop = res.y - uWander * res.y - lineHeight;
        float lineShift = (uRand[23] - 0.5) * res.x * 0.03 * s;
        float lineWidth = max(1.0, res.y / 540.0);
        float sx = p.x;
        if (p.y >= stripTop && p.y < stripTop + stripHeight) sx -= stripShift;
        if (p.y >= lineTop && p.y < lineTop + lineHeight) sx -= lineShift;
        o = vec3(vhsBase(vec2(sx - dx, p.y), br).r, vhsBase(vec2(sx, p.y), br).g, vhsBase(vec2(sx + dx, p.y), br).b);
        if ((int(floor(p.y / lineWidth)) % 2) == 0) o *= 1.0 - 0.22 * s;
        float n = grain(p + vec2(uRand[25] * 700.0, uRand[27] * 700.0));
        o = o * (1.0 - 0.12 * s) + vec3(n * 0.12 * s);
    } else if (uStyle == 12) {              // vignette
        o = frame * vignetteFactor(p, 1.2 * min(a, 2.0), 1.4);
    } else if (uStyle == 13) {              // noir
        o = frame + (noirOf(frame) - frame) * clamp01(a);
    } else if (uStyle == 14) {              // vintage
        vec3 aged = colorControls(sepiaOf(frame, 0.85), 0.9, 0.0, 1.06) * vignetteFactor(p, 0.9, 1.5);
        o = frame + (aged - frame) * clamp01(a);
    } else if (uStyle == 15) {              // pixelate
        float block = max(2.0, res.y * 0.012 * a);
        o = tap((floor(p / block) + 0.5) * block);
    } else if (uStyle == 16) {              // halftone
        float cell = max(3.0, res.y * 0.006 * a);
        float angle = 0.5;
        float cs = cos(angle), sn = sin(angle);
        vec2 d = p - c;
        float u = d.x * cs + d.y * sn, v = -d.x * sn + d.y * cs;
        float dotv = 0.5 + 0.24 * (cos(2.0 * PI * u / cell) + cos(2.0 * PI * v / cell));
        float l = luma(frame);
        o = frame * (1.0 - smoothstep(l - 0.04, l + 0.04, dotv));
    } else if (uStyle == 17) {              // kaleidoscope
        float rot = e * 0.5 * a;
        float segment = 2.0 * PI / 6.0;
        vec2 d = p - c;
        float r = length(d);
        float ang = atan(d.y, d.x) - rot;
        float t = ang - segment * floor(ang / segment);
        t = abs(t - segment / 2.0);
        float source = t + rot;
        o = tap(c + r * vec2(cos(source), sin(source)));
    } else if (uStyle == 18) {              // twirl
        float radius = min(res.x, res.y) * 0.45;
        float angle = 2.0 * a * sin(2.0 * PI * 0.5 * e);
        vec2 d = p - c;
        float r = length(d);
        if (r < radius) {
            float f = 1.0 - r / radius;
            float theta = angle * f * f;
            float cs = cos(theta), sn = sin(theta);
            o = tap(c + vec2(d.x * cs - d.y * sn, d.x * sn + d.y * cs));
        }
    } else if (uStyle == 19) {              // bulge
        float radius = min(res.x, res.y) * 0.45;
        float swell = 0.5 - 0.5 * cos(2.0 * PI * 0.7 * e);
        float strength = min(0.7 * a * swell, 0.9);
        vec2 d = p - c;
        float r = length(d);
        if (r < radius) {
            float f = 1.0 - r / radius;
            o = tap(c + d * (1.0 - strength * f * f));
        }
    } else if (uStyle == 20) {              // fade-in
        o = frame * clamp01(pr);
    } else if (uStyle == 21) {              // fade-out
        o = frame * clamp01(1.0 - pr);
    }
    fragColor = vec4(clamp(o, 0.0, 1.0), 1.0);
}
