package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.editing.*;
import com.vanvatcorporation.doubleclips.history.PropertyChangeCommand;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;

import java.util.function.Consumer;

public class PropertyPanel extends VBox {

    private final PropertyContext context;

    public PropertyPanel(PropertyContext context) {
        super(15);
        this.context = context;
        this.setPadding(new Insets(16));
    }

    public void update() {
        getChildren().clear();

        Clip selectedClip = context.getSelectedClip();
        if (selectedClip == null) {
            Label placeholder = new Label("Select a clip to view properties");
            placeholder.getStyleClass().add("text-muted");
            getChildren().add(placeholder);
            return;
        }

        Label sectionTitle = new Label("Clip Properties");
        sectionTitle.getStyleClass().add("text-bold");
        sectionTitle.setStyle("-fx-font-size: 16px;");

        VBox fields = new VBox(10);
        fields.getChildren().add(buildSectionDivider("Basic"));

        fields.getChildren().add(buildPropertyField("Name", selectedClip.getClipName(), newValue -> {
            String oldVal = selectedClip.getClipName();
            if (newValue.equals(oldVal)) return;
            context.executePropertyChange("Change Name", () -> {
                selectedClip.setClipName(newValue);
                context.refreshTimelineUI();
                context.saveProject();
            }, () -> {
                selectedClip.setClipName(oldVal);
                context.refreshTimelineUI();
                context.saveProject();
            });
        }));

        fields.getChildren().add(buildPropertyField("Start Time", String.valueOf(selectedClip.startTime), newValue -> {
            try {
                float val = Float.parseFloat(newValue);
                float oldVal = selectedClip.startTime;
                if (val == oldVal) return;
                context.executePropertyChange("Change Start Time", () -> {
                    selectedClip.startTime = val;
                    context.refreshTimelineUI();
                    context.saveProject();
                }, () -> {
                    selectedClip.startTime = oldVal;
                    context.refreshTimelineUI();
                    context.saveProject();
                });
            } catch (Exception ignored) {}
        }));

        fields.getChildren().add(buildPropertyField("Duration", String.valueOf(selectedClip.duration), newValue -> {
            try {
                float val = Float.parseFloat(newValue);
                float oldVal = selectedClip.duration;
                if (val == oldVal) return;
                context.executePropertyChange("Change Duration", () -> {
                    selectedClip.duration = val;
                    context.refreshTimelineUI();
                    context.saveProject();
                }, () -> {
                    selectedClip.duration = oldVal;
                    context.refreshTimelineUI();
                    context.saveProject();
                });
            } catch (Exception ignored) {}
        }));

        fields.getChildren().add(buildPropertyField("Start Trim", String.valueOf(selectedClip.startClipTrim), newValue -> {
            try {
                float val = Float.parseFloat(newValue);
                float oldVal = selectedClip.startClipTrim;
                if (val == oldVal) return;
                context.executePropertyChange("Change Start Trim", () -> {
                    selectedClip.setStartClipTrim(val);
                    context.refreshTimelineUI();
                    context.saveProject();
                }, () -> {
                    selectedClip.setStartClipTrim(oldVal);
                    context.refreshTimelineUI();
                    context.saveProject();
                });
            } catch (Exception ignored) {}
        }));

        fields.getChildren().add(buildPropertyField("End Trim", String.valueOf(selectedClip.endClipTrim), newValue -> {
            try {
                float val = Float.parseFloat(newValue);
                float oldVal = selectedClip.endClipTrim;
                if (val == oldVal) return;
                context.executePropertyChange("Change End Trim", () -> {
                    selectedClip.setEndClipTrim(val);
                    context.refreshTimelineUI();
                    context.saveProject();
                }, () -> {
                    selectedClip.setEndClipTrim(oldVal);
                    context.refreshTimelineUI();
                    context.saveProject();
                });
            } catch (Exception ignored) {}
        }));

        if (selectedClip.type == ClipType.TEXT) {
            fields.getChildren().add(buildSectionDivider("Text"));
            fields.getChildren().add(buildTextAreaPropertyField("Text Content", selectedClip.textContent != null ? selectedClip.textContent : "", newValue -> {
                String oldVal = selectedClip.textContent;
                if (newValue.equals(oldVal)) return;
                context.executePropertyChange("Change Text Content", () -> {
                    selectedClip.textContent = newValue;
                    context.refreshTimelineUI();
                    context.saveProject();
                }, () -> {
                    selectedClip.textContent = oldVal;
                    context.refreshTimelineUI();
                    context.saveProject();
                });
            }));

            fields.getChildren().add(buildPropertyField("Font Size", String.valueOf(selectedClip.fontSize), newValue -> {
                try {
                    float val = Float.parseFloat(newValue);
                    float oldVal = selectedClip.fontSize;
                    if (val == oldVal) return;
                    context.executePropertyChange("Change Font Size", () -> {
                        selectedClip.fontSize = val;
                        context.refreshTimelineUI();
                        context.saveProject();
                    }, () -> {
                        selectedClip.fontSize = oldVal;
                        context.refreshTimelineUI();
                        context.saveProject();
                    });
                } catch (Exception ignored) {}
            }));
        }

        if (selectedClip.type != ClipType.EFFECT) {
            fields.getChildren().add(buildSectionDivider("Transform"));
            addKeyframeableField(fields, "Position X", selectedClip.videoProperties.valuePosX, VideoProperties.ValueType.PosX, selectedClip);
            addKeyframeableField(fields, "Position Y", selectedClip.videoProperties.valuePosY, VideoProperties.ValueType.PosY, selectedClip);
            addKeyframeableField(fields, "Rotation", selectedClip.videoProperties.valueRot, VideoProperties.ValueType.Rot, selectedClip);
            addKeyframeableField(fields, "Scale X", selectedClip.videoProperties.valueScaleX, VideoProperties.ValueType.ScaleX, selectedClip);
            addKeyframeableField(fields, "Scale Y", selectedClip.videoProperties.valueScaleY, VideoProperties.ValueType.ScaleY, selectedClip);

            fields.getChildren().add(buildSectionDivider("Color & Effects"));
            addKeyframeableField(fields, "Opacity", selectedClip.videoProperties.valueOpacity, VideoProperties.ValueType.Opacity, selectedClip);
            addKeyframeableField(fields, "Speed", selectedClip.videoProperties.valueSpeed, VideoProperties.ValueType.Speed, selectedClip);
            addKeyframeableField(fields, "Hue", selectedClip.videoProperties.valueHue, VideoProperties.ValueType.Hue, selectedClip);
            addKeyframeableField(fields, "Saturation", selectedClip.videoProperties.valueSaturation, VideoProperties.ValueType.Saturation, selectedClip);
            addKeyframeableField(fields, "Brightness", selectedClip.videoProperties.valueBrightness, VideoProperties.ValueType.Brightness, selectedClip);
            addKeyframeableField(fields, "Temperature", selectedClip.videoProperties.valueTemperature, VideoProperties.ValueType.Temperature, selectedClip);
        }

        fields.getChildren().add(buildSectionDivider("Toggles"));
        if (selectedClip.type == ClipType.VIDEO || selectedClip.type == ClipType.AUDIO) {
            fields.getChildren().add(buildTogglePropertyField("Mute Audio", selectedClip.isMute, newValue -> {
                boolean oldVal = selectedClip.isMute;
                if (newValue == oldVal) return;
                context.executePropertyChange("Toggle Mute", () -> {
                    selectedClip.isMute = newValue;
                    context.refreshTimelineUI();
                    context.saveProject();
                }, () -> {
                    selectedClip.isMute = oldVal;
                    context.refreshTimelineUI();
                    context.saveProject();
                });
            }));

            fields.getChildren().add(buildTogglePropertyField("Reverse", selectedClip.isReverse, newValue -> {
                boolean oldVal = selectedClip.isReverse;
                if (newValue == oldVal) return;
                context.executePropertyChange("Toggle Reverse", () -> {
                    selectedClip.isReverse = newValue;
                    context.refreshTimelineUI();
                    context.saveProject();
                }, () -> {
                    selectedClip.isReverse = oldVal;
                    context.refreshTimelineUI();
                    context.saveProject();
                });
            }));
        }

        if (selectedClip.type == ClipType.VIDEO || selectedClip.type == ClipType.IMAGE) {
            fields.getChildren().add(buildTogglePropertyField("Remove Background", selectedClip.removeBackground, newValue -> {
                boolean oldVal = selectedClip.removeBackground;
                if (newValue == oldVal) return;
                context.executePropertyChange("Toggle Remove Background", () -> {
                    selectedClip.removeBackground = newValue;
                    context.refreshTimelineUI();
                    context.saveProject();
                }, () -> {
                    selectedClip.removeBackground = oldVal;
                    context.refreshTimelineUI();
                    context.saveProject();
                });
            }));
        }

        getChildren().add(buildSectionDivider("Animation"));
        fields.getChildren().add(buildPropertyField("In Animation Type", selectedClip.inAnimation != null ? selectedClip.inAnimation.type : "none", newValue -> {
            String oldVal = selectedClip.inAnimation != null ? selectedClip.inAnimation.type : "none";
            if (newValue.equals(oldVal)) return;
            context.executePropertyChange("Change In Animation Type", () -> {
                if (selectedClip.inAnimation == null) selectedClip.inAnimation = new AnimationClip(newValue, 0.5f);
                else selectedClip.inAnimation.type = newValue;
                context.refreshTimelineUI();
                context.saveProject();
            }, () -> {
                if (selectedClip.inAnimation == null) selectedClip.inAnimation = new AnimationClip(oldVal, 0.5f);
                else selectedClip.inAnimation.type = oldVal;
                context.refreshTimelineUI();
                context.saveProject();
            });
        }));

        fields.getChildren().add(buildPropertyField("In Animation Duration (s)", String.valueOf(selectedClip.inAnimation != null ? selectedClip.inAnimation.duration : 0.5f), newValue -> {
            try {
                float val = Float.parseFloat(newValue);
                float oldVal = selectedClip.inAnimation != null ? selectedClip.inAnimation.duration : 0.5f;
                if (val == oldVal) return;
                context.executePropertyChange("Change In Animation Duration", () -> {
                    if (selectedClip.inAnimation == null) selectedClip.inAnimation = new AnimationClip("none", val);
                    else selectedClip.inAnimation.duration = val;
                    context.refreshTimelineUI();
                    context.saveProject();
                }, () -> {
                    if (selectedClip.inAnimation == null) selectedClip.inAnimation = new AnimationClip("none", oldVal);
                    else selectedClip.inAnimation.duration = oldVal;
                    context.refreshTimelineUI();
                    context.saveProject();
                });
            } catch (Exception ignored) {}
        }));

        getChildren().addAll(sectionTitle, fields);

        if (selectedClip.type != ClipType.EFFECT) {
            getChildren().add(buildKeyframesSection(selectedClip));
        }

        Clip transClip = context.getSelectedTransitionSourceClip();
        if (transClip != null && transClip.endTransition != null) {
            getChildren().add(buildTransitionSection(transClip));
        }
    }

