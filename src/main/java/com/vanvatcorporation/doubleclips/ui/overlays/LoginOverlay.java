package com.vanvatcorporation.doubleclips.ui.overlays;

import atlantafx.base.controls.PasswordTextField;
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
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;

import java.util.function.Consumer;

public class LoginOverlay extends StackPane {

    private final Consumer<Void> onClose;
    
    // UI Components
    private final TextField emailField;
    private final PasswordTextField passwordField;
    private final CheckBox rememberMeCheckbox;
    private final Button loginButton;
    private final Label errorLabel;
    private final StackPane loadingOverlay;

    public LoginOverlay(Consumer<Void> onClose) {
        this.onClose = onClose;
        
        // Background Dimming
        getStyleClass().add("preview-overlay");
        
        // Main Login Card
        VBox loginCard = new VBox(25);
        loginCard.getStyleClass().add("preview-container");
        loginCard.setMaxSize(400, 600);
        loginCard.setPadding(new Insets(40));
        loginCard.setAlignment(Pos.CENTER);
        
        // --- 1. Branding ---
        VBox branding = new VBox(15);
        branding.setAlignment(Pos.CENTER);
        
        ImageView logoView = new ImageView();
        try {
            Image logo = new Image(getClass().getResourceAsStream("/icons/app.png"));
            logoView.setImage(logo);
            logoView.setFitWidth(80);
            logoView.setPreserveRatio(true);
        } catch (Exception e) {
            System.err.println("Could not load logo: " + e.getMessage());
        }
        
        Label titleLabel = new Label("DoubleClips");
        titleLabel.setStyle("-fx-font-size: 28px; -fx-font-weight: bold;");
        
        Label subtitleLabel = new Label("Sign in to your account");
        subtitleLabel.getStyleClass().add("text-muted");
        
        branding.getChildren().addAll(logoView, titleLabel, subtitleLabel);
        
        // --- 2. Form Fields ---
        VBox form = new VBox(15);
        form.setAlignment(Pos.CENTER_LEFT);
        
        Label emailLabel = new Label("Email or Username");
        emailField = new TextField();
        emailField.setPromptText("Enter your email");
        emailField.setPrefHeight(45);
        
        Label passwordLabel = new Label("Password");
        passwordField = new PasswordTextField();
        passwordField.setPromptText("Enter your password");
        passwordField.setPrefHeight(45);
        
        rememberMeCheckbox = new CheckBox("Remember Me");
        rememberMeCheckbox.setSelected(true);
        
        errorLabel = new Label();
        errorLabel.getStyleClass().add("text-danger");
        errorLabel.setStyle("-fx-font-size: 13px;");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        
        form.getChildren().addAll(emailLabel, emailField, passwordLabel, passwordField, rememberMeCheckbox, errorLabel);
        
        // --- 3. Buttons ---
        VBox buttons = new VBox(12);
        buttons.setAlignment(Pos.CENTER);
        
        loginButton = new Button("Sign In");
        loginButton.getStyleClass().addAll("button-primary", "button-large");
        loginButton.setMaxWidth(Double.MAX_VALUE);
        loginButton.setPrefHeight(50);
        loginButton.setOnAction(e -> handleLogin());
        
        Button registerBtn = new Button("Don't have an account? Register");
        registerBtn.getStyleClass().addAll("button-transparent");
        registerBtn.setStyle("-fx-text-fill: -color-accent-fg;");
        registerBtn.setOnAction(e -> {
            DoubleClipsDesktop.getInstance().getHostServices().showDocument("https://account.vanvatcorp.com/register");
        });
        
        buttons.getChildren().addAll(loginButton, registerBtn);
        
        loginCard.getChildren().addAll(branding, form, buttons);
        
        // --- 4. Loading Overlay ---
        loadingOverlay = new StackPane();
        loadingOverlay.setStyle("-fx-background-color: rgba(0,0,0,0.6); -fx-background-radius: 24;");
        loadingOverlay.setVisible(false);
        
        ProgressIndicator progress = new ProgressIndicator();
        progress.setMaxSize(50, 50);
        loadingOverlay.getChildren().add(progress);
        
        StackPane container = new StackPane(loginCard, loadingOverlay);
        container.setMaxSize(400, 600);
        
        // --- 5. Close Button ---
        Button closeBtn = new Button();
        closeBtn.setGraphic(new FontIcon(MaterialDesignC.CLOSE));
        closeBtn.getStyleClass().add("button-transparent");
        closeBtn.setStyle("-fx-background-color: rgba(0,0,0,0.4); -fx-background-radius: 20; -fx-min-width: 40; -fx-min-height: 40;");
        closeBtn.setCursor(javafx.scene.Cursor.HAND);
        closeBtn.setOnAction(e -> onClose.accept(null));
        
        StackPane.setAlignment(closeBtn, Pos.TOP_LEFT);
        StackPane.setMargin(closeBtn, new Insets(30));
        
        getChildren().addAll(container, closeBtn);
        
        // Background click to close
        this.setOnMouseClicked(e -> {
            if (e.getTarget() == this) {
                onClose.accept(null);
            }
        });
        
        // Enter key to login
        passwordField.setOnKeyPressed(e -> {
            if (e.getCode().toString().equals("ENTER")) handleLogin();
        });
    }

    private void handleLogin() {
        String email = emailField.getText().trim();
        String password = passwordField.getText();
        
        if (email.isEmpty() || password.isEmpty()) {
            showError("Please enter both email and password.");
            return;
        }
        
        setLoading(true);
        AuthRepository.getInstance().login(email, password, new AuthRepository.AuthCallback<User>() {
            @Override
            public void onSuccess(User data) {
                Platform.runLater(() -> {
                    setLoading(false);
                    onClose.accept(null);
                });
            }

            @Override
            public void onError(String message) {
                Platform.runLater(() -> {
                    setLoading(false);
                    showError("Login failed: " + message);
                });
            }
        });
    }

    private void setLoading(boolean loading) {
        loadingOverlay.setVisible(loading);
        loginButton.setDisable(loading);
        emailField.setDisable(loading);
        passwordField.setDisable(loading);
        rememberMeCheckbox.setDisable(loading);
    }
    
    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }
}
