package com.vanvatcorporation.doubleclips.ui.panes;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

public class HomePane extends VBox {

    public HomePane() {
        setSpacing(16);
        setPadding(new Insets(32));
        getStyleClass().add("content-pane");

        // 1. "Recent Projects" header with sort icon (top-right)
        Label titleLabel = new Label("Recent Projects");
        titleLabel.setStyle("-fx-font-size: 26px; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button sortButton = new Button();
        sortButton.setGraphic(new FontIcon(MaterialDesignS.SORT_VARIANT));
        sortButton.getStyleClass().addAll("button-transparent");

        HBox header = new HBox(titleLabel, spacer, sortButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 8, 0));

        // 2. Separator line under title (thin, matching sketch)
        Region divider = new Region();
        divider.setStyle("-fx-background-color: -color-border-subtle;");
        divider.setPrefHeight(1);
        divider.setMaxWidth(Double.MAX_VALUE);

        // 3. Project grid — 2-column using GridPane
        GridPane projectGrid = new GridPane();
        projectGrid.setHgap(20);
        projectGrid.setVgap(16);
        projectGrid.setPadding(new Insets(16, 0, 80, 0));

        // Ensure equal column widths
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        projectGrid.getColumnConstraints().addAll(col1, col2);

        // Mock project data matching the sketch
        String mockDate = "02/04/2026 05:47";
        String mockStats = "00:00:03 • 0.90MB";

        String[] names = {"abc", "abc", "abc", "abc", "abc"};
        int col = 0, row = 0;
        for (String name : names) {
            projectGrid.add(createProjectCard(name, mockDate, mockStats), col, row);
            col++;
            if (col > 1) {
                col = 0;
                row++;
            }
        }

        ScrollPane scrollPane = new ScrollPane(projectGrid);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        getChildren().addAll(header, divider, scrollPane);
    }

    /**
     * Horizontal card: [thumbnail] | [title / date / stats] | [menu icon]
     * Matching the sketch layout for each project entry.
     */
    private HBox createProjectCard(String title, String date, String stats) {
        HBox card = new HBox(16);
        card.getStyleClass().add("project-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(Double.MAX_VALUE);

        // Thumbnail (square, dark placeholder)
        Region thumbnail = new Region();
        thumbnail.getStyleClass().add("project-thumbnail");
        thumbnail.setPrefSize(80, 80);
        thumbnail.setMinSize(80, 80);
        thumbnail.setMaxSize(80, 80);

        // Text block
        VBox textContainer = new VBox(3);
        HBox.setHgrow(textContainer, Priority.ALWAYS);

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        Label dateLabel = new Label(date);
        dateLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.6;");

        Label statsLabel = new Label(stats);
        statsLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.6;");

        textContainer.getChildren().addAll(titleLabel, dateLabel, statsLabel);
        textContainer.setAlignment(Pos.CENTER_LEFT);

        // Three-line (hamburger) menu icon on the right
        Button menuBtn = new Button();
        menuBtn.setGraphic(new FontIcon(MaterialDesignD.DOTS_HORIZONTAL));
        menuBtn.getStyleClass().addAll("button-transparent");

        card.getChildren().addAll(thumbnail, textContainer, menuBtn);
        return card;
    }
}
