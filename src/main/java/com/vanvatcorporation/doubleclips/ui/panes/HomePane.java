package com.vanvatcorporation.doubleclips.ui.panes;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

public class HomePane extends VBox {

    public HomePane() {
        setSpacing(24);
        setPadding(new Insets(24));
        getStyleClass().add("content-pane");

        // 1. Creative Header / "Start Creating" Button
        Button addProjectButton = new Button("Start Creating\nTap to create a new project");
        addProjectButton.getStyleClass().add("start-creating-button");
        addProjectButton.setMaxWidth(Double.MAX_VALUE);
        
        // Using MaterialDesignP.PLUS for the add icon
        FontIcon addIcon = new FontIcon(MaterialDesignP.PLUS);
        addProjectButton.setGraphic(addIcon);
        addProjectButton.setGraphicTextGap(20);
        
        // 2. Recent Projects Header
        Label titleLabel = new Label("Recent Projects");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        Button sortButton = new Button();
        sortButton.setGraphic(new FontIcon(MaterialDesignS.SORT_VARIANT));
        sortButton.getStyleClass().addAll("button-transparent");
        
        HBox header = new HBox(titleLabel, spacer, sortButton);
        header.setAlignment(Pos.CENTER_LEFT);

        // 3. Project Grid
        FlowPane projectGrid = new FlowPane();
        projectGrid.setHgap(20);
        projectGrid.setVgap(20);
        projectGrid.setPadding(new Insets(8, 0, 80, 0));
        
        // Mocking some project data matching the sketch metadata
        String mockDate = "02/04/2026 05:47";
        String mockStats = "00:00:03 • 0.90MB";

        for (int i = 0; i < 5; i++) {
            projectGrid.getChildren().add(createMockProjectCard("Project " + (i + 1), mockDate, mockStats));
        }

        ScrollPane scrollPane = new ScrollPane(projectGrid);
        scrollPane.setFitToWidth(true);
        // Remove scrollpane borders
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");

        getChildren().addAll(addProjectButton, header, scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
    }

    private VBox createMockProjectCard(String title, String date, String stats) {
        VBox card = new VBox(10);
        card.getStyleClass().add("project-card");
        card.setPrefWidth(260); // Adjust based on grid needs
        
        // Thumbnail area
        Region thumbnail = new Region();
        thumbnail.getStyleClass().add("project-thumbnail");
        thumbnail.setPrefHeight(150);
        
        VBox textContainer = new VBox(2);
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");
        
        Label dateLabel = new Label(date);
        dateLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.6;");
        
        Label statsLabel = new Label(stats);
        statsLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.6;");
        
        textContainer.getChildren().addAll(titleLabel, dateLabel, statsLabel);
        
        card.getChildren().addAll(thumbnail, textContainer);
        return card;
    }
}
