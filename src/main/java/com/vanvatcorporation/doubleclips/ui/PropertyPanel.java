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
    private final MaskSection maskSection;
    private final BlendSection blendSection;
    private final ClipEffectSection clipEffectSection;
    private final AudioVolumeRow audioVolumeRow;

    /** The tabs of a selected clip's panel (CapCut style). Mask, Blend and Effect are for video, image and text clips only. */
    private enum Tab {
        PROPERTIES("Properties"), MASK("Mask"), BLEND("Blend"), EFFECT("Effect"), ANIMATION("Animation");
        final String title;
        Tab(String title) { this.title = title; }
    }

    private Tab activeTab = Tab.PROPERTIES;
    /** Sits above the scrolling panel (EditorWindow adds it); rebuilt by every update(), hidden when no clip is selected. */
    private final HBox tabStrip = new HBox(0);

    public PropertyPanel(PropertyContext context) {
        super(15);
        this.context = context;
        this.maskSection = new MaskSection(context);
        this.blendSection = new BlendSection(context);
        this.clipEffectSection = new ClipEffectSection(context);
        this.audioVolumeRow = new AudioVolumeRow(context);
        this.setPadding(new Insets(16));
        tabStrip.getStyleClass().add("editor-tab-strip");
        tabStrip.setVisible(false);
        tabStrip.setManaged(false);
    }

    /** The tab strip to put above this panel (it lives outside the scroll area so it never scrolls away). */
    public HBox getTabStrip() {
        return tabStrip;
    }

    private void hideTabStrip() {
        tabStrip.getChildren().clear();
        tabStrip.setVisible(false);
        tabStrip.setManaged(false);
    }

    /** Shows the tabs this clip has and marks the active one (falls back to Properties when the clip lacks it). */
    private void syncTabStrip(Clip clip) {
        boolean visual = clip.type == ClipType.VIDEO || clip.type == ClipType.IMAGE || clip.type == ClipType.TEXT;
        java.util.List<Tab> available = visual
                ? java.util.Arrays.asList(Tab.values())
                : java.util.Arrays.asList(Tab.PROPERTIES, Tab.ANIMATION);
        if (!available.contains(activeTab)) activeTab = Tab.PROPERTIES;
        tabStrip.getChildren().clear();
        ToggleGroup group = new ToggleGroup();
        for (Tab tab : available) {
            ToggleButton tb = new ToggleButton(tab.title);
            tb.setToggleGroup(group);
            tb.getStyleClass().add("editor-tab");
            tb.setMinWidth(0);
            tb.setMaxWidth(Double.MAX_VALUE);
            tb.setStyle("-fx-font-size: 11px;");
            HBox.setHgrow(tb, Priority.ALWAYS);
            tb.setSelected(tab == activeTab);
            tb.setOnAction(e -> {
                tb.setSelected(true); // a tab can't be switched off by clicking it again
                if (tab == activeTab) return;
                activeTab = tab;
                context.updatePropertiesPane();
            });
            tabStrip.getChildren().add(tb);
        }
        tabStrip.setVisible(true);
        tabStrip.setManaged(true);
    }

    public void update() {
        getChildren().clear();

        Clip selectedClip = context.getSelectedClip();
        if (selectedClip == null) {
            hideTabStrip();
            context.maskEditState().set(null, -1, false);
        }
        if (selectedClip == null) {
            // A selected transition (the cube between two clips) has the panel to itself.
            Clip transitionA = context.getSelectedTransitionSourceClip();
            if (transitionA != null && transitionA.endTransition != null) {
                buildTransitionPanel(transitionA);
                return;
            }
        }
        if (selectedClip == null) {
            Label placeholder = new Label("Select a clip, or a transition between two clips, to view properties");
            placeholder.setWrapText(true);
            placeholder.getStyleClass().add("text-muted");
            getChildren().add(placeholder);
            return;
        }

        syncTabStrip(selectedClip);
        if (activeTab != Tab.MASK) maskSection.suspend(selectedClip); // mask handles show on the canvas only on the Mask tab
        switch (activeTab) {
            case MASK: buildMaskTab(selectedClip); return;
            case BLEND: buildBlendTab(selectedClip); return;
            case EFFECT: buildClipEffectTab(selectedClip); return;
            case ANIMATION: buildAnimationTab(selectedClip); return;
            default: break;
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
            fields.getChildren().add(audioVolumeRow.build(selectedClip));
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

        if (selectedClip.type == ClipType.EFFECT) {
            addEffectFields(fields, selectedClip);
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

        getChildren().addAll(sectionTitle, fields);

        if (selectedClip.type != ClipType.EFFECT) {
            getChildren().add(buildKeyframesSection(selectedClip));
        }
    }

    // ---- the other tabs ---------------------------------------------------------------------------------

    private Label tabTitle(String text) {
        Label title = new Label(text);
        title.getStyleClass().add("text-bold");
        title.setStyle("-fx-font-size: 16px;");
        return title;
    }

    private void buildMaskTab(Clip clip) {
        getChildren().addAll(tabTitle("Mask"), maskSection.build(clip));
    }

    private void buildBlendTab(Clip clip) {
        getChildren().addAll(tabTitle("Blend"), blendSection.build(clip));
    }

    private void buildClipEffectTab(Clip clip) {
        getChildren().addAll(tabTitle("Effect"), clipEffectSection.build(clip));
    }

    /** In / out animations, the packs dialog, and the shortcut to the transition at this clip's end. */
    private void buildAnimationTab(Clip selectedClip) {
        VBox fields = new VBox(10);
        addAnimationFields(fields, selectedClip, ClipAnimation.Direction.IN);
        addAnimationFields(fields, selectedClip, ClipAnimation.Direction.OUT);
        fields.getChildren().add(buildButton("Animation packs...", e -> {
            javafx.stage.Window owner = getScene() != null ? getScene().getWindow() : null;
            // the dropdowns re-read the registry once a pack was installed / updated / removed
            AnimationPackDialog.show(owner, () -> javafx.application.Platform.runLater(context::updatePropertiesPane));
        }, "import-media-button"));

        // The transition at this clip's end is one click away (it used to need a hunt for the small cube on the timeline).
        if (selectedClip.endTransition != null && context.getTransitionNextClip(selectedClip) != null) {
            fields.getChildren().add(buildTransitionShortcut(selectedClip));
        }
        getChildren().addAll(tabTitle("Animation"), fields);
    }

    // ---- transitions: the panel of a selected transition, and the shortcut on its clip A ---------------------------

    /** The panel of a selected transition: which two clips it joins, then its type / timing / duration. */
    private void buildTransitionPanel(Clip clipA) {
        Label title = new Label("Transition");
        title.getStyleClass().add("text-bold");
        title.setStyle("-fx-font-size: 16px;");
        Clip clipB = context.getTransitionNextClip(clipA);
        Label between = new Label(clipA.getClipName() + "  \u2192  " + (clipB != null ? clipB.getClipName() : "?"));
        between.getStyleClass().add("text-muted");
        between.setWrapText(true);
        getChildren().addAll(title, between, buildTransitionSection(clipA));
    }

    /** On clip A: "Into <B>" and a button showing the transition's summary that selects it. */
    private VBox buildTransitionShortcut(Clip clipA) {
        VBox box = new VBox(4);
        box.getChildren().add(buildSectionDivider("Transition"));
        Clip clipB = context.getTransitionNextClip(clipA);
        Label into = new Label("Into \"" + (clipB != null ? clipB.getClipName() : "?") + "\"");
        into.getStyleClass().add("text-muted");
        into.setStyle("-fx-font-size: 11px;");
        Button open = buildButton(com.vanvatcorporation.doubleclips.TransitionSummary.of(clipA.endTransition) + "   \u203A", e -> context.selectTransition(clipA), "tool-button");
        open.setMaxWidth(Double.MAX_VALUE);
        box.getChildren().addAll(into, open);
        return box;
    }

    // ---- effect clips: which effect, and how strong ---------------------------------------------------------

    private static String strengthText(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /**
     * An effect clip is an adjustment layer: it filters everything on the tracks above it for as long as it lasts.
     * Here: the effect (opens the tile picker with looping previews) and, for effects that have one, its strength.
     */
    private void addEffectFields(VBox fields, Clip clip) {
        EffectTemplate fx = clip.effect;
        if (fx == null) return;
        com.vanvatcorporation.doubleclips.EffectCatalog.Style style = com.vanvatcorporation.doubleclips.EffectCatalog.find(fx.style);

        fields.getChildren().add(buildSectionDivider("Effect"));
        Label label = new Label("Effect");
        label.getStyleClass().add("text-muted");
        label.setStyle("-fx-font-size: 11px;");
        Button open = buildButton((style != null ? style.title : fx.style) + "   \u25B8", e -> showEffectBrowser(clip), "tool-button");
        open.setMaxWidth(Double.MAX_VALUE);
        fields.getChildren().add(new VBox(4, label, open));

        if (style == null) {
            Label note = new Label("This effect comes from a newer version of the app, so it isn't drawn here. Pick another to replace it.");
            note.setWrapText(true);
            note.getStyleClass().add("text-muted");
            note.setStyle("-fx-font-size: 11px;");
            fields.getChildren().add(note);
            return;
        }

        if (style.hasIntensity()) {
            float value = com.vanvatcorporation.doubleclips.EffectCatalog.clampIntensity(style, fx.intensity());
            Label strengthLabel = new Label(style.intensityLabel);
            strengthLabel.getStyleClass().add("text-muted");
            strengthLabel.setStyle("-fx-font-size: 11px;");
            Label shown = new Label(strengthText(value));
            shown.setStyle("-fx-font-size: 11px;");
            Slider slider = new Slider(style.intensityMin, style.intensityMax, value);
            slider.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(slider, Priority.ALWAYS);
            // Dragging only moves the number; the change is made (one undo step) when the slider is let go.
            slider.valueProperty().addListener((o, was, now) -> {
                shown.setText(strengthText(now.floatValue()));
                if (!slider.isValueChanging()) commitEffectStrength(clip, now.floatValue());
            });
            slider.valueChangingProperty().addListener((o, was, changing) -> {
                if (!changing) commitEffectStrength(clip, (float) slider.getValue());
            });
            HBox row = new HBox(8, slider, shown);
            row.setAlignment(Pos.CENTER_LEFT);
            fields.getChildren().add(new VBox(4, strengthLabel, row));
        }

        Label hint = new Label("Filters everything on the tracks above this one while it lasts. Clips on tracks below it are drawn over the result. Drawn by the OpenGL engine.");
        hint.setWrapText(true);
        hint.getStyleClass().add("text-muted");
        hint.setStyle("-fx-font-size: 11px;");
        fields.getChildren().add(hint);
    }

    /** One undo step: swaps the clip's whole effect (and its name, which is the effect's title) and back. */
    private void commitEffect(Clip clip, String undoName, EffectTemplate after, String newName) {
        final EffectTemplate before = new EffectTemplate(clip.effect);
        final String nameBefore = clip.getClipName();
        final EffectTemplate next = new EffectTemplate(after);
        context.executePropertyChange(undoName, () -> {
            clip.effect = new EffectTemplate(next);
            clip.setClipName(newName);
            context.refreshTimelineUI();
            context.saveProject();
            javafx.application.Platform.runLater(context::updatePropertiesPane);
        }, () -> {
            clip.effect = new EffectTemplate(before);
            clip.setClipName(nameBefore);
            context.refreshTimelineUI();
            context.saveProject();
            javafx.application.Platform.runLater(context::updatePropertiesPane);
        });
    }

    private void commitEffectStrength(Clip clip, float value) {
        com.vanvatcorporation.doubleclips.EffectCatalog.Style style = com.vanvatcorporation.doubleclips.EffectCatalog.find(clip.effect.style);
        float clamped = com.vanvatcorporation.doubleclips.EffectCatalog.clampIntensity(style, value);
        if (Math.abs(clamped - clip.effect.intensity()) < 1e-4f) return; // the slider reports a change twice on release
        EffectTemplate after = new EffectTemplate(clip.effect);
        after.setIntensity(clamped);
        commitEffect(clip, "Change Effect Strength", after, clip.getClipName());
    }

    private void showEffectBrowser(Clip clip) {
        java.util.List<com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Tile> tiles = new java.util.ArrayList<>();
        for (com.vanvatcorporation.doubleclips.EffectCatalog.Style st : com.vanvatcorporation.doubleclips.EffectCatalog.styles()) {
            java.util.Set<String> engines = new java.util.LinkedHashSet<>();
            for (String engine : st.engines()) {
                engines.add(com.vanvatcorporation.doubleclips.EffectCatalog.ENGINE_FFMPEG.equals(engine)
                        ? com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.ENGINE_FFMPEG
                        : com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.ENGINE_OPENGL);
            }
            tiles.add(new com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Tile(st.key, st.title, st.author, engines,
                    () -> context.tilePreviews().effectStill(st.key, 1f),
                    false,
                    () -> context.tilePreviews().effectLoop(st.key, 1f)));
        }
        java.util.List<com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Section> sections = new java.util.ArrayList<>();
        sections.add(new com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Section("Effects", tiles));
        javafx.stage.Window owner = getScene() != null ? getScene().getWindow() : null;
        com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.show(owner, "Effect", sections, clip.effect.style,
                new com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Listener() {
                    @Override
                    public void onPick(com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Tile tile) {
                        com.vanvatcorporation.doubleclips.EffectCatalog.Style chosen = com.vanvatcorporation.doubleclips.EffectCatalog.find(tile.id);
                        if (chosen == null || chosen.key.equals(clip.effect.style)) return;
                        EffectTemplate after = new EffectTemplate(clip.effect);
                        after.style = chosen.key;
                        after.params = null; // another effect starts at its own default strength
                        commitEffect(clip, "Change Effect", after, chosen.title);
                    }

                    @Override
                    public boolean onDelete(com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Tile tile) {
                        return false;
                    }
                }, null, null);
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

        boolean matches(TextStyleData d) {
            if (file != null) return file.equals(d.fontFile);
            return d.fontFile == null && java.util.Objects.equals(family, emptyToNull(d.fontName));
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /**
     * Changes the clip's text style as ONE undo step: {@code change} is applied to a copy, and the whole
     * style is swapped in (and back on undo). A clip from before text styles gets one built from its old
     * settings the first time, so it keeps looking the same. Changing a value by hand makes the look
     * "Custom" (it forgets which preset it came from) and keeps Android's spelling of it in step.
     */
    private void editTextStyle(Clip clip, String undoName, java.util.function.Consumer<TextStyleData> change) {
        TextStyleData before = new TextStyleData(clip.effectiveTextStyle());
        TextStyleData after = new TextStyleData(before);
        change.accept(after);
        if (before.sameAs(after)) return;
        after.clearPreset();
        after.withAndroidFields();
        commitTextStyle(clip, undoName, after, null, null);
    }

    /**
     * Puts {@code after} on the clip as one undo step; {@code inSlot} / {@code outSlot}, when not null, replace the
     * clip's In / Out animation in the same step (a preset can name them).
     */
    private void commitTextStyle(Clip clip, String undoName, TextStyleData after,
                                 com.vanvatcorporation.doubleclips.data.editing.AnimationClip inSlot,
                                 com.vanvatcorporation.doubleclips.data.editing.AnimationClip outSlot) {
        final TextStyleData before = new TextStyleData(clip.effectiveTextStyle());
        final com.vanvatcorporation.doubleclips.data.editing.AnimationClip inBefore = clip.inAnimation == null ? null
                : new com.vanvatcorporation.doubleclips.data.editing.AnimationClip(clip.inAnimation);
        final com.vanvatcorporation.doubleclips.data.editing.AnimationClip outBefore = clip.outAnimation == null ? null
                : new com.vanvatcorporation.doubleclips.data.editing.AnimationClip(clip.outAnimation);
        final TextStyleData next = new TextStyleData(after);
        context.executePropertyChange(undoName, () -> {
            clip.textStyle = new TextStyleData(next);
            if (inSlot != null) clip.inAnimation = new com.vanvatcorporation.doubleclips.data.editing.AnimationClip(inSlot);
            if (outSlot != null) clip.outAnimation = new com.vanvatcorporation.doubleclips.data.editing.AnimationClip(outSlot);
            context.refreshTimelineUI();
            context.saveProject();
            javafx.application.Platform.runLater(context::updatePropertiesPane);
        }, () -> {
            clip.textStyle = new TextStyleData(before);
            clip.inAnimation = inBefore == null ? null : new com.vanvatcorporation.doubleclips.data.editing.AnimationClip(inBefore);
            clip.outAnimation = outBefore == null ? null : new com.vanvatcorporation.doubleclips.data.editing.AnimationClip(outBefore);
            context.refreshTimelineUI();
            context.saveProject();
            javafx.application.Platform.runLater(context::updatePropertiesPane);
        });
    }

    // ---- style presets ----------------------------------------------------------------------------

    private static javafx.scene.image.Image thumbnailImage(TextStyleData preset, String fontsDir) {
        com.vanvatcorporation.doubleclips.TextPresetThumbnail.Pixels px = com.vanvatcorporation.doubleclips.TextPresetThumbnail.render(
                preset, null, fontsDir, com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.TILE_WIDTH,
                com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.PICTURE_HEIGHT);
        javafx.scene.image.WritableImage image = new javafx.scene.image.WritableImage(px.width, px.height);
        image.getPixelWriter().setPixels(0, 0, px.width, px.height, javafx.scene.image.PixelFormat.getIntArgbInstance(), px.argb, 0, px.width);
        return image;
    }

    private static java.util.Set<String> enginesOf(TextStyleData d) {
        java.util.Set<String> engines = new java.util.LinkedHashSet<>();
        if (d.supportsEngine(TextStyleData.ENGINE_FFMPEG)) engines.add(com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.ENGINE_FFMPEG);
        if (d.supportsEngine(TextStyleData.ENGINE_OPENGL)) engines.add(com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.ENGINE_OPENGL);
        return engines;
    }

    /** "Style: <name> >" - the preset the look came from, or Custom; opens the tile browser. */
    private void addStylePresetRow(VBox fields, Clip clip) {
        TextStyleData d = clip.effectiveTextStyle();
        Label label = new Label("Style");
        label.getStyleClass().add("text-muted");
        label.setStyle("-fx-font-size: 11px;");
        Button open = buildButton(com.vanvatcorporation.doubleclips.TextPresets.labelOf(d) + "   \u25B8", e -> showStyleBrowser(clip), "tool-button");
        open.setMaxWidth(Double.MAX_VALUE);
        fields.getChildren().add(new VBox(4, label, open));
    }

    private void showStyleBrowser(Clip clip) {
        com.vanvatcorporation.doubleclips.TextPresetLibrary library = com.vanvatcorporation.doubleclips.TextPresetLibrary.shared();
        String fontsDir = com.vanvatcorporation.doubleclips.TextStyle.fontsDirOf(context.getProject());
        java.util.List<TextStyleData> builtIns = com.vanvatcorporation.doubleclips.TextPresets.builtIns();
        java.util.List<TextStyleData> mine = library.load();
        java.util.List<TextStyleData> everything = new java.util.ArrayList<>(builtIns);
        everything.addAll(mine);

        java.util.function.Function<TextStyleData, com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Tile> tileOf = d ->
                new com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Tile(d.id, d.name, d.author, enginesOf(d),
                        () -> thumbnailImage(d, fontsDir), com.vanvatcorporation.doubleclips.TextPresetLibrary.isUserStyle(d));
        java.util.List<com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Tile> builtInTiles = new java.util.ArrayList<>();
        for (TextStyleData d : builtIns) builtInTiles.add(tileOf.apply(d));
        java.util.List<com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Tile> myTiles = new java.util.ArrayList<>();
        for (TextStyleData d : mine) myTiles.add(tileOf.apply(d));
        java.util.List<com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Section> sections = new java.util.ArrayList<>();
        sections.add(new com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Section("Styles", builtInTiles));
        sections.add(new com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Section("My styles", myTiles));

        javafx.stage.Window owner = getScene() != null ? getScene().getWindow() : null;
        com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.show(owner, "Text style", sections, clip.effectiveTextStyle().id,
                new com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Listener() {
                    @Override
                    public void onPick(com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Tile tile) {
                        TextStyleData preset = com.vanvatcorporation.doubleclips.TextPresets.find(everything, tile.id);
                        if (preset != null) applyStylePreset(clip, preset);
                    }

                    @Override
                    public boolean onDelete(com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Tile tile) {
                        try {
                            library.delete(tile.id);
                            return true;
                        } catch (java.io.IOException ex) {
                            Alert alert = new Alert(Alert.AlertType.ERROR, "Could not delete the style: " + ex.getMessage());
                            alert.initOwner(owner);
                            alert.showAndWait();
                            return false;
                        }
                    }
                },
                "Save current look as a style...", () -> saveCurrentLookAsStyle(clip, owner));
    }

    /** Puts a preset on the clip (its look and, if it names them, its In / Out animations) as one undo step. */
    private void applyStylePreset(Clip clip, TextStyleData preset) {
        TextStyleData after = com.vanvatcorporation.doubleclips.TextPresets.apply(clip.effectiveTextStyle(), preset);
        java.util.function.Predicate<String> installed = id -> com.vanvatcorporation.doubleclips.ClipAnimationLoader.get(id) != null;
        commitTextStyle(clip, "Apply Text Style", after,
                com.vanvatcorporation.doubleclips.TextPresets.slotFor(clip.inAnimation, after.inAnimationId, installed),
                com.vanvatcorporation.doubleclips.TextPresets.slotFor(clip.outAnimation, after.outAnimationId, installed));
    }

    private void saveCurrentLookAsStyle(Clip clip, javafx.stage.Window owner) {
        javafx.scene.control.TextInputDialog ask = new javafx.scene.control.TextInputDialog("My style");
        ask.initOwner(owner);
        ask.setTitle("Save style");
        ask.setHeaderText("Name this style");
        ask.setContentText("Name:");
        java.util.Optional<String> name = ask.showAndWait();
        if (!name.isPresent()) return;
        try {
            TextStyleData look = new TextStyleData(clip.effectiveTextStyle());
            // The saved style also takes this clip's In / Out animations if it animates per unit, as the built-in animated ones do.
            if (look.unitMode != null) {
                look.inAnimationId = clip.inAnimation != null && !"none".equals(clip.inAnimation.type) ? clip.inAnimation.type : null;
                look.outAnimationId = clip.outAnimation != null && !"none".equals(clip.outAnimation.type) ? clip.outAnimation.type : null;
            }
            TextStyleData saved = com.vanvatcorporation.doubleclips.TextPresetLibrary.shared().save(look, name.get());
            // The clip now IS that style.
            TextStyleData marked = new TextStyleData(clip.effectiveTextStyle());
            marked.id = saved.id;
            marked.name = saved.name;
            marked.author = saved.author;
            marked.withAndroidFields();
            commitTextStyle(clip, "Save Text Style", marked, null, null);
        } catch (java.io.IOException ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR, "Could not save the style: " + ex.getMessage());
            alert.initOwner(owner);
            alert.showAndWait();
        }
    }

    private static String numberText(float v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    /** A labelled number box that clamps to [min, max] and edits one style field. */
    private VBox styleNumberField(Clip clip, String label, float value, float min, float max, String undoName,
                                  java.util.function.BiConsumer<TextStyleData, Float> setter) {
        return buildPropertyField(label, numberText(value), text -> {
            try {
                float v = Math.max(min, Math.min(max, Float.parseFloat(text.trim())));
                editTextStyle(clip, undoName, d -> setter.accept(d, v));
            } catch (NumberFormatException ignored) {}
        });
    }

    private void addSectionLabel(VBox fields, String label) {
        Label heading = new Label(label);
        heading.getStyleClass().add("text-muted");
        heading.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 8 0 0 0;");
        fields.getChildren().add(heading);
    }

    private void addTextStyleFields(VBox fields, Clip clip) {
        TextStyleData d = clip.effectiveTextStyle();

        addStylePresetRow(fields, clip);

        // Font
        VBox fontBox = new VBox(4);
        Label fontLabel = new Label("Font");
        fontLabel.getStyleClass().add("text-muted");
        fontLabel.setStyle("-fx-font-size: 11px;");
        ComboBox<FontChoice> fontCombo = new ComboBox<>();
        java.util.List<FontChoice> choices = fontChoices(clip);
        fontCombo.getItems().addAll(choices);
        for (FontChoice c : choices) {
            if (c.matches(d)) {
                fontCombo.setValue(c);
                break;
            }
        }
        fontCombo.setMaxWidth(Double.MAX_VALUE);
        // Set AFTER the initial value: showing a clip must not count as the user picking a font.
        fontCombo.setOnAction(e -> {
            FontChoice chosen = fontCombo.getValue();
            if (chosen == null || chosen.matches(clip.effectiveTextStyle())) return;
            setFont(clip, chosen.family, chosen.file);
        });
        fontBox.getChildren().addAll(fontLabel, fontCombo);
        fields.getChildren().add(fontBox);
        fields.getChildren().add(buildButton("Import font...", e -> importFont(clip), "tool-button"));

        // Bold / italic / alignment
        HBox styleRow = new HBox(8);
        styleRow.setAlignment(Pos.CENTER_LEFT);
        ToggleButton bold = new ToggleButton("B");
        bold.setStyle("-fx-font-weight: bold;");
        bold.setSelected(d.bold);
        bold.setOnAction(e -> { boolean on = bold.isSelected(); editTextStyle(clip, "Change Text Bold", s -> s.bold = on); });
        ToggleButton italic = new ToggleButton("I");
        italic.setStyle("-fx-font-style: italic;");
        italic.setSelected(d.italic);
        italic.setOnAction(e -> { boolean on = italic.isSelected(); editTextStyle(clip, "Change Text Italic", s -> s.italic = on); });
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
            t.setSelected(d.alignmentIndex() == value);
            t.setOnAction(e -> {
                if (!t.isSelected()) { t.setSelected(true); return; } // clicking the active one must not clear it
                editTextStyle(clip, "Change Text Alignment", s -> s.setAlignmentIndex(value));
            });
            alignRow.getChildren().add(t);
        }
        styleRow.getChildren().addAll(bold, italic, gap, alignRow);
        fields.getChildren().add(styleRow);

        // Colour
        fields.getChildren().add(buildColorField("Color", d.colorHex, "#FFFFFF", hex ->
                editTextStyle(clip, "Change Text Color", s -> s.colorHex = hex)));

        // Spacing (ranges as on iOS)
        addSectionLabel(fields, "Spacing");
        fields.getChildren().add(styleNumberField(clip, "Letters", d.letterSpacing, -5f, 30f, "Change Letter Spacing", (s, v) -> s.letterSpacing = v));
        fields.getChildren().add(styleNumberField(clip, "Lines", d.lineSpacing, -10f, 60f, "Change Line Spacing", (s, v) -> s.lineSpacing = v));
        String wrapText = d.wrapWidth < 0f ? "clip width" : numberText(Math.round(d.wrapWidth * 100f));
        fields.getChildren().add(buildPropertyField("Wrap at (% of canvas, 0 = off)", wrapText, text -> {
            try {
                float pct = Math.max(0f, Math.min(100f, Float.parseFloat(text.trim())));
                editTextStyle(clip, "Change Text Wrap", s -> s.wrapWidth = pct / 100f);
            } catch (NumberFormatException ignored) {}
        }));

        // Outline
        addSectionLabel(fields, "Outline");
        fields.getChildren().add(styleNumberField(clip, "Width", d.outlineWidth, 0f, 24f, "Change Text Outline Width", (s, v) -> s.outlineWidth = v));
        fields.getChildren().add(buildColorField("Outline Color", d.outlineColorHex, "#000000", hex ->
                editTextStyle(clip, "Change Text Outline Color", s -> s.outlineColorHex = hex)));

        // Shadow
        addSectionLabel(fields, "Shadow");
        fields.getChildren().add(styleNumberField(clip, "Blur", d.shadowBlur, 0f, 40f, "Change Shadow Blur", (s, v) -> s.shadowBlur = v));
        fields.getChildren().add(styleNumberField(clip, "Offset X", d.shadowOffsetX, -40f, 40f, "Change Shadow Offset", (s, v) -> s.shadowOffsetX = v));
        fields.getChildren().add(styleNumberField(clip, "Offset Y", d.shadowOffsetY, -40f, 40f, "Change Shadow Offset", (s, v) -> s.shadowOffsetY = v));
        fields.getChildren().add(buildColorField("Shadow Color", d.shadowColorHex, "#00000099", hex ->
                editTextStyle(clip, "Change Shadow Color", s -> s.shadowColorHex = hex)));

        // Background box
        addSectionLabel(fields, "Background box");
        fields.getChildren().add(buildColorField("Box Color (transparent = no box)", d.backgroundColorHex, "#00000000", hex ->
                editTextStyle(clip, "Change Box Color", s -> s.backgroundColorHex = hex)));
        fields.getChildren().add(styleNumberField(clip, "Padding", d.backgroundPadding, 0f, 80f, "Change Box Padding", (s, v) -> s.backgroundPadding = v));
        fields.getChildren().add(styleNumberField(clip, "Corners", d.backgroundRadius, 0f, 80f, "Change Box Corners", (s, v) -> s.backgroundRadius = v));

        addTextUnitAnimationFields(fields, clip);
    }

    private static final String[] UNIT_LABELS = {"Whole text", "Character", "Word", "Line"};
    private static final String[] UNIT_VALUES = {null, "CHARACTER", "WORD", "LINE"};
    private static final String[] ORDER_LABELS = {"Forward", "Reverse", "Centre out", "Random"};
    private static final String[] ORDER_VALUES = {null, "REVERSE", "CENTER_OUT", "RANDOM"};

    /** The index of {@code value} in {@code values} (null counts as the first entry); 0 for anything unknown. */
    private static int indexOfValue(String[] values, String value) {
        if (value == null || value.isEmpty()) return 0;
        for (int i = 1; i < values.length; i++) if (values[i].equals(value)) return i;
        return 0;
    }

    /**
     * Per-character / word / line animation: the clip's own In / Out animation is played on each unit in turn
     * instead of on the whole text. OpenGL render engine only (FFmpeg's drawtext can't do it).
     */
    private void addTextUnitAnimationFields(VBox fields, Clip clip) {
        TextStyleData d = clip.effectiveTextStyle();
        addSectionLabel(fields, "Text animation");

        ComboBox<String> unitCombo = new ComboBox<>();
        unitCombo.getItems().addAll(UNIT_LABELS);
        unitCombo.getSelectionModel().select(indexOfValue(UNIT_VALUES, d.unitMode));
        unitCombo.setMaxWidth(Double.MAX_VALUE);
        unitCombo.setOnAction(e -> {
            int i = unitCombo.getSelectionModel().getSelectedIndex();
            if (i < 0) return;
            editTextStyle(clip, "Change Text Animation Unit", s -> s.unitMode = UNIT_VALUES[i]);
        });
        fields.getChildren().add(new VBox(4, new Label("Animate by"), unitCombo));

        if (d.unitMode == null || d.unitMode.isEmpty()) return; // the rest only matters once a unit is chosen

        float shownStagger = d.stagger > 0f ? d.stagger : com.vanvatcorporation.doubleclips.OpenGLEdit.DEFAULT_TEXT_STAGGER;
        fields.getChildren().add(buildPropertyField("Stagger (%)", String.valueOf(Math.round(shownStagger * 100f)), newValue -> {
            try {
                float pct = Math.max(5f, Math.min(95f, Float.parseFloat(newValue.trim())));
                editTextStyle(clip, "Change Text Stagger", s -> s.stagger = pct / 100f);
            } catch (NumberFormatException ignored) {}
        }));

        ComboBox<String> orderCombo = new ComboBox<>();
        orderCombo.getItems().addAll(ORDER_LABELS);
        orderCombo.getSelectionModel().select(indexOfValue(ORDER_VALUES, d.unitOrder));
        orderCombo.setMaxWidth(Double.MAX_VALUE);
        orderCombo.setOnAction(e -> {
            int i = orderCombo.getSelectionModel().getSelectedIndex();
            if (i < 0) return;
            editTextStyle(clip, "Change Text Animation Order", s -> s.unitOrder = ORDER_VALUES[i]);
        });
        fields.getChildren().add(new VBox(4, new Label("Order"), orderCombo));

        String note = d.hasBackground()
                ? "A background box is on, so the text animates as one block. Turn the box off to animate each unit."
                : "Plays this clip's In and Out animation on each unit in turn. Needs the OpenGL render engine; FFmpeg export animates the text as one block.";
        Label hint = new Label(note);
        hint.setWrapText(true);
        hint.getStyleClass().add("text-muted");
        hint.setStyle("-fx-font-size: 11px;");
        fields.getChildren().add(hint);
    }

    /** A label and a color picker; reports "#RRGGBB" (or "#RRGGBBAA" when translucent) when the user picks one. */
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
            String rgb = String.format("#%02X%02X%02X",
                    Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
            // "#RRGGBBAA" when not fully opaque (the iOS format), so shadows and boxes can be translucent
            onPicked.accept(c.getOpacity() >= 0.999 ? rgb : rgb + String.format("%02X", Math.round(c.getOpacity() * 255)));
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
        editTextStyle(clip, "Change Font", d -> {
            d.fontName = family == null ? "" : family;
            d.fontFile = file;
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
        TextStyleData current = clip.effectiveTextStyle();
        if (current.fontFile != null && !importedNames.contains(current.fontFile)) {
            choices.add(new FontChoice(current.fontFile + "  (missing)", null, current.fontFile));
        }
        java.util.List<String> families = javafx.scene.text.Font.getFamilies();
        for (String family : families) choices.add(new FontChoice(family, family, null));
        String wanted = emptyToNull(current.fontName);
        if (current.fontFile == null && wanted != null && !families.contains(wanted)) {
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

    /** Is the playhead inside the clip (a keyframe can only be set there)? */
    private boolean playheadInside(Clip clip, float time) {
        return time >= clip.startTime - 0.001f && time <= clip.startTime + clip.duration + 0.001f;
    }

    /**
     * A property row. A property with keyframes shows and edits its value AT THE PLAYHEAD: a new value keys that
     * property there (updating the keyframe that is there, or adding one); a property without keyframes is
     * edited as a plain value.
     */
    private void addKeyframeableField(VBox parent, String label, float currentVal, VideoProperties.ValueType type, Clip clip) {
        // Show the value at the playhead (an animated property's static value is not what is on screen).
        float shown = clip.keyframes != null ? clip.keyframes.getValueAtTime(clip, context.getCurrentTime(), type) : currentVal;
        parent.getChildren().add(buildKeyframeablePropertyField(label, String.valueOf(shown), newValue -> {
            try {
                float val = Float.parseFloat(newValue);
                float time = context.getCurrentTime();
                float oldVal = clip.keyframes.getValueAtTime(clip, time, type);
                if (val == oldVal) return;
                if (clip.keyframes.isAnimated(type) && !playheadInside(clip, time)) return; // nowhere to put a keyframe
                KeyframesSnapshot before = KeyframesSnapshot.of(clip);
                int frameRate = context.getVideoSettings().frameRate;
                // Speed changes how long the clip plays the source: the timeline length follows (video / audio), like iOS.
                final boolean refit = type == VideoProperties.ValueType.Speed && (clip.type == ClipType.VIDEO || clip.type == ClipType.AUDIO);
                final float oldDuration = clip.duration;
                context.executePropertyChange("Change " + label, () -> {
                    if (clip.keyframes.isAnimated(type)) clip.keyframes.setKey(clip, time, type, val, frameRate);
                    else clip.videoProperties.setValue(val, type);
                    if (refit) clip.refitDuration();
                    context.refreshTimelineUI();
                    context.saveProject();
                }, () -> {
                    before.restore(clip);
                    if (refit) clip.duration = oldDuration;
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

        Button addBtn = buildButton("+ Key All at Playhead", e -> context.handleAddKeyframe(), "import-media-button");
        Button clearBtn = buildButton("Clear All", e -> context.handleClearKeyframes(), "tool-button");
        Button importBtn = buildButton("Import", e -> context.handleImportKeyframes(), "tool-button");
        Button exportBtn = buildButton("Export", e -> context.handleExportKeyframes(), "tool-button");

        btnRow.getChildren().addAll(addBtn, clearBtn, importBtn, exportBtn);

        box.getChildren().addAll(buildSectionDivider("Keyframes"), countLbl, btnRow);

        if (count > 0) {
            VBox kfList = new VBox(4);
            java.util.Map<Keyframe, FontIcon> icons = new java.util.LinkedHashMap<>();
            java.util.Map<Keyframe, EasingChip> chips = new java.util.LinkedHashMap<>();
            for (Keyframe k : clip.keyframes.keyframes) {
                HBox kfRow = new HBox(8);
                kfRow.setAlignment(Pos.CENTER_LEFT);
                kfRow.setPadding(new Insets(6, 8, 6, 8));
                kfRow.setStyle("-fx-background-color: transparent; -fx-background-radius: 4px; -fx-cursor: hand;");

                FontIcon icon = new FontIcon(MaterialDesignR.RHOMBUS);
                icon.setIconSize(12);
                icons.put(k, icon);

                // "0.50s" with the properties it holds under it ("All", or e.g. "Pos X, Scale Y")
                Label timeLbl = new Label(String.format("%.2fs", k.getLocalTime()));
                timeLbl.setStyle("-fx-font-size: 11px;");
                Label channelsLbl = new Label(k.channelSummary());
                channelsLbl.setStyle("-fx-font-size: 10px;");
                channelsLbl.getStyleClass().add("text-muted");
                channelsLbl.setMaxWidth(110);
                VBox labels = new VBox(1, timeLbl, channelsLbl);

                Region grow = new Region();
                HBox.setHgrow(grow, Priority.ALWAYS);

                // The easing of the curve that starts at this keyframe: a small graph that opens the grid.
                EasingChip chip = new EasingChip(k.easing);
                chip.setOnAction(e -> EasingPicker.show(chip.getScene() != null ? chip.getScene().getWindow() : null,
                        k.easing, newEasing -> changeEasing(k, newEasing, chips)));
                chips.put(k, chip);

                kfRow.getChildren().addAll(icon, labels, grow, chip);
                kfRow.setOnMouseClicked(e -> {
                    context.updateCurrentTime(k.getGlobalTime(clip));
                    context.refreshTimelineUI();
                });

                kfRow.setOnMouseEntered(e -> kfRow.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 4px; -fx-cursor: hand;"));
                kfRow.setOnMouseExited(e -> kfRow.setStyle("-fx-background-color: transparent; -fx-background-radius: 4px; -fx-cursor: hand;"));

                kfList.getChildren().add(kfRow);
            }
            // Undo/redo changes the easing behind the chips' back: keep them showing the real one.
            context.addPropertyUpdater(() -> chips.forEach((k, chip) -> chip.setEasing(k.easing)));
            // The keyframe the playhead is on is the chosen one (purple); the rest are grey.
            Runnable paintIcons = () -> {
                float time = context.getTempTime() >= 0 ? context.getTempTime() : context.getCurrentTime();
                Keyframe chosen = clip.keyframes.getKeyframeAtTime(clip, time);
                icons.forEach((k, icon) -> icon.setIconColor(k == chosen ? ClipNode.KNOT_ACTIVE : Color.valueOf("#888888")));
            };
            paintIcons.run();
            context.addPropertyUpdater(paintIcons);
            box.getChildren().add(kfList);
        }

        return box;
    }

    /** Sets the easing of the curve that starts at {@code k} (undoable) and refreshes its chip. */
    private void changeEasing(Keyframe k, EasingType newEasing, java.util.Map<Keyframe, EasingChip> chips) {
        EasingType oldEasing = k.easing;
        if (oldEasing == newEasing) return;
        context.executePropertyChange("Change Keyframe Easing", () -> {
            k.easing = newEasing;
            EasingChip chip = chips.get(k);
            if (chip != null) chip.setEasing(newEasing);
            context.saveProject();
        }, () -> {
            k.easing = oldEasing;
            EasingChip chip = chips.get(k);
            if (chip != null) chip.setEasing(oldEasing);
            context.saveProject();
        });
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

        // The duration spinner is created up here because picking a data-driven style resets it to the style's own default.
        final Spinner<Double> durSpinner = new Spinner<>(0.0, 10.0, (double) tc.duration, 0.1);

        // The picker's entries come from TransitionStyleCatalog: "none", the styles both engines draw, the data-driven
        // (OpenGL-only) ones, then the FFmpeg-only ones - each badged. A stored style that is in neither list stays
        // selectable as "[not installed]", so opening the picker and closing it again never overwrites it.
        final String currentStyle = tc.effect != null ? tc.effect.style : null;
        final java.util.List<com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Entry> styleEntries =
                com.vanvatcorporation.doubleclips.TransitionStyleCatalog.build(
                        com.vanvatcorporation.doubleclips.FXCommandEmitter.FXRegistry.transitionFXMap,
                        com.vanvatcorporation.doubleclips.OpenGLEdit.SUPPORTED_TRANSITION_STYLES,
                        com.vanvatcorporation.doubleclips.TransitionStyleLoader.list(),
                        true,
                        currentStyle);
        // The type is picked in a grid of tiles (like the effect browser): each one is the style playing between two
        // pictures, drawn by the preview worker's real renderer. The button shows the current style and opens the grid.
        final java.util.function.Function<com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Entry, String> plainTitle =
                entry -> entry.label.replaceAll("\\s*\\[(FFmpeg only|OpenGL only)[^\\]]*\\]$", "");
        Button typeButton = new Button();
        typeButton.getStyleClass().add("tool-button");
        typeButton.setMaxWidth(Double.MAX_VALUE);
        int currentIndex = com.vanvatcorporation.doubleclips.TransitionStyleCatalog.indexOf(styleEntries, currentStyle == null ? "none" : currentStyle);
        typeButton.setText(currentIndex >= 0 ? plainTitle.apply(styleEntries.get(currentIndex)) : "None");
        typeButton.setGraphic(new FontIcon(org.kordamp.ikonli.materialdesign2.MaterialDesignV.VIEW_GRID));
        typeButton.setContentDisplay(ContentDisplay.RIGHT);

        java.util.function.Consumer<com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Entry> pick = chosen -> {
            if (tc.effect != null && chosen.id.equals(tc.effect.style)) return;
            if (tc.effect == null) tc.effect = new EffectTemplate(chosen.id, tc.duration, tc.startTime);
            else tc.effect.style = chosen.id;
            typeButton.setText(plainTitle.apply(chosen));
            // A data-driven style is authored for a duration (Glitch Blur: 2.3 s); start from it. The spinner's
            // listener below copies the value into tc.duration / tc.effect.duration and saves the project.
            com.vanvatcorporation.doubleclips.TransitionStyle dataStyle = chosen.dataDriven
                    ? com.vanvatcorporation.doubleclips.TransitionStyleLoader.get(chosen.id) : null;
            if (dataStyle != null) {
                durSpinner.getValueFactory().setValue((double) Math.round(dataStyle.getDefaultDuration() * 10f) / 10.0);
            }
            context.saveProject();
        };
        typeButton.setOnAction(e -> showTransitionBrowser(styleEntries, plainTitle,
                tc.effect != null ? tc.effect.style : null, pick));
        typePicker.getChildren().addAll(typeLabel, typeButton);

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

        durSpinner.setEditable(true);
        durSpinner.setMaxWidth(Double.MAX_VALUE);
        durSpinner.valueProperty().addListener((obs, old, nv) -> {
            tc.duration = nv.floatValue();
            if (tc.effect != null) tc.effect.duration = tc.duration;
            context.saveProject();
        });
        durBox.getChildren().addAll(durLabel, durSpinner);

        box.getChildren().addAll(typePicker, modePicker, durBox);
        return box;
    }

    /** The transition grid: every style as a tile that plays it between two pictures; the worker's real renderer draws them. */
    private void showTransitionBrowser(java.util.List<com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Entry> entries,
                                       java.util.function.Function<com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Entry, String> plainTitle,
                                       String currentStyle,
                                       java.util.function.Consumer<com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Entry> onChosen) {
        final com.vanvatcorporation.doubleclips.ui.components.GpuTilePreviews previews = context.tilePreviews();
        java.util.Map<com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Engine, java.util.List<com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Tile>> groups =
                new java.util.EnumMap<>(com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Engine.class);
        for (com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Entry entry : entries) {
            java.util.Set<String> engines = new java.util.LinkedHashSet<>();
            switch (entry.engine) {
                case BOTH:
                    engines.add(com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.ENGINE_FFMPEG);
                    engines.add(com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.ENGINE_OPENGL);
                    break;
                case OPENGL_ONLY:
                    engines.add(com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.ENGINE_OPENGL);
                    break;
                case FFMPEG_ONLY:
                    engines.add(com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.ENGINE_FFMPEG);
                    break;
                default:
                    break;
            }
            String author = null;
            com.vanvatcorporation.doubleclips.TransitionStyle dataStyle = entry.dataDriven
                    ? com.vanvatcorporation.doubleclips.TransitionStyleLoader.get(entry.id) : null;
            if (dataStyle != null && dataStyle.getAuthor() != null && !dataStyle.getAuthor().isBlank()) author = dataStyle.getAuthor();
            // Only a style the OpenGL renderer draws has a picture to play; the others stay still tiles.
            boolean drawn = entry.engine == com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Engine.BOTH
                    || entry.engine == com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Engine.OPENGL_ONLY;
            final String id = entry.id;
            groups.computeIfAbsent(entry.engine, k -> new java.util.ArrayList<>()).add(
                    new com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Tile(id, plainTitle.apply(entry), author, engines,
                            () -> previews.transitionStill(id), false,
                            drawn && !"none".equals(id) ? () -> previews.transitionLoop(id) : null));
        }
        java.util.List<com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Section> sections = new java.util.ArrayList<>();
        String[] titles = {"Both engines", "OpenGL only", "FFmpeg only", "Not installed"};
        com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Engine[] order = {
                com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Engine.BOTH,
                com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Engine.OPENGL_ONLY,
                com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Engine.FFMPEG_ONLY,
                com.vanvatcorporation.doubleclips.TransitionStyleCatalog.Engine.UNKNOWN};
        for (int i = 0; i < order.length; i++) {
            java.util.List<com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Tile> tiles = groups.get(order[i]);
            if (tiles != null) sections.add(new com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Section(titles[i], tiles));
        }
        javafx.stage.Window owner = getScene() != null ? getScene().getWindow() : null;
        com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.show(owner, "Transition", sections,
                currentStyle == null ? "none" : currentStyle,
                new com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Listener() {
                    @Override
                    public void onPick(com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Tile tile) {
                        int index = com.vanvatcorporation.doubleclips.TransitionStyleCatalog.indexOf(entries, tile.id);
                        if (index >= 0) onChosen.accept(entries.get(index));
                    }

                    @Override
                    public boolean onDelete(com.vanvatcorporation.doubleclips.ui.components.TileGridDialog.Tile tile) {
                        return false;
                    }
                }, null, null);
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

        // The diamond: hollow = not animated, half = animated but no keyframe at the playhead,
        // filled purple = this property has a keyframe at the playhead.
        Button kfBtn = new Button();
        kfBtn.getStyleClass().add("tool-button");
        kfBtn.setPadding(new Insets(4));
        kfBtn.setMaxHeight(Double.MAX_VALUE);

        KeyDiamond diamond = new KeyDiamond();
        diamond.setState(clip.keyframes.stateAt(clip, context.getCurrentTime(), vType));
        kfBtn.setGraphic(diamond);
        kfBtn.setDisable(!playheadInside(clip, context.getCurrentTime()));

        context.addPropertyUpdater(() -> {
            float time = context.getTempTime() >= 0 ? context.getTempTime() : context.getCurrentTime();
            if (clip.keyframes != null && !tf.isFocused()) {
                float interpolated = clip.keyframes.getValueAtTime(clip, time, vType);
                String displayStr = (interpolated == (long) interpolated)
                    ? String.format("%d", (long) interpolated)
                    : String.format("%.2f", interpolated).replaceAll("0*$", "").replaceAll("\\.$", "");
                tf.setText(displayStr);
            }
            diamond.setState(clip.keyframes.stateAt(clip, time, vType));
            kfBtn.setDisable(!playheadInside(clip, time));
        });

        kfBtn.setOnAction(e -> {
            float time = context.getCurrentTime();
            if (!playheadInside(clip, time)) return;
            KeyframesSnapshot before = KeyframesSnapshot.of(clip);
            int frameRate = context.getVideoSettings().frameRate;
            context.executePropertyChange("Keyframe " + label, () -> {
                clip.keyframes.toggleKey(clip, time, vType, frameRate);
                context.refreshTimelineUI();
                context.saveProject();
                context.updatePropertiesPane();
            }, () -> {
                before.restore(clip);
                context.refreshTimelineUI();
                context.saveProject();
                context.updatePropertiesPane();
            });
        });

        return new HBox(8, fieldBox, kfBtn);
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