    private void addKeyframeableField(VBox parent, String label, float currentVal, VideoProperties.ValueType type, Clip clip) {
        parent.getChildren().add(buildKeyframeablePropertyField(label, String.valueOf(currentVal), newValue -> {
            try {
                float val = Float.parseFloat(newValue);
                float oldVal = clip.videoProperties.getValue(type);
                if (val == oldVal) return;
                context.executePropertyChange("Change " + label, () -> {
                    clip.videoProperties.setValue(val, type);
                    updateKeyframeValueIfPresent(clip, type, val);
                    context.refreshTimelineUI();
                    context.saveProject();
                }, () -> {
                    clip.videoProperties.setValue(oldVal, type);
                    updateKeyframeValueIfPresent(clip, type, oldVal);
                    context.refreshTimelineUI();
                    context.saveProject();
                });
            } catch (Exception ignored) {}
        }, clip, type));
    }

    private VBox buildKeyframesSection(Clip clip) {
        VBox box = new VBox(8);
        box.setPadding(new Insets(0, 0, 8, 0));
        Label title = new Label("Keyframes");
        title.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

        int count = clip.keyframes != null && clip.keyframes.keyframes != null ? clip.keyframes.keyframes.size() : 0;
        Label countLbl = new Label(count + " keyframe" + (count == 1 ? "" : "s"));
        countLbl.getStyleClass().add("text-muted");
        countLbl.setStyle("-fx-font-size: 11px;");

        HBox btnRow = new HBox(6);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        Button addBtn = buildButton("+ Add at Playhead", e -> context.handleAddKeyframe(), "import-media-button");
        Button clearBtn = buildButton("Clear All", e -> context.handleClearKeyframes(), "tool-button");
        Button importBtn = buildButton("Import", e -> context.handleImportKeyframes(), "tool-button");
        Button exportBtn = buildButton("Export", e -> context.handleExportKeyframes(), "tool-button");

        btnRow.getChildren().addAll(addBtn, clearBtn, importBtn, exportBtn);

        HBox easingRow = new HBox(8);
        easingRow.setAlignment(Pos.CENTER_LEFT);
        Label easingLbl = new Label("Easing");
        easingLbl.setStyle("-fx-font-size: 11px;");
        easingLbl.getStyleClass().add("text-muted");

        ComboBox<EasingType> easingCombo = new ComboBox<>();
        easingCombo.getItems().addAll(EasingType.values());
        easingCombo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(easingCombo, Priority.ALWAYS);

        Keyframe currentKf = clip.keyframes.getKeyframeAtTime(clip, context.getCurrentTime());
        if (currentKf != null) easingCombo.setValue(currentKf.easing);
        else easingCombo.setDisable(true);

        context.addPropertyUpdater(() -> {
            float time = context.getTempTime() >= 0 ? context.getTempTime() : context.getCurrentTime();
            if (clip.keyframes != null) {
                Keyframe k = clip.keyframes.getKeyframeAtTime(clip, time);
                if (k != null) {
                    easingCombo.setValue(k.easing);
                    easingCombo.setDisable(false);
                } else {
                    easingCombo.setDisable(true);
                }
            }
        });

        easingCombo.setOnAction(e -> {
            Keyframe k = clip.keyframes.getKeyframeAtTime(clip, context.getCurrentTime());
            if (k != null && easingCombo.isShowing()) {
                EasingType oldE = k.easing;
                EasingType newE = easingCombo.getValue();
                if (oldE == newE) return;
                context.executePropertyChange("Change Keyframe Easing", () -> {
                    k.easing = newE;
                    context.saveProject();
                }, () -> {
                    k.easing = oldE;
                    context.saveProject();
                });
            }
        });

        easingRow.getChildren().addAll(easingLbl, easingCombo);
        box.getChildren().addAll(buildSectionDivider("Keyframes"), countLbl, btnRow, easingRow);

        if (count > 0) {
            VBox kfList = new VBox(4);
            for (Keyframe k : clip.keyframes.keyframes) {
                HBox kfRow = new HBox(8);
                kfRow.setAlignment(Pos.CENTER_LEFT);
                kfRow.setPadding(new Insets(6, 8, 6, 8));
                kfRow.setStyle("-fx-background-color: transparent; -fx-background-radius: 4px; -fx-cursor: hand;");

                FontIcon icon = new FontIcon(MaterialDesignR.RHOMBUS);
                icon.setIconColor(Color.valueOf("#4A90E2"));
                icon.setIconSize(12);

                Label timeLbl = new Label(String.format("Keyframe at %.2fs", k.getLocalTime()));
                timeLbl.setStyle("-fx-font-size: 11px;");

                kfRow.getChildren().addAll(icon, timeLbl);
                kfRow.setOnMouseClicked(e -> {
                    context.updateCurrentTime(k.getGlobalTime(clip));
                    context.refreshTimelineUI();
                });

                kfRow.setOnMouseEntered(e -> kfRow.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 4px; -fx-cursor: hand;"));
                kfRow.setOnMouseExited(e -> kfRow.setStyle("-fx-background-color: transparent; -fx-background-radius: 4px; -fx-cursor: hand;"));

                kfList.getChildren().add(kfRow);
            }
            box.getChildren().add(kfList);
        }

        return box;
    }

    private Button buildButton(String text, Consumer<javafx.event.ActionEvent> action, String styleClass) {
        Button b = new Button(text);
        if (styleClass != null) b.getStyleClass().add(styleClass);
        b.setOnAction(e -> action.accept(e));
        return b;
    }

    private VBox buildTransitionSection(Clip clip) {
        TransitionClip tc = clip.endTransition;
        VBox box = new VBox(10);
        box.getStyleClass().add("transition-properties-panel");

        VBox typePicker = new VBox(4);
        Label typeLabel = new Label("Type");
        typeLabel.getStyleClass().add("text-muted");
        typeLabel.setStyle("-fx-font-size: 11px;");

        ComboBox<String> typeCombo = new ComboBox<>();
        com.vanvatcorporation.doubleclips.FXCommandEmitter.FXRegistry.transitionFXMap.forEach((k, v) -> typeCombo.getItems().add(v));
        typeCombo.getItems().sort(String::compareToIgnoreCase);

        String currentStyle = tc.effect != null ? tc.effect.style : "none";
        com.vanvatcorporation.doubleclips.FXCommandEmitter.FXRegistry.transitionFXMap.entrySet().stream()
                .filter(e -> e.getKey().equals(currentStyle)).findFirst().ifPresent(e -> typeCombo.setValue(e.getValue()));

        typeCombo.setMaxWidth(Double.MAX_VALUE);
        typeCombo.setOnAction(e -> {
            String chosen = typeCombo.getValue();
            com.vanvatcorporation.doubleclips.FXCommandEmitter.FXRegistry.transitionFXMap.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(chosen)).findFirst().ifPresent(entry -> {
                        if (tc.effect == null) tc.effect = new EffectTemplate(entry.getKey(), tc.duration, tc.startTime);
                        else tc.effect.style = entry.getKey();
                        context.saveProject();
                    });
        });
        typePicker.getChildren().addAll(typeLabel, typeCombo);

        VBox modePicker = new VBox(4);
        Label modeLabel = new Label("Mode");
        modeLabel.getStyleClass().add("text-muted");
        modeLabel.setStyle("-fx-font-size: 11px;");

        ComboBox<String> modeCombo = new ComboBox<>();
        modeCombo.getItems().addAll("End First", "Overlap", "Begin Second");
        switch (tc.mode) {
            case END_FIRST -> modeCombo.setValue("End First");
            case OVERLAP -> modeCombo.setValue("Overlap");
            case BEGIN_SECOND -> modeCombo.setValue("Begin Second");
        }
        modeCombo.setMaxWidth(Double.MAX_VALUE);
        modeCombo.setOnAction(e -> {
            switch (modeCombo.getValue()) {
                case "End First" -> tc.mode = TransitionClip.TransitionMode.END_FIRST;
                case "Overlap" -> tc.mode = TransitionClip.TransitionMode.OVERLAP;
                case "Begin Second" -> tc.mode = TransitionClip.TransitionMode.BEGIN_SECOND;
            }
            context.saveProject();
        });
        modePicker.getChildren().addAll(modeLabel, modeCombo);

        VBox durBox = new VBox(4);
        Label durLabel = new Label("Duration (s)");
        durLabel.getStyleClass().add("text-muted");
        durLabel.setStyle("-fx-font-size: 11px;");

        Spinner<Double> durSpinner = new Spinner<>(0.0, 10.0, (double) tc.duration, 0.1);
        durSpinner.setEditable(true);
        durSpinner.setMaxWidth(Double.MAX_VALUE);
        durSpinner.valueProperty().addListener((obs, old, nv) -> {
            tc.duration = nv.floatValue();
            if (tc.effect != null) tc.effect.duration = tc.duration;
            context.saveProject();
        });
        durBox.getChildren().addAll(durLabel, durSpinner);

        box.getChildren().addAll(buildSectionDivider("Transition"), typePicker, modePicker, durBox);
        return box;
    }

