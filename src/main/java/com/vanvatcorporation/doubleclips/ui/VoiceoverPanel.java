package com.vanvatcorporation.doubleclips.ui;

import atlantafx.base.controls.ToggleSwitch;
import com.vanvatcorporation.doubleclips.voiceover.VoiceRecorder;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

import java.util.List;
import java.util.prefs.Preferences;

/**
 * The Voiceover panel (the content of the dock window): record button, timer, status, level meter, input device,
 * input volume and the three switches (Echo reduction, Mute project, Enhance voice). It only shows and edits state;
 * {@link VoiceoverController} does the recording. Its settings are remembered between sessions.
 */
final class VoiceoverPanel extends VBox {

    enum State { IDLE, RECORDING, PROCESSING }

    private static final Preferences PREFS = Preferences.userRoot().node("com/vanvatcorporation/doubleclips/voiceover");

    private final Button recordBtn = new Button();
    private final Circle idleDot = new Circle(17, Color.web("#ff3b30"));
    private final Rectangle recSquare = new Rectangle(26, 26, Color.web("#ff3b30"));
    private final Label timer = new Label("00:00:00");
    private final Label status = new Label("Recording not started");
    private final Canvas meter = new Canvas(280, 16);
    private final ComboBox<VoiceRecorder.Device> deviceBox = new ComboBox<>();
    private final Label deviceStatus = new Label();
    private final Slider volume = new Slider(0, 100, PREFS.getInt("volume", 100));
    private final Spinner<Integer> volumeField = new Spinner<>();
    private final ToggleSwitch echoSwitch = new ToggleSwitch();
    private final ToggleSwitch muteSwitch = new ToggleSwitch();
    private final ToggleSwitch enhanceSwitch = new ToggleSwitch();

    private State state = State.IDLE;
    private double level;            // smoothed 0..1 for the meter
    private double peakHold;

