#version 330 core
// Optical flow, one pyramid level (OpticalFlowGl). A symmetric block match: for every pixel of the picture to be made at
// time uAlpha between frame A (0) and frame B (1), find the displacement d (A -> B, in this level's pixels) for which the
// block around (p - uAlpha * d) in A looks like the block around (p + (1 - uAlpha) * d) in B. The motion is found at the
// new frame's own pixels, so there are no holes or doubled edges to fill in afterwards (what ffmpeg's bidirectional
// motion estimation does too). Coarse to fine: the level above (half the size) gives the starting d, this level refines it.
//
// The cost is the mean absolute luma difference over the block AFTER taking out the block's average difference, so a fade,
// an exposure change or a flicker (the whole block a bit brighter in B) doesn't pull the match away from the real motion.
//
// Output: RG = d, B = the matching cost (0 = identical, 1 = opposite), A = the contrast of the block (mean absolute deviation
// of frame A's luma around its average) at that match: the cost only means "a bad match" relative to how much there is to match.
// A and B are mipmapped, level uLod is the size this pass works at.
uniform sampler2D uA;
uniform sampler2D uB;
uniform sampler2D uPrev;   // the coarser level's result (only read when uHasPrev == 1)
uniform int uLod;
uniform int uHasPrev;
uniform float uAlpha;
uniform int uRadius;       // candidates: the start + uStep * (i, j), i and j from -uRadius to +uRadius
uniform float uStep;
uniform int uWin;          // the block reaches uWin pixels each side of the pixel
uniform int uStride;       // ... and is sampled every uStride pixels (2: a quarter of the reads, for a big block)
uniform float uBias;       // cost added per pixel a candidate moves away from the start: keeps the flow smooth on flat areas

out vec4 outFlow;

// (2 * uWin / uStride + 1) squared must not be more than this
const int MAX_SAMPLES = 64;
float diff[MAX_SAMPLES];

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 size = vec2(textureSize(uA, uLod));
    vec2 p = gl_FragCoord.xy;   // pixel centre, in this level's pixels
    vec2 start = vec2(0.0);
    if (uHasPrev == 1) {
        vec2 prevSize = vec2(textureSize(uPrev, 0));
        start = texture(uPrev, p / size).xy * (size / prevSize);
    }
    float lod = float(uLod);
    float best = 1e9;
    vec2 bestD = start;
    for (int j = -uRadius; j <= uRadius; j++) {
        for (int i = -uRadius; i <= uRadius; i++) {
            vec2 off = uStep * vec2(float(i), float(j));
            vec2 d = start + off;
            vec2 pa = p - uAlpha * d;
            vec2 pb = p + (1.0 - uAlpha) * d;
            int n = 0;
            for (int y = -uWin; y <= uWin; y += uStride) {
                for (int x = -uWin; x <= uWin; x += uStride) {
                    vec2 o = vec2(float(x), float(y));
                    float a = luma(textureLod(uA, (pa + o) / size, lod).rgb);
                    float b = luma(textureLod(uB, (pb + o) / size, lod).rgb);
                    if (n < MAX_SAMPLES) {
                        diff[n] = a - b;
                        n++;
                    }
                }
            }
            float mean = 0.0;
            for (int k = 0; k < n; k++) mean += diff[k];
            mean /= float(n);
            float sad = 0.0;
            for (int k = 0; k < n; k++) sad += abs(diff[k] - mean);
            float cost = sad / float(n) + uBias * length(off);
            if (cost < best) {
                best = cost;
                bestD = d;
            }
        }
    }
    // the contrast of frame A's block where the best match was found
    vec2 pa0 = p - uAlpha * bestD;
    float sumA = 0.0;
    int m = 0;
    for (int y = -uWin; y <= uWin; y += uStride) {
        for (int x = -uWin; x <= uWin; x += uStride) {
            if (m < MAX_SAMPLES) {
                diff[m] = luma(textureLod(uA, (pa0 + vec2(float(x), float(y))) / size, lod).rgb);
                sumA += diff[m];
                m++;
            }
        }
    }
    float meanA = sumA / float(m);
    float contrast = 0.0;
    for (int k = 0; k < m; k++) contrast += abs(diff[k] - meanA);
    outFlow = vec4(bestD, best, contrast / float(m));
}
