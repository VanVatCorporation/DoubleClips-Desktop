#version 330 core
// Motion blur (OpticalFlowGl): the picture smeared along the way things move, one pass. The motion comes from the optical flow
// pyramid (opticalflow_search.frag): d is how far, in the flow level's pixels, the content at this pixel moves from one frame
// to the next. A moving object seen over a time t after the frame is the picture shifted by t * d, so the smear at this pixel is
// the average of the picture at p - t * d for t over the exposure:
//   Both     t from -0.5 to +0.5  (centred: the streak reaches ahead of and behind the object)
//   Forward  t from  0   to +1    (the smear runs ahead of the object, to where it goes)
//   Backward t from -1   to  0    (the smear trails behind it, like a camera's)
// uLength is how much of the frame-to-frame motion the exposure covers (Blur 100 = all of it). Several passes in a row (the
// "Speed" setting) smear the smear again. Where the match was bad (the cost is high against the block's contrast) the flow is
// not trusted and the pixel is left alone, so noise doesn't draw streaks. The last pass mixes the sharp picture back in (Blend).
uniform sampler2D uSrc;    // the picture this pass smears (the sharp one, or the result of the pass before)
uniform sampler2D uSharp;  // the sharp picture, for Blend
uniform sampler2D uFlow;   // RG = d in its own pixels, B = match cost, A = block contrast
uniform float uLength;     // 0 ... 1
uniform int uDirection;    // 0 both, 1 forward, 2 backward
uniform float uBlend;      // 0 ... 1, only the last pass sets it
uniform float uCostLo;
uniform float uCostHi;
uniform int uMaxTaps;

in vec2 vUV;
out vec4 outColor;

void main() {
    vec4 f = texture(uFlow, vUV);
    vec2 size = vec2(textureSize(uSrc, 0));
    vec2 duv = f.xy / vec2(textureSize(uFlow, 0));
    float trust = 1.0 - smoothstep(uCostLo, uCostHi, f.z / (f.w + 0.015));
    vec2 s = duv * uLength * trust;                // the whole exposure, in uv
    float lengthPx = length(s * size);

    float t0 = -0.5, t1 = 0.5;
    if (uDirection == 1) { t0 = 0.0; t1 = 1.0; }
    else if (uDirection == 2) { t0 = -1.0; t1 = 0.0; }

    int taps = clamp(int(ceil(lengthPx)) + 1, 1, uMaxTaps);   // about one tap per pixel of streak
    vec4 sum = vec4(0.0);
    for (int i = 0; i < taps; i++) {
        float t = mix(t0, t1, (float(i) + 0.5) / float(taps));
        sum += textureLod(uSrc, vUV - t * s, 0.0);
    }
    vec4 smeared = sum / float(taps);
    outColor = mix(smeared, textureLod(uSharp, vUV, 0.0), uBlend);
}
