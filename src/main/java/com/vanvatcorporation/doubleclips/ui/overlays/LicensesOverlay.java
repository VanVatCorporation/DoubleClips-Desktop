package com.vanvatcorporation.doubleclips.ui.overlays;

import com.vanvatcorporation.doubleclips.FFmpegEditNative;
import com.vanvatcorporation.doubleclips.i18n.I18n;
import com.vanvatcorporation.doubleclips.ui.components.QuotaUi;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Settings > Open-source licences: the third-party software inside the app, what it is used for, its licence, where its source is and,
 * when the full text is bundled, the licence text. The texts are plain files in src/main/resources/licenses/ (tools/licenses/
 * fetch-license-texts.sh downloads them: LGPL-3.0.txt, GPL-3.0.txt, GPL-2.0.txt, Apache-2.0.txt, MIT.txt, BSD-2/3-Clause.txt); without them
 * the notice and the link are shown.
 *
 * The bundled ffmpeg is described by ASKING THE BINARY (ffmpeg -version, -L), not by a fixed line: a GPL build (--enable-gpl, libx264 ...)
 * is shown as GPL and a non-free build is flagged, whatever the build someone drops into the ffmpeg folder.
 *
 * The list is from the code's imports: add a library here when you add one to the build.
 */
public class LicensesOverlay extends StackPane {

    private static final String APACHE = "Apache License, Version 2.0";

    private static final class Notice {
        final String name, usedFor, license, copyright, note;
        final String[][] links;       // { title, url }
        final String[] textFiles;     // resources/licenses/<name>.txt
        Notice(String name, String usedFor, String license, String copyright, String note, String[][] links, String... textFiles) {
            this.name = name; this.usedFor = usedFor; this.license = license; this.copyright = copyright;
            this.note = note; this.links = links; this.textFiles = textFiles;
        }
    }

    private final Consumer<Void> onClose;

    /** @param onClose called when the card is closed (the settings card is shown again) */
    public LicensesOverlay(Consumer<Void> onClose) {
        this.onClose = onClose;
        getStyleClass().add("modal-overlay");

        VBox card = new VBox(16);
        card.getStyleClass().add("creation-dialog");
        card.setMaxSize(560, 640);
        card.setPadding(new Insets(30));

        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label(I18n.t("Open-source licences"));
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button();
        close.setGraphic(new FontIcon(MaterialDesignC.CLOSE));
        close.getStyleClass().add("button-transparent");
        close.setOnAction(e -> onClose.accept(null));
        header.getChildren().addAll(title, spacer, close);

        Label intro = new Label(I18n.t("The third-party software DoubleClips is built with, and its licences."));
        intro.setWrapText(true);
        intro.getStyleClass().add("text-muted");

        VBox list = new VBox(0);
        list.setPadding(new Insets(6, 6, 6, 0));

        VBox ffmpegBox = new VBox(4);
        list.getChildren().addAll(ffmpegBox, new Separator());
        fillFfmpeg(ffmpegBox, new Notice("FFmpeg", "Cutting, joining, filtering, encoding and previewing video and audio (the bundled ffmpeg program).",
                "(checking the bundled ffmpeg ...)", "Copyright © the FFmpeg developers", null,
                new String[][]{{"Website", "https://ffmpeg.org"}, {"Source code", "https://ffmpeg.org/download.html"}}));
        for (Notice n : notices()) {
            list.getChildren().addAll(box(n), new Separator());
        }

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        scroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        card.getChildren().addAll(header, intro, scroll);
        getChildren().add(card);
        setOnMouseClicked(e -> { if (e.getTarget() == this) onClose.accept(null); });
    }

    private static List<Notice> notices() {
        List<Notice> n = new ArrayList<>();
        n.add(new Notice("OpenJFX (JavaFX)", "The windows and controls of the app.", "GNU General Public License v2 with the Classpath Exception",
                "Copyright © Oracle and/or its affiliates and the OpenJFX contributors", null,
                new String[][]{{"Website", "https://openjfx.io"}, {"Licence text", "https://openjdk.org/legal/gplv2+ce.html"}}, "GPL-2.0"));
        n.add(new Notice("AtlantaFX", "The look of the app (themes).", "MIT License", "Copyright © mkpaz", null,
                new String[][]{{"Source code", "https://github.com/mkpaz/atlantafx"}}, "MIT"));
        n.add(new Notice("Ikonli", "The icons (including the Material Design Icons pack).", APACHE, "Copyright © Andres Almiray and the Ikonli contributors",
                "The Material Design Icons are © the Pictogrammers and licensed under the Apache License 2.0.",
                new String[][]{{"Source code", "https://github.com/kordamp/ikonli"}}, "Apache-2.0"));
        n.add(new Notice("Gson", "Reading and writing project files (JSON).", APACHE, "Copyright © Google Inc.", null,
                new String[][]{{"Source code", "https://github.com/google/gson"}}, "Apache-2.0"));
        n.add(new Notice("OkHttp, Okio and Retrofit", "Talking to the Van Vat servers (account, Drive, templates, assets).", APACHE,
                "Copyright © Square, Inc.", null,
                new String[][]{{"OkHttp", "https://github.com/square/okhttp"}, {"Okio", "https://github.com/square/okio"},
                        {"Retrofit", "https://github.com/square/retrofit"}}, "Apache-2.0"));
        n.add(new Notice("LWJGL", "OpenGL drawing of the preview and the export.", "BSD 3-Clause licence",
                "Copyright © 2012-present Lightweight Java Game Library. All rights reserved.", null,
                new String[][]{{"Website", "https://www.lwjgl.org"}, {"Licence text", "https://www.lwjgl.org/license"}}, "BSD-3-Clause"));
        return n;
    }

