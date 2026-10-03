package com.vanvatcorporation.doubleclips;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Runs as its OWN separate JVM process - see the comment in build.gradle for
 * why (LWJGL/GLFW's macOS main-thread requirement collides with JavaFX's own
 * main-thread requirement if they share a process). Launched by
 * OpenGLEditNative via ProcessBuilder; talks back over stdout with plain
 * "PROGRESS n total" / "LOG message" / "CANCELLED" lines, and reads a
 * cancel-flag file each frame (there is no shared memory across processes).
 * <p>
 * This class is deliberately thin. The algorithm - which clip is drawn where and
 * when, decoder lifetime, speed / trim / reverse / images, cancel, progress - is
 * {@link OpenGLTimelineExporter}, the desktop port of Android's
 * OpenGLEditNative.exportTimeline(); the media plumbing (ffmpeg decode/encode
 * subprocesses, since there is no MediaCodec on the JVM) is {@link OpenGLFrameIO}.
 * What is left here is only the GPU: a hidden GLFW window to get a GL 3.3 core
 * context, one FBO the size of the output canvas, and one textured-quad shader
 * carrying the same colour-grading maths as Android's fragment shaders.
 * <p>
 * Positional args (an internal contract with OpenGLEditNative, not a CLI):
 * <pre>
 *  0 timeline JSON path        1 project path           2 output (video-only mp4)
 *  3 width                     4 height                 5 bitrate (Mbps, informational)
 *  6 frame rate                7 ffmpeg binary path     8 encoder args
 *  9 cancel-flag file path    10 stretchToFull (true/false)
 * 11 reversed-clips manifest path, or "-" for none
 *    (lines: trackIndex TAB clipIndex TAB pre-reversed file path)
 * </pre>
 * The GL calls in this file have NOT been run from the environment this was
 * written in (no GPU/display, no LWJGL jars). Everything else in the pipeline is
 * exercised by a software compositor; test this class on a real machine.
 */
public class OpenGLExportWorker {

    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

