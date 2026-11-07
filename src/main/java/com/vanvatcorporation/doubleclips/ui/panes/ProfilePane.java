package com.vanvatcorporation.doubleclips.ui.panes;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.auth.AuthRepository;
import com.vanvatcorporation.doubleclips.auth.TemplateFeedSource;
import com.vanvatcorporation.doubleclips.auth.User;
import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.ProjectRepository;
import com.vanvatcorporation.doubleclips.helper.DateHelper;
import com.vanvatcorporation.doubleclips.i18n.I18n;
import com.vanvatcorporation.doubleclips.ui.overlays.LoginOverlay;
import com.vanvatcorporation.doubleclips.ui.overlays.SettingsOverlay;
import com.vanvatcorporation.doubleclips.ui.overlays.StatisticsOverlay;
import com.vanvatcorporation.doubleclips.ui.overlays.TemplateCollectionOverlay;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignH;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

/**
 * The Profile tab: the account on top, then grouped rows (each a coloured icon tile, a title, a short line under it and a
 * chevron) in rounded cards, the way the iOS Profile tab is laid out. Statistics and the settings work signed out too; the
 * template lists and Log Out need an account.
 */
public class ProfilePane extends VBox {

    private static final double COLUMN_WIDTH = 560;

    private final VBox profileHeader;
    private final VBox signInPrompt;
    /** Everything that needs an account (the template lists, Log Out). */
    private final VBox accountBox;

    private final Circle avatarCircle;
    private final Label avatarInitial;
    private final Label nameLabel;
    private final Label emailLabel;
    private final Label summaryLabel;

