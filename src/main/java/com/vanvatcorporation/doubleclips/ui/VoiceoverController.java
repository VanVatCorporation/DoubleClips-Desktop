package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.constants.Constants;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.voiceover.EchoCanceller;
import com.vanvatcorporation.doubleclips.voiceover.VoiceEnhancer;
import com.vanvatcorporation.doubleclips.voiceover.VoiceRecorder;
import com.vanvatcorporation.doubleclips.voiceover.WavFiles;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;

import java.io.File;

/**
 * Runs a voiceover take: opens the Voiceover window (a dock panel that disappears when closed), records from the
 * microphone while the timeline plays from the playhead, shows a temporary clip that grows with the take, and when
 * the recording ends turns it into a real audio clip - one undo step.
 * <p>
 * The recording is cleaned up AFTER it stops (echo reduction first, then "Enhance voice"), because the speaker signal
 * the echo canceller needs is exactly the timeline's own mix, which can be rendered for any time span; that avoids a
 * live ring buffer and any clock drift between the playback and the microphone.
 */
final class VoiceoverController {

    /** What the editor offers the controller (implemented by {@link EditorWindow}). */
    interface Host {
        DockManager dock();
        /** Screen position (x, y) where the window should open: next to the editor's top-left area. */
        double[] windowPosition();
        float playhead();
        /** The track the take goes on: an existing one that is free from {@code start} on, else a new one at the end. */
        int chooseTrack(float start);
        /** Starts the timeline clock from the playhead at normal speed; it keeps running past the end of the timeline. */
        void beginPlayback();
        void endPlayback();
        void setProjectMuted(boolean muted);
        void liveShow(int track, float start);
        void liveUpdate(double seconds, float[] peaks, int blocks);
        void liveHide();
        /** The speaker mix of [t0, t0 + seconds) as mono samples at {@code rate} (background thread). */
        float[] audioReference(double t0, double seconds, int rate);
        ProjectData project();
        /** Adds the clip on {@code track} as ONE undo step, refreshes the timeline and saves the project. */
        void addClip(Clip clip, int track);
        void showError(String title, String message);
    }

    private final Host host;
    private final VoiceoverPanel panel;
    private VoiceRecorder recorder;
    private DockManager.DockPanel dockPanel;
    private boolean closing;

    // the current take
    private float startTime;
    private int track;
    private boolean echo, enhance, muted;
    private volatile double lastRms, lastPeak;
    private final float[][] peakBuf = new float[1][];
    private int frameCounter;

    private final AnimationTimer ticker = new AnimationTimer() {
        @Override public void handle(long now) {
            tick();
        }
    };

    VoiceoverController(Host host) {
        this.host = host;
        this.panel = new VoiceoverPanel(this::toggleRecording);
        panel.volumeProperty().addListener((o, a, b) -> {
            VoiceRecorder r = recorder;
            if (r != null) r.setGain(b.doubleValue() / 100.0);
        });
    }

    boolean isRecording() {
        return panel.state() == VoiceoverPanel.State.RECORDING;
    }

    /** The microphone button: opens the window (or brings it to the front when it is already open). */
    void open() {
        if (dockPanel != null) {
            if (dockPanel.stage != null) dockPanel.stage.toFront();
            return;
        }
        double[] pos = host.windowPosition();
        panel.reloadDevices();
        dockPanel = host.dock().openDismissablePanel("Voiceover", panel, 330, 590, pos[0], pos[1], this::onDismissed);
    }

    /** The window went away (closed, or removed from the row). A running take is ended and kept. */
    private void onDismissed() {
        dockPanel = null;
        if (!closing && isRecording()) stopRecording();
    }

    /** The editor is closing: drop a running take without making a clip. */
    void shutdown() {
        closing = true;
        if (isRecording()) {
            ticker.stop();
            VoiceRecorder r = recorder;
            if (r != null) r.abort();
            host.setProjectMuted(false);
            host.liveHide();
        }
    }

    /** Space / the play button while recording: ends the take. */
    void stopFromEditor() {
        if (isRecording()) stopRecording();
    }

    // ---- start / stop -----------------------------------------------------------------------------

    private void toggleRecording() {
        if (panel.state() == VoiceoverPanel.State.IDLE) startRecording();
        else if (panel.state() == VoiceoverPanel.State.RECORDING) stopRecording();
    }

    private void startRecording() {
        VoiceRecorder r = new VoiceRecorder();
        panel.setStatus("Waiting to record");
        try {
            r.setGain(panel.volumeLinear());
            r.start(panel.selectedDevice(), (rms, peak) -> { lastRms = rms; lastPeak = peak; });
        } catch (Exception | Error e) {
            panel.setState(VoiceoverPanel.State.IDLE, "Recording not started");
            host.showError("Voiceover", "The microphone could not be opened.\n\n" + e.getMessage()
                    + "\n\nOn macOS, allow the microphone for the app that runs the editor (System Settings > Privacy & Security > Microphone).");
            return;
        }
        recorder = r;
        startTime = host.playhead();
        track = host.chooseTrack(startTime);
        echo = panel.echoReduction();
        enhance = panel.enhanceVoice();
        muted = panel.muteProject();
        host.setProjectMuted(muted);
        host.liveShow(track, startTime);
        host.beginPlayback();
        frameCounter = 0;
        panel.setTimer(0);
        panel.setState(VoiceoverPanel.State.RECORDING, "Recording");
        ticker.start();
    }

