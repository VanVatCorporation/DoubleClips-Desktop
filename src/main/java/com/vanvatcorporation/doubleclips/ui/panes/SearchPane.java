package com.vanvatcorporation.doubleclips.ui.panes;

import com.vanvatcorporation.doubleclips.i18n.I18n;
import javafx.scene.layout.VBox;
import javafx.scene.control.Label;
public class SearchPane extends VBox { public SearchPane() { getChildren().add(new Label(I18n.t("Search Pane"))); } }