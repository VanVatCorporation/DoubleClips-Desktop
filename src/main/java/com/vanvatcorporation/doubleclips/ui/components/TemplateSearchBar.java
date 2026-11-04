package com.vanvatcorporation.doubleclips.ui.components;

import com.vanvatcorporation.doubleclips.i18n.I18n;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Templates tab's search bar. It is a button: clicking it opens the search window. Inside it the hot searches take
 * turns: each stays about {@link #HOLD_SECONDS} seconds, then flies up and out while the next one flies up in from below
 * (the same as the iOS app). With no hot searches it shows the plain "Search templates..." prompt.
 *
 * <p>The rotation only runs while the bar is on screen. Use on the JavaFX thread.
 */
public final class TemplateSearchBar extends HBox {

    public static final double HOLD_SECONDS = 3.0;
    private static final double FLY_MILLIS = 450;
    private static final double ROW_HEIGHT = 22;

    private final StackPane ticker = new StackPane();
    private final Timeline rotation;
    private final Consumer<String> onOpen;
    private final String emptyPrompt;

    private List<String> terms = new ArrayList<>();
    private int index = 0;
    private Label shown;
    private Animation flying;

    /** @param onOpen gets the hot search showing in the bar at the click ("" when there is none): the search window's prompt */
    public TemplateSearchBar(Consumer<String> onOpen) {
        this(I18n.t("Search templates..."), onOpen);
    }

    /** @param emptyPrompt what the bar says while there are no hot searches ("Search assets..." in the Assets tab) */
    public TemplateSearchBar(String emptyPrompt, Consumer<String> onOpen) {
        this.emptyPrompt = emptyPrompt;
        this.onOpen = onOpen;
        setSpacing(12);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(12, 18, 12, 18));
        setCursor(Cursor.HAND);
        setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 25; -fx-border-color: -color-border-subtle; -fx-border-radius: 25;");

        FontIcon icon = new FontIcon(MaterialDesignM.MAGNIFY);

        ticker.setAlignment(Pos.CENTER_LEFT);
        ticker.setMinHeight(ROW_HEIGHT);
        ticker.setPrefHeight(ROW_HEIGHT);
        ticker.setMaxHeight(ROW_HEIGHT);
        // the text only shows inside the bar: what flies out above or comes in from below is cut off
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(ticker.widthProperty());
        clip.setHeight(ROW_HEIGHT);
        ticker.setClip(clip);
        HBox.setHgrow(ticker, Priority.ALWAYS);

        Label search = new Label(I18n.t("Search"));
        search.setStyle("-fx-font-weight: bold; -fx-text-fill: -color-accent-fg;");

        getChildren().addAll(icon, ticker, search);
        setOnMouseClicked(e -> onOpen.accept(currentTerm()));

        rotation = new Timeline(new KeyFrame(Duration.seconds(HOLD_SECONDS), e -> next()));
        rotation.setCycleCount(Animation.INDEFINITE);
        // only while on screen: a bar that left the window must not keep a timer alive
        sceneProperty().addListener((o, was, scene) -> updateRotation());

        showStatic();
    }

    /** The hot search showing now, or "" when there are none. */
    public String currentTerm() {
        return terms.isEmpty() ? "" : terms.get(index % terms.size());
    }

    /** The hot searches to rotate through (empty = the plain prompt). The same list again changes nothing. */
    public void setTerms(List<String> newTerms) {
        List<String> next = newTerms == null ? new ArrayList<>() : new ArrayList<>(newTerms);
        if (next.equals(terms)) return;
        terms = next;
        index = 0;
        stopFlying();
        showStatic();
        updateRotation();
    }

    private void updateRotation() {
        if (getScene() != null && terms.size() > 1) {
            rotation.playFromStart();
        } else {
            rotation.stop();
        }
    }

    private Label row(String text, boolean muted) {
        Label l = new Label(text);
        l.setStyle(muted ? "-fx-text-fill: -color-fg-muted;" : "-fx-text-fill: -color-fg-muted; -fx-font-size: 14px;");
        l.setMaxWidth(Double.MAX_VALUE);
        StackPane.setAlignment(l, Pos.CENTER_LEFT);
        return l;
    }

    /** The current term (or the prompt) with no movement. */
    private void showStatic() {
        ticker.getChildren().clear();
        shown = row(terms.isEmpty() ? emptyPrompt : terms.get(index % terms.size()), terms.isEmpty());
        ticker.getChildren().add(shown);
    }

    private void stopFlying() {
        if (flying != null) {
            flying.stop();
            flying = null;
        }
    }

    /** Flies the shown term up and out and the next one up in from below. */
    private void next() {
        if (terms.size() < 2 || shown == null) return;
        stopFlying();
        ticker.getChildren().setAll(shown); // whatever an interrupted flight left behind goes
        shown.setTranslateY(0);
        shown.setOpacity(1);
        index = (index + 1) % terms.size();

        final Label leaving = shown;
        final Label arriving = row(terms.get(index), false);
        arriving.setTranslateY(ROW_HEIGHT);
        arriving.setOpacity(0);
        ticker.getChildren().add(arriving);

        ParallelTransition fly = new ParallelTransition(
                move(leaving, 0, -ROW_HEIGHT), fade(leaving, 1, 0),
                move(arriving, ROW_HEIGHT, 0), fade(arriving, 0, 1));
        fly.setOnFinished(e -> {
            ticker.getChildren().remove(leaving);
            shown = arriving;
            flying = null;
        });
        flying = fly;
        fly.play();
    }

    private static TranslateTransition move(Label label, double from, double to) {
        TranslateTransition t = new TranslateTransition(Duration.millis(FLY_MILLIS), label);
        t.setFromY(from);
        t.setToY(to);
        t.setInterpolator(Interpolator.EASE_BOTH);
        return t;
    }

    private static FadeTransition fade(Label label, double from, double to) {
        FadeTransition t = new FadeTransition(Duration.millis(FLY_MILLIS), label);
        t.setFromValue(from);
        t.setToValue(to);
        t.setInterpolator(Interpolator.EASE_BOTH);
        return t;
    }
}
