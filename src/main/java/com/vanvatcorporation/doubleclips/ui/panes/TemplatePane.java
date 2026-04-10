package com.vanvatcorporation.doubleclips.ui.panes;

import com.vanvatcorporation.doubleclips.DoubleClipsDesktop;
import com.vanvatcorporation.doubleclips.auth.AuthRepository;
import com.vanvatcorporation.doubleclips.auth.TemplateRepository;
import com.vanvatcorporation.doubleclips.data.TemplateData;
import com.vanvatcorporation.doubleclips.ui.overlays.TemplatePreviewOverlay;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

import java.util.List;

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

        // 4. Data Observation
        TemplateRepository.getInstance().templatesProperty().addListener((obs, old, newList) -> {
            Platform.runLater(() -> {
                updateGrid(newList, searchField.getText());
            });
        });

        // Re-fetch when user logs in/out to update like/bookmark states
        AuthRepository.getInstance().userProperty().addListener((obs, old, val) -> {
            TemplateRepository.getInstance().fetchTemplates();
        });

        searchField.textProperty().addListener((obs, old, val) -> {
            updateGrid(TemplateRepository.getInstance().templatesProperty().get(), val);
        });

        // Initial Fetch
        TemplateRepository.getInstance().fetchTemplates();
    }

    private void updateGrid(List<TemplateData> allTemplates, String filter) {
        templateGrid.getChildren().clear();
        String pattern = filter == null ? "" : filter.toLowerCase().trim();

        for (TemplateData data : allTemplates) {
            if (pattern.isEmpty() || data.getTemplateTitle().toLowerCase().contains(pattern)) {
                templateGrid.getChildren().add(createTemplateCard(data));
            }
        }
    }

    private VBox createTemplateCard(TemplateData data) {
        VBox card = new VBox(12);
        card.getStyleClass().add("project-card");
        card.setPrefWidth(240);
        card.setCursor(javafx.scene.Cursor.HAND);

        // Thumbnail placeholder while loading
        StackPane thumbnailStack = new StackPane();
        thumbnailStack.getStyleClass().add("project-thumbnail");
        thumbnailStack.setPrefHeight(340);
        
        ImageView thumbView = new ImageView();
        thumbView.setFitWidth(240);
        thumbView.setPreserveRatio(true);
        
        // Asynchronous thumbnail loading
        if (data.getTemplateSnapshotLink() != null) {
            Platform.runLater(() -> {
                Image img = new Image(data.getTemplateSnapshotLink(), true);
                thumbView.setImage(img);
            });
        }
        
        thumbnailStack.getChildren().add(thumbView);
        
        VBox info = new VBox(4);
        Label titleLabel = new Label(data.getTemplateTitle());
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        
        Label authorLabel = new Label("@" + data.getTemplateAuthor());
        authorLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        info.getChildren().addAll(titleLabel, authorLabel);

        card.getChildren().addAll(thumbnailStack, info);
        
        card.setOnMouseClicked(e -> {
            TemplatePreviewOverlay[] overlayWrapper = new TemplatePreviewOverlay[1];
            overlayWrapper[0] = new TemplatePreviewOverlay(data, v -> {
                DoubleClipsDesktop.getInstance().hideOverlay(overlayWrapper[0]);
            });
            DoubleClipsDesktop.getInstance().showOverlay(overlayWrapper[0]);
        });

        return card;
    }
}