    public static void main(String[] args) {
        try {
            run(args); // a cancelled export is reported by its CANCELLED line, not by the exit code
            System.exit(0);
        } catch (Throwable t) {
            System.err.println("ERROR " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length < 12) {
            throw new IllegalArgumentException("Expected 12 arguments, got " + args.length
                    + " (OpenGLEditNative and OpenGLExportWorker are out of sync)");
        }
        Path timelineJsonPath = Path.of(args[0]);
        String projectPath = args[1];
        String outputPath = args[2];
        int width = Integer.parseInt(args[3]);
        int height = Integer.parseInt(args[4]);
        int frameRate = Integer.parseInt(args[6]);
        String ffmpegPath = args[7];
        String encoderArgs = args[8];
        Path cancelFlagPath = Path.of(args[9]);
        boolean stretchToFull = Boolean.parseBoolean(args[10]);
        String reversedManifest = args[11];

        Timeline timeline = GSON.fromJson(Files.readString(timelineJsonPath), Timeline.class);
        ProjectData projectData = new ProjectData(projectPath, "", 0, 0, 0);
        Map<Clip, String> reversedClipPaths = readReversedManifest(reversedManifest, timeline);

        log("OpenGL worker starting - " + width + "x" + height + " @" + frameRate + "fps"
                + (stretchToFull ? ", stretch-to-full" : "")
                + (reversedClipPaths.isEmpty() ? "" : ", " + reversedClipPaths.size() + " reversed clip(s)"));

        org.lwjgl.glfw.GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialise GLFW - is a display/GPU available to this process?");
        }
        try {
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE); // required for core profile on macOS
            // 1x1: never shown or drawn to - GLFW just needs SOME window to create a
            // GL context. All real rendering goes into the FBO created below.
            long window = glfwCreateWindow(1, 1, "DoubleClips OpenGL export (hidden)", NULL, NULL);
            if (window == NULL) throw new RuntimeException("Failed to create hidden GLFW window/GL context");
            try {
                glfwMakeContextCurrent(window);
                org.lwjgl.opengl.GL.createCapabilities();
                log("GL " + glGetString(GL_VERSION) + " on " + glGetString(GL_RENDERER));

                GlCompositor compositor = new GlCompositor(width, height);
                try {
                    boolean completed = OpenGLTimelineExporter.export(timeline, new OpenGLEdit(), projectData, compositor,
                            width, height, frameRate, stretchToFull, reversedClipPaths,
                            ffmpegPath, encoderArgs, outputPath,
                            OpenGLTimelineExporter.CancelSignal.fileExists(cancelFlagPath),
                            new OpenGLTimelineExporter.Listener() {
                                @Override public void onLog(String message) { log(message); }
                                @Override public void onProgress(int frameIndex, int totalFrames) { progress(frameIndex, totalFrames); }
                            });
                    if (!completed) {
                        System.out.println("CANCELLED");
                        System.out.flush();
                    }
                } finally {
                    compositor.close();
                }
            } finally {
                glfwDestroyWindow(window);
            }
        } finally {
            glfwTerminate();
        }
    }

    /**
     * Clips cross the process boundary as JSON, so they are matched back up by
     * position (track index, clip index) - stable, because the launcher writes
     * the manifest from the same Timeline object it serialised.
     */
    private static Map<Clip, String> readReversedManifest(String manifestPath, Timeline timeline) throws IOException {
        Map<Clip, String> map = new IdentityHashMap<>();
        if (manifestPath == null || manifestPath.equals("-") || timeline == null || timeline.tracks == null) return map;
        for (String line : Files.readAllLines(Path.of(manifestPath), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t", 3);
            if (parts.length != 3) continue;
            int trackIndex = Integer.parseInt(parts[0].trim());
            int clipIndex = Integer.parseInt(parts[1].trim());
            if (trackIndex < 0 || trackIndex >= timeline.tracks.size()) continue;
            Track track = timeline.tracks.get(trackIndex);
            if (track == null || track.clips == null || clipIndex < 0 || clipIndex >= track.clips.size()) continue;
            map.put(track.clips.get(clipIndex), parts[2]);
        }
        return map;
    }

    private static void log(String message) {
        System.out.println("LOG " + message);
        System.out.flush();
    }

    private static void progress(int frameIndex, int totalFrames) {
        System.out.println("PROGRESS " + frameIndex + " " + totalFrames);
        System.out.flush();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  GL33 core-profile compositor: one FBO sized to the output canvas, one
    //  textured-quad shader, one texture per active clip (so a held frame or a
    //  still image is uploaded once, not re-uploaded every output frame).
    //  No depth buffer - clips are composited purely by track order + alpha
    //  blending (painter's algorithm), like FFmpeg's overlay chain.
    //  Two extra canvas-sized scratch FBOs (created on first use) serve transitions
    //  (each side rendered to its own layer, then blended - see TRANSITION_FRAGMENT_SHADER)
    //  and in-animation blur (a separable Gaussian, see BLUR_FRAGMENT_SHADER).
    // ─────────────────────────────────────────────────────────────────────
    private static final class GlCompositor implements OpenGLTimelineExporter.Compositor {

        private static final class GlLayer implements OpenGLTimelineExporter.Layer {
            final int texture;
            final int width, height;

            GlLayer(int texture, int width, int height) {
                this.texture = texture;
                this.width = width;
                this.height = height;
            }
        }

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
                "    fragColor = vec4(clamp(rgb, 0.0, 1.0), color.a * uOpacity);\n" +
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
        private final int uUnfoldActiveLoc, uUnfoldTopXLoc, uUnfoldBottomXLoc, uUnfoldHeightLoc, uContrastLoc;
        private final int vao, vbo;

        /** A canvas-sized offscreen colour target (FBO + texture). */
        private static final class GlTarget {
            final int fbo, texture;
            GlTarget(int fbo, int texture) { this.fbo = fbo; this.texture = texture; }
        }

        // Created lazily on first use, so a project with no transitions or blur pays nothing for them.
        private final GlTarget[] scratch = new GlTarget[2];
        private int transitionProgram = 0, blurProgram = 0;
        private int uTransALoc, uTransBLoc, uTransProgressLoc, uTransStyleLoc;
        private int uBlurTexLoc, uBlurDirLoc, uBlurTexelLoc, uBlurSigmaLoc;

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
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
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

        @Override
        public void blurOntoMain(int sourceSlot, int tempSlot, float sigmaPixels) {
            if (blurProgram == 0) {
                blurProgram = buildProgram(FULLSCREEN_VERTEX_SHADER, BLUR_FRAGMENT_SHADER);
                uBlurTexLoc = glGetUniformLocation(blurProgram, "uTexture");
                uBlurDirLoc = glGetUniformLocation(blurProgram, "uDirection");
                uBlurTexelLoc = glGetUniformLocation(blurProgram, "uTexelSize");
                uBlurSigmaLoc = glGetUniformLocation(blurProgram, "uSigmaPixels");
            }
            glUseProgram(blurProgram);
            glBindVertexArray(vao);
            glActiveTexture(GL_TEXTURE0);
            glUniform1i(uBlurTexLoc, 0);
            glUniform2f(uBlurTexelLoc, 1f / width, 1f / height);
            glUniform1f(uBlurSigmaLoc, sigmaPixels);

            // Horizontal pass: source -> temp scratch.
            bindScratchCleared(tempSlot);
            glBindTexture(GL_TEXTURE_2D, scratchTarget(sourceSlot).texture);
            glUniform2f(uBlurDirLoc, 1f, 0f);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

            // Vertical pass: temp -> main canvas, blended over what is already there.
            bindMain();
            glBindTexture(GL_TEXTURE_2D, scratchTarget(tempSlot).texture);
            glUniform2f(uBlurDirLoc, 0f, 1f);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
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
}
