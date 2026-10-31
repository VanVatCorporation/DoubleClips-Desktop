package com.vanvatcorporation.doubleclips;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL33.*;

/**
 * Optical flow on the GPU: the picture BETWEEN two decoded frames of a clip (a slowed-down clip's in-between frames), made by
 * finding how things move from the first frame to the second and pulling both frames along that motion.
 *
 * <p>Per output picture, fragment passes only (macOS has OpenGL 4.1, no compute shaders):
 * <ol>
 * <li>a motion pyramid, coarse to fine ({@code opticalflow_search.frag}, one pass per level): a symmetric block match at
 *     the new frame's own pixels, so there are no holes; the finest level gets one more pass in half pixels;</li>
 * <li>{@code opticalflow_compose.frag}: frame A pulled forward and frame B pulled back by that motion, mixed, with a plain
 *     cross-fade where the match is bad.</li>
 * </ol>
 * The result is drawn into the clip's own layer texture, so everything after it (mask, effect, chroma key, transform...) treats
 * it as any decoded frame. The same shaders were run on a software GL against pictures with known motion (about 36-39 dB at a
 * half step on slow textured motion, a little above ffmpeg's minterpolate, and the cross-fade's 24 dB as the floor).
 *
 * <p>Single GL thread, like the compositor it belongs to. {@link #ready()} is false when the shaders don't build on this GPU;
 * the compositor then draws the nearer frame instead.
 */
final class OpticalFlowGl {

    // Per pyramid level: search radius (candidates are start + step * (i, j), i and j from -radius to radius), step in that
    // level's pixels, block half size, block sampling stride. Tuned against ground truth (see the shaders' header).
    private static final int[] COARSE = {8, 1, 4, 2}, MID = {2, 1, 3, 2}, FINE = {2, 1, 3, 2};
    // The extra pass on the finest level: half pixel steps around what it found.
    private static final int SUB_RADIUS = 1, SUB_WIN = 1, SUB_STRIDE = 1;
    private static final float SUB_STEP = 0.5f;
    private static final float BIAS = 0.002f;
    // Compose: the match cost relative to the block's contrast, and the jump of the motion between neighbours (flow pixels),
    // from "use the warped picture" (Lo) to "use the cross-fade" (Hi).
    private static final float COST_LO = 0.4f, COST_HI = 1.0f, JUMP_LO = 3.0f, JUMP_HI = 8.0f;

    private static final String VERTEX_SHADER =
            "#version 330 core\n" +
            "out vec2 vUV;\n" +
            "void main() {\n" +
            "    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n" +
            "    vUV = p;\n" +
            "    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n" +
            "}\n";

    /** Motion blur for one picture: {@code length} and {@code blend} 0 ... 1, {@code direction} 0 both / 1 forward / 2 backward. */
    static final class Blur {
        final float length, blend;
        final int direction, passes;

        Blur(float length, float blend, int direction, int passes) {
            this.length = length;
            this.blend = blend;
            this.direction = direction;
            this.passes = Math.max(1, Math.min(MAX_BLUR_PASSES, passes));
        }

        long signature() {
            return 1 + (long) (length * 1000) + 1001L * (long) (blend * 1000) + 1_002_001L * direction + 3_000_000_000L * passes;
        }
    }

    /**
     * The blur is applied at most this many times to a picture however many the user types (the model keeps the number): each
     * pass smears the result of the one before, so a lot of them add little streak and cost a full pass each.
     */
    static final int MAX_BLUR_PASSES = 24;
    private static final int MAX_BLUR_TAPS = 48;