    VoiceoverPanel(Runnable onRecordClicked) {
        setSpacing(12);
        setPadding(new Insets(14, 16, 16, 16));
        setFillWidth(true);
        setMinWidth(260);
        getStyleClass().add("voiceover-panel");

        Label title = new Label("Voiceover");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        // --- record button: a ring with a red dot (idle) or a red square (recording) ---
        Circle ring = new Circle(30, Color.TRANSPARENT);
        ring.setStroke(Color.web("#8a8a8a"));
        ring.setStrokeWidth(3);
        StackPane btnGraphic = new StackPane(ring, idleDot, recSquare);
        recSquare.setArcWidth(6);
        recSquare.setArcHeight(6);
        recSquare.setVisible(false);
        recordBtn.setGraphic(btnGraphic);
        recordBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2; -fx-cursor: hand;");
        recordBtn.setOnAction(e -> onRecordClicked.run());
        recordBtn.setTooltip(new Tooltip("Click to start recording"));

        timer.setStyle("-fx-font-size: 20px; -fx-font-family: 'Menlo', 'Consolas', monospace;");
        status.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        VBox top = new VBox(6, recordBtn, timer, status);
        top.setAlignment(Pos.CENTER);

        meter.setWidth(280);
        StackPane meterBox = new StackPane(meter);
        meterBox.setAlignment(Pos.CENTER);
        meterBox.setMinHeight(18);

        // --- input device ---
        Label deviceLabel = new Label("Input device");
        deviceLabel.setStyle("-fx-font-size: 12px;");
        deviceBox.setMaxWidth(Double.MAX_VALUE);
        deviceStatus.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
        Button refresh = new Button("\u21BB");
        refresh.getStyleClass().add("button-transparent");
        refresh.setTooltip(new Tooltip("Look for microphones again"));
        refresh.setOnAction(e -> reloadDevices());
        HBox deviceRow = new HBox(6, deviceBox, refresh);
        HBox.setHgrow(deviceBox, Priority.ALWAYS);
        deviceRow.setAlignment(Pos.CENTER_LEFT);
        VBox deviceCol = new VBox(4, deviceLabel, deviceRow, deviceStatus);

        // --- volume ---
        Label volLabel = new Label("Volume");
        volLabel.setStyle("-fx-font-size: 12px;");
        volumeField.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 100, (int) volume.getValue()));
        volumeField.setEditable(true);
        volumeField.setPrefWidth(74);
        volumeField.valueProperty().addListener((o, a, b) -> { if (b != null && Math.round(volume.getValue()) != b) volume.setValue(b); });
        volume.valueProperty().addListener((o, a, b) -> {
            int v = (int) Math.round(b.doubleValue());
            if (volumeField.getValue() == null || volumeField.getValue() != v) volumeField.getValueFactory().setValue(v);
            PREFS.putInt("volume", v);
        });
        // A typed number is committed on focus loss / Enter by the spinner; make sure bad text falls back.
        volumeField.getEditor().focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                try {
                    int v = Integer.parseInt(volumeField.getEditor().getText().trim());
                    volumeField.getValueFactory().setValue(Math.max(0, Math.min(100, v)));
                } catch (NumberFormatException ex) {
                    volumeField.getEditor().setText(String.valueOf(volumeField.getValue()));
                }
            }
        });
        HBox volRow = new HBox(10, volume, volumeField);
        HBox.setHgrow(volume, Priority.ALWAYS);
        volRow.setAlignment(Pos.CENTER_LEFT);
        VBox volCol = new VBox(4, volLabel, volRow);

        // --- switches ---
        echoSwitch.setSelected(PREFS.getBoolean("echo", false));
        muteSwitch.setSelected(PREFS.getBoolean("mute", true));        // on by default
        enhanceSwitch.setSelected(PREFS.getBoolean("enhance", false));
        echoSwitch.selectedProperty().addListener((o, a, b) -> PREFS.putBoolean("echo", b));
        muteSwitch.selectedProperty().addListener((o, a, b) -> PREFS.putBoolean("mute", b));
        enhanceSwitch.selectedProperty().addListener((o, a, b) -> PREFS.putBoolean("enhance", b));

        VBox echoRow = switchRow("Echo reduction",
                hint("Removes the project's sound from the recording when it is played through speakers (best effort; headphones are better)."),
                echoSwitch);
        VBox muteRow = switchRow("Mute project", hint("Sounds in all clips will be turned off while recording."), muteSwitch);
        VBox enhanceRow = switchRow("Enhance voice",
                hint("Automatically removes background noise, rumble, pops and mouth clicks, and evens out the level."),
                enhanceSwitch);

        deviceBox.valueProperty().addListener((o, a, b) -> {
            if (!loadingDevices && b != null) PREFS.put("device", b.name);
        });
        getChildren().addAll(title, top, meterBox, deviceCol, volCol, echoRow, muteRow, enhanceRow);
        reloadDevices();
        drawMeter();
    }

    private static Label hint(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
        return l;
    }

    private static VBox switchRow(String name, Label hint, ToggleSwitch sw) {
        Label l = new Label(name);
        l.setStyle("-fx-font-size: 12px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(l, spacer, sw);
        head.setAlignment(Pos.CENTER_LEFT);
        return new VBox(2, head, hint);
    }

    // ---- devices ------------------------------------------------------------------------------

    private boolean loadingDevices;

    void reloadDevices() {
        String wanted = deviceBox.getValue() != null ? deviceBox.getValue().name : PREFS.get("device", "System default");
        List<VoiceRecorder.Device> list = VoiceRecorder.listInputs();
        loadingDevices = true;
        try {
            deviceBox.getItems().setAll(list);
            VoiceRecorder.Device pick = list.get(0);
            for (VoiceRecorder.Device d : list) if (d.name.equals(wanted)) pick = d;
            deviceBox.setValue(pick);
        } finally {
            loadingDevices = false;
        }
        updateDeviceStatus();
    }

    private void updateDeviceStatus() {
        boolean any = deviceBox.getItems().size() > 1;
        deviceStatus.setText(any ? "Microphone connected" : "No microphone found");
    }

    // ---- what the controller reads / sets ------------------------------------------------------------

    VoiceRecorder.Device selectedDevice() { return deviceBox.getValue(); }
    double volumeLinear() { return volume.getValue() / 100.0; }
    DoubleProperty volumeProperty() { return volume.valueProperty(); }
    boolean echoReduction() { return echoSwitch.isSelected(); }
    boolean muteProject() { return muteSwitch.isSelected(); }
    boolean enhanceVoice() { return enhanceSwitch.isSelected(); }
    BooleanProperty muteProjectProperty() { return muteSwitch.selectedProperty(); }

    State state() { return state; }

    void setState(State s, String statusText) {
        state = s;
        boolean rec = s == State.RECORDING;
        idleDot.setVisible(!rec);
        recSquare.setVisible(rec);
        recordBtn.setDisable(s == State.PROCESSING);
        recordBtn.setTooltip(new Tooltip(rec ? "Click to end recording" : "Click to start recording"));
        deviceBox.setDisable(s != State.IDLE);
        // The three switches may be changed at any time except that a running take keeps what it started with.
        echoSwitch.setDisable(s != State.IDLE);
        muteSwitch.setDisable(s != State.IDLE);
        enhanceSwitch.setDisable(s == State.PROCESSING);
        status.setText(statusText);
        if (!rec) { level = 0; peakHold = 0; drawMeter(); }
    }

    void setStatus(String text) {
        status.setText(text);
    }

    void setTimer(double seconds) {
        int total = (int) (seconds * 100);
        int cs = total % 100, s = (total / 100) % 60, m = total / 6000;
        timer.setText(String.format("%02d:%02d:%02d", m, s, cs));
    }

    /** Called ~30 times a second while recording with the latest block's rms / peak (0..1). */
    void setLevel(double rms, double peak) {
        double target = Math.min(1.0, Math.sqrt(rms) * 1.6);      // a gentle curve so quiet speech still moves the bars
        level = target > level ? target : level * 0.85 + target * 0.15;
        peakHold = Math.max(peak, peakHold * 0.94);
        drawMeter();
    }

    private void drawMeter() {
        GraphicsContext g = meter.getGraphicsContext2D();
        double w = meter.getWidth(), h = meter.getHeight();
        g.clearRect(0, 0, w, h);
        int bars = 28;
        double gap = 3, bw = (w - gap * (bars - 1)) / bars;
        int lit = (int) Math.round(level * bars);
        for (int i = 0; i < bars; i++) {
            double frac = i / (double) bars;
            Color c = i < lit ? (frac > 0.85 ? Color.web("#ff3b30") : frac > 0.65 ? Color.web("#ffcc00") : Color.web("#34c759"))
                    : Color.web("#808080", 0.28);
            g.setFill(c);
            g.fillRoundRect(i * (bw + gap), 0, bw, h, 3, 3);
        }
    }
}
