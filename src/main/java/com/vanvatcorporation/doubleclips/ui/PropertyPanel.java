package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.AnimationChoices;
import com.vanvatcorporation.doubleclips.ClipAnimation;
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
        if(selectedClip.type == ClipType.AUDIO || selectedClip.type == ClipType.VIDEO) {
            fields.getChildren().add(buildPropertyField("Audio Volume", String.valueOf(selectedClip.getAudioVolume()), newValue -> {
                try {
                    float val = Float.parseFloat(newValue);
                    float oldVal = selectedClip.getAudioVolume();
                    if (val == oldVal) return;
                    context.executePropertyChange("Change Audio Volume", () -> {
                        selectedClip.setAudioVolume(val);
                        context.refreshTimelineUI();
                        context.saveProject();
                    }, () -> {
                        selectedClip.setAudioVolume(oldVal);
                        context.refreshTimelineUI();
                        context.saveProject();
                    });
                } catch (Exception ignored) {}
            }));
        }

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

            addTextStyleFields(fields, selectedClip);
        }

        if (selectedClip.type != ClipType.EFFECT) {
            fields.getChildren().add(buildSectionDivider("Transform"));
            addKeyframeableField(fields, "Position X", selectedClip.videoProperties.valuePosX, VideoProperties.ValueType.PosX, selectedClip);
            addKeyframeableField(fields, "Position Y", selectedClip.videoProperties.valuePosY, VideoProperties.ValueType.PosY, selectedClip);
            addKeyframeableField(fields, "Rotation", selectedClip.videoProperties.valueRot, VideoProperties.ValueType.Rot, selectedClip);
            addKeyframeableField(fields, "Scale X", selectedClip.videoProperties.valueScaleX, VideoProperties.ValueType.ScaleX, selectedClip);
            addKeyframeableField(fields, "Scale Y", selectedClip.videoProperties.valueScaleY, VideoProperties.ValueType.ScaleY, selectedClip);
            addKeyframeableField(fields, "Pivot X", selectedClip.videoProperties.valuePivotX, VideoProperties.ValueType.PivotX, selectedClip);
            addKeyframeableField(fields, "Pivot Y", selectedClip.videoProperties.valuePivotY, VideoProperties.ValueType.PivotY, selectedClip);

            fields.getChildren().add(buildSectionDivider("Color & Effects"));
            addKeyframeableField(fields, "Opacity", selectedClip.videoProperties.valueOpacity, VideoProperties.ValueType.Opacity, selectedClip);
            addKeyframeableField(fields, "Speed", selectedClip.videoProperties.valueSpeed, VideoProperties.ValueType.Speed, selectedClip);
            addKeyframeableField(fields, "Hue", selectedClip.videoProperties.valueHue, VideoProperties.ValueType.Hue, selectedClip);
            addKeyframeableField(fields, "Saturation", selectedClip.videoProperties.valueSaturation, VideoProperties.ValueType.Saturation, selectedClip);
            addKeyframeableField(fields, "Brightness", selectedClip.videoProperties.valueBrightness, VideoProperties.ValueType.Brightness, selectedClip);
            addKeyframeableField(fields, "Temperature", selectedClip.videoProperties.valueTemperature, VideoProperties.ValueType.Temperature, selectedClip);
        }

        fields.getChildren().add(buildSectionDivider("Toggles"));

        fields.getChildren().add(buildTogglePropertyField("Lock for Template", selectedClip.isLockedForTemplate, newValue -> {
            boolean oldVal = selectedClip.isLockedForTemplate;
            if (newValue == oldVal) return;
            context.executePropertyChange("Toggle Locking For Template", () -> {
                selectedClip.isLockedForTemplate = newValue;
                context.refreshTimelineUI();
                context.saveProject();
            }, () -> {
                selectedClip.isLockedForTemplate = oldVal;
                context.refreshTimelineUI();
                context.saveProject();
            });
        }));
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

        fields.getChildren().add(buildSectionDivider("Animation"));
        addAnimationFields(fields, selectedClip, ClipAnimation.Direction.IN);
        addAnimationFields(fields, selectedClip, ClipAnimation.Direction.OUT);
        fields.getChildren().add(buildButton("Animation packs...", e -> {
            javafx.stage.Window owner = getScene() != null ? getScene().getWindow() : null;
            // the dropdowns re-read the registry once a pack was installed / updated / removed
            AnimationPackDialog.show(owner, () -> javafx.application.Platform.runLater(context::updatePropertiesPane));
        }, "import-media-button"));

        getChildren().addAll(sectionTitle, fields);

        if (selectedClip.type != ClipType.EFFECT) {
            getChildren().add(buildKeyframesSection(selectedClip));
        }

        Clip transClip = context.getSelectedTransitionSourceClip();
        if (transClip != null && transClip.endTransition != null) {
            getChildren().add(buildTransitionSection(transClip));
        }
    }

    // ---- text style: font, bold / italic / alignment, colour, outline ---------------------------

    /** One entry of the font dropdown: the default, an imported font file, or an installed family. */
    private static final class FontChoice {
        final String label;
        final String family;   // null = the default font
        final String file;     // file name in the project's Fonts folder, or null

        FontChoice(String label, String family, String file) {
            this.label = label;
            this.family = family;
            this.file = file;
        }

        boolean matches(Clip clip) {
            if (file != null) return file.equals(clip.textFontFile);
            return clip.textFontFile == null && java.util.Objects.equals(family, emptyToNull(clip.textFontFamily));
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private void addTextStyleFields(VBox fields, Clip clip) {
        // Font
        VBox fontBox = new VBox(4);
        Label fontLabel = new Label("Font");
        fontLabel.getStyleClass().add("text-muted");
        fontLabel.setStyle("-fx-font-size: 11px;");
        ComboBox<FontChoice> fontCombo = new ComboBox<>();
        java.util.List<FontChoice> choices = fontChoices(clip);
        fontCombo.getItems().addAll(choices);
        for (FontChoice c : choices) {
            if (c.matches(clip)) {
                fontCombo.setValue(c);
                break;
            }
        }
        fontCombo.setMaxWidth(Double.MAX_VALUE);
        // Set AFTER the initial value: showing a clip must not count as the user picking a font.
        fontCombo.setOnAction(e -> {
            FontChoice chosen = fontCombo.getValue();
            if (chosen == null || chosen.matches(clip)) return;
            setFont(clip, chosen.family, chosen.file);
        });
        fontBox.getChildren().addAll(fontLabel, fontCombo);
        fields.getChildren().add(fontBox);
        fields.getChildren().add(buildButton("Import font...", e -> importFont(clip), "tool-button"));

        // Bold / italic
        HBox styleRow = new HBox(8);
        styleRow.setAlignment(Pos.CENTER_LEFT);
        ToggleButton bold = new ToggleButton("B");
        bold.setStyle("-fx-font-weight: bold;");
        bold.setSelected(clip.textBold);
        bold.setOnAction(e -> setTextProperty("Change Text Bold", clip.textBold, bold.isSelected(), v -> clip.textBold = v));
        ToggleButton italic = new ToggleButton("I");
        italic.setStyle("-fx-font-style: italic;");
        italic.setSelected(clip.textItalic);
        italic.setOnAction(e -> setTextProperty("Change Text Italic", clip.textItalic, italic.isSelected(), v -> clip.textItalic = v));
        Region gap = new Region();
        gap.setMinWidth(12);
        // Alignment: one of three, never none
        ToggleGroup alignGroup = new ToggleGroup();
        String[] alignNames = {"Left", "Center", "Right"};
        HBox alignRow = new HBox(4);
        for (int i = 0; i < alignNames.length; i++) {
            final int value = i;
            ToggleButton t = new ToggleButton(alignNames[i]);
            t.setToggleGroup(alignGroup);
            t.setSelected(clip.textAlign == value);
            t.setOnAction(e -> {
                if (!t.isSelected()) { t.setSelected(true); return; } // clicking the active one must not clear it
                setTextProperty("Change Text Alignment", clip.textAlign, value, v -> clip.textAlign = v);
            });
            alignRow.getChildren().add(t);
        }
        styleRow.getChildren().addAll(bold, italic, gap, alignRow);
        fields.getChildren().add(styleRow);

        // Color
        fields.getChildren().add(buildColorField("Color", clip.textColor, "#000000", hex ->
                setTextProperty("Change Text Color", clip.textColor, hex, v -> clip.textColor = v)));

        // Outline
        fields.getChildren().add(buildPropertyField("Outline Width", String.valueOf(clip.textOutlineWidth), newValue -> {
            try {
                float val = Math.max(0f, Float.parseFloat(newValue.trim()));
                setTextProperty("Change Text Outline Width", clip.textOutlineWidth, val, v -> clip.textOutlineWidth = v);
            } catch (NumberFormatException ignored) {}
        }));
        fields.getChildren().add(buildColorField("Outline Color", clip.textOutlineColor, "#000000", hex ->
                setTextProperty("Change Text Outline Color", clip.textOutlineColor, hex, v -> clip.textOutlineColor = v)));
    }

    /** A label and a color picker; reports "#RRGGBB" when the user picks one. */
    private VBox buildColorField(String label, String hex, String defaultHex, Consumer<String> onPicked) {
        VBox box = new VBox(4);
        Label lbl = new Label(label);
        lbl.getStyleClass().add("text-muted");
        lbl.setStyle("-fx-font-size: 11px;");
        Color initial;
        try {
            initial = Color.web(hex != null ? hex : defaultHex);
        } catch (IllegalArgumentException ex) {
            initial = Color.web(defaultHex);
        }
        ColorPicker picker = new ColorPicker(initial);
        picker.setMaxWidth(Double.MAX_VALUE);
        picker.setOnAction(e -> {
            Color c = picker.getValue();
            if (c == null) return;
            onPicked.accept(String.format("#%02X%02X%02X",
                    Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255)));
        });
        box.getChildren().addAll(lbl, picker);
        return box;
    }

    /** One undoable change of a text property, then redraw, save and refresh the panel (so undo shows in the controls). */
    private <T> void setTextProperty(String name, T oldVal, T newVal, Consumer<T> setter) {
        if (java.util.Objects.equals(oldVal, newVal)) return;
        context.executePropertyChange(name, () -> {
            setter.accept(newVal);
            context.refreshTimelineUI();
            context.saveProject();
            javafx.application.Platform.runLater(context::updatePropertiesPane);
        }, () -> {
            setter.accept(oldVal);
            context.refreshTimelineUI();
            context.saveProject();
            javafx.application.Platform.runLater(context::updatePropertiesPane);
        });
    }

    /** Picking a font sets family and file together, as one undo step. */
    private void setFont(Clip clip, String family, String file) {
        String oldFamily = clip.textFontFamily, oldFile = clip.textFontFile;
        if (java.util.Objects.equals(oldFamily, family) && java.util.Objects.equals(oldFile, file)) return;
        context.executePropertyChange("Change Font", () -> {
            clip.textFontFamily = family;
            clip.textFontFile = file;
            context.refreshTimelineUI();
            context.saveProject();
            javafx.application.Platform.runLater(context::updatePropertiesPane);
        }, () -> {
            clip.textFontFamily = oldFamily;
            clip.textFontFile = oldFile;
            context.refreshTimelineUI();
            context.saveProject();
            javafx.application.Platform.runLater(context::updatePropertiesPane);
        });
    }

    /** The dropdown's entries: default, this project's imported fonts, then every installed family. */
    private java.util.List<FontChoice> fontChoices(Clip clip) {
        java.util.List<FontChoice> choices = new java.util.ArrayList<>();
        choices.add(new FontChoice("Default", null, null));

        java.util.List<String> importedNames = new java.util.ArrayList<>();
        String dir = com.vanvatcorporation.doubleclips.TextStyle.fontsDirOf(context.getProject());
        java.io.File[] imported = dir == null ? null : new java.io.File(dir).listFiles((d, n) -> {
            String lower = n.toLowerCase();
            return lower.endsWith(".ttf") || lower.endsWith(".otf");
        });
        if (imported != null) {
            java.util.Arrays.sort(imported);
            for (java.io.File f : imported) {
                choices.add(new FontChoice(f.getName() + "  (imported)", null, f.getName()));
                importedNames.add(f.getName());
            }
        }
        // A font the clip uses but this computer doesn't have stays selectable, so opening a project never drops it.
        if (clip.textFontFile != null && !importedNames.contains(clip.textFontFile)) {
            choices.add(new FontChoice(clip.textFontFile + "  (missing)", null, clip.textFontFile));
        }
        java.util.List<String> families = javafx.scene.text.Font.getFamilies();
        for (String family : families) choices.add(new FontChoice(family, family, null));
        String wanted = emptyToNull(clip.textFontFamily);
        if (clip.textFontFile == null && wanted != null && !families.contains(wanted)) {
            choices.add(new FontChoice(wanted + "  (not installed)", wanted, null));
        }
        return choices;
    }

    /** Lets the user pick a .ttf/.otf, copies it into the project's Fonts folder, and uses it for this clip. */
    private void importFont(Clip clip) {
        javafx.stage.Window owner = getScene() != null ? getScene().getWindow() : null;
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Import font");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Fonts (*.ttf, *.otf)", "*.ttf", "*.otf"));
        java.io.File picked = chooser.showOpenDialog(owner);
        if (picked == null) return;

        if (!com.vanvatcorporation.doubleclips.TextLayoutEngine.isLoadableFont(picked)) {
            Alert alert = new Alert(Alert.AlertType.ERROR, "That file isn't a font this editor can read.");
            alert.initOwner(owner);
            alert.showAndWait();
            return;
        }
        String dir = com.vanvatcorporation.doubleclips.TextStyle.fontsDirOf(context.getProject());
        if (dir == null) return;
        try {
            java.io.File folder = new java.io.File(dir);
            if (!folder.isDirectory() && !folder.mkdirs()) throw new java.io.IOException("could not create " + folder);
            // Never overwrite: another clip may use a different font that happens to share the file name.
            String name = picked.getName();
            java.io.File target = new java.io.File(folder, name);
            int n = 1;
            while (target.exists() && target.length() != picked.length()) {
                int dot = name.lastIndexOf('.');
                target = new java.io.File(folder, (dot > 0 ? name.substring(0, dot) : name) + "-" + n++ + (dot > 0 ? name.substring(dot) : ""));
            }
            if (!target.exists()) java.nio.file.Files.copy(picked.toPath(), target.toPath());
            String family = com.vanvatcorporation.doubleclips.TextLayoutEngine.familyOfFile(target);
            setFont(clip, family, target.getName());
        } catch (java.io.IOException ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR, "Could not import the font: " + ex.getMessage());
            alert.initOwner(owner);
            alert.showAndWait();
        }
    }

    // ---- in / out animation rows ------------------------------------------------------------

    private static AnimationClip animationSlot(Clip clip, ClipAnimation.Direction dir) {
        return dir == ClipAnimation.Direction.IN ? clip.inAnimation : clip.outAnimation;
    }

    /** Writes type + duration into the clip's in/out slot, creating the slot if the clip has none yet. */
    private static void setAnimation(Clip clip, ClipAnimation.Direction dir, String type, float duration) {
        AnimationClip slot = animationSlot(clip, dir);
        if (slot == null) {
            slot = new AnimationClip(type, duration);
            if (dir == ClipAnimation.Direction.IN) clip.inAnimation = slot;
            else clip.outAnimation = slot;
        } else {
            slot.type = type;
            slot.duration = duration;
        }
    }

    /**
     * One animation's two rows: a dropdown of the installed animations of this direction (the
     * registry's "none" + every built-in and pack animation, shown by display name) and a duration
     * field. Picking an animation by hand also sets the duration to that animation's own default
     * (e.g. 1.5 s for unfold); just showing a clip never changes its saved duration. Same behaviour
     * as Android's AnimationPicker. A saved animation that isn't installed stays selectable as
     * "<id> (not installed)", so opening and closing a project never drops it.
     */
    private void addAnimationFields(VBox fields, Clip clip, ClipAnimation.Direction dir) {
        String title = dir == ClipAnimation.Direction.IN ? "In Animation" : "Out Animation";
        AnimationClip slot = animationSlot(clip, dir);
        String currentType = AnimationChoices.normalize(slot != null ? slot.type : null);

        VBox typeBox = new VBox(4);
        Label typeLabel = new Label(title);
        typeLabel.getStyleClass().add("text-muted");
        typeLabel.setStyle("-fx-font-size: 11px;");

        ComboBox<AnimationChoices.Choice> combo = new ComboBox<>();
        java.util.List<AnimationChoices.Choice> choices = AnimationChoices.choices(dir, currentType);
        combo.getItems().addAll(choices);
        combo.setValue(choices.get(AnimationChoices.indexOf(choices, currentType)));
        combo.setMaxWidth(Double.MAX_VALUE);
        // Set AFTER the initial value: ComboBox fires its action for programmatic changes too, and
        // showing a clip must not count as the user picking something.
        combo.setOnAction(e -> {
            AnimationChoices.Choice chosen = combo.getValue();
            if (chosen == null) return;
            AnimationClip now = animationSlot(clip, dir);
            String oldType = AnimationChoices.normalize(now != null ? now.type : null);
            float oldDuration = now != null ? now.duration : 0.5f;
            if (chosen.id.equals(oldType)) return;
            float defaultDuration = AnimationChoices.defaultDurationOf(chosen.id);
            String newType = chosen.id;
            float newDuration = defaultDuration > 0f ? defaultDuration : oldDuration;
            context.executePropertyChange("Change " + title + " Type", () -> {
                setAnimation(clip, dir, newType, newDuration);
                context.refreshTimelineUI();
                context.saveProject();
                javafx.application.Platform.runLater(context::updatePropertiesPane); // shows the new duration
            }, () -> {
                setAnimation(clip, dir, oldType, oldDuration);
                context.refreshTimelineUI();
                context.saveProject();
                javafx.application.Platform.runLater(context::updatePropertiesPane);
            });
        });
        typeBox.getChildren().addAll(typeLabel, combo);
        fields.getChildren().add(typeBox);

        fields.getChildren().add(buildPropertyField(title + " Duration (s)", String.valueOf(slot != null ? slot.duration : 0.5f), newValue -> {
            try {
                float val = Float.parseFloat(newValue.trim());
                if (!(val > 0f) || Float.isInfinite(val)) return; // keep the previous duration
                AnimationClip now = animationSlot(clip, dir);
                float oldVal = now != null ? now.duration : 0.5f;
                String type = AnimationChoices.normalize(now != null ? now.type : null);
                if (val == oldVal) return;
                context.executePropertyChange("Change " + title + " Duration", () -> {
                    setAnimation(clip, dir, type, val);
                    context.refreshTimelineUI();
                    context.saveProject();
                }, () -> {
                    setAnimation(clip, dir, type, oldVal);
                    context.refreshTimelineUI();
                    context.saveProject();
                });
            } catch (NumberFormatException ignored) {}
        }));
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