    /** What one clip layer keeps for its optical flow: the two frames, the pyramid and where the result goes. */
    static final class State {
        final int width, height, finest, levels, layerTexture;
        final int texA, texB, outFbo;
        final int[] flowTex, flowFbo;     // per level, index 0 = the finest
        final int subTex, subFbo;         // the finest level's half pixel pass writes here
        /** The pair whose frames are in texA / texB (mipmapped), and whether they are. */
        long uploadedPair = Long.MIN_VALUE;
        /** The pair seen last and whether it is a scene cut. */
        long cutPair = Long.MIN_VALUE;
        boolean cut;
        /** What the layer texture holds: the pair, and what of it (-1 = nothing yet, 0 = first frame, 1 = second, 2+ = a mix). */
        long contentPair = Long.MIN_VALUE;
        long contentKind = -1;
        /** ... and its motion blur ({@link Blur#signature}, 0 = none). */
        long contentBlur = 0;
        /** The sharp picture of a made frame, and the two textures the blur passes swap between: made when a blur first needs them. */
        int baseTex, baseFbo, pingTex, pingFbo, pongTex, pongFbo;

        private State(int width, int height, int finest, int levels, int layerTexture, int texA, int texB, int outFbo,
                      int[] flowTex, int[] flowFbo, int subTex, int subFbo) {
            this.width = width;
            this.height = height;
            this.finest = finest;
            this.levels = levels;
            this.layerTexture = layerTexture;
            this.texA = texA;
            this.texB = texB;
            this.outFbo = outFbo;
            this.flowTex = flowTex;
            this.flowFbo = flowFbo;
            this.subTex = subTex;
            this.subFbo = subFbo;
        }
    }

    private final int flowSide;
    private boolean built = false, failed = false;
    private int searchProgram, composeProgram, streakProgram, vao;
    private int uSA, uSB, uSPrev, uSLod, uSHasPrev, uSAlpha, uSRadius, uSStep, uSWin, uSStride, uSBias;
    private int uCA, uCB, uCFlow, uCAlpha, uCCostLo, uCCostHi, uCIncLo, uCIncHi;
    private int uMSrc, uMSharp, uMFlow, uMLength, uMDirection, uMBlend, uMCostLo, uMCostHi, uMMaxTaps;

    /** @param flowSide the long side, in pixels, of the finest level of the motion pyramid (see {@link OpticalFlowMath}) */
    OpticalFlowGl(int flowSide) {
        this.flowSide = Math.max(16, flowSide);
    }

    /** Builds the shaders on first use; false (for good) if they don't build on this GPU. */
    boolean ready() {
        if (built) return true;
        if (failed) return false;
        try {
            searchProgram = buildProgram(VERTEX_SHADER, resource("/shaders/opticalflow_search.frag"));
            composeProgram = buildProgram(VERTEX_SHADER, resource("/shaders/opticalflow_compose.frag"));
            streakProgram = buildProgram(VERTEX_SHADER, resource("/shaders/motionblur_streak.frag"));
            uSA = glGetUniformLocation(searchProgram, "uA");
            uSB = glGetUniformLocation(searchProgram, "uB");
            uSPrev = glGetUniformLocation(searchProgram, "uPrev");
            uSLod = glGetUniformLocation(searchProgram, "uLod");
            uSHasPrev = glGetUniformLocation(searchProgram, "uHasPrev");
            uSAlpha = glGetUniformLocation(searchProgram, "uAlpha");
            uSRadius = glGetUniformLocation(searchProgram, "uRadius");
            uSStep = glGetUniformLocation(searchProgram, "uStep");
            uSWin = glGetUniformLocation(searchProgram, "uWin");
            uSStride = glGetUniformLocation(searchProgram, "uStride");
            uSBias = glGetUniformLocation(searchProgram, "uBias");
            uCA = glGetUniformLocation(composeProgram, "uA");
            uCB = glGetUniformLocation(composeProgram, "uB");
            uCFlow = glGetUniformLocation(composeProgram, "uFlow");
            uCAlpha = glGetUniformLocation(composeProgram, "uAlpha");
            uCCostLo = glGetUniformLocation(composeProgram, "uCostLo");
            uCCostHi = glGetUniformLocation(composeProgram, "uCostHi");
            uCIncLo = glGetUniformLocation(composeProgram, "uIncLo");
            uCIncHi = glGetUniformLocation(composeProgram, "uIncHi");
            uMSrc = glGetUniformLocation(streakProgram, "uSrc");
            uMSharp = glGetUniformLocation(streakProgram, "uSharp");
            uMFlow = glGetUniformLocation(streakProgram, "uFlow");
            uMLength = glGetUniformLocation(streakProgram, "uLength");
            uMDirection = glGetUniformLocation(streakProgram, "uDirection");
            uMBlend = glGetUniformLocation(streakProgram, "uBlend");
            uMCostLo = glGetUniformLocation(streakProgram, "uCostLo");
            uMCostHi = glGetUniformLocation(streakProgram, "uCostHi");
            uMMaxTaps = glGetUniformLocation(streakProgram, "uMaxTaps");
            vao = glGenVertexArrays();
            built = true;
            return true;
        } catch (RuntimeException | IOException e) {
            failed = true;
            System.err.println("LOG Optical flow is unavailable on this GPU, clips with it draw the nearer frame: " + e.getMessage());
            return false;
        }
    }

