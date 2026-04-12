package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.*;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFprobe;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executors;

public class FFmpegEdit {
    private static FFmpeg ffmpeg;
    private static FFprobe ffprobe;

    static {
        try {
            // Bundled binaries path: assuming bin/ folder in app root
            String appPath = System.getProperty("user.dir");
            String ffmpegPath = IOHelper.CombinePath(appPath, "bin", isWindows() ? "ffmpeg.exe" : "ffmpeg");
            String ffprobePath = IOHelper.CombinePath(appPath, "bin", isWindows() ? "ffprobe.exe" : "ffprobe");
            
            ffmpeg = new FFmpeg(ffmpegPath);
            ffprobe = new FFprobe(ffprobePath);
        } catch (Exception e) {
            System.err.println("FFmpeg/FFprobe binaries not found in bundled bin/ folder. Falling back to system PATH.");
            try {
                ffmpeg = new FFmpeg("ffmpeg");
                ffprobe = new FFprobe("ffprobe");
            } catch (Exception ex) {
                System.err.println("FFmpeg not found in system PATH either.");
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    public static FfmpegRenderQueue queue = new FfmpegRenderQueue();

    public static void runAnyCommand(String cmd, String taskName) {
        runAnyCommand(cmd, taskName, "Ran command!", "Command failed: ", true);
    }

    public static void runAnyCommand(String cmd, String taskName,
                                     Runnable onSuccessRunnable, Runnable onFailRunnable) {
        runAnyCommand(cmd, taskName, "Ran command!", "Command failed: ", true, onSuccessRunnable, onFailRunnable);
    }

    public static void runAnyCommand(String cmd, String taskName, String successMessage, String failMessage, boolean includeFullReport) {
        runAnyCommand(cmd, taskName, successMessage, failMessage, includeFullReport, () -> {}, () -> {});
    }

    public static void runAnyCommand(String cmd, String taskName, String successMessage, String failMessage, boolean includeFullReport,
                                     Runnable onSuccessRunnable, Runnable onFailRunnable) {
        LoggingManager.LogToPersistentDataPath(cmd);

        queue.enqueue(
                new FfmpegRenderQueue.FfmpegRenderQueueInfo(
                        taskName,
                        () -> {
                            Executors.newSingleThreadExecutor().execute(() -> {
                                try {
                                    // Split command string into parts for ProcessBuilder
                                    // Handle quoted paths correctly
                                    List<String> commandList = new ArrayList<>();
                                    commandList.add(ffmpeg.getPath());
                                    
                                    // Simple space-based split for now, but respect quotes
                                    java.util.regex.Pattern regex = java.util.regex.Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'");
                                    java.util.regex.Matcher matcher = regex.matcher(cmd);
                                    while (matcher.find()) {
                                        if (matcher.group(1) != null) commandList.add(matcher.group(1));
                                        else if (matcher.group(2) != null) commandList.add(matcher.group(2));
                                        else commandList.add(matcher.group());
                                    }

                                    ProcessBuilder pb = new ProcessBuilder(commandList);
                                    pb.redirectErrorStream(true);
                                    Process process = pb.start();

                                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                                    StringBuilder output = new StringBuilder();
                                    String line;
                                    while ((line = reader.readLine()) != null) {
                                        output.append(line).append("\n");
                                        // TODO: Pass logs back to UI if needed
                                    }

                                    int exitCode = process.waitFor();
                                    if (exitCode == 0) {
                                        LoggingManager.LogToPersistentDataPath(successMessage + (includeFullReport ? "\nOutput: " + output : ""));
                                        onSuccessRunnable.run();
                                    } else {
                                        LoggingManager.LogToPersistentDataPath(failMessage + "\nExit code: " + exitCode + "\nOutput: " + output);
                                        onFailRunnable.run();
                                    }
                                } catch (Exception e) {
                                    LoggingManager.LogExceptionToNoteOverlay(e);
                                    onFailRunnable.run();
                                } finally {
                                    try {
                                        Thread.sleep(1000);
                                    } catch (InterruptedException ignored) {}
                                    queue.taskCompleted();
                                }
                            });
                        }
                )
        );
    }

    public static void generateSolidColorImage(String projectClipPath, String colorHex) {
        String emptyImagePath = IOHelper.getNextIndexPathInFolder(projectClipPath, "solid_color_", ".png", false);
        runAnyCommand("-f lavfi -i color=c=#" + colorHex + ":s=100x100 -frames:v 1 \"" + emptyImagePath + "\"",
                "Solid Image Generation");
    }

    public static String generateExportCmdPartially(RenderSettings renderSettings,
                                                    int clipCount, int clipOffset) {
        Clip[] clips = new Clip[clipCount];
        int currentClipCount = 0;
        for (Track track : renderSettings.timeline.tracks) {
            if (currentClipCount >= clipCount) break;
            for (Clip clip : track.clips) {
                if (currentClipCount >= clipCount) break;

                if (clipOffset > 0) {
                    clipOffset--;
                    continue;
                }

                clips[currentClipCount] = clip;
                currentClipCount++;
            }
        }

        renderSettings.setClips(clips);
        return generateExportCmdPartially(renderSettings);
    }

    public static String generateExportCmdPartially(RenderSettings templateSettings) {
        FfmpegFilterComplexTags tags = new FfmpegFilterComplexTags();
        StringBuilder cmd = new StringBuilder();

        if (templateSettings.renderingIndex > 0) {
            String previousRenderedClipPath = IOHelper.CombinePath(templateSettings.data.getProjectPath(), ((templateSettings.renderingIndex - 1) + "_") + Constants.DEFAULT_EXPORT_CLIP_FILENAME);
            cmd.append("-i \"").append(previousRenderedClipPath).append("\" ");
        } else {
            cmd.append("-f lavfi -i color=c=black:s=")
                    .append(templateSettings.settings.getRenderVideoWidth(templateSettings.isTemplateCommand)).append("x").append(templateSettings.settings.getRenderVideoHeight(templateSettings.isTemplateCommand))
                    .append(":r=").append(templateSettings.settings.getFrameRate()).append(" -t ").append(templateSettings.timeline.duration).append(" ");
        }

        StringBuilder filterComplex = new StringBuilder();
        int inputLayerIndex = 0;
        int inputMediaIndex = 0;

        for (int i = 0; i < templateSettings.clips.length; i++) {
            Clip clip = templateSettings.clips[i];
            String inputPath;
            if (templateSettings.isTemplateCommand && clip.isLockedForTemplate) {
                inputPath = Constants.DEFAULT_TEMPLATE_CLIP_STATIC_MARK(clip.getClipName());
            } else if (templateSettings.isTemplateCommand) {
                inputPath = Constants.DEFAULT_TEMPLATE_CLIP_MARK(i);
            } else if (clip.removeBackground && clip.type == ClipType.IMAGE && IOHelper.isFileExist(clip.getClipName() + "_cutout")) { // simplified cutout path logic for porting
                inputPath = clip.getClipName() + "_cutout";
            } else {
                inputPath = IOHelper.CombinePath(templateSettings.data.getProjectPath(), clip.getClipName());
            }

            switch (clip.type) {
                case VIDEO:
                case IMAGE:
                    cmd.append("-f lavfi -i \"nullsrc=size=")
                            .append(templateSettings.settings.getRenderVideoWidth(templateSettings.isTemplateCommand)).append("x").append(templateSettings.settings.getRenderVideoHeight(templateSettings.isTemplateCommand))
                            .append(":rate=").append(templateSettings.settings.getFrameRate()).append(",format=yuva420p\"").append(" ");

                    String frameFilter = clip.type == ClipType.IMAGE ?
                                    "-loop 1 -t " + clip.duration + " -framerate " + templateSettings.settings.getFrameRate() + " " : "";

                    cmd.append(templateSettings.isTemplateCommand ? "" : frameFilter)
                            .append("-i \"").append(inputPath).append("\" ");

                    if (clip.type == ClipType.VIDEO && clip.removeBackground) {
                        String maskPath = inputPath + "_mask.mp4";
                        if (IOHelper.isFileExist(maskPath)) {
                            cmd.append("-i \"").append(maskPath).append("\" ");
                        }
                    }
                    break;
                case AUDIO:
                    cmd.append("-i \"").append(inputPath).append("\" ");
                    break;
                case TEXT:
                    cmd.append("-f lavfi -i \"nullsrc=size=")
                            .append(templateSettings.settings.getRenderVideoWidth(templateSettings.isTemplateCommand)).append("x").append(templateSettings.settings.getRenderVideoHeight(templateSettings.isTemplateCommand))
                            .append(":rate=").append(templateSettings.settings.getFrameRate()).append(",format=yuva420p\"").append(" ");
                    break;
            }
        }

        String baseTag = "[base]";
        filterComplex.append("[").append(inputLayerIndex).append(":v]trim=duration=").append(templateSettings.timeline.duration).append(",setpts=PTS-STARTPTS").append(baseTag).append(";\n");
        tags.storeTag(baseTag);
        inputLayerIndex++;
        inputMediaIndex++;

        for (int clipIndex = 0; clipIndex < templateSettings.clips.length; clipIndex++) {
            Clip clip = templateSettings.clips[clipIndex];
            String clipLabel = "[video-" + inputMediaIndex + "]";
            String transparentLabel = "[trans-" + inputMediaIndex + "]";
            String outputLabel = "[trans-video-" + inputMediaIndex + "]";
            String audioLabel = "[audio-" + inputMediaIndex + "]";

            float fillingTransitionDuration = 0;
            if (clip.endTransition != null && clip.endTransitionEnabled && !"none".equals(clip.endTransition.effect.style)) {
                switch (clip.endTransition.mode) {
                    case OVERLAP:
                        fillingTransitionDuration = clip.endTransition.duration / 2;
                        break;
                    case BEGIN_SECOND:
                        fillingTransitionDuration = clip.endTransition.duration;
                        break;
                    case END_FIRST:
                        fillingTransitionDuration = 0;
                        break;
                }
            }

            float extendMediaDuration = Math.min(clip.endClipTrim, fillingTransitionDuration);
            float freezeFrameDuration = Math.max(fillingTransitionDuration - clip.endClipTrim, 0);

            switch (clip.type) {
                case VIDEO:
                case IMAGE:
                    filterComplex.append("[").append(inputLayerIndex).append(":v]")
                            .append("trim=duration=").append(clip.duration + fillingTransitionDuration).append(",")
                            .append("setpts=PTS-STARTPTS+").append(clip.startTime).append("/TB").append(transparentLabel).append(";\n");
                    inputLayerIndex++;

                    int sourceInputIndex = inputLayerIndex;
                    inputLayerIndex++;

                    boolean hasVideoMask = false;
                    if (clip.type == ClipType.VIDEO && clip.removeBackground) {
                        String maskPath = IOHelper.CombinePath(templateSettings.data.getProjectPath(), clip.getClipName() + "_mask.mp4");
                        if (IOHelper.isFileExist(maskPath)) {
                            hasVideoMask = true;
                            inputLayerIndex++;
                        }
                    }

                    String trimFilter = templateSettings.isTrimAllowed ?
                                    "trim=duration=" + (clip.duration + fillingTransitionDuration) :
                                    clip.type == ClipType.VIDEO ?
                                            "trim=start=" + clip.startClipTrim + ":end=" + (clip.startClipTrim + clip.duration + extendMediaDuration) :
                                            "trim=duration=" + (clip.duration + fillingTransitionDuration);

                    if (hasVideoMask) {
                        filterComplex.append("[").append(sourceInputIndex).append(":v]").append(trimFilter).append(",setpts=PTS-STARTPTS[v-raw-").append(clipIndex).append("];\n")
                                     .append("[").append(sourceInputIndex + 1).append(":v]").append(trimFilter).append(",setpts=PTS-STARTPTS[v-mask-").append(clipIndex).append("];\n")
                                     .append("[v-raw-").append(clipIndex).append("][v-mask-").append(clipIndex).append("]alphamerge,");
                    } else {
                        filterComplex.append("[").append(sourceInputIndex).append(":v]").append(trimFilter).append(",");
                    }

                    float radiansRotation = (float) Math.toRadians(clip.videoProperties.getValue(VideoProperties.ValueType.Rot));
                    String scaleXCmd = templateSettings.settings.isStretchToFull() ?
                            String.valueOf(templateSettings.settings.getRenderVideoWidth(templateSettings.isTemplateCommand)) :
                            "iw*" + clip.videoProperties.getValue(VideoProperties.ValueType.ScaleX);
                    String scaleYCmd = templateSettings.settings.isStretchToFull() ?
                            String.valueOf(templateSettings.settings.getRenderVideoHeight(templateSettings.isTemplateCommand)) :
                            "ih*" + clip.videoProperties.getValue(VideoProperties.ValueType.ScaleY);

                    filterComplex.append("scale=").append(scaleXCmd).append(":").append(scaleYCmd).append(",")
                            .append("rotate=").append(radiansRotation).append(":ow=rotw(").append(radiansRotation).append("):oh=roth(").append(radiansRotation).append(")")
                            .append(":fillcolor=0x00000000").append(",")
                            .append("hue=h=").append(clip.videoProperties.getValue(VideoProperties.ValueType.Hue))
                            .append(":s=").append(clip.videoProperties.getValue(VideoProperties.ValueType.Saturation))
                            .append(":b=").append(clip.videoProperties.getValue(VideoProperties.ValueType.Brightness)).append(",")
                            .append("format=yuva420p,colorchannelmixer=aa=").append(clip.videoProperties.getValue(VideoProperties.ValueType.Opacity)).append(",")
                            .append("setpts='(PTS-STARTPTS)/").append(clip.videoProperties.getValue(VideoProperties.ValueType.Speed)).append("+").append(clip.startTime).append("/TB'").append(",")
                            .append("tpad=stop_mode=clone:stop_duration=").append(freezeFrameDuration).append(clipLabel).append(";\n");

                    filterComplex.append(transparentLabel).append(clipLabel)
                            .append("overlay=").append(clip.videoProperties.getValue(VideoProperties.ValueType.PosX)).append(":").append(clip.videoProperties.getValue(VideoProperties.ValueType.PosY))
                            .append(":enable='between(t,").append(clip.startTime).append(",").append(clip.startTime + clip.duration + fillingTransitionDuration).append(")'").append(",")
                            .append("fps=").append(templateSettings.settings.getFrameRate())
                            .append(clip.isReverse ? ",reverse" : "")
                            .append(outputLabel).append(";\n");

                    tags.storeTag(clip, outputLabel);
                    break;
                case TEXT:
                    filterComplex.append("[").append(inputLayerIndex).append(":v]")
                            .append("trim=duration=").append(clip.duration).append(",")
                            .append("setpts=PTS-STARTPTS+").append(clip.startTime).append("/TB").append(transparentLabel).append(";\n");

                    filterComplex.append(transparentLabel)
                            .append("drawtext=fontsize=").append(clip.fontSize)
                            .append(":text='").append(clip.textContent == null ? "" : clip.textContent.replace(":", "\\:").replace("'", "\\'"))
                            .append("':x=(w-text_w)/2:y=(h-text_h)/2")
                            .append(":enable='between(t,").append(clip.startTime).append(",").append(clip.startTime + clip.duration).append(")'").append(",")
                            .append("fps=").append(templateSettings.settings.getFrameRate())
                            .append(outputLabel).append(";\n");

                    tags.storeTag(clip, outputLabel);
                    inputLayerIndex++;
                    break;
                case AUDIO:
                    int delayMs = (int) (clip.startTime * 1000);
                    filterComplex.append("[").append(inputLayerIndex).append(":a]")
                            .append("atrim=start=").append(clip.startClipTrim).append(":end=").append(clip.startClipTrim + clip.duration).append(",")
                            .append("adelay=").append(delayMs).append("|").append(delayMs).append(",")
                            .append("asetpts=PTS-STARTPTS")
                            .append(clip.isReverse ? ",areverse" : "")
                            .append(audioLabel).append(";\n");
                    inputLayerIndex++;
                    break;
            }
            inputMediaIndex++;
        }

        for (int i = 0; i < templateSettings.clips.length - 1; i++) {
            Clip clipA = templateSettings.clips[i];
            Clip clipB = templateSettings.clips[i + 1];
            if (clipA.endTransition != null && clipA.endTransitionEnabled)
                filterComplex.append(FXCommandEmitter.emitTransition(clipA, clipB, clipA.endTransition, tags));
        }

        int layer = 0;
        FfmpegFilterComplexTags.FilterComplexInfo baseInfo = tags.useTag(baseTag);
        while (tags.tagsMapToUsableTagIndex.size() > 0) {
            Clip clip = tags.tagsMapToUsableTagIndex.get(0).getKey();
            String prevOutputLabel = "[layer-" + (layer - 1) + "]";
            String outputLabel = "[layer-" + layer + "]";

            filterComplex.append((layer == 0 ? baseInfo.tag : (tags.useTag(prevOutputLabel).tag))).append(tags.useTag(clip).tag)
                    .append("overlay=enable='between(t,").append(clip.startTime).append(",").append(clip.startTime + clip.duration).append(")'")
                    .append(outputLabel).append(";\n");

            tags.storeTag(outputLabel);
            layer++;
        }

        cmd.append("-filter_complex \"").append(filterComplex).append("\" ");
        cmd.append("-map \"[layer-").append(layer - 1).append("]\" ");
        // Add final output params
        cmd.append("-c:v libx264 -preset superfast -y \"").append(IOHelper.CombinePath(templateSettings.data.getProjectPath(), "output.mp4")).append("\"");

        return cmd.toString();
    }

    public static class FfmpegFilterComplexTags {
        public List<Pair<Clip, FilterComplexInfo>> tagsMapToUsableTagIndex = new ArrayList<>();
        public List<String> usableTag = new ArrayList<>();

        public void storeTag(String tag) {
            usableTag.add(tag);
        }

        public void storeTag(String tag, int index) {
            usableTag.add(index, tag);
        }

        public void storeTag(Clip clip, String tag) {
            tagsMapToUsableTagIndex.add(new Pair<>(clip, new FilterComplexInfo(tag, tagsMapToUsableTagIndex.size())));
        }

        public void storeTag(Clip clip, String tag, int index) {
            tagsMapToUsableTagIndex.add(index, new Pair<>(clip, new FilterComplexInfo(tag, index)));
        }

        public FilterComplexInfo useTag(Clip clip) {
            for (int i = 0; i < tagsMapToUsableTagIndex.size(); i++) {
                if (tagsMapToUsableTagIndex.get(i).getKey() == clip) {
                    FilterComplexInfo info = tagsMapToUsableTagIndex.get(i).getValue();
                    tagsMapToUsableTagIndex.remove(i);
                    return info;
                }
            }
            return null;
        }

        public FilterComplexInfo useTag(Clip clip, Clip newClip) {
            FilterComplexInfo info = useTag(clip);
            if (info != null) {
                storeTag(newClip, info.tag, info.index);
            }
            return info;
        }

        public FilterComplexInfo useTag(String tag) {
            for (int i = 0; i < usableTag.size(); i++) {
                if (usableTag.get(i).equals(tag)) {
                    String t = usableTag.get(i);
                    usableTag.remove(i);
                    return new FilterComplexInfo(t, i);
                }
            }
            return null;
        }

        public static class Pair<K, V> {
            private final K key;
            private final V value;
            public Pair(K key, V value) { this.key = key; this.value = value; }
            public K getKey() { return key; }
            public V getValue() { return value; }
        }

        public static class FilterComplexInfo {

            public String tag;
            public int index;
            public FilterComplexInfo(String tag, int index) {
                this.tag = tag;
                this.index = index;
            }
        }
    }

    public static class FfmpegRenderQueue {
        private Queue<FfmpegRenderQueueInfo> taskQueue = new LinkedList<>();
        private boolean isRunning = false;

        public void enqueue(FfmpegRenderQueueInfo task) {
            taskQueue.add(task);
            if (!isRunning) {
                runNext();
            }
        }

        private void runNext() {
            FfmpegRenderQueueInfo task = taskQueue.poll();
            if (task == null) {
                isRunning = false;
                return;
            }
            isRunning = true;
            task.task.run();
        }

        public void taskCompleted() {
            runNext();
        }

        public static class FfmpegRenderQueueInfo {
            public Runnable task;
            public String taskName;
            public FfmpegRenderQueueInfo(String taskName, Runnable task) {
                this.task = task;
                this.taskName = taskName;
            }
        }
    }

    public static class RenderSettings {
        public VideoSettings settings;
        public Timeline timeline;
        public ProjectData data;
        public Clip[] clips;
        public int renderingIndex;
        public boolean isTemplateCommand;
        public boolean isTrimAllowed;

        public RenderSettings(VideoSettings settings, Timeline timeline, Clip[] clips, ProjectData data, int renderingIndex, boolean isTemplateCommand, boolean isTrimAllowed) {
            this.settings = settings;
            this.timeline = timeline;
            this.clips = clips;
            this.data = data;
            this.renderingIndex = renderingIndex;
            this.isTemplateCommand = isTemplateCommand;
            this.isTrimAllowed = isTrimAllowed;
        }

        public void setClips(Clip[] clips) {
            this.clips = clips;
        }
    }
}