    // ---- views -------------------------------------------------------------------------------------------------------

    private static VBox box(Notice n) {
        VBox box = new VBox(4);
        box.setPadding(new Insets(14, 0, 14, 0));
        fillBox(box, n, n.license);
        return box;
    }

    private static void fillBox(VBox box, Notice n, String license) {
        box.getChildren().clear();
        Label name = new Label(n.name);
        name.setFont(Font.font(null, FontWeight.BOLD, 16));
        box.getChildren().add(name);
        box.getChildren().add(wrapped(I18n.t("Used for") + ": " + I18n.t(n.usedFor), "text-muted"));
        Label lic = wrapped(I18n.t("Licence") + ": " + license, null);
        lic.setStyle("-fx-font-weight: bold;");
        box.getChildren().add(lic);
        box.getChildren().add(wrapped(n.copyright, "text-muted"));
        if (n.note != null && !n.note.isEmpty()) box.getChildren().add(wrapped(n.note, "text-muted"));
        for (String[] link : n.links) {
            Hyperlink h = new Hyperlink(I18n.t(link[0]) + ": " + link[1]);
            h.setOnAction(e -> QuotaUi.openUrl(link[1]));
            box.getChildren().add(h);
        }
        if (n.textFiles.length > 0) {
            Button toggle = new Button(I18n.t("Show the licence text"));
            Label full = new Label();
            full.setWrapText(true);
            full.setStyle("-fx-font-family: monospace; -fx-font-size: 10px;");
            full.setManaged(false);
            full.setVisible(false);
            toggle.setOnAction(e -> {
                boolean show = !full.isVisible();
                if (show && full.getText().isEmpty()) full.setText(readTexts(n.textFiles));
                full.setVisible(show);
                full.setManaged(show);
                toggle.setText(I18n.t(show ? "Hide the licence text" : "Show the licence text"));
            });
            box.getChildren().addAll(toggle, full);
        }
    }

    private static Label wrapped(String text, String styleClass) {
        Label l = new Label(text);
        l.setWrapText(true);
        if (styleClass != null) l.getStyleClass().add(styleClass);
        return l;
    }

    /** The bundled texts one after the other, or a pointer to the link when none is bundled. */
    private static String readTexts(String[] files) {
        StringBuilder out = new StringBuilder();
        for (String file : files) {
            try (InputStream in = LicensesOverlay.class.getResourceAsStream("/licenses/" + file + ".txt")) {
                if (in == null) continue;
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                in.transferTo(buffer);
                if (out.length() > 0) out.append("\n\n————————————————————\n\n");
                out.append(buffer.toString(StandardCharsets.UTF_8));
            } catch (Exception missing) { /* not bundled */ }
        }
        return out.length() == 0 ? I18n.t("The full text isn't bundled with this build. Use the licence link above.") : out.toString();
    }

    // ---- the bundled ffmpeg, asked for its own licence -------------------------------------------------------------------

    private static void fillFfmpeg(VBox box, Notice base) {
        fillBox(box, base, base.license);
        Thread t = new Thread(() -> {
            String version = "", configuration = "", licenseNotice = "";
            try {
                String path = FFmpegEditNative.getFfmpegPath();
                String v = run(path, "-hide_banner", "-version");
                for (String line : v.split("\\R")) {
                    if (line.startsWith("ffmpeg version")) version = line;
                    else if (line.startsWith("configuration:")) configuration = line;
                }
                licenseNotice = firstLines(run(path, "-hide_banner", "-L"), 12);
            } catch (Exception ignored) { /* ffmpeg could not be run */ }

            boolean gpl = configuration.contains("--enable-gpl");
            boolean v3 = configuration.contains("--enable-version3");
            boolean nonfree = configuration.contains("--enable-nonfree");
            String license = version.isEmpty()
                    ? I18n.t("The bundled ffmpeg could not be run, so its licence could not be read.")
                    : nonfree ? I18n.t("NON-FREE build (--enable-nonfree): it may not be redistributed")
                    : gpl ? (v3 ? "GNU General Public License, version 3 or later (GPL-3.0+): this build contains GPL libraries"
                                : "GNU General Public License, version 2 or later (GPL-2.0+): this build contains GPL libraries")
                    : (v3 ? "GNU Lesser General Public License, version 3 or later (LGPL-3.0+)"
                          : "GNU Lesser General Public License, version 2.1 or later (LGPL-2.1+)");
            String note = (version.isEmpty() ? "" : version + "\n") + (licenseNotice.isEmpty() ? "" : licenseNotice);
            Notice shown = new Notice(base.name, base.usedFor, license, base.copyright, note.isEmpty() ? null : note, base.links,
                    gpl ? new String[]{"GPL-3.0", "GPL-2.0"} : new String[]{"LGPL-3.0", "GPL-3.0"});
            Platform.runLater(() -> fillBox(box, shown, license));
        }, "licenses-ffmpeg");
        t.setDaemon(true);
        t.start();
    }

    private static String run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        p.waitFor(10, TimeUnit.SECONDS);
        return out.toString();
    }

    private static String firstLines(String text, int count) {
        String[] lines = text.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(count, lines.length); i++) sb.append(lines[i]).append('\n');
        return sb.toString().trim();
    }
}