    /** The textures and targets for a clip layer of this size; {@code layerTexture} is where the pictures will be drawn. */
    State createState(int width, int height, int layerTexture) {
        int[] plan = OpticalFlowMath.plan(width, height, flowSide);
        int finest = plan[0], levels = plan[1];
        int texA = frameTexture(width, height), texB = frameTexture(width, height);
        int[] flowTex = new int[levels], flowFbo = new int[levels];
        for (int k = 0; k < levels; k++) {
            int lw = OpticalFlowMath.mipSize(width, finest + k), lh = OpticalFlowMath.mipSize(height, finest + k);
            flowTex[k] = flowTexture(lw, lh);
            flowFbo[k] = fboFor(flowTex[k]);
        }
        int subTex = flowTexture(OpticalFlowMath.mipSize(width, finest), OpticalFlowMath.mipSize(height, finest));
        int subFbo = fboFor(subTex);
        int outFbo = fboFor(layerTexture);
        return new State(width, height, finest, levels, layerTexture, texA, texB, outFbo, flowTex, flowFbo, subTex, subFbo);
    }

    void destroyState(State s) {
        if (s == null) return;
        glDeleteTextures(s.texA);
        glDeleteTextures(s.texB);
        glDeleteTextures(s.subTex);
        glDeleteFramebuffers(s.subFbo);
        glDeleteFramebuffers(s.outFbo);
        if (s.baseTex != 0) { glDeleteTextures(s.baseTex); glDeleteFramebuffers(s.baseFbo); }
        if (s.pingTex != 0) { glDeleteTextures(s.pingTex); glDeleteFramebuffers(s.pingFbo); }
        if (s.pongTex != 0) { glDeleteTextures(s.pongTex); glDeleteFramebuffers(s.pongFbo); }
        for (int k = 0; k < s.levels; k++) {
            glDeleteTextures(s.flowTex[k]);
            glDeleteFramebuffers(s.flowFbo[k]);
        }
    }

