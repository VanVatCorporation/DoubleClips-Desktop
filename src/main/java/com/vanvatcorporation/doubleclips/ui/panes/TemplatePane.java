package com.vanvatcorporation.doubleclips.ui.panes;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.data.TemplateData;
import com.vanvatcorporation.doubleclips.ui.overlays.TemplatePreviewOverlay;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

public class TemplatePane extends VBox {

    private FlowPane templateGrid;

    public TemplatePane() {
        setSpacing(24);
        setPadding(new Insets(24));
        getStyleClass().add("content-pane");

        // 1. Header
        Label titleLabel = new Label("Templates");
        titleLabel.setStyle("-fx-font-size: 32px; -fx-font-weight: bold;");

        // 2. Search Bar
        HBox searchContainer = new HBox(15);
        searchContainer.setAlignment(Pos.CENTER_LEFT);
        searchContainer.setPadding(new Insets(12, 18, 12, 18));
        searchContainer.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 25; -fx-border-color: -color-border-subtle; -fx-border-radius: 25;");

        FontIcon searchIcon = new FontIcon(MaterialDesignM.MAGNIFY);
        TextField searchField = new TextField();
        searchField.setPromptText("Search templates...");
        searchField.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-text-fill: -color-fg-default;");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        searchContainer.getChildren().addAll(searchIcon, searchField);

        // 3. Template Grid
        templateGrid = new FlowPane();
        templateGrid.setHgap(20);
        templateGrid.setVgap(20);
        templateGrid.setPadding(new Insets(10, 0, 80, 0));

        ScrollPane scrollPane = new ScrollPane(templateGrid);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");

        getChildren().addAll(titleLabel, searchContainer, scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // Mock data
        loadMockTemplates();
    }

    private void loadMockTemplates() {
        for (int i = 0; i < 10; i++) {
            // Using standard test video links
            String videoUrl = (i % 2 == 0) ? 
                "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4" : 
                "https://storage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4";
            
            templateGrid.getChildren().add(createTemplateCard("Movie Intro #" + (i + 1), videoUrl));
        }
    }

    private VBox createTemplateCard(String title, String videoUrl) {
        VBox card = new VBox(12);
        card.getStyleClass().add("project-card");
        card.setPrefWidth(240);
        card.setCursor(javafx.scene.Cursor.HAND);

        Region preview = new Region();
        preview.getStyleClass().add("project-thumbnail");
        preview.setPrefHeight(340);
        
        VBox info = new VBox(4);
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        
        Label authorLabel = new Label("@creator_name");
        authorLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        info.getChildren().addAll(titleLabel, authorLabel);

        card.getChildren().addAll(preview, info);
        
        card.setOnMouseClicked(e -> {
            TemplateData data = new TemplateData();
            data.setTemplateTitle(title);
            data.setTemplateAuthor("vanvatcorp");
            data.setTemplateVideoLink(videoUrl);
            data.setHeartCount(120 + (int)(Math.random() * 50));
            data.setBookmarkCount(45 + (int)(Math.random() * 20));

            // Use an array to allow the lambda to reference the overlay instance being created
            TemplatePreviewOverlay[] overlayWrapper = new TemplatePreviewOverlay[1];
            overlayWrapper[0] = new TemplatePreviewOverlay(data, v -> {
                DoubleClipsDesktop.getInstance().hideOverlay(overlayWrapper[0]);
            });
            
            DoubleClipsDesktop.getInstance().showOverlay(overlayWrapper[0]);
        });

        return card;
    }
}