    public ProfilePane() {
        setAlignment(Pos.TOP_CENTER);
        getStyleClass().add("content-pane");

        // --- 1. Profile header (signed in) ---
        Circle ring = new Circle(63);
        ring.setFill(Color.TRANSPARENT);
        ring.getStyleClass().add("profile-avatar-ring");

        avatarCircle = new Circle(56);
        avatarCircle.setFill(Color.web("#3a3a40"));

        avatarInitial = new Label("");
        avatarInitial.getStyleClass().add("profile-avatar-initial");

        StackPane avatar = new StackPane(ring, avatarCircle, avatarInitial);

        nameLabel = new Label(I18n.t("User Name"));
        nameLabel.getStyleClass().add("profile-name");

        emailLabel = new Label(I18n.t("user@example.com"));
        emailLabel.getStyleClass().add("text-muted");

        summaryLabel = new Label("");
        summaryLabel.getStyleClass().add("profile-summary");

        profileHeader = new VBox(8, avatar, nameLabel, emailLabel, summaryLabel);
        profileHeader.setAlignment(Pos.CENTER);
        VBox.setMargin(nameLabel, new Insets(10, 0, 0, 0));
        VBox.setMargin(summaryLabel, new Insets(8, 0, 0, 0));

        // --- 2. Sign-in prompt (signed out) ---
        signInPrompt = new VBox(16);
        signInPrompt.setAlignment(Pos.CENTER);
        signInPrompt.setPadding(new Insets(24, 0, 12, 0));

        Label promptTitle = new Label(I18n.t("Sign in to DoubleClips"));
        promptTitle.getStyleClass().add("profile-name");

        Label promptDesc = new Label(I18n.t("Sign in to sync your projects and access professional templates."));
        promptDesc.getStyleClass().add("text-muted");
        promptDesc.setWrapText(true);
        promptDesc.setMaxWidth(400);
        promptDesc.setAlignment(Pos.CENTER);
        promptDesc.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        Button signInButton = new Button(I18n.t("Sign In"));
        signInButton.getStyleClass().add("app-button-primary");
        signInButton.setPrefWidth(200);
        signInButton.setOnAction(e -> {
            final LoginOverlay[] overlayRef = new LoginOverlay[1];
            overlayRef[0] = new LoginOverlay(v -> DoubleClipsDesktop.getInstance().hideOverlay(overlayRef[0]));
            DoubleClipsDesktop.getInstance().showOverlay(overlayRef[0]);
        });

        signInPrompt.getChildren().addAll(promptTitle, promptDesc, signInButton);

        // --- 3. General (works signed out) ---
        Button statsBtn = row(I18n.t("Statistics"), I18n.t("Your projects and templates at a glance"),
                new FontIcon(MaterialDesignP.POLL), "tile-blue", false);
        statsBtn.setOnAction(e -> {
            final StatisticsOverlay[] ref = new StatisticsOverlay[1];
            ref[0] = new StatisticsOverlay(v -> DoubleClipsDesktop.getInstance().hideOverlay(ref[0]));
            DoubleClipsDesktop.getInstance().showOverlay(ref[0]);
        });

        Button settingsBtn = row(I18n.t("App Settings"), I18n.t("Preferences"),
                new FontIcon(MaterialDesignC.COG), "tile-grey", false);
        settingsBtn.setOnAction(e -> {
            final SettingsOverlay[] ref = new SettingsOverlay[1];
            ref[0] = new SettingsOverlay(v -> DoubleClipsDesktop.getInstance().hideOverlay(ref[0]));
            DoubleClipsDesktop.getInstance().showOverlay(ref[0]);
        });

        VBox general = section(I18n.t("GENERAL"), group(statsBtn, settingsBtn));

        // --- 4. Templates + Log Out (need an account) ---
        VBox templates = section(I18n.t("TEMPLATES"), group(
                templatesButton(I18n.t("My Templates"), I18n.t("Templates you posted"), new FontIcon(MaterialDesignF.FOLDER), "tile-orange", TemplateFeedSource.mine()),
                templatesButton(I18n.t("Liked Templates"), I18n.t("Templates you liked"), new FontIcon(MaterialDesignH.HEART), "tile-pink", TemplateFeedSource.liked()),
                templatesButton(I18n.t("Saved Templates"), I18n.t("Templates you saved"), new FontIcon(MaterialDesignB.BOOKMARK), "tile-purple", TemplateFeedSource.bookmarked())));

        VBox logout = new VBox(group(createLogoutButton()));
        logout.setMaxWidth(COLUMN_WIDTH);

        accountBox = new VBox(24, templates, logout);
        accountBox.setAlignment(Pos.TOP_CENTER);

        // --- Layout: a scrolling column, so a short window never cuts the list off ---
        VBox column = new VBox(24, profileHeader, signInPrompt, general, accountBox);
        column.setAlignment(Pos.TOP_CENTER);
        column.setPadding(new Insets(36, 24, 48, 24));

        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("profile-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().add(scroll);

        // Bind to auth state
        AuthRepository.getInstance().userProperty().addListener((obs, oldUser, newUser) -> updateUI(newUser));

        // The one-line summary under the email follows the local project list
        ProjectRepository.getInstance().projectsProperty().addListener((javafx.beans.Observable o) -> refreshSummary());

        updateUI(AuthRepository.getInstance().getCurrentUser());
        refreshSummary();
    }

    private void updateUI(User user) {
        if (user != null) {
            nameLabel.setText(user.getUsername());
            emailLabel.setText(user.getEmail());

            String name = user.getUsername();
            avatarInitial.setText(name == null || name.isEmpty() ? "" : name.substring(0, 1).toUpperCase());
            avatarInitial.setVisible(true);
            avatarCircle.setFill(Color.web("#3a3a40"));

            String avatarUrl = "https://account.vanvatcorp.com/api/avatar/" + user.getId();
            Image avatarImg = new Image(avatarUrl, true);
            avatarImg.progressProperty().addListener((obs, oldP, newP) -> {
                if (newP.doubleValue() == 1.0 && !avatarImg.isError()) {
                    avatarCircle.setFill(new ImagePattern(avatarImg));
                    avatarInitial.setVisible(false);    // the picture replaces the letter
                }
            });

            show(profileHeader, true);
            show(signInPrompt, false);
            show(accountBox, true);
        } else {
            show(profileHeader, false);
            show(signInPrompt, true);
            show(accountBox, false);

            avatarCircle.setFill(Color.web("#3a3a40"));
            avatarInitial.setText("");
        }
    }

    private static void show(Node n, boolean visible) {
        n.setManaged(visible);
        n.setVisible(visible);
    }

    /** "12 projects · 01:23:45 of footage" under the email. */
    private void refreshSummary() {
        long totalMs = 0;
        int count = 0;
        for (ProjectData p : ProjectRepository.getInstance().projectsProperty()) {
            count++;
            totalMs += Math.max(0, p.getProjectDuration());
        }
        summaryLabel.setText(I18n.f("%d projects \u00b7 %s of footage", count, DateHelper.convertTimestampToHHMMSSFormat(totalMs)));
    }

    // ── Building blocks ────────────────────────────────────────────────────────

    /** A small grey header over a card. */
    private VBox section(String header, VBox group) {
        Label h = new Label(header);
        h.getStyleClass().add("profile-section-header");
        VBox s = new VBox(8, h, group);
        s.setMaxWidth(COLUMN_WIDTH);
        return s;
    }

    /** Rows in one rounded card with thin dividers between them. */
    private VBox group(Node... rows) {
        VBox g = new VBox();
        g.getStyleClass().add("profile-group");
        g.setMaxWidth(COLUMN_WIDTH);
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                Region divider = new Region();
                divider.getStyleClass().add("profile-divider");
                divider.setMinHeight(1);
                divider.setPrefHeight(1);
                divider.setMaxHeight(1);
                VBox.setMargin(divider, new Insets(0, 0, 0, 66));   // starts under the text, not under the icon
                g.getChildren().add(divider);
            }
            g.getChildren().add(rows[i]);
        }
        // the first / last row's hover colour must follow the card's rounded corners
        Rectangle clip = new Rectangle();
        clip.setArcWidth(28);
        clip.setArcHeight(28);
        clip.widthProperty().bind(g.widthProperty());
        clip.heightProperty().bind(g.heightProperty());
        g.setClip(clip);
        return g;
    }

    private Button row(String title, String subtitle, FontIcon icon, String tileClass, boolean danger) {
        icon.getStyleClass().add("profile-tile-icon");
        StackPane tile = new StackPane(icon);
        tile.getStyleClass().addAll("profile-tile", tileClass);
        tile.setMinSize(36, 36);
        tile.setPrefSize(36, 36);
        tile.setMaxSize(36, 36);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add(danger ? "profile-row-danger" : "profile-row-title");
        VBox text = new VBox(2, titleLabel);
        text.setAlignment(Pos.CENTER_LEFT);
        if (subtitle != null && !subtitle.isEmpty()) {
            Label sub = new Label(subtitle);
            sub.getStyleClass().add("profile-row-sub");
            text.getChildren().add(sub);
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox content = new HBox(14, tile, text, spacer);
        content.setAlignment(Pos.CENTER_LEFT);
        if (!danger) {
            FontIcon chevron = new FontIcon(MaterialDesignC.CHEVRON_RIGHT);
            chevron.getStyleClass().add("profile-chevron");
            content.getChildren().add(chevron);
        }

        Button btn = new Button();
        btn.setGraphic(content);
        btn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        btn.getStyleClass().add("profile-row");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setAlignment(Pos.CENTER_LEFT);
        content.prefWidthProperty().bind(btn.widthProperty().subtract(32));   // the button's padding is 16 + 16 (style.css)
        return btn;
    }

    /** A row that opens the account's My / Liked / Saved templates (paged from the server) over the app. */
    private Button templatesButton(String title, String subtitle, FontIcon icon, String tileClass, TemplateFeedSource source) {
        Button btn = row(title, subtitle, icon, tileClass, false);
        btn.setOnAction(e -> {
            final TemplateCollectionOverlay[] ref = new TemplateCollectionOverlay[1];
            ref[0] = new TemplateCollectionOverlay(source, v -> DoubleClipsDesktop.getInstance().hideOverlay(ref[0]));
            DoubleClipsDesktop.getInstance().showOverlay(ref[0]);
        });
        return btn;
    }

    private Button createLogoutButton() {
        Button btn = row(I18n.t("Log Out"), null, new FontIcon(MaterialDesignL.LOGOUT), "tile-red", true);
        btn.setOnAction(e -> handleLogout());
        return btn;
    }

    private void handleLogout() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.t("Log Out"));
        alert.setHeaderText(I18n.t("Log out from DoubleClips?"));
        alert.setContentText(I18n.t("Are you sure you want to log out?"));

        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                AuthRepository.getInstance().logout(new AuthRepository.AuthCallback<Void>() {
                    @Override public void onSuccess(Void data) {}
                    @Override public void onError(String message) {}
                });
            }
        });
    }
}
