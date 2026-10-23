package com.vanvatcorporation.doubleclips.ui.components;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.drive.DoubleClipsDrive;
import com.vanvatcorporation.doubleclips.drive.DriveClient;
import com.vanvatcorporation.doubleclips.drive.DriveError;
import com.vanvatcorporation.doubleclips.drive.DriveMe;
import com.vanvatcorporation.doubleclips.drive.LegalModels;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The blocking Terms of Service popup, same layout as the web: title, subtitle, the product's terms in a scrollable box, a
 * checkbox "I accept the Privacy Policy and Terms of Service" (both are links to the full pages) and a Continue button that
 * stays disabled until the box is ticked. The window cannot be closed (the close button does nothing): the only way on is
 * accepting, which the server records. It shows for users who have not accepted yet and again for everyone whenever new
 * terms are published.
 */
public final class TermsGate {
    private TermsGate() {}

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "terms-gate");
        t.setDaemon(true);
        return t;
    });
    private static final List<Runnable> ACCEPTED = new CopyOnWriteArrayList<>();
    private static Stage current;

    /** Runs (on the FX thread) after any acceptance, e.g. so the Storage pane reloads. */
    public static void addAcceptedListener(Runnable r) { ACCEPTED.add(r); }

    /** FX thread. Shows the popup unless one is already showing. {@code onAccepted} may be null. */
    public static void show(Window owner, DriveClient client, LegalModels.Status status, Runnable onAccepted) {
        if (status == null || !status.required) return;
        if (current != null && current.isShowing()) return;
        Window parent = owner;
        if (parent == null) for (Window w : Window.getWindows()) if (w.isShowing()) { parent = w; break; }
        new Gate(parent, client, status, onAccepted).open();
    }

    /** Any thread: reads /me and shows the popup if the user has not accepted the current terms (e.g. after a tos_required answer). */
    public static void checkAndShow() {
        if (!DoubleClipsDrive.hasSession()) return;
        IO.execute(() -> {
            try {
                DriveMe me = DoubleClipsDrive.client.me();
                if (me.legalRequired()) Platform.runLater(() -> show(null, DoubleClipsDrive.client, me.legal, null));
            } catch (IOException ignored) { /* offline: nothing to show */ }
        });
    }

    private static boolean vi() { return "vi".equals(Locale.getDefault().getLanguage()); }
    private static String tr(String en, String vi) { return vi() ? vi : en; }

    private static final class Gate {
        final Window owner;
        final DriveClient client;
        final LegalModels.Status status;
        final Runnable onAccepted;

        Stage stage;
        Label title, subtitle, error;
        MenuButton langMenu;
        VBox content;
        CheckBox check;
        TextFlow acceptFlow;
        Button go;
        final BooleanProperty loaded = new SimpleBooleanProperty(false);
        final BooleanProperty busy = new SimpleBooleanProperty(false);
        LegalModels.Document doc;
        String lang;                       // null = the device language

        Gate(Window owner, DriveClient client, LegalModels.Status status, Runnable onAccepted) {
            this.owner = owner; this.client = client; this.status = status; this.onAccepted = onAccepted;
        }

        void open() {
            stage = new Stage();
            stage.initModality(Modality.APPLICATION_MODAL);
            if (owner != null) stage.initOwner(owner);
            stage.setTitle(tr("Terms of Service", "Điều khoản dịch vụ"));
            stage.setOnCloseRequest(e -> e.consume());                 // the window cannot be closed: accepting is the way on

            Scene scene = new Scene(build(), 780, 700);
            if (owner != null && owner.getScene() != null) {            // same look as the app (dark/light)
                scene.getStylesheets().addAll(owner.getScene().getStylesheets());
                scene.getRoot().getStyleClass().addAll(owner.getScene().getRoot().getStyleClass());
                scene.getRoot().setStyle(owner.getScene().getRoot().getStyle());
            }
            stage.setScene(scene);
            stage.setMinWidth(520);
            stage.setMinHeight(480);
            current = stage;
            stage.setOnHidden(e -> { if (current == stage) current = null; });
            stage.show();
            load();
        }

        VBox build() {
            title = new Label(tr("Terms of Service", "Điều khoản dịch vụ"));
            title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
            subtitle = new Label(tr("Please read and accept the Terms of Service and Privacy Policy to keep using Van Vat Drive.",
                    "Vui lòng đọc và chấp nhận Điều khoản dịch vụ và Chính sách bảo mật để tiếp tục sử dụng Van Vat Drive."));
            subtitle.setWrapText(true);
            subtitle.setOpacity(0.7);
            VBox titles = new VBox(4, title, subtitle);
            HBox.setHgrow(titles, Priority.ALWAYS);

            langMenu = new MenuButton();
            langMenu.setVisible(false);
            langMenu.setManaged(false);
            HBox head = new HBox(12, titles, langMenu);
            head.setAlignment(Pos.TOP_LEFT);

            content = new VBox(8);
            content.setPadding(new Insets(10, 14, 14, 14));
            ScrollPane scroll = new ScrollPane(content);
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setStyle("-fx-background-color: transparent; -fx-border-color: rgba(128,128,128,0.35); -fx-border-radius: 10; -fx-background-radius: 10;");
            VBox.setVgrow(scroll, Priority.ALWAYS);

            check = new CheckBox();
            check.setDisable(true);
            check.disableProperty().bind(loaded.not().or(busy));
            acceptFlow = new TextFlow();
            HBox.setHgrow(acceptFlow, Priority.ALWAYS);
            HBox checkRow = new HBox(8, check, acceptFlow);
            checkRow.setAlignment(Pos.TOP_LEFT);

            error = new Label();
            error.setStyle("-fx-text-fill: #E53935;");
            error.setWrapText(true);
            error.setManaged(false);
            error.setVisible(false);

            go = new Button(tr("Continue", "Tiếp tục"));
            go.setDefaultButton(true);
            go.setMinWidth(130);
            go.setStyle("-fx-background-color: #1E88E5; -fx-text-fill: white; -fx-background-radius: 8; -fx-font-weight: bold;");
            go.disableProperty().bind(check.selectedProperty().not().or(loaded.not()).or(busy));
            go.setOnAction(e -> submit());
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            HBox actions = new HBox(grow, go);

            VBox root = new VBox(14, head, scroll, checkRow, error, actions);
            root.setPadding(new Insets(22));
            return root;
        }

        // ── loading ─────────────────────────────────────────────────────────

        void load() {
            loaded.set(false);
            doc = null;
            check.setSelected(false);
            content.getChildren().setAll(new Label(tr("Loading…", "Đang tải…")));
            final String asked = lang != null ? lang : Locale.getDefault().getLanguage();
            IO.execute(() -> {
                try {
                    LegalModels.Document d = client.legalDocument(status.inline, asked);
                    Platform.runLater(() -> { doc = d; renderDoc(); loaded.set(true); });
                } catch (Exception e) {
                    Platform.runLater(this::renderLoadError);
                }
            });
        }

        void renderLoadError() {
            Label msg = new Label(tr("The Terms of Service could not be loaded. Check your connection and try again.",
                    "Không thể tải Điều khoản dịch vụ. Hãy kiểm tra kết nối và thử lại."));
            msg.setWrapText(true);
            Button retry = new Button(tr("Try again", "Thử lại"));
            retry.setOnAction(e -> load());
            content.getChildren().setAll(msg, retry);
        }

        // ── the document ────────────────────────────────────────────────────

        void renderDoc() {
            title.applyCss();
            Paint p = title.getTextFill();
            Color fill = p instanceof Color ? (Color) p : Color.BLACK;       // follows the app's light/dark theme

            content.getChildren().clear();
            if (doc.languages != null && doc.languages.size() > 1) {
                langMenu.setVisible(true);
                langMenu.setManaged(true);
                langMenu.setText(doc.lang.toUpperCase(Locale.ROOT));
                langMenu.getItems().clear();
                for (String l : doc.languages) {
                    MenuItem mi = new MenuItem(l.toUpperCase(Locale.ROOT));
                    mi.setOnAction(e -> { lang = l; load(); });
                    langMenu.getItems().add(mi);
                }
            }
            if (doc.fallback) {
                Label note = new Label(tr("This document is not available in your language yet, so it is shown in English.",
                        "Tài liệu này chưa có bản dịch cho ngôn ngữ của bạn nên đang hiển thị bằng tiếng Anh."));
                note.setWrapText(true);
                note.setStyle("-fx-text-fill: #1E88E5;");
                content.getChildren().add(note);
            }
            for (LegalModels.Block b : doc.blocks) content.getChildren().add(block(b, fill));
            renderAcceptLabel(fill);
        }

        javafx.scene.Node block(LegalModels.Block b, Color fill) {
            String type = b.type == null ? "p" : b.type;
            switch (type) {
                case "h": {
                    int level = (b.level == null ? 1 : b.level) + 2;          // same shift as the web popup (its own title is the top heading)
                    TextFlow f = flow(b.spans, level <= 2 ? 19 : level == 3 ? 17 : 15, true, fill);
                    VBox box = new VBox(f);
                    box.setPadding(new Insets(10, 0, 0, 0));
                    return box;
                }
                case "li": {
                    Label marker = new Label(Boolean.TRUE.equals(b.ordered) ? (b.number == null ? 0 : b.number) + "." : "•");
                    marker.setMinWidth(22);
                    marker.setAlignment(Pos.TOP_RIGHT);
                    marker.setOpacity(0.7);
                    TextFlow f = flow(b.spans, 14, false, fill);
                    HBox.setHgrow(f, Priority.ALWAYS);
                    HBox row = new HBox(6, marker, f);
                    row.setAlignment(Pos.TOP_LEFT);
                    row.setPadding(new Insets(0, 0, 0, 18.0 * (b.depth == null ? 0 : b.depth)));
                    return row;
                }
                case "quote": {
                    VBox box = new VBox(flow(b.spans, 14, false, fill));
                    box.setStyle("-fx-background-color: rgba(255,160,0,0.13); -fx-padding: 10 12 10 12; -fx-border-color: #FFA000; -fx-border-width: 0 0 0 3;");
                    return box;
                }
                case "rule":
                    return new Separator();
                default:
                    return flow(b.spans, 14, false, fill);
            }
        }

        /** Bold / italic / code / link runs as one wrapping TextFlow. */
        TextFlow flow(List<LegalModels.Span> spans, double size, boolean boldAll, Color fill) {
            TextFlow f = new TextFlow();
            if (spans == null) return f;
            for (LegalModels.Span s : spans) {
                String text = s.t == null ? "" : s.t;
                boolean bold = boldAll || Boolean.TRUE.equals(s.b);
                Font font = Font.font(Boolean.TRUE.equals(s.c) ? "Monospaced" : Font.getDefault().getFamily(),
                        bold ? FontWeight.BOLD : FontWeight.NORMAL, Boolean.TRUE.equals(s.i) ? FontPosture.ITALIC : FontPosture.REGULAR, size);
                if (s.href != null && !s.href.isEmpty()) {
                    Hyperlink h = new Hyperlink(text);
                    h.setFont(font);
                    h.setPadding(Insets.EMPTY);
                    final String url = s.href;
                    h.setOnAction(e -> open(url));
                    f.getChildren().add(h);
                } else {
                    Text t = new Text(text);
                    t.setFont(font);
                    t.setFill(fill);
                    f.getChildren().add(t);
                }
            }
            return f;
        }

        /** "I accept the {privacy} and {terms}": the two names are links to the full pages (the word order differs per language). */
        void renderAcceptLabel(Color fill) {
            String template = tr("I accept the {privacy} and {terms}", "Tôi chấp nhận {privacy} và {terms}");
            String pageLang = doc != null && doc.lang != null ? doc.lang : Locale.getDefault().getLanguage();
            acceptFlow.getChildren().clear();
            int i = 0;
            while (i < template.length()) {
                int p = template.indexOf("{privacy}", i), t = template.indexOf("{terms}", i);
                int next = p < 0 ? t : t < 0 ? p : Math.min(p, t);
                if (next < 0) { addText(template.substring(i), fill); break; }
                if (next > i) addText(template.substring(i, next), fill);
                boolean isPrivacy = next == p;
                Hyperlink link = new Hyperlink(isPrivacy ? tr("Privacy Policy", "Chính sách bảo mật") : tr("Terms of Service", "Điều khoản dịch vụ"));
                link.setPadding(Insets.EMPTY);
                final String url = client.legalPageUrl(isPrivacy ? "privacy" : "terms", pageLang);
                link.setOnAction(e -> open(url));
                acceptFlow.getChildren().add(link);
                i = next + (isPrivacy ? "{privacy}".length() : "{terms}".length());
            }
        }

        void addText(String s, Color fill) {
            Text t = new Text(s);
            t.setFill(fill);
            acceptFlow.getChildren().add(t);
        }

        void open(String url) {
            try { DoubleClipsDesktop.getInstance().getHostServices().showDocument(url); } catch (RuntimeException ignored) { /* no browser */ }
        }

        // ── accept ──────────────────────────────────────────────────────────

        void submit() {
            if (busy.get() || doc == null || !check.isSelected()) return;
            busy.set(true);
            error.setVisible(false);
            error.setManaged(false);
            go.setText(tr("Saving…", "Đang lưu…"));
            final Map<String, String> versions = new HashMap<>();
            for (LegalModels.StatusDoc d : status.documents) versions.put(d.key, d.version);
            final String shownLang = doc.lang;
            IO.execute(() -> {
                try {
                    client.acceptLegal(versions, shownLang);
                    Platform.runLater(() -> {
                        busy.set(false);
                        stage.close();                                           // close() does not go through the (blocked) close request
                        if (onAccepted != null) onAccepted.run();
                        for (Runnable r : ACCEPTED) r.run();
                    });
                } catch (Exception e) {
                    final boolean changed = e instanceof DriveError && "version_changed".equals(((DriveError) e).getCode());
                    Platform.runLater(() -> {
                        busy.set(false);
                        go.setText(tr("Continue", "Tiếp tục"));
                        error.setText(e.getMessage());
                        error.setVisible(true);
                        error.setManaged(true);
                        // New terms were published while this one was open: reload /me so the new text is what gets accepted.
                        if (changed) { stage.close(); checkAndShow(); }
                    });
                }
            });
        }
    }
}
