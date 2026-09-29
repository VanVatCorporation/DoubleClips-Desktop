package com.vanvatcorporation.doubleclips;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Runs as its OWN separate JVM process - see the comment in build.gradle for
 * why (LWJGL/GLFW's macOS main-thread requirement collides with JavaFX's own
 * main-thread requirement if they share a process). Launched by
 * OpenGLEditNative via ProcessBuilder; talks back to it over stdout with
 * plain "PROGRESS n total" / "LOG message" lines, and reads a cancel-flag
 * file each frame instead of a shared in-memory flag (there is no shared
 * memory across processes).
 * <p>
 * Mirrors Android's OpenGLEditNative.exportTimeline() algorithm shape
 * (per-frame: compute draw commands via OpenGLEdit, lazily open/close a
 * per-clip frame source, draw, present) - what differs is entirely how
 * frames get in and out, because there's no MediaCodec/EGL on the JVM:
 * - decode: one streaming `ffmpeg ... -f rawvideo -pix_fmt rgba pipe:1`
 *   subprocess per active clip, read one frame at a time
 * - render: LWJGL/GLFW hidden window + GL33 core profile + an FBO (the
 *   window itself is never drawn to or shown - it exists only because GLFW
 *   needs one to create a GL context)
 * - encode: one streaming `ffmpeg -f rawvideo -pix_fmt rgba pipe:0 ...`
 *   subprocess fed via glReadPixels, video-only (audio is handled entirely
 *   outside this class - see OpenGLEditNative's audio pass + mux)
 * <p>
 * NOT verified on real hardware/GPU from this environment (no display or
 * GPU driver available here to actually run LWJGL/GLFW). Test on a real
 * machine - Windows, Linux, and BOTH Mac architectures - before shipping.
 */
public class OpenGLExportWorker {

    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

    public static void main(String[] args) {
        try {
            run(args);
            System.exit(0);
        } catch (Throwable t) {
            System.err.println("ERROR " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        // Args (all required, positional - this is an internal IPC contract
        // with OpenGLEditNative, not a user-facing CLI):
        //   0: timeline JSON file path
        //   1: project path (for Clip.getAbsolutePath)
        //   2: output video-only mp4 path
        //   3: width  4: height  5: bitrate(Mbps)  6: frameRate
        //   7: ffmpeg binary path (already resolved by the launcher - see
        //      FFmpegEditNative.getFfmpegPath(), not re-resolved here)
        //   8: encoder args, e.g. "-c:v h264_videotoolbox -b:v 15M" or
        //      "-c:v libx264 -preset medium -crf 23" (already decided by the
        //      launcher, which already has FFmpegEditNative's hwaccel
        //      detection - not duplicated here)
        //   9: cancel-flag file path (its mere existence means "stop")
        Path timelineJsonPath = Path.of(args[0]);
        String projectPath = args[1];
        String outputPath = args[2];
        int width = Integer.parseInt(args[3]);
        int height = Integer.parseInt(args[4]);
        int frameRate = Integer.parseInt(args[6]);
        String ffmpegPath = args[7];
        String encoderArgs = args[8];
        Path cancelFlagPath = Path.of(args[9]);

        Timeline timeline = GSON.fromJson(Files.readString(timelineJsonPath), Timeline.class);
        ProjectData projectData = new ProjectData(projectPath, "", 0, 0, 0);
        OpenGLEdit edit = new OpenGLEdit();

        log("OpenGL worker starting - " + width + "x" + height + " @" + frameRate + "fps");

        glfwInit();
        try {
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE); // required for core profile on macOS
            // 1x1: this window is never shown or drawn to directly - it only
            // exists because GLFW needs SOME window to create a GL context.
            // All real rendering happens into the FBO created below.
            long window = glfwCreateWindow(1, 1, "DoubleClips OpenGL export (hidden)", NULL, NULL);
            if (window == NULL) throw new RuntimeException("Failed to create hidden GLFW window/GL context");
            try {
                glfwMakeContextCurrent(window);
                org.lwjgl.opengl.GL.createCapabilities();

                Renderer renderer = new Renderer(width, height);
                try {
                    exportTimeline(timeline, edit, projectData, renderer, width, height, frameRate,
                            outputPath, ffmpegPath, encoderArgs, cancelFlagPath);
                } finally {
                    renderer.close();
                }
            } finally {
                glfwDestroyWindow(window);
            }
        } finally {
            glfwTerminate();
        }
    }

    private static void exportTimeline(Timeline timeline, OpenGLEdit edit, ProjectData projectData,
                                        Renderer renderer, int width, int height, int frameRate,
                                        String outputPath, String ffmpegPath, String encoderArgs,
                                        Path cancelFlagPath) throws Exception {
        float timelineDuration = timeline != null ? timeline.duration : 0f;
        int totalFrames = (int) Math.max(1, Math.ceil(timelineDuration * frameRate - 1e-6));
        log("Compositing " + totalFrames + " frames");

        Map<Clip, ClipFrameSource> activeSources = new IdentityHashMap<>();
        VideoEncoder encoder = new VideoEncoder(ffmpegPath, width, height, frameRate, encoderArgs, outputPath);
        ByteBuffer pixelBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());

        int frameIndex = 0;
        try {
            for (; frameIndex < totalFrames; frameIndex++) {
                if (Files.exists(cancelFlagPath)) {
                    log("Export cancelled");
                    break;
                }

                float outputTimeSeconds = (float) (frameIndex / (double) frameRate);
                List<OpenGLEdit.DrawCommand> commands = edit.computeFrameForTimestamp(timeline, outputTimeSeconds, width, height);

                // Close sources for clips no longer active this frame - clips don't
                // recur within a track, so once inactive they're done for good.
                java.util.Set<Clip> stillActive = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                for (OpenGLEdit.DrawCommand cmd : commands) stillActive.add(cmd.clip);
                activeSources.entrySet().removeIf(entry -> {
                    if (!stillActive.contains(entry.getKey())) {
                        entry.getValue().close();
                        return true;
                    }
                    return false;
                });

                renderer.beginFrame();

                for (OpenGLEdit.DrawCommand cmd : commands) {
                    if (cmd.clip.type != ClipType.VIDEO) continue; // see OpenGLEdit.getUnsupportedFeatures()

                    ClipFrameSource source = activeSources.get(cmd.clip);
                    if (source == null) {
                        try {
                            source = new ClipFrameSource(ffmpegPath, cmd.clip, projectData, frameRate);
                            activeSources.put(cmd.clip, source);
                        } catch (IOException e) {
                            log("Could not open a clip, it will be missing from this export: " + e.getMessage());
                            continue;
                        }
                    }

                    ByteBuffer frame = source.nextFrame();
                    if (frame == null) continue; // this clip ran out of frames early - just skip it this frame

                    renderer.drawClip(frame, cmd.clip.width, cmd.clip.height, cmd.mvpMatrix, cmd.opacity);
                }

                renderer.readPixelsInto(pixelBuffer);
                encoder.writeFrame(pixelBuffer);

                if (frameIndex % 5 == 0) progress(frameIndex, totalFrames);
                if (frameIndex % 30 == 0 && frameIndex > 0) {
                    log(String.format(Locale.US, "frame %d/%d (t=%.2fs)", frameIndex, totalFrames, outputTimeSeconds));
                }
            }
            progress(frameIndex, totalFrames);
            log("Timeline export finished, " + frameIndex + " frames -> " + outputPath);
        } finally {
            for (ClipFrameSource source : activeSources.values()) source.close();
            encoder.close();
        }
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
    //  Per-clip decode: a streaming ffmpeg subprocess emitting raw RGBA
    //  frames, read one at a time. No seeking mid-stream - output frames are
    //  requested in monotonically increasing time order (OpenGLEdit doesn't
    //  support reverse/speed effects yet), so a single sequential decode
    //  matches what's asked for.
    // ─────────────────────────────────────────────────────────────────────
    private static class ClipFrameSource {
        private final Process process;
        private final InputStream stdout;
        private final int clipWidth;
        private final int clipHeight;
        // LWJGL's GL calls hand the buffer's memory address straight to native
        // OpenGL, which REQUIRES a direct buffer - a heap buffer's backing
        // array can be relocated by the JVM's garbage collector at any time,
        // so passing one in works by luck until it doesn't (the classic
        // symptom: runs fine for a while, then an unexplained native crash -
        // exactly what a mid-export SIGABRT looks like). Allocated once here
        // and reused every frame, filled via a channel read rather than
        // read-into-byte-array-then-wrap, so there's no heap buffer in the
        // path at all, not even transiently.
        private final ByteBuffer frameBuffer;
        private final java.nio.channels.ReadableByteChannel channel;
        private boolean ended = false;

        ClipFrameSource(String ffmpegPath, Clip clip, ProjectData projectData, int frameRate) throws IOException {
            this.clipWidth = Math.max(1, clip.width);
            this.clipHeight = Math.max(1, clip.height);
            this.frameBuffer = ByteBuffer.allocateDirect(clipWidth * clipHeight * 4).order(ByteOrder.nativeOrder());

            String inputPath = (clip.removeBackground && clip.type == ClipType.VIDEO
                    && new File(clip.getCutoutPath(projectData.getProjectPath())).exists())
                    ? clip.getCutoutPath(projectData.getProjectPath())
                    : clip.getAbsolutePath(projectData);

            List<String> command = List.of(
                    ffmpegPath,
                    "-ss", String.valueOf(clip.startClipTrim),
                    "-i", inputPath,
                    "-an",
                    "-f", "rawvideo",
                    "-pix_fmt", "rgba",
                    "-r", String.valueOf(frameRate),
                    "pipe:1"
            );
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            this.process = pb.start();
            this.stdout = new BufferedInputStream(process.getInputStream(), frameBuffer.capacity());
            this.channel = java.nio.channels.Channels.newChannel(stdout);
        }

        /** Returns null once this clip's decoder has no more frames. */
        ByteBuffer nextFrame() {
            if (ended) return null;
            try {
                frameBuffer.clear();
                while (frameBuffer.hasRemaining()) {
                    int read = channel.read(frameBuffer);
                    if (read < 0) { ended = true; return null; } // EOF - including a partial trailing frame, discarded
                }
                frameBuffer.flip();
                return frameBuffer;
            } catch (IOException e) {
                ended = true;
                return null;
            }
        }

        int getClipWidth() { return clipWidth; }
        int getClipHeight() { return clipHeight; }

        void close() {
            process.destroy();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Encode: a streaming ffmpeg subprocess consuming raw RGBA frames on
    //  stdin, video-only (audio is handled entirely outside this worker).
    // ─────────────────────────────────────────────────────────────────────
    private static class VideoEncoder {
        private final Process process;
        private final OutputStream stdin;

        VideoEncoder(String ffmpegPath, int width, int height, int frameRate, String encoderArgs, String outputPath) throws IOException {
            java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of(
                    ffmpegPath,
                    "-f", "rawvideo",
                    "-pix_fmt", "rgba",
                    "-video_size", width + "x" + height,
                    "-framerate", String.valueOf(frameRate),
                    "-i", "pipe:0",
                    // glReadPixels returns rows bottom-first (OpenGL window-coordinate
                    // convention); every video/image pixel format expects top-first.
                    // OpenGLEdit's own world-space Y-down setup is unrelated to this -
                    // that's about where content lands ON the canvas, this is purely
                    // glReadPixels' own readback order. Cheaper to let ffmpeg's own
                    // optimized filter flip it than to reverse rows in Java.
                    "-vf", "vflip",
                    "-pix_fmt", "yuv420p"
            ));
            for (String part : encoderArgs.trim().split("\\s+")) {
                if (!part.isEmpty()) command.add(part);
            }
            command.add("-y");
            command.add(outputPath);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            this.process = pb.start();
            this.stdin = new BufferedOutputStream(process.getOutputStream(), width * height * 4);
        }

        void writeFrame(ByteBuffer frame) throws IOException {
            frame.rewind();
            byte[] bytes = new byte[frame.remaining()];
            frame.get(bytes);
            stdin.write(bytes);
        }

        void close() {
            try {
                stdin.flush();
                stdin.close();
            } catch (IOException ignored) {
            }
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  GL33 core-profile renderer: one FBO sized to the output canvas, one
    //  textured-quad shader, one texture reused (re-uploaded) per draw call.
    //  No depth buffer - clips are composited purely by track-index draw
    //  order + alpha blending (painter's algorithm), matching OpenGLEdit's
    //  own track-ordering guarantee and FFmpeg's own overlay chain.
    // ─────────────────────────────────────────────────────────────────────
    private static class Renderer {
        private static final String VERTEX_SHADER =
                "#version 330 core\n" +
                "layout(location=0) in vec2 aPos;\n" +
                "layout(location=1) in vec2 aUV;\n" +
                "uniform mat4 uMvp;\n" +
                "out vec2 vUV;\n" +
                "void main() {\n" +
                "    gl_Position = uMvp * vec4(aPos, 0.0, 1.0);\n" +
                "    vUV = aUV;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "#version 330 core\n" +
                "in vec2 vUV;\n" +
                "uniform sampler2D uTex;\n" +
                "uniform float uOpacity;\n" +
                "out vec4 fragColor;\n" +
                "void main() {\n" +
                "    vec4 c = texture(uTex, vUV);\n" +
                "    fragColor = vec4(c.rgb, c.a * uOpacity);\n" +
                "}\n";

        // Unit quad (-1,-1)..(1,1); OpenGLEdit's model matrix scales/rotates/
        // translates this into the clip's actual on-canvas position and size.
        // UV is NOT flipped here (unlike Android's OES-sampler version) - our
        // texture upload is a plain top-row-first RGBA buffer from ffmpeg's
        // rawvideo output, and OpenGLEdit's projection already places world
        // Y=0 (canvas top) at NDC+1 (window top), so the straightforward
        // V=(y+1)/2 mapping already lines up: local y=-1 (this quad's top,
        // after the model matrix) samples V=0 (the buffer's first/top row).
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
        private final int uMvpLoc, uOpacityLoc, uTexLoc;
        private final int vao, vbo;
        private int clipTexture; // reused each drawClip() call; resized via glTexImage2D when clip dimensions change
        private int clipTextureW = -1, clipTextureH = -1;

        Renderer(int width, int height) {
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
                throw new RuntimeException("FBO incomplete: 0x" + Integer.toHexString(status));
            }

            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            uMvpLoc = glGetUniformLocation(program, "uMvp");
            uOpacityLoc = glGetUniformLocation(program, "uOpacity");
            uTexLoc = glGetUniformLocation(program, "uTex");

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

            clipTexture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, clipTexture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }

        void beginFrame() {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glViewport(0, 0, width, height);
            glClearColor(0f, 0f, 0f, 1f); // opaque black canvas base - final output has no alpha channel anyway
            glClear(GL_COLOR_BUFFER_BIT);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }

        void drawClip(ByteBuffer rgbaFrame, int clipW, int clipH, float[] mvpMatrix, float opacity) {
            glUseProgram(program);
            glBindVertexArray(vao);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, clipTexture);
            rgbaFrame.rewind();
            if (clipW != clipTextureW || clipH != clipTextureH) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, clipW, clipH, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgbaFrame);
                clipTextureW = clipW;
                clipTextureH = clipH;
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, clipW, clipH, GL_RGBA, GL_UNSIGNED_BYTE, rgbaFrame);
            }
            glUniform1i(uTexLoc, 0);

            glUniformMatrix4fv(uMvpLoc, false, mvpMatrix);
            glUniform1f(uOpacityLoc, opacity);

            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        }

        void readPixelsInto(ByteBuffer outBuffer) {
            outBuffer.rewind();
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, outBuffer);
        }

        void close() {
            glDeleteTextures(colorTex);
            glDeleteTextures(clipTexture);
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
