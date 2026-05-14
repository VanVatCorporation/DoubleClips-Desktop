package com.vanvatcorporation.doubleclips.helper.android;

import javafx.scene.control.Alert;

public class AlertDialog {

    private String title;
    private String message;
    private AlertDialog() {}

    public void show() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null); // Optional: Android's setTitle is more like headerText or Title
        alert.setContentText(message);
        alert.show();
    }


    public static class Builder {
        private AlertDialog alert;
        public Builder() {
            alert = new AlertDialog();
        }
        public Builder setTitle(String title) {
            alert.title = title;
            return this;
        }
        public Builder setMessage(String message) {
            alert.message = message;
            return this;
        }
        public AlertDialog create() {
            return alert;
        }
    }
}
