package com.examhalls.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Semi-transparent overlay with a spinner and message; place it as the last child of a StackPane
 * so it covers the view and blocks clicks while a stored procedure runs. Calls nest: the overlay
 * stays up until every {@link #show} has been matched by a {@link #hide}.
 */
public class BusyOverlay extends StackPane {

    private final Label message = new Label();
    private int active;

    public BusyOverlay() {
        getStyleClass().add("busy-overlay");
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(48, 48);
        message.getStyleClass().add("busy-message");
        VBox box = new VBox(12, spinner, message);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("busy-box");
        box.setMaxSize(VBox.USE_PREF_SIZE, VBox.USE_PREF_SIZE);
        getChildren().add(box);
        setVisible(false);
    }

    public void show(String text) {
        active++;
        message.setText(text);
        setVisible(true);
    }

    public void hide() {
        active = Math.max(0, active - 1);
        setVisible(active > 0);
    }
}