    private void tick() {
        VoiceRecorder r = recorder;
        if (r == null) return;
        if (r.failure() != null) {
            host.showError("Voiceover", "Recording stopped: " + r.failure());
            stopRecording();
            return;
        }
        double seconds = r.seconds();
        panel.setTimer(seconds);
        panel.setLevel(lastRms, lastPeak);
        if (seconds > 1.5 && r.isSilentSoFar()) {
            panel.setStatus("No sound is coming in - check the microphone and its permission");
        } else {
            panel.setStatus("Recording");
        }
        if ((frameCounter++ & 1) == 0) {
            int blocks = r.copyPeaks(0, peakBuf);
            host.liveUpdate(seconds, peakBuf[0] == null ? new float[0] : peakBuf[0], blocks);
        }
    }

    private void stopRecording() {
        if (recorder == null) return;
        ticker.stop();
        host.endPlayback();
        final VoiceRecorder r = recorder;
        recorder = null;
        final float[] take = r.stop();
        final int rate = r.sampleRate();
        host.setProjectMuted(false);

        if (take.length < rate / 10) {                       // under 0.1 s: a mis-click, nothing to keep
            host.liveHide();
            panel.setState(VoiceoverPanel.State.IDLE, "Recording not started");
            panel.setTimer(0);
            return;
        }
        panel.setState(VoiceoverPanel.State.PROCESSING, "Processing...");

        final float start = startTime;
        final int onTrack = track;
        final boolean doEcho = echo && !muted, doEnhance = enhance;
        Thread worker = new Thread(() -> process(take, rate, start, onTrack, doEcho, doEnhance), "VoiceoverProcess");
        worker.setDaemon(true);
        worker.start();
    }

    // ---- processing (background thread) ----------------------------------------------------------------

    private void process(float[] take, int rate, float start, int onTrack, boolean doEcho, boolean doEnhance) {
        float[] x = take;
        String note = null;
        try {
            if (doEcho) {
                status("Reducing echo...");
                float[] ref = host.audioReference(start, take.length / (double) rate, rate);
                EchoCanceller.Result res = EchoCanceller.cancel(x, ref, rate, p -> status("Reducing echo... " + Math.round(p * 100) + "%"));
                x = res.output;
                if (!res.applied) note = "no echo was found";
            }
            if (doEnhance) {
                status("Enhancing voice...");
                x = VoiceEnhancer.process(x, rate, new VoiceEnhancer.Options(), p -> status("Enhancing voice... " + Math.round(p * 100) + "%"));
            }
            ProjectData project = host.project();
            File clipsDir = new File(project.getProjectPath(), Constants.DEFAULT_CLIP_DIRECTORY);
            File previewDir = new File(project.getProjectPath(), Constants.DEFAULT_PREVIEW_CLIP_DIRECTORY);
            String base = nextName(clipsDir);
            WavFiles.writeMono16(new File(clipsDir, base + ".wav"), x, rate);
            WavFiles.writeMono16(new File(previewDir, base + ".wav"), WavFiles.resample(x, rate, 22050), 22050);

            final String fileName = base + ".wav";
            final float duration = x.length / (float) rate;
            final String finalNote = note;
            Platform.runLater(() -> finish(fileName, start, duration, onTrack, finalNote));
        } catch (Exception | Error e) {
            Platform.runLater(() -> {
                host.liveHide();
                panel.setState(VoiceoverPanel.State.IDLE, "Recording not started");
                host.showError("Voiceover", "The recording could not be saved.\n\n" + e);
            });
        }
    }

    private void status(String text) {
        Platform.runLater(() -> panel.setStatus(text));
    }

    private void finish(String fileName, float start, float duration, int onTrack, String note) {
        Clip clip = new Clip(fileName, start, duration, onTrack, ClipType.AUDIO, true, 0, 0);
        clip.trackIndex = onTrack;
        host.liveHide();
        host.addClip(clip, onTrack);
        panel.setState(VoiceoverPanel.State.IDLE, note == null ? "Recording not started" : "Done (" + note + ")");
        panel.setTimer(0);
    }

    /** "Voiceover1.wav", "Voiceover2.wav", ... the first one not used in the project's clip folder. */
    static String nextName(File clipsDir) {
        for (int n = 1; ; n++) {
            String base = "Voiceover" + n;
            if (!new File(clipsDir, base + ".wav").exists()) return base;
        }
    }
}
