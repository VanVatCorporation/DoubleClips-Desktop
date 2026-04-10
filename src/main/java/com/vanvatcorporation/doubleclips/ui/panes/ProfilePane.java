package com.vanvatcorporation.doubleclips.ui.panes;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.auth.AuthRepository;
import com.vanvatcorporation.doubleclips.auth.User;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;
import javafx.scene.shape.Circle;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignH;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import com.vanvatcorporation.doubleclips.ui.overlays.LoginOverlay;
import javafx.scene.Node;

public class ProfilePane extends VBox {

    private final VBox profileHeader;
    private final VBox signInPrompt;
    private final VBox accountSettingsGroup;
    
    private final Circle avatarCircle;
    private final Label nameLabel;
    private final Label emailLabel;

    public ProfilePane() {
        setSpacing(30);
        setPadding(new Insets(40));
        setAlignment(Pos.TOP_CENTER);
        getStyleClass().add("content-pane");

        // --- 1. Profile Header (Logged In) ---
        profileHeader = new VBox(15);
        profileHeader.setAlignment(Pos.CENTER);

        avatarCircle = new Circle(60);
        avatarCircle.setFill(Color.web("#333"));
        avatarCircle.setStroke(Color.web("-color-border-subtle"));
        avatarCircle.setStrokeWidth(2);

        nameLabel = new Label("User Name");
        nameLabel.setStyle("-fx-font-size: 28px; -fx-font-weight: bold;");
        
        emailLabel = new Label("user@example.com");
        emailLabel.getStyleClass().add("text-muted");

        profileHeader.getChildren().addAll(avatarCircle, nameLabel, emailLabel);

        // --- 2. Sign In Prompt (Logged Out) ---
        signInPrompt = new VBox(20);
        signInPrompt.setAlignment(Pos.CENTER);
        signInPrompt.setPadding(new Insets(40, 0, 40, 0));

        Label promptTitle = new Label("Sign in to DoubleClips");
        promptTitle.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        
        Label promptDesc = new Label("Sign in to sync your projects and access professional templates.");
        promptDesc.getStyleClass().add("text-muted");
        promptDesc.setWrapText(true);
        promptDesc.setMaxWidth(400);
        promptDesc.setAlignment(Pos.CENTER);

        Button signInButton = new Button("Sign In");
        signInButton.getStyleClass().addAll("button-primary", "button-large");
        signInButton.setPrefWidth(200);
        signInButton.setOnAction(e -> {
            // We use an array for the reference so we can use it inside the lambda
            final LoginOverlay[] overlayRef = new LoginOverlay[1];
            overlayRef[0] = new LoginOverlay(v -> {
                DoubleClipsDesktop.getInstance().hideOverlay(overlayRef[0]);
            });
            DoubleClipsDesktop.getInstance().showOverlay(overlayRef[0]);
        });

        signInPrompt.getChildren().addAll(promptTitle, promptDesc, signInButton);

        // --- 3. Settings Group: General ---
        VBox generalSettings = new VBox(0);
        generalSettings.getStyleClass().add("settings-group");
        generalSettings.setMaxWidth(500);

        Label generalHeader = new Label("GENERAL");
        generalHeader.getStyleClass().add("settings-header");
        generalHeader.setPadding(new Insets(0, 0, 8, 4));

        generalSettings.getChildren().addAll(
                createSettingButton("App Settings", new FontIcon(MaterialDesignC.COG))
        );

        // --- 4. Settings Group: Account ---
        accountSettingsGroup = new VBox(0);
        accountSettingsGroup.getStyleClass().add("settings-group");
        accountSettingsGroup.setMaxWidth(500);

        Label accountHeader = new Label("ACCOUNT");
        accountHeader.getStyleClass().add("settings-header");
        accountHeader.setPadding(new Insets(0, 0, 8, 4));

        accountSettingsGroup.getChildren().addAll(
                createSettingButton("Statistics", new FontIcon(MaterialDesignP.POLL)),
                new Separator(),
                createSettingButton("Saved Templates", new FontIcon(MaterialDesignH.HEART)),
                new Separator(),
                createLogoutButton()
        );

        // Assemble initial layout
        getChildren().addAll(profileHeader, signInPrompt, generalSettings, accountSettingsGroup);

        // Bind to Auth State
        AuthRepository.getInstance().userProperty().addListener((obs, oldUser, newUser) -> {
            updateUI(newUser);
        });

        // Initialize UI state
        updateUI(AuthRepository.getInstance().getCurrentUser());
        
        // Initial session check
        AuthRepository.getInstance().checkSession(new AuthRepository.AuthCallback<User>() {
            @Override public void onSuccess(User data) {}
            @Override public void onError(String message) {}
        });
    }

    private void updateUI(User user) {
        if (user != null) {
            nameLabel.setText(user.getUsername());
            emailLabel.setText(user.getEmail());
            
            // Load Avatar
            String avatarUrl = "https://account.vanvatcorp.com/api/avatar/" + user.getId();
            Image avatarImg = new Image(avatarUrl, true);
            avatarImg.progressProperty().addListener((obs, oldP, newP) -> {
                if (newP.doubleValue() == 1.0 && !avatarImg.isError()) {
                    avatarCircle.setFill(new ImagePattern(avatarImg));
                }
            });

            profileHeader.setManaged(true);
            profileHeader.setVisible(true);
            signInPrompt.setManaged(false);
            signInPrompt.setVisible(false);
            accountSettingsGroup.setManaged(true);
            accountSettingsGroup.setVisible(true);
        } else {
            profileHeader.setManaged(false);
            profileHeader.setVisible(false);
            signInPrompt.setManaged(true);
            signInPrompt.setVisible(true);
            accountSettingsGroup.setManaged(false);
            accountSettingsGroup.setVisible(false);
            
            avatarCircle.setFill(Color.web("#333"));
        }
    }

    private Button createSettingButton(String text, FontIcon icon) {
        Button btn = new Button(text);
        btn.setGraphic(icon);
        btn.getStyleClass().addAll("button-transparent", "setting-item");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setAlignment(Pos.CENTER_LEFT);
        btn.setGraphicTextGap(15);
        btn.setPadding(new Insets(15, 20, 15, 20));
        return btn;
    }

    private Button createLogoutButton() {
        Button btn = createSettingButton("Log Out", new FontIcon(MaterialDesignL.LOGOUT));
        btn.setStyle("-fx-text-fill: -color-danger-fg;");
        if (btn.getGraphic() != null) {
            btn.getGraphic().setStyle("-fx-icon-color: -color-danger-fg;");
        }
        btn.setOnAction(e -> handleLogout());
        return btn;
    }

    private void handleLogout() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Log Out");
        alert.setHeaderText("Log out from DoubleClips?");
        alert.setContentText("Are you sure you want to log out?");
        
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