    private HBox buildSectionDivider(String label) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(12, 0, 4, 0));
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");
        Region line = new Region();
        line.setPrefHeight(1);
        line.setMaxHeight(1);
        line.setStyle("-fx-background-color: -color-fg-subtle;");
        HBox.setHgrow(line, Priority.ALWAYS);
        row.getChildren().addAll(lbl, line);
        return row;
    }

    private VBox buildPropertyField(String label, String value, Consumer<String> onUpdate) {
        VBox box = new VBox(4);
        Label lbl = new Label(label);
        lbl.getStyleClass().add("text-muted");
        lbl.setStyle("-fx-font-size: 11px;");

        TextField tf = new TextField(value);
        tf.getStyleClass().add("editor-textfield");
        tf.setOnAction(e -> onUpdate.accept(tf.getText()));
        tf.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) onUpdate.accept(tf.getText());
        });

        box.getChildren().addAll(lbl, tf);
        box.setUserData(tf);
        return box;
    }

    private VBox buildTextAreaPropertyField(String label, String value, Consumer<String> onUpdate) {
        VBox box = new VBox(4);
        Label lbl = new Label(label);
        lbl.getStyleClass().add("text-muted");
        lbl.setStyle("-fx-font-size: 11px;");

        TextArea ta = new TextArea(value);
        ta.getStyleClass().add("editor-textarea");
        ta.setPrefHeight(80);
        ta.setWrapText(true);
        ta.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) onUpdate.accept(ta.getText());
        });

        box.getChildren().addAll(lbl, ta);
        return box;
    }

    private HBox buildKeyframeablePropertyField(String label, String value, Consumer<String> onUpdate, Clip clip, VideoProperties.ValueType vType) {
        VBox fieldBox = buildPropertyField(label, value, onUpdate);
        HBox.setHgrow(fieldBox, Priority.ALWAYS);
        TextField tf = (TextField) fieldBox.getUserData();

        Button kfBtn = new Button();
        kfBtn.getStyleClass().add("tool-button");
        kfBtn.setPadding(new Insets(4));
        kfBtn.setMaxHeight(Double.MAX_VALUE);
        
        boolean hasKf = clip.keyframes != null && clip.keyframes.getKeyframeAtTime(clip, context.getCurrentTime()) != null;
        FontIcon diamondIcon = new FontIcon(hasKf ? MaterialDesignR.RHOMBUS : MaterialDesignR.RHOMBUS_OUTLINE);
        diamondIcon.setIconSize(14);
        diamondIcon.setIconColor(hasKf ? Color.valueOf("#4A90E2") : Color.valueOf("#888888"));
        kfBtn.setGraphic(diamondIcon);

        context.addPropertyUpdater(() -> {
            float time = context.getTempTime() >= 0 ? context.getTempTime() : context.getCurrentTime();
            if (clip.keyframes != null && !tf.isFocused()) {
                float interpolated = clip.keyframes.getValueAtTime(clip, time, vType);
                String displayStr = (interpolated == (long) interpolated) 
                    ? String.format("%d", (long) interpolated) 
                    : String.format("%.2f", interpolated).replaceAll("0*$", "").replaceAll("\\.$", "");
                tf.setText(displayStr);
            }
            boolean currentHasKf = clip.keyframes != null && clip.keyframes.getKeyframeAtTime(clip, time) != null;
            diamondIcon.setIconColor(currentHasKf ? Color.valueOf("#4A90E2") : Color.valueOf("#888888"));
            diamondIcon.setIconCode(currentHasKf ? MaterialDesignR.RHOMBUS : MaterialDesignR.RHOMBUS_OUTLINE);
        });

        kfBtn.setOnAction(e -> {
            if (clip.keyframes == null) clip.keyframes = new AnimatedProperty();
            Keyframe k = clip.keyframes.getKeyframeAtTime(clip, context.getCurrentTime());
            if (k != null) {
                clip.keyframes.keyframes.remove(k);
            } else {
                clip.keyframes.keyframes.add(new Keyframe(context.getCurrentTime() - clip.startTime, new VideoProperties(clip.videoProperties), EasingType.LINEAR));
                clip.keyframes.sortKeyframe();
            }
            context.refreshTimelineUI();
            context.saveProject();
            context.updatePropertiesPane();
        });

        return new HBox(8, fieldBox, kfBtn);
    }

    private void updateKeyframeValueIfPresent(Clip clip, VideoProperties.ValueType type, float val) {
        if (clip.keyframes != null) {
            Keyframe k = clip.keyframes.getKeyframeAtTime(clip, context.getCurrentTime());
            if (k != null) k.value.setValue(val, type);
        }
    }

    private HBox buildTogglePropertyField(String label, boolean isSelected, Consumer<Boolean> onUpdate) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        Label lbl = new Label(label);
        lbl.getStyleClass().add("text-muted");
        lbl.setStyle("-fx-font-size: 11px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        ToggleButton toggle = new ToggleButton();
        toggle.getStyleClass().add("pill-toggle");
        toggle.setSelected(isSelected);
        toggle.setOnAction(e -> onUpdate.accept(toggle.isSelected()));
        row.getChildren().addAll(lbl, spacer, toggle);
        return row;
    }
}
