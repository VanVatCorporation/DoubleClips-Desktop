package com.vanvatcorporation.doubleclips.ui.panes;

import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.ProjectRepository;
import com.vanvatcorporation.doubleclips.helper.DateHelper;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.util.Optional;

public class HomePane extends VBox {

    private final GridPane projectGrid;
    private final VBox welcomePane;
    private final ScrollPane scrollPane;

    public HomePane() {
        setSpacing(16);
        setPadding(new Insets(32));
        getStyleClass().add("content-pane");

        // 1. Header
        Label titleLabel = new Label("Recent Projects");
        titleLabel.setStyle("-fx-font-size: 26px; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button addButton = new Button("New Project");
        addButton.getStyleClass().addAll("button-primary");
        addButton.setGraphic(new FontIcon(MaterialDesignP.PLUS));
        addButton.setOnAction(e -> showCreateProjectDialog());

        Button sortButton = new Button();
        sortButton.setGraphic(new FontIcon(MaterialDesignS.SORT_VARIANT));
        sortButton.getStyleClass().addAll("button-transparent");

        HBox header = new HBox(titleLabel, spacer, addButton, sortButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setSpacing(10);
        header.setPadding(new Insets(0, 0, 8, 0));

        Region divider = new Region();
        divider.setStyle("-fx-background-color: -color-border-subtle;");
        divider.setPrefHeight(1);
        divider.setMaxWidth(Double.MAX_VALUE);

        // 2. Content Stack (List or Welcome)
        projectGrid = new GridPane();
        projectGrid.setHgap(20);
        projectGrid.setVgap(16);
        projectGrid.setPadding(new Insets(16, 0, 80, 0));

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        projectGrid.getColumnConstraints().addAll(col1, col2);

        scrollPane = new ScrollPane(projectGrid);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        welcomePane = createWelcomePane();
        VBox.setVgrow(welcomePane, Priority.ALWAYS);

        StackPane contentStack = new StackPane(scrollPane, welcomePane);
        VBox.setVgrow(contentStack, Priority.ALWAYS);

        getChildren().addAll(header, divider, contentStack);

        // 3. Data Binding
        ProjectRepository repository = ProjectRepository.getInstance();
        repository.projectsProperty().addListener((ListChangeListener<ProjectData>) c -> renderProjects());
        
        // Initial render
        renderProjects();
    }

    private void renderProjects() {
        projectGrid.getChildren().clear();
        var projects = ProjectRepository.getInstance().projectsProperty().get();

        if (projects.isEmpty()) {
            scrollPane.setVisible(false);
            welcomePane.setVisible(true);
        } else {
            scrollPane.setVisible(true);
            welcomePane.setVisible(false);

            int col = 0, row = 0;
            for (ProjectData project : projects) {
                projectGrid.add(createProjectCard(project), col, row);
                col++;
                if (col > 1) {
                    col = 0;
                    row++;
                }
            }
        }
    }

    private VBox createWelcomePane() {
        VBox pane = new VBox();
        pane.getStyleClass().add("welcome-pane");
        
        Label welcomeLabel = new Label("Welcome!");
        welcomeLabel.getStyleClass().add("welcome-title");
        
        Label subtitleLabel = new Label("Let's create something truly awesome.");
        subtitleLabel.getStyleClass().add("welcome-subtitle");
        
        Button startButton = new Button("Create Your First Project");
        startButton.getStyleClass().addAll("button-primary", "button-large");
        startButton.setStyle("-fx-padding: 12 24; -fx-font-size: 16px;");
        startButton.setOnAction(e -> showCreateProjectDialog());
        
        pane.getChildren().addAll(welcomeLabel, subtitleLabel, startButton);
        return pane;
    }

    private HBox createProjectCard(ProjectData project) {
        HBox card = new HBox(16);
        card.getStyleClass().add("project-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(Double.MAX_VALUE);

        // Thumbnail
        Region thumbnail = new Region();
        thumbnail.getStyleClass().add("project-thumbnail");
        thumbnail.setPrefSize(80, 80);
        thumbnail.setMinSize(80, 80);

        // Text block
        VBox textContainer = new VBox(3);
        HBox.setHgrow(textContainer, Priority.ALWAYS);

        Label titleLabel = new Label(project.getProjectTitle());
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        Label dateLabel = new Label(DateHelper.convertTimestampToDateTimeStringFormat(project.getProjectTimestamp()));
        dateLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.6;");

        String stats = DateHelper.convertTimestampToHHMMSSFormat(project.getProjectDuration()) + " • " + 
                       String.format("%.2f MB", project.getProjectSize() / (1024.0 * 1024.0));
        Label statsLabel = new Label(stats);
        statsLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.6;");

        textContainer.getChildren().addAll(titleLabel, dateLabel, statsLabel);
        textContainer.setAlignment(Pos.CENTER_LEFT);

        Button menuBtn = new Button();
        menuBtn.setGraphic(new FontIcon(MaterialDesignD.DOTS_HORIZONTAL));
        menuBtn.getStyleClass().addAll("button-transparent");

        card.getChildren().addAll(thumbnail, textContainer, menuBtn);
        return card;
    }

    private void showCreateProjectDialog() {
        TextInputDialog dialog = new TextInputDialog("My Awesome Clip");
        dialog.setTitle("New Project");
        dialog.setHeaderText("Create a new masterpiece");
        dialog.setContentText("Project Name:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            if (!name.trim().isEmpty()) {
                ProjectRepository.getInstance().createNewProject(name.trim());
            }
        });
    }
}
