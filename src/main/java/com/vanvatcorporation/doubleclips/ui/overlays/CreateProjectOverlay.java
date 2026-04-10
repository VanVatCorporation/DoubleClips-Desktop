package com.vanvatcorporation.doubleclips.ui.overlays;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.data.ProjectRepository;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

public class CreateProjectOverlay extends StackPane {

    private final StackPane cardContainer;
    private final VBox step1;
    private final VBox step2;
    private final TextField titleInput;

    public CreateProjectOverlay() {
        getStyleClass().add("modal-overlay");
        
        // 1. Dialog Card
        VBox dialogCard = new VBox();
        dialogCard.getStyleClass().add("creation-dialog");
        dialogCard.setMaxSize(480, 450);
        dialogCard.setClip(new javafx.scene.shape.Rectangle(480, 450)); // Prevent content spill during animation

        cardContainer = new StackPane();
        VBox.setVgrow(cardContainer, Priority.ALWAYS);

        // --- STEP 1: Option Picker ---
        step1 = new VBox(24);
        step1.setPadding(new Insets(32));
        step1.setAlignment(Pos.TOP_LEFT);

        Label step1Title = new Label("Create New Project");
        step1Title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");

        HBox options = new HBox(16);
        options.setAlignment(Pos.CENTER);
        
        VBox newProjectCard = createOptionCard(MaterialDesignP.PLUS, "New Project");
        newProjectCard.setOnMouseClicked(e -> goToStep2());
        
        VBox importProjectCard = createOptionCard(MaterialDesignF.FOLDER_UPLOAD, "Import Project");
        // Non-functional for now as requested
        importProjectCard.setOpacity(0.5);

        HBox.setHgrow(newProjectCard, Priority.ALWAYS);
        HBox.setHgrow(importProjectCard, Priority.ALWAYS);
        options.getChildren().addAll(newProjectCard, importProjectCard);

        step1.getChildren().addAll(step1Title, options);

        // --- STEP 2: Project Form ---
        step2 = new VBox(12);
        step2.setPadding(new Insets(32));
        step2.setAlignment(Pos.TOP_LEFT);
        step2.setTranslateX(480); // Start off-screen to the right

        Label step2Title = new Label("New Project");
        step2Title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");

        Label step2Sub = new Label("Give your project a name to get started.");
        step2Sub.getStyleClass().add("text-muted");

        titleInput = new TextField();
        titleInput.setPromptText("Project title...");
        titleInput.setPrefHeight(50);
        titleInput.setStyle("-fx-background-radius: 12; -fx-padding: 0 16;");

        Button createBtn = new Button("Create Project");
        createBtn.getStyleClass().addAll("button-primary", "button-large");
        createBtn.setMaxWidth(Double.MAX_VALUE);
        createBtn.setPrefHeight(50);
        createBtn.setOnAction(e -> handleCreate());

        Button backBtn = new Button("Back");
        backBtn.getStyleClass().add("button-transparent");
        backBtn.setMaxWidth(Double.MAX_VALUE);
        backBtn.setOnAction(e -> goToStep1());

        step2.getChildren().addAll(step2Title, step2Sub, titleInput, createBtn, backBtn);

        cardContainer.getChildren().addAll(step1, step2);
        dialogCard.getChildren().add(cardContainer);

        getChildren().add(dialogCard);

        // Close on background click
        this.setOnMouseClicked(e -> {
            if (e.getTarget() == this) close();
        });
    }

    private VBox createOptionCard(org.kordamp.ikonli.Ikon icon, String title) {
        VBox card = new VBox(12);
        card.getStyleClass().add("option-card");
        
        FontIcon fi = new FontIcon(icon);
        fi.getStyleClass().add("option-card-icon");
        
        Label lbl = new Label(title);
        lbl.getStyleClass().add("option-card-title");
        
        card.getChildren().addAll(fi, lbl);
        return card;
    }

    private void goToStep2() {
        animateSwipe(step1, step2, -480, 0);
        javafx.application.Platform.runLater(titleInput::requestFocus);
    }

    private void goToStep1() {
        animateSwipe(step1, step2, 0, 480);
    }

    private void animateSwipe(VBox out, VBox in, double outTargetX, double inTargetX) {
        TranslateTransition ttOut = new TranslateTransition(Duration.millis(250), out);
        ttOut.setToX(outTargetX);
        ttOut.setInterpolator(Interpolator.EASE_BOTH);

        TranslateTransition ttIn = new TranslateTransition(Duration.millis(250), in);
        ttIn.setToX(inTargetX);
        ttIn.setInterpolator(Interpolator.EASE_BOTH);

        ttOut.play();
        ttIn.play();
    }

    private void handleCreate() {
        String name = titleInput.getText().trim();
        if (!name.isEmpty()) {
            ProjectRepository.getInstance().createNewProject(name);
            close();
        }
    }

    private void close() {
        DoubleClipsDesktop.getInstance().hideOverlay(this);
    }
}
