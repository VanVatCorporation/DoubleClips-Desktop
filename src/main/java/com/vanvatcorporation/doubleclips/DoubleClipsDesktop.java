package com.vanvatcorporation.doubleclips;

import atlantafx.base.theme.CupertinoDark;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class DoubleClipsDesktop extends Application {

    @Override
    public void start(Stage stage) {
        // Set the Cupertino Dark theme for that "iOS on macOS" look
        Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());

        Label titleLabel = new Label("DoubleClips");
        titleLabel.setStyle("-fx-font-size: 32px; -fx-font-weight: bold; -fx-text-fill: -color-accent-fg;");

        Label subTitleLabel = new Label("Cross-platform Video Editor");
        subTitleLabel.setStyle("-fx-font-size: 16px; -fx-opacity: 0.7;");

        Button actionButton = new Button("Create New Project");
        actionButton.getStyleClass().addAll("accent", "large");
        actionButton.setPrefWidth(200);
        
        // Add some "iOS" styling to the button (rounded corners are handled by Cupertino theme)
        actionButton.setOnAction(e -> {
            System.out.println("Creating new project...");
        });

        VBox layout = new VBox(15, titleLabel, subTitleLabel, actionButton);
        layout.setAlignment(Pos.CENTER);
        layout.setPadding(new Insets(50));
        layout.setStyle("-fx-background-color: -color-bg-default;");

        Scene scene = new Scene(layout, 900, 600);
        stage.setScene(scene);
        stage.setTitle("DoubleClips Desktop");
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
