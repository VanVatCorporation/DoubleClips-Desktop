#version 330 core
// Optical flow, the new frame (OpticalFlowGl): frame A pulled forward and frame B pulled back along the motion found by
// opticalflow_search.frag, mixed by uAlpha. Where the match is bad (cost above uCostLo, fully by uCostHi: covered or
// uncovered areas, scene cuts, motion beyond the search) or where the motion jumps from one pixel of the flow to the next
// (uIncLo ... uIncHi, in flow pixels: noise matched at random) the picture falls back to a plain cross-fade of A and B,
// which is the safest answer there.
uniform sampler2D uA;
uniform sampler2D uB;
uniform sampler2D uFlow;   // the finest level of the pyramid: RG = d in ITS pixels, B = cost
uniform float uAlpha;
uniform float uCostLo;   // the match cost relative to the block's contrast: from here ...
uniform float uCostHi;   // ... to here the picture goes from the warped one to the cross-fade
uniform float uIncLo;
uniform float uIncHi;

in vec2 vUV;
out vec4 outColor;

void main() {
    vec4 f = texture(uFlow, vUV);
    vec2 duv = f.xy / vec2(textureSize(uFlow, 0));
    vec4 a = textureLod(uA, vUV - uAlpha * duv, 0.0);
    vec4 b = textureLod(uB, vUV + (1.0 - uAlpha) * duv, 0.0);
    vec4 warped = mix(a, b, uAlpha);
    vec4 plain = mix(textureLod(uA, vUV, 0.0), textureLod(uB, vUV, 0.0), uAlpha);
    vec2 px = 1.0 / vec2(textureSize(uFlow, 0));
    float jump = max(max(length(texture(uFlow, vUV + vec2(px.x, 0.0)).xy - f.xy), length(texture(uFlow, vUV - vec2(px.x, 0.0)).xy - f.xy)),
                     max(length(texture(uFlow, vUV + vec2(0.0, px.y)).xy - f.xy), length(texture(uFlow, vUV - vec2(0.0, px.y)).xy - f.xy)));
    float bad = max(smoothstep(uCostLo, uCostHi, f.z / (f.w + 0.015)), smoothstep(uIncLo, uIncHi, jump));
    outColor = mix(warped, plain, bad);
}
