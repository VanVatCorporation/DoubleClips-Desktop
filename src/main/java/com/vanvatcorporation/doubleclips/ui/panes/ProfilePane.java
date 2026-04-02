package com.vanvatcorporation.doubleclips.ui.panes;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignH;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

public class ProfilePane extends VBox {

    public ProfilePane() {
        setSpacing(30);
        setPadding(new Insets(40));
        setAlignment(Pos.TOP_CENTER);
        getStyleClass().add("content-pane");

        // 1. Profile Header
        VBox header = new VBox(15);
        header.setAlignment(Pos.CENTER);

        Circle avatar = new Circle(60);
        avatar.setFill(Color.web("#333"));
        avatar.setStroke(Color.web("-color-border-subtle"));
        avatar.setStrokeWidth(2);

        Label nameLabel = new Label("Nguyen Viet");
        nameLabel.setStyle("-fx-font-size: 28px; -fx-font-weight: bold;");

        header.getChildren().addAll(avatar, nameLabel);

        // 2. Settings Group
        VBox settingsList = new VBox(0);
        settingsList.getStyleClass().add("settings-group");
        settingsList.setMaxWidth(500);

        settingsList.getChildren().addAll(
                createSettingButton("Settings", new FontIcon(MaterialDesignC.COG)),
                new Separator(),
                createSettingButton("Statistics", new FontIcon(MaterialDesignP.POLL)),
                new Separator(),
                createSettingButton("Saved Templates", new FontIcon(MaterialDesignH.HEART)),
                new Separator(),
                createLogoutButton()
        );

        getChildren().addAll(header, settingsList);
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
        return btn;
    }
}