    /**
     * Makes {@code state.layerTexture} hold the picture {@code alpha} of the way from {@code prev} (frame A, 0) to
     * {@code next} (frame B, 1); {@code pairId} says which two frames these are (the same id = the same pair, so nothing
     * is uploaded again). With {@code interpolate} false the picture is {@code next} itself and only the motion between the
     * two frames is used (for a clip that has motion blur but no optical flow). {@code blur} (or null) then smears the picture
     * along that motion. Changes the framebuffer, viewport, blend state and bound textures: the caller puts them back.
     */
    void render(State s, long pairId, ByteBuffer prev, ByteBuffer next, double alpha, boolean interpolate, Blur blur) {
        OpticalFlowMath.Mode mode = interpolate ? OpticalFlowMath.modeFor(alpha) : OpticalFlowMath.Mode.SECOND;
        if (s.cutPair != pairId) {
            s.cutPair = pairId;
            s.cut = OpticalFlowMath.isSceneCut(prev, next, s.width, s.height);
        }
        // A scene cut has no in-between picture and no motion to smear: the nearer frame is held (ffmpeg's scene change
        // detection does the same).
        if (s.cut) {
            blur = null;
            if (mode == OpticalFlowMath.Mode.MIX) mode = alpha < 0.5 ? OpticalFlowMath.Mode.FIRST : OpticalFlowMath.Mode.SECOND;
        }
        long kind = mode == OpticalFlowMath.Mode.FIRST ? 0 : mode == OpticalFlowMath.Mode.SECOND ? 1 : 2 + (long) (alpha * 10000.0);
        long blurSignature = blur == null ? 0 : blur.signature();
        if (s.contentPair == pairId && s.contentKind == kind && s.contentBlur == blurSignature) return; // paused redraw: it is there

        if (blur == null && mode != OpticalFlowMath.Mode.MIX) {
            // one of the two frames as it is
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, s.layerTexture);
            ByteBuffer view = (mode == OpticalFlowMath.Mode.FIRST ? prev : next).duplicate();
            view.clear();
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, s.width, s.height, GL_RGBA, GL_UNSIGNED_BYTE, view);
        } else {
            if (s.uploadedPair != pairId) {
                upload(s.texA, s, prev);
                upload(s.texB, s, next);
                s.uploadedPair = pairId;
            }
            glDisable(GL_BLEND);
            glBindVertexArray(vao);
            // the motion is looked for at the moment of the picture: A for the first frame, B for the second, between for a mix
            runPyramid(s, mode == OpticalFlowMath.Mode.FIRST ? 0f : mode == OpticalFlowMath.Mode.SECOND ? 1f : (float) alpha);
            if (blur == null) {
                compose(s, (float) alpha, s.outFbo);
            } else {
                int base;
                if (mode == OpticalFlowMath.Mode.MIX) {
                    ensureBase(s);
                    compose(s, (float) alpha, s.baseFbo);
                    base = s.baseTex;
                } else {
                    base = mode == OpticalFlowMath.Mode.FIRST ? s.texA : s.texB;
                }
                smear(s, base, blur);
            }
            glActiveTexture(GL_TEXTURE0);
        }
        s.contentPair = pairId;
        s.contentKind = kind;
        s.contentBlur = blurSignature;
    }

    // ---- the passes ------------------------------------------------------------------------------

    private void runPyramid(State s, float alpha) {
        glUseProgram(searchProgram);
        glUniform1i(uSA, 0);
        glUniform1i(uSB, 1);
        glUniform1i(uSPrev, 2);
        glUniform1f(uSAlpha, alpha);
        glUniform1f(uSBias, BIAS);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, s.texA);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, s.texB);

        int previous = 0; // the texture of the coarser level; 0 = none
        for (int k = s.levels - 1; k >= 0; k--) {
            int[] p = k == s.levels - 1 ? COARSE : (k == 0 ? FINE : MID);
            searchPass(s, k, previous, p[0], p[1], p[2], p[3]);
            previous = s.flowTex[k];
        }
        // half pixel refinement of the finest level (written to its own texture, never read and written at once)
        searchPass(s, 0, previous, SUB_RADIUS, SUB_STEP, SUB_WIN, SUB_STRIDE, s.subFbo);
    }

    private void searchPass(State s, int k, int previousTexture, int radius, float step, int win, int stride) {
        searchPass(s, k, previousTexture, radius, step, win, stride, s.flowFbo[k]);
    }

    private void searchPass(State s, int k, int previousTexture, int radius, float step, int win, int stride, int targetFbo) {
        int lod = s.finest + k;
        int lw = OpticalFlowMath.mipSize(s.width, lod), lh = OpticalFlowMath.mipSize(s.height, lod);
        glBindFramebuffer(GL_FRAMEBUFFER, targetFbo);
        glViewport(0, 0, lw, lh);
        glUniform1i(uSLod, lod);
        glUniform1i(uSRadius, radius);
        glUniform1f(uSStep, step);
        glUniform1i(uSWin, win);
        glUniform1i(uSStride, stride);
        glUniform1i(uSHasPrev, previousTexture != 0 ? 1 : 0);
        // Unit 2 holds the coarser level, or nothing: never a texture that is also the target of this very pass (a texture
        // read and written at once is undefined, even when the shader's branch doesn't sample it).
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, previousTexture);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private void compose(State s, float alpha, int targetFbo) {
        glBindFramebuffer(GL_FRAMEBUFFER, targetFbo);
        glViewport(0, 0, s.width, s.height);
        glUseProgram(composeProgram);
        glUniform1i(uCA, 0);
        glUniform1i(uCB, 1);
        glUniform1i(uCFlow, 2);
        glUniform1f(uCAlpha, alpha);
        glUniform1f(uCCostLo, COST_LO);
        glUniform1f(uCCostHi, COST_HI);
        glUniform1f(uCIncLo, JUMP_LO);
        glUniform1f(uCIncHi, JUMP_HI);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, s.texA);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, s.texB);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, s.subTex);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    /**
     * The blur passes: {@code base} (the sharp picture) smeared {@code blur.passes} times along the motion in the finest flow
     * level, each pass reading the one before; the last one mixes the sharp picture back in by Blend and writes the layer.
     */
    private void smear(State s, int base, Blur blur) {
        ensureBlurTargets(s);
        glUseProgram(streakProgram);
        glUniform1i(uMSrc, 0);
        glUniform1i(uMSharp, 1);
        glUniform1i(uMFlow, 2);
        glUniform1f(uMLength, blur.length);
        glUniform1i(uMDirection, blur.direction);
        glUniform1f(uMCostLo, COST_LO);
        glUniform1f(uMCostHi, COST_HI);
        glUniform1i(uMMaxTaps, MAX_BLUR_TAPS);
        glViewport(0, 0, s.width, s.height);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, base);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, s.subTex);

        int source = base;
        for (int i = 0; i < blur.passes; i++) {
            boolean last = i == blur.passes - 1;
            int targetFbo = last ? s.outFbo : (i % 2 == 0 ? s.pingFbo : s.pongFbo);
            int targetTex = i % 2 == 0 ? s.pingTex : s.pongTex;
            glBindFramebuffer(GL_FRAMEBUFFER, targetFbo);
            glUniform1f(uMBlend, last ? blur.blend : 0f);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, source);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            source = targetTex;
        }
    }

    private static void ensureBase(State s) {
        if (s.baseTex != 0) return;
        s.baseTex = plainTexture(s.width, s.height);
        s.baseFbo = fboFor(s.baseTex);
    }

    private static void ensureBlurTargets(State s) {
        if (s.pingTex == 0) {
            s.pingTex = plainTexture(s.width, s.height);
            s.pingFbo = fboFor(s.pingTex);
            s.pongTex = plainTexture(s.width, s.height);
            s.pongFbo = fboFor(s.pongTex);
        }
    }

    private static int plainTexture(int w, int h) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return tex;
    }

    // ---- GL objects --------------------------------------------------------------------------------

    private static void upload(int texture, State s, ByteBuffer pixels) {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        ByteBuffer view = pixels.duplicate();
        view.clear();
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, s.width, s.height, GL_RGBA, GL_UNSIGNED_BYTE, view);
        glGenerateMipmap(GL_TEXTURE_2D); // the pyramid samples the frames at coarser sizes
    }

    private static int frameTexture(int w, int h) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return tex;
    }

    /** Half float RGBA: x, y of the motion in the level's pixels, the match cost, the block's contrast. */
    private static int flowTexture(int w, int h) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return tex;
    }

    private static int fboFor(int texture) {
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Optical flow framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
        return fbo;
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = OpticalFlowGl.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException(path + " is missing from the app");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    void close() {
        if (searchProgram != 0) glDeleteProgram(searchProgram);
        if (composeProgram != 0) glDeleteProgram(composeProgram);
        if (streakProgram != 0) glDeleteProgram(streakProgram);
        if (vao != 0) glDeleteVertexArrays(vao);
        searchProgram = composeProgram = streakProgram = vao = 0;
        built = false;
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
            throw new RuntimeException("Optical flow shader link failed: " + log);
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
            throw new RuntimeException("Optical flow shader compile failed: " + log);
        }
        return shader;
    }
}
